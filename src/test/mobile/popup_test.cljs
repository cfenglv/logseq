(ns mobile.popup-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [frontend.mobile.util :as mobile-util]
            [mobile.popup-test-support :as support]
            [mobile.components.popup :as popup]
            [mobile.state :as mobile-state]))

;; The support namespace supplies the plugin only while mobile.components.popup
;; installs its public native state listener. Tests scope the same stub explicitly.
(set! mobile-util/native-bottom-sheet support/original-native-bottom-sheet)
(set! mobile-util/native-platform? support/original-native-platform?)

(defn- popup-id
  []
  (get-in @mobile-state/*popup-data [:opts :id]))

(defn- show!
  [id]
  (popup/popup-show!
   nil
   (fn [] [:div (name id)])
   {:id id}))

(use-fixtures
  :each
  (fn [test-fn]
    (support/reset-plugin!)
    (mobile-state/set-popup! nil)
    (with-redefs [mobile-util/native-bottom-sheet support/native-plugin
                  mobile-util/native-platform? (constantly true)]
      (try
        (test-fn)
        (finally
          (mobile-state/set-popup! nil))))))

(deftest active-native-sheet-transitions-content-without-presenting-again-test
  (show! :transition-a)
  (is (= 1 (support/call-count :present)))
  (is (= :transition-a (popup-id)))

  (show! :transition-b)
  (is (= :transition-b (popup-id))
      "the active native sheet must expose the new content")
  (is (= 1 (support/call-count :present))
      "a content transition must not present a second native sheet")
  (is (true? @support/native-visible?)))

(deftest cleanup-for-previous-content-does-not-dismiss-current-native-sheet-test
  (show! :cleanup-a)
  (show! :cleanup-b)
  (is (= :cleanup-b (popup-id)))

  (popup/popup-hide! :cleanup-a)
  (is (= :cleanup-b (popup-id))
      "cleanup for A must not clear B's current content")
  (is (zero? (support/call-count :dismiss))
      "cleanup for A must not dismiss the single native sheet now showing B")
  (is (true? @support/native-visible?)))

(deftest show-during-native-dismissal-is-presented-on-dismissal-ack-test
  (let [listener (support/state-listener)]
    (is (fn? listener)
        "the popup namespace must subscribe to native sheet state")
    (show! :pending-a)
    (popup/popup-hide! :pending-a)
    (is (= 1 (support/call-count :present)))
    (is (= 1 (support/call-count :dismiss)))
    (is (false? @support/native-visible?))

    (show! :pending-b)
    (is (= :pending-b (popup-id))
        "B must remain the requested content while native dismissal is pending")
    (is (= 1 (support/call-count :present))
        "B must not present before native dismissal acknowledgement")
    (is (false? @support/native-visible?))

    (when listener
      (listener #js {:dismissing false}))
    (is (= 2 (support/call-count :present))
        "native dismissal acknowledgement must present pending B exactly once")
    (is (= :pending-b (popup-id)))
    (is (true? @support/native-visible?))))

(deftest ordinary-native-show-and-hide-still-call-plugin-once-test
  (show! :ordinary-popup)
  (is (= 1 (support/call-count :present)))
  (is (= :ordinary-popup (popup-id)))
  (is (true? @support/native-visible?))

  (popup/popup-hide! :ordinary-popup)
  (is (= 1 (support/call-count :dismiss)))
  (is (false? @support/native-visible?)))
