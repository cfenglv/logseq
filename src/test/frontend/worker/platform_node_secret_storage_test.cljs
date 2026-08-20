(ns frontend.worker.platform-node-secret-storage-test
  (:require ["fs" :as fs]
            ["keytar" :as keytar]
            ["path" :as node-path]
            [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [frontend.test.node-helper :as node-helper]
            [frontend.worker.platform.node :as platform-node]
            [goog.object :as gobj]
            [promesa.core :as p]))

(deftest test-build-secrets-use-isolated-storage
  (async done
    (let [root-dir-a (node-helper/create-tmp-dir "platform-node-test-secrets-a")
          root-dir-b (node-helper/create-tmp-dir "platform-node-test-secrets-b")
          account "rtc-encrypted-aes-key###shared-account"
          calls (atom [])
          secrets (atom {})
          original-save (gobj/get keytar "setPassword")
          original-read (gobj/get keytar "getPassword")
          original-delete (gobj/get keytar "deletePassword")]
      (gobj/set keytar "setPassword" (fn [service key value]
                                        (swap! calls conj [:save service key value])
                                        (swap! secrets assoc [service key] value)
                                        (js/Promise.resolve true)))
      (gobj/set keytar "getPassword" (fn [service key]
                                        (swap! calls conj [:read service key])
                                        (js/Promise.resolve (get @secrets [service key]))))
      (gobj/set keytar "deletePassword" (fn [service key]
                                           (swap! calls conj [:delete service key])
                                           (swap! secrets dissoc [service key])
                                           (js/Promise.resolve true)))
      (-> (p/let [platform-a (platform-node/node-platform {:root-dir root-dir-a
                                                           :owner-source :cli})
                  platform-b (platform-node/node-platform {:root-dir root-dir-b
                                                           :owner-source :cli})
                  crypto-a (:crypto platform-a)
                  crypto-b (:crypto platform-b)
                  kv-a (:kv platform-a)
                  kv-b (:kv platform-b)
                  _ ((:set! kv-a) account "ordinary-a")
                  _ ((:set! kv-b) account "ordinary-b")
                  _ ((:save-secret-text! crypto-a) account "secret-a")
                  _ ((:save-secret-text! crypto-b) account "secret-b")
                  platform-a-reloaded (platform-node/node-platform {:root-dir root-dir-a
                                                                    :owner-source :cli})
                  secret-a ((get-in platform-a-reloaded [:crypto :read-secret-text]) account)
                  secret-b ((:read-secret-text crypto-b) account)
                  ordinary-a ((:get kv-a) account)
                  ordinary-b ((:get kv-b) account)
                  _ ((get-in platform-a-reloaded [:crypto :delete-secret-text!]) account)
                  deleted-a ((get-in platform-a-reloaded [:crypto :read-secret-text]) account)
                  retained-b ((:read-secret-text crypto-b) account)]
            (testing "the dedicated test build never calls native Keychain APIs"
              (is (empty? @calls))
              (is (empty? @secrets)))
            (testing "test secrets persist per root without sharing state"
              (is (= "secret-a" secret-a))
              (is (= "secret-b" secret-b))
              (is (nil? deleted-a))
              (is (= "secret-b" retained-b)))
            (testing "test-secret storage is separate from the ordinary KV store"
              (is (= "ordinary-a" ordinary-a))
              (is (= "ordinary-b" ordinary-b))
              (is (not (string/includes?
                        (.toString
                         (fs/readFileSync (node-path/join root-dir-a "kv-store.json"))
                         "utf8")
                        "secret-a")))))
          (p/catch (fn [error]
                     (is false (str "unexpected error: " error))))
          (p/finally (fn []
                       (gobj/set keytar "setPassword" original-save)
                       (gobj/set keytar "getPassword" original-read)
                       (gobj/set keytar "deletePassword" original-delete)
                       (done)))))))
