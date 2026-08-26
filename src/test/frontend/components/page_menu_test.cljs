(ns frontend.components.page-menu-test
  (:require [clojure.test :refer [is]]
            [frontend.components.page-menu :as page-menu]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [goog.object :as gobj]
            [logseq.shui.hooks :as hooks]
            [promesa.core :as p]))

(defn- render-callable-component
  [element]
  (let [component-type (gobj/get element "type")
        render-fn (or (gobj/get component-type "type") component-type)]
    (render-fn (gobj/get element "props"))))

(defn- submit-handler
  [form-element]
  (some-> form-element
          (gobj/get "props")
          (gobj/get "onSubmit")))

(deftest-async delete-page-promise-resolves-from-the-success-callback-test
  (let [page-uuid #uuid "11111111-1111-1111-1111-111111111111"
        *callbacks (atom nil)
        *notifications (atom [])]
    (p/with-redefs [page-handler/<delete!
                    (fn [uuid ok-handler {:keys [error-handler]}]
                      (reset! *callbacks {:uuid uuid
                                          :ok ok-handler
                                          :error error-handler})
                      (p/resolved :started))
                    notification/show!
                    (fn [& args]
                      (swap! *notifications conj args))]
      (let [result (#'page-menu/<delete-page!
                    {:block/uuid page-uuid
                     :block/title "Delete me"})]
        (is (= page-uuid (:uuid @*callbacks)))
        (is (empty? @*notifications))
        ((:ok @*callbacks))
        (p/let [success? result]
          (is (true? success?))
          (is (= :success (second (first @*notifications)))))))))

(deftest-async delete-page-promise-rejects-and-warns-on-false-result-test
  (let [page-uuid #uuid "11111111-1111-1111-1111-111111111111"
        *notifications (atom [])]
    (p/with-redefs [page-handler/<delete!
                    (fn [_uuid _ok-handler {:keys [error-handler]}]
                      (error-handler nil)
                      (p/resolved :started))
                    notification/show!
                    (fn [& args]
                      (swap! *notifications conj args))]
      (let [result (#'page-menu/<delete-page!
                    {:block/uuid page-uuid
                     :block/title "Delete me"})]
        (-> result
            (p/then (constantly :resolved))
            (p/catch (constantly :rejected))
            (p/then (fn [status]
                      (is (= :rejected status))
                      (is (= :warning (second (first @*notifications)))))))))))

(deftest-async delete-page-dialog-deduplicates-submit-and-late-success-closes-only-its-owner-test
  (let [page {:block/uuid #uuid "11111111-1111-1111-1111-111111111111"
              :block/title "Delete me"}
        old-deleting? (atom false)
        new-deleting? (atom false)
        callbacks (atom [])
        state-updates (atom [])
        old-close-count (atom 0)
        new-close-count (atom 0)
        frames (atom [])
        previous-raf (gobj/get js/globalThis "requestAnimationFrame")
        event #js {:preventDefault (fn [])}
        flush-frame! (fn []
                       (let [frame (first @frames)]
                         (swap! frames #(vec (rest %)))
                         (when frame (frame))))]
    (gobj/set js/globalThis "requestAnimationFrame"
              (fn [callback]
                (swap! frames conj callback)
                (count @frames)))
    (-> (p/with-redefs [hooks/use-state
                        (fn [initial]
                          [initial #(swap! state-updates conj %)])
                        page-handler/<delete!
                        (fn [_uuid ok-handler {:keys [error-handler]}]
                          (swap! callbacks conj {:ok ok-handler
                                                 :error error-handler})
                          (p/resolved :started))
                        notification/show! (fn [& _])]
          (let [old-form (render-callable-component
                          (#'page-menu/delete-page-dialog
                           page #(swap! old-close-count inc) old-deleting?))
                old-submit! (submit-handler old-form)]
            (old-submit! event)
            (old-submit! event)
            (is (= 1 (count @callbacks))
                "Repeated confirm must start exactly one delete request.")
            (let [_new-form (render-callable-component
                             (#'page-menu/delete-page-dialog
                              page #(swap! new-close-count inc) new-deleting?))]
              ((:ok (first @callbacks)))
              (p/let [_ (p/delay 0)
                      _ (flush-frame!)
                      _ (flush-frame!)
                      _ (p/delay 0)]
                (is (= 1 @old-close-count))
                (is (zero? @new-close-count)
                    "A late result may close only the exact dialog that created it.")))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally (fn []
                     (if (some? previous-raf)
                       (gobj/set js/globalThis "requestAnimationFrame" previous-raf)
                       (gobj/remove js/globalThis "requestAnimationFrame")))))))
