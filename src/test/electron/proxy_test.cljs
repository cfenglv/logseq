(ns electron.proxy-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.proxy :as proxy]))

(deftest select-pac-route-test
  (testing "uses the first route in a Windows-style fallback chain"
    (is (= {:protocol "http"
            :host "127.0.0.1"
            :port "7890"}
           (proxy/select-pac-route "PROXY 127.0.0.1:7890; DIRECT"))))
  (testing "preserves a DIRECT-first PAC result"
    (is (= {:protocol "direct"}
           (proxy/select-pac-route "DIRECT; PROXY 127.0.0.1:7890"))))
  (testing "rejects the entire result when a clause is unsupported"
    (try
      (proxy/select-pac-route "UNKNOWN proxy.example:8080; DIRECT")
      (is false "unsupported PAC result should throw")
      (catch :default error
        (is (= :unsupported-system-proxy
               (:code (ex-data error))))))))
