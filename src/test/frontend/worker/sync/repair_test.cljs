(ns frontend.worker.sync.repair-test
  (:require [cljs.test :refer [async deftest is testing]]
            [frontend.common.thread-api :as api]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync :as sync]
            [frontend.worker.sync.apply-txs :as apply-txs]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.download :as download]
            [frontend.worker.sync.handle-message :as messages]
            [frontend.worker.sync.transport :as transport]
            [frontend.worker.sync.util :as util]
            [logseq.db-sync.checksum :as checksum]
            [promesa.core :as p]))

(deftest explicit-repair-rechecks-context-after-diagnostics
  (async done
    (let [repo "repair-test"
          conn (atom {})
          socket #js {}
          client {:repo repo :graph-id "graph-1" :ws socket :inflight (atom [])}
          observation {:remote-t 5 :journal-high-water 8 :pending-count 0 :legacy-anchor "same"}
          diagnostics {:t 5 :legacy-anchor "same" :server-recomputed-checksum "same"
                       :server-checkpoint-identity "checkpoint" :metadata-proof "proof"}
          claims (atom [])
          runs (atom 0)
          pulls (atom 0)
          *conn (atom conn)
          *observation (atom observation)
          *hook (atom (fn [] nil))
          *client (atom client)]
      (-> (p/with-redefs [worker-state/*db-sync-client *client
                         sync/*repo->latest-remote-tx (atom {repo 5})
                         worker-state/get-datascript-conn (fn [_] @*conn)
                         util/get-graph-id (constantly "graph-1")
                         transport/ws-open? (constantly true)
                         checksum/<recompute-checksum (fn [_] (p/resolved "same"))
                         client-op/read-repair-local-observation (fn [_] @*observation)
                         util/fetch-json (fn [& _] (@*hook) (p/resolved diagnostics))
                         api/*thread-apis (volatile! {:thread-api/db-sync-claim-repair
                                                     (fn [& args]
                                                       (swap! claims conj args)
                                                       {:claimed? true :operation {:operation-id "operation"}})})
                         download/<run-repair-operation! (fn [& _] (swap! runs inc) (p/resolved {:rename-count 1}))
                         apply-txs/enqueue-flush-pending! (fn [& _] nil)
                         client-op/get-local-tx (constantly 5)
                         messages/request-pull! (fn [& _] (swap! pulls inc))]
            (p/let [ready (sync/<request-repair! repo)
                    _ (p/delay 0)]
              (is (:claimed? ready))
              (is (= [1 1 1] [(count @claims) @runs @pulls]))
              (reset! *hook #(reset! *client nil))
              (p/let [disconnected (sync/<request-repair! repo)]
                (is (= :context-changed (:reason disconnected)))
                (reset! *client client)
                (reset! *hook #(reset! *conn nil))
                (p/let [closed (sync/<request-repair! repo)]
                  (is (= :context-changed (:reason closed)))
                  (reset! *conn conn)
                  (reset! *hook #(swap! *observation update :journal-high-water inc))
                  (p/let [edited (sync/<request-repair! repo)]
                    (is (= :not-converged (:reason edited)))
                    (reset! *hook (fn [] nil))
                    (swap! *observation assoc :pending-count 1)
                    (p/let [pending (sync/<request-repair! repo)]
                      (is (= :not-converged (:reason pending)))
                      (reset! *observation observation)
                      (reset! (:inflight client) ["pending"])
                      (p/let [inflight (sync/<request-repair! repo)
                              wrong-repo (sync/<request-repair! "another-graph")]
                        (is (= :not-converged (:reason inflight)))
                        (is (= :inactive-context (:reason wrong-repo)))
                        (is (= [1 1 1] [(count @claims) @runs @pulls])))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest repair-swap-wakes-current-connection-after-request-or-resume
  (async done
    (let [repo "repair-wakeup-test"
          conn (atom {})
          client {:repo repo :graph-id "graph-1" :ws #js {:readyState 1} :inflight (atom [])}
          replacement (assoc client :ws #js {:readyState 1})
          *client (atom client)
          *graph-id (atom "graph-1")
          *runner (atom nil)
          *started (atom nil)
          pulls (atom [])
          flushes (atom [])
          observation {:remote-t 5 :journal-high-water 8 :pending-count 0}
          cases (for [entry [:request :resume]
                      [label next-client next-graph result expected]
                      [[:same client "graph-1" {:rename-count 1} client]
                       [:reconnected replacement "graph-1" {:rename-count 1} replacement]
                       [:closed (assoc client :ws #js {:readyState 3}) "graph-1" {:rename-count 1} nil]
                       [:other-repo (assoc client :repo "other") "graph-1" {:rename-count 1} nil]
                       [:other-graph (assoc client :graph-id "graph-2") "graph-2" {:rename-count 1} nil]
                       [:no-client nil "graph-1" {:rename-count 1} nil]
                       [:no-swap client "graph-1" {:resumed? false} nil]
                       [:local-only client "graph-1" {:disposition :local-only} nil]]]
                  [entry label next-client next-graph result expected])]
      (-> (p/with-redefs
            [worker-state/*db-sync-client *client
             sync/*repo->latest-remote-tx (atom {repo 5})
             worker-state/get-datascript-conn (constantly conn)
             util/get-graph-id (fn [_] @*graph-id)
             transport/ws-open? (fn [socket] (= 1 (.-readyState socket)))
             checksum/<recompute-checksum (fn [_] (p/resolved "same"))
             client-op/read-repair-local-observation (constantly observation)
             client-op/get-local-tx (constantly 5)
             util/fetch-json (fn [& _]
                               (p/resolved {:t 5 :legacy-anchor "same" :server-recomputed-checksum "same"
                                            :server-checkpoint-identity "checkpoint" :metadata-proof "proof"}))
             api/*thread-apis (volatile! {:thread-api/db-sync-claim-repair
                                         (fn [& _] {:claimed? true :operation {:operation-id "operation"}})})
             download/<run-repair-operation! (fn [& _] @*runner)
             download/<resume-repair-operation! (fn [_] (p/resolve! @*started true) @*runner)
             apply-txs/enqueue-flush-pending! (fn [target-repo target-client]
                                               (swap! flushes conj [target-repo target-client]))
             messages/request-pull! (fn [target-client tx] (swap! pulls conj [target-client tx]))]
            (reduce
             (fn [chain [entry label next-client next-graph result expected]]
               (p/then chain
                       (fn [_]
                         (reset! *client client)
                         (reset! *graph-id "graph-1")
                         (reset! *runner (p/deferred))
                         (reset! *started (p/deferred))
                         (reset! pulls [])
                         (reset! flushes [])
                         (p/let [_ (if (= :request entry)
                                     (sync/<request-repair! repo)
                                     (do (#'sync/defer-repair-resume! repo) @*started))]
                           (is (empty? @pulls) (str entry " " label ": no pull before swap"))
                           (reset! *client next-client)
                           (reset! *graph-id next-graph)
                           (p/resolve! @*runner result)
                           (p/let [_ (p/delay 0)]
                             (is (= (if expected [[expected 5]] []) @pulls) (str entry " " label))
                             (is (= (if expected [[repo expected]] []) @flushes) (str entry " " label)))))))
             (p/resolved nil)
             cases))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest equal-head-completion-preserves-readiness-boundaries
  (doseq [[label head remote pending inflight remote-checksum expected]
          [[:ready 5 5 0 [] "same" 1]
           [:pending 5 5 1 [] "same" 0]
           [:inflight 5 5 0 ["pending"] "same" 0]
           [:newer-head 6 5 0 [] "same" 0]
           [:old-response 5 4 0 [] "same" 0]
           [:different-checksum 5 5 0 [] "different" 0]]]
    (testing label
      (let [calls (atom 0)
            client {:inflight (atom inflight) :pending-pull-since (atom nil)}]
        (with-redefs [apply-txs/*repo->latest-remote-tx (atom {"repo" head})
                      apply-txs/*repo->latest-remote-checksum (atom {"repo" "same"})
                      client-op/get-local-tx (constantly 5)
                      client-op/get-pending-local-tx-count (constantly pending)
                      client-op/read-local-checksum-and-sync-meta-value
                      (fn [& _] {:local-checksum "same" :reserved-value "activation"})
                      download/<complete-repair-if-converged!
                      (fn [& _] (swap! calls inc) (p/resolved {:completed? false}))]
          (messages/handle-message! "repo" client
                                    (js/JSON.stringify #js {:type "pull/ok" :t remote
                                                           :checksum remote-checksum :txs #js []}))
          (is (= expected @calls))
          (is (= head (get @apply-txs/*repo->latest-remote-tx "repo"))))))))
