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
           :native-editor-toolbar mobile-util/native-editor-toolbar
           :native-platform? mobile-util/native-platform?
           :pub-event! state/pub-event!
           :request-animation-frame
           (gobj/get js/window "requestAnimationFrame")})
  (set! mobile-util/native-bottom-sheet support/native-plugin)
  (set! mobile-util/native-editor-toolbar support/native-editor-toolbar)
  (set! mobile-util/native-platform? (constantly true))
  (set! state/pub-event! (fn [& _args]))
  (gobj/set js/window "requestAnimationFrame"
            (fn [callback]
              (callback 0)
              1)))

(defn- restore-test-runtime!
  []
  (let [{:keys [native-bottom-sheet
                native-editor-toolbar
                native-platform?
                pub-event!
                request-animation-frame]} @*fixture-originals]
    (set! mobile-util/native-bottom-sheet native-bottom-sheet)
    (set! mobile-util/native-editor-toolbar native-editor-toolbar)
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

(defn- <settled
  [promise]
  (-> promise
      (p/then (fn [value]
                {:status :resolved
                 :value value}))
      (p/catch (fn [error]
                 {:status :rejected
                  :error error}))))

(defn- deferred-promise
  []
  (let [*resolve! (atom nil)
        *reject! (atom nil)
        promise (js/Promise.
                 (fn [resolve reject]
                   (reset! *resolve! resolve)
                   (reset! *reject! reject)))]
    {:promise promise
     :resolve! (fn [value] (@*resolve! value))
     :reject! (fn [error] (@*reject! error))}))

(defn- prepare-pending-popup!
  []
  (show! :lifecycle-a)
  (popup/popup-hide! :lifecycle-a)
  (show! :lifecycle-b))

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

(deftest resolved-native-plugin-promises-complete-normal-lifecycle-test
  (async done
         (let [listener (support/state-listener)]
           (support/queue-present-promises! [(p/resolved nil)])
           (support/queue-dismiss-promises! [(p/resolved nil)])
           (support/queue-toolbar-dismiss-promises! [(p/resolved nil)])
           (show! :resolved-popup)
           (let [present-promise (support/last-call-promise :present)]
             (is (p/promise? present-promise)
                 "the native present stub must return a real Promise")
             (finish-async!
              (p/let [present-outcome (<settled present-promise)]
                (is (= :resolved (:status present-outcome)))
                (is (= :resolved-popup (popup-id)))
                (is (true? @support/native-visible?))
                (popup/popup-hide! :resolved-popup)
                (let [dismiss-promise (support/last-call-promise :dismiss)]
                  (is (p/promise? dismiss-promise)
                      "the native dismiss stub must return a real Promise")
                  (p/let [dismiss-outcome (<settled dismiss-promise)]
                    (is (= :resolved (:status dismiss-outcome)))
                    (is (false? @support/native-visible?))
                    (p/let [_ (listener #js {:dismissing true})
                            _ (p/delay 0)]
                      (is (= 1 (support/call-count :present)))
                      (is (= 1 (support/call-count :dismiss)))
                      (is (= 1 (support/call-count :toolbar-dismiss)))
                      (is (p/promise?
                           (support/last-call-promise :toolbar-dismiss))
                          "the native toolbar dismiss stub must return a real Promise")
                      (is (= 1 (support/call-count :content-ready)))
                      (is (nil? (popup-id)))))))
              done)))))

(deftest rejected-present-rolls-back-so-the-next-popup-can-present-test
  (async done
         (let [first-error (ex-info "present failed" {:attempt 1})]
           (support/queue-present-promises!
            [(p/rejected first-error)
             (p/resolved nil)])
           (show! :failed-present)
           (finish-async!
            (p/let [first-outcome
                    (<settled (support/last-call-promise :present))
                    _ (p/delay 0)]
              (is (= :rejected (:status first-outcome)))
              (is (nil? (popup-id))
                  "a rejected present must roll back the failed popup's current state")
              (is (false? @support/native-visible?))
              (is (= 1 (support/call-count :present)))

              (show! :retry-present)
              (is (= 2 (support/call-count :present))
                  "the next popup must make a fresh native present attempt")
              (p/let [retry-outcome
                      (<settled (support/last-call-promise :present))]
                (is (= :resolved (:status retry-outcome)))
                (is (= :retry-present (popup-id)))
                (is (true? @support/native-visible?))))
            done))))

(deftest rejected-dismiss-releases-latch-so-the-next-popup-is-not-stranded-test
  (async done
         (support/queue-dismiss-promises!
          [(p/rejected (ex-info "dismiss failed" {}))])
         (show! :failed-dismiss)
         (popup/popup-hide! :failed-dismiss)
         (finish-async!
          (p/let [dismiss-outcome
                  (<settled (support/last-call-promise :dismiss))
                  _ (p/delay 0)]
            (is (= :rejected (:status dismiss-outcome)))
            (is (= :failed-dismiss (popup-id)))
            (is (true? @support/native-visible?)
                "a rejected dismiss leaves the native sheet visible")
            (show! :after-dismiss-rejection)
            (p/let [_ (p/delay 0)]
              (is (= :after-dismiss-rejection (popup-id))
                  "the failed dismiss latch must not strand later popup content")
              (is (true? @support/native-visible?))
              (is (= 1 (support/call-count :present))
                  "the still-visible native sheet should transition content without another present")
              (is (= 1 (support/call-count :dismiss)))))
          done)))

(deftest rejected-inflight-present-does-not-revive-content-hidden-during-transition-test
  (async done
         (let [first-present (deferred-promise)]
           (support/queue-present-promises!
            [(:promise first-present)
             (p/resolved nil)])
           (show! :inflight-a)
           (let [first-present-outcome
                 (<settled (support/last-call-promise :present))]
             (show! :transition-b)
             (is (= :transition-b (popup-id)))
             (popup/popup-hide! :transition-b)
             ((:reject! first-present)
              (ex-info "inflight A present failed" {}))
             (finish-async!
              (p/let [outcome first-present-outcome
                      _ (p/delay 0)]
                (is (= :rejected (:status outcome)))
                (is (= 1 (support/call-count :present))
                    "closed B must not be automatically retried after A rejects")
                (is (= 1 (support/call-count :dismiss)))
                (is (nil? (popup-id))
                    "the transition content hidden by the user must remain closed")
                (is (false? @support/native-visible?))

                (show! :explicit-c)
                (is (= 2 (support/call-count :present))
                    "a later explicit popup must make a fresh present attempt")
                (p/let [retry-outcome
                        (<settled (support/last-call-promise :present))]
                  (is (= :resolved (:status retry-outcome)))
                  (is (= :explicit-c (popup-id)))
                  (is (true? @support/native-visible?))))
              done)))))

(deftest rejected-clear-edit-promise-does-not-block-native-lifecycle-test
  (async done
         (let [listener (support/state-listener)
               clear-edit-calls (atom [])
               clear-edit-rejection (deferred-promise)]
           ;; Mark the intentionally ignored Promise handled for the Node runtime
           ;; while still returning the original rejected Promise to production.
           (.catch ^js (:promise clear-edit-rejection) (fn [_] nil))
           ((:reject! clear-edit-rejection)
            (ex-info "clear edit failed" {}))
           (prepare-pending-popup!)
           (set! state/pub-event!
                 (fn [event]
                   (swap! clear-edit-calls conj event)
                   (:promise clear-edit-rejection)))
           (finish-async!
            (p/let [_ (<settled (listener #js {:dismissing true}))
                    _ (p/delay 0)]
              (is (= [[:mobile/clear-edit]] @clear-edit-calls))
              (is (= 1 (support/call-count :toolbar-dismiss)))
              (is (= 1 (support/call-count :content-ready)))
              (is (nil? (popup-id)))
              (is (false? @support/native-visible?))
              (set! state/pub-event! (fn [& _args]))
              (p/let [_ (listener #js {:dismissing false})
                      _ (p/delay 0)]
                (is (= 2 (support/call-count :present)))
                (is (= :lifecycle-b (popup-id)))
                (is (true? @support/native-visible?))))
            done))))

(deftest delayed-clear-edit-promise-does-not-block-or-overwrite-native-lifecycle-test
  (async done
         (let [listener (support/state-listener)
               clear-edit-calls (atom [])
               deferred (deferred-promise)]
           (prepare-pending-popup!)
           (set! state/pub-event!
                 (fn [event]
                   (swap! clear-edit-calls conj event)
                   (:promise deferred)))
           (finish-async!
            (p/let [_ (listener #js {:dismissing true})
                    _ (p/delay 0)]
              (is (= [[:mobile/clear-edit]] @clear-edit-calls))
              (is (= 1 (support/call-count :toolbar-dismiss)))
              (is (= 1 (support/call-count :content-ready)))
              (is (nil? (popup-id))
                  "unsettled clear-edit work must not delay native bookkeeping")
              (set! state/pub-event! (fn [& _args]))
              (p/let [_ (listener #js {:dismissing false})
                      _ (p/delay 0)]
                (is (= 2 (support/call-count :present)))
                (is (= :lifecycle-b (popup-id)))
                (is (true? @support/native-visible?))
                ((:resolve! deferred) nil)
                  (p/let [_ (p/delay 0)]
                    (is (= :lifecycle-b (popup-id))
                        "late clear-edit completion must not overwrite pending B")
                    (is (= 1 (support/call-count :content-ready))))))
            done))))

(deftest rejected-toolbar-dismiss-does-not-block-or-drop-pending-popup-test
  (async done
         (let [listener (support/state-listener)]
           (support/queue-toolbar-dismiss-promises!
            [(p/rejected (ex-info "toolbar dismiss failed" {}))])
           (prepare-pending-popup!)
           (finish-async!
            (p/let [_lifecycle-outcome
                    (<settled (listener #js {:dismissing true}))
                    _ (p/delay 0)]
              (is (= 1 (support/call-count :toolbar-dismiss)))
              (is (= 1 (support/call-count :content-ready))
                  "toolbar failure must not skip native contentReady")
              (is (nil? (popup-id))
                  "toolbar failure must not block native sheet bookkeeping")
              (is (false? @support/native-visible?))
              (p/let [_ (listener #js {:dismissing false})
                      _ (p/delay 0)]
                (is (= 2 (support/call-count :present)))
                (is (= :lifecycle-b (popup-id)))
                (is (true? @support/native-visible?))))
            done))))

(deftest delayed-toolbar-dismiss-does-not-block-or-overwrite-pending-popup-test
  (async done
         (let [listener (support/state-listener)
               deferred (deferred-promise)]
           (support/queue-toolbar-dismiss-promises! [(:promise deferred)])
           (prepare-pending-popup!)
           (let [lifecycle-outcome
                 (<settled (listener #js {:dismissing true}))]
             (finish-async!
              (p/let [_ (p/delay 0)]
                (is (= 1 (support/call-count :toolbar-dismiss)))
                (is (= 1 (support/call-count :content-ready))
                    "unsettled toolbar work must not delay native contentReady")
                (is (nil? (popup-id))
                    "unsettled toolbar work must not delay native bookkeeping")
                (is (false? @support/native-visible?))
                (p/let [_ (listener #js {:dismissing false})
                        _ (p/delay 0)]
                  (is (= 2 (support/call-count :present)))
                  (is (= :lifecycle-b (popup-id)))
                  (is (true? @support/native-visible?))
                  ((:resolve! deferred) nil)
                  (p/let [_ lifecycle-outcome
                          _ (p/delay 0)]
                    (is (= :lifecycle-b (popup-id))
                        "late toolbar completion must not overwrite pending B")
                    (is (true? @support/native-visible?))
                    (is (= 1 (support/call-count :content-ready))))))
              done)))))

(deftest rejected-content-ready-does-not-undo-completed-native-cleanup-test
  (async done
         (let [listener (support/state-listener)
               content-ready (deferred-promise)]
           (support/queue-content-ready-promises!
            [(:promise content-ready)])
           (show! :content-ready-a)
           (popup/popup-hide! :content-ready-a)
           (finish-async!
            (p/let [_lifecycle-outcome
                    (<settled (listener #js {:dismissing true}))
                    _ (p/delay 0)]
              (let [content-ready-outcome
                    (<settled
                     (support/last-call-promise :content-ready))]
                ((:reject! content-ready)
                 (ex-info "contentReady failed" {}))
                (p/let [outcome content-ready-outcome]
                  (is (= :rejected (:status outcome))
                      "the contentReady rejection must remain observable")
                  (is (= 1 (support/call-count :content-ready)))
                  (is (nil? (popup-id)))
                  (is (false? @support/native-visible?))
                  (p/let [_ (listener #js {:dismissing false})
                          _ (p/delay 0)]
                    (show! :after-content-ready-rejection)
                    (is (= 2 (support/call-count :present)))
                    (is (= :after-content-ready-rejection (popup-id)))
                    (is (true? @support/native-visible?))))))
            done))))
