(ns logseq.db-sync.storage-test
  (:require ["better-sqlite3" :as sqlite3]
            [clojure.string :as string]
            [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.db :as ldb]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.storage :as storage]
            [logseq.db.common.normalize :as db-normalize]
            [logseq.db-sync.test-sql :as test-sql]))

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
        sql #js {:exec (fn [sql-str & args]
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

(defn- seeded-rng
  [seed0]
  (let [state (atom (bit-or (long seed0) 0))]
    (fn []
      (let [s (swap! state
                     (fn [x]
                       (let [x (bit-xor x (bit-shift-left x 13))
                             x (bit-xor x (bit-shift-right x 17))
                             x (bit-xor x (bit-shift-left x 5))]
                         (bit-or x 0))))]
        (/ (double (unsigned-bit-shift-right s 0)) 4294967296.0)))))

(defn- rand-int*
  [rng n]
  (js/Math.floor (* (rng) n)))

(defn- pick-rand
  [rng coll]
  (when (seq coll)
    (nth coll (rand-int* rng (count coll)))))

(deftest versioned-server-checksum-listener-updates-independently-test
  (with-memory-sql
    (fn [sql]
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)
            block-uuid (random-uuid)]
        (ldb/transact!
         conn
         [{:block/uuid page-uuid
           :block/name "server-checksum-page"
           :block/title "Server checksum page"}
          {:block/uuid block-uuid
           :block/title "short"
           :block/parent [:block/uuid page-uuid]
           :block/page [:block/uuid page-uuid]}])
        (storage/set-server-checksum!
         sql
         (sync-checksum/recompute-server-checksum @conn)
         (storage/get-t sql))
        (ldb/transact!
         conn
         [[:db/add [:block/uuid block-uuid]
           :block/title
           (apply str (repeat 5000 "x"))]
          [:db/add [:block/uuid block-uuid]
           :logseq.property.sync/large-title-object
           {:asset-uuid "listener-large-title"
            :asset-type "txt"
            :payload-format "utf8-plain-v1"
            :payload-digest-alg "sha256-v1"
            :payload-digest (apply str (repeat 64 "a"))}]])
        (is (= (sync-checksum/recompute-server-checksum @conn)
               (storage/get-server-checksum sql)))
        (is (= (storage/get-t sql)
               (storage/get-server-checksum-t sql)))))))

(deftest versioned-server-checksum-recomputes-when-first-upgrade-action-is-write-test
  (with-memory-sql
    (fn [sql]
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)]
        (ldb/transact!
         conn
         [{:block/uuid page-uuid
           :block/name "checksum-rollback-page"
           :block/title "Before rollback"}])
        (storage/set-server-checksum!
         sql
         (sync-checksum/recompute-server-checksum @conn)
         (storage/get-t sql))

        ;; Model an old server write: DB and cursor advance, while the additive
        ;; versioned checksum metadata remains at the prior cursor.
        (ldb/transact!
         conn
         [[:db/add [:block/uuid page-uuid]
           :block/title
           "Written during rollback"]]
         {:db-sync/skip-checksum-update? true})
        (is (not= (storage/get-t sql)
                  (storage/get-server-checksum-t sql)))

        ;; The first action after upgrading is another write, not a hello/pull
        ;; that would otherwise repair metadata. It must recompute instead of
        ;; extending and blessing the stale checksum.
        (ldb/transact!
         conn
         [[:db/add [:block/uuid page-uuid]
           :block/title
           "First write after upgrade"]])
        (is (= (sync-checksum/recompute-server-checksum @conn)
               (storage/get-server-checksum sql)))
        (is (= (storage/get-t sql)
               (storage/get-server-checksum-t sql)))))))

(defn- normal-block-uuids
  [db]
  (->> (d/datoms db :avet :block/uuid)
       (map :e)
       distinct
       (keep (fn [eid]
               (let [ent (d/entity db eid)]
                 (when (and (uuid? (:block/uuid ent))
                            (not (ldb/built-in? ent))
                            (nil? (:block/name ent))
                            (some? (:block/page ent)))
                   (:block/uuid ent)))))
       vec))

(deftest cloudflare-transaction-sync-rolls-back-all-sql-writes-test
  (testing "the Durable Object transactionSync adapter is used atomically"
    (let [db (new sqlite ":memory:" nil)
          calls (atom 0)
          sql #js {:exec (fn [sql-str & args]
                           (let [stmt (.prepare db sql-str)]
                             (if (select-sql? sql-str)
                               (all-sql stmt args)
                               (do
                                 (run-sql stmt args)
                                 nil))))}]
      (try
        (storage/init-schema! sql)
        (storage/register-transaction-sync!
         sql
         (fn [f]
           (swap! calls inc)
           (let [tx-fn (.transaction db f)]
             (tx-fn))))
        (let [result (try
                       (storage/with-sql-transaction!
                        sql
                        (fn []
                          (storage/set-meta! sql :t 12)
                          (throw (js/Error. "abort transaction"))))
                       :unexpected-success
                       (catch :default error
                         error))]
          (is (instance? js/Error result))
          (is (= 1 @calls))
          (is (= 0 (storage/get-t sql))
              "a failed callback must not leave partial Durable Object writes"))
        (finally
          (.close db))))))

(deftest t-meta-test
  (let [sql (test-sql/make-sql)]
    (storage/init-schema! sql)
    (is (= 0 (storage/get-t sql)))
    (storage/set-t! sql 1)
    (is (= 1 (storage/get-t sql)))
    (storage/set-t! sql 2)
    (is (= 2 (storage/get-t sql)))))

(deftest tx-log-test
  (let [sql (test-sql/make-sql)]
    (storage/init-schema! sql)
    (storage/append-tx! sql 1 "tx-1" 100 :save-block)
    (storage/append-tx! sql 2 "tx-2" 200 :move-blocks)
    (storage/append-tx! sql 3 "tx-3" 300 nil)
    (let [result (storage/fetch-tx-since sql 1)]
      (is (= [{:t 2 :tx "tx-2" :outliner-op :move-blocks}
              {:t 3 :tx "tx-3" :outliner-op nil}]
             result)))))

(deftest stale-checksum-no-op-transact-does-not-throw-test
  (testing "a no-op tx should not throw and should keep incremental checksum state"
    (with-memory-sql
      (fn [sql]
        (let [stale-checksum "f4b78e83776d45fb"]
          (storage/init-schema! sql)
          (storage/set-checksum! sql stale-checksum)
          (let [conn (storage/open-conn sql)
                result (try
                         (d/transact! conn
                                      []
                                      {:outliner-op :rebase})
                         :ok
                         (catch :default e
                           e))]
            (is (= :ok result))
            (is (= stale-checksum
                   (storage/get-checksum sql)))))))))

(deftest initial-checksum-does-not-overwrite-existing-checksum-test
  (testing "snapshot checksum initialization must not replace an existing incremental checksum"
    (let [sql (test-sql/make-sql)
          existing-checksum "aaaaaaaaaaaaaaaa"
          snapshot-checksum "bbbbbbbbbbbbbbbb"]
      (storage/init-schema! sql)
      (storage/set-initial-checksum! sql existing-checksum)
      (let [result (try
                     (storage/set-initial-checksum! sql snapshot-checksum)
                     :ok
                     (catch :default error
                       error))]
        (is (not= :ok result))
        (is (= existing-checksum (storage/get-checksum sql)))))))

(deftest initial-checksum-rejects-non-empty-tx-log-test
  (testing "snapshot checksum initialization must not run after tx history already advanced"
    (let [sql (test-sql/make-sql)
          snapshot-checksum "bbbbbbbbbbbbbbbb"]
      (storage/init-schema! sql)
      (storage/append-tx! sql 1 "tx-1" 100 :save-block)
      (storage/set-t! sql 1)
      (let [result (try
                     (storage/set-initial-checksum! sql snapshot-checksum)
                     :ok
                     (catch :default error
                       error))]
        (is (not= :ok result))
        (is (nil? (storage/get-checksum sql)))))))

(deftest stale-checksum-transact-keeps-kvs-and-tx-log-consistent-test
  (testing "stale checksum should not fail transact; kvs and tx_log/t should advance together"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              stale-checksum "ffffffffffffffff"
              page-uuid (random-uuid)]
          ;; Use a stale checksum and ensure append path remains consistent.
          (storage/set-checksum! sql stale-checksum)
          (let [result (try
                         (d/transact! conn [{:block/uuid page-uuid
                                             :block/name "repro-kvs-ahead-page"}])
                         :ok
                         (catch :default e
                           e))]
            (is (= :ok result))
            (is (= 1 (storage/get-t sql)))
            (is (= 1 (count (storage/fetch-tx-since sql 0))))
            (is (not= stale-checksum (storage/get-checksum sql)))
            (let [restored-conn (storage/open-conn sql)]
              (is (= page-uuid
                     (:block/uuid (d/entity @restored-conn [:block/uuid page-uuid])))))))))))

(deftest tx-log-append-failure-does-not-advance-checksum-test
  (testing "server checksum and t should not advance when the tx log append fails"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              failed-block-uuid (random-uuid)]
          (d/transact! conn [{:block/uuid page-uuid
                              :block/name "tx-log-failure-repro"}])
          (let [checksum-before (storage/get-checksum sql)
                t-before (storage/get-t sql)
                original-sql-exec common/sql-exec
                result (with-redefs [common/sql-exec
                                      (fn [sql sql-str & args]
                                        (if (string/includes? sql-str "insert into tx_log")
                                          (throw (js/Error. "tx log append failed"))
                                          (apply original-sql-exec sql sql-str args)))]
                         (try
                           (d/transact! conn [{:block/uuid failed-block-uuid
                                               :block/title "failed append"
                                               :block/page [:block/uuid page-uuid]
                                               :block/parent [:block/uuid page-uuid]}])
                           :ok
                           (catch :default error
                             error)))]
            (is (not= :ok result))
            (is (= t-before (storage/get-t sql)))
            (is (= checksum-before (storage/get-checksum sql)))))))))

(deftest normalize-drop-can-hide-kvs-mutation-from-tx-log-test
  (testing "if normalize drops tx payload, tx_log can miss persisted kvs state changes"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)]
          (with-redefs [db-normalize/normalize-tx-data (fn [_db-after _db-before _tx-data]
                                                         [])]
            (d/transact! conn [{:block/uuid page-uuid
                                :block/name "normalize-drop-repro"}]))
          (is (= 1 (storage/get-t sql)))
          (let [entries (storage/fetch-tx-since sql 0)]
            (is (= 1 (count entries)))
            (is (= []
                   (common/read-transit (:tx (first entries)))))
            (let [restored-conn (storage/open-conn sql)]
              (is (= page-uuid
                     (:block/uuid (d/entity @restored-conn [:block/uuid page-uuid])))))))))))

(deftest randomized-normal-block-retract-recreate-does-not-throw-checksum-mismatch-test
  (testing "normal block retract/recreate patterns should not naturally trigger server checksum mismatch"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              rng (seeded-rng 424242)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "repro-page"
                                    :block/title "repro-page"}])
              _ (d/transact! conn (mapv (fn [idx]
                                          {:block/uuid (random-uuid)
                                           :block/title (str "seed-" idx)
                                           :block/page [:block/uuid page-uuid]
                                           :block/parent [:block/uuid page-uuid]
                                           :block/order (str "a" idx)})
                                        (range 5)))
              *mismatch (atom nil)]
          (dotimes [step 500]
            (when-not @*mismatch
              (let [db @conn
                    blocks (normal-block-uuids db)
                    target (pick-rand rng blocks)
                    sibling (pick-rand rng (remove #(= % target) blocks))
                    add-fresh? (< (rng) 0.3)
                    tx-data (cond-> [[:db/retractEntity [:block/uuid target]]
                                     [:db/add -1 :block/uuid target]
                                     [:db/add -1 :block/title (str "rr-" step)]
                                     [:db/add -1 :block/page [:block/uuid page-uuid]]
                                     [:db/add -1 :block/parent [:block/uuid page-uuid]]
                                     [:db/add -1 :block/order (str "z" (mod step 7))]]
                              sibling
                              (conj [:db/add [:block/uuid sibling] :block/title (str "sib-" step)])
                              add-fresh?
                              (into [[:db/add -2 :block/uuid (random-uuid)]
                                     [:db/add -2 :block/title (str "fresh-" step)]
                                     [:db/add -2 :block/page [:block/uuid page-uuid]]
                                     [:db/add -2 :block/parent [:block/uuid page-uuid]]
                                     [:db/add -2 :block/order (str "x" (mod step 9))]]))]
                (try
                  (d/transact! conn tx-data)
                  (catch :default e
                    (let [message (or (ex-message e) (some-> e .-message) (str e))]
                      (if (string/includes? message "server checksum doesn't match")
                        (reset! *mismatch {:step step
                                           :tx-data tx-data
                                           :error message})
                        ;; tx can be invalid due random -2 fresh fields; ignore non-checksum failures
                        nil)))))))
          (is (nil? @*mismatch)
              (str "found checksum mismatch repro: " (pr-str @*mismatch))))))))
