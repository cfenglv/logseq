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
            [logseq.db-sync.snapshot :as snapshot]
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
                          :v2? false}
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
                          (is (= [:download-progress :download-progress :download-completed]
                                 (mapv :sub-type @log-events)))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (reset! worker-state/*db-sync-config config-prev)
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
