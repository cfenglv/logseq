(ns frontend.worker.sync.restart-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.worker.platform :as platform]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync :as sync]
            [frontend.worker.sync.apply-txs :as sync-apply]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.util :as sync-util]
            [promesa.core :as p]))

(deftest heartbeat-sends-json-ping-while-websocket-is-open-test
  (let [sent (atom [])
        interval-f* (atom nil)
        original-clear-interval js/clearInterval
        original-set-interval js/setInterval
        prev-client @worker-state/*db-sync-client
        ws #js {:readyState 1
                :send (fn [payload] (swap! sent conj payload))
                :close (fn [] nil)}
        client {:repo "heartbeat-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :last-ws-message-ts (atom (js/Date.now))
                :inflight (atom [])
                :online-users (atom [])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}]
    (set! js/setInterval
          (fn [f _ms]
            (reset! interval-f* f)
            :interval-id))
    (set! js/clearInterval (fn [_] nil))
    (reset! worker-state/*db-sync-client client)
    (try
      (#'sync/close-stale-ws-loop client ws "wss://sync.example.test/sync/graph-1")
      (@interval-f*)
      (is (= ["{\"type\":\"ping\"}"] @sent))
      (finally
        (reset! worker-state/*db-sync-client prev-client)
        (set! js/setInterval original-set-interval)
        (set! js/clearInterval original-clear-interval)))))

(deftest heartbeat-send-failure-invalidates-and-schedules-reconnect-test
  (let [interval-f* (atom nil)
        original-clear-interval js/clearInterval
        original-set-interval js/setInterval
        original-set-timeout js/setTimeout
        prev-client @worker-state/*db-sync-client
        ws #js {:readyState 1
                :send (fn [_] (throw (js/Error. "socket write failed")))
                :close (fn [] nil)}
        client {:repo "heartbeat-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :last-ws-message-ts (atom (js/Date.now))
                :inflight (atom [(random-uuid)])
                :online-users (atom [])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}]
    (set! js/setInterval
          (fn [f _ms]
            (reset! interval-f* f)
            :interval-id))
    (set! js/clearInterval (fn [_] nil))
    (set! js/setTimeout (fn [& _] :timeout-id))
    (reset! worker-state/*db-sync-client client)
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _] nil)]
      (try
        (#'sync/close-stale-ws-loop client ws "wss://sync.example.test/sync/graph-1")
        (@interval-f*)
        (is (= :closed @(:ws-state client)))
        (is (empty? @(:inflight client)))
        (is (= :timeout-id (:timer @(:reconnect client))))
        (finally
          (reset! worker-state/*db-sync-client prev-client)
          (set! js/setInterval original-set-interval)
          (set! js/clearInterval original-clear-interval)
          (set! js/setTimeout original-set-timeout))))))

(deftest start-reconnects-half-open-stale-ws-test
  (async done
         (let [repo "suspended-repo"
               graph-id "graph-1"
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               prev-platform (try
                               (platform/current)
                               (catch :default _ nil))
               connect-calls (atom 0)
               flush-calls (atom 0)
               half-open-ws #js {:readyState 1
                                  :close (fn [] nil)}
               stale-client {:repo repo
                             :graph-id graph-id
                             :ws half-open-ws
                             :ws-state (atom :open)
                             :last-ws-message-ts (atom 0)
                             :inflight (atom [])
                             :online-users (atom [])
                             :reconnect (atom {:attempt 0 :timer nil})
                             :stale-kill-timer (atom nil)}]
           (reset! worker-state/*db-sync-config {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client stale-client)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect (fn [_url]
                                    (swap! connect-calls inc)
                                    #js {:readyState 0
                                         :close (fn [] nil)})}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs [worker-state/get-client-ops-conn (fn [_repo] true)
                               client-op/get-local-tx (fn [_repo] 0)
                               client-op/get-pending-local-tx-count (fn [_repo] 0)
                               client-op/get-unpushed-asset-ops-count (fn [_repo] 0)
                               client-op/get-all-asset-ops (fn [_repo] [])
                               client-op/get-local-checksum (fn [_repo] nil)
                               client-op/get-graph-uuid (fn [_repo] graph-id)
                               client-op/update-graph-uuid (fn [_repo _graph-id] nil)
                               sync-util/get-graph-id (fn [_repo] graph-id)
                               sync/<resolve-ws-token (fn [] (p/resolved "token"))
                               sync-apply/enqueue-flush-pending! (fn [& _]
                                                                  (swap! flush-calls inc))
                               shared-service/broadcast-to-clients! (fn [& _] nil)]
                 (sync/start! repo))
               (p/then
                (fn [_]
                  (is (= 1 @connect-calls)
                      "start! should replace a half-open websocket after a long suspend")
                  (is (zero? @flush-calls)
                      "pending work must not be flushed into a stale websocket")))
               (p/catch
                (fn [error]
                  (is false (str "unexpected error: " error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-client prev-client)
                  (reset! worker-state/*db-sync-config prev-config)
                  (when prev-platform
                    (platform/set-platform! prev-platform))
                  (done)))))))

(deftest concurrent-start-for-same-graph-shares-one-auth-and-connect-test
  (async done
         (let [repo "concurrent-start-repo"
               graph-id "graph-1"
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               prev-start-inflight @sync/*start-inflight
               token-resolve* (atom nil)
               token-calls* (atom 0)
               connect-calls* (atom 0)
               token-promise
               (js/Promise.
                (fn [resolve _reject]
                  (reset! token-resolve* resolve)))]
           (reset! worker-state/*db-sync-config
                   {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client nil)
           (reset! sync/*start-inflight nil)
           (-> (p/with-redefs
                 [worker-state/get-client-ops-conn (fn [_repo] true)
                  client-op/get-local-tx (fn [_repo] 0)
                  client-op/update-graph-uuid (fn [& _] nil)
                  sync-util/get-graph-id (fn [_repo] graph-id)
                  sync/<resolve-ws-token
                  (fn []
                    (swap! token-calls* inc)
                    token-promise)
                  sync/connect!
                  (fn [_repo client _url token]
                    (is (= "token" token))
                    (swap! connect-calls* inc)
                    (assoc client
                           :ws #js {:readyState 0
                                    :close (fn [] nil)}
                           :ws-state (atom :connecting)))
                  shared-service/broadcast-to-clients! (fn [& _] nil)]
                 (let [first-start (sync/start! repo)
                       second-start (sync/start! repo)]
                   (p/let [_ (p/delay 10)
                           _ (do
                               (is (= 1 @token-calls*))
                               (is (zero? @connect-calls*))
                               (@token-resolve* "token"))
                           _ (p/all [first-start second-start])]
                     (is (= 1 @token-calls*))
                     (is (= 1 @connect-calls*))
                     (is (= repo (:repo @worker-state/*db-sync-client)))
                     (is (= graph-id
                            (:graph-id @worker-state/*db-sync-client))))))
               (p/catch
                (fn [error]
                  (is false (str "unexpected error: " error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-client prev-client)
                  (reset! worker-state/*db-sync-config prev-config)
                  (reset! sync/*start-inflight prev-start-inflight)
                  (done)))))))

(deftest resume-invalidates-half-open-ws-and-deduplicates-reconnect-test
  (async done
         (let [repo "suspended-repo"
               graph-id "graph-1"
               original-set-timeout js/setTimeout
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               close-calls (atom 0)
               ws #js {:readyState 1
                       :close (fn [] (swap! close-calls inc))}
               client {:repo repo
                       :graph-id graph-id
                       :ws ws
                       :ws-state (atom :open)
                       :last-ws-message-ts (atom (js/Date.now))
                       :inflight (atom [(random-uuid)])
                       :online-users (atom [])
                       :reconnect (atom {:attempt 0 :timer nil})
                       :stale-kill-timer (atom nil)}]
           (set! js/setTimeout (fn [& _] :timeout-id))
           (reset! worker-state/*db-sync-config {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client client)
           (-> (p/with-redefs [sync-util/get-graph-id (fn [_] graph-id)
                               shared-service/broadcast-to-clients! (fn [& _] nil)]
                 (p/do!
                  (sync/resume! repo)
                  (sync/resume! repo)))
               (p/then (fn [_]
                         (is (= 1 @close-calls))
                         (is (= :closed @(:ws-state client)))
                         (is (empty? @(:inflight client)))
                         (is (= :timeout-id (:timer @(:reconnect client))))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-client prev-client)
                            (reset! worker-state/*db-sync-config prev-config)
                            (set! js/setTimeout original-set-timeout)
                            (done)))))))

(deftest repair-required-error-stops-reconnect-without-dropping-local-queue-test
  (let [prev-client @worker-state/*db-sync-client
        close-calls (atom 0)
        ws #js {:readyState 1
                :close (fn [] (swap! close-calls inc))}
        pending-id (random-uuid)
        client {:repo "repair-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :last-sync-error (atom nil)
                :last-ws-message-ts (atom (js/Date.now))
                :inflight (atom [pending-id])
                :online-users (atom [])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}
        error (ex-info "checksum mismatch"
                       {:type :db-sync/checksum-mismatch})]
    (reset! worker-state/*db-sync-client client)
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _] nil)]
      (try
        (#'sync/handle-runtime-sync-failure!
         "repair-repo"
         client
         ws
         "wss://sync.example.test/sync/graph-1"
         error
         :message-failed)
        (is (= 1 @close-calls))
        (is (= :repair-required @(:ws-state client)))
        (is (empty? @(:inflight client))
            "inflight transport state is cleared so pending storage can retry explicitly")
        (is (nil? (:timer @(:reconnect client))))
        (is (= :db-sync/checksum-mismatch
               (get-in @(:last-sync-error client) [:data :type])))
        (finally
          (reset! worker-state/*db-sync-client prev-client))))))

(deftest tx-reject-is-surfaced-without-reconnecting-a-healthy-socket-test
  (let [prev-client @worker-state/*db-sync-client
        close-calls (atom 0)
        ws #js {:readyState 1
                :close (fn [] (swap! close-calls inc))}
        client {:repo "reject-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :last-sync-error (atom nil)
                :inflight (atom [])
                :online-users (atom [])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}
        error (ex-info "transaction rejected"
                       {:type :db-sync/tx-rejected})]
    (reset! worker-state/*db-sync-client client)
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _] nil)]
      (try
        (#'sync/handle-runtime-sync-failure!
         "reject-repo"
         client
         ws
         "wss://sync.example.test/sync/graph-1"
         error
         :message-failed)
        (is (zero? @close-calls))
        (is (= :open @(:ws-state client)))
        (is (nil? (:timer @(:reconnect client))))
        (is (= :db-sync/tx-rejected
               (get-in @(:last-sync-error client) [:data :type])))
        (finally
          (reset! worker-state/*db-sync-client prev-client))))))

(deftest visibility-resume-does-not-loop-a-repair-required-client-test
  (async done
         (let [repo "repair-repo"
               graph-id "graph-1"
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               close-calls (atom 0)
               client {:repo repo
                       :graph-id graph-id
                       :ws #js {:readyState 3
                                :close (fn [] (swap! close-calls inc))}
                       :ws-state (atom :repair-required)
                       :last-sync-error (atom {:code :checksum-mismatch})
                       :inflight (atom [])
                       :online-users (atom [])
                       :reconnect (atom {:attempt 0 :timer nil})
                       :stale-kill-timer (atom nil)}]
           (reset! worker-state/*db-sync-config
                   {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client client)
           (-> (p/with-redefs [sync-util/get-graph-id (fn [_] graph-id)]
                 (sync/resume! repo))
               (p/then
                (fn [_]
                  (is (zero? @close-calls))
                  (is (= :repair-required @(:ws-state client)))
                  (is (nil? (:timer @(:reconnect client))))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-client prev-client)
                  (reset! worker-state/*db-sync-config prev-config)
                  (done)))))))

(deftest resume-replaces-a-pre-suspend-reconnect-timer-test
  (let [repo "suspended-backoff-repo"
        graph-id "graph-1"
        original-set-timeout js/setTimeout
        original-clear-timeout js/clearTimeout
        prev-client @worker-state/*db-sync-client
        prev-config @worker-state/*db-sync-config
        close-calls (atom 0)
        cleared-timers (atom [])
        scheduled-delays (atom [])
        ws #js {:readyState 3
                :close (fn [] (swap! close-calls inc))}
        client {:repo repo
                :graph-id graph-id
                :ws ws
                :ws-state (atom :closed)
                :last-ws-message-ts (atom 0)
                :inflight (atom [(random-uuid)])
                :online-users (atom [])
                :reconnect (atom {:attempt 7 :timer :pre-suspend-timeout})
                :stale-kill-timer (atom nil)}]
    (set! js/setTimeout
          (fn [_f delay]
            (swap! scheduled-delays conj delay)
            :resume-timeout))
    (set! js/clearTimeout
          (fn [timer]
            (swap! cleared-timers conj timer)))
    (reset! worker-state/*db-sync-config {:ws-url "wss://sync.example.test/sync/%s"})
    (reset! worker-state/*db-sync-client client)
    (with-redefs [sync-util/get-graph-id (fn [_] graph-id)
                  shared-service/broadcast-to-clients! (fn [& _] nil)]
      (try
        (sync/resume! repo)
        (is (= [:pre-suspend-timeout] @cleared-timers)
            "resume must not trust a timer that may have stopped during sleep")
        (is (= 1 @close-calls))
        (is (= 1 (count @scheduled-delays)))
        (is (<= 1000 (first @scheduled-delays) 1250)
            "resume should restart from the first reconnect delay")
        (is (= {:attempt 1
                :timer :resume-timeout
                :reason :system-resume}
               @(:reconnect client)))
        (finally
          (reset! worker-state/*db-sync-client prev-client)
          (reset! worker-state/*db-sync-config prev-config)
          (set! js/setTimeout original-set-timeout)
          (set! js/clearTimeout original-clear-timeout))))))

(deftest start-reconnects-closed-ws-with-stale-open-state-test
  (async done
         (let [repo "stale-repo"
               graph-id "graph-1"
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               prev-platform (try
                               (platform/current)
                               (catch :default _ nil))
               connect-calls (atom 0)
               stale-ws #js {:readyState 3
                              :close (fn [] nil)}
               stale-client {:repo repo
                             :graph-id graph-id
                             :ws stale-ws
                             :ws-state (atom :open)
                             :online-users (atom [])
                             :reconnect (atom {:attempt 0 :timer nil})
                             :stale-kill-timer (atom nil)}]
           (reset! worker-state/*db-sync-config {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client stale-client)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect (fn [_url]
                                    (swap! connect-calls inc)
                                    #js {:readyState 0
                                         :close (fn [] nil)})}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs [worker-state/get-client-ops-conn (fn [_repo] true)
                               client-op/get-local-tx (fn [_repo] 0)
                               client-op/get-pending-local-tx-count (fn [_repo] 0)
                               client-op/get-unpushed-asset-ops-count (fn [_repo] 0)
                               client-op/get-all-asset-ops (fn [_repo] [])
                               client-op/get-local-checksum (fn [_repo] nil)
                               client-op/get-graph-uuid (fn [_repo] graph-id)
                               client-op/update-graph-uuid (fn [_repo _graph-id] nil)
                               sync-util/get-graph-id (fn [_repo] graph-id)
                               sync/<resolve-ws-token (fn [] (p/resolved "token"))
                               shared-service/broadcast-to-clients! (fn [& _] nil)]
                 (sync/start! repo))
               (p/then
                (fn [_]
                  (is (= 1 @connect-calls)
                      "start! should reconnect when the cached websocket is already closed")))
               (p/catch
                (fn [error]
                  (is false (str "unexpected error: " error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-client prev-client)
                  (reset! worker-state/*db-sync-config prev-config)
                  (when prev-platform
                    (platform/set-platform! prev-platform))
                  (done)))))))

(deftest start-skips-when-client-op-local-tx-is-missing-test
  (async done
         (let [repo "missing-local-tx-repo"
               graph-id "graph-1"
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               prev-platform (try
                               (platform/current)
                               (catch :default _ nil))
               connect-calls (atom 0)]
           (reset! worker-state/*db-sync-config {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client nil)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect (fn [_url]
                                    (swap! connect-calls inc)
                                    #js {:readyState 0
                                         :close (fn [] nil)})}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs [worker-state/get-client-ops-conn (fn [_repo] true)
                               client-op/get-local-tx (fn [_repo] nil)
                               client-op/update-local-tx (fn [_repo _tx]
                                                           (throw (js/Error. "must not initialize missing local-tx to 0")))
                               sync-util/get-graph-id (fn [_repo] graph-id)]
                 (sync/start! repo))
               (p/then
                (fn [_]
                  (is (zero? @connect-calls)
                      "start! should wait for valid client-op local-tx instead of syncing from 0")))
               (p/catch
                (fn [error]
                  (is false (str "unexpected error: " error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-client prev-client)
                  (reset! worker-state/*db-sync-config prev-config)
                  (when prev-platform
                    (platform/set-platform! prev-platform))
                  (done)))))))

(deftest stale-loop-marks-non-open-ws-closed-test
  (let [broadcasts (atom [])
        clear-interval-calls (atom [])
        interval-f* (atom nil)
        original-clear-interval js/clearInterval
        original-set-interval js/setInterval
        prev-client @worker-state/*db-sync-client
        ws #js {:readyState 3
                :close (fn [] nil)}
        client {:repo "stale-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :inflight (atom [(random-uuid)])
                :online-users (atom [])
                :stale-kill-timer (atom nil)}]
    (set! js/setInterval
          (fn [f _ms]
            (reset! interval-f* f)
            :interval-id))
    (set! js/clearInterval
          (fn [interval-id]
            (swap! clear-interval-calls conj interval-id)))
    (reset! worker-state/*db-sync-client client)
    (with-redefs [shared-service/broadcast-to-clients!
                  (fn [topic payload]
                    (swap! broadcasts conj [topic payload]))]
      (try
        (#'sync/close-stale-ws-loop client ws "wss://sync.example.test/sync/graph-1")
        (@interval-f*)
        (is (= :closed @(:ws-state client)))
        (is (empty? @(:inflight client)))
        (is (= [:interval-id] @clear-interval-calls))
        (is (some #(= :rtc-sync-state (first %)) @broadcasts))
        (finally
          (reset! worker-state/*db-sync-client prev-client)
          (set! js/setInterval original-set-interval)
          (set! js/clearInterval original-clear-interval))))))

(deftest ws-close-clears-inflight-before-reconnect-test
  (let [broadcasts (atom [])
        original-set-timeout js/setTimeout
        prev-client @worker-state/*db-sync-client
        ws #js {:readyState 3
                :close (fn [] nil)}
        client {:repo "stale-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :inflight (atom [(random-uuid)])
                :online-users (atom [:user])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}]
    (set! js/setTimeout
          (fn [_f _ms]
            :timeout-id))
    (reset! worker-state/*db-sync-client client)
    (with-redefs [shared-service/broadcast-to-clients!
                  (fn [topic payload]
                    (swap! broadcasts conj [topic payload]))]
      (try
        (#'sync/attach-ws-handlers! "stale-repo" client ws "wss://sync.example.test/sync/graph-1")
        ((.-onclose ws) #js {})
        (is (= :closed @(:ws-state client)))
        (is (empty? @(:inflight client)))
        (is (empty? @(:online-users client)))
        (is (= :timeout-id (:timer @(:reconnect client))))
        (is (some #(= :rtc-sync-state (first %)) @broadcasts))
        (finally
          (reset! worker-state/*db-sync-client prev-client)
          (set! js/setTimeout original-set-timeout))))))

(deftest obsolete-websocket-close-does-not-restart-current-connection-test
  (let [original-set-timeout js/setTimeout
        prev-client @worker-state/*db-sync-client
        timeout-calls (atom 0)
        old-ws #js {:readyState 3 :close (fn [] nil)}
        current-ws #js {:readyState 1 :close (fn [] nil)}
        old-client {:repo "stale-repo"
                    :graph-id "graph-1"
                    :ws old-ws
                    :ws-state (atom :open)
                    :inflight (atom [])
                    :online-users (atom [])
                    :reconnect (atom {:attempt 0 :timer nil})
                    :stale-kill-timer (atom nil)}
        current-client (assoc old-client
                              :ws current-ws
                              :ws-state (atom :open)
                              :reconnect (atom {:attempt 0 :timer nil}))]
    (set! js/setTimeout
          (fn [& _]
            (swap! timeout-calls inc)
            :timeout-id))
    (reset! worker-state/*db-sync-client current-client)
    (try
      (#'sync/attach-ws-handlers! "stale-repo" old-client old-ws
                                  "wss://sync.example.test/sync/graph-1")
      ((.-onclose old-ws) #js {})
      (is (= :open @(:ws-state current-client)))
      (is (zero? @timeout-calls))
      (finally
        (reset! worker-state/*db-sync-client prev-client)
        (set! js/setTimeout original-set-timeout)))))

(deftest ws-error-without-close-invalidates-and-schedules-reconnect-test
  (let [original-set-timeout js/setTimeout
        prev-client @worker-state/*db-sync-client
        close-calls* (atom 0)
        ws #js {:readyState 1
                :close (fn [] (swap! close-calls* inc))}
        client {:repo "error-repo"
                :graph-id "graph-1"
                :ws ws
                :ws-state (atom :open)
                :inflight (atom [(random-uuid)])
                :online-users (atom [])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}]
    (set! js/setTimeout (fn [& _] :timeout-id))
    (reset! worker-state/*db-sync-client client)
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _] nil)]
      (try
        (#'sync/attach-ws-handlers!
         "error-repo" client ws "wss://sync.example.test/sync/graph-1")
        ((.-onerror ws) (js/Error. "transport failed"))
        (is (= 1 @close-calls*))
        (is (= :closed @(:ws-state client)))
        (is (empty? @(:inflight client)))
        (is (= :timeout-id (:timer @(:reconnect client))))
        (finally
          (reset! worker-state/*db-sync-client prev-client)
          (set! js/setTimeout original-set-timeout))))))

(deftest upload-response-timeout-invalidates-only-the-current-websocket-test
  (let [repo "upload-timeout-repo"
        graph-id "graph-1"
        url "wss://sync.example.test/sync/graph-1"
        original-set-interval js/setInterval
        original-clear-interval js/clearInterval
        original-set-timeout js/setTimeout
        prev-client @worker-state/*db-sync-client
        prev-platform (try
                        (platform/current)
                        (catch :default _ nil))
        close-calls (atom 0)
        ws #js {:readyState 1
                :send (fn [& _] nil)
                :close (fn [] (swap! close-calls inc))}
        client {:repo repo
                :graph-id graph-id
                :ws-state (atom :open)
                :last-ws-message-ts (atom (js/Date.now))
                :inflight (atom [(random-uuid)])
                :upload-request (atom nil)
                :online-users (atom [])
                :reconnect (atom {:attempt 0 :timer nil})
                :stale-kill-timer (atom nil)}]
    (set! js/setInterval (fn [& _] :interval-id))
    (set! js/clearInterval (fn [& _] nil))
    (set! js/setTimeout (fn [& _] :timeout-id))
    (platform/set-platform!
     {:env {:runtime :node}
      :storage {}
      :kv {}
      :broadcast {}
      :websocket {:connect (fn [_url] ws)}
      :crypto {}
      :timers {}
      :sqlite {}})
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _] nil)]
      (try
        (let [connected (#'sync/connect! repo client url "token")]
          (reset! worker-state/*db-sync-client connected)
          ((:upload-response-timeout-f connected) {:tx-ids [(random-uuid)]})
          (is (= 1 @close-calls))
          (is (= :closed @(:ws-state connected)))
          (is (empty? @(:inflight connected)))
          (is (= :timeout-id (:timer @(:reconnect connected))))
          (let [replacement-ws #js {:readyState 1 :close (fn [] nil)}
                replacement (assoc connected
                                   :ws replacement-ws
                                   :ws-state (atom :open)
                                   :inflight (atom [(random-uuid)])
                                   :reconnect (atom {:attempt 0 :timer nil}))]
            (reset! worker-state/*db-sync-client replacement)
            ((:upload-response-timeout-f connected) {:tx-ids [(random-uuid)]})
            (is (= 1 @close-calls)
                "a late timeout from an old socket must not close its replacement")
            (is (= :open @(:ws-state replacement)))
            (is (= 1 (count @(:inflight replacement))))))
        (finally
          (reset! worker-state/*db-sync-client prev-client)
          (when prev-platform
            (platform/set-platform! prev-platform))
          (set! js/setInterval original-set-interval)
          (set! js/clearInterval original-clear-interval)
          (set! js/setTimeout original-set-timeout))))))

(deftest reconnect-retries-token-failure-and-recovers-test
  (async done
         (let [repo "retry-repo"
               graph-id "graph-1"
               url "wss://sync.example.test/sync/graph-1"
               original-set-timeout js/setTimeout
               prev-client @worker-state/*db-sync-client
               prev-platform (try
                               (platform/current)
                               (catch :default _ nil))
               callbacks* (atom [])
               token-calls* (atom 0)
               sent* (atom [])
               new-ws #js {:readyState 0
                           :send (fn [payload] (swap! sent* conj payload))
                           :close (fn [] nil)}
               old-ws #js {:readyState 3
                           :close (fn [] nil)}
               client {:repo repo
                       :graph-id graph-id
                       :resolve-ws-token-f
                       (fn []
                         (if (= 1 (swap! token-calls* inc))
                           (p/resolved nil)
                           (p/resolved "token")))
                       :ws old-ws
                       :ws-state (atom :closed)
                       :last-ws-message-ts (atom 0)
                       :inflight (atom [(random-uuid)])
                       :online-users (atom [])
                       :reconnect (atom {:attempt 0 :timer nil})
                       :stale-kill-timer (atom nil)}]
           (set! js/setTimeout
                 (fn [f delay]
                   (swap! callbacks* conj {:f f :delay delay})
                   (keyword (str "timeout-" (count @callbacks*)))))
           (reset! worker-state/*db-sync-client client)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect (fn [_url] new-ws)}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs [shared-service/broadcast-to-clients!
                               (fn [& _] nil)]
                 (#'sync/schedule-reconnect! repo client url :test)
                 (p/let [_ ((:f (first @callbacks*)))
                         _ (is (= 2 (count @callbacks*)))
                         _ ((:f (second @callbacks*)))]
                   (is (= 2 @token-calls*))
                   (is (some? @worker-state/*db-sync-client))
                   (is (identical? new-ws (:ws @worker-state/*db-sync-client)))
                   (aset new-ws "readyState" 1)
                   ((.-onopen new-ws) #js {})
                   (is (= ["{\"type\":\"hello\",\"client\":\"retry-repo\"}"] @sent*))
                   (is (= :open @(:ws-state client)))
                   (is (= {:attempt 2 :timer nil} @(:reconnect client))
                       "opening the socket alone must not erase repeated catch-up failures")
                   ((:sync-succeeded-f @worker-state/*db-sync-client))
                   (is (= {:attempt 0 :timer nil} @(:reconnect client)))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-client prev-client)
                            (when prev-platform
                              (platform/set-platform! prev-platform))
                            (set! js/setTimeout original-set-timeout)
                            (done)))))))

(deftest reconnect-retries-websocket-construction-failure-test
  (async done
         (let [repo "proxy-retry-repo"
               graph-id "graph-1"
               url "wss://sync.example.test/sync/graph-1"
               original-set-timeout js/setTimeout
               prev-client @worker-state/*db-sync-client
               prev-platform (try
                               (platform/current)
                               (catch :default _ nil))
               callbacks* (atom [])
               connect-calls* (atom 0)
               replacement-ws #js {:readyState 0
                                   :send (fn [& _] nil)
                                   :close (fn [] nil)}
               client {:repo repo
                       :graph-id graph-id
                       :resolve-ws-token-f (fn [] (p/resolved "token"))
                       :ws #js {:readyState 3 :close (fn [] nil)}
                       :ws-state (atom :closed)
                       :last-ws-message-ts (atom 0)
                       :inflight (atom [])
                       :online-users (atom [])
                       :reconnect (atom {:attempt 0 :timer nil})
                       :stale-kill-timer (atom nil)}]
           (set! js/setTimeout
                 (fn [f delay]
                   (swap! callbacks* conj {:f f :delay delay})
                   (keyword (str "timeout-" (count @callbacks*)))))
           (reset! worker-state/*db-sync-client client)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect
                         (fn [_url]
                           (if (= 1 (swap! connect-calls* inc))
                             (throw (js/Error. "proxy is not ready"))
                             replacement-ws))}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs [shared-service/broadcast-to-clients!
                               (fn [& _] nil)]
                 (#'sync/schedule-reconnect! repo client url :test)
                 (p/let [_ ((:f (first @callbacks*)))
                         _ (is (= 2 (count @callbacks*))
                               "a synchronous proxy/WebSocket failure must retry")
                         _ ((:f (second @callbacks*)))]
                   (is (= 2 @connect-calls*))
                   (is (identical? replacement-ws
                                   (:ws @worker-state/*db-sync-client)))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-client prev-client)
                            (when prev-platform
                              (platform/set-platform! prev-platform))
                            (set! js/setTimeout original-set-timeout)
                            (done)))))))

(deftest resume-supersedes-a-reconnect-waiting-for-an-auth-token-test
  (async done
         (let [repo "resume-during-auth-repo"
               graph-id "graph-1"
               url "wss://sync.example.test/sync/graph-1"
               original-set-timeout js/setTimeout
               original-clear-timeout js/clearTimeout
               prev-client @worker-state/*db-sync-client
               prev-config @worker-state/*db-sync-config
               prev-platform (try
                               (platform/current)
                               (catch :default _ nil))
               callbacks* (atom [])
               first-token-resolve* (atom nil)
               token-calls* (atom 0)
               connect-calls* (atom 0)
               old-ws #js {:readyState 3
                           :close (fn [] nil)}
               replacement-ws #js {:readyState 0
                                   :send (fn [& _] nil)
                                   :close (fn [] nil)}
               first-token-promise
               (js/Promise.
                (fn [resolve _reject]
                  (reset! first-token-resolve* resolve)))
               client {:repo repo
                       :graph-id graph-id
                       :resolve-ws-token-f
                       (fn []
                         (if (= 1 (swap! token-calls* inc))
                           first-token-promise
                           (p/resolved "fresh-token")))
                       :ws old-ws
                       :ws-state (atom :closed)
                       :last-ws-message-ts (atom 0)
                       :inflight (atom [])
                       :online-users (atom [])
                       :reconnect (atom {:attempt 0 :timer nil})
                       :stale-kill-timer (atom nil)}]
           (set! js/setTimeout
                 (fn [f delay]
                   (swap! callbacks* conj {:f f :delay delay})
                   (keyword (str "timeout-" (count @callbacks*)))))
           (set! js/clearTimeout (fn [& _] nil))
           (reset! worker-state/*db-sync-config
                   {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client client)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect (fn [_url]
                                    (swap! connect-calls* inc)
                                    replacement-ws)}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs [sync-util/get-graph-id
                               (fn [_repo] graph-id)
                               shared-service/broadcast-to-clients! (fn [& _] nil)]
                 (#'sync/schedule-reconnect! repo client url :system-resume)
                 (let [old-attempt ((:f (first @callbacks*)))]
                   (sync/resume! repo)
                   (@first-token-resolve* "stale-token")
                   (p/let [_ old-attempt]
                     (is (zero? @connect-calls*)
                         "a token result from before resume must not install a websocket")
                     (is (= 2 (count @callbacks*))
                         "resume should own the only replacement reconnect attempt")
                     ((:f (second @callbacks*)))
                     (is (= 1 @connect-calls*))
                     (is (identical? replacement-ws
                                     (:ws @worker-state/*db-sync-client))))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-client prev-client)
                            (reset! worker-state/*db-sync-config prev-config)
                            (when prev-platform
                              (platform/set-platform! prev-platform))
                            (set! js/setTimeout original-set-timeout)
                            (set! js/clearTimeout original-clear-timeout)
                            (done)))))))
