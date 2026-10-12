(ns frontend.worker.sync.presence-test
  (:require [cljs.test :refer [deftest is]]
            [frontend.worker.sync.presence :as presence]
            [logseq.db-sync.malli-schema :as schema]))

(deftest profile-fields-survive-schema-normalization-and-rtc-state-test
  (let [wire {:type "online-users"
              :online-users [{:user-id "user-1" :username "Account" :display-name "中文 😀"
                              :has-avatar? true :profile-revision 2 :editing-block-uuid "block-a"}]}
        message (schema/ws-server-message-coercer wire)
        client {:repo "graph-a" :user-profile/context ["https://service.example" "user-1"]
                :online-users (atom []) :ws-state (atom :open)}]
    (presence/update-online-users! (fn [_]) client (:online-users message))
    (let [payload (presence/rtc-state-payload (constantly {}) client)
          user (first (:online-users payload))]
      (is (= (:user-profile/context client) (:user-profile/context payload)))
      (is (= "graph-a" (:user-profile/repo payload)))
      (is (= "Account" (:user/name user)))
      (is (= "中文 😀" (:display-name user)))
      (is (= "block-a" (:user/editing-block-uuid user)))
      (is (true? (:has-avatar? user)))
      (is (= 2 (:profile-revision user))))))

(deftest profile-update-clears-fields-without-changing-membership-or-editor-test
  (let [initial [{:user/uuid "user-1" :user/name "Account" :display-name "Old Name"
                  :has-avatar? true :profile-revision 2 :user/editing-block-uuid "block-a"}
                 {:user/uuid "user-2" :user/name "Other"}]
        client {:online-users (atom initial)}
        broadcasts (atom 0)
        broadcast #(swap! broadcasts inc)
        profile-update {:user-id "user-1" :display-name nil :has-avatar? false :profile-revision 3}]
    (presence/update-user-profile! (fn [_] (broadcast)) client profile-update)
    (is (= 1 @broadcasts))
    (is (= "block-a" (:user/editing-block-uuid (first @(:online-users client)))))
    (is (nil? (:display-name (first @(:online-users client)))))
    (is (false? (:has-avatar? (first @(:online-users client)))))
    (is (= "Account" (:user/name (first @(:online-users client)))))
    (is (= (second initial) (second @(:online-users client))))
    (presence/update-user-profile! (fn [_] (broadcast)) client (assoc profile-update :profile-revision 1 :display-name "Stale"))
    (is (= 1 @broadcasts))
    (is (nil? (:display-name (first @(:online-users client)))))
    (presence/update-online-users! (fn [_] (broadcast)) client
                                  [{:user-id "user-1" :username "Account" :display-name "Older list"
                                    :has-avatar? true :profile-revision 2 :editing-block-uuid "block-a"}])
    (is (= 1 (count @(:online-users client))))
    (is (= 3 (:profile-revision (first @(:online-users client)))))
    (is (nil? (:display-name (first @(:online-users client)))))))
