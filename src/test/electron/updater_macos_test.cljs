(ns electron.updater-macos-test
  (:require [cljs.test :refer [deftest is]]
            [electron.updater-macos :as updater-macos]))

(deftest executable-path-resolves-the-containing-app-bundle
  (is (= "/Applications/Logseq.app"
         (updater-macos/app-bundle-path
          "/Applications/Logseq.app/Contents/MacOS/Logseq"))))
