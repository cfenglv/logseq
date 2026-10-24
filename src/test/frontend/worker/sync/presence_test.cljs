(ns frontend.worker.sync.presence-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.worker-common.util :as worker-util]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.handle-message :as sync-handle-message]
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
(deftest presence-message-with-no-editing-block-clears-the-users-editing-block-test
  (testing "when another user stops editing (their presence message has a null editing-block-uuid, as the server broadcasts it), this client stops showing them on the old block"
    (let [client {:repo "r"
                  :online-users (atom [{:user/uuid "u1" :user/name "Alice"}
                                       {:user/uuid "u2" :user/name "Bob" :display-name "Bob Display"
                                        :has-avatar? true :profile-revision 3}])
                  :ws-state (atom :open)}
          message (fn [editing-block-uuid]
                    (js/JSON.stringify (clj->js {:type "presence"
                                                 :user-id "u2"
                                                 :editing-block-uuid editing-block-uuid})))
          bob (fn [] (second @(:online-users client)))]
      (with-redefs [worker-state/get-id-token (fn [] "token")
                    worker-util/parse-jwt (fn [_] {:sub "u1"})
                    client-op/get-local-tx (fn [_repo] 0)
                    shared-service/broadcast-to-clients! (fn [& _] nil)]
        (sync-handle-message/handle-message! "r" client (message "block-2"))
        (is (= "block-2" (:user/editing-block-uuid (bob))))
        (sync-handle-message/handle-message! "r" client (message nil))
        (is (nil? (:user/editing-block-uuid (bob))) "Bob no longer edits block-2")
        (is (= "Bob Display" (:display-name (bob))))
        (is (true? (:has-avatar? (bob))))
        (is (= 3 (:profile-revision (bob))))
        (is (= "Alice" (:user/name (first @(:online-users client)))) "other users are unchanged")))))
