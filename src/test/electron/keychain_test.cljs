(ns electron.keychain-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.keychain :as keychain]
            [goog.object :as gobj]))

(defn- with-qualification-home
  [value f]
  (let [env (.-env js/process)
        previous (gobj/get env "LOGSEQ_TEST_HOME_DIR")]
    (try
      (if (some? value)
        (gobj/set env "LOGSEQ_TEST_HOME_DIR" value)
        (gobj/remove env "LOGSEQ_TEST_HOME_DIR"))
      (f)
      (finally
        (if (some? previous)
          (gobj/set env "LOGSEQ_TEST_HOME_DIR" previous)
          (gobj/remove env "LOGSEQ_TEST_HOME_DIR"))))))

(deftest qualification-home-disables-main-keychain-owner-test
  (testing "the explicit qualification Home closes the keytar side-effect boundary"
    (with-qualification-home
      "/private/tmp/selfhost6-phase7/home"
      #(do
         (is (true? (#'keychain/qualification-home?)))
         (is (false? (keychain/supported?))))))
  (testing "an empty override is not a qualification environment"
    (with-qualification-home
      ""
      #(is (false? (#'keychain/qualification-home?))))))
