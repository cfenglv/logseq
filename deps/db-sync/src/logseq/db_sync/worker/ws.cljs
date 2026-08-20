(ns logseq.db-sync.worker.ws
  (:require [lambdaisland.glogi :as log]
            [logseq.db-sync.malli-schema :as db-sync-schema]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.worker.coerce :as coerce]))

(defn ws-open? [ws]
  (= 1 (.-readyState ws)))

(defn coerce-ws-client-message [message]
  (when message
    (let [coerced (coerce/coerce db-sync-schema/ws-client-message-coercer message {:schema :ws/client})]
      (when-not (= coerced coerce/invalid-coerce)
        coerced))))

(defn coerce-ws-server-message [message]
  (when message
    (let [coerced (coerce/coerce db-sync-schema/ws-server-message-coercer message {:schema :ws/server})]
      (when-not (= coerced coerce/invalid-coerce)
        coerced))))

(defn send! [ws msg]
  (when (ws-open? ws)
    (if-let [coerced (coerce-ws-server-message msg)]
      (.send ws (protocol/encode-message coerced))
      (do
        (log/error :db-sync/ws-response-invalid
                   {:message-type (when (map? msg) (:type msg))})
        (.send ws (protocol/encode-message {:type "error" :message "server error"}))))))

(defn broadcast! [^js self sender msg]
  (when-let [state (some-> self .-state)]
    (when (fn? (.-getWebSockets state))
      (let [clients (.getWebSockets state)]
        (doseq [ws clients]
          (when (and (not= ws sender) (ws-open? ws))
            (try
              (send! ws msg)
              (catch :default error
                ;; A stale peer must never turn an already committed graph
                ;; transaction into an HTTP/WebSocket failure or prevent
                ;; healthy peers from receiving the notification.
                (log/warn :db-sync/ws-broadcast-send-failed
                          {:message-type (when (map? msg) (:type msg))
                           :ready-state (.-readyState ws)
                           :error-name (or (some-> error .-name) "Error")})
                (try
                  (when (fn? (.-close ws))
                    (.close ws 1011 "send failed"))
                  (catch :default _ nil))))))))))
