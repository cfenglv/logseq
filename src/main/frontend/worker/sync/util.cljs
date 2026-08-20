(ns frontend.worker.sync.util
  "Helpers for sync"
  (:require [clojure.string :as string]
            [lambdaisland.glogi :as log]
            [frontend.worker.platform :as platform]
            [frontend.worker.state :as worker-state]
            [logseq.db :as ldb]
            [frontend.worker.sync.client-op :as client-op]
            [logseq.common.util :as common-util]
            [logseq.common.version :as build-version]
            [logseq.db-sync.malli-schema :as db-sync-schema]
            [promesa.core :as p]))

(def ^:private diagnostic-data-keys
  [:type :code :error :status :field :message-type :stage :operation
   :local-tx :remote-tx :expected-count :actual-count :payload-bytes
   :response-error-code :timeout-ms])

(def ^:private default-http-timeout-ms 60000)

(defn- safe-diagnostic-value?
  [value]
  (or (keyword? value)
      (string? value)
      (number? value)
      (boolean? value)))

(defn- diagnostic-data
  [data]
  (->> (select-keys (or data {}) diagnostic-data-keys)
       (filter (comp safe-diagnostic-value? val))
       (into {})))

(defn fail-fast [tag data]
  (log/error tag (diagnostic-data data))
  (throw (ex-info (name tag) data)))

(defn cli-node-owner?
  []
  (try
    (let [env (:env (platform/current))]
      (and (= :node (:runtime env))
           (= :cli (:owner-source env))))
    (catch :default _ false)))

(defn auth-token
  []
  (let [state @worker-state/*state]
    (or (:auth/id-token state)
        (:auth/access-token state))))

(defn get-graph-id
  [repo]
  (or (when-let [conn (worker-state/get-datascript-conn repo)]
        (let [db @conn
              graph-uuid (ldb/get-graph-rtc-uuid db)]
          (when graph-uuid
            (str graph-uuid))))
      (some-> (client-op/get-graph-uuid repo) str)))

(defn require-auth-token!
  [context]
  (when-not (seq (auth-token))
    (fail-fast :db-sync/missing-field (assoc context :field :auth-token))))

(defn- ex-message->code
  [message]
  (when (and (string? message)
             (re-matches #"[a-zA-Z0-9._/\-]+" message))
    (keyword message)))

(defn error->diagnostic
  [error]
  (let [data (or (ex-data error) {})
        raw-code (or (:code data)
                     (ex-message->code (ex-message error))
                     (:type data)
                     (:error data))
        code (if (safe-diagnostic-value? raw-code)
               raw-code
               :exception)
        safe-data (diagnostic-data data)]
    {:code code
     ;; Never retain arbitrary exception messages or ex-data in state shown by
     ;; the RTC indicator. They can contain transaction or response payloads.
     :message (if (keyword? code) (name code) (str code))
     :at (common-util/time-ms)
     :data (when (seq safe-data) safe-data)}))

(defn set-last-sync-error!
  [client error]
  (when-let [*last-error (:last-sync-error client)]
    (reset! *last-error (error->diagnostic error))))

(defn clear-last-sync-error!
  [client]
  (when-let [*last-error (:last-sync-error client)]
    (reset! *last-error nil)))

(defn transient-sync-error?
  [error]
  (contains? #{:db-sync/http-request-failed
               :db-sync/http-timeout
               :db-sync/recovery-timeout
               :db-sync/e2ee-key-unavailable}
             (:type (ex-data error))))

(defn with-timeout
  [promise timeout-ms {:keys [type code] :as context}]
  (if-not (and (number? timeout-ms) (pos? timeout-ms))
    promise
    (p/create
     (fn [resolve reject]
       (let [settled? (atom false)
             settle! (fn [f value]
                       (when (compare-and-set! settled? false true)
                         (f value)))
             timeout-id
             (js/setTimeout
              (fn []
                (settle!
                 reject
                 (ex-info "db-sync operation timeout"
                          (merge {:type (or type :db-sync/http-timeout)
                                  :code (or code :timeout)
                                  :timeout-ms timeout-ms}
                                 context))))
              timeout-ms)]
         (->
          promise
          (p/then #(settle! resolve %))
          (p/catch #(settle! reject %))
          (p/finally #(js/clearTimeout timeout-id))))))))

(def ^:private invalid-coerce ::invalid-coerce)

(defn coerce
  [coercer value context]
  (try
    (coercer value)
    (catch :default e
      (log/error :db-sync/malli-coerce-failed
                 (merge context
                        {:error-name (or (.-name e) "Error")
                         :message-type (when (map? value) (:type value))}))
      invalid-coerce)))

(defn- with-client-revision
  [schema-key body]
  (cond-> body
    (and (= :sync/tx-batch schema-key)
         (map? body)
         (not (contains? body :client-revision)))
    (assoc :client-revision (build-version/revision))))

(defn coerce-http-request [schema-key body]
  (if-let [coercer (get db-sync-schema/http-request-coercers schema-key)]
    (let [coerced (coerce coercer (with-client-revision schema-key body) {:schema schema-key :dir :request})]
      (when-not (= coerced invalid-coerce)
        coerced))
    body))

(defn coerce-http-response [schema-key body]
  (if-let [coercer (get db-sync-schema/http-response-coercers schema-key)]
    (let [coerced (coerce coercer body {:schema schema-key :dir :response})]
      (when-not (= coerced invalid-coerce)
        coerced))
    body))

(defn- auth-headers []
  (let [token (auth-token)]
    (when (nil? token)
      (throw (ex-info "Empty token" {})))
    {"authorization" (str "Bearer " token)}))

(defn- with-auth-headers [opts]
  (if-let [auth (auth-headers)]
    (assoc opts :headers (merge (or (:headers opts) {}) auth))
    opts))

(defn fetch
  [url opts]
  (let [opts (or opts {})
        timeout-ms (or (:timeout-ms opts) default-http-timeout-ms)
        operation (or (:operation opts) :db-sync/http-request)
        supplied-signal (:signal opts)
        controller (when-not supplied-signal (js/AbortController.))
        timed-out? (atom false)
        timeout-id
        (when (and controller (number? timeout-ms) (pos? timeout-ms))
          (js/setTimeout
           (fn []
             (reset! timed-out? true)
             (.abort controller))
           timeout-ms))
        request-opts
        (cond-> (dissoc opts :timeout-ms :operation)
          controller (assoc :signal (.-signal controller)))
        request
        (try
          (js/fetch url (clj->js request-opts))
          (catch :default error
            (p/rejected error)))]
    (->
     request
     (p/catch
      (fn [error]
        (throw
         (ex-info
          (if @timed-out?
            "db-sync http request timeout"
            "db-sync http request failed")
          {:type (if @timed-out?
                   :db-sync/http-timeout
                   :db-sync/http-request-failed)
           :code (if @timed-out? :http-timeout :http-request-failed)
           :operation operation
           :timeout-ms timeout-ms}
          error))))
     (p/finally
      (fn []
        (when timeout-id
          (js/clearTimeout timeout-id)))))))

(defn fetch-json
  [url opts {:keys [response-schema error-schema] :or {error-schema :error}}]
  (p/let [resp (fetch url (with-auth-headers opts))
          text (with-timeout
                (.text resp)
                (or (:timeout-ms opts) default-http-timeout-ms)
                {:type :db-sync/http-timeout
                 :code :http-body-timeout
                 :operation (or (:operation opts)
                                :db-sync/http-response-body)
                 :stage :response-body})
          data (when (seq text) (js/JSON.parse text))]
    (if (.-ok resp)
      (let [body (js->clj data :keywordize-keys true)
            body (if response-schema
                   (coerce-http-response response-schema body)
                   body)]
        (if (or (nil? response-schema) body)
          body
          (throw (ex-info "db-sync invalid response"
                          {:status (.-status resp)
                           :url url
                           :response-error-code
                           (some-> (:error body)
                                   str
                                   string/lower-case
                                   (string/replace #"\s+" "-")
                                   ex-message->code)}))))
      (let [body (when data (js->clj data :keywordize-keys true))
            body (if error-schema
                   (coerce-http-response error-schema body)
                   body)]
        (throw (ex-info "db-sync request failed"
                        {:status (.-status resp)
                         :url url
                         :response-error-code
                         (some-> (:error body)
                                 str
                                 string/lower-case
                                 (string/replace #"\s+" "-")
                                 ex-message->code)}))))))
