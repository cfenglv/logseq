(ns frontend.components.cmdk.core-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [frontend.components.block.breadcrumb :as block-breadcrumb]
   [frontend.context.i18n :as i18n]
   [frontend.components.cmdk.core :as cmdk]
   [frontend.components.icon :as icon-component]
   [frontend.db.async :as db-async]
   [frontend.handler.db-based.recent :as db-recent-handler]
   [frontend.handler.editor.format :as editor-format]
   [frontend.search :as search]
   [frontend.state :as state]
   [frontend.util :as util]
   [frontend.util.page :as page-util]
   [goog.object :as gobj]
   [logseq.shui.hooks :as hooks]
   [logseq.shui.ui :as shui]
   [promesa.core :as p]))

(deftest existing-matches-precede-page-creation-test
  (let [page {:result-type :page
              :source-block {:page? true :block.temp/original-title "car"}}
        alias-target {:page? true :block.temp/original-title "vehicle"}
        alias {:result-type :page :alias alias-target :source-block alias-target}
        another-page {:result-type :page
                      :source-block {:page? true :block.temp/original-title "cart"}}
        block {:result-type :block :source-block {:block/title "hello"}}
        another-block {:result-type :block :source-block {:block/title "hello again"}}
        command {:source-command {:id :test-command}}
        file {:file-path "car.pdf"}
        filter-item {:filter {:group :nodes}}
        namespaced-page {:result-type :page
                         :source-block {:page? true :block.temp/original-title "transport/car"}}]
    (doseq [[input filter-group results expected-groups expected-action]
            [["c" nil {:nodes {:items [page]}} [:nodes :create] :open]
             ["ca" nil {:nodes {:items [page]}} [:nodes :create] :open]
             ["car" nil {:nodes {:items [page]}} [:nodes] :open]
             ["car" nil {:nodes {:items [alias]}} [:nodes :create] :open]
             ["transport/ca" nil {:nodes {:items [namespaced-page]}} [:nodes :create] :open]
             ["ca" :nodes {:nodes {:items [page]}} [:nodes :create] :open]
             ["ca" nil {:current-page {:items [page]}} [:current-page :create] :open]
             ["ca" nil {:nodes {:items [page another-page]}
                        :commands {:items [command]}
                        :files {:items [file]}}
              [:nodes :create :nodes :commands :files] :open]
             ["car" nil {:nodes {:items [alias another-page]}}
              [:nodes :create :nodes] :open]
             ["transport/ca" nil {:nodes {:items [namespaced-page another-page]}
                                  :files {:items [file]}}
              [:nodes :create :nodes :files] :open]
             ["ca" :nodes {:nodes {:items [page another-page]}}
              [:nodes :create :nodes] :open]
             ["hello" nil {:nodes {:items [block another-block]}
                           :commands {:items [command]}
                           :filters {:items [filter-item]}}
              [:nodes :create :nodes :commands :filters] :open]
             ["ca" nil {:current-page {:items [block another-block]}
                        :nodes {:items [page another-page]}}
              [:current-page :current-page :nodes :create :nodes] :open]
             ["carr" nil {:filters {:items [filter-item]}} [:create :filters] :create]
             ["transport/carr" nil {:filters {:items [filter-item]}} [:create :filters] :create]]]
      (testing (str "Default action for " input " with filter " filter-group)
        (let [state {::cmdk/input (atom input)
                     ::cmdk/filter (atom (when filter-group {:group filter-group}))
                     ::cmdk/results (atom results)
                     ::cmdk/highlighted-item (atom nil)
                     ::cmdk/all-items-cache (atom [])
                     ::cmdk/focus-source (atom :keyboard)
                     ::cmdk/scroll-container-ref (atom nil)}
              items (vec (mapcat last (cmdk/state->results-ordered state)))]
          (reset! (::cmdk/all-items-cache state) items)
          (is (= expected-groups (mapv :group items)))
          (is (= expected-action (cmdk/state->action state)))
          (when-let [creation-index (some #(when (:source-create %) (:item-index %)) items)]
            (cmdk/move-highlight state creation-index)
            (is (= :create (cmdk/state->action state)))))))))

(deftest literature-filter-keeps-doi-and-excludes-page-creation-test
  (let [query "10.5555/shared-paper"
        paper {:source-block {:block/uuid #uuid "11111111-1111-1111-1111-111111111111"
                              :logseq.property/literature-item? true}}
        filter-state {::cmdk/input (atom query)
                      ::cmdk/filter (atom {:group :literature})
                      ::cmdk/results (atom {:literature {:items [paper]}
                                           :nodes {:items [{:source-block {:block/title "unrelated note"}}]}})}
        groups (cmdk/state->results-ordered filter-state)]
    (is (= [:literature] (mapv second groups)))
    (is (= [paper] (mapv #(dissoc % :group :item-index) (mapcat last groups))))
    (is (not-any? :source-create (mapcat last groups))
        "A DOI containing a slash stays in literature search without proposing a page.")))

(deftest literature-filter-reuses-search-and-ignores-stale-query-test
  (async done
    (let [query (atom "old paper")
          results (atom {})
          filter-state {::cmdk/input query
                        ::cmdk/filter (atom {:group :literature})
                        ::cmdk/results results}
          old-result (p/deferred)
          new-result (p/deferred)
          calls (atom [])
          paper {:block/uuid #uuid "11111111-1111-1111-1111-111111111111"
                 :block/title "Correct paper"
                 :literature/detail "Example · 2026"}]
      (-> (p/with-redefs [state/get-current-repo (constantly "graph-a")
                          icon-component/get-node-icon-cp (constantly nil)
                          db-async/<search-literature-papers
                          (fn [repo input]
                            (swap! calls conj [repo input])
                            (if (= input "old paper") old-result new-result))]
            (let [old-request (cmdk/load-results :literature filter-state)]
              (reset! query "10.5555/shared-paper")
              (let [new-request (cmdk/load-results :literature filter-state)]
                (p/resolve! new-result {:status :ok :papers [paper]})
                (p/let [_ new-request]
                  (is (= [["graph-a" "old paper"] ["graph-a" "10.5555/shared-paper"]] @calls))
                  (is (= (:block/uuid paper)
                         (get-in @results [:literature :items 0 :source-block :block/uuid])))
                  (is (= "Example · 2026" (get-in @results [:literature :items 0 :detail])))
                  (p/resolve! old-result {:status :ok :papers []})
                  (p/let [_ old-request]
                    (is (= 1 (count (get-in @results [:literature :items])))
                        "A late result for the previous input must not erase the current paper."))))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest literature-filter-distinguishes-unavailable-search-from-no-match
  (async done
    (let [response (atom nil)
          results (atom {})
          search-state {::cmdk/input (atom "existing paper")
                        ::cmdk/filter (atom {:group :literature})
                        ::cmdk/results results}]
      (-> (p/with-redefs [state/get-current-repo (constantly "graph-a")
                          db-async/<search-literature-papers (fn [& _] @response)]
            (p/doseq [[result expected] [[{:status :unavailable :reason :index-rebuilding} :unavailable]
                                        [{:status :disabled} :unavailable]
                                        [:rejected :unavailable]
                                        [{:status :ok :papers []} :success]]]
              (reset! results {:literature {:items [{:text "Previous stale item"}]}})
              (do
                (reset! response (if (= :rejected result)
                                   (p/rejected (js/Error. "Synthetic missing worker"))
                                   (p/resolved result)))
                nil)
              (p/let [_ (cmdk/load-results :literature search-state)]
                (is (= expected (get-in @results [:literature :status])))
                (is (empty? (get-in @results [:literature :items]))))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest completed-index-refreshes-current-node-query
  (let [search-state {::cmdk/input (atom "existing paper")
                      ::cmdk/filter (atom nil)}
        running {:repo "graph-a" :status :running :build-id "build-a"}
        completed (assoc running :status :completed)
        calls (atom [])]
    (with-redefs [state/get-current-repo (constantly "graph-a")
                  cmdk/load-results (fn [group value]
                                      (swap! calls conj [group @(::cmdk/input value)]))]
      (#'cmdk/refresh-after-index-build! search-state running completed)
      (is (= [[:nodes "existing paper"]] @calls))
      (reset! calls [])
      (doseq [next-build [running (assoc running :progress 50)
                         (assoc completed :visible? false)]]
        (#'cmdk/refresh-after-index-build! search-state completed next-build))
      (is (empty? @calls) "progress and visibility updates do not repeat a search")
      (#'cmdk/refresh-after-index-build! search-state completed (assoc completed :build-id "build-b"))
      (is (= 1 (count @calls)) "a new completed build refreshes even if intermediate progress was batched")
      (reset! calls [])
      (#'cmdk/refresh-after-index-build! search-state running (assoc completed :repo "graph-b"))
      (#'cmdk/refresh-after-index-build! search-state completed completed)
      (is (empty? @calls) "another graph and an already completed build do not refresh")
      (doseq [input ["" "   "]]
        (reset! (::cmdk/input search-state) input)
        (#'cmdk/refresh-after-index-build! search-state running completed))
      (is (empty? @calls) "blank input keeps the existing initial search behavior")
      (reset! (::cmdk/input search-state) "existing paper")
      (doseq [group [:commands :files :themes :filters]]
        (reset! (::cmdk/filter search-state) {:group group})
        (#'cmdk/refresh-after-index-build! search-state running completed))
      (is (empty? @calls) "non-node filters are independent of the rebuilt index")
      (doseq [group [:nodes :codes :current-page :literature]]
        (reset! (::cmdk/filter search-state) {:group group})
        (#'cmdk/refresh-after-index-build! search-state running completed))
      (is (= [:nodes :codes :current-page :literature] (mapv first @calls))))))

(deftest shift-open-page-uses-page-action-test
  (async done
    (let [page-id #uuid "11111111-1111-1111-1111-111111111111"
          item {:result-type :page
                :source-block {:block/uuid page-id
                               :block/name "ordinary page"}}
          state {::cmdk/highlighted-item (atom item)}
          calls (atom [])]
      (-> (p/with-redefs [db-async/<get-block
                          (fn [& args]
                            (swap! calls conj [:get-block args])
                            (p/resolved (:source-block item)))
                          editor-format/open-block-in-sidebar!
                          (fn [block-id]
                            (swap! calls conj [:open block-id]))
                          shui/dialog-close!
                          (fn [dialog-id]
                            (swap! calls conj [:close dialog-id]))]
            (cmdk/handle-action :open state {:shift? true}))
          (p/then
           (fn []
             (is (= [[:open page-id]
                     [:close :ls-dialog-cmdk]]
                    @calls))))
          (p/catch
           (fn [error]
             (is false (str error))))
          (p/finally done)))))

(defn- cmdk-input-state
  []
  {::cmdk/input (atom "")
   ::cmdk/input-ref (atom #js {:value ""})
   ::cmdk/focus-source (atom nil)
   ::cmdk/highlighted-item (atom {:text "Tag"})
   ::cmdk/pending-scroll-item-idx (atom 2)
   ::cmdk/scroll-container-ref (atom nil)})

(deftest cmdk-input-change-does-not-search-on-the-keystroke-test
  (is (false? (cmdk/search-on-input-event? false false false))
      "Each typed character updates input only; search is not armed on the keystroke.")
  (is (false? (cmdk/search-on-input-event? false true false)))
  (is (true? (cmdk/search-on-input-event? true false false)))
  (is (false? (cmdk/search-on-input-event? true true false))
      "IME composition does not search on each key.")
  (is (true? (cmdk/search-on-input-event? true true true)))
  (let [state (cmdk-input-state)
        event (js-obj)]
    (gobj/set event "type" "input")
    (gobj/set event "target" #js {:value "t"})
    (cmdk/handle-input-change state event "t" false)
    (is (= "t" @(::cmdk/input state)))
    (let [search-calls (atom 0)]
      (with-redefs [cmdk/load-results (fn [& _args] (swap! search-calls inc))]
        (cmdk/handle-input-change state event "ta" false)
        (is (zero? @search-calls)
            "The input event used while typing must not search.")))))

(deftest cmdk-initial-results-do-not-clobber-typed-search-test
  (async done
    (let [kept [{:text "kept-node"}]
          results (atom {:nodes {:status :success :items kept}})
          state {::cmdk/input (atom "table-search-filter-actions")
                 ::cmdk/filter (atom nil)
                 ::cmdk/results results}]
      (p/with-redefs [db-recent-handler/get-recent-pages
                      (fn [] (p/delay 20 [{:block/title "Recent"}]))]
        (cmdk/load-results :initial state)
        (js/setTimeout
         (fn []
           (is (= kept (get-in @results [:nodes :items]))
               "A late empty-state fetch must not reset nodes from a typed search.")
           (done))
         40)))))

(deftest cmdk-search-debouncer-coalesces-continuous-typing-test
  (async done
    (is (= 150 cmdk/search-debounce-ms)
        "CMDK search waits 150ms after typing stops.")
    (let [calls (atom 0)
          [schedule! cancel!] (cmdk/make-search-debouncer #(swap! calls inc))
          keystroke-gap 80
          last-keystroke (* 4 keystroke-gap)]
      (doseq [delay (range 0 (+ last-keystroke 1) keystroke-gap)]
        (js/setTimeout schedule! delay))
      (js/setTimeout
       (fn []
         (is (zero? @calls)
              "Search must wait for the final pause in continuous typing."))
        (+ last-keystroke 100))
      (js/setTimeout
       (fn []
         (cancel!)
         (is (= 1 @calls)
             "five keystrokes 80 ms apart should trigger one search after the pause")
         (done))
       (+ last-keystroke cmdk/search-debounce-ms 80)))))

(deftest composition-start-cancels-a-queued-input-search-test
  (async done
    (let [search-state (assoc (cmdk-input-state)
                             ::cmdk/filter (atom nil)
                             ::cmdk/last-refresh-key (atom nil))
          calls (atom [])
          event (fn [value composing?]
                  #js {:type "input"
                       :target #js {:value value}
                       :isComposing composing?})]
      (-> (p/with-redefs [hooks/use-memo (fn [create _deps] (create))
                          hooks/use-effect! (fn [& _args])
                          hooks/use-atom (fn [a] [@a #(reset! a %)])
                          i18n/t (constantly "")
                          state/get-current-repo (constantly "repo-a")
                          state/get-state (constantly nil)
                          cmdk/persist-cmdk-query-state! (fn [_state])
                          cmdk/load-results (fn [_group value]
                                              (swap! calls conj @(::cmdk/input value)))]
            (let [element (cmdk/input-row search-state [] {:sidebar? true})
                  render (gobj/get (.-type element) "type")
                  rendered (render (.-props element))
                  input-element (some (fn [child]
                                        (when (and child (= "input" (gobj/get child "type"))) child))
                                      (array-seq (gobj/getValueByKeys rendered "props" "children")))
                  props (gobj/get input-element "props")
                  on-change (gobj/get props "onChange")
                  composition-start (gobj/get props "onCompositionStart")
                  composition-end (gobj/get props "onCompositionEnd")]
              ;; Invoke the actual rendered input handlers with the real debounce.
              (on-change (event "n" false))
              (is (fn? composition-start))
              (when composition-start
                (composition-start #js {:type "compositionstart"}))
              (on-change (event "ni" true))
              (p/let [_ (p/delay (+ cmdk/search-debounce-ms 60))]
                (is (empty? @calls)
                    "A queued ordinary input must not query marked IME text.")
                (is (= "ni" @(::cmdk/input search-state)))
                (composition-end #js {:type "compositionend"
                                     :target #js {:value "你"}})
                (p/let [_ (p/delay (+ cmdk/search-debounce-ms 60))]
                  (is (= ["你"] @calls)
                      "Composition end still searches the committed text once.")))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest refresh-results-skips-duplicate-search-key-test
  (let [calls (atom [])
        cmdk-state {::cmdk/input (atom "#Movies")
                    ::cmdk/filter (atom {:group :nodes})
                    ::cmdk/last-refresh-key (atom nil)}]
    (with-redefs [cmdk/load-results (fn [& args] (swap! calls conj args))
                  cmdk/persist-cmdk-query-state! (fn [_state])
                  state/get-current-repo (constantly "repo-a")
                  state/get-state (constantly nil)]
      (#'cmdk/refresh-results! cmdk-state)
      (#'cmdk/refresh-results! cmdk-state)
      (is (= 1 (count @calls))
          "Mount effects with the same repo/input/filter/action should issue one search."))))

(defn- keydown-event
  [{:keys [key key-code composing?]}]
  (let [stopped? (atom false)
        event (js-obj)]
    (gobj/set event "key" key)
    (gobj/set event "keyCode" key-code)
    (gobj/set event "isComposing" (boolean composing?))
    (gobj/set event "ctrlKey" false)
    (gobj/set event "metaKey" false)
    (gobj/set event "shiftKey" false)
    (gobj/set event "stopPropagation" #(reset! stopped? true))
    {:event event
     :stopped? stopped?}))

(defn- cmdk-keydown-state
  []
  {::cmdk/input (atom "nihao")
   ::cmdk/filter (atom nil)
   ::cmdk/highlighted-item (atom {:text "Create page" :source-create :page :group :create})
   ::cmdk/all-items-cache (atom [])
   ::cmdk/focus-source (atom :keyboard)
   ::cmdk/results (atom {})
   ::cmdk/scroll-container-ref (atom nil)})

(defn- enter-action-calls
  [event]
  (let [calls (atom [])]
    (with-redefs [shui/shortcut-press! (fn [shortcut & _]
                                         (swap! calls conj [:shortcut shortcut]))
                  cmdk/handle-action (fn [action & _]
                                       (swap! calls conj [:action action]))
                  util/stop-propagation (fn [_]
                                          (swap! calls conj [:stop-propagation]))]
      (#'cmdk/keydown-handler (cmdk-keydown-state) event))
    @calls))

(deftest keydown-handler-ignores-ime-composition-enter
  (testing "macOS IME commit Enter (keyCode 229) does not run the highlighted action"
    (let [{:keys [event]} (keydown-event {:key "Enter" :key-code 229 :composing? false})]
      (is (empty? (enter-action-calls event)))))

  (testing "isComposing Enter does not run the highlighted action"
    (let [{:keys [event]} (keydown-event {:key "Enter" :key-code 13 :composing? true})]
      (is (empty? (enter-action-calls event)))))

  (testing "Process key from IME does not run the highlighted action"
    (let [{:keys [event]} (keydown-event {:key "Process" :key-code 229 :composing? false})]
      (is (empty? (enter-action-calls event))))))

(deftest keydown-handler-runs-highlighted-action-on-plain-enter
  (testing "plain Enter still selects the highlighted cmdk item"
    (let [{:keys [event]} (keydown-event {:key "Enter" :key-code 13 :composing? false})]
      (is (= [[:shortcut "return"]
              [:action :default]
              [:stop-propagation]]
             (enter-action-calls event))))))

(deftest load-results-codes-unwraps-matched-count-map-test
  (async done
    (let [block {:block/uuid #uuid "22222222-2222-2222-2222-222222222222"
                 :block/title "code snippet"}
          results (atom {:codes {:status :success :items nil}})
          state {::cmdk/input (atom "x")
                 ::cmdk/results results}
          search-opts (atom nil)
          breadcrumb-ids (atom [])]
      (-> (p/with-redefs [state/get-current-repo (constantly "repo")
                          state/get-current-page (constantly nil)
                          page-util/get-current-page-uuid (constantly nil)
                          search/block-search
                          (fn [_repo _input opts]
                            (reset! search-opts opts)
                            (p/resolved {:items [block] :matched-count 1}))
                          icon-component/get-node-icon-cp (fn [_ _] "code")
                          block-breadcrumb/breadcrumb
                          (fn [_opts _repo id _breadcrumb-opts]
                            (swap! breadcrumb-ids conj id)
                            [:breadcrumb id])]
            (cmdk/load-results :codes state)
            (p/delay 40))
          (p/then
           (fn []
             (is (true? (:code-only? @search-opts)))
             (is (true? (:include-matched-count? @search-opts)))
             (let [items (get-in @results [:codes :items])]
               (is (= :success (get-in @results [:codes :status])))
               (is (= 1 (count items)))
               (is (= block (:source-block (first items))))
               (is (= [#uuid "22222222-2222-2222-2222-222222222222"]
                      @breadcrumb-ids)
                   "Code search must unwrap :items so breadcrumbs get a real block uuid, not nil."))))
          (p/catch
           (fn [error]
             (is false (str error))))
          (p/finally done)))))

(deftest late-initial-results-preserve-current-search
  (async done
    (let [recent (p/deferred)
          queried {::cmdk/input (atom "")
                   ::cmdk/filter (atom nil)
                   ::cmdk/results (atom {})}
          filtered {::cmdk/input (atom "")
                    ::cmdk/filter (atom nil)
                    ::cmdk/results (atom {})}
          initial {::cmdk/input (atom "")
                   ::cmdk/filter (atom nil)
                   ::cmdk/results (atom {})}
          search-results {:commands {:status :success :items [{:text "Current command"}]}}]
      (-> (p/with-redefs [db-recent-handler/get-recent-pages (fn [] recent)]
            (let [requests (mapv #(cmdk/load-results :initial %) [queried filtered initial])]
              (reset! (::cmdk/input queried) "Current")
              (reset! (::cmdk/filter filtered) {:group :commands})
              (reset! (::cmdk/results queried) search-results)
              (reset! (::cmdk/results filtered) search-results)
              (p/resolve! recent [])
              (p/all requests)))
          (p/then
           (fn [_]
             (is (= search-results @(::cmdk/results queried)))
             (is (= search-results @(::cmdk/results filtered)))
             (is (= [] (vec (get-in @(::cmdk/results initial)
                                   [:recently-updated-pages :items]))))
             (is (= :success (get-in @(::cmdk/results initial)
                                    [:recently-updated-pages :status])))))
          (p/catch #(is false (str %)))
          (p/finally done)))))
