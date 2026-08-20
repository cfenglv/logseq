(ns logseq.db-sync.worker
  ;; Turn off false defclass errors
  {:clj-kondo/config {:linters {:unresolved-symbol {:level :off}}}}
  (:require ["cloudflare:workers" :refer [DurableObject]]
            [lambdaisland.glogi :as log]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.logging :as logging]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.sentry.worker :as sentry]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.worker.dispatch :as dispatch]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.handler.ws :as ws-handler]
            [logseq.db-sync.worker.presence :as presence]
            [logseq.db-sync.worker.ws :as ws]
            [promesa.core :as p]
            [shadow.cljs.modern :refer (defclass)]))

(logging/install!)

(def worker
  (sentry/wrap-handler
   #js {:fetch (fn [request env _ctx]
                 (dispatch/handle-worker-fetch request env))}))

(defclass SyncDO
  (extends DurableObject)

  (constructor [this ^js state env]
               (super state env)
               (set! (.-state this) state)
               (set! (.-env this) env)
               (let [durable-storage (.-storage state)
                     sql (.-sql ^js durable-storage)
                     transaction-sync (.-transactionSync durable-storage)]
                 (storage/register-transaction-sync!
                  sql
                  (when (fn? transaction-sync)
                    (fn [f]
                      (.call transaction-sync durable-storage f))))
                 (set! (.-sql this) sql))
               (set! (.-conn this) nil)
               (set! (.-schema-ready this) false)
               (let [presence (presence/presence* this)
                     sockets (.getWebSockets state)]
                 (doseq [^js ws sockets]
                   (when-let [attachment (.deserializeAttachment ws)]
                     (when-let [graph-id (presence/attachment->graph-id attachment)]
                       (aset this "graph-id" graph-id))
                     (when-let [user (presence/attachment->user attachment)]
                       (swap! presence assoc ws user))))
                 (.setWebSocketAutoResponse
                  state
                  (js/WebSocketRequestResponsePair.
                   (protocol/encode-message {:type "ping"})
                   (protocol/encode-message {:type "pong"})))))

  Object
  (fetch [this request]
         (->
          (p/do
            (if (common/upgrade-request? request)
              (ws-handler/handle-ws this request)
              (sync-handler/handle-http this request)))
          (p/catch (fn [error]
                     (sentry/capture-exception! error)
                     (log/error :db-sync/http-error
                                (common/error-log-data error))
                     (common/json-response {:error "server error"} 500)))))
  (webSocketMessage [this ws message]
                    (->
                     (ws-handler/handle-ws-message! this ws message)
                     (p/catch
                     (fn [e]
                        (sentry/capture-exception! e)
                        (log/error :db-sync/ws-error
                                   (common/error-log-data e))
                        (ws/send! ws {:type "error" :message "server error"})
                        (.close ws 1011 "server error")))))
  (webSocketClose [this ws _code _reason]
                  (presence/remove-presence! this ws)
                  (presence/broadcast-online-users! this))
  (webSocketError [this ws error]
                  (presence/remove-presence! this ws)
                  (presence/broadcast-online-users! this)
                  (sentry/capture-exception! error)
                  (log/error :db-sync/ws-error
                             (common/error-log-data error))))
