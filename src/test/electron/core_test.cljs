(ns electron.core-test
  (:require [cljs.test :refer [async deftest is]]
            [electron.lifecycle :as lifecycle]
            [promesa.core :as p]))

(deftest start-waits-for-asynchronous-teardown
  (async done
    (let [events (atom [])
          teardown-done (p/deferred)
          lifecycle-op (volatile! (p/resolved nil))]
      (lifecycle/enqueue! lifecycle-op
                          #(do
                             (swap! events conj :teardown)
                             teardown-done))
      (lifecycle/enqueue! lifecycle-op #(swap! events conj :setup))
      (-> (p/let [_ (p/delay 0)]
            (is (= [:teardown] @events))
            (p/resolve! teardown-done true)
            (p/delay 0))
          (p/then (fn [_]
                    (is (= [:teardown :setup] @events))))
          (p/finally (fn []
                       (p/resolve! teardown-done true)
                       (done)))))))

(deftest start-continues-after-teardown-failure
  (async done
    (let [events (atom [])
          lifecycle-op (volatile! (p/resolved nil))]
      (lifecycle/enqueue! lifecycle-op
                          #(do
                             (swap! events conj :teardown)
                             (p/rejected (ex-info "teardown failed" {}))))
      (lifecycle/enqueue! lifecycle-op #(swap! events conj :setup))
      (-> (p/let [_ (p/delay 0)]
            (is (= [:teardown :setup] @events)))
          (p/finally (fn []
                       (done)))))))

(deftest quit-waits-for-workers-and-does-not-repeat-the-stop
  (async done
    (let [pending (p/deferred)
          quitting? (atom false)
          prevented (atom 0)
          stopped (atom 0)
          quits (atom 0)
          event #js {:preventDefault #(swap! prevented inc)}
          handler (lifecycle/quit-handler
                   {:quitting? quitting?
                    :update-prepared? (constantly false)
                    :stop! #(do (swap! stopped inc) pending)
                    :quit! #(swap! quits inc)
                    :on-error! #(is false (str %))})]
      (handler event)
      (handler event)
      (-> (p/let [_ (p/delay 0)
                  _ (is (= 1 @stopped))
                  _ (is (zero? @quits))
                  _ (p/resolve! pending true)
                  _ (p/delay 0)]
            (is (= 1 @quits))
            (handler event)
            (is (= 2 @prevented) "The final Electron quit is allowed, not intercepted again.")
            (is (= 1 @stopped)))
          (p/finally (fn [] (p/resolve! pending true) (done)))))))

(deftest verified-update-quit-is-not-intercepted-again
  (let [quitting? (atom false)
        handler (lifecycle/quit-handler
                 {:quitting? quitting?
                  :update-prepared? (constantly true)
                  :stop! #(is false "Updater already stopped its workers.")
                  :quit! #(is false "Let the original updater quit proceed.")
                  :on-error! #(is false (str %))})]
    (handler #js {:preventDefault #(is false "Do not cancel the updater's quit.")})
    (is @quitting?)))

(deftest failed-worker-stop-does-not-report-a-successful-quit
  (async done
    (let [error (ex-info "synthetic stop failed" {})
          quitting? (atom false)
          errors (atom [])
          handler (lifecycle/quit-handler
                   {:quitting? quitting?
                    :update-prepared? (constantly false)
                    :stop! #(p/rejected error)
                    :quit! #(is false "Stop failed; the App must not pretend it is ready to quit.")
                    :on-error! #(swap! errors conj %)})]
      (handler #js {:preventDefault (fn [])})
      (-> (p/let [_ (p/delay 0)]
            (is (= [error] @errors))
            (is (false? @quitting?)))
          (p/finally done)))))
