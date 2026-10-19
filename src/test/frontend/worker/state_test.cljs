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

(deftest online?-treats-uninitialized-thread-online-event-as-online
  (let [state-prev @worker-state/*state]
    (try
      (with-redefs [platform/current (fn []
                                       {:env {:runtime :web}})]
        (reset! worker-state/*state (with-online-event nil))
        (testing "an uninitialized mobile network status should not block sync uploads"
          (is (true? (worker-state/online?)))))
      (finally
        (reset! worker-state/*state state-prev)))))

(deftest online?-node-runtime-does-not-require-main-thread-online-event
  (let [state-prev @worker-state/*state]
    (try
      (with-redefs [platform/current (fn []
                                       {:env {:runtime :node}})]
        (reset! worker-state/*state (with-online-event nil))
        (testing "node runtime should provide its own online detection"
          (is (true? (worker-state/online?)))))
      (finally
        (reset! worker-state/*state state-prev)))))

(deftest projection-epoch-is-one-in-memory-scalar-per-open-graph-test
  (let [state-prev @worker-state/*state
        repo "projection-epoch-test"]
    (try
      (is (zero? (worker-state/get-projection-epoch repo)))
      (is (= 7 (worker-state/set-projection-epoch! repo 7)))
      (is (= 7 (worker-state/get-projection-epoch repo)))
      (worker-state/clear-projection-epoch! repo)
      (is (zero? (worker-state/get-projection-epoch repo)))
      (is (thrown? js/Error
                   (worker-state/set-projection-epoch! repo -1)))
      (finally
        (reset! worker-state/*state state-prev)))))
