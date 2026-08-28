(ns logseq.e2e.custom-report-basic-test
  (:require [clojure.test :refer [deftest is testing]]
            [logseq.e2e.custom-report :as custom-report]))

(deftest sanitize-console-message-test
  (testing "leaves ordinary console output unchanged"
    (is (= "sync complete"
           (custom-report/sanitize-console-message "sync complete" nil))))

  (testing "redacts the configured URL and derived websocket host"
    (let [server-url "https://rtc.example.invalid/private-base"
          message (str "POST " server-url "/sync failed; "
                       "WebSocket wss://rtc.example.invalid/socket failed")
          sanitized (custom-report/sanitize-console-message message server-url)]
      (is (not (re-find #"rtc\.example\.invalid|private-base" sanitized)))
      (is (= 2 (count (re-seq #"\[REDACTED_SYNC_SERVER\]" sanitized))))))

  (testing "redacts an authority containing a non-default port"
    (is (= "connection to https://[REDACTED_SYNC_SERVER]/socket failed"
           (custom-report/sanitize-console-message
            "connection to https://10.0.0.8:8787/socket failed"
            "http://10.0.0.8:8787/api")))))
