(ns frontend.components.user-profile
  (:require [clojure.string :as string]
            [frontend.components.avatar :as avatar]
            [frontend.components.email :as email-component]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.notification :as notification]
            [frontend.handler.user-profile :as profile]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [io.factorhouse.hsx.core :as hsx]
            [logseq.db-sync.malli-schema :as schema]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [malli.core :as ma]
            [promesa.core :as p]))

(defn use-display
  ([user] (use-display user true))
  ([user service-profiles?]
  (let [user-id (some-> (:user/uuid user) str)
        context (rfx/use-sub [:user-profile/state :context])
        repo (rfx/use-sub [:git/current-repo])
        stored-profile (rfx/use-sub [:user-profile/state :profiles user-id])
        avatar-src (rfx/use-sub [:user-profile/state :avatars [user-id (:profile-revision stored-profile)]])
        config (rfx/use-sub [:config])
        current-context (profile/current-context)
        valid-context? (and service-profiles? (some? current-context) (= context current-context))
        metadata (when valid-context? stored-profile)]
    (hooks/use-effect!
     (fn []
       (when (and valid-context? (:has-avatar? metadata))
         (some-> (profile/<load-avatar! user-id)
                 (p/catch (fn [_]
                            (when (= context (profile/current-context))
                              (notification/show! (t :account/profile-avatar-load-error) :error))))))
       nil)
     [context repo user-id valid-context? (:profile-revision metadata) (:has-avatar? metadata)])
    (profile/resolve-display user metadata (when valid-context? avatar-src) config (second current-context)))))

(defn avatar-view
  "Thin presentation wrapper; never changes the image on hover or requests profile data."
  [{:keys [name user-id avatar-src] :user-profile/keys [self?]} {:keys [tooltip?] :or {tooltip? true} :as props}]
  (let [image (avatar/user-avatar
               (merge (dissoc props :tooltip? :title)
                      {:name name :uuid user-id :avatar-src avatar-src :image-props {:alt ""}}))]
    (if tooltip?
      (let [label (if self? (t :account/self-name-tooltip name) name)]
        (ui/tooltip
         [:span.inline-flex.rounded-full.outline-none.hover:ring-1.focus-visible:ring-2.ring-blue-rx-08
          {:tab-index 0 :aria-label label
           :class "data-[popup-open]:ring-1 data-[popup-open]:focus-visible:ring-2"}
          [:span.inline-flex {:aria-hidden true} image]]
         label
         {:content-props {:class "max-w-xs whitespace-normal break-words"}}))
      [:span.inline-flex {:aria-hidden true} image])))

(hsx/defc user-avatar [user props]
  (avatar-view (use-display user) props))

(hsx/defc name-label [user]
  [:span (:name (use-display user))])

(defn <normalize-avatar [^js file]
  (p/do
    (when (or (not (contains? #{"image/png" "image/jpeg" "image/webp"} (.-type file)))
              (> (.-size file) (* 5 1024 1024)))
      (throw (ex-info "profile invalid image" {})))
    (p/let [^js bitmap (js/createImageBitmap file #js {:imageOrientation "from-image"})]
      (try
        (let [^js canvas (.createElement js/document "canvas")
              _ (set! (.-width canvas) 128)
              _ (set! (.-height canvas) 128)
              ^js context (.getContext canvas "2d")
              size (min (.-width bitmap) (.-height bitmap))
              _ (.drawImage context bitmap
                            (/ (- (.-width bitmap) size) 2) (/ (- (.-height bitmap) size) 2)
                            size size 0 0 128 128)
              png (subs (.toDataURL canvas "image/png") (count "data:image/png;base64,"))]
          (when (> (count (js/atob png)) (* 128 1024))
            (throw (ex-info "profile image too large" {})))
          png)
        (finally (.close bitmap))))))

(defn draft-body [{:keys [display-name avatar-png-base64 expected-revision]}]
  {:display-name (when-not (string/blank? display-name) (string/trim display-name))
   :avatar-png-base64 avatar-png-base64 :expected-revision expected-revision})

(defn- saved-profile->draft [saved]
  (assoc (select-keys saved [:display-name :avatar-png-base64 :email :username])
         :expected-revision (:profile-revision saved)))

(hsx/defc profile-editor []
  (let [context (rfx/use-sub [:user-profile/state :context])
        config (rfx/use-sub [:config])
        _preferred-language (rfx/use-sub [:preferred-language])
        [draft set-draft!] (hooks/use-state nil)
        [saved-draft set-saved-draft!] (hooks/use-state nil)
        [loading? set-loading!] (hooks/use-state true)
        [saving? set-saving!] (hooks/use-state false)
        [image-loading? set-image-loading!] (hooks/use-state false)
        [error set-error!] (hooks/use-state nil)
        file-input (hooks/use-ref nil)
        file-selection (hooks/use-ref nil)
        mounted? (hooks/use-ref true)
        active? #(and (hooks/deref mounted?) (= context (profile/current-context)))
        body (when draft (draft-body draft))
        dirty? (not= body (when saved-draft (draft-body saved-draft)))
        valid? (and body (ma/validate schema/user-profile-save-request-schema body))
        disabled? (or loading? saving? (nil? draft))
        default-name (or (:username draft) (:email draft) (second context))
        user {:user/uuid (second context) :user/name default-name}
        default-display (profile/resolve-display user (select-keys draft [:username :email]) nil config (second context))
        preview (profile/resolve-display
                 user
                 {:display-name (:display-name body) :username (:username draft) :email (:email draft)}
                 (when-let [image (:avatar-png-base64 draft)] (str "data:image/png;base64," image))
                 config (second context))
        select-image! (fn [^js e]
                        (when-let [file (aget (.. e -target -files) 0)]
                          (set! (.. e -target -value) "")
                          (hooks/set-ref! file-selection file)
                          (set-image-loading! true)
                          (set-error! nil)
                          (-> (<normalize-avatar file)
                              (p/then (fn [png]
                                        (when (and (active?) (identical? file (hooks/deref file-selection)))
                                          (set-draft! #(assoc % :avatar-png-base64 png)))))
                              (p/catch (fn [_]
                                         (when (and (active?) (identical? file (hooks/deref file-selection)))
                                           (set-error! (t :account/profile-image-error)))))
                              (p/finally (fn []
                                           (when (and (active?) (identical? file (hooks/deref file-selection)))
                                             (set-image-loading! false)))))))
        choose-image! #(some-> (hooks/deref file-input) .click)
        save! (fn [e]
                (util/stop e)
                (when (and dirty? valid? (not disabled?) (not image-loading?))
                  (set-saving! true)
                  (set-error! nil)
                  (-> (profile/<save! body)
                      (p/then (fn [saved]
                                (when (active?)
                                  (let [draft' (saved-profile->draft saved)]
                                    (set-saved-draft! draft')
                                    (set-draft! draft'))
                                  (notification/show! (t :account/profile-save-success) :success))))
                      (p/catch (fn [error]
                                 (when (active?) (set-error! (profile/save-error-message error)))))
                      (p/finally #(when (active?) (set-saving! false))))))]
    (hooks/use-effect!
     (fn []
       (hooks/set-ref! mounted? true)
       (hooks/set-ref! file-selection nil)
       (set-loading! true)
       (set-saving! false)
       (set-image-loading! false)
       (set-error! nil)
       (set-draft! nil)
       (set-saved-draft! nil)
       (-> (profile/<get-own!)
           (p/then (fn [saved]
                     (when-not saved (throw (ex-info "profile unavailable" {})))
                     (when (active?)
                       (let [draft' (saved-profile->draft saved)]
                         (set-saved-draft! draft')
                         (set-draft! draft')))))
           (p/catch (fn [_]
                      (when (active?) (set-error! (t :account/profile-load-error)))))
           (p/finally #(when (active?) (set-loading! false))))
       #(hooks/set-ref! mounted? false))
     [context])
    [:form.ls-user-profile.flex.w-full.max-w-md.flex-col.gap-6 {:on-submit save!}
     (if loading?
       [:p (t :ui/loading)]
       [:<>
        [:div.flex.items-center.gap-4
         [:button.relative.inline-flex.flex-none.rounded-full
          {:type "button" :disabled disabled? :aria-label (t :account/profile-change-avatar-action)
           :on-click choose-image!}
          (avatar-view preview {:class "w-16 h-16" :fallback-props {:style {:font-size 22}} :tooltip? false})
          [:span.absolute.flex.h-6.w-6.items-center.justify-center.rounded-full.border.border-input.bg-background
           {:class "-bottom-0.5 -right-0.5" :aria-hidden true} (ui/icon "camera" {:size 14})]]
         [:div.flex.flex-col.gap-2
          [:div.flex.items-center.gap-2
           (shui/button {:type "button" :variant :outline :size :sm :disabled disabled?
                         :on-click choose-image!}
                        (t :account/profile-change-avatar-action))
           (when (:avatar-png-base64 draft)
             (shui/button {:type "button" :variant :ghost :size :sm :disabled disabled?
                           :on-click #(do (hooks/set-ref! file-selection nil)
                                          (set-image-loading! false)
                                          (set-draft! (assoc draft :avatar-png-base64 nil)))}
                          (t :account/profile-remove-avatar-action)))]
          [:p.text-xs.opacity-60 (t :account/profile-avatar-hint)]
          (when image-loading? [:p.text-xs (t :ui/loading)])]
         [:input.hidden {:ref file-input :type "file" :accept "image/png,image/jpeg,image/webp"
                         :disabled disabled? :on-change select-image!}]]
        [:div.flex.flex-col.gap-2
         [:label.flex.flex-col.gap-2.text-sm.font-medium
          (t :account/profile-display-name-label)
          (shui/input {:value (or (:display-name draft) "") :disabled disabled?
                       :placeholder (:name default-display)
                       :on-change #(set-draft! (assoc draft :display-name (util/evalue %)))})]
         [:p.text-xs.opacity-60 (t :account/profile-display-name-hint)]]
        [:div.flex.flex-col.gap-2.text-sm
         [:span.font-medium (t :account/email)]
         (email-component/email-address {:email (:email draft) :class "opacity-70"})]])
     (when error [:p.text-sm.text-red-rx-09 {:role "alert"} error])
     [:div.flex.justify-end.gap-2
      (shui/button {:type "button" :variant :ghost :size :sm
                    :disabled (or saving? (and (not dirty?) (not error) (not image-loading?)))
                    :on-click #(do (hooks/set-ref! file-selection nil)
                                   (set-image-loading! false)
                                   (set-error! nil)
                                   (set-draft! saved-draft))} (t :ui/cancel))
      (shui/button {:type "submit" :size :sm
                    :disabled (or disabled? image-loading? (not dirty?) (not valid?))}
                   (t :ui/save))]]))

(defn open! []
  (profile/sync-context!)
  (state/open-settings! :profile))
