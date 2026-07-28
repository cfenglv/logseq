(ns mobile.popup-test
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [mobile.popup-test-support :as support]
            [mobile.components.popup :as popup]
            [mobile.state :as mobile-state]
            [goog.object :as gobj]
            [promesa.core :as p]))

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

(defonce ^:private *fixture-originals (atom nil))

(defn- <settle-native-lifecycle!
  []
  (if-let [listener (support/state-listener)]
    (p/let [_ (listener #js {:dismissing false})
            _ (listener #js {:dismissing true})
            _ (listener #js {:dismissing false})
            _ (p/delay 0)]
      nil)
    (p/resolved nil)))

(defn- reset-observed-state!
  []
  (support/reset-plugin!)
  (mobile-state/set-popup! nil))

(defn- install-test-runtime!
  []
  (reset! *fixture-originals
          {:native-bottom-sheet mobile-util/native-bottom-sheet
           :native-platform? mobile-util/native-platform?
           :pub-event! state/pub-event!
           :request-animation-frame
           (gobj/get js/window "requestAnimationFrame")})
  (set! mobile-util/native-bottom-sheet support/native-plugin)
  (set! mobile-util/native-platform? (constantly true))
  (set! state/pub-event! (fn [& _args]))
  (gobj/set js/window "requestAnimationFrame"
            (fn [callback]
              (callback 0)
              1)))

(defn- restore-test-runtime!
  []
  (let [{:keys [native-bottom-sheet
                native-platform?
                pub-event!
                request-animation-frame]} @*fixture-originals]
    (set! mobile-util/native-bottom-sheet native-bottom-sheet)
    (set! mobile-util/native-platform? native-platform?)
    (set! state/pub-event! pub-event!)
    (if (some? request-animation-frame)
      (gobj/set js/window "requestAnimationFrame" request-animation-frame)
      (js-delete js/window "requestAnimationFrame"))
    (reset! *fixture-originals nil)))

(defn- finish-async!
  [promise done]
  (-> promise
      (p/then (fn [_] (done)))
      (p/catch
       (fn [error]
         (is false (str error))
         (done)))))

(defn- before-each!
  []
  (async done
         (install-test-runtime!)
         (finish-async!
          (p/let [_ (<settle-native-lifecycle!)]
            (reset-observed-state!))
          done)))

(defn- after-each!
  []
  (async done
         (finish-async!
          (-> (p/let [_ (<settle-native-lifecycle!)]
                (reset-observed-state!))
              (p/finally restore-test-runtime!))
          done)))

(use-fixtures :each {:before before-each!
                     :after after-each!})

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
  (async done
         (let [listener (support/state-listener)]
           (is (fn? listener)
               "the popup namespace must subscribe to native sheet state")
           (if-not listener
             (done)
             (do
               (show! :pending-a)
               (popup/popup-hide! :pending-a)
               (is (= 1 (support/call-count :present)))
               (is (= 1 (support/call-count :dismiss)))
               (is (false? @support/native-visible?))

               (show! :pending-b)
               (is (contains? #{nil :pending-a} (popup-id))
                   "B must remain queued rather than becoming current before dismissal ack")
               (is (= 1 (support/call-count :present))
                   "B must not present before native dismissal acknowledgement")
               (is (false? @support/native-visible?))

               (finish-async!
                (p/let [_ (listener #js {:dismissing true})
                        _ (p/delay 0)]
                  (is (nil? (popup-id))
                      "the dismissing state must clear A before pending B is presented")
                  (p/let [_ (listener #js {:dismissing false})
                          _ (p/delay 0)]
                    (is (= 2 (support/call-count :present))
                        "native dismissal acknowledgement must present pending B exactly once")
                    (is (= :pending-b (popup-id)))
                    (is (true? @support/native-visible?))))
                done))))))

(deftest pending-native-sheet-hidden-by-id-stays-cancelled-after-dismissal-ack-test
  (async done
         (let [listener (support/state-listener)]
           (is (fn? listener)
               "the popup namespace must subscribe to native sheet state")
           (if-not listener
             (done)
             (do
               (show! :active-a)
               (popup/popup-hide! :active-a)
               (show! :pending-b)
               (popup/popup-hide! :pending-b)

               (finish-async!
                (p/let [_ (listener #js {:dismissing true})
                        _ (p/delay 0)]
                  (is (= 1 (support/call-count :present))
                      "the dismissing acknowledgement must not present cancelled B")
                  (is (nil? (popup-id)))
                  (is (false? @support/native-visible?))
                  (p/let [_ (listener #js {:dismissing false})
                          _ (p/delay 0)]
                    (is (= 1 (support/call-count :present))
                        "the dismissed acknowledgement must not revive cancelled B")
                    (is (nil? (popup-id)))
                    (is (false? @support/native-visible?))))
                done))))))

(deftest pending-native-sheet-hidden-globally-stays-cancelled-after-dismissal-ack-test
  (async done
         (let [listener (support/state-listener)]
           (is (fn? listener)
               "the popup namespace must subscribe to native sheet state")
           (if-not listener
             (done)
             (do
               (show! :active-a)
               (popup/popup-hide! :active-a)
               (show! :pending-b)
               (popup/popup-hide!)

               (finish-async!
                (p/let [_ (listener #js {:dismissing true})
                        _ (p/delay 0)]
                  (is (= 1 (support/call-count :present))
                      "the dismissing acknowledgement must not present globally cancelled B")
                  (is (nil? (popup-id)))
                  (is (false? @support/native-visible?))
                  (p/let [_ (listener #js {:dismissing false})
                          _ (p/delay 0)]
                    (is (= 1 (support/call-count :present))
                        "the dismissed acknowledgement must not revive globally cancelled B")
                    (is (nil? (popup-id)))
                    (is (false? @support/native-visible?))))
                done))))))

(deftest hide-for-unrelated-id-does-not-cancel-pending-native-sheet-test
  (async done
         (let [listener (support/state-listener)]
           (is (fn? listener)
               "the popup namespace must subscribe to native sheet state")
           (if-not listener
             (done)
             (do
               (show! :active-a)
               (popup/popup-hide! :active-a)
               (show! :pending-b)
               (popup/popup-hide! :unrelated-popup)

               (finish-async!
                (p/let [_ (listener #js {:dismissing true})
                        _ (p/delay 0)]
                  (is (= 1 (support/call-count :present)))
                  (is (nil? (popup-id)))
                  (is (false? @support/native-visible?))
                  (p/let [_ (listener #js {:dismissing false})
                          _ (p/delay 0)]
                    (is (= 2 (support/call-count :present))
                        "an unrelated hide request must leave B queued")
                    (is (= :pending-b (popup-id)))
                    (is (true? @support/native-visible?))))
                done))))))

(deftest ordinary-native-show-and-hide-still-call-plugin-once-test
  (show! :ordinary-popup)
  (is (= 1 (support/call-count :present)))
  (is (= :ordinary-popup (popup-id)))
  (is (true? @support/native-visible?))

  (popup/popup-hide! :ordinary-popup)
  (is (= 1 (support/call-count :dismiss)))
  (is (false? @support/native-visible?)))
