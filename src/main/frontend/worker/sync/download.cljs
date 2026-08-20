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
   [frontend.worker.sync.apply-txs :as sync-apply]
   [frontend.worker.sync.client-op :as client-op]
   [frontend.worker.sync.crypt :as sync-crypt]
   [frontend.worker.sync.log-and-state :as rtc-log-and-state]
   [frontend.worker.sync.temp-sqlite :as sync-temp-sqlite]
   [frontend.worker.sync.transport :as sync-transport]
   [frontend.worker.sync.util :refer [fail-fast] :as sync-util]
   [lambdaisland.glogi :as log]
   [logseq.db-sync.snapshot :as snapshot]
   [logseq.db-sync.checksum :as sync-checksum]
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
                     ;; A tee branch keeps ownership of the underlying
                     ;; response until it is consumed or cancelled. Do not
                     ;; await cancellation here: tee cancellation settles
                     ;; only after the payload sibling finishes.
                     (try
                       (when-let [cancellation (.cancel reader)]
                         (p/catch cancellation (fn [_] nil)))
                       (catch :default _))
                     (try
                       (.releaseLock reader)
                       (catch :default _)))))))

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
(defonce ^:private *repair-staging-builds (atom {}))
(def ^:private snapshot-import-datoms-batch-size 10000)
(def ^:private repair-max-automatic-attempts 3)
(def ^:private repair-retry-backoff-ms [1000 5000 30000])
(def ^:private transient-repair-error-types
  #{:db-sync/repair-snapshot-download-failed
    :db-sync/repair-staging-remote-advanced
    :db-sync/repair-active-graph-changed
    :db-sync/repair-staging-cancelled})

(declare require-thread-api-f! <run-repair-operation!)

(defn- stable-repair-observation?
  [repo conn before after client remote-t]
  (and (= before after)
       (identical? conn (worker-state/get-datascript-conn repo))
       (= remote-t (:remote-t after))
       (zero? (:pending-count after))
       (empty? @(:inflight client))))

(defn- <claim-repair-after-checksum-mismatch*
  [repo client remote-t remote-checksum]
  (let [graph-id (some-> (:graph-id client) str)
        conn (worker-state/get-datascript-conn repo)
        base (sync-auth/http-base-url @worker-state/*db-sync-config)]
    ;; A checksum message can finish while the graph is closing. In that case
    ;; there is no repair context to claim; leave the durable state untouched.
    (if-not (and (seq graph-id) conn (seq base))
      (p/resolved {:claimed? false :reason :missing-context})
      (p/let [before (client-op/read-repair-local-observation repo)
              local-checksum-before (sync-checksum/<recompute-checksum @conn)
              diagnostics (fetch-json
                           (str base "/sync/" graph-id
                                "/checksum/diagnostics?proof-only=true")
                           {:method "GET"}
                           :sync/checksum-diagnostics)
              after (client-op/read-repair-local-observation repo)]
        (cond
          (not (stable-repair-observation? repo conn before after client remote-t))
          {:claimed? false :reason :suspect-changed}

          (not= remote-t (:t diagnostics))
          {:claimed? false :reason :server-t-changed}

          (not= (:legacy-anchor diagnostics)
                (:server-recomputed-checksum diagnostics))
          (throw (ex-info "Server checksum anchor failed recomputation"
                          {:type :db-sync/server-checksum-drift
                           :repo repo
                           :remote-t remote-t}))

          (not= remote-checksum (:legacy-anchor diagnostics))
          {:claimed? false :reason :server-anchor-changed}

          (= local-checksum-before (:server-recomputed-checksum diagnostics))
          (do
            (when (not= (:legacy-anchor after) local-checksum-before)
              (client-op/update-local-checksum repo local-checksum-before))
            {:claimed? false :reason :recomputed-match})

          :else
          (let [start-basis
                {:remote-t remote-t
                 :server-checkpoint-identity
                 (:server-checkpoint-identity diagnostics)
                 :journal-high-water (:journal-high-water after)
                 :checksum-basis
                 {:version 1
                  :legacy-checksum local-checksum-before
                  :server-recomputed-checksum
                  (:server-recomputed-checksum diagnostics)
                  :server-t (:t diagnostics)
                  :legacy-anchor (:legacy-anchor diagnostics)
                  :metadata-proof (:metadata-proof diagnostics)}}
                claim-f (require-thread-api-f! :thread-api/db-sync-claim-repair)]
            (p/let [result (claim-f repo graph-id start-basis)]
              (when (:claimed? result)
                (-> (<run-repair-operation! repo (:operation result))
                    (p/catch
                     (fn [error]
                       (log/error :db-sync/repair-run-failed
                                  {:repo repo
                                   :operation-id
                                   (get-in result [:operation :operation-id])
                                   :error error})))))
              result)))))))

(defn <claim-repair-after-checksum-mismatch!
  "Recheck one mismatch suspect. DB-core's durable CAS is the only operation
  claim and makes overlapping requests idempotent without a second scheduler."
  [repo client remote-t remote-checksum]
  (<claim-repair-after-checksum-mismatch*
   repo client remote-t remote-checksum))

(defn complete-datoms-import!
  [repo graph-id remote-tx]
  (-> (p/do!
       (when-let [search-db (worker-state/get-sqlite-conn repo :search)]
         (search/truncate-table! search-db))
       (rtc-log-and-state/rtc-log :rtc.log/download
                                  {:sub-type :download-progress
                                   :graph-uuid graph-id
                                   :message "Saving data to DB"})
       (->
        (if-let [rehydrate-f (@thread-api/*thread-apis :thread-api/db-sync-rehydrate-large-titles)]
          (rehydrate-f repo graph-id)
          (fail-fast :db-sync/missing-field {:field :thread-api/db-sync-rehydrate-large-titles}))
        (p/catch (fn [error]
                   (log/error ::rehydrate-large-title-failed error))))
       (rtc-log-and-state/rtc-log :rtc.log/download
                                  {:sub-type :download-completed
                                   :graph-uuid graph-id
                                   :message "Graph is ready!"})
       (when-let [^js db (worker-state/get-sqlite-conn repo :db)]
         (.exec db "PRAGMA wal_checkpoint(TRUNCATE)"))
       (client-op/update-local-tx repo remote-tx)
       (shared-service/broadcast-to-clients! :add-repo {:repo repo}))
      (p/catch (fn [error]
                 (js/console.error error)))))

(defn- require-thread-api-f!
  [k]
  (if-let [f (@thread-api/*thread-apis k)]
    f
    (fail-fast :db-sync/missing-field {:field k})))

(defn- repair-error-type
  [error]
  (let [type (:type (ex-data error))]
    (if (keyword? type) type :db-sync/repair-unknown-failure)))

(defn- <delay-repair-retry
  [delay-ms]
  (p/delay delay-ms))

(defn- notify-repair-local-only!
  []
  (shared-service/broadcast-to-clients!
   :notification
   [nil :warning nil nil nil
    {:i18n-key :sync/repair-local-only-warning}]))

(defn- <schedule-recorded-repair-retry!
  [repo operation]
  (let [operation-id (:operation-id operation)
        retry-at (:next-retry-at-ms operation)
        delay-ms (max 0 (- retry-at (js/Date.now)))]
    (p/let [_ (<delay-repair-retry delay-ms)
            retry-f (require-thread-api-f!
                     :thread-api/db-sync-claim-repair-retry)
            operation (retry-f repo operation-id false (js/Date.now))]
      (<run-repair-operation! repo operation))))

(defn <run-repair-operation!
  "Run the one durable repair operation. Only the frozen transient class uses
  the single bounded delay chain; every other failure enters LocalOnly."
  [repo operation]
  (let [operation-id (:operation-id operation)]
    (if (:next-retry-at-ms operation)
      (<schedule-recorded-repair-retry! repo operation)
      (let [commit-f (require-thread-api-f! :thread-api/db-sync-commit-repair)]
        (-> (p/then (p/resolved nil)
                    (fn [_] (commit-f repo operation-id)))
            (p/catch
             (fn [error]
               (let [error-type (repair-error-type error)
                     attempt-count (:attempt-count operation)
                     transient? (contains? transient-repair-error-types error-type)
                     retry? (and transient?
                                 (< attempt-count repair-max-automatic-attempts))
                     now-ms (js/Date.now)
                     delay-ms (when retry?
                                (nth repair-retry-backoff-ms
                                     (dec attempt-count)))
                     transition
                     {:disposition (if retry? :repairing :local-only)
                      :next-retry-at-ms (when retry? (+ now-ms delay-ms))
                      :error-type (str error-type)
                      :at-ms now-ms}
                     record-f (require-thread-api-f!
                               :thread-api/db-sync-record-repair-failure)
                     next-record (record-f repo operation-id transition)
                     next-operation (:operation next-record)]
                 (if retry?
                   (<schedule-recorded-repair-retry! repo next-operation)
                   (p/let [stop-f (require-thread-api-f!
                                   :thread-api/db-sync-stop)
                           _ (stop-f)]
                     (notify-repair-local-only!)
                     {:completed? false
                      :disposition :local-only
                      :operation-id operation-id}))))))))))

(defn <resume-repair-operation!
  "Resume only a pre-commit Repairing receipt when the official RTC owner
  starts. LocalOnly and post-commit verification receipts do not auto-build."
  [repo]
  (let [status-f (require-thread-api-f! :thread-api/db-sync-repair-status)
        {:keys [committed operation prepared-swap?]} (status-f repo)]
    (cond
      (= :local-only (:disposition operation))
      (let [stop-f (require-thread-api-f! :thread-api/db-sync-stop)]
        (p/let [_ (stop-f)]
          (notify-repair-local-only!)
          {:resumed? false :disposition :local-only}))

      (and operation
           (= :repairing (:disposition operation))
           (not prepared-swap?)
           (= (inc (:projection-epoch committed))
              (:target-projection-epoch operation)))
      (<run-repair-operation! repo operation)

      :else
      (p/resolved {:resumed? false}))))

(defn <explicit-retry-repair!
  "Explicitly re-claim the same LocalOnly operation and preserve its id."
  [repo operation-id]
  (let [retry-f (require-thread-api-f!
                 :thread-api/db-sync-claim-repair-retry)
        operation (retry-f repo operation-id true (js/Date.now))]
    (p/let [result (<run-repair-operation! repo operation)]
      (if (= :local-only (:disposition result))
        result
        (let [start-f (require-thread-api-f! :thread-api/db-sync-start)]
          (p/let [_ (start-f repo)]
            result))))))

(defn <complete-repair-if-converged!
  "Retire a post-commit operation only from an official checksum-ready
  hello/pull/ACK boundary. Newer journal rows are not part of the old target."
  [repo client remote-t remote-checksum activation-value]
  (let [status-f (require-thread-api-f!
                  :thread-api/db-sync-repair-status-from-value)
        {:keys [committed operation prepared-swap?]}
        (status-f activation-value)]
    (if-not (and operation
                 (= :repairing (:disposition operation))
                 (not prepared-swap?)
                 (= (:projection-epoch committed)
                    (:target-projection-epoch operation)))
      (p/resolved {:completed? false})
      (let [target (:target-basis operation)
            conn (worker-state/get-datascript-conn repo)
            graph-id (some-> (:graph-id client) str)]
        (if-not (and conn (= graph-id (:graph-id operation)))
          (p/resolved {:completed? false})
          (p/let [observation
                  (client-op/read-repair-completion-observation
                   repo (:journal-high-water target))
                  canonical-checksum
                  (sync-checksum/<recompute-checksum @conn)]
            (if-not (and (identical? conn
                                     (worker-state/get-datascript-conn repo))
                         (empty? @(:inflight client))
                         (= remote-t (:remote-t observation))
                         (= remote-checksum (:legacy-anchor observation)))
              {:completed? false}
              (let [complete-f
                    (require-thread-api-f! :thread-api/db-sync-complete-repair)]
                (complete-f
                 repo (:operation-id operation)
                 (assoc observation
                        :graph-id graph-id
                        :remote-t remote-t
                        :local-checksum (:legacy-anchor observation)
                        :remote-checksum remote-checksum
                        :canonical-checksum canonical-checksum))))))))))

(defn- stale-import-ex-info
  [repo graph-id import-id]
  (ex-info "stale db sync import"
           {:type :db-sync/stale-import
            :repo repo
            :graph-id graph-id
            :import-id import-id}))

(defn- import-temp-pool-name
  [repo]
  (worker-util/get-pool-name (str "download-import-" repo)))

(defn- close-import-state!
  [{:keys [rows-db rows-pool]}]
  (when rows-db
    (try
      (.close rows-db)
      (catch :default _)))
  (when rows-pool
    (-> (platform/remove-storage-pool! (platform/current) rows-pool)
        (p/catch (fn [_] nil)))))

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
  [repo]
  (if-let [sqlite @worker-state/*sqlite]
    (let [current-platform (platform/current)
          pool-name (import-temp-pool-name repo)]
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
    (p/let [{:keys [rows-db rows-path rows-pool]} (<create-import-temp-db! repo)]
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

(def ^:private snapshot-local-only-attrs
  #{:block/tx-id})

(defn- import-datoms-batch!
  [conn aes-key graph-e2ee? datoms]
  (p/let [datoms-batch (if graph-e2ee?
                         (sync-crypt/<decrypt-snapshot-datoms-batch aes-key datoms)
                         datoms)
          datoms-batch (remove #(contains? snapshot-local-only-attrs (:a %))
                               datoms-batch)
          block-eids (into #{}
                           (comp (filter #(= :block/uuid (:a %)))
                                 (map :e))
                           datoms-batch)
          schema-tx-data (into [] (comp (filter #(= "db" (namespace (:a %))))
                                        (map datom->tx))
                               datoms-batch)
          regular-tx-data (into [] (comp (remove #(= "db" (namespace (:a %))))
                                         (map datom->tx))
                                datoms-batch)
          tx-id (inc (:max-tx @conn))
          tx-data (into (into schema-tx-data regular-tx-data)
                        (map (fn [block-eid]
                               [:db/add block-eid :block/tx-id tx-id]))
                        block-eids)]
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

(defn- <copy-snapshot-rows-to-conn!
  [rows-db conn aes-key graph-e2ee? on-batch]
  (if (nil? rows-db)
    (p/resolved nil)
    (let [source-storage (sync-temp-sqlite/new-temp-sqlite-storage rows-db)
          source-conn (common-sqlite/get-storage-conn source-storage db-schema/schema)]
      (p/loop [remaining (seq (snapshot-datoms-in-import-order source-conn))]
        (if (seq remaining)
          (let [[batch remaining'] (take-import-datoms-batch remaining snapshot-import-datoms-batch-size)]
            (p/let [_ (import-datoms-batch! conn aes-key graph-e2ee? batch)
                    _ (when on-batch (on-batch (count batch)))
                    _ (<yield-next-tick)]
              (p/recur remaining')))
          (p/resolved nil))))))

(defn- <replay-imported-rows!
  [{:keys [conn rows-db aes-key graph-e2ee? graph-id import-id]}]
  (<copy-snapshot-rows-to-conn!
   rows-db conn aes-key graph-e2ee?
   #(log-import-progress! graph-id import-id %)))

(defn- repair-temp-pool-name
  [operation-id kind]
  (worker-util/get-pool-name
   (str "repair-" operation-id "-" (name kind))))

(declare <best-effort-cleanup-repair-resource!)

(defn- reset-repair-temp-resource!
  [resource]
  ;; A crash may leave the deterministic operation pool behind. Staging is
  ;; non-authoritative, so resume always rebuilds it from the bound snapshot.
  (.exec (:db resource) "delete from kvs")
  resource)

(defn- <cleanup-repair-resource!
  [resource]
  (if resource
    (try
      (p/resolved (sync-temp-sqlite/cleanup-temp-sqlite! resource))
      (catch :default error
        (p/rejected error)))
    (p/resolved nil)))

(defn- <best-effort-cleanup-repair-resource!
  [resource]
  (-> (<cleanup-repair-resource! resource)
      (p/catch (fn [_] nil))))

(defn- <capture-repair-cleanup-error
  [resource]
  (-> (<cleanup-repair-resource! resource)
      (p/then (fn [_] nil))
      (p/catch (fn [error] {:error error}))))

(defn- <create-repair-target!
  [operation-id]
  (p/let [resource
          (sync-temp-sqlite/<create-named-temp-sqlite-db!
           (repair-temp-pool-name operation-id :target)
           "/repair-target.sqlite")]
    (try
      (reset-repair-temp-resource! resource)
      (catch :default error
        (p/let [_ (<best-effort-cleanup-repair-resource! resource)]
          (throw error))))))

(def ^:private repair-snapshot-transform-page-size 1000)

(defn- snapshot-attr-page
  [conn attr after-eid]
  (let [start-eid (if (some? after-eid) (inc after-eid) 1)]
    (into []
          (comp (take-while #(= attr (:a %)))
                (take repair-snapshot-transform-page-size)
                (map #(select-keys % [:e :a :v])))
          (d/seek-datoms @conn :aevt attr start-eid))))

(defn- <transform-snapshot-attr!
  [conn aes-key attr]
  (p/loop [after-eid nil]
    (let [page (snapshot-attr-page conn attr after-eid)]
      (if (seq page)
        (p/let [decrypted (sync-crypt/<decrypt-snapshot-datoms-batch aes-key page)
                _ (d/transact! conn (mapv datom->tx decrypted)
                               {:sync-download-graph? true})
                _ (<yield-next-tick)]
          (p/recur (:e (peek page))))
        nil))))

(defn- <add-snapshot-local-revisions!
  [conn]
  (p/loop [after-eid nil]
    (let [page (snapshot-attr-page conn :block/uuid after-eid)]
      (if (seq page)
        (let [tx-id (inc (:max-tx @conn))]
          (p/let [_ (d/transact!
                     conn
                     (mapv (fn [{:keys [e]}]
                             [:db/add e :block/tx-id tx-id])
                           page)
                     {:sync-download-graph? true})
                  _ (<yield-next-tick)]
            (p/recur (:e (peek page)))))
        nil))))

(defn- <materialize-repair-target!
  [resource aes-key graph-e2ee?]
  (let [storage (sync-temp-sqlite/new-temp-sqlite-storage (:db resource))
        conn (d/restore-conn storage)]
    (when-not conn
      (throw (ex-info "Repair snapshot is missing its DataScript root"
                      {:type :db-sync/repair-corrupt-snapshot})))
    (p/let [_ (when graph-e2ee?
                (p/loop [attrs (seq [:block/title :block/name])]
                  (when-let [attr (first attrs)]
                    (p/let [_ (<transform-snapshot-attr! conn aes-key attr)]
                      (p/recur (next attrs))))))
            _ (<add-snapshot-local-revisions! conn)]
      (assoc resource :conn conn))))

(defn- repair-staging-key
  [repo graph-id]
  [repo graph-id])

(defn repair-staging-in-progress?
  "True while this graph's one in-memory repair staging build is owned."
  [repo graph-id]
  (contains? @*repair-staging-builds (repair-staging-key repo graph-id)))

(defn- repair-staging-owner?
  [repo graph-id owner-token]
  (identical? owner-token
              (get-in @*repair-staging-builds
                      [(repair-staging-key repo graph-id) :owner-token])))

(defn- release-repair-staging-owner!
  [repo graph-id owner-token]
  (let [key (repair-staging-key repo graph-id)]
    (swap! *repair-staging-builds
           (fn [builds]
             (if (identical? owner-token (get-in builds [key :owner-token]))
               (dissoc builds key)
               builds)))))

(defn <cleanup-repair-staging!
  [{:keys [repo target operation staging-owner-token]}]
  (let [graph-id (:graph-id operation)]
    (if-not (repair-staging-owner? repo graph-id staging-owner-token)
      (p/resolved nil)
      (-> (<cleanup-repair-resource! target)
          (p/then
           (fn [result]
             (release-repair-staging-owner!
              repo graph-id staging-owner-token)
             result))))))

(defn- ensure-repair-build-active!
  [repo active-conn owner-token]
  (when (or @(:cancelled? owner-token)
            (not (identical? active-conn
                             (worker-state/get-datascript-conn repo))))
    (throw (ex-info "Repair staging build was cancelled"
                    {:type :db-sync/repair-staging-cancelled
                     :repo repo}))))

(defn- diagnostics-bound?
  [graph-id diagnostics]
  (let [server-t (:t diagnostics)
        anchor (:legacy-anchor diagnostics)
        recomputed (:server-recomputed-checksum diagnostics)]
    (and (integer? server-t)
         (string? anchor)
         (= anchor recomputed)
         (= (str "sync-do-checkpoint-v1:" graph-id ":" server-t ":" anchor)
            (:server-checkpoint-identity diagnostics))
         (= (str "authenticated-diagnostics-v1:" graph-id ":" server-t ":"
                 anchor ":" recomputed)
            (:metadata-proof diagnostics)))))

(defn- require-bound-diagnostics!
  [graph-id diagnostics]
  (when-not (diagnostics-bound? graph-id diagnostics)
    (throw (ex-info "Unbound repair checksum diagnostics"
                    {:type :db-sync/unbound-repair-diagnostics
                     :graph-id graph-id
                     :server-t (:t diagnostics)})))
  diagnostics)

(defn- <fetch-repair-diagnostics
  [base graph-id]
  (p/let [diagnostics (fetch-json
                       (str base "/sync/" graph-id
                            "/checksum/diagnostics?proof-only=true")
                       {:method "GET"}
                       :sync/checksum-diagnostics)]
    (require-bound-diagnostics! graph-id diagnostics)))

(defn- <fetch-repair-snapshot-rows!
  [base graph-id rows-db]
  (p/let [snapshot-response (fetch-json
                             (str base "/sync/" graph-id "/snapshot/download")
                             {:method "GET"}
                             :sync/snapshot-download)
          response (js/fetch (:url snapshot-response)
                             (clj->js (with-auth-headers {:method "GET"})))]
    (when-not (.-ok response)
      (throw (ex-info "Repair snapshot download failed"
                      {:type :db-sync/repair-snapshot-download-failed
                       :graph-id graph-id
                       :status (.-status response)})))
    (<stream-snapshot-row-batches!
     response 25000
     (fn [rows]
       (import-rows-batch! {:rows-db rows-db} rows)))))

(defn- parse-repair-remote-txs
  [repo txs]
  (mapv (fn [entry]
          {:t (:t entry)
           :outliner-op (:outliner-op entry)
           :tx-data (sync-transport/parse-transit
                     fail-fast (:tx entry)
                     {:repo repo :type "repair-pull"})})
        txs))

(defn- <decrypt-repair-remote-txs
  [aes-key remote-txs]
  (if aes-key
    (p/all
     (mapv (fn [{:keys [tx-data] :as remote-tx}]
             (p/let [tx-data (sync-crypt/<decrypt-tx-data aes-key tx-data)]
               (assoc remote-tx :tx-data tx-data)))
           remote-txs))
    (p/resolved remote-txs)))

(def ^:private repair-tail-decode-batch-size 16)
(def ^:private repair-local-page-size 16)

(defn- <decode-repair-remote-tail
  [repo aes-key raw-txs]
  (p/loop [remaining (vec raw-txs)
           decoded []]
    (if (seq remaining)
      (let [batch-size (min repair-tail-decode-batch-size (count remaining))
            raw-batch (subvec remaining 0 batch-size)
            remaining (subvec remaining batch-size)]
        (p/let [remote-txs (<decrypt-repair-remote-txs
                            aes-key (parse-repair-remote-txs repo raw-batch))
                _ (<yield-next-tick)]
          (p/recur remaining (into decoded remote-txs))))
      decoded)))

(defn- <apply-repair-local-watermark!
  [repo staging-conn active-db pending-ids]
  (p/loop [remaining (vec pending-ids)
           local-count 0]
    (if (seq remaining)
      (let [page-size (min repair-local-page-size (count remaining))
            page-ids (subvec remaining 0 page-size)
            remaining (subvec remaining page-size)
            local-txs (client-op/read-repair-local-page repo page-ids)]
        (p/let [result (sync-apply/<apply-repair-staging-tails!
                        staging-conn [] local-txs active-db)]
          (p/recur remaining (+ local-count (:local-count result)))))
      {:remote-count 0
       :local-count local-count})))

(defn- capture-repair-local-state
  [repo active-conn server-t]
  (let [local-batch (client-op/read-repair-local-batch repo)
        active-db @active-conn
        observation (:observation local-batch)]
    (when-not (and (identical? active-conn
                                (worker-state/get-datascript-conn repo))
                   (= (:remote-t observation) server-t))
      (throw (ex-info "Active graph basis changed during repair staging"
                      {:type :db-sync/repair-active-graph-changed
                       :repo repo})))
    {:local-batch local-batch
     :active-db active-db}))

(defn- <build-repair-staging-once!
  [repo {:keys [operation-id graph-id] :as operation} owner-token]
  (let [base (sync-auth/http-base-url @worker-state/*db-sync-config)
        active-conn (worker-state/get-datascript-conn repo)
        target* (atom nil)]
    (if-not (and (uuid? operation-id) (seq graph-id) (seq base) active-conn
                 (not @(:cancelled? owner-token)))
      (p/rejected (ex-info "Missing repair staging context"
                           {:type :db-sync/missing-repair-staging-context
                            :repo repo}))
      (-> (p/let [target (<create-repair-target! operation-id)
                  _ (reset! target* target)
                  graph-e2ee? (sync-crypt/graph-e2ee? repo)
                  aes-key (when graph-e2ee?
                            (sync-crypt/<fetch-graph-aes-key-for-download graph-id))
                  _ (when (and graph-e2ee? (nil? aes-key))
                      (throw (ex-info "Missing repair snapshot AES key"
                                      {:type :db-sync/missing-field
                                       :repo repo :field :aes-key})))
                  snapshot-floor-proof (<fetch-repair-diagnostics base graph-id)
                  _ (<fetch-repair-snapshot-rows! base graph-id (:db target))
                  _ (ensure-repair-build-active! repo active-conn owner-token)
                  target (<materialize-repair-target! target aes-key graph-e2ee?)
                  _ (reset! target* target)
                  _ (ensure-repair-build-active! repo active-conn owner-token)
                  pull (fetch-json
                        (str base "/sync/" graph-id "/pull?since="
                             (:t snapshot-floor-proof))
                        {:method "GET"}
                        :sync/pull)
                  remote-txs (<decode-repair-remote-tail repo aes-key (:txs pull))
                  tail-proof (<fetch-repair-diagnostics base graph-id)
                  _ (when-not (and (= (:t pull) (:t tail-proof))
                                   (or (nil? (:checksum pull))
                                       (= (:checksum pull) (:legacy-anchor tail-proof))))
                      (throw (ex-info "Repair remote tail moved during proof"
                                      {:type :db-sync/repair-remote-tail-moved
                                       :graph-id graph-id})))
                  _ (ensure-repair-build-active! repo active-conn owner-token)
                  remote-result (sync-apply/<apply-repair-staging-tails!
                                 (:conn target) remote-txs [] @active-conn)
                  remote-checksum (sync-checksum/<recompute-checksum @(:conn target))
                  _ (when-not (= remote-checksum
                                  (:server-recomputed-checksum tail-proof))
                      (throw (ex-info "Repair remote tail checksum mismatch"
                                      {:type :db-sync/repair-remote-tail-checksum-mismatch
                                       :graph-id graph-id})))
                  local-state (capture-repair-local-state repo active-conn (:t tail-proof))
                  local-batch (:local-batch local-state)
                  observation (:observation local-batch)
                  local-result (<apply-repair-local-watermark!
                                repo (:conn target) (:active-db local-state)
                                (:pending-ids local-batch))
                  _ (ensure-repair-build-active! repo active-conn owner-token)
                  target-checksum (sync-checksum/<recompute-checksum @(:conn target))
                  _ (ensure-repair-build-active! repo active-conn owner-token)
                  target-basis
                  {:remote-t (:t tail-proof)
                   :server-checkpoint-identity
                   (:server-checkpoint-identity tail-proof)
                   :journal-high-water (:journal-high-water observation)
                   :checksum-basis
                   {:version 1
                    :legacy-checksum target-checksum
                    :server-recomputed-checksum
                    (:server-recomputed-checksum tail-proof)
                    :server-t (:t tail-proof)
                    :legacy-anchor (:legacy-anchor tail-proof)
                    :metadata-proof (:metadata-proof tail-proof)}}]
            {:repo repo
             :operation operation
             :target target
             :target-basis target-basis
             :remote-result remote-result
             :local-result local-result})
          (p/catch
           (fn [error]
             (p/let [cleanup-results
                     (p/all [(<capture-repair-cleanup-error @target*)])]
               (if-let [cleanup-errors (seq (keep :error cleanup-results))]
                 (throw
                  (ex-info "Repair staging cleanup failed"
                           {:type :db-sync/repair-staging-cleanup-failed
                            :repo repo
                            :operation operation
                            :original-error error
                            :cleanup-errors (vec cleanup-errors)
                            :target @target*}
                           error))
                 (throw error)))))))))

(defn <build-repair-staging!
  "Build the one non-authoritative staging target owned by a durable repair
  operation. Concurrent calls for that operation share the same build; a
  different operation cannot allocate a second target for the same graph. A
  pre-stream proof is a conservative pull floor, and the caller must release
  the returned target with <cleanup-repair-staging!."
  [repo {:keys [operation-id graph-id] :as operation}]
  (let [key (repair-staging-key repo graph-id)]
    (if-let [existing (get @*repair-staging-builds key)]
      (cond
        @(:cancelled? (:owner-token existing))
        (p/rejected
         (ex-info "Repair staging target is closing"
                  {:type :db-sync/repair-staging-closing
                   :repo repo
                   :graph-id graph-id
                   :operation-id operation-id}))

        (= operation-id (:operation-id existing))
        (:build existing)

        :else
        (p/rejected
         (ex-info "Another repair operation owns the staging target"
                  {:type :db-sync/repair-staging-already-owned
                   :repo repo
                   :graph-id graph-id
                   :operation-id operation-id
                   :owner-operation-id (:operation-id existing)})))
      (let [owner-token {:cancelled? (atom false)}
            build (<build-repair-staging-once! repo operation owner-token)
            owned-build
            (-> build
                (p/then #(assoc % :staging-owner-token owner-token))
                (p/catch
                 (fn [error]
                   (when-not (= :db-sync/repair-staging-cleanup-failed
                                (:type (ex-data error)))
                     (release-repair-staging-owner! repo graph-id owner-token))
                   (throw error))))]
        (swap! *repair-staging-builds assoc key
               {:operation-id operation-id
                :owner-token owner-token
                :build owned-build})
        owned-build))))

(defn <finalize-repair-staging!
  "Under the short db-core commit fence, prove that the remote cursor did not
  advance and apply only local journal rows created after the staging build.
  Remote movement rejects this attempt so the same durable operation can
  rebuild outside the fence; it is never merged after already-replayed locals."
  [{:keys [repo operation target target-basis staging-owner-token] :as staging}]
  (let [{:keys [graph-id]} operation
        active-conn (worker-state/get-datascript-conn repo)
        base (sync-auth/http-base-url @worker-state/*db-sync-config)]
    (when-not (and (repair-staging-owner? repo graph-id staging-owner-token)
                   active-conn
                   (seq base))
      (throw (ex-info "Repair staging target is no longer active"
                      {:type :db-sync/repair-staging-cancelled
                       :repo repo})))
    (p/let [tail-proof (<fetch-repair-diagnostics base graph-id)]
      (when-not (= (:remote-t target-basis) (:t tail-proof))
        (throw (ex-info "Repair staging remote basis advanced before commit"
                        {:type :db-sync/repair-staging-remote-advanced
                         :repo repo
                         :staging-remote-t (:remote-t target-basis)
                         :current-remote-t (:t tail-proof)})))
      (let [local-state (capture-repair-local-state repo active-conn (:t tail-proof))
            local-batch (:local-batch local-state)
            observation (:observation local-batch)
            new-pending-ids (->> (:pending-ids local-batch)
                                 (filter #(< (:journal-high-water target-basis) %))
                                 vec)]
        (p/let [local-result (<apply-repair-local-watermark!
                              repo (:conn target) (:active-db local-state)
                              new-pending-ids)
                target-checksum (sync-checksum/<recompute-checksum @(:conn target))
                _ (ensure-repair-build-active!
                   repo active-conn staging-owner-token)
                final-basis
                {:remote-t (:t tail-proof)
                 :server-checkpoint-identity
                 (:server-checkpoint-identity tail-proof)
                 :journal-high-water (:journal-high-water observation)
                 :checksum-basis
                 {:version 1
                  :legacy-checksum target-checksum
                  :server-recomputed-checksum
                  (:server-recomputed-checksum tail-proof)
                  :server-t (:t tail-proof)
                  :legacy-anchor (:legacy-anchor tail-proof)
                  :metadata-proof (:metadata-proof tail-proof)}}]
          (assoc staging
                 :target-basis final-basis
                 :local-result
                 (update (:local-result staging) :local-count +
                         (:local-count local-result))))))))

(defn seal-repair-staging!
  [{:keys [repo operation target staging-owner-token] :as staging}]
  (let [graph-id (:graph-id operation)]
    (when-not (repair-staging-owner? repo graph-id staging-owner-token)
      (throw (ex-info "Repair staging target is no longer active"
                      {:type :db-sync/repair-staging-cancelled
                       :repo repo})))
    (.exec (:db target) "PRAGMA wal_checkpoint(TRUNCATE)")
    (when-let [conn (:conn target)]
      (reset! conn nil))
    (.close (:db target))
    staging))

(defn- <retry-failed-repair-cleanup!
  [repo graph-id owner-token error]
  (if-not (repair-staging-owner? repo graph-id owner-token)
    (p/resolved nil)
    (p/let [cleanup-results
            (p/all [(<capture-repair-cleanup-error (:target (ex-data error)))])]
      (if-let [cleanup-errors (seq (keep :error cleanup-results))]
        (log/error :db-sync/repair-staging-close-cleanup-failed
                   {:repo repo
                    :graph-id graph-id
                    :cleanup-error-count (count cleanup-errors)})
        (release-repair-staging-owner! repo graph-id owner-token)))))

(defn close-repair-staging-for-repo!
  "Invalidate the one transient staging owner during graph close. Cleanup is
  asynchronous because close-db itself is synchronous; the owner remains
  reserved until its resources have actually closed."
  [repo]
  (doseq [[[entry-repo graph-id] {:keys [build owner-token]}]
          @*repair-staging-builds
          :when (= repo entry-repo)]
    (reset! (:cancelled? owner-token) true)
    (-> build
        (p/then <cleanup-repair-staging!)
        (p/catch
         (fn [error]
           (when (= :db-sync/repair-staging-cleanup-failed
                    (:type (ex-data error)))
             (<retry-failed-repair-cleanup!
              repo graph-id owner-token error))))
        (p/catch
         (fn [error]
           (log/error :db-sync/repair-staging-close-failed
                      {:repo repo :graph-id graph-id :error error})))))
  nil)

(defn prepare-import!
  [repo reset? graph-id graph-e2ee? & [total-datoms]]
  (let [graph-e2ee? (if (nil? graph-e2ee?) true (true? graph-e2ee?))]
    (-> (p/let [close-db-f (require-thread-api-f! :thread-api/db-sync-close-db)
                unlink-db-f (require-thread-api-f! :thread-api/unsafe-unlink-db)
                recreate-lock-f (require-thread-api-f! :thread-api/db-sync-recreate-lock)
                invalidate-search-db-f (require-thread-api-f! :thread-api/db-sync-invalidate-search-db)
                create-or-open-db-f (require-thread-api-f! :thread-api/create-or-open-db)
                _ (when-let [state @*import-state]
                    (close-import-state! state)
                    (close-db-f (:repo state)))
                _ (reset! *import-state nil)
                _ (when reset? (close-db-f repo))
                _ (when reset? (unlink-db-f repo))
                _ (when reset? (recreate-lock-f repo))
                _ (when reset? (invalidate-search-db-f repo))
                import-id (str (random-uuid))
                aes-key (when graph-e2ee?
                          (sync-crypt/<fetch-graph-aes-key-for-download graph-id))
                _ (when (and graph-e2ee? (nil? aes-key))
                    (fail-fast :db-sync/missing-field {:repo repo :field :aes-key}))
                _ (create-or-open-db-f repo {:close-other-db? true
                                             :sync-download-graph? true})
                conn (worker-state/get-datascript-conn repo)
                _ (when-not conn
                    (fail-fast :db-sync/missing-field {:repo repo :field :datascript-conn}))]
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

(defn finalize-import!
  [repo graph-id remote-tx import-id]
  (-> (p/let [state (require-import-state! repo graph-id import-id)
              _ (when (:rows-imported? state)
                  (<replay-imported-rows! state))
              result (complete-datoms-import! repo graph-id remote-tx)
              _ (clear-import-state! import-id)]
        result)
      (p/catch (fn [error]
                 (when-not (= :db-sync/stale-import (:type (ex-data error)))
                   (clear-import-state! import-id))
                 (throw error)))))

(defn- set-graph-sync-metadata!
  [conn graph-id graph-e2ee?]
  (assert (uuid? graph-id))
  (ldb/transact! conn [(ldb/kv :logseq.kv/graph-uuid graph-id)
                       (ldb/kv :logseq.kv/graph-remote? true)
                       (ldb/kv :logseq.kv/graph-rtc-e2ee? (true? graph-e2ee?))]
    {:persist-op? false}))

(defn download-graph-by-id!
  [repo graph-id graph-e2ee?]
  (let [base (sync-auth/http-base-url @worker-state/*db-sync-config)]
    (if (and (seq repo) (seq graph-id) (seq base))
      (let [stage* (atom :init)
            import-id* (atom nil)
            log-f (fn [payload]
                    (rtc-log-and-state/rtc-log :rtc.log/download payload))]
        (-> (p/let [_ (log-f {:sub-type :download-progress
                              :graph-uuid graph-id
                              :message "Preparing graph snapshot download"})
                    _ (reset! stage* :fetch-pull)
                    pull-resp (fetch-json (str base "/sync/" graph-id "/pull")
                                          {:method "GET"}
                                          :sync/pull)
                    remote-tx (:t pull-resp)
                    _ (when-not (integer? remote-tx)
                        (throw (ex-info "non-integer remote-tx when downloading graph"
                                        {:repo repo
                                         :remote-tx remote-tx})))
                    _ (reset! stage* :fetch-snapshot-download)
                    snapshot-resp (fetch-json (str base "/sync/" graph-id "/snapshot/download")
                                              {:method "GET"}
                                              :sync/snapshot-download)
                    _ (when graph-e2ee?
                        (reset! stage* :prepare-e2ee)
                        (sync-crypt/<fetch-graph-aes-key-for-download graph-id))
                    _ (reset! stage* :fetch-snapshot-stream)
                    resp (js/fetch (:url snapshot-resp)
                                   (clj->js (with-auth-headers {:method "GET"})))
                    _ (log-f {:sub-type :download-progress
                              :graph-uuid graph-id
                              :message "Start downloading graph snapshot"})]
              (when-not (.-ok resp)
                (throw (ex-info "snapshot download failed"
                                {:repo repo
                                 :status (.-status resp)})))
              (let [ensure-import! (fn []
                                     (if-let [import-id @import-id*]
                                       (p/resolved import-id)
                                       (p/let [_ (reset! stage* :prepare-import)
                                               {:keys [import-id]} (prepare-import! repo true graph-id graph-e2ee?)]
                                         (reset! import-id* import-id)
                                         import-id)))]
                (p/let [_ (do
                            (reset! stage* :stream-snapshot)
                            (<stream-snapshot-row-batches!
                             resp
                             25000
                             (fn [rows]
                               (p/let [import-id (ensure-import!)]
                                 (import-rows-chunk! rows graph-id import-id)))))
                        _ (log-f {:sub-type :download-completed
                                  :graph-uuid graph-id
                                  :message "Graph snapshot downloaded"})
                        _ (when-let [import-id @import-id*]
                            (reset! stage* :finalize-import)
                            (finalize-import! repo graph-id remote-tx import-id))]
                  (when-let [conn (worker-state/get-datascript-conn repo)]
                    (set-graph-sync-metadata! conn (uuid graph-id) graph-e2ee?))
                  {:repo repo
                   :graph-id graph-id
                   :remote-tx remote-tx
                   :graph-e2ee? graph-e2ee?})))
            (p/catch (fn [error]
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
                                   :error error
                                   :error-stack (when (instance? js/Error error)
                                                  (.-stack error))
                                   :error-cause (when (instance? js/Error error)
                                                  (some-> (.-cause error) (.-message)))})
                       (throw (ex-info "db-sync download failed"
                                      {:repo repo
                                       :graph-id graph-id
                                       :graph-e2ee? graph-e2ee?
                                       :stage @stage*
                                       :code (:code (ex-data error))
                                       :error-message (or (ex-message error)
                                                           (when (instance? js/Error error)
                                                             (.-message error)))
                                       :error-cause (when (instance? js/Error error)
                                                      (some-> (.-cause error) (.-message)))}
                                       error))))))
      (p/rejected (ex-info "db-sync missing graph download info"
                           {:repo repo
                            :graph-id graph-id
                            :base base})))))
