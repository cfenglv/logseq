(ns logseq.db-sync.worker.handler.sync
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [logseq.db-sync.batch :as batch]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.snapshot :as snapshot]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.tx-sanitize :as tx-sanitize]
            [logseq.db-sync.worker.http :as http]
            [logseq.db-sync.worker.handler.semantic :as semantic-handler]
            [logseq.db-sync.worker.presence :as presence]
            [logseq.db-sync.worker.routes.semantic :as semantic-routes]
            [logseq.db-sync.worker.routes.sync :as sync-routes]
            [logseq.db-sync.worker.ws :as ws]
            [promesa.core :as p]))

(def ^:private snapshot-download-batch-size 256)
(def ^:private snapshot-download-frame-max-bytes (* 1024 1024))
(def ^:private snapshot-download-retention-ms (* 10 60 1000))
(def ^:private snapshot-download-max-active 2)
;; (def ^:private snapshot-cache-control "private, max-age=300")
(def ^:private snapshot-content-type "application/transit+json")
(def ^:private snapshot-content-encoding "gzip")
(def ^:private snapshot-uploading-meta-key :snapshot-uploading?)
(def ^:private snapshot-upload-id-meta-key :snapshot-upload-id)
(def ^:private snapshot-upload-status-meta-key :snapshot-upload-status)
(def ^:private snapshot-upload-started-at-meta-key :snapshot-upload-started-at)
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

(defn parse-int [value]
  (when (some? value)
    (let [n (js/parseInt value 10)]
      (when-not (js/isNaN n)
        n))))

(defn- ensure-schema! [^js self]
  (when-not (true? (.-schema-ready self))
    (try
      (storage/init-schema! (.-sql self))
      (catch :default e
        ;; Schema may already exist. If DDL writes are rejected, probe
        ;; existing tables before deciding this is a fatal error.
        (try
          (common/sql-exec (.-sql self) "select 1 from kvs limit 1")
          (common/sql-exec (.-sql self) "select 1 from snapshot_kvs_staging limit 1")
          (common/sql-exec (.-sql self) "select 1 from snapshot_downloads limit 1")
          (common/sql-exec (.-sql self) "select 1 from snapshot_kvs_exports limit 1")
          (common/sql-exec (.-sql self) "select 1 from tx_log limit 1")
          (common/sql-exec (.-sql self) "select 1 from sync_meta limit 1")
          (catch :default _
            (throw e)))))
    (set! (.-schema-ready self) true)))

(defn- ensure-conn! [^js self]
  (ensure-schema! self)
  (when-not (.-conn self)
    (set! (.-conn self)
          (storage/open-conn (.-sql self)))))

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
  (not= "true" (storage/get-meta (.-sql self) snapshot-uploading-meta-key)))

(defn <ready-for-sync?
  [^js self graph-id]
  (if-not (snapshot-upload-finished? self)
    (p/resolved false)
    (if-let [db (some-> self .-env (aget "DB"))]
      (p/let [graph-ready-for-use? (index/<graph-ready-for-use? db graph-id)]
        (not= false graph-ready-for-use?))
      (p/resolved true))))

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
  (common/sql-exec sql "delete from sync_meta")
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

(defn- delete-snapshot-download!
  [sql download-id]
  (storage/with-sql-transaction!
   sql
   (fn []
     (common/sql-exec sql
                      "delete from snapshot_kvs_exports where download_id = ?"
                      download-id)
     (common/sql-exec sql
                      "delete from snapshot_downloads where download_id = ?"
                      download-id))))

(defn- cleanup-expired-snapshot-downloads!
  [sql now]
  (let [expires-before (- now snapshot-download-retention-ms)]
    (storage/with-sql-transaction!
     sql
     (fn []
       (common/sql-exec
        sql
        (str "delete from snapshot_kvs_exports where download_id in "
             "(select download_id from snapshot_downloads where created_at < ?)")
        expires-before)
       (common/sql-exec sql
                        "delete from snapshot_downloads where created_at < ?"
                        expires-before)))))

(defn- create-snapshot-download!
  [^js self]
  ;; A frozen snapshot and its checksum must come from the same verified DB
  ;; state. This also repairs persisted same-cursor drift before the checksum is
  ;; copied into snapshot_downloads.
  (verify-current-checksums! self)
  (let [sql (.-sql self)
        download-id (str (random-uuid))
        now (js/Date.now)]
    (cleanup-expired-snapshot-downloads! sql now)
    (storage/with-sql-transaction!
     sql
     (fn []
       (let [active-count (or (some-> (common/sql-exec
                                       sql
                                       "select count(*) as active_count from snapshot_downloads")
                                      common/get-sql-rows
                                      first
                                      (aget "active_count"))
                              0)
             _ (when (>= active-count snapshot-download-max-active)
                 (throw (ex-info "too many active snapshot downloads"
                                 {:type :db-sync/snapshot-download-capacity
                                  :active-count active-count})))
             t (storage/get-t sql)
             checksum (storage/get-checksum sql)
             row-count (snapshot-row-count sql)]
         (common/sql-exec
          sql
          (str "insert into snapshot_downloads "
               "(download_id, t, checksum, row_count, created_at) values (?, ?, ?, ?, ?)")
          download-id
          t
          checksum
          row-count
          now)
         (common/sql-exec
          sql
          (str "insert into snapshot_kvs_exports (download_id, addr, content, addresses) "
               "select ?, addr, content, addresses from kvs")
          download-id)
         {:download-id download-id
          :t t
          :checksum checksum
          :row-count row-count})))))

(defn- snapshot-download-row
  [sql download-id]
  (first
   (common/get-sql-rows
    (common/sql-exec
     sql
     (str "select download_id, t, checksum, row_count, created_at "
          "from snapshot_downloads where download_id = ?")
     download-id))))

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

(defn- snapshot-export-stream
  ([self]
   (snapshot-export-stream self nil))
  ([^js self download-id]
  (ensure-schema! self)
  (let [sql (.-sql self)
        last-addr (volatile! -1)
        pending-batches (volatile! [])
        cleaned? (volatile! false)
        cleanup! (fn []
                   (when (and download-id (not @cleaned?))
                     (vreset! cleaned? true)
                     (delete-snapshot-download! sql download-id)))]
    (js/ReadableStream.
     (clj->js
      {:pull (fn [controller]
               (let [pending (if (seq @pending-batches)
                               @pending-batches
                               (let [batch (if download-id
                                             (fetch-snapshot-export-rows
                                              sql download-id @last-addr snapshot-download-batch-size)
                                             (fetch-snapshot-kvs-rows
                                              sql @last-addr snapshot-download-batch-size))]
                                 (when (seq batch)
                                   (vreset! last-addr (first (peek batch))))
                                 (if (seq batch) [batch] [])))]
                 (if-let [{:keys [payload pending]} (next-snapshot-frame pending)]
                   (do
                     (vreset! pending-batches pending)
                     (.enqueue controller (frame-bytes payload)))
                   (do
                     (cleanup!)
                     (.close controller)))))
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

(defn pull-response [^js self since]
  (let [sql (.-sql self)
        txs (storage/fetch-tx-since sql since)]
    (merge {:type "pull/ok"
            :t (t-now self)
            :txs txs}
           (checksum-response-fields self))))

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
  {:upload-id (storage/get-meta sql snapshot-upload-id-meta-key)
   :status (storage/get-meta sql snapshot-upload-status-meta-key)})

(defn- snapshot-upload-session-error
  [expected-upload-id actual-upload-id]
  (ex-info "snapshot upload session replaced"
           {:type :db-sync/snapshot-upload-session-replaced
            :expected-upload-id expected-upload-id
            :actual-upload-id actual-upload-id}))

(defn- require-snapshot-upload-session!
  [sql upload-id]
  (let [{current-upload-id :upload-id
         status :status} (snapshot-upload-session sql)]
    (when-not (= upload-id current-upload-id)
      (throw (snapshot-upload-session-error upload-id current-upload-id)))
    status))

(defn- start-snapshot-upload-session!
  [sql upload-id]
  (storage/with-sql-transaction!
   sql
   (fn []
     (common/sql-exec sql (str "delete from " snapshot-staging-table))
     (storage/set-meta! sql snapshot-upload-id-meta-key upload-id)
     (storage/set-meta! sql snapshot-upload-status-meta-key snapshot-upload-status-active)
     (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now))
     (storage/set-meta! sql snapshot-uploading-meta-key true))))

(defn- active-legacy-snapshot-upload?
  [sql]
  (let [{:keys [status]} (snapshot-upload-session sql)
        uploading? (= "true"
                      (storage/get-meta sql snapshot-uploading-meta-key))]
    (and uploading?
         (not= snapshot-upload-status-active status))))

(defn- start-legacy-snapshot-upload!
  [sql]
  (storage/with-sql-transaction!
   sql
   (fn []
     (storage/set-meta! sql snapshot-upload-id-meta-key legacy-snapshot-upload-id)
     (storage/set-meta! sql snapshot-upload-status-meta-key
                        snapshot-upload-status-legacy-active)
     (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now))
     (storage/set-meta! sql snapshot-uploading-meta-key true))))

(defn- abort-staged-snapshot-upload!
  [sql]
  (storage/with-sql-transaction!
   sql
   (fn []
     (common/sql-exec sql (str "delete from " snapshot-staging-table))
     (storage/set-meta! sql snapshot-upload-id-meta-key "")
     (storage/set-meta! sql snapshot-upload-status-meta-key "aborted")
     (storage/set-meta! sql snapshot-uploading-meta-key false)
     (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now)))))

(defn- refresh-snapshot-upload-lease!
  [sql upload-id]
  (require-snapshot-upload-session! sql upload-id)
  (storage/set-meta! sql snapshot-upload-started-at-meta-key (js/Date.now)))

(defn- import-staged-snapshot-rows!
  [sql upload-id rows]
  (require-snapshot-upload-session! sql upload-id)
  (import-snapshot-rows! sql snapshot-staging-table rows))

(defn- commit-staged-snapshot!
  [^js self upload-id checksum expected-row-count]
  (let [sql (.-sql self)]
    (storage/with-sql-transaction!
     sql
     (fn []
       (require-snapshot-upload-session! sql upload-id)
       (let [actual-row-count
             (or (some-> (common/sql-exec
                          sql
                          (str "select count(*) as row_count from "
                               snapshot-staging-table))
                         common/get-sql-rows
                         first
                         (aget "row_count"))
                 0)]
         (when-not (= expected-row-count actual-row-count)
           (throw
            (ex-info "snapshot row count mismatch"
                     {:type :db-sync/snapshot-row-count-mismatch
                      :expected-row-count expected-row-count
                      :actual-row-count actual-row-count}))))
       (set! (.-conn self) nil)
       (common/sql-exec sql "delete from kvs")
       (common/sql-exec
        sql
        (str "insert into kvs (addr, content, addresses) "
             "select addr, content, addresses from " snapshot-staging-table))
       (common/sql-exec sql "delete from tx_log")
       (common/sql-exec sql "delete from sync_meta")
       (storage/set-t! sql 0)
       (when (seq checksum)
         (storage/set-checksum! sql checksum))
       (storage/set-meta! sql snapshot-upload-id-meta-key upload-id)
       (storage/set-meta! sql snapshot-upload-status-meta-key snapshot-upload-status-committed)
       (storage/set-meta! sql snapshot-uploading-meta-key false)))))

(defn- cleanup-staged-snapshot!
  [sql upload-id]
  (when (= upload-id (:upload-id (snapshot-upload-session sql)))
    (common/sql-exec sql (str "delete from " snapshot-staging-table))))

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

(defn- apply-large-tx-entry!
  [self conn tx-data {:keys [tx-id outliner-op]} request-context]
  (let [db-before @conn
        tx-meta (apply-client-tx-meta request-context outliner-op nil)
        sql (when self (.-sql ^js self))
        prev-t (when sql (storage/get-t sql))
        prev-checksum (when sql (storage/get-checksum sql))
        prev-server-checksum (when sql (storage/get-server-checksum sql))
        prev-server-checksum-t (when sql (storage/get-server-checksum-t sql))
        verified-checksum-metadata?
        (when sql (storage/checksum-metadata-verified? sql prev-t))
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
                               (assoc :db-sync/skip-checksum-update? true)))
              nil)
            nil
            tx-data)
           (when sql
             ;; Chunk rows share one logical client tx-id. Bind it to the
             ;; final row only after every chunk has applied successfully;
             ;; the surrounding SQL transaction makes that marker atomic.
             (storage/set-tx-id-for-t! sql (storage/get-t sql) tx-id)
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
                (storage/get-t sql))))
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

(defn- sanitize-tx-entry
  [db {:keys [tx outliner-op] :as tx-entry}]
  (let [input-tx-data (protocol/transit->tx tx)
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

(defn- apply-tx-entry!
  ([conn tx-entry]
   (apply-tx-entry! nil conn tx-entry nil))
  ([self conn {:keys [tx-id outliner-op] :as tx-entry} request-context]
   (let [sanitized (sanitize-tx-entry @conn tx-entry)
         input-tx-data (:input-tx-data sanitized)
         tx-data (:tx-data sanitized)
         sanitized-entry (:tx-entry sanitized)]
     (if (seq tx-data)
       (try
         (if (and (not= outliner-op :db-migrate)
                  (large-tx? tx-data))
           (apply-large-tx-entry! self conn tx-data sanitized-entry request-context)
           (do
             (ldb/transact! conn tx-data
                            (apply-client-tx-meta request-context outliner-op tx-id))
             true))
         (catch :default e
           ;; Rebase/fix txs are inferred from local history and can become stale
           ;; when concurrent remote edits remove referenced entities before upload.
           ;; Treat stale :entity-id/missing rebases/fixes as no-op so sync can continue.
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
         false)))))

(defn- apply-tx! [^js self tx-entries request-context]
  (let [sql (.-sql self)]
    (ensure-conn! self)
    (let [conn (.-conn self)
          preexisting-tx-ids
          (->> tx-entries
               (keep :tx-id)
               distinct
               (filter (partial storage/tx-id-applied? sql))
               set)
          last-index-by-tx-id
          (reduce-kv (fn [result idx {:keys [tx-id]}]
                       (cond-> result
                         tx-id (assoc tx-id idx)))
                     {}
                     (vec tx-entries))]
      (loop [remaining tx-entries
             entry-index 0
             applied? false
             successful-tx-ids []]
        (if-let [tx-entry (first remaining)]
          (let [tx-id (:tx-id tx-entry)
                already-applied? (contains? preexisting-tx-ids tx-id)
                ;; Some older clients split one logical operation into
                ;; multiple entries carrying the same tx-id. Apply every
                ;; entry in this request, but persist the idempotency marker
                ;; only on its final entry so the unique index remains valid.
                tx-entry-to-apply
                (cond-> tx-entry
                  (and tx-id
                       (not= entry-index (get last-index-by-tx-id tx-id)))
                  (dissoc :tx-id))
                applied-entry? (if already-applied?
                                 false
                                 (try
                                   (boolean (apply-tx-entry!
                                             self conn tx-entry-to-apply request-context))
                                   (catch :default e
                                     (log/error :db-sync/transact-failed
                                                (common/error-log-data e))
                                     (let [missing-block-uuids (missing-block-uuids-from-error e)]
                                       (throw (ex-info "tx entry apply failed"
                                                       (cond-> {:type :db-sync/tx-entry-failed
                                                                :successful-tx-ids successful-tx-ids}
                                                         tx-id (assoc :failed-tx-id tx-id)
                                                         (seq missing-block-uuids)
                                                         (assoc :missing-block-uuids missing-block-uuids))
                                                       e))))))
                next-successful-tx-ids (cond-> successful-tx-ids
                                         tx-id (conj tx-id))]
            (recur (next remaining)
                   (inc entry-index)
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
   (let [current-t (t-now self)]
     (cond
       (not (snapshot-upload-finished? self))
       {:type "tx/reject"
        :reason "snapshot upload in progress"
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
           ;; Migrate persisted checksum metadata before extending it. Older
           ;; deployed servers did not write the verification watermark, so a
           ;; first HTTP batch is just as safe as a hello/pull-first session.
           (verify-current-checksums! self)
           (let [{:keys [t applied?]} (apply-tx! self txs request-context)]
             (when applied?
               ;; Broadcast once per processed batch after tx-log/checksum settle.
               (ws/broadcast! self sender {:type "changed" :t t}))
             (merge {:type "tx/batch/ok"
                     :t t}
                    (checksum-response-fields self)))
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
                         :t new-t}
                  (seq successful-tx-ids) (assoc :success-tx-ids successful-tx-ids)
                  failed-tx-id (assoc :failed-tx-id failed-tx-id)
                  (seq missing-block-uuids) (assoc :missing-block-uuids missing-block-uuids))
                (checksum-response-fields self)))))
         {:type "tx/reject"
          :reason "empty tx data"})))))

(defn- handle-sync-pull
  [^js self ^js url]
  (let [raw-since (.get (.-searchParams url) "since")
        since (if (some? raw-since) (parse-int raw-since) 0)
        graph-id (.get (.-searchParams url) "graph-id")]
    (if (or (and (some? raw-since) (not (number? since))) (neg? since))
      (http/bad-request "invalid since")
      (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
        (if-not ready-for-sync?
          (http/error-response "graph not ready" 409)
          (http/json-response :sync/pull (pull-response self since)))))))

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

(defn- handle-sync-snapshot-stream
  [^js self request frozen?]
  (ensure-schema! self)
  (let [graph-id (graph-id-from-request request)
        url (js/URL. (.-url request))
        download-id (.get (.-searchParams url) "download-id")
        stored-download-row
        (when (and frozen? (seq download-id))
          (snapshot-download-row (.-sql self) download-id))
        expired?
        (and stored-download-row
             (< (or (aget stored-download-row "created_at") 0)
                (- (js/Date.now) snapshot-download-retention-ms)))
        _ (when expired?
            (delete-snapshot-download! (.-sql self) download-id))
        active-download-row (when-not expired? stored-download-row)]
    (if (not (seq graph-id))
      (http/bad-request "missing graph id")
      (if (and frozen?
               (or (not (seq download-id))
                   (nil? active-download-row)))
        (http/error-response "snapshot download expired" 410)
        (let [gzip? (and (snapshot-stream-gzip-enabled? self)
                         (exists? js/CompressionStream))
              stream (cond-> (snapshot-export-stream self
                                                     (when frozen? download-id))
                       gzip?
                       (maybe-compress-stream))
              row-count (if frozen?
                          (or (aget active-download-row "row_count") 0)
                          (snapshot-row-count (.-sql self)))
              headers (cond-> {"content-type" snapshot-content-type}
                        gzip?
                        (assoc "content-encoding" snapshot-content-encoding))]
          (js/Response. stream
                        #js {:status 200
                             :headers (js/Object.assign
                                       (clj->js headers)
                                       #js {"x-snapshot-row-count" (str row-count)}
                                       (common/cors-headers))}))))))

(defn- handle-sync-snapshot-download
  [^js self request frozen?]
  (let [graph-id (graph-id-from-request request)]
    (cond
      (not (seq graph-id))
      (http/bad-request "missing graph id")

      :else
      (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
        (if-not ready-for-sync?
          (http/error-response "graph not ready" 409)
          (try
            (let [{:keys [download-id t checksum row-count]}
                  (when frozen? (create-snapshot-download! self))
                  key (str "stream/" graph-id ".snapshot")
                  url (snapshot-stream-url request graph-id download-id frozen?)
                  content-encoding (when (and (snapshot-stream-gzip-enabled? self)
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
              (http/json-response :sync/snapshot-download response))
            (catch :default error
              (if (= :db-sync/snapshot-download-capacity (:type (ex-data error)))
                (http/error-response "snapshot download busy; retry later" 429)
                (throw error)))))))))

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
        delete-alarm (.-deleteAlarm storage)]
    (doseq [^js ws (.getWebSockets state)]
      (.close ws 1000 "graph deleted"))
    (p/let [_ (when (fn? delete-alarm)
                (.deleteAlarm storage))]
      (if (fn? delete-all)
        (p/let [_ (.deleteAll storage)]
          (set! (.-schema-ready self) false)
          (set! (.-conn self) nil)
          (http/json-response :sync/admin-reset {:ok true}))
        (do
          (common/sql-exec (.-sql self) "drop table if exists kvs")
          (common/sql-exec (.-sql self) "drop table if exists snapshot_kvs_staging")
          (common/sql-exec (.-sql self) "drop table if exists snapshot_kvs_exports")
          (common/sql-exec (.-sql self) "drop table if exists snapshot_downloads")
          (common/sql-exec (.-sql self) "drop table if exists tx_log")
          (common/sql-exec (.-sql self) "drop table if exists sync_meta")
          (storage/init-schema! (.-sql self))
          (set! (.-schema-ready self) true)
          (set! (.-conn self) nil)
          (http/json-response :sync/admin-reset {:ok true}))))))

(defn- handle-sync-tx-batch
  [^js self request]
  (.then (common/read-json request)
         (fn [result]
           (if (nil? result)
             (http/bad-request "missing body")
             (let [body (js->clj result :keywordize-keys true)
                   body (http/coerce-http-request :sync/tx-batch body)
                   graph-id (graph-id-from-request request)]
               (if (nil? body)
                 (http/bad-request "invalid tx")
                 (let [{:keys [client-revision txs t-before]} body
                       t-before (parse-int t-before)]
                   (if (sequential? txs)
                     (p/let [ready-for-sync? (<ready-for-sync? self graph-id)]
                       (if-not ready-for-sync?
                         (http/error-response "graph not ready" 409)
                         (http/json-response :sync/tx-batch
                                             (handle-tx-batch! self nil txs t-before
                                                               {:graph-id graph-id
                                                                :client-revision client-revision}))))
                     (http/bad-request "invalid tx")))))))))

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

(defn- valid-snapshot-upload-id?
  [value]
  (and (string? value)
       (seq value)
       (<= (count value) snapshot-upload-id-max-length)))

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
            encoding (or req-encoding "")]
        (if (and (= encoding snapshot-content-encoding)
                 (not (exists? js/DecompressionStream)))
          (http/error-response "gzip not supported" 500)
          (p/catch
           (p/let [_ (ensure-schema! self)
                   sql (.-sql self)
                   _ (when (and (not staged?)
                                reset?
                                (= snapshot-upload-status-active
                                   (:status (snapshot-upload-session sql))))
                       ;; A v2 request may have reached a new server before a
                       ;; rolling-deployment fallback restarts the complete
                       ;; upload through v1. Abort the isolated staging session
                       ;; before v1 resets the live snapshot.
                       (abort-staged-snapshot-upload! sql))
                   _ (when (and (not staged?)
                                reset?
                                (active-legacy-snapshot-upload? sql))
                       (throw
                        (ex-info "legacy snapshot upload already in progress"
                                 {:type
                                  :db-sync/legacy-snapshot-upload-in-progress})))
                   _ (when (and (not staged?)
                                (not reset?)
                                (not (active-legacy-snapshot-upload? sql)))
                       (throw
                        (ex-info "legacy snapshot upload session missing"
                                 {:type
                                  :db-sync/legacy-snapshot-upload-session-missing})))
                   _ (when (and (not staged?) reset?)
                       ;; v1 carries no upload identity, so an abandoned
                       ;; multipart session cannot be taken over safely: a
                       ;; delayed chunk from the old client would otherwise be
                       ;; indistinguishable from the new upload. Keep the
                       ;; session active until it finishes or an explicit
                       ;; admin reset clears it.
                       (start-legacy-snapshot-upload! sql))
                   _ (when reset?
                       (<set-graph-ready-for-use! self graph-id false))
                   stream (maybe-decompress-stream stream encoding)
                   count
                   (if staged?
                     (p/let [_ (when reset?
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
                                 (commit-staged-snapshot!
                                  self upload-id checksum-param
                                  expected-row-count))
                             _ (when finished?
                                 (cleanup-staged-snapshot! sql upload-id))]
                       count)
                     (p/let [count (import-snapshot-stream! self stream reset?)
                             _ (storage/set-meta!
                                sql
                                snapshot-upload-started-at-meta-key
                                (js/Date.now))
                             _ (storage/set-meta!
                                sql snapshot-uploading-meta-key (not finished?))
                             _ (when finished?
                                 (storage/set-meta!
                                  sql snapshot-upload-status-meta-key
                                  snapshot-upload-status-committed))
                             _ (when (and finished? (seq checksum-param))
                                 (storage/set-initial-checksum!
                                  sql checksum-param))]
                       count))
                   _ (when finished?
                       (<set-graph-ready-for-use! self graph-id true))]
             (http/json-response :sync/snapshot-upload
                                 {:ok true :count count}))
           (fn [error]
             (cond
               (sqlite-too-big-error? error)
               (http/error-response "snapshot row too large" 413)

               (snapshot-upload-session-replaced? error)
               (http/error-response "snapshot upload session replaced" 409)

               (= :db-sync/snapshot-upload-already-committed (:type (ex-data error)))
               (http/error-response "snapshot upload already committed" 409)

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

               (= :db-sync/snapshot-checksum-mismatch (:type (ex-data error)))
               (http/error-response "snapshot checksum mismatch" 409)

               (= :db-sync/snapshot-row-count-mismatch (:type (ex-data error)))
               (http/error-response "snapshot row count mismatch" 409)

               :else
               (throw error)))))))))

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
            (if (instance? js/Promise resp)
              (.catch resp
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
                (semantic-handler/handle {:self self :request request :url url :route route})
              (handle {:self self
                       :request request
                       :url url
                       :route route}))
              (http/not-found)))))
      (catch :default e
        (log/error :db-sync/http-error (common/error-log-data e))
        (http/error-response "server error" 500)))))
