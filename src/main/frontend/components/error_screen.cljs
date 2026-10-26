(ns frontend.components.error-screen
  "Page recovery within the workspace, with a last-resort application fallback."
  (:require [electron.ipc :as ipc]
            [frontend.components.diagnostics :as diagnostics-ui]
            [frontend.context.i18n :refer [interpolate-sentence t]]
            [frontend.diagnostics :as diagnostics]
            [frontend.handler.export :as export]
            [frontend.handler.route :as route-handler]
            [frontend.handler.search :as search-handler]
            [frontend.rfx :as rfx]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [io.factorhouse.hsx.core :as hsx]
            [lambdaisland.glogi :as log]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.toaster.core :as toaster]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

(declare restart-action)

(hsx/defc recovery-step [{:keys [number title description action content first?]}]
  [:div.flex.flex-wrap.items-start.justify-between.gap-x-4.gap-y-2.py-3
   {:class (when-not first? "separator-top")}
   [:div.min-w-0.flex-1
    [:div.flex.flex-wrap.items-baseline.gap-x-3
     [:span.text-xs.toned-down (t :page/step number)]
     [:div.text-sm.font-medium.leading-6 title]]
    (when description [:p.mt-1.mb-0.text-xs.leading-5.toned-down description])
    content]
   [:div.shrink-0 action]])

(defn- action-feedback [kind feedback busy waiting? progress]
  (when (= kind (:kind feedback))
    [:p.m-0.text-sm.break-words {:role "status"}
     (if (and (= busy kind) waiting? (not (:running? progress)))
       (t :bug-report.diagnostics/waiting-for-worker) (:text feedback))]))

(hsx/defc recovery-tools [repo]
  (let [[busy set-busy!] (hooks/use-state nil)
        [feedback set-feedback!] (hooks/use-state nil)
        [waiting? set-waiting!] (hooks/use-state false)
        [confirm? set-confirm!] (hooks/use-state false)
        previous-build (hooks/use-ref nil)
        progress (rfx/use-sub [:search/index-build])
        run-action! (fn [kind action success]
                      (when (= kind :rebuild) (set! (.-current previous-build) (:build-id progress)))
                      (set-busy! kind)
                      (set-feedback! {:kind kind :text (t :bug-report.diagnostics/working)})
                      (-> (p/then (p/resolved nil) action)
                          (p/then (fn [result]
                                    (if (= kind :rebuild)
                                      (when-not (= :started result)
                                        (throw (js/Error. (t :bug-report.recovery/search-unavailable))))
                                      (do (set-feedback! {:kind kind :text success}) (set-busy! nil)))))
                          (p/catch #(do (set-busy! nil)
                                        (set-feedback! {:kind kind
                                                        :text (t :bug-report.diagnostics/action-failed
                                                                 (diagnostics-ui/error-text %))})))))]
    (hooks/use-effect!
     (fn []
       (set-waiting! false)
       (when busy
         (let [timer (js/setTimeout #(set-waiting! true) 5000)]
           #(js/clearTimeout timer))))
     [busy])
    (hooks/use-effect!
     (fn []
       (when (and (= busy :rebuild) (= repo (:repo progress))
                  (:build-id progress) (not= (.-current previous-build) (:build-id progress)))
         (case (:status progress)
           :completed (do (set-busy! nil)
                          (set-feedback! {:kind :rebuild :text (t :bug-report.recovery/rebuild-complete)}))
           :failed (do (set-busy! nil)
                       (set-feedback! {:kind :rebuild
                                       :text (t :bug-report.diagnostics/action-failed (:error-message progress))}))
           nil))
       nil)
     [busy (:repo progress) (:build-id progress) (:status progress)])
    [:div.space-y-5
     [:section
      [:h3.m-0.text-xs.font-medium.toned-down (t :bug-report.recovery/resume-title)]
     (recovery-step
      {:number "1" :first? true :title [:<> [:span (t :page/rebuild)]
               [:span (str " " (t :page/search-index))]]
       :action (ui/button (t :page/rebuild)
                          {:size :sm :variant :outline :disabled (boolean busy)
                           :aria-label (t :bug-report.recovery/rebuild-search)
                           :on-click #(run-action! :rebuild
                                       (fn []
                                         (when-not (and repo (search/get-engine repo))
                                           (throw (js/Error. (t :bug-report.recovery/search-unavailable))))
                                         (search-handler/rebuild-indices! repo true)) nil)})
       :content [:div.w-full.space-y-1
                 (when (and (= busy :rebuild) (= repo (:repo progress)))
                   [:<> [:progress.w-full {:max 100 :value (:progress progress)}]
                    [:p.m-0.text-sm (t :search/index-progress (or (:progress progress) 0))]])
                 (action-feedback :rebuild feedback busy waiting? progress)]})
     (recovery-step
      {:number "2" :title [:<> [:span (t :page/relaunch)]
               [:span (str " " (t :page/the-app))]]
       :description (t :page/relaunch-desc)
       :action [:div.flex.flex-wrap.items-center.justify-end.gap-2
                (restart-action)
                (shui/shortcut ["cmd" (if (util/electron?) "q" "r")])]})]
     [:section
      [:h3.m-0.text-xs.font-medium.toned-down (t :bug-report.recovery/data-title)]
     (recovery-step
      {:number "3" :first? true :title [:<> [:span (t :ui/export)]
               [:span (str " " (t :page/current-graph-as-sqlite-db))]]
       :description (t :bug-report.recovery/export-description)
       :action (ui/button (t :ui/export)
                          {:size :sm :variant :outline :disabled (boolean (or busy (nil? repo)))
                           :on-click (if (util/electron?)
                                       #(run-action! :export (fn [] (export/<export-sqlite-file! repo))
                                                     (t :bug-report.recovery/export-complete))
                                       #(export/export-repo-as-sqlite-db! repo))})
       :content [:<> [:a#download-as-sqlite-db.hidden]
                 (action-feedback :export feedback busy waiting? progress)]})
     (recovery-step
      {:number "4" :title [:<> [:span (t :page/clear)]
               [:span (str " " (t :page/local-storage))]]
       :description (t :bug-report.recovery/reset-settings-description)
       :action (ui/button (t :bug-report.recovery/reset)
                          {:size :sm :variant :outline :disabled (boolean busy)
                           :aria-label (t :bug-report.recovery/reset-settings)
                           :on-click #(set-confirm! true)})
       :content [:div.space-y-2
                 (when confirm?
                   [:div.flex.gap-2.pt-2
                    (diagnostics-ui/action-button (t :ui/cancel) #(set-confirm! false))
                    (diagnostics-ui/action-button
                     (t :ui/confirm)
                     #(do (.clear js/localStorage)
                          (set-confirm! false)
                          (set-feedback! {:kind :reset :text (t :page/cleared)})))])
                 (action-feedback :reset feedback busy waiting? progress)]})]]))

(hsx/defc restart-action []
  (let [quit-error (rfx/use-sub [:diagnostics/quit-error])
        [restarting? set-restarting!] (hooks/use-state false)
        [feedback set-feedback!] (hooks/use-state nil)]
    (hooks/use-effect!
     (fn []
       (when quit-error
         (set-restarting! false)
         (set-feedback! (t :bug-report.diagnostics/action-failed (:message quit-error))))
       nil)
     [quit-error])
    (when (util/electron?)
      [:div.space-y-2
       (diagnostics-ui/action-button
        (t :bug-report.recovery/restart)
        #(do (state/set-state! :diagnostics/quit-error nil)
             (set-restarting! true)
             (set-feedback! (t :bug-report.recovery/restarting))
             (-> (ipc/ipc :relaunchApp)
                 (p/catch (fn [error]
                            (set-restarting! false)
                            (set-feedback! (t :bug-report.diagnostics/action-failed
                                              (diagnostics-ui/error-text error))))))) restarting?)
       (when feedback [:p.m-0.text-sm.break-words {:role "status"} feedback])])))

(defn- use-recovery-navigation [reset-error]
  (let [route-match (rfx/use-sub [:route-match])
        route-location [(:path route-match) (:query-params route-match)]
        failed-location (hooks/use-ref route-location)
        [go-to-graphs? set-go-to-graphs!] (hooks/use-state false)
        [feedback set-feedback!] (hooks/use-state nil)]
    (hooks/use-effect!
     (fn []
       (when (or (not= (.-current failed-location) route-location)
                 (and go-to-graphs? (= :graphs (get-in route-match [:data :name]))))
         (reset-error))
       nil)
     [route-location go-to-graphs?])
    {:go-to-graphs! #(try (set-go-to-graphs! true) (route-handler/redirect-to-all-graphs)
                         (catch :default error (set-feedback! (diagnostics-ui/error-text error))))
     :feedback feedback}))

(hsx/defc error-details [record repo snapshot busy? refresh! repair]
  [:div#recovery-diagnostics.space-y-5
   [:section.space-y-2
    [:h3.m-0.text-sm.font-medium (t :bug-report.diagnostics/details)]
    [:div.space-y-1
     [:p.m-0.text-sm.break-words (or (:message record) (t :bug-report.recovery/unknown-error))]
     [:p.m-0.text-xs.toned-down (.toLocaleString (js/Date. (:at record)))]]]
   [:section.space-y-2.separator-top.pt-4
    [:h3.m-0.text-sm.font-medium (t :bug-report.diagnostics/logs-title)]
    (diagnostics-ui/log-actions record (assoc snapshot :repair repair))]
   [:div.separator-top.pt-4
    (diagnostics-ui/status-content repo snapshot busy? refresh! {:error record :hide-log-actions? true})]
   [:details.separator-top.pt-4 {:class "group/technical"}
    [:summary.flex.items-center.gap-1.cursor-pointer.list-none.text-sm.toned-down
     (ui/icon "chevron-right" {:size 14 :class "transition-transform group-open/technical:rotate-90"})
     (t :bug-report.diagnostics/technical-details)]
    [:pre.mt-3.mb-0.max-h-48.overflow-auto.text-xs.whitespace-pre-wrap.break-words.select-text
     (str (:stack record) "\n" (:component-stack record))]]])

(hsx/defc screen [{:keys [error component-stack reset-error within-shell?]}]
  (let [[record] (hooks/use-state #(diagnostics/error-record error component-stack))
        repo (state/get-current-repo)
        [snapshot busy? refresh!] (diagnostics-ui/use-snapshot repo)
        event (rfx/use-sub [:rtc/repair-status])
        repair (diagnostics-ui/latest-repair snapshot event repo)
        {:keys [go-to-graphs! feedback]} (use-recovery-navigation reset-error)
        wrapper (if within-shell? :section :main#main-content-container)]
    [:<>
     [wrapper {:class (if within-shell? "w-full" "flex w-full justify-center overflow-auto")}
      [:div.flex.justify-center.px-4.py-8.sm:px-6
       [:div.w-full.max-w-2xl
        [:header.space-y-4.mb-6
         [:div.flex.items-center.gap-3
          [:div.icon-box.p-1.rounded (ui/icon "bug" {:style {:font-size ui/icon-size}})]
          [:h2.m-0.text-2xl.font-bold (t :page/something-went-wrong)]]
         [:div.flex.flex-wrap.items-center.gap-2
          (diagnostics-ui/action-button (t :bug-report.recovery/retry-display) reset-error nil :default)
          (diagnostics-ui/action-button (t :bug-report.recovery/switch-graph) go-to-graphs! nil :ghost)]
         (when-let [label (diagnostics-ui/repair-label repair)]
           [:p.m-0.text-sm.toned-down {:role "status"} label])
         (when feedback [:p.m-0.text-sm.break-words {:role "status"} feedback])]
        (shui/tabs
         {:default-value "recovery" :class "w-full"}
         (shui/tabs-list
          {:variant :line :class "w-full gap-6 border-b"}
          (shui/tabs-trigger {:value "recovery" :variant :line} (t :bug-report.recovery/tab-label))
          (shui/tabs-trigger {:value "diagnostics" :variant :line} (t :bug-report.diagnostics/tab-label)))
         (shui/tabs-content
          {:value "recovery" :keep-mounted true :class "pt-3 space-y-4"}
          [:p.m-0.text-sm.leading-6.toned-down (t :page/logseq-is-having-a-problem)]
          (recovery-tools repo))
         (shui/tabs-content
          {:value "diagnostics" :keep-mounted true :class "pt-3"}
          (error-details record repo snapshot busy? refresh! repair)))
        [:footer.mt-6.pt-4.separator-top.text-xs.leading-6.toned-down
         (interpolate-sentence
          (t :page/open-issue-desc)
          :links [{:href "https://github.com/logseq/logseq/issues/new?labels=from:in-app&template=bug_report.yaml"}])]]]]
     (when-not within-shell? (toaster/install-toaster))]))

(defn render-fallback [^js props]
  (screen {:error (.-error props) :component-stack (.-componentStack props)
           :reset-error (.-resetError props)}))

(defn render-content-fallback [^js props]
  (screen {:error (.-error props) :component-stack (.-componentStack props)
           :reset-error (.-resetError props) :within-shell? true}))

(hsx/defc boundary [view & [within-shell?]]
  ;; JS props preserve the fallback component identity across recovery navigation.
  (ui/error-boundary #js {:fallback (if within-shell? render-content-fallback render-fallback)
                          :onError (fn [error _stack _event-id] (log/error :exception error))}
                     view))
