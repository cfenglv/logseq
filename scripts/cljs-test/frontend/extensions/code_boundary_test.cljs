(ns frontend.extensions.code-boundary-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.commands :as commands]
            [frontend.db :as db]
            [frontend.extensions.code :as code]
            [frontend.handler.code :as code-handler]
            [frontend.handler.editor :as editor]
            [frontend.state :as state]
            [frontend.util :as util]
            [goog.dom :as gdom]
            [promesa.core :as p]))

(defn- register-real-math-transition!
  "Enter the production pending registry through the public ordinary-to-Math
  conversion path. Tests may control the returned worker receipt, but may not
  replace the registry lookup or await function that owns the gate."
  [block transition]
  (let [input-id (str "edit-block-" (:block/uuid block))
        input #js {:value "$$x$$"
                   :selectionStart 5
                   :selectionEnd 5}]
    (set! (.-setSelectionRange input) (fn [& _]))
    (set! (.-focus input) (fn []))
    (with-redefs [db/entity (constantly block)
                  state/get-edit-block (constantly block)
                  state/get-editor-args (constantly nil)
                  state/get-editor-info (constantly nil)
                  state/get-input (constantly input)
                  state/set-block-content-and-last-pos! (fn [& _])
                  state/set-state! (fn [& _])
                  state/pub-event! (constantly transition)]
      (is (true? (editor/maybe-convert-current-block-to-math!
                  input-id "$$x$$" 5 5))
          "Fixture must create a real production pending transition"))))

(defn- fake-code-editor
  [listeners events]
  (let [element #js {}
        editor #js {}]
    (set! (.-addEventListener element)
          (fn [event callback]
            (swap! listeners assoc event callback)))
    (set! (.-getWrapperElement editor) (constantly element))
    (set! (.-on editor)
          (fn [event callback]
            (swap! events assoc event callback)))
    (set! (.-save editor) (fn []))
    (set! (.-refresh editor) (fn []))
    (set! (.-getCursor editor) (fn [& _] #js {:line 0 :ch 0}))
    (set! (.-lastLine editor) (constantly 0))
    (set! (.-doc editor) #js {:getLine (constantly "")})
    editor))

(deftest mandatory-guarded-escape-wins-after-user-options-merge
  (async done
         (let [listeners (atom {})
               events (atom {})
               options (atom nil)
               user-escape-calls (atom 0)
               editor-instance (fake-code-editor listeners events)
               textarea #js {}
               block {:db/id 1 :block/uuid (random-uuid)}
               recovery (p/rejected
                         (ex-info "recovery failed"
                                  {:type :math-transition/recovery-failed}))]
           (register-real-math-transition! block recovery)
           (-> (p/with-redefs [state/get-config
                               (constantly {:editor/extra-codemirror-options
                                            {:extraKeys {"Esc" #(swap! user-escape-calls inc)}}})
                               state/get-edit-block (constantly block)
                               state/set-state! (fn [& _])
                               code/from-textarea (fn [_ actual-options]
                                                    (reset! options actual-options)
                                                    editor-instance)
                               gdom/getElement (constantly textarea)
                               editor/consume-math-transition-boundary!
                               (fn [_ promise]
                                 (p/catch promise (constantly {:status :recovery-failed})))]
                 (code/render! {:config {:block block :code-block block}
                                :id "code-boundary"
                                :attr {:data-lang "clojure"}
                                :theme "light"
                                :options {:autofocus true :tabIndex 0 :tabindex 0}})
                 (let [escape (aget (aget @options "extraKeys") "Esc")]
                   (is (fn? escape))
                   (is (= -1 (aget @options "tabindex"))
                       "Late user options cannot restore native keyboard focus")
                   (is (nil? (aget @options "tabIndex"))
                       "Only CodeMirror's real lowercase option is authoritative")
                   (is (false? (aget @options "autofocus"))
                       "Late user options cannot restore constructor autofocus")
                   (escape editor-instance)
                   (is (zero? @user-escape-calls)
                       "Late user options cannot replace the mandatory guarded Escape")))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest constructor-cannot-focus-an-active-textarea-before-the-math-gate
  (async done
         (let [pending (p/deferred)
               listeners (atom {})
               events (atom {})
               actions (atom [])
               options (atom nil)
               editor-instance (fake-code-editor listeners events)
               previous-document (.-document js/globalThis)
               document #js {}
               textarea #js {}
               source-uuid (random-uuid)
               switched-uuid (random-uuid)
               *block (atom {:block/uuid source-uuid})]
           (set! (.-activeElement document) textarea)
           (set! (.-blur textarea)
                 (fn []
                   (swap! actions conj :textarea-blur)
                   (reset! *block {:block/uuid switched-uuid})
                   (set! (.-activeElement document) nil)))
           (set! (.-document js/globalThis) document)
           (set! (.-focus editor-instance)
                 (fn [] (swap! actions conj :editor-focus)))
           (register-real-math-transition! @*block pending)
           (-> (p/with-redefs [state/get-config (constantly {})
                               state/get-edit-block #(deref *block)
                               state/set-state! (fn [& _])
                               gdom/getElement (constantly textarea)
                               code/from-textarea
                               (fn [_ actual-options]
                                 (reset! options actual-options)
                                 (when (or (identical? (.-activeElement document) textarea)
                                           (not (false? (aget actual-options "autofocus"))))
                                   (swap! actions conj :constructor-native-focus))
                                 editor-instance)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)]
                 (code/render! {:config {:block {:block/uuid (random-uuid)}}
                                :id "code-constructor-focus-boundary"
                                :attr {:data-lang "clojure"}
                                :theme "light"
                                :options {:autofocus true}})
                 (is (false? (aget @options "autofocus")))
                 (is (= [:textarea-blur] @actions)
                     "construction may defocus the source but cannot focus CodeMirror")
                 (p/resolve! pending {:status :committed})
                 (p/let [_ pending]
                   (is (= [:textarea-blur :editor-focus] @actions)
                       "A legitimate active textarea restores focus only after its UUID settles")))
               (p/finally #(set! (.-document js/globalThis) previous-document))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest constructor-restores-a-legitimate-active-textarea-synchronously-without-pending
  (let [listeners (atom {})
        events (atom {})
        actions (atom [])
        editor-instance (fake-code-editor listeners events)
        previous-document (.-document js/globalThis)
        document #js {}
        textarea #js {}
        block {:block/uuid (random-uuid)}]
    (set! (.-activeElement document) textarea)
    (set! (.-blur textarea)
          (fn []
            (swap! actions conj :textarea-blur)
            (set! (.-activeElement document) nil)))
    (set! (.-focus editor-instance) #(swap! actions conj :editor-focus))
    (set! (.-document js/globalThis) document)
    (try
      (with-redefs [state/get-config (constantly {})
                    state/get-edit-block (constantly block)
                    state/set-state! (fn [& _])
                    gdom/getElement (constantly textarea)
                    code/from-textarea (fn [_ options]
                                         (when (or (identical? (.-activeElement document) textarea)
                                                   (not (false? (aget options "autofocus"))))
                                           (swap! actions conj :constructor-native-focus))
                                         editor-instance)]
        (code/render! {:config {:block block}
                       :id "code-constructor-fast-path"
                       :attr {:data-lang "clojure"}
                       :theme "light"
                       :options {:autofocus true}})
        (is (= [:textarea-blur :editor-focus] @actions)
            "No-pending construction restores the legitimate native focus before render! returns"))
      (finally
        (set! (.-document js/globalThis) previous-document)))))

(deftest no-pending-keyboard-actions-preserve-synchronous-native-semantics
  (let [listeners (atom {})
        events (atom {})
        actions (atom [])
        editor-instance (fake-code-editor listeners events)
        textarea #js {}
        block-uuid (random-uuid)
        block {:db/id 1 :block/uuid block-uuid}
        block-node #js {:getAttribute (fn [key]
                                       (when (= key "blockid") (str block-uuid)))}
        target #js {:closest (fn [selector]
                              (when (= selector "[blockid]") block-node))}
        make-event (fn [code opts]
                     (let [prevented (atom false)
                           stopped (atom false)]
                       [#js {:code code
                             :target target
                             :metaKey (boolean (:meta opts))
                             :ctrlKey (boolean (:ctrl opts))
                             :shiftKey (boolean (:shift opts))
                             :preventDefault #(reset! prevented true)
                             :stopPropagation #(reset! stopped true)}
                        prevented stopped]))]
    (with-redefs [state/get-config (constantly {})
                  state/get-edit-block (constantly block)
                  state/set-state! (fn [& _])
                  code/from-textarea (fn [& _] editor-instance)
                  gdom/getElement (constantly textarea)
                  code-handler/save-code-editor! #(swap! actions conj :save)
                  editor/api-insert-new-block! (fn [& _] (swap! actions conj :insert))
                  util/schedule (fn [f] (f))]
      (code/render! {:config {:block block :code-block block}
                     :id "code-keyboard-fast-path"
                     :attr {:data-lang "clojure"}
                     :theme "light"
                     :options {}})
      (let [[cmd-event cmd-prevented cmd-stopped] (make-event "BracketLeft" {:meta true})
            [ctrl-event ctrl-prevented ctrl-stopped] (make-event "BracketRight" {:ctrl true})
            [enter-event enter-prevented enter-stopped] (make-event "Enter" {:shift true})
            handler (get @listeners "keydown")]
        (handler cmd-event)
        (handler ctrl-event)
        (handler enter-event)
        (is (every? true? [@cmd-prevented @cmd-stopped
                           @ctrl-prevented @ctrl-stopped
                           @enter-prevented @enter-stopped]))
        (is (= [:save :insert] @actions)
            "Shift+Enter save/insert and bracket prevention remain synchronous")))))

(deftest blocked-pointer-prevents-focus-and-propagation
  (async done
         (let [listeners (atom {})
               events (atom {})
               editor-instance (fake-code-editor listeners events)
               textarea #js {}
               prevented? (atom false)
               stopped? (atom false)
               block {:db/id 1 :block/uuid (random-uuid)}]
           (register-real-math-transition!
            block
            (p/rejected
             (ex-info "latched" {:type :math-transition/recovery-failed})))
           (-> (p/with-redefs [state/get-config (constantly {})
                               state/get-edit-block (constantly block)
                               state/set-state! (fn [& _])
                               code/from-textarea (fn [& _] editor-instance)
                               gdom/getElement (constantly textarea)
                               editor/consume-math-transition-boundary!
                               (fn [_ promise] (p/catch promise (constantly {:status :recovery-failed})))]
                 (code/render! {:config {:block block :code-block block}
                                :id "code-boundary"
                                :attr {:data-lang "clojure"}
                                :theme "light"
                                :options {}})
                 ((get @listeners "pointerdown")
                  #js {:preventDefault #(reset! prevented? true)
                       :stopPropagation #(reset! stopped? true)})
                 (is @prevented? "A blocked pointer cannot move native focus")
                 (is @stopped?))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(defn- code-focus-event
  [prevented? stopped?]
  #js {:preventDefault #(reset! prevented? true)
       :stopPropagation #(reset! stopped? true)})

(defn- run-pointer-focus-case!
  [event-name settled-value]
  (let [pending (p/deferred)
        listeners (atom {})
        events (atom {})
        actions (atom [])
        prevented? (atom false)
        stopped? (atom false)
        source-uuid (random-uuid)
        target-uuid (random-uuid)
        other-uuid (random-uuid)
        *block (atom {:db/id 2 :block/uuid source-uuid})
        editor-instance (fake-code-editor listeners events)
        textarea #js {}
        target-block {:db/id 1
                        :block/uuid target-uuid
                        :logseq.property.node/display-type :code}]
    (set! (.-focus editor-instance)
          (fn []
            (swap! actions conj :native-focus)
            ((get @events "focus") editor-instance)
            ((get @events "focus") editor-instance)))
    (register-real-math-transition! @*block pending)
    (-> (p/with-redefs [state/get-config (constantly {})
                        state/get-edit-block #(deref *block)
                        state/set-state! (fn [key _] (swap! actions conj [:state key]))
                        state/set-block-component-editing-mode!
                        (fn [editing?] (swap! actions conj [:editing editing?]))
                        state/clear-selection! #(swap! actions conj :clear-selection)
                        code/from-textarea (fn [& _] editor-instance)
                        gdom/getElement (constantly textarea)
                        editor/consume-math-transition-boundary!
                        (fn [_ promise] promise)
                        editor/edit-block!
                        (fn [block & _] (swap! actions conj [:edit (:block/uuid block)]))]
          (code/render! {:config {:block target-block
                                  :code-block target-block
                                  :container-id 9}
                         :id "code-focus-boundary"
                         :attr {:data-lang "clojure"}
                         :theme "light"
                         :options {}})
          (reset! actions [])
          (let [result ((get @listeners event-name)
                        (code-focus-event prevented? stopped?))]
            (reset! *block {:db/id 3 :block/uuid other-uuid})
            ;; Simulate the browser default only when the actual pointer
            ;; callback failed to suppress native focus.
            (when-not @prevented?
              (.focus editor-instance))
            (p/let [_ (p/resolved nil)
                    _ (is @prevented? "pointer default is suppressed until Math settles")
                    _ (is @stopped?)
                    _ (is (empty? @actions)
                          "no native focus or editor mutation precedes the frozen UUID receipt")
                    _ (p/resolve! pending settled-value)
                    _ result]
              (is (= [:clear-selection
                      :native-focus
                      [:edit target-uuid]
                      [:editing true]
                      [:state :editor/code-block-context]]
                     @actions))))))))

(deftest pointer-focus-chain-awaits-success-and-recoverable-rollback
  (async done
         (-> (p/do!
              (run-pointer-focus-case! "pointerdown" {:status :committed})
              (run-pointer-focus-case! "touchstart" {:status :rolled-back}))
             (p/then (fn [] (done)))
             (p/catch (fn [error]
                        (is false (str error))
                        (done))))))

(defn- run-rejected-pointer-focus-case!
  [expected-error typed?]
  (let [pending (p/deferred)
        listeners (atom {})
        events (atom {})
        actions (atom [])
        prevented? (atom false)
        stopped? (atom false)
        source-uuid (random-uuid)
        target-uuid (random-uuid)
        editor-instance (fake-code-editor listeners events)
        textarea #js {}
        source-block {:db/id 2 :block/uuid source-uuid}
        target-block {:db/id 1
                        :block/uuid target-uuid
                        :logseq.property.node/display-type :code}]
    (set! (.-focus editor-instance)
          (fn []
            (swap! actions conj :native-focus)
            (if typed?
              ((get @events "focus") editor-instance)
              (throw expected-error))))
    (register-real-math-transition! source-block pending)
    (p/with-redefs [state/get-config (constantly {})
                    state/get-edit-block (constantly source-block)
                    state/set-state! (fn [key _] (swap! actions conj [:state key]))
                    state/set-block-component-editing-mode!
                    (fn [editing?] (swap! actions conj [:editing editing?]))
                    state/clear-selection! #(swap! actions conj :clear-selection)
                    code/from-textarea (fn [& _] editor-instance)
                    gdom/getElement (constantly textarea)]
      (code/render! {:config {:block target-block :code-block target-block}
                     :id "code-rejected-focus-boundary"
                     :attr {:data-lang "clojure"}
                     :theme "light"
                     :options {}})
      (reset! actions [])
      (let [result ((get @listeners "pointerdown")
                    (code-focus-event prevented? stopped?))]
        (when-not @prevented?
          (.focus editor-instance))
        (if typed?
          (p/reject! pending expected-error)
          (p/resolve! pending :committed))
        (-> (p/promise result)
            (p/then (fn [value] {:value value}))
            (p/catch (fn [caught] {:error caught}))
            (p/then
             (fn [{:keys [value error]}]
               (is @prevented?)
               (is @stopped?)
               (if typed?
                 (do
                   (is (empty? @actions))
                   (is (= :recovery-failed (:status value))))
                 (do
                   (is (= [:clear-selection :native-focus] @actions)
                       "An unknown native-focus failure cannot reach edit/state mutations")
                   (is (identical? expected-error error)))))))))))

(deftest pointer-focus-chain-fails-closed-for-typed-and-unknown-rejection
  (async done
         (let [typed (ex-info "content-free recovery failure"
                              {:type :math-transition/recovery-failed})
               unknown (js/Error. "ordinary focus failure")
               original-console-error (.-error js/console)]
           (set! (.-error js/console) (fn [& _]))
           (-> (p/do!
                (run-rejected-pointer-focus-case! typed true)
                (run-rejected-pointer-focus-case! unknown false))
               (p/finally #(set! (.-error js/console) original-console-error))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest programmatic-focus-awaits-the-frozen-subject-before-native-focus
  (async done
         (let [pending (p/deferred)
               listeners (atom {})
               events (atom {})
               actions (atom [])
               source-uuid (random-uuid)
               target-uuid (random-uuid)
               other-uuid (random-uuid)
               *block (atom {:db/id 1 :block/uuid source-uuid})
               editor-instance (fake-code-editor listeners events)
               textarea #js {}
               target-block {:db/id 1
                               :block/uuid target-uuid
                               :logseq.property.node/display-type :code}]
           (set! (.-focus editor-instance)
                 (fn []
                   (swap! actions conj :native-focus)
                   ((get @events "focus") editor-instance)
                   ((get @events "focus") editor-instance)))
           (register-real-math-transition! @*block pending)
           (-> (p/with-redefs [state/get-config (constantly {})
                               state/get-edit-block #(deref *block)
                               state/set-state! (fn [key _] (swap! actions conj [:state key]))
                               state/set-block-component-editing-mode!
                               (fn [editing?] (swap! actions conj [:editing editing?]))
                               code/from-textarea (fn [& _] editor-instance)
                               gdom/getElement (constantly textarea)
                               editor/consume-math-transition-boundary!
                               (fn [_ promise] promise)
                               editor/edit-block!
                               (fn [block & _] (swap! actions conj [:edit (:block/uuid block)]))]
                 (code/render! {:config {:block target-block :code-block target-block}
                                :id "code-programmatic-focus-boundary"
                                :attr {:data-lang "clojure"}
                                :theme "light"
                                :options {}})
                 (reset! actions [])
                 (let [result (.focus editor-instance)]
                   (reset! *block {:db/id 2 :block/uuid other-uuid})
                   (p/let [_ (p/resolved nil)
                           _ (is (empty? @actions))
                           _ (p/resolve! pending {:status :committed})
                           _ result]
                     (is (= [:native-focus
                             [:edit target-uuid]
                             [:editing true]
                             [:state :editor/code-block-context]]
                            @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest codemirror-focus-freezes-subject-and-awaits-before-mutation
  (async done
         (let [pending (p/deferred)
               first-uuid (random-uuid)
               second-uuid (random-uuid)
               *block (atom {:block/uuid first-uuid})
               actions (atom [])
               original-timeout js/setTimeout]
           (set! js/setTimeout
                 (fn [callback delay]
                   (swap! actions conj [:timer delay])
                   (callback)
                   1))
           (register-real-math-transition! @*block pending)
           (-> (p/with-redefs [state/get-edit-block #(deref *block)
                               editor/consume-math-transition-boundary!
                               (fn [_ promise] promise)
                               state/pub-event! #(swap! actions conj [:save %])
                               state/clear-edit! #(swap! actions conj [:clear])
                               util/get-first-block-by-id
                               (fn [uuid]
                                 (swap! actions conj [:lookup uuid])
                                 #js {:querySelector (constantly nil)})]
                 (let [result (commands/handle-step [:codemirror/focus])]
                   (reset! *block {:block/uuid second-uuid})
                   (p/let [_ (p/resolved nil)
                           _ (is (empty? @actions))
                           _ (p/resolve! pending nil)
                           _ result]
                     (is (= [[:save [:editor/save-current-block]]
                             [:clear]
                             [:timer 256]
                             [:lookup first-uuid]]
                            @actions)))))
               (p/finally (fn [] (set! js/setTimeout original-timeout)))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))
