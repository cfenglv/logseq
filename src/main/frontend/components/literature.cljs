(ns frontend.components.literature
  (:require [clojure.string :as string]
            [frontend.components.select :as components-select]
            [frontend.context.i18n :as i18n :refer [t]]
            [frontend.db.subs :as db-subs]
            [frontend.db.hooks :as db-hooks]
            [frontend.db.async :as db-async]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.editor.assets :as editor-assets-handler]
            [frontend.handler.literature :as literature-handler]
            [frontend.handler.literature-zotero :as zotero]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.rfx :as rfx]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.util.ref :as ref]
            [lambdaisland.glogi :as log]
            [logseq.common.defkeywords :refer [defkeyword]]
            [logseq.db :as ldb]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [reitit.frontend.easy :as rfe]
            [io.factorhouse.hsx.core :as hsx]))

(defn- empty-search-result
  [status]
  {:status status :papers [] :truncated? false})

(defn- native-control-key-down
  [event]
  (when (and (contains? #{"BUTTON" "SELECT" "A"} (.. event -target -tagName))
             (contains? #{"Enter" "ArrowUp" "ArrowDown" "ArrowLeft" "ArrowRight"} (.-key event)))
    ;; Keep native controls ahead of the outliner shortcuts in this view.
    (.stopPropagation event)))


(hsx/defc doi-lookup-input
  [repo value set-value! on-reset! on-record! current? working? set-working!]
  (let [desktop? (util/electron?)
        [error-message set-error!] (hooks/use-state nil)
        *controller (hooks/use-ref nil)
        lookup! (fn [text]
                  (when (and (current?) (not working?) (not (string/blank? text)))
                    (let [controller (js/AbortController.)]
                      (when-let [previous (hooks/deref *controller)] (.abort previous))
                      (hooks/set-ref! *controller controller)
                      (set-error! nil)
                      (on-reset!)
                      (set-working! true)
                      (-> (literature-handler/<lookup-doi! repo text (.-signal controller))
                          (p/then (fn [result]
                                    (when (and (current?) (not (.. controller -signal -aborted)))
                                      (if (= :ready (:status result))
                                        (on-record! (:record result))
                                        (set-error! (t (if (= :invalid-identifier (:reason result))
                                                        :literature.doi/invalid
                                                        :literature.doi/lookup-error)))))))
                          (p/catch (fn [error]
                                     (log/error :literature-doi-lookup-failed error)
                                     (when (and (current?) (not (.. controller -signal -aborted)))
                                       (set-error! (t :literature.doi/lookup-error)))))
                          (p/finally #(when (and (current?)
                                                (identical? controller (hooks/deref *controller)))
                                        (set-working! false)))))))
        change! (fn [text]
                  (set-value! text)
                  (on-reset!)
                  (set-error! nil))]
    (hooks/use-effect! (fn [] #(when-let [controller (hooks/deref *controller)] (.abort controller))) [])
    [:div.flex.flex-col.gap-1
     [:div.flex.items-center.gap-1
      (shui/input
       {:class "flex-1"
        :data-literature-field "doi"
        :aria-label (t :literature.metadata/doi)
        :placeholder (t :literature.doi/input-placeholder)
        :value value :disabled working?
        :on-change #(change! (util/evalue %))
        :on-paste (when desktop? (fn [event]
                    (let [text (some-> (.-clipboardData event) (.getData "text/plain") string/trim)]
                      (when-not (string/blank? text)
                        (.preventDefault event)
                        (change! text)
                        (lookup! text)))))
        :on-key-down (when desktop? (fn [event]
                       (when (= "Enter" (.-key event))
                         (.preventDefault event)
                         (.stopPropagation event)
                         (lookup! value))))})
      (when desktop?
       (shui/button
       {:type "button" :variant :ghost :size :sm
        :title (t :literature.doi/lookup-action)
        :aria-label (t :literature.doi/lookup-action)
        :data-literature-action "lookup-doi"
        :disabled (or working? (string/blank? value))
        :on-click #(lookup! value)}
       (shui/tabler-icon (if working? "loader-2" "search")
                        {:class (when working? "animate-spin") :size 17})))]
     (when error-message [:div.text-sm.text-red-600 {:role "alert"} error-message])]))

(declare open-doi-review!)

(defn- review-paper-search-effect
  [*paper-search-request debounced-paper-query set-paper-search! repo current?]
  (fn []
    (let [request-id (swap! *paper-search-request inc)
          query (string/trim debounced-paper-query)]
      (if (< (count query) 2)
        (set-paper-search! {:status :idle
                            :papers []
                            :truncated? false})
        (do
          (set-paper-search! #(assoc % :status :loading))
          (-> (literature-handler/<search-existing-papers! repo query)
              (p/then
               (fn [result]
                 (when (and (current?) (= request-id @*paper-search-request))
                   (set-paper-search! result))))
              (p/catch
               (fn [error]
                 (log/error :literature-paper-search-failed error)
                 (when (and (current?) (= request-id @*paper-search-request))
                   (set-paper-search! {:status :unavailable
                                       :papers []
                                       :truncated? false})))))))
      (fn []
        (when (= request-id @*paper-search-request)
          (swap! *paper-search-request inc))))))

(defn- review-registration-handler
  [current? set-working! set-error-message!]
  (fn [result]
    (when (current?)
      (set-working! false)
      (case (:status result)
        :created
        (do
          (notification/show! (t :asset.literature/created) :success)
          (shui/dialog-close! :literature-review))

        :linked
        (do
          (notification/show! (t :asset.literature/linked) :success)
          (shui/dialog-close! :literature-review))

        :already-linked
        (do
          (notification/show! (t :asset.literature/already-linked) :success)
          (shui/dialog-close! :literature-review))

        (set-error-message! (t :asset.literature/register-error))))))

(defn- review-existing-paper-picker
  [doi-record paper-query working? set-paper-query! paper-search link-existing!]
  (when-not doi-record
  [:div.flex.flex-col.gap-2
   [:div.text-sm.font-medium (t :asset.literature/existing-label)]
   [:div.text-sm.opacity-60 (t :asset.literature/existing-description)]
   (shui/input
    {:data-literature-field "existing-paper"
     :aria-label (t :asset.literature/existing-label)
     :placeholder (t :asset.literature/existing-placeholder)
     :value paper-query
     :disabled working?
     :on-change #(set-paper-query! (.. % -target -value))})
   (case (:status paper-search)
     :idle
     (when (seq paper-query)
       [:div.text-xs.opacity-60
        (t :asset.literature/search-more-characters)])

     (:ok :loading)
     [:<>
      (if (seq (:papers paper-search))
        [:div.flex.flex-col.gap-1
         (for [{paper-uuid :block/uuid
                paper-title :block/title
                paper-detail :literature/detail}
               (:papers paper-search)]
           (shui/button
            {:key (str paper-uuid)
             :variant :outline
             :size :sm
             :class "h-auto justify-start whitespace-normal text-left"
             :data-literature-existing-paper (str paper-uuid)
             :disabled working?
             :on-click #(link-existing! paper-uuid)}
            [:span.flex.flex-col.items-start
             [:span paper-title]
             (when paper-detail
               [:span.text-xs.font-normal.opacity-60 paper-detail])]))]
        (when-not (:truncated? paper-search)
          [:div.text-xs.opacity-60
           (t (if (= :loading (:status paper-search))
                :asset.literature/searching :asset.literature/search-empty))]))
      (when (:truncated? paper-search)
        [:div.text-xs.opacity-60
         (t :asset.literature/search-truncated)])]

     [:div.text-xs.text-red-600
      (t :asset.literature/search-unavailable)])]))

(defn- review-keep-button
  [working? current? set-error-message! set-working! repo asset-uuid]
  (shui/button
   {:variant :ghost
    :size :sm
    :data-literature-action "keep"
     :disabled working?
     :on-click (fn []
                 (when (current?)
                   (set-error-message! nil)
                   (set-working! true)
                   (-> (literature-handler/<keep-pdf! repo asset-uuid)
                       (p/then
                        (fn [result]
                          (when (current?)
                            (set-working! false)
                            (if (= :kept (:status result))
                              (shui/dialog-close! :literature-review)
                              (set-error-message!
                               (t (if (= :asset-already-linked (:reason result))
                                    :asset.literature/already-linked
                                    :asset.literature/keep-error)))))))
                       (p/catch
                        (fn [error]
                          (log/error :literature-keep-failed error)
                          (when (current?)
                            (set-working! false)
                            (set-error-message!
                             (t :asset.literature/keep-error))))))))}
   (t :asset.literature/keep-action)))

(defn- review-retry-button
  [working? current? set-error-message! set-working! repo asset-uuid]
  (shui/button
   {:variant :outline
    :size :sm
    :data-literature-action "retry"
    :disabled working?
    :on-click (fn []
                (when (current?)
                  (set-error-message! nil)
                  (set-working! true)
                  (-> (literature-handler/<retry-review-candidate!
                       repo asset-uuid)
                      (p/then
                       (fn [result]
                         (when (current?)
                           (set-working! false)
                           (cond
                             (= :content-mismatch (get-in result [:results 0 :extraction :reason]))
                             (set-error-message!
                              (t :asset.literature/content-mismatch-error))

                             (and (= :complete (:status result))
                                  (= :complete (get-in result [:results 0 :extraction :status])))
                             (shui/dialog-close! :literature-review)

                             :else
                             (set-error-message!
                              (t :asset.literature/retry-error))))))
                      (p/catch
                       (fn [error]
                         (log/error :literature-identification-retry-failed error)
                         (when (current?)
                           (set-working! false)
                           (set-error-message!
                            (t :asset.literature/retry-error))))))))}
   (t :asset.literature/retry-action)))

(defn- review-identify-button
  [working? current? set-working! set-error-message! repo asset-uuid finish-registration! title
   asset-block set-title! doi set-doi! arxiv set-arxiv!]
  (shui/button
   {:size :sm
    :data-literature-action "identify-add"
    :disabled working?
    :on-click
    (fn []
      (when (current?)
        (set-working! true)
        (set-error-message! nil)
        (-> (literature-handler/<retry-review-candidate!
             repo asset-uuid {:register? true :allow-online? (util/electron?)})
            (p/then
             (fn [result]
               (let [applied (:automatic-result result)]
                 (if (contains? #{:created :linked :already-linked} (:status applied))
                   (finish-registration! applied)
                   (when (current?)
                     (set-working! false)
                     (when-let [fresh (literature-handler/review-candidate repo asset-uuid)]
                       (when (= title (:block/title asset-block))
                         (when-let [value (literature-handler/review-title fresh)] (set-title! value)))
                       (let [identifiers (literature-handler/review-identifiers fresh)]
                         (when (string/blank? doi) (set-doi! (or (:doi identifiers) "")))
                         (when (string/blank? arxiv) (set-arxiv! (or (:arxiv identifiers) "")))))
                     (set-error-message!
                      (t (if (contains? #{:metadata-unavailable :metadata-request-cancelled} (:reason applied))
                           :literature.organization/network-paused
                           :literature.organization/needs-review))))))))
            (p/catch (fn [error]
                       (log/error :literature-identify-and-add-failed error)
                       (when (current?)
                         (set-working! false)
                         (set-error-message! (t :asset.literature/retry-error))))))))}
   (t :literature.organization/identify-action)))

(defn- review-register-button
  [doi-record working? title current? set-error-message! set-working! repo asset-uuid doi arxiv
   finish-registration!]
  (shui/button
   {:size :sm :variant (if doi-record :default :outline)
    :data-literature-action "add"
    :disabled (or working? (string/blank? title))
    :on-click (fn []
                (when (current?)
                  (set-error-message! nil)
                  (set-working! true)
                  (-> (literature-handler/<register-pdf!
                       repo asset-uuid title {:doi doi :arxiv arxiv} doi-record)
                      (p/then finish-registration!)
                      (p/catch
                       (fn [error]
                         (log/error :literature-registration-failed error)
                         (when (current?)
                           (set-working! false)
                           (set-error-message!
                            (t :asset.literature/register-error))))))))}
   (t :asset.literature/add-action)))

(hsx/defc literature-review-dialog
  [repo asset-block review current?]
  (let [[title set-title!] (hooks/use-state
                            (or (literature-handler/review-title review)
                                (:block/title asset-block)
                                ""))
        [working? set-working!] (hooks/use-state false)
        [error-message set-error-message!] (hooks/use-state nil)
        asset-uuid (:block/uuid asset-block)
        identifiers (literature-handler/review-identifiers review)
        [doi set-doi!] (hooks/use-state (or (:doi identifiers) ""))
        [arxiv set-arxiv!] (hooks/use-state (or (:arxiv identifiers) ""))
        [doi-record set-doi-record!] (hooks/use-state nil)
        [paper-query set-paper-query!] (hooks/use-state "")
        [paper-search set-paper-search!] (hooks/use-state
                                          {:status :idle
                                           :papers []
                                           :truncated? false})
        *paper-search-request (hooks/use-memo #(atom 0) [])
        debounced-paper-query (hooks/use-debounced-value paper-query 200)
        _ (hooks/use-effect!
           (review-paper-search-effect *paper-search-request debounced-paper-query set-paper-search! repo current?)
           [debounced-paper-query repo])
        extraction-status (get-in review [:extraction :status])
        finish-registration!
        (review-registration-handler current? set-working! set-error-message!)
        link-existing!
        (fn [paper-uuid]
          (when (current?)
            (set-error-message! nil)
            (set-working! true)
            (-> (literature-handler/<link-pdf-to-paper!
                 repo asset-uuid paper-uuid)
                (p/then finish-registration!)
                (p/catch
                 (fn [error]
                   (log/error :literature-link-existing-failed error)
                   (when (current?)
                     (set-working! false)
                     (set-error-message!
                      (t :asset.literature/register-error))))))))]
    [:div.flex.flex-col.gap-4 {:on-key-down native-control-key-down}
     (when-not doi-record [:p.text-sm.opacity-80 (t :asset.literature/review-description)])
     [:div.flex.flex-col.gap-1
      [:label.text-sm.font-medium (t :asset.literature/title-label)]
      (shui/input
       {:data-literature-field "title"
        :aria-label (t :asset.literature/title-label)
        :value title
        :disabled working?
        :on-change #(set-title! (.. % -target -value))})]
     [:div.flex.flex-col.gap-1
      [:div.text-sm.font-medium (t :asset.literature/identifier-label)]
      (when-not (or doi-record (seq identifiers))
        [:div.text-sm.opacity-60
         (if (= :pending extraction-status)
           (t :asset.literature/identification-pending)
           (t :asset.literature/no-identifier))])
      (doi-lookup-input repo doi set-doi! #(set-doi-record! nil)
                        (fn [record]
                          (set-doi-record! record)
                          (set-doi! (:doi record))
                          (set-arxiv! (or (:arxiv record) ""))
                          (set-title! (:title record)))
                        current? working? set-working!)
      (when doi-record
        [:div.flex.items-baseline.gap-2.text-sm.text-muted-foreground
         (when-let [date (:date doi-record)] [:span.shrink-0 date])
         [:span.truncate {:title (:authors doi-record)} (:authors doi-record)]])
      (when-not doi-record
(shui/input
       {:data-literature-field "arxiv"
        :placeholder (t :asset.literature/arxiv-placeholder)
        :value arxiv
        :disabled working?
        :on-change #(set-arxiv! (.. % -target -value))}))]
     (review-existing-paper-picker doi-record paper-query working? set-paper-query! paper-search link-existing!)
     (when (and (not doi-record)
                (or (= :failed extraction-status)
                    (= :skipped extraction-status)
                    (= :busy (:review-status review))))
       [:div.text-sm.opacity-70
        (t (if (= :content-mismatch (get-in review [:extraction :reason]))
             :asset.literature/content-mismatch-error
             :asset.literature/identification-unavailable))])
     (when error-message
       [:div.text-sm.text-red-600 error-message])
     (when (and (util/electron?) (not doi-record))
       [:p.text-xs.opacity-60 (t :literature.organization/privacy-description)])
     [:div.flex.flex-wrap.justify-end.gap-2
      (review-keep-button working? current? set-error-message! set-working! repo asset-uuid)
      (when-not doi-record
      (review-retry-button working? current? set-error-message! set-working! repo asset-uuid))
      (when-not doi-record
      (review-identify-button working? current? set-working! set-error-message! repo asset-uuid finish-registration! title asset-block set-title! doi set-doi! arxiv set-arxiv!))
      (review-register-button doi-record working? title current? set-error-message! set-working! repo asset-uuid doi arxiv finish-registration!)]]))

(hsx/defc review-entry-dialog
  [repo asset-uuid dialog-close]
  (let [[result set-result!] (hooks/use-state {:status :loading})
        *request-id (hooks/use-memo #(atom 0) [])
        current-repo (rfx/use-sub [:git/current-repo])
        restoring? (rfx/use-sub [:db/restoring?])
        dialog-current? (fn [request-id]
                          (let [dialog (second (shui/dialog-get :literature-review))]
                            (and (= request-id @*request-id)
                                 (:open? dialog)
                                 (identical? dialog-close (:close dialog)))))
        current? (fn [context request-id]
                   (and (dialog-current? request-id)
                        (= repo (state/get-current-repo))
                        (not (state/get-state :db/restoring?))
                        (= context (db-subs/projection-context))))
        open-paper! (fn [paper-uuid]
                      (when (current? (:projection-context result) (:request-id result))
                        (shui/dialog-close! :literature-review)
                        (state/close-settings!)
                        (route-handler/redirect-to-page! paper-uuid)))
        paper-choice (fn [{paper-uuid :block/uuid title :block/title}]
                       (shui/button
                        {:key (str paper-uuid)
                         :variant :outline
                         :class "h-auto justify-start whitespace-normal text-left"
                         :data-literature-owner (str paper-uuid)
                         :on-click #(open-paper! paper-uuid)}
                        [:span.flex.flex-col.items-start
                         [:span (if (string/blank? title) (t :ui/untitled) title)]
                         [:code.text-xs.font-normal.opacity-60 (str paper-uuid)]]))]
    (hooks/use-effect!
     (fn []
       (let [request-id (swap! *request-id inc)
             context (db-subs/projection-context)]
         (set-result! {:status :loading})
         (-> (p/do! (literature-handler/pause-organization! repo)
                       (literature-handler/<prepare-review! repo asset-uuid))
             (p/then
              (fn [prepared]
                (when (dialog-current? request-id)
                  (set-result! (if (current? context request-id)
                                 (assoc prepared
                                        :projection-context context
                                        :request-id request-id)
                                 {:status :unavailable})))))
             (p/catch
              (fn [error]
                (log/error :literature-review-open-failed error)
                (when (dialog-current? request-id)
                  (set-result! {:status :unavailable})))))
         (fn []
           (when (= request-id @*request-id)
             (swap! *request-id inc)))))
     [repo asset-uuid current-repo restoring?])
    (if (or (not= repo current-repo) restoring?)
      [:div.text-sm.text-red-600 (t :asset.literature/review-error)]
      (case (:status result)
        :loading [:div.text-sm.opacity-60 (t :ui/loading)]
        :ready (literature-review-dialog
                repo (:asset result) (:review result)
                #(current? (:projection-context result) (:request-id result)))
        :linked
        [:div.flex.flex-col.gap-3
         [:p.text-sm.opacity-70 (t :asset.literature/already-linked)]
         (paper-choice (:paper result))
         (when (util/electron?)
           (shui/button {:variant :outline :data-literature-action "complete-doi"
                         :on-click #(open-doi-review! repo (get-in result [:paper :block/uuid]))}
                        (t :literature.doi/entry-action)))]
        :conflict
        [:div.flex.flex-col.gap-3
         [:p.text-sm.opacity-70 (t :asset.literature/owner-conflict-desc)]
         (map paper-choice (:papers result))]
        [:div.text-sm.text-red-600 (t :asset.literature/review-error)]))))

(defn open-review!
  [repo asset-uuid]
  (shui/dialog-open!
   (fn [{dialog-close :close}]
     (review-entry-dialog repo asset-uuid dialog-close))
   {:id :literature-review
    :content-props {:finalFocus true}
    :title (t :asset.literature/review-title)
    :align :center}))

(hsx/defc destination-picker
  [repo on-chosen]
  (let [[input set-input!] (hooks/use-state "")
        [pages set-pages!] (hooks/use-state [])
        [error? set-error!] (hooks/use-state false)
        debounced-input (hooks/use-debounced-value input 200)]
    (hooks/use-effect!
     (fn []
       (let [active? (atom true)]
         (set-error! false)
         (set-pages! [])
         (when-not (string/blank? debounced-input)
           (-> (search/block-search repo debounced-input
                                    {:page-only? true :built-in? false :enable-snippet? false})
               (p/then (fn [result]
                         (when @active?
                           (set-pages! (filterv #(and (ldb/internal-page? %)
                                                      (not (ldb/class? %))
                                                      (not (ldb/property? %)))
                                                result)))))
               (p/catch (fn [error]
                          (log/error :literature-destination-search-failed error)
                          (when @active? (set-error! true))))))
         #(reset! active? false)))
     [repo debounced-input])
    [:div.min-w-64
     (components-select/select
      {:items (mapv (fn [page] {:value page :label (:block/title page)}) pages)
       :extract-fn :label
       :extract-chosen-fn :value
       :close-modal? false
       :on-chosen (fn [page _selected? _choices _event]
                    (on-chosen page))
       :input-default-placeholder (t :literature/destination-placeholder)
       :on-input set-input!})
     (when error?
       [:div.text-sm.text-red-600 (t :literature/destination-search-error)])]))

(defn- <start-organization!
  [repo online?]
  (-> (literature-handler/<organize-existing-pdfs! repo online?)
      (p/catch (fn [error]
                 (log/error :literature-organization-failed error)
                 (when (= repo (state/get-current-repo))
                   (notification/show! (t :literature.organization/failed) :error))))))

(defn- open-organization!
  [repo final-focus]
  (shui/dialog-open!
   (fn [_]
     [:div.flex.flex-col.gap-4 {:on-key-down native-control-key-down}
      [:p.text-sm.opacity-70 (t :literature.organization/privacy-description)]
      [:div.flex.flex-wrap.justify-end.gap-2
       (shui/button {:variant :ghost :on-click #(shui/dialog-close! :literature-organization)} (t :ui/cancel))
       (shui/button {:variant :ghost :data-literature-organize-local true
                     :on-click #(do (shui/dialog-close! :literature-organization) (<start-organization! repo false))}
                    (t :literature.organization/local-action))
       (when (util/electron?)
         (shui/button {:data-literature-organize-online true
                       :on-click #(do (shui/dialog-close! :literature-organization) (<start-organization! repo true))}
                      (ui/icon "file-search" {:size 16})
                      (t :literature.organization/online-action)))]])
   {:id :literature-organization :title (t :literature.organization/title)
    :content-props {:finalFocus final-focus}}))

(hsx/defc pdf-organization
  [repo show-action?]
  (let [_language (rfx/use-sub [:preferred-language])
        task (rfx/use-sub [:literature/organization repo])
        *trigger (hooks/use-ref nil)
        running? (= :running (:status task))]
    (when (or show-action? task)
      [:div.flex.flex-wrap.items-center.gap-3.mb-4
       (when (and show-action? (not (contains? #{:running :paused} (:status task))))
         (shui/button {:size :sm :ref *trigger
                       :disabled running? :focusable-when-disabled true
                       :data-literature-organize true
                       :on-click #(open-organization! repo *trigger)}
                      (ui/icon "file-search" {:size 16})
                      (t :literature.organization/title)))
       (when task
         [:div.flex.flex-wrap.items-center.gap-2.text-sm.min-w-0
          {:data-literature-organization (name (:status task))}
          (ui/icon (case (:status task)
                     :running "loader-2"
                     :paused "player-pause"
                     :failed "alert-circle"
                     "circle-check")
                   {:size 16 :class (when running? "animate-spin")})
          [:span.opacity-70 {:role "status" :aria-live "polite"}
           (t :literature.organization/summary
              (:checked task) (+ (:created task) (:linked task)) (:pending task))]
          (when (pos? (:unavailable task))
            [:span.opacity-60 (t :literature.organization/unavailable-count (:unavailable task))])
          (case (:status task)
            :running
            [:<>
             [:span.truncate.opacity-50.max-w-48 {:title (:current-title task)} (:current-title task)]
             (shui/button {:variant :ghost :size :sm :class "px-2"
                           :aria-label (t :literature.organization/pause-action)
                           :title (t :literature.organization/pause-action)
                           :data-literature-organize-pause true
                           :on-click #(literature-handler/pause-organization! repo)}
                          (ui/icon "player-pause" {:size 16}))]
            :paused
            [:<>
             [:span.opacity-60
              (t (if (= :metadata-unavailable (:reason task))
                   :literature.organization/network-paused :literature.organization/paused))]
             (shui/button {:variant :ghost :size :sm :data-literature-organize-resume true
                           :on-click #(<start-organization! repo (:allow-online? task))}
                          (ui/icon "player-play" {:size 16})
                          (t :literature.organization/resume-action))]
            :failed [:span.text-red-600 (t :literature.organization/failed)]
            nil)])])))

(defn- pdf-review-status
  [review kept?]
  (let [status (or (:review-status review) (get-in review [:extraction :status]))
        reason (or (get-in review [:automatic-result :reason]) (get-in review [:extraction :reason]))]
    (cond
      kept? {:i18n-key :literature/unregistered-kept-status}
      (or (= :cancelled status) (contains? #{:cancelled :metadata-request-cancelled} reason)) nil
      (contains? #{:pending :retrying :busy} status) {:i18n-key :asset.literature/identification-pending}
      (= :file-not-local reason) {:i18n-key :literature.organization/file-unavailable}
      (= :metadata-unavailable reason) {:i18n-key :literature.organization/network-paused}
      (= :password reason) {:i18n-key :literature/pending-password}
      (contains? #{:parse-error :read-error :pdfjs-unavailable} reason) {:i18n-key :literature/pending-unreadable}
      (and review (not= :not-run status)) {:i18n-key :literature/pending-status})))

(hsx/defc unregistered-pdfs
  [repo]
  (let [[result set-result!] (hooks/use-state {:status :loading :assets []})
        local-reviews (rfx/use-sub [:literature/review-candidates repo])
        [include-kept? set-include-kept!] (hooks/use-state false)
        *request-id (hooks/use-memo #(atom 0) [])
        current-repo (rfx/use-sub [:git/current-repo])
        restoring? (rfx/use-sub [:db/restoring?])
        load-page!
        (fn [after]
          (let [request-id (swap! *request-id inc)]
            (set-result! #(assoc % :status :loading))
            (-> (literature-handler/<unregistered-pdfs! repo after {:include-kept? include-kept?})
                (p/then (fn [page]
                          (when (= request-id @*request-id)
                            (set-result!
                             (if (and (= :ok (:status page))
                                      (not= (:projection-context page)
                                            (db-subs/projection-context)))
                               {:status :unavailable :assets []}
                               page)))))
                (p/catch (fn [error]
                           (log/error :literature-unregistered-pdfs-failed error)
                           (when (= request-id @*request-id)
                             (set-result! {:status :unavailable :assets []})))))))]
    (hooks/use-effect!
     (fn []
       (load-page! nil)
       #(swap! *request-id inc))
     [repo current-repo restoring? include-kept? local-reviews])
    [:div.flex.flex-col.gap-1
     [:div.flex.items-center.justify-between.gap-3.mb-2
      [:label.flex.items-center.gap-2.text-sm.opacity-70
       (shui/checkbox {:checked include-kept?
                       :on-checked-change #(set-include-kept! (true? %))})
       (t :literature.library/show-ordinary-action)]
      (shui/button {:variant :ghost :size :sm :class "px-2"
                    :aria-label (t :ui/refresh) :title (t :ui/refresh)
                    :disabled (= :loading (:status result))
                    :focusable-when-disabled true
                    :data-literature-unregistered-refresh true
                    :on-click #(load-page! nil)}
                   (ui/icon "refresh" {:size 16}))]
     (case (if (and (= :loading (:status result))
                    (seq (:assets result))
                    (not restoring?)
                    (= (:projection-context result) (db-subs/projection-context)))
             :ok (:status result))
       :loading [:div.flex.justify-center.py-8 {:role "status" :aria-label (t :ui/loading)}
                 (ui/icon "loader-2" {:size 20 :class "animate-spin opacity-50"})]
       :ok
       [:div.flex.flex-col
        (if (seq (:assets result))
          (for [{asset-uuid :block/uuid title :block/title
                 kept? :logseq.property.asset/literature-auto-excluded?} (:assets result)
                :let [review (get local-reviews (str asset-uuid))
                      row-title (or (when review (literature-handler/review-title review)) title)
                      status-key (:i18n-key (pdf-review-status review kept?))]]
            [:div.flex.items-center.border-b {:key (str asset-uuid)}
             (shui/button
              {:variant :ghost
               :class "flex-1 min-w-0 h-auto min-h-12 justify-start gap-3 whitespace-normal px-3 py-3 text-left"
               :data-literature-unregistered-pdf (str asset-uuid)
               :data-literature-pending-pdf (when review (str asset-uuid))
               :on-click (fn []
                           (when (and (= repo (state/get-current-repo))
                                      (not (state/get-state :db/restoring?)))
                             (open-review! repo asset-uuid)))}
              (ui/icon "file-type-pdf" {:size 20 :class "shrink-0 opacity-60"})
              [:span.flex.flex-1.min-w-0.flex-col.items-start.gap-1
               [:span.w-full.truncate {:title row-title}
                (if (string/blank? row-title) (t :ui/untitled) row-title)]
               (when status-key [:span.text-xs.font-normal.opacity-60 (t status-key)])]
              (ui/icon "chevron-right" {:size 16 :class "shrink-0 opacity-40"}))
             (shui/button
              {:variant :ghost :size :sm :class "shrink-0 px-2"
               :aria-label (t :literature.library/open-pdf-action)
               :title (t :literature.library/open-pdf-action)
               :data-literature-pdf-preview (str asset-uuid)
               :on-click (fn []
                           (p/let [asset (db-async/<get-block repo asset-uuid {:children? false})]
                             (when (= repo (state/get-current-repo))
                               (state/pub-event! [:asset/show-preview asset]))))}
              (ui/icon "eye" {:size 18}))])
          [:div.flex.flex-col.items-center.gap-3.py-10.opacity-60
           (ui/icon "file-type-pdf" {:size 28})
           [:span.text-sm (t :literature/unregistered-empty)]])
        (when-let [after (:next-cursor result)]
          [:div.flex.justify-end.mt-3
           (shui/button {:variant :ghost :data-literature-unregistered-next true
                         :on-click #(load-page! after)}
                        (t :literature/unregistered-next-page)
                        (ui/icon "chevron-right" {:size 16}))])]
       [:div.text-sm.text-red-600.py-4 (t :literature/unregistered-error)])]))

(declare open-source-review!)

(defn- history-kind-label
  [kind]
  (t (case kind
       :asset-owner :literature.history/asset-owner-label
       :checksum :literature.history/checksum-label
       :source-owner :literature.history/source-owner-label)))

(defn- history-paper-choice
  [repo kind member {paper-uuid :block/uuid title :block/title} current?]
  (shui/button
   {:key (str paper-uuid)
    :variant :outline
    :class "h-auto justify-between gap-3 whitespace-normal text-left"
    :data-literature-history-owner (str paper-uuid)
    :data-literature-history-source-key (some-> (:key-uuid member) str)
    :on-click (fn []
                (when (current?)
                  (if (= :source-owner kind)
                    (open-source-review! repo paper-uuid)
                    (route-handler/redirect-to-page! paper-uuid))))}
   [:span (if (string/blank? title) (t :ui/untitled) title)]
   (when (= :source-owner kind)
     [:span.shrink-0 (t :literature.source/entry-action)])))

(hsx/defc history-conflict-group
  [repo kind result current? refresh!]
  (let [members (:members result)
        [working? set-working!] (hooks/use-state false)
        [keep-uuid set-keep!] (hooks/use-state
                               (when (= :checksum kind)
                                 (:block/uuid
                                  (first (sort-by (juxt (comp - :annotation-count)
                                                       (comp - :reference-count)) members)))))
        owner-conflict? (> (count (set (mapcat #(map :block/uuid (:papers %)) members))) 1)
        can-merge? (and keep-uuid (not owner-conflict?)
                        (every? #(or (= keep-uuid (:block/uuid %))
                                     (= :ready (:status %))) members))]
    (if (= :checksum kind)
      [:div.flex.flex-col
       [:div.divide-y
        (for [member members
              :let [asset-uuid (:block/uuid member)]]
          [:div.flex.items-start.justify-between.gap-3.py-3
           {:key (str asset-uuid) :data-literature-history-member (str asset-uuid)}
           [:label.flex.items-start.gap-3.min-w-0.flex-1.cursor-pointer
            [:input.mt-1 {:type "radio" :name (str "duplicate-" (:block/uuid (first members)))
                          :checked (= keep-uuid asset-uuid) :disabled working?
                          :data-literature-history-select (str asset-uuid)
                          :aria-label (t :asset.duplicate-pdf/primary-copy)
                          :on-change #(set-keep! asset-uuid)}]
            [:span.min-w-0
             [:span.text-sm.font-medium.break-words (:block/title member)]
             (when (= keep-uuid asset-uuid)
               [:span.ml-2.text-xs.opacity-70 (t :asset.duplicate-pdf/primary-copy)])
             (when-let [aliases (seq (keep :block/title (:block/alias member)))]
               [:span.block.text-xs.opacity-60.break-words (string/join " · " aliases)])
             [:span.block.text-xs.opacity-60
              (t :asset.duplicate-pdf/usage
                 (i18n/locale-format-number (:annotation-count member))
                 (i18n/locale-format-number (:reference-count member)))]]]
           (shui/button
            {:size :sm :variant :ghost
             :data-literature-pdf-preview (str asset-uuid)
             :aria-label (t :literature.library/open-pdf-action)
             :title (t :literature.library/open-pdf-action)
             :on-click #(when (current?)
                          (p/let [asset (db-async/<get-block repo asset-uuid {:children? false})]
                            (when (current?) (state/pub-event! [:asset/show-preview asset]))))}
            (ui/icon "eye" {:size 16}))])]
       (if owner-conflict?
         [:div.text-xs.opacity-70.py-2
          (t :literature.history/different-papers)
          (for [member members paper (:papers member)]
            (history-paper-choice repo :asset-owner member paper current?))]
         [:div.flex.justify-end.border-t.pt-3
          (shui/button
           {:size :sm :data-literature-history-merge true
            :disabled (or working? (not can-merge?))
            :on-click #(when (and (current?) (not working?) can-merge?)
                         (set-working! true)
                         (-> (editor-assets-handler/resolve-duplicate-pdf-choice!
                              repo members keep-uuid refresh! current? current?)
                             (p/finally (fn [] (set-working! false)))))}
           (ui/icon "git-merge" {:size 16})
           (t :asset.duplicate-pdf/merge-action))])]
      [:div.flex.flex-col.gap-3.max-h-72.overflow-y-auto
       (for [member members]
         (let [asset-uuid (:block/uuid member)]
           [:div.flex.flex-col.gap-2.border-t.pt-2
            {:key (str (if (= :source-owner kind) (:key-uuid member) asset-uuid))
             :data-literature-history-member
             (str (if (= :source-owner kind) (:key-uuid member) asset-uuid))}
            (when-not (= :source-owner kind)
              [:div.flex.items-center.justify-between.gap-2
               [:span.text-sm (:block/title member)]
               (shui/button
                {:variant :ghost :size :sm
                 :data-literature-history-review (str asset-uuid)
                 :on-click #(when (current?) (open-review! repo asset-uuid))}
                (t :asset.literature/review-action))])
            (for [paper (:papers member)]
              (history-paper-choice repo kind member paper current?))]))])))

(defn- history-conflict-toolbar
  [kind reading? set-kind! graph-current? *request-id set-page! set-detail! load-page!]
  [:div.flex.items-center.justify-between.gap-3
   (if (= :checksum kind)
     [:span.text-sm.opacity-60 (t :literature.history/checksum-label)]
     (shui/button {:variant :ghost :size :sm
                   :on-click #(when-not reading? (set-kind! :checksum))}
                  (ui/icon "arrow-left" {:size 16})
                  (t :literature.history/checksum-label)))
   [:div.flex.items-center.gap-1
    (shui/dropdown-menu
     (shui/dropdown-menu-trigger {:as-child true}
       (shui/button {:variant :ghost :size :sm :disabled reading?
                     :data-literature-history-check true}
                    (t :literature.history/check-associations)
                    (ui/icon "chevron-down" {:size 14})))
     (shui/dropdown-menu-content
      (for [option [:asset-owner :source-owner]]
        (shui/dropdown-menu-item
         {:key (name option) :data-literature-history-kind (name option)
          :on-click #(when (graph-current?)
                       (swap! *request-id inc)
                       (set-kind! option)
                       (set-page! {:status :idle})
                       (set-detail! nil))}
         (history-kind-label option)))))
    (shui/button
     {:variant :ghost :size :sm :class "px-2"
      :data-literature-history-refresh true
      :aria-label (t :ui/refresh) :title (t :ui/refresh)
      :disabled (or reading? (not (graph-current?)))
      :on-click #(load-page! nil)}
     (ui/icon "refresh" {:size 16}))]])

(defn- history-conflict-results
  [page-status page detail *request-id set-detail! read-detail! kind detail-status
   context-current? request-current? repo load-page! reading?]
  (case (if (and (= :loading page-status) (seq (:entries page))) :ok page-status)
    :idle nil
    :loading [:div.flex.justify-center.py-8 {:role "status" :aria-label (t :ui/loading)}
              (ui/icon "loader-2" {:size 20 :class "animate-spin opacity-50"})]
    :ok
    [:div.flex.flex-col.gap-3
     (if (seq (:entries page))
       (for [{group-identity :identity :keys [title count] :as entry} (:entries page)
             :let [expanded? (= group-identity (:identity detail))]]
         [:div.rounded-lg.border
          {:key (str group-identity) :data-literature-history-card (str group-identity)}
          (shui/button
           {:variant :ghost :class "h-auto w-full justify-between gap-3 whitespace-normal px-4 py-3 text-left"
            :data-literature-history-entry (str group-identity)
            :style {:justifyContent "space-between" :whiteSpace "normal"}
            :aria-expanded expanded?
            :disabled (= :loading page-status)
            :on-click #(if expanded?
                         (do (swap! *request-id inc) (set-detail! nil))
                         (read-detail! entry))}
           [:span.flex.min-w-0.flex-col.items-start.gap-1
            [:span.break-words (if (= kind :source-owner)
                                (history-kind-label kind)
                                (if (string/blank? title) (t :ui/untitled) title))]
            (when (= :checksum kind)
              [:span.text-xs.font-normal.opacity-60 (t :literature.history/copies-count count)])]
           (ui/icon (if expanded? "chevron-up" "chevron-down") {:size 16 :class "shrink-0 opacity-50"}))
          (when expanded?
            [:div.border-t.px-4
             (case detail-status
               :loading [:div.py-4.text-sm.opacity-60 (t :ui/loading)]
               :ok (let [current? #(and (context-current? (:projection-context detail))
                                       (request-current? (:request-id detail)))]
                     (history-conflict-group repo kind detail current?
                                             #(when (current?) (load-page! nil))))
               [:div.py-4.text-sm.text-red-600 (t :literature.history/detail-error)])])])
       [:div.flex.flex-col.items-center.gap-3.py-10.opacity-60
        (ui/icon "circle-check" {:size 28})
        [:span.text-sm (t :literature.history/page-empty)]])
     (when-let [cursor (:next-cursor page)]
       (shui/button {:variant :outline :data-literature-history-next true
                     :disabled reading? :on-click #(load-page! cursor)}
                    (t :literature.history/next-action)))]
    [:div.text-sm.text-red-600 (t :literature.history/load-error)]))

(hsx/defc history-conflicts
  [repo]
  (let [[kind set-kind!] (hooks/use-state :checksum)
        [page set-page!] (hooks/use-state {:status :idle})
        [detail set-detail!] (hooks/use-state nil)
        *active? (hooks/use-memo #(atom true) [])
        *request-id (hooks/use-memo #(atom 0) [])
        current-repo (rfx/use-sub [:git/current-repo])
        restoring? (rfx/use-sub [:db/restoring?])
        dialog-current? #(deref *active?)
        graph-current? #(and (dialog-current?)
                             (= repo (state/get-current-repo))
                             (not (state/get-state :db/restoring?)))
        context-current? (fn [context]
                           (and (graph-current?)
                                (= repo (:graph-id context))
                                (= context (db-subs/projection-context))))
        request-current? (fn [request-id]
                           (and (dialog-current?) (= request-id @*request-id)))
        page-status (if (and (= repo current-repo) (not restoring?)
                             (or (nil? (:projection-context page))
                                 (and (= kind (:kind page))
                                      (context-current? (:projection-context page)))))
                      (:status page) :unavailable)
        detail-status (when detail
                        (if (and (= kind (:kind detail))
                                 (context-current? (:projection-context detail)))
                          (:status detail) :unavailable))
        reading? (or (= :loading page-status) (= :loading detail-status))
        load-page!
        (fn [cursor]
          (when (and kind (graph-current?))
            (let [request-id (swap! *request-id inc)
                  context (db-subs/projection-context)]
              (set-page! #(assoc % :status :loading :kind kind :projection-context context))
              (set-detail! nil)
              (-> (literature-handler/<history-conflicts! repo kind cursor)
                  (p/then (fn [result]
                            (when (request-current? request-id)
                              (set-page! (if (context-current? context)
                                           result {:status :unavailable})))))
                  (p/catch (fn [error]
                             (log/error :literature-history-read-failed error)
                             (when (request-current? request-id)
                               (set-page! {:status :unavailable}))))))))
        read-detail!
        (fn [entry]
          (when (and (= kind (:kind page))
                     (context-current? (:projection-context page)))
            (let [request-id (swap! *request-id inc)
                  context (:projection-context page)]
              (set-detail! {:status :loading :kind kind :identity (:identity entry) :projection-context context})
              (-> (literature-handler/<history-conflict-group! repo kind entry)
                  (p/then (fn [result]
                            (when (request-current? request-id)
                              (set-detail! (if (context-current? context)
                                             (assoc result :request-id request-id :identity (:identity entry))
                                             {:status :unavailable})))))
                  (p/catch (fn [error]
                             (log/error :literature-history-detail-failed error)
                             (when (request-current? request-id)
                               (set-detail! {:status :unavailable}))))))))]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (fn []
         (reset! *active? false)
         (swap! *request-id inc)))
     [repo])
    (hooks/use-effect! (fn [] (when kind (load-page! nil)) nil) [repo kind])
    [:div.flex.flex-col.gap-3
     (history-conflict-toolbar kind reading? set-kind! graph-current? *request-id set-page! set-detail! load-page!)
     (when-not (= :checksum kind)
       [:h3.text-sm.font-medium (history-kind-label kind)])
     (history-conflict-results page-status page detail *request-id set-detail! read-detail! kind detail-status context-current? request-current? repo load-page! reading?)]))

(declare settings-section open-zotero-review! item-actions)

(hsx/defc manual-paper-dialog
  [repo doi? dialog-close]
  (let [context (hooks/use-memo db-subs/projection-context [])
        [title set-title!] (hooks/use-state "")
        [doi set-doi!] (hooks/use-state "")
        [record set-record!] (hooks/use-state nil)
        [destination set-destination!] (hooks/use-state nil)
        [working? set-working!] (hooks/use-state false)
        [error-message set-error!] (hooks/use-state nil)
        current? #(let [dialog (second (shui/dialog-get :literature-manual))]
                    (and (= repo (state/get-current-repo))
                         (= context (db-subs/projection-context))
                         (:open? dialog)
                         (identical? dialog-close (:close dialog))))
        create! (fn []
                  (set-working! true)
                  (set-error! nil)
                  (-> (p/let [target (or destination (literature-handler/<default-destination! repo))
                              result (when (current?)
                                       (if doi?
                                         (literature-handler/<add-doi-paper!
                                          repo (:block/uuid target) (assoc record :title title))
                                         (literature-handler/<create-manual-paper!
                                          repo (:block/uuid target) title)))]
                        (when (current?)
                          (if (contains? #{:created :existing} (:status result))
                            (do (shui/dialog-close! :literature-manual)
                                (when (= :existing (:status result))
                                  (notification/show! (t :literature.doi/existing) :info))
                                (route-handler/redirect-to-page! (:paper-uuid result)))
                            (set-error! (t (if (= :identifier-conflict (:reason result))
                                             :literature.doi/conflict :literature/create-error))))))
                      (p/catch (fn [error]
                                 (log/error :literature-manual-create-failed error)
                                 (when (current?) (set-error! (t :literature/create-error)))))
                      (p/finally #(when (current?) (set-working! false)))))]
    [:div.flex.flex-col.gap-4.min-w-0 {:on-key-down native-control-key-down}
     (when doi?
       (doi-lookup-input repo doi set-doi!
                         #(do (set-record! nil) (set-error! nil))
                         #(do (set-record! %) (set-title! (:title %)) (set-doi! (:doi %)))
                         current? working? set-working!))
     (when (or (not doi?) record)
       [:<>
        [:label.flex.flex-col.gap-2
         [:span.text-sm.font-medium (t :asset.literature/title-label)]
         (shui/input {:auto-focus (not doi?) :value title :disabled working?
                      :data-literature-field "manual-title"
                      :on-change #(set-title! (util/evalue %))})]
        (when record
          [:div.flex.items-baseline.gap-2.text-sm.text-muted-foreground.min-w-0
           [:span.shrink-0 (:date record)]
           [:span.flex-1.min-w-0.truncate {:title (:authors record)} (:authors record)]])
        (shui/button
         {:variant :outline :disabled working?
          :data-literature-library-destination true
          :on-click (fn [event]
                      (let [trigger (.-currentTarget event)]
                        (shui/dialog-open!
                         (fn [_]
                           (destination-picker
                            repo
                            (fn [page]
                              (set-destination! page)
                              (shui/dialog-close! :literature-destination))))
                         {:id :literature-destination
                          :title (t :literature.library/destination-title)
                          :content-props {:finalFocus (constantly trigger)}
                          :class "lg:max-w-xl"})))}
         (t :literature/destination-label
            (or (:block/title destination) (t :literature/default-page-title))))])
     (when error-message [:div.text-sm.text-red-600 {:role "alert"} error-message])
     [:div.flex.justify-end.gap-2
      (shui/button {:variant :ghost
                    :on-click #(shui/dialog-close! :literature-manual)} (t :ui/cancel))
      (shui/button {:disabled (or working? (string/blank? title) (and doi? (nil? record)))
                    :data-literature-library-create true :on-click create!}
                   (t :literature.library/add-action))]]))

(defn- open-manual-paper!
  [repo final-focus & [doi?]]
  (shui/dialog-open! (fn [{:keys [close]}] (manual-paper-dialog repo doi? close))
                     {:id :literature-manual
                      :title (t (if doi? :literature.doi/add-title :literature.library/manual-action))
                      :class "grid-cols-1"
                      :content-props {:finalFocus final-focus}}))

(hsx/defc local-options-dialog
  [repo on-close]
  (hooks/use-effect! (fn [] #(when (= repo (state/get-current-repo)) (on-close))) [repo])
  (settings-section repo))

(defn- open-local-options!
  [repo on-close final-focus]
  (shui/dialog-open! (fn [_] (local-options-dialog repo on-close))
                     {:id :literature-options :title (t :literature.library/local-options-action)
                      :content-props {:finalFocus final-focus}
                      :class "lg:max-w-xl"}))

(defn- <review-added-pdf!
  [repo blocks]
  (let [block (first blocks)
        asset-uuid (or (get-in block [:block/link :block/uuid]) (:block/uuid block))]
    (p/let [prepared (literature-handler/<prepare-review! repo asset-uuid)]
      (when (= repo (state/get-current-repo))
        (if (= :linked (:status prepared))
          (do (notification/show! (t :asset.literature/already-linked) :success)
              (route-handler/redirect-to-page! (get-in prepared [:paper :block/uuid])))
          (p/do!
           (when (= :ready (:status prepared))
             (literature-handler/<retry-review-candidate! repo asset-uuid))
           (when (= repo (state/get-current-repo)) (open-review! repo asset-uuid))))))))

(defn- paper-row
  [repo paper]
  (let [{paper-uuid :block/uuid title :block/title detail :literature/detail} paper]
    [:div.flex.items-start.justify-between.gap-3.border-b.py-3
     {:key (str paper-uuid) :data-literature-library-paper (str paper-uuid)}
     [:div.min-w-0.flex-1
      [:a.block.font-medium.break-words
       {:href (rfe/href :page {:name paper-uuid})
        :on-click (fn [event] (util/stop event) (route-handler/redirect-to-page! paper-uuid))}
       (if (string/blank? title) (t :ui/untitled) title)]
      (when-not (string/blank? detail) [:div.text-sm.opacity-60.truncate detail])]
     (shui/button {:variant :ghost :size :sm :class "shrink-0"
                   :data-literature-copy (str paper-uuid)
                   :on-click #(-> (editor-handler/copy-block-ref! paper-uuid ref/->block-ref)
                                  (p/then (fn [copied?]
                                            (notification/show!
                                             (t (if copied? :notification/copied :literature.library/copy-error))
                                             (if copied? :success :error))))
                                  (p/catch (fn [_]
                                             (notification/show! (t :literature.library/copy-error) :error))))}
                  (t :literature.library/copy-reference-action))
     (item-actions repo paper-uuid)]))

(defn- library-row
  [[eid paper-uuid title metadata]]
  {:db/id eid :block/uuid paper-uuid :block/title title
   :literature/detail
   (->> [:logseq.property/literature-authors :logseq.property/literature-date
         :logseq.property/literature-doi :logseq.property/literature-arxiv]
        (keep #(get-in metadata [% :block/title]))
        (remove string/blank?) (string/join " · "))})

(defn- library-pdf-change-handler
  [set-operation! repo]
  (fn [event]
    (when-let [file (some-> event .-target .-files (aget 0))]
      (set! (.. event -target -value) "")
      (set-operation! :saving)
      (-> (p/let [target (literature-handler/<default-destination! repo)]
            (when (= repo (state/get-current-repo))
              (editor-assets-handler/db-based-save-assets!
               repo [file] :target-block target :reuse-existing-pdf? true
               :literature-action? true :auto-identify-literature? false
               :on-assets-saved #(<review-added-pdf! repo %))))
          (p/catch (fn [error]
                     (log/error :literature-library-pdf-failed error)
                     (when (= repo (state/get-current-repo))
                       (notification/show! (t :literature.library/pdf-save-error) :error))))
          (p/finally #(set-operation! nil))))))

(defn- library-toolbar
  [*file save-pdf! operation *add-trigger repo local-options!]
  [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-5
   [:h1.text-2xl.font-semibold (t :nav/literature)]
   [:div.flex.items-center.gap-2
    [:input.hidden {:type "file" :accept ".pdf,application/pdf" :ref *file :on-change save-pdf!}]
    (shui/dropdown-menu
     (shui/dropdown-menu-trigger {:as-child true}
      (shui/button {:variant :outline :disabled (some? operation) :ref *add-trigger
                    :data-literature-add true}
                   (ui/icon "plus" {:size 16})
                   (t :literature.library/add-action)
                   (ui/icon "chevron-down" {:size 14})))
     (shui/dropdown-menu-content
      (when (util/electron?)
        (shui/dropdown-menu-item {:data-literature-action "add-by-doi"
                                  :on-click #(open-manual-paper! repo *add-trigger true)}
                                 (t :literature.doi/add-action)))
      (shui/dropdown-menu-item {:on-click #(some-> (hooks/deref *file) .click)}
                               (t :literature.library/pdf-action))
      (when (util/electron?)
        (shui/dropdown-menu-item {:on-click #(local-options! *add-trigger)} (t :literature.library/zotero-action)))
      (shui/dropdown-menu-item {:on-click #(open-manual-paper! repo *add-trigger)}
                               (t :literature.library/manual-action))))
    (shui/button {:variant :ghost :size :sm
                  :on-click #(local-options! (constantly (.-currentTarget %)))
                  :data-literature-local-options true}
                 (ui/icon "settings" {:size 16})
                 (t :literature.library/local-options-action))]])

(defn- library-tab-content
  [tab binding local-settings repo query set-composing! operation searching? search-result
   window-snapshot rows-snapshot window rows change-tab! page-index set-before! set-page-index!
   eids]
  (case tab
    "pending"
    [:div.flex.flex-col.gap-3
     (when (and binding (:enabled? local-settings))
       (shui/button {:variant :outline :on-click #(open-zotero-review! repo binding)}
                    (t :literature.source/review-zotero-action)))
     (unregistered-pdfs repo)]
    "duplicates" (history-conflicts repo)
    [:div.flex.flex-col.gap-3
     (shui/input {:value query :aria-label (t :literature.library/search-placeholder)
                  :placeholder (t :literature.library/search-placeholder)
                  :data-literature-library-search true
                  :on-composition-start #(set-composing! true)
                  :on-composition-end #(set-composing! false)
                  :on-change #(route-handler/redirect!
                               {:to :literature :push false
                                :query-params {:tab "all" :q (util/evalue %)}})})
     (when (some? operation) [:div.text-sm.opacity-60 (t :settings.literature/working)])
     (if searching?
       (case (:status search-result)
         (:ok :loading :idle)
         [:div {:aria-busy (not= :ok (:status search-result))}
          (if (seq (:papers search-result))
            (map #(paper-row repo %) (:papers search-result))
            [:p.text-sm.opacity-60
             (t (if (= :ok (:status search-result))
                  :asset.literature/search-empty :asset.literature/searching))])
          (when (or (seq (:papers search-result)) (= :ok (:status search-result)))
            [:p.text-xs.opacity-60 (t :literature.library/search-limit-description)])]
         [:p.text-sm.text-red-600 (t :asset.literature/search-unavailable)])
       (cond
         (or (= :error (:status window-snapshot)) (get-in window-snapshot [:value :error])
             (= :error (:status rows-snapshot)) (get-in rows-snapshot [:value :error]))
         [:p.text-sm.text-red-600 (t :literature.library/load-error)]
         (or (not= :ready (:status window-snapshot))
             (and (seq window) (not= :ready (:status rows-snapshot))))
         [:p.text-sm.opacity-60 (t :ui/loading)]
         :else
         [:div
          (when (= 1 (count (string/trim query)))
            [:p.text-sm.opacity-60 (t :asset.literature/search-more-characters)])
          (map #(paper-row repo %) rows)
          (when (empty? rows)
            [:div.flex.flex-col.items-center.gap-3.py-10
             (ui/icon "books" {:size 32 :class "opacity-40"})
             [:p.text-sm.opacity-60 (t :literature.library/empty-description)]
             (shui/button {:variant :outline :size :sm :data-literature-open-pdf-inbox true
                           :on-click #(change-tab! "pending")}
                          (ui/icon "file-type-pdf" {:size 16})
                          (t :literature.library/open-pdf-inbox-action))])
          [:div.flex.justify-end.gap-2.mt-4
           (when (pos? page-index)
             (shui/button {:variant :ghost :size :sm
                           :on-click #(do (set-before! nil) (set-page-index! 0))}
                          (t :literature.library/first-page-action)))
           (when (> (count eids) 50)
             (shui/button {:variant :outline :size :sm :data-literature-library-next true
                           :on-click #(do (set-before! (peek window)) (set-page-index! inc))}
                          (t :literature/pending-next-page)))]]))]))

(hsx/defc library-content
  [repo route-match]
  (let [_language (rfx/use-sub [:preferred-language])
        params (:query-params route-match)
        tab (get params :tab "all")
        query (get params :q "")
        [composing? set-composing!] (hooks/use-state false)
        [before set-before!] (hooks/use-state nil)
        [page-index set-page-index!] (hooks/use-state 0)
        [operation set-operation!] (hooks/use-state nil)
        [_settings-version bump-settings!] (hooks/use-state 0)
        *file (hooks/use-ref nil)
        *add-trigger (hooks/use-ref nil)
        debounced-query (hooks/use-debounced-value query 200)
        searching? (>= (count (string/trim query)) 2)
        [search-result set-search-result!] (hooks/use-state (empty-search-result :idle))
        index-build (rfx/use-sub [:search/index-build])
        context (db-subs/projection-context)
        [page-context set-page-context!] (hooks/use-state context)
        browse? (and (= tab "all") (not searching?) (= context page-context))
        window-snapshot (db-hooks/use-resource-snapshot
                         (when browse?
                           [:query (literature-handler/library-query-spec
                                    literature-handler/library-window-query
                                    [(or before js/Number.MAX_SAFE_INTEGER)])]))
        eids (literature-handler/library-window (get-in window-snapshot [:value :rows]))
        window (vec (take 50 eids))
        rows-snapshot (db-hooks/use-resource-snapshot
                       (when (and browse? (seq window))
                         [:query (literature-handler/library-query-spec
                                  literature-handler/library-rows-query [window])]))
        rows (->> (get-in rows-snapshot [:value :rows]) (sort-by first >) (mapv library-row))
        import-state (rfx/use-sub [:literature/import-state repo])
        local-settings (literature-handler/load-settings repo)
        binding (:zotero-binding local-settings)
        change-tab! #(route-handler/redirect! {:to :literature :query-params {:tab %}})
        local-options! #(open-local-options! repo (fn [] (bump-settings! inc)) %)
        save-pdf! (library-pdf-change-handler set-operation! repo)]
    (hooks/use-effect!
     (fn []
       (set-before! nil) (set-page-index! 0) (set-page-context! context)
       nil)
     [repo tab context])
    (hooks/use-effect!
     (fn []
       (let [active? (atom true)
             q (string/trim debounced-query)]
         (cond
           composing? nil
           (or (not= tab "all") (< (count q) 2))
           (set-search-result! (empty-search-result :idle))
           :else
           (do (set-search-result! #(assoc % :status :loading))
               (-> (literature-handler/<search-existing-papers! repo q)
                   (p/then #(when @active? (set-search-result! %)))
                   (p/catch (fn [error]
                              (log/error :literature-library-search-failed error)
                              (when @active? (set-search-result! (empty-search-result :unavailable))))))))
         #(reset! active? false)))
     [repo tab debounced-query composing? (:build-id index-build)])
    [:div.ls-literature.w-full.max-w-5xl.mx-auto
     {:on-key-down native-control-key-down}
     (library-toolbar *file save-pdf! operation *add-trigger repo local-options!)
     (when binding
       [:button.text-sm.opacity-70.mb-4
        {:on-click #(local-options! (constantly (.-currentTarget %))) :data-literature-import-summary true}
        (t (cond
             (not (:enabled? local-settings)) :literature.library/zotero-paused-status
             (or (= :failed (:status import-state)) (:notifier-error? import-state))
             :literature.library/zotero-error-status
             (= :working (:status import-state)) :literature.library/zotero-working-status
             :else :literature.library/zotero-configured-status))
        (when-let [counts (:counts import-state)]
          [:span.block.text-xs.mt-1
           (t :literature.library/import-summary
              (get counts :created 0)
              (+ (get counts :linked 0) (get counts :already-imported 0) (get counts :already-linked 0))
              (get counts :manual 0) (get counts :blocked 0))])])
     (when (and binding (:enabled? local-settings) (pos? (get-in import-state [:counts :manual] 0)))
       (shui/button {:variant :outline :size :sm :class "mb-4"
                     :on-click #(open-zotero-review! repo binding)}
                    (t :literature.source/review-zotero-action)))
     [:div.flex.flex-wrap.gap-1.mb-4 {:role "tablist" :aria-label (t :nav/literature)}
      (for [[id label icon] [["all" (t :literature.library/all-tab) "books"]
                             ["pending" (t :literature.library/pending-tab) "file-type-pdf"]
                             ["duplicates" (t :literature.library/duplicates-tab) "copy"]]]
        (shui/button {:key id :role "tab" :aria-selected (= tab id)
                      :variant (if (= tab id) :default :ghost) :size :sm
                      :data-literature-tab id :on-click #(change-tab! id)}
                     (ui/icon icon {:size 16}) label))]
     (pdf-organization repo (= tab "pending"))
     (library-tab-content tab binding local-settings repo query set-composing! operation searching? search-result window-snapshot rows-snapshot window rows change-tab! page-index set-before! set-page-index! eids)]))

(hsx/defc library-page
  [route-match]
  (let [repo (rfx/use-sub [:git/current-repo])]
    (when repo
      ^{:key repo} [library-content repo route-match])))

(defn open-library!
  [_repo]
  (state/close-settings!)
  (route-handler/redirect! {:to :literature}))

(defn ^:export open-library-current!
  []
  (when-let [repo (state/get-current-repo)] (open-library! repo)))

(def ^:private accepted-metadata-change-keys
  [:field :kind :current :source])

(defn- metadata-field-label
  [field]
  (t (case field
       :title :literature.metadata/title
       :authors :literature.metadata/authors
       :date :literature.metadata/date
       :publication :literature.metadata/publication
       :type :literature.metadata/type
       :doi :literature.metadata/doi
       :arxiv :literature.metadata/arxiv
       :url :literature.metadata/url
       :citekey :literature.metadata/citekey
       :literature.metadata/unknown)))

(defn- metadata-value-label
  [value]
  (if (nil? value)
    (t :literature.metadata/missing)
    (str value)))


(defn- doi-review-plan
  [repo current-repo plan asset-uuid working? set-asset!]
  (when (= repo current-repo)
    (case (:status plan)
      :manual
      [:dl.grid.grid-cols-1.gap-3.overflow-y-auto {:class "max-h-[45vh]"}
       (for [{:keys [field current source]} (:changes plan)]
         [:div {:key (name field) :data-literature-doi-change (name field)}
          [:dt.text-sm.font-medium (metadata-field-label field)]
          [:dd.text-sm.break-words
           [:span.text-muted-foreground (metadata-value-label current)]
           [:span.mx-2 "→"]
           [:span (metadata-value-label source)]]])]
      :existing
      [:div.flex.flex-col.gap-3
       [:div.text-sm.text-muted-foreground (t :literature.doi/existing)]
       (shui/button {:variant :link :class "h-auto justify-start whitespace-normal text-left p-0"
                     :on-click #(do (shui/dialog-close! :literature-review)
                                    (route-handler/redirect-to-page! (get-in plan [:paper :block/uuid])))}
                    (get-in plan [:paper :block/title]))
       (when (seq (:assets plan))
         [:div.flex.flex-col.gap-1
          [:label.text-sm {:for "literature-doi-pdf"} (t :literature.doi/select-pdf)]
          [:select.ui__input.w-full.min-w-0 {:id "literature-doi-pdf" :data-literature-field "doi-pdf"
                             :value (str asset-uuid) :disabled working?
                             :on-change #(set-asset! (let [value (util/evalue %)]
                                                      (when-not (string/blank? value) (uuid value))))}
           [:option {:value ""} (t :literature.doi/select-pdf)]
           (for [asset (:assets plan)]
             [:option {:key (str (:block/uuid asset)) :value (str (:block/uuid asset))}
              (:block/title asset)])]])]
      :already-imported [:div.text-sm.text-muted-foreground (t :literature.doi/up-to-date)]
      :blocked [:div.text-sm.text-red-600 {:role "alert"}
                (t (if (= :identifier-conflict (:reason plan))
                     :literature.doi/conflict :literature.doi/stale))]
      nil)))

(hsx/defc doi-review-dialog
  [repo paper-uuid dialog-close]
  (let [current-repo (rfx/use-sub [:git/current-repo])
        context (hooks/use-memo db-subs/projection-context [])
        [doi set-doi!] (hooks/use-state "")
        [record set-record!] (hooks/use-state nil)
        [plan set-plan!] (hooks/use-state nil)
        [asset-uuid set-asset!] (hooks/use-state nil)
        [working? set-working!] (hooks/use-state true)
        [error-message set-error!] (hooks/use-state nil)
        current? #(let [dialog (second (shui/dialog-get :literature-review))]
                    (and (= repo (state/get-current-repo))
                         (= context (db-subs/projection-context))
                         (:open? dialog)
                         (identical? dialog-close (:close dialog))))
        read-record! (fn [record]
                       (p/let [plan (literature-handler/<plan-doi-metadata! repo paper-uuid record nil)]
                         (when (current?)
                           (set-record! record)
                           (set-plan! plan)
                           (set-asset! (when (= 1 (count (:assets plan)))
                                         (:block/uuid (first (:assets plan))))))))
        apply! (fn []
                 (when (and (current?) (not working?))
                   (set-working! true)
                   (set-error! nil)
                   (let [changes (if (= :existing (:status plan))
                                   [{:field :assets :kind :different-value
                                     :current paper-uuid :source (get-in plan [:paper :block/uuid])}]
                                   (mapv #(select-keys % accepted-metadata-change-keys) (:changes plan)))]
                     (-> (literature-handler/<apply-doi-metadata! repo paper-uuid record changes asset-uuid)
                         (p/then (fn [result]
                                   (when (current?)
                                     (if (= :updated (:status result))
                                       (do
                                         (shui/dialog-close! :literature-review)
                                         (notification/show! (t :literature.doi/updated) :success)
                                         (when (= :reassign (:action result))
                                           (route-handler/redirect-to-page! (:paper-uuid result))))
                                       (set-error! (t (if (= :metadata-plan-stale (:reason result))
                                                       :literature.doi/stale
                                                       :literature.doi/apply-error)))))))
                         (p/catch (fn [error]
                                    (log/error :literature-doi-apply-failed error)
                                    (when (current?) (set-error! (t :literature.doi/apply-error)))))
                         (p/finally #(when (current?) (set-working! false)))))))]
    (hooks/use-effect!
     (fn []
       (-> (db-async/<get-block repo paper-uuid {:children? false})
           (p/then (fn [paper]
                     (when (current?)
                       (set-doi! #(if (string/blank? %)
                                    (or (get-in paper [:logseq.property/literature-doi :block/title]) "")
                                    %)))))
           (p/catch (fn [error]
                      (log/error :literature-doi-read-failed error)
                      (when (current?) (set-error! (t :literature.doi/stale)))))
           (p/finally #(when (current?) (set-working! false))))
       js/undefined)
     [repo paper-uuid])
    [:div.flex.flex-col.gap-4 {:on-key-down native-control-key-down}
     (doi-lookup-input repo doi set-doi!
                       #(do (set-record! nil) (set-plan! nil) (set-asset! nil) (set-error! nil))
                       read-record! current? working? set-working!)
     (doi-review-plan repo current-repo plan asset-uuid working? set-asset!)
     (when error-message [:div.text-sm.text-red-600 {:role "alert"} error-message])
     [:div.flex.justify-end.gap-2
      (shui/button {:variant :outline
                    :on-click #(shui/dialog-close! :literature-review)}
                   (t :ui/cancel))
      (when (or (= :manual (:status plan)) (= :existing (:status plan)))
        (shui/button {:data-literature-action "apply-doi" :on-click apply!
                      :disabled (or working? (and (= :existing (:status plan)) (nil? asset-uuid)))}
                     (t (if (= :existing (:status plan))
                          :literature.doi/reassign-action
                          :literature.doi/apply-action))))]]))

(defn open-doi-review!
  [repo paper-uuid & [final-focus]]
  (shui/dialog-open!
   (fn [{dialog-close :close}] (doi-review-dialog repo paper-uuid dialog-close))
   {:id :literature-review :title (t :literature.doi/review-title)
    :content-props (when final-focus {:finalFocus final-focus})
    :align :center :class "lg:max-w-2xl"}))

(defn- metadata-review-content
  [plan selected-fields applying? set-selected-fields! accepted-changes apply! check!]
  (case (:status plan)
    :loading
    [:div.text-sm.opacity-60 (t :literature.metadata/checking)]

    :manual
    (if (= :metadata-review (:reason plan))
      [:<>
       [:p.text-sm.opacity-70 (t :literature.metadata/review-description)]
        [:div.flex.flex-col.gap-2
         {:style {:max-height "50vh" :overflow-y "auto"}}
         (for [{:keys [field current source]} (:changes plan)]
          ^{:key (name field)}
          [:label.flex.items-start.gap-3.rounded.border.p-3.cursor-pointer
           {:data-literature-metadata-field (name field)}
           (shui/checkbox
            {:checked (contains? selected-fields field)
             :disabled applying?
             :on-checked-change
             (fn [checked?]
               (set-selected-fields!
                (if (true? checked?)
                  (conj selected-fields field)
                  (disj selected-fields field))))})
           [:span.flex.flex-col.gap-1.min-w-0
            [:span.text-sm.font-medium (metadata-field-label field)]
            [:span.text-xs.opacity-60.break-words
             (t :literature.metadata/current-value
                (metadata-value-label current))]
            [:span.text-sm.break-words
             (t :literature.metadata/zotero-value
                (metadata-value-label source))]]])]
       [:div.flex.justify-end.gap-2
        (shui/button
         {:variant :ghost
          :data-literature-action "cancel-metadata"
          :disabled applying?
          :on-click #(shui/dialog-close! :literature-metadata-review)}
         (t :ui/cancel))
        (shui/button
         {:data-literature-action "apply-metadata"
          :disabled (or applying? (empty? accepted-changes))
          :on-click apply!}
         (if applying?
           (t :settings.literature/working)
           (t :literature.metadata/apply-selected)))]]
      [:div.text-sm.text-red-600 (t :literature.metadata/unavailable)])

    :already-imported
    [:div.flex.flex-col.gap-3
     [:div.text-sm (t :literature.metadata/up-to-date)]
     [:div.flex.justify-end
      (shui/button
       {:variant :outline
        :data-literature-action "close-metadata"
        :on-click #(shui/dialog-close! :literature-metadata-review)}
       (t :ui/close))]]

    [:div.flex.flex-col.gap-3
     [:div.text-sm.text-red-600
      (if (= :identifier-metadata-conflict (:reason plan))
        (t :literature.metadata/identity-conflict)
        (t :literature.metadata/unavailable))]
     [:div.flex.justify-end.gap-2
      (shui/button
       {:variant :ghost
        :on-click #(shui/dialog-close! :literature-metadata-review)}
       (t :ui/cancel))
      (shui/button
       {:variant :outline
        :data-literature-action "recheck-metadata"
        :on-click check!}
       (t :literature.metadata/check-again))]]))

(hsx/defc metadata-review-dialog
  [repo paper-uuid]
  (let [[plan set-plan!] (hooks/use-state {:status :loading})
        [selected-fields set-selected-fields!] (hooks/use-state #{})
        [applying? set-applying!] (hooks/use-state false)
        *active? (hooks/use-memo #(atom true) [])
        check!
        (fn []
          (set-plan! {:status :loading})
          (set-selected-fields! #{})
          (-> (literature-handler/<check-zotero-metadata! repo paper-uuid)
              (p/then (fn [result]
                        (when @*active?
                          (set-plan! result))))
              (p/catch (fn [error]
                         (log/error :literature-metadata-review-failed error)
                         (when @*active?
                           (set-plan! {:status :unavailable}))))))
        accepted-changes
        (->> (:changes plan)
             (filterv #(contains? selected-fields (:field %)))
             (mapv #(select-keys % accepted-metadata-change-keys)))
        apply!
        (fn []
          (when (seq accepted-changes)
            (set-applying! true)
            (-> (literature-handler/<apply-zotero-metadata!
                 repo paper-uuid accepted-changes)
                (p/then
                 (fn [result]
                   (when @*active?
                     (set-applying! false)
                     (if (= :updated (:status result))
                       (do
                         (shui/dialog-close! :literature-metadata-review)
                         (notification/show!
                          (t :literature.metadata/updated)
                          :success))
                       (do
                         (set-selected-fields! #{})
                         (set-plan! result))))))
                (p/catch
                 (fn [error]
                   (log/error :literature-metadata-apply-failed error)
                   (when @*active?
                     (set-applying! false)
                     (set-selected-fields! #{})
                     (set-plan! {:status :unavailable})))))))]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (check!)
       #(reset! *active? false))
     [repo paper-uuid])
    [:div.flex.flex-col.gap-4.min-w-0
     (metadata-review-content plan selected-fields applying? set-selected-fields! accepted-changes apply! check!)]))

(defn open-metadata-review!
  [repo paper-uuid & [final-focus]]
  (shui/dialog-open!
   (fn [] (metadata-review-dialog repo paper-uuid))
   {:id :literature-metadata-review
    :content-props (when final-focus {:finalFocus final-focus})
    :title (t :literature.metadata/review-title)
    :align :center
    :class "lg:max-w-2xl"}))

(defkeyword :literature-source-review)

(defn- source-correction-handler
  [current? source *source-request set-working! set-error! repo paper-uuid read-sources!]
  (fn [action target-uuid]
    (when (and (current?) (:active? source))
      (let [request-id @*source-request]
        (set-working! true)
        (set-error! nil)
        (-> (literature-handler/<correct-paper-source!
             repo paper-uuid source action target-uuid)
            (p/then (fn [result]
                      (when (and (current?) (= request-id @*source-request))
                        (if (= :updated (:status result))
                          (do
                            (read-sources!)
                            (notification/show! (t :literature.source/update-success) :success))
                          (set-error! (t :literature.source/update-error))))))
            (p/catch (fn [error]
                       (log/error :literature-source-correction-failed error)
                       (when (and (current?) (= request-id @*source-request))
                         (set-error! (t :literature.source/update-error)))))
            (p/finally #(when (current?) (set-working! false))))))))

(defn- source-search-effect
  [*search-request debounced-query set-target! current? source set-search-result! repo
   paper-uuid]
  (fn []
    (let [request-id (swap! *search-request inc)
          query' (string/trim debounced-query)]
      (set-target! nil)
      (if (or (not (current?)) (nil? source) (< (count query') 2))
        (set-search-result! (empty-search-result :idle))
        (do
          (set-search-result! #(assoc % :status :loading))
          (-> (literature-handler/<search-existing-papers! repo query')
              (p/then (fn [result]
                        (when (and (current?) (= request-id @*search-request))
                          (set-search-result!
                           (update result :papers
                                   #(filterv (fn [paper] (not= paper-uuid (:block/uuid paper))) %))))))
              (p/catch (fn [error]
                         (log/error :literature-source-search-failed error)
                         (when (and (current?) (= request-id @*search-request))
                           (set-search-result! (empty-search-result :unavailable))))))))
      (fn []
        (when (= request-id @*search-request)
          (swap! *search-request inc))))))

(defn- source-review-content
  [repo current-repo restoring? current? plan source working? *search-request set-source!
   set-query! set-target! set-error! correct! query search-result target]
  (case (if (and (= repo current-repo) (not restoring?) (current?))
          (:status plan) :unavailable)
    :loading [:div.text-sm.opacity-60 (t :ui/loading)]
    :ok
    (if (seq (:sources plan))
      [:div.flex.flex-col.gap-2
       (for [{:keys [key-uuid source-key active?] :as item} (:sources plan)]
         (shui/button
          {:key (str key-uuid)
           :variant (if (= key-uuid (:key-uuid source)) :default :outline)
           :class "h-auto justify-between whitespace-normal text-left"
           :data-literature-source (str key-uuid)
           :data-literature-source-state (if active? "active" "inactive")
           :disabled (or working? (not active?))
           :on-click (fn []
                       (swap! *search-request inc)
                       (set-source! item)
                       (set-query! "")
                       (set-target! nil)
                       (set-error! nil))}
          [:span.flex.flex-col.items-start
           [:span (t :literature.source/zotero-item (last (string/split source-key #":")))]
           [:code.text-xs.font-normal.opacity-60 (str key-uuid)]]
          [:span.text-xs (t (if active?
                             :literature.source/active-status
                             :literature.source/inactive-status))]))
       (if source
         [:div.flex.flex-col.gap-3.border-t.pt-3
          (shui/button
           {:variant :outline
            :data-literature-action "unlink-source-owner"
            :disabled working?
            :on-click #(correct! :unlink-owner nil)}
           (t :literature.source/unlink-owner-action))
          (shui/button
           {:variant :outline
            :data-literature-action "unlink-source"
            :disabled working?
            :on-click #(correct! :unlink nil)}
           (t :literature.source/unlink-action))
          (shui/input
           {:data-literature-source-search true
            :placeholder (t :literature.source/search-placeholder)
            :value query
            :disabled working?
            :on-change (fn [event]
                         (swap! *search-request inc)
                         (set-query! (util/evalue event))
                         (set-target! nil))})
          (case (:status search-result)
            :idle [:div.text-xs.opacity-60 (t :literature.source/search-hint)]
            (:ok :loading)
            [:div.flex.flex-col.gap-2
             (if (seq (:papers search-result))
               (for [{target-uuid :block/uuid title :block/title detail :literature/detail}
                     (:papers search-result)]
                 (shui/button
                  {:key (str target-uuid)
                   :variant (if (= target-uuid target) :default :outline)
                   :class "h-auto justify-start whitespace-normal text-left"
                   :data-literature-source-target (str target-uuid)
                   :disabled working?
                   :on-click #(set-target! target-uuid)}
                  [:span.flex.flex-col.items-start
                   [:span (if (string/blank? title) (t :ui/untitled) title)]
                   (when detail [:span.text-xs.font-normal.opacity-60 detail])]))
               [:div.text-xs.opacity-60
                (t (if (= :loading (:status search-result)) :ui/loading :search/no-result))])
             (when (:truncated? search-result)
               [:div.text-xs.opacity-60 (t :literature.source/search-truncated)])]
            [:div.text-sm.text-red-600 (t :literature.source/search-error)])
          (shui/button
           {:data-literature-action "reassign-source"
            :disabled (or working? (nil? target))
            :on-click #(correct! :reassign target)}
           (t :literature.source/reassign-action))]
         [:div.text-sm.opacity-60 (t :literature.source/select-prompt)])]
      [:div.text-sm.opacity-60 (t :literature.source/empty)])
    [:div.text-sm.text-red-600 (t :literature.source/load-error)]))

(hsx/defc source-review-dialog
  [repo paper-uuid dialog-close]
  (let [[plan set-plan!] (hooks/use-state {:status :loading})
        [source set-source!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state "")
        [target set-target!] (hooks/use-state nil)
        [search-result set-search-result!] (hooks/use-state (empty-search-result :idle))
        [working? set-working!] (hooks/use-state false)
        [error-message set-error!] (hooks/use-state nil)
        *close-button (hooks/use-ref nil)
        *active? (hooks/use-memo #(atom true) [])
        *source-request (hooks/use-memo #(atom 0) [])
        *search-request (hooks/use-memo #(atom 0) [])
        context (hooks/use-memo #(db-subs/projection-context)
                                [repo paper-uuid dialog-close])
        current-repo (rfx/use-sub [:git/current-repo])
        restoring? (rfx/use-sub [:db/restoring?])
        dialog-current? #(let [dialog (second (shui/dialog-get :literature-source-review))]
                           (and @*active? (:open? dialog)
                                (identical? dialog-close (:close dialog))))
        current? #(and (dialog-current?)
                       (= repo (state/get-current-repo))
                       (not (state/get-state :db/restoring?))
                       (= repo (:graph-id context))
                       (= context (db-subs/projection-context)))
        debounced-query (hooks/use-debounced-value query 200)
        index-build (rfx/use-sub [:search/index-build])
        completed-build-id (when (and (= repo (:repo index-build))
                                     (= :completed (:status index-build)))
                            (:build-id index-build))
        read-sources!
        (fn []
          (when (current?)
            (let [request-id (swap! *source-request inc)]
              (swap! *search-request inc)
              (set-plan! {:status :loading})
              (set-source! nil)
              (set-query! "")
              (set-target! nil)
              (set-error! nil)
              (-> (literature-handler/<paper-sources! repo paper-uuid)
                  (p/then (fn [result]
                            (when (and (current?) (= request-id @*source-request))
                              (set-plan! result))))
                  (p/catch (fn [error]
                             (log/error :literature-source-read-failed error)
                             (when (and (current?) (= request-id @*source-request))
                               (set-plan! {:status :unavailable}))))))))
        correct!
        (source-correction-handler current? source *source-request set-working! set-error! repo paper-uuid read-sources!)]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (read-sources!)
       (fn []
         (reset! *active? false)
         (swap! *source-request inc)
         (swap! *search-request inc)))
     [repo paper-uuid dialog-close])
    (hooks/use-effect!
     (fn []
       (when (and (not working?) (current?))
         (some-> (hooks/deref *close-button) (.focus))))
     [working?])
    (hooks/use-effect!
     (source-search-effect *search-request debounced-query set-target! current? source set-search-result! repo paper-uuid)
     [repo paper-uuid debounced-query (:key-uuid source) completed-build-id])
    [:div.flex.flex-col.gap-4.min-w-0
     [:p.text-sm.opacity-70 (t :literature.source/review-desc)]
     (source-review-content repo current-repo restoring? current? plan source working? *search-request set-source! set-query! set-target! set-error! correct! query search-result target)
     (when error-message [:div.text-sm.text-red-600 error-message])
     [:div.flex.justify-end.gap-2
      (shui/button
       {:variant :outline
        :data-literature-action "reload-sources"
        :disabled (or working? (= :loading (:status plan)) (not (current?)))
        :on-click read-sources!}
       (t :literature.source/reload-action))
      (shui/button
       {:variant :ghost
        :ref *close-button
        :disabled (and working? (current?))
        :on-click #(when (dialog-current?)
                     (shui/dialog-close! :literature-source-review))}
       (t :ui/close))]]))

(defn open-source-review!
  [repo paper-uuid & [final-focus]]
  (shui/dialog-open!
   (fn [{dialog-close :close}]
     (source-review-dialog repo paper-uuid dialog-close))
   {:id :literature-source-review
    :content-props (when final-focus {:finalFocus final-focus})
    :title (t :literature.source/review-title)
    :align :center
    :class "lg:max-w-2xl"}))

(defn- pdf-comparison-content
  [status assets keep-id set-keep! other-id set-other! working? set-result! repo selected
   current? result]
  (cond
    (= :loading status) [:div.text-sm.opacity-60 (t :ui/loading)]
    (contains? #{:error :unavailable} status)
    [:p.text-sm.text-red-600 (t :literature.pdf-merge/error)]
    (< (count assets) 2) [:p.text-sm.opacity-70 (t :literature.pdf-merge/needs-pair)]
    :else
    [:<>
     (for [{:keys [i18n-key id set-id!]}
           [{:i18n-key :asset.duplicate-pdf/primary-copy :id keep-id :set-id! set-keep!}
            {:i18n-key :literature.pdf-merge/other-copy :id other-id :set-id! set-other!}]]
       [:div.flex.flex-col.gap-2 {:key (name i18n-key)}
        [:label.text-sm.font-medium (t i18n-key)]
        [:div.flex.items-center.gap-2
         [:select.form-select.flex-1.min-w-0
          {:value id :disabled working? :aria-label (t i18n-key)
           :data-literature-pdf-selection (if (= i18n-key :asset.duplicate-pdf/primary-copy) "main" "other")
           :on-change #(do (set-id! (util/evalue %)) (set-result! {:status :idle}))
           :on-key-down native-control-key-down}
          (for [asset assets :let [uuid (str (:block/uuid asset))]]
            [:option {:key uuid :value uuid}
             (str (:block/title asset) " · "
                  (t :asset.duplicate-pdf/usage
                     (i18n/locale-format-number (:annotation-count asset))
                     (i18n/locale-format-number (:reference-count asset))))])]
         (shui/button
          {:size :sm :variant :ghost :disabled working?
           :aria-label (t :literature.library/open-pdf-action)
           :title (t :literature.library/open-pdf-action)
           :on-click #(p/let [asset (db-async/<get-block repo (:block/uuid (selected id)) {:children? false})]
                        (when (current?) (state/pub-event! [:asset/show-preview asset])))}
          (ui/icon "eye" {:size 16}))]
        (when-let [aliases (seq (keep :block/title (:block/alias (selected id))))]
          [:div.text-xs.opacity-60.break-words (string/join " · " aliases)])])
     (case status
       :checking [:div.flex.items-center.gap-2.text-sm
                  (ui/icon "loader-2" {:size 16 :class "animate-spin"})
                  (if (:page result)
                    (t :literature.pdf-merge/progress (:page result) (:total result))
                    (t :ui/loading))]
       :equal [:div.flex.items-center.gap-2.text-sm
               (ui/icon "check" {:size 16}) (t :literature.pdf-merge/equal)]
       :different [:p.text-sm.opacity-70 (t :literature.pdf-merge/different)]
       :unsupported [:p.text-sm.opacity-70 (t :literature.pdf-merge/unsupported)]
       :changed [:p.text-sm.text-amber-600 (t :asset.duplicate-pdf/changed-warning)]
       (:error :unreadable :unavailable) [:p.text-sm.text-red-600 (t :literature.pdf-merge/error)]
       nil)]))

(hsx/defc pdf-comparison-dialog
  [repo paper-uuid dialog-close]
  (let [[assets set-assets!] (hooks/use-state [])
        [keep-id set-keep!] (hooks/use-state nil)
        [other-id set-other!] (hooks/use-state nil)
        [result set-result!] (hooks/use-state {:status :loading})
        *controller (hooks/use-ref nil)
        *active? (hooks/use-memo #(atom true) [])
        context (hooks/use-memo #(db-subs/projection-context) [repo paper-uuid dialog-close])
        current-repo (rfx/use-sub [:git/current-repo])
        restoring? (rfx/use-sub [:db/restoring?])
        current? #(and @*active? (= repo (state/get-current-repo))
                       (not (state/get-state :db/restoring?))
                       (= context (db-subs/projection-context)))
        status (if (current?) (:status result) :unavailable)
        working? (contains? #{:checking :merging} status)
        selected (fn [id] (some #(when (= id (str (:block/uuid %))) %) assets))
        pair [(selected keep-id) (selected other-id)]
        new-controller! (fn []
                          (when-let [previous (hooks/deref *controller)] (.abort previous))
                          (let [controller (js/AbortController.)]
                            (hooks/set-ref! *controller controller)
                            controller))
        check! (fn []
                 (when (and (current?) (not working?) (every? some? pair) (not= keep-id other-id))
                   (let [controller (new-controller!)]
                     (set-result! {:status :checking})
                     (-> (literature-handler/<compare-pdf-copies!
                          repo paper-uuid pair (.-signal controller)
                          (fn [progress]
                            (when (current?)
                              (set-result! {:status :checking :page (aget progress "page")
                                            :total (aget progress "totalPages")}))))
                         (p/then #(when (current?)
                                    (set-result! (if (= :cancelled (:status %)) {:status :idle} %))))
                         (p/catch (fn [error]
                                    (when (current?)
                                      (when-not (.-aborted (.-signal controller))
                                        (log/error :literature-pdf-comparison-failed error))
                                      (set-result! {:status (if (.-aborted (.-signal controller)) :idle :error)}))))))))
        merge! (fn []
                 (when (and (current?) (= :equal status))
                   (let [controller (new-controller!)]
                     (set-result! (assoc result :status :merging))
                     (-> (literature-handler/<merge-compared-pdfs!
                          repo (:block/uuid (first pair)) (:block/uuid (second pair))
                          (:comparison result) (.-signal controller))
                         (p/then (fn [merged]
                                   (when (current?)
                                     (if (= :resolved (:status merged))
                                       (do (shui/dialog-close! :literature-pdf-comparison)
                                           (notification/show! (t :asset.duplicate-pdf/resolved) :success))
                                       (set-result! {:status :changed})))))
                         (p/catch (fn [error]
                                    (log/error :literature-pdf-merge-failed error)
                                    (when (current?) (set-result! {:status :error}))))))))]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (when (current?)
         (-> (literature-handler/<paper-pdfs-for-comparison! repo paper-uuid)
             (p/then (fn [items]
                       (when (current?)
                         (set-assets! items)
                         (set-keep! (some-> items first :block/uuid str))
                         (set-other! (some-> items second :block/uuid str))
                         (set-result! {:status :idle}))))
             (p/catch (fn [error]
                        (log/error :literature-pdf-comparison-list-failed error)
                        (when (current?) (set-result! {:status :error}))))))
       (fn []
         (reset! *active? false)
         (when-let [controller (hooks/deref *controller)] (.abort controller))))
     [repo paper-uuid current-repo restoring? dialog-close])
    [:div.flex.flex-col.gap-4 {:data-literature-pdf-comparison (name status)}
     (pdf-comparison-content status assets keep-id set-keep! other-id set-other! working? set-result! repo selected current? result)
     [:div.flex.justify-end.gap-2
      (shui/button
       {:variant :ghost :disabled (= :merging status)
        :on-click #(if (= :checking status)
                     (.abort (hooks/deref *controller))
                     (shui/dialog-close! :literature-pdf-comparison))}
       (t (if (= :checking status) :ui/cancel :ui/close)))
      (when (and (>= (count assets) 2) (current?))
        (shui/button
         {:disabled (or working? (= keep-id other-id))
          :data-literature-pdf-compare-action (if (contains? #{:equal :merging} status) "merge" "check")
          :on-click (if (= :equal status) merge! check!)}
         (ui/icon "git-merge" {:size 16})
         (t (if (contains? #{:equal :merging} status)
              :asset.duplicate-pdf/merge-action :literature.pdf-merge/check-action))))]]))

(defn open-pdf-comparison!
  [repo paper-uuid final-focus]
  (shui/dialog-open!
   (fn [{dialog-close :close}] (pdf-comparison-dialog repo paper-uuid dialog-close))
   {:id :literature-pdf-comparison :title (t :literature.pdf-merge/title)
    :content-props (when final-focus {:finalFocus final-focus})
    :align :center :class "lg:max-w-xl"}))

(hsx/defc item-actions
  [repo paper-uuid]
  (let [_language (rfx/use-sub [:preferred-language])
        [open? set-open!] (hooks/use-state false)
        [sources set-sources!] (hooks/use-state [])
        *trigger (hooks/use-ref nil)
        {:keys [enabled? zotero-binding]} (literature-handler/load-settings repo)]
    (hooks/use-effect!
     (fn []
       (let [active? (atom true)]
         (when open?
           (-> (literature-handler/<paper-sources! repo paper-uuid)
               (p/then #(when @active? (set-sources! (:sources %))))
               (p/catch (fn [error] (log/error :literature-source-read-failed error)))))
         #(reset! active? false)))
     [repo paper-uuid open?])
    (shui/dropdown-menu
     {:open open? :on-open-change set-open!}
     (shui/dropdown-menu-trigger {:as-child true :on-key-down native-control-key-down}
      (shui/button {:variant :ghost :size :sm :ref *trigger :data-literature-item-actions true
                    :aria-label (t :literature.library/more-action)}
                   (shui/tabler-icon "dots")))
     (shui/dropdown-menu-content
      (when (util/electron?)
        (shui/dropdown-menu-item
         {:data-literature-action "compare-pdfs"
          :on-click #(open-pdf-comparison! repo paper-uuid *trigger)}
         (t :literature.pdf-merge/entry-action)))
      (when (util/electron?)
        (shui/dropdown-menu-item
         {:data-literature-action "complete-doi"
          :on-click #(open-doi-review! repo paper-uuid *trigger)}
         (t :literature.doi/entry-action)))
      (shui/dropdown-menu-item
       {:data-literature-action "review-sources"
        :on-click #(open-source-review! repo paper-uuid *trigger)}
       (t :literature.source/entry-action))
      (shui/dropdown-menu-item
       {:data-literature-action "rename-pdfs"
        :on-click
        (fn []
          (-> (literature-handler/<rename-paper-pdfs! repo paper-uuid)
              (p/then
               (fn [result]
                 (when (= repo (state/get-current-repo))
                   (notification/show!
                    (t (if (= :complete (:status result))
                         :literature.library/pdf-rename-success
                         :literature.library/pdf-rename-error))
                    (if (= :complete (:status result)) :success :error)))))
              (p/catch
               (fn [error]
                 (log/error :literature-pdf-rename-failed error)
                 (when (= repo (state/get-current-repo))
                   (notification/show! (t :literature.library/pdf-rename-error) :error))))))}
       (t :literature.library/rename-pdfs-action))
      (when (and (util/electron?) enabled? zotero-binding
                 (some #(and (:active? %) (= (:binding-uuid %) (:graph-binding-uuid zotero-binding))) sources))
        (shui/dropdown-menu-item
         {:data-literature-action "check-metadata"
          :on-click #(open-metadata-review! repo paper-uuid *trigger)}
         (t :literature.metadata/check-action)))))))

(defn- zotero-review-content
  [repo current-repo restoring? current? page source working? *search-request set-source!
   set-query! set-search-result! set-error! adopt! query search-result]
  (case (if (and (= repo current-repo) (not restoring?) (current?))
          (:status page) :unavailable)
    :loading [:div.text-sm.opacity-60 (t :ui/loading)]
    :complete
    [:<>
     (if (seq (:items page))
       [:div.flex.flex-col.gap-2.max-h-60.overflow-y-auto
        (for [{:keys [source-key title type] :as record} (:items page)]
          (shui/button
           {:key source-key
            :variant (if (= source-key (:source-key source)) :default :outline)
            :class "h-auto justify-start whitespace-normal text-left"
            :data-literature-zotero-item source-key
            :disabled working?
            :on-click (fn []
                        (swap! *search-request inc)
                        (set-source! record)
                        (set-query! title)
                        (set-search-result! (empty-search-result :idle))
                        (set-error! nil))}
           [:span.flex.flex-col.items-start
            [:span title]
            (when type [:span.text-xs.font-normal.opacity-60 type])]))]
       [:div.text-sm.opacity-60 (t :literature.source/zotero-review-empty)])
     (when source
       [:div.flex.flex-col.gap-3.border-t.pt-3
        [:dl.text-sm.grid.grid-cols-2.gap-2
         (for [field [:title :authors :date :publication :type :url :citekey]
               :let [value (get source field)]
               :when value]
           ^{:key (name field)}
           [:<>
            [:dt.opacity-60 (metadata-field-label field)]
            [:dd.break-words value]])]
        (shui/button
         {:data-literature-action "adopt-zotero-new"
          :disabled working?
          :on-click #(adopt! (random-uuid) true)}
         (t :literature.source/zotero-add-action))
        (shui/input
         {:data-literature-source-search true
          :placeholder (t :literature.source/search-placeholder)
          :value query
          :disabled working?
          :on-change (fn [event]
                       (swap! *search-request inc)
                       (set-query! (util/evalue event))
                       (set-search-result! (empty-search-result :idle)))})
        (case (:status search-result)
          :idle [:div.text-xs.opacity-60 (t :literature.source/search-hint)]
          :loading [:div.text-xs.opacity-60 (t :ui/loading)]
          :ok
          [:div.flex.flex-col.gap-2
           (if (seq (:papers search-result))
             (for [{paper-uuid :block/uuid title :block/title detail :literature/detail}
                   (:papers search-result)]
               (shui/button
                {:key (str paper-uuid)
                 :variant :outline
                 :class "h-auto justify-start whitespace-normal text-left"
                 :data-literature-source-target (str paper-uuid)
                 :disabled working?
                 :on-click #(adopt! paper-uuid false)}
                [:span.flex.flex-col.items-start
                 [:span (t :literature.source/zotero-use-existing-action title)]
                 (when detail [:span.text-xs.font-normal.opacity-60 detail])]))
             [:div.text-xs.opacity-60 (t :search/no-result)])
           (when (:truncated? search-result)
             [:div.text-xs.opacity-60 (t :literature.source/search-truncated)])]
          [:div.text-sm.text-red-600 (t :literature.source/search-error)])])]
    [:div.text-sm.text-red-600 (t :literature.source/zotero-review-error)]))

(hsx/defc zotero-review-dialog
  [repo binding dialog-close]
  (let [[page set-page!] (hooks/use-state {:status :loading})
        [offset set-offset!] (hooks/use-state 0)
        [source set-source!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state "")
        [search-result set-search-result!] (hooks/use-state (empty-search-result :idle))
        [working? set-working!] (hooks/use-state false)
        [error-message set-error!] (hooks/use-state nil)
        *active? (hooks/use-memo #(atom true) [])
        *search-request (hooks/use-memo #(atom 0) [])
        context (hooks/use-memo #(db-subs/projection-context) [repo binding dialog-close])
        current-repo (rfx/use-sub [:git/current-repo])
        restoring? (rfx/use-sub [:db/restoring?])
        dialog-current? #(let [dialog (second (shui/dialog-get :literature-source-review))]
                           (and @*active? (:open? dialog)
                                (identical? dialog-close (:close dialog))))
        current? #(and (dialog-current?)
                       (= repo (state/get-current-repo))
                       (not (state/get-state :db/restoring?))
                       (= context (db-subs/projection-context))
                       (= binding (literature-handler/zotero-binding repo)))
        debounced-query (hooks/use-debounced-value query 200)
        load-page!
        (fn []
          (when (current?)
            (set-page! {:status :loading})
            (set-source! nil)
            (set-query! "")
            (set-error! nil)
            (-> (literature-handler/<preview-manual-zotero-page! repo offset)
                (p/then #(when (current?) (set-page! %)))
                (p/catch (fn [error]
                           (log/error :literature-source-read-failed error)
                           (when (current?) (set-page! {:status :unavailable})))))))
        adopt!
        (fn [paper-uuid create?]
          (when (and (current?) source)
            (set-working! true)
            (set-error! nil)
            (-> (literature-handler/<adopt-manual-zotero-record!
                 repo binding source paper-uuid create?)
                (p/then (fn [result]
                          (when (current?)
                            (if (contains? #{:created :linked :already-imported} (:status result))
                              (do
                                (notification/show! (t :literature.source/zotero-accepted) :success)
                                (load-page!))
                              (set-error! (t :literature.source/zotero-add-error))))))
                (p/catch (fn [error]
                           (log/error :literature-zotero-import-ui-failed error)
                           (when (current?)
                             (set-error! (t :literature.source/zotero-add-error)))))
                (p/finally #(when (current?) (set-working! false))))))]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (load-page!)
       (fn []
         (reset! *active? false)
         (swap! *search-request inc)))
     [repo binding offset dialog-close])
    (hooks/use-effect!
     (fn []
       (let [request-id (swap! *search-request inc)
             query' (string/trim debounced-query)]
         (if (or (not (current?)) (nil? source) (< (count query') 2))
           (set-search-result! (empty-search-result :idle))
           (do
             (set-search-result! (empty-search-result :loading))
             (-> (literature-handler/<search-existing-papers! repo query')
                 (p/then #(when (and (current?) (= request-id @*search-request))
                            (set-search-result! %)))
                 (p/catch (fn [error]
                            (log/error :literature-source-search-failed error)
                            (when (and (current?) (= request-id @*search-request))
                              (set-search-result! (empty-search-result :unavailable))))))))
         #(when (= request-id @*search-request) (swap! *search-request inc))))
     [repo debounced-query (:source-key source)])
    [:div.flex.flex-col.gap-4.min-w-0
     [:p.text-sm.opacity-70 (t :literature.source/zotero-review-desc)]
     (zotero-review-content repo current-repo restoring? current? page source working? *search-request set-source! set-query! set-search-result! set-error! adopt! query search-result)
     (when error-message [:div.text-sm.text-red-600 error-message])
     [:div.flex.justify-end.gap-2
      (when (:next-offset page)
        (shui/button
         {:variant :outline
          :data-literature-action "more-zotero-items"
          :disabled (or working? (not (current?)))
          :on-click #(set-offset! (:next-offset page))}
         (t :literature.source/zotero-more-action)))
      (shui/button
       {:variant :ghost
        :disabled (and working? (current?))
        :on-click #(when (dialog-current?) (shui/dialog-close! :literature-source-review))}
       (t :ui/close))]]))

(defn open-zotero-review!
  [repo binding]
  (shui/dialog-open!
   (fn [{dialog-close :close}] (zotero-review-dialog repo binding dialog-close))
   {:id :literature-source-review
    :title (t :literature.source/zotero-review-title)
    :align :center
    :class "lg:max-w-2xl"}))

(defn- zotero-error-message
  [reason]
  (case reason
    :literature-zotero/library-identity-unavailable (t :zotero.local-import/library-unavailable)
    (:literature-zotero/library-identity-mismatch :library-identity-mismatch)
    (t :zotero.local-import/library-changed)
    :literature-zotero/request-failed (t :zotero.local-import/request-failed)
    (:literature-zotero/binding-inactive :binding-inactive :missing-zotero-binding :lifecycle-inactive)
    (t :settings.literature/zotero-stopped)
    (t :settings.literature/action-error)))

(defn- operation-error!
  [operation current? set-operation! set-error! error]
  (log/error operation error)
  (when (current?)
    (set-operation! nil)
    (set-error! (zotero-error-message (:type (ex-data error))))))

(defn- toggle-setup-help!
  [^js event]
  (when (contains? #{"Enter" " "} (.-key event))
    ;; Keep the graph's editing shortcuts out of the help disclosure.
    (.preventDefault event)
    (.stopPropagation event)
    (let [^js details (.-parentElement (.-currentTarget event))]
      (set! (.-open details) (not (.-open details))))))

(hsx/defc zotero-collection-picker
  [repo parent-current? on-chosen]
  (let [[page set-page!] (hooks/use-state {:status :loading})
        *active? (hooks/use-memo #(atom true) [repo])
        *request-id (hooks/use-memo #(atom 0) [])
        current? #(and @*active? (parent-current?)
                       (not (state/get-state :db/restoring?)))
        load-page!
        (fn [start library-key]
          (when (current?)
            (let [request-id (swap! *request-id inc)]
              (set-page! {:status :loading})
              (-> (zotero/<read-collections-page! current? start library-key)
                  (p/then (fn [result]
                            (when (and (current?) (= request-id @*request-id))
                              (set-page! (assoc result :status :ok :start start)))))
                  (p/catch (fn [error]
                             (log/error :literature-zotero-collections-failed error)
                             (when (and (current?) (= request-id @*request-id))
                               (set-page! {:status :unavailable
                                           :message (zotero-error-message
                                                     (:type (ex-data error)))}))))))))]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (load-page! 0 nil)
       (fn []
         (reset! *active? false)
         (swap! *request-id inc)))
     [repo])
    [:div.flex.flex-col.gap-2.min-w-64
     {:style {:maxWidth "calc(100vw - 2rem)"}
      :data-literature-collection-picker true}
     (case (:status page)
       :loading [:div.text-sm.opacity-60 (t :ui/loading)]
       :ok
       [:div.flex.flex-col.gap-1.overflow-y-auto
        {:style {:maxHeight "40vh"}}
        (if (seq (:collections page))
          (for [{:keys [key name] :as collection} (:collections page)]
            (shui/button
             {:key key :variant :outline
              :class "h-auto justify-start whitespace-normal text-left"
              :data-literature-collection key
              :on-click #(when (current?)
                           (on-chosen (assoc collection
                                             :zotero-library-key
                                             (:zotero-library-key page)))
                           (shui/dialog-close! :literature-zotero-collections))}
             [:span.flex.flex-col.items-start
              [:span name]
              [:code.text-xs.font-normal.opacity-60 key]]))
          [:div.text-sm.opacity-60 (t :zotero.local-import/collections-empty)])]
       [:div.text-sm.text-red-600 (:message page)])
     [:div.flex.flex-wrap.gap-2
      (when (pos? (or (:start page) 0))
        (shui/button
         {:variant :outline :size :sm
          :data-literature-action "previous-collections"
          :on-click #(load-page! (- (:start page) 50) (:zotero-library-key page))}
         (t :zotero.local-import/collections-previous)))
      (when-let [start (:next-offset page)]
        (shui/button
         {:variant :outline :size :sm
          :data-literature-action "more-collections"
          :on-click #(load-page! start (:zotero-library-key page))}
         (t :zotero.local-import/collections-next)))
      (shui/button
       {:variant :ghost :size :sm
        :disabled (= :loading (:status page))
        :data-literature-action "refresh-collections"
        :on-click #(load-page! 0 nil)}
       (t :ui/refresh))]]))

(defn- literature-activation-handler
  [set-error! set-operation! repo current? reload-settings!]
  (fn [enable-automatic?]
    (set-error! nil)
    (set-operation! :activating)
    (-> (p/do! (literature-handler/<activate! repo)
               (when (and enable-automatic? (current?))
                 (literature-handler/set-auto-identification-enabled! repo true)))
        (p/then (fn [_]
                  (when (current?)
                    (reload-settings!)
                    (set-operation! nil))))
        (p/catch #(operation-error!
                   :literature-activation-ui-failed
                   current? set-operation! set-error! %)))))

(defn- zotero-connection-handler
  [set-error! set-operation! repo pending-binding-uuid collection-scope? collection-key
   selected-collection current? reload-settings! set-pending-binding-uuid!]
  (fn []
    (set-error! nil)
    (set-operation! :connecting)
    (-> (p/do!
         (when-not (:enabled? (literature-handler/load-settings repo))
           (literature-handler/<activate! repo))
         ;; Saving the binding starts the existing notifier, whose first
         ;; response imports the scope. A second import here races it.
         (literature-handler/<connect-local-zotero!
          repo pending-binding-uuid
          (when collection-scope? collection-key)
          (when collection-scope? (:zotero-library-key selected-collection))))
        (p/then
         (fn [result]
           (when (current?)
             (if (= :connected (:status result))
               (do
                 (reload-settings!)
                 (set-pending-binding-uuid!
                  (get-in result [:binding :graph-binding-uuid]))
                 (set-operation! nil)
                 (notification/show!
                  (t :settings.literature/zotero-connected)
                  :success))
               (do
                 (set-operation! nil)
                 (set-error! (zotero-error-message (:reason result))))))))
        (p/catch #(operation-error!
                   :literature-zotero-connect-ui-failed
                   current? set-operation! set-error! %)))))

(defn- zotero-import-handler
  [set-error! set-operation! repo current? reload-settings!]
  (fn []
    (set-error! nil)
    (set-operation! :importing)
    (-> (literature-handler/<import-zotero-now! repo)
        (p/then
         (fn [result]
           (when (current?)
             (set-operation! nil)
             (if (contains? #{:complete :idle} (:status result))
               (let [manual-count (get-in result [:result-counts :manual] 0)
                     blocked-count (get-in result [:result-counts :blocked] 0)]
                 (if (pos? (+ manual-count blocked-count))
                   (notification/show!
                    (t :settings.literature/import-needs-attention
                       manual-count blocked-count)
                    :warning)
                   (notification/show!
                    (t :settings.literature/import-complete)
                    :success)))
               (set-error! (zotero-error-message (:reason result))))
             (reload-settings!))))
        (p/catch #(operation-error!
                   :literature-zotero-import-ui-failed
                   current? set-operation! set-error! %)))))

(defn- pdf-identification-settings
  [settings paused? operation enabled? activate! repo reload-settings!]
  [:section.flex.flex-col.gap-3
   {:aria-labelledby "literature-pdf-label"}
   [:div.flex.items-center.justify-between.gap-4
    [:label#literature-pdf-label.flex.items-center.gap-2.text-sm.font-medium
     {:for "literature-auto-pdf"}
     (ui/icon "file-search" {:size 18 :class "opacity-60"})
     (t :settings.literature/auto-identification)]
    (shui/switch
     {:id "literature-auto-pdf" :size "sm"
      :aria-label (t :settings.literature/auto-identification)
      :data-literature-setting "auto-pdf"
      :checked (true? (:auto-identification? settings))
      :disabled (or paused? (some? operation))
      :on-checked-change
      (fn [checked?]
        (if (and (true? checked?) (not enabled?))
          (activate! true)
          (do
            (literature-handler/set-auto-identification-enabled! repo (true? checked?))
            (reload-settings!))))})]
   (when (and (util/electron?) enabled? (:auto-identification? settings))
     [:div.flex.flex-col.gap-2.pl-6
      [:div.flex.items-center.justify-between.gap-4
       [:label.text-sm {:for "literature-online-metadata"}
        (t :settings.literature/online-metadata)]
       (shui/switch
        {:id "literature-online-metadata" :size "sm"
         :aria-label (t :settings.literature/online-metadata)
         :data-literature-setting "online-metadata"
         :checked (true? (:online-metadata? settings))
         :on-checked-change (fn [checked?]
                              (literature-handler/set-online-metadata-enabled! repo (true? checked?))
                              (reload-settings!))})]
      [:p.text-xs.opacity-60 (t :literature.organization/privacy-description)]])])

(defn- connected-zotero-settings
  [enabled? binding selected-collection operation import-now! repo set-error! reload-settings!]
  [:div.flex.flex-col.gap-3
   {:data-literature-zotero-connected true}
   [:div.flex.items-center.justify-between.gap-3
    [:h3.flex.items-center.gap-2.text-sm.font-medium
     (ui/icon "books" {:size 18 :class "opacity-60"})
     (t :settings.literature/zotero-title)]
    (when enabled?
      [:span.flex.items-center.gap-1.text-xs.opacity-60
       (ui/icon "circle-check" {:size 14})
       (t :settings.literature/connected-status)])]
   [:div.flex.flex-wrap.items-center.gap-2.text-sm.opacity-70
    [:span
     (if (= :collection (get-in binding [:scope :kind]))
       (if (and (= (:key selected-collection) (get-in binding [:scope :key]))
                (= (:zotero-library-key selected-collection) (:zotero-library-key binding)))
         (:name selected-collection)
         (t :literature.library/configured-collection))
       (t :settings.literature/zotero-library))]
    (when (= :collection (get-in binding [:scope :kind]))
      [:details.text-xs
       [:summary.cursor-pointer {:on-key-down toggle-setup-help!}
        (t :literature.library/details-action)]
       [:code (get-in binding [:scope :key])]])]
   [:div.flex.flex-wrap.items-center.gap-2
    (shui/button {:size :sm :data-literature-action "import-now"
                  :disabled (or (not enabled?) (some? operation))
                  :on-click import-now!}
                 (ui/icon "refresh" {:size 16})
                 (t :settings.literature/import-now))
    (shui/button {:size :sm :variant :outline
                  :data-literature-action "review-zotero-items"
                  :disabled (or (not enabled?) (some? operation))
                  :on-click #(open-zotero-review! repo binding)}
                 (t :literature.source/review-zotero-action))
    (shui/button {:size :sm :variant :ghost
                  :data-literature-action "disconnect"
                  :disabled (some? operation)
                  :on-click (fn []
                              (set-error! nil)
                              (literature-handler/set-zotero-binding! repo nil)
                              (reload-settings!))}
                 (t :settings.literature/disconnect))]])

(defn- zotero-setup-settings
  [set-setup-open! setup-status scope-kind operation set-scope-kind! collection-scope? repo
   current? set-collection-key! set-selected-collection! set-error! selected-collection
   collection-key proposed-binding connect! set-operation! check-setup!]
  [:details.group
   {:data-literature-zotero-setup true
    :on-toggle #(set-setup-open! (.-open (.-currentTarget %)))}
   [:summary.flex.items-center.justify-between.gap-3.cursor-pointer.list-none
    {:on-key-down toggle-setup-help!}
    [:span.flex.items-center.gap-2.text-sm.font-medium
     (ui/icon "books" {:size 18 :class "opacity-60"})
     (t :settings.literature/zotero-title)]
    [:span.flex.items-center.gap-1.text-sm.text-primary
     (t :settings.literature/connection-action)
     (ui/icon "chevron-down" {:size 16 :class "transition-transform group-open:rotate-180"})]]
   [:div.flex.flex-col.gap-3.pt-4
    (if (= :ready setup-status)
      [:div.flex.flex-col.gap-3
    [:label.text-xs.opacity-60 {:for "literature-zotero-scope"}
     (t :settings.literature/zotero-scope-label)]
    [:div.flex.flex-wrap.items-center.gap-2
     [:select#literature-zotero-scope.form-select.flex-1.min-w-0
      {:data-literature-field "scope"
       :aria-label (t :settings.literature/zotero-scope-label)
       :value (name scope-kind) :disabled (some? operation)
       :on-change #(set-scope-kind! (keyword (util/evalue %)))}
      [:option {:value "library"} (t :settings.literature/zotero-library)]
      [:option {:value "collection"} (t :settings.literature/zotero-collection-option)]]
     (when collection-scope?
       (shui/button
        {:variant :outline :size :sm :class "max-w-full"
         :data-literature-action "choose-collection"
         :disabled (some? operation)
         :on-click (fn [event]
                     (shui/dialog-open!
                      (fn [_]
                        (zotero-collection-picker
                         repo current?
                         (fn [collection]
                           (set-collection-key! (:key collection))
                           (set-selected-collection! collection)
                           (set-error! nil))))
                      {:id :literature-zotero-collections
                       :title (t :zotero.local-import/choose-collection)
                       :content-props {:finalFocus (constantly (.-currentTarget event))}
                       :class "lg:max-w-xl"}))}
        [:span.truncate
         (if selected-collection
           (:name selected-collection)
           (if (string/blank? collection-key)
             (t :zotero.local-import/choose-collection)
             (t :literature.library/configured-collection)))]))]
    [:p.text-xs.opacity-60 (t :zotero.local-import/setup-shared-data)]
    [:div.flex.justify-end
     (shui/button {:size :sm :data-literature-action "connect"
                   :disabled (or (some? operation) (nil? proposed-binding))
                   :on-click connect!}
                  (ui/icon "arrow-right" {:size 16})
                  (t :settings.literature/connect))]
    (when collection-scope?
      [:details.text-xs
       [:summary.cursor-pointer {:on-key-down toggle-setup-help!}
        (t :zotero.local-import/collection-manual)]
       [:div.pt-2
        (shui/input
         {:data-literature-field "collection-key"
          :aria-label (t :settings.literature/collection-label)
          :placeholder (t :settings.literature/collection-placeholder)
          :value collection-key :disabled (some? operation)
          :on-change (fn [event]
                       (let [value (string/upper-case (util/evalue event))]
                         (set-selected-collection! nil)
                         (set-collection-key! (subs value 0 (min 8 (count value))))))})]])]
      [:div.flex.flex-col.gap-3
       {:data-literature-zotero-step (name setup-status)}
       (if (contains? #{:idle :checking} setup-status)
         [:div.text-sm.opacity-60 {:role "status"} (t :ui/loading)]
         [:<>
          [:p.text-sm
           (t (case setup-status
                :local-access :zotero.local-import/setup-local-access
                :companion :zotero.local-import/install-companion-description
                :zotero.local-import/setup-start))]
          [:div.flex.flex-wrap.items-center.gap-2
           (when (= :companion setup-status)
             (shui/button
              {:size :sm :variant :outline
               :data-literature-action "show-companion"
               :on-click #(-> (zotero/<show-companion!)
                              (p/catch (fn [error]
                                         (operation-error!
                                          :literature-show-companion-failed current?
                                          set-operation! set-error! error))))}
              (ui/icon "folder-open" {:size 16})
              (t :zotero.local-import/show-companion-action)))
           (shui/button {:size :sm :variant :ghost
                         :data-literature-action "check-zotero"
                         :on-click check-setup!}
                        (ui/icon "refresh" {:size 16})
                        (t :zotero.local-import/recheck-action))]])])]])

(hsx/defc settings-section
  [repo]
  (let [initial-settings (literature-handler/load-settings repo)
        initial-binding (or (:zotero-binding initial-settings)
                            (:last-zotero-binding initial-settings))
        [settings set-settings!] (hooks/use-state initial-settings)
        [operation set-operation!] (hooks/use-state nil)
        [error-message set-error!] (hooks/use-state nil)
        [setup-open? set-setup-open!] (hooks/use-state false)
        [setup-status set-setup-status!] (hooks/use-state :idle)
        [scope-kind set-scope-kind!] (hooks/use-state
                                      (or (get-in initial-binding [:scope :kind])
                                          :collection))
        [collection-key set-collection-key!] (hooks/use-state
                                              (or (get-in initial-binding
                                                          [:scope :key])
                                                  ""))
        [selected-collection set-selected-collection!] (hooks/use-state nil)
        [pending-binding-uuid set-pending-binding-uuid!] (hooks/use-state
                                                          (or (:graph-binding-uuid
                                                               initial-binding)
                                                              (random-uuid)))
        *active? (hooks/use-memo #(atom true) [repo])
        current? #(and @*active? (= repo (state/get-current-repo)))
        reload-settings! #(set-settings! (literature-handler/load-settings repo))
        enabled? (:enabled? settings)
        binding (:zotero-binding settings)
        paused? (and (not enabled?)
                     (or (true? (:auto-identification? settings)) (some? binding)))
        collection-scope? (= :collection scope-kind)
        proposed-binding (literature-handler/local-zotero-binding
                          pending-binding-uuid
                          (when collection-scope? collection-key))
        check-setup!
        (fn []
          (when (current?)
            (set-setup-status! #(if (= :idle %) :checking %))
            (-> (zotero/<check-setup!)
                (p/then #(when (current?) (set-setup-status! %))))))
        activate!
        (literature-activation-handler set-error! set-operation! repo current? reload-settings!)
        connect!
        (zotero-connection-handler set-error! set-operation! repo pending-binding-uuid collection-scope? collection-key selected-collection current? reload-settings! set-pending-binding-uuid!)
        import-now!
        (zotero-import-handler set-error! set-operation! repo current? reload-settings!)]
    (hooks/use-effect!
     (fn []
       (reset! *active? true)
       (set-operation! nil)
       (set-error! nil)
       (let [settings' (literature-handler/load-settings repo)
             binding' (or (:zotero-binding settings')
                          (:last-zotero-binding settings'))]
         (set-settings! settings')
         (set-scope-kind! (or (get-in binding' [:scope :kind]) :collection))
         (set-collection-key! (or (get-in binding' [:scope :key]) ""))
         (set-selected-collection! nil)
         (set-pending-binding-uuid!
          (or (:graph-binding-uuid binding') (random-uuid))))
       #(reset! *active? false))
     [repo])

    (hooks/use-effect!
     (fn []
       (when (and setup-open? (not binding))
         (check-setup!)
         (js/window.addEventListener "focus" check-setup!)
         #(js/window.removeEventListener "focus" check-setup!)))
     [repo setup-open? binding])

    [:div.flex.flex-col.gap-6.min-w-0
     {:on-key-down native-control-key-down :data-literature-import-settings true}
     (when paused?
       [:div.flex.flex-wrap.items-center.justify-between.gap-2.text-sm
        [:span.opacity-70 (t :settings.literature/paused-status)]
        (shui/button {:size :sm :variant :outline
                      :data-literature-action "activate"
                      :disabled (some? operation)
                      :on-click #(activate! false)}
                     (ui/icon "player-play" {:size 16})
                     (t :settings.literature/enable))])
     (pdf-identification-settings settings paused? operation enabled? activate! repo reload-settings!)
     (when (util/electron?)
       [:section.border-t.pt-4.min-w-0
        {:aria-label (t :settings.literature/zotero-title)}
        (if binding
          (connected-zotero-settings enabled? binding selected-collection operation import-now! repo set-error! reload-settings!)
          (zotero-setup-settings set-setup-open! setup-status scope-kind operation set-scope-kind! collection-scope? repo current? set-collection-key! set-selected-collection! set-error! selected-collection collection-key proposed-binding connect! set-operation! check-setup!))])
     (when operation
       [:div.text-xs.opacity-60 {:role "status"} (t :settings.literature/working)])
     (when error-message
       [:div.text-sm.text-red-600 {:role "alert"} error-message])]))
