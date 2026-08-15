(ns frontend.worker.sync.download-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.crypt :as sync-crypt]
            [frontend.worker.sync.download :as sync-download]
            [frontend.worker.sync.log-and-state :as rtc-log-and-state]
            [logseq.db-sync.checksum :as sync-checksum]
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
