(ns logseq.db-sync.worker-large-op-memory-test
  (:require ["better-sqlite3" :as sqlite3]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.ws :as ws]
            [logseq.db.frontend.validate :as db-validate]))

(def sqlite (if (find-ns 'nbb.core) (aget sqlite3 "default") sqlite3))

(defn- select-sql?
  [sql]
  (string/starts-with? (-> sql string/trim string/lower-case) "select"))

(defn- run-sql
  [^js stmt args]
  (.apply (.-run stmt) stmt (to-array args)))

(defn- all-sql
  [^js stmt args]
  (.apply (.-all stmt) stmt (to-array args)))

(defn- with-memory-sql
  [f]
  (let [db (new sqlite ":memory:" nil)
        sql #js {:_db db
                 :exec (fn [sql-str & args]
                         (let [stmt (.prepare db sql-str)]
                           (if (select-sql? sql-str)
                             (all-sql stmt args)
                             (do
                               (run-sql stmt args)
                               nil))))
                 :close (fn []
                          (.close db))}]
    (try
      (f sql)
      (finally
        (.close sql)))))

(defn- large-block-insert-tx
  [page-uuid block-count]
  (let [block-uuids (mapv (fn [_idx] (random-uuid)) (range block-count))]
    {:block-uuids block-uuids
     :tx-data
     (vec
      (mapcat (fn [idx]
                (let [block-uuid (nth block-uuids idx)
                      eid (str block-uuid)]
                  [[:db/add eid :block/uuid block-uuid idx]
                   [:db/add eid :block/title (str "large-memory-block-" idx) idx]
                   [:db/add eid :block/page [:block/uuid page-uuid] idx]
                   [:db/add eid :block/parent [:block/uuid page-uuid] idx]
                   [:db/add eid :block/order "a0" idx]
                   [:db/add eid :block/created-at idx idx]
                   [:db/add eid :block/updated-at idx idx]]))
              (range block-count)))}))

(defn- sample-blocks-present?
  [db block-uuids]
  (every? (fn [idx]
            (some? (d/entity db [:block/uuid (nth block-uuids idx)])))
          [0 (quot (count block-uuids) 2) (dec (count block-uuids))]))

(defn- assert!
  [condition message]
  (when-not condition
    (throw (js/Error. message))))

(defn- run-large-op-memory-test!
  [{:keys [skip-final-store? skip-validation?]}]
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)
            _ (d/transact! conn [{:block/uuid page-uuid
                                  :block/name "large-memory-page"
                                  :block/title "large-memory-page"}])
            t-before (storage/get-t sql)
            {:keys [tx-data block-uuids]} (large-block-insert-tx page-uuid 2000)
            tx-entry {:tx (protocol/tx->transit tx-data)
                      :tx-id (random-uuid)
                      :outliner-op :insert-blocks}
            self #js {:sql sql
                      :conn conn
                      :schema-ready true}
            tx-report-count (atom 0)
            response (try
                       (d/listen! conn ::large-op-memory-chunks
                                  (fn [_tx-report]
                                    (swap! tx-report-count inc)))
                       (with-redefs [d/store (if skip-final-store?
                                               (fn [_db] nil)
                                               d/store)
                                     db-validate/validate-tx-report
                                     (if skip-validation?
                                       (fn [_tx-report _options] [true nil])
                                       db-validate/validate-tx-report)
                                     ws/broadcast! (fn [& _] nil)]
                         (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))
                       (finally
                         (d/unlisten! conn ::large-op-memory-chunks)))]
        (assert! (= "tx/batch/ok" (:type response))
                 (str "expected tx/batch/ok, got " (pr-str response)))
        (assert! (> @tx-report-count 1)
                 (str "expected more than one chunk, got " @tx-report-count))
        (assert! (= (+ t-before @tx-report-count) (:t response))
                 (str "expected response t to advance by visible chunks, got " (:t response)))
        (assert! (= @tx-report-count (count (storage/fetch-tx-since sql t-before)))
                 "expected large tx chunks to be persisted as visible tx log entries")
        (assert! (sample-blocks-present? @conn block-uuids)
                 "expected sampled large tx blocks to be persisted")))))

(defn- run-client-split-large-op-memory-test!
  [{:keys [serial?]}]
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)
            _ (d/transact! conn [{:block/uuid page-uuid
                                  :block/name "large-memory-page"
                                  :block/title "large-memory-page"}])
            t-before (storage/get-t sql)
            {:keys [tx-data block-uuids]} (large-block-insert-tx page-uuid 2000)
            tx-id (random-uuid)
            chunks (vec (partition-all 392 tx-data))
            tx-entries (mapv (fn [idx chunk]
                               (cond-> {:tx (protocol/tx->transit chunk)
                                        :outliner-op :insert-blocks}
                                 (= idx (dec (count chunks)))
                                 (assoc :tx-id tx-id)))
                             (range)
                             chunks)
            self #js {:sql sql
                      :conn conn
                      :schema-ready true}
            tx-report-count (atom 0)
            response (try
                       (d/listen! conn ::large-op-memory-client-split
                                  (fn [_tx-report]
                                    (swap! tx-report-count inc)))
                       (with-redefs [ws/broadcast! (fn [& _] nil)]
                         (if serial?
                           (loop [t-before* t-before
                                  [tx-entry & more] tx-entries
                                  response nil]
                             (if tx-entry
                               (let [response* (sync-handler/handle-tx-batch! self nil [tx-entry] t-before*)]
                                 (assert! (= "tx/batch/ok" (:type response*))
                                          (str "expected tx/batch/ok, got " (pr-str response*)))
                                 (recur (:t response*) more response*))
                               response))
                           (sync-handler/handle-tx-batch! self nil tx-entries t-before)))
                       (finally
                         (d/unlisten! conn ::large-op-memory-client-split)))]
        (assert! (= "tx/batch/ok" (:type response))
                 (str "expected tx/batch/ok, got " (pr-str response)))
        (assert! (= (count tx-entries) @tx-report-count)
                 (str "expected one tx report per client split entry, got " @tx-report-count))
        (assert! (= (+ t-before @tx-report-count) (:t response))
                 (str "expected response t to advance by client split entries, got " (:t response)))
        (assert! (= @tx-report-count (count (storage/fetch-tx-since sql t-before)))
                 "expected client split tx entries to be persisted as visible tx log entries")
        (assert! (sample-blocks-present? @conn block-uuids)
                 "expected sampled client split blocks to be persisted")))))

(defn- modern-staged-entry
  [logical-tx-id session-id outliner-op chunk-index final? chunk]
  {:tx (protocol/tx->transit chunk)
   :tx-id (protocol/tx-chunk-id logical-tx-id session-id chunk-index final?)
   :logical-tx-id logical-tx-id
   :upload-session-id session-id
   :chunk-index chunk-index
   :chunk-final? final?
   :outliner-op outliner-op})

(defn- run-modern-staged-large-op-memory-test!
  [{:keys [rollback? reopen?]}]
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)
            _ (d/transact! conn [{:block/uuid page-uuid
                                  :block/name "modern-staged-memory-page"
                                  :block/title "modern-staged-memory-page"}])
            t-before (storage/get-t sql)
            {:keys [tx-data block-uuids]} (large-block-insert-tx page-uuid 2000)
            missing-uuid (random-uuid)
            full-tx-data (if rollback?
                           (assoc tx-data (dec (count tx-data))
                                  [:db/add [:block/uuid missing-uuid]
                                   :block/title "modern-staged-missing" 0])
                           tx-data)
            logical-tx-id (random-uuid)
            outliner-op :insert-blocks
            session-id (protocol/tx-upload-session-id
                        logical-tx-id outliner-op full-tx-data)
            boundaries [[0 5000] [5000 10000] [10000 14000]]
            self #js {:sql sql :conn conn :schema-ready true}
            responses
            (with-redefs [ws/broadcast! (fn [& _] nil)]
              (mapv (fn [[start end]]
                      (sync-handler/handle-tx-batch!
                       self nil
                       [(modern-staged-entry
                         logical-tx-id session-id outliner-op start
                         (= end (count full-tx-data))
                         (subvec full-tx-data start end))]
                       (storage/get-t sql)))
                    boundaries))
            final-response (last responses)
            upload (storage/client-tx-upload sql logical-tx-id)]
        (assert! (= 14000 (count full-tx-data))
                 "expected a true 14k-datom modern logical transaction")
        (doseq [response (butlast responses)]
          (assert! (= "tx/batch/ok" (:type response))
                   (str "expected staged chunk ok, got " (pr-str response)))
          (assert! (= t-before (:t response))
                   "nonfinal modern chunks must not mutate the graph"))
        (if rollback?
          (do
            (assert! (= "tx/reject" (:type final-response))
                     (str "expected final rollback, got " (pr-str final-response)))
            (assert! (= t-before (storage/get-t sql))
                     "failed final assembly must roll back every graph chunk")
            (assert! (= "active" (:status upload))
                     "failed final must leave the acknowledged prefix resumable")
            (assert! (= 10000 (:next-index upload))
                     "failed final staging row must roll back atomically")
            (assert! (not (sample-blocks-present? @conn block-uuids))
                     "failed final must not expose partially assembled blocks"))
          (do
            (assert! (= "tx/batch/ok" (:type final-response))
                     (str "expected modern final ok, got " (pr-str final-response)))
            (assert! (> (:t final-response) t-before)
                     "modern final must publish the assembled logical tx")
            (assert! (= "completed" (:status upload))
                     "modern final must retain a bounded completion marker")
            (assert! (empty? (storage/client-tx-upload-chunks sql session-id))
                     "modern completion must consolidate staged chunks")
            (assert! (sample-blocks-present? @conn block-uuids)
                     "modern final must persist sampled blocks")
            (when reopen?
              (let [fresh (storage/open-conn sql)]
                (assert! (sample-blocks-present? @fresh block-uuids)
                         "fresh reopen must reconstruct the committed modern tx")
                (assert! (= (storage/get-checksum sql)
                            (sync-checksum/recompute-checksum @fresh))
                         "fresh reopen checksum must match the committed metadata")))))))))

(defn- run-open-sql-memory-test!
  []
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)]
        (d/transact! conn [{:block/uuid page-uuid
                            :block/name "large-memory-page"
                            :block/title "large-memory-page"}])
        (assert! (some? (d/entity @conn [:block/uuid page-uuid]))
                 "expected setup page to be persisted")))))

(defn- run-prepare-memory-test!
  []
  (let [page-uuid (random-uuid)
        {:keys [tx-data]} (large-block-insert-tx page-uuid 2000)
        tx-entry {:tx (protocol/tx->transit tx-data)
                  :tx-id (random-uuid)
                  :outliner-op :insert-blocks}]
    (assert! (= 14000 (count tx-data))
             "expected generated tx data")
    (assert! (string? (:tx tx-entry))
             "expected transit tx payload")))

(defn- run-mode!
  [mode]
  (case mode
    "baseline" nil
    "open-sql" (run-open-sql-memory-test!)
    "prepare" (run-prepare-memory-test!)
    "apply" (run-large-op-memory-test! nil)
    "apply-client-split" (run-client-split-large-op-memory-test! nil)
    "apply-client-split-serial" (run-client-split-large-op-memory-test! {:serial? true})
    "apply-modern-staged" (run-modern-staged-large-op-memory-test! nil)
    "apply-modern-staged-rollback" (run-modern-staged-large-op-memory-test! {:rollback? true})
    "apply-modern-staged-reopen" (run-modern-staged-large-op-memory-test! {:reopen? true})
    "apply-no-store" (run-large-op-memory-test! {:skip-final-store? true})
    "apply-no-validation" (run-large-op-memory-test! {:skip-validation? true})
    "apply-no-store-validation" (run-large-op-memory-test! {:skip-final-store? true
                                                            :skip-validation? true})
    (run-large-op-memory-test! nil)))

(defn main [& args]
  (try
    (let [mode (or (first args) "apply")]
      (run-mode! mode)
      (js/console.log (str "large op memory test passed: " mode)))
    (js/process.exit 0)
    (catch :default error
      (js/console.error error)
      (js/process.exit 1))))
