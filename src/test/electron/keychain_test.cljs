(ns electron.keychain-test
  (:require ["keytar" :as keytar]
            [cljs.test :refer [async deftest is testing]]
            [electron.keychain :as keychain]
            [goog.object :as gobj]
            [promesa.core :as p]))

(deftest production-keychain-keeps-service-and-account-compatible
  (async done
    (let [account "rtc-encrypted-aes-key###graph-id"
          encrypted-text "encrypted-graph-key"
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
      (-> (p/let [saved? (keychain/<set-password! account encrypted-text)
                  loaded (keychain/<get-password account)
                  deleted? (keychain/<delete-password! account)
                  after-delete (keychain/<get-password account)]
            (testing "the formal production service/account pair remains stable"
              (is (= [[:save "Logseq E2EE" account encrypted-text]
                      [:read "Logseq E2EE" account]
                      [:delete "Logseq E2EE" account]
                      [:read "Logseq E2EE" account]]
                     @calls)))
            (testing "the production adapter preserves CRUD semantics"
              (is (true? saved?))
              (is (= encrypted-text loaded))
              (is (true? deleted?))
              (is (nil? after-delete))))
          (p/catch (fn [error]
                     (is false (str "unexpected error: " error))))
          (p/finally (fn []
                       (gobj/set keytar "setPassword" original-save)
                       (gobj/set keytar "getPassword" original-read)
                       (gobj/set keytar "deletePassword" original-delete)
                       (done)))))))
