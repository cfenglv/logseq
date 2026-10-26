(ns frontend.components.diagnostics
  "On-demand error and sync details, usable without the normal workspace."
  (:require [frontend.context.i18n :refer [t]]
            [frontend.diagnostics :as diagnostics]
            [frontend.handler.db-based.sync :as sync-handler]
            [frontend.rfx :as rfx]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [io.factorhouse.hsx.core :as hsx]
            [logseq.shui.hooks :as hooks]
            [promesa.core :as p]))

(defn error-text [error]
  (cond
    (instance? p/TimeoutException error) (t :bug-report.diagnostics/timed-out)
    (= :diagnostics/clipboard-write-failed (:code (ex-data error)))
    (t :bug-report.diagnostics/copy-failed)
    :else (or (ex-message error) (when (instance? js/Error error) (.-message error)) (str error))))

(hsx/defc action-button [label action & [disabled? variant]]
  (ui/button label {:on-click action :disabled (boolean disabled?)
                    :variant (or variant :outline) :size :sm}))

(hsx/defc log-actions [error snapshot]
  (let [[feedback set-feedback!] (hooks/use-state nil)
        run-action! (fn [action message]
               (-> (p/then (p/resolved nil) action)
                   (p/then (fn [result]
                             (set-feedback!
                              (if (and (map? result) (false? (:opened? result)))
                                (t :bug-report.diagnostics/log-not-created) message))))
                   (p/catch #(set-feedback! (t :bug-report.diagnostics/action-failed (error-text %))))))]
    [:div.space-y-2
     [:div.flex.flex-wrap.items-center.gap-2
      (action-button (t :bug-report.diagnostics/copy)
                     #(run-action! (fn [] (diagnostics/<copy-report! error snapshot))
                            (t :bug-report.diagnostics/copied)) nil :ghost)
      (when (util/electron?)
        [:<>
         (action-button (t :bug-report.diagnostics/app-log)
                        #(run-action! (fn [] (diagnostics/<open-log! :app))
                               (t :bug-report.diagnostics/log-opened)) nil :ghost)
         (action-button (t :bug-report.diagnostics/sync-log)
                        #(run-action! (fn [] (diagnostics/<open-log! :sync))
                               (t :bug-report.diagnostics/log-opened)) nil :ghost)])]
     (when feedback [:p.m-0.text-sm {:role "status"} feedback])]))

(defn repair-label [repair]
  (case (:state repair)
    :repairing (t :bug-report.diagnostics/repairing)
    :waiting (t :bug-report.diagnostics/repair-waiting)
    :switching (t :bug-report.diagnostics/repair-switching)
    :verifying (t :bug-report.diagnostics/repair-verifying)
    :local-only (t :bug-report.diagnostics/repair-paused)
    :complete (t :bug-report.diagnostics/repair-complete)
    nil))

(defn connection-label [sync]
  (case (:ws-state sync)
    :open (t :bug-report.diagnostics/connected)
    :connecting (t :bug-report.diagnostics/connecting)
    :inactive (t :bug-report.diagnostics/not-enabled)
    :stopped (t :bug-report.diagnostics/stopped)
    :closed (t :bug-report.diagnostics/disconnected)
    :close (t :bug-report.diagnostics/disconnected)
    (t :bug-report.diagnostics/unavailable)))

(defn latest-repair
  [snapshot event repo]
  (let [current (:repair snapshot)
        candidate (when (and (= repo (:repo event))
                             (or (:operation-id current)
                                 (>= (or (:at event) 0) (or (:requested-at snapshot) 0))))
                    (diagnostics/repair-summary event))]
    (if (and candidate
             (not (and (= :complete (:state candidate))
                       (:operation-id current) (:operation-id candidate)
                       (not= (:operation-id current) (:operation-id candidate))
                       (not= :idle (:state current))))
             (or (> (:at candidate) (or (:at current) 0))
                 (and (= :idle (:state current)) (= :complete (:state candidate)))))
      candidate current)))

(defn repair-available?
  [snapshot repair error]
  (and (= :ready (:status snapshot))
       (or (= :local-only (:state repair))
      (and (= :open (get-in snapshot [:sync :ws-state]))
           (= :idle (:state repair))
           (contains? #{:db-sync/checksum-mismatch :db-sync/local-projection-mismatch}
                      (or (:code error) (get-in snapshot [:sync :last-error :code])))))))

(hsx/defc repair-actions [repo snapshot repair error refresh! on-retry]
  (let [[confirm? set-confirm!] (hooks/use-state false)
        [busy? set-busy!] (hooks/use-state false)
        [feedback set-feedback!] (hooks/use-state nil)]
    [:div.space-y-2
     (when-let [label (repair-label repair)]
       [:p.m-0.text-sm {:role "status"} label])
     (when (= :complete (:state repair))
       (when on-retry (action-button (t :bug-report.recovery/retry-display) on-retry)))
     (when (repair-available? snapshot repair error)
       (if confirm?
         [:div.space-y-2
          [:p.text-sm (t :sync/repair-confirm-desc)]
          [:div.flex.gap-2
           (action-button (t :ui/cancel) #(set-confirm! false) busy?)
           (action-button
            (t :bug-report.diagnostics/repair-action)
            (fn []
              (set-busy! true)
              (-> (sync-handler/<request-repair-for-repo! repo)
                  (p/then #(do (set-feedback! (sync-handler/repair-result-message %)) (refresh!)))
                  (p/catch #(set-feedback! (t :bug-report.diagnostics/action-failed (error-text %))))
                  (p/finally #(do (set-busy! false) (set-confirm! false))))) busy?)]]
         (action-button (t :bug-report.diagnostics/repair-action) #(set-confirm! true) busy?)))
     (when busy? [:p.text-sm {:role "status"} (t :bug-report.diagnostics/working)])
     (when feedback [:p.text-sm {:role "status"} feedback])]))

(defn use-snapshot [repo]
  (let [[snapshot set-snapshot!] (hooks/use-state #(hash-map :status :reading :requested-at (js/Date.now)))
        [busy? set-busy!] (hooks/use-state false)
        request (hooks/use-ref 0)
        refresh! (fn []
                   (let [id (inc (.-current request))]
                     (set! (.-current request) id)
                     (set-busy! true)
                     (-> (p/then (p/resolved nil)
                                 #(if repo (diagnostics/<read-status repo)
                                    {:status :no-graph}))
                         (p/then #(when (= id (.-current request)) (set-snapshot! %)))
                         (p/catch (fn [error]
                                    (when (= id (.-current request))
                                      (set-snapshot! #(assoc % :status :unavailable
                                                              :read-error (error-text error))))))
                         (p/finally #(when (= id (.-current request)) (set-busy! false))))))]
    (hooks/use-effect!
     (fn []
       (set-snapshot! {:status :reading :requested-at (js/Date.now)})
       (refresh!)
       #(set! (.-current request) (inc (.-current request))))
     [repo])
    [snapshot busy? refresh!]))

(hsx/defc status-content [repo snapshot busy? refresh! {:keys [error on-retry hide-log-actions?]}]
  (let [event (rfx/use-sub [:rtc/repair-status])
        repair (latest-repair snapshot event repo)
        sync (:sync snapshot)
        count-label #(if (number? %) % (t :bug-report.diagnostics/unknown))]
    [:section.space-y-3 {:aria-label (t :bug-report.diagnostics/status-title)}
     [:div.flex.flex-wrap.items-center.gap-3.text-sm
      [:strong (t :bug-report.diagnostics/status-title)]
      (case (:status snapshot)
        :reading [:span (t :bug-report.diagnostics/reading)]
        :no-graph [:span (t :bug-report.diagnostics/no-graph)]
        :unavailable [:span (t :bug-report.diagnostics/unavailable)]
        :ready [:span (connection-label sync)]
        nil)
      (action-button (t :bug-report.diagnostics/refresh) refresh! busy?)]
     (when (and sync (not= :inactive (:ws-state sync)))
       [:p.m-0.text-sm
        (t :bug-report.diagnostics/pending
           (count-label (:pending-local sync)) (count-label (:pending-asset sync))
           (count-label (:pending-server sync)))])
     (when-let [message (get-in sync [:last-error :message])]
       [:p.m-0.text-sm.break-words message])
     (when (:read-error snapshot)
       [:p.m-0.text-sm.break-words (t :bug-report.diagnostics/read-failed (:read-error snapshot))])
     (when-let [observed-at (:observed-at snapshot)]
       [:p.m-0.text-xs.opacity-60
        (t :bug-report.diagnostics/observed-at (.toLocaleTimeString (js/Date. observed-at)))])
     (repair-actions repo snapshot repair error refresh! on-retry)
     (when-not hide-log-actions?
       (log-actions (or error (get-in sync [:last-error])) (assoc snapshot :repair repair)))]))

(hsx/defc status-panel [repo opts]
  (let [[snapshot busy? refresh!] (use-snapshot repo)]
    (status-content repo snapshot busy? refresh! opts)))
