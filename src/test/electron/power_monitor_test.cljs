(ns electron.power-monitor-test
  (:require ["events" :refer [EventEmitter]]
            [cljs.test :refer [deftest is]]
            [electron.power-monitor :as power-monitor]))

(deftest resume-notifies-each-live-window-test
  (let [calls (atom [])
        live-window #js {:id 1
                         :isDestroyed (fn [] false)}
        destroyed-window #js {:id 2
                              :isDestroyed (fn [] true)}]
    (power-monitor/notify-renderers-on-resume!
     [live-window destroyed-window]
     (fn [window channel payload]
       (swap! calls conj [(.-id ^js window) channel payload])))
    (is (= [[1 :power-resume {:reason :system-resume}]] @calls))))

(deftest setup-registers-and-removes-resume-listener-test
  (let [monitor (EventEmitter.)
        calls (atom 0)
        live-window #js {:isDestroyed (fn [] false)}
        cleanup (power-monitor/setup!
                 monitor
                 (fn [] [live-window])
                 (fn [& _] (swap! calls inc)))]
    (.emit monitor "resume")
    (is (= 1 @calls))
    (cleanup)
    (.emit monitor "resume")
    (is (= 1 @calls))))
