(ns logseq.db-sync.worker-batch-integrity-guard-test
  (:require
   ["better-sqlite3" :as sqlite3]
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [datascript.core :as d]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db-sync.common :as common]
   [logseq.db-sync.protocol :as protocol]
   [logseq.db-sync.storage :as storage]
   [logseq.db-sync.worker.handler.sync :as sync-handler]
   [logseq.db-sync.worker.ws :as ws]
   [logseq.db.sqlite.export :as sqlite-export]))

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
                               nil))))}]
    (try
      (storage/init-schema! sql)
      (f sql)
      (finally
        (.close db)))))

(defn- seeded-attested-self!
  [sql]
  (let [page-uuid (random-uuid)
        seed-conn (sqlite-export/create-conn)]
    (d/transact!
     seed-conn
     [{:db/ident :logseq.kv/graph-created-at
       :kv/value 1760000000000}
      {:block/uuid page-uuid
       :block/name "batch-integrity-page"
       :block/title "before"
       :block/tags :logseq.class/Page
       :block/created-at 1
       :block/updated-at 1}])
    (d/store @seed-conn (storage/new-sqlite-storage sql))
    (let [conn (storage/open-conn sql)
          t (storage/get-t sql)]
      (storage/set-checksum!
       sql (sync-checksum/recompute-checksum @conn))
      (storage/set-server-checksum!
       sql (sync-checksum/recompute-server-checksum @conn) t)
      (storage/mark-checksum-metadata-verified! sql t)
      (storage/seal-verified-snapshot-integrity!
       sql
       (storage/verified-snapshot-integrity-descriptor
        sql @conn t "synthetic-batch-generation"))
      {:sql sql
       :conn conn
       :self #js {:sql sql :conn conn :schema-ready true}
       :page-uuid page-uuid})))

(defn- edit-entry
  ([page-uuid idx]
   (edit-entry page-uuid idx (random-uuid)))
  ([page-uuid idx tx-id]
   {:tx-id tx-id
    :tx (protocol/tx->transit
         [[:db/add [:block/uuid page-uuid]
           :block/title (str "edit-" idx)]])
    :outliner-op :save-block}))

(defn- apply-batch!
  [self entries]
  (with-redefs [ws/broadcast! (fn [& _] nil)]
    (sync-handler/handle-tx-batch!
     self nil entries (storage/get-t (.-sql ^js self)))))

(defn- tx-log-count
  [sql]
  (-> (common/sql-exec sql "select count(*) as n from tx_log")
      common/get-sql-rows
      first
      (aget "n")))

(deftest healthy-fifty-entry-batch-validates-log-chain-once-test
  (testing "one serial batch reuses its proven generation without rescanning tx_log per entry"
    (with-memory-sql
      (fn [sql]
        (let [{:keys [conn self page-uuid]} (seeded-attested-self! sql)
              entries (mapv #(edit-entry page-uuid %) (range 50))
              original-exec (.-exec sql)
              tx-log-selects (atom [])]
          (set! (.-exec sql)
                (fn [sql-str & args]
                  (let [normalized (-> sql-str string/trim string/lower-case)]
                    (when (and (string/starts-with? normalized "select")
                               (string/includes? normalized "from tx_log"))
                      (swap! tx-log-selects conj normalized))
                    (.apply original-exec sql
                            (to-array (cons sql-str args))))))
          (let [response (try
                           (apply-batch! self entries)
                           (finally
                             (set! (.-exec sql) original-exec)))]
            (is (= "tx/batch/ok" (:type response)))
            (is (= 50 (:t response) (storage/get-t sql)))
            (is (= 50 (tx-log-count sql)))
            (is (= "edit-49"
                   (:block/title
                    (d/entity @conn [:block/uuid page-uuid]))))
            (is (= (sync-checksum/recompute-checksum @conn)
                   (storage/get-checksum sql)))
            (is (= (sync-checksum/recompute-server-checksum @conn)
                   (storage/get-server-checksum sql)))
            (is (= 50 (storage/get-server-checksum-t sql)))
            (is (storage/snapshot-integrity-attested? sql))
            (is (<= (count @tx-log-selects) 2)
                (str "a 50-entry batch must not rescan tx_log per entry; observed "
                     (count @tx-log-selects)))))))))

(deftest failed-middle-entry-keeps-sealed-prefix-and-retry-safe-test
  (with-memory-sql
    (fn [sql]
      (let [{:keys [conn self page-uuid]} (seeded-attested-self! sql)
            good-prefix (mapv #(edit-entry page-uuid %) (range 25))
            failed-id (random-uuid)
            failed-entry
            {:tx-id failed-id
             :tx (protocol/tx->transit
                  [[:db/add [:block/uuid (random-uuid)]
                    :block/title "missing"]])
             :outliner-op :save-block}
            tail (mapv #(edit-entry page-uuid %) (range 26 50))
            response (apply-batch!
                      self (into good-prefix (cons failed-entry tail)))]
        (is (= "tx/reject" (:type response)))
        (is (= failed-id (:failed-tx-id response)))
        (is (= 25 (:t response) (storage/get-t sql) (tx-log-count sql)))
        (is (= 25 (count (:success-tx-ids response))))
        (is (= "edit-24"
               (:block/title (d/entity @conn [:block/uuid page-uuid]))))
        (is (storage/snapshot-integrity-attested? sql))
        (let [retry (apply-batch!
                     self
                     (into [(edit-entry page-uuid 25 failed-id)] tail))]
          (is (= "tx/batch/ok" (:type retry)))
          (is (= 50 (:t retry) (storage/get-t sql) (tx-log-count sql)))
          (is (= "edit-49"
                 (:block/title
                  (d/entity @conn [:block/uuid page-uuid]))))
          (is (storage/snapshot-integrity-attested? sql)))))))

(deftest duplicate-identity-rejects-before-mutating-attested-live-test
  (with-memory-sql
    (fn [sql]
      (let [{:keys [conn self page-uuid]} (seeded-attested-self! sql)
            duplicate-id (random-uuid)
            response (apply-batch!
                      self [(edit-entry page-uuid 1 duplicate-id)
                            (edit-entry page-uuid 2 duplicate-id)])]
        (is (= "tx/reject" (:type response)))
        (is (zero? (storage/get-t sql)))
        (is (zero? (tx-log-count sql)))
        (is (= "before"
               (:block/title (d/entity @conn [:block/uuid page-uuid]))))
        (is (storage/snapshot-integrity-attested? sql))))))

(deftest legacy-nil-tx-id-batch-keeps-contiguous-sealed-history-test
  (with-memory-sql
    (fn [sql]
      (let [{:keys [conn self page-uuid]} (seeded-attested-self! sql)
            entries (mapv #(dissoc (edit-entry page-uuid %) :tx-id)
                          (range 3))
            response (apply-batch! self entries)
            tx-id-rows
            (mapv #(aget % "tx_id")
                  (common/get-sql-rows
                   (common/sql-exec
                    sql "select tx_id from tx_log order by t")))]
        (is (= "tx/batch/ok" (:type response)))
        (is (= 3 (:t response) (storage/get-t sql) (tx-log-count sql)))
        (is (= [nil nil nil] tx-id-rows))
        (is (= "edit-2"
               (:block/title (d/entity @conn [:block/uuid page-uuid]))))
        (is (storage/snapshot-integrity-attested? sql))))))
