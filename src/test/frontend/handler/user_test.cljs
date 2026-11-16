(ns frontend.handler.user-test
  (:require [cljs-http.client :as http]
            [cljs.core.async :as a]
            [cljs.test :refer [async deftest is testing]]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.handler.user :as user-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

(defn- with-mocked-local-storage
  ([f]
   (with-mocked-local-storage {} f))
  ([items f]
   (let [old-storage (.-localStorage js/globalThis)
         had-local-storage?
         (.call (.-hasOwnProperty (.-prototype js/Object))
                js/globalThis
                "localStorage")
         mocked-storage (clj->js items)]
     (js/Object.defineProperties
      mocked-storage
      #js {:clear #js {:value (fn [] (doseq [key (js/Object.keys mocked-storage)]
                                      (js/Reflect.deleteProperty mocked-storage key)))}
           :setItem #js {:value (fn [key value] (aset mocked-storage key (js/String value)))}
           :getItem #js {:value (fn [key] (when (.call (.-hasOwnProperty (.-prototype js/Object))
                                                     mocked-storage key)
                                          (aget mocked-storage key)))}
           :removeItem #js {:value (fn [key] (js/Reflect.deleteProperty mocked-storage key))}})
     (js/Object.defineProperty js/globalThis
                               "localStorage"
                               #js {:value mocked-storage
                                    :configurable true
                                    :writable true})
     (try
       (f)
       (finally
         (if had-local-storage?
           (js/Object.defineProperty js/globalThis
                                     "localStorage"
                                     #js {:value old-storage
                                          :configurable true
                                          :writable true})
           (js/Reflect.deleteProperty js/globalThis "localStorage")))))))

(defn- jwt
  [payload]
  (str "header."
       (js/btoa (js/JSON.stringify (clj->js (merge {:cognito:username ""} payload))))
       ".sig"))

(deftest file-sync-request-uses-existing-electron-http-owner-test
  (async done
         (let [called* (atom nil)
               result-ch (with-redefs [util/electron? (constantly true)
                                       ipc/ipc (fn [& args]
                                                 (reset! called* args)
                                                 (p/resolved {:status 200 :ok true :body "{}"}))]
                           (#'user-handler/<request-once "user_info" {} "qualification-token"))
               [method request-id options] @called*]
           (is (= :httpRequest method))
           (is (some? (and (string? request-id)
                           (re-matches #"[0-9a-f-]{36}" request-id))))
           (is (= {:url "https://api.logseq.com/file-sync/user_info"
                   :method "POST"
                   :headers {"authorization" "Bearer qualification-token"
                             "content-type" "application/json"}
                   :body "{}"
                   :returnType "text"
                   :includeResponse true}
                  options))
           (a/take! result-ch
                    (fn [result]
                      (is (= {:status 200 :ok true :body "{}"}
                             (:resp result)))
                      (done))))))

(deftest file-sync-request-keeps-browser-http-path-test
  (let [called* (atom nil)]
    (with-redefs [util/electron? (constantly false)
                  http/post
                  (fn [& args]
                    (reset! called* args)
                    (a/go {:status 200 :body "{}"}))]
      (#'user-handler/<request-once "user_info" {} "qualification-token"))
    (is (= ["https://api.logseq.com/file-sync/user_info"
            {:oauth-token "qualification-token"
             :body "{}"
             :with-credentials? false}]
           @called*))))

(deftest set-tokens-persists-auth-json-with-latest-token-values-test
  (let [writes* (atom [])
        old-state (state/get-state)]
    (state/replace-state! (assoc-in old-state [:system/info :home-dir] "/tmp/home"))
    (try
      (with-mocked-local-storage
        (fn []
          (with-redefs [util/electron? (constantly true)
                        ipc/ipc (fn [op _repo path content]
                                  (is (= "writeFile" op))
                                  (swap! writes* conj {:path path
                                                       :content content})
                                  (p/resolved nil))]
            (#'user-handler/set-tokens! "id-token-v1" "access-token-v1" "refresh-token-v1")
            (is (= 1 (count @writes*)))
            (is (= "/tmp/home/logseq/auth.json" (:path (first @writes*))))
            (is (= {:id-token "id-token-v1"
                    :access-token "access-token-v1"
                    :refresh-token "refresh-token-v1"}
                   (select-keys (js->clj (js/JSON.parse (:content (first @writes*))) :keywordize-keys true)
                                [:id-token :access-token :refresh-token]))))))
      (finally
        (state/replace-state! old-state)))))

(deftest set-tokens-without-refresh-token-persists-existing-refresh-token-test
  (let [writes* (atom [])
        old-state (state/get-state)]
    (state/replace-state! (-> old-state
                              (assoc :auth/refresh-token "refresh-token-existing")
                              (assoc-in [:system/info :home-dir] "/tmp/home")))
    (try
      (with-mocked-local-storage
        (fn []
          (with-redefs [util/electron? (constantly true)
                        ipc/ipc (fn [op _repo path content]
                                  (is (= "writeFile" op))
                                  (swap! writes* conj {:path path
                                                       :content content})
                                  (p/resolved nil))]
            (#'user-handler/set-tokens! "id-token-v2" "access-token-v2")
            (is (= 1 (count @writes*)))
            (is (= "/tmp/home/logseq/auth.json" (:path (first @writes*))))
            (is (= {:id-token "id-token-v2"
                    :access-token "access-token-v2"
                    :refresh-token "refresh-token-existing"}
                   (select-keys (js->clj (js/JSON.parse (:content (first @writes*))) :keywordize-keys true)
                                [:id-token :access-token :refresh-token]))))))
      (finally
        (state/replace-state! old-state)))))

(deftest restore-tokens-preserves-refresh-token-before-refreshing-expired-id-token-test
  (async done
    (let [old-state (state/get-state)
          old-refresh user-handler/<refresh-id-token&access-token
          old-pub-event! state/pub-event!
          refresh-called (a/chan)
          expired-id-token (jwt {:exp 0})
          refresh-token "refresh-token-from-local-storage"
          restore! (fn []
                     (state/replace-state! old-state)
                     (set! user-handler/<refresh-id-token&access-token old-refresh)
                     (set! state/pub-event! old-pub-event!))]
      (state/replace-state! (assoc old-state
                                 :auth/id-token nil
                                 :auth/access-token nil
                                 :auth/refresh-token nil))
      (set! user-handler/<refresh-id-token&access-token
            (fn []
              (a/put! refresh-called :called)
              (a/go nil)))
      (set! state/pub-event! (fn [& _] nil))
      (try
        (with-mocked-local-storage
          {"id-token" expired-id-token
           "access-token" "access-token-from-local-storage"
           "refresh-token" refresh-token}
          (fn []
            (user-handler/restore-tokens-from-localstorage)
            (is (= refresh-token (state/get-auth-refresh-token)))))
        (a/go
          (let [[value] (a/alts! [refresh-called (a/timeout 1000)])]
            (is (= :called value))
            (restore!)
            (done)))
        (catch :default e
          (is false (str "unexpected error: " e))
          (restore!)
          (done))))))

(deftest restore-fresh-tokens-publishes-login-continuation-synchronously-test
  (let [old-state (state/get-state)
        events* (atom [])
        future-exp (+ (quot (.now js/Date) 1000) 7200)]
    (state/replace-state! (assoc old-state
                                :auth/id-token nil
                                :auth/access-token nil
                                :auth/refresh-token nil))
    (try
      (with-mocked-local-storage
        {"id-token" (jwt {:exp future-exp})
         "access-token" "access-token-from-local-storage"
         "refresh-token" "refresh-token-from-local-storage"}
        (fn []
          (with-redefs [state/pub-event! #(swap! events* conj %)]
            (user-handler/restore-tokens-from-localstorage)
            (is (= [[:user/fetch-info-and-graphs]] @events*)))))
      (finally
        (state/replace-state! old-state)))))

(deftest logout-clears-e2ee-password-when-db-worker-ready-test
  (testing "logout should request db-worker to clear persisted e2ee password"
    (let [ops* (atom [])
          old-worker @state/*db-worker
          old-login-user (:auth/current-login-user (state/get-state))]
      (reset! state/*db-worker (fn [method & _]
                                   (swap! ops* conj method)
                                   (p/resolved nil)))
      (try
        (with-mocked-local-storage
          (fn []
            (with-redefs [state/<invoke-db-worker (fn [op & _]
                                                    (swap! ops* conj op)
                                                    (p/resolved nil))
                          state/clear-user-info! (fn [] nil)
                          state/pub-event! (fn [& _] nil)
                          user-handler/clear-tokens (fn [] nil)]
              (user-handler/logout)
              (is (= [:thread-api/sync-app-state
                      :thread-api/db-sync-stop
                      :thread-api/clear-e2ee-password]
                     @ops*)))))
        (finally
          (state/set-state! :auth/current-login-user old-login-user)
          (reset! state/*db-worker old-worker))))))

(deftest can-list-remote-graphs-uses-default-server-test
  (with-redefs [config/dev? false
                config/get-custom-sync-server-url (constantly nil)
                state/user-groups (constantly #{})
                user-handler/logged-in? (constantly true)]
    (is (false? (user-handler/rtc-group?)))
    (is (true? (user-handler/can-list-remote-graphs?)))))

(deftest logout-skips-e2ee-password-clear-when-db-worker-missing-test
  (testing "logout should not call db-worker API when db-worker is unavailable"
    (let [invoke-calls* (atom 0)
          old-worker @state/*db-worker
          old-login-user (:auth/current-login-user (state/get-state))]
      (reset! state/*db-worker nil)
      (try
        (with-mocked-local-storage
          (fn []
            (with-redefs [state/<invoke-db-worker (fn [& _]
                                                    (swap! invoke-calls* inc)
                                                    (p/resolved nil))
                          state/clear-user-info! (fn [] nil)
                          state/pub-event! (fn [& _] nil)
                          user-handler/clear-tokens (fn [] nil)]
              (user-handler/logout)
              (is (zero? @invoke-calls*)))))
        (finally
          (state/set-state! :auth/current-login-user old-login-user)
          (reset! state/*db-worker old-worker))))))

(deftest logout-clears-only-account-state-and-preserves-device-settings-test
  (async done
    (let [old-state (state/get-state)
          old-worker @state/*db-worker
          calls (atom [])
          writes (atom [])
          events (atom [])
          device-settings {"sync-server-url" "https://sync.example.invalid"
                           "theme" "dark"
                           "radix-color" "violet"
                           "editor-font" "{:notes {:family \"Synthetic Font\"}}"
                           "preferred-language" "zh-CN"
                           "unrelated-device-setting" "keep"}]
      (state/replace-state! (assoc old-state
                                  :auth/id-token "synthetic-id"
                                  :auth/access-token "synthetic-access"
                                  :auth/refresh-token "synthetic-refresh"
                                  :user/info {:uuid "synthetic-user"}
                                  :system/info {:home-dir "/tmp/isolated-auth"}))
      (reset! state/*db-worker (fn [method & args]
                               (swap! calls conj (into [method] args))
                               (p/resolved nil)))
      (with-mocked-local-storage
        (merge device-settings {"id-token" "synthetic-id"
                                "access-token" "synthetic-access"
                                "refresh-token" "synthetic-refresh"
                                "CognitoIdentityServiceProvider.synthetic.refreshToken" "synthetic-refresh"
                                "user-groups" "#{:synthetic}"})
        (fn []
          (with-redefs [util/electron? (constantly true)
                        util/node-test? false
                        ipc/ipc (fn [op _ path content]
                                  (swap! writes conj [op path (js->clj (js/JSON.parse content) :keywordize-keys true)])
                                  (p/resolved nil))
                        state/pub-event! #(swap! events conj %)]
            (let [cleared (user-handler/logout)]
              (doseq [[key value] device-settings]
                (is (= value (.getItem js/localStorage key))))
              (doseq [key ["id-token" "access-token" "refresh-token"
                           "CognitoIdentityServiceProvider.synthetic.refreshToken" "user-groups"]]
                (is (not (seq (.getItem js/localStorage key)))))
              (is (nil? (:user/info (state/get-state))))
              (is (not (user-handler/logged-in?)))
              (is (= [[:user/logout]] @events))
              (is (= [[:thread-api/sync-app-state
                       {:auth/id-token nil :auth/access-token nil :auth/refresh-token nil :user/info nil}]
                      [:thread-api/db-sync-stop]
                      [:thread-api/clear-e2ee-password]] @calls))
              (is (= ["writeFile" "/tmp/isolated-auth/logseq/auth.json"] (take 2 (first @writes))))
              (is (= {:id-token nil :access-token nil :refresh-token nil}
                     (select-keys (nth (first @writes) 2) [:id-token :access-token :refresh-token])))
              (-> cleared
                  (p/catch #(is false (str %)))
                  (p/finally (fn []
                               (state/replace-state! old-state)
                               (reset! state/*db-worker old-worker)
                               (done)))))))))))
