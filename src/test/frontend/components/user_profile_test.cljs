(ns frontend.components.user-profile-test
  (:require [cljs.test :refer [deftest is]]
            [frontend.components.avatar :as avatar]
            [frontend.components.user-profile :as profile]
            [frontend.context.i18n :as i18n]
            [frontend.ui :as ui]))

(deftest avatar-tooltip-keeps-image-and-has-one-full-name-test
  (let [avatar-props (atom nil)
        tooltip (atom nil)]
    (with-redefs [avatar/user-avatar (fn [props] (reset! avatar-props props) [:image])
                  ui/tooltip (fn [trigger content & options]
                               (reset! tooltip {:trigger trigger :content content :options options}))
                  i18n/t (fn [_ name] (str name " (you)"))]
      (profile/avatar-view {:name "Full Name" :user-id "owner" :avatar-src "data:image/png;base64,cG5n"
                            :user-profile/self? true}
                           {:class "w-5 h-5" :title "old@example.com"})
      (is (= "data:image/png;base64,cG5n" (:avatar-src @avatar-props)))
      (is (not (contains? @avatar-props :title)))
      (is (= "Full Name (you)" (:content @tooltip)))
      (is (= {:tab-index 0 :aria-label "Full Name (you)"} (select-keys (second (:trigger @tooltip)) [:tab-index :aria-label])))
      (is (not (contains? (second (:trigger @tooltip)) :on-click))))))

(deftest draft-save-empty-name-and-cancel-data-is-local-test
  (let [draft {:display-name "  " :avatar-png-base64 "cG5n" :expected-revision 2 :email "user@example.com"}]
    (is (= {:display-name nil :avatar-png-base64 "cG5n" :expected-revision 2} (profile/draft-body draft)))
    (is (= "  " (:display-name draft)))
    (is (= "User" (:display-name (profile/draft-body (assoc draft :display-name " User ")))))))
