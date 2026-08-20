(ns logseq.db-sync.worker-semantic-integrity-guard-test
  (:require
   ["better-sqlite3" :as sqlite3]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as string]
   [datascript.core :as d]
   [logseq.db-sync.common :as common]
   [logseq.db-sync.storage :as storage]
   [logseq.db-sync.worker.handler.sync :as sync-handler]
   [logseq.db-sync.worker.ws :as ws]
   [logseq.outliner.page :as outliner-page]
   [promesa.core :as p]))

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

(defn- new-memory-sql
  []
  (let [db (new sqlite ":memory:" nil)]
    #js {:_db db
         :exec (fn [sql-str & args]
                 (let [stmt (.prepare db sql-str)]
                   (if (select-sql? sql-str)
                     (all-sql stmt args)
                     (do
                       (run-sql stmt args)
                       nil))))
         :close (fn [] (.close db))}))

(defn- with-memory-sql-async
  [f]
  (let [sql (new-memory-sql)]
    (-> (f sql)
        (p/finally #(.close sql)))))

(defn- semantic-create-page-request
  [title]
  (js/Request.
   "http://localhost/semantic/pages?graph-id=synthetic-graph"
   #js {:method "POST"
        :headers #js {"content-type" "application/json"}
        :body (js/JSON.stringify #js {:title title})}))

(defn- seed-healthy-legacy-graph!
  [conn]
  (d/transact!
   conn
   [{:db/ident :logseq.kv/graph-created-at
     :kv/value 1760000000000}
    {:block/uuid (random-uuid)
     :block/name "semantic-integrity-root"
     :block/title "Semantic integrity root"}]))

(deftest semantic-write-bootstraps-integrity-before-processing-test
  (testing "missing attestation on a healthy legacy graph is bootstrapped, not treated as corruption"
    (async done
      (with-memory-sql-async
        (fn [sql]
          (storage/init-schema! sql)
          (let [conn (storage/open-conn sql)
                _ (seed-healthy-legacy-graph! conn)
                self #js {:sql sql :conn conn :schema-ready true}
                page-id (random-uuid)
                calls (atom 0)
                t-before (storage/get-t sql)]
            (->
             (p/with-redefs
               [outliner-page/create!
                (fn [_conn title _opts]
                  (swap! calls inc)
                  (d/transact! conn
                               [{:block/uuid page-id
                                 :block/name "inbox"
                                 :block/title title}])
                  [title page-id])
                ws/broadcast! (fn [& _] nil)]
               (p/let [response
                       (sync-handler/handle-http
                        self (semantic-create-page-request "Inbox"))]
                 (is (= 201 (.-status response)))
                 (is (= 1 @calls))
                 (is (= (inc t-before) (storage/get-t sql)))
                 (is (storage/snapshot-integrity-attested? sql)
                     "the semantic transaction must advance the attested root and cursor")
                 (is (map?
                      (storage/snapshot-integrity-attestation sql))
                     "the successful request must leave an exact-root attestation")))
             (p/catch (fn [error]
                        (is false (str error))))
             (p/finally done))))))))

(deftest semantic-write-fails-closed-on-unattested-corrupt-live-root-test
  (testing "a corrupt raw root rejects the semantic write without advancing live cursor or tx-log"
    (async done
      (with-memory-sql-async
        (fn [sql]
          (storage/init-schema! sql)
          (let [conn (storage/open-conn sql)
                _ (seed-healthy-legacy-graph! conn)
                self #js {:sql sql :conn conn :schema-ready true}
                page-id (random-uuid)
                calls (atom 0)]
            (->
             (p/let [metadata-response
                     (sync-handler/handle
                      {:self self
                       :request (js/Request.
                                 "http://localhost/sync/synthetic-graph/snapshot/download-v2?graph-id=synthetic-graph")
                       :url (js/URL.
                             "http://localhost/sync/synthetic-graph/snapshot/download-v2?graph-id=synthetic-graph")
                       :route {:handler :sync/snapshot-download-v2}})
                     _ (is (= 200 (.-status metadata-response)))
                     t-before (storage/get-t sql)
                     tx-log-before
                     (count (common/get-sql-rows
                             (common/sql-exec sql "select t from tx_log")))
                     raw-conn (storage/open-snapshot-conn sql "kvs")
                     _ (d/transact!
                        raw-conn
                        [[:db/retractEntity :logseq.kv/graph-created-at]])
                     response
                     (p/with-redefs
                       [outliner-page/create!
                        (fn [_conn title _opts]
                          (swap! calls inc)
                          [title page-id])
                        ws/broadcast! (fn [& _] nil)]
                       (sync-handler/handle-http
                        self (semantic-create-page-request "Must not persist")))]
               (is (= 409 (.-status response)))
               (is (= 0 @calls))
               (is (= t-before (storage/get-t sql)))
               (is (= tx-log-before
                      (count (common/get-sql-rows
                              (common/sql-exec sql "select t from tx_log"))))))
             (p/catch (fn [error]
                        (is false (str error))))
             (p/finally done))))))))

(deftest semantic-write-cannot-reseal-a-truncated-history-floor-test
  (testing "a current floor-aware attestation makes tx-log truncation fail closed"
    (async done
      (with-memory-sql-async
        (fn [sql]
          (storage/init-schema! sql)
          (let [conn (storage/open-conn sql)
                _ (seed-healthy-legacy-graph! conn)
                self #js {:sql sql :conn conn :schema-ready true}
                page-id (random-uuid)
                calls (atom 0)]
            (->
             (p/let [metadata-response
                     (sync-handler/handle
                      {:self self
                       :request (js/Request.
                                 "http://localhost/sync/synthetic-graph/snapshot/download-v2?graph-id=synthetic-graph")
                       :url (js/URL.
                             "http://localhost/sync/synthetic-graph/snapshot/download-v2?graph-id=synthetic-graph")
                       :route {:handler :sync/snapshot-download-v2}})
                     _ (is (= 200 (.-status metadata-response)))
                     t-before (storage/get-t sql)
                     floor-before (:tx-log-floor
                                   (storage/snapshot-integrity-attestation
                                    sql))
                     _ (common/sql-exec sql "delete from tx_log")
                     response
                     (p/with-redefs
                       [outliner-page/create!
                        (fn [_conn title _opts]
                          (swap! calls inc)
                          [title page-id])
                        ws/broadcast! (fn [& _] nil)]
                       (sync-handler/handle-http
                        self (semantic-create-page-request "Must not reseal")))]
               (is (= 0 floor-before))
               (is (= 409 (.-status response)))
               (is (= 0 @calls))
               (is (= t-before (storage/get-t sql)))
               (is (empty? (common/get-sql-rows
                            (common/sql-exec sql "select t from tx_log"))))
               (is (storage/snapshot-integrity-history-conflict? sql)))
             (p/catch (fn [error]
                        (is false (str error))))
             (p/finally done))))))))

(deftest semantic-write-cannot-cross-active-snapshot-upload-generation-test
  (testing "an active snapshot upload rejects semantic mutation without touching live state and retry succeeds"
    (async done
      (with-memory-sql-async
        (fn [sql]
          (storage/init-schema! sql)
          (let [conn (storage/open-conn sql)
                _ (seed-healthy-legacy-graph! conn)
                self #js {:sql sql :conn conn :schema-ready true}
                page-id (random-uuid)
                calls (atom 0)
                t-before (storage/get-t sql)]
            (storage/set-meta! sql :snapshot-upload-id "semantic-gate")
            (storage/set-meta! sql :snapshot-upload-status "active")
            (storage/set-meta!
             sql :snapshot-upload-started-at 1760000000000)
            (storage/set-meta! sql :snapshot-uploading? true)
            (->
             (p/with-redefs
               [outliner-page/create!
                (fn [_conn title _opts]
                  (swap! calls inc)
                  [title page-id])
                ws/broadcast! (fn [& _] nil)]
               (p/let [blocked-response
                       (sync-handler/handle-http
                        self (semantic-create-page-request "Blocked"))
                       _ (doseq [key [:snapshot-upload-id
                                      :snapshot-upload-status
                                      :snapshot-upload-started-at
                                      :snapshot-uploading?]]
                           (storage/delete-meta! sql key))
                       retry-response
                       (sync-handler/handle-http
                        self (semantic-create-page-request "Retry"))]
                 (is (= 409 (.-status blocked-response)))
                 (is (= t-before (storage/get-t sql)))
                 (is (= 201 (.-status retry-response)))
                 (is (= 1 @calls)
                     "only the post-abort retry may reach the outliner")))
             (p/catch (fn [error]
                        (is false (str error))))
             (p/finally done))))))))
