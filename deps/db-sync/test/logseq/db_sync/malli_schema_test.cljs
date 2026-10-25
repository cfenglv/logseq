(ns logseq.db-sync.malli-schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [logseq.db-sync.malli-schema :as db-sync-schema]
            [malli.core :as ma]))

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

(deftest http-request-client-revision-is-optional-test
  (doseq [[schema-key body] request-samples]
    (testing schema-key
      (is (= body (coerce-request schema-key body))))))

(deftest tx-batch-request-client-revision-accepts-string-test
  (let [body' (assoc (:sync/tx-batch request-samples)
                     :client-revision "test-revision")]
    (is (= body' (coerce-request :sync/tx-batch body')))))

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

(deftest staged-upload-fields-survive-request-coercion-test
  (let [logical-tx-id (random-uuid)
        tx-id (random-uuid)
        entry {:tx-id tx-id
               :logical-tx-id logical-tx-id
               :upload-session-id (apply str (repeat 64 "a"))
               :chunk-index 0
               :chunk-final? false
               :tx "payload"
               :outliner-op :save-block}
        body {:t-before 0 :txs [entry]}]
    (is (= body (coerce-request :sync/tx-batch body)))))

(deftest user-profile-contract-test
  (let [body {:display-name "测试 😀" :avatar-png-base64 nil :expected-revision 0}
        response {:user-id "profile-user" :display-name nil :has-avatar? false
                  :profile-revision 2 :avatar-png-base64 nil}]
    (is (= body (coerce-request :user-profile/save body)))
    (is (= (assoc body :display-name nil)
           (coerce-request :user-profile/save (assoc body :display-name nil))))
    (is (= response ((get db-sync-schema/http-response-coercers :user-profile/get) response)))
    (is (ma/validate db-sync-schema/user-profile-save-request-schema
                     (assoc body :display-name (apply str (repeat 64 "😀")))))
    (doseq [invalid [(dissoc body :avatar-png-base64)
                     (assoc body :user-id "another-user")
                     (assoc body :display-name "  ")
                     (assoc body :display-name 42)
                     (assoc body :display-name (apply str (repeat 65 "😀")))
                     (assoc body :expected-revision -1)
                     (assoc body :expected-revision "1")
                     (assoc body :avatar-png-base64 (apply str (repeat (inc db-sync-schema/avatar-base64-max-length) "a")))]]
      (is (not (ma/validate db-sync-schema/user-profile-save-request-schema invalid))))
    (try
      (coerce-request :user-profile/save (assoc body :user-id "another-user" :avatar-png-base64 "private-image-data"))
      (is false "Invalid request must fail")
      (catch :default error
        (is (not (contains? (ex-data error) :value)))
        (is (not (.includes (str error) "private-image-data")))))))

(deftest profile-websocket-contract-test
  (let [refresh {:type "profile/refresh"}
        updated {:type "profile/updated" :user-id "user-1" :display-name nil
                 :has-avatar? false :profile-revision 3}]
    (is (= refresh (db-sync-schema/ws-client-message-coercer refresh)))
    (is (= updated (db-sync-schema/ws-server-message-coercer updated)))
    (is (not (ma/validate db-sync-schema/ws-client-message-schema (assoc refresh :user-id "another-user"))))
    (is (not (ma/validate db-sync-schema/ws-server-message-schema (assoc updated :avatar-png-base64 "image"))))
    (is (= (assoc updated :display-name "Display Name")
           (db-sync-schema/ws-server-message-coercer (assoc updated :display-name "Display Name"))))))

(deftest acknowledgement-validates-canonical-journal-test
  (let [body {:type "tx/batch/ok" :t-before 0 :t 1
              :txs [{:t 1 :tx "canonical Transit" :outliner-op "save-block"
                     :tx-id "00000000-0000-4000-8000-000000000001"}]}]
    (is (= :save-block (get-in (db-sync-schema/ws-server-message-coercer body)
                               [:txs 0 :outliner-op])))
    (is (uuid? (get-in (db-sync-schema/ws-server-message-coercer body) [:txs 0 :tx-id])))
    (doseq [invalid [(dissoc body :t)
                     (assoc body :t-before "invalid")
                     (assoc body :txs [{:t 1 :tx 42}])]]
      (is (thrown? js/Error (db-sync-schema/ws-server-message-coercer invalid))))))

(deftest acknowledgement-accepts-existing-client-protocol-test
  (doseq [body [{:type "tx/batch/ok" :t 1}
               {:type "tx/batch/ok" :t 1 :checksum "0000000000000000"}]]
    (is (= body (db-sync-schema/ws-server-message-coercer body)))
    (is (= body ((get db-sync-schema/http-response-coercers :sync/tx-batch) body)))))
