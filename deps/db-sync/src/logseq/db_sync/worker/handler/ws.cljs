(ns logseq.db-sync.worker.handler.ws
  (:require [logseq.db-sync.index :as index]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.worker.auth :as auth]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.http :as http]
            [logseq.db-sync.worker.presence :as presence]
            [logseq.db-sync.worker.ws :as ws]
            [promesa.core :as p]))

(def ^:private connection-access-cache-ttl-ms 5000)

(defn- access-cache*
  [^js self]
  (or (.-connectionAccessCache self)
      (set! (.-connectionAccessCache self) (atom {}))))

(defn- <connection-authorized?
  [^js self ^js ws]
  (let [env (.-env self)
        user (presence/get-user self ws)
        user-id (:user-id user)
        graph-id (presence/get-graph-id self ws)
        db (some-> env (aget "DB"))
        cache-key [graph-id user-id]
        now (.now js/Date)
        cached (get @(access-cache* self) cache-key)]
    (cond
      ;; Unit-level protocol handlers historically use a bare in-memory self.
      ;; Every production Worker and Node graph context carries :env.
      (nil? env)
      true

      (and (number? (:checked-at cached))
           (< (- now (:checked-at cached))
              connection-access-cache-ttl-ms))
      (:allowed? cached)

      (and db (string? user-id) (string? graph-id))
      (p/let [allowed?
              (index/<user-has-access-to-graph? db graph-id user-id)]
        (swap! (access-cache* self)
               assoc
               cache-key
               {:allowed? (true? allowed?)
                :checked-at now})
        (true? allowed?))

      :else
      false)))

(defn handle-ws-message! [^js self ^js ws raw]
  (letfn [(handle-authorized-message! []
            (let [message (-> raw protocol/parse-message ws/coerce-ws-client-message)]
              (if-not (map? message)
                (ws/send! ws {:type "error" :message "invalid request"})
                (case (:type message)
        "hello"
        (ws/send! ws (merge {:type "hello"
                             :t (sync-handler/t-now self)}
                            (sync-handler/checksum-response-fields self)))

        "ping"
        (ws/send! ws {:type "pong"})

        "presence"
        (let [editing-block-uuid (:editing-block-uuid message)
              user (presence/get-user self ws)]
          (presence/update-presence! self ws {:editing-block-uuid editing-block-uuid})
          (ws/broadcast! self ws {:type "presence"
                                  :editing-block-uuid editing-block-uuid
                                  :user-id (:user-id user)}))

        "pull"
        (let [raw-since (:since message)
              since (if (some? raw-since) (sync-handler/parse-int raw-since) 0)]
          (if (or (and (some? raw-since) (not (number? since))) (neg? since))
            (ws/send! ws {:type "error" :message "invalid since"})
            (ws/send! ws (sync-handler/pull-response self since))))

        ;; "snapshot"
        ;; (send! ws (snapshot-response self))

        "tx/batch"
        (let [txs (:txs message)
              user (presence/get-user self ws)
              t-before (sync-handler/parse-int (:t-before message))]
          (if (sequential? txs)
            (ws/send! ws (sync-handler/handle-tx-batch!
                          self
                          ws
                          txs
                          t-before
                          (cond-> {:graph-id (presence/get-graph-id self ws)}
                            (:client-revision message)
                            (assoc :client-revision (:client-revision message))
                            (:username user)
                            (assoc :username (:username user)))))
            (ws/send! ws {:type "tx/reject" :reason "invalid tx"})))

                  (ws/send! ws {:type "error" :message "unknown type"})))))]
    (let [authorized? (<connection-authorized? self ws)
          continue! (fn [allowed?]
                      (if allowed?
                        (handle-authorized-message!)
                        (do
                          (presence/remove-presence! self ws)
                          (.close ws 1008 "graph access revoked")
                          (presence/broadcast-online-users! self))))]
      (if (p/promise? authorized?)
        (p/let [allowed? authorized?]
          (continue! allowed?))
        (continue! authorized?)))))

(defn handle-ws [^js self request]
  (let [graph-id (sync-handler/graph-id-from-request request)]
    (p/let [ready-for-sync? (sync-handler/<ready-for-sync? self graph-id)]
      (if-not ready-for-sync?
        (http/error-response "graph not ready" 409)
        (let [pair (js/WebSocketPair.)
              client (aget pair 0)
              server (aget pair 1)
              state (.-state self)]
          (aset self "graph-id" graph-id)
          (.acceptWebSocket state server)
          (let [token (auth/token-from-request request)
                claims (auth/unsafe-jwt-claims token)
                user (presence/claims->user claims)]
            (if user
              (presence/add-presence! self server user graph-id)
              (presence/set-connection-context! server nil graph-id))
            (presence/broadcast-online-users! self)
            (js/Response. nil #js {:status 101 :webSocket client})))))))
