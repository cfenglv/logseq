(ns frontend.worker.sync.restart-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.worker.platform :as platform]
            [frontend.worker.repair-commit-fence :as repair-commit-fence]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync :as sync]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.download :as sync-download]
            [frontend.worker.sync.util :as sync-util]
            [logseq.common.user-navigation :as navigation]
            [promesa.core :as p]))

(deftest private-first-offline-reuses-reconnect-and-stopped-init-cannot-install-test
  (async done
    (let [repo "logseq_db_user-navigation-test"
          user "synthetic-user"
          graph-id (navigation/graph-id user)
          token (str "header." (js/btoa (js/JSON.stringify #js {:sub user})) ".signature")
          previous-client @worker-state/*db-sync-client
          previous-config @worker-state/*db-sync-config
          previous-context @worker-state/*user-navigation-context
          previous-platform (try (platform/current) (catch :default _ nil))
          token-stage (atom :offline)
          init-response (atom nil)
          initialized (p/deferred)
          timeouts (atom [])
          sockets (atom [])
          notices (atom [])
          context {:repo repo :user-id user :http-base "https://sync.example.test"
                   :identity [repo "https://sync.example.test" user graph-id]
                   :candidates (atom {}) :denied-scopes (atom #{}) :initializing (atom nil)}]
      (reset! worker-state/*db-sync-client nil)
      (reset! worker-state/*user-navigation-context context)
      (reset! worker-state/*db-sync-config {:ws-url "wss://sync.example.test/sync/%s"})
      (platform/set-platform! {:env {:runtime :node} :storage {} :kv {} :broadcast {} :crypto {} :timers {} :sqlite {}
                               :websocket {:connect (fn [_]
                                                      (let [ws #js {:readyState 0 :close (fn [] nil)}]
                                                        (swap! sockets conj ws) ws))}})
      (-> (p/with-redefs [worker-state/get-client-ops-conn (constantly true)
                          client-op/get-local-tx (constantly 0)
                          client-op/get-pending-local-tx-count (constantly 0)
                          client-op/get-unpushed-asset-ops-count (constantly 0)
                          client-op/get-all-asset-ops (constantly [])
                          client-op/get-local-checksum (constantly nil)
                          client-op/update-graph-uuid (fn [& _])
                          sync-util/get-graph-id (constantly graph-id)
                          sync/<resolve-ws-token (fn []
                                                  (if (= :offline @token-stage)
                                                    (do (reset! token-stage :online) (p/rejected (js/Error. "synthetic offline")))
                                                    (p/resolved token)))
                          sync-util/fetch-json (fn [& _]
                                                 (if-let [pending @init-response]
                                                   (if (= :missing-route pending)
                                                     (p/rejected (ex-info "synthetic old server" {:status 404}))
                                                     (do (p/resolve! initialized true) pending))
                                                   (p/resolved {:graph-id graph-id :scopes []})))
                          shared-service/broadcast-to-clients! (fn [event payload]
                                                                 (when (= :notification event) (swap! notices conj payload)))
                          js/setTimeout (fn [f delay]
                                          (swap! timeouts conj {:f f :delay delay}) (count @timeouts))
                          js/clearTimeout (fn [_])]
            (p/do!
             (sync/start! repo)
             (is (some? @worker-state/*db-sync-client) "A private owner exists before a socket exists.")
             (is (= 1 (count @timeouts)))
             (is (empty? @sockets))
             (is (empty? @notices) "Normal offline startup stays quiet.")
             ((:f (first @timeouts)))
             (is (= 1 (count @sockets)) "The original reconnect callback runs init and opens the socket.")
             (sync/stop!)
             (let [pending-response (p/deferred)]
               (reset! init-response pending-response)
               (let [starting (sync/start! repo)]
                 (p/do!
                  initialized
                  (sync/stop!)
                  (p/resolve! pending-response {:graph-id graph-id :scopes []})
                  starting
                  (is (nil? @worker-state/*db-sync-client))
                  (is (= 1 (count @sockets))))))
             (reset! init-response :missing-route)
             (sync/start! repo)
             (is (= 1 (count @notices)))
             (is (= :sync.navigation/online-error (:i18n-key (last (first @notices)))))
             (is (nil? (:timer @(:reconnect @worker-state/*db-sync-client))) "Known missing route is not retried forever.")))
          (p/catch (fn [error] (is false (str error))))
          (p/finally (fn []
                       (-> (sync/stop!)
                           (p/finally (fn []
                                        (reset! worker-state/*db-sync-client previous-client)
                                        (reset! worker-state/*db-sync-config previous-config)
                                        (reset! worker-state/*user-navigation-context previous-context)
                                        (when previous-platform (platform/set-platform! previous-platform))
                                        (done))))))))))

(deftest start-releases-caller-fence-before-resumed-repair-commit-test
  (async done
         (let [repo "restart-repair-fence-repo"
               graph-id "graph-1"
               operation-id #uuid "99000000-0000-4000-8000-000000000001"
               previous-client @worker-state/*db-sync-client
               previous-config @worker-state/*db-sync-config
               previous-platform (try
                                   (platform/current)
                                   (catch :default _ nil))
               resume-finished (p/deferred)]
           (repair-commit-fence/reset-for-test!)
           (reset! worker-state/*db-sync-config
                   {:ws-url "wss://sync.example.test/sync/%s"})
           (reset! worker-state/*db-sync-client nil)
           (platform/set-platform!
            {:env {:runtime :node}
             :storage {}
             :kv {}
             :broadcast {}
             :websocket {:connect (fn [_url]
                                    #js {:readyState 0
                                         :close (fn [] nil)})}
             :crypto {}
             :timers {}
             :sqlite {}})
           (-> (p/with-redefs
                 [worker-state/get-client-ops-conn (fn [_repo] true)
                  client-op/get-local-tx (fn [_repo] 0)
                  client-op/get-pending-local-tx-count (fn [_repo] 0)
                  client-op/get-unpushed-asset-ops-count (fn [_repo] 0)
                  client-op/get-all-asset-ops (fn [_repo] [])
                  client-op/get-local-checksum (fn [_repo] nil)
                  client-op/get-graph-uuid (fn [_repo] graph-id)
                  client-op/update-graph-uuid (fn [_repo _graph-id] nil)
                  sync-util/get-graph-id (fn [_repo] graph-id)
                  sync/<resolve-ws-token (fn [] (p/resolved "token"))
                  sync-download/<resume-repair-operation!
                  (fn [_repo]
                    (-> (repair-commit-fence/<enter! repo operation-id)
                        (p/then
                         (fn [owner-token]
                           (repair-commit-fence/release! repo owner-token)
                           (p/resolve! resume-finished true)
                           {:resumed? true}))))
                  shared-service/broadcast-to-clients! (fn [& _] nil)]
                 (let [start-call
                       (repair-commit-fence/<with-repo-call!
                        repo #(sync/start! repo))
                       start-settled? (atom false)]
                   (p/then start-call #(reset! start-settled? true))
                   (p/let [_ (p/delay 25)]
                     (is @start-settled?
                         "db-sync start must release its caller fence before resumed repair enters the commit fence")
                     (when @start-settled?
                       resume-finished))))
               (p/catch
                (fn [error]
                  (is false (str "unexpected error: " error))))
               (p/finally
                (fn []
                  (repair-commit-fence/reset-for-test!)
                  (reset! worker-state/*db-sync-client previous-client)
                  (reset! worker-state/*db-sync-config previous-config)
                  (when previous-platform
                    (platform/set-platform! previous-platform))
                  (done)))))))

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
                               sync-download/<resume-repair-operation!
                               (fn [_repo] (p/resolved {:resumed? false}))
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
