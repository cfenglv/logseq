(ns logseq.db-sync.protocol-test
  (:require [cljs.test :refer [deftest is]]
            [logseq.db-sync.protocol :as protocol]))

(deftest heartbeat-auto-response-payloads-match-wire-format-test
  (is (= "{\"type\":\"ping\"}"
         (protocol/encode-message {:type "ping"})))
  (is (= "{\"type\":\"pong\"}"
         (protocol/encode-message {:type "pong"}))))
