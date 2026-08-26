(ns electron.updater-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.updater-config :as updater-config]))

(deftest isolated-release-line-test
  (testing "the .7 client and its synthetic .8 fixture share one channel"
    (doseq [version [updater-config/source-version
                     updater-config/synthetic-forward-target-version]
            [platform arch] [["darwin" "arm64"]
                             ["darwin" "x64"]
                             ["win32" "x64"]
                             ["win32" "arm64"]
                             ["linux" "arm64"]
                             ["linux" "x64"]]]
      (is (= updater-config/release-line-id
             (updater-config/updater-channel version platform arch)))))
  (testing "old and non-formal product versions fail closed"
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5" "darwin" "arm64")))
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.7.nightly.20260825" "darwin" "arm64")))))

(deftest updater-options-test
  (is (= {:provider "generic"
          :feed-url updater-config/provider-base-url
          :channel updater-config/release-line-id
          :allow-prerelease? false
          :allow-downgrade? false}
         (updater-config/updater-options
          updater-config/source-version "darwin" "arm64")))
  (is (= updater-config/release-line-id
         (:channel (updater-config/updater-options
                    updater-config/source-version "win32" "arm64")))))
