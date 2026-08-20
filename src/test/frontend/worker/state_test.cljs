(ns frontend.worker.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.worker.platform :as platform]
            [frontend.worker.state :as worker-state]))

(defn- with-online-event
  [value]
  (assoc @worker-state/*state :thread-atom/online-event (atom value)))

(deftest online?-uses-thread-atom-in-non-node-runtime
  (let [state-prev @worker-state/*state]
    (try
      (with-redefs [platform/current (fn []
                                       {:env {:runtime :web}})]
        (reset! worker-state/*state (with-online-event true))
        (testing "web runtime stays compatible with main-thread online-event"
          (is (true? (worker-state/online?))))
        (reset! worker-state/*state (with-online-event false))
        (is (false? (worker-state/online?))))
      (finally
        (reset! worker-state/*state state-prev)))))

(deftest online?-node-runtime-uses-main-thread-online-event-when-available
  (let [state-prev @worker-state/*state]
    (try
      (with-redefs [platform/current (fn []
                                       {:env {:runtime :node}})]
        (reset! worker-state/*state (with-online-event false))
        (testing "desktop worker follows the renderer's network event"
          (is (false? (worker-state/online?))))
        (reset! worker-state/*state (with-online-event nil))
        (testing "node runtime stays online when no network signal exists"
          (is (true? (worker-state/online?)))))
      (finally
        (reset! worker-state/*state state-prev)))))
