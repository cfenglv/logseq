(ns electron.utils
  (:require ["electron" :refer [app BrowserWindow session]]
            ["fs-extra" :as fs]
            ["http" :as node-http]
            ["https" :as node-https]
            ["node-fetch" :default node-fetch]
            ["open" :as open-module]
            ["path" :as node-path]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.interop :as interop]
            [electron.logger :as logger]
            [logseq.common.config :as common-config]
            [logseq.common.graph :as common-graph]
            [logseq.common.graph-dir :as graph-dir]
            [logseq.db-worker.daemon :as db-worker-daemon]
            [promesa.core :as p]))

(defonce *win (atom nil)) ;; The main window

(defonce mac? (= (.-platform js/process) "darwin"))
(defonce win32? (= (.-platform js/process) "win32"))
(defonce linux? (= (.-platform js/process) "linux"))

(defonce prod? (= js/process.env.NODE_ENV "production"))

(defonce dev? (not prod?))
(defonce *fetchAgent (atom nil))
(defonce extract-zip (js/require "extract-zip"))
(defonce https-proxy-agent (js/require "https-proxy-agent"))
(defonce socks-proxy-agent (js/require "socks-proxy-agent"))
(defonce socks-client (.-SocksClient (js/require "socks")))
(defonce ^:private *db-worker-proxy-bridge (atom nil))
(defonce ^:private *proxy-transition (atom (p/resolved nil)))
(defonce open-external
  (interop/default-function-or-module open-module))

(declare <resolve-fetch-proxy <set-electron-proxy)

(defn- current-session
  []
  (or (some-> ^js @*win .-webContents .-session)
      (.-defaultSession session)))

(defn- <close-proxy-bridge!
  [{:keys [^js server sockets closed?]}]
  (if server
    (do
      (reset! closed? true)
      (doseq [^js socket @sockets]
        (.destroy socket))
      (p/create
       (fn [resolve _reject]
         (if (.-listening server)
           (.close server #(resolve nil))
           (resolve nil)))))
    (p/resolved nil)))

(defn- track-socket!
  [sockets ^js socket]
  (swap! sockets conj socket)
  (.once socket "close" #(swap! sockets disj socket))
  socket)

(defn- send-proxy-error!
  [^js res status message]
  (when-not (.-headersSent res)
    (.writeHead res status #js {"Content-Type" "text/plain"}))
  (.end res message))

(defn- create-socks-agent
  [{:keys [protocol host port]}]
  (new (.-SocksProxyAgent ^js socks-proxy-agent)
       (str protocol "://" host ":" port)))

(defn- url-hostname
  [^js url]
  (let [host (.-hostname url)]
    (if (and (string/starts-with? host "[")
             (string/ends-with? host "]"))
      (subs host 1 (dec (count host)))
      host)))

(defn- proxy-http-request!
  [socks-proxy sockets ^js req ^js res]
  (try
    (let [target-url (js/URL. (.-url req))
          transport (if (= "https:" (.-protocol target-url)) node-https node-http)
          headers (js/Object.assign #js {} (.-headers req))
          _ (js-delete headers "proxy-connection")
          proxy-req (.request transport
                              target-url
                              #js {:method (.-method req)
                                   :headers headers
                                   :agent (create-socks-agent socks-proxy)}
                              (fn [^js proxy-res]
                                (.writeHead res (.-statusCode proxy-res) (.-headers proxy-res))
                                (.pipe proxy-res res)))]
      (.on proxy-req "error" #(send-proxy-error! res 502 (str "SOCKS proxy request failed: " (.-message %))))
      (.on proxy-req "socket" #(track-socket! sockets %))
      (.pipe req proxy-req))
    (catch :default e
      (send-proxy-error! res 400 (str "Invalid proxy request: " (.-message e))))))

(defn- parse-connect-target
  [target]
  (let [url (js/URL. (str "http://" target))]
    {:host (url-hostname url)
     :port (js/parseInt (or (.-port url) "443") 10)}))

(defn- proxy-connect!
  [socks-proxy sockets closed? ^js req ^js client-socket head]
  (try
    (let [{:keys [host port]} (parse-connect-target (.-url req))]
      (-> (.createConnection socks-client
                             #js {:proxy #js {:host (:host socks-proxy)
                                              :port (js/parseInt (str (:port socks-proxy)) 10)
                                              :type (if (= "socks4" (:protocol socks-proxy)) 4 5)}
                                  :command "connect"
                                  :destination #js {:host host :port port}})
          (p/then (fn [^js result]
                    (let [^js upstream-socket (.-socket result)]
                      (if (or @closed? (.-destroyed client-socket))
                        (.destroy upstream-socket)
                        (do
                          (track-socket! sockets upstream-socket)
                          (.write client-socket "HTTP/1.1 200 Connection Established\r\n\r\n")
                          (when (pos? (.-length head))
                            (.write upstream-socket head))
                          (.on upstream-socket "error" #(.destroy client-socket))
                          (.on client-socket "error" #(.destroy upstream-socket))
                          (.on upstream-socket "close" #(.destroy client-socket))
                          (.on client-socket "close" #(.destroy upstream-socket))
                          (.pipe upstream-socket client-socket)
                          (.pipe client-socket upstream-socket))))))
          (p/catch (fn [e]
                     (when-not (.-destroyed client-socket)
                       (.end client-socket
                             (str "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n\r\n"
                                  "SOCKS proxy connection failed: " (.-message e))))))))
    (catch :default e
      (.end client-socket
            (str "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\n\r\n"
                 "Invalid CONNECT target: " (.-message e))))))

(defn- <start-socks-proxy-bridge!
  [socks-proxy]
  (p/create
   (fn [resolve reject]
     (let [sockets (atom #{})
           closed? (atom false)
           server (.createServer node-http #(proxy-http-request! socks-proxy sockets %1 %2))
           startup-error (fn [e] (reject e))]
       (.once server "error" startup-error)
       (.on server "connect" #(proxy-connect! socks-proxy sockets closed? %1 %2 %3))
       (.on server "connection"
            #(track-socket! sockets %))
       (.listen server 0 "127.0.0.1"
                (fn []
                  (.removeListener server "error" startup-error)
                  (.on server "error"
                       (fn [e]
                         (logger/error :db-worker-proxy-bridge-failed e)))
                  (.unref server)
                  (let [port (.-port (.address server))
                        bridge {:server server
                                :sockets sockets
                                :closed? closed?
                                :proxy {:protocol "http"
                                        :host "127.0.0.1"
                                        :port port}}]
                    (resolve bridge))))))))

(defn open
  ([target] (open target nil))
  ([target options]
   (if options
     (open-external target (bean/->js options))
     (open-external target))))

(defn- <build-fetch-agent
  [{:keys [protocol host port]}]
  (when (and protocol host port (contains? #{"http" "https" "socks4" "socks5"} protocol))
    (let [proxy-url (str protocol "://" host ":" port)]
      (if-let [ctor (case protocol
                     ("http" "https") (.-HttpsProxyAgent ^js https-proxy-agent)
                     ("socks4" "socks5") (.-SocksProxyAgent ^js socks-proxy-agent)
                     nil)]
        (new ctor proxy-url)
        (do
          (logger/error "Unknown proxy protocol:" protocol)
          nil)))))

(defn- <resolve-fetch-agent
  [url options]
  (let [options (or options {})]
    (cond
      (contains? options :agent)
      (:agent options)

      (contains? options :proxy)
      (p/let [proxy (<resolve-fetch-proxy url (:proxy options))]
        (<build-fetch-agent proxy))

      :else
      @*fetchAgent)))

(defn- ->proxy-config
  [{:keys [type protocol host port] :or {type "system"}}]
  (let [type (or type protocol)
        ->proxy-rules (fn [proxy-type proxy-host proxy-port]
                        (cond
                          (= proxy-type "http")
                          (str "http=" proxy-host ":" proxy-port ";https=" proxy-host ":" proxy-port)
                          (= proxy-type "socks5")
                          (str "http=socks5://" proxy-host ":" proxy-port ";https=socks5://" proxy-host ":" proxy-port)
                          (or (= proxy-type "socks") (= proxy-type "socks4"))
                          (str "http=socks://" proxy-host ":" proxy-port ";https=socks://" proxy-host ":" proxy-port)
                          (= proxy-type "direct")
                          "direct://"
                          :else
                          nil))]
    (cond
      (= type "system")
      #js {:mode "system"}

      (= type "direct")
      #js {:mode "direct"}

      (or (= type "socks5") (= type "http"))
      #js {:mode "fixed_servers"
           :proxyRules (->proxy-rules type host port)
           :proxyBypassRules "<local>"}

      :else
      #js {:mode "system"})))

(defn fetch
  ([url] (fetch url nil))
  ([url options]
   (let [options (or options {})]
     (p/let [agent (<resolve-fetch-agent url options)]
       (node-fetch url (bean/->js (cond-> (dissoc options :proxy)
                                    (some? agent) (assoc :agent agent))))))))

(defn fix-win-path!
  [path]
  (when (not-empty path)
    (if win32?
      (string/replace path "\\" "/")
      path)))

(defn to-native-win-path!
  "Convert path to native win path"
  [path]
  (when (not-empty path)
    (if win32?
      (string/replace path "/" "\\")
      path)))

(defn get-ls-dotdir-root
  []
  (let [lg-dir (node-path/join (.getPath app "home") ".logseq")]
    (when-not (fs/existsSync lg-dir)
      (fs/mkdirSync lg-dir))
    (fix-win-path! lg-dir)))

(defn get-ls-default-plugins
  []
  (let [plugins-root (node-path/join (get-ls-dotdir-root) "plugins")
        _ (when-not (fs/existsSync plugins-root)
            (fs/mkdirSync plugins-root))
        dirs (js->clj (fs/readdirSync plugins-root #js{"withFileTypes" true}))
        dirs (->> dirs
                  (filter #(.isDirectory %))
                  (filter (fn [f] (not (some #(string/starts-with? (.-name f) %) ["_" "."]))))
                  (map #(node-path/join plugins-root (.-name %))))]
    dirs))

(defn- <prepare-fetch-proxy
  [proxy]
  (p/let [agent (<build-fetch-agent proxy)
          bridge (when (contains? #{"socks4" "socks5"} (:protocol proxy))
                   (<start-socks-proxy-bridge! proxy))]
    {:agent agent
     :bridge bridge
     :db-worker-proxy (or (:proxy bridge) proxy)}))

(defn- <commit-fetch-proxy!
  [{:keys [agent bridge db-worker-proxy]}]
  (let [old-bridge @*db-worker-proxy-bridge]
    (reset! *db-worker-proxy-bridge bridge)
    (db-worker-daemon/configure-proxy-env! db-worker-proxy)
    (reset! *fetchAgent agent)
    (<close-proxy-bridge! old-bridge)))

(defn- <apply-proxy-transition!
  [electron-opts proxy]
  (p/let [prepared (<prepare-fetch-proxy proxy)]
    (-> (p/do!
         (<set-electron-proxy electron-opts)
         (<commit-fetch-proxy! prepared))
        (p/catch (fn [e]
                   (-> (<close-proxy-bridge! (:bridge prepared))
                       (p/then (fn [] (p/rejected e)))))))))

(defn- <serialize-proxy-transition!
  [transition-fn]
  (let [result (p/then @*proxy-transition transition-fn)]
    (reset! *proxy-transition (p/catch result (fn [_] nil)))
    result))

(defn <set-electron-proxy
  "Set proxy for electron
  type: system | direct | socks5 | http"
  ([{:keys [type host port] :or {type "system"}}]
   (let [config (->proxy-config {:type type :host host :port port})
         ^js sess (current-session)]
     (if sess
       (-> (p/do!
            (.setProxy sess config)
            (.forceReloadProxyConfig sess))
           (p/timeout 10000))
       (p/resolved nil)))))

(defn- parse-pac-rule
  "Parse Proxy Auto Config(PAC) line"
  [line]
  (try
    (let [[type address] (string/split (string/trim line) #"\s+" 2)]
      (cond
        (= type "DIRECT")
        {:protocol "direct"}

        (and (contains? #{"PROXY" "HTTP" "HTTPS" "SOCKS" "SOCKS4" "SOCKS5"} type)
             (not (string/blank? address)))
        (let [url (js/URL. (str "http://" address))]
          {:protocol (cond
                       (= type "HTTPS") "https"
                       (= type "SOCKS4") "socks4"
                       (contains? #{"SOCKS" "SOCKS5"} type) "socks5"
                       :else "http")
           :host (url-hostname url)
           :port (.-port url)})

        :else
        (do
          (logger/warn "Unknown PAC rule:" line)
          nil)))
    (catch :default e
      (logger/warn "Invalid PAC rule:" line (.-message e))
      nil)))

(defn- <resolve-session-proxy
  [^js sess for-url]
  (p/let [proxy (.resolveProxy sess for-url)
          clauses (->> (string/split proxy #";")
                       (map string/trim)
                       (remove string/blank?)
                       vec)
          pac-opts (mapv parse-pac-rule clauses)]
    (cond
      (or (empty? pac-opts) (some nil? pac-opts))
      (p/rejected (ex-info "system proxy returned no supported route"
                           {:code :unsupported-system-proxy
                            :result proxy
                            :url for-url}))

      (> (count pac-opts) 1)
      (p/rejected (ex-info "system proxy fallback chains are not supported for db-worker"
                           {:code :unsupported-system-proxy-chain
                            :result proxy
                            :url for-url}))

      :else
      (first pac-opts))))

(defn <get-system-proxy
  "Get system proxy for url, requires proxy to be set to system"
  ([] (<get-system-proxy "https://www.google.com"))
  ([for-url]
   (when-let [sess (current-session)]
     (<resolve-session-proxy sess for-url))))

(defn- <resolve-temporary-system-proxy
  [for-url]
  (let [session-partition (str "logseq-system-proxy-" (random-uuid))
        ^js sess (.fromPartition session session-partition)]
    (-> (p/do!
         (.setProxy sess #js {:mode "system"})
         (.forceReloadProxyConfig sess)
         (<resolve-session-proxy sess for-url))
        (p/timeout 10000))))

(defn- <resolve-fetch-proxy
  [url {:keys [type protocol host port] :as proxy}]
  (let [type (or type protocol)]
    (cond
      (string/blank? type)
      nil

      (= type "system")
      (<resolve-temporary-system-proxy url)

      (= type "direct")
      nil

      (contains? #{"http" "socks5"} type)
      {:protocol type :host host :port port}

      :else
      (do
        (logger/warn "Unknown fetch proxy type:" proxy)
        nil))))

(defn <set-proxy
  "Set proxy for electron, fetch"
  ([{test-url :test :keys [type host port] :or {type "system"} :as opts}]
   (logger/info "set proxy to" opts)
   (<serialize-proxy-transition!
    (fn []
      (cond
        (= type "system")
        (p/let [proxy (<resolve-temporary-system-proxy
                       (if (string/blank? test-url)
                         "https://www.google.com"
                         test-url))
                effective-proxy (when-not (= "direct" (:protocol proxy)) proxy)]
          (<apply-proxy-transition! {:type "system"} effective-proxy))

        (= type "direct")
        (<apply-proxy-transition! {:type "direct"} nil)

        (or (= type "socks5") (= type "http"))
        (<apply-proxy-transition! {:type type :host host :port port}
                                  {:protocol type :host host :port port})

        :else
        (p/rejected (ex-info "Unknown proxy type" {:type type})))))))

(defn <restore-proxy-settings
  "Restore proxy settings from configs.edn"
  []
  (let [settings (cfgs/get-item :settings/agent)
        settings (cond
                   (:type settings)
                   settings

                   ;; migration from old config
                   (not-empty (:protocol settings))
                   (assoc settings :type (:protocol settings))

                   :else
                   {:type "system"})]
    (logger/info "restore proxy settings" settings)
    (<set-proxy settings)))

(defn save-proxy-settings
  "Save proxy settings to configs.edn"
  [{test' :test :keys [type host port] :or {type "system"}}]
  (if (or (= type "system") (= type "direct"))
    (cfgs/set-item! :settings/agent {:type type :test test'})
    (cfgs/set-item! :settings/agent {:type type :protocol type :host host :port port :test test'})))

(defn read-file-raw
  [path]
  (fs/readFileSync path))

(defn read-file
  [path]
  (try
    (when (fs/existsSync path)
      (.toString (fs/readFileSync path)))
    (catch :default e
      (logger/error "Read file:" e))))

(defn get-focused-window
  []
  (.getFocusedWindow BrowserWindow))

(defn get-win-from-sender
  [^js evt]
  (try
    (.fromWebContents BrowserWindow (.-sender evt))
    (catch :default _
      nil)))

(defn send-to-renderer
  "Notice: pass the `window` parameter if you can. Otherwise, the message
  will not be received if there's no focused window.
   Use `send-to-focused-renderer` instead if you want to set a window for fallback"
  ([kind payload]
   (send-to-renderer (get-focused-window) kind payload))
  ([window kind payload]
   (when window
     (.. ^js window -webContents
         (send (name kind) (bean/->js payload))))))

(defn send-to-focused-renderer
  "Try to send to focused window. If no focused window, fallback to the `fallback-win`"
  ([kind payload fallback-win]
   (let [focused-win (get-focused-window)
         win         (if focused-win focused-win fallback-win)]
     (send-to-renderer win kind payload))))

(defn get-graph-dir
  "required by all internal state in the electron section"
  [graph-name]
  (when (and (string? graph-name)
             (string/starts-with? graph-name common-config/db-version-prefix))
    (let [repo (common-config/canonicalize-db-version-repo graph-name)]
      (node-path/join (common-graph/get-db-graphs-dir)
                      (graph-dir/repo->encoded-graph-dir-name repo)))))

(comment
  (defn get-graph-name
    "Reverse `get-graph-dir`"
    [graph-dir]
    (str common-config/db-version-prefix (node-path/basename graph-dir))))

(defn decode-protected-assets-schema-path
  [schema-path]
  (cond-> schema-path
    (string? schema-path)
    (string/replace "/logseq__colon/" ":/")))

;; Keep update with the normalization in main
(defn normalize
  [s]
  (.normalize s "NFC"))

(defn normalize-lc
  [s]
  (normalize (string/lower-case s)))

(defn safe-decode-uri-component
  [uri]
  (try
    (js/decodeURIComponent uri)
    (catch :default _
      (println "decodeURIComponent failed: " uri)
      uri)))

(defn fs-stat->clj
  [path]
  (let [stat (fs/statSync path)]
    {:size (.-size stat)
     :birthtime (.-birthtime stat)
     :mtime (.-mtime stat)
     :ctime (.-ctime stat)}))
