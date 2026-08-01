(ns frontend.worker.sync
  "Sync client"
  (:require
   [frontend.worker.platform :as platform]
   [frontend.worker.shared-service :as shared-service]
   [frontend.worker.state :as worker-state]
   [frontend.worker.sync.apply-txs :as sync-apply]
   [frontend.worker.sync.assets :as sync-assets]
   [frontend.worker.sync.auth :as sync-auth]
   [frontend.worker.sync.client-op :as client-op]
   [frontend.worker.sync.handle-message :as sync-handle-message]
   [frontend.worker.sync.presence :as sync-presence]
   [frontend.worker.sync.transport :as sync-transport]
   [frontend.worker.sync.upload :as sync-upload]
   [frontend.worker.sync.util :as sync-util]
   [lambdaisland.glogi :as log]
   [logseq.common.util :as common-util]
   [logseq.db-sync.checksum :as sync-checksum]
   [promesa.core :as p]
   [logseq.common.config :as common-config]))

(def ^:private reconnect-base-delay-ms 1000)
(def ^:private reconnect-max-delay-ms 30000)
(def ^:private reconnect-jitter-ms 250)
(def ^:private ws-heartbeat-interval-ms 30000)
(def ^:private ws-stale-timeout-ms 90000)
(def ^:private repair-required-error-types
  #{:db-sync/checksum-mismatch
    :db-sync/pull-history-gap
    :db-sync/remote-apply-failed
    :db-sync/server-cursor-regressed})
(def fail-fast sync-util/fail-fast)

(defonce *repo->latest-remote-tx sync-apply/*repo->latest-remote-tx)
(defonce *repo->latest-remote-checksum sync-apply/*repo->latest-remote-checksum)
(defonce *repo->latest-remote-checksum-version
  sync-apply/*repo->latest-remote-checksum-version)
(defonce *start-inflight (atom nil))

(defn- current-client
  [repo]
  (sync-presence/current-client worker-state/*db-sync-client repo))

(defn status
  [repo]
  (sync-presence/sync-counts
   {:get-datascript-conn worker-state/get-datascript-conn
    :get-client-ops-conn worker-state/get-client-ops-conn
    :get-pending-local-tx-count client-op/get-pending-local-tx-count
    :get-unpushed-asset-ops-count client-op/get-unpushed-asset-ops-count
    :get-missing-asset-upload-files sync-assets/get-missing-asset-upload-files
    :get-local-tx client-op/get-local-tx
    :get-local-checksum client-op/get-local-checksum
    :get-local-server-checksum client-op/get-local-server-checksum
    :get-graph-uuid client-op/get-graph-uuid
    :latest-remote-tx @*repo->latest-remote-tx
    :latest-remote-checksum @*repo->latest-remote-checksum
    :latest-remote-checksum-version @*repo->latest-remote-checksum-version}
   repo))

(defn update-local-sync-checksum!
  [repo tx-report]
  (when (worker-state/get-client-ops-conn repo)
    (let [current-checksum (client-op/get-local-checksum repo)
          current-server-checksum
          (or (client-op/get-local-server-checksum repo)
              (sync-checksum/recompute-server-checksum (:db-before tx-report)))
          new-checksum (sync-checksum/update-checksum current-checksum tx-report)
          new-server-checksum
          (sync-checksum/update-server-checksum current-server-checksum tx-report)]
      (when (and (exists? js/process)
                 (= "1" (aget (.-env js/process) "LOGSEQ_CHECKSUM_ASSERT")))
        (let [recomputed-checksum (sync-checksum/recompute-checksum (:db-after tx-report))
              recomputed-server-checksum
              (sync-checksum/recompute-server-checksum (:db-after tx-report))]
          (when-not (and (= new-checksum recomputed-checksum)
                         (= new-server-checksum recomputed-server-checksum))
            (let [{:keys [tx-meta tx-data]} tx-report]
              (log/error :db-sync/checksum-incremental-drift
                         {:repo repo
                          :current-checksum current-checksum
                          :incremental-checksum new-checksum
                          :recomputed-checksum recomputed-checksum
                          :current-server-checksum current-server-checksum
                          :incremental-server-checksum new-server-checksum
                          :recomputed-server-checksum recomputed-server-checksum
                          :tx-meta tx-meta
                          :tx-count (count tx-data)
                          :tx-sample (take 30 tx-data)})
              (throw (ex-info "Incremental checksum drift"
                              {:repo repo
                               :current-checksum current-checksum
                               :incremental-checksum new-checksum
                               :recomputed-checksum recomputed-checksum
                               :current-server-checksum current-server-checksum
                               :incremental-server-checksum new-server-checksum
                               :recomputed-server-checksum recomputed-server-checksum
                               :tx-meta tx-meta
                               :tx-count (count tx-data)}))))))
      (client-op/update-local-checksum repo new-checksum)
      (client-op/update-local-server-checksum repo new-server-checksum))))

(defn- broadcast-rtc-state!
  [client]
  (when client
    (shared-service/broadcast-to-clients!
     :rtc-sync-state
     (sync-presence/rtc-state-payload status client))))

(defn- set-sync-state!
  [client ws-state ready?]
  (when-let [*ws-state (:ws-state client)]
    (reset! *ws-state ws-state))
  (when-let [*sync-ready? (:sync-ready? client)]
    (reset! *sync-ready? (true? ready?)))
  (broadcast-rtc-state! client))

(defn- update-online-users!
  [client users]
  (sync-presence/update-online-users! broadcast-rtc-state! client users))

(defn- clear-inflight!
  [client]
  (when-let [*inflight (:inflight client)]
    (reset! *inflight [])))

(defn- ws-base-url
  []
  (sync-auth/ws-base-url @worker-state/*db-sync-config))

(def auth-token sync-util/auth-token)

(def id-token-expired? sync-auth/id-token-expired?)

(def <resolve-ws-token sync-auth/<resolve-ws-token)

(defn- ensure-client-graph-uuid!
  [repo graph-id]
  (when (seq graph-id)
    (client-op/update-graph-uuid repo graph-id)))

(defn- client-op-ready?
  [repo]
  (and (some? (worker-state/get-client-ops-conn repo))
       (integer? (client-op/get-local-tx repo))))

(defn- reconnect-delay-ms
  [attempt]
  (sync-transport/reconnect-delay-ms
   attempt
   {:base-delay-ms reconnect-base-delay-ms
    :max-delay-ms reconnect-max-delay-ms
    :jitter-ms reconnect-jitter-ms}))

(defn- clear-reconnect-timer!
  [reconnect]
  (when-let [timer (:timer @reconnect)]
    (js/clearTimeout timer))
  (swap! reconnect
         (fn [state]
           (-> state
               (assoc :timer nil)
               (dissoc :reason)))))

(defn- reset-reconnect!
  [client]
  (when-let [reconnect (:reconnect client)]
    (clear-reconnect-timer! reconnect)
    (swap! reconnect assoc :attempt 0)))

(defn- clear-stale-ws-loop-timer!
  [client]
  (when-let [*timer (:stale-kill-timer client)]
    (when-let [timer @*timer]
      (js/clearInterval timer)
      (reset! *timer nil))))

(defn- touch-last-ws-message!
  [client]
  (when-let [*ts (:last-ws-message-ts client)]
    (reset! *ts (common-util/time-ms))))

(defn- stale-connection?
  [client]
  (when-let [last-ts (some-> (:last-ws-message-ts client) deref)]
    (>= (- (common-util/time-ms) last-ts) ws-stale-timeout-ms)))

(defn- ready-state
  [ws]
  (sync-transport/ready-state ws))

(defn- ws-open?
  [ws]
  (sync-transport/ws-open? ws))

(defn- send!
  [ws message]
  (sync-transport/send! sync-transport/coerce-ws-client-message ws message))

(declare invalidate-connection!)

(defn- enqueue-receive-message!
  [client task]
  (if-let [queue (:receive-queue client)]
    (swap! queue
           (fn [prev]
             (-> (or prev (p/resolved nil))
                 ;; Keep queue alive even if one message handler fails.
                 (p/catch (fn [_] nil))
                 (p/then
                  (fn [_]
                    (let [current @worker-state/*db-sync-client
                          generation (:connection-generation client)]
                      (when (or (nil? generation)
                                (and current
                                     (= (:repo client) (:repo current))
                                     (= generation
                                        (:connection-generation current))))
                        (task)))))
                 (p/catch
                  (fn [error]
                    (if-let [message-failed-f (:message-failed-f client)]
                      (message-failed-f error)
                      (sync-util/set-last-sync-error! client error))
                    (log/error :db-sync/ws-handle-message-failed
                               {:repo (:repo client)
                                :diagnostic
                                (dissoc
                                 (sync-util/error->diagnostic error)
                                 :at)}))))))
    (task)))

(defn update-presence!
  [editing-block-uuid]
  (when-let [client @worker-state/*db-sync-client]
    (when-let [ws (:ws client)]
      (send! ws {:type "presence"
                 :editing-block-uuid editing-block-uuid}))))

(defn- enqueue-asset-task!
  [client task]
  (sync-assets/enqueue-asset-task! client task))

(defn- ensure-client-state!
  [repo]
  {:repo repo
   :send-queue (atom (p/resolved nil))
   :flush-scheduler (atom {:active? false
                           :follow-up? false
                           :stopped? false})
   :receive-queue (atom (p/resolved nil))
   :asset-queue (atom (p/resolved nil))
   :pending-pull-since (atom nil)
   :inflight (atom [])
   :upload-request (atom nil)
   :last-sync-error (atom nil)
   :reconnect (atom {:attempt 0 :timer nil})
   :stale-kill-timer (atom nil)
   :last-ws-message-ts (atom (common-util/time-ms))
   :connection-generation (str (random-uuid))
   :online-users (atom [])
   :sync-ready? (atom false)
   :ws-state (atom :closed)})

(declare connect! detach-ws-handlers! start!)

(defn- schedule-reconnect!
  [repo client url reason]
  (when-let [reconnect (:reconnect client)]
    (let [{:keys [attempt timer]} @reconnect]
      (when (nil? timer)
        (let [delay (reconnect-delay-ms attempt)
              *timeout-id (atom nil)
              timeout-id
              (js/setTimeout
               (fn []
                 (let [attempt-timeout-id @*timeout-id
                       current-attempt?
                       (fn []
                         (let [current @worker-state/*db-sync-client]
                           (and current
                                (= (:repo current) repo)
                                (= (:graph-id current) (:graph-id client))
                                (identical? reconnect (:reconnect current))
                                (identical? attempt-timeout-id
                                            (:timer @reconnect)))))]
                   (when (current-attempt?)
                     ;; The timer has fired. Keep its opaque id as the
                     ;; generation token while auth is pending, but no longer
                     ;; describe it as a scheduled resume timer. A later
                     ;; resume/network event may then supersede a hung auth
                     ;; request.
                     (swap! reconnect dissoc :reason)
                     (log/info :db-sync/ws-reconnect
                               {:repo repo
                                :db-sync-client-exists?
                                (some? @worker-state/*db-sync-client)})
                     (->
                      (p/let [token ((or (:resolve-ws-token-f client)
                                        <resolve-ws-token))]
                        ;; Resolving auth can outlive a suspend/resume or another
                        ;; network transition. Never let an obsolete attempt
                        ;; install a second WebSocket over the current client.
                        (when (current-attempt?)
                          (let [current @worker-state/*db-sync-client
                                updated (connect! repo current url token)]
                            (reset! worker-state/*db-sync-client updated))))
                      (p/catch
                       (fn [error]
                         (when (current-attempt?)
                           (clear-reconnect-timer! reconnect)
                           (log/error :db-sync/ws-reconnect-failed
                                      {:repo repo :error error})
                           (schedule-reconnect!
                            repo
                            @worker-state/*db-sync-client
                            url
                            :connect-failed))))))))
               delay)]
          (reset! *timeout-id timeout-id)
          (swap! reconnect assoc
                 :timer timeout-id
                 :attempt (inc attempt)
                 :reason reason)
          (log/info :db-sync/ws-reconnect-scheduled
                    {:repo repo :delay delay :attempt attempt :reason reason}))))))

(defn- invalidate-connection!
  [repo client ws url reason close-ws?]
  (clear-stale-ws-loop-timer! client)
  (sync-apply/clear-upload-response-timeout! client)
  (clear-inflight! client)
  (when-let [pending-pull-since (:pending-pull-since client)]
    (reset! pending-pull-since nil))
  (update-online-users! client [])
  (set-sync-state! client :closed false)
  (when ws
    (detach-ws-handlers! ws)
    (when close-ws?
      (try (.close ws) (catch :default _ nil))))
  (schedule-reconnect! repo client url reason))

(defn- error-type
  [error]
  (or (:type (ex-data error))
      (:code (ex-data error))))

(defn- repair-required-error?
  [error]
  (contains? repair-required-error-types (error-type error)))

(defn- enter-repair-required!
  [repo client ws error]
  ;; Protocol/data inconsistencies cannot be healed by reconnecting to the
  ;; same cursor. Stop the reconnect loop, retain every pending local op, and
  ;; let the user explicitly retry after inspecting or repairing the graph.
  (clear-stale-ws-loop-timer! client)
  (sync-apply/clear-upload-response-timeout! client)
  (clear-inflight! client)
  (when-let [reconnect (:reconnect client)]
    (clear-reconnect-timer! reconnect))
  (when-let [pending-pull-since (:pending-pull-since client)]
    (reset! pending-pull-since nil))
  (sync-util/set-last-sync-error! client error)
  (update-online-users! client [])
  (set-sync-state! client :repair-required false)
  (when ws
    (detach-ws-handlers! ws)
    (try (.close ws) (catch :default _ nil)))
  (log/error :db-sync/repair-required
             {:repo repo
              :error-type (error-type error)}))

(defn- handle-runtime-sync-failure!
  [repo client ws url error source]
  (sync-util/set-last-sync-error! client error)
  (cond
    (repair-required-error? error)
    (enter-repair-required! repo client ws error)

    (= :db-sync/tx-rejected (error-type error))
    ;; The reject handler has already rolled back/marked the failed entry and
    ;; scheduled remaining work. Keep the healthy socket and surface the
    ;; rejected operation without creating a reconnect storm.
    (do
      (broadcast-rtc-state! client)
      (log/warn :db-sync/transaction-rejected-without-reconnect
                {:repo repo
                 :source source}))

    :else
    (do
      (log/warn :db-sync/runtime-failure-reconnect
                {:repo repo
                 :source source
                 :error-type (error-type error)})
      (invalidate-connection! repo client ws url source true))))

(defn- attach-ws-handlers!
  [repo client ws url]
  (set! (.-onmessage ws)
        (fn [event]
          (when (identical? ws (:ws @worker-state/*db-sync-client))
            (touch-last-ws-message! client)
            (enqueue-receive-message! client
                                      (fn []
                                        (sync-handle-message/handle-message! repo client (.-data event)))))))
  (set! (.-onerror ws)
        (fn [error]
          (log/error :db-sync/ws-error error)
          (when (identical? ws (:ws @worker-state/*db-sync-client))
            (sync-util/set-last-sync-error!
             client
             (ex-info "db-sync/websocket-error"
                      {:type :db-sync/websocket-error}))
            (invalidate-connection! repo client ws url :error true))))
  (set! (.-onclose ws)
        (fn [_]
          (when (identical? ws (:ws @worker-state/*db-sync-client))
            (log/info :db-sync/ws-closed {:repo repo})
            (when (or (seq (some-> (:inflight client) deref))
                      (some? (some-> (:upload-request client) deref)))
              (sync-util/set-last-sync-error!
               client
               (ex-info "db-sync/upload-connection-closed"
                        {:type :db-sync/upload-connection-closed})))
            (invalidate-connection! repo client ws url :close false)))))

(defn- detach-ws-handlers!
  [ws]
  (set! (.-onopen ws) nil)
  (set! (.-onmessage ws) nil)
  (set! (.-onerror ws) nil)
  (set! (.-onclose ws) nil))

(defn- close-stale-ws-loop
  [client ws url]
  (let [repo (:repo client)
        graph-id (:graph-id client)]
    (clear-stale-ws-loop-timer! client)
    (when-let [*timer (:stale-kill-timer client)]
      (let [timer (js/setInterval
                   (fn []
                     (when-let [current @worker-state/*db-sync-client]
                       (when (and (= repo (:repo current))
                                  (= graph-id (:graph-id current))
                                  (identical? ws (:ws current)))
                         (cond
                           (ws-open? ws)
                           (let [now (common-util/time-ms)
                                 last-ts (or (some-> (:last-ws-message-ts current) deref) now)
                                 stale-ms (- now last-ts)]
                             (if (>= stale-ms ws-stale-timeout-ms)
                               (do
                                 (log/warn :db-sync/ws-stale-timeout {:repo repo :stale-ms stale-ms})
                                 (invalidate-connection! repo current ws url :heartbeat-timeout true))
                               (try
                                 (send! ws {:type "ping"})
                                 (catch :default error
                                   (log/warn :db-sync/ws-heartbeat-send-failed
                                             {:repo repo :error error})
                                   (invalidate-connection! repo current ws url :heartbeat-send-failed true)))))

                           (contains? #{2 3} (ready-state ws))
                           (do
                             (log/warn :db-sync/ws-stale-closed {:repo repo :ready-state (ready-state ws)})
                             (invalidate-connection! repo current ws url :stale-closed false))))))
                   ws-heartbeat-interval-ms)]
        (reset! *timer timer))))
  client)

(defn- stop-client!
  [client]
  (clear-stale-ws-loop-timer! client)
  (sync-apply/clear-upload-response-timeout! client)
  (when-let [scheduler (:flush-scheduler client)]
    (reset! scheduler {:active? false
                       :follow-up? false
                       :stopped? true}))
  (when-let [reconnect (:reconnect client)]
    (clear-reconnect-timer! reconnect))
  (when-let [ws (:ws client)]
    (detach-ws-handlers! ws)
    (update-online-users! client [])
    (set-sync-state! client :closed false)
    (try (.close ws) (catch :default _ nil))))

(defn- active-client-for?
  [client repo graph-id]
  (when (and client (= repo (:repo client)) (= graph-id (:graph-id client)))
    (let [ws (:ws client)
          ws-ready-state (when ws (ready-state ws))]
      (or (= 0 ws-ready-state)
          (and (= 1 ws-ready-state)
               (not (stale-connection? client)))))))

(defn resume!
  [repo]
  (let [client @worker-state/*db-sync-client
        graph-id (sync-util/get-graph-id repo)
        reconnect (:reconnect client)
        reconnect-state (some-> reconnect deref)
        resume-reconnect-scheduled?
        (and (some? (:timer reconnect-state))
             (= :system-resume (:reason reconnect-state)))]
    (cond
      (and client
           (= repo (:repo client))
           (= graph-id (:graph-id client))
           (= :repair-required (some-> client :ws-state deref)))
      ;; Visibility/network resume events must not turn a deterministic data
      ;; inconsistency into an infinite reconnect loop. The visible Start sync
      ;; action still calls start! and creates a fresh client explicitly.
      (p/resolved nil)

      (and client
           (= repo (:repo client))
           (= graph-id (:graph-id client))
           resume-reconnect-scheduled?)
      (p/resolved nil)

      (and client
           (= repo (:repo client))
           (= graph-id (:graph-id client)))
      (let [base (ws-base-url)
            url (sync-transport/format-ws-url base graph-id)]
        (log/info :db-sync/resume-reconnect {:repo repo})
        (reset-reconnect! client)
        (invalidate-connection! repo client (:ws client) url :system-resume true)
        (p/resolved nil))

      :else
      (start! repo))))

(defn- connect!
  [repo client url token]
  (let [token' (or token (auth-token))]
    (log/info :db-sync/connect! {:repo repo
                                 :token-exists? (some? token')})
    (when-not (and (string? token') (seq token'))
      (fail-fast :db-sync/missing-field {:repo repo :field :ws-token}))
    ;; Resolve auth and construct the replacement socket before tearing down
    ;; the current client state. Token, proxy, DNS, or TLS setup failures must
    ;; leave the reconnect generation recoverable.
    (let [ws (platform/websocket-connect
              (platform/current)
              (sync-transport/append-token url token'))
          _ (when (:ws client)
              (stop-client! client))
          updated (assoc client
                         :ws ws
                         :connection-generation (str (random-uuid))
                         :send-queue (atom (p/resolved nil))
                         :flush-scheduler (atom {:active? false
                                                 :follow-up? false
                                                 :stopped? false})
                         :receive-queue (atom (p/resolved nil))
                         :asset-queue (atom (p/resolved nil))
                         :upload-response-timeout-f
                         (fn [_request]
                           (when-let [current @worker-state/*db-sync-client]
                             (when (and (= repo (:repo current))
                                        (= (:graph-id client) (:graph-id current))
                                        (identical? ws (:ws current)))
                               (log/warn :db-sync/upload-response-timeout-reconnect
                                         {:repo repo})
                               (invalidate-connection!
                                repo current ws url :upload-response-timeout true))))
                         :upload-send-failed-f
                         (fn [_error]
                           (when-let [current @worker-state/*db-sync-client]
                             (when (and (= repo (:repo current))
                                        (= (:graph-id client) (:graph-id current))
                                        (identical? ws (:ws current)))
                               (log/warn :db-sync/upload-send-failed-reconnect
                                         {:repo repo})
                               (invalidate-connection!
                                repo current ws url :upload-send-failed true))))
                         :pull-failed-f
                         (fn [error]
                           (when-let [current @worker-state/*db-sync-client]
                             (when (and (= repo (:repo current))
                                        (= (:graph-id client) (:graph-id current))
                                        (identical? ws (:ws current)))
                               (handle-runtime-sync-failure!
                                repo current ws url error :pull-failed))))
                         :message-failed-f
                         (fn [error]
                           (when-let [current @worker-state/*db-sync-client]
                             (when (and (= repo (:repo current))
                                        (= (:graph-id client) (:graph-id current))
                                        (identical? ws (:ws current)))
                               (handle-runtime-sync-failure!
                                repo current ws url error :message-failed))))
                         :transport-recovered-f
                         (fn []
                           (when-let [current @worker-state/*db-sync-client]
                             (when (and (= repo (:repo current))
                                        (= (:graph-id client) (:graph-id current))
                                        (identical? ws (:ws current)))
                               ;; A verified hello proves that this transport
                               ;; generation recovered even when durable local
                               ;; work still needs to upload. Do not let an old
                               ;; failure streak keep later reconnects pinned at
                               ;; the maximum backoff.
                               (reset-reconnect! current))))
                         :sync-succeeded-f
                         (fn []
                           (when-let [current @worker-state/*db-sync-client]
                             (when (and (= repo (:repo current))
                                        (= (:graph-id client) (:graph-id current))
                                        (identical? ws (:ws current)))
                               ;; Full convergence also implies transport
                               ;; recovery; keep this idempotent reset for
                               ;; direct success paths and older callers.
                               (reset-reconnect! current)
                               (sync-util/clear-last-sync-error! current)
                               (set-sync-state! current :open true)))))]
      (attach-ws-handlers! repo updated ws url)
      (set! (.-onopen ws)
            (fn [_]
              ;; Opening a socket is not enough to prove that catch-up worked.
              ;; Keep the attempt count until hello/pull has completed.
              (when-let [reconnect (:reconnect updated)]
                (clear-reconnect-timer! reconnect))
              (touch-last-ws-message! updated)
              (set-sync-state! updated :syncing false)
              (send! ws {:type "hello" :client repo})
              (sync-assets/enqueue-asset-sync!
               repo updated
               {:enqueue-asset-task-f enqueue-asset-task!
                :current-client-f current-client
                :broadcast-rtc-state!-f broadcast-rtc-state!
                :fail-fast-f fail-fast})))
      (close-stale-ws-loop updated ws url))))

(defn stop!
  []
  (let [client @worker-state/*db-sync-client
        repo (or (:repo client) (worker-state/get-current-repo))]
    ;; Lazy asset restoration may start before the WS client exists. Stopping
    ;; or switching the current graph must still abort those pre-connect HTTP
    ;; requests and discard their queue state.
    (when repo
      (sync-assets/cancel-remote-asset-downloads! repo))
    (when client
      (stop-client! client)
      (reset! worker-state/*db-sync-client nil)))
  (p/resolved nil))

(declare list-remote-graphs!)

(defn- <resolve-start-graph-id
  [repo]
  (if-let [graph-id (sync-util/get-graph-id repo)]
    (p/resolved graph-id)
    (let [target-graph-name (some-> repo common-config/strip-leading-db-version-prefix)]
      (if-not (seq target-graph-name)
        (p/resolved nil)
        (p/let [remote-graphs (list-remote-graphs!)
                remote-graph-id (some (fn [{:keys [graph-name graph-id]}]
                                        (when (= target-graph-name graph-name)
                                          graph-id))
                                      remote-graphs)]
          (when (seq remote-graph-id)
            (ensure-client-graph-uuid! repo remote-graph-id)
            remote-graph-id))))))

(defn start!
  [repo]
  (let [base (ws-base-url)
        initial-graph-id (sync-util/get-graph-id repo)]
    (if-not (and (string? base) (seq base))
      (do
        (log/info :db-sync/start-skipped
                  {:repo repo :graph-id initial-graph-id :base base})
        (p/resolved nil))
      (p/let [graph-id (<resolve-start-graph-id repo)]
        (let [target [repo graph-id]
              inflight @*start-inflight
              current @worker-state/*db-sync-client]
          (cond
            (not (seq graph-id))
            (do
              (log/info :db-sync/start-skipped
                        {:repo repo :graph-id graph-id :base base})
              (p/resolved nil))

            (not (client-op-ready? repo))
            (do
              (log/info :db-sync/start-skipped
                        {:repo repo
                         :graph-id graph-id
                         :base base
                         :reason :client-op-not-ready})
              (p/resolved nil))

            (= target (:target inflight))
            (:promise inflight)

            inflight
            (-> (:promise inflight)
                (p/catch (fn [_] nil))
                (p/then (fn [_] (start! repo))))

            (active-client-for? current repo graph-id)
            (do
              (broadcast-rtc-state! current)
              (sync-apply/enqueue-flush-pending! repo current)
              (p/resolved nil))

            :else
            (let [start-promise
                  (->
                   (p/resolved nil)
                   (p/then
                    (fn []
                      (p/do!
                       (stop!)
                       (p/let [client (ensure-client-state! repo)
                               url (sync-transport/format-ws-url base graph-id)
                               _ (ensure-client-graph-uuid! repo graph-id)
                               connected (assoc client :graph-id graph-id)
                               token (<resolve-ws-token)
                               connected (connect! repo connected url token)]
                         (reset! worker-state/*db-sync-client connected)
                         nil)))))]
              (reset! *start-inflight
                      {:target target
                       :promise start-promise})
              (-> start-promise
                  (p/finally
                   (fn []
                     (when (= start-promise
                              (:promise @*start-inflight))
                       (reset! *start-inflight nil))))))))))))

(defn enqueue-local-tx!
  [repo tx-report]
  (sync-apply/enqueue-local-tx! repo tx-report))

(defn handle-local-tx!
  [repo tx-report]
  (sync-apply/handle-local-tx! repo tx-report))

(defn request-asset-download!
  [repo asset-uuid]
  (sync-apply/request-asset-download! repo asset-uuid))

(defn download-missing-assets!
  [repo graph-id]
  (sync-assets/download-missing-remote-assets! repo graph-id))

(defn retry-asset-upload!
  [repo]
  (when-let [client (current-client repo)]
    (sync-assets/enqueue-asset-sync!
     repo client
     {:enqueue-asset-task-f enqueue-asset-task!
      :current-client-f current-client
      :broadcast-rtc-state!-f broadcast-rtc-state!
      :fail-fast-f fail-fast}))
  (p/resolved nil))

(defn rehydrate-large-titles-from-db!
  [repo graph-id]
  (sync-apply/rehydrate-large-titles-from-db! repo graph-id))

(defn upload-graph!
  [repo]
  (sync-upload/upload-graph! repo))

(defn create-remote-graph!
  [repo opts]
  (sync-upload/create-remote-graph! repo opts))

(def list-remote-graphs! sync-upload/list-remote-graphs!)

(defn stop-upload!
  [repo]
  (sync-apply/set-upload-stopped! repo true))

(defn resume-upload!
  [repo]
  (sync-apply/set-upload-stopped! repo false))

(defn upload-stopped?
  [repo]
  (sync-apply/upload-stopped? repo))
