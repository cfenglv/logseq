(ns frontend.worker.sync.download-test
  (:require [cljs.test :refer [async deftest is]]
            [clojure.string :as string]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.crypt :as sync-crypt]
            [frontend.worker.sync.download :as sync-download]
            [frontend.worker.sync.log-and-state :as rtc-log-and-state]
            [logseq.db :as ldb]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.snapshot :as snapshot]
            [logseq.db.test.helper :as db-test]
            [promesa.core :as p]))

(defn- frame-bytes
  [^js data]
  (let [len (.-byteLength data)
        out (js/Uint8Array. (+ 4 len))
        view (js/DataView. (.-buffer out))]
    (.setUint32 view 0 len false)
    (.set out data 4)
    out))

(defn- stream-from-payload
  [^js payload]
  (js/ReadableStream.
   #js {:start (fn [controller]
                 (.enqueue controller payload)
                 (.close controller))}))

(deftest stream-snapshot-row-batches-ignores-stale-gzip-header-test
  (async done
         (let [rows [[1 "row-1" nil]
                     [2 "row-2" nil]]
               payload (frame-bytes (snapshot/encode-rows rows))
               resp (js/Response.
                     (stream-from-payload payload)
                     #js {:status 200
                          :headers #js {"content-encoding" "gzip"}})
               batches* (atom [])]
           (-> (#'sync-download/<stream-snapshot-row-batches!
                resp
                1000
                (fn [batch]
                  (swap! batches* conj batch)
                  (p/resolved true)))
               (p/then (fn [_]
                         (is (= [rows] @batches*))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest snapshot-watermark-prefers-metadata-and-falls-back-to-preflight-watermark-test
  (async done
         (-> (p/let [modern-t (#'sync-download/<resolve-snapshot-remote-tx
                               {:t 42}
                               41)
                     legacy-t (#'sync-download/<resolve-snapshot-remote-tx
                               {:url "https://sync.example.test/snapshot"}
                               41)]
               (is (= 42 modern-t))
               (is (= 41 legacy-t)))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done))))

(deftest unstable-legacy-snapshot-is-discarded-and-retried-at-a-stable-watermark-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               base "https://sync.example.test"
               graph-id "graph-1"
               pull-values* (atom [381 382 382 382])
               metadata-calls* (atom 0)
               import-calls* (atom 0)
               discarded-imports* (atom [])
               finalized* (atom [])]
           (reset! worker-state/*db-sync-config {:http-base base})
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [_url _opts schema]
                    (case schema
                      :sync/pull
                      (let [t (first @pull-values*)]
                        (swap! pull-values* subvec 1)
                        (p/resolved {:t t}))

                      (p/rejected
                       (ex-info "unexpected schema" {:schema schema}))))
                  sync-download/<fetch-snapshot-metadata!
                  (fn [_base _graph-id]
                    (let [attempt (swap! metadata-calls* inc)]
                      (p/resolved
                       {:snapshot-resp
                        {:url (str base "/legacy-snapshot-" attempt)}
                        :v2? false})))
                  sync-download/<fetch-snapshot-stream!
                  (fn [_base _graph-id snapshot-info & _]
                    (p/resolved
                     (assoc snapshot-info
                            :resp #js {:ok true :status 200})))
                  sync-download/prepare-import!
                  (fn [& _]
                    (p/resolved
                     {:import-id
                      (str "import-" (swap! import-calls* inc))}))
                  sync-download/<stream-snapshot-row-batches!
                  (fn [& _] (p/resolved {:chunk-count 1}))
                  sync-download/clear-import-state!
                  (fn [import-id]
                    (swap! discarded-imports* conj import-id))
                  sync-download/finalize-import!
                  (fn [_repo _graph-id remote-tx import-id & _]
                    (swap! finalized* conj
                           {:remote-tx remote-tx
                            :import-id import-id})
                    (p/resolved nil))
                  rtc-log-and-state/rtc-log (fn [& _] nil)]
                 (sync-download/download-graph-by-id!
                  "synthetic-repo" graph-id false))
               (p/then
                (fn [result]
                  (is (= 382 (:remote-tx result)))
                  (is (= 2 @metadata-calls*))
                  (is (= ["import-1"] @discarded-imports*)
                      "the mixed-cursor legacy snapshot never reaches the live graph")
                  (is (= [{:remote-tx 382
                           :import-id "import-2"}]
                         @finalized*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-config config-prev)
                  (done)))))))

(deftest continuously-changing-legacy-snapshot-fails-after-bounded-clean-retries-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               base "https://sync.example.test"
               graph-id "graph-1"
               pull-values* (atom [10 11 20 21 30 31])
               import-calls* (atom 0)
               discarded-imports* (atom [])
               finalized* (atom [])]
           (reset! worker-state/*db-sync-config {:http-base base})
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [_url _opts schema]
                    (if (= :sync/pull schema)
                      (let [t (first @pull-values*)]
                        (swap! pull-values* subvec 1)
                        (p/resolved {:t t}))
                      (p/rejected
                       (ex-info "unexpected schema" {:schema schema}))))
                  sync-download/<fetch-snapshot-metadata!
                  (fn [& _]
                    (p/resolved
                     {:snapshot-resp {:url (str base "/legacy-snapshot")}
                      :v2? false
                      :fallback-reason :v2-metadata-404}))
                  sync-download/<fetch-snapshot-stream!
                  (fn [_base _graph-id snapshot-info & _]
                    (p/resolved
                     (assoc snapshot-info
                            :resp #js {:ok true :status 200})))
                  sync-download/prepare-import!
                  (fn [& _]
                    (p/resolved
                     {:import-id
                      (str "import-" (swap! import-calls* inc))}))
                  sync-download/<stream-snapshot-row-batches!
                  (fn [& _] (p/resolved {:chunk-count 1}))
                  sync-download/clear-import-state!
                  (fn [import-id]
                    (swap! discarded-imports* conj import-id))
                  sync-download/finalize-import!
                  (fn [& args]
                    (swap! finalized* conj args)
                    (p/resolved nil))
                  rtc-log-and-state/rtc-log (fn [& _] nil)]
                 (sync-download/download-graph-by-id!
                  "synthetic-repo" graph-id false))
               (p/then (fn [_]
                         (is false "expected bounded legacy snapshot failure")))
               (p/catch
                (fn [error]
                  (is (= "db-sync download failed" (ex-message error)))
                  (is (= :verify-legacy-snapshot-watermark
                         (:stage (ex-data error))))
                  (is (= 3 @import-calls*))
                  (is (= ["import-1" "import-2" "import-3"]
                         @discarded-imports*))
                  (is (empty? @finalized*))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-config config-prev)
                  (done)))))))

(deftest frozen-v2-snapshot-does-not-perform-legacy-postflight-pull-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               base "https://sync.example.test"
               graph-id "graph-1"
               pull-calls* (atom 0)
               finalized* (atom [])]
           (reset! worker-state/*db-sync-config {:http-base base})
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [_url _opts schema]
                    (if (= :sync/pull schema)
                      (do
                        (swap! pull-calls* inc)
                        (p/resolved {:t 41}))
                      (p/rejected
                       (ex-info "unexpected schema" {:schema schema}))))
                  sync-download/<fetch-snapshot-metadata!
                  (fn [& _]
                    (p/resolved
                     {:snapshot-resp {:url (str base "/snapshot-v2")
                                      :t 42
                                      :row-count 0
                                      :checksum "0000000000000000"}
                      :v2? true}))
                  sync-download/<fetch-snapshot-stream!
                  (fn [_base _graph-id snapshot-info & _]
                    (p/resolved
                     (assoc snapshot-info
                            :resp #js {:ok true :status 200})))
                  sync-download/prepare-import!
                  (fn [& _] (p/resolved {:import-id "import-v2"}))
                  sync-download/<stream-snapshot-row-batches!
                  (fn [& _] (p/resolved {:chunk-count 1}))
                  sync-download/finalize-import!
                  (fn [_repo _graph-id remote-tx import-id & _]
                    (swap! finalized* conj [remote-tx import-id])
                    (p/resolved nil))
                  rtc-log-and-state/rtc-log (fn [& _] nil)]
                 (sync-download/download-graph-by-id!
                  "synthetic-repo" graph-id false))
               (p/then
                (fn [result]
                  (is (= 42 (:remote-tx result)))
                  (is (= :v2-frozen (:snapshot-protocol result)))
                  (is (= 1 @pull-calls*)
                      "only the compatibility preflight pull remains")
                  (is (= [[42 "import-v2"]] @finalized*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally
                (fn []
                  (reset! worker-state/*db-sync-config config-prev)
                  (done)))))))

(deftest snapshot-metadata-v2-404-falls-back-to-v1-test
  (async done
         (let [urls* (atom [])
               legacy-metadata
               {:url "https://sync.example.test/sync/graph-1/snapshot/stream"}]
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [url _opts _schema]
                    (swap! urls* conj url)
                    (if (.endsWith url "/snapshot/download-v2")
                      (p/rejected
                       (ex-info "route unavailable" {:status 404}))
                      (p/resolved legacy-metadata)))]
                 (#'sync-download/<fetch-snapshot-metadata!
                  "https://sync.example.test" "graph-1"))
               (p/then
                (fn [result]
                  (is (= {:snapshot-resp legacy-metadata
                          :v2? false
                          :fallback-reason :v2-metadata-404}
                         result))
                  (is (= ["https://sync.example.test/sync/graph-1/snapshot/download-v2"
                          "https://sync.example.test/sync/graph-1/snapshot/download"]
                         @urls*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest snapshot-v2-stream-404-cancels-frozen-export-and-falls-back-to-v1-test
  (async done
         (let [fetch-prev js/fetch
               calls* (atom [])
               base "https://sync.example.test"
               graph-id "graph-1"
               download-id "download-1"
               v2-stream-url
               (str base "/sync/" graph-id
                    "/snapshot/stream-v2?download-id=" download-id)
               legacy-stream-url
               (str base "/sync/" graph-id "/snapshot/stream")]
           (set! js/fetch
                 (fn [url opts]
                   (let [method (or (some-> opts (aget "method")) "GET")]
                     (swap! calls* conj [url method])
                     (cond
                       (= url v2-stream-url)
                       (js/Promise.resolve (js/Response. nil #js {:status 404}))

                       (= method "DELETE")
                       (js/Promise.resolve (js/Response. nil #js {:status 200}))

                       (= url legacy-stream-url)
                       (js/Promise.resolve (js/Response. nil #js {:status 200}))

                       :else
                       (js/Promise.reject
                        (js/Error. (str "unexpected fetch " method " " url)))))))
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [url opts schema]
                    (is (= (str base "/sync/" graph-id
                                "/snapshot/download")
                           url))
                    (is (= {:method "GET"} opts))
                    (is (= :sync/snapshot-download schema))
                    (p/resolved {:url legacy-stream-url}))]
                 (#'sync-download/<fetch-snapshot-stream!
                  base
                  graph-id
                  {:snapshot-resp {:url v2-stream-url}
                   :v2? true}))
               (p/then
                (fn [{:keys [snapshot-resp v2? resp]}]
                  (is (false? v2?))
                  (is (= {:url legacy-stream-url} snapshot-resp))
                  (is (= 200 (.-status resp)))
                  (is (= [[v2-stream-url "GET"]
                          [(str base "/sync/" graph-id
                                "/snapshot/download-v2?download-id="
                                download-id)
                           "DELETE"]
                          [legacy-stream-url "GET"]]
                         @calls*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (done)))))))

(deftest snapshot-v2-stream-410-cancels-and-refreshes-metadata-once-test
  (async done
         (let [fetch-prev js/fetch
               calls* (atom [])
               base "https://sync.example.test"
               graph-id "graph-1"
               stale-url (str base "/sync/" graph-id
                              "/snapshot/stream-v2?download-id=stale")
               fresh-url (str base "/sync/" graph-id
                              "/snapshot/stream-v2?download-id=fresh")]
           (set! js/fetch
                 (fn [url opts]
                   (let [method (or (some-> opts (aget "method")) "GET")]
                     (swap! calls* conj [url method])
                     (cond
                       (= method "DELETE")
                       (js/Promise.resolve (js/Response. nil #js {:status 200}))

                       (= url stale-url)
                       (js/Promise.resolve (js/Response. nil #js {:status 410}))

                       (= url fresh-url)
                       (js/Promise.resolve (js/Response. nil #js {:status 200}))

                       :else
                       (js/Promise.reject
                        (js/Error. (str "unexpected fetch " method " " url)))))))
           (-> (p/with-redefs
                 [sync-download/<fetch-snapshot-metadata!
                  (fn [_base _graph-id]
                    (p/resolved {:snapshot-resp {:url fresh-url}
                                 :v2? true}))]
                 (#'sync-download/<fetch-snapshot-stream!
                  base
                  graph-id
                  {:snapshot-resp {:url stale-url}
                   :v2? true}))
               (p/then
                (fn [{:keys [snapshot-resp v2? resp]}]
                  (is (true? v2?))
                  (is (= {:url fresh-url} snapshot-resp))
                  (is (= 200 (.-status resp)))
                  (is (= [[stale-url "GET"]
                          [(str base "/sync/" graph-id
                                "/snapshot/download-v2?download-id=stale")
                           "DELETE"]
                          [fresh-url "GET"]]
                         @calls*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (done)))))))

(deftest import-temporary-pool-name-is-scoped-to-import-id-test
  (let [first-name (#'sync-download/import-temp-pool-name
                    "logseq_db_team" "import-1")
        second-name (#'sync-download/import-temp-pool-name
                     "logseq_db_team" "import-2")]
    (is (not= first-name second-name))
    (is (string/includes? first-name "import-1"))
    (is (string/includes? second-name "import-2"))))

(deftest refreshed-frozen-export-is-canceled-when-download-still-fails-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               fetch-prev js/fetch
               base "https://sync.example.test"
               graph-id "graph-1"
               stale-url (str base "/sync/" graph-id
                              "/snapshot/stream-v2?download-id=stale")
               fresh-url (str base "/sync/" graph-id
                              "/snapshot/stream-v2?download-id=fresh")
               metadata-calls* (atom 0)
               calls* (atom [])]
           (reset! worker-state/*db-sync-config {:http-base base})
           (set! js/fetch
                 (fn [url opts]
                   (let [method (or (some-> opts (aget "method")) "GET")]
                     (swap! calls* conj [url method])
                     (cond
                       (= method "DELETE")
                       (js/Promise.resolve (js/Response. nil #js {:status 200}))

                       (= url stale-url)
                       (js/Promise.resolve (js/Response. nil #js {:status 410}))

                       (= url fresh-url)
                       (js/Promise.reject
                        (js/Error. "fresh snapshot stream network failure"))

                       :else
                       (js/Promise.reject
                        (js/Error. (str "unexpected fetch " method " " url)))))))
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [_url _opts schema]
                    (case schema
                      :sync/pull
                      (p/resolved {:t 7})

                      :sync/snapshot-download
                      (let [metadata-call (swap! metadata-calls* inc)]
                        (p/resolved
                         {:url (if (= 1 metadata-call)
                                 stale-url
                                 fresh-url)
                          :t 7
                          :row-count 0
                          :checksum "0000000000000000"}))

                      (p/rejected
                       (ex-info "unexpected schema" {:schema schema}))))
                  rtc-log-and-state/rtc-log (fn [& _] nil)]
                 (sync-download/download-graph-by-id!
                  "repo" graph-id false))
               (p/then (fn [_]
                         (is false "expected refreshed stream failure")))
               (p/catch
                (fn [error]
                  (is (= "db-sync download failed" (ex-message error)))
                  (is (= :fetch-snapshot-stream (:stage (ex-data error))))
                  (is (= [[stale-url "GET"]
                          [(str base "/sync/" graph-id
                                "/snapshot/download-v2?download-id=stale")
                           "DELETE"]
                          [fresh-url "GET"]
                          [(str base "/sync/" graph-id
                                "/snapshot/download-v2?download-id=fresh")
                           "DELETE"]]
                         @calls*))))
               (p/finally
                (fn []
                  (set! js/fetch fetch-prev)
                  (reset! worker-state/*db-sync-config config-prev)
                  (done)))))))

(deftest encrypted-download-key-failure-cancels-frozen-export-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               fetch-prev js/fetch
               base "https://sync.example.test"
               graph-id "graph-1"
               stream-url (str base "/sync/" graph-id
                               "/snapshot/stream-v2?download-id=download-1")
               calls* (atom [])]
           (reset! worker-state/*db-sync-config {:http-base base})
           (set! js/fetch
                 (fn [url opts]
                   (let [method (or (some-> opts (aget "method")) "GET")]
                     (swap! calls* conj [url method])
                     (if (= method "DELETE")
                       (js/Promise.resolve (js/Response. nil #js {:status 200}))
                       (js/Promise.reject
                        (js/Error. (str "unexpected fetch " method " " url)))))))
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [url _opts schema]
                    (case schema
                      :sync/pull
                      (p/resolved {:t 7})

                      :sync/snapshot-download
                      (do
                        (is (.endsWith url "/snapshot/download-v2"))
                        (p/resolved {:url stream-url
                                     :t 7
                                     :row-count 0
                                     :checksum "0000000000000000"}))

                      (p/rejected
                       (ex-info "unexpected schema" {:schema schema}))))
                  sync-crypt/<fetch-graph-aes-key-for-download
                  (fn [_graph-id]
                    (p/rejected
                     (ex-info "missing graph key" {:type :test/missing-key})))
                  rtc-log-and-state/rtc-log (fn [& _] nil)]
                 (sync-download/download-graph-by-id! "repo" graph-id true))
               (p/then (fn [_]
                         (is false "expected key failure")))
               (p/catch
                (fn [error]
                  (is (= "db-sync download failed" (ex-message error)))
                  (is (= :prepare-e2ee (:stage (ex-data error))))
                  (is (= [[(str base "/sync/" graph-id
                                  "/snapshot/download-v2?download-id=download-1")
                           "DELETE"]]
                         @calls*))))
               (p/finally
                (fn []
                  (set! js/fetch fetch-prev)
                  (reset! worker-state/*db-sync-config config-prev)
                  (done)))))))

(deftest encrypted-download-preflights-e2ee-and-logs-stream-failure-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               fetch-prev js/fetch
               calls (atom [])
               log-events (atom [])
               prepare-opts* (atom nil)]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (set! js/fetch
                 (fn [_url _opts]
                   (swap! calls conj :snapshot-stream)
                   (js/Promise.resolve #js {:ok true})))
           (-> (p/with-redefs [sync-download/fetch-json (fn [_url _opts schema]
                                                          (case schema
                                                            :sync/pull
                                                            (p/resolved {:t 42})

                                                            :sync/snapshot-download
                                                            (p/resolved {:url "https://sync.example.test/snapshot"})

                                                            (p/rejected (ex-info "unexpected schema" {:schema schema}))))
                               sync-crypt/<fetch-graph-aes-key-for-download (fn [_graph-id]
                                                                               (swap! calls conj :e2ee-preflight)
                                                                               (p/resolved :aes-key))
                               sync-download/prepare-import! (fn [_repo _reset? _graph-id _graph-e2ee?
                                                                 & [_total-datoms opts]]
                                                               (reset! prepare-opts* opts)
                                                               (p/resolved {:import-id "import-1"}))
                               sync-download/<stream-snapshot-row-batches! (fn [_resp _batch-size _on-batch]
                                                                             (p/rejected
                                                                              (ex-info "stream interrupted"
                                                                                       {:type :test/stream-interrupted})))
                               rtc-log-and-state/rtc-log (fn [type payload]
                                                           (swap! log-events conj (assoc payload :type type))
                                                           nil)]
                 (sync-download/download-graph-by-id! "repo" "graph-1" true))
               (p/then (fn [_]
                         (is false "expected download failure")))
               (p/catch (fn [error]
                          (is (= "db-sync download failed" (ex-message error)))
                          (is (= :stream-snapshot (:stage (ex-data error))))
                          (is (= [:e2ee-preflight :snapshot-stream] @calls))
                          (is (= :aes-key (:aes-key @prepare-opts*)))
                          (is (true? (:defer-target? @prepare-opts*)))
                          (is (= [:download-progress :download-progress :download-failed]
                                 (mapv :sub-type @log-events)))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest snapshot-finalize-failure-quarantines-existing-open-sync-client-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               client-prev @worker-state/*db-sync-client
               fetch-prev js/fetch
               graph-id "00000000-0000-4000-8000-000000000042"
               ws-state (atom :open)
               sync-ready? (atom true)
               last-sync-error (atom nil)
               online-users (atom [{:user/uuid "old-user"}])
               send-queue (atom (p/resolved :old-send-tail))
               receive-queue (atom (p/resolved :old-receive-tail))
               asset-queue (atom (p/resolved :old-asset-tail))
               inflight (atom ["old-tx"])
               pending-pull-since (atom 41)
               upload-request (atom {:tx-ids ["old-tx"]})
               reconnect (atom {:attempt 3 :timer 123})
               stale-kill-timer (atom nil)
               closed* (atom 0)
               ws #js {:onopen (fn [])
                       :onmessage (fn [])
                       :onerror (fn [])
                       :onclose (fn [])
                       :close (fn [] (swap! closed* inc))}
               client {:repo "repo"
                       :graph-id graph-id
                       :ws ws
                       :connection-generation "old-generation"
                       :send-queue send-queue
                       :receive-queue receive-queue
                       :asset-queue asset-queue
                       :inflight inflight
                       :pending-pull-since pending-pull-since
                       :upload-request upload-request
                       :reconnect reconnect
                       :stale-kill-timer stale-kill-timer
                       :online-users online-users
                       :ws-state ws-state
                       :sync-ready? sync-ready?
                       :last-sync-error last-sync-error}
               broadcasts* (atom [])]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (reset! worker-state/*db-sync-client client)
           (set! js/fetch
                 (fn [_url _opts]
                   (js/Promise.resolve #js {:ok true :status 200})))
           (-> (p/with-redefs
                 [sync-download/fetch-json
                  (fn [_url _opts schema]
                    (case schema
                      :sync/pull
                      (p/resolved {:t 42})

                      :sync/snapshot-download
                      (p/resolved
                       {:url "https://sync.example.test/snapshot"
                        :t 42
                        :row-count 0
                        :checksum "0000000000000000"})

                      (p/rejected
                       (ex-info "unexpected schema" {:schema schema}))))
                  sync-download/prepare-import!
                  (fn [& _]
                    (p/resolved {:import-id "import-42"}))
                  sync-download/<stream-snapshot-row-batches!
                  (fn [& _] (p/resolved {:chunk-count 0}))
                  sync-download/finalize-import!
                  (fn [& _]
                    (p/rejected
                     (ex-info "synthetic saving/finalize failure"
                              {:type :test/finalize-failed})))
                  shared-service/broadcast-to-clients!
                  (fn [& args]
                    (swap! broadcasts* conj args)
                    nil)
                  rtc-log-and-state/rtc-log (fn [& _] nil)]
                 (sync-download/download-graph-by-id!
                  "repo" graph-id false))
               (p/then (fn [_]
                         (is false "expected finalize failure")))
               (p/catch
                (fn [error]
                  (is (= :finalize-import (:stage (ex-data error))))
                  (is (= :repair-required @ws-state))
                  (is (false? @sync-ready?))
                  (is (= :db-sync/snapshot-download-failed
                         (get-in @last-sync-error [:data :type])))
                  (is (= 1 @closed*))
                  (is (nil? (.-onmessage ws)))
                  (is (empty? @inflight))
                  (is (nil? @pending-pull-since))
                  (is (nil? @upload-request))
                  (is (nil? (:timer @reconnect)))
                  (let [quarantined @worker-state/*db-sync-client]
                    (is (nil? (:ws quarantined)))
                    (is (not= "old-generation"
                              (:connection-generation quarantined)))
                    (is (not (identical? send-queue
                                         (:send-queue quarantined))))
                    (is (not (identical? receive-queue
                                         (:receive-queue quarantined))))
                    (is (not (identical? asset-queue
                                         (:asset-queue quarantined)))))
                  (is (seq @broadcasts*))))
               (p/finally
                (fn []
                  (set! js/fetch fetch-prev)
                  (reset! worker-state/*db-sync-config config-prev)
                  (reset! worker-state/*db-sync-client client-prev)
                  (done)))))))

(deftest deferred-import-prepare-does-not-open-or-replace-local-graph-test
  (async done
         (-> (sync-download/prepare-import!
              "repo"
              true
              "graph-1"
              false
              nil
              {:defer-target? true
               :aes-key nil})
             (p/then (fn [{:keys [import-id]}]
                       (let [state @@#'sync-download/*import-state]
                         (is (= import-id (:import-id state)))
                         (is (false? (:target-prepared? state)))
                         (is (nil? (:conn state))))))
             (p/catch (fn [error]
                        (is false (str error))))
             (p/finally (fn []
                          (sync-download/close-import-state-for-repo! "repo")
                          (done))))))

(deftest snapshot-target-invalidates-search-and-vector-after-open-test
  (async done
         (let [calls* (atom [])]
           (->
            (p/with-redefs
              [sync-download/require-thread-api-f!
               (fn [api-key]
                 (case api-key
                   :thread-api/db-sync-reset-target-preserving-backup
                   (fn [_repo]
                     (swap! calls* conj :reset-target)
                     (p/resolved nil))

                   :thread-api/db-sync-recreate-lock
                   (fn [_repo]
                     (swap! calls* conj :recreate-lock)
                     (p/resolved nil))

                   :thread-api/create-or-open-db
                   (fn [_repo _opts]
                     (swap! calls* conj :open-target)
                     (p/resolved nil))

                   :thread-api/db-sync-invalidate-search-db
                   (fn [_repo]
                     (swap! calls* conj :invalidate-indexes)
                     (p/resolved nil))))
               worker-state/get-datascript-conn (fn [_repo] :conn)]
              (#'sync-download/<open-import-target! "repo" true))
            (p/then
             (fn [conn]
               (is (= :conn conn))
               (is (= [:reset-target
                       :recreate-lock
                       :open-target
                       :invalidate-indexes]
                      @calls*))))
            (p/catch (fn [error]
                       (is false (str error))))
               (p/finally done)))))

(deftest complete-import-rehydrates-large-titles-before-logical-checksum-validation-test
  (async done
         (let [calls* (atom [])
               thread-apis-prev @thread-api/*thread-apis]
           (vreset! thread-api/*thread-apis
                    (assoc thread-apis-prev
                           :thread-api/db-sync-rehydrate-large-titles
                           (fn [_repo _graph-id]
                             (swap! calls* conj :rehydrate)
                             (p/resolved nil))))
           (-> (p/with-redefs
                 [worker-state/get-sqlite-conn (fn [& _] nil)
                  rtc-log-and-state/rtc-log (fn [& _] nil)
                  client-op/update-local-tx
                  (fn [_repo _remote-tx]
                    (swap! calls* conj :local-tx))
                  shared-service/broadcast-to-clients!
                  (fn [& _]
                    (swap! calls* conj :broadcast))]
                 (sync-download/complete-datoms-import!
                  "repo"
                  "graph-1"
                  9
                  (fn []
                    (swap! calls* conj :validate-checksum))))
               (p/then
                (fn [_]
                  (is (= [:rehydrate
                          :validate-checksum
                          :local-tx
                          :broadcast]
                         @calls*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally
                (fn []
                  (vreset! thread-api/*thread-apis thread-apis-prev)
                  (done)))))))

(defn- install-finalize-test-state!
  [repo graph-id import-id conn]
  (reset! @#'sync-download/*import-state
          {:aes-key nil
           :conn conn
           :graph-e2ee? false
           :graph-id graph-id
           :import-id import-id
           :imported-datoms 0
           :repo repo
           :reset? true
           :rows-imported? false
           :target-prepared? true}))

(deftest clean-snapshot-finalize-requires-and-accepts-exact-checksum-test
  (async done
         (let [repo "clean-finalize-repo"
               graph-id (str (random-uuid))
               import-id (str (random-uuid))
               conn
               (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "clean snapshot page"}
                   :blocks [{:block/title "clean snapshot block"}]}]})
               _ (ldb/transact!
                  conn
                  [(ldb/kv :logseq.kv/graph-uuid (uuid graph-id))
                   (ldb/kv :logseq.kv/graph-remote? true)
                   (ldb/kv :logseq.kv/graph-rtc-e2ee? false)]
                  {:persist-op? false})
               expected-checksum
               (sync-checksum/recompute-checksum @conn)
               stored* (atom {})]
           (install-finalize-test-state!
            repo graph-id import-id conn)
           (-> (p/with-redefs
                 [sync-download/complete-datoms-import!
                  (fn [_repo _graph-id _remote-tx after-rehydrate-f]
                    (after-rehydrate-f)
                    (p/resolved :finalized))
                  client-op/update-local-checksum
                  (fn [_repo checksum]
                    (swap! stored* assoc :legacy checksum))
                  client-op/update-local-server-checksum
                  (fn [_repo checksum]
                    (swap! stored* assoc :versioned checksum))]
                 (sync-download/finalize-import!
                  repo graph-id 2734 import-id expected-checksum))
               (p/then
                (fn [result]
                  (is (= :finalized result))
                  (is (= expected-checksum (:legacy @stored*)))
                  (is (= (sync-checksum/recompute-server-checksum @conn)
                         (:versioned @stored*))
                      "a successful clean download stores the independently recomputed v2 checksum")))
               (p/catch
                (fn [error]
                  (is false (str "clean finalize unexpectedly failed: " error))))
               (p/finally
                (fn []
                  (sync-download/close-import-state-for-repo! repo)
                  (done)))))))

(deftest clean-snapshot-finalize-never-accepts-mismatch-test
  (async done
         (let [repo "strict-finalize-repo"
               graph-id (str (random-uuid))
               import-id (str (random-uuid))
               conn
               (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "strict snapshot page"}
                   :blocks [{:block/title "strict snapshot block"}]}]})
               _ (ldb/transact!
                  conn
                  [(ldb/kv :logseq.kv/graph-uuid (uuid graph-id))
                   (ldb/kv :logseq.kv/graph-remote? true)
                   (ldb/kv :logseq.kv/graph-rtc-e2ee? false)]
                  {:persist-op? false})
               actual-checksum
               (sync-checksum/recompute-checksum @conn)
               wrong-checksum
               (if (= actual-checksum "0000000000000000")
                 "ffffffffffffffff"
                 "0000000000000000")]
           (install-finalize-test-state!
            repo graph-id import-id conn)
           (-> (p/with-redefs
                 [sync-download/complete-datoms-import!
                  (fn [_repo _graph-id _remote-tx after-rehydrate-f]
                    (try
                      (after-rehydrate-f)
                      (p/resolved :incorrectly-accepted)
                      (catch :default error
                        (p/rejected error))))
                  client-op/update-local-checksum
                  (fn [& _]
                    (throw
                     (ex-info "mismatch must not be persisted"
                              {:type :test/mismatch-persisted})))]
                 (sync-download/finalize-import!
                  repo graph-id 2734 import-id wrong-checksum))
               (p/then
                (fn [_]
                  (is false "checksum mismatch was incorrectly accepted")))
               (p/catch
                (fn [error]
                  (is (= :db-sync/snapshot-checksum-mismatch
                         (:type (ex-data error))))
                  (is (= wrong-checksum
                         (:expected-checksum (ex-data error))))
                  (is (= actual-checksum
                         (:actual-checksum (ex-data error))))))
               (p/finally
                (fn []
                  (sync-download/close-import-state-for-repo! repo)
                  (done)))))))

(deftest corrupt-snapshot-is-rejected-before-local-graph-is-replaced-test
  (async done
         (let [open-target-calls* (atom 0)
               graph-id (str (random-uuid))
               rows-db #js {:exec (fn [_query]
                                    #js [#js {:row_count 1}])}]
           (-> (sync-download/prepare-import!
                "repo"
                true
                graph-id
                false
                nil
                {:defer-target? true
                 :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (swap! @#'sync-download/*import-state
                         assoc
                         :rows-db rows-db
                         :rows-imported? true)
                  (p/with-redefs [sync-download/<open-import-target!
                                  (fn [& _]
                                    (swap! open-target-calls* inc)
                                    (p/rejected
                                     (js/Error. "target must not be opened")))]
                    (sync-download/finalize-import!
                     "repo" graph-id 0 import-id nil 2))))
               (p/then (fn [_]
                         (is false "expected corrupt snapshot rejection")))
               (p/catch
                (fn [error]
                  (is (= :db-sync/snapshot-row-count-mismatch
                         (:type (ex-data error))))
                  (is (zero? @open-target-calls*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (sync-download/close-import-state-for-repo! "repo")
                            (done)))))))

(deftest automatic-repair-preflight-aborts-before-local-target-switch-test
  (async done
         (let [repo "auto-repair-preflight-repo"
               graph-id (str (random-uuid))
               target-calls* (atom 0)]
           (-> (sync-download/prepare-import!
                repo true graph-id false nil
                {:defer-target? true :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (p/with-redefs
                    [sync-download/require-thread-api-f!
                     (fn [_]
                       (swap! target-calls* inc)
                       (throw (js/Error. "target switch must not start")))]
                    (sync-download/finalize-import!
                     repo graph-id 7 import-id nil nil
                     {:activation-preflight-f
                      (fn []
                        (throw
                         (ex-info "new local work"
                                  {:type :db-sync/auto-repair-local-work-detected})))}))))
               (p/then (fn [_]
                         (is false "expected repair preflight rejection")))
               (p/catch
                (fn [error]
                  (is (= :db-sync/auto-repair-local-work-detected
                         (:type (ex-data error))))
                  (is (zero? @target-calls*)
                      "backup/reset/open must not start after a failed preflight")
                  (is (nil? (worker-state/snapshot-activation-promise repo))
                      "the short activation gate must always release")
                  (is (nil? @@#'sync-download/*import-state))))
               (p/finally (fn []
                            (sync-download/close-import-state-for-repo! repo)
                            (done)))))))

(deftest automatic-repair-post-backup-proof-preserves-newer-live-input-test
  (async done
         (let [repo "auto-repair-post-backup-repo"
               graph-id (str (random-uuid))
               backup {:durable? true
                       :has-client-ops? true
                       :source-existed? true}
               calls* (atom [])]
           (-> (sync-download/prepare-import!
                repo true graph-id false nil
                {:defer-target? true :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (p/with-redefs
                    [sync-download/require-thread-api-f!
                     (fn [api-key]
                       (case api-key
                         :thread-api/db-sync-export-local-backup
                         (fn [actual-repo]
                           (swap! calls* conj [:backup actual-repo])
                           (p/resolved backup))

                         :thread-api/db-sync-discard-local-backup
                         (fn [actual-repo actual-backup]
                           (swap! calls* conj
                                  [:discard actual-repo actual-backup])
                           (p/resolved true))

                         (throw
                          (ex-info "destructive target API must not run"
                                   {:api-key api-key}))))]
                    (sync-download/finalize-import!
                     repo graph-id 7 import-id nil nil
                     {:activation-preflight-f
                      (fn []
                        (swap! calls* conj [:preflight]))
                      :post-backup-pre-reset-f
                      (fn []
                        (swap! calls* conj [:post-backup-proof])
                        (throw
                         (ex-info "new local input raced the backup"
                                  {:type :db-sync/auto-repair-local-work-detected})))}))))
               (p/then (fn [_]
                         (is false "expected post-backup proof rejection")))
               (p/catch
                (fn [error]
                  (is (= :db-sync/auto-repair-local-work-detected
                         (:type (ex-data error))))
                  (is (= [[:preflight]
                          [:backup repo]
                          [:post-backup-proof]
                          [:discard repo backup]]
                         @calls*)
                      "the stale backup is deleted without touching or restoring the live graph")
                  (is (nil? (worker-state/snapshot-activation-promise repo)))
                  (is (nil? @@#'sync-download/*import-state))))
               (p/finally (fn []
                            (sync-download/close-import-state-for-repo! repo)
                            (done)))))))

(deftest replay-failure-restores-local-backup-test
  (async done
         (let [restores* (atom [])
               graph-id (str (random-uuid))
               backup {:db-binary :original-db
                       :client-ops-binary :original-client-ops}]
           (-> (sync-download/prepare-import!
                "repo"
                true
                graph-id
                false
                nil
                {:defer-target? true
                 :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (swap! @#'sync-download/*import-state
                         assoc
                         :conn :replacement-conn
                         :local-backup backup
                         :rows-imported? true
                         :target-prepared? true)
                  (p/with-redefs
                    [sync-download/<replay-imported-rows!
                     (fn [_state]
                       (p/rejected
                        (ex-info "replay failed" {:type :test/replay-failed})))
                     sync-download/require-thread-api-f!
                     (fn [api-key]
                       (is (= :thread-api/db-sync-restore-local-backup
                              api-key))
                       (fn [repo backup*]
                         (swap! restores* conj [repo backup*])
                         (p/resolved true)))]
                    (sync-download/finalize-import!
                     "repo" graph-id 0 import-id))))
               (p/then (fn [_]
                         (is false "expected replay failure")))
               (p/catch
                (fn [error]
                  (is (= :test/replay-failed (:type (ex-data error))))
                  (is (= [["repo" backup]] @restores*))))
               (p/finally (fn []
                            (sync-download/close-import-state-for-repo! "repo")
                            (done)))))))

(deftest repair-receipt-failure-restores-backup-before-commit-test
  (async done
         (let [repo "repair-receipt-rollback-repo"
               graph-id (str (random-uuid))
               backup {:durable? true
                       :has-client-ops? true
                       :source-existed? true}
               restored* (atom [])
               committed* (atom 0)]
           (-> (sync-download/prepare-import!
                repo true graph-id false nil
                {:defer-target? true :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (swap! @#'sync-download/*import-state
                         assoc
                         :conn :replacement-conn
                         :local-backup backup
                         :rows-imported? false
                         :target-prepared? true)
                  (p/with-redefs
                    [sync-download/complete-datoms-import!
                     (fn [& _] (p/resolved :replacement-complete))
                     sync-download/require-thread-api-f!
                     (fn [api-key]
                       (case api-key
                         :thread-api/db-sync-restore-local-backup
                         (fn [actual-repo actual-backup]
                           (swap! restored* conj [actual-repo actual-backup])
                           (p/resolved true))

                         :thread-api/db-sync-commit-local-backup
                         (fn [& _]
                           (swap! committed* inc)
                           (p/resolved true))

                         (throw (ex-info "unexpected API" {:api-key api-key}))))]
                    (sync-download/finalize-import!
                     repo graph-id 7 import-id nil nil
                     {:post-activation-precommit-f
                      (fn []
                        (throw
                         (ex-info "receipt persistence failed"
                                  {:type :test/receipt-persist-failed})))}))))
               (p/then (fn [_]
                         (is false "expected receipt persistence failure")))
               (p/catch
                (fn [error]
                  (is (= :test/receipt-persist-failed
                         (:type (ex-data error))))
                  (is (= [[repo backup]] @restored*))
                  (is (zero? @committed*)
                      "the old graph backup remains recoverable until the circuit receipt is durable")))
               (p/finally
                (fn []
                  (sync-download/close-import-state-for-repo! repo)
                  (done)))))))

(deftest replay-failure-without-backup-discards-partial-target-test
  (async done
         (let [discarded* (atom [])
               graph-id (str (random-uuid))]
           (-> (sync-download/prepare-import!
                "fresh-repo"
                true
                graph-id
                false
                nil
                {:defer-target? true
                 :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (swap! @#'sync-download/*import-state
                         assoc
                         :conn :partial-replacement-conn
                         :local-backup {:source-existed? false}
                         :rows-imported? true
                         :target-prepared? true)
                  (p/with-redefs
                    [sync-download/<replay-imported-rows!
                     (fn [_state]
                       (p/rejected
                        (ex-info "replay failed" {:type :test/replay-failed})))
                     sync-download/require-thread-api-f!
                     (fn [api-key]
                       (is (= :thread-api/db-sync-discard-failed-target
                              api-key))
                       (fn [repo]
                         (swap! discarded* conj repo)
                         (p/resolved nil)))]
                    (sync-download/finalize-import!
                     "fresh-repo" graph-id 0 import-id))))
               (p/then (fn [_]
                         (is false "expected replay failure")))
               (p/catch
                (fn [error]
                  (is (= :test/replay-failed (:type (ex-data error))))
                  (is (= ["fresh-repo"] @discarded*))
                  (is (nil? @@#'sync-download/*import-state))))
               (p/finally
                (fn []
                  (sync-download/close-import-state-for-repo! "fresh-repo")
                  (done)))))))

(deftest replay-failure-with-unknown-backup-state-never-deletes-target-test
  (async done
         (let [cleanup-calls* (atom 0)
               graph-id (str (random-uuid))]
           (-> (sync-download/prepare-import!
                "unknown-backup-repo"
                true
                graph-id
                false
                nil
                {:defer-target? true
                 :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (swap! @#'sync-download/*import-state
                         assoc
                         :conn :partial-replacement-conn
                         :local-backup nil
                         :rows-imported? true
                         :target-prepared? true)
                  (p/with-redefs
                    [sync-download/<replay-imported-rows!
                     (fn [_state]
                       (p/rejected
                        (ex-info "replay failed" {:type :test/replay-failed})))
                     sync-download/require-thread-api-f!
                     (fn [_api-key]
                       (swap! cleanup-calls* inc)
                       (throw (js/Error. "unknown state must fail closed")))]
                    (sync-download/finalize-import!
                     "unknown-backup-repo" graph-id 0 import-id))))
               (p/then (fn [_]
                         (is false "expected replay failure")))
               (p/catch
                (fn [error]
                  (is (= :test/replay-failed (:type (ex-data error))))
                  (is (zero? @cleanup-calls*))
                  (is (nil? @@#'sync-download/*import-state))))
               (p/finally
                (fn []
                  (sync-download/close-import-state-for-repo!
                   "unknown-backup-repo")
                  (done)))))))

(deftest successful-snapshot-activation-commits-durable-backup-marker-test
  (async done
         (let [commits* (atom [])
               graph-id (str (random-uuid))
               backup {:durable? true
                       :has-client-ops? true}]
           (-> (sync-download/prepare-import!
                "repo"
                true
                graph-id
                false
                nil
                {:defer-target? true
                 :aes-key nil})
               (p/then
                (fn [{:keys [import-id]}]
                  (swap! @#'sync-download/*import-state
                         assoc
                         :conn :replacement-conn
                         :local-backup backup
                         :rows-imported? false
                         :target-prepared? true)
                  (p/with-redefs
                    [sync-download/complete-datoms-import!
                     (fn [_repo _graph-id _remote-tx & _]
                       (p/resolved :activated))
                     sync-download/require-thread-api-f!
                     (fn [api-key]
                       (is (= :thread-api/db-sync-commit-local-backup
                              api-key))
                       (fn [repo backup*]
                         (swap! commits* conj [repo backup*])
                         (p/resolved nil)))]
                    (p/let [result
                            (sync-download/finalize-import!
                             "repo" graph-id 7 import-id)]
                      (is (= :activated result))))))
               (p/then
                (fn [_]
                  (is (= [["repo" backup]] @commits*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (sync-download/close-import-state-for-repo!
                             "repo")
                            (done)))))))
