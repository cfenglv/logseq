(ns frontend.handler.history-test
  (:require [clojure.test :refer [deftest is]]
            [frontend.db :as db]
            [frontend.handler.editor :as editor]
            [frontend.handler.editor.lifecycle]
            [frontend.handler.history :as history]
            [frontend.state :as state]
            [frontend.test.helper :include-macros true :refer [deftest-async]]
            [frontend.util :as util]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(deftest-async undo-and-redo-share-one-ordered-request-queue-test
  (let [undo-result (p/deferred)
        calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly "ordered-history-test")
                    state/<invoke-db-worker
                    (fn [op _repo]
                      (swap! calls conj op)
                      (case op
                        :thread-api/undo-redo-undo undo-result
                        :thread-api/undo-redo-redo
                        (p/resolved {:undo? false :editor-cursors []})))
                    state/set-state! (fn [& _])
                    state/clear-editor-action! (fn [& _])
                    editor/save-current-block! (fn [& _])
                    util/stop (fn [& _])
                    history/restore-cursor-and-state! (fn [& _])]
      (let [undo-promise (#'history/undo-aux! nil)
            redo-promise (#'history/redo-aux! nil)]
        (p/let [_ (p/delay 25)
                _ (is (= [:thread-api/undo-redo-undo]
                         @calls)
                      "Redo must wait for the preceding Undo to settle")
                _ (p/resolve! undo-result {:undo? true :editor-cursors []})
                _ undo-promise
                _ redo-promise]
          (is (= [:thread-api/undo-redo-undo
                  :thread-api/undo-redo-redo]
                 @calls)
              "History actions must execute in user-observed order"))))))

(deftest-async failed-history-action-does-not-poison-the-shared-queue-test
  (let [failure (ex-info "synthetic history failure"
                         {:type :test/history-failure})
        calls (atom [])
        first-request (p/catch
                       (#'history/enqueue-history-action!
                        (fn []
                          (swap! calls conj :failed-undo)
                          (p/rejected failure)))
                       (fn [error] {:error error}))
        second-request (#'history/enqueue-history-action!
                        (fn []
                          (swap! calls conj :next-redo)
                          (p/resolved :recovered)))]
    (p/let [{first-error :error} first-request
            second-result second-request]
      (is (identical? failure first-error)
          "The failed caller must observe its original history error")
      (is (= :recovered second-result))
      (is (= [:failed-undo :next-redo] @calls)
          "A failed action must not block later ordered history actions"))))

(deftest restore-cursor-and-state-prefers-ui-state-test
  (let [pause-calls (atom [])
        app-state-calls (atom [])
        cursor-calls (atom [])]
    (with-redefs [state/set-state! (fn [k v]
                                     (swap! pause-calls conj [k v]))
                  ldb/read-transit-str (fn [_]
                                         {:old-state {:route-data {:to :page}}
                                          :new-state {:route-data {:to :home}}})
                  history/restore-app-state! (fn [app-state]
                                               (swap! app-state-calls conj app-state))
                  history/restore-cursor! (fn [data]
                                            (swap! cursor-calls conj data))]
      (#'history/restore-cursor-and-state!
       {:ui-state-str "ui-state"
        :undo? true
        :editor-cursors [{:block-uuid (random-uuid)}]})
      (is (= [[:history/paused? true]
              [:history/paused? false]]
             @pause-calls))
      (is (= [{:route-data {:to :page}}]
             @app-state-calls))
      (is (empty? @cursor-calls)))))

(deftest restore-cursor-and-state-falls-back-to-cursor-test
  (let [pause-calls (atom [])
        app-state-calls (atom [])
        cursor-calls (atom [])]
    (with-redefs [state/set-state! (fn [k v]
                                     (swap! pause-calls conj [k v]))
                  history/restore-app-state! (fn [app-state]
                                               (swap! app-state-calls conj app-state))
                  history/restore-cursor! (fn [data]
                                            (swap! cursor-calls conj data))]
      (#'history/restore-cursor-and-state!
       {:ui-state-str nil
        :undo? false
        :editor-cursors [{:block-uuid (random-uuid)
                          :start-pos 1
                          :end-pos 2}]})
      (is (= [[:history/paused? true]
              [:history/paused? false]]
             @pause-calls))
      (is (empty? @app-state-calls))
      (is (= 1 (count @cursor-calls)))
      (is (nil? (:ui-state-str (first @cursor-calls))))
      (is (= false (:undo? (first @cursor-calls)))))))

(deftest restore-cursor-prefers-block-selection-test
  (let [selection-calls (atom [])
        edit-calls (atom [])]
    (with-redefs [util/get-blocks-by-id (fn [block-id]
                                          (case block-id
                                            #uuid "00000000-0000-0000-0000-000000000001" [:node-1]
                                            #uuid "00000000-0000-0000-0000-000000000002" [:node-2]
                                            nil))
                  state/exit-editing-and-set-selected-blocks! (fn [blocks direction]
                                                                (swap! selection-calls conj [blocks direction]))
                  editor/edit-block! (fn [& args]
                                       (swap! edit-calls conj args))
                  db/pull (constantly nil)]
      (#'history/restore-cursor!
       {:undo? true
        :editor-cursors [{:selected-block-uuids [#uuid "00000000-0000-0000-0000-000000000001"
                                                 #uuid "00000000-0000-0000-0000-000000000002"]
                          :selection-direction :down}]})
      (is (= [[[:node-1 :node-2] :down]]
             @selection-calls))
      (is (empty? @edit-calls)))))

(deftest restore-cursor-selection-falls-back-to-editor-cursor-test
  (let [selection-calls (atom [])
        edit-calls (atom [])
        block-uuid #uuid "00000000-0000-0000-0000-000000000003"]
    (with-redefs [util/get-blocks-by-id (constantly nil)
                  state/exit-editing-and-set-selected-blocks! (fn [blocks direction]
                                                                (swap! selection-calls conj [blocks direction]))
                  editor/edit-block! (fn [& args]
                                       (swap! edit-calls conj args))
                  db/pull (fn [[_lookup-k id]]
                            (when (= block-uuid id)
                              {:db/id 42
                               :block/uuid block-uuid}))]
      (#'history/restore-cursor!
       {:undo? false
        :editor-cursors [{:selected-block-uuids [#uuid "00000000-0000-0000-0000-000000000001"]
                          :selection-direction :up
                          :block-uuid block-uuid
                          :container-id 99
                          :start-pos 1
                          :end-pos 3}]})
      (is (empty? @selection-calls))
      (is (= [[{:db/id 42
                :block/uuid block-uuid}
               3
               {:container-id 99
                :custom-content nil}]]
             @edit-calls)))))

(deftest math-undo-restores-ordinary-cursor-container-and-focus-test
  (let [block-uuid #uuid "00000000-0000-0000-0000-000000000004"
        focused? (atom false)
        edit-calls (atom [])]
    (with-redefs [db/pull (fn [[_ id]]
                            (when (= block-uuid id)
                              {:db/id 44 :block/uuid block-uuid :block/title "$$$$"}))
                  editor/edit-block! (fn [block pos opts]
                                       (reset! focused? true)
                                       (swap! edit-calls conj [block pos opts]))
                  state/set-state! (constantly nil)]
      (#'history/restore-cursor-and-state!
       {:undo? true
        :block-content "$$$$"
        :editor-cursors [{:block-uuid block-uuid
                          :container-id 17
                          :start-pos 4
                          :end-pos 4}]})
      (is @focused?)
      (is (= [[{:db/id 44 :block/uuid block-uuid :block/title "$$$$"}
                4
                {:container-id 17 :custom-content "$$$$"}]]
             @edit-calls)))))

(deftest renderer-history-cursor-adapter-applies-real-selection-and-focus-test
  (let [previous-state @state/state
        previous-document (.-document js/globalThis)
        block-uuid #uuid "00000000-0000-0000-0000-000000000005"
        input #js {:value "$$$$"
                   :selectionStart 0
                   :selectionEnd 0}
        adapter (resolve
                 'frontend.handler.editor.lifecycle/restore-selection-and-focus!)]
    (set! (.-setSelectionRange input)
          (fn [start end]
            (set! (.-selectionStart input) start)
            (set! (.-selectionEnd input) end)))
    (set! (.-focus input)
          #(set! (.-activeElement js/document) input))
    (try
      (reset! state/state
              (assoc previous-state
                     :editor/block (atom {:block/uuid block-uuid
                                          :block/title "$$$$"})
                     :editor/last-saved-cursor (atom {})))
      (set! (.-document js/globalThis) #js {:activeElement nil})
      (state/set-editor-last-pos! 4)
      (is (= 4 (state/get-editor-last-pos))
          "The test preimage must use the real renderer cursor state")
      (is (fn? adapter) "History restore needs a public renderer adapter")
      (when (fn? adapter)
        (adapter input "$$$$" {})
        (is (identical? input (.-activeElement js/document)))
        (is (= [4 4]
               [(.-selectionStart input) (.-selectionEnd input)]))
        (is (= 4 (state/get-editor-last-pos))
            "The renderer must consume the real saved cursor state"))
      (finally
        (reset! state/state previous-state)
        (set! (.-document js/globalThis) previous-document)))))
