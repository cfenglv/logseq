(ns electron.proxy-env-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.proxy-env :as proxy-env]))

(deftest set-worker-proxy-env-populates-standard-env-vars-test
  (testing "the Electron-owned db-worker child must inherit the resolved proxy through standard env vars"
    (let [saved (into {}
                      (map (fn [k]
                             [k (aget js/process.env k)]))
                      ["https_proxy" "http_proxy" "all_proxy" "NO_PROXY"])]
      (try
        (doseq [k ["https_proxy" "http_proxy" "all_proxy" "NO_PROXY"]]
          (js-delete js/process.env k))
        (proxy-env/set-worker-proxy-env!
         {:protocol "http" :host "127.0.0.1" :port 7897})
        (is (= "http://127.0.0.1:7897" (aget js/process.env "https_proxy")))
        (is (= "http://127.0.0.1:7897" (aget js/process.env "http_proxy")))
        (is (= "http://127.0.0.1:7897" (aget js/process.env "all_proxy")))
        (is (= "127.0.0.1,localhost,<local>"
               (aget js/process.env "NO_PROXY")))
        (finally
          (doseq [[k v] saved]
            (if (nil? v)
              (js-delete js/process.env k)
              (aset js/process.env k v))))))))

(deftest set-worker-proxy-env-direct-clears-only-owned-values-test
  (testing "switching to direct must clear the vars this function previously set"
    (let [saved (into {}
                      (map (fn [k]
                             [k (aget js/process.env k)]))
                      ["https_proxy" "http_proxy" "all_proxy"])]
      (try
        (doseq [k ["https_proxy" "http_proxy" "all_proxy"]]
          (js-delete js/process.env k))
        (proxy-env/set-worker-proxy-env!
         {:protocol "http" :host "127.0.0.1" :port 7897})
        (proxy-env/set-worker-proxy-env! nil)
        (is (nil? (aget js/process.env "https_proxy")))
        (is (nil? (aget js/process.env "http_proxy")))
        (is (nil? (aget js/process.env "all_proxy")))
        (finally
          (doseq [[k v] saved]
            (if (nil? v)
              (js-delete js/process.env k)
              (aset js/process.env k v))))))))

(deftest set-worker-proxy-env-never-clobbers-existing-env-test
  (testing "an explicitly configured proxy env must win over the resolved system proxy"
    (let [saved (aget js/process.env "https_proxy")]
      (try
        (aset js/process.env "https_proxy" "http://user-proxy:3128")
        (proxy-env/set-worker-proxy-env!
         {:protocol "http" :host "127.0.0.1" :port 7897})
        (is (= "http://user-proxy:3128"
               (aget js/process.env "https_proxy")))
        (finally
          (if (nil? saved)
            (js-delete js/process.env "https_proxy")
            (aset js/process.env "https_proxy" saved)))))))
