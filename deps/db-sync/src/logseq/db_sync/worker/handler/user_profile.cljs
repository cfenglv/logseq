(ns logseq.db-sync.worker.handler.user-profile
  (:require [clojure.string :as string]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.malli-schema :as schema]
            [malli.core :as ma]
            [promesa.core :as p]))

(def request-max-bytes (* 192 1024))
(def avatar-max-bytes (* 128 1024))

(defn- input-error! []
  (throw (ex-info "profile-invalid-input" {:status 400})))

(defn- <read-body [^js request]
  (if-let [body (.-body request)]
    (let [^js reader (.getReader body)
          payload (js/Uint8Array. request-max-bytes)]
      (-> (p/loop [size 0]
            (p/let [^js chunk (.read reader)]
              (if (.-done chunk)
                (try
                  (let [text (.decode (js/TextDecoder. "utf-8" #js {:fatal true})
                                      (.subarray payload 0 size))]
                    (js->clj (js/JSON.parse text) :keywordize-keys true))
                  (catch :default _ (input-error!)))
                (let [value (.-value chunk)
                      next-size (+ size (.-byteLength value))]
                  (if (> next-size request-max-bytes)
                    (p/let [_ (.cancel reader)]
                      (throw (ex-info "profile-request-too-large" {:status 413})))
                    (do
                      (.set payload value size)
                      (p/recur next-size)))))))
          (p/finally #(.releaseLock reader))))
    (input-error!)))

(defn- valid-avatar? [value]
  (or (nil? value)
      (and (re-matches #"(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?" value)
           (try
             (let [raw (js/atob value)
                   size (count raw)]
               (and (<= 33 size avatar-max-bytes)
                    (= value (js/btoa raw))
                    (= [137 80 78 71 13 10 26 10]
                       (mapv #(.charCodeAt raw %) (range 8)))
                    (let [payload (js/Uint8Array.from raw #(.charCodeAt % 0))
                          view (js/DataView. (.-buffer payload))]
                      (and (= 13 (.getUint32 view 8))
                           (= "IHDR" (subs raw 12 16))
                           (= 128 (.getUint32 view 16))
                           (= 128 (.getUint32 view 20))))))
             (catch :default _ false)))))

(defn- <save [db user-id request]
  (p/let [body (<read-body request)
          _ (when-not (ma/validate schema/user-profile-save-request-schema body)
              (input-error!))
          body ((get schema/http-request-coercers :user-profile/save) body)
          body (update body :display-name #(when (some? %) (string/trim %)))
          _ (when-not (valid-avatar? (:avatar-png-base64 body)) (input-error!))
          profile (index/<user-profile-save! db user-id body)]
    (if profile
      {:schema :user-profile/save :profile profile}
      (throw (ex-info "profile-conflict" {:status 409})))))

(defn- private-response [data status]
  (let [^js response (common/json-response data status)]
    (.set (.-headers response) "cache-control" "private, no-store")
    response))

(defn handle
  "Called after the shared index handler has verified the request's identity."
  [{:keys [db claims request route]}]
  (-> (p/let [user-id (some-> claims (aget "sub"))
              _ (when-not (string? user-id)
                  (throw (ex-info "unauthorized" {:status 401})))
              result (case (:handler route)
                       :user-profile/get
                       (p/let [profile (index/<user-profile db user-id)]
                         {:schema :user-profile/get :profile profile})

                       :user-profile/save
                       (<save db user-id request)

                       :graph-members/avatar
                       (let [{:keys [graph-id member-id]} (:path-params route)]
                         (p/let [profile (index/<graph-member-profile db graph-id user-id member-id)]
                           (if profile
                             {:schema :graph-members/avatar :profile profile}
                             (throw (ex-info "forbidden" {:status 403}))))))
              profile ((get schema/http-response-coercers (:schema result)) (:profile result))]
        (private-response profile 200))
      (p/catch (fn [error]
                 (if-let [status (:status (ex-data error))]
                   (private-response {:error (ex-message error)} status)
                   ;; Never serialize database exceptions or image bodies to a response or log.
                   (private-response {:error "profile-server-error"} 500))))))
