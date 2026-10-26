(ns frontend.diagnostics
  "Small, on-demand diagnostic records. Never captures graph contents or request data."
  (:require [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.version :as version]
            [logseq.common.version :as build-version]
            [promesa.core :as p]))

(defn error-record
  [error & [component-stack]]
  (let [data (ex-data error)]
    (cond-> {:source :renderer
             :at (js/Date.now)
             :message (or (ex-message error)
                          (when (instance? js/Error error) (.-message error))
                          (some-> error str))}
      (instance? js/Error error) (assoc :name (.-name error) :stack (.-stack error))
      (or (:code data) (:type data)) (assoc :code (or (:code data) (:type data)))
      component-stack (assoc :component-stack component-stack))))

(defn redact-text
  [text private-values]
  (when (some? text)
    (-> (reduce (fn [value private-value]
                  (if (and (string? private-value) (seq private-value))
                    (string/replace value private-value "<private>")
                    value))
                (str text) (sort-by count > (filter string? private-values)))
        (string/replace #"(?i)Bearer\s+[^\s\"']+" "Bearer <redacted>")
        (string/replace #"(?i)((?:access_token|id_token|refresh_token|password|secret|token)[\"']?\s*[:=]\s*[\"']?)[^\s\"',}]+"
                        "$1<redacted>"))))

(defn report
  [error snapshot context]
  (let [private-values (conj (:private-values context) (config/get-custom-sync-server-url))
        safe-error (reduce-kv (fn [result k value]
                               (assoc result k (if (or (string? value) (keyword? value))
                                                 (redact-text value private-values) value)))
                             {} (select-keys error [:source :at :name :code :message :stack :component-stack]))
        sync (select-keys (:sync snapshot)
                          [:ws-state :pending-local :pending-asset :pending-server :local-tx :remote-tx
                           :local-checksum :remote-checksum])
        repair (select-keys (:repair snapshot) [:state :at :error-type])]
    {:version version/version
     :revision (build-version/revision)
     :platform (:platform context) :arch (:arch context)
     :error safe-error
     :status (select-keys snapshot [:status :observed-at])
     :sync sync :repair repair}))

(defn repair-state
  [{:keys [operation prepared-swap? committed completed?]}]
  (cond
    completed? :complete
    (= :local-only (:disposition operation)) :local-only
    prepared-swap? :switching
    (and operation (number? (:projection-epoch committed))
         (= (:projection-epoch committed) (:target-projection-epoch operation))) :verifying
    (:next-retry-at-ms operation) :waiting
    operation :repairing
    :else :idle))

(defn repair-summary
  [status]
  (let [operation (:operation status)]
    (cond-> {:state (repair-state status) :at (or (:at status) (js/Date.now))}
      (or operation (:operation-id status))
      (assoc :operation-id (or (:operation-id operation) (:operation-id status)))
      (get-in operation [:last-error :type]) (assoc :error-type (get-in operation [:last-error :type])))))

(defn <read-status
  [repo]
  (let [requested-at (js/Date.now)]
    (-> (p/all [(state/<invoke-db-worker :thread-api/db-sync-status repo)
              (state/<invoke-db-worker :thread-api/db-sync-repair-status repo)])
      (p/timeout 5000)
      (p/then (fn [[sync repair]]
                {:status (if sync :ready :unavailable) :observed-at (js/Date.now)
                 :requested-at requested-at :sync sync :repair (repair-summary repair)})))))

(defn <context
  []
  (if (util/electron?)
    (ipc/ipc :diagnostics/context)
    (p/resolved {})))

(defn <open-log!
  [source]
  (ipc/ipc :diagnostics/open-log source))

(defn <copy-report!
  [error snapshot]
  (p/let [context (<context)
          text (js/JSON.stringify (clj->js (report error snapshot context)) nil 2)
          copied? (util/copy-to-clipboard! text)]
    (when-not (true? copied?)
      (throw (ex-info "Clipboard write failed" {:code :diagnostics/clipboard-write-failed})))
    true))

(defn record-renderer-error!
  [{:keys [level message exception time logger-name]}]
  (when (and (contains? #{:error :severe} level) (exists? js/window.apis))
    (let [error (or exception (when (map? message) (or (:exception message) (:error message))))
          record (if error (error-record error)
                   {:source :renderer :at time
                    :message (cond (string? message) message
                                   (map? message) (str (keys message))
                                   :else (str message))})]
      (-> (ipc/ipc :diagnostics/record-error (assoc record :logger logger-name))
          ;; Logging failure must not recursively enter the same error handler.
          (p/catch (fn [_] (js/console.warn "Could not save renderer error to the application log")))))))
