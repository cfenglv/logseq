(ns logseq.e2e.rtc-quiescence-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [logseq.e2e.rtc :as rtc]))

(defn- synced-client
  [tx blocks]
  {:blocks blocks
   :rtc-tx {:local-tx tx
            :remote-tx tx}})

(defn- two-client-state
  ([tx blocks]
   (two-client-state tx blocks tx blocks))
  ([p1-tx p1-blocks p2-tx p2-blocks]
   {:page1 (synced-client p1-tx p1-blocks)
    :page2 (synced-client p2-tx p2-blocks)}))

(defn- scripted-runtime
  [states]
  (let [remaining (atom (seq states))
        last-state (atom nil)
        calls (atom 0)
        now-ms (atom 0)]
    {:calls calls
     :now-ms-f #(deref now-ms)
     :sample-f (fn []
                 (swap! calls inc)
                 (if-let [state (first @remaining)]
                   (do
                     (swap! remaining next)
                     (reset! last-state state)
                     state)
                   @last-state))
     :wait-ms-f #(swap! now-ms + %)}))

(defn- wait-script!
  [states & [options]]
  (let [{:keys [calls now-ms-f sample-f wait-ms-f]}
        (scripted-runtime states)
        result (rtc/wait-for-stable-state!
                sample-f
                rtc/two-client-snapshot-quiescent?
                (merge {:now-ms-f now-ms-f
                        :poll-ms 100
                        :stable-ms 200
                        :timeout-ms 1000
                        :wait-ms-f wait-ms-f}
                       options))]
    {:calls @calls
     :result result}))

(deftest stable-quiescence-rejects-the-baseline-one-shot-gap
  (testing "an initially synced snapshot is not proof against a late transaction"
    (let [initial (two-client-state 301 ["sync-trigger-0"])
          late-divergence (two-client-state
                           303 ["p1-r0-op48-nest" "sync-trigger-0"]
                           301 ["" "sync-trigger-0"])
          final (two-client-state
                 303 ["p1-r0-op48-nest" "sync-trigger-0"])
          {:keys [calls result]}
          (wait-script! [initial late-divergence final final final])]
      (is (true? (rtc/two-client-snapshot-quiescent? initial))
          "the baseline one-shot assertion would accept the first snapshot")
      (is (= 5 calls)
          "the stable gate must observe through the late transaction")
      (is (= final result)))))

(deftest stable-quiescence-rejects-a-marker-overtaken-by-the-other-client
  (testing "a later client transaction resets an apparently stable marker"
    (let [at-first-marker (two-client-state 301 ["sync-trigger-0"])
          first-client-late (two-client-state 303 ["p1-late"] 301 [])
          at-first-client (two-client-state 303 ["p1-late"])
          second-client-late (two-client-state 303 ["p1-late"] 305 ["p2-late"])
          final (two-client-state 305 ["p1-late" "p2-late"])
          {:keys [calls result]}
          (wait-script! [at-first-marker
                         first-client-late
                         at-first-client
                         second-client-late
                         final
                         final
                         final])]
      (is (= 7 calls)
          "neither the first marker nor the first client's later tx is final")
      (is (= final result)))))

(deftest stable-quiescence-times-out-fail-closed
  (testing "permanent divergence throws with the last observed state"
    (let [diverged (two-client-state 303 ["p1-late"] 305 ["p2-late"])
          runtime (scripted-runtime [diverged])
          thrown (try
                   (rtc/wait-for-stable-state!
                    (:sample-f runtime)
                    rtc/two-client-snapshot-quiescent?
                    {:now-ms-f (:now-ms-f runtime)
                     :poll-ms 100
                     :stable-ms 200
                     :timeout-ms 300
                     :wait-ms-f (:wait-ms-f runtime)})
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     error))]
      (is (some? thrown))
      (is (= 300 (:timeout-ms (ex-data thrown))))
      (is (= diverged (:last-state (ex-data thrown)))))))
