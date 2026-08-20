(ns frontend.handler.e2ee-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.handler.e2ee :as e2ee-handler]
            [frontend.util :as util]))

(defn- with-qualification-preload
  [value f]
  (let [old-apis (.-apis js/window)
        had-apis? (.call (.-hasOwnProperty (.-prototype js/Object))
                         js/window
                         "apis")]
    (try
      (js/Object.defineProperty js/window
                                "apis"
                                #js {:value #js {:qualificationHome value}
                                     :configurable true
                                     :writable true})
      (f)
      (finally
        (if had-apis?
          (js/Object.defineProperty js/window
                                    "apis"
                                    #js {:value old-apis
                                         :configurable true
                                         :writable true})
          (js/Reflect.deleteProperty js/window "apis"))))))

(deftest electron-native-storage-respects-qualification-home-test
  (testing "normal Electron keeps the official OS Keychain owner"
    (with-qualification-preload
      false
      #(with-redefs [util/electron? (constantly true)
                     util/capacitor? (constantly false)]
         (is (true? (e2ee-handler/native-storage-supported?))))))
  (testing "qualification Electron leaves the global Keychain untouched"
    (with-qualification-preload
      true
      #(with-redefs [util/electron? (constantly true)
                     util/capacitor? (constantly false)]
         (is (false? (e2ee-handler/native-storage-supported?)))))))
