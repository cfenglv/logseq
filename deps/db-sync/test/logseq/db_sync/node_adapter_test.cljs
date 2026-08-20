(ns logseq.db-sync.node-adapter-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [logseq.db-sync.node.server :as node-server]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.worker.auth :as auth]
            [promesa.core :as p]))

(def test-token "header.payload.signature")

(defn- auth-headers []
  #js {"authorization" (str "Bearer " test-token)
       "content-type" "application/json"})

(defn- post-json [url body]
  (js/fetch url #js {:method "POST"
                     :headers (auth-headers)
                     :body (js/JSON.stringify (clj->js body))}))

(defn- get-json [url]
  (js/fetch url #js {:method "GET" :headers (auth-headers)}))

(defn- parse-json [resp]
  (.json resp))

(defn- start-test-server []
  (let [dir (.mkdtempSync fs (node-path/join (.tmpdir os) "logseq-db-sync-node-test-"))]
    (p/let [server (node-server/start! {:port 0
                                        :data-dir dir})]
      (assoc server :test-data-dir dir))))

(defn- cleanup-test-server!
  [server]
  (p/let [_ (when-let [stop! (:stop! server)]
              (stop!))]
    (when-let [dir (:test-data-dir server)]
      (.rmSync fs dir #js {:recursive true :force true}))))

(defn- wait-until!
  [pred timeout-ms]
  (p/loop [remaining timeout-ms]
    (cond
      (pred)
      true

      (pos? remaining)
      (p/let [_ (p/delay 10)]
        (p/recur (- remaining 10)))

      :else
      (p/rejected (ex-info "timed out waiting for node adapter"
                           {:timeout-ms timeout-ms})))))

(defn- test-claims
  [& _]
  (p/resolved #js {:sub "node-adapter-test-user"
                   :email "node-adapter@example.test"}))

(deftest node-adapter-http-roundtrip-test
  (async done
         (let [server* (atom nil)]
           (->
            (p/with-redefs
              [auth/auth-claims test-claims]
              (p/let [{:keys [base-url] :as server} (start-test-server)
                      _ (reset! server* server)
                      health-resp (js/fetch (str base-url "/health"))
                      health-body (parse-json health-resp)]
                (testing "health"
                  (is (.-ok health-resp))
                  (is (= true (aget health-body "ok"))))
                (p/let [create-resp (post-json
                                     (str base-url "/graphs")
                                     {:graph-name "Test Graph"
                                      :graph-e2ee? false})
                        create-body (parse-json create-resp)
                        graph-id (aget create-body "graph-id")
                        access-resp (get-json
                                     (str base-url "/graphs/" graph-id "/access"))
                        access-body (parse-json access-resp)
                        sync-health (get-json
                                     (str base-url "/sync/" graph-id "/health"))
                        sync-health-body (parse-json sync-health)]
                  (testing "graph access"
                    (is (.-ok create-resp))
                    (is (string? graph-id))
                    (is (.-ok access-resp))
                    (is (= true (aget access-body "ok"))))
                  (testing "sync health"
                    (is (.-ok sync-health))
                    (is (= true (aget sync-health-body "ok"))))
                  (p/let [asset-id (random-uuid)
                          asset-url (str base-url "/assets/" graph-id "/" asset-id ".bin")
                          asset-bytes (js/Uint8Array. #js [0 1 2 255])
                          asset-put (js/fetch
                                     asset-url
                                     #js {:method "PUT"
                                          :headers #js {"authorization" (str "Bearer " test-token)
                                                        "content-type" "application/octet-stream"
                                                        "x-amz-meta-checksum" "asset-checksum"
                                                        "x-logseq-asset-size" "4"}
                                          :body asset-bytes})
                          asset-get (get-json asset-url)
                          asset-buffer (.arrayBuffer asset-get)
                          downloaded-bytes (js/Uint8Array. asset-buffer)]
                    (testing "asset stream roundtrip"
                      (is (.-ok asset-put))
                      (is (.-ok asset-get))
                      (is (= [0 1 2 255]
                             (vec (js/Array.from downloaded-bytes)))))
                    (p/let [tx-data [{:block/uuid (random-uuid)
                                      :block/title "hello"}]
                            tx-entry {:tx (protocol/tx->transit tx-data)
                                      :outliner-op :save-block}
                            tx-resp (post-json
                                     (str base-url "/sync/" graph-id "/tx/batch")
                                     {:t-before 0
                                      :txs [tx-entry]})
                            tx-body (parse-json tx-resp)
                            pull-resp (get-json
                                       (str base-url
                                            "/sync/"
                                            graph-id
                                            "/pull?since=0"))
                            pull-body (parse-json pull-resp)]
                      (testing "tx batch"
                        (is (.-ok tx-resp))
                        (is (= "tx/batch/ok" (aget tx-body "type"))))
                      (testing "pull"
                        (is (.-ok pull-resp))
                        (is (= "pull/ok" (aget pull-body "type")))
                        (is (= 1 (count (aget pull-body "txs"))))
                        (is (= "save-block"
                               (aget (aget pull-body "txs" 0) "outliner-op")))))))))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally
             (fn []
               (-> (cleanup-test-server! @server*)
                   (p/finally done))))))))

(deftest node-adapter-websocket-test
  (async done
         (let [server* (atom nil)
               client* (atom nil)
               sync-auth-calls* (atom 0)]
           (->
            (p/with-redefs
              [auth/auth-claims
               (fn [request _env]
                 (if (string/includes? (.-url request) "/sync/")
                   (do
                     (swap! sync-auth-calls* inc)
                     (p/let [_ (p/delay 25)]
                       (test-claims)))
                   (test-claims)))]
              (p/let [{:keys [base-url] :as server} (start-test-server)
                      _ (reset! server* server)
                      create-resp (post-json
                                   (str base-url "/graphs")
                                   {:graph-name "WS Graph"
                                    :graph-e2ee? false})
                      create-body (parse-json create-resp)
                      graph-id (aget create-body "graph-id")
                      ws-url (str (string/replace base-url "http" "ws")
                                  "/sync/"
                                  graph-id)
                      ws-module (js/require "ws")
                      WebSocket (or (.-WebSocket ws-module) ws-module)
                      ^js client (new WebSocket ws-url #js {:headers (auth-headers)})
                      _ (reset! client* client)
                      messages (atom [])
                      opened (js/Promise.
                              (fn [resolve reject]
                                (.once client "open" resolve)
                                (.once client "error" reject)))]
                (.on client
                     "message"
                     (fn [data]
                       (let [text (if (string? data) data (.toString data))]
                         (swap! messages conj (js/JSON.parse text)))))
                (p/let [_ opened
                        _ (.send client
                                 (protocol/encode-message
                                  {:type "hello" :client "test"}))
                        _ (wait-until!
                           #(some (fn [message]
                                    (= "hello" (aget message "type")))
                                  @messages)
                           2000)
                        _ (is (= 1 @sync-auth-calls*)
                              "the websocket upgrade must reuse its verified claims")
                        tx-data [{:block/uuid (random-uuid)
                                  :block/title "ws"}]
                        tx-entry {:tx (protocol/tx->transit tx-data)
                                  :outliner-op :save-block}
                        tx-resp (post-json
                                 (str base-url "/sync/" graph-id "/tx/batch")
                                 {:t-before 0
                                  :txs [tx-entry]})
                        _ (is (.-ok tx-resp))
                        _ (wait-until!
                           #(some (fn [message]
                                    (= "changed" (aget message "type")))
                                  @messages)
                           2000)]
                  (let [types (set (map #(aget % "type") @messages))]
                    (is (contains? types "hello"))
                    (is (contains? types "changed"))))))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally
             (fn []
               (when-let [client @client*]
                 (.close client))
               (-> (cleanup-test-server! @server*)
                   (p/finally done))))))))
