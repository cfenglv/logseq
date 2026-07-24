(ns frontend.worker.sync.assets-test
  (:require [cljs.test :refer [async deftest is]]
            [datascript.core :as d]
            [frontend.common.crypt :as crypt]
            [frontend.worker.platform :as platform]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.assets :as sync-assets]
            [frontend.worker.sync.auth :as sync-auth]
            [frontend.worker.sync.util :as sync-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.schema :as db-schema]
            [promesa.core :as p]))

(defn- asset-conn
  [asset-uuid]
  (let [conn (d/create-conn db-schema/schema)]
    (ldb/transact! conn [{:block/uuid asset-uuid
                          :logseq.property.asset/type "png"
                          :logseq.property.asset/checksum "sha-256-value"
                          :logseq.property.asset/remote-metadata {:checksum "sha-256-value"
                                                                  :type "png"}}])
    conn))

(defn- execute-enqueued-asset-task!
  [task]
  (if (fn? task)
    (task)
    (p/resolved nil)))

(deftest asset-string-payload-size-uses-utf8-bytes-test
  (is (= 4 (#'sync-assets/payload-size "a中"))))

(deftest asset-upload-retries-without-size-header-for-legacy-server-test
  (async done
         (let [original-fetch js/fetch
               calls* (atom [])]
           (set! js/fetch
                 (fn [_url opts]
                   (swap! calls* conj (.-headers opts))
                   (if (= 1 (count @calls*))
                     (p/rejected (js/TypeError. "CORS preflight rejected"))
                     (p/resolved #js {:ok true :status 200}))))
           (-> (#'sync-assets/<put-remote-asset!
                "https://sync.example.test/assets/graph/asset.png"
                {"authorization" "Bearer token"
                 "x-logseq-asset-size" "3"}
                (js/Uint8Array. #js [1 2 3]))
               (p/then (fn [response]
                         (is (.-ok response))
                         (is (= 2 (count @calls*)))
                         (is (= "3" (aget (first @calls*) "x-logseq-asset-size")))
                         (is (nil? (aget (second @calls*) "x-logseq-asset-size")))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (set! js/fetch original-fetch)
                            (done)))))))

(deftest request-asset-download-skips-existing-local-asset-test
  (async done
         (let [repo "asset-download-repo"
               graph-id "graph-1"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               download-calls (atom [])
               asset-stat-calls (atom [])
               enqueued-task (atom nil)
               broadcast-calls (atom [])]
           (-> (p/with-redefs [worker-state/get-datascript-conn (fn [_repo]
                                                                  conn)
                               platform/current (fn [] {})
                               platform/asset-stat (fn [_platform repo' file-name]
                                                     (swap! asset-stat-calls conj [repo' file-name])
                                                     (p/resolved {:size 10}))
                               sync-assets/download-remote-asset! (fn [& args]
                                                                    (swap! download-calls conj args)
                                                                    (p/resolved nil))]
                 (sync-assets/request-asset-download!
                  repo
                  asset-uuid
                  {:current-client-f (fn [_repo]
                                       {:graph-id graph-id})
                   :enqueue-asset-task-f (fn [_client task]
                                           (reset! enqueued-task task)
                                           (execute-enqueued-asset-task! task))
                   :broadcast-rtc-state!-f (fn [& args]
                                             (swap! broadcast-calls conj args))}))
               (p/then (fn [_]
                         (is (= [[repo (str asset-uuid ".png")]] @asset-stat-calls))
                         (is (fn? @enqueued-task))
                         (is (= [] @download-calls))
                         (is (= [] @broadcast-calls))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally done)))))

(deftest request-asset-download-downloads-missing-local-asset-test
  (async done
         (let [repo "asset-download-repo"
               graph-id "graph-1"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               download-calls (atom [])
               asset-stat-calls (atom [])
               broadcast-calls (atom [])]
           (-> (p/with-redefs [worker-state/get-datascript-conn (fn [_repo]
                                                                  conn)
                               platform/current (fn [] {})
                               platform/asset-stat (fn [_platform repo' file-name]
                                                     (swap! asset-stat-calls conj [repo' file-name])
                                                     (p/resolved nil))
                               sync-assets/download-remote-asset! (fn [& args]
                                                                    (swap! download-calls conj args)
                                                                    (p/resolved nil))]
                 (sync-assets/request-asset-download!
                  repo
                  asset-uuid
                  {:current-client-f (fn [_repo]
                                       {:graph-id graph-id})
                   :enqueue-asset-task-f (fn [_client task]
                                           (execute-enqueued-asset-task! task))
                   :broadcast-rtc-state!-f (fn [& args]
                                             (swap! broadcast-calls conj args))}))
               (p/then (fn [_]
                         (is (= [[repo (str asset-uuid ".png")]] @asset-stat-calls))
                         (is (= [[repo graph-id (str asset-uuid) "png"]]
                                @download-calls))
                         (is (= 1 (count @broadcast-calls)))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally done)))))

(deftest request-asset-download-before-client-connects-test
  (async done
         (let [repo "asset-download-before-client-repo"
               graph-id "graph-before-client"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               download-calls (atom [])
               enqueue-calls (atom [])
               broadcast-calls (atom [])]
           (-> (p/with-redefs [worker-state/get-datascript-conn (fn [_repo]
                                                                  conn)
                               sync-util/get-graph-id (fn [_repo]
                                                        graph-id)
                               platform/current (fn [] {})
                               platform/asset-stat (fn [_platform _repo _file-name]
                                                     (p/resolved nil))
                               sync-assets/download-remote-asset! (fn [& args]
                                                                    (swap! download-calls conj args)
                                                                    (p/resolved nil))]
                 (sync-assets/request-asset-download!
                  repo
                  asset-uuid
                  {:current-client-f (fn [_repo] nil)
                   :enqueue-asset-task-f (fn [& args]
                                           (swap! enqueue-calls conj args))
                   :broadcast-rtc-state!-f (fn [& args]
                                             (swap! broadcast-calls conj args))}))
               (p/then (fn [requested-download?]
                         (is (true? requested-download?))
                         (is (= [[repo graph-id (str asset-uuid) "png"]]
                                @download-calls))
                         (is (= [] @enqueue-calls))
                         (is (= [] @broadcast-calls))
                         (is (= {:checksum "sha-256-value"
                                 :type "png"}
                                (:logseq.property.asset/remote-metadata
                                 (d/entity @conn [:block/uuid asset-uuid]))))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally done)))))

(deftest shared-asset-queue-recovers-after-rejected-task-test
  (async done
         (let [client {:asset-queue (atom (p/resolved nil))}
               ran (atom [])
               first-task
               (sync-assets/enqueue-asset-task!
                client
                (fn []
                  (swap! ran conj :first)
                  (p/rejected (js/Error. "temporary failure"))))
               second-task
               (sync-assets/enqueue-asset-task!
                client
                (fn []
                  (swap! ran conj :second)
                  (p/resolved :ok)))]
           (->
            (p/let [_ (p/catch first-task (fn [_] nil))
                    result second-task]
              (is (= :ok result))
              (is (= [:first :second] @ran)))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally done)))))

(deftest preconnect-asset-download-deduplicates-concurrent-request-test
  (async done
         (let [repo "asset-download-preconnect-dedupe-repo"
               graph-id "graph-preconnect-dedupe"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               active (atom 0)
               max-active (atom 0)
               calls (atom 0)
               request
               (fn []
                 (sync-assets/request-asset-download!
                  repo
                  asset-uuid
                  {:current-client-f (fn [_] nil)
                   :enqueue-asset-task-f
                   (fn [& _]
                     (p/rejected
                      (js/Error. "client queue must not be used")))
                   :broadcast-rtc-state!-f (fn [& _] nil)}))]
           (->
            (p/with-redefs
              [worker-state/get-datascript-conn (fn [_] conn)
               sync-util/get-graph-id (fn [_] graph-id)
               platform/current (fn [] {})
               platform/asset-stat (fn [& _] (p/resolved nil))
               sync-assets/download-remote-asset!
               (fn [& _]
                 (swap! calls inc)
                 (let [n (swap! active inc)]
                   (swap! max-active max n))
                 (p/let [_ (p/delay 20)]
                   (swap! active dec)
                   nil))]
              (p/all [(request) (request) (request)]))
            (p/then
             (fn [results]
               (is (= [true true true] results))
               (is (= 1 @calls))
               (is (= 1 @max-active))))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally done)))))

(deftest cancel-prevents-queued-download-and-allows-fresh-generation-test
  (async done
         (let [repo "asset-download-cancel-generation-repo"
               graph-id "graph-cancel-generation"
               first-uuid (random-uuid)
               second-uuid (random-uuid)
               conn (asset-conn first-uuid)
               _ (ldb/transact!
                  conn
                  [{:block/uuid second-uuid
                    :logseq.property.asset/type "png"
                    :logseq.property.asset/checksum "sha-256-value"
                    :logseq.property.asset/remote-metadata
                    {:checksum "sha-256-value" :type "png"}}])
               client {:graph-id graph-id
                       :asset-queue (atom (p/resolved nil))}
               calls (atom [])
               release-first! (atom nil)
               first-download
               (js/Promise.
                (fn [resolve _reject]
                  (reset! release-first! resolve)))
               request
               (fn [asset-uuid]
                 (sync-assets/request-asset-download!
                  repo
                  asset-uuid
                  {:current-client-f (constantly client)
                   :enqueue-asset-task-f sync-assets/enqueue-asset-task!
                   :broadcast-rtc-state!-f (fn [& _] nil)}))]
           (->
            (p/with-redefs
              [worker-state/get-datascript-conn (constantly conn)
               platform/current (constantly {})
               platform/asset-stat (fn [& _] (p/resolved nil))
               sync-assets/download-remote-asset!
               (fn [_repo _graph-id asset-uuid _asset-type]
                 (swap! calls conj (str asset-uuid))
                 (if (= 1 (count @calls))
                   first-download
                   (p/resolved nil)))]
              (let [first-request (request first-uuid)
                    second-request (request second-uuid)]
                (p/let [_ (p/delay 10)
                        _ (is (= [(str first-uuid)] @calls))
                        _ (sync-assets/cancel-remote-asset-downloads! repo)
                        _ (@release-first! nil)
                        _ first-request
                        second-result second-request
                        _ (is (false? second-result)
                              "a queued task from the cancelled generation must not start")
                        _ (is (= [(str first-uuid)] @calls))
                        fresh-result (request second-uuid)]
                  (is (true? fresh-result))
                  (is (= [(str first-uuid) (str second-uuid)] @calls)))))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally done)))))

(deftest cancel-during-asset-stat-prevents-late-download-test
  (async done
         (let [repo "asset-download-cancel-during-stat-repo"
               graph-id "graph-cancel-during-stat"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               resolve-stat* (atom nil)
               stat-result
               (js/Promise.
                (fn [resolve _reject]
                  (reset! resolve-stat* resolve)))
               download-calls (atom [])
               client {:graph-id graph-id
                       :asset-queue (atom (p/resolved nil))}]
           (->
            (p/with-redefs
              [worker-state/get-datascript-conn (constantly conn)
               platform/current (constantly {})
               platform/asset-stat (fn [& _] stat-result)
               sync-assets/download-remote-asset!
               (fn [& args]
                 (swap! download-calls conj args)
                 (p/resolved nil))]
              (let [request
                    (sync-assets/request-asset-download!
                     repo
                     asset-uuid
                     {:current-client-f (constantly client)
                      :enqueue-asset-task-f
                      sync-assets/enqueue-asset-task!
                      :broadcast-rtc-state!-f (fn [& _] nil)})]
                (p/let [_ (p/delay 10)
                        _ (sync-assets/cancel-remote-asset-downloads! repo)
                        _ (@resolve-stat* nil)
                        downloaded? request]
                  (is (false? downloaded?))
                  (is (= [] @download-calls)
                      "cancellation during stat must prevent a late fetch"))))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally done)))))

(deftest completed-download-broadcasts-current-reconnected-client-test
  (async done
         (let [repo "asset-download-reconnect-client-repo"
               graph-id "graph-reconnect-client"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               old-client {:id :old
                           :graph-id graph-id
                           :asset-queue (atom (p/resolved nil))}
               new-client {:id :new
                           :graph-id graph-id
                           :asset-queue (atom (p/resolved nil))}
               current-client* (atom old-client)
               resolve-download* (atom nil)
               download-result
               (js/Promise.
                (fn [resolve _reject]
                  (reset! resolve-download* resolve)))
               broadcasts (atom [])]
           (->
            (p/with-redefs
              [worker-state/get-datascript-conn (constantly conn)
               platform/current (constantly {})
               platform/asset-stat (fn [& _] (p/resolved nil))
               sync-assets/download-remote-asset!
               (fn [& _] download-result)]
              (let [request
                    (sync-assets/request-asset-download!
                     repo
                     asset-uuid
                     {:current-client-f (fn [_] @current-client*)
                      :enqueue-asset-task-f
                      sync-assets/enqueue-asset-task!
                      :broadcast-rtc-state!-f
                      (fn [client]
                        (swap! broadcasts conj client))})]
                (p/let [_ (p/delay 10)
                        _ (reset! current-client* new-client)
                        _ (@resolve-download* nil)
                        downloaded? request]
                  (is (true? downloaded?))
                  (is (= [new-client] @broadcasts)
                      "a stale queue must not rebroadcast the closed client"))))
            (p/catch (fn [error]
                       (is false (str "unexpected error: " error))))
            (p/finally done)))))

(deftest download-remote-asset-clears-progress-after-body-failure-test
  (async done
         (let [repo "asset-download-progress-failure-repo"
               graph-id "graph-progress-failure"
               asset-uuid (random-uuid)
               original-fetch js/fetch
               db-sync-config @worker-state/*db-sync-config
               progress (atom [])]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (set! js/fetch
                 (fn [& _]
                   (p/resolved
                    #js {:ok true
                         :status 200
                         :headers #js {:get (fn [_] "10")}
                         :arrayBuffer
                         (fn []
                           (p/rejected
                            (js/Error. "response body interrupted")))})))
           (->
            (p/with-redefs
              [sync-assets/graph-aes-key (fn [& _] (p/resolved nil))
               shared-service/broadcast-to-clients!
               (fn [event payload]
                 (when (= :rtc-asset-upload-download-progress event)
                   (swap! progress conj payload)))]
              (sync-assets/download-remote-asset!
               repo graph-id asset-uuid "png"))
            (p/then (fn [_]
                      (is false "expected interrupted body to reject")))
            (p/catch
             (fn [error]
               (is (= :rtc.exception/download-asset-failed
                      (:type (ex-data error))))
               (is (= {:direction :download :loaded 0 :total 0}
                      (get-in (last @progress) [:progress])))))
            (p/finally
              (fn []
                (set! js/fetch original-fetch)
                (reset! worker-state/*db-sync-config db-sync-config)
                (done)))))))

(deftest download-remote-asset-aborts-after-timeout-test
  (async done
         (let [repo "asset-download-timeout-repo"
               graph-id "graph-timeout"
               asset-uuid (random-uuid)
               original-fetch js/fetch
               db-sync-config @worker-state/*db-sync-config
               aborted? (atom false)]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (set! js/fetch
                 (fn [_url opts]
                   (js/Promise.
                    (fn [_resolve reject]
                      (.addEventListener
                       (.-signal opts)
                       "abort"
                       (fn []
                         (reset! aborted? true)
                         (reject (js/Error. "aborted"))))))))
           (->
            (p/with-redefs
              [sync-auth/http-base-url
               (fn [_] "https://sync.example.test")
               sync-assets/remote-asset-download-timeout-ms 10
               sync-assets/graph-aes-key (fn [& _] (p/resolved nil))
               shared-service/broadcast-to-clients! (fn [& _] nil)]
              (sync-assets/download-remote-asset!
               repo graph-id asset-uuid "png"))
            (p/then (fn [_]
                      (is false "expected timeout to reject")))
            (p/catch
             (fn [error]
               (is @aborted?
                   (str "expected abort signal; actual error "
                        (pr-str error)))
               (is (= :rtc.exception/download-asset-failed
                      (:type (ex-data error)))
                   (str "unexpected timeout error " (pr-str error)))))
            (p/finally
              (fn []
                (set! js/fetch original-fetch)
                (reset! worker-state/*db-sync-config db-sync-config)
                (done)))))))

(deftest request-asset-download-propagates-and-logs-download-failure-test
  (async done
         (let [repo "asset-download-repo"
               graph-id "graph-1"
               asset-uuid (random-uuid)
               conn (asset-conn asset-uuid)
               download-error (ex-info "download failed" {:type :rtc.exception/download-asset-failed})
               log-calls (atom [])]
           (-> (p/with-redefs [worker-state/get-datascript-conn (fn [_repo]
                                                                  conn)
                               platform/current (fn [] {})
                               platform/asset-stat (fn [_platform _repo _file-name]
                                                     (p/resolved nil))
                               sync-assets/download-remote-asset! (fn [& _args]
                                                                    (p/rejected download-error))
                               sync-assets/log-request-asset-download-failed!
                               (fn [repo' asset-uuid' error']
                                 (swap! log-calls conj [repo' asset-uuid' error']))]
                 (sync-assets/request-asset-download!
                  repo
                  asset-uuid
                  {:current-client-f (fn [_repo]
                                       {:graph-id graph-id})
                   :enqueue-asset-task-f (fn [_client task]
                                           (execute-enqueued-asset-task! task))
                   :broadcast-rtc-state!-f (fn [& _args] nil)}))
               (p/then (fn [_]
                         (is false "expected download failure to reject")))
               (p/catch (fn [error]
                          (is (= download-error error))
                          (is (= [[repo asset-uuid download-error]] @log-calls))))
               (p/finally done)))))

(deftest upload-remote-asset-serializes-resolved-encrypted-payload-test
  (async done
         (let [repo "asset-upload-repo"
               graph-id "graph-1"
               asset-uuid (random-uuid)
               checksum "sha-256-value"
               asset-bytes (js/Uint8Array. #js [1 2 3])
               encrypted-payload {:cipher "encrypted-payload"}
               expected-body (ldb/write-transit-str encrypted-payload)
               fetch-call* (atom nil)
               encrypt-input* (atom nil)
               original-fetch js/fetch
               db-sync-config @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (set! js/fetch
                 (fn [url opts]
                   (reset! fetch-call*
                           {:url url
                            :body (.-body opts)
                            :headers (js->clj (.-headers opts) :keywordize-keys true)})
                   (p/resolved #js {:ok true
                                     :status 200})))
           (-> (p/with-redefs [sync-assets/graph-aes-key
                               (fn [_repo _graph-id _fail-fast-f]
                                 (p/resolved "aes-key"))
                               platform/current
                               (fn [] {})
                               platform/asset-read-bytes!
                               (fn [_platform _repo _file-name]
                                 (p/resolved asset-bytes))
                               crypt/<encrypt-uint8array
                               (fn [_aes-key payload]
                                 (reset! encrypt-input* payload)
                                 (p/resolved encrypted-payload))
                               shared-service/broadcast-to-clients!
                               (fn [& _] nil)]
                 (sync-assets/upload-remote-asset!
                  repo graph-id asset-uuid "png" checksum))
               (p/then
                (fn [_]
                  (is (instance? js/Uint8Array @encrypt-input*))
                  (is (= expected-body (:body @fetch-call*)))
                  (is (= (str (count expected-body))
                         (get-in @fetch-call* [:headers :x-logseq-asset-size])))))
               (p/catch
                (fn [error]
                  (is false (str "unexpected error: " error))))
               (p/finally
                 (fn []
                   (set! js/fetch original-fetch)
                   (reset! worker-state/*db-sync-config db-sync-config)
                   (done)))))))

(deftest upload-remote-asset-records-missing-local-file-test
  (async done
         (let [repo "asset-upload-repo"
               graph-id "graph-1"
               asset-uuid (random-uuid)
               checksum "sha-256-value"
               missing-file (str "assets/" asset-uuid ".pdf")
               read-error (js/Error. "ENOENT: no such file or directory")
               original-fetch js/fetch
               db-sync-config @worker-state/*db-sync-config
               fetch-called? (atom false)
               broadcasts (atom [])]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (set! js/fetch
                 (fn [& _args]
                   (reset! fetch-called? true)
                   (p/resolved #js {:ok true
                                     :status 200})))
           (-> (p/with-redefs [sync-assets/graph-aes-key
                               (fn [_repo _graph-id _fail-fast-f]
                                 (p/resolved nil))
                               platform/current
                               (fn [] {})
                               platform/asset-read-bytes!
                               (fn [_platform _repo _file-name]
                                 (p/rejected read-error))
                               shared-service/broadcast-to-clients!
                               (fn [event payload]
                                 (swap! broadcasts conj [event payload]))]
                 (sync-assets/clear-missing-asset-upload-files! repo)
                 (sync-assets/upload-remote-asset!
                  repo graph-id asset-uuid "pdf" checksum))
               (p/then
                (fn [_]
                  (is false "expected missing local file to reject")))
               (p/catch
                (fn [error]
                  (is (= :rtc.exception/read-asset-failed (:type (ex-data error))))
                  (is (false? @fetch-called?))
                  (is (= [] @broadcasts))
                  (is (= [{:asset-id (str asset-uuid)
                           :asset-type "pdf"
                           :file missing-file}]
                         (sync-assets/get-missing-asset-upload-files repo)))))
               (p/finally
                 (fn []
                   (set! js/fetch original-fetch)
                   (reset! worker-state/*db-sync-config db-sync-config)
                   (sync-assets/clear-missing-asset-upload-files! repo)
                   (done)))))))

(deftest download-missing-remote-assets-downloads-only-missing-sync-assets-test
  (async done
         (let [repo "asset-prefetch-repo"
               graph-id "graph-1"
               missing-uuid (random-uuid)
               existing-uuid (random-uuid)
               local-uuid (random-uuid)
               external-uuid (random-uuid)
               conn (d/create-conn db-schema/schema)
               stat-calls (atom [])
               download-calls (atom [])]
           (ldb/transact!
            conn
            [{:db/ident :logseq.class/Asset}
             {:block/uuid missing-uuid
              :block/tags #{:logseq.class/Asset}
              :logseq.property.asset/type "png"
              :logseq.property.asset/checksum "missing-checksum"
              :logseq.property.asset/remote-metadata {:checksum "missing-checksum"
                                                      :type "png"}}
             {:block/uuid existing-uuid
              :block/tags #{:logseq.class/Asset}
              :logseq.property.asset/type "pdf"
              :logseq.property.asset/checksum "existing-checksum"
              :logseq.property.asset/remote-metadata {:checksum "existing-checksum"
                                                      :type "pdf"}}
             {:block/uuid local-uuid
              :block/tags #{:logseq.class/Asset}
              :logseq.property.asset/type "jpg"
              :logseq.property.asset/checksum "local-checksum"}
             {:block/uuid external-uuid
              :block/tags #{:logseq.class/Asset}
              :logseq.property.asset/type "gif"
              :logseq.property.asset/checksum "external-checksum"
              :logseq.property.asset/remote-metadata {:checksum "external-checksum"
                                                      :type "gif"}
              :logseq.property.asset/external-url "https://example.com/asset.gif"}])
           (-> (p/with-redefs [worker-state/get-datascript-conn (fn [_repo]
                                                                  conn)
                               platform/current (fn [] {})
                               platform/asset-stat
                               (fn [_platform repo' file-name]
                                 (swap! stat-calls conj [repo' file-name])
                                 (p/resolved (when (= file-name (str existing-uuid ".pdf"))
                                               {:size 10})))
                               sync-assets/download-remote-asset!
                               (fn [& args]
                                 (swap! download-calls conj args)
                                 (p/resolved nil))]
                 (sync-assets/download-missing-remote-assets! repo graph-id))
               (p/then (fn [result]
                         (is (= {:total 2
                                 :downloaded 1
                                 :skipped-existing 1}
                                result))
                         (is (= #{[repo (str existing-uuid ".pdf")]
                                  [repo (str missing-uuid ".png")]}
                                (set @stat-calls)))
                         (is (= [[repo graph-id missing-uuid "png"]]
                                @download-calls))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally done)))))

(deftest download-remote-assets-if-missing-bounds-download-concurrency-test
  (async done
         (let [repo "asset-prefetch-repo"
               graph-id "graph-1"
               candidates (mapv (fn [_]
                                   {:asset-uuid (random-uuid)
                                    :asset-type "png"})
                                 (range 12))
               active-downloads (atom 0)
               max-active-downloads (atom 0)
               download-calls (atom [])]
           (-> (p/with-redefs [platform/current (fn [] {})
                               platform/asset-stat
                               (fn [_platform _repo _file-name]
                                 (p/resolved nil))
                               sync-assets/download-remote-asset!
                               (fn [repo' graph-id' asset-uuid asset-type]
                                 (swap! download-calls conj [repo' graph-id' asset-uuid asset-type])
                                 (let [active (swap! active-downloads inc)]
                                   (swap! max-active-downloads max active))
                                 (p/let [_ (p/delay 20)]
                                   (swap! active-downloads dec)
                                   nil))]
                 (sync-assets/download-remote-assets-if-missing!
                  repo graph-id candidates))
               (p/then (fn [result]
                         (is (= {:total 12
                                 :downloaded 12
                                 :skipped-existing 0}
                                result))
                         (is (= 12 (count @download-calls)))
                         (is (= 2 @max-active-downloads))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally done)))))
