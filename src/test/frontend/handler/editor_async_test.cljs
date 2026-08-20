(ns frontend.handler.editor-async-test
  (:require ["react" :as react]
            ["react-dom/server" :as react-dom-server]
            [cljs.test :refer [deftest is testing async use-fixtures]]
            [datascript.core :as d]
            [dommy.core :as dom]
            [frontend.components.block :as block-component]
            [frontend.components.block.comments-model :as comments-model]
            [frontend.components.editor :as editor-component]
            [frontend.components.property.value :as property-value]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.db.model :as db-model]
            [frontend.db.transact :as db-transact]
            [frontend.handler.assets :as assets-handler]
            [frontend.handler.block :as block-handler]
            [frontend.handler.comments :as comments-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor]
            [frontend.handler.events :as events]
            [frontend.handler.property :as property-handler]
            [frontend.mobile.intent :as mobile-intent]
            [frontend.mobile.util :as mobile-util]
            [frontend.modules.outliner.op :as outliner-op]
            [frontend.quick-capture :as quick-capture]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async load-test-files]]
            [frontend.util :as util]
            [frontend.util.cursor :as cursor]
            [goog.dom :as gdom]
            [logseq.api.editor :as editor-api]
            [logseq.db :as ldb]
            [logseq.db.frontend.property :as db-property]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [mobile.navigation :as mobile-nav]
            [promesa.core :as p]))

(defonce ^:private *previous-state (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! *previous-state @state/state)
             (async done
                    (test-helper/start-test-db!)
                    (done)))
   :after (fn []
            (let [previous-state @*previous-state]
              (test-helper/destroy-test-db!)
              (state/set-current-repo! (:git/current-repo previous-state))
              (reset! state/state previous-state)
              (reset! *previous-state nil)))})

(defn- fake-key-event
  []
  (let [stopped? (atom false)]
    {:event #js {:preventDefault #(reset! stopped? true)
                 :stopPropagation #(reset! stopped? true)}
     :stopped? stopped?}))

(defn- math-transition-input
  [value]
  (let [focused? (atom false)
        input #js {:id "edit-block-math-transition"
                   :value value
                   :selectionStart (count value)
                   :selectionEnd (count value)}]
    (set! (.-setSelectionRange input)
          (fn [start end]
            (set! (.-selectionStart input) start)
            (set! (.-selectionEnd input) end)))
    (set! (.-focus input) #(reset! focused? true))
    {:input input
     :focused? focused?}))

(deftest-async pending-math-transition-blocks-escape-until-success
  (let [block {:db/id 1
               :block/uuid (random-uuid)
               :block/title "ordinary"}
        *editing-block (atom block)
        transition (p/deferred)
        {:keys [input]} (math-transition-input "$$x$$")
        calls (atom [])]
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-input (constantly input)
                    state/get-edit-input-id (constantly (.-id input))
                    state/get-current-repo (constantly test-helper/test-db)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/pub-event! (constantly transition)
                    state/clear-edit! #(swap! calls conj :clear)
                    db/entity (constantly block)
                    editor/save-current-block! #(swap! calls conj :save)]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$x$$" 5 5))
      (let [exit-result (editor/escape-editing)]
        (is (empty? @calls)
            "Escape/blur must not save or clear while the block transition is pending")
        (p/resolve! transition :committed)
        (p/let [_ exit-result]
          (is (= [:save :clear] @calls)))))))

(deftest-async rejected-empty-math-transition-rolls-back-before-exit
  (let [block {:db/id 1
               :block/uuid (random-uuid)
               :block/title "ordinary"}
        *editing-block (atom block)
        transition (p/deferred)
        {:keys [input focused?]} (math-transition-input "$$$$")
        calls (atom [])]
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-input (constantly input)
                    state/get-edit-input-id (constantly (.-id input))
                    state/get-current-repo (constantly test-helper/test-db)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/pub-event!
                    (fn [[_ {:keys [math-transition-rollback]}]]
                      (-> transition
                          (p/catch (fn [error]
                                     (editor/rollback-math-transition!
                                      math-transition-rollback)
                                     (p/rejected error)))))
                    state/clear-edit! #(swap! calls conj [:clear (.-value input)])
                    db/entity (constantly block)
                    editor/save-current-block!
                    #(swap! calls conj [:save
                                        (.-value input)
                                        (:logseq.property.node/display-type
                                         @*editing-block)])]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$$$" 4 4))
      (is (= "" (.-value input)) "The optimistic state is canonical Math")
      (let [exit-result (editor/escape-editing)
            failure (ex-info "synthetic Math rejection" {})]
        (is (empty? @calls))
        (p/reject! transition failure)
        (p/let [_ exit-result]
          (is (= [[:save "$$$$" nil]
                  [:clear "$$$$"]]
                 @calls)
              "The original ordinary delimiters must be restored before save/exit")
          (is (= 4 (.-selectionStart input)))
          (is @focused?))))))

(deftest-async target-pointerdown-and-outside-hook-wait-for-old-math-rollback
  (let [old-block {:db/id 1 :block/uuid (random-uuid) :block/title "$$$$"}
        target-block {:db/id 2 :block/uuid (random-uuid) :block/title "target"}
        *editing-block (atom old-block)
        transition (p/deferred)
        {:keys [input focused?]} (math-transition-input "$$$$")
        calls (atom [])
        block-element #js {}
        target #js {:classList #js {:contains (constantly false)}
                    :closest (constantly nil)}
        suppressed (atom [])
        pointer-event #js {:target target :buttons 1 :shiftKey false
                           :preventDefault #(swap! suppressed conj :prevent-default)
                           :stopPropagation #(swap! suppressed conj :stop-propagation)}
        outside-event #js {:type "mousedown" :target target}]
    (set! (.-getElementsByClassName block-element) (fn [_] #js []))
    (swap! state/state assoc :ui/scrolling? (atom false))
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-editor-action (constantly nil)
                    state/editor-in-composition? (constantly false)
                    state/get-input (constantly input)
                    state/get-edit-input-id (constantly (.-id input))
                    state/get-current-repo (constantly test-helper/test-db)
                    state/get-selection-blocks (constantly [])
                    state/get-selection-start-block-or-first (constantly nil)
                    state/block-content-max-length (constantly 10000)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/pub-event!
                    (fn [[event payload]]
                      (case event
                        :editor/upsert-type-block
                        (-> transition
                            (p/catch (fn [error]
                                       (editor/rollback-math-transition!
                                        (:math-transition-rollback payload))
                                       (p/rejected error))))
                        :editor/save-code-editor (p/resolved nil)
                        (p/resolved nil)))
                    state/set-editing! (fn [_ _ block & _]
                                         (swap! calls conj [:switch (:block/uuid block)])
                                         (reset! *editing-block block))
                    state/set-selection-start-block! (constantly nil)
                    state/clear-edit! #(swap! calls conj [:clear (:block/uuid @*editing-block)])
                    db/entity (fn [lookup]
                                (cond
                                  (contains? #{1 [:block/uuid (:block/uuid old-block)]} lookup) old-block
                                  (contains? #{2 [:block/uuid (:block/uuid target-block)]} lookup) target-block))
                    db/get-db (constantly ::db)
                    db-async/<get-block (fn [& _] (p/resolved target-block))
                    editor/save-current-block!
                    #(swap! calls conj [:save (:block/uuid @*editing-block)
                                        (.-value input)
                                        (:logseq.property.node/display-type @*editing-block)])
                    editor/clear-selection! (constantly nil)
                    editor/unhighlight-blocks! (constantly nil)
                    mobile-util/mobile-focus-hidden-input (constantly nil)
                    util/mobile? (constantly false)
                    util/meta-key? (constantly false)
                    util/rec-get-node (fn [_ class-name]
                                        (when (= "ls-block" class-name) block-element))
                    util/link? (constantly false)
                    util/time? (constantly false)
                    util/input? (constantly false)
                    util/audio? (constantly false)
                    util/video? (constantly false)
                    util/details-or-summary? (constantly false)
                    util/sup? (constantly false)
                    dom/has-class? (constantly false)
                    dom/closest (constantly nil)]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$$$" 4 4))
      ;; This mirrors browser order: target pointerdown runs before the window
      ;; mousedown outside hook. Both returned promises are retained so the
      ;; deferred can be rejected without p/with-redefs awaiting either first.
      (let [pointer-result (#'block-component/block-content-on-pointer-down
                            pointer-event target-block "target" "edit-target" "target"
                            {:container-id 17})
            outside-result (#'editor-component/editor-on-hide
                            {:config nil} :click outside-event true)]
        (is (empty? @calls)
            "Neither target handler nor outside hook may save/switch while pending")
        (is (= [:prevent-default :stop-propagation] @suppressed)
            "The central barrier synchronously suppresses the pending pointer event")
        (p/reject! transition (ex-info "synthetic rejection" {}))
        (p/let [_ (p/all [pointer-result outside-result])]
          (is @focused?)
          (is (= "$$$$" (.-value input)))
          (is (= (:block/uuid target-block) (:block/uuid @*editing-block)))
          (is (= [:save (:block/uuid old-block) "$$$$" nil]
                 (first @calls))
              "Rollback must restore the old UUID before any generic save")
          (is (= [:switch (:block/uuid target-block)] (last @calls))))))))

(deftest-async pending-math-transition-blocks-enter-and-selection-navigation
  (let [block {:db/id 1
               :block/uuid (random-uuid)
               :block/title "ordinary"}
        *editing-block (atom block)
        transition (p/deferred)
        {:keys [input]} (math-transition-input "$$x$$")
        calls (atom [])
        container #js {}]
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-input (constantly input)
                    state/get-edit-input-id (constantly (.-id input))
                    state/get-current-repo (constantly test-helper/test-db)
                    state/get-editor-block-container (constantly container)
                    state/editing? (constantly true)
                    state/selection? (constantly false)
                    state/doc-mode-enter-for-new-line? (constantly false)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/pub-event! (constantly transition)
                    state/exit-editing-and-set-selected-blocks!
                    (fn [& _] (swap! calls conj :navigate))
                    db/entity (constantly block)
                    editor/get-state (constantly {:node nil})
                    editor/inside-of-single-block (constantly false)
                    editor/keydown-new-block (fn [_] (swap! calls conj :enter))
                    editor/save-current-block! #(swap! calls conj :save)
                    util/scroll-to-block (constantly nil)]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$x$$" 5 5))
      ;; Keep both returned Promises as values. `p/with-redefs` sequences its
      ;; top-level forms, so placing either call in a separate form would await
      ;; it before this test can settle the transition.
      (let [enter-result (editor/keydown-new-block-handler nil)
            navigation-result (#'editor/select-block-up-down :down)]
        (is (empty? @calls)
            "Enter and keyboard navigation must not mutate/exit before settlement")
        (p/resolve! transition :committed)
        (p/let [_ (p/all [enter-result navigation-result])]
          (is (= 1 (count (filter #{:enter} @calls))))
          (is (= [:save :navigate]
                 (filterv #{:save :navigate} @calls))))))))

(deftest-async settled-math-transition-token-rejects-stale-rollback
  (let [block {:db/id 1
               :block/uuid (random-uuid)
               :block/title "ordinary"}
        *editing-block (atom block)
        transition (p/deferred)
        {:keys [input]} (math-transition-input "$$x$$")
        rollback (atom nil)]
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-input (constantly input)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/pub-event! (fn [[_ payload]]
                                       (reset! rollback (:math-transition-rollback payload))
                                       transition)
                    db/entity (constantly block)]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$x$$" 5 5))
      (is (uuid? (:transition-token @rollback))
          "Every optimistic transition needs a unique rollback token")
      (p/resolve! transition :committed)
      (p/let [_ (p/delay 0)]
        (reset! *editing-block (assoc block :block/title "newer ordinary"))
        (set! (.-value input) "newer ordinary")
        (editor/rollback-math-transition! @rollback)
        (is (= "newer ordinary" (.-value input)))
        (is (= "newer ordinary" (:block/title @*editing-block))
            "A settled/stale token cannot roll back a later state on the same UUID")))))

(deftest-async committed-math-transition-is-one-real-db-and-editor-unit
  (load-test-files
   [{:page {:block/title "Math atomic lifecycle"}
     :blocks [{:block/title "$$$$"}]}])
  (let [block (test-helper/find-block-by-content "$$$$")
        block-uuid (:block/uuid block)
        *editing-block (atom block)
        {:keys [input]} (math-transition-input "$$$$")
        tx-reports (atom [])
        previous-document (.-document js/globalThis)
        listener-key ::math-transition]
    (d/listen! (db/get-db test-helper/test-db false) listener-key
               #(when (= :upsert-type-block (get-in % [:tx-meta :outliner-op]))
                  (swap! tx-reports conj %)))
    (set! (.-document js/globalThis) #js {:activeElement input})
    (->
     (p/with-redefs [state/get-edit-block #(deref *editing-block)
                     state/get-editor-args (constantly nil)
                     state/get-input (constantly input)
                     state/get-edit-input-id (constantly (.-id input))
                     state/get-current-repo (constantly test-helper/test-db)
                     state/set-block-content-and-last-pos!
                     (fn [_ title pos]
                       (set! (.-value input) title)
                       (.setSelectionRange input pos pos))
                     state/set-state! (fn [path value]
                                        (when (= :editor/block path)
                                          (reset! *editing-block value)))
                     state/pub-event! events/handle]
       (is (#'editor/maybe-convert-current-block-to-math!
            (.-id input) "$$$$" 4 4))
       (let [await-fn (resolve 'frontend.handler.editor/await-pending-math-transition!)]
         (is (fn? await-fn))
         (p/let [_ (if (fn? await-fn)
                     (await-fn block-uuid)
                     (p/resolved nil))
                 committed (db/entity [:block/uuid block-uuid])]
           (is (= ["" :math]
                  [(:block/title committed)
                   (:logseq.property.node/display-type committed)]))
           (is (= ["" 0 0]
                  [(.-value input) (.-selectionStart input) (.-selectionEnd input)]))
           (is (identical? input (.-activeElement js/document)))
           (is (= 1 (count @tx-reports))
               "Title and type must be one real transaction and therefore one undo unit"))))
     (p/finally (fn []
                  (d/unlisten! (db/get-db test-helper/test-db false) listener-key)
                  (set! (.-document js/globalThis) previous-document))))))

(deftest-async rejected-empty-math-real-apply-has-no-partial-title-or-type
  (load-test-files
   [{:page {:block/title "Math rejected lifecycle"}
     :blocks [{:block/title "$$$$"}]}])
  (let [block (test-helper/find-block-by-content "$$$$")
        block-uuid (:block/uuid block)
        *editing-block (atom block)
        {:keys [input focused?]} (math-transition-input "$$$$")
        real-apply-outliner-ops db-transact/apply-outliner-ops
        apply-called? (atom false)]
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-input (constantly input)
                    state/get-edit-input-id (constantly (.-id input))
                    state/get-current-repo (constantly test-helper/test-db)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/pub-event! events/handle
                    db-transact/apply-outliner-ops
                    (fn [conn ops opts]
                      (reset! apply-called? true)
                      (try
                        ;; The valid Math save is applied to the real temporary
                        ;; transaction before this valid-shaped second op
                        ;; rejects. The batch must publish neither change.
                        (real-apply-outliner-ops
                         conn
                         (conj (vec ops) [:rename-page [block-uuid ""]])
                         opts)
                        (catch :default error
                          (p/rejected error))))]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$$$" 4 4))
      (p/let [outcome (editor/await-pending-math-transition! block-uuid)
              persisted (db/entity [:block/uuid block-uuid])]
        (is @apply-called? "The rejection must come from the real DB apply path")
        (is (= :rolled-back (:status outcome)))
        (is (= ["$$$$" nil]
               [(:block/title persisted)
                (:logseq.property.node/display-type persisted)])
            "Rejected real batch apply must leave the DB wholly ordinary")
        (is (= "$$$$" (.-value input)))
        (is (= 4 (.-selectionStart input)))
        (is @focused?)))))

(deftest-async postcommit-math-readback-mismatch-restores-db-before-ui-rollback
  (load-test-files
   [{:page {:block/title "Math postcommit mismatch lifecycle"}
     :blocks [{:block/title "$$$$"}]}])
  (let [block (test-helper/find-block-by-content "$$$$")
        block-uuid (:block/uuid block)
        *editing-block (atom block)
        {:keys [input focused?]} (math-transition-input "$$$$")
        real-apply-outliner-ops db-transact/apply-outliner-ops
        real-db-entity db/entity
        committed? (atom false)
        mismatch-served? (atom false)
        tx-reports (atom [])
        listener-key ::math-postcommit-mismatch]
    (d/listen! (db/get-db test-helper/test-db false) listener-key
               #(swap! tx-reports conj %))
    (->
     (p/with-redefs [state/get-edit-block #(deref *editing-block)
                     state/get-editor-args (constantly nil)
                     state/get-input (constantly input)
                     state/get-edit-input-id (constantly (.-id input))
                     state/get-current-repo (constantly test-helper/test-db)
                     state/set-block-content-and-last-pos!
                     (fn [_ title pos]
                       (set! (.-value input) title)
                       (.setSelectionRange input pos pos))
                     state/set-state! (fn [path value]
                                        (when (= :editor/block path)
                                          (reset! *editing-block value)))
                     state/pub-event! events/handle
                     db-transact/apply-outliner-ops
                     (fn [conn ops opts]
                       (p/let [result (real-apply-outliner-ops conn ops opts)
                               _ (reset! committed? true)]
                         result))
                     db/entity
                     (fn [lookup]
                       (let [entity (real-db-entity lookup)]
                         (if (and @committed?
                                  (= lookup [:block/uuid block-uuid])
                                  (compare-and-set! mismatch-served? false true))
                           (assoc entity :block/title "forced postcommit mismatch")
                           entity)))]
       (is (#'editor/maybe-convert-current-block-to-math!
            (.-id input) "$$$$" 4 4))
       (p/let [outcome (editor/await-pending-math-transition! block-uuid)
               persisted (real-db-entity [:block/uuid block-uuid])]
         (is @mismatch-served?)
         (is (= :rolled-back (:status outcome)))
         (is (= ["$$$$" nil]
                [(:block/title persisted)
                 (:logseq.property.node/display-type persisted)])
             "DB preimage must be restored before the editor becomes ordinary")
         (is (= ["$$$$" nil]
                [(.-value input)
                 (:logseq.property.node/display-type @*editing-block)]))
         (is @focused?)
         (is (= 2 (count @tx-reports))
             "The committed Math tx requires one real compensating tx")))
     (p/finally
      (fn []
        (d/unlisten! (db/get-db test-helper/test-db false) listener-key))))))

(defn- <caught-result
  [promise]
  ;; Resolve both branches explicitly so the test observes the rejection
  ;; without asking the async test harness to treat it as a test failure.
  (js/Promise.
   (fn [resolve _reject]
     (.then (p/promise promise)
            (fn [value] (resolve {:unexpected-resolution value}))
            resolve))))

(defn- <wait-for-value
  ([*value expected]
   (<wait-for-value *value expected (+ (js/Date.now) 1000)))
  ([*value expected deadline-ms]
   (cond
     (= expected @*value)
     (p/resolved nil)

     (>= (js/Date.now) deadline-ms)
     (p/rejected
      (ex-info "Timed out waiting for test state"
               {:expected expected :actual @*value}))

     :else
     (p/let [_ (p/delay 0)]
       (<wait-for-value *value expected deadline-ms)))))

(defn- <assert-math-recovery-failure-stays-fail-closed!
  [restore-mode]
  (let [block (test-helper/find-block-by-content "$$$$")
        block-uuid (:block/uuid block)
        target-block (test-helper/find-block-by-content "target")
        *editing-block (atom block)
        {:keys [input]} (math-transition-input "$$$$")
        real-apply-outliner-ops db-transact/apply-outliner-ops
        real-db-entity db/entity
        apply-count (atom 0)
        committed? (atom false)
        mismatch-served? (atom false)
        restore-result (p/deferred)
        side-effects (atom [])
        expected-error-data {:type :math-transition/recovery-failed}]
    (p/with-redefs [state/get-edit-block #(deref *editing-block)
                    state/get-editor-args (constantly nil)
                    state/get-input (constantly input)
                    state/get-edit-input-id (constantly (.-id input))
                    state/get-current-repo (constantly test-helper/test-db)
                    state/get-editor-block-container (constantly #js {})
                    state/editing? (constantly true)
                    state/selection? (constantly false)
                    state/doc-mode-enter-for-new-line? (constantly false)
                    state/set-block-content-and-last-pos!
                    (fn [_ title pos]
                      (set! (.-value input) title)
                      (.setSelectionRange input pos pos))
                    state/set-state! (fn [path value]
                                       (when (= :editor/block path)
                                         (reset! *editing-block value)))
                    state/clear-edit! #(swap! side-effects conj :clear)
                    state/pub-event! events/handle
                    editor/save-current-block! #(swap! side-effects conj :save)
                    editor/get-state (constantly {:node nil})
                    editor/inside-of-single-block (constantly false)
                    editor/keydown-new-block #(swap! side-effects conj :enter)
                    editor/move-cross-boundary-up-down-now
                    (fn [direction {:keys [block]}]
                      (swap! side-effects conj
                             [:navigate direction (:block/uuid block)]))
                    util/scroll-to-block (constantly nil)
                    db-transact/apply-outliner-ops
                    (fn [conn ops opts]
                      (case (swap! apply-count inc)
                        1 (p/let [result (real-apply-outliner-ops conn ops opts)
                                  _ (reset! committed? true)]
                            result)
                        2 (case restore-mode
                            :restore-reject
                            restore-result

                            :restore-readback-mismatch
                            ;; Model a worker outcome that resolves without
                            ;; publishing the compensating transaction. The
                            ;; following authoritative readback remains Math.
                            restore-result)
                        (p/rejected (ex-info "unexpected extra transaction" {}))))
                    db/entity
                    (fn [lookup]
                      (let [entity (real-db-entity lookup)]
                        (if (and @committed?
                                 (= lookup [:block/uuid block-uuid])
                                 (compare-and-set! mismatch-served? false true))
                          (assoc entity :block/title "forced postcommit mismatch")
                          entity)))]
      (is (#'editor/maybe-convert-current-block-to-math!
           (.-id input) "$$$$" 4 4))
      (let [escape-result (editor/escape-editing)
            enter-result (editor/keydown-new-block-handler nil)
            navigation-result
            (editor/move-cross-boundary-up-down
             :down {:block target-block})
            direct-result (editor/await-pending-math-transition! block-uuid)
            caught-results (mapv <caught-result
                                 [escape-result enter-result
                                  navigation-result direct-result])]
        (p/let [_ (<wait-for-value apply-count 2)
                _ (do
                    (case restore-mode
                      :restore-reject
                      (p/reject!
                       restore-result
                       (ex-info "secret synthetic restore rejection"
                                {:secret "$$$$"}))
                      :restore-readback-mismatch
                      (p/resolve! restore-result {:blocks []}))
                    nil)
                results (p/all caught-results)
                repeated-error
                (<caught-result
                 (editor/await-pending-math-transition! block-uuid))
                persisted (real-db-entity [:block/uuid block-uuid])]
          (is @mismatch-served?)
          (is (= 2 @apply-count))
          (is (= {:unexpected-resolution {:status :recovery-failed}}
                 (nth results 1))
              "The actual Enter boundary consumes only after its p/let aborts")
          (doseq [error (conj [(nth results 0)
                               (nth results 2)
                               (nth results 3)]
                              repeated-error)]
            (is (= "Math transition recovery failed" (ex-message error)))
            (is (= expected-error-data (ex-data error))
                "Recovery failure is typed and contains no graph content"))
          (is (empty? @side-effects)
              "Escape, Enter and old-to-new navigation must remain blocked")
          (is (= ["" :math]
                 [(:block/title persisted)
                  (:logseq.property.node/display-type persisted)]))
          (is (= [block-uuid "" :math]
                 [(:block/uuid @*editing-block)
                  (.-value input)
                  (:logseq.property.node/display-type @*editing-block)])
              "The editor remains aligned with the committed Math DB state"))))))

(deftest-async rejected-math-compensation-remains-pending-and-blocks-exit
  (load-test-files
   [{:page {:block/title "Math compensation rejection"}
     :blocks [{:block/title "$$$$"}
              {:block/title "target"}]}])
  (<assert-math-recovery-failure-stays-fail-closed! :restore-reject))

(deftest-async mismatched-math-compensation-remains-pending-and-blocks-navigation
  (load-test-files
   [{:page {:block/title "Math compensation mismatch"}
     :blocks [{:block/title "$$$$"}
              {:block/title "target"}]}])
  (<assert-math-recovery-failure-stays-fail-closed!
   :restore-readback-mismatch))

(deftest-async actual-math-event-boundaries-consume-one-recovery-failure
  (let [old-block {:db/id 1 :block/uuid (random-uuid) :block/title "$$$$"}
        target-block {:db/id 2 :block/uuid (random-uuid) :block/title "target"}
        *editing-block (atom old-block)
        transition (p/deferred)
        {:keys [input]} (math-transition-input "$$$$")
        side-effects (atom [])
        reports (atom [])
        unhandled (atom [])
        block-element #js {}
        target #js {:classList #js {:contains (constantly false)}
                    :closest (constantly nil)}
        pointer-event #js {:target target :buttons 1 :shiftKey false}
        outside-event #js {:type "mousedown" :target target}
        escape-event #js {:type "keydown" :target target}
        key-event #js {:preventDefault (fn []) :stopPropagation (fn [])}
        bottom-block #js {}
        bottom-row #js {}
        bottom-event #js {:key "ArrowDown"
                          :currentTarget bottom-row
                          :preventDefault (fn [])
                          :stopPropagation (fn [])}
        bottom-up-event #js {:key "ArrowUp"
                             :currentTarget bottom-row
                             :preventDefault (fn [])
                             :stopPropagation (fn [])}
        property-target #js {:getAttribute (fn [attribute]
                                             (when (= attribute "data-property-nav-mode")
                                               "edit"))}
        property-event #js {:key "ArrowDown"
                            :currentTarget property-target
                            :preventDefault (fn [])
                            :stopPropagation (fn [])}
        property-focus-target #js {:getAttribute (fn [attribute]
                                                   (when (= attribute "data-property-nav-mode")
                                                     "focus"))}
        property-focus-event #js {:key "ArrowUp"
                                  :currentTarget property-focus-target
                                  :preventDefault (fn [])
                                  :stopPropagation (fn [])}
        number-state-index (atom -1)
        number-ref-index (atom -1)
        number-input-props (atom nil)
        number-outer #js {}
        number-input #js {:selectionStart 0 :selectionEnd 0 :value "7"}
        previous-console-error (.-error js/console)
        previous-document (.-document js/globalThis)
        previous-react (.-React js/globalThis)
        unhandled-handler #(swap! unhandled conj %)]
    (set! (.-getElementsByClassName block-element) (fn [_] #js []))
    (set! (.-closest bottom-row) (constantly bottom-block))
    (set! (.-blur bottom-row) #(swap! side-effects conj :bottom-blur))
    (set! (.-focus number-outer) #(swap! side-effects conj :number-focus))
    (set! (.-focus number-input) (fn []))
    (swap! state/state assoc :ui/scrolling? (atom false))
    (.on js/process "unhandledRejection" unhandled-handler)
    (set! (.-document js/globalThis)
          #js {:querySelector (constantly nil)
               :activeElement property-target})
    (set! (.-React js/globalThis) react)
    (set! (.-error js/console) (fn [& args] (swap! reports conj (vec args))))
    (->
     (p/with-redefs [state/get-edit-block #(deref *editing-block)
                     state/get-editor-args (constantly nil)
                     state/get-editor-action (constantly nil)
                     state/editor-in-composition? (constantly false)
                     state/get-input (constantly input)
                     state/get-edit-input-id (constantly (.-id input))
                     state/get-current-repo (constantly test-helper/test-db)
                     state/get-selection-blocks (constantly [])
                     state/get-selection-start-block-or-first (constantly nil)
                     state/block-content-max-length (constantly 10000)
                     state/editing? (constantly true)
                     state/set-block-content-and-last-pos!
                     (fn [_ title pos]
                       (set! (.-value input) title)
                       (.setSelectionRange input pos pos))
                     state/set-state! (fn [path value]
                                        (when (= :editor/block path)
                                          (reset! *editing-block value)))
                     state/pub-event!
                     (fn [[event _payload]]
                       (if (= :editor/upsert-type-block event)
                         transition
                         (p/resolved nil)))
                     state/set-editing! (fn [& _]
                                          (swap! side-effects conj :switch))
                     state/set-selection-start-block! (constantly nil)
                     state/clear-edit! #(swap! side-effects conj :clear)
                     state/get-editor-block-container (constantly block-element)
                     db/entity (fn [lookup]
                                 (cond
                                   (contains? #{1 [:block/uuid (:block/uuid old-block)]} lookup)
                                   old-block
                                   (contains? #{2 [:block/uuid (:block/uuid target-block)]} lookup)
                                   target-block))
                     db/get-db (constantly ::db)
                     db/get-today-journal-title (constantly "Today")
                     db/get-page-format (constantly :markdown)
                     db-async/<get-block (fn [& _] (p/resolved target-block))
                     editor/save-current-block! #(swap! side-effects conj :save)
                     editor/insert #(swap! side-effects conj :insert)
                     editor/edit-block! (fn [& _] (swap! side-effects conj :edit))
                     editor/api-insert-new-block!
                     (fn [& _] (swap! side-effects conj :api-insert))
                     editor/clear-selection! (constantly nil)
                     editor/unhighlight-blocks! (constantly nil)
                     editor/get-state (constantly {:node nil})
                     editor/inside-of-single-block (constantly false)
                     editor/keydown-new-block #(swap! side-effects conj :enter)
                     editor/keydown-up-down-handler
                     (fn [_ _]
                       (editor/move-cross-boundary-up-down
                        :down {:block target-block}))
                     editor/keydown-arrow-handler
                     (fn [_]
                       (editor/move-to-block-when-cross-boundary
                        :right {:block target-block}))
                     editor/move-cross-boundary-up-down-now
                     (fn [& _] (swap! side-effects conj :navigate))
                     editor/move-to-block-when-cross-boundary-now
                     (fn [& _] (swap! side-effects conj :navigate-left-right))
                     editor/move-property-focus-up-down
                     (fn [& _] (swap! side-effects conj :property-focus))
                     editor/auto-complete? (constantly false)
                     editor/in-page-preview? (constantly false)
                     editor/in-shui-popup? (constantly false)
                     mobile-nav/pop-stack! #(swap! side-effects conj :pop)
                     property-handler/remove-block-property!
                     (fn [& _] (swap! side-effects conj :delete-property))
                     db-property/property-value-content (constantly 0)
                     db-property-handler/set-block-property!
                     (fn [& _] (swap! side-effects conj :number-property))
                     state/get-config (constantly {})
                     state/get-current-page (constantly "today")
                     state/get-edit-content (constantly "")
                     state/get-timestamp-block (constantly nil)
                     mobile-util/mobile-focus-hidden-input (constantly nil)
                     util/mobile? (constantly false)
                     util/meta-key? (constantly false)
                     util/get-selection-direction (constantly "forward")
                     util/rec-get-node (fn [_ class-name]
                                         (when (= "ls-block" class-name) block-element))
                     util/link? (constantly false)
                     util/time? (constantly false)
                     util/input? (constantly false)
                     util/audio? (constantly false)
                     util/video? (constantly false)
                     util/details-or-summary? (constantly false)
                     util/sup? (constantly false)
                     cursor/get-caret-pos (constantly #js {})
                     cursor/textarea-cursor-rect-last-row? (constantly true)
                     hooks/use-state
                     (fn [initial]
                       (case (swap! number-state-index inc)
                         0 [true (fn [& _])]
                         1 ["7" (fn [& _])]
                         2 [(atom "7") (fn [& _])]
                         [initial (fn [& _])]))
                     hooks/use-ref
                     (fn [_]
                       #js {:current (if (zero? (swap! number-ref-index inc))
                                       number-outer
                                       number-input)})
                     hooks/use-effect! (fn [& _])
                     shui/input
                     (fn [props]
                       (reset! number-input-props props)
                       (.createElement react "input" nil))
                     dom/attr (fn [_ attribute]
                                (case attribute
                                  "blockid" (str (:block/uuid old-block))
                                  "containerid" "17"
                                  nil))
                     dom/has-class? (constantly false)
                     dom/closest (constantly nil)]
       (is (#'editor/maybe-convert-current-block-to-math!
            (.-id input) "$$$$" 4 4))
       (.renderToStaticMarkup
        react-dom-server
        (property-value/single-number-input
         {:db/id 11 :user.property/test 0}
         {:db/ident :user.property/test}
         {:db/id 12}
         false))
       ;; Browser order can invoke target pointerdown and the window outside
       ;; hook for the same gesture. These actual UI boundaries deliberately
       ;; discard their return values, as React/shui do.
       (let [_ (#'block-component/block-content-on-pointer-down
                pointer-event target-block "target" "edit-target" "target"
                {:container-id 17})
             _ (#'editor-component/editor-on-hide
                {:config nil} :click outside-event true)
             _ (#'editor-component/editor-on-hide
                {:config nil} :esc escape-event false)
             _ (editor/keydown-new-block-handler key-event)
             _ (editor/keydown-new-line-handler key-event)
             _ ((editor/shortcut-up-down :down) key-event)
             _ ((editor/shortcut-select-up-down :down) key-event)
             _ ((editor/shortcut-left-right :right) key-event)
             _ ((editor/on-select-block :down) key-event)
             _ (#'block-component/handle-bottom-properties-row-key-down!
                bottom-event)
             _ (#'block-component/handle-bottom-properties-row-key-down!
                bottom-up-event)
             _ ((:on-key-down
                 (#'property-value/property-value-block-container-props
                  {:db/ident :user.property/test}))
                property-event)
             _ (set! (.-activeElement js/document) property-focus-target)
             _ ((:on-key-down
                 (#'property-value/property-value-block-container-props
                  {:db/ident :user.property/test}))
                property-focus-event)
             _ ((:on-blur @number-input-props) #js {})
             _ ((:on-key-down @number-input-props) #js {:key "Escape"})
             _ ((:on-key-down @number-input-props) #js {:key "Enter"})
             _ (#'property-value/delete-block-property!
                {:db/id 11}
                {:db/ident :user.property/test}
                {})
             _ (quick-capture/quick-capture #js {:content "shared"})
             _ (mobile-intent/handle-payload
                {:text "shared"
                 :resources [{:type "text/plain"
                              :name "shared"
                              :ext "txt"}]})
             _ (mobile-nav/install-native-bridge!)
             _ ((.-onNativePop (.-LogseqNative js/window)))]
         (p/reject! transition
                    (ex-info "Math transition recovery failed"
                             {:type :math-transition/recovery-failed}))
         (p/let [_ (p/delay 25)]
           (let [blocked? (resolve
                           'frontend.handler.editor/math-transition-recovery-blocked?)]
             (is (fn? blocked?)
                 "A shared synchronous recovery latch must guard component roots")
             (when (fn? blocked?)
               (is (true? (blocked?))))
             ((:on-change @number-input-props)
              #js {:target #js {:value "8"}}))
           (p/let [_ (p/delay 10)]
             (is (empty? @unhandled)
                 "Discarded actual DOM boundary Promises must not reject unhandled")
             (is (= [["Math transition recovery failed" "block-pointer"]]
                    @reports)
                 "One content-free report covers the same failure at every boundary")
             (is (empty? @side-effects)
                 "Pointer/outside/Escape/Enter/navigation/capture/native back remain fail-closed")))))
     (p/finally
      (fn []
        (.off js/process "unhandledRejection" unhandled-handler)
        (set! (.-document js/globalThis) previous-document)
        (set! (.-React js/globalThis) previous-react)
        (set! (.-error js/console) previous-console-error))))))

(deftest-async native-pop-boundary-preserves-unknown-rejection
  (let [unknown (ex-info "ordinary native navigation error" {:type :unknown})
        previous-document (.-document js/globalThis)]
    (set! (.-document js/globalThis)
          #js {:querySelector (constantly nil)})
    (->
     (p/with-redefs [editor/await-pending-math-transition!
                     (fn [] (p/rejected unknown))
                     state/get-selection-blocks (constantly [])
                     state/editing? (constantly true)]
       (mobile-nav/install-native-bridge!)
       (p/let [native-observed (<caught-result
                                ((.-onNativePop (.-LogseqNative js/window))))
               plugin-observed (<caught-result
                                (editor-api/exit_editing_mode false))]
         (is (identical? unknown native-observed)
             "The native callback consumes only typed recovery failures")
         (is (identical? unknown plugin-observed)
             "The plugin API must not force an unknown rejection to nil")))
     (p/finally
      #(set! (.-document js/globalThis) previous-document)))))

(deftest-async plugin-exit-is-promise-void-for-success-and-typed-failure
  (let [typed (ex-info "Math transition recovery failed"
                       {:type :math-transition/recovery-failed})
        unknown (ex-info "ordinary plugin exit failure" {:type :unknown})
        previous-console-error (.-error js/console)]
    (set! (.-error js/console) (fn [& _]))
    (-> (p/let [success (p/with-redefs [editor/escape-editing
                                        (fn [& _] (p/resolved :internal-success))]
                           (editor-api/exit_editing_mode false))
                typed-result (p/with-redefs [editor/escape-editing
                                             (fn [& _] (p/rejected typed))]
                               (editor-api/exit_editing_mode false))
                unknown-result (<caught-result
                                (p/with-redefs [editor/escape-editing
                                               (fn [& _] (p/rejected unknown))]
                                  (editor-api/exit_editing_mode false)))]
          (is (nil? success)
              "Plugin exit success exposes Promise<void>, not an internal status")
          (is (nil? typed-result)
              "A consumed typed recovery failure also exposes Promise<void>")
          (is (identical? unknown unknown-result)
              "Unknown plugin exit failures remain rejected"))
        (p/finally #(set! (.-error js/console) previous-console-error)))))

(deftest-async math-boundary-consumer-does-not-hide-unknown-errors
  (let [consumer-var
        (resolve 'frontend.handler.editor/consume-math-transition-boundary!)]
    (is (fn? consumer-var))
    (if (fn? consumer-var)
      (let [unknown (ex-info "ordinary unknown error" {:type :unknown})]
        (p/let [observed (<caught-result
                          (consumer-var :test-boundary
                                        (p/rejected unknown)))]
          (is (identical? unknown observed)
              "Only typed recovery failures may be consumed")))
      (p/resolved nil))))

(defn- take-edit-block-fn!
  ([]
   (state/take-edit-block-fn!))
  ([tx-id]
   (state/take-edit-block-fn! tx-id)))

(defn- delete-block
  [db block {:keys [embed? on-delete edit-content on-edit schedule-immediately?]}]
  (let [sibling-block (ldb/get-left-sibling (d/entity db (:db/id block)))
        first-block (ldb/get-left-sibling sibling-block)
        block-dom-id "ls-block-block-to-delete"
        sibling-dom-id "ls-block-sibling-block"
        sibling-dom #js {:id sibling-dom-id
                         :getAttribute #({"blockid" (str (:block/uuid sibling-block))
                                          "data-embed" (if embed? "true" "false")} %)}
        edit-content (or edit-content (:block/title block))
        previous-repo (:git/current-repo @state/state)]
    (swap! state/state assoc :git/current-repo test-helper/test-db)
    (-> (p/with-redefs
         [editor/get-state (constantly {:block-id (:block/uuid block)
                                        :block-parent-id block-dom-id
                                        :config {:embed? embed?}
                                        :value edit-content})
                  ;; stub for delete-block
          gdom/getElement (constantly #js {:id block-dom-id})
                  ;; stub since not testing moving
          editor/edit-block! (fn [block pos opts]
                               (when (fn? on-edit)
                                 (on-edit block pos opts))
                               nil)
          state/get-edit-content (constantly edit-content)
          util/get-prev-block-non-collapsed-non-embed (constantly sibling-dom)
                  ;; stub b/c of js/document
          state/get-selection-blocks (constantly [])
          util/get-blocks-noncollapse (constantly (mapv
                                                   (fn [m]
                                                     #js {:id (:id m)
                                                                  ;; for dom/attr
                                                          :getAttribute #({"blockid" (str (:block-uuid m))
                                                                           "data-embed" (if embed? "true" "false")} %)})
                                                   [{:id "ls-block-first-block"
                                                     :block-uuid (:block/uuid first-block)}
                                                    {:id sibling-dom-id
                                                     :block-uuid (:block/uuid sibling-block)}
                                                    {:id block-dom-id
                                                     :block-uuid (:block/uuid block)}]))
          util/schedule (fn [f]
                          (if schedule-immediately?
                            (f)
                            (js/setTimeout f 0)))]
          (p/do!
           (editor/delete-block! test-helper/test-db)
           (when (fn? on-delete)
             (on-delete))))
        (p/finally
          (fn []
            (swap! state/state assoc :git/current-repo previous-repo))))))

(deftest-async delete-block-async!
  (testing "backspace deletes empty block"
    (load-test-files
     [{:page {:block/title "page1"}
       :blocks
       [{:block/title "b1"}
        {:block/title "b2"}
        {:block/title ""}]}])
    (p/let [conn (db/get-db test-helper/test-db false)
            block (->> (d/q '[:find (pull ?b [*])
                              :where [?b :block/title ""]
                              [?p :block/name "page1"]
                              [?b :block/page ?p]]
                            @conn)
                       ffirst)
            edit-calls (atom [])]
      (delete-block @conn block
                    {:on-edit (fn [block pos opts]
                                (swap! edit-calls conj {:block block
                                                        :pos pos
                                                        :opts opts}))
                     :on-delete (fn []
                                  (let [updated-blocks (->> (d/q '[:find (pull ?b [*])
                                                                   :where
                                                                   [?p :block/name "page1"]
                                                                   [?b :block/page ?p]
                                                                   [?b :block/title]
                                                                   [(missing? $ ?b :logseq.property/deleted-at)]]
                                                                 @conn)
                                                            (map (comp :block/title first)))
                                        deleted-blocks (->> (d/q '[:find (pull ?b [*])
                                                                   :where
                                                                   [?b :block/title ""]]
                                                                  @conn)
                                                            (map first))]
                                    (is (= ["b1" "b2"] updated-blocks) "Visible page blocks stay on the page")
                                    (is (empty? deleted-blocks) "Deleted block is removed from page db")
                                    (is (= {:block "b2"
                                            :pos 2
                                            :opts {:custom-content "b2"
                                                   :tail-len 0
                                                   :container-id nil}}
                                           (some-> (last @edit-calls)
                                                   (update :block :block/title)))
                                        "Deleting an empty block should focus the previous block")))})))

  (testing "backspace deletes empty block in embedded context"
    ;; testing embed at this layer doesn't require an embed block since
    ;; delete-block handles all the embed setup
    (p/let [conn (db/get-db test-helper/test-db false)
            block (->> (d/q '[:find (pull ?b [*])
                              :where [?b :block/title ""]
                              [?p :block/name "page1"]
                              [?b :block/page ?p]]
                            @conn)
                       ffirst)]
      (delete-block @conn block
                    {:embed? true
                     :on-delete (fn []
                                  (let [updated-blocks (->> (d/q '[:find (pull ?b [*])
                                                                   :where
                                                                   [?p :block/name "page1"]
                                                                   [?b :block/page ?p]
                                                                   [?b :block/title]
                                                                   [(missing? $ ?b :logseq.property/deleted-at)]]
                                                                 @conn)
                                                            (map (comp :block/title first)))
                                        deleted-blocks (->> (d/q '[:find (pull ?b [*])
                                                                   :where
                                                                   [?b :block/title ""]]
                                                                 @conn)
                                                            (map first))]
                                     (is (= ["b1" "b2"] updated-blocks) "Visible page blocks stay on the page")
                                     (is (empty? deleted-blocks) "Deleted block is removed from page db")))}))))

(deftest-async backspace-before-block-merges-into-previous-blank-asset-block
  (load-test-files
   [{:page {:block/title "page1"}
     :blocks
     [{:block/title "b1"}
      {:block/title ""
       :logseq.property.asset/type "png"
       :logseq.property.asset/checksum "blank-asset-checksum"
       :logseq.property.asset/size 1}
      {:block/title "after"}]}])
  (p/let [conn (db/get-db test-helper/test-db false)
          block (->> (d/q '[:find (pull ?b [*])
                            :where [?b :block/title "after"]
                            [?p :block/name "page1"]
                            [?b :block/page ?p]]
                          @conn)
                     ffirst)]
    (delete-block @conn block
                  {:on-delete (fn []
                                (let [visible-blocks (->> (d/q '[:find (pull ?b [*])
                                                                 :where
                                                                 [?p :block/name "page1"]
                                                                 [?b :block/page ?p]
                                                                 [?b :block/title]
                                                                 [(missing? $ ?b :logseq.property/deleted-at)]]
                                                               @conn)
                                                          (map first))
                                      visible-titles (map :block/title visible-blocks)
                                      asset-blocks (filter :logseq.property.asset/type visible-blocks)]
                                  (is (= ["b1" "after"] visible-titles))
                                  (is (= "after" (:block/title (first asset-blocks)))
                                      "Backspace before the following block should merge its title into the asset block")
                                  (is (= "png" (:logseq.property.asset/type (first asset-blocks)))
                                      "Merging must keep the previous block renderable as an asset")))})))

(deftest-async backspace-before-block-merges-into-previous-blank-comments-block
  (load-test-files
   [{:page {:block/title "page1"}
     :blocks
     [{:block/title "b1"}
      {:block/title ""
       :build/tags [:logseq.class/Comments]}
      {:block/title "after"}]}])
  (p/let [conn (db/get-db test-helper/test-db false)
          block (->> (d/q '[:find (pull ?b [*])
                            :where [?b :block/title "after"]
                            [?p :block/name "page1"]
                            [?b :block/page ?p]]
                          @conn)
                     ffirst)]
    (delete-block @conn block
                  {:on-delete (fn []
                                (let [visible-blocks (->> (d/q '[:find (pull ?b [* {:block/tags [:db/ident]}])
                                                                 :where
                                                                 [?p :block/name "page1"]
                                                                 [?b :block/page ?p]
                                                                 [?b :block/title]
                                                                 [(missing? $ ?b :logseq.property/deleted-at)]]
                                                               @conn)
                                                          (map first))
                                      visible-titles (map :block/title visible-blocks)
                                      comments-blocks (filter comments-model/comments-area? visible-blocks)]
                                  (is (= ["b1" "after"] visible-titles))
                                  (is (= "after" (:block/title (first comments-blocks)))
                                      "Backspace before the following block should merge its title into the Comments block")
                                  (is (= #{:logseq.class/Comments}
                                         (set (map :db/ident (:block/tags (first comments-blocks)))))
                                      "Merging must keep the previous block tagged as a Comments block")))})))

(deftest-async delete-at-empty-asset-end-merges-next-block-into-asset-block
  (load-test-files
   [{:page {:block/title "page1"}
     :blocks
     [{:block/title "b1"}
      {:block/title ""
       :logseq.property.asset/type "png"
       :logseq.property.asset/checksum "blank-asset-checksum"
       :logseq.property.asset/size 1}
      {:block/title "after"}]}])
  (p/let [conn (db/get-db test-helper/test-db false)
          asset-block (->> (d/q '[:find (pull ?b [*])
                                  :where [?b :logseq.property.asset/type "png"]
                                  [?p :block/name "page1"]
                                  [?b :block/page ?p]]
                                @conn)
                           ffirst)
          next-block (->> (d/q '[:find (pull ?b [*])
                                 :where [?b :block/title "after"]
                                 [?p :block/name "page1"]
                                 [?b :block/page ?p]]
                               @conn)
                          ffirst)
          asset-dom #js {:getAttribute #({"blockid" (str (:block/uuid asset-block))
                                          "containerid" nil} %)}]
    (-> (p/with-redefs [state/get-edit-content (constantly "")
                        util/get-prev-block-non-collapsed-non-embed (constantly asset-dom)
                        editor/edit-block! (constantly nil)]
          (p/do!
           (editor/delete-block-inner!
            test-helper/test-db
            {:block-id (:block/uuid next-block)
             :value (:block/title next-block)
             :config {}
             :block-container #js {}
             :current-block asset-block
             :next-block next-block
             :delete-concat? true})
           (let [visible-blocks (->> (d/q '[:find (pull ?b [*])
                                            :where
                                            [?p :block/name "page1"]
                                            [?b :block/page ?p]
                                            [?b :block/title]
                                            [(missing? $ ?b :logseq.property/deleted-at)]]
                                          @conn)
                                     (map first))
                 visible-titles (map :block/title visible-blocks)
                 asset-blocks (filter :logseq.property.asset/type visible-blocks)]
             (is (= ["b1" "after"] visible-titles))
             (is (= "after" (:block/title (first asset-blocks)))
                 "Delete at the end of an empty asset title should merge the next title into the asset block")
             (is (= "png" (:logseq.property.asset/type (first asset-blocks)))
                 "Delete merge must keep the current block renderable as an asset"))))
        (p/finally (fn []
                     (state/set-state! :editor/edit-block-fn nil))))))

(deftest-async delete-at-empty-comments-end-merges-next-block-into-comments-block
  (load-test-files
   [{:page {:block/title "page1"}
     :blocks
     [{:block/title "b1"}
      {:block/title ""
       :build/tags [:logseq.class/Comments]}
      {:block/title "after"}]}])
  (p/let [conn (db/get-db test-helper/test-db false)
          comments-block (->> (d/q '[:find (pull ?b [* {:block/tags [:db/ident]}])
                                      :where
                                      [?p :block/name "page1"]
                                      [?b :block/page ?p]
                                      [?b :block/tags :logseq.class/Comments]]
                                    @conn)
                               ffirst)
          next-block (->> (d/q '[:find (pull ?b [*])
                                 :where [?b :block/title "after"]
                                 [?p :block/name "page1"]
                                 [?b :block/page ?p]]
                               @conn)
                          ffirst)
          comments-dom #js {:getAttribute #({"blockid" (str (:block/uuid comments-block))
                                             "containerid" nil} %)}]
    (-> (p/with-redefs [state/get-edit-content (constantly "")
                        util/get-prev-block-non-collapsed-non-embed (constantly comments-dom)
                        editor/edit-block! (constantly nil)]
          (p/do!
           (editor/delete-block-inner!
            test-helper/test-db
            {:block-id (:block/uuid next-block)
             :value (:block/title next-block)
             :config {}
             :block-container #js {}
             :current-block comments-block
             :next-block next-block
             :delete-concat? true})
           (let [visible-blocks (->> (d/q '[:find (pull ?b [* {:block/tags [:db/ident]}])
                                            :where
                                            [?p :block/name "page1"]
                                            [?b :block/page ?p]
                                            [?b :block/title]
                                            [(missing? $ ?b :logseq.property/deleted-at)]]
                                          @conn)
                                     (map first))
                 visible-titles (map :block/title visible-blocks)
                 comments-blocks (filter comments-model/comments-area? visible-blocks)]
             (is (= ["b1" "after"] visible-titles))
             (is (= "after" (:block/title (first comments-blocks)))
                 "Delete at the end of an empty Comments title should merge the next title into the Comments block")
             (is (= #{:logseq.class/Comments}
                    (set (map :db/ident (:block/tags (first comments-blocks)))))
                 "Delete merge must keep the current block tagged as a Comments block"))))
        (p/finally (fn []
                     (state/set-state! :editor/edit-block-fn nil))))))

(deftest-async rapid-tab-after-new-block-indents-pending-block
  (let [current-block {:db/id 1
                       :block/uuid (random-uuid)
                       :block/title "first"}
        next-block {:db/id 2
                    :block/uuid (random-uuid)
                    :block/title ""}
        input #js {:value "first"}
        current-block-indents (atom [])
        pending-block-indents (atom [])
        edit-calls (atom [])
        insert-config (atom nil)
        {:keys [event stopped?]} (fake-key-event)
        previous-document (.-document js/globalThis)]
    (state/set-editing-block-id! [:unknown-container (:block/uuid current-block)])
    (set! (.-document js/globalThis)
          #js {:activeElement input
               :getElementById (fn [_id] input)})
    (-> (p/with-redefs [state/get-edit-input-id (constantly "edit-block-current")
                        gdom/getElement (constantly input)
                        util/get-selection-start (constantly 5)
                        util/get-selection-end (constantly 5)
                        db/entity (fn [lookup-ref]
                                    (case lookup-ref
                                      [:block/uuid (:block/uuid current-block)] current-block
                                      [:block/uuid (:block/uuid next-block)] next-block
                                      current-block))
                        editor/get-state (constantly {:block current-block
                                                      :value "first"
                                                      :config {}
                                                      :block-container #js {}})
                        editor/insert-new-block-aux! (fn [config _block _value]
                                                       (reset! insert-config config)
                                                       [(p/resolved true) true next-block])
                        editor/get-new-container-id (constantly nil)
                        editor/indent-outdent (fn [indent?]
                                                (swap! current-block-indents conj indent?))
                        editor/edit-block! (fn [block pos opts]
                                             (swap! edit-calls conj {:block block
                                                                     :pos pos
                                                                     :opts opts})
                                             (p/resolved nil))
                        block-handler/indent-outdent-blocks! (fn [blocks indent? save-current-block]
                                                               (swap! pending-block-indents conj
                                                                      {:blocks blocks
                                                                       :indent? indent?
                                                                       :save-current-block save-current-block})
                                                               (p/resolved nil))]
          (editor/insert-new-block! nil nil)
          ((editor/keydown-tab-handler :right) event)
          (p/let [_ (when-let [edit-block-f (take-edit-block-fn!
                                             (:editor/edit-block-fn-id @insert-config))]
                       (edit-block-f))]
            (is @stopped? "Tab should still be consumed while the new block is pending")
            (is (empty? @current-block-indents) "Tab must not indent the block that was split by Enter")
            (is (= [{:blocks [next-block]
                     :indent? true
                     :save-current-block nil}]
                   @pending-block-indents)
                "Tab should apply to the newly inserted block once it exists")
            (is (= [{:block next-block
                     :pos 0
                     :opts {:container-id nil
                            :custom-content ""}}]
                   @edit-calls)
                "The pending block should still enter edit mode after applying queued Tab")))
        (p/finally (fn []
                     (state/set-state! :editor/edit-block-fn nil)
                     (state/set-state! :editor/pending-new-block nil)
                     (set! (.-document js/globalThis) previous-document))))))

(deftest-async rapid-enter-waits-for-pending-new-block
  (let [current-block {:db/id 1
                       :block/uuid (random-uuid)
                       :block/title "first"}
        first-new-block {:db/id 2
                         :block/uuid (random-uuid)
                         :block/title ""}
        inserted-blocks (atom [first-new-block])
        insert-configs (atom [])
        input #js {:value "first"}
        edit-calls (atom [])
        previous-document (.-document js/globalThis)]
    (state/set-editing-block-id! [:unknown-container (:block/uuid current-block)])
    (set! (.-document js/globalThis)
          #js {:activeElement input
               :getElementById (fn [_id] input)})
    (-> (p/with-redefs [state/get-edit-input-id (constantly "edit-block-current")
                        gdom/getElement (constantly input)
                        util/get-selection-start (constantly 5)
                        util/get-selection-end (constantly 5)
                        db/entity (fn [lookup-ref]
                                    (case lookup-ref
                                      [:block/uuid (:block/uuid current-block)] current-block
                                      [:block/uuid (:block/uuid first-new-block)] first-new-block
                                      current-block))
                        editor/get-state (constantly {:block current-block
                                                      :value "first"
                                                      :config {}
                                                      :node input
                                                      :block-container #js {}})
                        editor/inside-of-single-block (constantly false)
                        ldb/get-right-sibling (constantly nil)
                        editor/insert-new-block-aux! (fn [config _block _value]
                                                       (swap! insert-configs conj config)
                                                       (let [next-block (first @inserted-blocks)]
                                                         (swap! inserted-blocks subvec 1)
                                                         [(p/resolved true) true next-block]))
                        editor/get-new-container-id (constantly nil)
                        editor/edit-block! (fn [block pos opts]
                                             (swap! edit-calls conj {:block block
                                                                     :pos pos
                                                                     :opts opts})
                                             (p/resolved nil))]
          (editor/keydown-new-block-handler nil)
          (editor/keydown-new-block-handler nil)
          (p/let [_ (when-let [edit-block-f (take-edit-block-fn! (:editor/edit-block-fn-id (first @insert-configs)))]
                       (edit-block-f))]
            (is (= 1 (count @insert-configs))
                "Enter should not start another insert while a new block is pending")
            (is (= [{:block first-new-block
                     :pos 0
                     :opts {:container-id nil
                            :custom-content ""}}]
                   @edit-calls)
                "The pending block should enter edit mode once the insert transaction completes")))
        (p/finally (fn []
                     (state/set-state! :editor/edit-block-fn nil)
                     (state/set-state! :editor/pending-new-block nil)
                     (set! (.-document js/globalThis) previous-document))))))

(deftest-async rejected-new-block-insert-removes-queued-edit-callback
  (let [current-block {:db/id 1
                       :block/uuid (random-uuid)
                       :block/title "first"}
        next-block {:db/id 2
                    :block/uuid (random-uuid)
                    :block/title ""}
        input #js {:value "first"}
        expected-error (js/Error. "insert failed")
        result-promise (p/deferred)
        insert-config (atom nil)
        previous-document (.-document js/globalThis)]
    (state/set-editing-block-id! [:unknown-container (:block/uuid current-block)])
    (set! (.-document js/globalThis)
          #js {:activeElement input
               :getElementById (fn [_id] input)})
    (-> (p/with-redefs [state/get-edit-input-id (constantly "edit-block-current")
                        gdom/getElement (constantly input)
                        util/get-selection-start (constantly 5)
                        util/get-selection-end (constantly 5)
                        db/entity (fn [lookup-ref]
                                    (case lookup-ref
                                      [:block/uuid (:block/uuid current-block)] current-block
                                      [:block/uuid (:block/uuid next-block)] next-block
                                      current-block))
                        editor/get-state (constantly {:block current-block
                                                      :value "first"
                                                      :config {}
                                                      :node input
                                                      :block-container #js {}})
                        editor/inside-of-single-block (constantly false)
                        ldb/get-right-sibling (constantly nil)
                        editor/insert-new-block-aux! (fn [config _block _value]
                                                       (reset! insert-config config)
                                                       [result-promise true next-block])
                        editor/get-new-container-id (constantly nil)]
          (let [insert-result (.catch (.then (editor/insert-new-block! nil nil)
                                            (fn [_] ::resolved))
                                      (fn [e] e))]
            (.catch result-promise (fn [_] nil))
            (p/let [_ (p/delay 0)
                    _ (do
                        (p/reject! result-promise expected-error)
                        nil)
                    result insert-result]
              (is (identical? expected-error result)
                  "Insert failure should still reject with the original error")
              (is (nil? (take-edit-block-fn! (:editor/edit-block-fn-id @insert-config)))
                  "Rejected insert should remove the queued tagged edit callback"))))
        (p/finally (fn []
                     (state/set-state! :editor/edit-block-fn nil)
                     (state/set-state! :editor/pending-new-block nil)
                     (set! (.-document js/globalThis) previous-document))))))

(deftest tagged-enter-callback-does-not-block-delete-focus
  (let [enter-tx-id (random-uuid)
        edit-calls (atom [])]
    (try
      (state/queue-edit-block-fn! enter-tx-id #(swap! edit-calls conj :enter))
      (state/queue-edit-block-fn! #(swap! edit-calls conj :delete-previous))
      (when-let [edit-block-f (take-edit-block-fn! enter-tx-id)]
        (edit-block-f))
      (when-let [edit-block-f (take-edit-block-fn!)]
        (edit-block-f))
      (is (= [:enter :delete-previous] @edit-calls)
          "Tagged Enter focus should not consume the untagged delete focus callback")
      (finally
        (state/set-state! :editor/edit-block-fn nil)))))

(deftest-async indent-block-does-not-move-into-comments-area
  (load-test-files
   [{:page {:block/title "Comments indent"}
     :blocks [{:block/title "ordinary"}
              {:block/title "Comments"}
              {:block/title "target"}]}])
  (let [comments-area (test-helper/find-block-by-content "Comments")
        target (test-helper/find-block-by-content "target")
        original-parent-id (:db/id (:block/parent target))]
    (db/transact! test-helper/test-db
                  [[:db/add (:db/id comments-area) :block/tags comments-model/comments-tag-ident]])
    (p/let [_ (block-handler/indent-outdent-blocks! [target] true nil)
            target' (db/entity [:block/uuid (:block/uuid target)])
            comments-area' (db/entity [:block/uuid (:block/uuid comments-area)])]
      (is (= original-parent-id (:db/id (:block/parent target')))
          "Indenting a block after #Comments should leave it at the same parent")
      (is (not= (:db/id comments-area') (:db/id (:block/parent target')))
          "The target block must not become a child of the comments area"))))

(deftest-async db-based-save-assets-appends-to-today-page-without-editor
  (let [today-page {:block/uuid (random-uuid)
                    :block/title "today"}
        inserted (atom nil)]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_repo]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-model/get-today-journal-title (constantly "today")
                        db-model/get-journal-page (constantly today-page)
                        state/get-edit-block (constantly nil)
                        state/get-edit-content (constantly "")
                        outliner-op/insert-blocks! (fn [blocks target opts]
                                                     (reset! inserted {:blocks blocks
                                                                       :target target
                                                                       :opts opts})
                                                     [:insert-blocks [blocks target opts]])
                        db/entity (fn [[_lookup uuid]]
                                    {:block/uuid uuid})]
          (editor/db-based-save-assets! "repo" [{:src "image.png"
                                                 :title "image"}]))
        (p/then
         (fn [_]
           (is (= today-page (:target @inserted)))
           (is (= {:keep-uuid? true
                   :bottom? true
                   :sibling? false
                   :replace-empty-target? false}
                  (:opts @inserted))
               "Page-target asset insertion must allow :bottom? to take effect"))))))

(deftest-async db-based-save-assets-uses-last-edit-block-title-without-editor-state
  (let [last-edit-block {:block/uuid (random-uuid)
                         :block/title "existing content"}
        inserted (atom nil)]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_repo]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-model/get-today-journal-title (constantly "today")
                        db-model/get-journal-page (constantly {:block/uuid (random-uuid)
                                                               :block/title "today"})
                        state/get-edit-block (constantly nil)
                        state/get-edit-content (constantly "")
                        outliner-op/insert-blocks! (fn [blocks target opts]
                                                     (reset! inserted {:blocks blocks
                                                                       :target target
                                                                       :opts opts})
                                                     [:insert-blocks [blocks target opts]])
                        db/entity (fn [[_lookup uuid]]
                                    {:block/uuid uuid})]
          (editor/db-based-save-assets! "repo"
                                        [{:src "image.png"
                                          :title "image"}]
                                        :last-edit-block last-edit-block))
        (p/then
         (fn [_]
           (is (= last-edit-block (:target @inserted)))
           (is (not= (:block/uuid last-edit-block)
                     (:block/uuid (first (:blocks @inserted))))
               "Non-empty last edit block must not be replaced by the pasted asset")
           (is (= {:keep-uuid? true
                   :bottom? true
                   :sibling? true
                   :replace-empty-target? true}
                  (:opts @inserted))))))))

(deftest-async db-based-save-assets-appends-to-explicit-target-block
  (let [target-block {:block/uuid (random-uuid)
                      :block/title "comments"}
        temp-edit-block {:block/uuid (random-uuid)
                         :block/title ""}
        inserted (atom nil)]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_repo]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-model/get-today-journal-title (constantly "today")
                        db-model/get-journal-page (constantly {:block/uuid (random-uuid)
                                                               :block/title "today"})
                        state/get-edit-block (constantly temp-edit-block)
                        state/get-edit-content (constantly "")
                        outliner-op/insert-blocks! (fn [blocks target opts]
                                                     (reset! inserted {:blocks blocks
                                                                       :target target
                                                                       :opts opts})
                                                     [:insert-blocks [blocks target opts]])
                        db/entity (fn [[_lookup uuid]]
                                    {:block/uuid uuid})]
          (editor/db-based-save-assets! "repo"
                                        [{:src "image.png"
                                          :title "image"}]
                                        :target-block target-block
                                        :last-edit-block temp-edit-block))
        (p/then
         (fn [_]
           (is (= target-block (:target @inserted)))
           (is (not= (:block/uuid temp-edit-block)
                     (:block/uuid (first (:blocks @inserted))))
               "Explicit target insertion must not replace a temporary editor block")
           (is (= {:keep-uuid? true
                   :bottom? true
                   :sibling? false
                   :replace-empty-target? false}
                  (:opts @inserted))
               "Explicit target asset insertion should append as children, not replace the temp edit block"))))))

(deftest-async ensure-comments-area-for-selected-blocks
  (let [first-uuid (random-uuid)
        second-uuid (random-uuid)
        created-comments-area-uuid (random-uuid)
        first-block {:block/uuid first-uuid
                     :db/id 1
                     :block/title "first"
                     :block/page {:db/id 10}}
        second-block {:block/uuid second-uuid
                      :db/id 2
                      :block/title "second"
                      :block/page {:db/id 10}}
        comments-area {:block/uuid (random-uuid)
                       :block/title "Comments"
                       :block/tags #{comments-model/comments-tag-ident}}
        comment-block {:block/uuid (random-uuid)
                       :block/title "comment"
                       :block/parent comments-area}
        created-comments-area {:block/uuid created-comments-area-uuid
                               :block/title "Comments"
                               :block/tags #{comments-model/comments-tag-ident}
                               comments-model/comments-blocks-property [first-block second-block]}
        inserts (atom [])
        expanded (atom [])]
    (-> (p/with-redefs [db/entity (fn [lookup-ref]
                                    (case lookup-ref
                                      [:block/uuid first-uuid] first-block
                                      [:block/uuid second-uuid] second-block
                                      nil))
                        block-handler/get-top-level-blocks identity
                        editor/api-insert-new-block! (fn [content opts]
                                                       (swap! inserts conj {:content content
                                                                           :opts opts})
                                                       (p/resolved created-comments-area))
                        editor/expand-block! (fn [block-uuid]
                                               (swap! expanded conj block-uuid)
                                               (p/resolved nil))]
          (p/let [area (comments-handler/ensure-comments-area-for-selected-blocks! [first-block
                                                                                    comments-area
                                                                                    comment-block
                                                                                    second-block])]
            (is (= created-comments-area area)
                "Selected blocks should share one comments area")
            (is (= [{:content "Comments"
                     :opts {:block-uuid second-uuid
                            :sibling? true
                            :edit-block? false
                            :other-attrs {:block/tags #{comments-model/comments-tag-ident}
                                          comments-model/comments-blocks-property #{[:block/uuid first-uuid]
                                                                                   [:block/uuid second-uuid]}}}}]
                   @inserts)
                "A range comments area should be inserted after the last selected top block with lookup-ref targets")
            (is (= [created-comments-area-uuid] @expanded)
                "The range comments area should be expanded inline"))))))

(deftest-async ensure-comments-area-for-single-selected-block
  (let [block-uuid (random-uuid)
        created-comments-area-uuid (random-uuid)
        block {:block/uuid block-uuid
               :db/id 1
               :block/title "target"
               :block/page {:db/id 10}}
        created-comments-area {:block/uuid created-comments-area-uuid
                               :block/title "Comments"
                               :block/tags #{comments-model/comments-tag-ident}}
        inserts (atom [])
        expanded (atom [])]
    (-> (p/with-redefs [db/entity (fn [lookup-ref]
                                    (case lookup-ref
                                      [:block/uuid block-uuid] block
                                      nil))
                        db/sort-by-order identity
                        block-handler/get-top-level-blocks identity
                        editor/api-insert-new-block! (fn [content opts]
                                                       (swap! inserts conj {:content content
                                                                           :opts opts})
                                                       (p/resolved created-comments-area))
                        editor/expand-block! (fn [block-uuid]
                                               (swap! expanded conj block-uuid)
                                               (p/resolved nil))]
          (p/let [area (comments-handler/ensure-comments-area-for-selected-blocks! [block])]
            (is (= created-comments-area area)
                "A single selected block should use a child comments area")
            (is (= [{:content "Comments"
                     :opts {:block-uuid block-uuid
                            :end? true
                            :edit-block? false
                            :other-attrs {:block/tags #{comments-model/comments-tag-ident}
                                          comments-model/comments-blocks-property #{[:block/uuid block-uuid]}}}}]
                   @inserts)
                "Single-block comments area should be inserted as a child with the target property")
            (is (= [created-comments-area-uuid] @expanded)
                "The single-block comments area should be expanded inline"))))))

(deftest-async add-comment-to-blocks-opens-comment-box
  (let [block {:block/uuid (random-uuid)
               :db/id 1
               :block/title "target"
               :block/page {:db/id 10}}
        comments-area {:block/uuid (random-uuid)
                       :block/title "Comments"
                       :block/tags #{comments-model/comments-tag-ident}}
        revealed (atom nil)
        cleared-selection? (atom false)
        events (atom [])]
    (-> (p/with-redefs [comments-handler/ensure-comments-area-for-selected-blocks! (fn [blocks]
                                                                                     (is (= [block] blocks))
                                                                                     (p/resolved comments-area))
                        comments-handler/reveal-comments-area! (fn [area opts]
                                                                 (reset! revealed [area opts]))
                        state/clear-selection! #(reset! cleared-selection? true)
                        state/pub-event! #(swap! events conj %)]
          (comments-handler/add-comment-to-blocks! [block])
          (p/resolved nil))
        (p/then (fn [_]
                  (is (= [comments-area {:focus-editor? true}]
                         @revealed)
                      "Adding a comment should open the reply editor")
                  (is @cleared-selection?)
                  (is (= [[:editor/hide-action-bar]] @events)))))))

(deftest-async add-comment-to-non-empty-edit-block-focuses-comment-box
  (let [block-uuid (random-uuid)
        selected-uuid (random-uuid)
        block {:block/uuid block-uuid
               :db/id 1
               :block/title "typing"
               :block/page {:db/id 10}}
        selected-block {:block/uuid selected-uuid
                        :db/id 2
                        :block/title "stale selection"
                        :block/page {:db/id 10}}
        comments-area {:block/uuid (random-uuid)
                       :block/title "Comments"
                       :block/tags #{comments-model/comments-tag-ident}}
        saved? (atom false)
        cleared? (atom false)
        revealed (atom nil)]
    (-> (p/with-redefs [state/editing? (constantly true)
                        state/get-edit-block (constantly block)
                        state/get-edit-content (constantly "typing")
                        state/get-selection-block-ids (constantly [selected-uuid])
                        db/entity (fn [lookup-ref]
                                    (case lookup-ref
                                      [:block/uuid block-uuid] block
                                      [:block/uuid selected-uuid] selected-block
                                      nil))
                        block-handler/get-top-level-blocks identity
                        editor/save-current-block! #(reset! saved? true)
                        state/clear-edit! #(reset! cleared? true)
                        comments-handler/ensure-comments-area-for-selected-blocks! (fn [blocks]
                                                                                     (is (= [block] blocks)
                                                                                         "The editing block should take precedence over stale selection state")
                                                                                     (p/resolved comments-area))
                        comments-handler/reveal-comments-area! (fn [area opts]
                                                                 (reset! revealed [area opts]))
                        state/clear-selection! (fn [])
                        state/pub-event! (fn [_])]
          (comments-handler/add-comment-to-current-context!))
        (p/then (fn [_]
                  (is @saved?)
                  (is @cleared?
                      "Moving focus to the comment box should leave the original block editor")
                  (is (= [comments-area {:focus-editor? true}]
                         @revealed)
                      "Adding a comment while typing should open and focus the reply editor"))))))

(deftest-async add-comment-to-empty-edit-block
  (let [block {:block/uuid (random-uuid)
               :db/id 1
               :block/title ""}
        saved? (atom false)
        cleared? (atom false)
        properties (atom [])
        revealed (atom nil)]
    (-> (p/with-redefs [state/editing? (constantly true)
                        state/get-edit-block (constantly block)
                        state/get-edit-content (constantly "")
                        editor/save-current-block! #(reset! saved? true)
                        db-property-handler/set-block-property! (fn [db-id property value]
                                                                  (swap! properties conj [db-id property value]))
                        state/clear-edit! #(reset! cleared? true)
                        comments-handler/reveal-comments-area! (fn [area opts]
                                                                 (reset! revealed [area opts]))
                        editor/api-insert-new-block! (fn [& _]
                                                       (is false "Empty /Add comment should not insert a child comments block"))]
          (comments-handler/add-comment-to-current-context!)
          (p/resolved nil))
        (p/then (fn [_]
                  (is @saved?)
                  (is @cleared?)
                  (is (= [[1 :block/tags comments-model/comments-tag-ident]]
                         @properties)
                      "Empty /Add comment should turn the current block into a comments area")
                  (is (= [block {:focus-editor? true}]
                         @revealed)
                      "Empty /Add comment should open the reply editor"))))))

(deftest-async add-comment-to-empty-edit-block-reveals-after-comments-tag-is-saved
  (let [block {:block/uuid (random-uuid)
               :db/id 1
               :block/title ""}
        property-save (p/deferred)
        revealed (atom nil)]
    (-> (p/with-redefs [state/editing? (constantly true)
                        state/get-edit-block (constantly block)
                        state/get-edit-content (constantly "")
                        editor/save-current-block! (fn [])
                        db-property-handler/set-block-property! (fn [_db-id _property _value]
                                                                  property-save)
                        state/clear-edit! (fn [])
                        comments-handler/reveal-comments-area! (fn [area opts]
                                                                 (reset! revealed [area opts]))]
          (let [result (comments-handler/add-comment-to-current-context!)]
            (is (nil? @revealed)
                "The reply editor cannot be focused until the blank block is saved as a comments area")
            (p/resolve! property-save :saved)
            result))
        (p/then (fn [_]
                  (is (= [block {:focus-editor? true}]
                         @revealed)
                      "The converted comments area should be revealed after the property transaction finishes"))))))

(deftest-async empty-comment-submit-creates-sibling-block-after-comments
  (let [comments-area {:block/uuid (random-uuid)
                       :block/title "Comments"
                       :block/tags #{comments-model/comments-tag-ident}}
        inserted (atom nil)]
    (-> (p/with-redefs [editor/api-insert-new-block! (fn [content opts]
                                                       (reset! inserted {:content content
                                                                         :opts opts})
                                                       (p/resolved {:block/uuid (random-uuid)}))]
          (let [create-sibling (resolve 'frontend.handler.comments/create-sibling-block-after-comments!)]
            (is (fn? create-sibling))
            (when (fn? create-sibling)
              (create-sibling comments-area))))
        (p/then (fn [_]
                  (is (= {:content ""
                          :opts {:block-uuid (:block/uuid comments-area)
                                 :sibling? true
                                 :edit-block? true}}
                         @inserted)
                      "Empty Enter in the reply box should create an editable sibling after #Comments"))))))

(deftest-async insert-comment-tags-created-comment-block
  (let [comments-area {:block/uuid (random-uuid)
                       :block/title "Comments"
                       :block/tags #{comments-model/comments-tag-ident}}
        inserted (atom nil)]
    (-> (p/with-redefs [editor/api-insert-new-block! (fn [content opts]
                                                       (reset! inserted {:content content
                                                                         :opts opts})
                                                       (p/resolved {:block/uuid (random-uuid)}))]
          (comments-handler/insert-comment! comments-area "review this"))
        (p/then (fn [_]
                  (is (= {:content "review this"
                          :opts {:block-uuid (:block/uuid comments-area)
                                 :end? true
                                 :edit-block? false
                                 :other-attrs {:block/tags #{:logseq.class/Comment}}}}
                         @inserted)
                      "Inserted comment blocks should be tagged as #Comment"))))))

(deftest delete-comment-targets
  (let [delete-targets (resolve 'frontend.handler.comments/comment-delete-targets)
        first-comment {:block/uuid (random-uuid)
                       :block/title "first"}
        second-comment {:block/uuid (random-uuid)
                        :block/title "second"}
        deleted-comment (assoc second-comment :logseq.property/deleted-at 1)
        comments-area {:block/uuid (random-uuid)
                       :block/title "Comments"
                       :block/tags #{comments-model/comments-tag-ident}}]
    (is (fn? delete-targets))
    (when (fn? delete-targets)
      (testing "deletes the comments area when the deleted comment is the only live child"
        (let [comments-area (assoc comments-area :block/_parent [first-comment])
              first-comment (assoc first-comment :block/parent comments-area)]
          (is (= [comments-area]
                 (delete-targets first-comment)))))
      (testing "keeps the comments area when other live comments remain"
        (let [comments-area (assoc comments-area :block/_parent [first-comment second-comment])
              first-comment (assoc first-comment :block/parent comments-area)]
          (is (= [first-comment]
                 (delete-targets first-comment)))))
      (testing "ignores already deleted comment children"
        (let [comments-area (assoc comments-area :block/_parent [first-comment deleted-comment])
              first-comment (assoc first-comment :block/parent comments-area)]
          (is (= [comments-area]
                 (delete-targets first-comment))))))))
