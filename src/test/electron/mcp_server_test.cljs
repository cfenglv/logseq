(ns electron.mcp-server-test
  (:require ["@modelcontextprotocol/sdk/server/streamableHttp.js" :refer [StreamableHTTPServerTransport]]
            [cljs.test :refer [deftest is]]
            [electron.mcp-server :as mcp-server]
            [goog.object :as gobj]))

(defn- initialize-request
  [id]
  #js {:headers #js {}
       :body #js {:jsonrpc "2.0"
                  :id id
                  :method "initialize"
                  :params #js {:protocolVersion "2025-03-26"
                               :capabilities #js {}
                               :clientInfo #js {:name "logseq-test"
                                                :version "1.0.0"}}}
       :raw #js {}})

(defn- fake-mcp-server
  [id connected]
  #js {:connect (fn [transport]
                  (aset transport "sessionId" (str "session-" (name id)))
                  (swap! connected conj [id transport]))})

(defn- shared-api-server
  [connected]
  (let [api-fn (fn [_method _args])]
    (gobj/set api-fn "connect"
              (fn [transport]
                (aset transport "sessionId" "session-shared")
                (swap! connected conj [:shared transport])))
    api-fn))

(deftest fresh-server-per-session-test
  (let [connected (atom [])
        created (atom [])
        shared-api-fn (shared-api-server connected)
        transport-prototype (.-prototype StreamableHTTPServerTransport)
        original-handle-request (.-handleRequest transport-prototype)]
    (set! (.-handleRequest transport-prototype) (fn [& _args]))
    (try
      (with-redefs [mcp-server/create-mcp-api-server
                    (fn [received-api-fn]
                      (let [id (keyword (str "fresh-" (inc (count @created))))]
                        (swap! created conj received-api-fn)
                        (fake-mcp-server id connected)))]
        (mcp-server/handle-post-request
         shared-api-fn {:host "127.0.0.1" :port 12315}
         (initialize-request 1) #js {:raw #js {}})
        (mcp-server/handle-post-request
         shared-api-fn {:host "127.0.0.1" :port 12315}
         (initialize-request 2) #js {:raw #js {}})
        (is (= [shared-api-fn shared-api-fn] @created)
            "Each initialize request must build its own MCP server from the same API function.")
        (is (= [:fresh-1 :fresh-2] (mapv first @connected))
            "Two sessions must connect through two distinct server instances.")
        (doseq [[_ transport] @connected]
          (when-let [onclose (.-onclose transport)]
            (onclose))))
      (finally
        (set! (.-handleRequest transport-prototype) original-handle-request)))))
