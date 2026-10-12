(ns logseq.db-sync.user-profile-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [logseq.common.authorization :as authorization]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.node.dispatch :as node-dispatch]
            [logseq.db-sync.node.storage :as node-storage]
            [logseq.db-sync.worker.dispatch :as worker-dispatch]
            [logseq.db-sync.worker.handler.index :as index-handler]
            [logseq.db-sync.worker.handler.user-profile :as user-profile]
            [promesa.core :as p]))

(def avatar-png
  "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAABL0lEQVR4nO3SIQEAIBDAwI9CTOLQFGIgduL8xGbtc+ma3wEYAANgAAyAATAABsAAGAADYAAMgAEwAAbAABgAA2AADIABMAAGwAAYAANgAAyAATAABsAAGAADYAAMgAEwAAbAABgAA2CAOAPEGSDOAHEGiDNAnAHiDBBngDgDxBkgzgBxBogzQJwB4gwQZ4A4A8QZIM4AcQaIM0CcAeIMEGeAOAPEGSDOAHEGiDNAnAHiDBBngDgDxBkgzgBxBogzQJwB4gwQZ4A4A8QZIM4AcQaIM0CcAeIMEGeAOAPEGSDOAHEGiDNAnAHiDBBngDgDxBkgzgBxBogzQJwB4gwQZ4A4A8QZIM4AcQaIM0CcAeIMEGeAOAPEGSDOAHEGiDNAnAHiDBBngDgDxBkgzgBxD81PyESg8P04AAAAAElFTkSuQmCC")

(defn- request
  ([method path token] (request method path token nil))
  ([method path token body]
   (js/Request. (str "http://localhost" path)
                (clj->js (cond-> {:method method}
                           token (assoc :headers {:authorization (str "Bearer " token)})
                           (some? body) (assoc :body (if (string? body) body (js/JSON.stringify (clj->js body)))))))))

(defn- <json [response]
  (p/let [text (.text response)]
    (js->clj (js/JSON.parse text) :keywordize-keys true)))

(deftest user-profile-http-storage-and-permissions-test
  (async done
         (let [dir (.mkdtempSync fs (node-path/join (.tmpdir os) "db-sync-profile-http-test-"))
               db (node-storage/open-index-db dir)
               env #js {:DB db}
               self #js {:d1 db :env env}
               user-id (str "profile-owner-" (random-uuid))
               member-id (str "profile-member-" (random-uuid))
               outsider-id (str "profile-outsider-" (random-uuid))
               graph-id (str "profile-graph-" (random-uuid))
               avatar-path (str "/graphs/" graph-id "/members/" member-id "/avatar")
               <fetch #(index-handler/handle-fetch self %)
               logs (atom [])
               console-error (.-error js/console)
               _ (set! (.-error js/console) (fn [& args] (swap! logs conj args)))]
           (-> (p/with-redefs [authorization/verify-jwt
                               (fn [token _env]
                                 (case token
                                   "profile-owner-token" (p/resolved #js {:sub user-id :email "owner@example.com"
                                                                        "cognito:username" "Owner"})
                                   "profile-member-token" (p/resolved #js {:sub member-id :email "member@example.com"})
                                   "profile-outsider-token" (p/resolved #js {:sub outsider-id})
                                   "profile-expired-token" (p/rejected (ex-info "exp" {}))
                                   (p/rejected (ex-info "invalid" {}))))]
                 (p/let [_ (index/<index-init! db)
                         missing (<fetch (request "GET" "/user/profile" nil))
                         forged (<fetch (request "PUT" "/user/profile" "forged-token" {}))
                         expired (<fetch (request "GET" "/user/profile" "profile-expired-token"))
                         _ (is (= [401 401 401] (mapv #(.-status %) [missing forged expired])))
                         initial (worker-dispatch/handle-worker-fetch
                                  (request "GET" "/user/profile" "profile-owner-token") env)
                         initial-body (<json initial)
                         _ (is (= 0 (:profile-revision initial-body)))
                         _ (is (= "private, no-store" (.get (.-headers initial) "cache-control")))
                         saved (<fetch (request "PUT" "/user/profile" "profile-owner-token"
                                                {:display-name "  用户 😀  " :avatar-png-base64 avatar-png :expected-revision 0}))
                         saved-body (<json saved)
                         _ (is (= 200 (.-status saved)))
                         _ (is (= "用户 😀" (:display-name saved-body)))
                         _ (is (= avatar-png (:avatar-png-base64 saved-body)))
                         _ (is (= 1 (:profile-revision saved-body)))
                         read-node (node-dispatch/handle-node-fetch
                                    {:request (request "GET" "/user/profile" "profile-owner-token") :env env})
                         read-body (<json read-node)
                         _ (is (= saved-body read-body))
                         stale (<fetch (request "PUT" "/user/profile" "profile-owner-token"
                                                {:display-name "Stale" :avatar-png-base64 nil :expected-revision 0}))
                         _ (is (= 409 (.-status stale)))
                         _ (index/<user-upsert! db #js {:sub member-id :email "member@example.com"})
                         _ (index/<user-profile-save! db member-id
                                                     {:display-name "Member" :avatar-png-base64 avatar-png :expected-revision 0})
                         _ (index/<index-upsert! db graph-id "Profile Test" user-id "65" false)
                         _ (index/<graph-member-upsert! db graph-id user-id "manager" nil)
                         _ (index/<graph-member-upsert! db graph-id member-id "member" user-id)
                         avatar (<fetch (request "GET" avatar-path "profile-owner-token"))
                         avatar-body (<json avatar)
                         _ (is (= 200 (.-status avatar)))
                         _ (is (= member-id (:user-id avatar-body)))
                         _ (is (= avatar-png (:avatar-png-base64 avatar-body)))
                         denied (<fetch (request "GET" avatar-path "profile-outsider-token"))
                         _ (is (= 403 (.-status denied)))
                         _ (index/<graph-member-delete! db graph-id member-id)
                         removed-member (<fetch (request "GET" avatar-path "profile-owner-token"))
                         _ (is (= 403 (.-status removed-member)))
                         _ (p/doseq [body ["not-json"
                                          {:display-name " " :avatar-png-base64 nil :expected-revision 1}
                                          {:display-name "User" :avatar-png-base64 nil :expected-revision "1"}
                                          {:display-name "User" :avatar-png-base64 "not-base64" :expected-revision 1}
                                          {:display-name "User" :avatar-png-base64 "data:image/png;base64,abc" :expected-revision 1}
                                          {:display-name "User" :avatar-png-base64 avatar-png :expected-revision 1 :user-id member-id}]]
                             (p/let [invalid (<fetch (request "PUT" "/user/profile" "profile-owner-token" body))]
                               (is (= 400 (.-status invalid)))))
                         ^js wrong-size (js/Buffer.from avatar-png "base64")
                         _ (.writeUInt32BE wrong-size 127 16)
                         invalid-png (<fetch (request "PUT" "/user/profile" "profile-owner-token"
                                                      {:display-name "User" :avatar-png-base64 (.toString wrong-size "base64")
                                                       :expected-revision 1}))
                         _ (is (= 400 (.-status invalid-png)))
                         ^js oversized-png (js/Buffer.alloc (inc user-profile/avatar-max-bytes))
                         _ (.copy (js/Buffer.from avatar-png "base64") oversized-png)
                         invalid-png-size (<fetch (request "PUT" "/user/profile" "profile-owner-token"
                                                           {:display-name "User" :avatar-png-base64 (.toString oversized-png "base64")
                                                            :expected-revision 1}))
                         _ (is (= 400 (.-status invalid-png-size)))
                         oversized (<fetch (request "PUT" "/user/profile" "profile-owner-token"
                                                    (apply str (repeat (inc user-profile/request-max-bytes) "a"))))
                         _ (is (= 413 (.-status oversized)))
                         unchanged (index/<user-profile db user-id)
                         _ (is (= saved-body unchanged))
                         cleared (<fetch (request "PUT" "/user/profile" "profile-owner-token"
                                                  {:display-name nil :avatar-png-base64 nil :expected-revision 1}))
                         cleared-body (<json cleared)]
                   (is (= 200 (.-status cleared)))
                   (is (= {:display-name nil :avatar-png-base64 nil :has-avatar? false :profile-revision 2}
                          (select-keys cleared-body [:display-name :avatar-png-base64 :has-avatar? :profile-revision])))
                   (is (empty? @logs))))
               (p/catch (fn [error] (is false (str error))))
               (p/finally (fn []
                            (set! (.-error js/console) console-error)
                            (.close db)
                            (.rmSync fs dir #js {:recursive true})
                            (done)))))))

(deftest user-profile-streaming-body-limit-test
  (async done
         (let [cancelled? (atom false)
               reads (atom 0)
               stream (js/ReadableStream.
                       #js {:pull (fn [controller]
                                    (swap! reads inc)
                                    (.enqueue controller (js/Uint8Array. 65537)))
                            :cancel (fn [] (reset! cancelled? true))})
               profile-request (js/Request. "http://localhost/user/profile"
                                            #js {:method "PUT" :body stream :duplex "half"})]
           (-> (p/let [response (user-profile/handle
                                {:db :db :claims #js {:sub "profile-user"} :request profile-request
                                 :route {:handler :user-profile/save}})]
                 (is (= 413 (.-status response)))
                 (is (true? @cancelled?))
                 (is (<= @reads 4)))
               (p/catch (fn [error] (is false (str error))))
               (p/finally done)))))

(deftest user-profile-failed-save-does-not-expose-image-test
  (async done
         (let [logs (atom [])
               console-error (.-error js/console)
               _ (set! (.-error js/console) (fn [& args] (swap! logs conj args)))]
           (-> (p/with-redefs [index/<user-profile-save!
                               (fn [& _] (p/rejected (ex-info (str "database error " avatar-png)
                                                            {:value avatar-png})))]
                 (p/let [response (user-profile/handle
                                  {:db :db :claims #js {:sub "profile-user"}
                                   :route {:handler :user-profile/save}
                                   :request (request "PUT" "/user/profile" "profile-owner-token"
                                                     {:display-name "User" :avatar-png-base64 avatar-png :expected-revision 0})})
                         body (<json response)]
                   (is (= 500 (.-status response)))
                   (is (= {:error "profile-server-error"} body))
                   (is (empty? @logs))))
               (p/catch (fn [error] (is false (str error))))
               (p/finally (fn []
                            (set! (.-error js/console) console-error)
                            (done)))))))
