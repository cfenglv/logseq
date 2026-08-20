(ns frontend.worker.sync.missing-system-kv-test
  (:require
   [cljs.test :refer [async deftest is testing use-fixtures]]
   [datascript.core :as d]
   [frontend.test.noise :as test-noise]
   [frontend.worker.shared-service :as shared-service]
   [frontend.worker.state :as worker-state]
   [frontend.worker.sync :as db-sync]
   [frontend.worker.sync.apply-txs :as sync-apply]
   [frontend.worker.sync.client-op :as client-op]
   [frontend.worker.sync.crypt :as sync-crypt]
   [frontend.worker.sync.download :as sync-download]
   [frontend.worker.sync.handle-message :as sync-handle-message]
   [frontend.worker.sync.log-and-state :as rtc-log-and-state]
   [frontend.worker.undo-redo :as undo-redo]
   [logseq.db :as ldb]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db.sqlite.util :as sqlite-util]
   [logseq.db.test.helper :as db-test]
   [promesa.core :as p]))

(def ^:private supported-system-kv :logseq.kv/graph-created-at)

(use-fixtures :once
  (test-noise/mute-console-fixture ::missing-system-kv-test))

(defn- new-client-ops-db
  []
  (let [Database (js/require "better-sqlite3")
        db (new Database ":memory:")]
    (client-op/ensure-sqlite-schema! db)
    db))

(defn- synthetic-state
  []
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks
               [{:page {:block/title (str "synthetic-page-" (random-uuid))}
                 :blocks [{:block/title "synthetic-remote-target"}
                          {:block/title "synthetic-local-1"}
                          {:block/title "synthetic-local-2"}]}]})
        remote-conn (d/conn-from-db @conn)
        remote-target (db-test/find-block-by-content @conn "synthetic-remote-target")
        local-targets [(db-test/find-block-by-content @conn "synthetic-local-1")
                       (db-test/find-block-by-content @conn "synthetic-local-2")]
        initial-kv-value (:kv/value (d/entity @conn supported-system-kv))]
    ;; Model the observable post-download defect without touching a filesystem
    ;; graph, credentials, or a sync service: the supported system ident is
    ;; absent locally while the authoritative synthetic peer still has it.
    (d/transact! conn [[:db/retractEntity supported-system-kv]])
    (is (nil? (d/entity @conn supported-system-kv))
        "the synthetic local fixture must really omit the supported ident")
    {:conn conn
     :remote-conn remote-conn
     :client-ops-conn (new-client-ops-db)
     :remote-target remote-target
     :local-targets local-targets
     :initial-kv-value initial-kv-value}))

(defn- with-synthetic-sync-state
  [repo {:keys [conn client-ops-conn]} f]
  (let [datascript-conns-before @worker-state/*datascript-conns
        client-ops-conns-before @worker-state/*client-ops-conns]
    (reset! worker-state/*datascript-conns {repo conn})
    (reset! worker-state/*client-ops-conns {repo client-ops-conn})
    (swap! client-op/*repo->pending-local-tx-count dissoc repo)
    (client-op/update-local-tx repo 0)
    (undo-redo/clear-history! repo)
    (d/listen! conn ::persist-synthetic-local-txs
               (fn [tx-report]
                 (db-sync/enqueue-local-tx! repo tx-report)))
    (let [cleanup (fn []
        (d/unlisten! conn ::persist-synthetic-local-txs)
        (undo-redo/clear-history! repo)
        (swap! client-op/*repo->pending-local-tx-count dissoc repo)
        (swap! sync-apply/*repo->latest-remote-tx dissoc repo)
        (swap! sync-apply/*repo->latest-remote-checksum dissoc repo)
        (swap! sync-apply/*repo->latest-remote-checksum-version dissoc repo)
        (reset! worker-state/*datascript-conns datascript-conns-before)
        (reset! worker-state/*client-ops-conns client-ops-conns-before)
        (.close client-ops-conn))
          result (try
                   (f)
                   (catch :default error
                     (cleanup)
                     (throw error)))]
      (if (or (p/promise? result)
              (and (some? result) (fn? (.-then result))))
        (.finally (js/Promise.resolve result) cleanup)
        (do
          (cleanup)
          result)))))

(defn- pending-semantics
  [repo]
  (mapv (fn [{:keys [tx-id tx]}]
          {:tx-id tx-id
           ;; A replay can allocate a fresh DataScript transaction id. The
           ;; fifth datom field and the internal :rebase marker are not part
           ;; of the queued user operation's identity, order, or content.
           :tx (mapv (fn [item]
                       (if (and (vector? item) (= 5 (count item)))
                         (subvec item 0 4)
                         item))
                     tx)})
        (sync-apply/pending-txs repo)))

(defn- seed-pending-local-txs!
  [repo conn local-targets pending-count]
  (doseq [[index target] (take pending-count (map-indexed vector local-targets))]
    (ldb/transact! conn
                   [[:db/add (:db/id target)
                     :block/title
                     (str "pending-local-title-" (inc index))]]))
  (let [pending (pending-semantics repo)]
    (is (= pending-count (count pending))
        "the synthetic durable queue must have the requested cardinality")
    pending))

(defn- remote-kv-ops
  [shape initial-value next-value]
  (case shape
    :add
    [[:db/add supported-system-kv :kv/value next-value]]

    :retract
    [[:db/retract supported-system-kv :kv/value initial-value]]

    :update
    [[:db/retract supported-system-kv :kv/value initial-value]
     [:db/add supported-system-kv :kv/value next-value]]))

(defn- remote-tx
  [{:keys [shape initial-value remote-target next-value remote-title]}]
  (conj (remote-kv-ops shape initial-value next-value)
        [:db/add [:block/uuid (:block/uuid remote-target)]
         :block/title remote-title]))

(defn- sync-client
  [repo]
  {:repo repo
   :inflight (atom [])
   :last-sync-error (atom nil)
   :online-users (atom [])
   :pending-pull-since (atom nil)
   :ws-state (atom :open)})

(defn- apply-entries-and-route-runtime-error!
  [repo client tx-entries]
  (let [error (try
                (sync-apply/apply-remote-txs! repo client tx-entries)
                nil
                (catch :default error
                  error))]
    (when error
      ;; Exercise the same externally visible state transition as the receive
      ;; loop. A valid remote transaction must not become repair-required.
      (with-redefs [shared-service/broadcast-to-clients! (fn [& _] nil)]
        (#'db-sync/handle-runtime-sync-failure!
         repo client nil nil error :receive)))
    error))

(defn- apply-and-route-runtime-error!
  [repo client tx-entry]
  (apply-entries-and-route-runtime-error! repo client [tx-entry]))

(defn- caught-remote-entries-error
  [repo client tx-entries]
  (try
    (sync-apply/apply-remote-txs! repo client tx-entries)
    nil
    (catch :default error
      error)))

(defn- db-facts
  [db]
  (set (d/q '[:find ?entity ?attribute ?value
              :where [?entity ?attribute ?value]]
            db)))

(defn- apply-expected-pending-titles!
  [remote-conn local-targets pending-count]
  (doseq [[index target] (take pending-count (map-indexed vector local-targets))]
    (d/transact! remote-conn
                 [[:db/add [:block/uuid (:block/uuid target)]
                   :block/title
                   (str "pending-local-title-" (inc index))]])))

(defn- system-kv-projection
  [db]
  (some-> (d/entity db supported-system-kv)
          (select-keys [:db/ident :kv/value])))

(defn- restore-supported-system-kv!
  [{:keys [conn initial-kv-value]}]
  (d/transact! conn
               [{:db/ident supported-system-kv
                 :kv/value initial-kv-value}])
  (is (= initial-kv-value
         (:kv/value (d/entity @conn supported-system-kv)))))

(defn- without-local-persistence-listener
  [conn f]
  (d/unlisten! conn ::persist-synthetic-local-txs)
  (try
    (f)
    (finally
      (d/listen! conn ::persist-synthetic-local-txs
                 (fn [tx-report]
                   ;; The caller always installs this helper inside
                   ;; with-synthetic-sync-state, whose map contains one repo.
                   (let [repo (first (keys @worker-state/*datascript-conns))]
                     (db-sync/enqueue-local-tx! repo tx-report)))))))

(defn- assert-pending-and-open!
  [repo client pending-before label]
  (is (= pending-before (pending-semantics repo))
      (str label " must preserve pending identity, order, and semantics"))
  (is (= :open @(:ws-state client))
      (str label " must not enter repair-required"))
  (is (nil? @(:last-sync-error client))
      (str label " must not leave a sync error")))

(deftest missing-supported-system-kv-applies-add-retract-update-with-pending-matrix-test
  (testing "a post-download missing supported KV ident heals without losing queued local edits"
    (doseq [shape [:add :retract :update]
            pending-count [0 1 2]]
      (let [repo (str "synthetic-missing-system-kv-" (random-uuid))
            {:keys [conn remote-conn remote-target local-targets
                    initial-kv-value]
             :as state} (synthetic-state)
            next-value (+ initial-kv-value 1000)
            remote-title (str "remote-title-" (name shape) "-" pending-count)
            tx-data (remote-tx {:shape shape
                                :initial-value initial-kv-value
                                :remote-target remote-target
                                :next-value next-value
                                :remote-title remote-title})
            client (sync-client repo)]
        (with-synthetic-sync-state
          repo state
          (fn []
            (let [pending-before
                  (seed-pending-local-txs!
                   repo conn local-targets pending-count)]
              (d/transact! remote-conn tx-data)
              (apply-expected-pending-titles!
               remote-conn local-targets pending-count)
              (let [error (apply-and-route-runtime-error!
                           repo client
                           {:t 381
                            :tx-id (random-uuid)
                            :tx-data tx-data})
                    case-label (str "shape=" shape
                                    " pending=" pending-count)]
                (is (nil? error)
                    (str case-label
                         " must not surface :db-sync/remote-apply-failed; "
                         (some-> error ex-message)
                         " "
                         (some-> error ex-data)))
                (is (= :open @(:ws-state client))
                    (str case-label " must not enter repair-required"))
                (is (nil? @(:last-sync-error client))
                    (str case-label " must not leave a runtime sync error"))
                (is (= (system-kv-projection @remote-conn)
                       (system-kv-projection @conn))
                    (str case-label
                         " must converge on the authoritative system KV state"))
                (is (= remote-title
                       (:block/title
                        (d/entity @conn
                                  [:block/uuid (:block/uuid remote-target)])))
                    (str case-label
                         " must atomically apply the ordinary remote datom"))
                (is (= (sync-checksum/recompute-server-checksum @remote-conn)
                       (sync-checksum/recompute-server-checksum @conn))
                    (str case-label
                         " must converge to the same server-db-v2 checksum"))
                (is (= pending-before (pending-semantics repo))
                    (str case-label
                         " must preserve pending identity, order, and semantics"))))))))))

(deftest missing-supported-system-kv-retry-and-duplicate-apply-are-idempotent-test
  (testing "replaying the same authoritative update is harmless and queue-stable"
    (let [repo (str "synthetic-missing-system-kv-idempotent-" (random-uuid))
          {:keys [conn remote-target local-targets initial-kv-value]
           :as state} (synthetic-state)
          next-value (+ initial-kv-value 2000)
          tx-data (remote-tx {:shape :update
                              :initial-value initial-kv-value
                              :remote-target remote-target
                              :next-value next-value
                              :remote-title "remote-idempotent-title"})
          client (sync-client repo)]
      (with-synthetic-sync-state
        repo state
        (fn []
          (let [pending-before (seed-pending-local-txs! repo conn local-targets 2)
                entry {:t 382 :tx-id (random-uuid) :tx-data tx-data}
                first-error (apply-and-route-runtime-error! repo client entry)
                first-kv-eid (some-> (d/entity @conn supported-system-kv) :db/id)
                first-checksum (sync-checksum/recompute-server-checksum @conn)
                first-pending (pending-semantics repo)
                second-error (apply-and-route-runtime-error! repo client entry)]
            (is (nil? first-error)
                (str "first apply failed: "
                     (some-> first-error ex-message)
                     " "
                     (some-> first-error ex-data)))
            (is (nil? second-error)
                (str "duplicate apply failed: "
                     (some-> second-error ex-message)
                     " "
                     (some-> second-error ex-data)))
            (is (= next-value
                   (:kv/value (d/entity @conn supported-system-kv))))
            (is (= first-kv-eid
                   (:db/id (d/entity @conn supported-system-kv)))
                "duplicate apply must not create a second system KV entity")
            (is (= first-checksum
                   (sync-checksum/recompute-server-checksum @conn)))
            (is (= pending-before first-pending (pending-semantics repo))
                "retry/duplicate apply must not mutate the pending queue")
            (is (= :open @(:ws-state client)))))))))

(deftest invalid-cross-tx-delete-then-keyword-reference-fails-closed-test
  (testing "a server cannot produce a tx that references an ident deleted by its preceding tx"
    (let [repo (str "synthetic-kv-ordered-batch-" (random-uuid))
          {:keys [conn remote-conn remote-target local-targets initial-kv-value]
           :as state} (synthetic-state)
          next-value (+ initial-kv-value 3000)
          client (sync-client repo)
          tx-entries [{:t 501
                       :tx-id (random-uuid)
                       :tx-data [[:db/retractEntity supported-system-kv]]}
                      {:t 502
                       :tx-id (random-uuid)
                       :tx-data [[:db/add supported-system-kv :kv/value next-value]
                                 [:db/add
                                  [:block/uuid (:block/uuid remote-target)]
                                  :block/title
                                  "must-not-apply-cross-tx"]]}]
          authoritative-conn (d/conn-from-db @remote-conn)
          authoritative-error
          (try
            ;; Apply the exact wire transactions, in order, to a separate
            ;; authoritative DataScript connection. The second transaction is
            ;; rejected because the first removed the keyword lookup ident.
            (doseq [{:keys [tx-data]} tx-entries]
              (d/transact! authoritative-conn tx-data))
            nil
            (catch :default error
              error))]
      (is (= :entity-id/missing
             (:error (ex-data authoritative-error)))
          "the exact wire history is invalid on the authoritative engine")
      (is (nil? (d/entity @authoritative-conn supported-system-kv))
          "only the preceding valid delete can exist in server history")
      (restore-supported-system-kv! state)
      (with-synthetic-sync-state
        repo state
        (fn []
          (let [pending-before (seed-pending-local-txs! repo conn local-targets 2)
                facts-before (db-facts @conn)
                error (caught-remote-entries-error repo client tx-entries)]
            (is (= :db-sync/remote-apply-failed (:type (ex-data error))))
            (is (= :entity-id/missing
                   (:error (ex-data (ex-cause error)))))
            (is (= facts-before (db-facts @conn))
                "the rejected remote batch must be atomic in the client")
            (is (= pending-before (pending-semantics repo))
                "fail-closed must preserve pending identity, order, and content")))))))

(deftest invalid-same-tx-delete-then-keyword-reference-fails-closed-test
  (testing "retractEntity followed by a keyword lookup in one tx is invalid and atomic"
    (let [repo (str "synthetic-kv-ordered-ops-" (random-uuid))
          {:keys [conn remote-conn remote-target local-targets initial-kv-value]
           :as state} (synthetic-state)
          next-value (+ initial-kv-value 4000)
          client (sync-client repo)
          tx-data [[:db/retractEntity supported-system-kv]
                   [:db/add supported-system-kv :kv/value next-value]
                   [:db/add [:block/uuid (:block/uuid remote-target)]
                    :block/title "must-not-apply-same-tx"]]
          entry {:t 503 :tx-id (random-uuid) :tx-data tx-data}
          authoritative-conn (d/conn-from-db @remote-conn)
          authoritative-facts-before (db-facts @authoritative-conn)
          authoritative-error
          (try
            ;; This is the exact unmodified wire transaction under audit.
            (d/transact! authoritative-conn tx-data)
            nil
            (catch :default error
              error))]
      (is (= :entity-id/missing
             (:error (ex-data authoritative-error)))
          "the authoritative engine rejects the same ordered operations")
      (is (= authoritative-facts-before (db-facts @authoritative-conn))
          "DataScript rejects the invalid transaction atomically")
      (restore-supported-system-kv! state)
      (with-synthetic-sync-state
        repo state
        (fn []
          (let [pending-before (seed-pending-local-txs! repo conn local-targets 1)
                facts-before (db-facts @conn)
                error (caught-remote-entries-error repo client [entry])]
            (is (= :db-sync/remote-apply-failed (:type (ex-data error))))
            (is (= :entity-id/missing
                   (:error (ex-data (ex-cause error)))))
            (is (= facts-before (db-facts @conn))
                "the client must not partially apply the invalid transaction")
            (is (= pending-before (pending-semantics repo))
                "fail-closed must preserve pending identity, order, and content")))))))

(deftest missing-supported-system-kv-is-safe-in-each-remote-rebase-stage-test
  (testing "reverse-local, transact-remote, and rebase-local each preserve the same queue contract"
    (doseq [stage [:reverse-local :transact-remote :rebase-local]]
      (let [repo (str "synthetic-kv-stage-" (name stage) "-" (random-uuid))
            {:keys [conn remote-target local-targets initial-kv-value]
             :as state} (synthetic-state)
            client (sync-client repo)
            pending-kv-value (+ initial-kv-value 5000)
            remote-kv-value (+ initial-kv-value 6000)]
        (when (contains? #{:reverse-local :rebase-local} stage)
          (restore-supported-system-kv! state))
        (with-synthetic-sync-state
          repo state
          (fn []
            (let [known-db-before-local @conn]
              (if (= :transact-remote stage)
                (seed-pending-local-txs! repo conn local-targets 1)
                (ldb/transact!
                 conn
                 [[:db/add supported-system-kv :kv/value pending-kv-value]]))
              (when (contains? #{:reverse-local :rebase-local} stage)
                (let [pending-row (first (sync-apply/pending-txs repo))
                      local-wire-tx
                      (mapv (fn [item]
                              (if (and (vector? item) (= 5 (count item)))
                                (subvec item 0 4)
                                item))
                            (:tx pending-row))
                      audit-conn (d/conn-from-db known-db-before-local)
                      local-error
                      (try
                        (d/transact! audit-conn local-wire-tx)
                        nil
                        (catch :default error
                          error))]
                  (is (nil? local-error)
                      (str "stage=" stage
                           " pending system-KV tx must be locally legal"))
                  (is (= pending-kv-value
                         (:kv/value
                          (d/entity @audit-conn supported-system-kv)))
                      (str "stage=" stage
                           " independent replay must preserve its semantics"))))
              (when (= :reverse-local stage)
                ;; Preserve the durable pending row while recreating the
                ;; post-download missing-ident condition underneath it. This
                ;; makes the first failing reference occur during local reverse.
                (without-local-persistence-listener
                  conn
                  #(d/transact!
                    conn [[:db/retractEntity supported-system-kv]])))
              (let [pending-before (pending-semantics repo)
                  tx-data
                  (case stage
                    :reverse-local
                    [[:db/add [:block/uuid (:block/uuid remote-target)]
                      :block/title "remote-after-reverse"]]

                    :transact-remote
                    [[:db/add supported-system-kv
                      :kv/value remote-kv-value]
                     [:db/add [:block/uuid (:block/uuid remote-target)]
                      :block/title "remote-during-transact"]]

                    :rebase-local
                    [[:db/retractEntity supported-system-kv]
                     [:db/add [:block/uuid (:block/uuid remote-target)]
                      :block/title "remote-before-rebase"]])
                  error (apply-and-route-runtime-error!
                         repo client
                         {:t 510
                          :tx-id (random-uuid)
                          :tx-data tx-data})
                  label (str "stage=" stage)]
              (is (seq pending-before) label)
              (is (nil? error)
                  (str label " failed: "
                       (some-> error ex-message)
                       " "
                       (some-> error ex-data)))
              (assert-pending-and-open! repo client pending-before label)
              (when (contains? #{:reverse-local :rebase-local} stage)
                (is (= pending-kv-value
                       (:kv/value (d/entity @conn supported-system-kv)))
                    (str label
                         " must replay the pending local system value")))))))))))

(deftest legacy-cursor-replay-of-already-reflected-system-kv-delete-is-idempotent-test
  (testing "cursor N plus snapshot content from N+1 safely replays the N+1 system delete"
    (async done
           (let [repo (str "synthetic-kv-cursor-race-" (random-uuid))
                 {:keys [conn remote-target local-targets] :as state}
                 (synthetic-state)
                 remote-title "already-reflected-n-plus-one"
                 tx-data [[:db/retractEntity supported-system-kv]
                          [:db/add [:block/uuid (:block/uuid remote-target)]
                           :block/title remote-title]]
                 client (sync-client repo)
                 raw-message
                 (js/JSON.stringify
                  (clj->js
                   {:type "pull/ok"
                    :t 382
                    :txs [{:t 382
                           :tx (sqlite-util/write-transit-str tx-data)}]}))]
             ;; A v2 frozen snapshot should prevent this mixed metadata/content
             ;; state. Model it anyway as the observable legacy fallback: the
             ;; content already reflects N+1, while durable metadata says N.
             (d/transact! conn
                          [[:db/add
                            [:block/uuid (:block/uuid remote-target)]
                            :block/title remote-title]])
             (-> (with-synthetic-sync-state
                  repo state
                  (fn []
                    (client-op/update-local-tx repo 381)
                    (let [pending-before
                          (seed-pending-local-txs! repo conn local-targets 1)]
                      (-> (p/with-redefs
                            [sync-crypt/graph-e2ee? (constantly false)
                             sync-crypt/<ensure-graph-aes-key
                             (fn [& _] (p/resolved nil))]
                            (sync-handle-message/handle-message!
                             repo client raw-message))
                          (p/then
                           (fn [_]
                             (is (= 382 (client-op/get-local-tx repo))
                                 "successful replay must advance the cursor once")
                             (is (nil? (d/entity @conn supported-system-kv)))
                             (is (= remote-title
                                    (:block/title
                                     (d/entity
                                      @conn
                                      [:block/uuid
                                       (:block/uuid remote-target)]))))
                             (assert-pending-and-open!
                              repo client pending-before
                              "legacy cursor/content race")))
                          (p/catch
                           (fn [error]
                             (with-redefs
                               [shared-service/broadcast-to-clients!
                                (fn [& _] nil)]
                               (#'db-sync/handle-runtime-sync-failure!
                                repo client nil nil error :receive))
                             (is false
                                 (str "legacy replay became a sync failure: "
                                      (ex-message error)
                                      " "
                                      (ex-data error)))
                             (is (not= :repair-required
                                       @(:ws-state client)))))))))
                 (p/catch (fn [error]
                            (is false (str error))))
                 (p/finally done))))))

(defn- next-pull-watermark!
  [watermarks calls]
  (let [index @calls
        value (nth watermarks index (last watermarks))]
    (swap! calls inc)
    (p/resolved {:t value})))

(deftest legacy-snapshot-activates-only-after-equal-pre-and-post-stream-watermarks-test
  (testing "a transient legacy watermark race discards the first import and retries before activation"
    (async done
           (let [repo (str "synthetic-legacy-snapshot-stable-" (random-uuid))
                 graph-id (str (random-uuid))
                 {:keys [conn local-targets] :as state} (synthetic-state)
                 config-before @worker-state/*db-sync-config
                 pull-calls (atom 0)
                 metadata-calls (atom 0)
                 stream-calls (atom 0)
                 prepared (atom [])
                 imported (atom [])
                 finalized (atom [])]
             (reset! worker-state/*db-sync-config
                     {:http-base "https://synthetic.invalid"})
             (-> (with-synthetic-sync-state
                   repo state
                   (fn []
                     (let [pending-before
                           (seed-pending-local-txs! repo conn local-targets 2)]
                       (-> (p/with-redefs
                             [sync-download/fetch-json
                              (fn [_url _opts schema]
                                (is (= :sync/pull schema))
                                ;; attempt 1: 10 -> 11, attempt 2: 11 -> 11
                                (next-pull-watermark!
                                 [10 11 11 11] pull-calls))
                              sync-download/<fetch-snapshot-metadata!
                              (fn [_base _graph-id]
                                (let [attempt (swap! metadata-calls inc)]
                                  (p/resolved
                                   {:snapshot-resp
                                    {:url (str "https://synthetic.invalid/legacy-"
                                               attempt)}
                                    :v2? false})))
                              sync-download/<fetch-snapshot-stream!
                              (fn [_base _graph-id snapshot-info
                                   _refreshed? _on-metadata]
                                (swap! stream-calls inc)
                                (p/resolved
                                 (assoc snapshot-info
                                        :resp #js {:ok true :status 200})))
                              sync-download/prepare-import!
                              (fn [_repo _reset? _graph-id _e2ee? & _]
                                (let [import-id (str "synthetic-import-"
                                                     (inc (count @prepared)))]
                                  (swap! prepared conj import-id)
                                  (p/resolved {:import-id import-id})))
                              sync-download/<stream-snapshot-row-batches!
                              (fn [_resp _batch-size on-batch]
                                (on-batch [[1 "synthetic-row" nil]]))
                              sync-download/import-rows-chunk!
                              (fn [rows _graph-id import-id]
                                (swap! imported conj [import-id rows])
                                (p/resolved nil))
                              sync-download/finalize-import!
                              (fn [_repo _graph-id snapshot-t import-id & _]
                                (swap! finalized conj [import-id snapshot-t])
                                (p/resolved :activated))
                              sync-download/<cancel-snapshot-download!
                              (fn [& _] (p/resolved nil))
                              rtc-log-and-state/rtc-log (fn [& _] nil)]
                             (sync-download/download-graph-by-id!
                              repo graph-id false))
                           (p/then
                            (fn [result]
                              (is (= 11 (:remote-tx result)))
                              (is (= 4 @pull-calls)
                                  "each legacy attempt needs pre/post watermarks")
                              (is (= 2 @metadata-calls))
                              (is (= 2 @stream-calls))
                              (is (= 2 (count @prepared)))
                              (is (= [["synthetic-import-2" 11]] @finalized)
                                  "the unstable first temp import must never activate")
                              (is (= pending-before (pending-semantics repo))
                                  "snapshot retry must not mutate pending work")
                              (is (= "pending-local-title-1"
                                     (:block/title
                                      (d/entity
                                       @conn
                                       [:block/uuid
                                        (:block/uuid (first local-targets))])))
                                  "temporary snapshot rows must not overwrite the live graph")))))))
                 (p/catch (fn [error]
                            (is false (str error))))
                 (p/finally
                  (fn []
                    (reset! worker-state/*db-sync-config config-before)
                    (done))))))))

(deftest continuously-changing-legacy-snapshot-watermark-fails-after-bounded-retries-test
  (testing "an unstable legacy stream never activates and fails explicitly after a bounded retry budget"
    (async done
           (let [repo (str "synthetic-legacy-snapshot-unstable-" (random-uuid))
                 graph-id (str (random-uuid))
                 {:keys [conn local-targets] :as state} (synthetic-state)
                 config-before @worker-state/*db-sync-config
                 pull-calls (atom 0)
                 stream-calls (atom 0)
                 finalized (atom [])]
             (reset! worker-state/*db-sync-config
                     {:http-base "https://synthetic.invalid"})
             (-> (with-synthetic-sync-state
                   repo state
                   (fn []
                     (let [pending-before
                           (seed-pending-local-txs! repo conn local-targets 1)]
                       (-> (p/with-redefs
                             [sync-download/fetch-json
                              (fn [_url _opts schema]
                                (is (= :sync/pull schema))
                                (p/resolved {:t (swap! pull-calls inc)}))
                              sync-download/<fetch-snapshot-metadata!
                              (fn [_base _graph-id]
                                (p/resolved
                                 {:snapshot-resp
                                  {:url "https://synthetic.invalid/legacy-moving"}
                                  :v2? false}))
                              sync-download/<fetch-snapshot-stream!
                              (fn [_base _graph-id snapshot-info
                                   _refreshed? _on-metadata]
                                (swap! stream-calls inc)
                                (p/resolved
                                 (assoc snapshot-info
                                        :resp #js {:ok true :status 200})))
                              sync-download/prepare-import!
                              (fn [& _]
                                (p/resolved
                                 {:import-id (str "moving-import-"
                                                  @stream-calls)}))
                              sync-download/<stream-snapshot-row-batches!
                              (fn [_resp _batch-size on-batch]
                                (on-batch [[1 "moving-row" nil]]))
                              sync-download/import-rows-chunk!
                              (fn [& _] (p/resolved nil))
                              sync-download/finalize-import!
                              (fn [& args]
                                (swap! finalized conj args)
                                (p/resolved :must-not-activate))
                              sync-download/<cancel-snapshot-download!
                              (fn [& _] (p/resolved nil))
                              rtc-log-and-state/rtc-log (fn [& _] nil)]
                             (sync-download/download-graph-by-id!
                              repo graph-id false))
                           (p/then
                            (fn [_]
                              (is false
                                  "continuously moving legacy snapshot was activated")))
                           (p/catch
                            (fn [error]
                              (is (= "db-sync download failed"
                                     (ex-message error)))
                              (is (<= 2 @stream-calls 8)
                                  "retry count must be nonzero and bounded")
                              (is (empty? @finalized)
                                  "no unstable temp import may activate")
                              (is (= pending-before (pending-semantics repo))
                                  "bounded failure must preserve pending work")
                              (is (= "pending-local-title-1"
                                     (:block/title
                                      (d/entity
                                       @conn
                                       [:block/uuid
                                        (:block/uuid (first local-targets))])))
                                  "failed temp imports must not contaminate live data")))))))
                 (p/finally
                  (fn []
                    (reset! worker-state/*db-sync-config config-before)
                    (done))))))))

(defn- caught-remote-apply-error
  [repo client tx-data]
  (try
    (sync-apply/apply-remote-txs!
     repo client [{:t 383 :tx-id (random-uuid) :tx-data tx-data}])
    nil
    (catch :default error
      error)))

(deftest ordinary-unknown-keyword-entity-remains-fail-closed-test
  (testing "compatibility for a known system KV is not a general keyword upsert"
    (let [repo (str "synthetic-unknown-ident-" (random-uuid))
          {:keys [conn remote-target local-targets] :as state} (synthetic-state)
          unknown-ident :untrusted.remote/not-a-system-kv
          client (sync-client repo)
          original-remote-title (:block/title remote-target)]
      (with-synthetic-sync-state
        repo state
        (fn []
          (let [pending-before (seed-pending-local-txs! repo conn local-targets 2)
                error (caught-remote-apply-error
                       repo client
                       [[:db/add unknown-ident :kv/value "untrusted"]
                        [:db/add [:block/uuid (:block/uuid remote-target)]
                         :block/title "must-roll-back"]])]
            (is (= :db-sync/remote-apply-failed (:type (ex-data error))))
            (is (nil? (d/entity @conn unknown-ident))
                "an unknown keyword entity must not be synthesized")
            (is (= original-remote-title
                   (:block/title
                    (d/entity @conn
                              [:block/uuid (:block/uuid remote-target)])))
                "the rejected remote transaction must roll back atomically")
            (is (= pending-before (pending-semantics repo))
                "fail-closed must also preserve the local queue")))))))

(deftest malformed-or-forged-system-kv-transactions-cannot-create-identities-test
  (testing "a missing supported ident cannot be abused to create or rename arbitrary idents"
    (doseq [[label tx-data forbidden-ident]
            [["unsupported logseq.kv name"
              [[:db/add :logseq.kv/not-supported-by-client
                :kv/value "untrusted"]]
              :logseq.kv/not-supported-by-client]
             ["forged ident rewrite"
              [[:db/add supported-system-kv
                :db/ident :untrusted.remote/forged-ident]]
              :untrusted.remote/forged-ident]
             ["incomplete add"
              [[:db/add supported-system-kv :kv/value]]
              :untrusted.remote/incomplete-ident]]]
      (let [repo (str "synthetic-malformed-system-kv-" (random-uuid))
            {:keys [conn local-targets] :as state} (synthetic-state)
            client (sync-client repo)]
        (with-synthetic-sync-state
          repo state
          (fn []
            (let [pending-before (seed-pending-local-txs! repo conn local-targets 1)
                  error (caught-remote-apply-error repo client tx-data)]
              (is (= :db-sync/remote-apply-failed (:type (ex-data error))) label)
              (is (nil? (d/entity @conn forbidden-ident)) label)
              (is (nil? (d/entity @conn supported-system-kv))
                  (str label " must not leave partial compatibility state"))
              (is (= pending-before (pending-semantics repo))
                  (str label " must preserve pending local work")))))))))
