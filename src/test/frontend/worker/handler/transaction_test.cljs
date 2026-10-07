(ns frontend.worker.handler.transaction-test
  (:require [cljs.test :refer [deftest is]]
            [datascript.core :as d]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.db-listener :as db-listener]
            [frontend.worker.handler.block :as block-handler]
            [frontend.worker.handler.transaction :as transaction]
            [frontend.worker.state :as worker-state]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.op :as outliner-op]
            [logseq.outliner.recycle :as recycle]))

(deftest automatic-recycle-gc-respects-remote-graph-boundary-test
  (doseq [remote? [true false]]
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "retained-page"}
                  :blocks [{:block/title "retained-text"
                            :build/properties {:default "retained-property"}
                            :build/children [{:block/title "retained-child"}]}]}])
          page (ldb/get-page @conn "retained-page")
          page-id (:db/id page)
          reports (atom [])]
      (d/transact! conn [{:db/ident :logseq.kv/graph-remote? :kv/value remote?}])
      (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {:now-ms 0})
                     {:outliner-op :delete-page})
      (let [before @conn]
        (d/listen! conn ::gc #(swap! reports conj %))
        (with-redefs [common-util/time-ms (constantly (* 31 24 3600 1000))]
          (transaction/maybe-run-recycle-gc! conn))
        (if remote?
          (do
            (is (true? (identical? before @conn))
                "Automatic GC must not mutate even an offline remote graph.")
            (is (zero? (count @reports)))
            (is (some? (d/entity @conn page-id)))
            (is (nil? (d/entity @conn :logseq.kv/recycle-last-gc-at))))
          (do
            (is (nil? (d/entity @conn page-id)))
            (is (= (* 31 24 3600 1000)
                   (:kv/value (d/entity @conn :logseq.kv/recycle-last-gc-at)))))))
      (d/unlisten! conn ::gc))))

(deftest apply-outliner-ops-returns-stored-delta-and-canonical-editor-rows-test
  (let [repo "transaction-handler-test"
        conn (d/create-conn)
        perf-id #uuid "11111111-1111-1111-1111-111111111111"
        first-row-uuid #uuid "22222222-2222-2222-2222-222222222222"
        second-row-uuid #uuid "33333333-3333-3333-3333-333333333333"
        editor-row-uuids [second-row-uuid first-row-uuid]
        first-row {:block/uuid first-row-uuid :block/tx-id 6}
        second-row {:block/uuid second-row-uuid :block/tx-id 7}
        editor-rows {first-row-uuid first-row
                     second-row-uuid second-row}
        delta {:graph-id repo
               :rev 7
               :blocks {}
               :deleted {}
               :children {}
               :affected-keys #{[:graph]}}
        result {:cursor-position 3}
        previous-conns @worker-state/*datascript-conns
        taken-perf-ids (atom [])
        canonical-calls (atom [])
        hydration-calls (atom 0)]
    (try
      (reset! worker-state/*datascript-conns {repo conn})
      (with-redefs [outliner-op/apply-ops! (fn [_conn _ops _opts]
                                             result)
                    db-listener/take-outliner-op-delta!
                    (fn [requested-perf-id]
                      (swap! taken-perf-ids conj requested-perf-id)
                      delta)
                    db-listener/take-outliner-op-perf! (fn [_perf-id] nil)
                    block-handler/canonical-blocks
                    (fn [db row-uuids]
                      (swap! canonical-calls conj [db row-uuids])
                      {:basis-rev 7 :blocks editor-rows})
                    block-handler/get-block-and-children
                    (fn [& _args]
                      (swap! hydration-calls inc)
                      nil)]
        (let [apply-ops! (get @thread-api/*thread-apis
                              :thread-api/apply-outliner-ops)
              response (apply-ops! repo
                                   [[:save-block []]]
                                   {:affected-block-uuids #{}
                                    :editor-row-uuids editor-row-uuids
                                    :ui/perf-id perf-id})]
          (is (= [perf-id] @taken-perf-ids))
          (is (= result (:result response)))
          (is (identical? delta (:delta response))
              "The direct response must return the delta stored by the database listener.")
          (is (= editor-row-uuids (:editor-row-uuids response))
              "Editor row UUIDs must retain operation order.")
          (is (= editor-rows (:editor-rows response))
              "Editor rows are a complete canonical response, not a subset of the render delta.")
          (is (= [[@conn editor-row-uuids]] @canonical-calls))
          (is (zero? @hydration-calls)
              "The origin handler must not reconstruct updated block payloads.")
          (is (not-any? #(contains? response %)
                        [:affected-page-uuids
                         :deleted-block-uuids
                         :entity-updated-block-uuids
                         :render-invalidated-block-uuids
                         :structural-parent-uuids
                         :updated-blocks]))))
      (finally
        (reset! worker-state/*datascript-conns previous-conns)))))
