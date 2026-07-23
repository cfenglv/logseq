(ns logseq.db-sync.protocol-test
  (:require [cljs.test :refer [deftest is]]
            [logseq.db-sync.protocol :as protocol]))

(deftest heartbeat-auto-response-payloads-match-wire-format-test
  (is (= "{\"type\":\"ping\"}"
         (protocol/encode-message {:type "ping"})))
  (is (= "{\"type\":\"pong\"}"
         (protocol/encode-message {:type "pong"}))))

(deftest legacy-v1-wire-messages-round-trip-test
  (doseq [raw ["{\"type\":\"hello\",\"client\":\"legacy-repo\"}"
               "{\"type\":\"pull\",\"since\":0}"
               "{\"type\":\"tx/batch\",\"t-before\":0,\"txs\":[{\"tx\":\"legacy-transit\"}]}"
               "{\"type\":\"hello\",\"t\":0}"
               "{\"type\":\"pull/ok\",\"t\":0,\"txs\":[]}"
               "{\"type\":\"tx/batch/ok\",\"t\":0}"]]
    (let [message (protocol/parse-message raw)]
      (is (map? message))
      (is (= message
             (protocol/parse-message
              (protocol/encode-message message)))))))
