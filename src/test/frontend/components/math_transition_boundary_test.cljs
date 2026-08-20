(ns frontend.components.math-transition-boundary-test
  {:dev/always true}
  (:require ["react" :as react]
            ["react-dom/server" :as react-dom-server]
            [cljs.test :refer [async deftest is use-fixtures]]
            [clojure.string :as string]
            [frontend.components.block :as block]
            [frontend.components.icon :as icon-component]
            [frontend.components.property :as property-component]
            [frontend.components.property.value :as value]
            [frontend.components.select :as select]
            [frontend.db :as db]
            [frontend.db.hooks :as db-hooks]
            [frontend.db.model :as model]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor]
            [frontend.handler.property :as property-handler]
            [frontend.handler.reaction :as reaction-handler]
            [frontend.handler.user :as user-handler]
            [frontend.reaction :as reaction]
            [frontend.handler.route :as route-handler]
            [frontend.modules.shortcut.core :as shortcut]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.util.cursor :as cursor]
            [goog.dom :as gdom]
            [logseq.db.frontend.property :as db-property]
            [logseq.db :as ldb]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.property :as outliner-property]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

;; Shadow's no-fixture path disables async tests. Keep this namespace on the
;; async fixture strategy so each public event oracle fully settles before the
;; next oracle installs its own boundary collaborators.
(use-fixtures :each {:before (fn [] (async done (done)))
                     :after (fn [])})

(defn- register-real-math-transition!
  [block transition]
  (let [input-id (str "edit-block-" (:block/uuid block))
        input #js {:value "$$x$$" :selectionStart 5 :selectionEnd 5}]
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

(defn- event
  [key target]
  #js {:key key
       :target target
       :currentTarget target
       :preventDefault (fn [])
       :stopPropagation (fn [])})

(defn- pending-boundary
  [pending boundaries]
  (fn [boundary & args]
    (swap! boundaries conj boundary)
    (p/then pending (fn [_] ((last args))))))

(defn- element-props
  [element]
  (if (sequential? element)
    (second element)
    (.-props element)))

(defn- expand-hsx-component
  [element]
  (let [component-type (.-type element)
        component-fn (or (.-type component-type) component-type)]
    (component-fn (.-props element))))

(defn- element-handler
  [element hiccup-key react-key]
  (let [props (element-props element)]
    (if (map? props)
      (get props hiccup-key)
      (aget props react-key))))

(defn- find-element-class
  [tree class-fragment]
  (cond
    (react/isValidElement tree)
    (let [props (.-props tree)
          class-name (or (aget props "className") "")]
      (or (when (string/includes? class-name class-fragment) tree)
          (some #(find-element-class % class-fragment)
                (array-seq (.toArray (.-Children react) (aget props "children"))))))

    (sequential? tree)
    (or (when (and (keyword? (first tree))
                   (string/includes? (name (first tree)) class-fragment))
          tree)
        (some #(find-element-class % class-fragment) tree))

    :else nil))

(defn- find-element-handler
  [tree hiccup-key react-key]
  (cond
    (react/isValidElement tree)
    (let [props (.-props tree)]
      (or (when (fn? (aget props react-key)) tree)
          (some #(find-element-handler % hiccup-key react-key)
                (array-seq (.toArray (.-Children react) (aget props "children"))))))

    (sequential? tree)
    (or (when (fn? (get (second tree) hiccup-key)) tree)
        (some #(find-element-handler % hiccup-key react-key) tree))

    :else nil))

(defn- react-component-fn
  [element]
  (let [component-type (.-type element)]
    (or (.-type component-type) component-type)))

(defn- find-react-element
  [tree predicate]
  (cond
    (react/isValidElement tree)
    (or (when (predicate tree) tree)
        (some #(find-react-element % predicate)
              (array-seq (.toArray (.-Children react) (aget (.-props tree) "children")))))

    (sequential? tree)
    (some #(find-react-element % predicate) tree)

    :else nil))

(defn- invoke-four-argument-select!
  [handler chosen selected? choices e]
  (try
    (handler chosen selected? choices e)
    (catch :default error
      (is false (str "select leaf rejected its public four-argument callback: " error))
      (p/resolved nil))))

(defn- invoke-calendar-day!
  [handler day modifiers e]
  (try
    (handler day modifiers e)
    (catch :default error
      (is false (str "calendar day callback rejected its real event signature: " error))
      (p/resolved nil))))

(deftest central-math-interaction-barrier-is-the-single-public-action-protocol
  (let [barrier (resolve 'frontend.handler.editor/run-math-transition-action!)]
    (is (fn? barrier)
        "All popup/focus/save/DB/pub event leaves delegate to one auditable barrier")))

(deftest central-no-pending-fast-path-preserves-the-original-synchronous-return
  (let [block {:block/uuid (random-uuid)}
        actions (atom [])]
    (with-redefs [state/get-edit-block (constantly block)]
      (let [result (editor/run-math-transition-action!
                    :no-pending-fast-path
                    #(do (swap! actions conj :ran) :original-return))]
        (is (= :original-return result))
        (is (= [:ran] @actions)
            "No-pending actions run before the public helper returns")))))

(deftest central-no-pending-fast-path-preserves-synchronous-throw
  (let [block {:block/uuid (random-uuid)}
        failure (js/Error. "ordinary synchronous failure")]
    (with-redefs [state/get-edit-block (constantly block)]
      (try
        (editor/run-math-transition-action!
         :no-pending-sync-throw
         #(throw failure))
        (is false "the no-pending action must throw before the helper returns")
        (catch :default error
          (is (identical? failure error)))))))

(deftest property-navigation-freezes-pooled-event-before-await
  (async done
         (let [pending (p/deferred)
               source {:db/id 100 :block/uuid (random-uuid)}
               calls (atom [])
               target #js {:getAttribute #(when (= % "data-property-nav-mode") "edit")}
               e (event "ArrowDown" target)
               previous-document (.-document js/globalThis)]
           (set! (.-document js/globalThis) #js {:activeElement target})
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               editor/move-cross-boundary-up-down
                               (fn [direction opts]
                                 (swap! calls conj [:edit direction opts]))
                               editor/move-property-focus-up-down
                               (fn [direction]
                                 (swap! calls conj [:focus direction]))]
                 (let [result ((:on-key-down
                                (#'value/property-value-block-container-props
                                 {:db/ident :user.property/test}))
                               e)]
                   ;; React may clear pooled event fields as soon as the callback
                   ;; returns. The async continuation must use a frozen snapshot.
                   (set! (.-currentTarget e) nil)
                   (is (empty? @calls))
                   (p/resolve! pending nil)
                   (p/let [_ result]
                     (is (= [[:edit :down {:input nil}]] @calls)))))
               (p/finally (fn []
                            (set! (.-document js/globalThis) previous-document)))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest asset-picker-click-keyboard-and-navigation-await-before-action
  (async done
         (let [pending (p/deferred)
               source {:db/id 100 :block/uuid (random-uuid)}
               calls (atom [])
               trigger-props (atom [])
               trigger #js {:getAttribute #(when (= % "data-property-nav-mode") "edit")
                            :click (fn [])}
               target #js {:closest (constantly nil)}]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               editor/move-cross-boundary-up-down
                               (fn [direction opts]
                                 (swap! calls conj [:navigate direction opts]))
                               editor/move-property-focus-up-down
                               (fn [direction]
                                 (swap! calls conj [:focus direction]))
                               hooks/use-ref (fn [_] #js {:current nil})
                               shui/trigger-as (fn [_ props & _]
                                                 (reset! trigger-props props)
                                                 (.createElement react "div" nil))
                               shui/popup-show! (fn [captured-target & _]
                                                  (swap! calls conj [:open captured-target]))
                               property-handler/remove-block-property!
                               (fn [& _] (swap! calls conj [:delete]))]
                 (.renderToStaticMarkup
                  react-dom-server
                  (value/asset-value-picker
                   {:db/id 1} {:db/ident :user.property/asset} nil {}))
                 (let [click-event (event nil target)
                       click! (:onClickCapture @trigger-props)
                       key! (:on-key-down @trigger-props)
                       click-result (click! click-event)
                       up-result (key! (event "ArrowUp" trigger))
                       down-result (key! (event "ArrowDown" trigger))
                       delete-result (key! (event "Delete" trigger))
                       backspace-result (key! (event "Backspace" trigger))
                       space-result (key! (event " " trigger))
                       enter-result (key! (event "Enter" trigger))]
                   (set! (.-currentTarget click-event) nil)
                   (is (empty? @calls))
                   (p/resolve! pending nil)
                   (p/let [_ (p/all [click-result up-result down-result delete-result
                                     backspace-result space-result enter-result])]
                     (is (= 3 (count (filter #(= :open (first %)) @calls))))
                     (is (= 2 (count (filter #(= [:delete] %) @calls))))
                     (is (= #{[:navigate :up {:input nil}]
                              [:navigate :down {:input nil}]
                              [:navigate :up {}]}
                            (set (filter #(= :navigate (first %)) @calls)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest asset-preview-click-and-keyboard-await-at-the-embedded-leaf
  (async done
         (let [pending (p/deferred)
               source {:db/id 100 :block/uuid (random-uuid)}
               actions (atom [])
               button-props (atom nil)
               target #js {:closest (constantly nil)}
               embedded-target #js {:closest (constantly #js {})}
               asset-cp (fn [& _] nil)
               values [{:db/id 1 :block/uuid (random-uuid)
                        :block/title "movie" :logseq.property.asset/type "mp4"}
                       {:db/id 2 :block/uuid (random-uuid)
                        :block/title "image" :logseq.property.asset/type "png"}
                       {:db/id 3 :block/uuid (random-uuid)
                        :block/title "document" :logseq.property.asset/type "pdf"}]]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/get-component (fn [key] (when (= key :block/asset-cp) asset-cp))
                               state/pub-event! #(swap! actions conj %)
                               shui/button (fn [props & _]
                                             (reset! button-props props)
                                             (.createElement react "button" nil))]
                 (let [[video image other] values
                       _ (.renderToStaticMarkup react-dom-server (value/asset-value-content video))
                       image-tree (expand-hsx-component (value/asset-value-content image))
                       other-tree (expand-hsx-component (value/asset-value-content other))
                       video-result ((:on-click @button-props) (event nil target))
                       image-click (element-handler image-tree :on-click-capture "onClickCapture")
                       image-key (element-handler image-tree :on-key-down "onKeyDown")
                       other-click (element-handler other-tree :on-click-capture "onClickCapture")
                       other-key (element-handler other-tree :on-key-down "onKeyDown")
                       results [video-result
                                (image-click (event nil target))
                                (image-key (event "Enter" target))
                                (other-click (event nil target))
                                (other-key (event " " target))]
                       embedded-result (image-click (event nil embedded-target))]
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved (conj results embedded-result)))]
                     (is (= 5 (count @actions)))
                     (is (every? #(= :asset/show-preview (first %)) @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest table-create-and-default-block-pointer-await-before-create
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               table-opts (atom nil)
               block-config (atom nil)
               default-value {:db/id 9 :block/uuid (random-uuid) :block/title "seed"}
               block-value {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2 :db/ident :user.property/text
                         :logseq.property/type :default
                         :logseq.property/default-value default-value}]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/use-container-id (constantly 7)
                               state/get-component
                               (fn [key]
                                 (case key
                                   :block/container (fn [config _]
                                                      (reset! block-config config)
                                                      nil)
                                   :block/blocks-container (fn [& _] nil)
                                   nil))
                               value/<create-new-block!
                               (fn [_ _ title & _]
                                 (swap! actions conj [:create title]))]
                 (expand-hsx-component
                  (value/property-normal-block-value
                   block-value property nil
                   {:table-text-property-render
                    (fn [_ opts]
                      (reset! table-opts opts)
                      nil)}))
                 (expand-hsx-component
                  (value/property-normal-block-value block-value property default-value {}))
                 (let [table-result ((:create-new-block @table-opts))
                       default-result ((:on-block-content-pointer-down @block-config)
                                       (event nil #js {}))]
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved [table-result default-result]))]
                     (is (= [[:create ""] [:create "seed"]] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest property-key-title-and-icon-await-before-route-or-popup
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               trigger-props (atom [])
               property {:db/id 2 :block/uuid (random-uuid)
                         :block/title "Priority" :db/ident :user.property/priority}
               block {:db/id 1 :block/uuid (random-uuid)}
               target #js {}
               meta-event #js {:metaKey true :target target
                               :preventDefault #(swap! actions conj :prevented)}
               click-event #js {:metaKey false :altKey true :target target}
               icon-event #js {:target target}]
           (register-real-math-transition! block pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               db/entity (fn [_] property)
                               util/meta-key? (fn [e] (true? (.-metaKey e)))
                               shui/trigger-as (fn [_ props & _]
                                                 (swap! trigger-props conj props)
                                                 (.createElement react "button" nil))
                               route-handler/redirect-to-page!
                               #(swap! actions conj [:route %])
                               shui/popup-show!
                               (fn [captured-target & _]
                                 (swap! actions conj [:popup captured-target]))]
                 (.renderToStaticMarkup react-dom-server
                                        (property-component/property-key-title block property false))
                 (.renderToStaticMarkup react-dom-server
                                        (property-component/property-key-cp block property {}))
                 (let [[title-props icon-props] @trigger-props
                       route-result ((:on-pointer-down title-props) meta-event)
                       title-result ((:on-click title-props) click-event)
                       icon-result ((:on-click icon-props) icon-event)]
                   (is (= [:prevented] @actions)
                       "Only synchronous browser cancellation may precede the Math gate")
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved [route-result title-result icon-result]))]
                     (is (= [[:route (:block/uuid property)]
                             [:popup target]
                             [:popup target]]
                            (vec (remove #{:prevented} @actions)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest property-popup-escape-leaves-reacquire-the-central-barrier
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               trigger-props (atom [])
               popup-options (atom [])
               property {:db/id 2 :block/uuid (random-uuid)
                         :block/title "Priority" :db/ident :user.property/priority}
               block {:db/id 1 :block/uuid (random-uuid)}
               target #js {}
               input #js {:focus #(swap! actions conj :focus)}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (let [action (last args)]
                                   (if (contains? #{:property-key-popup-escape
                                                    :property-icon-popup-escape}
                                                  boundary)
                                     (p/then pending action)
                                     (action))))
                               db/entity (fn [_] property)
                               state/get-input (constantly input)
                               util/meta-key? (constantly false)
                               shui/trigger-as (fn [_ props & _]
                                                 (swap! trigger-props conj props)
                                                 (.createElement react "button" nil))
                               shui/popup-show!
                               (fn [_target _content opts]
                                 (swap! popup-options conj opts))
                               shui/popup-hide! #(swap! actions conj :hide)]
                 (.renderToStaticMarkup react-dom-server
                                        (property-component/property-key-title block property false))
                 (.renderToStaticMarkup react-dom-server
                                        (property-component/property-key-cp block property {}))
                 (let [[title-props icon-props] @trigger-props]
                   ((:on-click title-props) #js {:metaKey false :altKey false :target target})
                   ((:on-click icon-props) #js {:target target})
                   (let [[title-opts icon-opts] @popup-options
                         title-escape (get-in title-opts [:content-props :onEscapeKeyDown])
                         icon-escape (get-in icon-opts [:content-props :onEscapeKeyDown])
                         result-a (title-escape (event "Escape" target))
                         result-b (icon-escape (event "Escape" target))]
                     (is (empty? @actions)
                         "Neither popup leaf hides or focuses before its fresh barrier")
                     (p/resolve! pending {:status :committed})
                     (p/let [_ (p/all [result-a result-b])]
                       (is (= [:hide :focus :hide] @actions))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest property-icon-selection-reacquires-before-db-and-popup-close
  (async done
         (let [pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               trigger-props (atom [])
               popup-content (atom nil)
               icon-search-props (atom nil)
               block-value {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2 :block/uuid (random-uuid)
                         :block/title "Priority" :db/ident :user.property/priority}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (let [action (last args)]
                                   (if (= :property-icon-select boundary)
                                     ((pending-boundary pending boundaries) boundary action)
                                     (action))))
                               db/entity (fn [_] property)
                               shui/trigger-as (fn [_ props & _]
                                                 (swap! trigger-props conj props)
                                                 (.createElement react "button" nil))
                               shui/popup-show! (fn [_target content & _]
                                                  (reset! popup-content content))
                               icon-component/icon-search (fn [props]
                                                            (reset! icon-search-props props)
                                                            (.createElement react "div" nil))
                               db-property-handler/set-block-property!
                               (fn [& args] (swap! actions conj (into [:set] args)))
                               db-property-handler/remove-block-property!
                               (fn [& args] (swap! actions conj (into [:remove] args)))
                               shui/popup-hide! #(swap! actions conj [:hide %])]
                 (let [_ (.renderToStaticMarkup react-dom-server
                                                (property-component/property-key-cp
                                                 block-value property {}))
                       _ ((:on-click (first @trigger-props)) (event nil #js {}))
                       _ (@popup-content {:id "property-icon-popup"})
                       result ((:on-chosen @icon-search-props)
                               (event nil #js {}) {:id "star" :type :tabler-icon})]
                   (is (= [:property-icon-select] @boundaries))
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved result)]
                     (is (= :set (ffirst @actions)))
                     (is (= [:hide "property-icon-popup"] (last @actions))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest tag-context-menu-awaits-before-opening-the-public-popup
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               block-value {:db/id 1 :block/uuid (random-uuid)}
               tag {:db/id 2 :block/uuid (random-uuid)
                    :block/title "tag" :db/ident :user.class/tag}
               target #js {}]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/get-edit-block (constantly block-value)
                               util/mobile? (constantly false)
                               hooks/use-memo (fn [init _deps] (init))
                               hooks/use-atom (fn [a] [@a #(reset! a %)])
                               shui/popup-show! (fn [& _] (swap! actions conj :popup))]
                 (let [tree (expand-hsx-component
                             (#'block/block-tag block-value tag {} {}))
                       context-node (find-element-handler tree :on-context-menu "onContextMenu")
                       result ((element-handler context-node :on-context-menu "onContextMenu")
                               (event nil target))]
                   (is (some? context-node) "the public desktop tag branch exposes its real context handler")
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ result]
                     (is (= [:popup] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest collapsed-tags-popup-and-remove-leaf-each-use-a-fresh-barrier
  (async done
         (let [open-pending (p/deferred)
               leaf-pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               popup-content (atom nil)
               buttons (atom [])
               block-value {:db/id 1 :block/uuid (random-uuid) :block/raw-title "block"
                            :block/tags (mapv (fn [id]
                                               {:db/id id :block/uuid (random-uuid)
                                                :block/title (str "tag" id)
                                                :db/ident (keyword "user.class" (str "tag" id))})
                                             [2 3 4])}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (swap! boundaries conj boundary)
                                 (p/then (case boundary
                                           :collapsed-tags-open open-pending
                                           :collapsed-tag-remove leaf-pending
                                           leaf-pending)
                                         (last args)))
                               util/mobile? (constantly false)
                               block/page-cp (fn [& _] nil)
                               shui/popup-show! (fn [_target content & _]
                                                  (reset! popup-content content))
                               shui/button (fn [props & _]
                                             (swap! buttons conj props)
                                             (.createElement react "button" nil))
                               db-property-handler/delete-property-value!
                               (fn [& _] (swap! actions conj :remove))]
                 (let [tree (expand-hsx-component (block/tags-cp {} block-value))
                       open-result ((element-handler tree :on-pointer-down "onPointerDown")
                                    (event nil #js {}))]
                   (is (= [:collapsed-tags-open] @boundaries))
                   (is (nil? @popup-content))
                   (p/resolve! open-pending {:status :committed})
                   (p/let [_ (p/resolved open-result)]
                     (doall (@popup-content))
                     (let [remove-result ((:on-click (first @buttons)) (event nil #js {}))]
                       (is (= [:collapsed-tags-open :collapsed-tag-remove] @boundaries))
                       (is (empty? @actions))
                       (p/resolve! leaf-pending {:status :committed})
                       (p/let [_ (p/resolved remove-result)]
                         (is (= [:remove] @actions)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest reaction-toggle-picker-and-selection-each-use-the-popup-action-protocol
  (async done
         (let [initial-pending (p/deferred)
               select-pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               buttons (atom [])
               popup-content (atom nil)
               icon-search-props (atom nil)
               block-value {:db/id 1 :block/uuid (random-uuid)}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (swap! boundaries conj boundary)
                                 (p/then (if (= :reaction-picker-select boundary)
                                           select-pending
                                           initial-pending)
                                         (fn [_] ((last args)))))
                               state/get-current-repo (constantly "test")
                               db-hooks/use-query (fn [& _] [])
                               user-handler/user-uuid (constantly nil)
                               reaction/summarize (fn [& _]
                                                    [{:emoji-id "thumbsup"
                                                      :count 1
                                                      :reacted-by-me? false
                                                      :usernames ["test"]}])
                               shui/button (fn [props & _]
                                             (swap! buttons conj props)
                                             (.createElement react "button" nil))
                               shui/popup-show! (fn [_target content & _]
                                                  (reset! popup-content content))
                               icon-component/icon-search (fn [props]
                                                            (reset! icon-search-props props)
                                                            (.createElement react "div" nil))
                               reaction-handler/toggle-reaction!
                               (fn [& _] (swap! actions conj :toggle))
                               shui/popup-hide! (fn [& _] (swap! actions conj :hide))]
                 (expand-hsx-component (block/block-reactions block-value))
                 (let [reaction-props (some #(when (:key %) %) @buttons)
                       add-props (some #(when (:title %) %) @buttons)
                       toggle-result ((:on-click reaction-props) (event nil #js {}))
                       open-result ((:on-click add-props) (event nil #js {}))]
                   (is (= [:reaction-toggle :reaction-picker-open] @boundaries))
                   (is (empty? @actions))
                   (is (nil? @popup-content))
                   (p/resolve! initial-pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved [toggle-result open-result]))]
                     (@popup-content {:id "reaction-popup"})
                     (let [select-result ((:on-chosen @icon-search-props)
                                          (event nil #js {}) {:id "heart"} false)]
                       (is (= :reaction-picker-select (last @boundaries)))
                       (is (= [:toggle] @actions))
                       (p/resolve! select-pending {:status :committed})
                       (p/let [_ (p/resolved select-result)]
                         (is (= [:toggle :toggle :hide] @actions)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest page-title-icon-selection-awaits-before-its-real-db-leaf
  (async done
         (let [pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               picker-props (atom nil)
               conn (db-test/create-conn)
               page {:db/id 1 :block/uuid (random-uuid) :block/title "Page"
                     :block/tags [{:db/ident :logseq.class/Page}]
                     :logseq.property/icon {:id "old" :type :tabler-icon}}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (pending-boundary pending boundaries)
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-atom (fn [a] [(when a @a)])
                               hooks/use-state (fn [initial]
                                                 [(if (fn? initial) (initial) initial)
                                                  (fn [& _])])
                               hooks/use-ref (fn [value] #js {:current value})
                               hooks/use-callback (fn [f _] f)
                               hooks/use-effect! (fn [& _])
                               state/get-current-page (constantly nil)
                               state/auto-expand-block-refs? (constantly false)
                               state/use-sub-block-collapsed (fn [& _] nil)
                               state/use-sub (fn [& _] nil)
                               state/get-component (fn [& _] nil)
                               state/slot-hook-exist? (constantly false)
                               db/get-db (constantly @conn)
                               db/entity (fn [& _] page)
                               ldb/page? (constantly true)
                               ldb/class? (constantly false)
                               ldb/property? (constantly false)
                               ldb/get-children (constantly [])
                               util/collapsed? (constantly false)
                               util/mobile? (constantly false)
                               icon-component/icon-picker
                               (fn [_icon props]
                                 (reset! picker-props props)
                                 (.createElement react "button" nil))
                               db-property-handler/set-block-property!
                               (fn [& _] (swap! actions conj :set-icon))]
                 (expand-hsx-component
                  (block/block-container-inner-aux
                   {} "test" {:page-title? true :container-id 1} page
                   {:editing? false :selected? false}))
                 (let [result ((:on-chosen @picker-props)
                               (event nil #js {}) {:id "star" :type :tabler-icon})]
                   (is (= [:page-icon-select] @boundaries))
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved result)]
                     (is (= [:set-icon] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest asset-action-bar-awaits-before-alignment-and-delete-mutations
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               items (atom [])
               menu-props (atom nil)
               trigger-props (atom nil)
               asset-uuid (random-uuid)
               asset {:db/id 1 :block/uuid asset-uuid}
               block-node #js {:getAttribute (fn [key]
                                               (when (= key "blockid") (str asset-uuid)))}
               ref-el #js {:closest (constantly block-node)}]
           (register-real-math-transition! asset pending)
           (-> (p/with-redefs [editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/get-edit-block (constantly asset)
                               state/get-current-repo (constantly "test")
                               hooks/use-effect! (fn [& _])
                               hooks/use-ref (fn [_] #js {:current ref-el})
                               hooks/use-state (fn [initial] [initial (fn [_])])
                               shui/dropdown-menu (fn [props & _]
                                                    (reset! menu-props props)
                                                    (.createElement react "div" nil))
                               shui/dropdown-menu-trigger (fn [props & _]
                                                            (reset! trigger-props props)
                                                            (.createElement react "button" nil))
                               shui/dropdown-menu-item (fn [props & _]
                                                         (swap! items conj props)
                                                         (.createElement react "button" nil))
                               shui/dialog-confirm! (fn [& _]
                                                      (swap! actions conj :confirm)
                                                      (p/resolved true))
                               shui/dialog-close! #(swap! actions conj :close)
                               property-handler/set-block-property!
                               (fn [& args] (swap! actions conj (into [:align] args)))
                               editor/delete-asset-of-block!
                               (fn [opts] (swap! actions conj [:delete opts]))
                               util/electron? (constantly false)]
                 (expand-hsx-component
                 (block/asset-container asset "asset.png" "asset" nil
                                         {:breadcrumb? false :positioned? false
                                          :local? false :full-text "" :gallery-view? false}))
                 (let [pending-prevented? (atom false)
                       pending-stopped? (atom false)
                       trigger-event #js {:target #js {} :currentTarget #js {}
                                          :preventDefault #(reset! pending-prevented? true)
                                          :stopPropagation #(reset! pending-stopped? true)}
                       trigger-handler (:on-pointer-down @trigger-props)
                       _ (is (fn? trigger-handler)
                             "The production Radix Trigger, not a nominal Root prop, owns the barrier")
                       trigger-result (when trigger-handler
                                        (trigger-handler trigger-event))
                       alignment-results (mapv #((:on-click (nth @items %)) (event nil #js {}))
                                               [0 1 2])
                       delete-result ((:on-click (last @items)) (event nil #js {}))]
                   (is (empty? @actions))
                   (is @pending-prevented?)
                   (is @pending-stopped?)
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved
                                         (into [trigger-result]
                                               (conj alignment-results delete-result))))]
                     (is (= 3 (count (filter #(and (vector? %)
                                                   (= :align (first %)))
                                             @actions))))
                     (is (= [:confirm :close]
                            (vec (filter keyword? @actions))))
                     (is (= 1 (count (filter #(and (vector? %)
                                                   (= :delete (first %)))
                                             @actions))))
                     (p/let [_ (p/delay 0)]
                       (let [ordinary-prevented? (atom false)
                             ordinary-stopped? (atom false)
                             ordinary-event #js {:target #js {} :currentTarget #js {}
                                                 :preventDefault #(reset! ordinary-prevented? true)
                                                 :stopPropagation #(reset! ordinary-stopped? true)}]
                         (trigger-handler ordinary-event)
                         (is (false? @ordinary-prevented?)
                             "Radix trigger default stays native when no Math transition is pending")
                         (is (false? @ordinary-stopped?)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest ordinary-image-lightbox-trigger-awaits-the-event-time-math-source
  (async done
         (let [pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               asset {:db/id 1 :block/uuid (random-uuid)
                      :logseq.property.asset/width 100}
               image-target #js {:nodeName "IMG"}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (pending-boundary pending boundaries)
                               hooks/use-effect! (fn [& _])
                               hooks/use-ref (fn [_] #js {:current nil})
                               hooks/use-state (fn [initial] [initial (fn [_])])
                               block/open-lightbox! (fn [& _] (swap! actions conj :lightbox))]
                 (let [tree (expand-hsx-component
                             (block/asset-container asset "asset.png" "asset" nil
                                                    {:breadcrumb? true
                                                     :positioned? true
                                                     :local? false
                                                     :full-text ""
                                                     :gallery-view? false}))
                       result ((element-handler tree :on-click "onClick")
                               (event nil image-target))]
                   (is (= [:asset-lightbox-open] @boundaries))
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved result)]
                     (is (= [:lightbox] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest asset-confirm-runs-a-fresh-second-stage-barrier
  (async done
         (let [first-stage (p/deferred)
               dialog-result (p/deferred)
               typed (ex-info "content-free" {:type :math-transition/recovery-failed})
               actions (atom [])
               items (atom [])
               asset-uuid (random-uuid)
               asset {:db/id 1 :block/uuid asset-uuid}
               block-node #js {:getAttribute #(when (= % "blockid") (str asset-uuid))}
               ref-el #js {:closest (constantly block-node)}
               previous-console-error (.-error js/console)]
           (set! (.-error js/console) (fn [& _]))
           (register-real-math-transition! asset first-stage)
           (-> (p/with-redefs [state/get-edit-block (constantly asset)
                               hooks/use-effect! (fn [& _])
                               hooks/use-ref (fn [_] #js {:current ref-el})
                               hooks/use-state (fn [initial] [initial (fn [_])])
                               shui/dropdown-menu-item (fn [props & _]
                                                         (swap! items conj props)
                                                         (.createElement react "button" nil))
                               shui/dialog-confirm! (fn [& _]
                                                      (swap! actions conj :confirm)
                                                      dialog-result)
                               shui/dialog-close! #(swap! actions conj :close)
                               editor/delete-asset-of-block!
                               #(swap! actions conj [:delete %])
                               util/electron? (constantly false)]
                 (expand-hsx-component
                  (block/asset-container asset "asset.png" "asset" nil
                                         {:breadcrumb? false :positioned? false
                                          :local? false :full-text "" :gallery-view? false}))
                 (let [result ((:on-click (last @items)) (event nil #js {}))]
                   (is (empty? @actions))
                   (p/resolve! first-stage {:status :committed})
                   (p/let [_ first-stage]
                     (is (= [:confirm] @actions))
                     (register-real-math-transition! asset (p/rejected typed))
                     (p/resolve! dialog-result true)
                     (p/let [outcome result]
                       (is (= :recovery-failed (:status outcome)))
                       (is (= [:confirm] @actions)
                           "A typed second-stage failure cannot close or delete")))))
               (p/finally #(set! (.-error js/console) previous-console-error))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest asset-grid-select-unselect-and-upload-each-reacquire-the-barrier
  (async done
         (let [pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               button-props (atom nil)
               selected {:db/id 1 :block/uuid (random-uuid)
                         :block/title "selected" :logseq.property.asset/type "png"}
               unselected {:db/id 2 :block/uuid (random-uuid)
                           :block/title "unselected" :logseq.property.asset/type "png"}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (pending-boundary pending boundaries)
                               state/get-component (constantly nil)
                               shui/checkbox (fn [& _] nil)
                               shui/button (fn [props & _]
                                             (reset! button-props props)
                                             (.createElement react "button" nil))]
                 (let [select-tree (expand-hsx-component
                                    (value/asset-grid-cell
                                     unselected false
                                     #(swap! actions conj [:select (:db/id %)])))
                       unselect-tree (expand-hsx-component
                                      (value/asset-grid-cell
                                       selected true
                                       #(swap! actions conj [:unselect (:db/id %)])))
                       _ (expand-hsx-component
                          (value/asset-grid-upload-button
                           false #(swap! actions conj :upload)))
                       select-result ((element-handler select-tree :on-click "onClick")
                                      (event nil #js {}))
                       unselect-result ((element-handler unselect-tree :on-key-down "onKeyDown")
                                        (event "Enter" #js {}))
                       upload-result ((:on-click @button-props) (event nil #js {}))]
                   (is (= [:asset-grid-select :asset-grid-unselect :asset-grid-upload]
                          @boundaries))
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved
                                         [select-result unselect-result upload-result]))]
                     (is (= [[:select 2] [:unselect 1] :upload] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest code-language-picker-awaits-before-popup-and-db-mutations
  (async done
         (let [pending (p/deferred)
               typed (ex-info "content-free" {:type :math-transition/recovery-failed})
               actions (atom [])
               buttons (atom [])
               popup-content (atom nil)
               select-props (atom nil)
               source {:db/id 100 :block/uuid (random-uuid)}
               block {:db/id 1 :block/uuid (random-uuid)
                      :logseq.property.node/display-type :code
                      :logseq.property.code/lang "JavaScript"}
               target #js {}
               cm #js {}
               previous-code-mirror (.-CodeMirror js/window)
               previous-console-error (.-error js/console)]
           (set! (.-error js/console) (fn [& _]))
           (set! (.-setOption cm) (fn [key value] (swap! actions conj [:mode key value])))
           (set! (.-CodeMirror js/window)
                 #js {:modeInfo #js [#js {:name "Clojure" :mode "clojure"}]})
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-ref (fn [_] #js {:current nil})
                               shui/button (fn [props & _]
                                             (swap! buttons conj props)
                                             (.createElement react "button" nil))
                               shui/popup-show! (fn [captured-target content & _]
                                                  (swap! actions conj [:popup captured-target])
                                                  (reset! popup-content content))
                               shui/popup-hide! #(swap! actions conj [:hide])
                               select/select (fn [props]
                                               (reset! select-props props)
                                               nil)
                               util/get-cm-instance (constantly cm)
                               util/rec-get-node (fn [& _] #js {})
                               db/transact! #(swap! actions conj [:transact %])
                               db-property-handler/set-block-property!
                               (fn [& args] (swap! actions conj (into [:property] args)))]
                 (expand-hsx-component
                  (block/src-cp {:block block :container-id 7}
                                {:lines ["(+ 1 2)"] :language "JavaScript"}))
                 (let [pointer-prevented? (atom false)
                       pointer-stopped? (atom false)
                       pointer-event #js {:target target :currentTarget target
                                          :preventDefault #(reset! pointer-prevented? true)
                                          :stopPropagation #(reset! pointer-stopped? true)}
                       pointer-handler (:on-pointer-down (first @buttons))
                       _ (is (fn? pointer-handler)
                             "the real code-language focusable trigger owns pointerdown gating")
                       pointer-result (when pointer-handler (pointer-handler pointer-event))
                       click-result ((:on-click (first @buttons))
                                     (event nil target))]
                   (is (empty? @actions))
                   (is @pointer-prevented?)
                   (is @pointer-stopped?)
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved [pointer-result click-result]))]
                     (is (= [[:popup target]] @actions))
                     (expand-hsx-component (@popup-content))
                     (register-real-math-transition! source (p/resolved :committed))
                     (p/let [_ ((:on-chosen @select-props) {:value "Clojure"} nil nil nil)]
                     (is (= [:popup :mode :transact :property :hide]
                            (mapv first @actions)))
                     (is (= [:property 1 :logseq.property.code/lang "Clojure"]
                            (nth @actions 3)))
                     (reset! actions [])
                     (register-real-math-transition! source (p/rejected typed))
                     (p/let [typed-result ((:on-chosen @select-props)
                                           {:value "Clojure"} nil nil nil)]
                       (is (= :recovery-failed (:status typed-result)))
                       (is (empty? @actions)
                           "A fresh second-stage recovery failure cannot hide or mutate"))))))
               (p/finally (fn []
                            (set! (.-CodeMirror js/window) previous-code-mirror)
                            (set! (.-error js/console) previous-console-error)))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest code-language-same-non-code-and-stale-choices-all-reacquire-before-hide
  (async done
         (let [pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               select-props (atom [])
               previous-codemirror (.-CodeMirror js/window)]
           (set! (.-CodeMirror js/window)
                 #js {:modeInfo #js [#js {:name "JavaScript" :mode "javascript"}]})
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (pending-boundary pending boundaries)
                               select/select (fn [props]
                                               (swap! select-props conj props)
                                               (.createElement react "div" nil))
                               shui/popup-hide! #(swap! actions conj :hide)]
                 (expand-hsx-component
                  (block/src-lang-picker
                   {:logseq.property.node/display-type :code
                    :logseq.property.code/lang "JavaScript"}
                   #(swap! actions conj :unexpected-same)))
                 (expand-hsx-component
                  (block/src-lang-picker
                   {:logseq.property.node/display-type :default}
                   #(swap! actions conj :unexpected-non-code)))
                 (expand-hsx-component
                  (block/src-lang-picker
                   {:logseq.property.node/display-type :code
                    :logseq.property.code/lang "Clojure"}
                   (fn [& _]
                     (swap! actions conj :stale-select)
                     nil)))
                 (let [results (mapv (fn [props]
                                       ((:on-chosen props)
                                        {:value "JavaScript"} nil nil (event nil #js {})))
                                     @select-props)]
                   (is (= (repeat 3 :code-language-choice) @boundaries))
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved results))]
                     (is (= [:hide :hide :stale-select :hide] @actions)))))
               (p/finally #(set! (.-CodeMirror js/window) previous-codemirror))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest property-input-escape-and-cleanup-await-before-editor-mutation
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               effect-fn (atom nil)
               key-handler (atom nil)
               source {:db/id 1 :block/uuid (random-uuid)}
               active-source (atom source)
               input #js {:focus #(swap! actions conj :focus)}
               previous-add (.-addEventListener js/window)
               previous-remove (.-removeEventListener js/window)]
           (set! (.-addEventListener js/window)
                 (fn [kind callback]
                   (when (= kind "keydown") (reset! key-handler callback))))
           (set! (.-removeEventListener js/window)
                 (fn [kind _callback] (swap! actions conj [:remove kind])))
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/get-edit-block #(deref active-source)
                               state/get-input (constantly input)
                               state/set-editor-action! #(swap! actions conj [:action %])
                               shui/popup-hide! #(swap! actions conj :hide)
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-ref (fn [value] #js {:current value})
                               hooks/use-atom (fn [atm] [@atm])
                               hooks/use-effect! (fn [f _] (reset! effect-fn f))
                               value/batch-operation? (constantly false)]
                 (expand-hsx-component
                  (property-component/property-input source (atom nil) {}))
                 (let [cleanup (@effect-fn)]
                   (reset! actions [])
                   (let [prevented? (atom false)
                         stopped? (atom false)
                         escape-result (@key-handler #js {:keyCode 27
                                                          :preventDefault #(reset! prevented? true)
                                                          :stopPropagation #(reset! stopped? true)})
                         cleanup-result (cleanup)]
                     (reset! active-source {:db/id 2 :block/uuid (random-uuid)})
                     (is (= [[:remove "keydown"]] @actions)
                         "Unmount detaches its listener synchronously; only mutations wait")
                     (is (and @prevented? @stopped?)
                         "The actual window Escape is synchronously suppressed while pending")
                     (p/resolve! pending {:status :committed})
                     (p/let [_ (p/all (map p/resolved [escape-result cleanup-result]))]
                       (is (= 2 (count (filter #{:hide} @actions))))
                       (is (some #{:focus} @actions))
                       (is (some #{[:action nil]} @actions))))))
               (p/finally (fn []
                            (set! (.-addEventListener js/window) previous-add)
                            (set! (.-removeEventListener js/window) previous-remove)))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest property-input-type-and-chosen-leaves-reacquire-before-db-or-close
  (async done
         (let [pending (p/deferred)
               boundaries (atom [])
               actions (atom [])
               type-select-props (atom [])
               property-select-props (atom nil)
               chosen-event (event "Enter" #js {})
               boundary-events (atom [])
               property-uuid (random-uuid)
               property {:db/id 2 :block/uuid property-uuid
                         :db/ident :user.property/remove-me
                         :block/title "Remove me"
                         :logseq.property/type :default}
               block-value {:db/id 1 :block/uuid (random-uuid)}]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (swap! boundaries conj boundary)
                                 (when (map? (first args))
                                   (swap! boundary-events conj (:event (first args))))
                                 (p/then pending (fn [_] ((last args)))))
                               shortcut/use-disable-all-shortcuts! (fn [])
                               shui/select (fn [props & _]
                                             (swap! type-select-props conj props)
                                             (.createElement react "div" nil))
                               property-component/property-select
                               (fn [props]
                                 (reset! property-select-props props)
                                 (.createElement react "div" nil))
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-ref (fn [value] #js {:current value})
                               hooks/use-atom (fn [atm] [@atm])
                               hooks/use-effect! (fn [& _])
                               value/batch-operation? (constantly false)
                               value/get-operating-blocks (fn [_] [block-value])
                               db/entity (fn [lookup]
                                           (when (or (= lookup [:block/uuid property-uuid])
                                                     (= lookup (:db/id property)))
                                             property))
                               db-property-handler/upsert-property!
                               (fn [& _] (swap! actions conj :upsert))
                               property-handler/batch-remove-block-property!
                               (fn [& _] (swap! actions conj :remove))
                               shui/popup-hide! #(swap! actions conj :hide)]
                 (expand-hsx-component
                 (property-component/property-type-select
                   property {:*property (atom property)
                             :*property-schema (atom {:logseq.property/type :default})
                             :*show-new-property-config? (atom false)
                             :*show-class-select? (atom false)}))
                 (let [type-handler (:on-value-change (first @type-select-props))
                       _ (expand-hsx-component
                          (property-component/property-input
                           block-value (atom nil) {:remove-property? true}))
                       type-result (type-handler "number")
                       chosen-result ((:on-chosen @property-select-props)
                                      {:value property-uuid :label "Remove me"}
                                      false [] chosen-event)]
                   (is (= [:property-input-type :property-input-chosen] @boundaries))
                   (is (identical? chosen-event (last @boundary-events))
                       "the real four-argument property select event reaches the fresh barrier")
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all [type-result chosen-result])]
                     (is (some #{:upsert} @actions))
                     (is (= [:remove :hide] (vec (filter #{:remove :hide} @actions)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest page-context-menu-uses-the-central-pending-boundary
  (async done
         (let [pending (p/deferred)
               popups (atom [])
               page-opts (atom nil)
               source {:block/uuid (random-uuid)}
               property {:db/id 2 :db/ident :user.property/page
                         :logseq.property/type :default}
               value-entity {:db/id 3 :block/uuid (random-uuid)
                             :block/title "Page" :block/tags [{:db/id 30}]}]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/get-edit-block (constantly source)
                               shui/popup-show! (fn [target & _]
                                                  (swap! popups conj target))]
                 (expand-hsx-component
                  (value/select-item
                   property :default value-entity
                   {:page-cp (fn [opts _]
                               (reset! page-opts opts)
                               nil)}))
                 (let [page-result ((:on-context-menu @page-opts) (event nil #js {}))]
                   (is (empty? @popups))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved page-result)]
                     (is (= 1 (count @popups))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest direct-property-value-entry-handlers-await-before-actions
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               trigger-props (atom [])
               block-value {:db/id 1 :block/uuid (random-uuid)}
               date-property {:db/id 2 :db/ident :user.property/date :logseq.property/type :date}
               select-property {:db/id 3 :db/ident :user.property/status :logseq.property/type :default}
               ref-target #js {:click #(swap! actions conj :ref-click)}
               event-target #js {:closest (constantly nil)}]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               hooks/use-ref (fn [_] #js {:current ref-target})
                               hooks/use-state (fn [initial]
                                                 [initial #(swap! actions conj [:state %])])
                               state/use-container-id (constantly 1)
                               state/clear-selection! #(swap! actions conj :clear-selection)
                               shui/trigger-as (fn [_ props & _]
                                                 (swap! trigger-props conj props)
                                                 (.createElement react "div" nil))
                               shui/popup-show! (fn [& _] (swap! actions conj :popup))
                               value/<create-new-block! (fn [& _] (swap! actions conj :create))]
                 (.renderToStaticMarkup
                  react-dom-server
                  (#'value/date-picker 1 {:block block-value :property date-property}))
                 (.renderToStaticMarkup
                  react-dom-server
                  (value/single-value-select
                   block-value select-property "open" {}
                   {:value-render (fn [] "open")}))
                 (let [[date-props select-props] @trigger-props
                       date-result ((:on-click date-props) (event nil event-target))
                       select-result ((:on-click select-props) (event nil event-target))
                       empty-element (expand-hsx-component
                                      (value/property-normal-block-value
                                       block-value select-property nil {}))
                       empty-result ((element-handler empty-element :on-click "onClick")
                                     (event nil event-target))]
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all [date-result select-result empty-result])]
                     (is (= [:popup :clear-selection :popup :create] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest string-checkbox-and-multiple-leaf-actions-await
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               input-props (atom nil)
               checkbox-props (atom [])
               state-call (atom 0)
               block-value {:db/id 1 :block/uuid (random-uuid) :user.property/enabled false}
               string-property {:db/id 2 :db/ident :user.property/text :logseq.property/type :string}
               checkbox-property {:db/id 3 :db/ident :user.property/enabled :logseq.property/type :checkbox}
               closed-property {:db/id 5
                                :db/ident :user.property/closed
                                :logseq.property/type :default
                                :property/closed-values
                                [{:db/id 51 :logseq.property/choice-checkbox-state false}
                                 {:db/id 52 :logseq.property/choice-checkbox-state true}]}
               closed-value {:db/id 52 :logseq.property/choice-checkbox-state true}
               many-property {:db/id 4 :db/ident :user.property/tags :logseq.property/type :default}
               target #js {:closest (constantly nil)
                           :focus #(swap! actions conj :focus)}]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               hooks/use-state
                               (fn [initial]
                                 (case (swap! state-call inc)
                                   1 [true #(swap! actions conj [:editing %])]
                                   2 [initial #(swap! actions conj [:value %])]
                                   3 [initial (fn [_])]
                                   [initial (fn [_])]))
                               hooks/use-ref (fn [_] #js {:current target})
                               hooks/use-effect! (fn [& _])
                               shui/input (fn [props]
                                            (reset! input-props props)
                                            (.createElement react "input" nil))
                               shui/checkbox (fn [props]
                                               (swap! checkbox-props conj props)
                                               (.createElement react "input" nil))
                               shui/popup-show! (fn [& _] (swap! actions conj :popup))
                               state/clear-selection! #(swap! actions conj :clear-selection)
                               model/sub-block (fn [id]
                                                 (case id
                                                   3 checkbox-property
                                                   5 closed-property
                                                   id))
                               outliner-property/get-block-classes (fn [& _] [])
                               db/entity (fn [_] block-value)
                               db-property-handler/set-block-property!
                               (fn [& _] (swap! actions conj :closed-checkbox-write))
                               value/<add-property! (fn [& _] (swap! actions conj :checkbox-write))]
                 (let [string-tree (expand-hsx-component
                                    (value/single-string-input block-value string-property "old" false))
                       click-result ((element-handler string-tree :on-click "onClick") (event nil target))
                       blur-result ((:on-blur @input-props) (event nil target))
                       enter-result ((:on-key-down @input-props) (event "Enter" target))
                       escape-result ((:on-key-down @input-props) (event "Escape" target))]
                   (expand-hsx-component
                    (value/property-scalar-value-aux block-value checkbox-property false {}))
                   (expand-hsx-component
                    (value/property-scalar-value-aux
                     (assoc block-value :logseq.property/checkbox-display-properties [closed-property])
                     closed-property closed-value {}))
                   (let [[ordinary-props closed-props] @checkbox-props
                         checkbox-result ((:on-checked-change ordinary-props))
                         closed-result ((:on-checked-change closed-props) false)
                         many-element (expand-hsx-component
                                       (value/multiple-values-inner block-value many-property #{} {}))
                         popup-result ((element-handler many-element :on-click "onClick") (event nil target))]
                     (is (empty? @actions))
                     (p/resolve! pending {:status :committed})
                     (p/let [_ (p/all (map p/resolved [click-result blur-result enter-result escape-result
                                                       checkbox-result closed-result popup-result]))]
                       (is (some #{:checkbox-write} @actions))
                       (is (some #{:closed-checkbox-write} @actions))
                       (is (some #{:popup} @actions))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest each-string-handler-independently-awaits-before-its-own-effect
  (async done
         (let [block-value {:db/id 1 :block/uuid (random-uuid)
                            :user.property/text "old"}
               property {:db/id 2 :db/ident :user.property/text
                         :logseq.property/type :string}
               run-case!
               (fn [kind]
                 (let [pending (p/deferred)
                       actions (atom [])
                       input-props (atom nil)
                       state-call (atom 0)
                       target #js {:closest (constantly nil)
                                   :focus #(swap! actions conj :focus)}]
                   (register-real-math-transition! block-value pending)
                   (p/with-redefs
                    [editor/consume-math-transition-boundary! (fn [_ promise] promise)
                     hooks/use-state
                     (fn [initial]
                       (case (swap! state-call inc)
                         1 [true #(swap! actions conj [:editing %])]
                         2 ["new" #(swap! actions conj [:value %])]
                         [initial (fn [_])]))
                     hooks/use-ref (fn [_] #js {:current target})
                     hooks/use-effect! (fn [& _])
                     state/get-edit-block (constantly block-value)
                     shui/input (fn [props]
                                  (reset! input-props props)
                                  (.createElement react "input" nil))
                     state/clear-selection! #(swap! actions conj :clear-selection)
                     db/entity (fn [_] (assoc block-value :user.property/text "new"))
                     db-property-handler/set-block-property!
                     (fn [& args] (swap! actions conj (into [:set-property] args)))]
                    (let [tree (expand-hsx-component
                                (value/single-string-input block-value property "old" false))
                          result (case kind
                                   :click ((element-handler tree :on-click "onClick")
                                           (event nil target))
                                   :blur ((:on-blur @input-props) (event nil target))
                                   :enter ((:on-key-down @input-props) (event "Enter" target))
                                   :escape ((:on-key-down @input-props) (event "Escape" target)))]
                      (is (empty? @actions) (str (name kind) " is independently gated"))
                      (p/resolve! pending {:status :committed})
                      (p/let [_ (p/resolved result)]
                        (when (contains? #{:click :escape} kind)
                          (is (seq @actions)
                              (str (name kind) " executes its visible effect after settle")))
                        (when (contains? #{:blur :enter} kind)
                          (is (some #(= :set-property (first %)) @actions)
                              (str (name kind) " performs its real public DB write after settle"))))))))]
           (-> (p/let [_ (run-case! :click)
                       _ (run-case! :blur)
                       _ (run-case! :enter)
                       _ (run-case! :escape)])
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest property-panel-edit-button-awaits-before-trigger-and-focus
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               block-value {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2
                         :db/ident :user.property/date
                         :block/title "Date"
                         :logseq.property/type :date}
               trigger #js {:click #(swap! actions conj :click)
                            :focus #(swap! actions conj :focus)}
               panel #js {:querySelector (fn [_] trigger)}
               button #js {:closest (fn [_] panel)}]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               db/entity (fn [_] property)
                               db/sub-block (fn [_] property)
                               state/use-sub-editing? (constantly false)
                               state/get-editor-action-data (constantly nil)]
                 (let [tree (expand-hsx-component
                             (property-component/property-cp
                              block-value (:db/ident property) nil
                              {:property-position :block-below}))
                       edit-node (find-element-class tree "property-panel-edit-btn")
                       result ((element-handler edit-node :on-click "onClick") (event nil button))]
                   (is (some? edit-node) "the public property panel branch renders the edit button")
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved result)]
                     (is (= [:click :focus] @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest bottom-row-and-pill-all-directions-await
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               block-uuid (random-uuid)
               block-node #js {:getAttribute (fn [key]
                                               (case key
                                                 "blockid" (str block-uuid)
                                                 "containerid" "9"
                                                 nil))}
               active #js {:getAttribute (fn [k] (when (= k "data-bottom-row-nav") "true"))
                           :focus #(swap! actions conj :focus-first)}
               next #js {:getAttribute (fn [k] (when (= k "data-bottom-row-nav") "true"))
                         :focus #(swap! actions conj :focus-next)}
               row #js {:querySelectorAll (fn [_] #js [active next])
                        :closest (fn [selector] (when (= selector ".ls-block") block-node))
                        :blur #(swap! actions conj :row-blur)}
               _ (set! (.-closest active) (fn [_] row))
               _ (set! (.-closest next) (fn [_] row))
               pill #js {:closest (fn [_] row)}
               previous-document (.-document js/globalThis)]
           (set! (.-document js/globalThis) #js {:activeElement active})
           (let [source {:db/id 1 :block/uuid block-uuid}]
             (register-real-math-transition! source pending))
           (-> (p/with-redefs [state/get-edit-block
                               (constantly {:db/id 1 :block/uuid block-uuid})
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               editor/edit-block!
                               (fn [block pos opts]
                                 (swap! actions conj [:edit (:block/uuid block) pos opts]))
                               editor/move-cross-boundary-up-down
                               (fn [direction opts] (swap! actions conj [:move direction opts]))]
                 (let [row-results (mapv #(#'block/handle-bottom-properties-row-key-down!
                                           (event % row))
                                         ["ArrowUp" "ArrowDown" "ArrowLeft" "ArrowRight"])
                       pill-results (mapv #(#'block/handle-bottom-pill-key-down!
                                            (event % pill))
                                          ["ArrowUp" "ArrowDown"])]
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (into row-results pill-results))]
                     (is (= [[:move :down {:exclude-property? true}]
                             [:move :down {:exclude-property? true}]]
                            (vec (filter #(and (vector? %)
                                               (= :move (first %)))
                                         @actions))))
                     (is (= [:focus-first :focus-next]
                            (vec (filter #{:focus-first :focus-next} @actions)))
                         "Left stays on the first item while Right uses the real next item")
                     (is (= 2 (count (filter #{:row-blur} @actions)))
                         "Row and pill ArrowUp each use closest row and blur it")
                     (is (= 2 (count (filter #(and (vector? %)
                                                  (= :edit (first %)))
                                            @actions)))
                         "Both ArrowUp paths focus the enclosing block editor"))))
               (p/finally #(set! (.-document js/globalThis) previous-document))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest number-navigation-prevents-default-synchronously-before-await
  (async done
         (let [pending (p/deferred)
               input-props (atom nil)
               actions (atom [])
               prevented? (atom false)
               state-call (atom 0)
               input #js {:selectionStart 0 :selectionEnd 0 :value "7"}
               outer #js {:focus (fn [])}
               block-value {:db/id 1 :block/uuid (random-uuid) :user.property/number 7}
               property {:db/id 2 :db/ident :user.property/number :logseq.property/type :number}]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               editor/move-cross-boundary-up-down
                               (fn [direction opts] (swap! actions conj [:move direction opts]))
                               hooks/use-state
                               (fn [initial]
                                 (case (swap! state-call inc)
                                   1 [true #(swap! actions conj [:editing %])]
                                   2 [initial #(swap! actions conj [:value %])]
                                   3 [initial (fn [_])]))
                               hooks/use-ref
                               (let [calls (atom 0)]
                                 (fn [_]
                                   (if (= 1 (swap! calls inc))
                                     #js {:current outer}
                                     #js {:current input})))
                               hooks/use-effect! (fn [& _])
                               shui/input (fn [props]
                                            (reset! input-props props)
                                            (.createElement react "input" nil))
                               db-property/property-value-content identity
                               util/input-text-selected? (constantly false)
                               cursor/pos (constantly 0)
                               db-property-handler/set-block-property! (fn [& _] nil)]
                 (.renderToStaticMarkup
                  react-dom-server
                  (value/single-number-input block-value property 7 false))
                 (let [e #js {:key "ArrowDown"
                              :preventDefault #(reset! prevented? true)
                              :stopPropagation (fn [])}
                       result ((:on-key-down @input-props) e)]
                   (is @prevented? "number navigation must synchronously prevent native increment")
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ result]
                     (is (= [[:move :down {}] [:editing false]] (take 2 @actions))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest number-no-pending-change-and-navigation-remain-synchronous
  (async done
         (let [input-props (atom nil)
               actions (atom [])
               state-call (atom 0)
               input #js {:selectionStart 0 :selectionEnd 0 :value "7"}
               outer #js {:focus (fn [])}
               block-value {:db/id 1 :block/uuid (random-uuid) :user.property/number 7}
               property {:db/id 2 :db/ident :user.property/number :logseq.property/type :number}]
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/move-cross-boundary-up-down
                               (fn [direction opts] (swap! actions conj [:move direction opts]))
                               hooks/use-state
                               (fn [initial]
                                 (case (swap! state-call inc)
                                   1 [true #(swap! actions conj [:editing %])]
                                   2 [initial #(swap! actions conj [:value %])]
                                   3 [initial (fn [_])]))
                               hooks/use-ref
                               (let [calls (atom 0)]
                                 (fn [_]
                                   (if (= 1 (swap! calls inc))
                                     #js {:current outer}
                                     #js {:current input})))
                               hooks/use-effect! (fn [& _])
                               shui/input (fn [props]
                                            (reset! input-props props)
                                            (.createElement react "input" nil))
                               db-property/property-value-content identity
                               util/input-text-selected? (constantly false)
                               cursor/pos (constantly 0)
                               db/entity (constantly block-value)
                               db-property-handler/set-block-property! (fn [& _] nil)]
                 (.renderToStaticMarkup react-dom-server
                                        (value/single-number-input block-value property 7 false))
                 ((:on-change @input-props) #js {:target #js {:value "8"}})
                 (let [prevented? (atom false)
                       result ((:on-key-down @input-props)
                               #js {:key "ArrowDown"
                                    :preventDefault #(reset! prevented? true)
                                    :stopPropagation (fn [])})]
                   (is @prevented?)
                   (is (= [[:value "8"]
                           [:move :down {}]]
                          @actions)
                       "No-pending value and move invocation stay synchronous")
                   (p/let [_ result]
                     (is (some #{[:editing false]} @actions)
                         "exit waits for the move Promise"))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest bottom-controls-click-enter-and-space-all-await
  (async done
         (let [pending (p/deferred)
               actions (atom [])
               captured-buttons (atom [])
               block-value {:db/id 1
                            :block/uuid (random-uuid)
                            :user.property/date 1}
               date-property {:db/id 2
                              :db/ident :user.property/date
                              :logseq.property/type :date}
               pill #js {}
               target #js {}
               current-target #js {}
               _ (set! (.-closest current-target) (constantly pill))]
           (register-real-math-transition! block-value pending)
           (-> (p/with-redefs [state/get-edit-block (constantly block-value)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               value/property-value (fn [& _] nil)
                               block/trigger-bottom-pill-edit!
                               (fn [_] (swap! actions conj [:date]))
                               shui/button (fn [props & _]
                                             (swap! captured-buttons conj props)
                                             (.createElement react "button" nil))
                               property-component/use-hidden-properties-visible (constantly false)
                               property-component/toggle-hidden-properties-visibility!
                               #(swap! actions conj [:hidden %])
                               state/pub-event! #(swap! actions conj [:new %])]
                 (let [date-hiccup (#'block/bottom-property-pill-cp block-value date-property {})
                       date-props (get-in date-hiccup [3 3 1])
                       _ (.renderToStaticMarkup
                          react-dom-server
                          (#'block/bottom-properties-expand-button
                           false #(swap! actions conj [:expand %])))
                       _ (.renderToStaticMarkup
                          react-dom-server
                          (property-component/hidden-properties-toggle-button
                           block-value {:icon-only? true}))
                       _ (.renderToStaticMarkup
                          react-dom-server
                          (property-component/new-property
                           block-value {:property-position :block-below}))
                       props-list (into [date-props] @captured-buttons)
                       results
                       (mapcat
                        (fn [props]
                          (let [click (:on-click props)
                                keydown (or (:on-key-down props) (:on-key-press props))]
                            (is (fn? click))
                            (is (fn? keydown))
                            (cond-> [(when click
                                      (click (event nil current-target)))]
                              keydown
                              (conj (keydown (event "Enter" target))
                                    (keydown (event " " target))))))
                        props-list)]
                   (is (empty? @actions))
                   (p/resolve! pending nil)
                   (p/let [_ (p/all results)]
                     (is (= (* 3 (count props-list)) (count @actions))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest number-navigation-awaits-move-before-exit-and-write-and-propagates-rejection
  (async done
         (let [block-value {:db/id 1 :block/uuid (random-uuid)
                            :user.property/number 7}
               property {:db/id 2 :db/ident :user.property/number
                         :logseq.property/type :number}
               run-case!
               (fn [reject?]
                 (let [move (p/deferred)
                       actions (atom [])
                       input-props (atom nil)
                       state-call (atom 0)
                       input #js {:selectionStart 0 :selectionEnd 0 :value "8"}
                       outer #js {:focus (fn [])}
                       prevented? (atom false)]
                   (p/with-redefs
                    [state/get-edit-block (constantly block-value)
                     editor/move-cross-boundary-up-down
                     (fn [direction opts]
                       (swap! actions conj [:move direction opts])
                       move)
                     hooks/use-state
                     (fn [initial]
                       (case (swap! state-call inc)
                         1 [true #(swap! actions conj [:editing %])]
                         2 ["8" #(swap! actions conj [:value %])]
                         3 [initial (fn [_])]))
                     hooks/use-ref
                     (let [calls (atom 0)]
                       (fn [_]
                         (if (= 1 (swap! calls inc))
                           #js {:current outer}
                           #js {:current input})))
                     hooks/use-effect! (fn [& _])
                     shui/input (fn [props]
                                  (reset! input-props props)
                                  (.createElement react "input" nil))
                     db-property/property-value-content identity
                     util/input-text-selected? (constantly false)
                     cursor/pos (constantly 0)
                     db/entity (constantly block-value)
                     db-property-handler/set-block-property!
                     (fn [& args]
                       (swap! actions conj (into [:write] args))
                       (p/resolved nil))]
                    (.renderToStaticMarkup
                     react-dom-server
                     (value/single-number-input block-value property 7 false))
                    (let [result ((:on-key-down @input-props)
                                  #js {:key "ArrowDown"
                                       :preventDefault #(reset! prevented? true)
                                       :stopPropagation (fn [])})]
                      (is @prevented? "native number increment is prevented synchronously")
                      (is (= [[:move :down {}]] @actions)
                          "exit and persistence wait for the asynchronous move")
                      (if reject?
                        (do
                          (p/reject! move (js/Error. "move failed"))
                          (-> result
                              (p/then (fn [_]
                                        (is false "move rejection must propagate")))
                              (p/catch (fn [error]
                                         (is (= "move failed" (.-message error)))
                                         (is (= [[:move :down {}]] @actions)
                                             "a rejected move cannot exit or persist")))))
                        (do
                          (p/resolve! move nil)
                          (p/let [_ result]
                            (is (= [:editing false] (second @actions)))
                            (is (= :write (first (nth @actions 2)))))))))))]
           (-> (p/let [_ (run-case! false)
                       _ (run-case! true)])
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest icon-value-selection-reacquires-before-real-property-write
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)}
               picker-props (atom nil)
               actions (atom [])
               chosen-event (event nil #js {})]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               hooks/use-effect! (fn [& _])
                               value/get-operating-blocks (fn [_] [source])
                               icon-component/icon-picker
                               (fn [_ props]
                                 (reset! picker-props props)
                                 (.createElement react "button" nil))
                               property-handler/batch-set-block-property!
                               (fn [& args]
                                 (swap! actions conj (into [:write] args))
                                 (p/resolved nil))
                               shui/popup-hide-all! #(swap! actions conj :hide)]
                 (.renderToStaticMarkup react-dom-server (value/icon-row source false))
                 (let [result ((:on-chosen @picker-props)
                               chosen-event {:id "star" :type :tabler-icon})]
                   (is (empty? @actions)
                       "icon value write and popup close wait for the event-time transition")
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved result)]
                     (is (= :write (ffirst @actions)))
                     (is (= :hide (last @actions))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest property-select-clear-single-and-multiple-leaves-use-four-argument-fresh-barriers
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)
                       :user.property/status {:db/id 3}}
               property {:db/id 2 :db/ident :user.property/status
                         :logseq.property/type :default}
               select-props (atom [])
               boundaries (atom [])
               events (atom [])
               actions (atom [])
               leaf-event (event "Enter" #js {})]
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (swap! boundaries conj boundary)
                                 (when (map? (first args))
                                   (swap! events conj (:event (first args))))
                                 (p/then pending (fn [_] ((last args)))))
                               hooks/use-memo (fn [f _] (f))
                               value/get-operating-blocks (fn [_] [source])
                               select/select (fn [props]
                                               (swap! select-props conj props)
                                               (.createElement react "div" nil))
                               property-handler/batch-remove-block-property!
                               (fn [& args]
                                 (swap! actions conj (into [:clear] args))
                                 (p/resolved nil))
                               shui/popup-hide! #(swap! actions conj :hide)]
                 (expand-hsx-component
                  (#'value/select-aux
                   source property
                   {:multiple-choices? false
                    :items [{:value 3 :label "Open"}]
                    :selected-choices [3]
                    :on-chosen (fn [& args]
                                 (swap! actions conj (into [:single] args)))}))
                 (expand-hsx-component
                  (#'value/select-aux
                   source property
                   {:multiple-choices? false
                    :items [{:value 4 :label "Closed"}]
                    :selected-choices []
                    :on-chosen (fn [& args]
                                 (swap! actions conj (into [:single] args)))}))
                 (expand-hsx-component
                  (#'value/select-aux
                   source property
                   {:multiple-choices? true
                    :items [{:value 3 :label "Open"}]
                    :selected-choices [3]
                    :on-chosen (fn [& args]
                                 (swap! actions conj (into [:multiple] args)))}))
                 (let [[clear-select single multiple] @select-props
                       clear-value (:value (some #(when (:clear? %) %)
                                                (:items clear-select)))
                       clear-result (invoke-four-argument-select!
                                     (:on-chosen clear-select) clear-value false [] leaf-event)
                       single-result (invoke-four-argument-select!
                                      (:on-chosen single) 4 false [4] leaf-event)
                       multiple-result (invoke-four-argument-select!
                                        (:on-chosen multiple) 3 false [3] leaf-event)]
                   (is (empty? @actions))
                   (is (= 3 (count @boundaries))
                       "clear/single/multiple values must reacquire at the chosen leaf")
                   (is (every? #(identical? leaf-event %) @events))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all [clear-result single-result multiple-result])]
                     (is (some #(= :clear (first %)) @actions))
                     (is (some #(= :multiple (first %)) @actions)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest hidden-asset-input-change-and-deferred-save-select-each-reacquire
  (async done
         (let [source {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2 :db/ident :user.property/assets
                         :logseq.property/type :asset}
               pending-open (p/deferred)
               pending-change (p/deferred)
               pending-select (p/deferred)
               upload (p/deferred)
               actions (atom [])
               listeners (atom {})
               upload-button (atom nil)
               state-call (atom 0)
               input #js {:style #js {}
                          :files #js [#js {:name "offline.png"}]}
               body #js {}
               previous-document (.-document js/globalThis)
               saved-asset {:db/id 8 :block/uuid (random-uuid)
                            :block/title "offline.png"
                            :logseq.property.asset/type "png"}]
           (set! (.-addEventListener input)
                 (fn [kind handler] (swap! listeners assoc kind handler)))
           (set! (.-click input) #(swap! actions conj :native-picker))
           (set! (.-removeChild body) #(swap! actions conj :input-cleanup))
           (set! (.-appendChild body)
                 (fn [child]
                   (set! (.-parentNode child) body)
                   child))
           (set! (.-document js/globalThis)
                 #js {:body body
                      :createElement (fn [kind]
                                       (is (= "input" kind))
                                       input)})
           (register-real-math-transition! source pending-open)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               hooks/use-state
                               (fn [initial]
                                 (case (swap! state-call inc)
                                   1 [[] #(swap! actions conj [:assets %])]
                                   2 [#{} #(swap! actions conj [:selected %])]
                                   3 [false #(swap! actions conj [:saving %])]
                                   [initial (fn [_])]))
                               hooks/use-effect! (fn [& _])
                               state/get-current-repo (constantly "offline-repo")
                               db-property/many? (constantly false)
                               editor/db-based-save-assets!
                               (fn [repo files]
                                 (swap! actions conj [:save repo (count files)])
                                 upload)
                               db-property-handler/set-block-property!
                               (fn [& args]
                                 (swap! actions conj (into [:write] args))
                                 (p/resolved nil))
                               shui/button (fn [props & _]
                                             (reset! upload-button props)
                                             (.createElement react "button" nil))
                               shui/checkbox (fn [& _] nil)]
                 (.renderToStaticMarkup
                  react-dom-server
                  (value/asset-grid-popup-content source property {}))
                 (let [open-result ((:on-click @upload-button) (event nil input))]
                   (is (empty? @actions))
                   (p/resolve! pending-open {:status :committed})
                   (p/let [_ open-result]
                     (is (= [:native-picker] @actions))
                     (register-real-math-transition! source pending-change)
                     (let [change-result ((get @listeners "change")
                                          #js {:target input})]
                       (is (= [:native-picker] @actions)
                           "hidden input change cannot start upload before its fresh barrier")
                       (p/resolve! pending-change {:status :committed})
                       (p/let [_ (p/delay 0)]
                         (is (some #(and (vector? %) (= :save (first %))) @actions))
                         (register-real-math-transition! source pending-select)
                         (p/resolve! upload [saved-asset])
                         (p/let [_ (p/delay 0)]
                           (is (not-any? #(and (vector? %) (= :write (first %))) @actions)
                               "a deferred upload completion must reacquire before select/write")
                           (p/resolve! pending-select {:status :committed})
                           (p/let [_ (p/resolved change-result)
                                   _ (p/delay 0)]
                             (is (= 1 (count (filter #(and (vector? %)
                                                          (= :write (first %)))
                                                    @actions)))))))))))
               (p/finally (fn []
                            (set! (.-document js/globalThis) previous-document)))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest calendar-day-enter-and-delete-leaves-each-reacquire
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)
                       :user.property/date 1}
               property {:db/id 2 :db/ident :user.property/date
                         :logseq.property/type :date}
               calendar-props (atom nil)
               window-keydown (atom nil)
               boundaries (atom [])
               boundary-events (atom [])
               actions (atom [])
               day (js/Date.)
               day-event (event nil #js {})
               delete-event (event nil #js {})]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary & args]
                                 (swap! boundaries conj boundary)
                                 (when (map? (first args))
                                   (swap! boundary-events conj [boundary (:event (first args))]))
                                 (p/then pending (fn [_] ((last args)))))
                               db/sub-block (constantly source)
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-callback (fn [f _] f)
                               hooks/use-effect! (fn [& _])
                               hooks/use-window-keydown
                               (fn [handler _] (reset! window-keydown handler))
                               value/<resolve-journal-page-for-date
                               (fn [_] (p/resolved {:db/id 9 :block/journal-day 20260811}))
                               ui/nlp-calendar
                               (fn [props]
                                 (reset! calendar-props props)
                                 (.createElement react "div" nil))
                               value/repeat-setting (fn [& _] nil)
                               shui/separator (fn [& _] nil)
                               shui/popup-hide! #(swap! actions conj :hide)
                               ui/hide-popups-until-preview-popup!
                               #(swap! actions conj :hide-until-preview)]
                 (.renderToStaticMarkup
                  react-dom-server
                  (#'value/calendar-inner
                   :date-popup
                   {:block source
                    :property property
                    :on-change #(swap! actions conj [:change %])
                    :on-delete #(swap! actions conj [:delete %])
                    :del-btn? true}))
                 (let [day-result (invoke-calendar-day!
                                   (:on-day-click @calendar-props) day #js {} day-event)
                       enter-result (@window-keydown (event "Enter" #js {:closest (constantly nil)}))
                       delete-result ((:on-delete @calendar-props) delete-event)]
                   (is (= [:property-date-day :property-date-enter :property-date-delete]
                          @boundaries)
                       "calendar day, keyboard Enter, and delete are independent fresh leaves")
                   (is (identical? day-event
                                   (second (some #(when (= :property-date-day (first %)) %)
                                                 @boundary-events)))
                       "the DayPicker event reaches the event-time barrier")
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved
                                         [day-result enter-result delete-result]))]
                     (is (= 2 (count (filter #(and (vector? %)
                                                  (= :change (first %)))
                                            @actions))))
                     (is (= 1 (count (filter #(and (vector? %)
                                                  (= :delete (first %)))
                                            @actions)))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest focusable-property-and-asset-pointerdowns-suppress-only-while-pending
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2 :block/uuid (random-uuid)
                         :block/title "Date" :db/ident :user.property/date
                         :logseq.property/type :date}
               trigger-props (atom [])
               button-props (atom [])
               make-event (fn []
                            (let [prevented? (atom false)
                                  stopped? (atom false)
                                  target #js {:closest (constantly nil)}]
                              {:event #js {:target target :currentTarget target
                                           :metaKey false :altKey false
                                           :preventDefault #(reset! prevented? true)
                                           :stopPropagation #(reset! stopped? true)}
                               :prevented? prevented?
                               :stopped? stopped?}))]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               util/meta-key? (constantly false)
                               db/entity (fn [_] property)
                               db/sub-block (fn [_] property)
                               state/use-sub-editing? (constantly false)
                               state/get-editor-action-data (constantly nil)
                               value/property-value (fn [& _] nil)
                               state/get-component (constantly nil)
                               shui/checkbox (fn [& _] nil)
                               shui/trigger-as (fn [_ props & _]
                                                 (swap! trigger-props conj props)
                                                 (.createElement react "button" nil))
                               shui/button (fn [props & _]
                                             (swap! button-props conj props)
                                             (.createElement react "button" nil))]
                 (.renderToStaticMarkup react-dom-server
                                        (property-component/property-key-title source property false))
                 (.renderToStaticMarkup react-dom-server
                                        (property-component/property-key-cp source property {}))
                 (let [panel-tree (expand-hsx-component
                                   (property-component/property-cp
                                    source (:db/ident property) nil
                                    {:property-position :block-below}))
                       panel-node (find-element-class panel-tree "property-panel-edit-btn")
                       panel-handler (element-handler panel-node :on-pointer-down "onPointerDown")
                       asset-node (expand-hsx-component
                                   (value/asset-grid-cell
                                    {:db/id 3 :block/uuid (random-uuid)
                                     :block/title "asset"
                                     :logseq.property.asset/type "png"}
                                    false (fn [& _])))
                       asset-handler (element-handler asset-node :on-pointer-down "onPointerDown")
                       _ (expand-hsx-component
                          (value/asset-grid-upload-button false (fn [] nil)))
                       upload-props (last @button-props)
                       handlers [[:property-title (:on-pointer-down (first @trigger-props))]
                                 [:property-icon (:on-pointer-down (second @trigger-props))]
                                 [:property-panel panel-handler]
                                 [:asset-grid asset-handler]
                                 [:asset-upload (:on-pointer-down upload-props)]]
                       pending-events (mapv (fn [_] (make-event)) handlers)
                       pending-results
                       (mapv (fn [[label handler] event-state]
                                 (is (fn? handler)
                                     (str (name label) " exposes a pointerdown barrier"))
                                 (when handler (handler (:event event-state))))
                             handlers pending-events)]
                   (doseq [{:keys [prevented? stopped?]} pending-events]
                     (is @prevented?)
                     (is @stopped?))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved pending-results))
                           _ (p/delay 0)]
                     (let [ordinary (make-event)
                           handler asset-handler
                           result (when handler (handler (:event ordinary)))]
                       (is (false? @(:prevented? ordinary))
                           "no-pending pointerdown keeps native focus/default behavior")
                       (is (false? @(:stopped? ordinary)))
                       (p/resolved result)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest real-icon-picker-awaits-action-outcome-before-popup-close
  (async done
         (let [first-result (p/deferred)
               outcomes (atom [first-result
                               (p/resolved {:status :rolled-back})
                               (p/resolved {:status :recovery-failed})
                               (p/rejected (js/Error. "unknown icon failure"))])
               trigger-props (atom nil)
               content-fn (atom nil)
               search-props (atom nil)
               actions (atom [])]
           (-> (p/with-redefs [hooks/use-ref (fn [_] #js {:current nil})
                               hooks/use-effect! (fn [& _])
                               shui/button (fn [props & _]
                                             (reset! trigger-props props)
                                             (.createElement react "button" nil))
                               shui/popup-show! (fn [_target content & _]
                                                  (reset! content-fn content))
                               shui/popup-hide! #(swap! actions conj [:hide %])
                               icon-component/icon-search
                               (fn [props]
                                 (reset! search-props props)
                                 (.createElement react "div" nil))]
                 (expand-hsx-component
                  (icon-component/icon-picker
                   nil
                   {:on-chosen (fn [& _]
                                 (let [result (first @outcomes)]
                                   (swap! outcomes subvec 1)
                                   result))}))
                 ((:on-click @trigger-props) (event nil #js {}))
                 (@content-fn {:id :real-icon-popup})
                 (let [choose! (:on-chosen @search-props)
                       committed (choose! (event nil #js {}) {:id "a"} false)]
                   (is (empty? @actions)
                       "the real wrapper cannot close before its action settles")
                   (p/resolve! first-result {:status :committed})
                   (p/let [_ committed]
                     (is (= [[:hide :real-icon-popup]] @actions))
                     (p/let [_ (choose! (event nil #js {}) {:id "b"} false)]
                       (is (= 2 (count @actions))
                           "recoverable rollback is a settled outcome and may close")
                       (p/let [typed (choose! (event nil #js {}) {:id "c"} false)]
                         (is (= :recovery-failed (:status typed)))
                         (is (= 2 (count @actions))
                             "typed recovery failure must not close")
                         (-> (choose! (event nil #js {}) {:id "d"} false)
                             (p/then (fn [_]
                                       (is false "unknown rejection must propagate")))
                             (p/catch (fn [error]
                                        (is (= "unknown icon failure" (.-message error)))
                                        (is (= 2 (count @actions)))))))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest real-property-value-date-select-and-icon-pointers-pass-the-native-event
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)
                       :user.property/date 1
                       :user.property/status {:db/id 30}
                       :logseq.property/icon {:id "star" :type :tabler-icon}}
               properties [{:db/id 2 :db/ident :user.property/date
                            :logseq.property/type :date}
                           {:db/id 3 :db/ident :user.property/status
                            :logseq.property/type :default
                            :property/closed-values [{:db/id 30}]}
                           {:db/id 4 :db/ident :logseq.property/icon
                            :logseq.property/type :default}]
               make-event (fn []
                            (let [prevented? (atom false)
                                  stopped? (atom false)
                                  target #js {:closest (constantly nil)}]
                              {:event #js {:target target :currentTarget target
                                           :preventDefault #(reset! prevented? true)
                                           :stopPropagation #(reset! stopped? true)}
                               :prevented? prevented?
                               :stopped? stopped?}))
               actions (atom [])]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               state/get-component (constantly nil)
                               ui/catch-error (fn [_ view] view)
                               db/entity (fn [lookup]
                                           (if (= lookup (:db/id source)) source lookup))
                               model/sub-block identity
                               db-property/many? (constantly false)
                               state/use-sub-editing? (constantly false)
                               state/get-editor-action-data (constantly nil)
                               state/clear-selection! #(swap! actions conj :clear-selection)]
                 (let [nodes (mapv (fn [property]
                                     (let [tree (expand-hsx-component
                                                 (value/property-value source property {}))
                                           node (find-element-class tree "property-value-inner")]
                                       (is (some? node)
                                           (str "the public " (:logseq.property/type property)
                                                " property value renders its production pointer boundary"))
                                       node))
                                   properties)
                       event-states (mapv (fn [_] (make-event)) nodes)
                       results (mapv (fn [node event-state]
                                       (let [handler (some-> node
                                                             (element-handler :on-pointer-down
                                                                              "onPointerDown"))]
                                         (is (fn? handler))
                                         (when handler
                                           (handler (:event event-state)))))
                                     nodes event-states)]
                   (doseq [{:keys [prevented? stopped?]} event-states]
                     (is @prevented?)
                     (is @stopped?))
                   (is (empty? @actions))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved results))
                           _ (p/delay 0)]
                     (is (= 3 (count @actions)))
                     (let [ordinary (make-event)
                           handler (some-> (first nodes)
                                           (element-handler :on-pointer-down "onPointerDown"))
                           result (when handler (handler (:event ordinary)))]
                       (is (false? @(:prevented? ordinary)))
                       (is (false? @(:stopped? ordinary)))
                       (p/resolved result)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest daypicker-v9-day-click-and-select-both-forward-the-real-event
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2 :db/ident :user.property/date
                         :logseq.property/type :date}
               boundaries (atom [])
               changes (atom [])
               click-date (js/Date. 2026 7 11)
               select-date (js/Date. 2026 7 12)
               trigger-date (js/Date. 2026 7 13)
               click-event (event nil #js {})
               select-event (event nil #js {})]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary options action]
                                 (swap! boundaries conj [boundary (:event options)])
                                 (p/then pending (fn [_] (action))))
                               db/sub-block (constantly source)
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-callback (fn [f _] f)
                               hooks/use-effect! (fn [& _])
                               hooks/use-window-keydown (fn [& _])
                               value/<resolve-journal-page-for-date
                               (fn [d]
                                 (p/resolved {:db/id (.getDate d)
                                              :block/journal-day 20260811}))
                               value/repeat-setting (fn [& _] nil)
                               shui/separator (fn [& _] nil)
                               shui/popup-hide! (fn [& _])
                               ui/hide-popups-until-preview-popup! (fn [& _])]
                 (let [calendar-tree
                       (expand-hsx-component
                        (#'value/calendar-inner
                         :date-popup
                         {:block source
                          :property property
                          :on-change #(swap! changes conj %)}))
                       nlp-component (react-component-fn (ui/nlp-calendar {}))
                       nlp-element (find-react-element
                                    calendar-tree
                                    #(identical? nlp-component (react-component-fn %)))
                       _ (is (some? nlp-element)
                             "calendar-inner must compose the real nlp-calendar")
                       nlp-tree (when nlp-element (expand-hsx-component nlp-element))
                       daypicker (find-react-element
                                  nlp-tree
                                  (fn [element]
                                    (let [props (.-props element)]
                                      (and (fn? (aget props "onDayClick"))
                                           (fn? (aget props "onSelect"))))))
                       _ (is (some? daypicker)
                             "the real nlp-calendar must expose the DayPicker v9 callbacks")
                       props (some-> daypicker (.-props))
                       click-result (when props
                                      ((aget props "onDayClick") click-date #js {} click-event))
                       select-result (try
                                       (when props
                                         ((aget props "onSelect") select-date trigger-date
                                          #js {} select-event))
                                       (catch :default error
                                         (is false (str "DayPicker v9 onSelect rejected four arguments: " error))
                                         (p/resolved nil)))]
                   (is (= [[:property-date-day click-event]
                           [:property-date-day select-event]]
                          @boundaries))
                   (is (empty? @changes))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved [click-result select-result]))]
                     (is (= [11 12] (mapv :db/id @changes))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest datetime-wrapper-forwards-daypicker-v9-four-argument-select-event
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)}
               property {:db/id 2 :db/ident :user.property/due
                         :logseq.property/type :datetime}
               boundaries (atom [])
               changes (atom [])
               selected-date (js/Date. 2026 7 14 9 30)
               trigger-date (js/Date. 2026 7 15 9 30)
               select-event (event nil #js {})]
           (-> (p/with-redefs [editor/run-math-transition-action!
                               (fn [boundary options action]
                                 (swap! boundaries conj [boundary (:event options)])
                                 (p/then pending (fn [_] (action))))
                               db/sub-block (constantly source)
                               hooks/use-memo (fn [f _] (f))
                               hooks/use-callback (fn [f _] f)
                               hooks/use-effect! (fn [& _])
                               hooks/use-window-keydown (fn [& _])
                               gdom/getElement (fn [_] #js {:value "09:30"})
                               value/<resolve-journal-page-for-date
                               (fn [d]
                                 (p/resolved {:db/id (.getDate d)
                                              :block/journal-day 20260814}))
                               value/repeat-setting (fn [& _] nil)
                               shui/separator (fn [& _] nil)
                               shui/popup-hide! (fn [& _])
                               ui/hide-popups-until-preview-popup! (fn [& _])]
                 (let [calendar-tree
                       (expand-hsx-component
                        (#'value/calendar-inner
                         :datetime-popup
                         {:block source
                          :property property
                          :datetime? true
                          :on-change #(swap! changes conj %)}))
                       nlp-component (react-component-fn (ui/nlp-calendar {}))
                       nlp-element (find-react-element
                                    calendar-tree
                                    #(identical? nlp-component (react-component-fn %)))
                       _ (is (some? nlp-element)
                             "datetime calendar must compose the real nlp-calendar wrapper")
                       nlp-tree (when nlp-element (expand-hsx-component nlp-element))
                       daypicker (find-react-element
                                  nlp-tree
                                  (fn [element]
                                    (fn? (aget (.-props element) "onSelect"))))
                       _ (is (some? daypicker)
                             "datetime wrapper must expose the real DayPicker callback")
                       select-result (try
                                       (when daypicker
                                         ((aget (.-props daypicker) "onSelect")
                                          selected-date trigger-date #js {} select-event))
                                       (catch :default error
                                         (is false (str "datetime DayPicker onSelect rejected four arguments: "
                                                        error))
                                         (p/resolved nil)))]
                   (is (= [[:property-date-day select-event]] @boundaries))
                   (is (empty? @changes))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/resolved select-result)]
                     (is (= [(.getTime selected-date)] @changes)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest property-icon-row-generic-picker-click-keyboard-and-programmatic-open-await
  (async done
         (let [pending (p/deferred)
               source {:db/id 1 :block/uuid (random-uuid)}
               popups (atom [])
               trigger-props (atom nil)
               click-handler (atom nil)
               target #js {}
               _ (set! (.-click target)
                       #(when-let [handler @click-handler]
                          (handler (event nil target))))]
           (register-real-math-transition! source pending)
           (-> (p/with-redefs [state/get-edit-block (constantly source)
                               editor/consume-math-transition-boundary! (fn [_ promise] promise)
                               hooks/use-effect! (fn [& _])
                               hooks/use-ref (fn [_] #js {:current nil})
                               value/get-operating-blocks (fn [_] [source])
                               shui/button (fn [props & _]
                                             (reset! trigger-props props)
                                             (.createElement react "button" nil))
                               shui/popup-show!
                               (fn [captured-target _content options]
                                 (swap! popups conj [captured-target options]))]
                 (.renderToStaticMarkup react-dom-server (value/icon-row source false))
                 (let [click! (:on-click @trigger-props)
                       _ (reset! click-handler click!)
                       click-result (when click! (click! (event nil target)))
                       key-result (when click! (click! (event "Enter" target)))
                       programmatic-result (.click target)]
                   (is (fn? click!)
                       "icon-row must compose the generic icon-picker trigger")
                   (is (empty? @popups))
                   (p/resolve! pending {:status :committed})
                   (p/let [_ (p/all (map p/resolved
                                         [click-result key-result
                                          programmatic-result]))
                           _ (p/delay 0)]
                     (is (= 3 (count @popups)))
                     (let [ordinary (click! (event nil target))]
                       (is (= 4 (count @popups))
                           "the no-pending generic picker retains synchronous popup behavior")
                       (p/resolved ordinary)))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest real-radix-asset-trigger-controls-one-open-after-settlement
  (async done
         (let [make-event (fn []
                            (let [prevented? (atom false)
                                  stopped? (atom false)
                                  target #js {}]
                              {:event #js {:target target :currentTarget target
                                           :preventDefault #(reset! prevented? true)
                                           :stopPropagation #(reset! stopped? true)}
                               :prevented? prevented? :stopped? stopped?}))
               run-case!
               (fn [settlement expected-status]
                 (let [pending (p/deferred)
                       production-run! editor/run-math-transition-action!
                       source {:db/id 1 :block/uuid (random-uuid)}
                       block-node #js {:getAttribute (fn [key]
                                                       (when (= key "blockid")
                                                         (str (:block/uuid source))))}
                       ref-el #js {:closest (constantly block-node)}
                       open? (atom false)
                       open-transitions (atom [])]
                   (when (= :resolve settlement)
                     (register-real-math-transition! source pending))
                   (p/with-redefs [state/get-edit-block (constantly source)
                                   editor/run-math-transition-action!
                                   (case settlement
                                     :resolve production-run!
                                     :typed (fn [_boundary options _action]
                                              (util/stop (:event options))
                                              (p/rejected
                                               (ex-info "content-free"
                                                        {:type :math-transition/recovery-failed})))
                                     :unknown (fn [_boundary options _action]
                                                (util/stop (:event options))
                                                (p/rejected
                                                 (js/Error. "unknown asset trigger"))))
                                   hooks/use-effect! (fn [& _])
                                   hooks/use-ref (fn [_] #js {:current ref-el})
                                   hooks/use-state (fn [initial]
                                                     [(if (nil? @open?) initial @open?)
                                                      (fn [next-open?]
                                                        (swap! open-transitions conj next-open?)
                                                        (reset! open? next-open?))])
                                   util/electron? (constantly false)]
                     (let [tree (expand-hsx-component
                                 (block/asset-container
                                  source "asset.png" "asset" nil
                                  {:breadcrumb? false :positioned? false
                                   :local? false :full-text "" :gallery-view? false}))
                           root (find-react-element
                                 tree (fn [element]
                                        (let [props (.-props element)]
                                          (and (boolean? (aget props "open"))
                                               (fn? (aget props "onOpenChange"))))))
                           trigger (find-react-element
                                    tree (fn [element]
                                           (let [props (.-props element)]
                                             (and (true? (aget props "asChild"))
                                                  (fn? (aget props "onPointerDown"))))))
                           _ (is (some? root)
                                 "the production Radix Root must be controlled")
                           _ (is (some? trigger)
                                 "the production Radix Trigger owns the pending barrier")
                           trigger-props (some-> trigger (.-props))
                           root-props (some-> root (.-props))
                           pending-event (make-event)
                           trigger-result (when trigger-props
                                            ((aget trigger-props "onPointerDown")
                                             (:event pending-event)))]
                       (is @(:prevented? pending-event))
                       (is @(:stopped? pending-event))
                       (is (empty? @open-transitions))
                       (case settlement
                         :resolve (p/resolve! pending {:status :committed})
                         nil)
                       (let [observed (-> (p/resolved trigger-result)
                                          (p/then (fn [value] {:value value}))
                                          (p/catch (fn [error] {:error error})))]
                         (p/let [_ observed]
                           (case expected-status
                             :opened
                             (do
                               (is (= [true] @open-transitions)
                                   "settled pending activation controls one open")
                               (let [ordinary-event (make-event)
                                     ordinary-result ((aget trigger-props "onPointerDown")
                                                      (:event ordinary-event))]
                                 (is (false? @(:prevented? ordinary-event)))
                                 (is (false? @(:stopped? ordinary-event)))
                                 (is (= [true] @open-transitions)
                                     "ordinary Trigger pointerdown does not synthesize an open")
                                 (when root-props
                                   ((aget root-props "onOpenChange") true))
                                 (is (= [true true] @open-transitions)
                                     "the one native Radix onOpenChange is authoritative")
                                 (p/resolved ordinary-result)))

                             :typed
                             (is (empty? @open-transitions))

                             :unknown
                             (is (empty? @open-transitions)))))))))]
           (-> (p/let [_ (run-case! :resolve :opened)
                       _ (run-case! :typed :typed)
                       _ (run-case! :unknown :unknown)])
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))

(deftest icon-picker-real-icon-search-returns-and-awaits-selection-promises
  (async done
         (let [first-outcome (p/deferred)
               unknown-outcome (p/deferred)
               outcomes (atom [first-outcome
                                (p/resolved {:status :rolled-back})
                                (p/resolved {:status :recovery-failed})
                                unknown-outcome])
               trigger-props (atom nil)
               content-fn (atom nil)
               search-options (atom nil)
               used (atom [])]
           (-> (p/with-redefs [hooks/use-ref (fn [_] #js {:current nil})
                               hooks/use-effect! (fn [& _])
                               hooks/use-state (fn [initial] [initial (fn [_])])
                               hooks/use-memo (fn [f _] (f))
                               shui/button (fn [props & _]
                                             (reset! trigger-props props)
                                             (.createElement react "button" nil))
                               shui/input (fn [& _] nil)
                               shui/tabler-icon (fn [& _] nil)
                               shui/popup-show! (fn [_target content & _]
                                                  (reset! content-fn content))
                               icon-component/all-cp
                               (fn [opts]
                                 (reset! search-options opts)
                                 [:div.real-icon-search-body])
                               icon-component/add-used-item!
                               #(swap! used conj %)]
                 (expand-hsx-component
                  (icon-component/icon-picker
                   nil
                   {:on-chosen (fn [& _]
                                 (let [result (first @outcomes)]
                                   (swap! outcomes subvec 1)
                                   result))}))
                 ((:on-click @trigger-props) (event nil #js {}))
                 (let [search-element (@content-fn {:id :real-composed-icon-popup})]
                   (is (react/isValidElement search-element)
                       "icon-picker must compose the real icon-search")
                   (expand-hsx-component search-element)
                   (let [choose! (:on-chosen @search-options)
                         chosen {:type :tabler-icon :id "star"}
                         first-result (choose! (event nil #js {}) chosen)]
                     (is (p/promise? first-result)
                         "the real icon-search wrapper returns its async on-chosen outcome")
                     (is (empty? @used)
                         "used-icon persistence cannot run before selection settles")
                     (p/resolve! first-outcome {:status :committed})
                     (p/let [outcome (if (p/promise? first-result)
                                      first-result
                                      first-outcome)]
                       (is (= :committed (:status outcome)))
                       (is (= [chosen] @used))
                       (let [rolled {:type :tabler-icon :id "rotate"}
                             typed {:type :tabler-icon :id "alert"}
                             rolled-result (choose! (event nil #js {}) rolled)]
                         (is (p/promise? rolled-result))
                         (p/let [rolled-outcome rolled-result]
                           (is (= :rolled-back (:status rolled-outcome)))
                           (is (= [chosen rolled] @used)
                               "a settled recoverable outcome preserves icon-search persistence")
                           (p/let [typed-outcome (choose! (event nil #js {}) typed)]
                             (is (= :recovery-failed (:status typed-outcome)))
                             (is (= [chosen rolled] @used)
                                 "typed recovery failure propagates without persistence"))))
                       (let [unknown-result (choose! (event nil #js {})
                                                     {:type :tabler-icon :id "x"})]
                         (is (p/promise? unknown-result)
                             "unknown selection rejection remains observable at the real composition")
                         (if (p/promise? unknown-result)
                           (let [observed
                                 (-> unknown-result
                                     (p/then (fn [_]
                                               (is false "unknown selection failure resolved")))
                                     (p/catch (fn [error] {:error error})))
                                 _ (p/reject! unknown-outcome
                                              (js/Error. "unknown icon-search failure"))]
                             (p/let [receipt observed]
                               (let [error (:error receipt)]
                                 (is (= "unknown icon-search failure" (.-message error)))
                                 (is (= ["star" "rotate"] (mapv :id @used))))))
                           (do
                             ;; Current broken code discards the derived picker
                             ;; Promise. Resolve its source to avoid turning a
                             ;; contract failure into an unrelated unhandled rejection.
                             (p/resolve! unknown-outcome {:status :committed})
                             (is false "real icon-search discarded the unknown rejection")
                             (p/resolved nil))))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (or (.-stack error) (str error)))
                          (done)))))))
