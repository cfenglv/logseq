(ns logseq.db-sync.worker.handler.sync
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [logseq.db-sync.batch :as batch]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.common :as common]
            [logseq.db.common.normalize :as db-normalize]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.snapshot :as snapshot]
            [logseq.db-sync.snapshot-integrity :as snapshot-integrity]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.tx-sanitize :as tx-sanitize]
            [logseq.db-sync.worker.http :as http]
            [logseq.db-sync.worker.handler.semantic :as semantic-handler]
            [logseq.db-sync.worker.presence :as presence]
            [logseq.db-sync.worker.routes.semantic :as semantic-routes]
            [logseq.db-sync.worker.routes.sync :as sync-routes]
            [logseq.db-sync.worker.ws :as ws]
            [logseq.db.frontend.kv-entity :as kv-entity]
            [logseq.outliner.core :as outliner-core]
            [promesa.core :as p]))

(def ^:private snapshot-download-batch-size 256)
(def ^:private snapshot-download-frame-max-bytes (* 1024 1024))
(def ^:private snapshot-download-retention-ms (* 10 60 1000))
(def ^:private snapshot-download-max-active 2)
(def ^:private snapshot-download-max-leases-per-export 64)
(def ^:private legacy-snapshot-download-queue-meta-key
  :legacy-snapshot-download-queue)
;; (def ^:private snapshot-cache-control "private, max-age=300")
(def ^:private snapshot-content-type "application/transit+json")
(def ^:private snapshot-content-encoding "gzip")
(def ^:private snapshot-uploading-meta-key :snapshot-uploading?)
(def ^:private snapshot-upload-id-meta-key :snapshot-upload-id)
(def ^:private snapshot-upload-status-meta-key :snapshot-upload-status)
(def ^:private snapshot-upload-started-at-meta-key :snapshot-upload-started-at)
(def ^:private snapshot-upload-committed-checksum-meta-key
  :snapshot-upload-committed-checksum)
(def ^:private snapshot-upload-committed-row-count-meta-key
  :snapshot-upload-committed-row-count)
(def ^:private snapshot-upload-status-active "active")
(def ^:private snapshot-upload-status-committed "committed")
(def ^:private snapshot-upload-status-legacy-active "legacy-active")
(def ^:private legacy-snapshot-upload-id "legacy-v1")
(def ^:private snapshot-staging-table "snapshot_kvs_staging")
(def ^:private snapshot-upload-id-max-length 128)
(def ^:private large-tx-min-items 500)
(def ^:private large-tx-max-chunk-items 500)
;; 10m
;; (def ^:private snapshot-multipart-part-size (* 10 1024 1024))

(def ^:private graph-created-at-ident
  snapshot-integrity/graph-created-at-ident)

(def ^:private registered-system-kv-idents
  (set (keys kv-entity/kv-entities)))

(defn- graph-created-at-valid?
  [db]
  (snapshot-integrity/graph-created-at-valid? db))

(defn- system-kv-integrity-error
  [phase]
  (ex-info "required graph creation metadata is missing"
           {:type :db-sync/system-kv-integrity
            :phase phase
            :missing-system-kv-idents [graph-created-at-ident]}))

(defn- require-graph-created-at!
  [db phase]
  (when-not (graph-created-at-valid? db)
    (throw (system-kv-integrity-error phase)))
  db)

(defn- open-validated-snapshot-conn!
  [sql]
  (let [conn (storage/open-conn sql)]
    (require-graph-created-at! @conn :snapshot-upload)
    conn))

(defn parse-int [value]
  (when (some? value)
    (let [n (js/parseInt value 10)]
      (when-not (js/isNaN n)
        n))))

(defn- valid-snapshot-upload-id?
  [value]
  (and (string? value)
       (seq value)
       (<= (count value) snapshot-upload-id-max-length)))

(defn- valid-legacy-snapshot-upload-id?
  [value]
  (let [prefix (str legacy-snapshot-upload-id ":")]
    (and (valid-snapshot-upload-id? value)
         (string/starts-with? value prefix)
         (> (count value) (count prefix)))))

(defn- parse-nonnegative-safe-integer
  [value]
  (when (and (string? value)
             (boolean (re-matches #"(?:0|[1-9][0-9]*)" value)))
    (let [n (js/Number value)]
      (when (and (js/Number.isSafeInteger n)
                 (not (neg? n)))
        n))))

(defn- snapshot-upload-state
  "Decode the complete durable upload receipt. No individual sync_meta marker
  is authoritative: only a complete state shape may authorize live writes or
  resume an upload. Unknown and partial shapes remain fail-closed."
  [sql]
  (let [raw {:upload-id
             (storage/get-meta sql snapshot-upload-id-meta-key)
             :status
             (storage/get-meta sql snapshot-upload-status-meta-key)
             :uploading
             (storage/get-meta sql snapshot-uploading-meta-key)
             :started-at
             (storage/get-meta sql snapshot-upload-started-at-meta-key)
             :committed-checksum
             (storage/get-meta
              sql snapshot-upload-committed-checksum-meta-key)
             :committed-row-count
             (storage/get-meta
              sql snapshot-upload-committed-row-count-meta-key)}
        {:keys [upload-id status uploading started-at
                committed-checksum committed-row-count]} raw
        parsed-started-at (parse-nonnegative-safe-integer started-at)
        parsed-row-count (parse-nonnegative-safe-integer committed-row-count)
        no-committed-fields? (and (nil? committed-checksum)
                                  (nil? committed-row-count))]
    (cond
      (every? nil? (vals raw))
      {:kind :idle :raw raw}

      (and (= snapshot-upload-status-active status)
           (valid-snapshot-upload-id? upload-id)
           (= "true" uploading)
           (some? parsed-started-at)
           no-committed-fields?)
      {:kind :active
       :upload-id upload-id
       :status status
       :started-at parsed-started-at
       :raw raw}

      (and (= snapshot-upload-status-legacy-active status)
           (valid-legacy-snapshot-upload-id? upload-id)
           (= "true" uploading)
           (some? parsed-started-at)
           no-committed-fields?)
      {:kind :legacy-active
       :upload-id upload-id
       :status status
       :started-at parsed-started-at
       :raw raw}

      (and (= snapshot-upload-status-committed status)
           (valid-snapshot-upload-id? upload-id)
           (= "false" uploading)
           (some? parsed-started-at)
           (sync-checksum/valid-checksum? committed-checksum)
           (some? parsed-row-count))
      {:kind :committed
       :upload-id upload-id
       :status status
       :started-at parsed-started-at
       :checksum committed-checksum
       :row-count parsed-row-count
       :raw raw}

      :else
      {:kind :invalid
       :upload-id upload-id
       :status status
       :raw raw})))

(defn- snapshot-upload-state-error
  [state]
  (ex-info "snapshot upload state is inconsistent"
           {:type :db-sync/snapshot-upload-state-inconsistent
            :kind (:kind state)
            :status (:status state)
            :upload-id-present? (boolean (seq (:upload-id state)))}))

(defn- require-valid-snapshot-upload-state!
  [sql]
  (let [state (snapshot-upload-state sql)]
    (when (= :invalid (:kind state))
      (throw (snapshot-upload-state-error state)))
    state))

(defn- ensure-schema! [^js self]
  (when-not (true? (.-schema-ready self))
    (set! (.-schema-ready self) false)
    (try
      (storage/init-schema! (.-sql self))
      (catch :default e
        ;; Some runtimes reject repeated DDL after a schema is initialized.
        ;; Accept that only after proving every required table, column and
        ;; idempotency index exists; a partial migration must remain unready.
        (when-not (storage/schema-ready? (.-sql self))
          (throw e))))
    (set! (.-schema-ready self) true)))

(defn- ensure-conn! [^js self]
  (ensure-schema! self)
  (when-not (.-conn self)
    (set! (.-conn self)
          (storage/open-conn (.-sql self)))))

(defn- <trusted-graph-created-at
  "Return graph creation time only from the authoritative D1 graph row, and
  only when the snapshot itself is missing that registered system KV."
  [^js self graph-id conn]
  (if (graph-created-at-valid? @conn)
    (p/resolved nil)
    (if-let [db (some-> self .-env (aget "DB"))]
      (index/<graph-created-at db graph-id)
      (p/resolved nil))))

(defn t-now [^js self]
  (ensure-schema! self)
  (storage/get-t (.-sql self)))

(defn- verify-current-checksums!
  [^js self]
  (ensure-schema! self)
  (let [sql (.-sql self)
        current-t (storage/get-t sql)]
    (when-not (storage/checksum-metadata-verified? sql current-t)
      (ensure-conn! self)
      (let [conn (.-conn self)
            stored-checksum (storage/get-checksum sql)
            stored-server-checksum (storage/get-server-checksum sql)
            stored-server-checksum-t (storage/get-server-checksum-t sql)
            checksum (when (or (string? stored-checksum)
                               (pos? current-t))
                       (sync-checksum/recompute-checksum @conn))
            server-checksum (sync-checksum/recompute-server-checksum @conn)
            checksum-drift? (and (string? stored-checksum)
                                 (not= stored-checksum checksum))
            server-checksum-drift?
            (and (= current-t stored-server-checksum-t)
                 (string? stored-server-checksum)
                 (not= stored-server-checksum server-checksum))]
        (storage/with-sql-transaction!
         sql
         (fn []
           (when (string? checksum)
             (storage/set-checksum! sql checksum))
           (storage/set-server-checksum! sql server-checksum current-t)
           (storage/mark-checksum-metadata-verified! sql current-t)))
        (when (or checksum-drift? server-checksum-drift?)
          (log/warn :db-sync/checksum-metadata-repaired
                    {:t current-t
                     :legacy-drift? checksum-drift?
                     :server-v2-drift? server-checksum-drift?})))))
  {:checksum (storage/get-checksum (.-sql self))
   :server-checksum (storage/get-server-checksum (.-sql self))})

(defn- seal-validated-snapshot-db!
  "Publish checksum metadata only from a one-shot descriptor recomputed from
  the validated DataScript DB. Generic sync_meta setters cannot mint it."
  [sql db t generation]
  (let [descriptor
        (storage/verified-snapshot-integrity-descriptor
         sql db t generation)]
    (storage/seal-verified-snapshot-integrity! sql descriptor)))

(defn current-checksum [^js self]
  (:checksum (verify-current-checksums! self)))

(defn current-server-checksum [^js self]
  (:server-checksum (verify-current-checksums! self)))

(defn checksum-response-fields [^js self]
  (let [legacy-checksum (current-checksum self)
        server-checksum (current-server-checksum self)]
    (cond-> {}
      (string? legacy-checksum) (assoc :checksum legacy-checksum)
      (string? server-checksum)
      (assoc :checksum-version sync-checksum/server-checksum-version
             :server-checksum server-checksum))))

(defn snapshot-upload-finished? [^js self]
  (ensure-schema! self)
  (contains? #{:idle :committed}
             (:kind (snapshot-upload-state (.-sql self)))))

(declare <set-graph-ready-for-use! <ensure-live-integrity!)

(defn- persisted-live-snapshot?
  "Distinguish a recoverable legacy/live graph from a brand-new empty Durable
  Object before repairing a stale D1 ready projection. Reading the first EAVT
  datom is bounded and does not authorize the graph; the integrity bootstrap
  below still performs the complete validation before any request continues."
  [^js self]
  (ensure-conn! self)
  (or (map? (storage/snapshot-integrity-attestation (.-sql self)))
      (pos? (storage/get-t (.-sql self)))
      (boolean (first (d/datoms @(.-conn self) :eavt)))))

(defn <ready-for-sync?
  [^js self graph-id]
  (if-not (snapshot-upload-finished? self)
    (p/resolved false)
    (if-let [db (some-> self .-env (aget "DB"))]
      (p/let [graph-ready-for-use? (index/<graph-ready-for-use? db graph-id)]
        (cond
          (not= false graph-ready-for-use?)
          (p/let [_ (<ensure-live-integrity! self graph-id)]
            true)

          :else
          (if-not (persisted-live-snapshot? self)
            false
            (p/let [_ (<ensure-live-integrity! self graph-id)
                    _ (ensure-conn! self)
                    authoritative-created-at
                    (index/<graph-created-at db graph-id)]
              (if (and (snapshot-upload-finished? self)
                       (storage/snapshot-integrity-attested? (.-sql self))
                       (graph-created-at-valid? @(.-conn self))
                       (integer? authoritative-created-at)
                       (not (neg? authoritative-created-at)))
                (p/let [_ (<set-graph-ready-for-use!
                          self graph-id true)]
                  true)
                false)))))
      (p/let [_ (<ensure-live-integrity! self graph-id)]
        true))))

(defn- <set-graph-ready-for-use!
  [^js self graph-id graph-ready-for-use?]
  (if-let [db (some-> self .-env (aget "DB"))]
    (p/let [result (index/<graph-ready-for-use-set! db graph-id graph-ready-for-use?)
            meta (some-> result (aget "meta"))
            rows-affected (or (some-> meta (aget "changes"))
                              (some-> meta (aget "rows_written"))
                              (some-> result (aget "changes"))
                              (some-> result (aget "rows_written")))]
      (when (or (nil? result)
                (false? (some-> result (aget "success"))))
        (throw (ex-info "failed to persist graph_ready_for_use"
                        {:type :db-sync/graph-ready-for-use-set-failed
                         :graph-id graph-id
                         :graph-ready-for-use? graph-ready-for-use?
                         :result result})))
      (when (and (number? rows-affected)
                 (<= rows-affected 0))
        (throw (ex-info "graph_ready_for_use update affected no rows"
                        {:type :db-sync/graph-ready-for-use-set-no-rows
                         :graph-id graph-id
                         :graph-ready-for-use? graph-ready-for-use?
                         :rows-affected rows-affected
                         :result result})))
      result)
    (p/rejected (ex-info "missing DB binding for graph_ready_for_use update"
                         {:type :db-sync/missing-db-binding
                          :graph-id graph-id
                          :graph-ready-for-use? graph-ready-for-use?}))))

(defn- import-snapshot-rows!
  [sql table rows]
  (when (seq rows)
    (doseq [batch (batch/rows->insert-batches table rows nil)]
      (let [sql-str (:sql batch)
            args (:args batch)]
        (apply common/sql-exec sql sql-str args)))))

(defn- reset-import!
  [sql]
  (common/sql-exec sql "delete from kvs")
  (common/sql-exec sql "delete from tx_log")
  (common/sql-exec sql "delete from applied_client_txs")
  (common/sql-exec sql "delete from client_tx_upload_chunks")
  (common/sql-exec sql "delete from client_tx_uploads")
  (common/sql-exec sql "delete from sync_meta")
  (storage/clear-snapshot-integrity-attestation! sql)
  (storage/set-t! sql 0))

(defn- graph-id-from-sync-path
  [^js url]
  (let [path (.-pathname url)
        prefix "/sync/"]
    (when (string/starts-with? path prefix)
      (let [rest-path (subs path (count prefix))
            slash-idx (or (string/index-of rest-path "/") -1)
            graph-id (if (neg? slash-idx)
                       rest-path
                       (subs rest-path 0 slash-idx))]
        (when (seq graph-id)
          graph-id)))))

(defn graph-id-from-request [request]
  (let [header-id (.get (.-headers request) "x-graph-id")
        url (js/URL. (.-url request))
        param-id (.get (.-searchParams url) "graph-id")
        graph-id (or header-id param-id (graph-id-from-sync-path url))]
    (when (seq graph-id)
      graph-id)))

;; (defn- snapshot-key [graph-id snapshot-id]
;;   (str graph-id "/" snapshot-id ".snapshot"))

;; (defn- snapshot-url [request graph-id snapshot-id]
;;   (let [url (js/URL. (.-url request))]
;;     (str (.-origin url) "/assets/" graph-id "/" snapshot-id ".snapshot")))

(defn- snapshot-stream-url [request graph-id download-id frozen?]
  (let [url (js/URL. (.-url request))]
    (str (.-origin url)
         "/sync/"
         graph-id
         (if frozen?
           "/snapshot/stream-v2?download-id="
           "/snapshot/stream")
         (when frozen?
           (js/encodeURIComponent download-id)))))

(defn- maybe-decompress-stream [stream encoding]
  (if (and (= encoding snapshot-content-encoding) (exists? js/DecompressionStream))
    (.pipeThrough stream (js/DecompressionStream. "gzip"))
    stream))

(defn- maybe-compress-stream [stream]
  (.pipeThrough stream (js/CompressionStream. snapshot-content-encoding)))

(defn- snapshot-stream-gzip-enabled?
  [^js self]
  (let [v (some-> self .-env (aget "DB_SYNC_SNAPSHOT_STREAM_GZIP"))]
    (cond
      (nil? v) true
      (false? v) false
      (string? v) (not (contains? #{"false" "0" "off" "no"}
                                   (string/lower-case v)))
      :else (boolean v))))

;; (defn- <buffer-stream
;;   [stream]
;;   (p/let [resp (js/Response. stream)
;;           buf (.arrayBuffer resp)]
;;     buf))

;; (defn- ->uint8 [data]
;;   (cond
;;     (instance? js/Uint8Array data) data
;;     (instance? js/ArrayBuffer data) (js/Uint8Array. data)
;;     :else (js/Uint8Array. data)))

;; (defn- concat-uint8 [^js a ^js b]
;;   (cond
;;     (nil? a) b
;;     (nil? b) a
;;     :else
;;     (let [out (js/Uint8Array. (+ (.-byteLength a) (.-byteLength b)))]
;;       (.set out a 0)
;;       (.set out b (.-byteLength a))
;;       out)))

(defn- frame-bytes
  [^js data]
  (let [len (.-byteLength data)
        out (js/Uint8Array. (+ 4 len))
        view (js/DataView. (.-buffer out))]
    (.setUint32 view 0 len false)
    (.set out data 4)
    out))

(defn- fetch-snapshot-kvs-rows
  [sql last-addr limit]
  (let [rows (common/get-sql-rows
              (common/sql-exec sql
                               "select addr, content, addresses from kvs where addr > ? order by addr asc limit ?"
                               last-addr
                               limit))]
    (mapv (fn [row]
            [(aget row "addr")
             (aget row "content")
             (aget row "addresses")])
          rows)))

(defn- fetch-snapshot-export-rows
  [sql download-id last-addr limit]
  (let [rows (common/get-sql-rows
              (common/sql-exec
               sql
               (str "select addr, content, addresses from snapshot_kvs_exports "
                    "where download_id = ? and addr > ? order by addr asc limit ?")
               download-id
               last-addr
               limit))]
    (mapv (fn [row]
            [(aget row "addr")
             (aget row "content")
             (aget row "addresses")])
          rows)))

(defn- snapshot-row-count
  [sql]
  (if-let [row (first (common/get-sql-rows
                       (common/sql-exec sql "select count(*) as row_count from kvs")))]
    (or (aget row "row_count") 0)
    0))

(defn- snapshot-download-generation-key!
  [sql]
  (let [{:keys [version generation t root-digest kvs-generation
                checksum-metadata-generation checksum server-checksum
                server-checksum-t tx-log-floor]}
        (storage/snapshot-integrity-attestation sql)]
    (when-not (and (storage/snapshot-integrity-attested? sql)
                   (string? version)
                   (string? generation)
                   (number? t)
                   (string? root-digest)
                   (number? kvs-generation)
                   (number? checksum-metadata-generation)
                   (number? tx-log-floor))
      (throw (ex-info "snapshot generation is not integrity-attested"
                      {:type :db-sync/snapshot-write-integrity-invalid
                       :t (storage/get-t sql)})))
    ;; A generation UUID alone intentionally survives normal transactions.
    ;; Bind v1 export reuse to the complete sealed basis instead.
    (common/write-transit
     [version generation t root-digest kvs-generation
      checksum-metadata-generation checksum server-checksum
      server-checksum-t tx-log-floor])))

(defn- delete-snapshot-download-rows!
  [sql download-id]
  (common/sql-exec sql
                   "delete from snapshot_kvs_exports where download_id = ?"
                   download-id)
  (common/sql-exec
   sql
   "delete from snapshot_download_generations where download_id = ?"
   download-id)
  (common/sql-exec sql
                   "delete from snapshot_downloads where download_id = ?"
                   download-id))

(defn- delete-snapshot-download!
  [sql download-id]
  (storage/with-sql-transaction!
   sql
   #(delete-snapshot-download-rows! sql download-id)))

(defn- release-snapshot-download!
  [sql download-id]
  (storage/with-sql-transaction!
   sql
   (fn []
     (let [lease-count
           (some->
            (common/sql-exec
             sql
             (str "select lease_count from snapshot_download_generations "
                  "where download_id = ?")
             download-id)
            common/get-sql-rows
            first
            (aget "lease_count"))]
       (if (and (number? lease-count) (> lease-count 1))
         (common/sql-exec
          sql
          (str "update snapshot_download_generations "
               "set lease_count = lease_count - 1 where download_id = ?")
          download-id)
         (delete-snapshot-download-rows! sql download-id))))))

(defn- legacy-snapshot-download-queue
  [sql]
  (when-let [value (storage/get-meta
                    sql legacy-snapshot-download-queue-meta-key)]
    (try
      (let [queue (common/read-transit value)]
        (if (and (vector? queue) (every? string? queue))
          queue
          (throw (ex-info "legacy snapshot download queue is malformed"
                          {:type
                           :db-sync/snapshot-download-queue-inconsistent}))))
      (catch :default error
        (if (= :db-sync/snapshot-download-queue-inconsistent
               (:type (ex-data error)))
          (throw error)
          (throw
           (ex-info "legacy snapshot download queue is malformed"
                    {:type :db-sync/snapshot-download-queue-inconsistent}
                    error)))))))

(defn- set-legacy-snapshot-download-queue!
  [sql queue]
  (let [queue (vec queue)]
    (if (seq queue)
      (storage/set-meta!
       sql legacy-snapshot-download-queue-meta-key
       (common/write-transit queue))
      (storage/delete-meta!
       sql legacy-snapshot-download-queue-meta-key))))

(defn- persisted-snapshot-download-id?
  [sql download-id]
  (boolean
   (first
    (common/get-sql-rows
     (common/sql-exec
      sql
      "select 1 as present from snapshot_downloads where download_id = ?"
      download-id)))))

(defn- active-snapshot-download-counts
  [^js self]
  (or (aget self "activeSnapshotDownloadCounts")
      (let [counts (js/Map.)]
        (aset self "activeSnapshotDownloadCounts" counts)
        counts)))

(defn- retain-active-snapshot-download!
  [^js self download-id]
  (when (seq download-id)
    (let [counts (active-snapshot-download-counts self)]
      (.set counts download-id
            (inc (or (.get counts download-id) 0))))))

(defn- release-active-snapshot-download!
  [^js self download-id]
  (when-let [counts (aget self "activeSnapshotDownloadCounts")]
    (let [count (or (.get counts download-id) 0)]
      (if (> count 1)
        (.set counts download-id (dec count))
        (.delete counts download-id)))))

(defn- active-snapshot-download?
  [^js self download-id]
  (boolean
   (when-let [counts (aget self "activeSnapshotDownloadCounts")]
     (.has counts download-id))))

(defn- cleanup-expired-snapshot-downloads!
  [^js self now]
  (let [sql (.-sql self)
        expires-before (- now snapshot-download-retention-ms)]
    (storage/with-sql-transaction!
     sql
     (fn []
       (doseq [row (common/get-sql-rows
                    (common/sql-exec
                     sql
                     (str "select download_id from snapshot_downloads "
                          "where created_at < ?")
                     expires-before))
               :let [download-id (aget row "download_id")]]
         (when-not (active-snapshot-download? self download-id)
           (delete-snapshot-download-rows! sql download-id)))
       (set-legacy-snapshot-download-queue!
        sql
        (filterv #(persisted-snapshot-download-id? sql %)
                 (legacy-snapshot-download-queue sql)))))))

(defn- active-snapshot-download-count
  [sql]
  (or (some->
       (common/sql-exec
        sql
        "select count(*) as active_count from snapshot_downloads")
       common/get-sql-rows
       first
       (aget "active_count"))
      0))

(defn- outstanding-legacy-snapshot-download?
  [sql]
  (boolean
   (first
    (common/get-sql-rows
     (common/sql-exec
      sql
      (str "select 1 as present from snapshot_downloads d "
           "join snapshot_download_generations g "
           "on g.download_id = d.download_id "
           "where g.legacy = 1 and g.lease_count > 0 limit 1"))))))

(defn- reusable-legacy-snapshot-download
  [sql generation-key]
  (first
   (common/get-sql-rows
    (common/sql-exec
     sql
     (str "select d.download_id, d.t, d.checksum, d.row_count, "
          "g.lease_count, g.frozen "
          "from snapshot_downloads d "
          "join snapshot_download_generations g "
          "on g.download_id = d.download_id "
          "where g.legacy = 1 and g.generation_key = ? "
          "order by d.created_at desc limit 1")
     generation-key))))

(defn- legacy-pre-system-kv-snapshot-basis
  [sql]
  (let [t (storage/get-t sql)
        root-digest (storage/snapshot-storage-root-digest sql)
        kvs-generation (storage/live-kvs-mutation-generation sql)
        checksum-generation
        (storage/snapshot-checksum-mutation-generation sql)
        tx-log-floor (storage/tx-log-floor sql)]
    (when-not (and (number? t)
                   (string? root-digest)
                   (number? kvs-generation)
                   (number? checksum-generation)
                   (number? tx-log-floor))
      (throw
       (ex-info "legacy snapshot basis is not internally consistent"
                {:type (if (number? tx-log-floor)
                         :db-sync/snapshot-write-integrity-invalid
                         :db-sync/tx-log-integrity)
                 :t t})))
    {:t t
     :root-digest root-digest
     :kvs-generation kvs-generation
     :checksum-generation checksum-generation
     :checksum (storage/get-checksum sql)
     :server-checksum (storage/get-server-checksum sql)
     :server-checksum-t (storage/get-server-checksum-t sql)
     :tx-log-floor tx-log-floor
     :snapshot-uploading
     (storage/get-meta sql snapshot-uploading-meta-key)
     :snapshot-upload-id
     (storage/get-meta sql snapshot-upload-id-meta-key)
     :snapshot-upload-status
     (storage/get-meta sql snapshot-upload-status-meta-key)
     :snapshot-upload-started-at
     (storage/get-meta sql snapshot-upload-started-at-meta-key)}))

(defn- create-legacy-pre-system-kv-snapshot-download!
  [^js self expected-basis]
  (let [sql (.-sql self)
        download-id (str (random-uuid))
        now (js/Date.now)
        generation-key
        (common/write-transit
         [:legacy-v1-pre-system-kv
          (:t expected-basis)
          (:root-digest expected-basis)
          (:kvs-generation expected-basis)
          (:checksum-generation expected-basis)
          (:checksum expected-basis)
          (:server-checksum expected-basis)
          (:server-checksum-t expected-basis)
          (:tx-log-floor expected-basis)
          (:snapshot-uploading expected-basis)
          (:snapshot-upload-id expected-basis)
          (:snapshot-upload-status expected-basis)
          (:snapshot-upload-started-at expected-basis)])]
    (cleanup-expired-snapshot-downloads! self now)
    (storage/with-sql-transaction!
     sql
     (fn []
       (when-not (snapshot-upload-finished? self)
         (throw (ex-info "snapshot upload already in progress"
                         {:type :db-sync/snapshot-upload-in-progress})))
       (when-not (= expected-basis
                    (legacy-pre-system-kv-snapshot-basis sql))
         (throw (ex-info "legacy snapshot changed before freeze"
                         {:type :db-sync/snapshot-write-integrity-invalid
                          :t (storage/get-t sql)})))
       (let [active-count (active-snapshot-download-count sql)
             _ (when (>= active-count snapshot-download-max-active)
                 (throw (ex-info "too many active snapshot downloads"
                                 {:type :db-sync/snapshot-download-capacity
                                  :active-count active-count})))
             t (:t expected-basis)
             checksum (storage/get-checksum sql)
             row-count (snapshot-row-count sql)]
         (common/sql-exec
          sql
          (str "insert into snapshot_downloads "
               "(download_id, t, checksum, row_count, created_at) "
               "values (?, ?, ?, ?, ?)")
          download-id t checksum row-count now)
         (common/sql-exec
          sql
          (str "insert into snapshot_kvs_exports "
               "(download_id, addr, content, addresses) "
               "select ?, addr, content, addresses from kvs")
          download-id)
         (common/sql-exec
          sql
          (str "insert into snapshot_download_generations "
               "(download_id, generation_key, legacy, lease_count, frozen) "
               "values (?, ?, 1, 1, 1)")
          download-id generation-key)
         (when-not (= expected-basis
                      (legacy-pre-system-kv-snapshot-basis sql))
           (throw (ex-info "legacy snapshot changed during freeze"
                           {:type :db-sync/snapshot-write-integrity-invalid
                            :t (storage/get-t sql)})))
         (set-legacy-snapshot-download-queue!
          sql
          (conj (legacy-snapshot-download-queue sql) download-id))
         {:download-id download-id
          :t t
          :checksum checksum
          :row-count row-count})))))

(defn- create-snapshot-download!
  ([self]
   (create-snapshot-download! self false))
  ([^js self legacy?]
  ;; A frozen snapshot and its checksum must come from the same verified DB
  ;; state. This also repairs persisted same-cursor drift before the checksum is
  ;; copied into snapshot_downloads.
  (verify-current-checksums! self)
  (let [sql (.-sql self)
        download-id (str (random-uuid))
        now (js/Date.now)
        generation-key (snapshot-download-generation-key! sql)]
    (cleanup-expired-snapshot-downloads! self now)
    (storage/with-sql-transaction!
     sql
     (fn []
       (let [active-count (active-snapshot-download-count sql)
             reusable-row
             (when legacy?
               (reusable-legacy-snapshot-download sql generation-key))
             _ (when (and legacy?
                          (nil? reusable-row)
                          (seq (legacy-snapshot-download-queue sql)))
                 ;; The v1 stream URL contains no download identity. Do not
                 ;; enqueue a newer generation behind an older unclaimed URL;
                 ;; the server could not tell an early S2 from a late S1.
                 (throw (ex-info "legacy snapshot generation is still queued"
                                 {:type :db-sync/snapshot-download-capacity
                                  :active-count active-count})))
             reusable-lease-count
             (some-> reusable-row (aget "lease_count"))
             reusable-frozen (some-> reusable-row (aget "frozen"))
             _ (when (and reusable-row
                          (not (contains? #{0 1} reusable-frozen)))
                 (throw (ex-info "snapshot reservation state is invalid"
                                 {:type
                                  :db-sync/snapshot-write-integrity-invalid
                                  :download-id
                                  (aget reusable-row "download_id")})))
             _ (when (and reusable-row
                          (>= reusable-lease-count
                              snapshot-download-max-leases-per-export))
                 (throw (ex-info "too many snapshot download leases"
                                 {:type :db-sync/snapshot-download-capacity
                                  :active-count active-count
                                  :lease-count reusable-lease-count})))
             _ (when (and (nil? reusable-row)
                          (>= active-count snapshot-download-max-active))
                 (throw (ex-info "too many active snapshot downloads"
                                 {:type :db-sync/snapshot-download-capacity
                                  :active-count active-count})))
             t (if reusable-row
                 (aget reusable-row "t")
                 (storage/get-t sql))
             checksum (if reusable-row
                        (aget reusable-row "checksum")
                        (storage/get-checksum sql))
             row-count (if reusable-row
                         (aget reusable-row "row_count")
                         (snapshot-row-count sql))
             effective-download-id
             (if reusable-row
               (aget reusable-row "download_id")
               download-id)]
         (if reusable-row
           (do
             (common/sql-exec
              sql
              (str "update snapshot_download_generations "
                   "set lease_count = lease_count + 1 where download_id = ?")
              effective-download-id)
             (common/sql-exec
              sql
              "update snapshot_downloads set created_at = ? where download_id = ?"
              now effective-download-id))
           (do
             (common/sql-exec
              sql
              (str "insert into snapshot_downloads "
                   "(download_id, t, checksum, row_count, created_at) "
                   "values (?, ?, ?, ?, ?)")
              effective-download-id
              t
              checksum
              row-count
              now)
             (when-not legacy?
               (common/sql-exec
                sql
                (str "insert into snapshot_kvs_exports "
                     "(download_id, addr, content, addresses) "
                     "select ?, addr, content, addresses from kvs")
                effective-download-id))
             (common/sql-exec
              sql
              (str "insert into snapshot_download_generations "
                   "(download_id, generation_key, legacy, lease_count, frozen) "
                   "values (?, ?, ?, 1, ?)")
              effective-download-id generation-key (if legacy? 1 0)
              (if legacy? 0 1))))
         (when legacy?
           (set-legacy-snapshot-download-queue!
            sql
            (conj (legacy-snapshot-download-queue sql)
                  effective-download-id)))
         {:download-id effective-download-id
          :t t
          :checksum checksum
          :row-count row-count}))))))

(defn- snapshot-download-row
  [sql download-id]
  (first
   (common/get-sql-rows
    (common/sql-exec
     sql
     (str "select download_id, t, checksum, row_count, created_at "
          "from snapshot_downloads where download_id = ?")
     download-id))))

(defn- snapshot-download-generation-row
  [sql download-id]
  (first
   (common/get-sql-rows
    (common/sql-exec
     sql
     (str "select generation_key, legacy, lease_count, frozen "
          "from snapshot_download_generations where download_id = ?")
     download-id))))

(defn- snapshot-download-export-row-count
  [sql download-id]
  (or (some->
       (common/sql-exec
        sql
        (str "select count(*) as row_count from snapshot_kvs_exports "
             "where download_id = ?")
        download-id)
       common/get-sql-rows
       first
       (aget "row_count"))
      0))

(defn- claim-legacy-snapshot-download!
  [^js self]
  (cleanup-expired-snapshot-downloads! self (js/Date.now))
  (storage/with-sql-transaction!
   (.-sql self)
   (fn []
     (let [sql (.-sql self)
           queue (legacy-snapshot-download-queue sql)
           download-id (first queue)
           row (when download-id
                 (snapshot-download-row sql download-id))]
       (set-legacy-snapshot-download-queue! sql (rest queue))
       (when row
         {:download-id download-id
          :row row})))))

(defn- next-snapshot-frame
  "Encode only the next bounded frame. Keep the remaining row batches
  unencoded so a single stream pull cannot materialize every split payload in
  memory at once."
  [pending-batches]
  (loop [pending (vec pending-batches)]
    (when-let [batch (first pending)]
      (let [rest-pending (subvec pending 1)
            payload (snapshot/encode-rows batch)]
        (if (or (<= (.-byteLength payload) snapshot-download-frame-max-bytes)
                (= 1 (count batch)))
          {:payload payload
           :pending rest-pending}
          (let [middle (quot (count batch) 2)]
            (recur (into [(subvec batch 0 middle)
                          (subvec batch middle)]
                         rest-pending))))))))

(defn- live-reservation-kvs-generation
  [generation-key]
  (try
    (let [basis (common/read-transit generation-key)]
      (when (and (vector? basis)
                 (<= 5 (count basis))
                 (number? (nth basis 4 nil)))
        (nth basis 4)))
    (catch :default _
      nil)))

(defn- snapshot-download-source!
  [sql download-id expected-row-count validated-frozen?]
  (let [generation-row
        (snapshot-download-generation-row sql download-id)
        frozen (some-> generation-row (aget "frozen"))
        legacy (some-> generation-row (aget "legacy"))]
    (cond
      (= 1 frozen)
      (do
        (when-not @validated-frozen?
          (let [actual-row-count
                (snapshot-download-export-row-count sql download-id)]
            (when-not (= expected-row-count actual-row-count)
              (throw
               (ex-info "frozen snapshot export is incomplete"
                        {:type :db-sync/snapshot-write-integrity-invalid
                         :download-id download-id
                         :expected-row-count expected-row-count
                         :actual-row-count actual-row-count})))
            (vreset! validated-frozen? true)))
        :export)

      (and (= 0 frozen) (= 1 legacy))
      (let [export-row-count
            (snapshot-download-export-row-count sql download-id)
            expected-kvs-generation
            (live-reservation-kvs-generation
             (aget generation-row "generation_key"))
            actual-kvs-generation
            (storage/live-kvs-mutation-generation sql)]
        (when-not (and (zero? export-row-count)
                       (number? expected-kvs-generation)
                       (= expected-kvs-generation actual-kvs-generation))
          (throw
           (ex-info "live snapshot reservation changed without freezing"
                    {:type :db-sync/snapshot-write-integrity-invalid
                     :download-id download-id
                     :export-row-count export-row-count
                     :expected-kvs-generation expected-kvs-generation
                     :actual-kvs-generation actual-kvs-generation})))
        :live)

      :else
      (throw
       (ex-info "snapshot reservation is missing or invalid"
                {:type :db-sync/snapshot-write-integrity-invalid
                 :download-id download-id
                 :frozen frozen
                 :legacy legacy})))))

(defn- snapshot-export-stream
  ([self]
   (snapshot-export-stream self nil nil))
  ([^js self download-id expected-row-count]
  (ensure-schema! self)
  (let [sql (.-sql self)
        last-addr (volatile! -1)
        pending-batches (volatile! [])
        validated-frozen? (volatile! false)
        cleaned? (volatile! false)
        cleanup! (fn []
                   (when (and download-id (not @cleaned?))
                     (vreset! cleaned? true)
                     (try
                       (release-snapshot-download! sql download-id)
                       (finally
                         (release-active-snapshot-download!
                          self download-id)))))]
    (retain-active-snapshot-download! self download-id)
    (js/ReadableStream.
     (clj->js
      {:pull (fn [controller]
               (try
                 (let [pending
                       (if (seq @pending-batches)
                         @pending-batches
                         (let [source
                               (when download-id
                                 (snapshot-download-source!
                                  sql download-id expected-row-count
                                  validated-frozen?))
                               batch
                               (if (= :export source)
                                 (fetch-snapshot-export-rows
                                  sql download-id @last-addr
                                  snapshot-download-batch-size)
                                 (fetch-snapshot-kvs-rows
                                  sql @last-addr snapshot-download-batch-size))]
                           (when (seq batch)
                             (vreset! last-addr (first (peek batch))))
                           (if (seq batch) [batch] [])))]
                   (if-let [{:keys [payload pending]}
                            (next-snapshot-frame pending)]
                     (do
                       (vreset! pending-batches pending)
                       (.enqueue controller (frame-bytes payload)))
                     (do
                       (cleanup!)
                       (.close controller))))
                 (catch :default error
                   (cleanup!)
                   (throw error))))
       :cancel (fn []
                 (cleanup!))})))))

;; (defn- upload-multipart!
;;   [^js bucket key stream opts]
;;   (p/let [^js upload (.createMultipartUpload bucket key opts)]
;;     (let [reader (.getReader stream)]
;;       (-> (p/loop [buffer nil
;;                    part-number 1
;;                    parts []]
;;             (p/let [chunk (.read reader)]
;;               (if (.-done chunk)
;;                 (cond
;;                   (and buffer (pos? (.-byteLength buffer)))
;;                   (p/let [^js resp (.uploadPart upload part-number buffer)
;;                           parts (conj parts {:partNumber part-number :etag (.-etag resp)})]
;;                     (p/let [_ (.complete upload (clj->js parts))]
;;                       {:ok true}))

;;                   (seq parts)
;;                   (p/let [_ (.complete upload (clj->js parts))]
;;                     {:ok true})

;;                   :else
;;                   (p/let [_ (.abort upload)]
;;                     (.put bucket key (js/Uint8Array. 0) opts)))
;;                 (let [value (.-value chunk)
;;                       buffer (concat-uint8 buffer (->uint8 value))]
;;                   (if (>= (.-byteLength buffer) snapshot-multipart-part-size)
;;                     (let [part (.slice buffer 0 snapshot-multipart-part-size)
;;                           rest-parts (.slice buffer snapshot-multipart-part-size (.-byteLength buffer))]
;;                       (p/let [^js resp (.uploadPart upload part-number part)
;;                               parts (conj parts {:partNumber part-number :etag (.-etag resp)})]
;;                         (p/recur rest-parts (inc part-number) parts)))
;;                     (p/recur buffer part-number parts))))))
;;           (p/catch (fn [error]
;;                      (.abort upload)
;;                      (throw error)))))))

(defn- import-snapshot-stream-with!
  [stream reset? import-f]
  (let [reader (.getReader stream)
        reset-pending? (volatile! reset?)
        total-count (volatile! 0)]
    (p/let [buffer nil]
      (p/catch
       (p/loop [buffer buffer]
         (p/let [chunk (.read reader)]
           (if (.-done chunk)
             (let [rows (snapshot/finalize-framed-buffer buffer)
                   rows-count (count rows)
                   reset? (and @reset-pending? true)]
               (when (or reset? (seq rows))
                 (import-f rows reset?)
                 (vreset! reset-pending? false))
               (vswap! total-count + rows-count)
               @total-count)
             (let [value (.-value chunk)
                   {:keys [rows buffer]} (snapshot/parse-framed-chunk buffer value)
                   rows-count (count rows)
                   reset? (boolean (and @reset-pending? (seq rows)))]
               (when (seq rows)
                 (import-f rows reset?)
                 (vreset! reset-pending? false))
               (vswap! total-count + rows-count)
               (p/recur buffer)))))
       (fn [error]
         (throw error))))))

(declare import-snapshot!)
(defn- import-snapshot-stream!
  ([^js self stream reset?]
   (import-snapshot-stream-with!
    stream
    reset?
    (fn [rows reset?]
      (import-snapshot! self rows reset?))))
  ([^js _self stream reset? import-f]
   (import-snapshot-stream-with! stream reset? import-f)))

(def server-capabilities
  ["tx-upload-staged-v1"
   "canonical-structural-move-v1"])

(defn pull-response [^js self since]
  (let [sql (.-sql self)
        floor (storage/tx-log-floor sql)]
    (if (or (not (number? floor))
            (not (number? since))
            (< since floor))
      {:type "error" :message "snapshot required"}
      (merge {:type "pull/ok"
              :t (t-now self)
              :txs (storage/fetch-tx-since sql since)
              :capabilities server-capabilities}
             (checksum-response-fields self)))))

(defn- block-uuid-lookup-ref
  [entity-id]
  (when (and (sequential? entity-id)
             (= :block/uuid (first entity-id))
             (uuid? (second entity-id)))
    (second entity-id)))

(defn- missing-block-uuids-from-error
  [error]
  (loop [e error
         result []]
    (if e
      (let [missing-uuid (block-uuid-lookup-ref (:entity-id (ex-data e)))]
        (recur (ex-cause e)
               (cond-> result
                 missing-uuid (conj missing-uuid))))
      (-> result distinct vec))))

(def ^:private delete-outliner-ops
  #{:delete-blocks
    :delete-page})

(defn- request-context->tx-meta
  [{:keys [graph-id client-revision username]}]
  (cond-> {}
    graph-id (assoc :graph-id graph-id)
    client-revision (assoc :client-revision client-revision)
    username (assoc :username username)))

(defn- tempid?
  [value]
  (or (and (integer? value) (neg? value))
      (string? value)))

(defn- ref-attr?
  [db attr]
  (= :db.type/ref (:db/valueType (d/entity db attr))))

(defn- tx-item-tempids
  [db item]
  (cond
    (and (map? item) (tempid? (:db/id item)))
    #{(:db/id item)}

    (and (vector? item)
         (contains? #{:db/add :db/retract :db/cas :db.fn/cas} (first item))
         (<= 4 (count item)))
    (let [[_op entity attr value] item]
      (cond-> #{}
        (tempid? entity)
        (conj entity)
        (and (ref-attr? db attr)
             (tempid? value))
        (conj value)))

    (and (vector? item)
         (contains? #{:db/retractEntity :db.fn/retractEntity} (first item))
         (= 2 (count item))
         (tempid? (second item)))
    #{(second item)}

    :else
    #{}))

(defn- merge-ranges
  [ranges]
  (loop [remaining (sort-by first ranges)
         merged []]
    (if-let [[start end] (first remaining)]
      (if-let [[prev-start prev-end] (peek merged)]
        (if (<= start prev-end)
          (recur (next remaining)
                 (conj (pop merged) [prev-start (max prev-end end)]))
          (recur (next remaining)
                 (conj merged [start end])))
        (recur (next remaining) [[start end]]))
      merged)))

(defn- tempid-ranges
  [db tx-data]
  (let [ranges-by-tempid
        (reduce-kv
         (fn [acc idx item]
           (reduce (fn [acc* tempid]
                     (update acc* tempid
                             (fn [[start end]]
                               [(if (some? start) (min start idx) idx)
                                (if (some? end) (max end idx) idx)])))
                   acc
                   (tx-item-tempids db item)))
         {}
         tx-data)]
    (merge-ranges (vals ranges-by-tempid))))

(defn- tempid-range-by-start
  [db tx-data]
  (let [ranges (tempid-ranges db tx-data)
        range-by-start (into {} (map (fn [[start end]] [start end]) ranges))]
    range-by-start))

(defn- next-ordered-tx-group
  [tx-data range-by-start idx]
  (if-let [end (get range-by-start idx)]
    [(inc end) (subvec tx-data idx (inc end))]
    [(inc idx) [(nth tx-data idx)]]))

(defn- add-group-to-chunk
  [items group]
  (into items group))

(defn- reduce-ordered-tx-chunks
  [db f init tx-data]
  (let [tx-data (vec tx-data)
        item-count (count tx-data)
        range-by-start (tempid-range-by-start db tx-data)]
    (loop [idx 0
           chunk []
           acc init]
      (if (< idx item-count)
        (let [[next-idx group] (next-ordered-tx-group tx-data range-by-start idx)
              next-count (+ (count chunk) (count group))]
          (if (and (seq chunk)
                   (> next-count large-tx-max-chunk-items))
            (recur idx [] (f acc chunk))
            (recur next-idx (add-group-to-chunk chunk group) acc)))
        (cond-> acc
          (seq chunk) (f chunk))))))

(defn- large-tx?
  [tx-data]
  (>= (count tx-data) large-tx-min-items))

(defn- import-snapshot! [^js self rows reset?]
  (let [sql (.-sql self)]
    (ensure-schema! self)
    (when reset?
      (set! (.-conn self) nil)
      (reset-import! sql))
    (import-snapshot-rows! sql "kvs" rows)))

(defn- snapshot-upload-session
  [sql]
  (let [{:keys [upload-id status]} (snapshot-upload-state sql)]
    {:upload-id upload-id
     :status status}))

(defn- snapshot-upload-activation-receipt
  [sql]
  (let [{:keys [kind upload-id checksum row-count]}
        (snapshot-upload-state sql)]
    (when (= :committed kind)
      {:upload-id upload-id
       :checksum checksum
       :row-count row-count})))

(defn- snapshot-upload-session-error
  [expected-upload-id actual-upload-id]
  (ex-info "snapshot upload session replaced"
           {:type :db-sync/snapshot-upload-session-replaced
            :expected-upload-id expected-upload-id
            :actual-upload-id actual-upload-id}))

(defn- require-snapshot-upload-session!
  [sql upload-id]
  (let [{current-upload-id :upload-id
         status :status} (require-valid-snapshot-upload-state! sql)]
    (when-not (= upload-id current-upload-id)
      (throw (snapshot-upload-session-error upload-id current-upload-id)))
    status))

(defn- start-snapshot-upload-session!
  [sql upload-id]
  (storage/with-sql-transaction!
   sql
   (fn []
     (common/sql-exec sql (str "delete from " snapshot-staging-table))
     (storage/delete-meta!
      sql snapshot-upload-committed-checksum-meta-key)
     (storage/delete-meta!
      sql snapshot-upload-committed-row-count-meta-key)
     (storage/set-meta! sql snapshot-upload-id-meta-key upload-id)
     (storage/set-meta! sql snapshot-upload-status-meta-key snapshot-upload-status-active)
     (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now))
     (storage/set-meta! sql snapshot-uploading-meta-key true))))

(defn- active-legacy-snapshot-upload?
  [sql]
  (= :legacy-active (:kind (snapshot-upload-state sql))))

(defn- start-legacy-snapshot-upload!
  [sql]
  (let [upload-id (str legacy-snapshot-upload-id ":" (random-uuid))]
    (storage/with-sql-transaction!
     sql
     (fn []
       (common/sql-exec sql (str "delete from " snapshot-staging-table))
       (storage/delete-meta!
        sql snapshot-upload-committed-checksum-meta-key)
       (storage/delete-meta!
        sql snapshot-upload-committed-row-count-meta-key)
       (storage/set-meta! sql snapshot-upload-id-meta-key upload-id)
       (storage/set-meta! sql snapshot-upload-status-meta-key
                          snapshot-upload-status-legacy-active)
       (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now))
       (storage/set-meta! sql snapshot-uploading-meta-key true)))
    upload-id))

(defn- clear-staged-snapshot-upload!
  [sql]
  (common/sql-exec sql (str "delete from " snapshot-staging-table))
  (doseq [key [snapshot-upload-id-meta-key
               snapshot-upload-status-meta-key
               snapshot-uploading-meta-key
               snapshot-upload-started-at-meta-key
               snapshot-upload-committed-checksum-meta-key
               snapshot-upload-committed-row-count-meta-key]]
    (storage/delete-meta! sql key)))

(defn- abort-staged-snapshot-upload!
  [sql]
  (storage/with-sql-transaction!
   sql
   (fn []
     (clear-staged-snapshot-upload! sql))))

(defn- abort-snapshot-upload-if-current!
  [sql upload-id]
  (storage/with-sql-transaction!
   sql
   (fn []
     (let [{current-upload-id :upload-id
            status :status} (snapshot-upload-session sql)]
       (when (and (seq upload-id)
                  (= upload-id current-upload-id)
                  (not= snapshot-upload-status-committed status))
         (clear-staged-snapshot-upload! sql)
         true)))))

(defn- recover-snapshot-upload-if-current!
  "Recover only the durable generation named by this failed request. An
  already committed generation retains its activation receipt and merely
  drops scratch rows; a replaced generation is untouched."
  [sql upload-id]
  (storage/with-sql-transaction!
   sql
   (fn []
     (let [{current-upload-id :upload-id
            status :status} (snapshot-upload-session sql)]
       (cond
         (or (not (seq upload-id))
             (not= upload-id current-upload-id))
         :not-current

         (= snapshot-upload-status-committed status)
         (do
           (common/sql-exec sql (str "delete from " snapshot-staging-table))
         :committed)

         :else
         (if (abort-snapshot-upload-if-current! sql upload-id)
           :aborted
           :not-current))))))

(defn- try-upload-recovery-step-twice
  [f]
  (try
    {:ok? true :value (f) :attempts 1}
    (catch :default first-error
      (try
        {:ok? true :value (f) :attempts 2}
        (catch :default error
          {:ok? false
           :attempts 2
           :first-error first-error
           :error error})))))

(defn- refresh-snapshot-upload-lease!
  [sql upload-id]
  (require-snapshot-upload-session! sql upload-id)
  (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now)))

(defn- import-staged-snapshot-rows!
  [sql upload-id rows]
  (require-snapshot-upload-session! sql upload-id)
  (import-snapshot-rows! sql snapshot-staging-table rows))

(defn- copy-snapshot-table!
  [sql source-table destination-table]
  (when-not (and (contains? #{"kvs" snapshot-staging-table} source-table)
                 (contains? #{"kvs" snapshot-staging-table} destination-table)
                 (not= source-table destination-table))
    (throw (ex-info "invalid snapshot table switch"
                    {:type :db-sync/invalid-snapshot-table-switch
                     :source source-table
                     :destination destination-table})))
  (common/sql-exec sql (str "delete from " destination-table))
  (common/sql-exec
   sql
   (str "insert into " destination-table " (addr, content, addresses) "
        "select addr, content, addresses from " source-table)))

(defn- staging-repair-plan
  "Open the source staging root only long enough to validate it and extract a
  plain replay plan. The returned value never retains that DataScript root."
  [sql trusted-graph-created-at]
  (let [source-conn
        (storage/open-snapshot-conn sql snapshot-staging-table)]
    (snapshot-integrity/prepare-repair-plan!
     source-conn trusted-graph-created-at)))

(defn- persist-staging-repair-plan!
  "Construct and persist one clean shadow after the source connection has left
  scope. No live connection is resident while this helper runs."
  [sql repair-plan]
  (let [repaired-conn
        (snapshot-integrity/replay-repair-plan! repair-plan)]
    (storage/replace-snapshot-staging-from-db! sql @repaired-conn)
    true))

(defn- validate-staging-and-canonicalize!
  [sql trusted-graph-created-at]
  (let [repair-plan
        (staging-repair-plan sql trusted-graph-created-at)
        repaired? (boolean repair-plan)]
    (when repair-plan
      (persist-staging-repair-plan! sql repair-plan))
    ;; Reopen through the raw persisted root only after both the source and
    ;; replay connections have left their helper scopes. A replay that is valid
    ;; only in memory is not eligible to replace live storage.
    (let [persisted-conn
          (storage/open-snapshot-conn sql snapshot-staging-table)]
      (when (snapshot-integrity/repair-required? persisted-conn nil)
        (throw (ex-info "snapshot canonicalization is not idempotent"
                        {:type :db-sync/snapshot-repair-not-idempotent})))
      (require-graph-created-at! @persisted-conn :snapshot-staging)
      {:conn persisted-conn
       :repaired? repaired?
       :checksum (sync-checksum/recompute-checksum @persisted-conn)
       :server-checksum
       (sync-checksum/recompute-server-checksum @persisted-conn)})))

(defn- validate-persisted-live-snapshot!
  [sql]
  (let [live-conn (storage/open-snapshot-conn sql "kvs")]
    (when (snapshot-integrity/validate-or-repair! live-conn nil)
      (throw (ex-info "live snapshot switch is not canonical"
                      {:type :db-sync/live-snapshot-not-canonical})))
    (require-graph-created-at! @live-conn :snapshot-live-switch)
    live-conn))

(defn- repair-live-snapshot!
  "Repair live KVS from isolated staging. The caller must release self.conn
  before entering so the replay never overlaps the resident live DataScript
  root. Every non-KVS durability table and metadata key remains outside the
  write set."
  [^js self trusted-graph-created-at]
  (let [sql (.-sql self)
        repaired?
        (if (storage/snapshot-integrity-attested? sql)
          false
          (try
            (storage/with-sql-transaction!
             sql
             (fn []
               (if (storage/snapshot-integrity-attested? sql)
                 false
                 (do
             ;; Do not borrow staging from an upload generation. This check
             ;; and the table switch are synchronous under Durable Object
             ;; transactionSync, so an upload cannot interleave after the
             ;; gate.
             (when-not (snapshot-upload-finished? self)
               (throw (ex-info "snapshot upload already in progress"
                               {:type :db-sync/snapshot-upload-in-progress})))
                   (copy-snapshot-table! sql "kvs" snapshot-staging-table)
                   (let [{:keys [repaired?]}
                         (validate-staging-and-canonicalize!
                          sql trusted-graph-created-at)
                         persisted-live-conn
                         (if repaired?
                           (do
                             (copy-snapshot-table!
                              sql snapshot-staging-table "kvs")
                             (validate-persisted-live-snapshot! sql))
                           (storage/open-snapshot-conn sql "kvs"))]
                     ;; Checksum metadata and the integrity attestation are one
                     ;; atomic statement of the final validated live root at
                     ;; the existing server cursor.
                     (seal-validated-snapshot-db!
                      sql @persisted-live-conn (storage/get-t sql)
                      (str (random-uuid)))
                     (common/sql-exec
                      sql (str "delete from " snapshot-staging-table))
                     repaired?)))))
            (catch :default error
              ;; The failed transaction restored the prior staging root. When
              ;; no upload owns it, clear that stale scratch state separately;
              ;; never clear an active upload generation.
              (when (and (not= :db-sync/snapshot-upload-in-progress
                               (:type (ex-data error)))
                         (snapshot-upload-finished? self))
                (storage/with-sql-transaction!
                 sql
                 #(common/sql-exec
                   sql (str "delete from " snapshot-staging-table))))
              (throw error))))]
    (when repaired?
      ;; Reopen only after the atomic switch commits. A failed transaction
      ;; leaves both live SQL and the existing in-memory connection untouched.
      (set! (.-conn self) nil)
      (set! (.-conn self) (storage/open-conn sql))
      (when (snapshot-integrity/validate-or-repair! (.-conn self) nil)
        (throw (ex-info "reopened live snapshot is not canonical"
                        {:type :db-sync/live-snapshot-not-canonical}))))
    repaired?))

(defn- seal-healthy-live-snapshot!
  "Validate and attest the sole resident live connection without copying or
  reopening the DataScript root. The raw root/t are rechecked inside the same
  SQL transaction that publishes checksums and the attestation."
  [^js self trusted-graph-created-at]
  (ensure-conn! self)
  (let [sql (.-sql self)
        conn (.-conn self)
        t-before (storage/get-t sql)
        root-before (storage/snapshot-storage-root-digest sql)]
    (when (snapshot-integrity/repair-required?
           conn trusted-graph-created-at)
      (throw (ex-info "snapshot repair requires released live connection"
                      {:type :db-sync/snapshot-repair-requires-release})))
    (require-graph-created-at! @conn :snapshot-live-bootstrap)
    (storage/with-sql-transaction!
     sql
     (fn []
       (when-not (snapshot-upload-finished? self)
         (throw (ex-info "snapshot upload already in progress"
                         {:type :db-sync/snapshot-upload-in-progress})))
       (when-not (and (= t-before (storage/get-t sql))
                      (string? root-before)
                      (= root-before
                         (storage/snapshot-storage-root-digest sql)))
         (throw (ex-info "live snapshot changed during validation"
                         {:type :db-sync/snapshot-write-integrity-invalid
                          :t-before t-before
                          :t-after (storage/get-t sql)})))
       (seal-validated-snapshot-db!
        sql @conn t-before (str (random-uuid)))))
    false))

(defn- prepare-live-snapshot!
  "Synchronous fast path for direct/internal callers. Healthy legacy storage
  is sealed in-place. A repairable or stale-root state remains fail-closed so
  only the async single-flight path may release/reopen connections."
  [^js self trusted-graph-created-at]
  (let [sql (.-sql self)]
    (cond
      (storage/snapshot-integrity-attested? sql)
      false

      (storage/snapshot-integrity-history-conflict? sql)
      (throw (ex-info "snapshot transaction history inconsistent"
                      {:type :db-sync/tx-log-integrity
                       :t (storage/get-t sql)}))

      (and (map? (storage/snapshot-integrity-attestation sql))
           (some? (.-conn self)))
      (throw (ex-info "stale live connection requires async reopen"
                      {:type :db-sync/snapshot-repair-requires-release}))

      :else
      (do
        (ensure-conn! self)
        (if (snapshot-integrity/repair-required?
             (.-conn self) trusted-graph-created-at)
          (throw (ex-info "snapshot repair requires async release"
                          {:type :db-sync/snapshot-repair-requires-release}))
          (seal-healthy-live-snapshot!
           self trusted-graph-created-at))))))

(defn- <prepare-live-snapshot!
  [^js self graph-id]
  (ensure-schema! self)
  (let [sql (.-sql self)]
    (cond
      (storage/snapshot-integrity-attested? sql)
      (p/resolved false)

      (storage/snapshot-integrity-history-conflict? sql)
      (p/rejected
       (ex-info "snapshot transaction history inconsistent"
                {:type :db-sync/tx-log-integrity
                 :t (storage/get-t sql)}))

      :else
      (let [stale-root?
            (and (map? (storage/snapshot-integrity-attestation sql))
                 (some? (.-conn self)))]
        (when stale-root?
          ;; An exact-root marker exists but no longer binds raw KVS. Drop the
          ;; cached view before inspecting storage so a same-cursor replacement
          ;; cannot be re-attested from stale in-memory facts.
          (set! (.-conn self) nil))
        (p/let [_ (when stale-root? (p/delay 0))
                _ (ensure-conn! self)
                trusted-graph-created-at
                (<trusted-graph-created-at self graph-id (.-conn self))
                repair?
                (snapshot-integrity/repair-required?
                 (.-conn self) trusted-graph-created-at)]
          (if-not repair?
            (seal-healthy-live-snapshot! self trusted-graph-created-at)
            (do
              ;; End the only live connection's lifetime before staging replay.
              ;; The bootstrap Promise is already installed as the single-flight
              ;; gate, so all normal reads/writes await this transition.
              (set! (.-conn self) nil)
              (-> (p/let [_ (p/delay 0)]
                    (repair-live-snapshot! self trusted-graph-created-at))
                  (p/catch
                   (fn [error]
                     ;; Live SQL was either untouched or transactionally rolled
                     ;; back. Reopen that exact old root before surfacing failure.
                     (when-not (.-conn self)
                       (set! (.-conn self) (storage/open-conn sql)))
                     (throw error)))))))))))

(defn <ensure-live-integrity!
  "Single-flight bootstrap for healthy legacy storage. Exact-root attestation
  is a synchronous fast path; the Promise exists only while first validation
  may await authoritative D1 metadata. Failure always releases the in-memory
  flight so a later request can retry."
  [^js self graph-id]
  (cond
    (not (snapshot-upload-finished? self))
    (p/rejected (ex-info "snapshot upload already in progress"
                         {:type :db-sync/snapshot-upload-in-progress}))

    (storage/snapshot-integrity-attested? (.-sql self))
    false

    (some? (aget self "snapshotIntegrityBootstrap"))
    (aget self "snapshotIntegrityBootstrap")

    :else
    (let [flight* (atom nil)
          flight
          (p/finally
           (p/let [_ (<prepare-live-snapshot! self graph-id)]
             (when-not (snapshot-upload-finished? self)
               (throw (ex-info "snapshot upload already in progress"
                               {:type :db-sync/snapshot-upload-in-progress})))
             true)
           (fn []
             (when (identical?
                    (aget self "snapshotIntegrityBootstrap")
                    @flight*)
               (aset self "snapshotIntegrityBootstrap" nil))))]
      (reset! flight* flight)
      (aset self "snapshotIntegrityBootstrap" flight)
      flight)))

(defn- <prepare-live-write!
  "Bootstrap a healthy legacy graph before its first write and reject an
  active snapshot generation both before and after the only await. The final
  synchronous write guard repeats these checks inside transactionSync."
  [^js self graph-id]
  (<ensure-live-integrity! self graph-id))

(defn- semantic-write-executor
  [^js self]
  ;; This factory runs after integrity bootstrap but before the Semantic
  ;; handler may await JSON/R2 work. Capture both the live object and its
  ;; durable generation; the returned synchronous commit capability is valid
  ;; only while both are still current.
  (let [sql (.-sql self)
        expected-conn (.-conn self)
        expected-t (storage/get-t sql)
        expected-generation
        (storage/eligible-snapshot-integrity-generation sql expected-t)]
    (fn [write-f]
      (let [sql (.-sql self)
            write-started? (atom false)]
        (try
          (storage/with-sql-transaction!
           sql
           (fn []
             (when-not (identical? expected-conn (.-conn self))
               (throw (ex-info
                       "semantic write connection changed before commit"
                       {:type :db-sync/snapshot-write-integrity-invalid
                        :t (storage/get-t sql)})))
             (when-not (snapshot-upload-finished? self)
               (throw (ex-info "snapshot upload already in progress"
                               {:type :db-sync/snapshot-upload-in-progress})))
             (let [t (storage/get-t sql)
                   generation
                   (storage/eligible-snapshot-integrity-generation sql t)]
               (when-not (and (string? expected-generation)
                              (= expected-t t)
                              (= expected-generation generation))
                 (throw (ex-info
                         "semantic write generation changed before commit"
                         {:type :db-sync/snapshot-write-integrity-invalid
                          :t t})))
               (let [result
                     (binding [storage/*snapshot-integrity-write-generation*
                               generation]
                       (reset! write-started? true)
                       (write-f))]
                 (when-not (storage/snapshot-integrity-attested? sql)
                   (throw (ex-info
                           "semantic write did not preserve snapshot integrity"
                           {:type :db-sync/snapshot-write-integrity-invalid
                            :t (storage/get-t sql)})))
                 result))))
          (catch :default error
            ;; transactionSync restored SQL. Discard a DataScript db-after only
            ;; when this request actually began mutating the connection that is
            ;; still live. A pre-write identity/generation rejection must leave
            ;; the newly activated connection untouched.
            (when (and @write-started?
                       (identical? expected-conn (.-conn self)))
              (set! (.-conn self) nil)
              (ensure-conn! self))
            (throw error)))))))

(defn- commit-staged-snapshot!
  [^js self upload-id checksum expected-row-count trusted-graph-created-at]
  (let [sql (.-sql self)]
    (storage/with-sql-transaction!
     sql
     (fn []
       (let [upload-started-at
             (:started-at (require-valid-snapshot-upload-state! sql))
             _ (require-snapshot-upload-session! sql upload-id)
             ;; A v1 URL has no download identity. Replacing live while an
             ;; already-issued lease exists would either orphan that URL or
             ;; serve it from the new basis. Expire only idle reservations,
             ;; then retain this upload for a retry after the old lease drains.
             _ (cleanup-expired-snapshot-downloads! self (js/Date.now))
             _ (when (outstanding-legacy-snapshot-download? sql)
                 (throw
                  (ex-info "snapshot download is still in progress"
                           {:type
                            :db-sync/snapshot-upload-waiting-for-downloads
                            :upload-id upload-id})))
             actual-row-count
             (or (some-> (common/sql-exec
                          sql
                          (str "select count(*) as row_count from "
                               snapshot-staging-table))
                         common/get-sql-rows
                         first
                         (aget "row_count"))
                 0)
             _ (when (and (some? expected-row-count)
                          (not= expected-row-count actual-row-count))
                 (throw
                  (ex-info "snapshot row count mismatch"
                           {:type :db-sync/snapshot-row-count-mismatch
                            :expected-row-count expected-row-count
                            :actual-row-count actual-row-count})))
             {canonical-checksum :checksum}
             (validate-staging-and-canonicalize!
              sql trusted-graph-created-at)]
         (when-not (= checksum canonical-checksum)
           (throw (ex-info "snapshot checksum mismatch"
                           {:type :db-sync/snapshot-checksum-mismatch
                            :expected-checksum checksum
                            :actual-checksum canonical-checksum})))
       (copy-snapshot-table! sql snapshot-staging-table "kvs")
       ;; Prove the raw live storage root reopens consistently before any
       ;; activation metadata is committed. Any failure rolls the switch back.
       (let [persisted-live-conn (validate-persisted-live-snapshot! sql)]
       (common/sql-exec sql "delete from tx_log")
       (common/sql-exec sql "delete from applied_client_txs")
       (common/sql-exec sql "delete from client_tx_upload_chunks")
       (common/sql-exec sql "delete from client_tx_uploads")
       (common/sql-exec sql "delete from sync_meta")
       (storage/set-t! sql 0)
       (seal-validated-snapshot-db!
        sql @persisted-live-conn (storage/get-t sql) (str (random-uuid)))
       (storage/set-meta! sql snapshot-upload-id-meta-key upload-id)
       (storage/set-meta! sql snapshot-upload-status-meta-key snapshot-upload-status-committed)
       (storage/set-meta!
        sql snapshot-upload-started-at-meta-key upload-started-at)
       (storage/set-meta!
        sql snapshot-upload-committed-checksum-meta-key canonical-checksum)
       (storage/set-meta!
        sql snapshot-upload-committed-row-count-meta-key actual-row-count)
       (storage/set-meta! sql snapshot-uploading-meta-key false)))))
    (set! (.-conn self) nil)
    (set! (.-conn self) (open-validated-snapshot-conn! sql))))

(defn- cleanup-staged-snapshot!
  [sql upload-id]
  (when (= upload-id (:upload-id (snapshot-upload-session sql)))
    (common/sql-exec sql (str "delete from " snapshot-staging-table))))

(defn- tx-entry-identity
  [{:keys [tx tx-id logical-tx-id upload-session-id chunk-index chunk-final?
           outliner-op]
    :as tx-entry}]
  (let [chunk-metadata? (some #(contains? tx-entry %)
                              [:logical-tx-id :upload-session-id
                               :chunk-index :chunk-next-index :chunk-final?])]
    (cond
      chunk-metadata?
      (do
        (when-not (and (string? tx)
                       (uuid? logical-tx-id)
                       (string? upload-session-id)
                       (boolean (re-matches #"[0-9a-f]{64}" upload-session-id))
                       (integer? chunk-index)
                       (not (neg? chunk-index))
                       ;; Source slicing progress is client-local state. Even a
                       ;; numerically plausible span field must be rejected,
                       ;; never accepted as server authority.
                       (not (contains? tx-entry :chunk-next-index))
                       ;; Semantic operations are ordinary, bounded txs. A
                       ;; staged envelope must never smuggle an unbound intent.
                       (not (contains? tx-entry :semantic-op))
                       (boolean? chunk-final?)
                       (keyword? outliner-op)
                       (= tx-id
                          (protocol/tx-chunk-id
                           logical-tx-id upload-session-id
                           chunk-index chunk-final?)))
          (throw (ex-info "invalid tx chunk identity"
                          (cond-> {:type :db-sync/invalid-tx-chunk-identity}
                            tx-id (assoc :failed-tx-id tx-id)))))
        (str "chunk/" logical-tx-id "/" upload-session-id "/" chunk-index))

      tx-id
      (str "tx/" tx-id)

      :else
      nil)))

(defn- tx-entry-payload-digest
  [{:keys [outliner-op chunk-final? tx semantic-op] :as tx-entry}]
  ;; Always compute from the received envelope. A client-declared digest is
  ;; advisory at most and must never authorize an idempotent replay.
  (if (:logical-tx-id tx-entry)
    (protocol/tx-wire-payload-digest tx-entry)
    (protocol/tx-payload-digest
     outliner-op chunk-final? (protocol/transit->tx tx)
     (some-> semantic-op protocol/transit->tx))))

(defn- prepare-tx-batch
  [sql tx-entries]
  (let [prepared
        (mapv (fn [tx-entry]
                (let [identity (tx-entry-identity tx-entry)
                      upload? (contains? tx-entry :logical-tx-id)]
                  (cond-> (assoc tx-entry
                                 ::payload-digest
                                 (tx-entry-payload-digest tx-entry)
                                 ::batch-identity identity
                                 ::upload? upload?)
                    (and identity (not upload?))
                    (assoc ::identity identity))))
              tx-entries)
        batch-identities (into [] (keep ::batch-identity) prepared)
        identities (into [] (keep ::identity) prepared)
        duplicate-upload-logical
        (some (fn [[logical-tx-id n]]
                (when (> n 1) logical-tx-id))
              (frequencies (keep #(when (::upload? %)
                                    (:logical-tx-id %))
                                 prepared)))
        duplicate-identity
        (some (fn [[identity n]]
                (when (> n 1) identity))
              (frequencies batch-identities))]
    (when (or duplicate-identity duplicate-upload-logical)
      (let [entry (some #(when (if duplicate-identity
                                 (= duplicate-identity (::batch-identity %))
                                 (= duplicate-upload-logical (:logical-tx-id %)))
                           %)
                        prepared)]
        (throw (ex-info "duplicate tx identity in batch"
                        (cond-> {:type :db-sync/duplicate-tx-identity}
                          (:tx-id entry) (assoc :failed-tx-id (:tx-id entry)))))))
    (let [persisted (storage/applied-client-tx-records sql identities)]
      (doseq [{:keys [tx-id] :as entry} prepared
              :let [identity (::identity entry)
                    previous-digest (get persisted identity)]
              :when (and identity previous-digest
                         (not= previous-digest (::payload-digest entry)))]
        (throw (ex-info "tx identity payload conflict"
                        (cond-> {:type :db-sync/tx-identity-conflict}
                          tx-id (assoc :failed-tx-id tx-id)))))
      {:tx-entries prepared
       :applied-identities (set (keys persisted))})))

(defn- apply-client-tx-meta
  [request-context outliner-op tx-id]
  (cond-> (merge {:op :apply-client-tx}
                 (request-context->tx-meta request-context))
    tx-id
    (assoc :tx-id tx-id)
    outliner-op
    (assoc :outliner-op outliner-op)
    (= outliner-op :db-migrate)
    (assoc :db-migrate? true
           :skip-validate-db? true)))

(declare tx-touches-graph-created-at?)

(defn- apply-large-tx-entry!
  [self conn tx-data {:keys [tx-id outliner-op] :as tx-entry} request-context]
  (let [db-before @conn
        protect-graph-created-at?
        (or (graph-created-at-valid? db-before)
            (tx-touches-graph-created-at? db-before tx-data))
        base-tx-meta (apply-client-tx-meta request-context outliner-op nil)
        sql (when self (.-sql ^js self))
        prev-t (when sql (storage/get-t sql))
        prev-checksum (when sql (storage/get-checksum sql))
        prev-server-checksum (when sql (storage/get-server-checksum sql))
        prev-server-checksum-t (when sql (storage/get-server-checksum-t sql))
        integrity-generation
        (when sql
          (or (storage/batch-snapshot-integrity-generation sql)
              (when (storage/snapshot-integrity-attested? sql)
                (storage/eligible-snapshot-integrity-generation sql prev-t))))
        tx-meta (cond-> base-tx-meta
                  integrity-generation
                  (assoc :db-sync/snapshot-integrity-generation
                         integrity-generation))
        verified-checksum-metadata?
        (when sql (storage/checksum-metadata-verified? sql prev-t))
        staged-upload-final? (::staged-upload-final? tx-entry)
        logical-tx-data (volatile! [])
        chunk-count (volatile! 0)]
    (log/info :db-sync/apply-large-tx-entry-start
              {:graph-id (:graph-id request-context)
               :tx-id tx-id
               :outliner-op outliner-op
               :tx-count (count tx-data)
               :max-chunk-items large-tx-max-chunk-items})
    (try
      (when sql
        (d/listen! conn ::large-logical-tx-checksum
                   (fn [{:keys [tx-data]}]
                     (vswap! logical-tx-data into tx-data))))
      ((if sql
         #(storage/with-sql-transaction! sql %)
         (fn [f] (f)))
       (fn []
         (try
           (reduce-ordered-tx-chunks
            db-before
            (fn [_ chunk]
              (vswap! chunk-count inc)
              (ldb/transact! conn
                             chunk
                             (cond-> tx-meta
                               sql
                               (assoc :db-sync/skip-checksum-update? true)
                               (and sql staged-upload-final?)
                               (assoc :db-sync/skip-tx-log? true)))
              nil)
            nil
            tx-data)
           (when protect-graph-created-at?
             (require-graph-created-at! @conn :server-transaction))
           (when sql
             (if staged-upload-final?
               ;; Staged chunks are already the wire-level split of one
               ;; logical transaction. The later Datascript safety chunks are
               ;; an implementation detail, so expose one normalized row/t.
               (let [new-t (inc prev-t)
                     normalized-data (->> @logical-tx-data
                                          (db-normalize/normalize-tx-data
                                           @conn db-before)
                                          vec)]
                 (storage/append-tx! sql new-t
                                     (common/write-transit normalized-data)
                                     (common/now-ms) outliner-op tx-id
                                     integrity-generation)
                 (storage/set-t! sql new-t))
               ;; Preserve the established ordinary-large-tx history shape:
               ;; its internal chunk rows remain visible and only the final
               ;; row carries the idempotency tx id.
               (storage/set-tx-id-for-t! sql (storage/get-t sql) tx-id))
             (if integrity-generation
               ;; The logical transaction has one validated final DB. Seal
               ;; from that DB instead of allowing incremental metadata to
               ;; attest itself after the safety chunks complete.
               (seal-validated-snapshot-db!
                sql @conn (storage/get-t sql) integrity-generation)
               (do
                 (storage/set-checksum!
                  sql
                  (sync-checksum/update-checksum
                   prev-checksum
                   {:db-before db-before
                    :db-after @conn
                    :tx-data @logical-tx-data}))
                 (when (or verified-checksum-metadata?
                           (string? prev-server-checksum))
                   (storage/set-server-checksum!
                    sql
                    (if (and (string? prev-server-checksum)
                             (= prev-t prev-server-checksum-t))
                      ((if verified-checksum-metadata?
                         sync-checksum/update-verified-server-checksum
                         sync-checksum/update-server-checksum)
                       prev-server-checksum
                       {:db-before db-before
                        :db-after @conn
                        :tx-data @logical-tx-data})
                      (sync-checksum/recompute-server-checksum @conn))
                    (storage/get-t sql)))
                 (when verified-checksum-metadata?
                   (storage/mark-checksum-metadata-verified!
                    sql
                    (storage/get-t sql)))))
             (storage/record-applied-client-tx!
              sql (::identity tx-entry) (::payload-digest tx-entry)))
           (finally
             (when sql
               (d/unlisten! conn ::large-logical-tx-checksum))))))
      (log/info :db-sync/apply-large-tx-entry-done
                {:graph-id (:graph-id request-context)
                 :tx-id tx-id
                 :outliner-op outliner-op
                 :tx-count (count tx-data)
                 :chunk-count @chunk-count})
      true
      (catch :default error
        (log/info :db-sync/apply-large-tx-entry-failed
                  {:graph-id (:graph-id request-context)
                   :tx-id tx-id
                   :outliner-op outliner-op
                   :tx-count (count tx-data)
                   :chunk-count @chunk-count})
        (reset! conn db-before)
        (throw error)))))

(defn- upload-state-error
  [type tx-entry message]
  (ex-info message
           (cond-> {:type type}
             (:tx-id tx-entry) (assoc :failed-tx-id (:tx-id tx-entry)))))

(defn- require-contiguous-upload!
  [tx-entry chunks]
  (loop [expected-index 0
         remaining chunks]
    (if-let [{:keys [chunk-index]} (first remaining)]
      (if (= expected-index chunk-index)
        (recur (inc expected-index) (next remaining))
        (throw (upload-state-error
                :db-sync/upload-session-corrupt tx-entry
                "stored upload chunk ordinals are not contiguous")))
      expected-index)))

(defn- ensure-client-upload-session!
  [sql {:keys [logical-tx-id upload-session-id chunk-index chunk-final?
               outliner-op] :as tx-entry}]
  (let [existing (storage/client-tx-upload sql logical-tx-id)]
    (cond
      (nil? existing)
      (do
        (when (or (not (zero? chunk-index)) chunk-final?)
          (throw (upload-state-error
                  (if chunk-final?
                    :db-sync/upload-session-final-first
                    :db-sync/upload-session-out-of-order)
                  tx-entry
                  "upload session must start with a nonfinal chunk at index zero")))
        (storage/start-client-tx-upload!
         sql logical-tx-id upload-session-id outliner-op)
        (storage/client-tx-upload sql logical-tx-id))

      (= "completed" (:status existing))
      (if (and (= upload-session-id (:session-id existing))
               chunk-final?
               (= chunk-index (:final-index existing))
               (= (::payload-digest tx-entry) (:final-wire-digest existing)))
        (assoc existing :completed-retry? true)
        (throw (upload-state-error
                :db-sync/upload-session-completed tx-entry
                "logical upload is already completed")))

      (not= upload-session-id (:session-id existing))
      (do
        (when (or (not (zero? chunk-index)) chunk-final?)
          (throw (upload-state-error
                  :db-sync/upload-session-mismatch tx-entry
                  "replacement upload session must restart at index zero")))
        ;; An explicit authenticated chunk-zero request is the only operation
        ;; that abandons an incomplete generation. No time-based GC can race a
        ;; delayed ACK retry.
        (storage/replace-client-tx-upload!
         sql logical-tx-id (:session-id existing) upload-session-id outliner-op)
        (storage/client-tx-upload sql logical-tx-id))

      (not= outliner-op (:outliner-op existing))
      (throw (upload-state-error
              :db-sync/upload-session-metadata-conflict tx-entry
              "upload outliner operation changed within one session"))

      :else
      existing)))

(declare apply-tx-entry!)

(defn- allowed-empty-upload?
  [sql {:keys [logical-tx-id upload-session-id chunk-index chunk-final?]
        :as tx-entry}]
  (let [existing (storage/client-tx-upload sql logical-tx-id)]
    (and (= upload-session-id (:session-id existing))
         chunk-final?
         (pos? chunk-index)
         (or (and (= "active" (:status existing))
                  (= chunk-index (:next-index existing)))
             ;; A lost final ACK may replay the exact empty terminator after
             ;; the session has completed. Let the existing completed-retry
             ;; path verify the persisted final ordinal and server-derived
             ;; wire digest; no other completed empty entry is admitted.
             (and (= "completed" (:status existing))
                  (= chunk-index (:final-index existing))
                  (= (::payload-digest tx-entry)
                     (:final-wire-digest existing)))))))

(defn- apply-upload-chunk!
  [self conn {:keys [logical-tx-id upload-session-id chunk-index chunk-final?
                     outliner-op tx] :as tx-entry}
   request-context]
  (let [sql (.-sql ^js self)
        db-before @conn]
    (try
      (storage/with-sql-transaction!
       sql
       (fn []
         (let [tx-data (protocol/transit->tx tx)
               _ (when (and (empty? tx-data)
                            (not (allowed-empty-upload?
                                  sql tx-entry)))
                   ;; Validate before ensure/start/replace/append. An empty
                   ;; nonfinal chunk carries no progress and must never create,
                   ;; replace, or advance durable staging state. The sole
                   ;; useful empty payload is the final terminator for an
                   ;; already-active contiguous generation, or its exact
                   ;; completed-session ACK-loss replay.
                   (throw (upload-state-error
                           :db-sync/invalid-empty-upload-chunk tx-entry
                           "empty upload chunk is not an allowed final terminator")))
               session (ensure-client-upload-session! sql tx-entry)]
           (if (:completed-retry? session)
             false
             (let [wire-digest (::payload-digest tx-entry)
                   datom-count (count tx-data)
                   stored (storage/client-tx-upload-chunk
                           sql upload-session-id chunk-index)]
               (cond
                 stored
                 (if (= wire-digest (:wire-digest stored))
                   false
                   (throw (upload-state-error
                           :db-sync/upload-chunk-payload-conflict tx-entry
                           "upload chunk identity was reused with different wire content")))

                 (not= chunk-index (:next-index session))
                 (throw (upload-state-error
                         :db-sync/upload-session-out-of-order tx-entry
                         "upload chunk is not the next contiguous range"))

                 (and chunk-final? (zero? chunk-index))
                 (throw (upload-state-error
                         :db-sync/upload-session-final-first tx-entry
                         "final chunk cannot start a split upload"))

                 :else
                 (do
                   (storage/append-client-tx-upload-chunk!
                    sql upload-session-id chunk-index tx wire-digest datom-count)
                   (if-not chunk-final?
                     false
                     (let [chunks (storage/client-tx-upload-chunks
                                   sql upload-session-id)
                           next-index (require-contiguous-upload! tx-entry chunks)
                           _ (when-not (= next-index (inc chunk-index))
                               (throw (upload-state-error
                                       :db-sync/upload-session-corrupt tx-entry
                                       "final upload ordinal does not match stored chunks")))
                           full-tx-data (into []
                                              (mapcat (comp protocol/transit->tx :tx))
                                              chunks)
                           completed-digest
                           (protocol/tx-upload-completed-digest
                            outliner-op (mapv :wire-digest chunks))
                           apply-entry (-> tx-entry
                                           (assoc :tx-id logical-tx-id
                                                  ::decoded-tx-data full-tx-data
                                                  ::identity (str "upload/" logical-tx-id)
                                                  ::payload-digest completed-digest
                                                  ::staged-upload-final? true)
                                           (dissoc ::upload?))]
                       ;; Final assembly must enter exactly the same sanitize,
                       ;; no-op, stale rebase/fix, delete, migration, and large
                       ;; transaction path as an ordinary tx entry. The outer
                       ;; SQL transaction includes both staged rows and graph
                       ;; mutation, so any sanitize/apply failure rolls back all
                       ;; final-chunk effects atomically.
                       #_{:clj-kondo/ignore [:redundant-let]}
                       (let [applied? (apply-tx-entry!
                                       self conn apply-entry request-context)]
                         (storage/complete-client-tx-upload!
                          sql logical-tx-id upload-session-id chunk-index
                          wire-digest completed-digest)
                         applied?))))))))))
      (catch :default error
        (reset! conn db-before)
        (throw error)))))

(defn- logseq-kv-ident?
  [value]
  (and (keyword? value)
       (= "logseq.kv" (namespace value))))

(defn- tx-item-declared-ident
  [item]
  (cond
    (map? item)
    (:db/ident item)

    (and (vector? item)
         (<= 4 (count item))
         (= :db/ident (nth item 2 nil)))
    (if (contains? #{:db/cas :db.fn/cas} (first item))
      (nth item 4 nil)
      (nth item 3 nil))

    :else
    nil))

(defn- tx-item-entity-ref
  [item]
  (cond
    (map? item) (:db/id item)
    (and (vector? item) (<= 2 (count item))) (second item)
    :else nil))

(defn- entity-ident
  [db entity-ref]
  (when (some? entity-ref)
    (try
      (:db/ident (d/entity db entity-ref))
      (catch :default _
        nil))))

(defn- validate-system-kv-wire!
  [db tx-data]
  (let [tempid->registered-ident (volatile! {})]
    (doseq [item tx-data]
      (let [declared-ident (tx-item-declared-ident item)
            entity-ref (tx-item-entity-ref item)
            existing-ident (or (entity-ident db entity-ref)
                               (get @tempid->registered-ident entity-ref))]
        (when (and (logseq-kv-ident? declared-ident)
                   (not (contains? registered-system-kv-idents declared-ident)))
          (throw (ex-info "unregistered system KV ident"
                          {:type :db-sync/unregistered-system-kv
                           :ident declared-ident})))
        (when (and (logseq-kv-ident? entity-ref)
                   (not (contains? registered-system-kv-idents entity-ref)))
          (throw (ex-info "unregistered system KV ident"
                          {:type :db-sync/unregistered-system-kv
                           :ident entity-ref})))
        (when (and (contains? registered-system-kv-idents existing-ident)
                   (or (and (= :db/ident
                               (when (vector? item) (nth item 2 nil)))
                            (or (= :db/retract (first item))
                                (= :db.fn/retract (first item))
                                (not= existing-ident declared-ident)))
                       (and (map? item)
                            (contains? item :db/ident)
                            (not= existing-ident declared-ident))))
          (throw (ex-info "registered system KV identity is immutable"
                          {:type :db-sync/system-kv-ident-rewrite
                           :ident existing-ident})))
        (when (and (contains? registered-system-kv-idents declared-ident)
                   (or (string? entity-ref)
                       (and (number? entity-ref) (neg? entity-ref))))
          (vswap! tempid->registered-ident assoc entity-ref declared-ident))))))

(defn- tx-touches-graph-created-at?
  [db tx-data]
  (boolean
   (some (fn [item]
           (or (= graph-created-at-ident
                  (tx-item-declared-ident item))
               (= graph-created-at-ident
                  (tx-item-entity-ref item))
               (= graph-created-at-ident
                  (entity-ident db (tx-item-entity-ref item)))))
         tx-data)))

(defn- sanitize-tx-entry
  [db {:keys [tx outliner-op] :as tx-entry}]
  (let [input-tx-data (or (::decoded-tx-data tx-entry)
                          (protocol/transit->tx tx))
        _ (validate-system-kv-wire! db input-tx-data)
        tx-data (tx-sanitize/sanitize-tx db
                                         input-tx-data
                                         {:drop-missing-retract-ops? (or (= outliner-op :fix)
                                                                         (contains? delete-outliner-ops outliner-op))
                                          :drop-ops-targeting-retracted-entities? (contains? delete-outliner-ops
                                                                                             outliner-op)
                                          :retract-touched-descendants? (contains? delete-outliner-ops outliner-op)})]
    {:input-tx-data input-tx-data
     :tx-data tx-data
     :tx-entry tx-entry}))

(def ^:private semantic-move-option-keys
  #{:sibling? :top? :bottom?})

(defn- semantic-move-op
  [{:keys [semantic-op outliner-op tx-id]}]
  (when semantic-op
    (let [op-entry
          (try
            (protocol/transit->tx semantic-op)
            (catch :default error
              (throw (ex-info "invalid semantic operation encoding"
                              {:type :db-sync/invalid-semantic-op
                               :failed-tx-id tx-id}
                              error))))
          [op args] op-entry
          [block-ids target-id opts] args
          option-keys (set (keys opts))]
      (when-not (and (= :move-blocks op)
                     (= :move-blocks outliner-op)
                     (= 2 (count op-entry))
                     (= 3 (count args))
                     (vector? block-ids)
                     (seq block-ids)
                     (= (count block-ids) (count (distinct block-ids)))
                     (every? uuid? block-ids)
                     (uuid? target-id)
                     (not (contains? (set block-ids) target-id))
                     (map? opts)
                     (every? semantic-move-option-keys option-keys)
                     (every? boolean? (vals opts))
                     (or (contains? opts :sibling?)
                         (contains? opts :top?)
                         (contains? opts :bottom?)))
        (throw (ex-info "invalid semantic move operation"
                        {:type :db-sync/invalid-semantic-op
                         :failed-tx-id tx-id})))
      op-entry)))

(defn- apply-semantic-move!
  [sql conn tx-entry request-context integrity-generation]
  (let [[_ [block-ids target-id opts]] (semantic-move-op tx-entry)
        db @conn
        blocks (mapv #(d/entity db [:block/uuid %]) block-ids)
        target (d/entity db [:block/uuid target-id])]
    (when (or (some nil? blocks) (nil? target))
      (throw (ex-info "semantic move references a missing block"
                      {:type :db-sync/invalid-semantic-op
                       :failed-tx-id (:tx-id tx-entry)})))
    (let [t-before (some-> sql storage/get-t)]
      (ldb/batch-transact-with-temp-conn!
       conn
       (cond-> (apply-client-tx-meta
                request-context :move-blocks (:tx-id tx-entry))
         integrity-generation
         (assoc :db-sync/snapshot-integrity-generation
                integrity-generation))
       (fn [temp-conn]
         (let [temp-db @temp-conn
               temp-blocks
               (mapv #(d/entity temp-db [:block/uuid %]) block-ids)
               temp-target (d/entity temp-db [:block/uuid target-id])]
           (outliner-core/move-blocks!
            temp-conn temp-blocks temp-target opts))))
      (if sql
        (< t-before (storage/get-t sql))
        (not= db @conn)))))

(defn- apply-tx-entry!
  ([conn tx-entry]
   (apply-tx-entry! nil conn tx-entry nil))
  ([self conn {:keys [tx-id outliner-op] :as tx-entry} request-context]
   (let [db-before @conn
         sql (when self (.-sql ^js self))
         semantic-move (semantic-move-op tx-entry)
         sanitized (when-not semantic-move
                     (sanitize-tx-entry db-before tx-entry))
         input-tx-data (or (:input-tx-data sanitized) [])
         tx-data (or (:tx-data sanitized) [])
         protect-graph-created-at?
         (or (graph-created-at-valid? db-before)
             (tx-touches-graph-created-at? db-before input-tx-data))
         sanitized-entry (:tx-entry sanitized)
         integrity-generation
         (when sql
           (or (storage/batch-snapshot-integrity-generation sql)
               (when (storage/snapshot-integrity-attested? sql)
                 (storage/eligible-snapshot-integrity-generation
                  sql (storage/get-t sql)))))
         apply-entry
         (fn []
           (if semantic-move
             (let [applied?
                   (apply-semantic-move!
                    sql conn tx-entry request-context integrity-generation)]
               (when protect-graph-created-at?
                 (require-graph-created-at! @conn :server-transaction))
               applied?)
             (if (seq tx-data)
               (try
                 (ldb/transact!
                  conn tx-data
                  (cond-> (apply-client-tx-meta
                           request-context outliner-op tx-id)
                    integrity-generation
                    (assoc :db-sync/snapshot-integrity-generation
                           integrity-generation)))
                 (when protect-graph-created-at?
                   (require-graph-created-at! @conn :server-transaction))
                 true
                 (catch :default e
                   ;; Rebase/fix txs are inferred from local history and can become stale
                   ;; when concurrent remote edits remove referenced entities before upload.
                   ;; Treat stale :entity-id/missing rebases/fixes as an accepted no-op.
                   (if (and (contains? #{:rebase :fix} outliner-op)
                            (= :entity-id/missing (:error (ex-data e))))
                     (do
                       (log/warn :db-sync/drop-stale-rebase-tx
                                 {:outliner-op outliner-op
                                  :tx-count (count tx-data)
                                  :error-code (or (:error (ex-data e))
                                                  (:type (ex-data e))
                                                  :stale-rebase)})
                       false)
                     (throw e))))
               (if (and (contains? delete-outliner-ops outliner-op)
                        (empty? input-tx-data))
                 (throw (ex-info "delete tx input is empty"
                                 {:type :db-sync/empty-delete-tx
                                  :outliner-op outliner-op}))
                 false))))]
     (if (and (not= outliner-op :db-migrate)
              (large-tx? tx-data))
       (apply-large-tx-entry! self conn tx-data sanitized-entry request-context)
       (try
         ((if sql
            #(storage/with-sql-transaction! sql %)
            (fn [f] (f)))
          (fn []
            (let [applied? (apply-entry)]
              (when sql
                ;; This explicit marker also covers accepted no-op txs, for
                ;; which the Datascript listener has no tx_log row to attach.
                (storage/record-applied-client-tx!
                 sql (::identity tx-entry) (::payload-digest tx-entry)))
              applied?)))
         (catch :default error
           ;; SQL rollback restores KVS/log/meta. Datascript has already
           ;; published its in-memory db by the time a listener can fail, so
           ;; restore that view explicitly to keep current/fresh conns equal.
           (reset! conn db-before)
           (throw error)))))))

(defn- apply-tx! [^js self tx-entries applied-identities request-context]
  (let [sql (.-sql self)]
    (ensure-conn! self)
    (let [conn (.-conn self)]
      (loop [remaining tx-entries
             applied? false
             successful-tx-ids []]
        (if-let [tx-entry (first remaining)]
          (let [tx-id (:tx-id tx-entry)
                already-applied? (contains? applied-identities (::identity tx-entry))
                applied-entry? (if already-applied?
                                 false
                                 (try
                                   (boolean
                                    (if (::upload? tx-entry)
                                      (apply-upload-chunk!
                                       self conn tx-entry request-context)
                                      (apply-tx-entry!
                                       self conn tx-entry request-context)))
                                   (catch :default e
                                     (log/error :db-sync/transact-failed
                                                (common/error-log-data e))
                                     (let [missing-block-uuids (missing-block-uuids-from-error e)
                                           cause-data (or (ex-data e) {})
                                           cause-type (:type cause-data)
                                           upload-protocol-error?
                                           (and (keyword? cause-type)
                                                (string/starts-with?
                                                 (name cause-type)
                                                 "upload-"))
                                           system-kv-policy-error?
                                           (contains?
                                            #{:db-sync/system-kv-integrity
                                              :db-sync/unregistered-system-kv
                                              :db-sync/system-kv-ident-rewrite}
                                            cause-type)
                                           semantic-policy-error?
                                           (= :db-sync/invalid-semantic-op
                                              cause-type)]
                                       (throw (ex-info "tx entry apply failed"
                                                       (cond-> {:type (if (or upload-protocol-error?
                                                                             system-kv-policy-error?
                                                                             semantic-policy-error?)
                                                                        cause-type
                                                                        :db-sync/tx-entry-failed)
                                                                :successful-tx-ids successful-tx-ids}
                                                         tx-id (assoc :failed-tx-id tx-id)
                                                         (seq missing-block-uuids)
                                                         (assoc :missing-block-uuids missing-block-uuids))
                                                       e))))))
                _ (storage/advance-snapshot-integrity-write-chain! sql)
                next-successful-tx-ids (cond-> successful-tx-ids
                                         tx-id (conj tx-id))]
            (recur (next remaining)
                   (or applied? applied-entry?)
                   next-successful-tx-ids))
          (let [new-t (storage/get-t sql)]
            {:t new-t
             :applied? applied?
             :successful-tx-ids successful-tx-ids}))))))

(defn handle-tx-batch!
  ([^js self sender txs t-before]
   (handle-tx-batch! self sender txs t-before nil))
  ([^js self sender txs t-before request-context]
   (let [integrity-error
         (when (and (snapshot-upload-finished? self)
                    (not (storage/snapshot-integrity-attested?
                          (.-sql self))))
           (try
             ;; Production HTTP/WS entry points await the D1-capable
             ;; single-flight first. Keep direct/internal callers safe too:
             ;; a complete legacy graph can bootstrap synchronously, while a
             ;; missing authoritative KV remains fail-closed.
             (ensure-conn! self)
             (prepare-live-snapshot! self nil)
             (when-not (storage/snapshot-integrity-attested?
                        (.-sql self))
               (throw (ex-info "snapshot integrity bootstrap failed"
                               {:type
                                :db-sync/snapshot-write-integrity-invalid})))
             nil
             (catch :default error
               error)))
         current-t (t-now self)]
     (cond
       (not (snapshot-upload-finished? self))
       {:type "tx/reject"
        :reason "snapshot upload in progress"
        :t current-t}

       integrity-error
       {:type "tx/reject"
        :reason "snapshot integrity unavailable"
        :error-detail (str (or (:type (ex-data integrity-error))
                               :db-sync/snapshot-write-integrity-invalid))
        :t current-t}

       (or (not (number? t-before)) (neg? t-before))
       {:type "tx/reject"
        :reason "invalid t-before"}

       (not= t-before current-t)
       {:type "tx/reject"
        :reason "stale"
        :t current-t}

       :else
       (if (seq txs)
         (try
           (let [{:keys [tx-entries applied-identities]}
                 (prepare-tx-batch (.-sql self) txs)]
             ;; Migrate persisted checksum metadata before extending it. Older
             ;; deployed servers did not write the verification watermark, so a
             ;; first HTTP batch is just as safe as a hello/pull-first session.
             (verify-current-checksums! self)
             (let [integrity-chain
                   (storage/validated-snapshot-integrity-write-chain
                    (.-sql self))
                   _ (when-not integrity-chain
                       (throw (ex-info
                               "snapshot integrity changed before batch apply"
                               {:type
                                :db-sync/snapshot-write-integrity-invalid})))
                   result
                   (binding [storage/*snapshot-integrity-write-chain*
                             integrity-chain]
                     (apply-tx! self tx-entries applied-identities
                                request-context))
                   {:keys [t applied? successful-tx-ids]} result]
               (when applied?
                 ;; Broadcast once per processed batch after tx-log/checksum settle.
                 (ws/broadcast! self sender {:type "changed" :t t}))
               (merge (cond-> {:type "tx/batch/ok"
                               :t t
                               :capabilities server-capabilities}
                        (:canonical-ack? request-context)
                        (assoc
                         :canonical-basis-t t-before
                         :canonical-txs
                         (storage/fetch-canonical-tx-range
                          (.-sql self) t-before t)
                         :canonical-tx-ids successful-tx-ids))
                      (checksum-response-fields self))))
           (catch :default e
             (let [new-t (t-now self)
                   error-data (or (ex-data e) {})
                   {:keys [successful-tx-ids failed-tx-id missing-block-uuids]}
                   error-data
                   error-code (:type error-data)
                   error-detail (if (keyword? error-code)
                                  (str error-code)
                                  ":db-sync/transact-failed")]
               (log/error :db-sync/transact-failed
                          (common/error-log-data e))
               (when (> new-t current-t)
                 ;; Broadcast once when partial batch writes advanced the graph.
                 (ws/broadcast! self sender {:type "changed" :t new-t}))
               (merge
                (cond-> {:type "tx/reject"
                         :reason "db transact failed"
                         :error-detail error-detail
                         :t new-t
                         :capabilities server-capabilities}
                  (seq successful-tx-ids) (assoc :success-tx-ids successful-tx-ids)
                  failed-tx-id (assoc :failed-tx-id failed-tx-id)
                  (seq missing-block-uuids) (assoc :missing-block-uuids missing-block-uuids))
                (checksum-response-fields self)))))
         {:type "tx/reject"
          :reason "empty tx data"})))))

(declare snapshot-integrity-error-response)

(defn- handle-sync-pull
  [^js self ^js url]
  (let [raw-since (.get (.-searchParams url) "since")
        since (if (some? raw-since) (parse-int raw-since) 0)
        graph-id (.get (.-searchParams url) "graph-id")]
    (if (or (and (some? raw-since) (not (number? since))) (neg? since))
      (http/bad-request "invalid since")
      (->
       (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
         (if-not ready-for-sync?
           (http/error-response "graph not ready" 409)
           (let [floor (storage/tx-log-floor (.-sql self))]
             (cond
               (not (number? floor))
               (http/error-response "snapshot history unavailable" 409)

               (and (some? raw-since) (< since floor))
               (http/error-response "snapshot required" 409)

               :else
               ;; A pull without an explicit cursor is a metadata/watermark
               ;; request in the snapshot download workflow. Start at the
               ;; retained history floor rather than fabricating pre-snapshot
               ;; transaction history.
               (http/json-response
                :sync/pull
                (pull-response
                 self (if (some? raw-since) since floor)))))))
       (p/catch snapshot-integrity-error-response)))))

(defn- normalize-diagnostic-block
  [{:keys [block/uuid block/parent block/page block/order] :as block}]
  (cond-> block
    uuid (assoc :block/uuid (str uuid))
    parent (assoc :block/parent (str parent))
    page (assoc :block/page (str page))
    order (assoc :block/order order)))

(defn- checksum-diagnostics-response
  [^js self]
  (ensure-conn! self)
  (-> (sync-checksum/recompute-checksum-diagnostics @(.-conn self))
      (update :blocks (fn [blocks]
                        (mapv normalize-diagnostic-block blocks)))))

(defn- handle-sync-checksum-diagnostics
  [^js self request]
  (let [graph-id (graph-id-from-request request)]
    (if (not (seq graph-id))
      (http/bad-request "missing graph id")
      (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
        (if-not ready-for-sync?
          (http/error-response "graph not ready" 409)
          (http/json-response :sync/checksum-diagnostics
                              (checksum-diagnostics-response self)))))))

(defn- large-title-marker-state-response
  [^js self]
  (ensure-conn! self)
  (let [db @(.-conn self)
        t (t-now self)
        checksum (current-checksum self)
        server-checksum (current-server-checksum self)
        markers (sync-checksum/server-large-title-markers db)]
    (when (and (string? checksum)
               (string? server-checksum)
               (vector? markers))
      {:t t
       :checksum checksum
       :checksum-version sync-checksum/server-checksum-version
       :server-checksum server-checksum
       :large-title-markers
       (mapv (fn [{:keys [block-uuid marker]}]
               {:block-uuid (str block-uuid)
                :marker marker})
             markers)})))

(defn- handle-sync-large-title-markers
  [^js self request]
  (let [graph-id (graph-id-from-request request)]
    (if (not (seq graph-id))
      (http/bad-request "missing graph id")
      (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
        (if-not ready-for-sync?
          (http/error-response "graph not ready" 409)
          (if-let [response (large-title-marker-state-response self)]
            (http/json-response :sync/large-title-markers response)
            (http/error-response "versioned checksum unavailable" 409)))))))

(defn- snapshot-integrity-error-response
  [error]
  (case (:type (ex-data error))
    :db-sync/snapshot-download-capacity
    (http/error-response "snapshot download busy; retry later" 429)

    :db-sync/snapshot-download-queue-inconsistent
    (http/error-response "snapshot download queue inconsistent" 409)

    :db-sync/snapshot-upload-in-progress
    (http/error-response "snapshot upload already in progress" 409)

    :db-sync/system-kv-integrity
    (http/error-response "snapshot system metadata incomplete" 409)

    :db-sync/snapshot-index-inconsistent
    (http/error-response "snapshot index inconsistent" 409)

    :db-sync/snapshot-repair-not-idempotent
    (http/error-response "snapshot index inconsistent" 409)

    :db-sync/live-snapshot-not-canonical
    (http/error-response "snapshot index inconsistent" 409)

    :db-sync/snapshot-write-integrity-invalid
    (http/error-response "snapshot integrity unavailable" 409)

    :db-sync/tx-log-integrity
    (http/error-response "snapshot transaction history inconsistent" 409)

    (throw error)))

(defn- handle-sync-snapshot-stream
  [^js self request frozen?]
  (ensure-schema! self)
  (let [graph-id (graph-id-from-request request)]
    (if (not (seq graph-id))
      (http/bad-request "missing graph id")
      (try
        (let [sql (.-sql self)
              url (js/URL. (.-url request))
              requested-download-id
              (.get (.-searchParams url) "download-id")
              legacy-claim
              (when-not frozen?
                (claim-legacy-snapshot-download! self))
              download-id
              (if frozen?
                requested-download-id
                (:download-id legacy-claim))
              stored-download-row
              (if frozen?
                (when (seq download-id)
                  (snapshot-download-row sql download-id))
                (:row legacy-claim))
              expired?
              (and stored-download-row
                   (< (or (aget stored-download-row "created_at") 0)
                      (- (js/Date.now) snapshot-download-retention-ms)))
              _ (when expired?
                  (delete-snapshot-download! sql download-id))
              active-download-row (when-not expired? stored-download-row)]
          (if (or (not (seq download-id))
                  (nil? active-download-row))
            (http/error-response "snapshot download expired" 410)
            (let [gzip? (and (snapshot-stream-gzip-enabled? self)
                             (exists? js/CompressionStream))
                  row-count (or (aget active-download-row "row_count") 0)
                  stream
                  (cond-> (snapshot-export-stream
                           self download-id row-count)
                    gzip?
                    (maybe-compress-stream))
                  headers
                  (cond-> {"content-type" snapshot-content-type}
                    gzip?
                    (assoc "content-encoding" snapshot-content-encoding))]
              (js/Response.
               stream
               #js {:status 200
                    :headers (js/Object.assign
                              (clj->js headers)
                              #js {"x-snapshot-row-count" (str row-count)}
                              (common/cors-headers))}))))
        (catch :default error
          (snapshot-integrity-error-response error))))))

(defn- snapshot-download-response
  [^js self request graph-id frozen?
   {:keys [download-id t checksum row-count]}]
  (let [key (str "stream/" graph-id ".snapshot")
        url (snapshot-stream-url request graph-id download-id frozen?)
        content-encoding
        (when (and (snapshot-stream-gzip-enabled? self)
                   (exists? js/CompressionStream))
          snapshot-content-encoding)
        response (cond-> {:ok true
                          :key key
                          :url url}
                   frozen?
                   (assoc :t t
                          :row-count row-count)
                   (and frozen? (string? checksum))
                   (assoc :checksum checksum)
                   content-encoding
                   (assoc :content-encoding content-encoding))]
    (http/json-response :sync/snapshot-download response)))

(defn- validated-pre-system-kv-snapshot-basis!
  [^js self]
  (ensure-conn! self)
  (let [conn (.-conn self)]
    (when (graph-created-at-valid? @conn)
      (throw (ex-info "legacy fallback precondition changed"
                      {:type :db-sync/snapshot-write-integrity-invalid})))
    ;; This path never repairs or seals live state. It accepts only an
    ;; already-consistent legacy index/reference closure and returns a receipt
    ;; for that exact raw root. v2 uses the same classifier before emitting its
    ;; 404, so a missing KV can never mask an unrelated tear.
    (snapshot-integrity/validate-legacy-indexes-without-created-at! conn)
    (legacy-pre-system-kv-snapshot-basis (.-sql self))))

(defn- legacy-pre-system-kv-download-response
  [^js self request graph-id]
  (try
    (let [basis (validated-pre-system-kv-snapshot-basis! self)]
      (snapshot-download-response
       self request graph-id false
       (create-legacy-pre-system-kv-snapshot-download! self basis)))
    (catch :default error
      (snapshot-integrity-error-response error))))

(defn- snapshot-download-error-response
  [^js self request graph-id frozen? error]
  (if (= :db-sync/system-kv-integrity (:type (ex-data error)))
    (if frozen?
      (try
        (validated-pre-system-kv-snapshot-basis! self)
        ;; selfhost.5 falls back to v1 only on 404. A 409 here strands a
        ;; healthy pre-system-KV graph even though its old wire is replayable.
        (http/error-response "versioned snapshot unavailable" 404)
        (catch :default validation-error
          (snapshot-integrity-error-response validation-error)))
      (legacy-pre-system-kv-download-response self request graph-id))
    (snapshot-integrity-error-response error)))

(defn- handle-sync-snapshot-download
  [^js self request frozen?]
  (let [graph-id (graph-id-from-request request)]
    (cond
      (not (seq graph-id))
      (http/bad-request "missing graph id")

      :else
      (->
       (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
         (if-not ready-for-sync?
           (http/error-response "graph not ready" 409)
           (p/let [_ (<prepare-live-snapshot! self graph-id)
                   download
                   (create-snapshot-download! self (not frozen?))]
             (snapshot-download-response
              self request graph-id frozen? download))))
       (p/catch
        (fn [error]
          (snapshot-download-error-response
           self request graph-id frozen? error)))))))

(defn- handle-sync-snapshot-download-cancel
  [^js self request]
  (let [graph-id (graph-id-from-request request)
        url (js/URL. (.-url request))
        download-id (.get (.-searchParams url) "download-id")]
    (cond
      (not (seq graph-id))
      (http/bad-request "missing graph id")

      (not (seq download-id))
      (http/bad-request "missing download id")

      :else
      (do
        (ensure-schema! self)
        (delete-snapshot-download! (.-sql self) download-id)
        (http/json-response :sync/snapshot-download-cancel {:ok true})))))

(defn- handle-sync-admin-reset
  [^js self]
  (let [^js state (.-state self)
        ^js storage (.-storage state)
        delete-all (.-deleteAll storage)
        delete-alarm (.-deleteAlarm storage)
        finish-reset! (fn [schema-ready?]
                        (set! (.-schema-ready self) schema-ready?)
                        (set! (.-conn self) nil)
                        (doseq [^js ws (.getWebSockets state)]
                          (.close ws 1000 "graph deleted"))
                        (http/json-response :sync/admin-reset {:ok true}))]
    (p/let [_ (when-let [bootstrap
                         (aget self "snapshotIntegrityBootstrap")]
                bootstrap)
            _ (when (fn? delete-alarm)
                (.deleteAlarm storage))]
      (if (fn? delete-all)
        (p/let [_ (.deleteAll storage)]
          (finish-reset! false))
        (do
          (storage/with-sql-transaction!
            (.-sql self)
            (fn []
              (common/sql-exec (.-sql self) "drop table if exists kvs")
              (common/sql-exec (.-sql self) "drop table if exists snapshot_kvs_staging")
              (common/sql-exec (.-sql self) "drop table if exists snapshot_kvs_exports")
              (common/sql-exec (.-sql self) "drop table if exists snapshot_download_generations")
              (common/sql-exec (.-sql self) "drop table if exists snapshot_downloads")
              (common/sql-exec (.-sql self) "drop table if exists tx_log")
              (common/sql-exec (.-sql self) "drop table if exists applied_client_txs")
              (common/sql-exec (.-sql self) "drop table if exists client_tx_upload_chunks")
              (common/sql-exec (.-sql self) "drop table if exists client_tx_uploads")
              (common/sql-exec (.-sql self) "drop table if exists sync_meta")
              (common/sql-exec (.-sql self) "drop table if exists sync_meta_mutation_generations")
              (common/sql-exec (.-sql self) "drop table if exists integrity_attestations")
              (common/sql-exec (.-sql self) "drop table if exists kvs_mutation_generations")
              (storage/init-schema! (.-sql self))))
          (finish-reset! true))))))

(defn- handle-sync-tx-batch
  [^js self request]
  ;; A Workers-compatible body parser may expose a generic thenable whose
  ;; `.then` schedules callbacks but does not itself return a chained Promise.
  ;; Assimilate it first so the fetch lifecycle cannot return before the DB
  ;; callback settles (and before a test/runtime is allowed to release SQL).
  (p/let [result (js/Promise.resolve (common/read-json request))]
    (if (nil? result)
      (http/bad-request "missing body")
      (let [body (js->clj result :keywordize-keys true)
            body (http/coerce-http-request :sync/tx-batch body)
            graph-id (graph-id-from-request request)]
        (if (nil? body)
          (http/bad-request "invalid tx")
          (let [{:keys [client-revision canonical-ack? txs t-before]} body
                t-before (parse-int t-before)]
            (if (sequential? txs)
              (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
                (if-not ready-for-sync?
                  (http/error-response "graph not ready" 409)
                  (http/json-response :sync/tx-batch
                                      (handle-tx-batch! self nil txs t-before
                                                        {:graph-id graph-id
                                                         :client-revision client-revision
                                                         :canonical-ack? canonical-ack?}))))
              (http/bad-request "invalid tx"))))))))

(defn- parse-reset-param
  [value]
  (if (nil? value)
    true
    (not (contains? #{"false" "0"} value))))

(defn- parse-finished-param
  [value]
  (contains? #{"true" "1"} value))

(defn- sqlite-too-big-error?
  [error]
  (let [message (-> (or (ex-message error)
                        (some-> error .-message)
                        (str error))
                    string/lower-case)]
    (or (string/includes? message "sqlite_toobig")
        (string/includes? message "string or blob too big")
        (string/includes? message "statement too long"))))

(defn- snapshot-upload-session-replaced?
  [error]
  (= :db-sync/snapshot-upload-session-replaced
     (:type (ex-data error))))

(defn- snapshot-upload-error-requires-recovery?
  [error]
  (not (contains?
        #{:db-sync/snapshot-upload-session-replaced
          :db-sync/snapshot-upload-already-committed
          :db-sync/snapshot-upload-committed-mismatch
          :db-sync/snapshot-upload-in-progress
          :db-sync/legacy-snapshot-upload-in-progress
          :db-sync/legacy-snapshot-upload-session-missing
          :db-sync/snapshot-upload-waiting-for-downloads
          :db-sync/snapshot-upload-state-inconsistent}
        (:type (ex-data error)))))

(defn- handle-sync-snapshot-upload
  [^js self request url staged?]
  (let [graph-id (graph-id-from-request request)
        reset-param (.get (.-searchParams url) "reset")
        reset? (parse-reset-param reset-param)
        finished-param (.get (.-searchParams url) "finished")
        finished? (parse-finished-param finished-param)
        checksum-param (.get (.-searchParams url) "checksum")
        row-count-param (.get (.-searchParams url) "row-count")
        expected-row-count (when (and (string? row-count-param)
                                      (re-matches #"[0-9]+" row-count-param))
                             (parse-int row-count-param))
        upload-id-param (.get (.-searchParams url) "upload-id")
        upload-id (if staged?
                    upload-id-param
                    legacy-snapshot-upload-id)
        req-encoding (.get (.-headers request) "content-encoding")]
    (cond
      (not (seq graph-id))
      (http/bad-request "missing graph id")

      (and staged? (not (valid-snapshot-upload-id? upload-id)))
      (http/bad-request "invalid upload id")

      (and staged? finished?
           (not (sync-checksum/valid-checksum? checksum-param)))
      (http/bad-request "invalid checksum")

      (and staged? finished? (nil? expected-row-count))
      (http/bad-request "invalid row count")

      (nil? (.-body request))
      (http/bad-request "missing body")

      :else
      (let [stream (.-body request)
            encoding (or req-encoding "")
            request-upload-id* (volatile! (when staged? upload-id))]
        (if (and (= encoding snapshot-content-encoding)
                 (not (exists? js/DecompressionStream)))
          (http/error-response "gzip not supported" 500)
          (p/catch
           (p/let [_ (when-let [bootstrap
                                (aget self "snapshotIntegrityBootstrap")]
                        bootstrap)
                   _ (ensure-schema! self)
                   sql (.-sql self)
                   _ (require-valid-snapshot-upload-state! sql)
                   _ (when (and (not staged?)
                                reset?
                                (= snapshot-upload-status-active
                                   (:status (snapshot-upload-session sql))))
                       ;; A v2 request may have reached a new server before a
                       ;; rolling-deployment fallback restarts the complete
                       ;; upload through v1. Abort the isolated staging session
                       ;; before v1 resets the live snapshot.
                       (abort-staged-snapshot-upload! sql))
                   legacy-upload-id
                   (when-not staged?
                     (if reset?
                       ;; The v1 wire protocol has no upload id. Supported
                       ;; clients await every chunk before sending the next, so
                       ;; the only stale request after a retry is an already
                       ;; executing handler. Give each reset an internal,
                       ;; durable generation so that handler cannot write after
                       ;; the retry replaces its staging session.
                       (start-legacy-snapshot-upload! sql)
                       (if (active-legacy-snapshot-upload? sql)
                         (:upload-id (snapshot-upload-session sql))
                         (throw
                          (ex-info "legacy snapshot upload session missing"
                                   {:type
                                    :db-sync/legacy-snapshot-upload-session-missing})))))
                   effective-upload-id (or legacy-upload-id upload-id)
                   _ (vreset! request-upload-id* effective-upload-id)
                   stream (maybe-decompress-stream stream encoding)
                   count
                   (if staged?
                     (p/let [activation-receipt
                             (snapshot-upload-activation-receipt sql)
                             same-committed-upload?
                             (= upload-id (:upload-id activation-receipt))
                             committed-retry?
                             (and same-committed-upload?
                                  finished?
                                  (= checksum-param
                                     (:checksum activation-receipt))
                                  (= expected-row-count
                                     (:row-count activation-receipt))
                                  (snapshot-upload-finished? self)
                                  (storage/snapshot-integrity-attested? sql))
                             _ (when (and same-committed-upload?
                                          (not committed-retry?))
                                 (throw
                                  (ex-info
                                   "committed snapshot retry does not match activation receipt"
                                   {:type
                                    :db-sync/snapshot-upload-committed-mismatch
                                    :upload-id upload-id})))
                             _ (when (and reset? (not committed-retry?))
                                 (start-snapshot-upload-session! sql upload-id))
                             status (require-snapshot-upload-session! sql upload-id)
                             committed? (= snapshot-upload-status-committed status)
                             _ (when (and committed? (not finished?))
                                 (throw (ex-info "snapshot upload already committed"
                                                 {:type :db-sync/snapshot-upload-already-committed
                                                  :upload-id upload-id})))
                             count (if committed?
                                     0
                                     (import-snapshot-stream!
                                      self
                                      stream
                                      false
                                      (fn [rows _reset?]
                                        (import-staged-snapshot-rows!
                                         sql upload-id rows))))
                             _ (when-not committed?
                                 (refresh-snapshot-upload-lease! sql upload-id))
                             _ (when (and finished? (not committed?))
                                 ;; The upload gate already blocks live writes.
                                 ;; Release the old root before opening staging
                                 ;; for validation/replay.
                                 (set! (.-conn self) nil))
                             _ (when (and finished? (not committed?))
                                 (p/delay 0))
                             trusted-graph-created-at
                             (when (and finished? (not committed?))
                               (<trusted-graph-created-at
                                self graph-id
                                (storage/open-snapshot-conn
                                 sql snapshot-staging-table)))
                             _ (when (and finished? (not committed?))
                                 (commit-staged-snapshot!
                                  self upload-id checksum-param
                                  expected-row-count
                                  trusted-graph-created-at))
                             _ (when finished?
                                 (cleanup-staged-snapshot! sql upload-id))]
                       count)
                     (p/let [count
                             (import-snapshot-stream!
                              self stream false
                              (fn [rows _reset?]
                                (import-staged-snapshot-rows!
                                 sql effective-upload-id rows)))
                             _ (when finished?
                                 (set! (.-conn self) nil))
                             _ (when finished? (p/delay 0))
                             trusted-graph-created-at
                             (when finished?
                               (<trusted-graph-created-at
                                self graph-id
                                (storage/open-snapshot-conn
                                 sql snapshot-staging-table)))
                             _ (when finished?
                                 (commit-staged-snapshot!
                                  self effective-upload-id
                                  checksum-param nil
                                  trusted-graph-created-at))
                             _ (when finished?
                                 (cleanup-staged-snapshot!
                                  sql effective-upload-id))]
                       count))
                   _ (when finished?
                       (<set-graph-ready-for-use! self graph-id true))]
             (http/json-response :sync/snapshot-upload
                                 {:ok true :count count}))
           (fn [error]
             ;; Cleanup and reopening are independent recovery steps. A
             ;; transient failure in either must not skip the other, while a
             ;; replaced generation remains entirely untouched.
             (let [cleanup-result
                   (when (snapshot-upload-error-requires-recovery? error)
                     (try-upload-recovery-step-twice
                      #(recover-snapshot-upload-if-current!
                        (.-sql self) @request-upload-id*)))
                   reopen-result
                   (when (and finished? (nil? (.-conn self)))
                     (try-upload-recovery-step-twice
                      #(ensure-conn! self)))
                   cleanup-error
                   (when (and cleanup-result (not (:ok? cleanup-result)))
                     (:error cleanup-result))
                   reopen-error
                   (when (and reopen-result (not (:ok? reopen-result)))
                     (:error reopen-result))]
               (when (or cleanup-error reopen-error)
                 (throw
                  (ex-info
                   "snapshot upload failure recovery failed"
                   {:type :db-sync/snapshot-upload-recovery-failed
                    :upload-id @request-upload-id*
                    :cleanup-failed? (some? cleanup-error)
                    :reopen-failed? (some? reopen-error)}
                   (or cleanup-error reopen-error))))
               (cond
               (sqlite-too-big-error? error)
               (http/error-response "snapshot row too large" 413)

               (snapshot-upload-session-replaced? error)
               (http/error-response "snapshot upload session replaced" 409)

               (= :db-sync/snapshot-upload-already-committed (:type (ex-data error)))
               (http/error-response "snapshot upload already committed" 409)

               (= :db-sync/snapshot-upload-committed-mismatch
                  (:type (ex-data error)))
               (http/error-response
                "snapshot upload retry does not match committed snapshot" 409)

               (= :db-sync/snapshot-upload-in-progress (:type (ex-data error)))
               (http/error-response "snapshot upload already in progress" 409)

               (= :db-sync/legacy-snapshot-upload-in-progress
                  (:type (ex-data error)))
               (http/error-response
                "legacy snapshot upload already in progress" 409)

               (= :db-sync/legacy-snapshot-upload-session-missing
                  (:type (ex-data error)))
               (http/error-response
                "legacy snapshot upload session missing" 409)

               (= :db-sync/snapshot-upload-state-inconsistent
                  (:type (ex-data error)))
               (http/error-response
                "snapshot upload state inconsistent" 409)

               (= :db-sync/snapshot-upload-waiting-for-downloads
                  (:type (ex-data error)))
               (http/error-response "snapshot download in progress" 409)

               (= :db-sync/snapshot-checksum-mismatch (:type (ex-data error)))
               (http/error-response "snapshot checksum mismatch" 409)

               (= :db-sync/snapshot-row-count-mismatch (:type (ex-data error)))
               (http/error-response "snapshot row count mismatch" 409)

               (= :db-sync/system-kv-integrity (:type (ex-data error)))
               (http/error-response
                "snapshot system metadata incomplete" 409)

               (contains? #{:db-sync/snapshot-index-inconsistent
                            :db-sync/snapshot-repair-not-idempotent
                            :db-sync/live-snapshot-not-canonical}
                          (:type (ex-data error)))
               (http/error-response
               "snapshot index inconsistent" 409)

               :else
               (throw error))))))))))

(defn handle [{:keys [^js self request url route]}]
  (case (:handler route)
    :sync/health
    (http/json-response :sync/health {:ok true})

    :sync/pull
    (handle-sync-pull self url)

    :sync/checksum-diagnostics
    (handle-sync-checksum-diagnostics self request)

    :sync/large-title-markers
    (handle-sync-large-title-markers self request)

    :sync/snapshot-stream
    (handle-sync-snapshot-stream self request false)

    :sync/snapshot-stream-v2
    (handle-sync-snapshot-stream self request true)

    :sync/snapshot-download
    (handle-sync-snapshot-download self request false)

    :sync/snapshot-download-v2
    (handle-sync-snapshot-download self request true)

    :sync/snapshot-download-v2-cancel
    (handle-sync-snapshot-download-cancel self request)

    :sync/admin-reset
    (handle-sync-admin-reset self)

    :sync/tx-batch
    (handle-sync-tx-batch self request)

    :sync/snapshot-upload
    (handle-sync-snapshot-upload self request url false)

    :sync/snapshot-upload-v2
    (handle-sync-snapshot-upload self request url true)

    (http/not-found)))

(defn- strip-sync-prefix [path]
  (if (string/starts-with? path "/sync/")
    (let [rest-path (subs path (count "/sync/"))
          slash-idx (string/index-of rest-path "/")]
      (if (neg? slash-idx)
        "/"
        (subs rest-path slash-idx)))
    path))

(defn handle-http [^js self request]
  (letfn [(with-cors-error [resp]
            (if (and (some? resp)
                     (fn? (.-then resp)))
              (.catch (js/Promise.resolve resp)
                      (fn [e]
                        (log/error :db-sync/http-error
                                   (common/error-log-data e))
                        (http/error-response "server error" 500)))
              resp))]
    (try
      (let [url (js/URL. (.-url request))
            raw-path (.-pathname url)
            path (strip-sync-prefix raw-path)
            method (.-method request)]
        (with-cors-error
          (cond
            (= method "OPTIONS")
            (common/options-response)

            (and (= method "POST")
                 (= raw-path "/internal/revoke-user"))
            (if-let [user-id (.get (.-searchParams url) "user-id")]
              (do
                (presence/revoke-user! self user-id)
                (http/json-response :ok {:ok true}))
              (http/bad-request "missing user id"))

            :else
            (if-let [route (or (sync-routes/match-route method path)
                               (semantic-routes/match-internal method path))]
              (if (string/starts-with? path "/semantic/")
                (if (contains? #{"POST" "PUT" "PATCH" "DELETE"} method)
                  (let [graph-id (graph-id-from-request request)]
                    (if-not (seq graph-id)
                      (http/bad-request "missing graph id")
                      (->
                       (p/let [_ (<prepare-live-write! self graph-id)]
                         (semantic-handler/handle
                          {:self self
                           :request request
                           :url url
                           :route route
                           :write! (semantic-write-executor self)}))
                       (p/catch snapshot-integrity-error-response))))
                  (semantic-handler/handle
                   {:self self :request request :url url :route route}))
              (handle {:self self
                       :request request
                       :url url
                       :route route}))
              (http/not-found)))))
      (catch :default e
        (log/error :db-sync/http-error (common/error-log-data e))
        (http/error-response "server error" 500)))))
