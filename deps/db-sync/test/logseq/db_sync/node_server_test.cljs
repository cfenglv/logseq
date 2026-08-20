(ns logseq.db-sync.node-server-test
  (:require [cljs.test :refer [async deftest is]]
            [logseq.db-sync.node.graph :as graph]
            [logseq.db-sync.node.server :as node-server]
            [logseq.db-sync.node.storage :as node-storage]
            [logseq.db-sync.platform.node :as platform-node]
            [logseq.db-sync.worker.auth :as auth]
            [promesa.core :as p]))

(deftest node-graph-delete-terminates-websockets-before-closing-storage-test
  (let [events* (atom [])
        socket #js {:terminate (fn []
                                (swap! events* conj :socket-terminated))}
        sql #js {:close (fn []
                         (swap! events* conj :sql-closed))}
        ctx #js {:state #js {:getWebSockets (fn [] #js [socket])}
                 :sql sql}
        registry (atom {"graph-1" ctx})]
    (with-redefs [node-storage/delete-graph-db!
                  (fn [_data-dir _graph-id]
                    (swap! events* conj :storage-deleted))]
      (graph/delete-graph! registry {:config {:data-dir "/tmp/test"}}
                           "graph-1"))
    (is (= [:socket-terminated :sql-closed :storage-deleted]
           @events*))
    (is (true? (.-deleting ctx)))
    (is (empty? @registry))))

(defn- fetch-with-timeout [url timeout-ms]
  (let [timeout-sentinel ::timeout]
    {:sentinel timeout-sentinel
     :promise (js/Promise.
               (fn [resolve reject]
                 (let [controller (js/AbortController.)
                       timeout-id (js/setTimeout
                                   (fn []
                                     (.abort controller))
                                   timeout-ms)]
                   (-> (js/fetch url #js {:method "GET"
                                          :headers #js {"authorization" "Bearer test.token.sig"}
                                          :signal (.-signal controller)})
                       (.then (fn [response]
                                (js/clearTimeout timeout-id)
                                (resolve response)))
                       (.catch (fn [error]
                                 (js/clearTimeout timeout-id)
                                 (if (= "AbortError" (.-name error))
                                   (resolve timeout-sentinel)
                                   (reject error))))))))}))

(deftest node-server-returns-500-when-auth-claims-rejects-test
  (async done
         (let [stop-server! (atom nil)
               test-url (atom nil)]
           (-> (p/with-redefs [auth/auth-claims
                               (fn [_request _env]
                                 (p/rejected (ex-info "jwks" {})))]
                 (p/let [{:keys [base-url stop!]} (node-server/start! {:port 0
                                                                       :data-dir (str "tmp/db-sync-node-server-test/" (random-uuid))})
                         _ (reset! stop-server! stop!)
                         _ (reset! test-url (str base-url "/graphs"))
                         {:keys [promise sentinel]} (fetch-with-timeout @test-url 1200)
                         response promise]
                   (if (identical? response sentinel)
                     (is false "request timed out")
                     (p/let [body (.json response)]
                       (is (= 500 (.-status response)))
                       (is (= "server error" (aget body "error")))))))
               (p/then
                (fn []
                  (if-let [stop! @stop-server!]
                    (-> (stop!)
                        (p/then (fn [] (done)))
                        (p/catch (fn [error]
                                   (is false (str error))
                                   (done))))
                    (done))))
               (p/catch
                (fn [error]
                  (if-let [stop! @stop-server!]
                    (-> (stop!)
                        (p/then (fn []
                                  (is false (str error))
                                  (done)))
                        (p/catch (fn [stop-error]
                                   (is false (str error))
                                   (is false (str stop-error))
                                   (done))))
                    (do
                      (is false (str error))
                      (done)))))))))

(deftest node-server-request-origin-uses-configured-base-url-host-test
  (async done
         (let [stop-server! (atom nil)
               request-opts (atom [])
               original-request-from-node platform-node/request-from-node]
           (-> (p/with-redefs [platform-node/request-from-node
                               (fn [req opts]
                                 (swap! request-opts conj opts)
                                 (original-request-from-node req opts))]
                 (p/let [{:keys [port stop!]} (node-server/start! {:port 0
                                                                   :base-url "https://sync.example.test:9443"
                                                                   :data-dir (str "tmp/db-sync-node-server-base-url-test/" (random-uuid))})
                         _ (reset! stop-server! stop!)
                         response (js/fetch (str "http://localhost:" port "/health"))]
                   (is (= 200 (.-status response)))
                   (is (some #(= {:scheme "https"
                                  :host "sync.example.test:9443"}
                                %)
                             @request-opts))))
               (p/then
                (fn []
                  (if-let [stop! @stop-server!]
                    (-> (stop!)
                        (p/then (fn [] (done)))
                        (p/catch (fn [error]
                                   (is false (str error))
                                   (done))))
                    (done))))
               (p/catch
                (fn [error]
                  (if-let [stop! @stop-server!]
                    (-> (stop!)
                        (p/then (fn []
                                  (is false (str error))
                                  (done)))
                        (p/catch (fn [stop-error]
                                   (is false (str error))
                                   (is false (str stop-error))
                                   (done))))
                    (do
                      (is false (str error))
                      (done)))))))))

(deftest node-server-start-rejects-when-port-is-already-in-use-test
  (async done
         (let [first-server* (atom nil)
               cleanup! (fn []
                          (if-let [stop! (:stop! @first-server*)]
                            (stop!)
                            (p/resolved nil)))
               finish! (fn [assertions]
                         (try
                           (assertions)
                           (catch :default error
                             (is false (str error))))
                         (-> (cleanup!)
                             (p/then (fn [] (done)))
                             (p/catch (fn [error]
                                        (is false (str error))
                                        (done)))))]
           (-> (node-server/start!
                {:port 0
                 :data-dir
                 (str "tmp/db-sync-node-server-port-owner/" (random-uuid))})
               (p/then
                (fn [first-server]
                  (reset! first-server* first-server)
                  (let [second-start
                        (try
                          (node-server/start!
                           {:port (:port first-server)
                            :data-dir
                            (str "tmp/db-sync-node-server-port-conflict/"
                                 (random-uuid))})
                          (catch :default error
                            (p/rejected error)))]
                    (.then (js/Promise.resolve second-start)
                           (fn [second-server]
                             (when-let [stop! (:stop! second-server)]
                               (stop!))
                             (finish!
                              #(is false "expected EADDRINUSE rejection")))
                           (fn [error]
                             (finish!
                              #(is (= "EADDRINUSE" (.-code error)))))))))
               (p/catch
                (fn [error]
                  (finish! #(is false (str error)))))))))
