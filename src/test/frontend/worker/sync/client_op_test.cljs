(ns frontend.worker.sync.client-op-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [logseq.db.common.sqlite :as common-sqlite]
            [logseq.db.sqlite.gc :as sqlite-gc]))

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

(deftest sqlite-sync-meta-roundtrip-test
  (let [repo "repo-1"]
    (with-client-ops-db
      repo
      (fn [_db]
        (client-op/update-graph-uuid repo "graph-1")
        (client-op/update-local-tx repo 9)
        (client-op/update-local-checksum repo "checksum-1")
        (client-op/update-local-server-checksum repo "server-checksum-1")

        (client-op/update-graph-uuid repo "graph-2")
        (client-op/update-local-tx repo 12)
        (client-op/update-local-checksum repo "checksum-2")
        (client-op/update-local-server-checksum repo "server-checksum-2")

        (is (= "graph-2" (client-op/get-graph-uuid repo)))
        (is (= 12 (client-op/get-local-tx repo)))
        (is (= "checksum-2" (client-op/get-local-checksum repo)))
        (is (= "server-checksum-2"
               (client-op/get-local-server-checksum repo)))))))

(deftest automatic-repair-receipt-is-one-shot-and-persistent-test
  (let [repo "repo-auto-repair-receipt"
        graph-id "graph-1"]
    (with-client-ops-db
      repo
      (fn [_db]
        (is (true? (client-op/claim-auto-repair-attempt!
                    repo graph-id :db-sync/checksum-mismatch)))
        (is (= graph-id
               (:graph-id (client-op/get-auto-repair-attempt repo))))
        (is (false? (client-op/claim-auto-repair-attempt!
                     repo graph-id :db-sync/checksum-mismatch))
            "a worker restart or visibility event cannot start another automatic replacement")
        (let [receipt (client-op/get-auto-repair-attempt repo)]
          (is (false? (client-op/clear-auto-repair-attempt-if-matches!
                       repo (assoc receipt :attempt-id "stale-attempt")))
              "a stale generation cannot clear the durable repair receipt")
          (is (= receipt (client-op/get-auto-repair-attempt repo)))
          (is (true? (client-op/clear-auto-repair-attempt-if-matches!
                      repo receipt)))
          (is (nil? (client-op/get-auto-repair-attempt repo)))
          (client-op/clear-auto-repair-attempt! repo)
          (is (true? (client-op/preserve-auto-repair-attempt! repo receipt)))
          (is (= receipt (client-op/get-auto-repair-attempt repo)))
          (is (false? (client-op/claim-auto-repair-attempt!
                       repo graph-id :db-sync/checksum-mismatch))
              "snapshot replacement must not re-arm automatic repair"))
        (client-op/clear-auto-repair-attempt! repo)
        (is (nil? (client-op/get-auto-repair-attempt repo)))
        (is (true? (client-op/claim-auto-repair-attempt!
                    repo graph-id :db-sync/checksum-mismatch))
            "only an explicit clear re-arms one bounded attempt")))))

(deftest stale-versioned-checksum-watermark-is-not-trusted-test
  (let [repo "repo-stale-server-checksum"]
    (with-client-ops-db
      repo
      (fn [db]
        (client-op/update-local-tx repo 9)
        (client-op/update-local-checksum repo "legacy-checksum-at-9")
        (client-op/update-local-server-checksum repo "server-checksum-at-9")
        (is (= "server-checksum-at-9"
               (client-op/get-local-server-checksum repo)))

        ;; Simulate an old client that advances only the historical metadata.
        (let [^js stmt
              (.prepare ^js db
                        (str "insert into sync_meta (key, value) values ('local-tx', '10') "
                             "on conflict(key) do update set value = excluded.value"))]
          (.run stmt))
        (is (nil? (client-op/get-local-server-checksum repo))
            "a new client must recompute after an old-client downgrade")

        (client-op/update-local-checksum repo "legacy-checksum-at-10")
        (client-op/update-local-server-checksum repo "server-checksum-at-10")
        (is (= "server-checksum-at-10"
               (client-op/get-local-server-checksum repo)))))))

(deftest versioned-checksum-is-invalidated-by-old-client-pending-edit-test
  (let [repo "repo-stale-server-checksum-pending-edit"]
    (with-client-ops-db
      repo
      (fn [_db]
        (client-op/update-local-tx repo 4)
        (client-op/update-local-checksum repo "legacy-before-edit")
        (client-op/update-local-server-checksum repo "server-before-edit")
        (is (= "server-before-edit"
               (client-op/get-local-server-checksum repo)))

        ;; Old clients maintain the legacy checksum for local edits but do not
        ;; know the additive versioned metadata. local-t remains unchanged
        ;; until the pending edit is acknowledged.
        (client-op/update-local-checksum repo "legacy-after-local-edit")
        (is (nil? (client-op/get-local-server-checksum repo)))

        ;; Advancing the cursor must not bless that stale value. The message
        ;; verifier will recompute and stamp a fresh checksum at the new t.
        (client-op/update-local-tx repo 5)
        (is (nil? (client-op/get-local-server-checksum repo)))))))

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

(deftest pending-local-tx-readers-preserve-repair-ordering-metadata-test
  (let [repo "repo-pending-repair-metadata"
        tx-id (random-uuid)]
    (with-client-ops-db
      repo
      (fn [_db]
        (client-op/upsert-local-tx-entry!
         repo
         {:tx-id tx-id
          :created-at 1700000000123
          :pending? true
          :outliner-op :save-block
          :undo-redo :undo
          :normalized-tx-data []
          :reversed-tx-data []})
        (doseq [entry [(client-op/get-local-tx-entry repo tx-id)
                       (first (client-op/get-pending-local-txs repo))]]
          (is (= tx-id (:tx-id entry)))
          (is (= 1700000000123 (:created-at entry)))
          (is (= :undo (:db-sync/undo-redo entry))))))))

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

(defn- add-pending-tx!
  [repo tx-id]
  (client-op/upsert-local-tx-entry!
   repo
   {:tx-id tx-id
    :pending? true
    :normalized-tx-data []
    :reversed-tx-data []}))

(defn- sqlite-pending-tx-count
  [db]
  (sqlite-count db
                "select count(*) as c from client_ops where kind = 'tx' and pending = 1"))

(deftest pending-local-tx-count-waits-for-client-ops-store-test
  (let [repo "repo-pending-before-store-ready"]
    (with-client-ops-db
      repo
      (fn [db]
        (try
          (doseq [_ (range 13)]
            (add-pending-tx! repo (random-uuid)))
          (swap! worker-state/*client-ops-conns dissoc repo)
          (swap! client-op/*repo->pending-local-tx-count dissoc repo)

          (testing "a startup read returns zero without caching it before store registration"
            (is (zero? (client-op/get-pending-local-tx-count repo)))
            (is (not (contains? @client-op/*repo->pending-local-tx-count repo))))

          (testing "the first mutation after store registration reconciles existing rows"
            (swap! worker-state/*client-ops-conns assoc repo db)
            (add-pending-tx! repo (random-uuid))
            (client-op/adjust-pending-local-tx-count! repo 1)
            (is (= 14 (sqlite-pending-tx-count db)))
            (is (= 14 (client-op/get-pending-local-tx-count repo))))

          (testing "an adjustment while the store is absent also stays unknown"
            (swap! worker-state/*client-ops-conns dissoc repo)
            (swap! client-op/*repo->pending-local-tx-count dissoc repo)
            (client-op/adjust-pending-local-tx-count! repo 1)
            (is (not (contains? @client-op/*repo->pending-local-tx-count repo))))
          (finally
            (swap! client-op/*repo->pending-local-tx-count dissoc repo)))))))

(deftest pending-local-tx-count-caches-zero-for-ready-store-test
  (let [repo "repo-zero-pending-ready-store"]
    (with-client-ops-db
      repo
      (fn [_db]
        (swap! client-op/*repo->pending-local-tx-count dissoc repo)
        (try
          (is (zero? (client-op/get-pending-local-tx-count repo)))
          (is (= 0 (get @client-op/*repo->pending-local-tx-count repo)))
          (finally
            (swap! client-op/*repo->pending-local-tx-count dissoc repo)))))))

(deftest pending-local-tx-count-cache-miss-after-storage-mutation-test
  (let [repo "repo-pending-local-tx-count"]
    (with-client-ops-db
      repo
      (fn [db]
        (swap! client-op/*repo->pending-local-tx-count dissoc repo)
        (try
          (doseq [_ (range 13)]
            (add-pending-tx! repo (random-uuid)))

          (testing "the first positive delta after worker startup uses persisted pending rows"
            (add-pending-tx! repo (random-uuid))
            (client-op/adjust-pending-local-tx-count! repo 1)
            (is (= 14 (sqlite-pending-tx-count db)))
            (is (= 14 (client-op/get-pending-local-tx-count repo))))

          (testing "a warm cache still applies subsequent deltas"
            (add-pending-tx! repo (random-uuid))
            (client-op/adjust-pending-local-tx-count! repo 1)
            (is (= 15 (sqlite-pending-tx-count db)))
            (is (= 15 (client-op/get-pending-local-tx-count repo))))

          (testing "the first negative delta after a cache reset uses post-update storage"
            (swap! client-op/*repo->pending-local-tx-count dissoc repo)
            (let [tx-id (-> (client-op/get-pending-local-txs repo)
                            first
                            :tx-id)
                  removed (client-op/mark-pending-txs-false! repo [tx-id])]
              (is (= 1 removed))
              (client-op/adjust-pending-local-tx-count! repo (- removed))
              (is (= 14 (sqlite-pending-tx-count db)))
              (is (= 14 (client-op/get-pending-local-tx-count repo)))))

          (testing "a warm cache never becomes negative"
            (reset! client-op/*repo->pending-local-tx-count {repo 1})
            (client-op/adjust-pending-local-tx-count! repo -2)
            (is (zero? (client-op/get-pending-local-tx-count repo))))
          (finally
            (swap! client-op/*repo->pending-local-tx-count dissoc repo)))))))
