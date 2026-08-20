(ns mobile.popup-test-support
  (:require [frontend.mobile.util :as mobile-util]
            [promesa.core :as p]))

(def original-native-bottom-sheet mobile-util/native-bottom-sheet)
(def original-native-editor-toolbar mobile-util/native-editor-toolbar)
(def original-native-platform? mobile-util/native-platform?)

(def calls (atom []))
(def call-promises (atom []))
(def listeners (atom {}))
(def native-visible? (atom false))
(def present-promises (atom []))
(def dismiss-promises (atom []))
(def content-ready-promises (atom []))
(def toolbar-dismiss-promises (atom []))

(defn- take-plugin-promise!
  [promises]
  (if (seq @promises)
    (let [promise (first @promises)]
      (swap! promises subvec 1)
      [true promise])
    [false (p/resolved nil)]))

(defn- record-call!
  [method opts promise]
  (swap! calls conj [method opts])
  (swap! call-promises conj [method promise])
  promise)

(def native-plugin
  #js {:present
       (fn [opts]
         (let [[configured? promise] (take-plugin-promise! present-promises)
               promise' (if configured?
                          (p/then promise
                                  (fn [value]
                                    (reset! native-visible? true)
                                    value))
                          (do
                            (reset! native-visible? true)
                            promise))]
           (record-call! :present opts promise')))
       :dismiss
       (fn [opts]
         (let [[configured? promise] (take-plugin-promise! dismiss-promises)
               promise' (if configured?
                          (p/then promise
                                  (fn [value]
                                    (reset! native-visible? false)
                                    value))
                          (do
                            (reset! native-visible? false)
                            promise))]
           (record-call! :dismiss opts promise')))
       :contentReady
       (fn [opts]
         (let [[_configured? promise]
               (take-plugin-promise! content-ready-promises)]
           (record-call! :content-ready opts promise)))
       :addListener
       (fn [event listener]
         (swap! listeners assoc event listener)
         #js {:remove (fn [])})})

(def native-editor-toolbar
  #js {:dismiss
       (fn []
         (let [[_configured? promise]
               (take-plugin-promise! toolbar-dismiss-promises)]
           (record-call! :toolbar-dismiss nil promise)))})

(defn reset-plugin!
  []
  (reset! calls [])
  (reset! call-promises [])
  (reset! native-visible? false)
  (reset! present-promises [])
  (reset! dismiss-promises [])
  (reset! content-ready-promises [])
  (reset! toolbar-dismiss-promises []))

(defn queue-present-promises!
  [promises]
  (reset! present-promises (vec promises)))

(defn queue-dismiss-promises!
  [promises]
  (reset! dismiss-promises (vec promises)))

(defn queue-content-ready-promises!
  [promises]
  (reset! content-ready-promises (vec promises)))

(defn queue-toolbar-dismiss-promises!
  [promises]
  (reset! toolbar-dismiss-promises (vec promises)))

(defn call-count
  [method]
  (count (filter #(= method (first %)) @calls)))

(defn last-call-promise
  [method]
  (some->> @call-promises
           (filter #(= method (first %)))
           last
           second))

(defn state-listener
  []
  (get @listeners "state"))

;; mobile.components.popup installs its native state listener at namespace load.
(set! mobile-util/native-bottom-sheet native-plugin)
(set! mobile-util/native-platform? (constantly true))
