(ns logseq.db-sync.node.server
  (:require ["http" :as http]
            ["path" :as node-path]
            ["ws" :as ws]
            [clojure.string :as string]
            [lambdaisland.glogi :as log]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.logging :as logging]
            [logseq.db-sync.node.assets :as assets]
            [logseq.db-sync.node.config :as config]
            [logseq.db-sync.node.dispatch :as dispatch]
            [logseq.db-sync.node.graph :as graph]
            [logseq.db-sync.node.routes :as node-routes]
            [logseq.db-sync.node.storage :as storage]
            [logseq.db-sync.platform.core :as platform]
            [logseq.db-sync.platform.node :as platform-node]
            [logseq.db-sync.worker.auth :as auth]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.handler.ws :as ws-handler]
            [logseq.db-sync.worker.http :as worker-http]
            [logseq.db-sync.worker.presence :as presence]
            [promesa.core :as p]))

(logging/install!)

(defn- make-env [cfg index-db assets-bucket]
  (let [allow-unverified-jwt-claims (some-> js/process .-env (aget "DB_SYNC_ALLOW_UNVERIFIED_JWT_CLAIMS"))
        env (doto (js-obj)
              (aset "DB" index-db)
              (aset "LOGSEQ_SYNC_ASSETS" assets-bucket)
              ;; Node adapter serves snapshot transit stream without gzip to avoid
              ;; browser/adapter content-encoding mismatches during graph download.
              (aset "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false")
              (aset "COGNITO_ISSUER" (:cognito-issuer cfg))
              (aset "COGNITO_CLIENT_ID" (:cognito-client-id cfg))
              (aset "COGNITO_JWKS_URL" (:cognito-jwks-url cfg)))]
    (when (some? allow-unverified-jwt-claims)
      (aset env "DB_SYNC_ALLOW_UNVERIFIED_JWT_CLAIMS" allow-unverified-jwt-claims))
    env))

(defn- request-origin-opts
  [cfg]
  (if-let [base-url (:base-url cfg)]
    (let [url (js/URL. base-url)
          protocol (.-protocol url)
          scheme (if (string/ends-with? protocol ":")
                   (subs protocol 0 (dec (count protocol)))
                   protocol)]
      {:scheme scheme
       :host (.-host url)})
    {:scheme "http"}))

(defn- <access-context
  [env graph-id request]
  (p/let [claims (auth/auth-claims request env)
          user-id (when claims (aget claims "sub"))
          db (aget env "DB")]
    {:claims claims
     :allowed? (if (string? user-id)
                 (index/<user-has-access-to-graph? db graph-id user-id)
                 false)}))

(defn- attach-ws! [^js ctx ^js socket]
  (let [state (.-state ctx)]
    (when-let [add-ws (.-addWebSocket state)]
      (add-ws socket))
    (set! (.-serializeAttachment socket) (fn [_] nil))
    (set! (.-deserializeAttachment socket) (fn [] nil))))

(defn- detach-ws! [^js ctx ^js socket]
  (let [state (.-state ctx)]
    (when-let [remove-ws (.-removeWebSocket state)]
      (remove-ws socket))))

(defn- reject-ws-upgrade!
  [^js socket status reason]
  (.write socket
          (str "HTTP/1.1 " status " Conflict\r\n"
               "Connection: close\r\n"
               "Content-Type: application/json\r\n\r\n"
               "{\"error\":\"" reason "\"}"))
  (.destroy socket))

(defn- handle-ws-connection
  [^js ctx graph-id claims ^js socket]
  (let [user (presence/claims->user claims)]
    (when user
      (presence/add-presence! ctx socket user graph-id))
    (presence/broadcast-online-users! ctx))
  (.on socket "message"
       (fn [data]
         (if (true? (.-deleting ctx))
           (when (= 1 (.-readyState socket))
             (.close socket 1001 "graph deleted"))
           (let [text (if (string? data) data (.toString data))]
             (->
              (try
                (p/resolved (ws-handler/handle-ws-message! ctx socket text))
                (catch :default e
                  (p/rejected e)))
              (p/catch
               (fn [e]
                 (log/error :db-sync/ws-error (common/error-log-data e))
                 (when (= 1 (.-readyState socket))
                   (.send socket (js/JSON.stringify #js {:type "error" :message "server error"})))
                 (.close socket 1011 "server error"))))))))
  (.on socket "close"
       (fn []
         (presence/remove-presence! ctx socket)
         (presence/broadcast-online-users! ctx)
         (detach-ws! ctx socket)))
  (.on socket "error"
       (fn [error]
         (presence/remove-presence! ctx socket)
         (presence/broadcast-online-users! ctx)
         (detach-ws! ctx socket)
         (log/error :db-sync/ws-error (common/error-log-data error)))))

(defn start!
  [overrides]
  (let [cfg (config/normalize-config overrides)
        request-origin (request-origin-opts cfg)
        index-db (storage/open-index-db (:data-dir cfg))
        assets-bucket (assets/make-bucket (node-path/join (:data-dir cfg) "assets"))
        registry (atom {})
        deps {:config cfg
              :index-db index-db
              :assets-bucket assets-bucket}
        env (doto (make-env cfg index-db assets-bucket)
              (aset "DB_SYNC_DELETE_GRAPH"
                    (fn [graph-id]
                      (graph/delete-graph! registry deps graph-id)))
              (aset "DB_SYNC_REVOKE_GRAPH_USER"
                    (fn [graph-id user-id]
                      (when-let [ctx (get @registry graph-id)]
                        (presence/revoke-user! ctx user-id)))))
        server (.createServer http
                              (fn [req res]
                                (-> (p/let [request (platform-node/request-from-node req request-origin)
                                            response (dispatch/handle-node-fetch {:request request
                                                                                  :env env
                                                                                  :registry registry
                                                                                  :deps deps})]
                                      (platform-node/send-response! res response))
                                    (p/catch
                                     (fn [e]
                                       (log/error :db-sync/node-request-failed
                                                  (common/error-log-data e))
                                       (platform-node/send-response! res (worker-http/error-response "server error" 500)))))))
        WSS (or (.-WebSocketServer ws) (.-Server ws))
        ^js wss (new WSS #js {:noServer true})]
    (.on server "error" (fn [error]
                           (log/error :db-sync/node-server-error
                                      (common/error-log-data error))))
    (.on wss "error" (fn [error]
                        (log/error :db-sync/node-ws-error
                                   (common/error-log-data error))))
    (p/let [_ (index/<index-init! index-db)]
      (.on server "upgrade"
           (fn [req ^js socket head]
             (let [request (platform-node/request-from-node req request-origin)
                   url (platform/request-url request)
                   path (.-pathname url)
                   parsed (node-routes/parse-sync-path path)
                   graph-id (:graph-id parsed)]
               (if (and graph-id (seq graph-id))
                 (->
                  (p/let [{:keys [allowed? claims]}
                          (<access-context env graph-id request)]
                    (if allowed?
                      (let [ctx (graph/get-or-create-graph registry deps graph-id)]
                        (aset ctx "graph-id" graph-id)
                        (p/let [ready-for-sync? (sync-handler/<ready-for-sync? ctx graph-id)]
                          (if ready-for-sync?
                            (.handleUpgrade wss req socket head
                                            (fn [ws-socket]
                                              (attach-ws! ctx ws-socket)
                                              (handle-ws-connection
                                               ctx graph-id claims ws-socket)))
                            (reject-ws-upgrade! socket 409 "graph not ready"))))
                      (.destroy socket)))
                  (p/catch
                   (fn [error]
                     (log/error :db-sync/node-upgrade-failed
                                (common/error-log-data error))
                     (when-not (.-destroyed socket)
                       (.destroy socket)))))
                 (.destroy socket)))))
      (p/let [_ (js/Promise.
                 (fn [resolve reject]
                   (letfn [(on-listen-error [error]
                             (.removeListener server "error"
                                              on-listen-error)
                             ;; start! does not return a stop handle when
                             ;; listen fails, so release storage opened before
                             ;; binding the port here.
                             (try
                               (graph/close-graphs! registry)
                               (catch :default _ nil))
                             (try
                               (when-let [close (.-close index-db)]
                                 (close))
                               (catch :default _ nil))
                             (reject error))]
                     (.once server "error" on-listen-error)
                     (.listen server (:port cfg)
                              (fn []
                                (.removeListener server "error"
                                                 on-listen-error)
                                (resolve nil))))))
              address (.address server)
              port (if (number? address) address (.-port address))
              base-url (or (:base-url cfg) (str "http://localhost:" port))]
        {:server server
         :wss wss
         :env env
         :registry registry
         :port port
         :base-url base-url
         :stop! (fn []
                  ;; Stop accepting upgrades and terminate upgraded sockets
                  ;; before closing graph/index storage used by their handlers.
                  (.removeAllListeners server "upgrade")
                  (doseq [^js client (js/Array.from (.-clients wss))]
                    (try
                      (.terminate client)
                      (catch :default _ nil)))
                  ;; A noServer WebSocketServer can report "not running" from
                  ;; close() without invoking its callback. It owns no listener,
                  ;; so terminate its clients and let the HTTP server closure be
                  ;; the lifecycle barrier.
                  (try
                    (.close wss)
                    (catch :default _ nil))
                  (when-let [close-all-connections
                             (.-closeAllConnections server)]
                    (.call close-all-connections server))
                  (-> (if (.-listening server)
                        (p/create
                         (fn [resolve _reject]
                           (try
                             (.close server (fn [& _] (resolve nil)))
                             (catch :default _ (resolve nil)))))
                        (p/resolved nil))
                      (p/finally
                       (fn []
                         (graph/close-graphs! registry)
                         (when-let [close (.-close index-db)]
                           (close))))))}))))
