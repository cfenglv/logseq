(ns logseq.db-sync.user-navigation-http-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [datascript.core :as d]
            [logseq.common.user-navigation :as navigation]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.node.dispatch :as node-dispatch]
            [logseq.db-sync.node.graph :as node-graph]
            [logseq.db-sync.node.storage :as node-storage]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.user-navigation-test :as fixture]
            [logseq.db-sync.worker.dispatch :as worker-dispatch]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.handler.user-navigation :as handler]
            [promesa.core :as p]))

(defn- request [token method path & [body]]
  (js/Request. (str "http://localhost" path)
               (clj->js (cond-> {:method method :headers {"authorization" (str "Bearer " token)}}
                          body (assoc :body (js/JSON.stringify (clj->js body)))))))

(defn- <json [response]
  (p/let [body (.text response)]
    (js->clj (js/JSON.parse body) :keywordize-keys true)))

(deftest account-private-native-graph-initialization-test
  (async done
    (let [dir (.mkdtempSync fs (node-path/join (.tmpdir os) "db-sync-navigation-test-"))
          db (node-storage/open-index-db dir)
          registry (atom {})
          issuer "https://identity.example.test/navigation"
          jwks-url (str "https://identity.example.test/jwks/" (random-uuid))
          config {:data-dir dir :cognito-issuer issuer :cognito-client-id "synthetic-client" :cognito-jwks-url jwks-url}
          deps {:config config :index-db db}
          env #js {"DB" db "COGNITO_ISSUER" issuer "COGNITO_CLIENT_ID" "synthetic-client" "COGNITO_JWKS_URL" jwks-url}
          <node (fn [request] (node-dispatch/handle-node-fetch {:request request :env env :registry registry :deps deps}))
          <init (fn [token candidate] (<node (request token "POST" "/user/navigation/init" {:scopes [candidate]})))
          candidate {:scope fixture/scope :device-id fixture/device :favorites-seed [fixture/x] :recents-seed [fixture/x]}]
      (-> (p/let [pair (.generateKey js/crypto.subtle #js {:name "RSASSA-PKCS1-v1_5" :hash "SHA-256" :modulusLength 2048
                                                        :publicExponent (js/Uint8Array. #js [1 0 1])} true #js ["sign" "verify"])
                  jwk (.exportKey js/crypto.subtle "jwk" (.-publicKey pair))
                  _ (aset jwk "kid" "navigation-key")
                  encode (fn [value] (.toString (js/Buffer.from (js/JSON.stringify (clj->js value))) "base64url"))
                  sign (fn [user device]
                         (let [input (str (encode {:alg "RS256" :kid "navigation-key"}) "."
                                          (encode {:iss issuer :aud "synthetic-client" :sub user :jti device
                                                   :exp (+ (js/Math.floor (/ (.now js/Date) 1000)) 3600)}))]
                           (p/let [signed (.sign js/crypto.subtle "RSASSA-PKCS1-v1_5" (.-privateKey pair)
                                                (.encode (js/TextEncoder.) input))]
                             (str input "." (.toString (js/Buffer.from signed) "base64url")))))
                  a1 (sign "user-a" "a1") a2 (sign "user-a" "a2") b1 (sign "user-b" "b1")
                  _ (index/<index-init! db)
                  _ (index/<index-upsert! db fixture/scope "Notes" "user-a" "65" false)
                  _ (index/<graph-member-upsert! db fixture/scope "user-b" "member" "user-a")]
            (p/with-redefs [js/fetch (fn [url]
                                      (is (= jwks-url url))
                                      (p/resolved (js/Response. (js/JSON.stringify #js {:keys #js [jwk]}))))]
              (p/let [responses (p/all [(<init a1 candidate) (<init a2 candidate)])
                      bodies (p/all (map <json responses))
                      private-id (navigation/graph-id "user-a")
                      ctx (get @registry private-id)
                      conn (.-conn ^js ctx)
                      first-t (storage/get-t (.-sql ^js ctx))
                      repeated (<init a1 (assoc candidate :favorites-seed [] :recents-seed []))
                      _ (is (= [200 200] (mapv #(.-status %) responses)))
                      _ (is (= [private-id private-id] (mapv :graph-id bodies)))
                      _ (is (= first-t (storage/get-t (.-sql ^js ctx))))
                      _ (is (= [fixture/x] (navigation/favorites (handler/scope-values @conn fixture/scope) fixture/scope)))
                      _ (is (empty? (d/datoms @conn :avet :block/uuid)) "Private KV init creates no Page.")
                      node-list (<node (request a1 "GET" "/graphs"))
                      list-body (<json node-list)
                      _ (is (= [fixture/scope] (mapv :graph-id (:graphs list-body))))
                      name-exists? (index/<graph-name-exists? db "User navigation" "user-a")
                      _ (is (false? name-exists?))
                      forged (js/Request. (str "http://localhost/sync/" fixture/scope "/sync/unused/user-navigation/init")
                                          #js {:method "POST" :headers #js {:authorization (str "Bearer " a1)}
                                               :body (common/write-transit {:scopes [(assoc candidate :values {})]})})
                      nested-response (<node forged)
                      _ (is (= 404 (.-status nested-response)) "Nested sync paths cannot invoke internal initialization.")
                      failed-response (p/with-redefs [sync-handler/handle-http
                                                       (fn [& _] (p/resolved (js/Response. "{\"error\":\"synthetic-denial\"}" #js {:status 403})))]
                                        (<init a1 candidate))
                      _ (is (= 403 (.-status failed-response)))
                      _ (is (false? (.-bodyUsed failed-response)) "Return unread Graph errors to the HTTP adapter.")
                      _ (p/doseq [[method path body]
                                 [["GET" (str "/graphs/" private-id "/members") nil]
                                  ["POST" (str "/graphs/" private-id "/members") {:user-id "user-b"}]
                                  ["POST" "/api/v1/personal-access-tokens" {:graph-id private-id :permission "both"}]]]
                          (p/let [response (<node (request a1 method path body))] (is (= 403 (.-status response)))))
                      other-access (<node (request b1 "GET" (str "/sync/" private-id "/pull?since=0")))
                      _ (is (= 403 (.-status other-access)))
                      other-init (<init b1 (assoc candidate :favorites-seed [fixture/y]))
                      other-body (<json other-init)
                      _ (is (not= private-id (:graph-id other-body)))
                      _ (is (= [fixture/y] (navigation/favorites
                                           (handler/scope-values @(.-conn ^js (get @registry (:graph-id other-body))) fixture/scope)
                                           fixture/scope)))
                      _ (d/transact! conn [{:db/ident (navigation/ident :favorite fixture/scope fixture/x) :kv/value {:selected? false}}])
                      transfer (<init a2 (assoc candidate :transfer-id fixture/member
                                               :values (common/write-transit
                                                        {(navigation/ident :favorite fixture/scope fixture/x)
                                                         {:selected? true :membership-id fixture/new-member :added-at 1}})))
                      _ (is (= 200 (.-status transfer)))
                      _ (is (= [] (navigation/favorites (handler/scope-values @conn fixture/scope) fixture/scope)))
                      rejected (<init a1 (assoc candidate :scope "99999999-9999-4999-8999-999999999999"))
                      rejected-body (<json rejected)
                      _ (is (= "forbidden" (-> rejected-body :scopes first :status)))
                      _ (aset env "LOGSEQ_SYNC_DO"
                              #js {:idFromName identity
                                   :get (fn [id] #js {:fetch (fn [request]
                                                              (sync-handler/handle-http (node-graph/get-or-create-graph registry deps id) request))})})
                      nested-worker (worker-dispatch/handle-worker-fetch
                                     (js/Request. (.-url forged) #js {:method "POST" :headers #js {:authorization (str "Bearer " a1)}
                                                                    :body (common/write-transit {:scopes [(assoc candidate :values {})]})}) env)
                      _ (is (= 404 (.-status nested-worker)))
                      _ (is (nil? (.-conn ^js (get @registry fixture/scope))))
                      worker-result (worker-dispatch/handle-worker-fetch
                                     (request a2 "POST" "/user/navigation/init"
                                              {:scopes [(assoc candidate :device-id fixture/other-device :recents-seed [fixture/y])]}) env)
                      _ (is (= 200 (.-status worker-result)))
                      _ (is (= [fixture/x fixture/y] (navigation/recents (handler/scope-values @conn fixture/scope) fixture/scope)))
                      ^js state (.-state ^js ctx)
                      sockets (.-getWebSockets state)
                      _ (aset state "getWebSockets" (fn [] #js [#js {:readyState 1 :send (fn [_] (throw (js/Error. "synthetic send failure")))}]))
                      broadcast-failure (<init a1 (assoc candidate :device-id fixture/new-member :recents-seed []))
                      _ (aset state "getWebSockets" sockets)
                      _ (is (= 500 (.-status broadcast-failure)))
                      _ (is (= [] (:kv/value (d/entity @conn (navigation/ident :recents-seed fixture/scope fixture/new-member))))
                            "A post-commit broadcast error must not rewind the committed DB.")
                      raw-response (worker-dispatch/handle-worker-fetch
                                    (request a1 "POST" (str "/sync/" private-id "/user-navigation/init") {}) env)
                      _ (is (= 404 (.-status raw-response)))
                      snapshot (handler/scope-values @conn fixture/scope)
                      _ (node-graph/close-graphs! registry)
                      _ (reset! registry {})
                      reopened (<init a1 candidate)
                      reopened-conn (.-conn ^js (get @registry private-id))]
                (is (= 200 (.-status repeated) (.-status reopened)))
                (is (= snapshot (handler/scope-values @reopened-conn fixture/scope)))
                (is (empty? (d/datoms @reopened-conn :avet :block/uuid))))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally (fn []
                       (node-graph/close-graphs! registry)
                       (.close db)
                       (.rmSync fs dir #js {:recursive true})
                       (done)))))))
