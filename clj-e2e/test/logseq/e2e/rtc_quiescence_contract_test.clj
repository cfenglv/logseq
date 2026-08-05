(ns logseq.e2e.rtc-quiescence-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [logseq.e2e.block :as block]
            [logseq.e2e.const :as const]
            [logseq.e2e.rtc :as rtc]
            [logseq.e2e.rtc-extra-part2-test]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(def ^:private stress-ns 'logseq.e2e.rtc-extra-part2-test)

(defn- stress-var
  [symbol]
  (or (ns-resolve stress-ns symbol)
      (throw (ex-info "missing stress helper" {:symbol symbol}))))

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

(deftest quiescence-validates-both-clients-and-the-complete-state
  (let [valid (two-client-state 17 ["a" "b"])
        tx-paths [[:page1 :rtc-tx :local-tx]
                  [:page1 :rtc-tx :remote-tx]
                  [:page2 :rtc-tx :local-tx]
                  [:page2 :rtc-tx :remote-tx]]]
    (is (true? (rtc/two-client-snapshot-quiescent? valid)))
    (doseq [path tx-paths]
      (is (false? (rtc/two-client-snapshot-quiescent?
                   (assoc-in valid path 17.0)))
          (str "non-integer tx accepted at " path))
      (is (false? (rtc/two-client-snapshot-quiescent?
                   (assoc-in valid path -1)))
          (str "negative tx accepted at " path))
      (is (false? (rtc/two-client-snapshot-quiescent?
                   (assoc-in valid path 18)))
          (str "non-converged tx accepted at " path)))
    (is (false? (rtc/two-client-snapshot-quiescent?
                 (assoc-in valid [:page1 :blocks] nil))))
    (is (false? (rtc/two-client-snapshot-quiescent?
                 (assoc-in valid [:page2 :blocks] nil))))
    (is (false? (rtc/two-client-snapshot-quiescent?
                 (assoc-in valid [:page2 :blocks] ["different"]))))))

(deftest stress-sampler-reads-each-client-once
  (let [events (atom [])
        original-page1 @const/*page1
        original-page2 @const/*page2]
    (try
      (reset! const/*page1 :client-1)
      (reset! const/*page2 :client-2)
      (with-redefs-fn
        {(stress-var 'page-sync-state)
         (fn [client]
           (swap! events conj client)
           {:client client})}
        (fn []
          (is (= {:page1 {:client :client-1}
                  :page2 {:client :client-2}}
                 ((deref (stress-var 'two-page-sync-state)))))))
      (is (= [:client-1 :client-2] @events))
      (finally
        (reset! const/*page1 original-page1)
        (reset! const/*page2 original-page2)))))

(deftest stress-wait-uses-the-production-stability-window
  (let [captured (atom nil)
        state (two-client-state 23 ["settled"])]
    (with-redefs-fn
      {#'rtc/wait-for-stable-state!
       (fn [sample-f stable? options]
         (reset! captured {:sample-f sample-f
                           :stable? stable?
                           :options options})
         state)}
      (fn []
        (is (= state
               ((deref (stress-var 'wait-for-two-pages-quiescent!)))))))
    (is (identical? rtc/two-client-snapshot-quiescent?
                    (:stable? @captured)))
    (is (= {:poll-ms 250
            :stable-ms 3000
            :timeout-ms 30000}
           (:options @captured)))))

(deftest stress-workflow-executes-the-quiescence-wait-before-asserting
  (let [events (atom [])
        state (two-client-state 31 ["complete"])
        original-page1 @const/*page1
        original-page2 @const/*page2]
    (try
      (reset! const/*page1 :client-1)
      (reset! const/*page2 :client-2)
      (with-redefs-fn
        {(stress-var 'env-int) (fn [_ default] default)
         (stress-var 'seed-long-nested-page!) (fn [_] #{})
         (stress-var 'run-two-clients-in-parallel!)
         (fn [client1-f client2-f]
           [(client1-f) (client2-f)])
         (stress-var 'local-random-edit-batch!)
         (fn [& _] 1)
         (stress-var 'local-undo-redo-batch!) (fn [_])
         #'rtc/get-rtc-tx (fn [] {:local-tx 31 :remote-tx 31})
         (stress-var 'sync-by-barrier!)
         (fn [& _] (swap! events conj :barrier))
         (stress-var 'wait-for-two-pages-quiescent!)
         (fn []
           (swap! events conj :wait)
           state)
         (stress-var 'assert-two-pages-synced!)
         (fn [& [observed]]
           (swap! events conj [:assert observed]))
         (stress-var 'assert-no-severe-sync-errors!)
         (fn [] (swap! events conj :logs))}
        (fn []
          ((deref (stress-var 'online-two-clients-undo-redo-stress-test)))))
      (is (= [:barrier :wait [:assert state] :logs]
             @events))
      (finally
        (reset! const/*page1 original-page1)
        (reset! const/*page2 original-page2)))))

(deftest stable-window-holds-for-the-exact-configured-duration
  (let [now (atom 0)
        samples (atom [])
        waits (atom [])
        state (two-client-state 29 ["stable"])
        result (rtc/wait-for-stable-state!
                (fn []
                  (swap! samples conj @now)
                  state)
                rtc/two-client-snapshot-quiescent?
                {:now-ms-f #(deref now)
                 :poll-ms 100
                 :stable-ms 300
                 :timeout-ms 1000
                 :wait-ms-f (fn [duration]
                              (swap! waits conj duration)
                              (swap! now + duration))})]
    (is (= state result))
    (is (= [0 100 200 300] @samples))
    (is (= [100 100 100] @waits))))

(deftest stable-window-propagates-sampler-errors-unchanged
  (let [failure (ex-info "sample failed" {:phase :sample})
        thrown (try
                 (rtc/wait-for-stable-state!
                  #(throw failure)
                  rtc/two-client-snapshot-quiescent?
                  {:now-ms-f (constantly 0)
                   :poll-ms 100
                   :stable-ms 300
                   :timeout-ms 1000
                   :wait-ms-f (fn [_])})
                 nil
                 (catch Throwable error
                   error))]
    (is (identical? failure thrown))))

(defn- barrier-marker-title
  [values]
  (some #(when (and (string? %)
                    (re-matches #"sync-(?:trigger|ack)-.+" %))
           %)
        (tree-seq coll? seq values)))

(defn- run-editorless-barrier-contract
  [barrier-f]
  (let [events (atom [])
        transactions (atom {:client-1 0 :client-2 0})
        no-editor (ex-info "no editor wrapper" {:phase :editorless})
        original-page1 @const/*page1
        original-page2 @const/*page2]
    (try
      (reset! const/*page1 :client-1)
      (reset! const/*page2 :client-2)
      {:events events
       :thrown
       (with-redefs-fn
         {#'block/new-block
          (fn [title & _]
            (swap! events conj [:dom-write w/*page* title])
            (throw no-editor))
          (stress-var 'ls-api-call!)
          (fn [operation & args]
            (let [title (barrier-marker-title args)]
              (swap! events conj [:client-write w/*page* operation title])
              (when-not title
                (throw (ex-info "client write omitted barrier marker"
                                {:operation operation
                                 :args args})))
              (swap! transactions update w/*page* inc)
              {:uuid (str (name w/*page*) "-" title)}))
          (stress-var 'page-has-block-title?) (constantly false)
          #'block/open-last-block
          (fn [& _]
            (swap! events conj [:editor-retry w/*page*]))
          #'rtc/get-rtc-tx
          (fn []
            (let [tx (get @transactions w/*page* 0)]
              {:local-tx tx :remote-tx tx}))
          #'rtc/wait-tx-update-to
          (fn [target]
            (swap! events conj [:observe w/*page* target])
            (swap! transactions assoc w/*page* target)
            target)
          #'util/exit-edit
          (fn []
            (swap! events conj [:exit-edit w/*page*]))
          #'util/wait-timeout (fn [_])
          #'w/wait-for (fn [& _])
          #'clojure.core/prn (fn [& _])}
         (fn []
           (try
             (barrier-f)
             nil
             (catch Throwable error
               error))))}
      (finally
        (reset! const/*page1 original-page1)
        (reset! const/*page2 original-page2)))))

(deftest editorless-client-write-event-log-control
  (let [{:keys [events thrown]}
        (run-editorless-barrier-contract
         (fn []
           (w/with-page @const/*page1
             ((deref (stress-var 'ls-api-call!))
              :editor.appendBlockInPage
              "contract-page"
              "sync-trigger-control"))
           (doseq [page [@const/*page1 @const/*page2]]
             (w/with-page page
               (rtc/wait-tx-update-to 1)))
           (w/with-page @const/*page2
             ((deref (stress-var 'ls-api-call!))
              :editor.appendBlockInPage
              "contract-page"
              "sync-ack-control"))
           (doseq [page [@const/*page1 @const/*page2]]
             (w/with-page page
               (rtc/wait-tx-update-to 2)))))]
    (is (nil? thrown))
    (is (= [[:client-write :client-1
             :editor.appendBlockInPage "sync-trigger-control"]
            [:observe :client-1 1]
            [:observe :client-2 1]
            [:client-write :client-2
             :editor.appendBlockInPage "sync-ack-control"]
            [:observe :client-1 2]
            [:observe :client-2 2]]
           @events))))

(deftest editorless-barrier-uses-one-client-transaction-per-marker
  (let [{:keys [events thrown]}
        (run-editorless-barrier-contract
         #((deref (stress-var 'sync-by-barrier!)) "editorless"))
        client-writes (filterv #(= :client-write (first %)) @events)
        dom-writes (filterv #(= :dom-write (first %)) @events)
        observations (filterv #(= :observe (first %)) @events)
        expected-writes [[:client-1 "sync-trigger-editorless"]
                         [:client-2 "sync-ack-editorless"]]
        actual-writes (mapv (fn [[_ page _operation title]]
                              [page title])
                            client-writes)
        operations (mapv #(nth % 2) client-writes)
        mismatch (cond-> {}
                   (some? thrown)
                   (assoc :thrown (ex-message thrown))

                   (not= expected-writes actual-writes)
                   (assoc :client-writes client-writes)

                   (not (every? #{:editor.appendBlockInPage
                                  :editor.insertBlock}
                                operations))
                   (assoc :operations operations)

                   (seq dom-writes)
                   (assoc :dom-writes dom-writes)

                   (not= [[:observe :client-1 1]
                          [:observe :client-2 1]
                          [:observe :client-1 2]
                          [:observe :client-2 2]]
                         observations)
                   (assoc :observations observations))]
    (is (empty? mismatch)
        (str "editorless barrier did not use one durable client write per marker: "
             (pr-str mismatch)
             " events=" (pr-str @events)))))

(defn- run-barrier-with-write-failure
  [failing-title]
  (let [failure (ex-info "barrier write failed" {:title failing-title})
        events (atom [])
        transactions (atom {:client-1 0 :client-2 0})
        next-tx (atom 0)
        original-page1 @const/*page1
        original-page2 @const/*page2]
    (try
      (reset! const/*page1 :client-1)
      (reset! const/*page2 :client-2)
      {:events events
       :failure failure
       :thrown
       (with-redefs-fn
         {#'block/new-block
          (fn [title & _]
            (swap! events conj [:write w/*page* title])
            (if (= title failing-title)
              (throw failure)
              (let [tx (swap! next-tx inc)]
                (swap! transactions assoc w/*page* tx))))
          (stress-var 'ls-api-call!)
          (fn [_operation & args]
            (let [title (barrier-marker-title args)]
              (swap! events conj [:write w/*page* title])
              (if (= title failing-title)
                (throw failure)
                (let [tx (swap! next-tx inc)]
                  (swap! transactions assoc w/*page* tx)))))
          (stress-var 'page-has-block-title?) (constantly true)
          #'block/open-last-block (fn [])
          #'rtc/get-rtc-tx
          (fn []
            (let [tx (get @transactions w/*page* 0)]
              {:local-tx tx :remote-tx tx}))
          #'rtc/wait-tx-update-to
          (fn [target]
            (swap! events conj [:wait w/*page* target])
            target)
          #'util/exit-edit (fn [])
          #'util/wait-timeout (fn [_])
          #'w/wait-for (fn [& _])
          #'clojure.core/prn (fn [& _])}
         (fn []
           (try
             ((deref (stress-var 'sync-by-barrier!)) "contract")
             nil
             (catch Throwable error
               error))))}
      (finally
        (reset! const/*page1 original-page1)
        (reset! const/*page2 original-page2)))))

(deftest marker-and-ack-write-errors-fail-the-barrier-directly
  (doseq [title ["sync-trigger-contract" "sync-ack-contract"]]
    (testing title
      (let [{:keys [events failure thrown]}
            (run-barrier-with-write-failure title)
            mismatch (when-not (identical? failure thrown)
                       {:events @events
                        :expected (ex-message failure)
                        :thrown (some-> thrown ex-message)})]
        (is (nil? mismatch)
            (str "barrier replaced or swallowed the write failure: "
                 (pr-str mismatch)))))))
