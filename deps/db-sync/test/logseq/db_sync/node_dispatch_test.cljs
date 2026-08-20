(ns logseq.db-sync.node-dispatch-test
  (:require [cljs.test :refer [async deftest is]]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.node.dispatch :as node-dispatch]
            [logseq.db-sync.node.graph :as graph]
            [logseq.db-sync.worker.auth :as auth]
            [logseq.db-sync.worker.handler.index :as index-handler]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.http :as http]
            [promesa.core :as p]))

(defn- json-body [response]
  (p/let [text (.text response)]
    (js->clj (js/JSON.parse text) :keywordize-keys true)))

(deftest public-sync-route-never-forwards-internal-revoke-user-test
  (async done
         (let [forwarded* (atom 0)
               request
               (js/Request.
                "http://localhost/sync/graph-1/internal/revoke-user?user-id=member-1"
                #js {:method "POST"
                     :headers
                     #js {"x-db-sync-admin-token" "test-admin-token"}})
               env #js {"DB_SYNC_ADMIN_TOKEN" "test-admin-token"}]
           (-> (p/with-redefs
                 [graph/get-or-create-graph (fn [& _] #js {})
                  sync-handler/handle-http
                  (fn [_ctx _request]
                    (swap! forwarded* inc)
                    (p/resolved
                     (http/json-response :sync/health {:ok true})))]
                 (p/let [response
                         (node-dispatch/handle-node-fetch
                          {:request request
                           :env env
                           :registry (atom {})
                           :deps {}})
                         body (json-body response)]
                   (is (= 404 (.-status response)))
                   (is (= {:error "not found"} body))
                   (is (zero? @forwarded*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest destructive-sync-routes-require-graph-owner-test
  (async done
         (let [forwarded* (atom 0)
               request (js/Request. "http://localhost/sync/graph-1/admin/reset"
                                    #js {:method "DELETE"
                                         :headers #js {"authorization" "Bearer member-token"}})
               env #js {"DB" #js {}}]
           (-> (p/with-redefs [auth/auth-claims (fn [_request _env]
                                                  (p/resolved #js {"sub" "shared-member"}))
                               index/<user-owns-graph? (fn [_db _graph-id _user-id]
                                                        (p/resolved false))
                               index-handler/graph-access-response (fn [_request _env _graph-id]
                                                                     (p/resolved
                                                                      (http/json-response
                                                                       :graphs/access
                                                                       {:ok true})))
                               graph/get-or-create-graph (fn [& _]
                                                           #js {})
                               sync-handler/handle-http (fn [_ctx _request]
                                                          (swap! forwarded* inc)
                                                          (p/resolved
                                                           (http/json-response
                                                            :sync/health
                                                            {:ok true})))]
                 (p/let [response (node-dispatch/handle-node-fetch
                                   {:request request
                                    :env env
                                    :registry (atom {})
                                    :deps {}})
                         body (json-body response)]
                   (is (= 403 (.-status response)))
                   (is (= {:error "forbidden"} body))
                   (is (zero? @forwarded*))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest snapshot-upload-v2-requires-graph-owner-test
  (async done
         (let [forwarded* (atom 0)
               request (js/Request. "http://localhost/sync/graph-1/snapshot/upload-v2?reset=true"
                                    #js {:method "POST"
                                         :headers #js {"authorization" "Bearer member-token"
                                                       "content-type" "application/octet-stream"}
                                         :body "snapshot"})
               env #js {"DB" #js {}}]
           (-> (p/with-redefs [auth/auth-claims (fn [_request _env]
                                                  (p/resolved #js {"sub" "shared-member"}))
                               index/<user-owns-graph? (fn [_db _graph-id _user-id]
                                                        (p/resolved false))
                               index-handler/graph-access-response (fn [_request _env _graph-id]
                                                                     (p/resolved
                                                                      (http/json-response
                                                                       :graphs/access
                                                                       {:ok true})))
                               graph/get-or-create-graph (fn [& _]
                                                           #js {})
                               sync-handler/handle-http (fn [_ctx _request]
                                                          (swap! forwarded* inc)
                                                          (p/resolved
                                                           (http/json-response
                                                            :sync/health
                                                            {:ok true})))]
                 (p/let [response (node-dispatch/handle-node-fetch
                                   {:request request
                                    :env env
                                    :registry (atom {})
                                    :deps {}})
                         body (json-body response)]
                   (is (= 403 (.-status response)))
                   (is (= {:error "forbidden"} body))
                   (is (zero? @forwarded*))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest legacy-v1-snapshot-upload-is-forwarded-unchanged-test
  (async done
         (let [forwarded* (atom nil)
               request (js/Request.
                        "http://localhost/sync/graph-1/snapshot/upload?reset=true&finished=true&checksum=legacy-v1"
                        #js {:method "POST"
                             :headers #js {"authorization" "Bearer owner-token"
                                           "content-type" "application/transit+json"}
                             :body "legacy-v1-snapshot"})
               env #js {"DB" #js {}}]
           (-> (p/with-redefs [index-handler/graph-owner-response
                               (fn [_request _env _graph-id]
                                 (p/resolved
                                  (http/json-response
                                   :graphs/access
                                   {:ok true})))
                               graph/get-or-create-graph (fn [& _]
                                                           #js {})
                               sync-handler/handle-http
                               (fn [_ctx forwarded-request]
                                 (p/let [forwarded-url (js/URL. (.-url forwarded-request))
                                         forwarded-body (.text forwarded-request)]
                                   (reset! forwarded*
                                           {:path (.-pathname forwarded-url)
                                            :graph-id (.get (.-searchParams forwarded-url) "graph-id")
                                            :reset (.get (.-searchParams forwarded-url) "reset")
                                            :finished (.get (.-searchParams forwarded-url) "finished")
                                            :checksum (.get (.-searchParams forwarded-url) "checksum")
                                            :body forwarded-body})
                                   (http/json-response :sync/health {:ok true})))]
                 (p/let [response (node-dispatch/handle-node-fetch
                                   {:request request
                                    :env env
                                    :registry (atom {})
                                    :deps {}})]
                   (is (= 200 (.-status response)))
                   (is (= {:path "/snapshot/upload"
                           :graph-id "graph-1"
                           :reset "true"
                           :finished "true"
                           :checksum "legacy-v1"
                           :body "legacy-v1-snapshot"}
                          @forwarded*))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))
