(ns electron.updater-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.updater-config :as updater-config]))

(deftest selfhost-version-test
  (is (updater-config/selfhost-version? "2.0.1-selfhost.4"))
  (is (updater-config/selfhost-version? "2.0.1-selfhost"))
  (is (not (updater-config/selfhost-version? "2.0.1-alpha.1")))
  (is (not (updater-config/selfhost-version? nil))))

(deftest updater-channel-test
  (testing "Windows and macOS use architecture-specific metadata"
    (is (= "latest-x64"
           (updater-config/updater-channel "win32" "x64")))
    (is (= "latest-arm64"
           (updater-config/updater-channel "win32" "arm64")))
    (is (= "latest-x64"
           (updater-config/updater-channel "darwin" "x64")))
    (is (= "latest-arm64"
           (updater-config/updater-channel "darwin" "arm64"))))
  (testing "Linux lets electron-updater select its native platform metadata"
    (is (nil? (updater-config/updater-channel "linux" "x64")))
    (is (nil? (updater-config/updater-channel "linux" "arm64"))))
  (testing "unsupported architectures do not reuse another architecture"
    (is (nil? (updater-config/updater-channel "darwin" "ia32")))
    (is (nil? (updater-config/updater-channel "win32" "ia32")))))

(deftest updater-options-test
  (testing "selfhost versions use the latest production GitHub release"
    (is (= {:channel "latest-arm64"
            :allow-prerelease? false
            :allow-downgrade? false}
           (updater-config/updater-options
            "2.0.1-selfhost.4"
            "darwin"
            "arm64"))))
  (testing "upstream prerelease behavior remains owned by electron-updater"
    (is (= {:channel "latest-x64"
            :allow-prerelease? nil
            :allow-downgrade? false}
           (updater-config/updater-options
            "2.0.1-alpha.1"
            "win32"
            "x64")))))
