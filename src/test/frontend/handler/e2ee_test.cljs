(ns frontend.handler.e2ee-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.handler.e2ee :as e2ee-handler]
            [frontend.util :as util]
            [goog.object :as gobj]))

(defn- with-test-home-env
  [value f]
  (let [process (gobj/get js/globalThis "process")
        env (gobj/get process "env")
        old-value (gobj/get env "LOGSEQ_TEST_HOME_DIR")]
    (try
      (if (some? value)
        (gobj/set env "LOGSEQ_TEST_HOME_DIR" value)
        (gobj/remove env "LOGSEQ_TEST_HOME_DIR"))
      (f)
      (finally
        (if (some? old-value)
          (gobj/set env "LOGSEQ_TEST_HOME_DIR" old-value)
          (gobj/remove env "LOGSEQ_TEST_HOME_DIR"))))))

(deftest electron-native-storage-respects-qualification-home-test
  (testing "normal Electron keeps the official OS Keychain owner"
    (with-test-home-env
      nil
      #(with-redefs [util/electron? (constantly true)
                     util/capacitor? (constantly false)]
         (is (true? (e2ee-handler/native-storage-supported?))))))
  (testing "qualification Electron leaves the global Keychain untouched"
    (with-test-home-env
      "/private/tmp/selfhost6-phase7-test/home"
      #(with-redefs [util/electron? (constantly true)
                     util/capacitor? (constantly false)]
         (is (false? (e2ee-handler/native-storage-supported?)))))))
