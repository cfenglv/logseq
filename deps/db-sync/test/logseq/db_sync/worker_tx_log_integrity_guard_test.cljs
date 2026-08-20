(ns logseq.db-sync.worker-tx-log-integrity-guard-test
  (:require
   ["better-sqlite3" :as sqlite3]
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as string]
   [logseq.db-sync.common :as common]
   [logseq.db-sync.storage :as storage]))

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
                             (do (run-sql stmt args) nil))))}]
    (try
      (storage/init-schema! sql)
      (f sql)
      (finally
        (.close db)))))

(defn- tx-log-rows
  [sql]
  (mapv #(js->clj % :keywordize-keys true)
        (common/get-sql-rows
         (common/sql-exec
          sql "select t, tx, tx_id from tx_log order by t"))))

(deftest tx-log-is-insert-only-and-keeps-legacy-nil-tx-id-test
  (testing "a duplicate server cursor cannot silently replace committed history"
    (with-memory-sql
      (fn [sql]
        (storage/append-tx! sql 1 "tx-one" 1000 :save-block nil)
        (is (= [{:t 1 :tx "tx-one" :tx_id nil}]
               (tx-log-rows sql))
            "healthy .1-.4 entries may omit tx-id")
        (is (thrown? js/Error
                     (storage/append-tx!
                      sql 1 "replacement" 1001 :save-block nil))
            "duplicate t must reject rather than overwrite")
        (is (= [{:t 1 :tx "tx-one" :tx_id nil}]
               (tx-log-rows sql)))))))

(deftest tx-log-rejects-a-gap-before-writing-test
  (testing "the next append must equal max persisted t plus one"
    (with-memory-sql
      (fn [sql]
        (is (thrown? js/Error
                     (storage/append-tx! sql 2 "gap" 1000 :save-block nil)))
        (is (empty? (tx-log-rows sql)))))))

(deftest tx-log-silent-insert-drop-is-not-a-batch-receipt-test
  (testing "a non-throwing adapter fault cannot advance an in-memory suffix proof"
    (with-memory-sql
      (fn [sql]
        (let [original-exec (.-exec sql)]
          (set! (.-exec sql)
                (fn [sql-str & args]
                  (if (string/starts-with?
                       (-> sql-str string/trim string/lower-case)
                       "insert into tx_log")
                    nil
                    (.apply original-exec sql
                            (to-array (cons sql-str args))))))
          (try
            (is (thrown? js/Error
                         (storage/append-tx!
                          sql 1 "silently-dropped" 1000 :save-block nil)))
            (finally
              (set! (.-exec sql) original-exec)))
          (is (empty? (tx-log-rows sql))))))))

(deftest tx-log-floor-models-only-complete-retained-suffixes-test
  (testing "legacy empty history and contiguous suffixes have bounded floors"
    (with-memory-sql
      (fn [sql]
        (is (= 0 (storage/tx-log-floor sql)))
        (storage/set-t! sql 7)
        (is (= 7 (storage/tx-log-floor sql)))
        (common/sql-exec
         sql
         (str "insert into tx_log (t, tx, created_at, outliner_op, tx_id) "
              "values (?, ?, ?, ?, ?)")
         8 "tx-eight" 1000 "save-block" nil)
        (storage/set-t! sql 8)
        (is (= 7 (storage/tx-log-floor sql)))
        (common/sql-exec
         sql
         (str "insert into tx_log (t, tx, created_at, outliner_op, tx_id) "
              "values (?, ?, ?, ?, ?)")
         9 "tx-nine" 1001 "save-block" nil)
        (storage/set-t! sql 9)
        (is (= 7 (storage/tx-log-floor sql)))
        (common/sql-exec
         sql
         (str "insert into tx_log (t, tx, created_at, outliner_op, tx_id) "
              "values (?, ?, ?, ?, ?)")
         10 "tx-ten" 1002 "save-block" nil)
        (storage/set-t! sql 10)
        (common/sql-exec sql "delete from tx_log where t = 9")
        (is (nil? (storage/tx-log-floor sql))
            "a gap in the retained suffix is corruption")
        (common/sql-exec sql "delete from tx_log")
        (common/sql-exec
         sql
         (str "insert into tx_log (t, tx, created_at, outliner_op, tx_id) "
              "values (?, ?, ?, ?, ?)")
         8 "tx-eight" 1003 "save-block" nil)
        (storage/set-t! sql 9)
        (is (nil? (storage/tx-log-floor sql))
            "a suffix whose max cursor trails t is corruption")))))

(deftest integrity-attestation-requires-cursor-and-log-continuity-test
  (testing "an exact KVS root cannot attest over a gapped server history"
    (with-memory-sql
      (fn [sql]
        (storage/append-tx! sql 1 "tx-one" 1000 :save-block nil)
        (storage/set-t! sql 2)
        (is (not (storage/tx-log-contiguous? sql)))
        (is (thrown? js/Error
                     (storage/mark-snapshot-integrity-attested!
                      sql 2 "synthetic-generation")))))))
