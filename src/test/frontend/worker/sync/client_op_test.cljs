(ns frontend.worker.sync.client-op-test
  (:require ["fs" :as node-fs]
            ["path" :as node-path]
            [cljs.test :refer [deftest is testing]]
            [frontend.test.node-helper :as node-helper]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [logseq.db.common.sqlite :as common-sqlite]
            [logseq.db.sqlite.gc :as sqlite-gc]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.db-sync.protocol :as protocol]))

(defn- new-memory-db
  []
  (let [Database (js/require "better-sqlite3")]
    (new Database ":memory:")))

(defn- with-client-ops-db
  [repo f]
  (let [db (new-memory-db)
        prev-client-ops-conns @worker-state/*client-ops-conns]
    (reset! worker-state/*client-ops-conns {repo db})
    (try
      (f db)
      (finally
        (.close db)
        (reset! worker-state/*client-ops-conns prev-client-ops-conns)))))

(defn- with-fixture-client-ops-db
  [repo fixture-relative-path f]
  (let [root-dir (node-helper/create-tmp-dir "client-op-fixture")
        source (node-path/join (js/process.cwd)
                               "src/test/fixtures/selfhost6"
                               fixture-relative-path)
        target (node-path/join root-dir "client-ops.sqlite")
        Database (js/require "better-sqlite3")
        prev-client-ops-conns @worker-state/*client-ops-conns]
    (node-fs/copyFileSync source target)
    (let [db (new Database target)]
      (reset! worker-state/*client-ops-conns {repo db})
      (try
        (f db)
        (finally
          (.close db)
          (reset! worker-state/*client-ops-conns prev-client-ops-conns))))))

(defn- sqlite-count
  [^js db sql & args]
  (let [^js stmt (.prepare db sql)
        ^js row (if (seq args)
                  (.apply (.-get stmt) stmt (to-array args))
                  (.get stmt))]
    (if row
      (or (aget row "c")
          (aget row "count"))
      0)))

(deftest reserved-sync-meta-whole-value-atomicity-test
  (let [key "selfhost.activation-record.v1"
        initial "activation-v1-initial"
        replacement "activation-v1-replacement"]
    (with-client-ops-db
      "repo-activation-atomicity"
      (fn [db]
        (testing "missing values are inserted once without overwriting the existing row"
          (is (= {:found? false}
                 (client-op/read-sync-meta-value db key)))
          (is (= {:inserted? true :value initial}
                 (client-op/insert-sync-meta-value-if-absent! db key initial)))
          (is (= {:inserted? false :value initial}
                 (client-op/insert-sync-meta-value-if-absent! db key replacement)))
          (is (= {:found? true :value initial}
                 (client-op/read-sync-meta-value db key))))

        (testing "the whole encoded value is compared before replacement"
          (is (false? (client-op/compare-and-set-sync-meta-value!
                       db key "stale-value" replacement)))
          (is (= {:found? true :value initial}
                 (client-op/read-sync-meta-value db key)))
          (is (true? (client-op/compare-and-set-sync-meta-value!
                      db key initial replacement)))
          (is (= {:found? true :value replacement}
                 (client-op/read-sync-meta-value db key))))

        (testing "repair cutover advances activation and official sync_meta together"
          (is (true?
               (client-op/commit-repair-activation-and-sync-meta!
                db key replacement "activation-v1-target" 7 "checksum-7")))
          (is (= {:found? true :value "activation-v1-target"}
                 (client-op/read-sync-meta-value db key)))
          (is (= 7 (client-op/get-local-tx "repo-activation-atomicity")))
          (is (= "checksum-7"
                 (client-op/get-local-checksum "repo-activation-atomicity"))))

        (testing "an aborted replacement leaves the previous value readable"
          (.exec db (str "create trigger abort_activation_update before update on sync_meta "
                         "when old.key = 'selfhost.activation-record.v1' "
                         "begin select raise(abort, 'activation update aborted'); end"))
          (is (thrown-with-msg?
               js/Error
               #"activation update aborted"
               (client-op/commit-repair-activation-and-sync-meta!
                db key "activation-v1-target" "activation-v1-cleared"
                8 "checksum-8")))
          (is (= {:found? true :value "activation-v1-target"}
                 (client-op/read-sync-meta-value db key)))
          (is (= 7 (client-op/get-local-tx "repo-activation-atomicity")))
          (is (= "checksum-7"
                 (client-op/get-local-checksum "repo-activation-atomicity"))))))))

(deftest frozen-upload-state-orphan-and-malformed-reader-boundaries-test
  (let [orphan-id #uuid "20000000-0000-4000-8000-000000000002"
        unrelated-id #uuid "20000000-0000-4000-8000-000000000004"
        malformed-id #uuid "20000000-0000-4000-8000-000000000003"]
    (with-fixture-client-ops-db
      "repo-orphan-upload-fixture" "upload-state/orphan.sqlite"
      (fn [db]
        (is (= :staged-large-upload-v1
               (:kind (client-op/get-client-tx-upload-state
                       "repo-orphan-upload-fixture" orphan-id))))
        (is (= {} (client-op/get-client-tx-upload-states
                   "repo-orphan-upload-fixture" [unrelated-id])))
        (is (= 1 (sqlite-count db
                               "select count(*) as c from client_tx_upload_state where logical_tx_id = ?"
                               (str orphan-id))))))
    (with-fixture-client-ops-db
      "repo-malformed-upload-fixture" "upload-state/malformed.sqlite"
      (fn [db]
        (let [error (try
                      (client-op/get-client-tx-upload-state
                       "repo-malformed-upload-fixture" malformed-id)
                      nil
                      (catch :default error
                        error))]
          (is (some? error))
          (is (= 1 (sqlite-count db
                                 "select count(*) as c from client_tx_upload_state where logical_tx_id = ?"
                                 (str malformed-id)))))))))

(deftest sqlite-sync-meta-roundtrip-test
  (let [repo "repo-1"]
    (with-client-ops-db
      repo
      (fn [_db]
        (client-op/update-graph-uuid repo "graph-1")
        (client-op/update-local-tx repo 9)
        (client-op/update-local-checksum repo "checksum-1" 9)

        (client-op/update-graph-uuid repo "graph-2")
        (client-op/update-local-tx repo 12)
        (client-op/update-local-checksum repo "checksum-2" 12)

        (is (= "graph-2" (client-op/get-graph-uuid repo)))
        (is (= 12 (client-op/get-local-tx repo)))
        (is (= "checksum-2" (client-op/get-local-checksum repo)))
        (is (= 12 (client-op/get-local-checksum-covered-tx repo)))))))

(deftest checksum-covered-tx-roundtrip-test
  (let [repo "repo-checksum-covered-tx"]
    (with-client-ops-db
      repo
      (fn [_db]
        (is (nil? (client-op/get-local-checksum-covered-tx repo)))
        (client-op/update-local-checksum repo "checksum-1" 41)
        (is (= "checksum-1" (client-op/get-local-checksum repo)))
        (is (= 41 (client-op/get-local-checksum-covered-tx repo)))))))

(deftest legacy-client-tx-upload-state-table-is-migrated-test
  (testing "legacy .5 client_tx_upload_state (session_data) must migrate to the current upload-state schema"
    (let [repo "legacy-upload-state-repo"]
      (with-client-ops-db
       repo
       (fn [^js db]
         (.exec db (str "create table client_tx_upload_state ("
                        "logical_tx_id text primary key,"
                        "session_data text not null,"
                        "updated_at integer not null)"))
         (let [tx-id (random-uuid)]
           (client-op/put-client-tx-upload-state!
            repo
            tx-id
            {:format-version 1
             :kind :single-wire-v1
             :source-digest "legacy-migration-digest"
             :outliner-op :save-block
             :wire-entry {:tx-id (str tx-id)}})
           (is (= "legacy-migration-digest"
                  (get-in (client-op/get-client-tx-upload-state
                           repo tx-id)
                          [:source-digest])))
           (let [columns (->> (.all (.prepare db
                                              "pragma table_info(client_tx_upload_state)"))
                              (mapv #(aget % "name")))]
             (is (contains? (set columns) "format_version"))
             (is (contains? (set columns) "kind"))
             (is (contains? (set columns) "source_digest"))
             (is (contains? (set columns) "state")))))))))

(deftest legacy-frozen-upload-state-keeps-wire-and-original-source-test
  (doseq [one-group? [false true]]
    (with-client-ops-db
      "legacy-frozen-upload-repo"
      (fn [^js db]
        (let [repo "legacy-frozen-upload-repo"
              tx-id (random-uuid)
              source [[:db/add [:block/uuid tx-id] :block/title "original"]
                      [:db/add [:block/uuid tx-id] :block/title "final"]]
              frozen (if one-group? [] [(second source)])
              session-id "synthetic-replacement-session"
              session {:session-version 2 :session-id session-id
                       :source-digest (protocol/tx-upload-session-id tx-id :save-block source)
                       :outliner-op :save-block :tx-data source
                       :boundaries (if one-group? [[0 2]] [[0 1] [1 2]])
                       :source-next-index (if one-group? 2 1) :chunk-seq 1
                       :wire-cache {1 {:tx-data frozen}} :final-wire-frozen? true}]
          (client-op/upsert-local-tx-entry!
           repo {:tx-id tx-id :pending? true :created-at 1 :failed? false
                 :outliner-op :insert-blocks :normalized-tx-data [] :reversed-tx-data []})
          (.exec db "drop table client_tx_upload_state")
          (.exec db "create table client_tx_upload_state (logical_tx_id text primary key, session_data text not null, updated_at integer not null)")
          (.run (.prepare db "insert into client_tx_upload_state values (?, ?, ?)")
                (str tx-id) (sqlite-util/write-transit-str session) 17)
          (aset db "__logseq_client_ops_schema_ready_v3" false)
          (client-op/ensure-sqlite-schema! db)
          (let [state (client-op/get-client-tx-upload-state repo tx-id)
                pending (first (client-op/get-pending-local-txs repo))]
            (is (= (if one-group? [2 2] [1 2]) (:boundaries state)))
            (is (= session-id (:session-id state)))
            (is (= {:tx (sqlite-util/write-transit-str frozen)
                    :tx-id (str (protocol/tx-chunk-id tx-id session-id 1 true))
                    :logical-tx-id (str tx-id) :upload-session-id session-id
                    :chunk-index 1 :chunk-final? true :outliner-op :save-block}
                   (:wire-cache state)))
            (is (= :save-block (:outliner-op pending)))
            (is (= source (:tx pending)))
            (client-op/ensure-sqlite-schema! db)
            (is (= state (client-op/get-client-tx-upload-state repo tx-id)))))))))

(deftest legacy-nonfinal-upload-rebase-preserves-pending-and-replaces-generation-test
  (doseq [acked? [false true]]
    (with-client-ops-db
      "legacy-nonfinal-rebase-repo"
      (fn [^js db]
        (let [repo "legacy-nonfinal-rebase-repo"
              tx-id (random-uuid)
              source [[:db/add [:block/uuid tx-id] :block/title "original"]
                      [:db/add [:block/uuid tx-id] :block/title "final"]]
              rebased (assoc source 1 [:db/add [:block/uuid tx-id] :block/title "rebased"])
              session {:session-version 2
                       :session-id (protocol/tx-upload-session-id tx-id :save-block source)
                       :outliner-op :save-block :tx-data source
                       :boundaries [[0 1] [1 2]]
                       :source-next-index (if acked? 1 0) :chunk-seq (if acked? 1 0)
                       :wire-cache {0 {:tx-data [(first source)]}}}]
          (client-op/upsert-local-tx-entry!
           repo {:tx-id tx-id :pending? true :created-at 1 :failed? false
                 :outliner-op :save-block :normalized-tx-data rebased :reversed-tx-data []})
          (.exec db "drop table client_tx_upload_state")
          (.exec db "create table client_tx_upload_state (logical_tx_id text primary key, session_data text not null, updated_at integer not null)")
          (.run (.prepare db "insert into client_tx_upload_state values (?, ?, ?)")
                (str tx-id) (sqlite-util/write-transit-str session) 17)
          (aset db "__logseq_client_ops_schema_ready_v3" false)
          (client-op/ensure-sqlite-schema! db)
          (is (nil? (client-op/get-client-tx-upload-state repo tx-id)))
          (let [pending (first (client-op/get-pending-local-txs repo))]
            (is (= tx-id (:tx-id pending)))
            (is (= :save-block (:outliner-op pending)))
            (is (= rebased (:tx pending)))))))))

(deftest repair-local-observation-is-one-journal-read-transaction-test
  (let [repo "repo-repair-observation"
        tx-id (random-uuid)]
    (with-client-ops-db
      repo
      (fn [_db]
        (client-op/update-local-tx repo 7)
        (client-op/update-local-checksum repo "legacy-7" 41)
        (client-op/upsert-local-tx-entry!
         repo {:tx-id tx-id
               :created-at 10
               :pending? true
               :normalized-tx-data []
               :reversed-tx-data []})
        (is (= {:remote-t 7
                :journal-high-water 1
                :pending-count 1
                :legacy-anchor "legacy-7"}
               (client-op/read-repair-local-observation repo)))))))

(deftest repair-completion-observation-bounds-pending-membership-test
  (let [repo "repo-repair-completion"
        target-tx-id (random-uuid)
        later-tx-id (random-uuid)]
    (with-client-ops-db
      repo
      (fn [db]
        (client-op/update-local-tx repo 12)
        (client-op/update-local-checksum repo "legacy-12" 42)
        (client-op/insert-sync-meta-value-if-absent!
         db "selfhost.activation-record.v1" "activation-raw")
        (client-op/upsert-local-tx-entry!
         repo {:tx-id target-tx-id
               :created-at 10
               :pending? false
               :normalized-tx-data []
               :reversed-tx-data []})
        (client-op/upsert-local-tx-entry!
         repo {:tx-id later-tx-id
               :created-at 20
               :pending? true
               :normalized-tx-data []
               :reversed-tx-data []})
        (is (= {:remote-t 12
                :journal-high-water 2
                :pending-count 1
                :legacy-anchor "legacy-12"
                :pending-through-target 0}
               (client-op/read-repair-completion-observation repo 1)))
        (is (= 1
               (:pending-through-target
                (client-op/read-repair-completion-observation repo 2))))
        (is (= {:local-checksum "legacy-12"
               :reserved-value "activation-raw"}
               (client-op/read-local-checksum-and-sync-meta-value
                repo "selfhost.activation-record.v1")))))))

(deftest repair-local-batch-binds-watermark-and-official-order-test
  (let [repo "repo-repair-batch"
        first-tx-id (random-uuid)
        second-tx-id (random-uuid)]
    (with-client-ops-db
      repo
      (fn [db]
        (client-op/update-local-tx repo 11)
        (doseq [tx-id [first-tx-id second-tx-id]]
          (client-op/upsert-local-tx-entry!
           repo {:tx-id tx-id
                 :created-at 10
                 :normalized-tx-data []
                 :reversed-tx-data []}))
        (client-op/upsert-local-tx-entry!
         repo {:tx-id (random-uuid)
               :created-at 20
               :pending? false
               :normalized-tx-data []
               :reversed-tx-data []})
        (.exec db "delete from client_ops where id = 3")
        (let [{:keys [observation pending-ids]}
              (client-op/read-repair-local-batch repo)]
          (is (= {:remote-t 11
                  :journal-high-water 3
                  :pending-count 2
                  :legacy-anchor nil}
                 observation))
          (is (= [1 2] pending-ids))
          (is (= [first-tx-id second-tx-id]
                 (mapv :tx-id
                       (client-op/read-repair-local-page repo pending-ids)))))))))

(deftest sqlite-asset-ops-coalescing-test
  (let [repo "repo-asset"
        asset-uuid (random-uuid)]
    (with-client-ops-db
      repo
      (fn [_db]
        (client-op/add-asset-ops repo [[:update-asset 10 {:block-uuid asset-uuid}]])
        (is (= 1 (client-op/get-unpushed-asset-ops-count repo)))
        (is (= [:update-asset 10 {:block-uuid asset-uuid}]
               (:update-asset (first (client-op/get-all-asset-ops repo)))))

        ;; older remove should be ignored because a newer update already exists
        (client-op/add-asset-ops repo [[:remove-asset 9 {:block-uuid asset-uuid}]])
        (is (= [:update-asset 10 {:block-uuid asset-uuid}]
               (:update-asset (first (client-op/get-all-asset-ops repo)))))

        ;; newer remove should replace update
        (client-op/add-asset-ops repo [[:remove-asset 11 {:block-uuid asset-uuid}]])
        (is (= [:remove-asset 11 {:block-uuid asset-uuid}]
               (:remove-asset (first (client-op/get-all-asset-ops repo)))))

        (client-op/remove-asset-op repo asset-uuid)
        (is (= 0 (client-op/get-unpushed-asset-ops-count repo)))))))

(deftest cleanup-finished-history-ops-removes-only-unreferenced-finished-txs-test
  (let [repo "repo-cleanup"
        keep-tx-id (random-uuid)
        remove-tx-id (random-uuid)
        pending-tx-id (random-uuid)]
    (with-client-ops-db
      repo
      (fn [db]
        (client-op/update-local-tx repo 99)
        (client-op/upsert-local-tx-entry!
         repo
         {:tx-id keep-tx-id
          :created-at 1
          :pending? false
          :failed? false
          :normalized-tx-data []
          :reversed-tx-data []})
        (client-op/upsert-local-tx-entry!
         repo
         {:tx-id remove-tx-id
          :created-at 2
          :pending? false
          :failed? false
          :normalized-tx-data []
          :reversed-tx-data []})
        (client-op/upsert-local-tx-entry!
         repo
         {:tx-id pending-tx-id
          :created-at 3
          :pending? true
          :failed? false
          :normalized-tx-data []
          :reversed-tx-data []})

        (is (= 1 (client-op/cleanup-finished-history-ops! repo #{keep-tx-id})))
        (is (= 1 (sqlite-count db "select count(*) as c from client_ops where tx_id = ?" (str keep-tx-id))))
        (is (= 0 (sqlite-count db "select count(*) as c from client_ops where tx_id = ?" (str remove-tx-id))))
        (is (= 1 (sqlite-count db "select count(*) as c from client_ops where tx_id = ?" (str pending-tx-id))))
        (is (= 99 (client-op/get-local-tx repo)))))))

(deftest cleanup-finished-history-ops-no-conn-is-noop-test
  (let [repo "repo-no-conn"
        prev-client-ops-conns @worker-state/*client-ops-conns]
    (reset! worker-state/*client-ops-conns {})
    (try
      (testing "cleanup should be safe when client-ops conn is missing"
        (is (= 0 (client-op/cleanup-finished-history-ops! repo #{}))))
      (finally
        (reset! worker-state/*client-ops-conns prev-client-ops-conns)))))

(deftest gc-on-client-ops-db-preserves-sync-metadata-test
  "Reproduces the bug where GC on client-ops db could reset local-tx to 0.
  The client-ops db has a kvs table (created by create-kvs-table!) but it's
  empty because client-ops doesn't store Datascript data. Running GC on an
  empty kvs table is a no-op with better-sqlite3 but can crash or misbehave
  with WASM sqlite (browser), leading to db corruption and local-tx reset."
  (let [repo "repo-gc-test"]
    (with-client-ops-db
      repo
      (fn [db]
        ;; client-ops db gets a kvs table during db open, but it's empty
        (common-sqlite/create-kvs-table! db)
        (client-op/ensure-sqlite-schema! db)
        (client-op/update-local-tx repo 42)
        (is (= 42 (client-op/get-local-tx repo))
            "local-tx should be 42 before GC")

        ;; GC on client-ops db: kvs table is empty because client-ops db
        ;; does not store Datascript data. gc-kvs-table! expects a valid
        ;; Datascript schema at kvs addr 0, which doesn't exist here.
        ;; This causes transit-js to throw "Expected first argument to be
        ;; a string" because it receives undefined instead of a transit
        ;; string. This is why gc-sqlite-dbs! must NOT include client-ops db.
        (testing "gc-kvs-table! crashes on client-ops db with empty kvs table"
          (is (thrown-with-msg?
               js/Error
               #"Expected first argument to be a string"
               (sqlite-gc/gc-kvs-table! db {:full-gc? false}))))

        ;; After GC, local-tx should still be intact
        (is (= 42 (client-op/get-local-tx repo))
            "local-tx should still be 42 after GC")

        ;; VACUUM should also be safe
        (.exec db "VACUUM")
        (is (= 42 (client-op/get-local-tx repo))
            "local-tx should still be 42 after VACUUM")))))
