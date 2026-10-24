(ns frontend.db.subs-loader-test
  (:require [cljs.test :refer [deftest is]]
            [frontend.db.subs-loader :as subs-loader]))

(defn- reset-loader! []
  (reset! @#'subs-loader/*batch {})
  (reset! @#'subs-loader/*flushing? false)
  (subs-loader/set-still-wanted-fn! (constantly true)))

(deftest request-groups-flushes-block-trees-before-resources-test
  (let [view-uuid (random-uuid)
        block-uuid (random-uuid)
        parent-uuid (random-uuid)
        page-uuid (random-uuid)
        entries [{:slot-key [:children parent-uuid]}
                 {:slot-key [:block block-uuid]}
                 {:slot-key [:resource [:block-ref-count page-uuid]]}
                 {:slot-key [:resource [:view-data view-uuid {}]]}
                 {:slot-key [:resource [:views page-uuid :class-objects]]}]
        groups (#'subs-loader/request-groups entries)
        resource-kinds (fn [group]
                         (into #{}
                               (keep (fn [{:keys [slot-key]}]
                                       (when (= :resource (first slot-key))
                                         (first (second slot-key)))))
                               group))]
    (is (= 4 (count groups)))
    (is (= #{:view-data :views} (resource-kinds (nth groups 0)))
        "Tags/All Pages view-data must leave before leftover resources.")
    (is (= [[:block block-uuid]] (mapv :slot-key (nth groups 1)))
        "An open page's blocks must not wait behind title decorations.")
    (is (= [[:children parent-uuid]] (mapv :slot-key (nth groups 2)))
        "An open page's children must not wait behind title decorations.")
    (is (= #{:block-ref-count} (resource-kinds (nth groups 3)))
        "block-ref-count must not share the first table window request.")))

(deftest loader-prioritizes-page-identity-before-ordinary-resources-test
  (reset-loader!)
  (let [graph-id "graph"
        ordinary-slots (mapv (fn [_] [:resource [:block-ref-count (random-uuid)]])
                             (range 500))
        identity-slots [[:resource [:page-identity "Synthetic identity page"]]
                        [:resource [:page-identity (random-uuid)]]]]
    (try
      (doseq [slot-key (concat ordinary-slots identity-slots)]
        (subs-loader/load! graph-id slot-key (fn [_])))
      (let [first-wave (#'subs-loader/take-next-request-group!)]
        (is (= 2 (count first-wave)))
        (is (= (set identity-slots) (set (map :slot-key first-wave)))
            "Page identity must leave before ordinary resources can delay the view owner.")
        (is (let [queued-slots (set (map :slot-key (vals @@#'subs-loader/*batch)))]
              (= (set ordinary-slots) queued-slots))
            "Prioritizing identities must retain every ordinary resource."))
      (let [second-wave (#'subs-loader/take-next-request-group!)]
        (is (= 25 (count second-wave)))
        (is (every? (set ordinary-slots) (map :slot-key second-wave)))
        (is (= 475 (count @@#'subs-loader/*batch))))
      (finally
        (reset-loader!)))))

(deftest loader-takes-one-request-group-at-a-time-test
  (reset-loader!)
  (let [graph-id "graph"
        resource-keys (mapv (fn [idx] [:page-identity (str "page-" idx)])
                            (range 26))]
    (doseq [resource-key resource-keys]
      (subs-loader/load! graph-id [:resource resource-key] (fn [_])))
    (let [first-wave (#'subs-loader/take-next-request-group!)
          second-wave (#'subs-loader/take-next-request-group!)]
      (is (= 25 (count first-wave))
          "A flush should take one bounded resource wave, not everything queued.")
      (is (= 1 (count second-wave))
          "The remaining queue stays in the renderer for the next wave."))
    (reset-loader!)))

(deftest loader-drops-unmounted-slots-before-worker-request-test
  (reset-loader!)
  (let [graph-id "graph"
        stale-block (random-uuid)
        visible-block (random-uuid)]
    (subs-loader/set-still-wanted-fn!
     (fn [slot-key]
       (not= slot-key [:block stale-block])))
    (subs-loader/load! graph-id [:block stale-block] (fn [_]))
    (subs-loader/load! graph-id [:block visible-block] (fn [_]))
    (let [wave (#'subs-loader/take-next-request-group!)]
      (is (= [[:block visible-block]]
             (mapv :slot-key wave))
          "Rows that scrolled past should be rejected before they enter the worker queue."))
    (reset-loader!)))
