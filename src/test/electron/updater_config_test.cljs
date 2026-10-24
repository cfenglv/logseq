(ns electron.updater-config-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.updater-config :as updater-config]))

(deftest traditional-release-channel-test
  (testing "standard architecture channels do not depend on the running version"
    (doseq [[platform arch channel] [["darwin" "x64" "latest-x64"]
                                   ["darwin" "arm64" "latest-arm64"]
                                   ["win32" "x64" "latest-x64"]
                                   ["win32" "arm64" "latest-arm64"]
                                   ["linux" "x64" "latest"]
                                   ["linux" "arm64" "latest"]]]
      (is (= channel (updater-config/updater-channel platform arch)))))
  (testing "unsupported platforms or architectures do not reuse a channel"
    (is (nil? (updater-config/updater-channel "darwin" "ia32")))
    (is (nil? (updater-config/updater-channel "unsupported" "x64")))))

(deftest updater-options-test
  (is (= {:channel "latest-arm64"
          :allow-prerelease? false
          :allow-downgrade? false}
         (updater-config/updater-options
          "darwin" "arm64")))
  (is (= "latest-arm64"
         (:channel (updater-config/updater-options
                    "win32" "arm64"))))
  (is (nil? (:feed-url (updater-config/updater-options
                        "linux" "x64"))))
  (is (nil? (:provider (updater-config/updater-options
                        "linux" "x64")))))
