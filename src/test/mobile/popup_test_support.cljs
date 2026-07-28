(ns mobile.popup-test-support
  (:require [frontend.mobile.util :as mobile-util]))

(def original-native-bottom-sheet mobile-util/native-bottom-sheet)
(def original-native-platform? mobile-util/native-platform?)

(def calls (atom []))
(def listeners (atom {}))
(def native-visible? (atom false))

(def native-plugin
  #js {:present
       (fn [opts]
         (swap! calls conj [:present opts])
         (reset! native-visible? true))
       :dismiss
       (fn [opts]
         (swap! calls conj [:dismiss opts])
         (reset! native-visible? false))
       :contentReady
       (fn [opts]
         (swap! calls conj [:content-ready opts]))
       :addListener
       (fn [event listener]
         (swap! listeners assoc event listener)
         #js {:remove (fn [])})})

(defn reset-plugin!
  []
  (reset! calls [])
  (reset! native-visible? false))

(defn call-count
  [method]
  (count (filter #(= method (first %)) @calls)))

(defn state-listener
  []
  (get @listeners "state"))

;; mobile.components.popup installs its native state listener at namespace load.
(set! mobile-util/native-bottom-sheet native-plugin)
(set! mobile-util/native-platform? (constantly true))
