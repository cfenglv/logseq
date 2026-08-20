(ns logseq.e2e.rtc
  (:require [clojure.edn :as edn]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.const :refer [*page1 *page2]]
            [logseq.e2e.graph :as graph]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(defn get-rtc-tx
  []
  (let [loc (w/get-by-test-id "rtc-tx")]
    (edn/read-string (w/text-content loc))))

(defmacro with-wait-tx-updated
  "exec body, then wait for the rtc-tx update.
  Return the updated rtc-tx{:local-tx ..., :remote-tx ...}"
  [& body]
  `(let [m# (get-rtc-tx)
         local-tx# (or (:local-tx m#) 0)
         remote-tx# (or (:remote-tx m#) 0)
         _# (prn :current-rtc-tx m# local-tx# remote-tx#)
         tx# (max local-tx# remote-tx#)]
     ~@body
     (loop [i# 15]
       (when (zero? i#) (throw (ex-info "wait-tx-updated failed" {:old m# :new (get-rtc-tx)})))
       (util/wait-timeout 500)
       (w/wait-for "button.cloud.on.idle" {:timeout 35000})
       (util/wait-timeout 1000)
       (let [new-m# (get-rtc-tx)
             new-local-tx# (or (:local-tx new-m#) 0)
             new-remote-tx# (or (:remote-tx new-m#) 0)]
         (if (and (= new-local-tx# new-remote-tx#)
                  (> new-local-tx# tx#))
           (do (prn :new-rtc-tx new-m#)
               {:local-tx new-local-tx# :remote-tx new-remote-tx#})
           (do (prn :current-rtc-tx new-m#)
               (recur (dec i#))))))))

(defn wait-current-tx-synced
  "Wait until the current RTC client has no transaction in flight.

  The initial delay prevents an already-visible idle indicator from satisfying
  the wait before a just-issued local transaction reaches the sync worker."
  []
  (util/wait-timeout 500)
  (loop [i 15
         previous nil]
    (when (zero? i)
      (throw
       (ex-info
        "wait-current-tx-synced failed"
        {:current (get-rtc-tx)})))
    (w/wait-for "button.cloud.on.idle" {:timeout 35000})
    (util/wait-timeout 500)
    (let [{:keys [local-tx remote-tx] :as current} (get-rtc-tx)
          local-tx (or local-tx 0)
          remote-tx (or remote-tx 0)]
      (if (and (= local-tx remote-tx)
               (= previous current))
        current
        (recur (dec i) current)))))

(defn two-client-snapshot-quiescent?
  [{:keys [page1 page2]}]
  (let [page1-tx (:rtc-tx page1)
        page2-tx (:rtc-tx page2)
        tx-values [(:local-tx page1-tx)
                   (:remote-tx page1-tx)
                   (:local-tx page2-tx)
                   (:remote-tx page2-tx)]]
    (and (every? #(and (integer? %) (not (neg? %))) tx-values)
         (apply = tx-values)
         (some? (:blocks page1))
         (= (:blocks page1) (:blocks page2)))))

(defn wait-for-stable-state!
  "Poll `sample-f` until `stable?` accepts the exact same state for a full
  stable window. Any changing or rejected state resets the window. Timeout and
  sampler errors are propagated so E2E reporting can preserve diagnostics."
  [sample-f stable? {:keys [now-ms-f poll-ms stable-ms timeout-ms wait-ms-f]
                     :or {now-ms-f #(quot (System/nanoTime) 1000000)
                          poll-ms 250
                          stable-ms 3000
                          timeout-ms 30000
                          wait-ms-f #(Thread/sleep %)}}]
  (when-not (and (ifn? sample-f)
                 (ifn? stable?)
                 (ifn? now-ms-f)
                 (ifn? wait-ms-f)
                 (pos-int? poll-ms)
                 (pos-int? stable-ms)
                 (pos-int? timeout-ms)
                 (<= stable-ms timeout-ms))
    (throw (ex-info "invalid stable-state wait options"
                    {:poll-ms poll-ms
                     :stable-ms stable-ms
                     :timeout-ms timeout-ms})))
  (let [started-at (now-ms-f)
        deadline (+ started-at timeout-ms)]
    (loop [previous-state ::none
           stable-since nil
           recent-states []]
      (let [state (sample-f)
            sampled-at (now-ms-f)
            state-stable? (true? (stable? state))
            same-stable-state? (and state-stable?
                                    (= previous-state state))
            stable-since' (cond
                            same-stable-state? stable-since
                            state-stable? sampled-at
                            :else nil)
            recent-states' (->> (conj recent-states state)
                                (take-last 6)
                                vec)]
        (cond
          (and stable-since'
               (>= (- sampled-at stable-since') stable-ms))
          state

          (>= sampled-at deadline)
          (throw (ex-info "stable-state wait timed out"
                          {:last-state state
                           :recent-states recent-states'
                           :stable-ms stable-ms
                           :timeout-ms timeout-ms}))

          :else
          (do
            (wait-ms-f (min poll-ms (- deadline sampled-at)))
            (recur state stable-since' recent-states')))))))

(defn wait-tx-update-to
  [new-tx]
  (assert (int? new-tx))
  (loop [i 5]
    (when (zero? i) (throw (ex-info "wait-tx-update-to" {:update-to new-tx})))
    (util/wait-timeout 1000)
    (let [m (get-rtc-tx)
          local-tx (or (:local-tx m) 0)
          ;; remote-tx (or (:remote-tx m) 0)
          ]
      (if (>= local-tx new-tx)
        local-tx
        (recur (dec i))))))

(defn rtc-start
  []
  (util/search-and-click "(Dev) RTC Start"))

(defn rtc-stop
  []
  (util/search-and-click "(Dev) RTC Stop"))

(defmacro with-stop-restart-rtc
  "- rtc stop on `stop-pw-pages` in order
  - run `body`
  - rtc start and exec `after-start-body` in order"
  [stop-pw-pages start-pw-page+after-start-body & body]
  (let [after-body
        (cons
         'do
         (for [[p body] (partition 2 start-pw-page+after-start-body)]
           `(w/with-page ~p
              (rtc-start)
              ~body)))]
    `(do
       (doseq [p# ~stop-pw-pages]
         (w/with-page p# (rtc-stop)))
       ~@body
       ~after-body)))

(defn validate-graphs-in-2-pw-pages
  []
  (let [[p1-summary p2-summary]
        (map
         (fn [p]
           (w/with-page p
             (graph/validate-graph)))
         [@*page1 @*page2])]
    (assert/assert-graph-summary-equal p1-summary p2-summary)))
