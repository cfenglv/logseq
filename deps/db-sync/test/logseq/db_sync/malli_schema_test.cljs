(ns logseq.db-sync.malli-schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [logseq.db-sync.malli-schema :as db-sync-schema]))

(def ^:private request-samples
  {:graphs/create {:graph-name "Demo"}
   :graph-members/create {:email "user@example.com"}
   :graph-members/update {:role "member"}
   :sync/tx-batch {:t-before 0 :txs []}
   :e2ee/user-keys {:public-key "public"
                    :encrypted-private-key "private"}
   :e2ee/graph-aes-key {:encrypted-aes-key "aes"}
   :e2ee/grant-access {:target-user-email+encrypted-aes-key-coll
                       [{:email "user@example.com"
                         :encrypted-aes-key "aes"}]}})

(defn- coerce-request
  [schema-key body]
  ((get db-sync-schema/http-request-coercers schema-key) body))

(def ^:private legacy-v1-ws-client-samples
  [{:type "hello"
    :client "legacy-repo"}
   {:type "presence"
    :editing-block-uuid nil}
   {:type "pull"
    :since 0}
   {:type "tx/batch"
    :t-before 0
    :txs [{:tx "legacy-transit"}]}
   {:type "ping"}])

(def ^:private legacy-v1-ws-server-samples
  [{:type "hello"
    :t 0}
   {:type "presence"
    :user-id "legacy-user"
    :editing-block-uuid nil}
   {:type "pull/ok"
    :t 0
    :txs []}
   {:type "tx/batch/ok"
    :t 0}
   {:type "changed"
    :t 0}
   {:type "tx/reject"
    :reason "stale"
    :t 0}
   {:type "pong"}])

(deftest http-request-client-revision-is-optional-test
  (doseq [[schema-key body] request-samples]
    (testing schema-key
      (is (= body (coerce-request schema-key body))))))

(deftest legacy-v1-websocket-messages-remain-valid-test
  (testing "client messages without optional revision fields"
    (doseq [message legacy-v1-ws-client-samples]
      (is (= message
             (db-sync-schema/ws-client-message-coercer message)))))
  (testing "server messages without optional checksum fields"
    (doseq [message legacy-v1-ws-server-samples]
      (is (= message
             (db-sync-schema/ws-server-message-coercer message))))))

(deftest tx-batch-request-client-revision-accepts-string-test
  (let [body' (assoc (:sync/tx-batch request-samples)
                     :client-revision "test-revision")]
    (is (= body' (coerce-request :sync/tx-batch body')))))

(deftest versioned-server-checksum-fields-are-additive-test
  (doseq [message [{:type "hello" :t 0}
                   {:type "pull/ok" :t 0 :txs []}
                   {:type "tx/batch/ok" :t 0}]]
    (let [message' (assoc message
                          :checksum "legacy"
                          :checksum-version "server-db-v2"
                          :server-checksum "versioned")]
      (is (= message'
             (db-sync-schema/ws-server-message-coercer message'))))))

(deftest ws-tx-batch-client-revision-accepts-string-test
  (let [body {:type "tx/batch"
              :t-before 0
              :txs []
              :client-revision "test-revision"}]
    (is (= body (db-sync-schema/ws-client-message-coercer body)))))

(deftest tx-batch-request-client-revision-rejects-non-string-test
  (is (thrown? js/Error
               (coerce-request :sync/tx-batch
                               (assoc (:sync/tx-batch request-samples)
                                      :client-revision 42)))))
