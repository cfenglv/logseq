(ns frontend.worker.sync.download-test
  (:require [cljs.test :refer [async deftest is]]
            [datascript.core :as d]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.apply-txs :as sync-apply]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.crypt :as sync-crypt]
            [frontend.worker.sync.download :as sync-download]
            [frontend.worker.sync.log-and-state :as rtc-log-and-state]
            [frontend.worker.sync.temp-sqlite :as sync-temp-sqlite]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.snapshot :as snapshot]
            [logseq.db.frontend.schema :as db-schema]
            [frontend.worker.sync.transport :as sync-transport]
            [promesa.core :as p]))

(defn- frame-bytes
  [^js data]
  (let [len (.-byteLength data)
        out (js/Uint8Array. (+ 4 len))
        view (js/DataView. (.-buffer out))]
    (.setUint32 view 0 len false)
    (.set out data 4)
    out))

(defn- stream-from-payload
  [^js payload]
  (js/ReadableStream.
   #js {:start (fn [controller]
                 (.enqueue controller payload)
                 (.close controller))}))

(deftest checksum-mismatch-suspect-claims-from-bounded-proof-test
  (async done
         (let [repo "repair-suspect-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               conn (atom :db)
               observation {:remote-t 9
                            :journal-high-water 14
                            :pending-count 0
                            :legacy-anchor "local-cached"}
               diagnostics {:checksum "remote-full"
                            :t 9
                            :legacy-anchor "remote-full"
                            :server-recomputed-checksum "remote-full"
                            :server-checkpoint-identity "checkpoint-9"
                            :metadata-proof "proof-9"}
               fetch-count (atom 0)
               fetch-urls (atom [])
               claims (atom [])
               repair-runs (atom [])
               committed (atom [])
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] conn)
                  worker-state/*db-sync-client (atom client)
                  sync-apply/*repo->latest-remote-tx (atom {repo 9})
                  client-op/read-repair-local-observation
                  (fn [_] observation)
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json
                  (fn [url _opts schema]
                    (is (= :sync/checksum-diagnostics schema))
                    (swap! fetch-count inc)
                    (swap! fetch-urls conj url)
                    (p/resolved diagnostics))
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [claim-repo graph-id basis]
                           (let [result {:claimed? true
                                         :operation {:operation-id
                                                     #uuid "90000000-0000-4000-8000-000000000001"}}]
                             (swap! claims conj [claim-repo graph-id basis])
                             result))
                         :thread-api/db-sync-commit-repair
                         (fn [run-repo operation-id]
                           (swap! repair-runs conj [run-repo operation-id])
                           (p/resolved {:rename-count 1}))})]
                 (sync-download/<claim-repair-after-checksum-mismatch!
                  repo client 9 "remote-full"
                  (fn [result] (swap! committed conj result))))
               (p/then
                (fn [result]
                  (p/then (p/delay 5)
                          (fn [_]
                            (is (= 1 @fetch-count))
                            (is (= ["https://sync.example.test/sync/graph-1/checksum/diagnostics?proof-only=true"]
                                   @fetch-urls))
                            (is (= 1 (count @claims)))
                            (is (= [[repo #uuid "90000000-0000-4000-8000-000000000001"]]
                                   @repair-runs))
                            (is (= [{:rename-count 1}] @committed))
                            (is (true? (:claimed? result)))
                            (is (= {:remote-t 9
                                    :server-checkpoint-identity "checkpoint-9"
                                    :journal-high-water 14
                                    :checksum-basis
                                    {:version 1
                                     :legacy-checksum "local-full"
                                     :server-recomputed-checksum "remote-full"
                                     :server-t 9
                                     :legacy-anchor "remote-full"
                                     :metadata-proof "proof-9"}}
                                   (nth (first @claims) 2)))))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest stale-server-proof-does-not-claim-or-write-checksum-test
  (async done
    (-> (p/doseq [server-t [8 10]]
          (let [repo "stale-proof-repo"
                conn (atom :db)
                client {:graph-id "graph-1" :inflight (atom [])}
                writes (atom 0)]
            (p/with-redefs
              [worker-state/get-datascript-conn (constantly conn)
               worker-state/*db-sync-client (atom client)
               worker-state/*db-sync-config (atom {:http-base "https://sync.example.test"})
               sync-apply/*repo->latest-remote-tx (atom {repo 9})
               client-op/read-repair-local-observation
               (constantly {:remote-t 9 :journal-high-water 14 :pending-count 0 :legacy-anchor "cached"})
               client-op/update-local-checksum (fn [& _] (swap! writes inc))
               sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
               sync-download/fetch-json (fn [& _] (p/resolved {:t server-t}))
               thread-api/*thread-apis
               (atom {:thread-api/db-sync-claim-repair (fn [& _] (swap! writes inc))
                      :thread-api/db-sync-commit-repair (fn [& _] (swap! writes inc))})]
              (p/let [result (sync-download/<claim-repair-after-checksum-mismatch!
                             repo client 9 "remote-full")]
                (is (= {:claimed? false :reason :server-t-changed} result))
                (is (zero? @writes))))))
        (p/catch (fn [error] (is false (str error))))
        (p/finally done))))

(deftest local-only-repair-run-stops-without-automatic-commit-test
  (async done
    (let [calls (atom [])
          operation {:operation-id #uuid "90700000-0000-4000-8000-000000000001"
                     :disposition :local-only :attempt-count 3}]
      (-> (p/with-redefs
            [thread-api/*thread-apis
             (atom {:thread-api/db-sync-stop (fn [] (swap! calls conj :stop))
                    :thread-api/db-sync-commit-repair (fn [& _] (swap! calls conj :commit))
                    :thread-api/db-sync-claim-repair-retry (fn [& _] (swap! calls conj :retry))})
             shared-service/broadcast-to-clients! (fn [& _] (swap! calls conj :warning))]
            (sync-download/<run-repair-operation! "exhausted-repair" operation))
          (p/then (fn [result]
                    (is (= :local-only (:disposition result)))
                    (is (= [:stop :warning] @calls))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest postcommit-cleanup-failure-stops-without-precommit-transition-test
  (async done
    (let [operation {:operation-id #uuid "90700000-0000-4000-8000-000000000002"
                     :target-projection-epoch 5 :disposition :repairing :attempt-count 1}
          error (ex-info "Synthetic postcommit cleanup failure" {:type :selfhost6/repair-commit-cleanup-failed})
          calls (atom [])]
      (-> (p/with-redefs
            [thread-api/*thread-apis
             (atom {:thread-api/db-sync-commit-repair (fn [& _] (p/rejected error))
                    :thread-api/db-sync-repair-status
                    (fn [_]
                      (js/queueMicrotask #(swap! calls conj :next-operation))
                      {:committed {:projection-epoch 5} :operation operation :prepared-swap? false})
                    :thread-api/db-sync-record-repair-failure
                    (fn [& _] (swap! calls conj :record) (throw (js/Error. "Wrong precommit transition")))
                    :thread-api/db-sync-stop (fn [] (swap! calls conj :stop))})]
            (-> (sync-download/<run-repair-operation! "postcommit-cleanup-failure" operation)
                (p/then (fn [_] (is false "The original cleanup error must remain visible.")))
                (p/catch (fn [caught] (is (true? (identical? error caught)))))))
          (p/then (fn [_] (is (= [:stop :next-operation] @calls))))
          (p/catch (fn [caught] (is false (str caught))))
          (p/finally done)))))

(deftest superseded-repair-failure-does-not-change-current-operation-test
  (async done
    (let [operation {:operation-id #uuid "90700000-0000-4000-8000-000000000003"
                     :target-projection-epoch 5 :disposition :repairing :attempt-count 1}
          error (ex-info "Late failure from the prior attempt" {:type :selfhost6/repair-commit-cleanup-failed})]
      (-> (p/doseq [current [nil (assoc operation :target-projection-epoch 6 :attempt-count 2)
                            (assoc operation :attempt-count 2)]]
            (let [calls (atom [])]
              (p/with-redefs
                [thread-api/*thread-apis
                 (atom {:thread-api/db-sync-commit-repair (fn [& _] (p/rejected error))
                        :thread-api/db-sync-repair-status
                        (fn [_] {:committed {:projection-epoch 5} :operation current :prepared-swap? false})
                        :thread-api/db-sync-record-repair-failure (fn [& _] (swap! calls conj :record))
                        :thread-api/db-sync-stop (fn [] (swap! calls conj :stop))})]
                (-> (sync-download/<run-repair-operation! "superseded-repair" operation)
                    (p/then (fn [_] (is false "The original error must propagate.")))
                    (p/catch (fn [caught] (is (true? (identical? error caught)))))
                    (p/then (fn [_] (is (empty? @calls))))))))
          (p/catch (fn [caught] (is false (str caught))))
          (p/finally done)))))

(deftest repair-transient-retry-is-one-bounded-chain-test
  (async done
    (let [repo "repair-transient-retry"
          operation-id #uuid "90300000-0000-4000-8000-000000000001"
          operation* (atom {:operation-id operation-id
                            :target-projection-epoch 1
                            :attempt-count 1
                            :disposition :repairing
                            :next-retry-at-ms nil})
          commits (atom [])
          delays (atom [])]
      (-> (p/with-redefs
            [sync-download/<delay-repair-retry
             (fn [delay-ms]
               (swap! delays conj delay-ms)
               (p/resolved nil))
             thread-api/*thread-apis
             (atom {:thread-api/db-sync-repair-status
                    (fn [_] {:committed {:projection-epoch 0} :operation @operation*})
                    :thread-api/db-sync-commit-repair
                    (fn [_repo id]
                      (swap! commits conj id)
                      (if (< (count @commits) 3)
                        (p/rejected
                         (ex-info "remote moved"
                                  {:type (if (= 1 (count @commits))
                                           :db-sync/repair-remote-tail-moved
                                           :db-sync/repair-staging-remote-advanced)}))
                        (p/resolved {:completed? true})))
                    :thread-api/db-sync-record-repair-failure
                    (fn [_repo _id transition]
                      (swap! operation* merge transition)
                      {:operation @operation*})
                    :thread-api/db-sync-claim-repair-retry
                    (fn [_repo _id _explicit? _now]
                      (swap! operation*
                             #(-> %
                                  (update :attempt-count inc)
                                  (assoc :next-retry-at-ms nil)))
                      @operation*)})]
            (sync-download/<run-repair-operation! repo @operation*))
          (p/then
           (fn [result]
             (is (= {:completed? true} result))
             (is (= [operation-id operation-id operation-id] @commits))
             (is (= 2 (count @delays)))
             (is (<= 0 (first @delays) 1000))
             (is (<= 0 (second @delays) 5000))
             (is (= 3 (:attempt-count @operation*)))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest transient-repair-budget-exhaustion-enters-local-only-test
  (async done
    (let [repo "repair-retry-budget"
          operation-id #uuid "90300000-0000-4000-8000-000000000002"
          operation {:operation-id operation-id
                     :target-projection-epoch 1
                     :attempt-count 3
                     :disposition :repairing
                     :next-retry-at-ms nil}
          transitions (atom [])
          delays (atom 0)]
      (-> (p/with-redefs
            [sync-download/<delay-repair-retry
             (fn [_]
               (swap! delays inc)
               (p/resolved nil))
             shared-service/broadcast-to-clients! (fn [& _] nil)
             thread-api/*thread-apis
             (atom {:thread-api/db-sync-repair-status
                    (fn [_] {:committed {:projection-epoch 0} :operation operation})
                    :thread-api/db-sync-commit-repair
                    (fn [& _]
                      (p/rejected
                       (ex-info "remote moved"
                                {:type :db-sync/repair-staging-remote-advanced})))
                    :thread-api/db-sync-record-repair-failure
                    (fn [_repo _id transition]
                      (swap! transitions conj transition)
                      {:operation (merge operation transition)})
                    :thread-api/db-sync-stop (fn [] (p/resolved nil))})]
            (sync-download/<run-repair-operation! repo operation))
          (p/then
           (fn [result]
             (is (= :local-only (:disposition result)))
             (is (= :local-only (:disposition (first @transitions))))
             (is (nil? (:next-retry-at-ms (first @transitions))))
             (is (zero? @delays))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest deterministic-local-only-explicit-retry-keeps-operation-id-test
  (async done
    (let [repo "repair-local-only-retry"
          operation-id #uuid "90400000-0000-4000-8000-000000000001"
          fault? (atom true)
          operation* (atom {:operation-id operation-id
                            :target-projection-epoch 5
                            :attempt-count 1
                            :disposition :repairing
                            :next-retry-at-ms nil})
          notifications (atom [])
          lifecycle (atom [])
          commits (atom [])]
      (-> (p/with-redefs
            [shared-service/broadcast-to-clients!
             (fn [topic payload]
               (swap! notifications conj [topic payload]))
             thread-api/*thread-apis
             (atom {:thread-api/db-sync-stop
                    (fn []
                      (swap! lifecycle conj :stop)
                      (p/resolved nil))
                    :thread-api/db-sync-start
                    (fn [start-repo]
                      (swap! lifecycle conj [:start start-repo])
                      (p/resolved nil))
                    :thread-api/db-sync-repair-status
                    (fn [_repo]
                      {:committed {:projection-epoch 4}
                       :prepared-swap? false
                       :operation (assoc @operation*
                                         :target-projection-epoch 5)})
                    :thread-api/db-sync-commit-repair
                    (fn [_repo id]
                      (swap! commits conj id)
                      (if @fault?
                        (p/rejected
                         (ex-info "deterministic corruption"
                                  {:type :db-sync/repair-corrupt-snapshot}))
                        (p/resolved {:completed? true})))
                    :thread-api/db-sync-record-repair-failure
                    (fn [_repo _id transition]
                      (swap! operation* merge transition)
                      {:operation @operation*})
                    :thread-api/db-sync-claim-repair-retry
                    (fn [_repo id explicit? _now]
                      (is explicit?)
                      (is (= operation-id id))
                      (swap! operation*
                             #(-> %
                                  (update :attempt-count inc)
                                  (assoc :disposition :repairing
                                         :next-retry-at-ms nil)))
                      @operation*)})]
            (p/let [first-result
                    (sync-download/<run-repair-operation! repo @operation*)
                    restart-result
                    (sync-download/<resume-repair-operation! repo)
                    _ (reset! fault? false)
                    second-result
                    (sync-download/<explicit-retry-repair! repo operation-id)]
              {:initial first-result
               :restart restart-result
               :retried second-result}))
          (p/then
           (fn [{:keys [initial restart retried]}]
             (is (= :local-only (:disposition initial)))
             (is (= {:resumed? false :disposition :local-only} restart))
             (is (= {:completed? true} retried))
             (is (= [operation-id operation-id] @commits))
             (is (= operation-id (:operation-id @operation*)))
             (is (= 2 (:attempt-count @operation*)))
             (is (= :repairing (:disposition @operation*)))
             (is (= 2 (count @notifications)))
             (is (= [:stop :stop [:start repo]] @lifecycle))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest checksum-ready-boundary-completes-only-target-membership-test
  (async done
    (let [repo "repair-completion-boundary"
          graph-id "repair-completion-graph"
          operation-id #uuid "90500000-0000-4000-8000-000000000001"
          conn (atom :canonical-db)
          client {:graph-id graph-id :inflight (atom [])}
          completions (atom [])
          conns-prev @worker-state/*datascript-conns]
      (reset! worker-state/*datascript-conns {repo conn})
      (-> (p/with-redefs
            [worker-state/*db-sync-client (atom client)
             sync-apply/*repo->latest-remote-tx (atom {repo 12})
             client-op/read-repair-completion-observation
             (fn [_repo high-water]
               (is (= 34 high-water))
               {:remote-t 12
                :journal-high-water 35
                :pending-count 1
                :pending-through-target 0
                :legacy-anchor "checksum-12"})
             sync-checksum/<recompute-checksum
             (fn [db]
               (is (= :canonical-db db))
               (p/resolved "checksum-12"))
             thread-api/*thread-apis
             (atom {:thread-api/db-sync-repair-status-from-value
                    (fn [raw]
                      (is (= "activation-value" raw))
                      {:committed {:projection-epoch 5}
                       :prepared-swap? false
                       :operation
                       {:operation-id operation-id
                        :graph-id graph-id
                        :disposition :repairing
                        :target-projection-epoch 5
                        :target-basis {:remote-t 12
                                       :journal-high-water 34}}})
                    :thread-api/db-sync-complete-repair
                    (fn [complete-repo id verification]
                      (swap! completions conj [complete-repo id verification])
                      (p/resolved {:completed? true}))})]
            (sync-download/<complete-repair-if-converged!
             repo client 12 "checksum-12" "activation-value"))
          (p/then
           (fn [result]
             (is (= {:completed? true} result))
             (is (= 1 (count @completions)))
             (is (= operation-id (second (first @completions))))
             (is (= 0 (get-in (first @completions)
                              [2 :pending-through-target])))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally (fn []
                       (reset! worker-state/*datascript-conns conns-prev)
                       (done)))))))

(deftest committed-repair-checks-canonical-data-and-async-freshness-test
  (async done
    (-> (p/doseq [change [:none :canonical-mismatch :db :journal :remote :client :inflight]]
          (let [repo "committed-repair-freshness"
                client {:graph-id "graph-1" :inflight (atom [])}
                conn (atom :canonical-db)
                observation (atom {:remote-t 12 :journal-high-water 35
                                   :pending-count 0 :pending-through-target 0
                                   :legacy-anchor "remote-full"})
                active-client (atom client)
                remote-ts (atom {repo 12})
                canonical (if (= :canonical-mismatch change) "local-full" "remote-full")
                completions (atom 0)
                claims (atom 0)]
            (p/with-redefs
              [worker-state/get-datascript-conn (constantly conn)
               worker-state/*db-sync-client active-client
               worker-state/*db-sync-config (atom {:http-base "https://sync.example.test"})
               sync-apply/*repo->latest-remote-tx remote-ts
               client-op/read-repair-completion-observation (fn [& _] @observation)
               client-op/read-repair-local-observation (fn [_] (dissoc @observation :pending-through-target))
               sync-checksum/<recompute-checksum
               (fn [_]
                 (case change
                   :db (reset! conn :edited-db)
                   :journal (swap! observation update :journal-high-water inc)
                   :remote (swap! remote-ts update repo inc)
                   :client (reset! active-client (assoc client :replacement true))
                   :inflight (reset! (:inflight client) ["pending"])
                   nil)
                 (p/resolved canonical))
               sync-download/fetch-json
               (fn [& _]
                 (p/resolved {:t 12 :legacy-anchor "remote-full"
                              :server-recomputed-checksum "remote-full"
                              :server-checkpoint-identity "checkpoint-12"
                              :metadata-proof "proof-12"}))
               thread-api/*thread-apis
               (atom {:thread-api/db-sync-repair-status-from-value
                      (fn [_]
                        {:committed {:projection-epoch 5}
                         :prepared-swap? false
                         :operation {:operation-id #uuid "90600000-0000-4000-8000-000000000001"
                                     :graph-id "graph-1" :disposition :repairing
                                     :target-projection-epoch 5
                                     :target-basis {:remote-t 12 :journal-high-water 34}}})
                      :thread-api/db-sync-complete-repair
                      (fn [& _] (swap! completions inc) {:completed? true})
                      :thread-api/db-sync-claim-repair
                      (fn [& _] (swap! claims inc) {:claimed? false})})]
              (p/let [_ (sync-download/<complete-repair-if-converged!
                        repo client 12 "remote-full" "receipt")]
                (is (= (if (= :none change) 1 0) @completions) (str change))
                (is (= (if (= :canonical-mismatch change) 1 0) @claims) (str change))))))
        (p/catch (fn [error] (is false (str error))))
        (p/finally done))))

(deftest final-repair-observation-and-mutation-share-a-synchronous-boundary-test
  (async done
    (-> (p/doseq [mode [:claim :complete]]
          (let [repo "repair-final-boundary"
                conn (atom :db)
                client {:graph-id "graph-1" :inflight (atom [])}
                reads (atom 0)
                events (atom [])
                observation (fn [& _]
                              (when (= 2 (swap! reads inc))
                                (js/queueMicrotask #(swap! events conj :later-mutation)))
                              {:remote-t 12 :journal-high-water 35 :pending-count 0
                               :pending-through-target 0 :legacy-anchor "remote-full"})]
            (p/with-redefs
              [worker-state/get-datascript-conn (constantly conn)
               worker-state/*db-sync-client (atom client)
               worker-state/*db-sync-config (atom {:http-base "https://sync.example.test"})
               sync-apply/*repo->latest-remote-tx (atom {repo 12})
               client-op/read-repair-local-observation observation
               client-op/read-repair-completion-observation observation
               sync-checksum/<recompute-checksum (fn [_] (p/resolved (if (= :claim mode) "local-full" "remote-full")))
               sync-download/fetch-json
               (fn [& _] (p/resolved {:t 12 :legacy-anchor "remote-full" :server-recomputed-checksum "remote-full"}))
               thread-api/*thread-apis
               (atom {:thread-api/db-sync-claim-repair
                      (fn [& _] (swap! events conj :claim) {:claimed? false})
                      :thread-api/db-sync-repair-status-from-value
                      (fn [_] {:committed {:projection-epoch 5} :prepared-swap? false
                               :operation {:operation-id #uuid "90600000-0000-4000-8000-000000000001"
                                           :graph-id "graph-1" :disposition :repairing
                                           :target-projection-epoch 5 :target-basis {:remote-t 12 :journal-high-water 34}}})
                      :thread-api/db-sync-complete-repair
                      (fn [& _] (swap! events conj :complete) {:completed? true})})]
              (p/let [_ (if (= :claim mode)
                          (sync-download/<claim-repair-after-checksum-mismatch! repo client 12 "remote-full")
                          (sync-download/<complete-repair-if-converged! repo client 12 "remote-full" "receipt"))]
                (is (= [mode :later-mutation] @events))))))
        (p/catch (fn [error] (is false (str error))))
        (p/finally done))))

(deftest changed-suspect-does-not-claim-test
  (async done
         (let [repo "repair-suspect-changed-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               conn (atom :db)
               observations (atom [{:remote-t 9 :journal-high-water 14
                                    :pending-count 0 :legacy-anchor "local"}
                                   {:remote-t 9 :journal-high-water 15
                                    :pending-count 0 :legacy-anchor "local"}])
               claim-count (atom 0)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] conn)
                  worker-state/*db-sync-client (atom client)
                  sync-apply/*repo->latest-remote-tx (atom {repo 9})
                  client-op/read-repair-local-observation
                  (fn [_]
                    (let [result (first @observations)]
                      (swap! observations subvec 1)
                      result))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json
                  (fn [& _]
                    (p/resolved {:checksum "remote-full"
                                 :t 9
                                 :legacy-anchor "remote-full"
                                 :server-recomputed-checksum "remote-full"
                                 :server-checkpoint-identity "checkpoint-9"
                                 :metadata-proof "proof-9"}))
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [& _] (swap! claim-count inc))})]
                 (sync-download/<claim-repair-after-checksum-mismatch!
                  repo client 9 "remote-full"))
               (p/then (fn [result]
                         (is (= {:claimed? false :reason :suspect-changed}
                                result))
                         (is (zero? @claim-count))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest reopened-graph-discards-stale-suspect-test
  (async done
         (let [repo "repair-suspect-reopened-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               old-conn (atom :old-db)
               new-conn (atom :new-db)
               current-conn (atom old-conn)
               resolve-diagnostics (atom nil)
               diagnostics-promise
               (js/Promise. (fn [resolve _reject]
                              (reset! resolve-diagnostics resolve)))
               observation {:remote-t 9 :journal-high-water 14
                            :pending-count 0 :legacy-anchor "remote-full"}
               writes (atom 0)
               claims (atom 0)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] @current-conn)
                  worker-state/*db-sync-client (atom client)
                  sync-apply/*repo->latest-remote-tx (atom {repo 9})
                  client-op/read-repair-local-observation (fn [_] observation)
                  client-op/update-local-checksum (fn [& _] (swap! writes inc))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json (fn [& _] diagnostics-promise)
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [& _] (swap! claims inc))})]
                 (let [result (sync-download/<claim-repair-after-checksum-mismatch!
                               repo client 9 "remote-full")]
                   (js/setTimeout
                    (fn []
                      (reset! current-conn new-conn)
                      (@resolve-diagnostics
                       {:checksum "remote-full"
                        :t 9
                        :legacy-anchor "remote-full"
                        :server-recomputed-checksum "remote-full"
                        :server-checkpoint-identity "checkpoint-9"
                        :metadata-proof "proof-9"}))
                    0)
                   result))
               (p/then (fn [result]
                         (is (= {:claimed? false :reason :suspect-changed} result))
                         (is (zero? @writes))
                         (is (zero? @claims))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest server-checksum-drift-fails-before-anchor-change-test
  (async done
         (let [repo "repair-server-drift-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               conn (atom :db)
               observation {:remote-t 9 :journal-high-water 14
                            :pending-count 0 :legacy-anchor "message-anchor"}
               writes (atom 0)
               claims (atom 0)
               drift-error (atom nil)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] conn)
                  worker-state/*db-sync-client (atom client)
                  sync-apply/*repo->latest-remote-tx (atom {repo 9})
                  client-op/read-repair-local-observation (fn [_] observation)
                  client-op/update-local-checksum (fn [& _] (swap! writes inc))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json
                  (fn [& _]
                    (p/resolved {:checksum "recomputed"
                                 :t 9
                                 :legacy-anchor "server-anchor"
                                 :server-recomputed-checksum "recomputed"
                                 :server-checkpoint-identity "checkpoint-9"
                                 :metadata-proof "proof-9"}))
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [& _] (swap! claims inc))})]
                 (sync-download/<claim-repair-after-checksum-mismatch!
                  repo client 9 "message-anchor"))
               (p/catch (fn [error]
                          (reset! drift-error error)))
               (p/then (fn []
                         (is (= :db-sync/server-checksum-drift
                                (:type (ex-data @drift-error))))
                         (is (zero? @writes))
                         (is (zero? @claims))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest repair-staging-resume-clears-stale-target-pool-test
  (async done
         (let [operation-id #uuid "90000000-0000-4000-8000-000000000001"
               sql-calls (atom [])
               create-call (atom nil)
               db #js {:exec (fn [sql] (swap! sql-calls conj sql))}
               resource {:db db :pool :target-pool :path "/repair-target.sqlite"}]
           (-> (p/with-redefs
                 [sync-temp-sqlite/<create-named-temp-sqlite-db!
                  (fn [pool-name file-name]
                    (reset! create-call [pool-name file-name])
                    (p/resolved resource))]
                 (#'sync-download/<create-repair-target! operation-id))
               (p/then (fn [result]
                         (is (identical? resource result))
                         (is (= "/repair-target.sqlite" (second @create-call)))
                         (is (= ["delete from kvs"] @sql-calls))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest repair-snapshot-transform-pages-indexed-values-and-local-revisions-test
  (async done
         (let [conn (d/create-conn db-schema/schema)
               entity-count 1001
               decrypt-page-sizes (atom [])
               tx-data (into []
                             (mapcat
                              (fn [eid]
                                [{:db/id eid
                                  :block/uuid (random-uuid)
                                  :block/title (str "encrypted-title-" eid)}
                                 {:db/id eid
                                  :block/name (str "encrypted-name-" eid)}]))
                             (range 1 (inc entity-count)))]
           (d/transact! conn tx-data)
           (-> (p/with-redefs
                 [sync-crypt/<decrypt-snapshot-datoms-batch
                  (fn [_aes-key datoms]
                    (swap! decrypt-page-sizes conj (count datoms))
                    (p/resolved
                     (mapv #(update % :v (fn [value]
                                           (str "plain-" value)))
                           datoms)))]
                 (p/let [_ (#'sync-download/<transform-snapshot-attr!
                            conn :aes-key :block/title)
                         _ (#'sync-download/<transform-snapshot-attr!
                            conn :aes-key :block/name)
                         _ (#'sync-download/<add-snapshot-local-revisions! conn)]
                   nil))
               (p/then
                (fn []
                  (is (= [1000 1 1000 1] @decrypt-page-sizes))
                  (is (= entity-count
                         (count (d/datoms @conn :aevt :block/title))))
                  (is (= "plain-encrypted-title-1001"
                         (:v (first (d/datoms @conn :eavt 1001 :block/title)))))
                  (is (= "plain-encrypted-name-1001"
                         (:v (first (d/datoms @conn :eavt 1001 :block/name)))))
                  (is (= entity-count
                         (count (d/datoms @conn :aevt :block/tx-id))))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest repair-snapshot-preserves-literature-assets-and-reused-pdf-links-test
  (async done
         (let [literature-assets :logseq.property/literature-assets
               conn (d/create-conn
                     (assoc db-schema/schema
                            literature-assets
                            {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many}))
               asset-id 1
               paper-id 2
               link-id 3
               asset-uuid #uuid "94000000-0000-4000-8000-000000000001"
               paper-uuid #uuid "94000000-0000-4000-8000-000000000002"
               link-uuid #uuid "94000000-0000-4000-8000-000000000003"]
           (d/transact! conn
                        [{:db/id asset-id
                          :block/uuid asset-uuid
                          :block/title "encrypted-asset-title"
                          :logseq.property.asset/type "pdf"
                          :logseq.property.asset/checksum "synthetic-checksum"}
                         {:db/id paper-id
                          :block/uuid paper-uuid
                          :block/title "encrypted-paper-title"
                          literature-assets #{asset-id}}
                         {:db/id link-id
                          :block/uuid link-uuid
                          :block/title "encrypted-link-title"
                          :block/link asset-id}])
           (-> (p/with-redefs
                 [sync-crypt/<decrypt-snapshot-datoms-batch
                  (fn [_aes-key datoms]
                    (p/resolved
                     (mapv #(update % :v
                                    {"encrypted-asset-title" "paper.pdf"
                                     "encrypted-paper-title" "Private paper"
                                     "encrypted-link-title" ""})
                           datoms)))]
                 (p/let [_ (#'sync-download/<transform-snapshot-attr!
                            conn :aes-key :block/title)
                         _ (#'sync-download/<add-snapshot-local-revisions! conn)]
                   nil))
               (p/then
                (fn []
                  (let [asset (d/entity @conn [:block/uuid asset-uuid])
                        paper (d/entity @conn [:block/uuid paper-uuid])
                        link (d/entity @conn [:block/uuid link-uuid])]
                    (is (= "paper.pdf" (:block/title asset)))
                    (is (= "Private paper" (:block/title paper)))
                    (is (= "" (:block/title link)))
                    (is (= asset-id (:db/id (:block/link link))))
                    (is (= #{asset-id}
                           (set (map :db/id (literature-assets paper)))))
                    (is (= "synthetic-checksum"
                           (:logseq.property.asset/checksum asset)))
                    (is (every? :block/tx-id [asset paper link])))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(def ^:private repair-builder-diagnostics
  [{:t 5
    :legacy-anchor "server-5"
    :server-recomputed-checksum "server-5"
    :server-checkpoint-identity "sync-do-checkpoint-v1:graph-1:5:server-5"
    :metadata-proof "authenticated-diagnostics-v1:graph-1:5:server-5:server-5"}
   {:t 6
    :legacy-anchor "server-6"
    :server-recomputed-checksum "server-6"
    :server-checkpoint-identity "sync-do-checkpoint-v1:graph-1:6:server-6"
    :metadata-proof "authenticated-diagnostics-v1:graph-1:6:server-6:server-6"}])

(def ^:private repair-builder-target-basis
  {:remote-t 6
   :server-checkpoint-identity "sync-do-checkpoint-v1:graph-1:6:server-6"
   :journal-high-water 9
   :checksum-basis
   {:version 1
    :legacy-checksum "local-final"
    :server-recomputed-checksum "server-6"
    :server-t 6
    :legacy-anchor "server-6"
    :metadata-proof "authenticated-diagnostics-v1:graph-1:6:server-6:server-6"}})

(deftest repair-snapshot-rehydrates-placeholders-without-overwriting-short-edits-test
  (async done
         (let [conn (d/create-conn db-schema/schema)
               marker {:asset-uuid "60000000-0000-4000-8000-000000000001" :asset-type "txt"}
               _ (d/transact! conn [{:db/id 1 :block/title ""
                                    :logseq.property.sync/large-title-object marker}
                                   {:db/id 2 :block/title "A short newer edit"
                                    :logseq.property.sync/large-title-object marker}])
               calls (atom [])]
           (-> (p/with-redefs
                 [sync-apply/rehydrate-large-titles!
                  (fn [repo opts]
                    (swap! calls conj [repo opts])
                    (p/resolved nil))]
                 (p/let [_ (#'sync-download/<rehydrate-repair-titles!
                            "synthetic-repo" "synthetic-graph" conn nil)]
                   (is (= [["synthetic-repo"
                            {:conn conn :graph-id "synthetic-graph" :aes-key nil
                             :tx-data [[:db/add 1 :logseq.property.sync/large-title-object marker]]}]]
                          @calls))
                   (is (= "A short newer edit" (:block/title (d/entity @conn 2))))))
               (p/catch #(is false (str %)))
               (p/finally done)))))

(deftest repair-staging-builder-uses-one-snapshot-resource-and-captures-watermarks-test
  (async done
         (let [repo "repair-staging-repo"
               operation {:operation-id #uuid "91000000-0000-4000-8000-000000000001"
                          :graph-id "graph-1"}
               active-conn (atom :active-db)
               target-conn (atom :target-db)
               target {:name :target :db :target-db :conn target-conn :pool :target-pool}
               diagnostics (atom repair-builder-diagnostics)
               checksums (atom ["server-6" "local-final"])
               apply-calls (atom [])
               cleaned (atom [])
               target-create-count (atom 0)
               local-tx {:tx-id #uuid "92000000-0000-4000-8000-000000000001"}
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                  [sync-download/*repair-staging-builds (atom {})
                  worker-state/get-datascript-conn (fn [_] active-conn)
                  sync-download/<create-repair-target!
                  (fn [_]
                    (swap! target-create-count inc)
                    (p/resolved target))
                  sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (when resource
                      (swap! cleaned conj (:name resource)))
                    (p/resolved nil))
                  sync-crypt/graph-e2ee? (fn [_] false)
                  sync-download/<fetch-repair-snapshot-rows!
                  (fn [_base _graph-id target-db]
                    (is (= :target-db target-db))
                    (p/resolved nil))
                  sync-download/<materialize-repair-target!
                  (fn [resource _aes-key _graph-e2ee?]
                    (is (identical? target resource))
                    (p/resolved target))
                  sync-download/<rehydrate-repair-titles!
                  (fn [_repo _graph-id staging-conn _aes-key]
                    (is (identical? target-conn staging-conn))
                    (p/resolved nil))
                  sync-checksum/<recompute-checksum
                  (fn [_]
                    (let [checksum (first @checksums)]
                      (swap! checksums subvec 1)
                      (p/resolved checksum)))
                  sync-download/<fetch-repair-diagnostics
                  (fn [& _]
                    (let [proof (first @diagnostics)]
                      (swap! diagnostics subvec 1)
                      (p/resolved proof)))
                  sync-download/fetch-json
                  (fn [url _opts schema]
                    (is (= :sync/pull schema))
                    (is (= "https://sync.example.test/sync/graph-1/pull?since=5" url))
                    (p/resolved {:type "pull/ok"
                                 :t 6
                                 :checksum "server-6"
                                 :txs [{:t 6 :tx "remote" :outliner-op :save-block}]}))
                  sync-transport/parse-transit (fn [& _] [:remote-tx])
                  sync-apply/<apply-repair-staging-tails!
                  (fn [_conn remote-txs local-txs _active-db]
                    (swap! apply-calls conj {:remote remote-txs :local local-txs})
                    {:remote-count (count remote-txs)
                     :local-count (count local-txs)})
                  client-op/read-repair-local-batch
                  (fn [_]
                    {:observation {:remote-t 6
                                   :journal-high-water 9
                                   :pending-count 1
                                   :legacy-anchor "server-6"}
                     :pending-ids [9]})
                  client-op/read-repair-local-page
                  (fn [_ ids]
                    (is (= [9] ids))
                    [local-tx])]
                 (let [build (sync-download/<build-repair-staging! repo operation)
                       duplicate-build (sync-download/<build-repair-staging! repo operation)]
                   (is (identical? build duplicate-build))
                   (p/let [result build
                           duplicate-result duplicate-build
                           _ (sync-download/<cleanup-repair-staging! result)]
                     (is (identical? result duplicate-result))
                     result)))
               (p/then
                (fn [result]
                  (is (= [{:remote [{:t 6
                                     :outliner-op :save-block
                                     :tx-data [:remote-tx]}]
                           :local []}
                          {:remote [] :local [local-tx]}]
                         @apply-calls))
                  (is (= repair-builder-target-basis (:target-basis result)))
                  (is (= [:target] @cleaned))
                  (is (= 1 @target-create-count))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest repair-staging-cleanup-failure-remains-visible-test
  (async done
         (let [target {:name :target :pool :target-pool}
               staging {:target target}
               cleanup-error (ex-info "cleanup failed" {:type :db-sync/cleanup-failed})]
           (-> (p/with-redefs
                 [sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (is (identical? target resource))
                    (p/rejected cleanup-error))]
                 (sync-download/<cleanup-repair-staging! staging))
               (p/then (fn [_]
                         (is false "expected cleanup failure")))
               (p/catch (fn [error]
                          (is (identical? cleanup-error error))
                          (is (identical? target (:target staging)))))
               (p/finally done)))))

(deftest stale-repair-cleanup-cannot-release-new-owner-test
  (async done
         (let [repo "repair-owner-token-repo"
               graph-id "graph-1"
               operation {:operation-id #uuid "94000000-0000-4000-8000-000000000001"
                          :graph-id graph-id}
               stale-token {:cancelled? (atom false)}
               current-token {:cancelled? (atom false)}
               cleaned (atom 0)
               builds (atom {[repo graph-id]
                             {:operation-id (:operation-id operation)
                              :owner-token current-token
                              :build (p/resolved nil)}})]
           (-> (p/with-redefs
                 [sync-download/*repair-staging-builds builds
                  sync-download/<cleanup-repair-resource!
                  (fn [_]
                    (swap! cleaned inc)
                    (p/resolved nil))]
                 (sync-download/<cleanup-repair-staging!
                  {:repo repo
                   :operation operation
                   :target {:name :stale-target}
                   :staging-owner-token stale-token}))
               (p/then (fn [_]
                         (is (zero? @cleaned))
                         (is (identical? current-token
                                         (get-in @builds
                                                 [[repo graph-id] :owner-token])))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest graph-close-invalidates-and-cleans-repair-staging-owner-test
  (async done
         (let [repo "repair-close-repo"
               graph-id "graph-1"
               operation {:operation-id #uuid "95000000-0000-4000-8000-000000000001"
                          :graph-id graph-id}
               owner-token {:cancelled? (atom false)}
               target {:name :target}
               result {:repo repo
                       :operation operation
                       :target target
                       :staging-owner-token owner-token}
               cleaned (atom [])
               cleanup-resolve (atom nil)
               cleanup-promise (js/Promise. (fn [resolve]
                                              (reset! cleanup-resolve resolve)))
               builds (atom {[repo graph-id]
                             {:operation-id (:operation-id operation)
                              :owner-token owner-token
                              :build (p/resolved result)}})]
           (-> (p/with-redefs
                 [sync-download/*repair-staging-builds builds
                  sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (swap! cleaned conj resource)
                    cleanup-promise)]
                 (sync-download/close-repair-staging-for-repo! repo)
                 (let [closing-build
                       (-> (sync-download/<build-repair-staging! repo operation)
                           (p/then (fn [value] {:value value}))
                           (p/catch (fn [error] {:error error})))]
                   (p/let [closing-result closing-build
                           _ (do (@cleanup-resolve nil) cleanup-promise)]
                   (is (true? @(:cancelled? owner-token)))
                   (is (= :db-sync/repair-staging-closing
                          (-> closing-result :error ex-data :type)))
                   (is (= [target] @cleaned))
                   (is (empty? @builds)))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest repair-staging-surfaces-cleanup-failure-with-unverified-tail-test
  (async done
         (let [repo "repair-staging-mismatch-repo"
               operation {:operation-id #uuid "93000000-0000-4000-8000-000000000001"
                          :graph-id "graph-1"}
               active-conn (atom :active-db)
               target {:name :target
                       :db :target-db
                       :conn (atom :target-db)
                       :pool :target-pool}
               cleaned (atom [])
               cleanup-error (ex-info "target cleanup failed"
                                      {:type :db-sync/target-cleanup-failed})
               pull-count (atom 0)
               apply-count (atom 0)
               local-read-count (atom 0)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                  [sync-download/*repair-staging-builds (atom {})
                  worker-state/get-datascript-conn (fn [_] active-conn)
                  sync-download/<create-repair-target! (fn [_] (p/resolved target))
                  sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (when resource
                      (swap! cleaned conj (:name resource)))
                    (if (= :target (:name resource))
                      (p/rejected cleanup-error)
                      (p/resolved nil)))
                  sync-crypt/graph-e2ee? (fn [_] false)
                  sync-download/<fetch-repair-snapshot-rows! (fn [& _] (p/resolved nil))
                  sync-download/<materialize-repair-target!
                  (fn [resource _aes-key _graph-e2ee?]
                    (p/resolved resource))
                  sync-download/<rehydrate-repair-titles! (fn [& _] (p/resolved nil))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "stale-snapshot"))
                  sync-download/<fetch-repair-diagnostics
                  (fn [& _]
                    (p/resolved
                     {:t 5
                      :legacy-anchor "server-5"
                      :server-recomputed-checksum "server-5"
                      :server-checkpoint-identity
                      "sync-do-checkpoint-v1:graph-1:5:server-5"
                      :metadata-proof
                      "authenticated-diagnostics-v1:graph-1:5:server-5:server-5"}))
                  sync-download/fetch-json
                  (fn [_url _opts schema]
                    (is (= :sync/pull schema))
                    (swap! pull-count inc)
                    (p/resolved {:type "pull/ok"
                                 :t 5
                                 :checksum "server-5"
                                 :txs []}))
                  sync-apply/<apply-repair-staging-tails!
                  (fn [& _]
                    (swap! apply-count inc)
                    {:remote-count 0 :local-count 0})
                  client-op/read-repair-local-batch
                  (fn [& _]
                    (swap! local-read-count inc)
                    nil)]
                 (sync-download/<build-repair-staging! repo operation))
               (p/then (fn [_]
                         (is false "expected unverified tail failure")))
               (p/catch (fn [error]
                          (is (= :db-sync/repair-staging-cleanup-failed
                                 (:type (ex-data error))))
                          (is (= :db-sync/repair-remote-tail-checksum-mismatch
                                 (-> error ex-data :original-error ex-data :type)))
                          (is (= [cleanup-error]
                                 (:cleanup-errors (ex-data error))))
                          (is (identical? target (:target (ex-data error))))
                          (is (= [:target] @cleaned))
                          (is (= 1 @pull-count))
                          (is (= 1 @apply-count))
                          (is (zero? @local-read-count))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest stream-snapshot-row-batches-ignores-stale-gzip-header-test
  (async done
         (let [rows [[1 "row-1" nil]
                     [2 "row-2" nil]]
               payload (frame-bytes (snapshot/encode-rows rows))
               resp (js/Response.
                     (stream-from-payload payload)
                     #js {:status 200
                          :headers #js {"content-encoding" "gzip"}})
               batches* (atom [])]
           (-> (#'sync-download/<stream-snapshot-row-batches!
                resp
                1000
                (fn [batch]
                  (swap! batches* conj batch)
                  (p/resolved true)))
               (p/then (fn [_]
                         (is (= [rows] @batches*))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest snapshot-gzip-probe-cancels-unused-tee-branch-test
  (async done
         (let [cancel-count (atom 0)
               release-count (atom 0)
               reader #js {:read (fn []
                                    (p/resolved
                                     #js {:done false
                                          :value (js/Uint8Array. #js [1 2 3])}))
                           :cancel (fn []
                                     (swap! cancel-count inc)
                                     (p/resolved nil))
                           :releaseLock (fn [] (swap! release-count inc))}
               stream #js {:getReader (fn [] reader)}]
           (-> (#'sync-download/<stream-starts-with-gzip? stream)
               (p/then (fn [gzip?]
                         (is (false? gzip?))
                         (is (= 1 @cancel-count))
                         (is (= 1 @release-count))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest repair-staging-final-fence-applies-only-new-local-ids-and-rejects-remote-move-test
  (async done
         (let [repo "repair-final-fence-repo"
               graph-id "repair-final-fence-graph"
               active-conn (atom :active-db)
               config-prev @worker-state/*db-sync-config
               conns-prev @worker-state/*datascript-conns
               applied-ids (atom nil)
               remote-t* (atom 12)
               proof (fn []
                       (let [remote-t @remote-t*
                             anchor "server-checksum"]
                         {:t remote-t
                          :legacy-anchor anchor
                          :server-recomputed-checksum anchor
                          :server-checkpoint-identity
                          (str "sync-do-checkpoint-v1:" graph-id ":" remote-t ":" anchor)
                          :metadata-proof
                          (str "authenticated-diagnostics-v1:" graph-id ":" remote-t ":"
                               anchor ":" anchor)}))
               staging {:repo repo
                        :operation {:operation-id
                                    #uuid "99000000-0000-4000-8000-000000000001"
                                    :graph-id graph-id}
                        :target {:conn (atom :target-db)}
                        :target-basis {:remote-t 12
                                       :journal-high-water 10}
                        :local-result {:local-count 3}
                        :staging-owner-token {:cancelled? (atom false)}}
               capture-outcome (fn [f]
                                 (try
                                   (-> (f)
                                       (p/then (fn [value] {:value value}))
                                       (p/catch (fn [error] {:error error})))
                                   (catch :default error
                                     (p/resolved {:error error}))))]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (reset! worker-state/*datascript-conns {repo active-conn})
           (-> (p/with-redefs
                 [sync-download/repair-staging-owner? (fn [& _] true)
                  sync-download/<fetch-repair-diagnostics (fn [& _] (p/resolved (proof)))
                  sync-download/capture-repair-local-state
                  (fn [& _]
                    {:local-batch {:pending-ids [9 10 11 12]
                                   :observation {:remote-t 12
                                                 :journal-high-water 12}}
                     :active-db :captured-active-db})
                  sync-download/<apply-repair-local-watermark!
                  (fn [_repo _target-conn _active-db ids]
                    (reset! applied-ids ids)
                    (p/resolved {:remote-count 0 :local-count (count ids)}))
                  sync-checksum/<recompute-checksum
                  (fn [_db] (p/resolved "target-checksum"))]
                 (p/let [finalized (sync-download/<finalize-repair-staging! staging)
                         _ (reset! remote-t* 13)
                         remote-move-outcome
                         (capture-outcome
                          #(sync-download/<finalize-repair-staging! staging))]
                   (is (= [11 12] @applied-ids))
                   (is (= 12 (get-in finalized [:target-basis :journal-high-water])))
                   (is (= 5 (get-in finalized [:local-result :local-count])))
                   (is (= :db-sync/repair-staging-remote-advanced
                          (:type (ex-data (:error remote-move-outcome)))))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (reset! worker-state/*datascript-conns conns-prev)
                            (done)))))))

(deftest encrypted-download-preflights-e2ee-before-fetching-snapshot-stream-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               fetch-prev js/fetch
               calls (atom [])]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (set! js/fetch
                 (fn [_url _opts]
                   (swap! calls conj :snapshot-stream)
                   (js/Promise.resolve #js {:ok true})))
           (-> (p/with-redefs [sync-download/fetch-json (fn [_url _opts schema]
                                                          (case schema
                                                            :sync/snapshot-download
                                                            (p/resolved {:t 42 :url "https://sync.example.test/snapshot"})

                                                            (p/rejected (ex-info "unexpected schema" {:schema schema}))))
                               sync-crypt/<fetch-graph-aes-key-for-download (fn [_graph-id]
                                                                              (swap! calls conj :e2ee-preflight)
                                                                              (p/resolved :aes-key))
                               sync-download/<stream-snapshot-row-batches! (fn [_resp _batch-size _on-batch]
                                                                             (p/resolved {:chunk-count 0}))]
                 (sync-download/download-graph-by-id! "repo" "graph-1" true))
               (p/then (fn [_]
                         (is (= [:e2ee-preflight :snapshot-stream] @calls))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest encrypted-download-failure-emits-completed-log-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               log-events (atom [])]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (-> (p/with-redefs [sync-download/fetch-json (fn [_url _opts schema]
                                                          (case schema
                                                            :sync/snapshot-download
                                                            (p/resolved {:t 42 :url "https://sync.example.test/snapshot"})

                                                            (p/rejected (ex-info "unexpected schema" {:schema schema}))))
                               sync-crypt/<fetch-graph-aes-key-for-download (fn [_graph-id]
                                                                              (p/rejected (ex-info "decrypt-private-key" {})))
                               rtc-log-and-state/rtc-log (fn [type payload]
                                                           (swap! log-events conj (assoc payload :type type))
                                                           nil)]
                 (sync-download/download-graph-by-id! "repo" "graph-1" true))
               (p/then (fn [_]
                         (is false "expected download failure")))
               (p/catch (fn [error]
                          (is (= "db-sync download failed" (ex-message error)))
                          (is (= [:download-progress :download-completed]
                                 (mapv :sub-type @log-events)))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest download-uses-snapshot-position-without-pulling-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               state-prev @worker-state/*state
               fetch-prev js/fetch
               calls (atom [])
               imported (atom [])
               finalized (atom [])
               rows [[1 "snapshot-row" nil]]
               snapshot-t (atom 0)]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (swap! worker-state/*state assoc :auth/id-token "snapshot-test-token")
           (set! js/fetch
                 (fn [url _opts]
                   (swap! calls conj url)
                   (js/Promise.resolve
                    (case url
                      "https://sync.example.test/sync/graph-1/snapshot/download"
                      (js/Response. (js/JSON.stringify (clj->js {:ok true :key "stream/graph-1.snapshot"
                                                               :url "https://sync.example.test/snapshot" :t @snapshot-t})))
                      "https://sync.example.test/snapshot"
                      (js/Response. (stream-from-payload (frame-bytes (snapshot/encode-rows rows))))
                      (throw (ex-info "Unexpected download request" {:url url}))))))
           (-> (p/with-redefs [sync-download/prepare-import! (fn [& _] (p/resolved {:import-id "snapshot-import"}))
                               sync-download/import-rows-chunk! (fn [batch _graph-id _import-id]
                                                                  (swap! imported conj batch)
                                                                  (p/resolved true))
                               sync-download/finalize-import! (fn [_repo _graph-id t _import-id]
                                                                (swap! finalized conj t)
                                                                (p/resolved true))]
                 (p/loop [positions [0 42]]
                   (when-let [t (first positions)]
                     (reset! snapshot-t t)
                     (p/let [result (sync-download/download-graph-by-id! "repo" "graph-1" false)]
                       (is (= t (:remote-tx result)))
                       (p/recur (next positions))))))
               (p/then (fn []
                         (is (= [0 42] @finalized))
                         (is (= [rows rows] @imported))
                         (is (= (vec (repeat 2 ["https://sync.example.test/sync/graph-1/snapshot/download" "https://sync.example.test/snapshot"]))
                                (mapv vec (partition 2 @calls))))))
               (p/catch (fn [error] (is false (str error))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (reset! worker-state/*db-sync-config config-prev)
                            (reset! worker-state/*state state-prev)
                            (done)))))))

(deftest download-rejects-snapshot-without-a-journal-position-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               state-prev @worker-state/*state
               fetch-prev js/fetch
               calls (atom [])]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (swap! worker-state/*state assoc :auth/id-token "snapshot-test-token")
           (set! js/fetch
                 (fn [url _opts]
                   (swap! calls conj url)
                   (js/Promise.resolve
                    (js/Response. (js/JSON.stringify (clj->js {:ok true :key "stream/graph-1.snapshot"
                                                             :url "https://sync.example.test/snapshot"}))))))
           (-> (sync-download/download-graph-by-id! "repo" "graph-1" false)
               (p/then (fn [_] (is false "Missing snapshot t must reject before downloading data")))
               (p/catch (fn [error]
                          (is (= "db-sync download failed" (ex-message error)))
                          (is (= ["https://sync.example.test/sync/graph-1/snapshot/download"] @calls))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (reset! worker-state/*db-sync-config config-prev)
                            (reset! worker-state/*state state-prev)
                            (done)))))))
