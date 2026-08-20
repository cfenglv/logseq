(ns frontend.worker.sync.util-test
  (:require [cljs.test :refer [async deftest is testing]]
            [frontend.worker.sync.util :as sync-util]
            [logseq.common.version :as build-version]
            [promesa.core :as p]))

(deftest coerce-http-request-adds-client-revision-to-tx-batch-test
  (is (= (build-version/revision)
         (:client-revision
          (sync-util/coerce-http-request :sync/tx-batch
                                         {:t-before 0
                                          :txs []})))))

(deftest coerce-http-request-does-not-add-client-revision-to-other-requests-test
  (is (= {:graph-name "Demo"}
         (sync-util/coerce-http-request :graphs/create
                                        {:graph-name "Demo"}))))

(deftest coerce-http-request-preserves-explicit-client-revision-test
  (is (= "explicit-revision"
         (:client-revision
          (sync-util/coerce-http-request :sync/tx-batch
                                         {:t-before 0
                                          :txs []
                                          :client-revision "explicit-revision"})))))

(deftest sync-fetch-aborts-an-unsettled-request-at-its-deadline-test
  (async done
    (let [original-fetch js/fetch
          observed-signal* (atom nil)]
      (set! js/fetch
            (fn [_url opts]
              (let [signal (.-signal opts)]
                (reset! observed-signal* signal)
                (p/create
                 (fn [_resolve reject]
                   (.addEventListener
                    signal
                    "abort"
                    #(reject (js/DOMException. "aborted" "AbortError"))
                    #js {:once true}))))))
      (->
       (sync-util/fetch
        "https://sync.invalid/marker-state"
        {:method "GET"
         :operation :marker-state
         :timeout-ms 10})
       (p/then
        (fn [_]
          (is false "an unsettled request must not outlive its deadline")))
       (p/catch
        (fn [error]
          (is (= :db-sync/http-timeout (:type (ex-data error))))
          (is (= :http-timeout (:code (ex-data error))))
          (is (= :marker-state (:operation (ex-data error))))
          (is (= 10 (:timeout-ms (ex-data error))))
          (is (true? (.-aborted @observed-signal*)))))
       (p/finally
        (fn []
          (set! js/fetch original-fetch)
          (done)))))))

(deftest sync-fetch-classifies-network-rejections-as-transient-test
  (async done
    (let [original-fetch js/fetch]
      (set! js/fetch
            (fn [_url _opts]
              (p/rejected (js/TypeError. "connect failed"))))
      (->
       (sync-util/fetch
        "https://sync.invalid/asset"
        {:method "GET"
         :operation :large-title-download
         :timeout-ms 100})
       (p/then
        (fn [_]
          (is false "a rejected network request must remain rejected")))
       (p/catch
        (fn [error]
          (is (= :db-sync/http-request-failed
                 (:type (ex-data error))))
          (is (= :large-title-download
                 (:operation (ex-data error))))
          (is (sync-util/transient-sync-error? error))))
       (p/finally
        (fn []
          (set! js/fetch original-fetch)
          (done)))))))

(deftest with-timeout-bounds-non-http-recovery-stages-test
  (async done
    (->
     (sync-util/with-timeout
      (p/create (fn [_resolve _reject]))
      10
      {:type :db-sync/recovery-timeout
       :code :marker-recovery-aes-key-timeout
       :operation :large-title-marker-recovery
       :stage :aes-key})
     (p/then
      (fn [_]
        (is false "an unsettled E2EE stage must not block the receive queue")))
     (p/catch
      (fn [error]
        (testing "the diagnostic is bounded and contains no request secrets"
          (is (= :db-sync/recovery-timeout (:type (ex-data error))))
          (is (= :marker-recovery-aes-key-timeout
                 (:code (ex-data error))))
          (is (= :aes-key (:stage (ex-data error))))
          (is (= :large-title-marker-recovery
                 (:operation (ex-data error))))
          (is (sync-util/transient-sync-error? error)))))
     (p/finally done))))

(deftest temporarily-unavailable-e2ee-key-is-transient-test
  (let [error (ex-info "graph E2EE key is temporarily unavailable"
                       {:type :db-sync/e2ee-key-unavailable
                        :code :missing-aes-key
                        :operation :large-title-marker-recovery})]
    (is (sync-util/transient-sync-error? error))
    (is (= :missing-aes-key
           (:code (sync-util/error->diagnostic error))))))
