(ns electron.home-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.home :as home]))

(deftest resolve-root-test
  (testing "production keeps the Electron home path"
    (is (= "/real/home" (home/resolve-root nil "/real/home")))
    (is (= "/real/home" (home/resolve-root "" "/real/home")))
    (is (= "/real/home" (home/resolve-root "  " "/real/home"))))
  (testing "an explicit qualification root isolates every desktop home consumer"
    (is (= "/tmp/selfhost6/home"
           (home/resolve-root "/tmp/selfhost6/home" "/real/home")))))
