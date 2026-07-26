(ns frontend.worker.sync.download
  "Download helpers for graph snapshots."
  (:require
   [datascript.core :as d]
   [frontend.common.thread-api :as thread-api]
   [frontend.worker-common.util :as worker-util]
   [frontend.worker.platform :as platform]
   [frontend.worker.search :as search]
   [frontend.worker.shared-service :as shared-service]
   [frontend.worker.state :as worker-state]
   [frontend.worker.sync.auth :as sync-auth]
   [frontend.worker.sync.client-op :as client-op]
   [frontend.worker.sync.crypt :as sync-crypt]
   [frontend.worker.sync.log-and-state :as rtc-log-and-state]
   [frontend.worker.sync.temp-sqlite :as sync-temp-sqlite]
   [frontend.worker.sync.util :refer [fail-fast] :as sync-util]
   [lambdaisland.glogi :as log]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db-sync.snapshot :as snapshot]
   [logseq.db.common.sqlite :as common-sqlite]
   [logseq.db.frontend.schema :as db-schema]
   [promesa.core :as p]
   [logseq.db :as ldb]))

(defn- ->uint8 [data]
  (cond
    (instance? js/Uint8Array data) data
    (instance? js/ArrayBuffer data) (js/Uint8Array. data)
    (string? data) (.encode (js/TextEncoder.) data)
    :else (js/Uint8Array. data)))

(defn- gzip-bytes?
  [^js payload]
  (and (some? payload)
       (>= (.-byteLength payload) 2)
       (= 31 (aget payload 0))
       (= 139 (aget payload 1))))

(defn- bytes->stream
  [^js payload]
  (js/ReadableStream.
   #js {:start (fn [controller]
                 (.enqueue controller payload)
                 (.close controller))}))

(defn- <decompress-gzip-bytes
  [^js payload]
  (if (exists? js/DecompressionStream)
    (p/let [stream (bytes->stream payload)
            decompressed (.pipeThrough stream (js/DecompressionStream. "gzip"))
            resp (js/Response. decompressed)
            buf (.arrayBuffer resp)]
      (->uint8 buf))
    (p/rejected (ex-info "gzip decompression not supported"
                         {:type :db-sync/decompression-not-supported}))))

(defn- <snapshot-response-bytes
  [^js resp]
  (p/let [buf (.arrayBuffer resp)
          chunk (->uint8 buf)]
    (if (gzip-bytes? chunk)
      (<decompress-gzip-bytes chunk)
      chunk)))

(defn- <stream-starts-with-gzip?
  [^js stream]
  (let [reader (.getReader stream)]
    (-> (.read reader)
        (p/then (fn [result]
                  (if (.-done result)
                    false
                    (gzip-bytes? (->uint8 (.-value result))))))
        (p/catch (fn [_] false))
        (p/finally (fn []
                     (try
                       ;; `tee` buffers unread data independently for both
                       ;; branches. Cancel the one-chunk probe so a large
                       ;; snapshot cannot accumulate behind an abandoned
                       ;; reader while the payload branch is consumed.
                       (.cancel reader)
                       ;; A tee-branch cancel promise may not resolve until the
                       ;; sibling finishes; do not make probing wait for it.
                       nil
                       (catch :default _ nil)))))))

(defn- <response-body-stream
  [^js resp]
  (let [body (.-body resp)
        encoding (some-> resp .-headers (.get "content-encoding"))]
    (cond
      (nil? body)
      (p/resolved nil)

      ;; Never trust `content-encoding` alone.
      ;; Some runtimes (e.g. Node/undici fetch) may auto-decompress body while
      ;; still exposing `content-encoding: gzip` in headers.
      ;; We only stream-decompress when the first bytes are actual gzip magic.
      (and (= "gzip" encoding)
           (exists? js/DecompressionStream)
           (fn? (.-tee body)))
      (let [branches (.tee body)
            probe (aget branches 0)
            payload (aget branches 1)]
        (-> (<stream-starts-with-gzip? probe)
            (p/then (fn [gzip?]
                      (if gzip?
                        (.pipeThrough payload (js/DecompressionStream. "gzip"))
                        payload)))
            ;; If probing fails, keep original payload stream.
            (p/catch (fn [_] payload))))

      ;; If we cannot safely probe (no tee support), do not guess.
      ;; Fall back to the arrayBuffer path where we inspect magic bytes first.
      (= "gzip" encoding)
      (p/resolved nil)

      :else
      (p/resolved body))))

(defn- <flush-row-batches!
  [rows batch-size on-batch]
  (p/loop [remaining rows]
    (if (>= (count remaining) batch-size)
      (let [batch (subvec remaining 0 batch-size)
            rest-rows (subvec remaining batch-size)]
        (p/let [_ (on-batch batch)]
          (p/recur rest-rows)))
      remaining)))

(defn- <stream-snapshot-row-batches!
  [^js resp batch-size on-batch]
  (p/let [stream (<response-body-stream resp)]
    (if stream
      (let [reader (.getReader stream)]
        (p/loop [buffer nil
                 pending []]
          (p/let [result (.read reader)]
            (if (.-done result)
              (let [pending (if (and buffer (pos? (.-byteLength buffer)))
                              (into pending (snapshot/finalize-framed-buffer buffer))
                              pending)]
                (if (seq pending)
                  (p/let [_ (on-batch pending)]
                    {:chunk-count 1})
                  {:chunk-count 0}))
              (let [{rows :rows next-buffer :buffer} (snapshot/parse-framed-chunk buffer (->uint8 (.-value result)))
                    pending (into pending rows)]
                (p/let [pending (<flush-row-batches! pending batch-size on-batch)]
                  (p/recur next-buffer pending)))))))
      (p/let [snapshot-bytes (<snapshot-response-bytes resp)
              rows (vec (snapshot/finalize-framed-buffer snapshot-bytes))]
        (if (seq rows)
          (p/let [_ (on-batch rows)]
            {:chunk-count 1})
          {:chunk-count 0})))))

(defn- with-auth-headers
  [opts]
  (sync-auth/with-auth-headers
   #(sync-auth/auth-headers (worker-state/get-id-token))
   opts))

(defn- fetch-json
  [url opts schema]
  (sync-util/fetch-json url opts {:response-schema schema}))

(defonce ^:private *import-state (atom nil))
(def ^:private snapshot-import-datoms-batch-size 10000)

(defn complete-datoms-import!
  ([repo graph-id remote-tx]
   (complete-datoms-import! repo graph-id remote-tx nil))
  ([repo graph-id remote-tx after-rehydrate-f]
   (-> (p/do!
        (when-let [search-db (worker-state/get-sqlite-conn repo :search)]
          (search/truncate-table! search-db))
        (rtc-log-and-state/rtc-log :rtc.log/download
                                   {:sub-type :download-progress
                                    :graph-uuid graph-id
                                    :message "Saving data to DB"})
        (if-let [rehydrate-f
                 (@thread-api/*thread-apis
                  :thread-api/db-sync-rehydrate-large-titles)]
          (rehydrate-f repo graph-id)
          (fail-fast
           :db-sync/missing-field
           {:field :thread-api/db-sync-rehydrate-large-titles}))
        (when after-rehydrate-f
          (after-rehydrate-f))
        (rtc-log-and-state/rtc-log :rtc.log/download
                                   {:sub-type :download-completed
                                    :graph-uuid graph-id
                                    :message "Graph is ready!"})
        (when-let [^js db (worker-state/get-sqlite-conn repo :db)]
          (.exec db "PRAGMA wal_checkpoint(TRUNCATE)"))
        (client-op/update-local-tx repo remote-tx)
        (shared-service/broadcast-to-clients! :add-repo {:repo repo}))
       (p/catch (fn [error]
                  (log/error ::complete-datoms-import-failed
                             {:repo repo
                              :graph-id graph-id
                              :error-name (or (.-name error) "Error")})
                  (throw error))))))

(defn- require-thread-api-f!
  [k]
  (if-let [f (@thread-api/*thread-apis k)]
    f
    (fail-fast :db-sync/missing-field {:field k})))

(defn- stale-import-ex-info
  [repo graph-id import-id]
  (ex-info "stale db sync import"
           {:type :db-sync/stale-import
            :repo repo
            :graph-id graph-id
            :import-id import-id}))

(defn- import-temp-pool-name
  [repo import-id]
  ;; A previous pool removal is asynchronous on Node. Use an import-scoped
  ;; name so a delayed cleanup can never delete the next import's database.
  (worker-util/get-pool-name
   (str "download-import-" repo "-" import-id)))

(defn- close-import-state!
  [{:keys [import-id repo rows-db rows-pool]}]
  (when rows-db
    (try
      (.close rows-db)
      (catch :default _)))
  (if rows-pool
    (-> (platform/remove-storage-pool! (platform/current) rows-pool)
        (p/catch
         (fn [error]
           (log/warn :db-sync/import-pool-cleanup-failed
                     {:repo repo
                      :import-id import-id
                      :error-name (or (some-> error .-name) "Error")})
           nil)))
    (p/resolved nil)))

(defn close-import-state-for-repo!
  [repo]
  (when-let [state @*import-state]
    (when (= repo (:repo state))
      (close-import-state! state)
      (reset! *import-state nil))))

(defn- clear-import-state!
  [import-id]
  (when-let [state @*import-state]
    (when (= import-id (:import-id state))
      (close-import-state! state)
      (reset! *import-state nil))))

(defn- require-import-state!
  [repo graph-id import-id]
  (let [state @*import-state]
    (when-not (and state
                   (= import-id (:import-id state))
                   (or (nil? repo) (= repo (:repo state)))
                   (= graph-id (:graph-id state)))
      (throw (stale-import-ex-info repo graph-id import-id)))
    state))

(defn- upsert-addr-content!
  [^js db data]
  (.transaction
   db
   (fn [tx]
     (doseq [item data]
       (.exec tx #js {:sql "INSERT INTO kvs (addr, content, addresses) values ($addr, $content, $addresses) on conflict(addr) do update set content = $content, addresses = $addresses"
                      :bind item})))))

(defn import-rows-batch!
  [{:keys [rows-db]} rows]
  (when-not rows-db
    (throw (ex-info "missing import rows db"
                    {:type :db-sync/missing-field
                     :field :rows-db})))
  (let [data (map (fn [[addr content addresses]]
                    #js {:$addr addr
                         :$content content
                         :$addresses addresses})
                  rows)]
    (upsert-addr-content! rows-db data))
  (count rows))

(defn- <create-import-temp-db!
  [repo import-id]
  (if-let [sqlite @worker-state/*sqlite]
    (let [current-platform (platform/current)
          pool-name (import-temp-pool-name repo import-id)]
      (p/let [pool (platform/install-storage-pool current-platform sqlite pool-name)
              path (platform/resolve-db-path current-platform pool-name pool "/download-import.sqlite")
              db (platform/sqlite-open current-platform
                                       {:sqlite sqlite
                                        :pool pool
                                        :path path
                                        :mode "c"})]
        (common-sqlite/create-kvs-table! db)
        {:rows-db db
         :rows-path path
         :rows-pool pool}))
    (fail-fast :db-sync/missing-field {:repo repo :field :sqlite})))

(defn- <ensure-import-rows-db!
  [{:keys [import-id repo rows-db] :as state}]
  (if rows-db
    (p/resolved state)
    (p/let [{:keys [rows-db rows-path rows-pool]}
            (<create-import-temp-db! repo import-id)]
      (swap! *import-state
             (fn [current]
               (if (= import-id (:import-id current))
                 (assoc current
                        :rows-db rows-db
                        :rows-path rows-path
                        :rows-pool rows-pool)
                 current)))
      (assoc state
             :rows-db rows-db
             :rows-path rows-path
             :rows-pool rows-pool))))

(defn- datom->tx
  [{:keys [e a v]}]
  [:db/add e a v])

(defn- import-datoms-batch!
  [conn aes-key graph-e2ee? datoms]
  (p/let [datoms-batch (if graph-e2ee?
                         (sync-crypt/<decrypt-snapshot-datoms-batch aes-key datoms)
                         datoms)
          schema-tx-data (into [] (comp (filter #(= "db" (namespace (:a %))))
                                        (map datom->tx))
                               datoms-batch)
          regular-tx-data (into [] (comp (remove #(= "db" (namespace (:a %))))
                                         (map datom->tx))
                                datoms-batch)
          tx-data (into schema-tx-data regular-tx-data)]
    (when (seq tx-data)
      (d/transact! conn tx-data {:sync-download-graph? true}))))

(defn- schema-datom?
  [ident-eids schema-version-eid datom]
  (or (= schema-version-eid (:e datom))
      (and (contains? ident-eids (:e datom))
           (or (= :db/ident (:a datom))
               (= "db" (namespace (:a datom)))))))

(defn snapshot-datoms-in-import-order
  [conn]
  (let [db @conn
        schema-version-eid (some-> (d/entity db :logseq.kv/schema-version) :db/id)
        ident-eids (into #{}
                         (map :e)
                         (d/datoms db :avet :db/ident))
        schema-datom?* #(schema-datom? ident-eids schema-version-eid %)
        ordered-datoms (fn [pred]
                         (sequence
                          (comp (filter pred)
                                (map #(select-keys % [:e :a :v])))
                          (d/datoms db :eavt)))]
    (concat (ordered-datoms schema-datom?*)
            (ordered-datoms #(not (schema-datom?* %))))))

(defn- take-import-datoms-batch
  [datoms batch-size]
  (loop [batch (transient [])
         remaining (seq datoms)
         n 0]
    (if (or (nil? remaining)
            (>= n batch-size))
      [(persistent! batch) remaining]
      (recur (conj! batch (first remaining))
             (next remaining)
             (inc n)))))

(defn- <yield-next-tick
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- log-import-progress!
  [graph-id import-id datoms-count]
  (when (pos? datoms-count)
    (let [{:keys [imported-datoms total-datoms]}
          (swap! *import-state
                 (fn [state]
                   (if (= import-id (:import-id state))
                     (update state :imported-datoms (fnil + 0) datoms-count)
                     state)))]
      (rtc-log-and-state/rtc-log :rtc.log/download
                                 {:sub-type :download-progress
                                  :graph-uuid graph-id
                                  :message (if (some? total-datoms)
                                             (str "Importing data " imported-datoms "/" total-datoms)
                                             (str "Importing data " imported-datoms))}))))

(defn- <replay-imported-rows!
  [{:keys [conn rows-db aes-key graph-e2ee? graph-id import-id]}]
  (if (nil? rows-db)
    (p/resolved nil)
    (let [source-storage (sync-temp-sqlite/new-temp-sqlite-storage rows-db)
          source-conn (common-sqlite/get-storage-conn source-storage db-schema/schema)]
      (p/loop [remaining (seq (snapshot-datoms-in-import-order source-conn))]
        (if (seq remaining)
          (let [[batch remaining'] (take-import-datoms-batch remaining snapshot-import-datoms-batch-size)]
            (p/let [_ (import-datoms-batch! conn aes-key graph-e2ee? batch)
                    _ (log-import-progress! graph-id import-id (count batch))
                    _ (<yield-next-tick)]
              (p/recur remaining')))
          (p/resolved nil))))))

(defn- <validate-imported-snapshot!
  [{:keys [rows-db]} expected-row-count]
  (when (integer? expected-row-count)
    (let [actual-row-count
          (if rows-db
            (or (some-> (.exec rows-db #js {:sql "select count(*) as row_count from kvs"
                                            :rowMode "object"})
                        first
                        (aget "row_count"))
                0)
            0)]
      (when (and (integer? expected-row-count)
                 (not= expected-row-count actual-row-count))
        (throw (ex-info "downloaded snapshot row count mismatch"
                        {:type :db-sync/snapshot-row-count-mismatch
                         :expected-row-count expected-row-count
                         :actual-row-count actual-row-count}))))))

(defn- <open-import-target!
  [repo reset?]
  (p/let [reset-target-f
          (when reset?
            (require-thread-api-f!
             :thread-api/db-sync-reset-target-preserving-backup))
          recreate-lock-f (require-thread-api-f! :thread-api/db-sync-recreate-lock)
          invalidate-search-db-f (require-thread-api-f! :thread-api/db-sync-invalidate-search-db)
          create-or-open-db-f (require-thread-api-f! :thread-api/create-or-open-db)
          _ (when reset?
              (reset-target-f repo))
          _ (when reset? (recreate-lock-f repo))
          _ (create-or-open-db-f repo {:close-other-db? true
                                      :sync-download-graph? true})
          ;; Reset after the main/search/vector handles are open so both the
          ;; SQLite search tables and any persistent vector index are cleared.
          _ (when reset? (invalidate-search-db-f repo))
          conn (worker-state/get-datascript-conn repo)
          _ (when-not conn
              (fail-fast :db-sync/missing-field {:repo repo :field :datascript-conn}))]
    conn))

(defn prepare-import!
  [repo reset? graph-id graph-e2ee? & [total-datoms opts]]
  (let [graph-e2ee? (if (nil? graph-e2ee?) true (true? graph-e2ee?))
        defer-target? (true? (:defer-target? opts))]
    (-> (p/let [aes-key (if (contains? opts :aes-key)
                          (:aes-key opts)
                          (when graph-e2ee?
                            (sync-crypt/<fetch-graph-aes-key-for-download graph-id)))
                _ (when (and graph-e2ee? (nil? aes-key))
                    (fail-fast :db-sync/missing-field {:repo repo :field :aes-key}))
                previous-state @*import-state
                close-db-f (when (and previous-state
                                      (not defer-target?)
                                      (not= false (:target-prepared? previous-state)))
                             (require-thread-api-f! :thread-api/db-sync-close-db))
                _ (when previous-state
                    (close-import-state! previous-state)
                    (when close-db-f
                      (close-db-f (:repo previous-state))))
                _ (reset! *import-state nil)
                import-id (str (random-uuid))
                conn (when-not defer-target?
                       (<open-import-target! repo reset?))]
          (reset! *import-state {:aes-key aes-key
                                 :conn conn
                                 :graph-e2ee? graph-e2ee?
                                 :graph-id graph-id
                                 :import-id import-id
                                 :imported-datoms 0
                                 :rows-db nil
                                 :rows-imported? false
                                 :rows-path nil
                                 :rows-pool nil
                                 :repo repo
                                 :reset? reset?
                                 :local-backup nil
                                 :target-prepared? (not defer-target?)
                                 :total-datoms total-datoms})
          {:import-id import-id})
        (p/catch (fn [error]
                   (reset! *import-state nil)
                   (throw error))))))

(defn import-rows-chunk!
  [rows graph-id import-id]
  (-> (p/let [state (require-import-state! nil graph-id import-id)
              state (<ensure-import-rows-db! state)
              _ (import-rows-batch! state rows)
              _ (swap! *import-state
                       (fn [current]
                         (if (= import-id (:import-id current))
                           (assoc current :rows-imported? true)
                           current)))]
        true)
      (p/catch (fn [error]
                 (when-not (= :db-sync/stale-import (:type (ex-data error)))
                   (clear-import-state! import-id))
                 (throw error)))))

(defn- <activate-import-target!
  [{:keys [import-id repo reset?] :as state}]
  (p/let [_ (require-import-state! repo (:graph-id state) import-id)
          export-backup-f
          (when reset?
            (require-thread-api-f!
             :thread-api/db-sync-export-local-backup))
          local-backup (when export-backup-f
                         (export-backup-f repo))
          state-with-backup (assoc state :local-backup local-backup)
          _ (swap! *import-state
                   (fn [current]
                     (if (= import-id (:import-id current))
                       state-with-backup
                       current)))
          conn (<open-import-target! repo reset?)
          state' (assoc state-with-backup
                        :conn conn
                        :target-prepared? true)]
    (swap! *import-state
           (fn [current]
             (if (= import-id (:import-id current))
               state'
               current)))
    (require-import-state! repo (:graph-id state) import-id)))

(defn- <restore-local-backup!
  [state original-error]
  (if-let [local-backup (:local-backup state)]
    (let [restore-backup-f
          (require-thread-api-f!
           :thread-api/db-sync-restore-local-backup)]
      (-> (restore-backup-f (:repo state) local-backup)
          (p/catch
           (fn [restore-error]
             (throw
              (ex-info "snapshot activation failed and local backup restore failed"
                       {:type :db-sync/local-backup-restore-failed
                        :repo (:repo state)
                        :graph-id (:graph-id state)
                        :activation-error-name
                        (or (some-> original-error .-name)
                            "Error")
                        :restore-error-name
                        (or (some-> restore-error .-name)
                            "Error")}
                       restore-error))))))
    (p/resolved nil)))

(defn- set-graph-sync-metadata!
  [conn graph-id graph-e2ee?]
  (assert (uuid? graph-id))
  (ldb/transact! conn [(ldb/kv :logseq.kv/graph-uuid graph-id)
                       (ldb/kv :logseq.kv/graph-remote? true)
                       (ldb/kv :logseq.kv/graph-rtc-e2ee? (true? graph-e2ee?))]
    {:persist-op? false}))

(defn finalize-import!
  [repo graph-id remote-tx import-id & [expected-checksum expected-row-count]]
  (-> (p/let [state (require-import-state! repo graph-id import-id)
              _ (<validate-imported-snapshot!
                 state expected-row-count)
              state (if (false? (:target-prepared? state))
                      (<activate-import-target! state)
                      state)
              _ (when (:rows-imported? state)
                  (<replay-imported-rows! state))
              conn (:conn state)
              _ (when (string? expected-checksum)
                  (set-graph-sync-metadata!
                   conn (uuid graph-id) (:graph-e2ee? state)))
              result
              (complete-datoms-import!
               repo
               graph-id
               remote-tx
               (when (string? expected-checksum)
                 (fn []
                   (let [local-checksum
                         (sync-checksum/recompute-checksum @conn)]
                     (when-not (= expected-checksum local-checksum)
                       (throw
                        (ex-info
                         "downloaded snapshot checksum mismatch"
                         {:type :db-sync/snapshot-checksum-mismatch
                          :repo repo
                          :graph-id graph-id
                          :expected-checksum expected-checksum
                          :actual-checksum local-checksum})))
                     (client-op/update-local-checksum
                      repo local-checksum)
                     (client-op/update-local-server-checksum
                      repo
                      (sync-checksum/recompute-server-checksum @conn))))))
              commit-backup-f
              (when (:local-backup state)
                (require-thread-api-f!
                 :thread-api/db-sync-commit-local-backup))
              _ (when commit-backup-f
                  (commit-backup-f repo (:local-backup state)))
              _ (clear-import-state! import-id)]
        result)
      (p/catch (fn [error]
                 (let [state @*import-state
                       restore? (and (= import-id (:import-id state))
                                     (:local-backup state)
                                     (not= :db-sync/stale-import
                                           (:type (ex-data error))))]
                   (-> (if restore?
                         (<restore-local-backup! state error)
                         (p/resolved nil))
                       (p/then
                        (fn []
                          (when-not (= :db-sync/stale-import
                                      (:type (ex-data error)))
                            (clear-import-state! import-id))
                          (throw error)))))))))

(defn- <resolve-snapshot-remote-tx
  [snapshot-resp legacy-remote-tx]
  (if (integer? (:t snapshot-resp))
    (p/resolved (:t snapshot-resp))
    ;; Compatibility with servers deployed before snapshot metadata included
    ;; its transaction watermark. Capture the fallback before requesting the
    ;; snapshot so a concurrent transaction cannot be skipped.
    (p/resolved legacy-remote-tx)))

(defn- <fetch-snapshot-metadata!
  [base graph-id]
  (letfn [(fetch-version [v2?]
            (p/let [snapshot-resp
                    (fetch-json
                     (str base
                          "/sync/"
                          graph-id
                          (if v2?
                            "/snapshot/download-v2"
                            "/snapshot/download"))
                     {:method "GET"}
                     :sync/snapshot-download)]
              {:snapshot-resp snapshot-resp
               :v2? v2?}))]
    (-> (fetch-version true)
        (p/catch
         (fn [error]
           (if (= 404 (:status (ex-data error)))
             (fetch-version false)
             (p/rejected error)))))))

(defn- snapshot-download-id
  [snapshot-info]
  (when (:v2? snapshot-info)
    (some-> (:snapshot-resp snapshot-info)
            :url
            js/URL.
            .-searchParams
            (.get "download-id"))))

(defn- <cancel-snapshot-download!
  [base graph-id snapshot-info]
  (if-let [download-id (snapshot-download-id snapshot-info)]
    (-> (js/fetch
         (str base
              "/sync/"
              graph-id
              "/snapshot/download-v2?download-id="
              (js/encodeURIComponent download-id))
         (clj->js (with-auth-headers {:method "DELETE"})))
        (p/then (fn [_] nil))
        ;; Cancellation is best-effort cleanup and must not hide the original
        ;; download failure.
        (p/catch (fn [_] nil)))
    (p/resolved nil)))

(defn- <fetch-snapshot-stream!
  ([base graph-id snapshot-info]
   (<fetch-snapshot-stream! base graph-id snapshot-info false nil))
  ([base graph-id snapshot-info refreshed-expired?]
   (<fetch-snapshot-stream!
    base graph-id snapshot-info refreshed-expired? nil))
  ([base graph-id {:keys [snapshot-resp v2?] :as snapshot-info}
    refreshed-expired? on-metadata]
   (p/let [resp (js/fetch (:url snapshot-resp)
                          (clj->js (with-auth-headers {:method "GET"})))]
     (cond
       (and v2? (= 404 (.-status resp)))
       ;; A rolling deployment can route v2 metadata to a new instance and the
       ;; stream request to an old one. Restart with the legacy metadata before
       ;; any local import has begun.
       (p/let [_ (<cancel-snapshot-download! base graph-id snapshot-info)
               legacy-info
               (fetch-json
                (str base "/sync/" graph-id "/snapshot/download")
                {:method "GET"}
                :sync/snapshot-download)
               legacy-resp
               (js/fetch (:url legacy-info)
                         (clj->js (with-auth-headers {:method "GET"})))]
         {:snapshot-resp legacy-info
          :v2? false
          :resp legacy-resp})

       (and v2?
            (= 410 (.-status resp))
            (not refreshed-expired?))
       (p/let [_ (<cancel-snapshot-download! base graph-id snapshot-info)
               fresh-info (<fetch-snapshot-metadata! base graph-id)
               ;; Publish the fresh reservation before its stream request.
               ;; If fetch rejects at the network layer, the caller can still
               ;; cancel this exact download id.
               _ (when on-metadata
                   (on-metadata fresh-info))]
         (<fetch-snapshot-stream!
          base graph-id fresh-info true on-metadata))

       :else
       (assoc snapshot-info :resp resp)))))

(defn download-graph-by-id!
  [repo graph-id graph-e2ee?]
  (let [base (sync-auth/http-base-url @worker-state/*db-sync-config)]
    (if (and (seq repo) (seq graph-id) (seq base))
      (let [stage* (atom :init)
            import-id* (atom nil)
            snapshot-info* (atom nil)
            log-f (fn [payload]
                    (rtc-log-and-state/rtc-log :rtc.log/download payload))]
        (-> (p/let [_ (log-f {:sub-type :download-progress
                              :graph-uuid graph-id
                              :message "Preparing graph snapshot download"})
                    _ (reset! stage* :fetch-pull)
                    pull-resp (fetch-json (str base "/sync/" graph-id "/pull")
                                          {:method "GET"}
                                          :sync/pull)
                    legacy-remote-tx (:t pull-resp)
                    _ (reset! stage* :fetch-snapshot-download)
                    snapshot-info (<fetch-snapshot-metadata! base graph-id)
                    _ (reset! snapshot-info* snapshot-info)
                    aes-key (when graph-e2ee?
                              (reset! stage* :prepare-e2ee)
                              (sync-crypt/<fetch-graph-aes-key-for-download graph-id))
                    _ (reset! stage* :fetch-snapshot-stream)
                    {:keys [snapshot-resp resp] :as stream-info}
                    (<fetch-snapshot-stream!
                     base
                     graph-id
                     snapshot-info
                     false
                     (fn [fresh-info]
                       (reset! snapshot-info* fresh-info)))
                    _ (reset! snapshot-info* stream-info)
                    remote-tx (<resolve-snapshot-remote-tx
                               snapshot-resp legacy-remote-tx)
                    _ (when-not (integer? remote-tx)
                        (throw (ex-info "non-integer remote-tx when downloading graph"
                                        {:repo repo
                                         :remote-tx remote-tx})))
                    _ (log-f {:sub-type :download-progress
                              :graph-uuid graph-id
                              :message "Start downloading graph snapshot"})]
              (when-not (.-ok resp)
                (throw (ex-info "snapshot download failed"
                                {:repo repo
                                 :status (.-status resp)})))
              (p/let [_ (reset! stage* :prepare-import)
                      {:keys [import-id]} (prepare-import!
                                           repo
                                           true
                                           graph-id
                                           graph-e2ee?
                                           nil
                                           {:defer-target? true
                                            :aes-key aes-key})
                      _ (reset! import-id* import-id)
                      _ (do
                          (reset! stage* :stream-snapshot)
                          (<stream-snapshot-row-batches!
                           resp
                           25000
                           (fn [rows]
                             (import-rows-chunk! rows graph-id import-id))))
                      _ (log-f {:sub-type :download-completed
                                  :graph-uuid graph-id
                                  :message "Graph snapshot downloaded"})
                      _ (reset! stage* :finalize-import)
                      _ (finalize-import!
                         repo
                         graph-id
                         remote-tx
                         import-id
                         (:checksum snapshot-resp)
                         (:row-count snapshot-resp))]
                (when-let [conn (worker-state/get-datascript-conn repo)]
                  (set-graph-sync-metadata! conn (uuid graph-id) graph-e2ee?))
                {:repo repo
                 :graph-id graph-id
                 :remote-tx remote-tx
                 :graph-e2ee? graph-e2ee?}))
            (p/catch
             (fn [error]
               (p/let [_ (<cancel-snapshot-download!
                          base graph-id @snapshot-info*)]
                 (when-let [import-id @import-id*]
                   (clear-import-state! import-id))
                 (log-f {:sub-type :download-completed
                         :graph-uuid graph-id
                         :message "Graph snapshot download failed"})
                 (log/error :db-sync/download-graph-by-id-failed
                            {:repo repo
                             :graph-id graph-id
                             :graph-e2ee? graph-e2ee?
                             :stage @stage*
                             :diagnostic
                             (dissoc
                              (sync-util/error->diagnostic error)
                              :at)})
                 (throw
                  (ex-info
                   "db-sync download failed"
                   {:repo repo
                    :graph-id graph-id
                    :graph-e2ee? graph-e2ee?
                    :stage @stage*
                    :error-message (or (ex-message error)
                                       (when (instance? js/Error error)
                                         (.-message error)))
                   :error-cause (when (instance? js/Error error)
                                   (some-> (.-cause error)
                                           (.-message)))}
                   error)))))))
      (p/rejected (ex-info "db-sync missing graph download info"
                           {:repo repo
                            :graph-id graph-id
                            :base base})))))
