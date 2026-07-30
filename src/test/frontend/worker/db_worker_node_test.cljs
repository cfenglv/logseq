(ns frontend.worker.db-worker-node-test
  (:require ["child_process" :as child-process]
            ["fs" :as fs]
            ["http" :as http]
            ["path" :as node-path]
            ["ws" :as ws-module]
            [cljs.test :refer [async deftest is use-fixtures]]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.crypt :as crypt]
            [frontend.test.node-helper :as node-helper]
            [frontend.worker.db-core :as db-core]
            [frontend.worker.db-worker-node :as db-worker-node]
            [frontend.worker.db-worker-node-lock :as db-lock]
            [frontend.worker.platform.node :as platform-node]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync :as db-sync]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.crypt :as sync-crypt]
            [frontend.worker.sync.large-title :as sync-large-title]
            [goog.object :as gobj]
            [logseq.cli.config :as cli-config]
            [logseq.cli.server :as cli-server]
            [logseq.cli.style :as style]
            [logseq.cli.test-helper :as test-helper]
            [logseq.common.config :as common-config]
            [logseq.common.version :as build-version]
            [logseq.db :as ldb]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-worker.log :as db-worker-log]
            [promesa.core :as p]))

(defn- http-request
  [opts body]
  (p/create
   (fn [resolve reject]
     (let [req (.request http (clj->js opts)
                         (fn [^js res]
                           (let [chunks (array)]
                             (.on res "data" (fn [chunk] (.push chunks chunk)))
                             (.on res "end" (fn []
                                              (resolve {:status (.-statusCode res)
                                                        :body (.toString (js/Buffer.concat chunks) "utf8")}))))))
           finish! (fn []
                     (when body (.write req body))
                     (.end req))]
       (.on req "error" reject)
       (finish!)))))

(defn- escape-regex
  [value]
  (let [pattern (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g")]
    (string/replace value pattern "\\\\$&")))

(defn- contains-bold?
  [value token]
  (let [token (escape-regex token)
        pattern (re-pattern (str "\\u001b\\[[0-9;]*m" token "\\u001b\\[[0-9;]*m"))]
    (boolean (re-find pattern value))))

(defn- http-get
  [host port path]
  (http-request {:hostname host
                 :port port
                 :path path
                 :method "GET"}
                nil))

(defn- invoke
  [host port method args]
  (let [payload (js/JSON.stringify
                 (clj->js {:method method
                           :argsTransit (ldb/write-transit-str args)}))]
    (p/let [{:keys [status body]}
            (http-request {:hostname host
                           :port port
                           :path "/v1/invoke"
                           :method "POST"
                           :headers {"Content-Type" "application/json"}}
                          payload)
            parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
      (when (not= 200 status)
        (println "[db-worker-node-test] invoke failed"
                 {:method method
                  :status status
                  :body body}))
      (is (= 200 status))
      (is (:ok parsed))
      (ldb/read-transit-str (:resultTransit parsed)))))

(defn- invoke-raw
  [host port method args]
  (let [payload (js/JSON.stringify
                 (clj->js {:method method
                           :argsTransit (ldb/write-transit-str args)}))]
    (http-request {:hostname host
                   :port port
                   :path "/v1/invoke"
                   :method "POST"
                   :headers {"Content-Type" "application/json"}}
                  payload)))

(defn- invoke-import-db-binary-raw
  [host port repo payload]
  (http-request {:hostname host
                 :port port
                 :path (str "/v1/import-db-binary?repo=" (js/encodeURIComponent repo))
                 :method "POST"
                 :headers {"Content-Type" "application/octet-stream"}}
                payload))

(defn- lock-path
  [root-dir repo]
  (db-lock/lock-path root-dir repo))

(defn- log-path
  [root-dir repo]
  (db-worker-log/log-path root-dir repo))

(defn- start-daemon!
  "Start daemon with quiet logging by default"
  [opts]
  (db-worker-node/start-daemon! (update opts :log-level #(or % "error"))))

(defn- future-test-id-token
  []
  (let [header (.toString
                (js/Buffer.from
                 (js/JSON.stringify #js {:alg "none" :typ "JWT"}))
                "base64url")
        payload (.toString
                 (js/Buffer.from
                  (js/JSON.stringify
                   #js {:sub "runtime-marker-test-user"
                        :exp (+ (js/Math.floor (/ (js/Date.now) 1000))
                                3600)}))
                 "base64url")]
    (str header "." payload ".test-signature")))

(defn- <wait-for
  ([pred description]
   (<wait-for pred description 150 20))
  ([pred description attempts delay-ms]
   (p/loop [remaining attempts]
     (cond
       (pred)
       true

       (zero? remaining)
       (throw (ex-info (str "timed out waiting for " description)
                       {:description description}))

       :else
       (p/let [_ (p/delay delay-ms)]
         (p/recur (dec remaining)))))))

(defn- <wait-for-server
  [root-dir repo ^js child]
  (p/loop [remaining 200]
    (p/let [servers
            (-> (cli-server/list-servers
                 {:root-dir root-dir})
                (p/catch (fn [_] [])))
            server
            (some
             (fn [entry]
               (when (and (= repo (:repo entry))
                          (= (.-pid child) (:pid entry)))
                 entry))
             servers)]
      (cond
        server
        server

        (not (nil? (.-exitCode child)))
        (throw
         (ex-info
          "bundled db-worker-node exited before discovery"
          {:exit-code (.-exitCode child)}))

        (zero? remaining)
        (throw
         (ex-info
          "timed out discovering bundled db-worker-node"
          {:repo repo}))

        :else
        (p/let [_ (p/delay 25)]
          (p/recur (dec remaining)))))))

(defn- start-bundled-daemon!
  [bundle-path root-dir repo]
  (let [child
        (.spawn
         child-process
         (.-execPath js/process)
         (clj->js
          [bundle-path
           "--root-dir" root-dir
           "--repo" repo
           "--owner-source" "cli"
           "--log-level" "error"])
         #js {:env (.-env js/process)
              :stdio #js ["ignore" "ignore" "ignore"]})]
    (p/let [server (<wait-for-server root-dir repo child)]
      (assoc
       server
       :child child
       :stop!
       (fn []
         (p/let [_ (-> (http-request
                        {:hostname (:host server)
                         :port (:port server)
                         :path "/v1/shutdown"
                         :method "POST"}
                        nil)
                       (p/catch (fn [_] nil)))
                 _ (p/delay 50)]
           (when (nil? (.-exitCode child))
             (.kill child "SIGTERM"))
           nil))))))

(defn- <wait-for-sync-status
  [host port repo pred description]
  (p/loop [remaining 150]
    (p/let [status
            (-> (invoke
                 host port
                 "thread-api/db-sync-status"
                 [repo])
                (p/catch (fn [_] nil)))]
      (cond
        (and status (pred status))
        status

        (zero? remaining)
        (throw
         (ex-info
          (str "timed out waiting for " description)
          {:description description
           :last-status status}))

        :else
        (p/let [_ (p/delay 20)]
          (p/recur (dec remaining)))))))

(defn- start-runtime-sync-peer!
  [{:keys [graph-id token state* requests* ws-messages*]}]
  (p/create
   (fn [resolve reject]
     (let [hanging-marker-responses* (atom #{})
           hanging-batch-acks* (atom [])
           send-marker-state-response!
           (fn [^js res]
             (let [{:keys [t checksum checksum-version
                           server-checksum block-uuid
                           remote-marker]}
                   @state*]
               (.writeHead
                res
                200
                #js {"content-type" "application/json"})
               (.end
                res
                (js/JSON.stringify
                 (clj->js
                  {:t t
                   :checksum checksum
                   :checksum-version checksum-version
                   :server-checksum server-checksum
                   :large-title-markers
                   [{:block-uuid (str block-uuid)
                     :marker remote-marker}]})))))
           send-batch-ack!
           (fn [^js socket]
             (let [{:keys [ack-t ack-checksum
                           ack-checksum-version
                           ack-server-checksum]}
                   @state*]
               (swap! state*
                      (fn [state]
                        (-> state
                            (assoc
                             :t ack-t
                             :checksum ack-checksum
                             :checksum-version
                             ack-checksum-version
                             :server-checksum
                             ack-server-checksum)
                            (dissoc
                             :tx-batch-response-mode))))
               (.send
                socket
                (js/JSON.stringify
                 (clj->js
                  {:type "tx/batch/ok"
                   :t ack-t
                   :checksum ack-checksum
                   :checksum-version ack-checksum-version
                   :server-checksum ack-server-checksum})))))
           server
           (.createServer
            http
            (fn [^js req ^js res]
              (let [url (js/URL. (.-url req) "http://127.0.0.1")
                    path (.-pathname url)
                    authorization (aget (.-headers req) "authorization")
                    {:keys [remote-marker payload marker-response-mode]}
                    @state*]
                (swap! requests* conj
                       {:method (.-method req)
                        :path path
                        :authorization authorization})
                (cond
                  (= path
                     (str "/sync/" graph-id
                          "/checksum/large-title-markers"))
                  (if (= :hang marker-response-mode)
                    (swap! hanging-marker-responses* conj res)
                    (send-marker-state-response! res))

                  (= path
                     (str "/assets/" graph-id "/"
                          (:asset-uuid remote-marker) "."
                          (:asset-type remote-marker)))
                  (do
                    (.writeHead
                     res
                     200
                     #js {"content-type"
                          "text/plain; charset=utf-8"})
                    (.end res (js/Buffer.from payload)))

                  :else
                  (do
                    (.writeHead
                     res
                     404
                     #js {"content-type" "application/json"})
                    (.end res "{\"error\":\"not found\"}"))))))
           WebSocketServer (.-WebSocketServer ws-module)
           wss (new WebSocketServer #js {:noServer true})]
       (.on
        server
        "upgrade"
        (fn [^js req ^js socket head]
          (let [url (js/URL. (.-url req) "http://127.0.0.1")]
            (if (and (= (.-pathname url)
                        (str "/sync/" graph-id))
                     (= (.get (.-searchParams url) "token") token))
              (.handleUpgrade
               wss
               req
               socket
               head
               (fn [socket*]
                 (.emit wss "connection" socket* req)))
              (.destroy socket)))))
       (.on
        wss
        "connection"
        (fn [^js socket]
          (.on
           socket
           "message"
           (fn [^js raw]
             (let [message
                   (js->clj
                    (js/JSON.parse (.toString raw))
                    :keywordize-keys true)
                   {:keys [t checksum checksum-version server-checksum
                           hello-delay-ms]}
                   @state*]
               (swap! ws-messages* conj message)
               (case (:type message)
                 "hello"
                 (let [send-hello!
                       (fn []
                         (.send
                          socket
                          (js/JSON.stringify
                           (clj->js
                            {:type "hello"
                             :t t
                             :checksum checksum
                             :checksum-version checksum-version
                             :server-checksum server-checksum}))))]
                   (if (pos? (or hello-delay-ms 0))
                     (js/setTimeout send-hello! hello-delay-ms)
                     (send-hello!)))

                 "tx/batch"
                 (if (= :hang
                        (:tx-batch-response-mode @state*))
                   (swap! hanging-batch-acks* conj socket)
                   (send-batch-ack! socket))

                 nil)))))
       (.once server "error" reject)
       (.listen
        server
        0
        "127.0.0.1"
        (fn []
          (let [port (.-port (.address server))]
            (resolve
             {:port port
              :release-marker-responses!
              (fn []
                (let [responses @hanging-marker-responses*]
                  (reset! hanging-marker-responses* #{})
                  (swap! state* dissoc :marker-response-mode)
                  (doseq [response responses]
                    (send-marker-state-response! response))))
              :release-batch-acks!
              (fn []
                (let [sockets @hanging-batch-acks*]
                  (reset! hanging-batch-acks* [])
                  (doseq [socket sockets]
                    (send-batch-ack! socket))))
              :stop!
              (fn []
                (doseq [^js response
                        @hanging-marker-responses*]
                  (try (.destroy response)
                       (catch :default _ nil)))
                (reset! hanging-marker-responses* #{})
                (reset! hanging-batch-acks* [])
                (doseq [socket (array-seq (.-clients wss))]
                  (try (.terminate socket) (catch :default _ nil)))
                (p/create
                 (fn [resolve-stop _]
                   (.close
                    wss
                    (fn []
                      (.close server resolve-stop))))))})))))))))

(defn- seed-cold-runtime-marker-split!
  ([repo cursor graph-id large-title local-marker]
   (seed-cold-runtime-marker-split!
    repo cursor graph-id large-title local-marker {}))
  ([repo cursor graph-id large-title local-marker {:keys [e2ee?]}]
   (let [conn (worker-state/get-datascript-conn repo)
         page-uuid (random-uuid)]
     (ldb/transact!
      conn
      (cond->
       [{:block/uuid page-uuid
         :block/name "runtime-marker-page"
         :block/title large-title
         :block/tags :logseq.class/Page
         :block/created-at 1785400000000
         :block/updated-at 1785400000000
         sync-large-title/large-title-object-attr local-marker}]
        e2ee?
        (conj {:db/ident :logseq.kv/graph-rtc-e2ee?
               :kv/value true}))
      {:rtc-tx? true
       :persist-op? false})
     ;; A newly created desktop graph may carry its bootstrap transaction.
     ;; The observed incident has no pending logical edits, so retire only the
     ;; fixture's own bootstrap/persistence entries before freezing the cursor.
     (let [pending-tx-ids
           (mapv :tx-id
                 (client-op/get-pending-local-txs repo))
           removed
           (client-op/mark-pending-txs-false!
            repo pending-tx-ids)]
       (when (pos? (or removed 0))
         (client-op/adjust-pending-local-tx-count!
          repo
          (- removed))))
     (client-op/update-graph-uuid repo graph-id)
     (client-op/update-local-tx repo cursor)
     (client-op/update-local-checksum
      repo
      (sync-checksum/recompute-checksum @conn))
     (client-op/update-local-server-checksum
      repo
      (sync-checksum/recompute-server-checksum @conn))
     {:block-uuid page-uuid
      :local-checksum
      (sync-checksum/recompute-checksum @conn)
      :local-server-checksum
      (sync-checksum/recompute-server-checksum @conn)})))

(defn- semantic-search-integration-enabled?
  []
  (and (= "darwin" (.-platform js/process))
       (some? (gobj/get (.-env js/process) "LOGSEQ_EMBEDDINGS_URL"))))

(defn- noisy-debug-line?
  [line]
  (or (string/includes? line ":listen-db-changes!")
      (string/includes? line ":debug :db-gc")))

(defonce ^:private *orig-print-fn (atom nil))

(defn- quiet-debug-output-before
  []
  (when-not @*orig-print-fn
    (reset! *orig-print-fn *print-fn*))
  (set-print-fn!
   (fn [line]
     (when-not (and (string? line) (noisy-debug-line? line))
       (when-let [orig @*orig-print-fn]
         (orig line))))))

(defn- quiet-debug-output-after
  []
  (when-let [orig @*orig-print-fn]
    (set-print-fn! orig)))

(defn- reset-daemon-state!
  []
  (reset! @#'db-worker-node/*ready? false)
  (reset! @#'db-worker-node/*sse-clients #{})
  (reset! @#'db-worker-node/*lock-info nil)
  (db-worker-log/uninstall!))

(defn- normalize-db-worker-state-before
  []
  (quiet-debug-output-before)
  (reset-daemon-state!))

(defn- normalize-db-worker-state-after
  []
  (reset-daemon-state!)
  (quiet-debug-output-after))

(defn- run-main-with-overrides
  [{:keys [argv on-exit on-log on-error start-daemon-fn]}]
  (test-helper/with-js-property-override
    js/process
    "argv"
    argv
    (fn []
      (test-helper/with-js-property-override
        js/process
        "exit"
        on-exit
        (fn []
          (test-helper/with-js-property-override
            js/console
            "log"
            on-log
            (fn []
              (test-helper/with-js-property-override
                js/console
                "error"
                on-error
                (fn []
                  (p/with-redefs [db-worker-node/start-daemon! start-daemon-fn]
                    (p/resolved
                     (try
                       (db-worker-node/main)
                       (catch :default e
                         (when-not (= "process-exit" (.-message e))
                           (throw e)))))))))))))))

(use-fixtures :each {:before normalize-db-worker-state-before
                     :after normalize-db-worker-state-after})

(deftest db-worker-node-root-dir-permission-error
  (async done
         (if (= "win32" (.-platform js/process))
           (done)
           (let [data-dir (node-helper/create-tmp-dir "db-worker-readonly")
                 repo (str "logseq_db_perm_" (subs (str (random-uuid)) 0 8))]
             (fs/chmodSync data-dir 365)
             (-> (start-daemon! {:root-dir data-dir
                                 :repo repo})
                 (p/then (fn [_]
                           (is false "expected root-dir permission error")))
                 (p/catch (fn [e]
                            (let [data (ex-data e)]
                              (is (= :root-dir-permission (:code data)))
                              (is (= (node-path/resolve data-dir) (:path data))))))
                 (p/finally (fn [] (done))))))))

(deftest db-worker-node-creates-log-file
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log")
               repo (str "logseq_db_log_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (p/delay 50)]
                 (is (fs/existsSync log-file)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-log-file-has-entries
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log-entries")
               repo (str "logseq_db_log_entries_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       {:keys [status]} (invoke-raw host port "thread-api/not-found" [repo nil])
                       _ (p/delay 50)
                       contents (when (fs/existsSync log-file)
                                  (.toString (fs/readFileSync log-file) "utf8"))]
                 (is (= 500 status))
                 (is (fs/existsSync log-file))
                 (is (pos? (count contents))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-logs-version-on-startup
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log-version")
               repo (str "logseq_db_log_version_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo
                                       :log-level "info"})
                       _ (reset! daemon {:stop! stop!})
                       _ (p/delay 50)
                       contents (.toString (fs/readFileSync log-file) "utf8")]
                 (is (string/includes? contents ":db-worker-node-version"))
                 (is (string/includes? contents (str ":build-time " (pr-str (build-version/build-time)))))
                 (is (string/includes? contents (str ":revision " (pr-str (build-version/revision))))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-logs-println-output
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log-println")
               repo (str "logseq_db_log_println_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)
               message (str "println output " (random-uuid))]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (println message)
                       _ (p/delay 50)
                       contents (.toString (fs/readFileSync log-file) "utf8")]
                 (is (string/includes? contents message)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-logs-console-error-output
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log-console-error")
               repo (str "logseq_db_log_console_error_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)
               message (str "console error output " (random-uuid))]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (.error js/console message)
                       _ (p/delay 50)
                       contents (.toString (fs/readFileSync log-file) "utf8")]
                 (is (string/includes? contents message)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-logs-console-number-output
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log-console-number")
               repo (str "logseq_db_log_console_number_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (.error js/console 123)
                       _ (p/delay 50)
                       contents (.toString (fs/readFileSync log-file) "utf8")]
                 (is (string/includes? contents "123")))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-logs-process-stdout-output
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-log-stdout")
               repo (str "logseq_db_log_stdout_" (subs (str (random-uuid)) 0 8))
               log-file (log-path data-dir repo)
               message (str "stdout output " (random-uuid))]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (.write (.-stdout js/process) (str message "\n"))
                       _ (p/delay 50)
                       contents (.toString (fs/readFileSync log-file) "utf8")]
                 (is (string/includes? contents message)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-log-retention
  (let [data-dir (node-helper/create-tmp-dir "db-worker-log-retention")
        repo (str "logseq_db_log_retention_" (subs (str (random-uuid)) 0 8))
        repo-dir (db-lock/repo-dir data-dir repo)
        days ["20240101" "20240102" "20240103" "20240104" "20240105"
              "20240106" "20240107" "20240108" "20240109"]
        make-log (fn [day]
                   (node-path/join repo-dir (str "db-worker-node-" day ".log")))]
    (fs/mkdirSync repo-dir #js {:recursive true})
    (doseq [day days]
      (fs/writeFileSync (make-log day) "log\n"))
    (db-worker-log/enforce-retention! repo-dir)
    (let [remaining (->> (fs/readdirSync repo-dir)
                         (filter (fn [^js name]
                                   (re-matches #"db-worker-node-\d{8}\.log" name)))
                         (sort))]
      (is (= 7 (count remaining)))
      (is (= ["db-worker-node-20240103.log"
              "db-worker-node-20240104.log"
              "db-worker-node-20240105.log"
              "db-worker-node-20240106.log"
              "db-worker-node-20240107.log"
              "db-worker-node-20240108.log"
              "db-worker-node-20240109.log"]
             remaining)))))

(deftest db-worker-node-parse-args-ignores-host-and-port
  (let [parse-args #'db-worker-node/parse-args
        result (parse-args #js ["node" "dist/db-worker-node.js"
                                "--host" "0.0.0.0"
                                "--port" "1234"
                                "--repo" "logseq_db_parse_args"
                                "--root-dir" "/tmp/logseq-root"])]
    (is (nil? (:host result)))
    (is (nil? (:port result)))
    (is (= "logseq_db_parse_args" (:repo result)))
    (is (= "/tmp/logseq-root" (:root-dir result)))))

(deftest db-worker-node-parse-args-ignores-auth-token
  (let [parse-args #'db-worker-node/parse-args
        result (parse-args #js ["node" "dist/db-worker-node.js"
                                "--auth-token" "secret"
                                "--root-dir" "/tmp/logseq-root"])]
    (is (nil? (:auth-token result)))
    (is (= "/tmp/logseq-root" (:root-dir result)))))

(deftest db-worker-node-parse-args-ignores-rtc-ws-url
  (let [parse-args #'db-worker-node/parse-args
        result (parse-args #js ["node" "dist/db-worker-node.js"
                                "--rtc-ws-url" "ws://example.com"
                                "--repo" "logseq_db_parse_args"])]
    (is (nil? (:rtc-ws-url result)))
    (is (= "logseq_db_parse_args" (:repo result)))))

(deftest db-worker-node-parse-args-recognizes-create-empty-db
  (let [parse-args #'db-worker-node/parse-args
        result (parse-args #js ["node" "dist/db-worker-node.js"
                                "--repo" "logseq_db_parse_args"
                                "--create-empty-db"])]
    (is (= "logseq_db_parse_args" (:repo result)))
    (is (= true (:create-empty-db? result)))))

(deftest db-worker-node-parse-args-ignores-server-list-file
  (let [parse-args #'db-worker-node/parse-args
        result (parse-args #js ["node" "dist/db-worker-node.js"
                                "--repo" "logseq_db_parse_args"
                                "--root-dir" "/tmp/logseq-root"
                                "--server-list-file" "/tmp/server-list"])]
    (is (= "logseq_db_parse_args" (:repo result)))
    (is (= "/tmp/logseq-root" (:root-dir result)))
    (is (nil? (:server-list-file result)))))

(deftest db-worker-node-parse-args-recognizes-version
  (let [parse-args #'db-worker-node/parse-args
        result (parse-args #js ["node" "dist/db-worker-node.js"
                                "--version"])]
    (is (= true (:version? result)))
    (is (nil? (:repo result)))))

(deftest db-worker-node-main-version-exits-early-without-repo
  (async done
         (let [exit-code* (atom nil)
               start-called? (atom false)]
           (-> (test-helper/with-js-property-override
                 js/process
                 "argv"
                 #js ["node" "dist/db-worker-node.js" "--version"]
                 (fn []
                   (test-helper/with-js-property-override
                     js/process
                     "exit"
                     (fn [code]
                       (reset! exit-code* code)
                       (throw (ex-info "process-exit" {:code code})))
                     (fn []
                       (p/with-redefs [db-worker-node/start-daemon! (fn [_]
                                                                      (reset! start-called? true)
                                                                      (p/rejected (ex-info "should-not-start-daemon" {})))]
                         (p/resolved
                          (let [output (with-out-str
                                         (try
                                           (db-worker-node/main)
                                           (catch :default e
                                             (when-not (= "process-exit" (.-message e))
                                               (throw e)))))]
                            (is (= 0 @exit-code*))
                            (is (= false @start-called?))
                            (is (string/includes? output "Revision:")))))))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-main-missing-root-dir-prints-error-and-exits-1
  (async done
         (let [exit-code* (atom nil)
               stdout* (atom [])
               stderr* (atom [])
               start-called? (atom false)]
           (-> (run-main-with-overrides
                {:argv #js ["node" "dist/db-worker-node.js" "--repo" "logseq_db_missing_root"]
                 :on-exit (fn [code]
                            (reset! exit-code* code)
                            (throw (ex-info "process-exit" {:code code})))
                 :on-log (fn [& args]
                           (swap! stdout* conj (string/join " " args)))
                 :on-error (fn [& args]
                             (swap! stderr* conj (string/join " " args)))
                 :start-daemon-fn (fn [_]
                                    (reset! start-called? true)
                                    (p/rejected (ex-info "should-not-start-daemon" {})))})
               (p/then (fn [_]
                         (is (= 1 @exit-code*))
                         (is (= false @start-called?))
                         (is (empty? @stdout*))
                         (is (some #(string/includes? % "root-dir is required")
                                   @stderr*))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-main-missing-repo-prints-error-and-exits-1
  (async done
         (let [exit-code* (atom nil)
               stdout* (atom [])
               stderr* (atom [])
               start-called? (atom false)]
           (-> (run-main-with-overrides
                {:argv #js ["node" "dist/db-worker-node.js" "--root-dir" "/tmp/logseq-root"]
                 :on-exit (fn [code]
                            (reset! exit-code* code)
                            (throw (ex-info "process-exit" {:code code})))
                 :on-log (fn [& args]
                           (swap! stdout* conj (string/join " " args)))
                 :on-error (fn [& args]
                             (swap! stderr* conj (string/join " " args)))
                 :start-daemon-fn (fn [_]
                                    (reset! start-called? true)
                                    (p/rejected (ex-info "should-not-start-daemon" {})))})
               (p/then (fn [_]
                         (is (= 1 @exit-code*))
                         (is (= false @start-called?))
                         (is (empty? @stdout*))
                         (is (some #(string/includes? % "repo is required")
                                   @stderr*))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-owner-source-cli-is-written-into-lock
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-owner-source-cli")
               repo (str "logseq_db_owner_cli_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo
                                       :owner-source :cli})
                       _ (reset! daemon {:stop! stop!})
                       lock-json (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))]
                 (is (= "cli" (gobj/get lock-json "owner-source"))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-owner-source-electron-is-written-into-lock
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-owner-source-electron")
               repo (str "logseq_db_owner_electron_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo
                                       :owner-source :electron})
                       _ (reset! daemon {:stop! stop!})
                       lock-json (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))]
                 (is (= "electron" (gobj/get lock-json "owner-source"))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-handle-event-encodes-sse-json-payload
  (let [handle-event! #'db-worker-node/handle-event!
        *sse-clients @#'db-worker-node/*sse-clients
        writes (atom [])
        fake-res #js {:write (fn [message]
                               (swap! writes conj message))}]
    (reset! *sse-clients #{fake-res})
    (handle-event! "sync-db-changes" {:repo "graph-a"})
    (is (= 1 (count @writes)))
    (let [raw-message (first @writes)
          event-json (-> raw-message
                         (string/replace-first #"^data: " "")
                         (string/replace #"\n\n$" ""))
          parsed (js->clj (js/JSON.parse event-json) :keywordize-keys true)]
      (is (= "sync-db-changes" (:type parsed)))
      (is (= {:repo "graph-a"}
             (ldb/read-transit-str (:payload parsed)))))))

(deftest db-worker-node-handle-event-preserves-namespaced-type
  (let [handle-event! #'db-worker-node/handle-event!
        *sse-clients @#'db-worker-node/*sse-clients
        writes (atom [])
        fake-res #js {:write (fn [message]
                               (swap! writes conj message))}]
    (reset! *sse-clients #{fake-res})
    (handle-event! :db-worker/ui-request {:request-id "r1"})
    (is (= 1 (count @writes)))
    (let [raw-message (first @writes)
          event-json (-> raw-message
                         (string/replace-first #"^data: " "")
                         (string/replace #"\n\n$" ""))
          parsed (js->clj (js/JSON.parse event-json) :keywordize-keys true)]
      (is (= "db-worker/ui-request" (:type parsed)))
      (is (= {:request-id "r1"}
             (ldb/read-transit-str (:payload parsed)))))))

(deftest db-worker-node-help-documents-required-root-dir-and-omits-server-list-file
  (let [show-help! #'db-worker-node/show-help!
        output (binding [style/*color-enabled?* true]
                 (with-out-str (show-help!)))
        plain-output (style/strip-ansi output)]
    (is (not (string/includes? (style/strip-ansi output) "--auth-token")))
    (is (not (string/includes? plain-output "--rtc-ws-url")))
    (is (not (string/includes? plain-output "--server-list-file")))
    (is (not (string/includes? plain-output "(default ~/logseq)")))
    (is (re-find #"\u001b\[[0-9;]*moptions\u001b\[[0-9;]*m:" output))
    (is (contains-bold? output "db-worker-node"))
    (is (contains-bold? output "--root-dir"))
    (is (contains-bold? output "--repo"))
    (is (string/includes? plain-output "--root-dir"))
    (is (string/includes? plain-output "(required)"))
    (is (string/includes? plain-output "--create-empty-db"))
    (is (contains-bold? output "--create-empty-db"))
    (is (not (contains-bold? output "--rtc-ws-url")))
    (is (contains-bold? output "--log-level"))))

(deftest db-worker-node-start-daemon-uses-empty-datoms-when-create-empty-enabled
  (async done
         (let [data-dir (node-helper/create-tmp-dir "db-worker-create-empty-start")
               repo (str "logseq_db_create_empty_start_" (subs (str (random-uuid)) 0 8))
               lock-file-path (lock-path data-dir repo)
               invoke-calls (atom [])]
           (-> (p/with-redefs [platform-node/node-platform (fn [_opts] #js {})
                               db-core/init-core! (fn [_platform]
                                                    #js {:remoteInvoke (fn [method args-transit]
                                                                         (swap! invoke-calls conj
                                                                                [method
                                                                                 (ldb/read-transit-str args-transit)])
                                                                         (p/resolved (ldb/write-transit-str nil)))})
                               db-lock/ensure-lock! (fn [_]
                                                      (p/resolved {:path lock-file-path
                                                                   :lock {:repo repo
                                                                          :pid (.-pid js/process)
                                                                          :host "127.0.0.1"
                                                                          :port 0
                                                                          :lock-id "create-empty-lock"}}))
                               db-lock/update-lock! (fn [_path lock] lock)]
                 (p/let [{:keys [stop!]} (db-worker-node/start-daemon! {:root-dir data-dir
                                                                        :repo repo
                                                                        :create-empty-db? true
                                                                        :log-level "error"})
                         _ (is (= ["thread-api/init" []]
                                  (first @invoke-calls)))
                         _ (is (= ["thread-api/create-or-open-db" [repo {:datoms []
                                                                         :sync-download-graph? true}]]
                                  (second @invoke-calls)))
                         _ (stop!)]
                   true))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-start-daemon-uses-default-startup-opts-without-create-empty
  (async done
         (let [data-dir (node-helper/create-tmp-dir "db-worker-default-start")
               repo (str "logseq_db_default_start_" (subs (str (random-uuid)) 0 8))
               lock-file-path (lock-path data-dir repo)
               invoke-calls (atom [])]
           (-> (p/with-redefs [platform-node/node-platform (fn [_opts] #js {})
                               db-core/init-core! (fn [_platform]
                                                    #js {:remoteInvoke (fn [method args-transit]
                                                                         (swap! invoke-calls conj
                                                                                [method
                                                                                 (ldb/read-transit-str args-transit)])
                                                                         (p/resolved (ldb/write-transit-str nil)))})
                               db-lock/ensure-lock! (fn [_]
                                                      (p/resolved {:path lock-file-path
                                                                   :lock {:repo repo
                                                                          :pid (.-pid js/process)
                                                                          :host "127.0.0.1"
                                                                          :port 0
                                                                          :lock-id "default-lock"}}))
                               db-lock/update-lock! (fn [_path lock] lock)]
                 (p/let [{:keys [stop!]} (db-worker-node/start-daemon! {:root-dir data-dir
                                                                        :repo repo
                                                                        :log-level "error"})
                         _ (is (= ["thread-api/init" []]
                                  (first @invoke-calls)))
                         _ (is (= ["thread-api/create-or-open-db" [repo {}]]
                                  (second @invoke-calls)))
                         _ (stop!)]
                   true))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-stop-closes-bound-repo
  (async done
         (let [data-dir (node-helper/create-tmp-dir "db-worker-stop-close-db")
               repo (str "logseq_db_stop_close_" (subs (str (random-uuid)) 0 8))
               lock-file-path (lock-path data-dir repo)
               invoke-calls (atom [])]
           (-> (p/with-redefs [platform-node/node-platform (fn [_opts] #js {})
                               db-core/init-core! (fn [_platform]
                                                    #js {:remoteInvoke (fn [method args-transit]
                                                                         (swap! invoke-calls conj
                                                                                [method
                                                                                 (ldb/read-transit-str args-transit)])
                                                                         (p/resolved (ldb/write-transit-str nil)))})
                               db-lock/ensure-lock! (fn [_]
                                                      (p/resolved {:path lock-file-path
                                                                   :lock {:repo repo
                                                                          :pid (.-pid js/process)
                                                                          :host "127.0.0.1"
                                                                          :port 0
                                                                          :lock-id "stop-close-lock"}}))
                               db-lock/update-lock! (fn [_path lock] lock)]
                 (p/let [{:keys [stop!]} (db-worker-node/start-daemon! {:root-dir data-dir
                                                                        :repo repo
                                                                        :log-level "error"})
                         _ (stop!)]
                   (is (= ["thread-api/init" []]
                          (first @invoke-calls)))
                   (is (= ["thread-api/create-or-open-db" [repo {}]]
                          (second @invoke-calls)))
                   (is (= ["thread-api/close-db" [repo]]
                          (nth @invoke-calls 2)))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-start-daemon-registers-and-unregisters-derived-server-list-entry
  (async done
         (let [data-dir (node-helper/create-tmp-dir "db-worker-server-list")
               repo (str "logseq_db_server_list_" (subs (str (random-uuid)) 0 8))
               lock-file-path (lock-path data-dir repo)
               server-list-file (cli-config/server-list-path data-dir)]
           (-> (p/with-redefs [platform-node/node-platform (fn [_opts] #js {})
                               db-core/init-core! (fn [_platform]
                                                    #js {:remoteInvoke (fn [_method _args-transit]
                                                                         (p/resolved (ldb/write-transit-str nil)))})
                               db-lock/ensure-lock! (fn [_]
                                                      (p/resolved {:path lock-file-path
                                                                   :lock {:repo repo
                                                                          :pid (.-pid js/process)
                                                                          :lock-id "server-list-lock"
                                                                          :owner-source :cli}}))
                               db-lock/update-lock! (fn [_path lock] lock)]
                 (p/let [{:keys [port stop!]} (db-worker-node/start-daemon! {:root-dir data-dir
                                                                             :repo repo
                                                                             :log-level "error"})
                         contents-after-start (.toString (fs/readFileSync server-list-file) "utf8")
                         _ (is (string/includes? contents-after-start (str (.-pid js/process) " " port)))
                         _ (stop!)
                         contents-after-stop (when (fs/existsSync server-list-file)
                                               (.toString (fs/readFileSync server-list-file) "utf8"))]
                   (is (or (nil? contents-after-stop)
                           (not (string/includes? contents-after-stop (str (.-pid js/process) " " port)))))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally done)))))

(deftest db-worker-node-repo-error-handles-keyword-methods
  (let [repo-error #'db-worker-node/repo-error
        bound-repo "logseq_db_bound"]
    (is (nil? (repo-error :thread-api/list-db [] bound-repo)))
    (is (nil? (repo-error :thread-api/get-db-sync-config [] bound-repo)))
    (is (nil? (repo-error :thread-api/sync-app-state [{:auth/id-token "token"}] bound-repo)))
    (is (nil? (repo-error :thread-api/db-sync-list-remote-graphs [] bound-repo)))
    (is (nil? (repo-error "thread-api/list-db" [] bound-repo)))
    (is (nil? (repo-error :thread-api/rtc-get-graphs ["token"] bound-repo)))
    (is (nil? (repo-error :thread-api/set-context [{:repo "not-a-repo-arg"}] bound-repo)))
    (is (nil? (repo-error :thread-api/resolve-ui-request ["req-id" {:password "pw"}] bound-repo)))
    (is (nil? (repo-error :thread-api/reject-ui-request ["req-id" {:code :cancelled}] bound-repo)))
    (is (nil? (repo-error :thread-api/cancel-ui-requests [{:context :logout}] bound-repo)))
    (is (= {:status 400
            :error {:code :missing-repo
                    :message "repo is required"}}
           (repo-error :thread-api/create-or-open-db [] bound-repo)))
    (is (= {:status 400
            :error {:code :missing-repo
                    :message "repo is required"}}
           (repo-error :thread-api/create-or-open-db [:public-key] bound-repo)))
    (is (nil? (repo-error :thread-api/create-or-open-db ["bound"] bound-repo)))
    (is (= {:status 409
            :error {:code :repo-mismatch
                    :message "repo does not match bound repo"
                    :repo "other"
                    :bound-repo bound-repo}}
           (repo-error :thread-api/create-or-open-db ["other"] bound-repo)))))

(deftest db-worker-node-set-context-does-not-trigger-repo-mismatch
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-set-context")
               repo (str "logseq_db_set_context_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:host host :port port :stop! stop!})
                       _ (invoke host port "thread-api/set-db-sync-config"
                                 [{:ws-url "wss://example.com/sync/%s"}])
                       _ (invoke host port "thread-api/sync-app-state"
                                 [{:auth/id-token "token-value"}])
                       config (invoke host port "thread-api/get-db-sync-config" [])
                       _ (is (= "wss://example.com/sync/%s" (:ws-url config)))
                       _ (is (not (contains? config :auth-token)))
                       result (invoke host port "thread-api/set-context" [{:app "desktop"}])]
                 (is (nil? result)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-create-empty-startup-skips-built-in-initial-data
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-empty-initial-data")
               repo (str "logseq_db_empty_initial_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo
                                       :create-empty-db? true})
                       _ (reset! daemon {:stop! stop!})
                       library-result (invoke host port "thread-api/q"
                                              [repo
                                               ['[:find ?e
                                                  :in $ ?title
                                                  :where [?e :block/title ?title]]
                                                common-config/library-page-name]])]
                 (is (empty? library-result)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-sync-status-requires-repo-and-returns-structured-status
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-sync-status")
               repo (str "logseq_db_sync_status_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:host host :port port :stop! stop!})
                       {:keys [status body]} (invoke-raw host port "thread-api/db-sync-status" [])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)
                       _ (is (= 400 status))
                       _ (is (= false (:ok parsed)))
                       _ (is (= "missing-repo" (get-in parsed [:error :code])))
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       status-result (invoke host port "thread-api/db-sync-status" [repo])]
                 (is (= repo (:repo status-result)))
                 (is (contains? status-result :ws-state))
                 (is (contains? status-result :pending-local))
                 (is (contains? status-result :pending-asset))
                 (is (contains? status-result :pending-server))
                 (is (contains? status-result :local-tx))
                 (is (contains? status-result :remote-tx))
                 (is (contains? status-result :graph-id)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-sync-start-and-status-invoke-path
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-sync-start")
               repo (str "logseq_db_sync_start_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:host host :port port :stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       _ (invoke host port "thread-api/set-db-sync-config"
                                 [{:ws-url nil
                                   :http-base "https://example.com"}])
                       start-result (invoke host port "thread-api/db-sync-start" [repo])
                       status-result (invoke host port "thread-api/db-sync-status" [repo])]
                 (is (nil? start-result))
                 (is (= repo (:repo status-result)))
                 (is (= :inactive (:ws-state status-result)))
                 (is (contains? status-result :pending-local))
                 (is (contains? status-result :pending-server)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-cold-start-recovers-marker-split-over-real-transports
  (async
   done
   (let [seed-daemon* (atom nil)
         runtime-daemon* (atom nil)
         peer* (atom nil)
         data-dir
         (node-helper/create-tmp-dir "db-worker-marker-recovery")
         repo
         (str "logseq_db_marker_recovery_"
              (subs (str (random-uuid)) 0 8))
         graph-id "runtime-marker-graph"
         cursor 2734
         token (future-test-id-token)
         large-title
         (str (apply str (repeat 1535 "汉"))
              " cold-runtime-marker-split")
         payload (.encode sync-large-title/text-encoder large-title)
         state* (atom nil)
         requests* (atom [])
         ws-messages* (atom [])]
     (->
      (p/let [{seed-stop! :stop!}
              (start-daemon!
               {:root-dir data-dir
                :repo repo
                :owner-source :cli})
              _ (reset! seed-daemon* seed-stop!)
              payload-digest
              (sync-large-title/<sha256-hex payload)
              local-marker
              (sync-large-title/large-title-object
               "11111111-1111-4111-8111-111111111111"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-plain-payload-format
               payload-digest)
              remote-marker
              (sync-large-title/large-title-object
               "22222222-2222-4222-8222-222222222222"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-plain-payload-format
               payload-digest)
              {:keys [block-uuid
                      local-checksum
                      local-server-checksum]}
              (seed-cold-runtime-marker-split!
               repo cursor graph-id large-title local-marker)
              conn (worker-state/get-datascript-conn repo)
              remote-db
              (:db-after
               (d/with
                @conn
                [[:db/add
                  [:block/uuid block-uuid]
                  :block/title
                  ""]
                 [:db/add
                  [:block/uuid block-uuid]
                  sync-large-title/large-title-object-attr
                  remote-marker]]))
              remote-checksum
              (sync-checksum/recompute-checksum remote-db)
              remote-server-checksum
              (sync-checksum/recompute-server-checksum remote-db)
              _ (is (= local-checksum remote-checksum)
                    "the cold fixture must match the observed legacy checksum")
              _ (is (not= local-server-checksum
                          remote-server-checksum)
                    "only the persisted randomized marker may differ")
              _ (reset!
                 state*
                 {:t cursor
                  :checksum remote-checksum
                  :checksum-version
                  sync-checksum/server-checksum-version
                  :server-checksum remote-server-checksum
                  :block-uuid block-uuid
                  :remote-marker remote-marker
                  :payload payload})
              _ (seed-stop!)
              _ (reset! seed-daemon* nil)
              peer
              (start-runtime-sync-peer!
               {:graph-id graph-id
                :token token
                :state* state*
                :requests* requests*
                :ws-messages* ws-messages*})
              _ (reset! peer* peer)
              {runtime-host :host
               runtime-port :port
               runtime-stop! :stop!}
              (start-daemon!
               {:root-dir data-dir
                :repo repo
                :owner-source :cli})
              _ (reset! runtime-daemon* runtime-stop!)
              cold-conn (worker-state/get-datascript-conn repo)
              cold-entity
              (d/entity @cold-conn [:block/uuid block-uuid])
              _ (is (= large-title (:block/title cold-entity))
                    "the logical title must survive the daemon cold start")
              _ (is (= local-marker
                       (get cold-entity
                            sync-large-title/large-title-object-attr))
                    "the mismatched local marker must survive the cold start")
              _ (is (= 1
                       (count
                        (sync-checksum/server-large-title-markers
                         @cold-conn))))
              _ (is (= cursor (client-op/get-local-tx repo)))
              _ (is (zero?
                     (client-op/get-pending-local-tx-count repo)))
              _ (is (= local-checksum
                       (sync-checksum/recompute-checksum @cold-conn)
                       (client-op/get-local-checksum repo)))
              _ (is (= local-server-checksum
                       (sync-checksum/recompute-server-checksum @cold-conn)
                       (client-op/get-local-server-checksum repo)))
              http-base
              (str "http://127.0.0.1:" (:port peer))
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/set-db-sync-config"
                 [{:ws-url
                   (str "ws://127.0.0.1:" (:port peer)
                        "/sync/%s")
                   :http-base http-base}])
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/sync-app-state"
                 [{:auth/id-token token}])
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/db-sync-start"
                 [repo])
              _ (<wait-for
                 #(some
                   (fn [message]
                     (= "hello" (:type message)))
                   @ws-messages*)
                 "WebSocket hello from cold db-worker-node")
              _ (<wait-for
                 #(some
                   (fn [{:keys [path]}]
                     (= path
                        (str "/sync/" graph-id
                             "/checksum/large-title-markers")))
                   @requests*)
                 "authenticated marker-state request")
              _ (<wait-for
                 #(= remote-server-checksum
                     (client-op/get-local-server-checksum repo))
                 "durable local marker checksum convergence")
              first-status
              (invoke
               runtime-host
               runtime-port
               "thread-api/db-sync-status"
               [repo])
              recovered-entity
              (d/entity
               @(worker-state/get-datascript-conn repo)
               [:block/uuid block-uuid])
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/db-sync-stop"
                 [])
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/db-sync-start"
                 [repo])
              _ (<wait-for
                 #(<= 2
                      (count
                       (filter
                        (fn [message]
                          (= "hello" (:type message)))
                        @ws-messages*)))
                 "fresh hello after runtime restart")
              restart-status
              (invoke
               runtime-host
               runtime-port
               "thread-api/db-sync-status"
               [repo])]
        (let [marker-state-requests
              (filterv
               (fn [{:keys [path]}]
                 (= path
                    (str "/sync/" graph-id
                         "/checksum/large-title-markers")))
               @requests*)
              marker-asset-requests
              (filterv
               (fn [{:keys [path]}]
                 (= path
                    (str "/assets/" graph-id "/"
                         "22222222-2222-4222-8222-222222222222.txt")))
               @requests*)]
          (is (= :open (:ws-state first-status)))
          (is (nil? (:last-error first-status)))
          (is (= cursor
                 (:local-tx first-status)
                 (:remote-tx first-status)))
          (is (zero? (:pending-local first-status)))
          (is (= large-title (:block/title recovered-entity))
              "automatic transport recovery must preserve the logical title")
          (is (= (:remote-marker @state*)
                 (get recovered-entity
                      sync-large-title/large-title-object-attr))
              "the cold SQLite marker must converge to server authority")
          (is (= 1 (count marker-state-requests))
              "a fresh synchronized restart must not repeat recovery")
          (is (= 1 (count marker-asset-requests)))
          (is (every?
               #(= (str "Bearer " token) (:authorization %))
               (concat marker-state-requests marker-asset-requests))
              "both control and payload HTTP requests must use runtime auth")
          (is (not-any?
               #(string/includes? (:path %) "/snapshot/")
               @requests*)
              "runtime recovery must not use snapshot upload/download")
          (is (not-any?
               #(= "tx/batch" (:type %))
               @ws-messages*)
              "runtime recovery must not upload a manual repair transaction")
          (is (= :open (:ws-state restart-status)))
          (is (nil? (:last-error restart-status)))
          (is (= remote-server-checksum
                 (:local-checksum restart-status)
                 (:remote-checksum restart-status)))))
      (p/catch
       (fn [error]
         (is false
             (str "cold runtime marker recovery failed: " error))))
      (p/finally
       (fn []
         (->
          (p/let [_ (db-sync/stop!)
                  _ (when-let [stop! @runtime-daemon*]
                      (stop!))
                  _ (when-let [stop! @seed-daemon*]
                      (stop!))
                  _ (when-let [stop! (:stop! @peer*)]
                      (stop!))]
            nil)
          (p/finally done))))))))

(deftest db-worker-node-e2ee-marker-recovery-retries-aes-key-until-available
  (async
   done
   (let [seed-daemon* (atom nil)
         runtime-daemon* (atom nil)
         peer* (atom nil)
         data-dir
         (node-helper/create-tmp-dir
          "db-worker-e2ee-marker-recovery-delayed-key")
         repo
         (str "logseq_db_e2ee_marker_recovery_"
              (subs (str (random-uuid)) 0 8))
         graph-id "runtime-e2ee-marker-graph"
         cursor 2734
         token (future-test-id-token)
         large-title
         (str (apply str (repeat 1535 "密"))
              " delayed-aes-key-marker-split")
         state* (atom nil)
         requests* (atom [])
         ws-messages* (atom [])
         key-available?* (atom false)
         key-lookups* (atom [])]
     (->
      (p/let [aes-key (crypt/<generate-aes-key)
              encrypted-payload
              (sync-crypt/<encrypt-text-value aes-key large-title)
              payload
              (.encode
               sync-large-title/text-encoder
               encrypted-payload)
              payload-digest
              (sync-large-title/<sha256-hex payload)
              local-marker
              (sync-large-title/large-title-object
               "33333333-3333-4333-8333-333333333333"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-encrypted-payload-format
               payload-digest)
              remote-marker
              (sync-large-title/large-title-object
               "44444444-4444-4444-8444-444444444444"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-encrypted-payload-format
               payload-digest)
              _
              (p/with-redefs
                [sync-crypt/<ensure-graph-aes-key
                 (fn [lookup-repo lookup-graph-id]
                   (swap! key-lookups*
                          conj
                          {:repo lookup-repo
                           :graph-id lookup-graph-id
                           :available? @key-available?*})
                   (p/resolved
                    (when @key-available?*
                      aes-key)))]
                (p/let [{seed-stop! :stop!}
                        (start-daemon!
                         {:root-dir data-dir
                          :repo repo
                          :owner-source :cli})
                        _ (reset! seed-daemon* seed-stop!)
                        {:keys [block-uuid
                                local-checksum
                                local-server-checksum]}
                        (seed-cold-runtime-marker-split!
                         repo
                         cursor
                         graph-id
                         large-title
                         local-marker
                         {:e2ee? true})
                        conn
                        (worker-state/get-datascript-conn repo)
                        remote-db
                        (:db-after
                         (d/with
                          @conn
                          [[:db/add
                            [:block/uuid block-uuid]
                            :block/title
                            ""]
                           [:db/add
                            [:block/uuid block-uuid]
                            sync-large-title/large-title-object-attr
                            remote-marker]]))
                        remote-checksum
                        (sync-checksum/recompute-checksum remote-db)
                        remote-server-checksum
                        (sync-checksum/recompute-server-checksum remote-db)
                        _
                        (is (= local-checksum remote-checksum)
                            "the delayed-key fixture must keep the legacy cursor/checksum contract")
                        _
                        (is (not= local-server-checksum
                                  remote-server-checksum)
                            "the E2EE fixture must differ only in v2 marker identity")
                        _
                        (reset!
                         state*
                         {:t cursor
                          :checksum remote-checksum
                          :checksum-version
                          sync-checksum/server-checksum-version
                          :server-checksum remote-server-checksum
                          :block-uuid block-uuid
                          :remote-marker remote-marker
                          :payload payload})
                        _ (seed-stop!)
                        _ (reset! seed-daemon* nil)
                        peer
                        (start-runtime-sync-peer!
                         {:graph-id graph-id
                          :token token
                          :state* state*
                          :requests* requests*
                          :ws-messages* ws-messages*})
                        _ (reset! peer* peer)
                        {runtime-host :host
                         runtime-port :port
                         runtime-stop! :stop!}
                        (start-daemon!
                         {:root-dir data-dir
                          :repo repo
                          :owner-source :cli})
                        _ (reset! runtime-daemon* runtime-stop!)
                        http-base
                        (str "http://127.0.0.1:" (:port peer))
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/set-db-sync-config"
                         [{:ws-url
                           (str "ws://127.0.0.1:"
                                (:port peer)
                                "/sync/%s")
                           :http-base http-base}])
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/sync-app-state"
                         [{:auth/id-token token}])
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-start"
                         [repo])
                        _
                        (<wait-for
                         #(seq @key-lookups*)
                         "the first unavailable E2EE AES-key lookup")
                        waiting-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])
                        waiting-entity
                        (d/entity
                         @(worker-state/get-datascript-conn repo)
                         [:block/uuid block-uuid])
                        _
                        (is (not= :open (:ws-state waiting-status))
                            "an unavailable AES key must not advertise Online")
                        _
                        (is (= cursor
                               (:local-tx waiting-status)
                               (:remote-tx waiting-status)))
                        _
                        (is (zero? (:pending-local waiting-status)))
                        _
                        (is (= large-title
                               (:block/title waiting-entity))
                            "waiting for the AES key must preserve the logical title")
                        _
                        (is (= local-marker
                               (get
                                waiting-entity
                                sync-large-title/large-title-object-attr))
                            "waiting for the AES key must not partially commit the remote marker")
                        _
                        (is (= local-server-checksum
                               (client-op/get-local-server-checksum
                                repo))
                            "waiting for the AES key must leave the v2 mismatch intact")
                        _
                        (is (not-any?
                             #(= "tx/batch" (:type %))
                             @ws-messages*)
                            "the unavailable-key phase must not emit repair transactions")
                        _ (reset! key-available?* true)
                        _
                        (<wait-for
                         #(some :available? @key-lookups*)
                         "an automatic AES-key retry after the key becomes available"
                         500
                         20)
                        _
                        (<wait-for
                         #(= remote-server-checksum
                             (client-op/get-local-server-checksum repo))
                         "automatic E2EE marker recovery convergence"
                         500
                         20)
                        recovered-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])
                        recovered-entity
                        (d/entity
                         @(worker-state/get-datascript-conn repo)
                         [:block/uuid block-uuid])
                        key-lookups-after-recovery
                        (count @key-lookups*)
                        marker-requests-after-recovery
                        (count
                         (filter
                          (fn [{:keys [path]}]
                            (= path
                               (str
                                "/sync/"
                                graph-id
                                "/checksum/large-title-markers")))
                          @requests*))
                        _ (p/delay 300)
                        _
                        (is (= key-lookups-after-recovery
                               (count @key-lookups*))
                            "successful recovery must not keep retrying AES-key acquisition")
                        _
                        (is (= marker-requests-after-recovery
                               (count
                                (filter
                                 (fn [{:keys [path]}]
                                   (= path
                                      (str
                                       "/sync/"
                                       graph-id
                                       "/checksum/large-title-markers")))
                                 @requests*)))
                            "successful recovery must not loop marker-state requests")
                        hello-count-before-restart
                        (count
                         (filter
                          #(= "hello" (:type %))
                          @ws-messages*))
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-stop"
                         [])
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-start"
                         [repo])
                        _
                        (<wait-for
                         #(< hello-count-before-restart
                             (count
                              (filter
                               (fn [message]
                                 (= "hello" (:type message)))
                               @ws-messages*)))
                         "a healthy E2EE restart after marker recovery")
                        restart-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])]
                  (let [marker-asset-requests
                        (filterv
                         (fn [{:keys [path]}]
                           (= path
                              (str "/assets/"
                                   graph-id
                                   "/44444444-4444-4444-8444-444444444444.txt")))
                         @requests*)]
                    (is (<= 2 (count @key-lookups*))
                        "recovery must retry after initial AES-key unavailability")
                    (is (every?
                         #(= {:repo repo
                              :graph-id graph-id}
                             (select-keys % [:repo :graph-id]))
                         @key-lookups*))
                    (is (= :open (:ws-state recovered-status)))
                    (is (nil? (:last-error recovered-status)))
                    (is (= cursor
                           (:local-tx recovered-status)
                           (:remote-tx recovered-status)))
                    (is (zero? (:pending-local recovered-status)))
                    (is (= remote-server-checksum
                           (:local-checksum recovered-status)
                           (:remote-checksum recovered-status)))
                    (is (= large-title
                           (:block/title recovered-entity))
                        "decryption must authenticate the original logical title")
                    (is (= remote-marker
                           (get
                            recovered-entity
                            sync-large-title/large-title-object-attr))
                        "only the authenticated remote marker may be committed")
                    (is (seq marker-asset-requests))
                    (is (every?
                         #(= (str "Bearer " token)
                             (:authorization %))
                         marker-asset-requests))
                    (is (not-any?
                         #(string/includes? (:path %) "/tx/batch")
                         @requests*)
                        "E2EE recovery must not mutate the remote transaction log")
                    (is (not-any?
                         #(= "tx/batch" (:type %))
                         @ws-messages*)
                        "E2EE recovery must not upload a manual repair transaction")
                    (is (= :open (:ws-state restart-status)))
                    (is (nil? (:last-error restart-status)))
                    (is (= key-lookups-after-recovery
                           (count @key-lookups*))
                        "a converged restart must not re-enter AES-key recovery")
                    (is (= marker-requests-after-recovery
                           (count
                            (filter
                             (fn [{:keys [path]}]
                               (= path
                                  (str
                                   "/sync/"
                                   graph-id
                                   "/checksum/large-title-markers")))
                             @requests*)))
                        "a converged restart must not repeat marker repair"))))]
        nil)
      (p/catch
       (fn [error]
         (is false
             (str "delayed AES-key marker recovery failed: "
                  error))))
      (p/finally
       (fn []
         (->
          (p/let [_ (db-sync/stop!)
                  _ (when-let [stop! @runtime-daemon*]
                      (stop!))
                  _ (when-let [stop! @seed-daemon*]
                      (stop!))
                  _ (when-let [stop! (:stop! @peer*)]
                      (stop!))]
            nil)
          (p/finally done))))))))

(deftest db-worker-node-pending-ack-defers-ready-until-e2ee-marker-recovery
  (async
   done
   (let [seed-daemon* (atom nil)
         runtime-daemon* (atom nil)
         peer* (atom nil)
         data-dir
         (node-helper/create-tmp-dir
          "db-worker-pending-post-ack-marker-recovery")
         repo
         (str "logseq_db_pending_post_ack_"
              (subs (str (random-uuid)) 0 8))
         graph-id "runtime-pending-post-ack-graph"
         cursor 2734
         ack-cursor (inc cursor)
         token (future-test-id-token)
         large-title
         (str (apply str (repeat 1535 "密"))
              " pending-post-ack-marker-split")
         pending-page-uuid (random-uuid)
         pending-page-title "pending user transaction survives exactly once"
         state* (atom nil)
         requests* (atom [])
         ws-messages* (atom [])
         aes-key-lookups* (atom [])]
     (->
      (p/let [aes-key (crypt/<generate-aes-key)
              encrypted-payload
              (sync-crypt/<encrypt-text-value aes-key large-title)
              payload
              (.encode
               sync-large-title/text-encoder
               encrypted-payload)
              payload-digest
              (sync-large-title/<sha256-hex payload)
              local-marker
              (sync-large-title/large-title-object
               "55555555-5555-4555-8555-555555555555"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-encrypted-payload-format
               payload-digest)
              remote-marker
              (sync-large-title/large-title-object
               "66666666-6666-4666-8666-666666666666"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-encrypted-payload-format
               payload-digest)
              _
              (p/with-redefs
                [sync-crypt/<ensure-graph-aes-key
                 (fn [lookup-repo lookup-graph-id]
                   (swap! aes-key-lookups*
                          conj
                          {:repo lookup-repo
                           :graph-id lookup-graph-id})
                   (p/resolved aes-key))]
                (p/let [{seed-stop! :stop!}
                        (start-daemon!
                         {:root-dir data-dir
                          :repo repo
                          :owner-source :cli})
                        _ (reset! seed-daemon* seed-stop!)
                        {:keys [block-uuid]}
                        (seed-cold-runtime-marker-split!
                         repo
                         cursor
                         graph-id
                         large-title
                         local-marker
                         {:e2ee? true})
                        conn
                        (worker-state/get-datascript-conn repo)
                        base-db @conn
                        remote-pre-db
                        (:db-after
                         (d/with
                          base-db
                          [[:db/add
                            [:block/uuid block-uuid]
                            :block/title
                            ""]
                           [:db/add
                            [:block/uuid block-uuid]
                            sync-large-title/large-title-object-attr
                            remote-marker]]))
                        _
                        (ldb/transact!
                         conn
                         [{:block/uuid pending-page-uuid
                           :block/name
                           "pending-user-transaction-page"
                           :block/title pending-page-title
                           :block/tags :logseq.class/Page
                           :block/created-at 1785400001000
                           :block/updated-at 1785400001000}]
                         {:outliner-op :save-block})
                        _
                        (<wait-for
                         #(= 1
                             (client-op/get-pending-local-tx-count
                              repo))
                         "one durable pending user transaction")
                        pending-before-cold-start
                        (client-op/get-pending-local-txs repo)
                        local-db @conn
                        local-legacy-checksum
                        (sync-checksum/recompute-checksum local-db)
                        local-v2-checksum
                        (sync-checksum/recompute-server-checksum
                         local-db)
                        remote-post-db
                        (:db-after
                         (d/with
                          local-db
                          [[:db/add
                            [:block/uuid block-uuid]
                            :block/title
                            ""]
                           [:db/add
                            [:block/uuid block-uuid]
                            sync-large-title/large-title-object-attr
                            remote-marker]]))
                        pre-legacy-checksum
                        (sync-checksum/recompute-checksum
                         remote-pre-db)
                        pre-v2-checksum
                        (sync-checksum/recompute-server-checksum
                         remote-pre-db)
                        post-legacy-checksum
                        (sync-checksum/recompute-checksum
                         remote-post-db)
                        post-v2-checksum
                        (sync-checksum/recompute-server-checksum
                         remote-post-db)
                        _
                        (is (= 1
                               (count pending-before-cold-start)))
                        _
                        (is (not= pre-legacy-checksum
                                  local-legacy-checksum)
                            "the initial hello is stale only because the user transaction is pending")
                        _
                        (is (= post-legacy-checksum
                               local-legacy-checksum)
                            "the acknowledged user transaction must align the legacy checksum")
                        _
                        (is (not= post-v2-checksum
                                  local-v2-checksum)
                            "the post-ack state must retain only the marker v2 split")
                        _
                        (reset!
                         state*
                         {:t cursor
                          :checksum pre-legacy-checksum
                          :checksum-version
                          sync-checksum/server-checksum-version
                          :server-checksum pre-v2-checksum
                          :ack-t ack-cursor
                          :ack-checksum post-legacy-checksum
                          :ack-checksum-version
                          sync-checksum/server-checksum-version
                          :ack-server-checksum post-v2-checksum
                          :tx-batch-response-mode :hang
                          :marker-response-mode :hang
                          :block-uuid block-uuid
                          :remote-marker remote-marker
                          :payload payload})
                        _ (seed-stop!)
                        _ (reset! seed-daemon* nil)
                        peer
                        (start-runtime-sync-peer!
                         {:graph-id graph-id
                          :token token
                          :state* state*
                          :requests* requests*
                          :ws-messages* ws-messages*})
                        _ (reset! peer* peer)
                        {runtime-host :host
                         runtime-port :port
                         runtime-stop! :stop!}
                        (start-daemon!
                         {:root-dir data-dir
                          :repo repo
                          :owner-source :cli})
                        _ (reset! runtime-daemon* runtime-stop!)
                        cold-conn
                        (worker-state/get-datascript-conn repo)
                        cold-pending-page
                        (d/entity
                         @cold-conn
                         [:block/uuid pending-page-uuid])
                        _
                        (is (= pending-page-title
                               (:block/title cold-pending-page))
                            "the pending user transaction must survive the cold start")
                        _
                        (is (= 1
                               (client-op/get-pending-local-tx-count
                                repo)))
                        _
                        (is (= (mapv :tx-id
                                     pending-before-cold-start)
                               (mapv
                                :tx-id
                                (client-op/get-pending-local-txs
                                 repo)))
                            "the cold start must preserve the exact pending transaction identity")
                        http-base
                        (str "http://127.0.0.1:" (:port peer))
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/set-db-sync-config"
                         [{:ws-url
                           (str "ws://127.0.0.1:"
                                (:port peer)
                                "/sync/%s")
                           :http-base http-base}])
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/sync-app-state"
                         [{:auth/id-token token}])
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-start"
                         [repo])
                        _
                        (<wait-for
                         #(some
                           (fn [message]
                             (= "tx/batch" (:type message)))
                           @ws-messages*)
                         "the pending transaction upload")
                        batch-before-ack
                        (first
                         (filter
                          #(= "tx/batch" (:type %))
                          @ws-messages*))
                        pre-ack-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])
                        _
                        (is (not= :open
                                  (:ws-state pre-ack-status))
                            "sync must not report Online while the pending upload is unacknowledged")
                        _
                        (is (false?
                             (:sync-ready? pre-ack-status))
                            "sync readiness must wait for the upload acknowledgement and marker recovery")
                        _
                        (is (= cursor
                               (:local-tx pre-ack-status)
                               (:remote-tx pre-ack-status)))
                        _
                        (is (= 1
                               (:pending-local pre-ack-status)))
                        _
                        (is (= 1 (count (:txs batch-before-ack)))
                            "the pending user transaction must be uploaded in exactly one entry")
                        _
                        (is (= (str
                                (:tx-id
                                 (first
                                  pending-before-cold-start)))
                               (str
                                (get-in
                                 batch-before-ack
                                 [:txs 0 :tx-id])))
                            "the upload must preserve the pending transaction identity")
                        _
                        ((:release-batch-acks! peer))
                        post-ack-status
                        (<wait-for-sync-status
                         runtime-host
                         runtime-port
                         repo
                         #(and (= ack-cursor
                                  (:local-tx %))
                               (zero?
                                (:pending-local %)))
                         "the exact pending transaction acknowledgement")
                        post-ack-entity
                        (d/entity
                         @(worker-state/get-datascript-conn repo)
                         [:block/uuid block-uuid])
                        post-ack-pending-page
                        (d/entity
                         @(worker-state/get-datascript-conn repo)
                         [:block/uuid pending-page-uuid])
                        _
                        (is (= ack-cursor
                               (:local-tx post-ack-status)
                               (:remote-tx post-ack-status))
                            "one acknowledged batch must advance the cursor exactly once")
                        _
                        (is (not= :repair-required
                                  (:ws-state post-ack-status))
                            "a marker-only post-ack v2 split must enter recovery, not repair-required")
                        _
                        (is (not= :open
                                  (:ws-state post-ack-status))
                            "post-ack sync must wait for the held marker recovery response")
                        _
                        (is (false?
                             (:sync-ready? post-ack-status))
                            "post-ack marker recovery must keep readiness false")
                        _
                        (is (= pending-page-title
                               (:block/title
                                post-ack-pending-page))
                            "acknowledgement must not lose the user transaction")
                        _
                        (is (= large-title
                               (:block/title post-ack-entity)))
                        _
                        (is (= local-marker
                               (get
                                post-ack-entity
                                sync-large-title/large-title-object-attr))
                            "the remote marker must not be committed before its encrypted payload is authenticated")
                        _
                        (<wait-for
                         #(some
                           (fn [{:keys [path]}]
                             (= path
                                (str
                                 "/sync/"
                                 graph-id
                                 "/checksum/large-title-markers")))
                           @requests*)
                         "post-ack marker-state recovery request")
                        held-recovery-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])
                        _
                        (is (not= :open
                                  (:ws-state held-recovery-status)))
                        _
                        (is (false?
                             (:sync-ready?
                              held-recovery-status)))
                        _
                        ((:release-marker-responses! peer))
                        _
                        (<wait-for
                         #(= post-v2-checksum
                             (client-op/get-local-server-checksum
                              repo))
                         "post-ack E2EE marker checksum convergence"
                         500
                         20)
                        final-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])
                        final-entity
                        (d/entity
                         @(worker-state/get-datascript-conn repo)
                         [:block/uuid block-uuid])
                        final-pending-page
                        (d/entity
                         @(worker-state/get-datascript-conn repo)
                         [:block/uuid pending-page-uuid])
                        batch-count-after-recovery
                        (count
                         (filter
                          #(= "tx/batch" (:type %))
                          @ws-messages*))
                        hello-count-before-restart
                        (count
                         (filter
                          #(= "hello" (:type %))
                          @ws-messages*))
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-stop"
                         [])
                        _
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-start"
                         [repo])
                        _
                        (<wait-for
                         #(< hello-count-before-restart
                             (count
                              (filter
                               (fn [message]
                                 (= "hello" (:type message)))
                               @ws-messages*)))
                         "post-recovery restart hello")
                        _ (p/delay 300)
                        restart-status
                        (invoke
                         runtime-host
                         runtime-port
                         "thread-api/db-sync-status"
                         [repo])]
                  (let [batch-messages
                        (filterv
                         #(= "tx/batch" (:type %))
                         @ws-messages*)
                        marker-state-requests
                        (filterv
                         (fn [{:keys [path]}]
                           (= path
                              (str
                               "/sync/"
                               graph-id
                               "/checksum/large-title-markers")))
                         @requests*)
                        marker-asset-requests
                        (filterv
                         (fn [{:keys [path]}]
                           (= path
                              (str
                               "/assets/"
                               graph-id
                               "/66666666-6666-4666-8666-666666666666.txt")))
                         @requests*)
                        final-client
                        @worker-state/*db-sync-client]
                    (is (= :open (:ws-state final-status)))
                    (is (true?
                         (:sync-ready? final-status)))
                    (is (nil? (:last-error final-status)))
                    (is (= ack-cursor
                           (:local-tx final-status)
                           (:remote-tx final-status)))
                    (is (zero? (:pending-local final-status)))
                    (is (= post-v2-checksum
                           (:local-checksum final-status)
                           (:remote-checksum final-status)))
                    (is (= post-legacy-checksum
                           (sync-checksum/recompute-checksum
                            @(worker-state/get-datascript-conn
                              repo)))
                        "legacy checksum must remain converged after marker recovery")
                    (is (= large-title
                           (:block/title final-entity))
                        "E2EE recovery must preserve the authenticated logical title")
                    (is (= remote-marker
                           (get
                            final-entity
                            sync-large-title/large-title-object-attr))
                        "the authenticated server marker must become durable")
                    (is (= pending-page-title
                           (:block/title final-pending-page))
                        "the acknowledged user transaction must remain durable")
                    (is (= 1
                           batch-count-after-recovery
                           (count batch-messages))
                        "the user transaction must never be uploaded twice")
                    (is (= 1
                           (reduce
                            +
                            (map
                             #(count (:txs %))
                             batch-messages)))
                        "the server must receive exactly one transaction entry")
                    (is (empty?
                         (some-> final-client
                                 :inflight
                                 deref))
                        "the acknowledged transaction must leave no inflight residue")
                    (is (= 1
                           (count marker-state-requests))
                        "post-ack marker recovery must run exactly once")
                    (is (= 1
                           (count marker-asset-requests)))
                    (is (every?
                         #(= (str "Bearer " token)
                             (:authorization %))
                         (concat
                          marker-state-requests
                          marker-asset-requests)))
                    (is (seq @aes-key-lookups*)
                        "the E2EE payload must use the controlled AES-key boundary")
                    (is (every?
                         #(= {:repo repo
                              :graph-id graph-id}
                             %)
                         @aes-key-lookups*))
                    (is (= :open
                           (:ws-state restart-status)))
                    (is (true?
                         (:sync-ready? restart-status)))
                    (is (nil?
                         (:last-error restart-status)))
                    (is (= ack-cursor
                           (:local-tx restart-status)
                           (:remote-tx restart-status)))
                    (is (zero?
                         (:pending-local restart-status)))
                    (is (= post-v2-checksum
                           (:local-checksum restart-status)
                           (:remote-checksum restart-status)))
                    (is (= batch-count-after-recovery
                           (count
                            (filter
                             #(= "tx/batch" (:type %))
                             @ws-messages*)))
                        "restart must not re-upload the acknowledged transaction")
                    (is (= 1
                           (count
                            (filter
                             (fn [{:keys [path]}]
                               (= path
                                  (str
                                   "/sync/"
                                   graph-id
                                   "/checksum/large-title-markers")))
                             @requests*)))
                        "restart must not repeat a converged marker recovery"))))]
        nil)
      (p/catch
       (fn [error]
         (is false
             (str
              "pending post-ack marker recovery failed: "
              error))))
      (p/finally
       (fn []
         (->
          (p/let [_ (db-sync/stop!)
                  _ (when-let [stop! @runtime-daemon*]
                      (stop!))
                  _ (when-let [stop! @seed-daemon*]
                      (stop!))
                  _ (when-let [stop! (:stop! @peer*)]
                      (stop!))]
            nil)
          (p/finally done))))))))

(deftest db-worker-node-does-not-advertise-open-while-marker-recovery-is-unsettled
  (async
   done
   (let [seed-daemon* (atom nil)
         runtime-daemon* (atom nil)
         peer* (atom nil)
         data-dir
         (node-helper/create-tmp-dir
          "db-worker-marker-recovery-unsettled")
         repo
         (str "logseq_db_marker_recovery_unsettled_"
              (subs (str (random-uuid)) 0 8))
         graph-id "runtime-marker-unsettled-graph"
         cursor 2734
         token (future-test-id-token)
         large-title
         (str (apply str (repeat 1535 "汉"))
              " unsettled-runtime-marker-split")
         payload (.encode
                  sync-large-title/text-encoder
                  large-title)
         state* (atom nil)
         requests* (atom [])
         ws-messages* (atom [])]
     (->
      (p/let [{seed-stop! :stop!}
              (start-daemon!
               {:root-dir data-dir
                :repo repo
                :owner-source :cli})
              _ (reset! seed-daemon* seed-stop!)
              payload-digest
              (sync-large-title/<sha256-hex payload)
              local-marker
              (sync-large-title/large-title-object
               "55555555-5555-4555-8555-555555555555"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-plain-payload-format
               payload-digest)
              remote-marker
              (sync-large-title/large-title-object
               "66666666-6666-4666-8666-666666666666"
               sync-large-title/large-title-asset-type
               sync-large-title/large-title-plain-payload-format
               payload-digest)
              {:keys [block-uuid
                      local-checksum
                      local-server-checksum]}
              (seed-cold-runtime-marker-split!
               repo cursor graph-id large-title local-marker)
              conn (worker-state/get-datascript-conn repo)
              remote-db
              (:db-after
               (d/with
                @conn
                [[:db/add
                  [:block/uuid block-uuid]
                  :block/title
                  ""]
                 [:db/add
                  [:block/uuid block-uuid]
                  sync-large-title/large-title-object-attr
                  remote-marker]]))
              remote-checksum
              (sync-checksum/recompute-checksum remote-db)
              remote-server-checksum
              (sync-checksum/recompute-server-checksum remote-db)
              _ (is (= local-checksum remote-checksum))
              _ (is (not= local-server-checksum
                          remote-server-checksum))
              _ (is (zero?
                     (client-op/get-pending-local-tx-count repo)))
              _ (reset!
                 state*
                 {:t cursor
                  :checksum remote-checksum
                  :checksum-version
                  sync-checksum/server-checksum-version
                  :server-checksum remote-server-checksum
                  :block-uuid block-uuid
                  :remote-marker remote-marker
                  :payload payload
                  :marker-response-mode :hang})
              _ (seed-stop!)
              _ (reset! seed-daemon* nil)
              peer
              (start-runtime-sync-peer!
               {:graph-id graph-id
                :token token
                :state* state*
                :requests* requests*
                :ws-messages* ws-messages*})
              _ (reset! peer* peer)
              {runtime-host :host
               runtime-port :port
               runtime-stop! :stop!}
              (start-daemon!
               {:root-dir data-dir
                :repo repo
                :owner-source :cli})
              _ (reset! runtime-daemon* runtime-stop!)
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/set-db-sync-config"
                 [{:ws-url
                   (str "ws://127.0.0.1:" (:port peer)
                        "/sync/%s")
                   :http-base
                   (str "http://127.0.0.1:" (:port peer))}])
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/sync-app-state"
                 [{:auth/id-token token}])
              _ (invoke
                 runtime-host
                 runtime-port
                 "thread-api/db-sync-start"
                 [repo])
              _ (<wait-for
                 #(some
                   (fn [{:keys [path]}]
                     (= path
                        (str "/sync/" graph-id
                             "/checksum/large-title-markers")))
                   @requests*)
                 "unsettled marker-state request")
              _ (p/delay 200)
              stalled-status
              (invoke
               runtime-host
               runtime-port
               "thread-api/db-sync-status"
               [repo])
              _ (is (= cursor
                       (:local-tx stalled-status)
                       (:remote-tx stalled-status)))
              _ (is (zero? (:pending-local stalled-status)))
              _ (is (= local-server-checksum
                       (:local-checksum stalled-status)))
              _ (is (= remote-server-checksum
                       (:remote-checksum stalled-status)))
              _ (is (not
                     (and (= :open (:ws-state stalled-status))
                          (nil? (:last-error stalled-status))))
                    (str
                     "an unresolved hello recovery must not remain "
                     "indistinguishable from healthy open sync: "
                     (select-keys
                      stalled-status
                      [:ws-state
                       :last-error
                       :local-tx
                       :remote-tx
                       :pending-local
                       :local-checksum
                       :remote-checksum])))
              _ ((:release-marker-responses! peer))
              recovered-status
              (<wait-for-sync-status
               runtime-host
               runtime-port
               repo
               #(and (= :open (:ws-state %))
                     (nil? (:last-error %))
                     (= remote-server-checksum
                        (:local-checksum %)
                        (:remote-checksum %)))
               "eventual marker recovery after response resumes")
              recovered-entity
              (d/entity
               @(worker-state/get-datascript-conn repo)
               [:block/uuid block-uuid])]
        (is (= :open (:ws-state recovered-status)))
        (is (= large-title (:block/title recovered-entity)))
        (is (= remote-marker
               (get
                recovered-entity
                sync-large-title/large-title-object-attr)))
        (is (= 1
               (count
                (filter
                 (fn [{:keys [path]}]
                   (= path
                      (str "/sync/" graph-id
                           "/checksum/large-title-markers")))
                 @requests*)))
            "resuming the same request must not create a manual retry")
        (is (not-any?
             #(= "tx/batch" (:type %))
             @ws-messages*)))
      (p/catch
       (fn [error]
         (is false
             (str "unsettled runtime marker recovery failed: "
                  error))))
      (p/finally
       (fn []
         (->
          (p/let [_ (db-sync/stop!)
                  _ (when-let [stop! @runtime-daemon*]
                      (stop!))
                  _ (when-let [stop! @seed-daemon*]
                      (stop!))
                  _ (when-let [stop! (:stop! @peer*)]
                      (stop!))]
            nil)
          (p/finally done))))))))

(deftest bundled-db-worker-node-recovers-cold-marker-split-over-real-transports
  (if-let [bundle-path
           (some-> (gobj/get
                    (.-env js/process)
                    "LOGSEQ_MARKER_RECOVERY_BUNDLE")
                   not-empty)]
    (async
     done
     (let [seed-daemon* (atom nil)
           runtime* (atom nil)
           peer* (atom nil)
           data-dir
           (node-helper/create-tmp-dir
            "bundled-db-worker-marker-recovery")
           repo
           (str "logseq_db_bundled_marker_recovery_"
                (subs (str (random-uuid)) 0 8))
           graph-id "bundled-runtime-marker-graph"
           cursor 2734
           token (future-test-id-token)
           large-title
           (str (apply str (repeat 1535 "汉"))
                " bundled-cold-runtime-marker-split")
           payload (.encode
                    sync-large-title/text-encoder
                    large-title)
           state* (atom nil)
           requests* (atom [])
           ws-messages* (atom [])]
       (->
        (p/let [_ (is (fs/existsSync bundle-path)
                      (str "missing bundled db-worker-node: "
                           bundle-path))
                {seed-stop! :stop!}
                (start-daemon!
                 {:root-dir data-dir
                  :repo repo
                  :owner-source :cli})
                _ (reset! seed-daemon* seed-stop!)
                payload-digest
                (sync-large-title/<sha256-hex payload)
                local-marker
                (sync-large-title/large-title-object
                 "33333333-3333-4333-8333-333333333333"
                 sync-large-title/large-title-asset-type
                 sync-large-title/large-title-plain-payload-format
                 payload-digest)
                remote-marker
                (sync-large-title/large-title-object
                 "44444444-4444-4444-8444-444444444444"
                 sync-large-title/large-title-asset-type
                 sync-large-title/large-title-plain-payload-format
                 payload-digest)
                {:keys [block-uuid
                        local-checksum
                        local-server-checksum]}
                (seed-cold-runtime-marker-split!
                 repo
                 cursor
                 graph-id
                 large-title
                 local-marker)
                conn (worker-state/get-datascript-conn repo)
                remote-db
                (:db-after
                 (d/with
                  @conn
                  [[:db/add
                    [:block/uuid block-uuid]
                    :block/title
                    ""]
                   [:db/add
                    [:block/uuid block-uuid]
                    sync-large-title/large-title-object-attr
                    remote-marker]]))
                remote-checksum
                (sync-checksum/recompute-checksum remote-db)
                remote-server-checksum
                (sync-checksum/recompute-server-checksum remote-db)
                _ (is (= local-checksum remote-checksum))
                _ (is (not= local-server-checksum
                            remote-server-checksum))
                _ (is (zero?
                       (client-op/get-pending-local-tx-count repo)))
                _ (reset!
                   state*
                   {:t cursor
                    :checksum remote-checksum
                    :checksum-version
                    sync-checksum/server-checksum-version
                    :server-checksum remote-server-checksum
                    :block-uuid block-uuid
                    :remote-marker remote-marker
                    :payload payload})
                _ (seed-stop!)
                _ (reset! seed-daemon* nil)
                peer
                (start-runtime-sync-peer!
                 {:graph-id graph-id
                  :token token
                  :state* state*
                  :requests* requests*
                  :ws-messages* ws-messages*})
                _ (reset! peer* peer)
                runtime
                (start-bundled-daemon!
                 bundle-path data-dir repo)
                _ (reset! runtime* runtime)
                runtime-host (:host runtime)
                runtime-port (:port runtime)
                _ (invoke
                   runtime-host
                   runtime-port
                   "thread-api/set-db-sync-config"
                   [{:ws-url
                     (str "ws://127.0.0.1:" (:port peer)
                          "/sync/%s")
                     :http-base
                     (str "http://127.0.0.1:" (:port peer))}])
                _ (invoke
                   runtime-host
                   runtime-port
                   "thread-api/sync-app-state"
                   [{:auth/id-token token}])
                _ (invoke
                   runtime-host
                   runtime-port
                   "thread-api/db-sync-start"
                   [repo])
                _ (<wait-for
                   #(some
                     (fn [message]
                       (= "hello" (:type message)))
                     @ws-messages*)
                   "WebSocket hello from bundled db-worker-node")
                _ (<wait-for
                   #(some
                     (fn [{:keys [path]}]
                       (= path
                          (str "/sync/" graph-id
                               "/checksum/large-title-markers")))
                     @requests*)
                   "bundled authenticated marker-state request")
                first-status
                (<wait-for-sync-status
                 runtime-host
                 runtime-port
                 repo
                 #(and (= :open (:ws-state %))
                       (= cursor
                          (:local-tx %)
                          (:remote-tx %))
                       (= remote-server-checksum
                          (:local-checksum %)
                          (:remote-checksum %)))
                 "bundled marker checksum convergence")
                recovered-entity
                (invoke
                 runtime-host
                 runtime-port
                 "thread-api/pull"
                 [repo
                  [:block/title
                   sync-large-title/large-title-object-attr]
                  [:block/uuid block-uuid]])
                _ (invoke
                   runtime-host
                   runtime-port
                   "thread-api/db-sync-stop"
                   [])
                _ (invoke
                   runtime-host
                   runtime-port
                   "thread-api/db-sync-start"
                   [repo])
                _ (<wait-for
                   #(<= 2
                        (count
                         (filter
                          (fn [message]
                            (= "hello" (:type message)))
                          @ws-messages*)))
                   "fresh bundled hello after restart")
                restart-status
                (<wait-for-sync-status
                 runtime-host
                 runtime-port
                 repo
                 #(and (= :open (:ws-state %))
                       (= remote-server-checksum
                          (:local-checksum %)
                          (:remote-checksum %)))
                 "bundled restart checksum stability")]
          (let [marker-state-requests
                (filterv
                 (fn [{:keys [path]}]
                   (= path
                      (str "/sync/" graph-id
                           "/checksum/large-title-markers")))
                 @requests*)
                marker-asset-requests
                (filterv
                 (fn [{:keys [path]}]
                   (= path
                      (str "/assets/" graph-id "/"
                           "44444444-4444-4444-8444-444444444444.txt")))
                 @requests*)]
            (is (= :open (:ws-state first-status)))
            (is (nil? (:last-error first-status)))
            (is (zero? (:pending-local first-status)))
            (is (= large-title
                   (:block/title recovered-entity)))
            (is (= remote-marker
                   (get
                    recovered-entity
                    sync-large-title/large-title-object-attr)))
            (is (= 1 (count marker-state-requests)))
            (is (= 1 (count marker-asset-requests)))
            (is (every?
                 #(= (str "Bearer " token)
                     (:authorization %))
                 (concat
                  marker-state-requests
                  marker-asset-requests)))
            (is (not-any?
                 #(string/includes? (:path %) "/snapshot/")
                 @requests*))
            (is (not-any?
                 #(= "tx/batch" (:type %))
                 @ws-messages*))
            (is (= :open (:ws-state restart-status)))
            (is (nil? (:last-error restart-status)))))
        (p/catch
         (fn [error]
           (is false
               (str "bundled cold marker recovery failed: "
                    error))))
        (p/finally
         (fn []
           (->
            (p/let [_ (when-let [stop! (:stop! @runtime*)]
                        (stop!))
                    _ (when-let [stop! @seed-daemon*]
                        (stop!))
                    _ (when-let [stop! (:stop! @peer*)]
                        (stop!))]
              nil)
            (p/finally done)))))))
    (is true
        "set LOGSEQ_MARKER_RECOVERY_BUNDLE to exercise the release bundle")))

(deftest db-worker-node-daemon-smoke-test
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-daemon")
               repo (str "logseq_db_smoke_" (subs (str (random-uuid)) 0 8))
               server-list-file (cli-config/server-list-path data-dir)
               now (js/Date.now)
               page-uuid (random-uuid)
               block-uuid (random-uuid)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon!  {:root-dir data-dir
                                        :repo repo})
                       health (http-get host port "/healthz")
                       missing-readyz (http-get host port "/readyz")
                       health-body (js->clj (js/JSON.parse (:body health)) :keywordize-keys true)
                       server-list-contents (.toString (fs/readFileSync server-list-file) "utf8")
                       _ (do
                           (reset! daemon {:host host :port port :stop! stop!})
                           (is (= 200 (:status health)))
                           (is (= 404 (:status missing-readyz)))
                           (is (= repo (:repo health-body)))
                           (is (= "ready" (:status health-body)))
                           (is (= host (:host health-body)))
                           (is (= port (:port health-body)))
                           (is (= (.-pid js/process) (:pid health-body)))
                           (is (= (node-path/resolve data-dir) (:root-dir health-body)))
                           (is (contains? health-body :owner-source))
                           (is (contains? health-body :revision))
                           (is (string/includes? server-list-contents (str (.-pid js/process) " " port))))
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       dbs (invoke host port "thread-api/list-db" [])
                       _ (is (some #(= repo (:name %)) dbs))
                       lock-file (lock-path data-dir repo)
                       _ (is (fs/existsSync lock-file))
                       lock-contents (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))
                       _ (is (= repo (gobj/get lock-contents "repo")))
                       _ (is (nil? (gobj/get lock-contents "host")))
                       _ (is (nil? (gobj/get lock-contents "port")))
                       _ (invoke host port "thread-api/transact"
                                 [repo
                                  [{:block/uuid page-uuid
                                    :block/title "Smoke Page"
                                    :block/name "smoke-page"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}
                                   {:block/uuid block-uuid
                                    :block/title "Smoke Test"
                                    :block/page [:block/uuid page-uuid]
                                    :block/parent [:block/uuid page-uuid]
                                    :block/order "a0"
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       result (invoke host port "thread-api/q"
                                      [repo
                                       ['[:find ?e
                                          :in $ ?uuid
                                          :where [?e :block/uuid ?uuid]]
                                        block-uuid]])]
                 (is (seq result)))
               (p/catch (fn [e]
                          (println "[db-worker-node-test] e:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!)
                                  (p/finally (fn []
                                               (is (not (fs/existsSync (lock-path data-dir repo))))
                                               (let [contents (when (fs/existsSync server-list-file)
                                                                (.toString (fs/readFileSync server-list-file) "utf8"))]
                                                 (is (or (nil? contents)
                                                         (not (string/includes? contents (str (.-pid js/process) " ")))))
                                                 (done)))))
                              (done))))))))

(deftest db-worker-node-vector-search-finds-outline-context-after-rebuild
  (if-not (semantic-search-integration-enabled?)
    (is true "Skipping semantic search integration test without a macOS embedding endpoint")
    (async done
           (let [daemon (atom nil)
                 data-dir (node-helper/create-tmp-dir "db-worker-vector-search")
                 repo (str "logseq_db_vector_search_" (subs (str (random-uuid)) 0 8))
                 now (js/Date.now)
                 page-uuid (random-uuid)
                 manu-uuid (random-uuid)
                 manu-team-uuid (random-uuid)
                 tony-uuid (random-uuid)
                 tony-team-uuid (random-uuid)]
             (-> (p/let [{:keys [host port stop!]}
                         (start-daemon! {:root-dir data-dir
                                         :repo repo})
                         _ (reset! daemon {:stop! stop!})
                         _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                         _ (invoke host port "thread-api/transact"
                                   [repo
                                    [{:block/uuid page-uuid
                                      :block/title "Teams"
                                      :block/name "teams"
                                      :block/tags #{:logseq.class/Page}
                                      :block/created-at now
                                      :block/updated-at now}
                                     {:block/uuid manu-uuid
                                      :block/title "which team is Manu in?"
                                      :block/page [:block/uuid page-uuid]
                                      :block/parent [:block/uuid page-uuid]
                                      :block/order "a0"
                                      :block/created-at now
                                      :block/updated-at now}
                                     {:block/uuid manu-team-uuid
                                      :block/title "Spurs"
                                      :block/page [:block/uuid page-uuid]
                                      :block/parent [:block/uuid manu-uuid]
                                      :block/order "a0"
                                      :block/created-at now
                                      :block/updated-at now}
                                     {:block/uuid tony-uuid
                                      :block/title "Which team is Tony in?"
                                      :block/page [:block/uuid page-uuid]
                                      :block/parent [:block/uuid page-uuid]
                                      :block/order "b0"
                                      :block/created-at now
                                      :block/updated-at now}
                                     {:block/uuid tony-team-uuid
                                      :block/title "Spurs"
                                      :block/page [:block/uuid page-uuid]
                                      :block/parent [:block/uuid page-uuid]
                                      :block/order "c0"
                                      :block/created-at now
                                      :block/updated-at now}]
                                    {}
                                    nil])
                         _ (invoke host port "thread-api/search-build-blocks-indice-in-worker" [repo true])
                         manu-results (invoke host port "thread-api/search-blocks" [repo "manu spurs" {:limit 10}])
                         tony-results (invoke host port "thread-api/search-blocks" [repo "tony spurs" {:limit 10}])]
                   (is (some #(= manu-uuid (:block/uuid %)) manu-results)
                       (str "Expected Manu vector result, got: " (pr-str (map :block/title manu-results))))
                   (is (= manu-uuid (:block/uuid (first manu-results)))
                       (str "Expected Manu vector result first, got: " (pr-str (map :block/title manu-results))))
                   (is (some #(= tony-uuid (:block/uuid %)) tony-results)
                       (str "Expected Tony vector result, got: " (pr-str (map :block/title tony-results))))
                   (is (= tony-uuid (:block/uuid (first tony-results)))
                       (str "Expected Tony vector result first, got: " (pr-str (map :block/title tony-results)))))
                 (p/catch (fn [e]
                            (println "[db-worker-node-test] vector-search error:" e)
                            (is false (str e))))
                 (p/finally (fn []
                              (if-let [stop! (:stop! @daemon)]
                                (-> (stop!)
                                    (p/finally done))
                                (done)))))))))

(deftest db-worker-node-import-edn
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-import-edn")
               repo-a (str "logseq_db_import_edn_a_" (subs (str (random-uuid)) 0 8))
               repo-b (str "logseq_db_import_edn_b_" (subs (str (random-uuid)) 0 8))
               now (js/Date.now)
               page-uuid (random-uuid)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo-a})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo-a {}])
                       _ (invoke host port "thread-api/transact"
                                 [repo-a
                                  [{:block/uuid page-uuid
                                    :block/title "Import Page"
                                    :block/name "import-page"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       export-edn (invoke host port "thread-api/export-edn" [repo-a {:export-type :graph}])]
                 (is (map? export-edn))
                 (p/let [_ ((:stop! @daemon-a))
                         {:keys [host port stop!]}
                         (start-daemon! {:root-dir data-dir
                                         :repo repo-b})
                         _ (reset! daemon-b {:stop! stop!})
                         _ (invoke host port "thread-api/create-or-open-db" [repo-b {}])
                         _ (invoke host port "thread-api/import-edn" [repo-b export-edn])
                         result (invoke host port "thread-api/q"
                                        [repo-b
                                         ['[:find ?e
                                            :in $ ?title
                                            :where [?e :block/title ?title]]
                                          "Import Page"]])]
                   (is (seq result))))
               (p/catch (fn [e]
                          (println "[db-worker-node-test] import-edn error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (let [stop-a (:stop! @daemon-a)
                                  stop-b (:stop! @daemon-b)]
                              (cond
                                (and stop-a stop-b)
                                (-> (stop-a)
                                    (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))

                                stop-a
                                (-> (stop-a) (p/finally (fn [] (done))))

                                stop-b
                                (-> (stop-b) (p/finally (fn [] (done))))

                                :else
                                (done)))))))))

(deftest db-worker-node-import-db-binary
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-import-sqlite")
               repo-a (str "logseq_db_import_sqlite_a_" (subs (str (random-uuid)) 0 8))
               repo-b (str "logseq_db_import_sqlite_b_" (subs (str (random-uuid)) 0 8))
               now (js/Date.now)
               page-uuid (random-uuid)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo-a})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo-a {}])
                       _ (invoke host port "thread-api/transact"
                                 [repo-a
                                  [{:block/uuid page-uuid
                                    :block/title "SQLite Import Page"
                                    :block/name "sqlite-import-page"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       export-binary (invoke host port "thread-api/export-db-binary" [repo-a])]
                 (is (instance? js/Uint8Array export-binary))
                 (is (pos? (.-byteLength export-binary)))
                 (p/let [_ ((:stop! @daemon-a))
                         {:keys [host port stop!]}
                         (start-daemon! {:root-dir data-dir
                                         :repo repo-b})
                         _ (reset! daemon-b {:stop! stop!})
                         _ (invoke host port "thread-api/import-db-binary" [repo-b export-binary])
                         _ (invoke host port "thread-api/create-or-open-db" [repo-b {}])
                         result (invoke host port "thread-api/q"
                                        [repo-b
                                         ['[:find ?e
                                            :in $ ?title
                                            :where [?e :block/title ?title]]
                                          "SQLite Import Page"]])]
                   (is (seq result))))
               (p/catch (fn [e]
                          (println "[db-worker-node-test] import-sqlite error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (let [stop-a (:stop! @daemon-a)
                                  stop-b (:stop! @daemon-b)]
                              (cond
                                (and stop-a stop-b)
                                (-> (stop-a)
                                    (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))

                                stop-a
                                (-> (stop-a) (p/finally (fn [] (done))))

                                stop-b
                                (-> (stop-b) (p/finally (fn [] (done))))

                                :else
                                (done)))))))))

(deftest db-worker-node-import-db-binary-accepts-raw-request-body
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-import-sqlite-raw")
               repo-a (str "logseq_db_import_sqlite_raw_a_" (subs (str (random-uuid)) 0 8))
               repo-b (str "logseq_db_import_sqlite_raw_b_" (subs (str (random-uuid)) 0 8))
               now (js/Date.now)
               page-uuid (random-uuid)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo-a})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo-a {}])
                       _ (invoke host port "thread-api/transact"
                                 [repo-a
                                  [{:block/uuid page-uuid
                                    :block/title "Raw SQLite Import Page"
                                    :block/name "raw-sqlite-import-page"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       export-binary (invoke host port "thread-api/export-db-binary" [repo-a])]
                 (is (instance? js/Uint8Array export-binary))
                 (is (pos? (.-byteLength export-binary)))
                 (p/let [_ ((:stop! @daemon-a))
                         {:keys [host port stop!]}
                         (start-daemon! {:root-dir data-dir
                                         :repo repo-b})
                         _ (reset! daemon-b {:stop! stop!})
                         {:keys [status body]} (invoke-import-db-binary-raw host port repo-b export-binary)
                         parsed (js->clj (js/JSON.parse body) :keywordize-keys true)
                         _ (invoke host port "thread-api/create-or-open-db" [repo-b {}])
                         result (invoke host port "thread-api/q"
                                        [repo-b
                                         ['[:find ?e
                                            :in $ ?title
                                            :where [?e :block/title ?title]]
                                          "Raw SQLite Import Page"]])]
                   (is (= 200 status))
                   (is (:ok parsed))
                   (is (seq result))))
               (p/catch (fn [e]
                          (println "[db-worker-node-test] import-sqlite-raw error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (let [stop-a (:stop! @daemon-a)
                                  stop-b (:stop! @daemon-b)]
                              (cond
                                (and stop-a stop-b)
                                (-> (stop-a)
                                    (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))

                                stop-a
                                (-> (stop-a) (p/finally (fn [] (done))))

                                stop-b
                                (-> (stop-b) (p/finally (fn [] (done))))

                                :else
                                (done)))))))))

(deftest db-worker-node-export-client-ops-db-binary
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-export-client-ops")
               repo (str "logseq_db_export_client_ops_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       export-binary (invoke host port "thread-api/export-client-ops-db-binary" [repo])
                       decoded (js/Buffer.from export-binary)]
                 (is (instance? js/Uint8Array export-binary))
                 (is (pos? (.-byteLength export-binary)))
                 (is (= "SQLite format 3\u0000"
                        (.toString (.subarray decoded 0 16) "utf8"))))
               (p/catch (fn [e]
                          (println "[db-worker-node-test] export-client-ops-db-binary error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-backup-db-sqlite
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-backup-sqlite")
               repo-a (str "logseq_db_backup_sqlite_a_" (subs (str (random-uuid)) 0 8))
               repo-b (str "logseq_db_backup_sqlite_b_" (subs (str (random-uuid)) 0 8))
               backup-path (node-path/join data-dir "backup" "snapshot.sqlite")
               now (js/Date.now)
               page-uuid (random-uuid)]
           (-> (p/let [{:keys [host port stop!]} (start-daemon! {:root-dir data-dir
                                                                 :repo repo-a})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo-a {}])
                       _ (invoke host port "thread-api/transact"
                                 [repo-a
                                  [{:block/uuid page-uuid
                                    :block/title "Backup Source Page"
                                    :block/name "backup-source-page"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       backup-result (invoke host port "thread-api/backup-db-sqlite" [repo-a backup-path])
                       _ (is (= backup-path (:path backup-result)))
                       _ (is (fs/existsSync backup-path))
                       backup-binary (fs/readFileSync backup-path)]
                 (is (instance? js/Uint8Array backup-binary))
                 (is (pos? (.-byteLength backup-binary)))
                 (p/let [_ ((:stop! @daemon-a))
                         {:keys [host port stop!]} (start-daemon! {:root-dir data-dir
                                                                   :repo repo-b})
                         _ (reset! daemon-b {:stop! stop!})
                         _ (invoke host port "thread-api/import-db-binary" [repo-b backup-binary])
                         _ (invoke host port "thread-api/create-or-open-db" [repo-b {}])
                         result (invoke host port "thread-api/q"
                                        [repo-b
                                         ['[:find ?e
                                            :in $ ?title
                                            :where [?e :block/title ?title]]
                                          "Backup Source Page"]])]
                   (is (seq result))))
               (p/catch (fn [e]
                          (println "[db-worker-node-test] backup-sqlite error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (let [stop-a (:stop! @daemon-a)
                                  stop-b (:stop! @daemon-b)]
                              (cond
                                (and stop-a stop-b)
                                (-> (stop-a)
                                    (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))

                                stop-a
                                (-> (stop-a) (p/finally (fn [] (done))))

                                stop-b
                                (-> (stop-b) (p/finally (fn [] (done))))

                                :else
                                (done)))))))))

(deftest db-worker-node-accepts-prefix-equivalent-repo-test
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-prefix-equivalent")
               bound-repo "demo"
               requested-repo "logseq_db_demo"]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo bound-repo
                                       :create-empty-db? true})
                       _ (reset! daemon {:host host :port port :stop! stop!})
                       {:keys [status body]} (invoke-raw host port "thread-api/create-or-open-db" [requested-repo {}])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 200 status))
                 (is (= true (:ok parsed))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-repo-mismatch-test
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-repo-mismatch")
               repo (str "logseq_db_mismatch_" (subs (str (random-uuid)) 0 8))
               other-repo (str repo "_other")]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:host host :port port :stop! stop!})
                       {:keys [status body]} (invoke-raw host port "thread-api/create-or-open-db" [other-repo {}])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 409 status))
                 (is (= false (:ok parsed)))
                 (is (= "repo-mismatch" (get-in parsed [:error :code]))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-lock-prevents-multiple-daemons
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-lock")
               repo (str "logseq_db_lock_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})]
                 (-> (start-daemon! {:root-dir data-dir
                                     :repo repo})
                     (p/then (fn [_]
                               (is false "expected lock error")))
                     (p/catch (fn [e]
                                (is (= :repo-locked (-> (ex-data e) :code)))))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-write-mutation-fails-for-non-owner-pid
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-write-lease-pid")
               repo (str "logseq_db_write_lease_pid_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       export-binary (invoke host port "thread-api/export-db-binary" [repo])
                       lock-contents (js->clj (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))
                                              :keywordize-keys true)
                       tampered-lock (assoc lock-contents
                                            :pid (inc (:pid lock-contents))
                                            :lock-id "non-owner-lock")
                       _ (fs/writeFileSync lock-file (js/JSON.stringify (clj->js tampered-lock)))
                       {:keys [status body]} (invoke-raw host port "thread-api/import-db-binary" [repo export-binary])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 409 status))
                 (is (= false (:ok parsed)))
                 (is (= "repo-locked" (get-in parsed [:error :code]))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-backup-write-mutation-fails-for-non-owner-pid
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-backup-write-lease-pid")
               repo (str "logseq_db_backup_write_lease_pid_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)
               backup-path (node-path/join data-dir "backup" "non-owner.sqlite")]
           (-> (p/let [{:keys [host port stop!]} (start-daemon! {:root-dir data-dir
                                                                 :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       lock-contents (js->clj (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))
                                              :keywordize-keys true)
                       tampered-lock (assoc lock-contents
                                            :pid (inc (:pid lock-contents))
                                            :lock-id "non-owner-lock")
                       _ (fs/writeFileSync lock-file (js/JSON.stringify (clj->js tampered-lock)))
                       {:keys [status body]} (invoke-raw host port "thread-api/backup-db-sqlite" [repo backup-path])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 409 status))
                 (is (= false (:ok parsed)))
                 (is (= "repo-locked" (get-in parsed [:error :code]))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-write-mutation-succeeds-for-active-owner
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-write-lease-owner")
               repo (str "logseq_db_write_lease_owner_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       lock-contents (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))
                       lock-id (gobj/get lock-contents "lock-id")
                       _ (is (string? lock-id))
                       export-binary (invoke host port "thread-api/export-db-binary" [repo])
                       {:keys [status body]} (invoke-raw host port "thread-api/import-db-binary" [repo export-binary])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 200 status))
                 (is (= true (:ok parsed))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-write-mutation-rejects-stale-lock-after-replacement
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-write-lease-replaced")
               repo (str "logseq_db_write_lease_replaced_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       export-binary (invoke host port "thread-api/export-db-binary" [repo])
                       lock-contents (js->clj (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))
                                              :keywordize-keys true)
                       replaced-lock (assoc lock-contents :lock-id "replaced-lock-id")
                       _ (fs/writeFileSync lock-file (js/JSON.stringify (clj->js replaced-lock)))
                       {:keys [status body]} (invoke-raw host port "thread-api/import-db-binary" [repo export-binary])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 409 status))
                 (is (= false (:ok parsed)))
                 (is (= "repo-locked" (get-in parsed [:error :code]))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-start-recovers-stale-lock-before-acquire
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-stale-lock-recover")
               repo (str "logseq_db_stale_lock_" (subs (str (random-uuid)) 0 8))
               lock-file (lock-path data-dir repo)
               stale-lock {:repo repo
                           :pid 999999
                           :host "127.0.0.1"
                           :port 6553
                           :lock-id "stale-lock-id"}]
           (fs/mkdirSync (node-path/dirname lock-file) #js {:recursive true})
           (fs/writeFileSync lock-file (js/JSON.stringify (clj->js stale-lock)))
           (-> (p/let [{:keys [stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       lock' (js->clj (js/JSON.parse (.toString (fs/readFileSync lock-file) "utf8"))
                                      :keywordize-keys true)]
                 (is (not= 999999 (:pid lock')))
                 (is (not= "stale-lock-id" (:lock-id lock'))))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!)
                                  (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-desktop-and-cli-share-same-graph-daemon
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-desktop-cli")
               config-path (node-path/join data-dir "cli.edn")
               server-list-file (cli-config/server-list-path data-dir)
               repo (str "logseq_db_desktop_cli_" (subs (str (random-uuid)) 0 8))
               now (js/Date.now)
               page-uuid (random-uuid)]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir
                                       :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       _ (invoke host port "thread-api/transact"
                                 [repo
                                  [{:block/uuid page-uuid
                                    :block/title "Desktop+CLI Shared"
                                    :block/name "desktop-cli-shared"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       _ (is (fs/existsSync server-list-file))
                       _ (is (string/includes? (.toString (fs/readFileSync server-list-file) "utf8")
                                               (str (.-pid js/process) " " port)))
                       health (http-get host port "/healthz")
                       health-body (js->clj (js/JSON.parse (:body health)) :keywordize-keys true)
                       ensured (cli-server/ensure-server! {:root-dir data-dir
                                                           :config-path config-path
                                                           :expected-revision (:revision health-body)}
                                                          repo)
                       url (js/URL. (:base-url ensured))
                       cli-host (.-hostname url)
                       cli-port (js/parseInt (.-port url) 10)
                       result (invoke cli-host cli-port "thread-api/q"
                                      [repo
                                       ['[:find ?e
                                          :in $ ?title
                                          :where [?e :block/title ?title]]
                                        "Desktop+CLI Shared"]])]
                 (is (seq result)))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!)
                                  (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-validation-error-returns-400
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-validation-error")
               repo (str "logseq_db_validation_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       ;; Build a deterministic block to use as target and fetch Journal tag db/id
                       journal (invoke host port "thread-api/pull"
                                       [repo [:db/id] [:db/ident :logseq.class/Journal]])
                       journal-id (:db/id journal)
                       now (js/Date.now)
                       page-uuid (random-uuid)
                       block-uuid (random-uuid)
                       _ (invoke host port "thread-api/transact"
                                 [repo
                                  [{:block/uuid page-uuid
                                    :block/title "Validation Target Page"
                                    :block/name "validation-target-page"
                                    :block/tags #{:logseq.class/Page}
                                    :block/created-at now
                                    :block/updated-at now}
                                   {:block/uuid block-uuid
                                    :block/title "Validation Target Block"
                                    :block/page [:block/uuid page-uuid]
                                    :block/parent [:block/uuid page-uuid]
                                    :block/order "a0"
                                    :block/created-at now
                                    :block/updated-at now}]
                                  {}
                                  nil])
                       ;; Try to set the built-in Journal tag on the block
                       {:keys [status body]}
                       (invoke-raw host port "thread-api/apply-outliner-ops"
                                   [repo [[:batch-set-property [[block-uuid] :block/tags journal-id {}]]] {}])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 400 status)
                     "validation errors should return 400, not 500")
                 (is (false? (:ok parsed)))
                 (is (string/includes? (get-in parsed [:error :message]) "Can't set tag")
                     "error message should describe the validation failure"))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(deftest db-worker-node-query-validate-error-returns-400-with-invalid-query-code
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "db-worker-query-validate-error")
               repo (str "logseq_db_query_validate_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]}
                       (start-daemon! {:root-dir data-dir :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       {:keys [status body]}
                       (invoke-raw host port "thread-api/q"
                                   [repo
                                    ['[:find (pull ?e ...)
                                       :where
                                       [?e :block/title "Status"]]]])
                       parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
                 (is (= 400 status)
                     "query validation errors should return 400, not 500")
                 (is (false? (:ok parsed)))
                 (is (= "invalid-query" (get-in parsed [:error :code])))
                 (is (string/includes? (get-in parsed [:error :message]) "Query for unknown vars")))
               (p/catch (fn [e]
                          (is false (str "unexpected error: " e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))
