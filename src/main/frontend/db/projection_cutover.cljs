(ns frontend.db.projection-cutover
  "Main-thread projection cutover orchestration over official owners.

  The Worker remains the only durable owner. Each window only advances the
  existing db.subs store and invokes the existing graph lifecycle hook."
  (:require [frontend.db.subs :as db-subs]
            [frontend.flows :as flows]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

(defonce ^:private *probe (atom nil))
(defonce ^:private *installed? (atom false))

(defn apply-committed!
  [{:keys [repo projection-epoch] :as payload}]
  (when-not (and (= #{:repo :projection-epoch} (set (keys payload)))
                 (string? repo)
                 (nat-int? projection-epoch))
    (throw (ex-info "Invalid projection commit notification"
                    {:payload payload})))
  (when (and (= repo (state/get-current-repo))
             (db-subs/advance-projection! repo projection-epoch))
    ;; hook-plugin-app is the existing graph lifecycle owner and normally
    ;; catches plugin exceptions itself. Keep the cutover boundary safe when a
    ;; test or alternate host supplies a throwing implementation.
    (try
      (plugin-handler/hook-plugin-app
       :current-graph-changed
       {:projection-epoch projection-epoch})
      (catch :default error
        (log/error :db/projection-plugin-invalidation-failed error)))
    true))

(defn <probe-current!
  []
  (let [repo (state/get-current-repo)
        context (db-subs/projection-context)]
    (if-not (and @state/db-worker-ready?
                 (string? repo)
                 (= repo (:graph-id context)))
      (p/resolved nil)
      (if-let [probe @*probe]
        probe
        (let [request (try
                        (state/<invoke-db-worker
                         :thread-api/get-projection-epoch repo)
                        (catch :default error
                          (p/rejected error)))
              probe (-> request
                        (p/then (fn [projection-epoch]
                                  (when (nat-int? projection-epoch)
                                    (apply-committed!
                                     {:repo repo
                                      :projection-epoch projection-epoch}))))
                        (p/catch (fn [error]
                                   (log/warn :db/projection-epoch-probe-failed
                                             {:repo repo :error error})
                                   nil)))]
          (reset! *probe probe)
          (p/finally probe
                     (fn []
                       (when (identical? probe @*probe)
                         (reset! *probe nil)))))))))

(defn install-probes!
  []
  (when (compare-and-set! *installed? false true)
    (add-watch flows/document-visibility-state
               ::document-visible-probe
               (fn [_ _ _ visibility]
                 (when (= "visible" visibility)
                   (<probe-current!))))
    (when (fn? (.-addEventListener js/globalThis))
      (.addEventListener js/globalThis
                         "focus"
                         (fn [_event] (<probe-current!)))))
  nil)

(defn reset-probe-for-test!
  []
  (reset! *probe nil))
