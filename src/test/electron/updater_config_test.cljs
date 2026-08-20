(ns electron.updater-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.updater-config :as updater-config]))

(deftest selfhost-version-test
  (is (updater-config/selfhost-version? "2.0.1-selfhost.4"))
  (is (updater-config/selfhost-version? "2.0.1-selfhost"))
  (is (not (updater-config/selfhost-version? "2.0.1-alpha.1")))
  (is (not (updater-config/selfhost-version? nil))))

(deftest updater-channel-test
  (testing "Windows keeps architecture-specific legacy metadata"
    (is (= "latest-x64"
           (updater-config/updater-channel
            "2.0.1-selfhost.5" "win32" "x64")))
    (is (= "latest-arm64"
           (updater-config/updater-channel
            "2.0.1-selfhost.5" "win32" "arm64"))))
  (testing "macOS selfhost.4 remains on its frozen legacy channel"
    (is (= "latest-x64"
           (updater-config/updater-channel
            "2.0.1-selfhost.4" "darwin" "x64")))
    (is (= "latest-arm64"
           (updater-config/updater-channel
            "2.0.1-selfhost.4" "darwin" "arm64"))))
  (testing "macOS selfhost.5 starts a signed update channel"
    (is (= "selfhost-macos-v2-x64"
           (updater-config/updater-channel
            "2.0.1-selfhost.5" "darwin" "x64")))
    (is (= "selfhost-macos-v2-nightly-arm64"
           (updater-config/updater-channel
            "2.0.1-selfhost.5.nightly.20260729"
            "darwin"
            "arm64")))
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5-alpha.nightly.20260729"
               "darwin"
               "arm64")))
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5.nightly.20260229"
               "darwin"
               "arm64"))))
  (testing "upstream macOS versions keep their existing channels"
    (is (= "latest-arm64"
           (updater-config/updater-channel
            "2.0.1-alpha.1" "darwin" "arm64"))))
  (testing "Linux lets electron-updater select its native platform metadata"
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5" "linux" "x64")))
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5" "linux" "arm64"))))
  (testing "unsupported architectures do not reuse another architecture"
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5" "darwin" "ia32")))
    (is (nil? (updater-config/updater-channel
               "2.0.1-selfhost.5" "win32" "ia32")))))

(deftest project-signed-macos-updater-test
  (is (updater-config/project-signed-macos-updater?
       "2.0.1-selfhost.5" "darwin"))
  (is (updater-config/project-signed-macos-updater?
       "2.0.1-selfhost.6" "darwin"))
  (is (updater-config/project-signed-macos-updater?
       "2.0.1-selfhost.6.nightly.20260729" "darwin"))
  (is (not (updater-config/project-signed-macos-updater?
            "2.0.1-selfhost.6-alpha.nightly.20260729" "darwin")))
  (is (not (updater-config/project-signed-macos-updater?
            "2.0.1-selfhost.4" "darwin")))
  (is (not (updater-config/project-signed-macos-updater?
            "2.0.1-selfhost.5" "win32"))))

(deftest updater-options-test
  (testing "selfhost versions use the signed macOS production channel"
    (is (= {:channel "selfhost-macos-v2-arm64"
            :allow-prerelease? false
            :allow-downgrade? false}
           (updater-config/updater-options
            "2.0.1-selfhost.5"
            "darwin"
            "arm64"))))
  (testing "selfhost nightly builds use the isolated rolling prerelease feed"
    (is (= {:channel "selfhost-macos-v2-nightly-arm64"
            :allow-prerelease? true
            :allow-downgrade? false
            :feed-url updater-config/selfhost-nightly-feed-url}
           (updater-config/updater-options
            "2.0.1-selfhost.5.nightly.20260729"
            "darwin"
            "arm64")))
    (is (= {:channel "latest-x64"
            :allow-prerelease? true
            :allow-downgrade? false
            :feed-url updater-config/selfhost-nightly-feed-url}
           (updater-config/updater-options
            "2.0.1-selfhost.5.nightly.20260729"
            "win32"
            "x64")))
    (is (= {:channel nil
            :allow-prerelease? true
            :allow-downgrade? false
            :feed-url updater-config/selfhost-nightly-feed-url}
           (updater-config/updater-options
            "2.0.1-selfhost.5.nightly.20260729"
            "linux"
            "arm64"))))
  (testing "upstream prerelease behavior remains owned by electron-updater"
    (is (= {:channel "latest-x64"
            :allow-prerelease? nil
            :allow-downgrade? false}
           (updater-config/updater-options
            "2.0.1-alpha.1"
            "win32"
            "x64")))))
