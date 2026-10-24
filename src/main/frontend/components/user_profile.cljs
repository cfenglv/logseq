(ns frontend.components.user-profile
  (:require [clojure.string :as string]
            [frontend.components.avatar :as avatar]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.notification :as notification]
            [frontend.handler.user-profile :as profile]
            [frontend.rfx :as rfx]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.util.email :as email-util]
            [io.factorhouse.hsx.core :as hsx]
            [logseq.db-sync.malli-schema :as schema]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [malli.core :as ma]
            [promesa.core :as p]))

(def profile-dialog-id "ls-user-profile")

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
          {:tab-index 0 :aria-label label}
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

(hsx/defc profile-editor []
  (let [context (rfx/use-sub [:user-profile/state :context])
        config (rfx/use-sub [:config])
        [draft set-draft!] (hooks/use-state nil)
        [loading? set-loading!] (hooks/use-state true)
        [saving? set-saving!] (hooks/use-state false)
        [image-loading? set-image-loading!] (hooks/use-state false)
        [error set-error!] (hooks/use-state nil)
        file-selection (hooks/use-ref nil)
        mounted? (hooks/use-ref true)
        active? #(and (hooks/deref mounted?) (= context (profile/current-context)))
        body (when draft (draft-body draft))
        valid? (and body (ma/validate schema/user-profile-save-request-schema body))
        default-name (or (:username draft) (:email draft) (second context))
        preview (profile/resolve-display
                 {:user/uuid (second context) :user/name default-name}
                 {:display-name (:display-name body) :username (:username draft) :email (:email draft)}
                 (when-let [image (:avatar-png-base64 draft)] (str "data:image/png;base64," image))
                 config (second context))
        select-image! (fn [^js e]
                        (when-let [file (aget (.. e -target -files) 0)]
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
        save! (fn [e]
                (util/stop e)
                (when (and valid? (not loading?) (not saving?) (not image-loading?))
                  (set-saving! true)
                  (set-error! nil)
                  (-> (profile/<save! body)
                      (p/then (fn [_]
                                (when (active?)
                                  (notification/show! (t :account/profile-save-success) :success)
                                  (shui/dialog-close! profile-dialog-id))))
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
       (-> (profile/<get-own!)
           (p/then (fn [saved]
                     (when-not saved (throw (ex-info "profile unavailable" {})))
                     (when (active?)
                       (set-draft! (assoc (select-keys saved [:display-name :avatar-png-base64 :email :username])
                                          :expected-revision (:profile-revision saved))))))
           (p/catch (fn [_]
                      (when (active?) (set-error! (t :account/profile-load-error)))))
           (p/finally #(when (active?) (set-loading! false))))
       #(hooks/set-ref! mounted? false))
     [context])
    [:form.ls-user-profile.flex.flex-col.gap-4 {:on-submit save!}
     (if loading?
       [:p (t :ui/loading)]
       [:<>
        [:div.flex.items-center.gap-3 (avatar-view preview {:class "w-16 h-16" :tooltip? false})
         [:span.break-words (:name preview)]]
        [:label.flex.flex-col.gap-1
         (t :account/profile-display-name-label)
         (shui/input {:value (or (:display-name draft) "") :disabled (or saving? (nil? draft))
                      :on-change #(set-draft! (assoc draft :display-name (util/evalue %)))})]
        [:p.text-sm.opacity-70 (t :account/profile-display-name-hint)]
        [:label.flex.flex-col.gap-1
         (t :account/email)
         (shui/input {:value (or (email-util/display-email (:email draft) config) "") :read-only true})]
        [:div.flex.flex-col.gap-2
         [:label.flex.flex-col.gap-1
          (t :account/profile-avatar-label)
          [:input {:type "file" :accept "image/png,image/jpeg,image/webp"
                   :disabled (or saving? (nil? draft)) :on-change select-image!}]]
         [:p.text-sm.opacity-70 (t :account/profile-avatar-hint)]
         (when image-loading? [:p (t :ui/loading)])
         (shui/button {:type "button" :variant :outline
                       :disabled (or saving? (nil? draft) (nil? (:avatar-png-base64 draft)))
                       :on-click #(do (hooks/set-ref! file-selection nil)
                                      (set-image-loading! false)
                                      (set-draft! (assoc draft :avatar-png-base64 nil)))}
                      (t :account/profile-remove-avatar-action))]])
     (when error [:p.text-red-rx-09 {:role "alert"} error])
     [:div.flex.justify-end.gap-2
      (shui/button {:type "button" :variant :outline :disabled saving?
                    :on-click #(shui/dialog-close! profile-dialog-id)} (t :ui/cancel))
      (shui/button {:type "submit" :disabled (or loading? saving? image-loading? (not valid?))}
                   (t :ui/save))]]))

(defn open! []
  (profile/sync-context!)
  (shui/dialog-open! profile-editor
                    {:id profile-dialog-id :title (t :account/profile) :class "max-w-md"}))
