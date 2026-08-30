(ns electron.updater-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.updater-config :as updater-config]))

(deftest traditional-release-channel-test
  (testing "the .7 client and its synthetic .8 fixture use release-local architecture channels"
    (doseq [version [updater-config/source-version
                     updater-config/synthetic-forward-target-version]]
      (is (= (str updater-config/release-line-id "-x64")
             (updater-config/updater-channel version "darwin" "x64")))
      (is (= (str updater-config/release-line-id "-arm64")
             (updater-config/updater-channel version "darwin" "arm64")))
      (is (= (str updater-config/release-line-id "-x64")
             (updater-config/updater-channel version "win32" "x64")))
      (is (= (str updater-config/release-line-id "-arm64")
             (updater-config/updater-channel version "win32" "arm64")))
      (is (= updater-config/release-line-id
             (updater-config/updater-channel version "linux" "x64")))
      (is (= updater-config/release-line-id
             (updater-config/updater-channel version "linux" "arm64")))))
  (testing "old and non-formal product versions fail closed"
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5" "darwin" "arm64")))
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.7.nightly.20260825" "darwin" "arm64"))))
  (testing "unsupported architectures never reuse a qualified channel"
    (is (nil? (updater-config/updater-channel
               updater-config/source-version "darwin" "ia32")))))

(deftest updater-options-test
  (is (= {:channel (str updater-config/release-line-id "-arm64")
          :allow-prerelease? false
          :allow-downgrade? false}
         (updater-config/updater-options
          updater-config/source-version "darwin" "arm64")))
  (is (= (str updater-config/release-line-id "-arm64")
         (:channel (updater-config/updater-options
                    updater-config/source-version "win32" "arm64"))))
  (is (nil? (:feed-url (updater-config/updater-options
                        updater-config/source-version "linux" "x64"))))
  (is (nil? (:provider (updater-config/updater-options
                        updater-config/source-version "linux" "x64")))))
