(ns logseq.db.frontend.malli-schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [malli.core :as m]
            [logseq.db.frontend.malli-schema :as db-malli-schema]))

(deftest registered-system-kv-may-retain-ident-without-value-test
  (testing "registered system KV dispatch is narrow and permits authoritative ident-only state"
    (is (= :system-kv
           (db-malli-schema/entity-dispatch-key
            nil {:db/ident :logseq.kv/graph-created-at})))
    (is (m/validate db-malli-schema/Data
                    {:db/ident :logseq.kv/graph-created-at})))
  (testing "unknown db idents keep the existing key-value contract"
    (is (= :db-ident-key-value
           (db-malli-schema/entity-dispatch-key
            nil {:db/ident :logseq.kv/not-registered})))
    (is (not (m/validate db-malli-schema/Data
                         {:db/ident :logseq.kv/not-registered})))
    (is (m/validate db-malli-schema/Data
                    {:db/ident :logseq.kv/not-registered
                     :kv/value "still-required"}))))
