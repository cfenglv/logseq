(ns frontend.handler.user-profile-test
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.handler.user :as user-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.user-profile :as profile]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [promesa.core :as p]))

(def ^:private context ["https://service.example" "owner"])
(defonce ^:private *before (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! *before (rfx/snapshot))
             (rfx/replace-state! {:git/current-repo "graph-a"
                                 :rtc/graphs [{:url "graph-a" :GraphUUID "graph-a-id"}]
                                 :user-profile/state {:context context :repo "graph-a" :profiles {} :avatars {}}}))
   :after #(rfx/replace-state! @*before)})

(defn- saved-profile [id revision image]
  {:user-id id :display-name "Display Name" :email "user@example.com" :username "Account"
   :profile-revision revision :has-avatar? (some? image) :avatar-png-base64 image})

(deftest display-source-and-email-privacy-test
  (let [user {:user/uuid "member" :user/name "member@example.com" :avatar-src "old-graph-image"}
        named (profile/resolve-display user {:display-name "用户 😀" :has-avatar? true} "data:image/png;base64,cG5n" {} "member")
        removed (profile/resolve-display user {:display-name nil :has-avatar? false} nil {} "owner")
        historical (profile/resolve-display user nil nil {} "owner")]
    (is (= "用户 😀" (:name named)))
    (is (true? (:user-profile/self? named)))
    (is (= "data:image/png;base64,cG5n" (:avatar-src named)))
    (is (not= "member@example.com" (:name removed)))
    (is (nil? (:avatar-src removed)))
    (is (= "old-graph-image" (:avatar-src historical)))
    (is (= "member@example.com" (:name (profile/resolve-display user nil nil {:ui/mask-email? false} "owner"))))))

(deftest metadata-revision-and-explicit-removal-test
  (with-redefs [profile/current-context (constantly context)]
    (profile/ingest-metadata! context "graph-a" [(saved-profile "member" 2 nil)])
    (profile/ingest-metadata! context "graph-a" [(saved-profile "member" 1 "old-image")])
    (is (= 2 (get-in (state/get-state :user-profile/state) [:profiles "member" :profile-revision])))
    (is (false? (get-in (state/get-state :user-profile/state) [:profiles "member" :has-avatar?])))
    (state/set-state! [:user-profile/state :avatars ["member" 2]] "previous-image")
    (profile/ingest-metadata! context "graph-a" [{:user-id "member" :display-name nil :has-avatar? false :profile-revision 3}])
    (is (nil? (get-in (state/get-state :user-profile/state) [:profiles "member" :display-name])))
    (is (empty? (:avatars (state/get-state :user-profile/state))))
    (is (not (contains? (get-in (state/get-state :user-profile/state) [:profiles "member"]) :avatar-png-base64)))))

(deftest profile-context-and-graph-pruning-test
  (let [current-context (atom context)]
    (with-redefs [profile/current-context #(deref current-context)]
      (profile/ingest-metadata! context "graph-a" [(saved-profile "owner" 1 "own") (saved-profile "member" 1 "other")])
      (state/set-state! [:user-profile/state :avatars] {["owner" 1] "own-image" ["member" 1] "member-image"})
      (state/set-state! :git/current-repo "graph-b")
      (profile/sync-context!)
      (is (= #{"owner"} (set (keys (:profiles (state/get-state :user-profile/state))))))
      (is (= {["owner" 1] "own-image"} (:avatars (state/get-state :user-profile/state))))
      (reset! current-context ["https://another-service.example" "owner"])
      (profile/sync-context!)
      (is (empty? (:profiles (state/get-state :user-profile/state))))
      (is (empty? (:avatars (state/get-state :user-profile/state))))
      (profile/ingest-metadata! context "graph-b" [(saved-profile "member" 99 "stale-image")])
      (is (empty? (:profiles (state/get-state :user-profile/state)))))))

(deftest save-uses-json-and-publishes-only-confirmed-data-test
  (async done
         (let [pending (p/deferred)
               call (atom nil)
               body {:display-name "New Name" :avatar-png-base64 "cG5n" :expected-revision 0}]
           (-> (p/with-redefs [profile/current-context (constantly context)
                               user-handler/<ensure-id&access-token! #(p/resolved nil)
                               rtc-handler/fetch-json (fn [url options schemas]
                                                        (reset! call {:url url :options options :schemas schemas})
                                                        pending)]
                 (let [request (profile/<save! body)]
                  (p/let [_ (p/delay 0)
                         _ (is (empty? (:profiles (state/get-state :user-profile/state))))
                         _ (p/resolve! pending (assoc (saved-profile "owner" 1 "cG5n") :display-name "New Name"))
                         result request]
                   (is (= "New Name" (:display-name result)))
                   (is (= "https://service.example/user/profile" (:url @call)))
                   (is (= "PUT" (get-in @call [:options :method])))
                   (is (= body (js->clj (js/JSON.parse (get-in @call [:options :body])) :keywordize-keys true)))
                   (is (= {} (:schemas @call)))
                   (is (= "data:image/png;base64,cG5n" (get-in (state/get-state :user-profile/state) [:avatars ["owner" 1]]))))))
               (p/catch (fn [error] (is false (str error))))
               (p/finally done)))))

(deftest old-account-response-is-discarded-test
  (async done
         (let [current-context (atom context)
               pending (p/deferred)]
           (-> (p/with-redefs [profile/current-context #(deref current-context)
                               user-handler/<ensure-id&access-token! #(p/resolved nil)
                               rtc-handler/fetch-json (fn [& _] pending)]
                 (let [request (profile/<get-own!)]
                  (p/let [_ (p/delay 0)
                         _ (reset! current-context ["https://service.example" "another-account"])
                         _ (profile/sync-context!)
                         _ (p/resolve! pending (saved-profile "owner" 1 "old-image"))]
                   (-> request
                       (p/then (fn [_] (is false "Old account data must not be accepted")))
                       (p/catch (fn [_]
                                  (is (empty? (:profiles (state/get-state :user-profile/state))))
                                  (is (empty? (:avatars (state/get-state :user-profile/state))))))))))
               (p/catch (fn [error] (is false (str error))))
               (p/finally done)))))

(deftest member-refresh-removes-permission-scoped-cache-test
  (async done
         (-> (p/with-redefs [profile/current-context (constantly context)
                             rtc-handler/<rtc-get-users-info
                             (fn [force?]
                               (is (true? force?))
                               (p/resolved [{:user/uuid "member" :user/name "Account" :display-name "New Display"
                                             :has-avatar? false :profile-revision 2}]))]
               (profile/ingest-metadata! context "graph-a" [(saved-profile "removed" 1 "old-image")])
               (state/set-state! [:user-profile/state :avatars ["removed" 1]] "old-image")
               (p/let [_ (profile/<refresh-members!)]
                 (is (= #{"member"} (set (keys (:profiles (state/get-state :user-profile/state))))))
                 (is (= "New Display" (get-in (state/get-state :user-profile/state) [:profiles "member" :display-name])))
                 (is (empty? (:avatars (state/get-state :user-profile/state))))))
             (p/catch (fn [error] (is false (str error))))
             (p/finally done))))

(deftest broadcast-failure-does-not-reverse-confirmed-save-test
  (async done
    (let [warnings (atom [])]
      (state/set-state! :rtc/state {:rtc-state {:ws-state :open}})
      (-> (p/with-redefs [profile/current-context (constantly context)
                         user-handler/<ensure-id&access-token! #(p/resolved nil)
                         rtc-handler/fetch-json (fn [& _] (p/resolved (saved-profile "owner" 1 "cG5n")))
                         state/<invoke-db-worker (fn [& _] (p/rejected (ex-info "send failed" {})))
                         notification/show! (fn [message kind] (swap! warnings conj [message kind]))]
            (p/let [saved (profile/<save! {:display-name "Display Name" :avatar-png-base64 "cG5n" :expected-revision 0})]
              (is (= 1 (:profile-revision saved)))
              (is (= 1 (get-in (state/get-state :user-profile/state) [:profiles "owner" :profile-revision])))
              (is (= "data:image/png;base64,cG5n" (get-in (state/get-state :user-profile/state) [:avatars ["owner" 1]])))
              (is (= [:warning] (mapv second @warnings)))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest rtc-profile-ingestion-is-context-bound-and-retains-account-default-test
  (with-redefs [profile/current-context (constantly context)]
    (profile/ingest-metadata! context "graph-a" [(saved-profile "member" 1 nil)])
    (profile/on-rtc-state! {:user-profile/context context :user-profile/repo "graph-a"
                           :online-users [{:user/uuid "member" :display-name nil :has-avatar? false :profile-revision 2}]})
    (is (= "Account" (get-in (state/get-state :user-profile/state) [:profiles "member" :username])))
    (is (nil? (get-in (state/get-state :user-profile/state) [:profiles "member" :display-name])))
    (profile/on-rtc-state! {:user-profile/context ["https://another-service.example" "owner"] :user-profile/repo "graph-a"
                           :online-users [{:user/uuid "member" :display-name "Wrong service" :has-avatar? false :profile-revision 99}]})
    (is (= 2 (get-in (state/get-state :user-profile/state) [:profiles "member" :profile-revision])))))

(deftest automatic-refresh-failure-retains-cache-without-toast-test
  (async done
    (let [network-error (js/Error. "Connection unavailable")
          notifications (atom [])]
      (-> (p/with-redefs [profile/current-context (constantly context)
                         user-handler/<ensure-id&access-token! #(p/resolved nil)
                         rtc-handler/fetch-json (fn [& _] (p/rejected network-error))
                         rtc-handler/<rtc-get-users-info (fn [& _] (p/resolved []))
                         notification/show! (fn [& args] (swap! notifications conj args))]
            (profile/ingest-metadata! context "graph-a" [(saved-profile "owner" 1 nil)])
            (p/let [_ (profile/refresh-on-focus!)]
              (is (empty? @notifications) "An automatic read failure is not a failed user action")
              (is (= 1 (get-in (state/get-state :user-profile/state) [:profiles "owner" :profile-revision])))
              (-> (profile/<get-own!)
                  (p/then (fn [_] (is false "Explicit reads must still reject")))
                  (p/catch (fn [error] (is (identical? network-error error)))))
              (-> (profile/<save! {:display-name "Display Name" :avatar-png-base64 nil :expected-revision 1})
                  (p/then (fn [_] (is false "A failed save must not become success")))
                  (p/catch (fn [error] (is (identical? network-error error)))))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))
