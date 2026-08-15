(ns frontend.worker.sync.download-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.apply-txs :as sync-apply]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.crypt :as sync-crypt]
            [frontend.worker.sync.download :as sync-download]
            [frontend.worker.sync.log-and-state :as rtc-log-and-state]
            [frontend.worker.sync.temp-sqlite :as sync-temp-sqlite]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.snapshot :as snapshot]
            [frontend.worker.sync.transport :as sync-transport]
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

(deftest checksum-mismatch-suspect-claims-from-bounded-proof-test
  (async done
         (let [repo "repair-suspect-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               conn (atom :db)
               observation {:remote-t 9
                            :journal-high-water 14
                            :pending-count 0
                            :legacy-anchor "local-cached"}
               diagnostics {:checksum "remote-full"
                            :t 9
                            :legacy-anchor "remote-full"
                            :server-recomputed-checksum "remote-full"
                            :server-checkpoint-identity "checkpoint-9"
                            :metadata-proof "proof-9"}
               fetch-count (atom 0)
               fetch-urls (atom [])
               claims (atom [])
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] conn)
                  client-op/read-repair-local-observation
                  (fn [_] observation)
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json
                  (fn [url _opts schema]
                    (is (= :sync/checksum-diagnostics schema))
                    (swap! fetch-count inc)
                    (swap! fetch-urls conj url)
                    (p/resolved diagnostics))
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [claim-repo graph-id basis]
                           (let [result {:claimed? true
                                         :operation {:operation-id
                                                     #uuid "90000000-0000-4000-8000-000000000001"}}]
                             (swap! claims conj [claim-repo graph-id basis])
                             result))})]
                 (sync-download/<claim-repair-after-checksum-mismatch!
                  repo client 9 "remote-full"))
               (p/then
                (fn [result]
                  (is (= 1 @fetch-count))
                  (is (= ["https://sync.example.test/sync/graph-1/checksum/diagnostics?proof-only=true"]
                         @fetch-urls))
                  (is (= 1 (count @claims)))
                  (is (true? (:claimed? result)))
                  (is (= {:remote-t 9
                          :server-checkpoint-identity "checkpoint-9"
                          :journal-high-water 14
                          :checksum-basis
                          {:version 1
                           :legacy-checksum "local-full"
                           :server-recomputed-checksum "remote-full"
                           :server-t 9
                           :legacy-anchor "remote-full"
                           :metadata-proof "proof-9"}}
                         (nth (first @claims) 2)))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest changed-suspect-does-not-claim-test
  (async done
         (let [repo "repair-suspect-changed-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               conn (atom :db)
               observations (atom [{:remote-t 9 :journal-high-water 14
                                    :pending-count 0 :legacy-anchor "local"}
                                   {:remote-t 9 :journal-high-water 15
                                    :pending-count 0 :legacy-anchor "local"}])
               claim-count (atom 0)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] conn)
                  client-op/read-repair-local-observation
                  (fn [_]
                    (let [result (first @observations)]
                      (swap! observations subvec 1)
                      result))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json
                  (fn [& _]
                    (p/resolved {:checksum "remote-full"
                                 :t 9
                                 :legacy-anchor "remote-full"
                                 :server-recomputed-checksum "remote-full"
                                 :server-checkpoint-identity "checkpoint-9"
                                 :metadata-proof "proof-9"}))
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [& _] (swap! claim-count inc))})]
                 (sync-download/<claim-repair-after-checksum-mismatch!
                  repo client 9 "remote-full"))
               (p/then (fn [result]
                         (is (= {:claimed? false :reason :suspect-changed}
                                result))
                         (is (zero? @claim-count))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest reopened-graph-discards-stale-suspect-test
  (async done
         (let [repo "repair-suspect-reopened-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               old-conn (atom :old-db)
               new-conn (atom :new-db)
               current-conn (atom old-conn)
               resolve-diagnostics (atom nil)
               diagnostics-promise
               (js/Promise. (fn [resolve _reject]
                              (reset! resolve-diagnostics resolve)))
               observation {:remote-t 9 :journal-high-water 14
                            :pending-count 0 :legacy-anchor "remote-full"}
               writes (atom 0)
               claims (atom 0)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] @current-conn)
                  client-op/read-repair-local-observation (fn [_] observation)
                  client-op/update-local-checksum (fn [& _] (swap! writes inc))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json (fn [& _] diagnostics-promise)
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [& _] (swap! claims inc))})]
                 (let [result (sync-download/<claim-repair-after-checksum-mismatch!
                               repo client 9 "remote-full")]
                   (js/setTimeout
                    (fn []
                      (reset! current-conn new-conn)
                      (@resolve-diagnostics
                       {:checksum "remote-full"
                        :t 9
                        :legacy-anchor "remote-full"
                        :server-recomputed-checksum "remote-full"
                        :server-checkpoint-identity "checkpoint-9"
                        :metadata-proof "proof-9"}))
                    0)
                   result))
               (p/then (fn [result]
                         (is (= {:claimed? false :reason :suspect-changed} result))
                         (is (zero? @writes))
                         (is (zero? @claims))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest server-checksum-drift-fails-before-anchor-change-test
  (async done
         (let [repo "repair-server-drift-repo"
               client {:graph-id "graph-1" :inflight (atom [])}
               conn (atom :db)
               observation {:remote-t 9 :journal-high-water 14
                            :pending-count 0 :legacy-anchor "message-anchor"}
               writes (atom 0)
               claims (atom 0)
               drift-error (atom nil)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config
                   {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [worker-state/get-datascript-conn (fn [_] conn)
                  client-op/read-repair-local-observation (fn [_] observation)
                  client-op/update-local-checksum (fn [& _] (swap! writes inc))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "local-full"))
                  sync-download/fetch-json
                  (fn [& _]
                    (p/resolved {:checksum "recomputed"
                                 :t 9
                                 :legacy-anchor "server-anchor"
                                 :server-recomputed-checksum "recomputed"
                                 :server-checkpoint-identity "checkpoint-9"
                                 :metadata-proof "proof-9"}))
                  thread-api/*thread-apis
                  (atom {:thread-api/db-sync-claim-repair
                         (fn [& _] (swap! claims inc))})]
                 (sync-download/<claim-repair-after-checksum-mismatch!
                  repo client 9 "message-anchor"))
               (p/catch (fn [error]
                          (reset! drift-error error)))
               (p/then (fn []
                         (is (= :db-sync/server-checksum-drift
                                (:type (ex-data @drift-error))))
                         (is (zero? @writes))
                         (is (zero? @claims))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest repair-staging-resume-clears-stale-rows-pool-test
  (async done
         (let [operation-id #uuid "90000000-0000-4000-8000-000000000001"
               sql-calls (atom [])
               create-call (atom nil)
               db #js {:exec (fn [sql] (swap! sql-calls conj sql))}
               resource {:db db :pool :rows-pool :path "/repair-rows.sqlite"}]
           (-> (p/with-redefs
                 [sync-temp-sqlite/<create-named-temp-sqlite-db!
                  (fn [pool-name file-name]
                    (reset! create-call [pool-name file-name])
                    (p/resolved resource))]
                 (#'sync-download/<create-repair-rows-db! operation-id))
               (p/then (fn [result]
                         (is (identical? resource result))
                         (is (= "/repair-rows.sqlite" (second @create-call)))
                         (is (= ["delete from kvs"] @sql-calls))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest repair-staging-builder-captures-remote-then-local-watermarks-test
  (async done
         (let [repo "repair-staging-repo"
               operation {:operation-id #uuid "91000000-0000-4000-8000-000000000001"
                          :graph-id "graph-1"}
               active-conn (atom :active-db)
               target-conn (atom :target-db)
               rows {:name :rows :db :rows-db :pool :rows-pool}
               target {:name :target :db :target-db :conn target-conn :pool :target-pool}
               diagnostics (atom [{:t 5
                                   :legacy-anchor "server-5"
                                   :server-recomputed-checksum "server-5"
                                   :server-checkpoint-identity
                                   "sync-do-checkpoint-v1:graph-1:5:server-5"
                                   :metadata-proof
                                   "authenticated-diagnostics-v1:graph-1:5:server-5:server-5"}
                                  {:t 6
                                   :legacy-anchor "server-6"
                                   :server-recomputed-checksum "server-6"
                                   :server-checkpoint-identity
                                   "sync-do-checkpoint-v1:graph-1:6:server-6"
                                   :metadata-proof
                                   "authenticated-diagnostics-v1:graph-1:6:server-6:server-6"}])
               checksums (atom ["server-6" "local-final"])
               apply-calls (atom [])
               cleaned (atom [])
               rows-create-count (atom 0)
               target-create-count (atom 0)
               local-tx {:tx-id #uuid "92000000-0000-4000-8000-000000000001"}
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [sync-download/*repair-staging-builds (atom {})
                  worker-state/get-datascript-conn (fn [_] active-conn)
                  sync-download/<create-repair-rows-db!
                  (fn [_]
                    (swap! rows-create-count inc)
                    (p/resolved rows))
                  sync-download/<create-repair-target!
                  (fn [_]
                    (swap! target-create-count inc)
                    (p/resolved target))
                  sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (when resource
                      (swap! cleaned conj (:name resource)))
                    (p/resolved nil))
                  sync-crypt/graph-e2ee? (fn [_] false)
                  sync-download/<fetch-repair-snapshot-rows! (fn [& _] (p/resolved nil))
                  sync-download/<copy-snapshot-rows-to-conn! (fn [& _] (p/resolved nil))
                  sync-checksum/<recompute-checksum
                  (fn [_]
                    (let [checksum (first @checksums)]
                      (swap! checksums subvec 1)
                      (p/resolved checksum)))
                  sync-download/<fetch-repair-diagnostics
                  (fn [& _]
                    (let [proof (first @diagnostics)]
                      (swap! diagnostics subvec 1)
                      (p/resolved proof)))
                  sync-download/fetch-json
                  (fn [url _opts schema]
                    (is (= :sync/pull schema))
                    (is (= "https://sync.example.test/sync/graph-1/pull?since=5" url))
                    (p/resolved {:type "pull/ok"
                                 :t 6
                                 :checksum "server-6"
                                 :txs [{:t 6 :tx "remote" :outliner-op :save-block}]}))
                  sync-transport/parse-transit (fn [& _] [:remote-tx])
                  sync-apply/<apply-repair-staging-tails!
                  (fn [_conn remote-txs local-txs _active-db]
                    (swap! apply-calls conj {:remote remote-txs :local local-txs})
                    {:remote-count (count remote-txs)
                     :local-count (count local-txs)})
                  client-op/read-repair-local-batch
                  (fn [_]
                    {:observation {:remote-t 6
                                   :journal-high-water 9
                                   :pending-count 1
                                   :legacy-anchor "server-6"}
                     :pending-ids [9]})
                  client-op/read-repair-local-page
                  (fn [_ ids]
                    (is (= [9] ids))
                    [local-tx])]
                 (let [build (sync-download/<build-repair-staging! repo operation)
                       duplicate-build (sync-download/<build-repair-staging! repo operation)]
                   (is (identical? build duplicate-build))
                   (p/let [result build
                           duplicate-result duplicate-build
                           _ (sync-download/<cleanup-repair-staging! result)]
                     (is (identical? result duplicate-result))
                     result)))
               (p/then
                (fn [result]
                  (is (= [{:remote [{:t 6
                                     :outliner-op :save-block
                                     :tx-data [:remote-tx]}]
                           :local []}
                          {:remote [] :local [local-tx]}]
                         @apply-calls))
                  (is (= {:remote-t 6
                          :server-checkpoint-identity
                          "sync-do-checkpoint-v1:graph-1:6:server-6"
                          :journal-high-water 9
                          :checksum-basis
                          {:version 1
                           :legacy-checksum "local-final"
                           :server-recomputed-checksum "server-6"
                           :server-t 6
                           :legacy-anchor "server-6"
                           :metadata-proof
                           "authenticated-diagnostics-v1:graph-1:6:server-6:server-6"}}
                         (:target-basis result)))
                  (is (= [:rows :target] @cleaned))
                  (is (= 1 @rows-create-count))
                  (is (= 1 @target-create-count))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest repair-staging-cleanup-failure-remains-visible-test
  (async done
         (let [target {:name :target :pool :target-pool}
               staging {:target target}
               cleanup-error (ex-info "cleanup failed" {:type :db-sync/cleanup-failed})]
           (-> (p/with-redefs
                 [sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (is (identical? target resource))
                    (p/rejected cleanup-error))]
                 (sync-download/<cleanup-repair-staging! staging))
               (p/then (fn [_]
                         (is false "expected cleanup failure")))
               (p/catch (fn [error]
                          (is (identical? cleanup-error error))
                          (is (identical? target (:target staging)))))
               (p/finally done)))))

(deftest stale-repair-cleanup-cannot-release-new-owner-test
  (async done
         (let [repo "repair-owner-token-repo"
               graph-id "graph-1"
               operation {:operation-id #uuid "94000000-0000-4000-8000-000000000001"
                          :graph-id graph-id}
               stale-token {:cancelled? (atom false)}
               current-token {:cancelled? (atom false)}
               cleaned (atom 0)
               builds (atom {[repo graph-id]
                             {:operation-id (:operation-id operation)
                              :owner-token current-token
                              :build (p/resolved nil)}})]
           (-> (p/with-redefs
                 [sync-download/*repair-staging-builds builds
                  sync-download/<cleanup-repair-resource!
                  (fn [_]
                    (swap! cleaned inc)
                    (p/resolved nil))]
                 (sync-download/<cleanup-repair-staging!
                  {:repo repo
                   :operation operation
                   :target {:name :stale-target}
                   :staging-owner-token stale-token}))
               (p/then (fn [_]
                         (is (zero? @cleaned))
                         (is (identical? current-token
                                         (get-in @builds
                                                 [[repo graph-id] :owner-token])))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest graph-close-invalidates-and-cleans-repair-staging-owner-test
  (async done
         (let [repo "repair-close-repo"
               graph-id "graph-1"
               operation {:operation-id #uuid "95000000-0000-4000-8000-000000000001"
                          :graph-id graph-id}
               owner-token {:cancelled? (atom false)}
               target {:name :target}
               result {:repo repo
                       :operation operation
                       :target target
                       :staging-owner-token owner-token}
               cleaned (atom [])
               cleanup-resolve (atom nil)
               cleanup-promise (js/Promise. (fn [resolve]
                                              (reset! cleanup-resolve resolve)))
               builds (atom {[repo graph-id]
                             {:operation-id (:operation-id operation)
                              :owner-token owner-token
                              :build (p/resolved result)}})]
           (-> (p/with-redefs
                 [sync-download/*repair-staging-builds builds
                  sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (swap! cleaned conj resource)
                    cleanup-promise)]
                 (sync-download/close-repair-staging-for-repo! repo)
                 (let [closing-build
                       (-> (sync-download/<build-repair-staging! repo operation)
                           (p/then (fn [value] {:value value}))
                           (p/catch (fn [error] {:error error})))]
                   (p/let [closing-result closing-build
                           _ (do (@cleanup-resolve nil) cleanup-promise)]
                   (is (true? @(:cancelled? owner-token)))
                   (is (= :db-sync/repair-staging-closing
                          (-> closing-result :error ex-data :type)))
                   (is (= [target] @cleaned))
                   (is (empty? @builds)))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest repair-staging-surfaces-cleanup-failure-with-unverified-tail-test
  (async done
         (let [repo "repair-staging-mismatch-repo"
               operation {:operation-id #uuid "93000000-0000-4000-8000-000000000001"
                          :graph-id "graph-1"}
               active-conn (atom :active-db)
               rows {:name :rows :db :rows-db :pool :rows-pool}
               target {:name :target
                       :db :target-db
                       :conn (atom :target-db)
                       :pool :target-pool}
               cleaned (atom [])
               cleanup-error (ex-info "target cleanup failed"
                                      {:type :db-sync/target-cleanup-failed})
               pull-count (atom 0)
               apply-count (atom 0)
               local-read-count (atom 0)
               config-prev @worker-state/*db-sync-config]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (-> (p/with-redefs
                 [sync-download/*repair-staging-builds (atom {})
                  worker-state/get-datascript-conn (fn [_] active-conn)
                  sync-download/<create-repair-rows-db! (fn [_] (p/resolved rows))
                  sync-download/<create-repair-target! (fn [_] (p/resolved target))
                  sync-download/<cleanup-repair-resource!
                  (fn [resource]
                    (when resource
                      (swap! cleaned conj (:name resource)))
                    (if (= :target (:name resource))
                      (p/rejected cleanup-error)
                      (p/resolved nil)))
                  sync-crypt/graph-e2ee? (fn [_] false)
                  sync-download/<fetch-repair-snapshot-rows! (fn [& _] (p/resolved nil))
                  sync-download/<copy-snapshot-rows-to-conn! (fn [& _] (p/resolved nil))
                  sync-checksum/<recompute-checksum (fn [_] (p/resolved "stale-snapshot"))
                  sync-download/<fetch-repair-diagnostics
                  (fn [& _]
                    (p/resolved
                     {:t 5
                      :legacy-anchor "server-5"
                      :server-recomputed-checksum "server-5"
                      :server-checkpoint-identity
                      "sync-do-checkpoint-v1:graph-1:5:server-5"
                      :metadata-proof
                      "authenticated-diagnostics-v1:graph-1:5:server-5:server-5"}))
                  sync-download/fetch-json
                  (fn [_url _opts schema]
                    (is (= :sync/pull schema))
                    (swap! pull-count inc)
                    (p/resolved {:type "pull/ok"
                                 :t 5
                                 :checksum "server-5"
                                 :txs []}))
                  sync-apply/<apply-repair-staging-tails!
                  (fn [& _]
                    (swap! apply-count inc)
                    {:remote-count 0 :local-count 0})
                  client-op/read-repair-local-batch
                  (fn [& _]
                    (swap! local-read-count inc)
                    nil)]
                 (sync-download/<build-repair-staging! repo operation))
               (p/then (fn [_]
                         (is false "expected unverified tail failure")))
               (p/catch (fn [error]
                          (is (= :db-sync/repair-staging-cleanup-failed
                                 (:type (ex-data error))))
                          (is (= :db-sync/repair-remote-tail-checksum-mismatch
                                 (-> error ex-data :original-error ex-data :type)))
                          (is (= [cleanup-error]
                                 (:cleanup-errors (ex-data error))))
                          (is (identical? target (:target (ex-data error))))
                          (is (= [:rows :target] @cleaned))
                          (is (= 1 @pull-count))
                          (is (= 1 @apply-count))
                          (is (zero? @local-read-count))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

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

(deftest repair-staging-final-fence-applies-only-new-local-ids-and-rejects-remote-move-test
  (async done
         (let [repo "repair-final-fence-repo"
               graph-id "repair-final-fence-graph"
               active-conn (atom :active-db)
               config-prev @worker-state/*db-sync-config
               conns-prev @worker-state/*datascript-conns
               applied-ids (atom nil)
               remote-t* (atom 12)
               proof (fn []
                       (let [remote-t @remote-t*
                             anchor "server-checksum"]
                         {:t remote-t
                          :legacy-anchor anchor
                          :server-recomputed-checksum anchor
                          :server-checkpoint-identity
                          (str "sync-do-checkpoint-v1:" graph-id ":" remote-t ":" anchor)
                          :metadata-proof
                          (str "authenticated-diagnostics-v1:" graph-id ":" remote-t ":"
                               anchor ":" anchor)}))
               staging {:repo repo
                        :operation {:operation-id
                                    #uuid "99000000-0000-4000-8000-000000000001"
                                    :graph-id graph-id}
                        :target {:conn (atom :target-db)}
                        :target-basis {:remote-t 12
                                       :journal-high-water 10}
                        :local-result {:local-count 3}
                        :staging-owner-token {:cancelled? (atom false)}}
               capture-outcome (fn [f]
                                 (try
                                   (-> (f)
                                       (p/then (fn [value] {:value value}))
                                       (p/catch (fn [error] {:error error})))
                                   (catch :default error
                                     (p/resolved {:error error}))))]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (reset! worker-state/*datascript-conns {repo active-conn})
           (-> (p/with-redefs
                 [sync-download/repair-staging-owner? (fn [& _] true)
                  sync-download/<fetch-repair-diagnostics (fn [& _] (p/resolved (proof)))
                  sync-download/capture-repair-local-state
                  (fn [& _]
                    {:local-batch {:pending-ids [9 10 11 12]
                                   :observation {:remote-t 12
                                                 :journal-high-water 12}}
                     :active-db :captured-active-db})
                  sync-download/<apply-repair-local-watermark!
                  (fn [_repo _target-conn _active-db ids]
                    (reset! applied-ids ids)
                    (p/resolved {:remote-count 0 :local-count (count ids)}))
                  sync-checksum/<recompute-checksum
                  (fn [_db] (p/resolved "target-checksum"))]
                 (p/let [finalized (sync-download/<finalize-repair-staging! staging)
                         _ (reset! remote-t* 13)
                         remote-move-outcome
                         (capture-outcome
                          #(sync-download/<finalize-repair-staging! staging))]
                   (is (= [11 12] @applied-ids))
                   (is (= 12 (get-in finalized [:target-basis :journal-high-water])))
                   (is (= 5 (get-in finalized [:local-result :local-count])))
                   (is (= :db-sync/repair-staging-remote-advanced
                          (:type (ex-data (:error remote-move-outcome)))))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (reset! worker-state/*datascript-conns conns-prev)
                            (done)))))))

(deftest encrypted-download-preflights-e2ee-before-fetching-snapshot-stream-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               fetch-prev js/fetch
               calls (atom [])]
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
                               sync-download/<stream-snapshot-row-batches! (fn [_resp _batch-size _on-batch]
                                                                             (p/resolved {:chunk-count 0}))]
                 (sync-download/download-graph-by-id! "repo" "graph-1" true))
               (p/then (fn [_]
                         (is (= [:e2ee-preflight :snapshot-stream] @calls))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (set! js/fetch fetch-prev)
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))

(deftest encrypted-download-failure-emits-completed-log-test
  (async done
         (let [config-prev @worker-state/*db-sync-config
               log-events (atom [])]
           (reset! worker-state/*db-sync-config {:http-base "https://sync.example.test"})
           (-> (p/with-redefs [sync-download/fetch-json (fn [_url _opts schema]
                                                          (case schema
                                                            :sync/pull
                                                            (p/resolved {:t 42})

                                                            :sync/snapshot-download
                                                            (p/resolved {:url "https://sync.example.test/snapshot"})

                                                            (p/rejected (ex-info "unexpected schema" {:schema schema}))))
                               sync-crypt/<fetch-graph-aes-key-for-download (fn [_graph-id]
                                                                               (p/rejected (ex-info "decrypt-private-key" {})))
                               rtc-log-and-state/rtc-log (fn [type payload]
                                                           (swap! log-events conj (assoc payload :type type))
                                                           nil)]
                 (sync-download/download-graph-by-id! "repo" "graph-1" true))
               (p/then (fn [_]
                         (is false "expected download failure")))
               (p/catch (fn [error]
                          (is (= "db-sync download failed" (ex-message error)))
                          (is (= [:download-progress :download-completed]
                                 (mapv :sub-type @log-events)))))
               (p/finally (fn []
                            (reset! worker-state/*db-sync-config config-prev)
                            (done)))))))
