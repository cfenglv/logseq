(ns logseq.db-sync.worker-large-op-memory-test
  (:require ["better-sqlite3" :as sqlite3]
            ["crypto" :as node-crypto]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db-sync.checksum :as checksum]
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

(defn- sha256-hex
  [value]
  (-> (.createHash node-crypto "sha256")
      (.update value "utf8")
      (.digest "hex")))

(defn- modern-upload-session-id
  [logical-tx-id outliner-op full-tx-data]
  (sha256-hex
   (protocol/tx->transit
    [:client-tx-upload-session-v1
     logical-tx-id
     outliner-op
     full-tx-data])))

(defn- modern-nonfinal-chunk-tx-id
  [logical-tx-id upload-session-id chunk-index]
  (let [raw (sha256-hex
             (str "logseq-tx-chunk-v1/"
                  logical-tx-id "/"
                  upload-session-id "/"
                  chunk-index))
        versioned (str (subs raw 0 12)
                       "5"
                       (subs raw 13 16)
                       "8"
                       (subs raw 17 32))]
    (uuid (str (subs versioned 0 8) "-"
               (subs versioned 8 12) "-"
               (subs versioned 12 16) "-"
               (subs versioned 16 20) "-"
               (subs versioned 20 32)))))

(defn- modern-session-entry
  [logical-tx-id upload-session-id outliner-op chunk-index chunk final?]
  {:tx (protocol/tx->transit chunk)
   :tx-id (if final?
            logical-tx-id
            (modern-nonfinal-chunk-tx-id
             logical-tx-id upload-session-id chunk-index))
   :logical-tx-id logical-tx-id
   :upload-session-id upload-session-id
   :chunk-index chunk-index
   :chunk-final? final?
   :outliner-op outliner-op})

(defn- modern-session-entries
  [logical-tx-id outliner-op tx-data]
  (let [upload-session-id
        (modern-upload-session-id logical-tx-id outliner-op tx-data)
        chunks (vec (partition-all 1000 tx-data))]
    (mapv (fn [index chunk]
            (modern-session-entry
             logical-tx-id upload-session-id outliner-op
             (* index 1000) (vec chunk) (= index (dec (count chunks)))))
          (range (count chunks))
          chunks)))

(defn- assert-modern-invisible!
  [sql conn fresh-conn t-before checksum-before block-uuids]
  (assert! (= t-before (storage/get-t sql))
           "nonfinal modern chunks must not advance visible t")
  (assert! (= checksum-before
              (checksum/recompute-server-checksum @conn))
           "nonfinal modern chunks must not change the current graph checksum")
  (assert! (= checksum-before
              (checksum/recompute-server-checksum @fresh-conn))
           "nonfinal modern chunks must not change a freshly reopened graph")
  (assert! (not (sample-blocks-present? @conn block-uuids))
           "nonfinal modern chunks must remain invisible on current conn")
  (assert! (not (sample-blocks-present? @fresh-conn block-uuids))
           "nonfinal modern chunks must remain invisible after reopen"))

(defn- run-modern-staged-memory-test!
  [rollback?]
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)
            _ (d/transact! conn [{:block/uuid page-uuid
                                  :block/name "modern-memory-page"
                                  :block/title "modern-memory-page"}])
            t-before (storage/get-t sql)
            checksum-before (checksum/recompute-server-checksum @conn)
            {:keys [tx-data block-uuids]} (large-block-insert-tx page-uuid 2000)
            logical-tx-id (random-uuid)
            entries (modern-session-entries
                     logical-tx-id :insert-blocks tx-data)
            prefix (pop entries)
            final-entry (peek entries)
            self #js {:sql sql :conn conn :schema-ready true}]
        (assert! (= 14000 (count tx-data))
                 "modern memory fixture must contain exactly 14k datoms")
        (assert! (> (count entries) 1)
                 "modern memory fixture must exercise nonfinal staging")
        (doseq [entry prefix]
          (let [response (with-redefs [ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch!
                            self nil [entry] t-before))]
            (assert! (= "tx/batch/ok" (:type response))
                     (str "nonfinal modern stage rejected: " (pr-str response)))))
        (let [fresh-before (storage/open-conn sql)]
          (assert-modern-invisible!
           sql conn fresh-before t-before checksum-before block-uuids))
        (if rollback?
          (let [original-append storage/append-tx!
                response
                (with-redefs [storage/append-tx!
                              (fn [& _]
                                (throw (js/Error. "injected modern final append failure")))
                              ws/broadcast! (fn [& _] nil)]
                  (sync-handler/handle-tx-batch!
                   self nil [final-entry] t-before))
                fresh-after (storage/open-conn sql)]
            ;; Keep an explicit reference so Closure cannot elide the real
            ;; production var before with-redefs restores it.
            (assert! (fn? original-append) "append-tx! must be callable")
            (assert! (= "tx/reject" (:type response))
                     (str "expected final fault rejection, got " (pr-str response)))
            (assert-modern-invisible!
             sql conn fresh-after t-before checksum-before block-uuids)
            (assert! (empty? (storage/fetch-tx-since sql t-before))
                     "failed final must leave no visible tx log row"))
          (let [response (with-redefs [ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch!
                            self nil [final-entry] t-before))
                fresh-after (storage/open-conn sql)
                recomputed (checksum/recompute-server-checksum @conn)]
            (assert! (= "tx/batch/ok" (:type response))
                     (str "expected modern final success, got " (pr-str response)))
            (assert! (= (inc t-before) (:t response))
                     "full logical tx advances visible t exactly once")
            (assert! (= 1 (count (storage/fetch-tx-since sql t-before)))
                     "full logical tx persists exactly one visible tx row")
            (assert! (sample-blocks-present? @conn block-uuids)
                     "final atomically exposes all sampled blocks")
            (assert! (sample-blocks-present? @fresh-after block-uuids)
                     "fresh reopen observes the committed full graph")
            (assert! (= recomputed
                        (checksum/recompute-server-checksum @fresh-after))
                     "current and fresh graph checksums converge")
            (assert! (= recomputed (storage/get-server-checksum sql))
                     "stored checksum equals full recomputation")
            (assert! (= (inc t-before)
                        (storage/get-server-checksum-t sql))
                     "stored checksum cursor follows the one logical commit")))))))

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
            tx-entries (mapv (fn [chunk]
                               {:tx (protocol/tx->transit chunk)
                                :tx-id tx-id
                                :outliner-op :insert-blocks})
                             (partition-all 392 tx-data))
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
    "apply-modern-staged" (run-modern-staged-memory-test! false)
    "apply-modern-staged-rollback" (run-modern-staged-memory-test! true)
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
