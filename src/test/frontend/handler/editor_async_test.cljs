(ns frontend.handler.editor-async-test
  (:require [cljs.test :refer [is testing async use-fixtures]]
            [electron.ipc :as ipc]
            [datascript.core :as d]
            [dommy.core :as dom]
            [frontend.components.block.comments-model :as comments-model]
            [frontend.db.async :as db-async]
            [frontend.db.conn :as conn]
            [frontend.db.subs :as db-subs]
            [frontend.db.transact :as db-transact]
            [frontend.db.utils :as db-utils]
            [frontend.fs :as fs]
            [frontend.handler.assets :as assets-handler]
            [frontend.handler.block :as block-handler]
            [frontend.handler.comments :as comments-handler]
            [frontend.handler.common :as common-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor]
            [frontend.handler.editor.assets :as editor-assets]
            [frontend.handler.editor.quick-add :as editor-quick-add]
            [frontend.handler.export.html :as export-html]
            [frontend.handler.literature :as literature-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.user :as user-handler]
            [frontend.modules.outliner.op :as frontend-outliner-op]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async load-test-files]]
            [frontend.util :as util]
            [frontend.worker.handler.block :as worker-block-handler]
            [frontend.worker.handler.comments :as worker-comments]
            [goog.dom :as gdom]
            [logseq.db :as ldb]
            [logseq.db.frontend.asset :as db-asset]
            [logseq.outliner.op :as outliner-op]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

(defonce ^:private *previous-state (atom nil))

(def ^:private test-worker-block-pull
  '[* {:block/page [*]
       :block/parent [*]
       :block/_parent [*]
       :block/tags [*]}])

(defn- block-by-worker-id
  [test-db id]
  (when-let [entity
             (or
              (when (uuid? id)
                (db-utils/entity test-db [:block/uuid id]))
              (when (string? id)
                (or
                 (when (util/uuid-string? id)
                   (db-utils/entity test-db [:block/uuid (uuid id)]))
                 (ldb/get-page test-db id)))
              (db-utils/entity test-db id))]
    (d/pull test-db test-worker-block-pull (:db/id entity))))

(defn- <test-db-worker
  [api & args]
  (case api
    :thread-api/get-blocks
    (let [[repo requests] args
          test-db (conn/get-db repo)]
      (p/resolved
       (mapv (fn [{:keys [id opts]}]
               (let [block (block-by-worker-id test-db id)]
                 (cond
                   (nil? block) nil
                   (:children? opts)
                   {:block block
                    :children (mapv #(d/pull test-db
                                             test-worker-block-pull
                                             (:db/id %))
                                    (ldb/get-children test-db (:db/id block)))}
                   :else
                   {:block block})))
             requests)))

    :thread-api/get-block-sibling
    (let [[repo block-id direction] args
          test-db (conn/get-db repo)
          block (db-utils/entity test-db block-id)
          sibling (case direction
                    :left (ldb/get-left-sibling block)
                    :right (ldb/get-right-sibling block)
                    nil)]
      (p/resolved
       (when sibling
         (d/pull test-db test-worker-block-pull (:db/id sibling)))))

    :thread-api/get-comment-threads-for-block
    (let [[repo block-uuid] args]
      (p/resolved
       (worker-comments/get-comment-threads-for-block
        (conn/get-db repo)
        block-uuid)))

    (p/resolved nil)))

(defn- apply-test-outliner-ops!
  [_db ops opts]
  (let [result (outliner-op/apply-ops! (conn/get-db test-helper/test-db false)
                                        ops
                                        (dissoc opts :editor/edit-block-fn))]
    (when-let [edit-block-f (:editor/edit-block-fn opts)]
      (edit-block-f nil))
    result))

(use-fixtures :each
  {:before (fn []
             (reset! *previous-state (state/get-state))
             (async done
                    (test-helper/start-test-db!)
                    (done)))
   :after (fn []
            (let [previous-state @*previous-state]
              (test-helper/destroy-test-db!)
              (state/set-current-repo! (:git/current-repo previous-state))
              (state/replace-state! previous-state)
              (reset! *previous-state nil)))})

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
        previous-repo (state/get-state :git/current-repo)]
    (state/set-state! :git/current-repo test-helper/test-db)
    (-> (p/with-redefs
         [editor/get-state (constantly {:block-id (:block/uuid block)
                                        :block-parent-id block-dom-id
                                        :config {:embed? embed?}
                                        :value edit-content})
          state/<invoke-db-worker <test-db-worker
          db-transact/apply-outliner-ops apply-test-outliner-ops!
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
            (state/set-state! :git/current-repo previous-repo))))))

(deftest-async delete-block-async!
  (p/do!
  (testing "backspace deletes empty block"
    (load-test-files
     [{:page {:block/title "page1"}
       :blocks
       [{:block/title "b1"}
        {:block/title "b2"}
        {:block/title ""}]}])
    (p/let [conn (conn/get-db test-helper/test-db false)
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
                                                   :container-id nil
                                                   :save-code-editor? false
                                                   :save-current-block? false
                                                   :skip-load? true}}
                                           (some-> (last @edit-calls)
                                                   (update :block :block/title)))
                                        "Deleting an empty block should focus the previous block")))})))

  (testing "backspace deletes empty block in embedded context"
    ;; testing embed at this layer doesn't require an embed block since
    ;; delete-block handles all the embed setup
    (p/let [conn (conn/get-db test-helper/test-db false)
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
                                     (is (empty? deleted-blocks) "Deleted block is removed from page db")))})))))

(deftest-async backspace-between-empty-blocks-focuses-current-block-like-master
  (load-test-files
   [{:page {:block/title "empty-delete-page"}
     :blocks
     [{:block/title "before"}
      {:block/title ""}
      {:block/title ""}]}])
  (p/let [conn (conn/get-db test-helper/test-db false)
          blocks (->> (d/q '[:find (pull ?b [*])
                             :where
                             [?p :block/name "empty-delete-page"]
                             [?b :block/page ?p]
                             [(missing? $ ?b :logseq.property/deleted-at)]]
                           @conn)
                      (map first)
                      ldb/sort-by-order)
          current-block (last blocks)
          edit-calls (atom [])]
    (delete-block @conn current-block
                  {:on-edit (fn [block pos opts]
                              (swap! edit-calls conj {:block block
                                                      :pos pos
                                                      :opts opts}))
                   :on-delete
                   (fn []
                     (let [visible-blocks (->> (d/q '[:find (pull ?b [*])
                                                        :where
                                                        [?p :block/name "empty-delete-page"]
                                                        [?b :block/page ?p]
                                                        [(missing? $ ?b :logseq.property/deleted-at)]]
                                                      @conn)
                                               (map first))]
                       (is (= 2 (count visible-blocks)))
                       (is (= (:block/uuid current-block)
                              (some-> @edit-calls last :block :block/uuid))
                           "Backspace must keep the current block focused, matching master.")))})))

(deftest-async backspace-falls-back-to-the-loaded-editor-block-before-worker-persistence
  (let [page {:db/id 10}
        previous-block {:db/id 1
                        :block/uuid (random-uuid)
                        :block/title ""
                        :block/parent page
                        :block/page page}
        current-block {:block/uuid (random-uuid)
                       :block/title ""
                       :block/parent page
                       :block/page page}
        previous-dom #js {:__logseqBlock previous-block
                          :getAttribute (fn [attr]
                                          (case attr
                                            "blockid" (str (:block/uuid previous-block))
                                            "containerid" nil
                                            nil))}
        worker-fetches (atom 0)
        applied-ops (atom nil)]
    (-> (p/with-redefs [state/get-edit-content (constantly "")
                        util/get-prev-block-non-collapsed-non-embed
                        (constantly previous-dom)
                        db-async/<get-block-with-children
                        (fn [& _]
                          (swap! worker-fetches inc)
                          (p/resolved nil))
                        state/<invoke-db-worker
                        (fn [api & _]
                          (if (= :thread-api/get-comment-threads-for-block api)
                            (p/resolved [])
                            (p/resolved nil)))
                        db-transact/apply-outliner-ops
                        (fn [_db ops _opts]
                          (reset! applied-ops ops)
                          (p/resolved nil))
                        editor/edit-block! (constantly nil)]
          (editor/delete-block-inner!
           test-helper/test-db
           {:block-id (:block/uuid current-block)
            :block current-block
            :value ""
            :config {}
            :block-container #js {}}))
        (p/then
         (fn []
           (is (= 1 @worker-fetches)
               "Backspace should check the worker for the canonical child structure.")
           (is (= [[:delete-blocks [[(:block/uuid previous-block)] {}]]]
                  @applied-ops)
               "The loaded editor block should remain usable until worker persistence catches up.")))
        (p/finally (fn [] nil)))))

(deftest-async delete-selection-focuses-the-previous-block-after-the-worker-transaction
  (let [previous-block {:db/id 1
                        :block/uuid (random-uuid)
                        :block/title "previous"}
        deleted-block {:db/id 2
                       :block/uuid (random-uuid)
                       :block/title "deleted"}
        previous-dom #js {:getAttribute #({"blockid" (str (:block/uuid previous-block))
                                           "containerid" nil} %)}
        deleted-dom #js {}
        tx-opts (atom nil)
        worker-lookups (atom 0)
        edit-call (atom nil)]
    (-> (p/with-redefs [util/get-prev-block-non-collapsed-non-embed
                        (fn [block]
                          (is (identical? deleted-dom block))
                          previous-dom)
                        db-async/<get-block
                        (fn [& _args]
                          (swap! worker-lookups inc)
                          (p/resolved nil))
                        db-transact/apply-outliner-ops (fn [_conn _ops opts]
                                                        (reset! tx-opts opts)
                                                        (p/resolved nil))
                        editor/edit-block! (fn [block pos opts]
                                             (reset! edit-call [block pos opts]))]
          (-> (editor/delete-blocks! "test-repo"
                                     [(:block/uuid deleted-block)]
                                     [deleted-block]
                                     [deleted-dom]
                                     false)
              (p/then
               (fn [_]
                 (is (nil? @edit-call)
                     "Deletion should not focus before the renderer applies the response")
                 (is (zero? @worker-lookups)
                     "Selection deletion should use the post-transaction window instead of a preflight worker lookup")
                 (is (fn? (:editor/edit-block-fn @tx-opts))
                     "Deletion should carry a response-local renderer callback")
                 (let [edit-block-f (:editor/edit-block-fn @tx-opts)]
                   (is (fn? edit-block-f)
                       "Deletion should run focus from the worker response")
                   (when edit-block-f
                     (edit-block-f [previous-block])))
                 (is (= [previous-block 8
                         {:custom-content "previous"
                          :tail-len 0
                          :container-id nil
                          :save-code-editor? false
                          :save-current-block? false
                          :skip-load? true}]
                        @edit-call)
                     "Deletion should restore focus from the renderer callback")))))
        (p/finally (fn [] nil)))))

(defn- selection-block-dom
  [block]
  #js {:closest (constantly nil)
       :getAttribute (fn [attr]
                       (when (= attr "blockid")
                         (str (:block/uuid block))))})

(defn- <delete-selected-blocks!
  [blocks]
  (p/with-redefs [state/<invoke-db-worker <test-db-worker
                  db-transact/apply-outliner-ops apply-test-outliner-ops!
                  editor/get-selected-blocks (fn [] (mapv selection-block-dom blocks))
                  dom/has-class? (constantly false)
                  dom/attr (fn [node attr]
                             (when node
                               (.getAttribute node attr)))
                  util/get-prev-block-non-collapsed-non-embed (constantly nil)
                  editor/edit-block! (constantly nil)]
    (editor/cut-selection-blocks false)))

(deftest-async delete-selected-child-page-unlinks-namespace-and-keeps-content
  (load-test-files
   [{:page {:block/title "Parent page"}
     :blocks [{:block/title "sibling"}]}
    {:page {:block/title "Child page"}
     :blocks [{:block/title "keep this content"}
              {:block/title "and this too"}]}])
  (let [conn (conn/get-db test-helper/test-db false)
        parent (ldb/get-page @conn "parent page")
        child (ldb/get-page @conn "child page")]
    (d/transact! conn [{:db/id (:db/id child)
                        :block/parent (:db/id parent)
                        :block/order "a1"}])
    (p/let [_ (<delete-selected-blocks! [(d/entity @conn (:db/id child))])
            child' (ldb/get-page @conn "child page")
            parent' (ldb/get-page @conn "parent page")
            child-titles (->> (d/q '[:find [?title ...]
                                     :in $ ?page
                                     :where
                                     [?b :block/page ?page]
                                     [?b :block/title ?title]
                                     [(missing? $ ?b :logseq.property/deleted-at)]]
                                   @conn
                                   (:db/id child'))
                              set)
            parent-children (map :block/title (ldb/sort-by-order (:block/_parent parent')))]
      (is (some? child')
          "The child page entity must remain after selection delete")
      (is (nil? (:block/parent child'))
          "Selection delete must only remove the namespace relationship")
      (is (= #{"keep this content" "and this too"} child-titles)
          "Child page content must stay intact")
      (is (= ["sibling"] parent-children)
          "The parent page must keep its own blocks and lose the child page"))))

(deftest-async delete-selected-block-still-removes-its-children
  (load-test-files
   [{:page {:block/title "page1"}
     :blocks [{:block/title "parent"
               :build/children [{:block/title "nested child"}]}]}])
  (p/let [parent (test-helper/find-block-by-content "parent")
          _ (<delete-selected-blocks! [parent])]
    (is (nil? (test-helper/find-block-by-content "parent")))
    (is (nil? (test-helper/find-block-by-content "nested child"))
        "Selection delete of a regular block must still retract its subtree")))

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
  (p/let [conn (conn/get-db test-helper/test-db false)
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
  (p/let [conn (conn/get-db test-helper/test-db false)
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
    (-> (p/with-redefs [state/<invoke-db-worker <test-db-worker
                        db-transact/apply-outliner-ops apply-test-outliner-ops!
                        state/get-edit-content (constantly "")
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
        (p/finally (fn [] nil)))))

(deftest-async top-level-blank-enter-does-not-query-right-sibling
  (let [page {:db/id 10}
        block {:db/id 1
               :block/uuid (random-uuid)
               :block/title ""
               :block/parent page}
        input #js {:value ""}
        inserts (atom 0)]
    (p/with-redefs [editor/get-state (constantly {:block block
                                                  :config {:page page}
                                                  :node input})
                    editor/inside-of-single-block (constantly false)
                    state/get-input (constantly input)
                    db-async/<get-block-sibling
                    (fn [& _]
                      (throw (js/Error. "Top-level Enter does not need a sibling lookup")))
                    editor/insert-new-block! (fn [& _]
                                               (swap! inserts inc)
                                               (p/resolved nil))]
      (p/let [_ (editor/keydown-new-block-handler nil)]
        (is (= 1 @inserts))))))

(deftest-async api-insert-at-page-end-loads-only-the-page-and-last-child
  (let [page-id (random-uuid)
        page {:db/id 10
              :block/uuid page-id
              :block/title "page"}
        last-child {:db/id 20
                    :block/uuid (random-uuid)
                    :block/title "last"
                    :block/parent {:db/id 10}
                    :block/page {:db/id 10}}
        block-reads (atom [])
        sibling-reads (atom [])
        insertion (atom nil)]
    (-> (p/with-redefs [state/get-current-repo (constantly "repo")
                        db-async/<get-block-with-children
                        (fn [& _]
                          (throw (js/Error. "Page insertion must not load the complete tree")))
                        db-async/<get-block
                        (fn [_repo block-id opts]
                          (swap! block-reads conj [block-id opts])
                          (p/resolved (if (= page-id block-id)
                                        page
                                        {:block/uuid block-id})))
                        db-async/<get-block-sibling
                        (fn [_repo block-id direction]
                          (swap! sibling-reads conj [block-id direction])
                          (p/resolved last-child))
                        editor/outliner-insert-block!
                        (fn [config target new-block opts]
                          (reset! insertion {:config config
                                             :target target
                                             :new-block new-block
                                             :opts opts})
                          (p/resolved nil))]
          (editor/api-insert-new-block! "new" {:page page-id
                                                 :edit-block? false
                                                 :end? true}))
        (p/then
         (fn [_]
           (is (= [[page-id {:children? false}]]
                  (take 1 @block-reads)))
           (is (= [[10 :last-child]] @sibling-reads))
           (is (= last-child (:target @insertion)))
           (is (true? (get-in @insertion [:opts :sibling?]))))))))

(deftest-async insert-above-awaits-async-insert
  (let [current-block {:db/id 1
                       :block/uuid (random-uuid)
                       :block/title "first"}
        next-block {:db/id 2
                    :block/uuid (random-uuid)
                    :block/title ""}
        input #js {:value "first"}
        cleared? (atom false)
        previous-document (.-document js/globalThis)]
    (state/set-editing-block-id! [:unknown-container (:block/uuid current-block)])
    (set! (.-document js/globalThis)
          #js {:activeElement input
               :getElementById (fn [_id] input)})
    (-> (p/with-redefs [state/get-edit-input-id (constantly "edit-block-current")
                        gdom/getElement (constantly input)
                        util/get-selection-start (constantly 0)
                        util/get-selection-end (constantly 0)
                        editor/get-state (constantly {:block current-block
                                                      :value "first"
                                                      :config {}
                                                      :node input
                                                      :block-container #js {}})
                        editor/insert-new-block-before-block-aux!
                        (fn [_config _block _value]
                          (p/resolved [true true next-block]))
                        editor/clear-when-saved! #(reset! cleared? true)]
          (p/let [_ (editor/insert-new-block! (editor/get-state) nil)]
            (is @cleared? "Insert above should await the async insert helper")))
        (p/finally (fn []
                     (set! (.-document js/globalThis) previous-document))))))

(deftest-async indent-block-does-not-move-into-comments-area
  (load-test-files
   [{:page {:block/title "Comments indent"}
     :blocks [{:block/title "ordinary"}
              {:block/title "Comments"}
              {:block/title "target"}]}])
  (let [comments-area (test-helper/find-block-by-content "Comments")
        target (test-helper/find-block-by-content "target")
        original-parent-id (:db/id (:block/parent target))]
    (d/transact! (conn/get-db test-helper/test-db false)
                 [[:db/add (:db/id comments-area) :block/tags comments-model/comments-tag-ident]])
    (p/with-redefs [state/<invoke-db-worker <test-db-worker
                    db-transact/apply-outliner-ops apply-test-outliner-ops!]
      (p/let [_ (block-handler/indent-outdent-blocks! [target] true nil)
              test-db (conn/get-db test-helper/test-db)
              target' (db-utils/entity test-db [:block/uuid (:block/uuid target)])
              comments-area' (db-utils/entity test-db [:block/uuid (:block/uuid comments-area)])]
        (is (= original-parent-id (:db/id (:block/parent target')))
            "Indenting a block after #Comments should leave it at the same parent")
        (is (not= (:db/id comments-area') (:db/id (:block/parent target')))
            "The target block must not become a child of the comments area")))))

(deftest-async db-based-save-assets-appends-to-asset-page-without-editor
  (let [asset-page {:block/uuid (random-uuid)
                    :block/title "Asset"}
        inserted (atom nil)]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_repo]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        state/<invoke-db-worker (fn [api & _]
                                                  (p/resolved
                                                   (when (= api :thread-api/pull)
                                                     asset-page)))
                        state/get-edit-block (constantly nil)
                        state/get-edit-content (constantly "")
                        frontend-outliner-op/insert-blocks! (fn [blocks target opts]
                                                     (reset! inserted {:blocks blocks
                                                                       :target target
                                                                       :opts opts})
                                                     [:insert-blocks [blocks target opts]])
                        db-async/<get-blocks (fn [_repo block-ids _opts]
                                               (p/resolved
                                                (mapv (fn [block-id]
                                                        {:block/uuid block-id})
                                                      block-ids)))]
          (editor-assets/db-based-save-assets! "repo" [{:src "image.png"
                                                 :title "image"}]))
        (p/then
         (fn [_]
           (is (= asset-page (:target @inserted)))
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
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _] (p/resolved {:block/uuid (random-uuid)
                                                                                 :block/title "today"}))
                        state/<invoke-db-worker (fn [& _] (p/resolved nil))
                        state/get-edit-block (constantly nil)
                        state/get-edit-content (constantly "")
                        frontend-outliner-op/insert-blocks! (fn [blocks target opts]
                                                     (reset! inserted {:blocks blocks
                                                                       :target target
                                                                       :opts opts})
                                                     [:insert-blocks [blocks target opts]])
                        db-async/<get-blocks (fn [_repo block-ids _opts]
                                               (p/resolved
                                                (mapv (fn [block-id]
                                                        {:block/uuid block-id})
                                                      block-ids)))]
          (editor-assets/db-based-save-assets! "repo"
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
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _] (p/resolved {:block/uuid (random-uuid)
                                                                                 :block/title "today"}))
                        state/<invoke-db-worker (fn [& _] (p/resolved nil))
                        state/get-edit-block (constantly temp-edit-block)
                        state/get-edit-content (constantly "")
                        frontend-outliner-op/insert-blocks! (fn [blocks target opts]
                                                     (reset! inserted {:blocks blocks
                                                                       :target target
                                                                       :opts opts})
                                                     [:insert-blocks [blocks target opts]])
                        db-async/<get-blocks (fn [_repo block-ids _opts]
                                               (p/resolved
                                                (mapv (fn [block-id]
                                                        {:block/uuid block-id})
                                                      block-ids)))]
          (editor-assets/db-based-save-assets! "repo"
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

(deftest-async db-based-save-assets-reuses-and-repairs-an-existing-literature-pdf
  (let [target {:block/uuid (random-uuid) :block/title "target"}
        existing-uuid #uuid "32d917fc-4599-476f-ae84-c0d3fbf9439c"
        inserted (atom nil)
        writes (atom [])
        filenames (atom nil)
        file #js {:name "paper.pdf" :size 100}]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_repo]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _]
                                                            (p/resolved {:block/uuid existing-uuid}))
                        db-async/<find-reusable-pdf-by-checksum
                        (fn [_repo checksum]
                          (is (= "checksum" checksum))
                          (p/resolved {:status :found
                                       :assets [{:block/uuid existing-uuid}]}))
                        db-async/<add-literature-pdf-filenames
                        (fn [_repo names]
                          (reset! filenames names)
                          (p/resolved nil))
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _]
                                                            (p/resolved {:block/uuid (random-uuid)}))
                        fs/file-exists? (fn [repo-dir path]
                                          (is (= "/repo" repo-dir))
                                          (is (= (str "assets/" existing-uuid ".pdf") path))
                                          (p/resolved false))
                        assets-handler/db-based-write-asset! (fn [_repo file-path file']
                                                       (swap! writes conj [file-path file'])
                                                       (p/resolved nil))
                        state/get-edit-block (constantly nil)
                        frontend-outliner-op/insert-blocks! (fn [blocks target' _opts]
                                                             (reset! inserted {:blocks blocks
                                                                               :target target'})
                                                             [:insert-blocks])
                        db-async/<get-blocks (fn [_repo block-ids _opts]
                                               (p/resolved
                                                (mapv (fn [block-id] {:block/uuid block-id})
                                                      block-ids)))]
          (editor-assets/db-based-save-assets! "repo" [file]
                                        :reuse-existing-pdf? true
                                        :target-block target))
        (p/then
         (fn [_]
           (is (= target (:target @inserted)))
           (is (= {existing-uuid #{"paper.pdf"}} @filenames))
           (is (= [:block/uuid existing-uuid]
                  (get-in @inserted [:blocks 0 :block/link])))
           (is (= [[(str existing-uuid ".pdf") file]] @writes)
               "A missing local file is restored at the existing Asset UUID"))))))

(deftest-async literature-pdf-reuse-restores-only-missing-or-damaged-files
  (let [writes (atom [])
        matches? (atom true)
        checks (atom [])
        file #js {:name "paper.pdf" :size 3}
        content (.-buffer (js/Uint8Array. #js [1 2 3]))
        write {:file file :file-path "existing.pdf" :checksum "expected-checksum"}]
    (-> (p/with-redefs [util/electron? (constantly true)
                        ipc/ipc (fn [& args]
                                  (swap! checks conj args)
                                  (p/resolved @matches?))
                        fs/read-file-raw (fn [& _]
                                           (throw (js/Error. "Native verification does not send local PDF bytes")))
                        assets-handler/db-based-write-asset! (fn [repo file-path file' content']
                                                               (swap! writes conj [repo file-path file' content'])
                                                               (p/resolved nil))]
          (p/let [_ (assets-handler/<restore-reused-asset-file! "repo" "/repo" "assets" write content)
                  _ (is (empty? @writes) "Healthy duplicate files are never rewritten")
                  _ (reset! matches? false)
                  _ (assets-handler/<restore-reused-asset-file! "repo" "/repo" "assets" write content)
                  _ (assets-handler/<restore-reused-asset-file! "repo" "/repo" "assets" write content)]
            nil))
        (p/then (fn [_]
                  (is (= (repeat 2 ["repo" "existing.pdf" file content]) @writes)
                      "Missing or damaged files use the supplied correct bytes at the original path")
                  (is (= (repeat 3 ["fileContentMatches" "/repo/assets/existing.pdf" 3 "expected-checksum"])
                         @checks)))))))

(deftest-async literature-pdf-reuse-read-failure-does-not-overwrite
  (let [writes (atom [])]
    (-> (p/with-redefs [util/electron? (constantly true)
                        ipc/ipc (fn [& _] (p/rejected (js/Error. "File cannot be read")))
                        assets-handler/db-based-write-asset! (fn [& args]
                                                               (swap! writes conj args)
                                                               (p/resolved nil))]
          (-> (assets-handler/<restore-reused-asset-file!
               "repo" "/repo" "assets"
               {:file #js {:name "paper.pdf" :size 3}
                :file-path "existing.pdf" :checksum "expected-checksum"}
               (.-buffer (js/Uint8Array. #js [1 2 3])))
              (p/then (fn [_] (is false "An unreadable file cannot be reported restored")))
              (p/catch (fn [error] (is (= "File cannot be read" (.-message error)))))))
        (p/then (fn [_] (is (empty? @writes)))))))

(deftest-async literature-pdf-reuse-checks-browser-bytes
  (let [stored-bytes (js/Uint8Array. #js [0 1 2 3 0])
        expected (.subarray stored-bytes 1 4)]
    (p/with-redefs [util/electron? (constantly false)
                    fs/file-exists? (fn [& _] (p/resolved true))
                    fs/read-file-raw (fn [& _] (p/resolved expected))]
      (p/let [checksum (db-asset/<get-file-array-buffer-checksum expected)
              matches? (assets-handler/<asset-file-content-matches? "/repo" "assets/existing.pdf" 3 checksum)
              _ (is matches? "The browser checks only the returned byte view")
              _ (aset stored-bytes 2 4)
              matches? (assets-handler/<asset-file-content-matches? "/repo" "assets/existing.pdf" 3 checksum)]
        (is (not matches?) "Same-size damage is detected in the browser path")))))

(deftest-async db-based-save-assets-collapses-same-batch-identical-pdfs
  (let [target {:block/uuid (random-uuid) :block/title "target"}
        inserted (atom nil)
        writes (atom [])
        filenames (atom nil)
        lookups (atom 0)
        files [#js {:name "first.pdf" :size 100}
               #js {:name "second.pdf" :size 100}]]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_repo]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-async/<find-reusable-pdf-by-checksum
                        (fn [_repo checksum]
                          (swap! lookups inc)
                          (is (= "checksum" checksum))
                          (p/resolved {:status :missing :assets []}))
                        db-async/<add-literature-pdf-filenames
                        (fn [_repo names]
                          (reset! filenames names)
                          (p/resolved nil))
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _]
                                                            (p/resolved {:block/uuid (random-uuid)}))
                        assets-handler/db-based-write-asset! (fn [_repo file-path file]
                                                       (swap! writes conj [file-path file])
                                                       (p/resolved nil))
                        state/get-edit-block (constantly nil)
                        frontend-outliner-op/insert-blocks! (fn [blocks target' _opts]
                                                             (reset! inserted {:blocks blocks
                                                                               :target target'})
                                                             [:insert-blocks])
                        db-async/<get-blocks (fn [_repo block-ids _opts]
                                               (p/resolved
                                                (mapv (fn [block-id] {:block/uuid block-id})
                                                      block-ids)))]
          (editor-assets/db-based-save-assets! "repo" files
                                        :reuse-existing-pdf? true
                                        :target-block target))
        (p/then
         (fn [_]
           (let [[asset link] (:blocks @inserted)]
             (is (= 1 @lookups))
             (is (= 1 (count @writes)))
             (is (= {(:block/uuid asset) #{"first.pdf" "second.pdf"}} @filenames))
             (is (= (str (:block/uuid asset) ".pdf")
                    (ffirst @writes)))
             (is (= [:block/uuid (:block/uuid asset)] (:block/link link)))))))))

(defn- check-overlapping-pdf-saves
  [empty-edit?]
  (let [reads (atom {})
        hashes (atom [])
        writes (atom [])
        saved (atom nil)
        inserted (atom [])
        first-write (p/deferred)
        finish-write (p/deferred)
        make-file (fn [filename]
                    #js {:name filename :size 1 :type "application/pdf"
                         :arrayBuffer
                         (fn []
                           (let [n (get (swap! reads update filename (fnil inc 0)) filename)]
                             (p/resolved (.-buffer (js/Uint8Array. #js [n])))))})
        files [(make-file "paper.pdf") (make-file "renamed-paper.pdf")]
        blank {:block/uuid (random-uuid) :block/title ""}
        targets (if empty-edit? [blank blank]
                    [{:block/uuid (random-uuid)} {:block/uuid (random-uuid)}])]
    (-> (p/with-redefs [literature-handler/load-settings (constantly {:enabled? true})
                        state/get-current-repo (constantly "repo")
                        db-subs/projection-context (constantly {:repo "repo" :epoch 1})
                        state/get-edit-block (constantly (when empty-edit? blank))
                        state/get-edit-content (constantly "")
                        editor/edit-block! (fn [& _] nil)
                        db-async/<get-block (fn [& _] (p/resolved (or @saved blank)))
                        assets-handler/ensure-assets-dir! (fn [_] (p/resolved ["/repo" "assets"]))
                        db-asset/<get-file-array-buffer-checksum
                        (fn [content]
                          (let [value (aget (js/Uint8Array. content) 0)]
                            (swap! hashes conj value)
                            (p/resolved (str "checksum-" value))))
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved @saved))
                        db-async/<find-reusable-pdf-by-checksum
                        (fn [& _]
                          (p/resolved (if @saved
                                        {:status :found :assets [@saved]}
                                        {:status :missing :assets []})))
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _] (p/resolved (first targets)))
                        fs/file-exists? (fn [& _] (p/resolved true))
                        assets-handler/<asset-file-content-matches? (fn [& _] (p/resolved true))
                        fs/write-asset-file!
                        (fn [_repo path content]
                          (swap! writes conj [path (aget (js/Uint8Array. content) 0)])
                          (p/resolve! first-write nil)
                          finish-write)
                        db-async/<add-literature-pdf-filenames (fn [& _] (p/resolved nil))
                        frontend-outliner-op/insert-blocks!
                        (fn [blocks target _opts]
                          (swap! inserted conj {:blocks blocks :target target})
                          (when-let [asset (first (filter :logseq.property.asset/checksum blocks))]
                            (reset! saved asset))
                          [:insert-blocks])
                        db-async/<get-blocks
                        (fn [_ ids _] (p/resolved (mapv #(hash-map :block/uuid %) ids)))]
          (let [saves (mapv (fn [file target]
                              (if empty-edit?
                                (editor-assets/db-based-save-assets!
                                 "repo" [file] :reuse-existing-pdf? true)
                                (editor-assets/db-based-save-assets!
                                 "repo" [file] :reuse-existing-pdf? true :target-block target)))
                            files targets)]
            (p/let [_ first-write
                    _ (p/delay 0)]
              (p/resolve! finish-write nil)
              (p/all saves))))
        (p/then
         (fn [_]
           (is (= {"paper.pdf" 1 "renamed-paper.pdf" 1} @reads))
           (is (= [1 1] @hashes))
           (is (= 1 (count @writes))
               "A healthy Asset is written only on the first paste")
           (is (= 1 (second (first @writes))) "Saved bytes must be the bytes that were hashed")
           (is (= targets (mapv :target @inserted)))
           (let [assets (filter :logseq.property.asset/checksum (mapcat :blocks @inserted))]
             (is (= 1 (count assets)))
             (when empty-edit?
               (is (= (:block/uuid blank) (:block/uuid (first assets))))))
           (let [link (first (filter :block/link (mapcat :blocks @inserted)))]
             (is (= [:block/uuid (:block/uuid @saved)] (:block/link link)))
             (is (not= (:block/uuid link) (second (:block/link link)))
                 "A second paste into the former empty node must not self-link")))))))

(deftest-async overlapping-pdf-saves-share-one-asset-and-read-each-file-once
  (p/do! (check-overlapping-pdf-saves false)
         (check-overlapping-pdf-saves true)))

(deftest-async reused-unregistered-pdf-is-identified-once-after-paste
  (let [asset-uuid #uuid "72000000-0000-4000-8000-000000000010"
        checksum "reused-checksum"
        files [#js {:name "paper.pdf" :type "application/pdf" :size 100}
               #js {:name "renamed-paper.pdf" :type "application/pdf" :size 100}]
        candidates (mapv (fn [file]
                           {:file file
                            :file-name (.-name file)
                            :ext "pdf"
                            :checksum checksum
                            :asset-block {:block/uuid (random-uuid)}})
                         files)
        reuse {:status :found
               :literature-status :missing
               :assets [{:block/uuid asset-uuid}]}
        plan (#'editor-assets/build-asset-insert-plan candidates {checksum reuse})]
    (is (= {asset-uuid #{"paper.pdf" "renamed-paper.pdf"}} (:pdf-filenames plan)))
    (is (= [{:asset-uuid asset-uuid
             :file-path (str asset-uuid ".pdf")
             :checksum checksum
             :byte-size 100}]
           (:new-pdf-identifications plan))
        "Same-batch aliases of an unregistered PDF identify its original Asset once")
    (is (= [[:block/uuid asset-uuid] [:block/uuid asset-uuid]]
           (mapv :block/link (:blocks plan))))
    (is (empty? (:new-writes plan)))
    (is (= [(str asset-uuid ".pdf")]
           (mapv :file-path (:repair-writes plan))))
    (doseq [status [:found :conflict]]
      (is (empty? (:new-pdf-identifications
                   (#'editor-assets/build-asset-insert-plan
                    candidates {checksum (assoc reuse :literature-status status)})))
          "Registered or ambiguously associated PDFs are not parsed again"))
    (is (empty? (:new-pdf-identifications
                 (#'editor-assets/build-asset-insert-plan
                  [(assoc (first candidates) :file nil)] {checksum reuse}))))
    (is (empty? (:new-pdf-identifications
                 (#'editor-assets/build-asset-insert-plan
                  [(assoc (first candidates)
                          :file #js {:name "image.pdf" :type "image/png" :size 100})]
                  {checksum reuse})))
        "An explicit non-PDF MIME type never starts PDF identification")
    (p/resolved nil)))

(deftest-async literature-identification-entry-is-default-off
  (let [target {:block/uuid (random-uuid) :block/title "target"}
        lookups (atom 0)
        schedules (atom 0)
        file #js {:name "paper.pdf" :size 100}]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-async/<find-reusable-pdf-by-checksum (fn [& _]
                                                                  (swap! lookups inc)
                                                                  (p/resolved {:status :missing}))
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _]
                                                            (p/resolved {:block/uuid (random-uuid)}))
                        assets-handler/db-based-write-asset! (fn [& _] (p/resolved nil))
                        state/get-edit-block (constantly nil)
                        frontend-outliner-op/insert-blocks! (fn [& _] [:insert-blocks])
                        db-async/<get-blocks (fn [_ block-ids _]
                                               (p/resolved (mapv #(hash-map :block/uuid %) block-ids)))
                        literature-handler/schedule-new-pdf-identification!
                        (fn [& _]
                          (swap! schedules inc)
                          (p/resolved {:status :complete}))]
          (editor-assets/db-based-save-assets! "repo" [file]
                                        :reuse-existing-pdf? true
                                        :target-block target))
        (p/then
         (fn [_]
           (is (zero? @lookups)
               "A single new PDF keeps the existing save path when identification is off")
           (is (zero? @schedules)))))))

(deftest-async literature-identification-starts-after-a-new-pdf-is-saved-and-inserted
  (let [target {:block/uuid (random-uuid) :block/title "target"}
        events (atom [])
        scheduled (atom nil)
        callback (fn [_])
        file #js {:name "paper.pdf" :size 100}]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-async/<find-reusable-pdf-by-checksum
                        (fn [_ checksum]
                          (is (= "checksum" checksum))
                          (p/resolved {:status :missing :assets []}))
                        db-async/<add-literature-pdf-filenames
                        (fn [_ names]
                          (is (= #{"paper.pdf"} (first (vals names))))
                          (swap! events conj [:filenames])
                          (p/resolved nil))
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _]
                                                            (p/resolved {:block/uuid (random-uuid)}))
                        assets-handler/db-based-write-asset!
                        (fn [_ file-path file']
                          (swap! events conj [:write file-path file'])
                          (p/resolved nil))
                        state/get-edit-block (constantly nil)
                        frontend-outliner-op/insert-blocks!
                        (fn [blocks _target _opts]
                          (swap! events conj [:insert (mapv :block/uuid blocks)])
                          [:insert-blocks])
                        db-async/<get-blocks
                        (fn [_ block-ids _]
                          (swap! events conj [:read-back (vec block-ids)])
                          (p/resolved (mapv #(hash-map :block/uuid %) block-ids)))
                        literature-handler/schedule-new-pdf-identification!
                        (fn [repo repo-dir asset-dir entries & {:keys [on-result]}]
                          (swap! events conj [:schedule])
                          (reset! scheduled {:repo repo
                                             :repo-dir repo-dir
                                             :asset-dir asset-dir
                                             :entries entries
                                             :on-result on-result})
                          (p/resolved {:status :complete}))]
          (editor-assets/db-based-save-assets! "repo" [file]
                                        :reuse-existing-pdf? true
                                        :auto-identify-literature? true
                                        :on-literature-identification callback
                                        :target-block target))
        (p/then
         (fn [_]
           (let [entry (first (:entries @scheduled))]
             (is (= [:write :insert :filenames :read-back :schedule]
                    (mapv first @events)))
             (is (= "repo" (:repo @scheduled)))
             (is (= "/repo" (:repo-dir @scheduled)))
             (is (= "assets" (:asset-dir @scheduled)))
             (is (= "checksum" (:checksum entry)))
             (is (= 100 (:byte-size entry)))
             (is (= (str (:asset-uuid entry) ".pdf") (:file-path entry)))
             (is (identical? callback (:on-result @scheduled)))))))))

(deftest-async literature-identification-keeps-a-lightweight-manual-review-entry
  (let [target {:block/uuid (random-uuid) :block/title "target"}
        stored (atom [])
        scheduled (atom nil)
        file #js {:name "paper.pdf" :size 100}]
    (-> (p/with-redefs [assets-handler/ensure-assets-dir! (fn [_]
                                                            (p/resolved ["/repo" "assets"]))
                        assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                        db-async/<find-reusable-pdf-by-checksum
                        (fn [& _] (p/resolved {:status :missing :assets []}))
                        db-async/<add-literature-pdf-filenames
                        (fn [& _] (p/resolved nil))
                        db-async/<get-today-journal-title (fn [& _] (p/resolved "today"))
                        db-async/<get-journal-page-by-day (fn [& _]
                                                            (p/resolved {:block/uuid (random-uuid)}))
                        assets-handler/db-based-write-asset! (fn [& _] (p/resolved nil))
                        state/get-edit-block (constantly nil)
                        frontend-outliner-op/insert-blocks! (fn [& _] [:insert-blocks])
                        db-async/<get-blocks
                        (fn [_ block-ids _]
                          (p/resolved (mapv #(hash-map :block/uuid %) block-ids)))
                        literature-handler/store-review-result!
                        (fn [repo repo-dir asset-dir-rpath result]
                          (swap! stored conj [repo repo-dir asset-dir-rpath result]))
                        literature-handler/schedule-new-pdf-identification!
                        (fn [_repo _repo-dir _asset-dir-rpath entries
                             & {:keys [on-result]}]
                          (reset! scheduled {:entries entries :on-result on-result})
                          (p/resolved {:status :busy}))]
          (editor-assets/db-based-save-assets! "repo" [file]
                                        :reuse-existing-pdf? true
                                        :auto-identify-literature? true
                                        :target-block target))
        (p/then
         (fn [_]
           (let [[repo repo-dir asset-dir-rpath pending] (first @stored)
                 entry (first (:entries @scheduled))]
             (is (= ["repo" "/repo" "assets"]
                    [repo repo-dir asset-dir-rpath]))
             (is (= :pending (get-in pending [:extraction :status])))
             (is (= (:asset-uuid entry) (:asset-uuid pending)))
             (is (= "checksum" (:checksum pending)))
             (is (= 100 (:byte-size pending)))
             (is (= 1 (count @stored)))
             (is (fn? (:on-result @scheduled)))))))))

(deftest-async db-upload-assets-enables-literature-identification-only-for-local-graph-settings
  (let [calls (atom [])
        uploading? (atom true)]
    (-> (p/with-redefs [editor/insert-command! (fn [& _])
                        literature-handler/auto-identification-enabled?
                        #(= % "enabled-repo")
                        editor-assets/db-based-save-assets!
                        (fn [repo _files & opts]
                          (swap! calls conj [repo (apply hash-map opts)])
                          (p/resolved []))]
          (p/let [_ (editor-assets/db-upload-assets! "default-repo" "id" #js [] :markdown uploading? true)
                  _ (editor-assets/db-upload-assets! "enabled-repo" "id" #js [] :markdown uploading? true)]
            (is (= [["default-repo"
                     {:reuse-existing-pdf? true
                      :auto-identify-literature? false}]
                    ["enabled-repo"
                     {:reuse-existing-pdf? true
                      :auto-identify-literature? true}]]
                   @calls))))
        (p/then (fn [_]
                  (is (false? @uploading?)))))))

(deftest-async db-based-save-assets-stops-checksum-conflicts-before-writes
  (let [side-effects (atom [])
        dialog (atom nil)
        first-uuid #uuid "21271957-3e09-4ca7-9ca9-2425af7a5702"
        second-uuid #uuid "c1e8dd1b-acbb-4dc4-8b77-09d597406ee6"
        file #js {:name "paper.pdf" :size 100}]
    (-> (p/with-redefs [assets-handler/get-file-checksum (constantly "checksum")
                        db-async/<get-asset-with-checksum (fn [& _]
                                                            (p/resolved {:block/uuid first-uuid}))
                        db-async/<find-reusable-pdf-by-checksum
                        (fn [& _]
                          (p/resolved {:status :conflict
                                       :assets [{:block/uuid first-uuid}
                                                {:block/uuid second-uuid}]}))
                        assets-handler/ensure-assets-dir! (fn [& _]
                                                            (swap! side-effects conj :ensure-assets-dir)
                                                            (p/resolved ["/repo" "assets"]))
                        db-async/<get-today-journal-title (fn [& _]
                                                           (swap! side-effects conj :journal-title)
                                                           (p/resolved "today"))
                        assets-handler/db-based-write-asset! (fn [& _]
                                                       (swap! side-effects conj :file-write)
                                                       (p/resolved nil))
                        frontend-outliner-op/insert-blocks! (fn [& _]
                                                             (swap! side-effects conj :outliner-write)
                                                             [:insert-blocks])
                        shui/dialog-open! (fn [content opts]
                                            (reset! dialog {:content content
                                                            :opts opts}))
                        state/get-edit-block (constantly nil)]
          (editor-assets/db-based-save-assets! "repo" [file]
                                        :reuse-existing-pdf? true
                                        :target-block {:block/uuid (random-uuid)}))
        (p/then
         (fn [_]
           (is (fn? (:content @dialog)))
           (is (= :literature-duplicate-pdf
                  (get-in @dialog [:opts :id])))
           (is (empty? @side-effects)
               "Conflict must open the choice UI before directory, page, file, or outliner writes"))))))

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
        command-targets (atom [])
        expanded (atom [])]
    (-> (p/with-redefs [state/get-current-repo (constantly "test-repo")
                        block-handler/get-top-level-blocks identity
                        state/<invoke-db-worker
                        (fn [api _repo block-refs]
                          (is (= :thread-api/ensure-comments-area-for-blocks api))
                          (reset! command-targets (vec block-refs))
                          (is (= [first-uuid second-uuid] (vec block-refs)))
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
            (is (= [first-uuid second-uuid] @command-targets))
            (is (= [created-comments-area-uuid] @expanded)
                "The range comments area should be expanded inline"))))))

(deftest-async quick-add-creates-block-for-current-user
  (let [user-id (random-uuid)
        quick-add-page-id (random-uuid)
        inserts (atom [])]
    (p/with-redefs [state/get-current-repo (constantly "test-repo")
                    user-handler/user-uuid (constantly (str user-id))
                    db-async/<get-block
                    (fn [_repo block-id & _opts]
                      (p/resolved (when (= user-id block-id)
                                    {:db/id 42
                                     :block/uuid user-id})))
                    db-async/<get-block-with-children
                    (fn [& _]
                      (p/resolved {:block {:block/uuid quick-add-page-id}
                                   :children [{:block/uuid (random-uuid)
                                               :logseq.property/created-by-ref {:db/id 99}}]}))
                    editor/api-insert-new-block!
                    (fn [content opts]
                      (swap! inserts conj [content opts])
                      (p/resolved nil))]
      (p/let [_ (editor-quick-add/quick-add-ensure-new-block-exists!)]
        (is (= [["" {:page quick-add-page-id
                      :container-id :unknown-container
                      :replace-empty-target? false}]]
               @inserts)
            "Another user's Quick Add block must not suppress the current user's block.")))))

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
        command-targets (atom [])
        expanded (atom [])]
    (-> (p/with-redefs [state/get-current-repo (constantly "test-repo")
                        block-handler/get-top-level-blocks identity
                        state/<invoke-db-worker
                        (fn [api _repo block-refs]
                          (is (= :thread-api/ensure-comments-area-for-blocks api))
                          (reset! command-targets (vec block-refs))
                          (is (= [block-uuid] (vec block-refs)))
                          (p/resolved created-comments-area))
                        editor/expand-block! (fn [block-uuid]
                                               (swap! expanded conj block-uuid)
                                               (p/resolved nil))]
          (p/let [area (comments-handler/ensure-comments-area-for-selected-blocks! [block])]
            (is (= created-comments-area area)
                "A single selected block should use a child comments area")
            (is (= [block-uuid] @command-targets))
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
                        db-async/<get-block (fn [_repo block-id _opts]
                                              (p/resolved
                                               (cond
                                                 (= block-uuid block-id) block
                                                 (= selected-uuid block-id) selected-block
                                                 :else nil)))
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

(deftest-async comment-delete-uses-atomic-worker-command
  (let [comment-block {:block/uuid (random-uuid)
                       :block/title "first"}
        resolved-block-ref (atom nil)]
    (-> (p/with-redefs [state/get-current-repo (constantly "test-repo")
                        state/<invoke-db-worker
                        (fn [api _repo block-ref]
                          (is (= :thread-api/delete-comment api))
                          (reset! resolved-block-ref block-ref)
                          (p/resolved nil))]
          (comments-handler/delete-comment! comment-block))
        (p/then
         (fn []
           (is (= (:block/uuid comment-block) @resolved-block-ref)))))))

;; Collapsed copy/cut/paste coverage for https://github.com/logseq/db-test/issues/1216
(defn- <complete-tree-worker
  [_api repo' requests]
  (let [db' (conn/get-db repo')]
    (p/resolved
     (mapv (fn [{:keys [id opts]}]
             (let [id' (if (string? id) (uuid id) id)]
               (assoc (worker-block-handler/get-block-and-children db' id' opts)
                      :id id)))
           requests))))

(defn- load-collapsed-clipboard-fixture!
  [& {:keys [parent-collapsed? child-collapsed?]
      :or {parent-collapsed? true
           child-collapsed? false}}]
  (load-test-files
   [{:page {:block/title "Source"}
     :blocks [{:block/title "Parent"
               :block/collapsed? parent-collapsed?
               :build/children
               [{:block/title "Child"
                 :block/collapsed? child-collapsed?
                 :build/children [{:block/title "Grandchild"}]}
                {:block/title "Sibling child"}]}]}
    {:page {:block/title "Dest"}
     :blocks [{:block/title "Target"}]}])
  {:parent (test-helper/find-block-by-content "Parent")
   :target (test-helper/find-block-by-content "Target")
   :expected-titles ["Parent" "Child" "Grandchild" "Sibling child"]})

(defn- page-block-by-title
  [page-name title]
  (d/q '[:find (pull ?b [:block/uuid :block/title]) .
         :in $ ?page-name ?title
         :where
         [?p :block/name ?page-name]
         [?b :block/page ?p]
         [?b :block/title ?title]
         [(missing? $ ?b :logseq.property/deleted-at)]]
       (conn/get-db test-helper/test-db)
       page-name
       title))

(defn- subtree-titles
  [block-uuid]
  (mapv :block/title
        (ldb/get-block-and-children (conn/get-db test-helper/test-db) block-uuid)))

(defn- <structured-clipboard-blocks
  [block-uuid]
  (p/let [blocks (#'editor/<get-all-blocks-by-ids test-helper/test-db [block-uuid])]
    (#'editor/blocks-for-clipboard blocks)))

(defn- <cut-collapsed-parent!
  [parent]
  (let [clipboard (atom nil)]
    (p/with-redefs [state/<invoke-db-worker <complete-tree-worker
                    editor/compose-copied-blocks-contents
                    (fn [_repo ids & _]
                      (p/resolved [ids (:block/title parent) [parent]]))
                    export-html/export-blocks-as-html (fn [& _] (p/resolved ""))
                    common-handler/copy-to-clipboard-without-id-property!
                    (fn [_repo _text _html blocks & _opts]
                      (reset! clipboard blocks))
                    db-transact/apply-outliner-ops apply-test-outliner-ops!]
      (p/let [_ (editor/cut-block! (:block/uuid parent))]
        @clipboard))))

(deftest-async ^:large-vars/cleanup-todo copied-selection-loads-the-complete-structured-tree
  (let [repo test-helper/test-db
        page-uuid (random-uuid)
        property-uuid (random-uuid)
        root-uuid (random-uuid)
        collapsed-uuid (random-uuid)
        collapsed-child-uuid (random-uuid)
        query-uuid (random-uuid)
        property-value-uuid (random-uuid)
        independent-uuid (random-uuid)
        chain-uuids (vec (repeatedly 101 random-uuid))
        chain-blocks
        (mapv (fn [index block-uuid]
                {:db/id (- (+ 100 index))
                 :block/uuid block-uuid
                 :block/tx-id 1
                 :block/title (str "Chain " index)
                 :block/page -1
                 :block/parent (if (zero? index) -3 (- (+ 99 index)))
                 :block/order "a0"})
              (range (count chain-uuids))
              chain-uuids)]
    (d/transact!
     (conn/get-db repo false)
     (concat
      [{:db/id -1
        :block/uuid page-uuid
        :block/tx-id 1
        :block/title "Page"
        :block/name "page"
        :block/tags :logseq.class/Page}
       {:db/id -2
        :db/ident :user.property/Text
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/one
        :block/uuid property-uuid
        :block/tx-id 1
        :block/title "Text"
        :block/tags :logseq.class/Property}
       {:db/id -3
        :block/uuid root-uuid
        :block/tx-id 1
        :block/title "Root"
        :block/page -1
        :block/parent -1
        :block/order "a0"}
       {:db/id -4
        :block/uuid collapsed-uuid
        :block/tx-id 1
        :block/title "Collapsed"
        :block/page -1
        :block/parent -3
        :block/order "a1"
        :block/collapsed? true}
       {:db/id -5
        :block/uuid collapsed-child-uuid
        :block/tx-id 1
        :block/title "Collapsed child"
        :block/page -1
        :block/parent -4
        :block/order "a0"}
       {:db/id -6
        :block/uuid query-uuid
        :block/tx-id 1
        :block/title "Query"
        :block/page -1
        :block/order "a3"}
       {:db/id -7
        :block/uuid independent-uuid
        :block/tx-id 1
        :block/title "Independent"
        :block/page -1
        :block/parent -1
        :block/order "a1"}
       {:db/id -8
        :block/uuid property-value-uuid
        :block/tx-id 1
        :block/title "Property value"
        :block/page -1
        :block/parent -3
        :block/order "a2"
        :logseq.property/created-from-property -2
        :logseq.property/query -6}]
      chain-blocks))
    (let [db (conn/get-db repo)
          expected-uuids
          (conj (mapv :block/uuid
                      (ldb/get-block-and-children
                       db root-uuid {:include-property-block? true}))
                independent-uuid)]
      (p/with-redefs [state/<invoke-db-worker <complete-tree-worker]
        (p/let [blocks (#'editor/<get-all-blocks-by-ids
                         repo [root-uuid (nth chain-uuids 50) independent-uuid])]
          (is (= expected-uuids (mapv :block/uuid blocks))))))))

(deftest-async copied-collapsed-parent-includes-nested-children
  (let [{:keys [parent expected-titles]} (load-collapsed-clipboard-fixture!)]
    (p/with-redefs [state/<invoke-db-worker <complete-tree-worker]
      (p/let [blocks (#'editor/<get-all-blocks-by-ids
                       test-helper/test-db [(:block/uuid parent)])]
        (is (= expected-titles (mapv :block/title blocks)))
        (is (= (:block/uuid parent) (:block/uuid (first blocks))))))))

(deftest-async copied-expanded-parent-includes-nested-children
  (testing "Non-collapsed control still copies the full subtree"
    (let [{:keys [parent expected-titles]} (load-collapsed-clipboard-fixture!
                                            :parent-collapsed? false)]
      (p/with-redefs [state/<invoke-db-worker <complete-tree-worker]
        (p/let [blocks (#'editor/<get-all-blocks-by-ids
                         test-helper/test-db [(:block/uuid parent)])]
          (is (= expected-titles (mapv :block/title blocks))))))))

(deftest-async copied-partially-collapsed-nested-levels-include-hidden-descendants
  (testing "A collapsed nested child still contributes hidden descendants to the copy"
    (let [{:keys [parent expected-titles]} (load-collapsed-clipboard-fixture!
                                            :parent-collapsed? false
                                            :child-collapsed? true)]
      (p/with-redefs [state/<invoke-db-worker <complete-tree-worker]
        (p/let [blocks (#'editor/<get-all-blocks-by-ids
                         test-helper/test-db [(:block/uuid parent)])]
          (is (= expected-titles (mapv :block/title blocks)))
          (is (some #(= "Grandchild" (:block/title %)) blocks)))))))

(deftest-async cut-collapsed-parent-includes-nested-children-and-removes-source
  (let [{:keys [parent expected-titles]} (load-collapsed-clipboard-fixture!)]
    (p/let [clipboard (<cut-collapsed-parent! parent)]
      (is (= expected-titles (mapv :block/title clipboard)))
      (is (nil? (page-block-by-title "source" "Parent")))
      (is (nil? (page-block-by-title "source" "Child")))
      (is (nil? (page-block-by-title "source" "Grandchild"))))))

(deftest-async paste-after-copy-of-collapsed-parent-restores-full-tree
  (let [{:keys [parent target expected-titles]} (load-collapsed-clipboard-fixture!)]
    (p/with-redefs [state/<invoke-db-worker <complete-tree-worker
                    db-transact/apply-outliner-ops apply-test-outliner-ops!]
      (p/let [clipboard (<structured-clipboard-blocks (:block/uuid parent))
              _ (editor/paste-blocks clipboard {:target-block target
                                                :sibling? true
                                                :keep-uuid? false
                                                :ops-only? true})
              pasted (page-block-by-title "dest" "Parent")
              source (page-block-by-title "source" "Parent")]
        (is (some? pasted) "Copy+paste must insert the collapsed parent on the destination page")
        (is (not= (:block/uuid parent) (:block/uuid pasted))
            "Copy must mint a new parent uuid")
        (is (= expected-titles (subtree-titles (:block/uuid pasted))))
        (is (= expected-titles (subtree-titles (:block/uuid source)))
            "The original collapsed tree must remain after copy")))))

(deftest-async paste-after-cut-of-collapsed-parent-restores-full-tree
  (let [{:keys [parent target expected-titles]} (load-collapsed-clipboard-fixture!)
        parent-uuid (:block/uuid parent)]
    (p/let [clipboard (<cut-collapsed-parent! parent)
            _ (p/with-redefs [db-transact/apply-outliner-ops apply-test-outliner-ops!]
                (editor/paste-blocks clipboard {:target-block target
                                                :sibling? true
                                                :keep-uuid? true
                                                :ops-only? true}))
            pasted (page-block-by-title "dest" "Parent")]
      (is (= parent-uuid (:block/uuid pasted))
          "Cut+paste keeps the original parent uuid")
      (is (= expected-titles (subtree-titles parent-uuid)))
      (is (nil? (page-block-by-title "source" "Parent"))
          "The source page must stay empty after cut+paste"))))

(deftest-async expand-block-skip-db-keeps-parent-container-collapse
  (testing "Display-only expand must not mark the parent editor container as open"
    (let [block-id (random-uuid)
          parent-container 1
          zoom-container 2
          repo test-helper/test-db
          previous-repo (state/get-current-repo)
          previous-args (state/get-editor-args)
          previous-editor-container (state/get-current-editor-container-id)
          previous-collapsed (state/get-state :ui/collapsed-blocks)]
      (state/set-current-repo! repo)
      (state/set-state! :editor/args [nil nil {:container-id parent-container}])
      (state/set-state! :editor/container-id parent-container)
      (state/set-state! :ui/collapsed-blocks {})
      (-> (p/with-redefs [db-async/<get-block
                          (fn [_repo id opts]
                            (is (= block-id id))
                            (is (true? (:include-collapsed-children? opts)))
                            (p/resolved {:block/uuid id}))]
            (p/do!
             ;; Zoom/root load path used to omit :container-id, which wrote false
             ;; onto the current editor/parent container via resolve-container-id.
             (editor/expand-block! block-id {:skip-db-collpsing? true})
             (is (nil? (state/get-block-collapsed block-id parent-container))
                 "skip-db expand without a container must not write the editor container")
             (editor/expand-block! block-id {:skip-db-collpsing? true
                                             :container-id zoom-container})
             (state/set-collapsed-block! block-id false zoom-container)
             (is (false? (state/get-block-collapsed block-id zoom-container)))
             (is (nil? (state/get-block-collapsed block-id parent-container))
                 "Zoom expand must stay on the zoom container")
             ;; Navigate-back cleanup only clears the zoom container.
             (state/set-collapsed-block! block-id nil zoom-container)))
          (p/then
           (fn [_]
             (is (nil? (state/get-block-collapsed block-id parent-container))
                 "Parent view can fall back to DB :block/collapsed? after back")
             (is (nil? (state/get-block-collapsed block-id zoom-container)))))
          (p/finally
           (fn []
             (state/set-state! :editor/args previous-args)
             (state/set-state! :editor/container-id previous-editor-container)
             (state/set-state! :ui/collapsed-blocks previous-collapsed)
             (state/set-current-repo! previous-repo)))))))
(deftest-async duplicate-pdf-resolution-stops-after-the-first-current-blocker
  (let [keep-uuid #uuid "72000000-0000-4000-8000-000000000021"
        first-duplicate #uuid "72000000-0000-4000-8000-000000000022"
        blocked-duplicate #uuid "72000000-0000-4000-8000-000000000023"
        untouched-duplicate #uuid "72000000-0000-4000-8000-000000000024"
        calls (atom [])]
    (-> (p/with-redefs
          [db-async/<resolve-duplicate-pdf
           (fn [repo kept-uuid duplicate]
             (swap! calls conj [repo kept-uuid duplicate])
             (p/resolved
              (if (= duplicate blocked-duplicate)
                {:status :blocked :reason :duplicate-in-use}
                {:status :resolved
                 :keep-uuid kept-uuid
                 :duplicate-uuid duplicate})))]
          (#'editor-assets/<resolve-duplicate-pdfs!
           "test-repo"
           keep-uuid
           [first-duplicate blocked-duplicate untouched-duplicate]
           (constantly true)))
        (p/then
         (fn [results]
           (is (= [["test-repo" keep-uuid first-duplicate]
                   ["test-repo" keep-uuid blocked-duplicate]]
                  @calls))
           (is (= [:resolved :blocked] (mapv :status results)))
           (is (= :duplicate-in-use (:reason (last results)))))))))

(deftest-async duplicate-pdf-blocker-closes-the-stale-choice-dialog
  (let [keep-uuid #uuid "72000000-0000-4000-8000-000000000031"
        duplicate-uuid #uuid "72000000-0000-4000-8000-000000000032"
        close-count (atom 0)
        notification-type (atom nil)]
    (-> (p/with-redefs
          [db-async/<resolve-duplicate-pdf
           (fn [& _]
             (p/resolved {:status :blocked :reason :duplicate-in-use}))
           shui/dialog-close! (fn [id]
                                (is (= :literature-duplicate-pdf id))
                                (swap! close-count inc))
           notification/show! (fn [_message type & _]
                                (reset! notification-type type))]
          (#'editor-assets/resolve-duplicate-pdf-choice!
           "test-repo"
           [{:block/uuid keep-uuid} {:block/uuid duplicate-uuid}]
           keep-uuid
           #(is false "A blocked choice must not resume the import")
           (constantly true)
           (constantly true)))
        (p/then
         (fn []
           (is (= 1 @close-count))
           (is (= :warning @notification-type)))))))

(defn- capture-duplicate-pdf-dialog!
  [dialog content opts]
  (let [config (assoc opts :open? true :close (fn []))]
    (reset! dialog config)
    (dorun (tree-seq coll? seq (content config)))))

(deftest-async duplicate-pdf-chooser-reuses-the-existing-paste-callback
  (let [keep-uuid #uuid "72000000-0000-4000-8000-000000000041"
        duplicate-uuid #uuid "72000000-0000-4000-8000-000000000042"
        files [:synthetic-pdf]
        opts {:target-block #uuid "72000000-0000-4000-8000-000000000043"}
        assets [{:block/uuid keep-uuid :block/title "Keep"}
                {:block/uuid duplicate-uuid :block/title "Duplicate"}]
        dialog (atom nil)
        choice (atom nil)
        calls (atom [])
        resumed (atom [])
        history-completions (atom 0)
        notifications (atom [])]
    (state/set-state! :db/restoring? false)
    (p/with-redefs
      [state/get-current-repo (constantly "test-repo")
       db-subs/projection-context (constantly {:graph-id "test-repo" :projection-epoch 1})
       shui/dialog-open! #(capture-duplicate-pdf-dialog! dialog %1 %2)
       shui/dialog-get (fn [_] [0 @dialog])
       shui/button (fn [button-opts & _]
                     (when-not @choice (reset! choice (:on-click button-opts))))
       shui/dialog-close! (fn [id]
                            (is (= :literature-duplicate-pdf id))
                            (swap! dialog assoc :open? false))
       notification/show! (fn [_ type & _] (swap! notifications conj type))
       db-async/<resolve-duplicate-pdf
       (fn [repo kept duplicate]
         (swap! calls conj [repo kept duplicate])
         (p/resolved {:status :resolved}))
       editor-assets/<db-based-save-assets!
       (fn [repo incoming incoming-opts]
         (swap! resumed conj [repo incoming incoming-opts])
         (p/resolved []))]
      (editor-assets/show-duplicate-pdf-conflict! "test-repo" files opts {:assets assets})
      (p/let [_ (@choice)]
        (is (= [["test-repo" keep-uuid duplicate-uuid]] @calls))
        (is (= [["test-repo" files opts]] @resumed))
        (is (= [:success] @notifications))
        (is (false? (:open? @dialog)))
        (reset! choice nil)
        (editor-assets/show-duplicate-pdf-conflict!
         "test-repo" assets #(swap! history-completions inc))
        (p/let [_ (@choice)]
          (is (= 1 @history-completions))
          (is (= [["test-repo" files opts]] @resumed)
              "A history choice must call its callback without repasting files")
          (is (= [["test-repo" keep-uuid duplicate-uuid]
                  ["test-repo" keep-uuid duplicate-uuid]] @calls))
          (is (= [:success :success] @notifications)))))))

(deftest-async duplicate-pdf-chooser-discards-replies-after-close-reopen-or-cutover
  (let [keep-uuid #uuid "72000000-0000-4000-8000-000000000051"
        first-duplicate #uuid "72000000-0000-4000-8000-000000000052"
        untouched-duplicate #uuid "72000000-0000-4000-8000-000000000053"
        assets [{:block/uuid keep-uuid :block/title "Keep"}
                {:block/uuid first-duplicate :block/title "First"}
                {:block/uuid untouched-duplicate :block/title "Second"}]]
    (p/loop [remaining (seq [:closed :reopened :graph-switched :cutover :restoring])]
      (when-let [scenario (first remaining)]
        (let [dialog (atom nil)
              choice (atom nil)
              current-repo (atom "test-repo")
              context (atom {:graph-id "test-repo" :projection-epoch 1})
              started (p/deferred)
              deferred (p/deferred)
              calls (atom [])
              resumed (atom 0)
              closes (atom 0)
              notifications (atom [])]
          (state/set-state! :db/restoring? false)
          (p/let [_ (p/with-redefs
                      [state/get-current-repo #(deref current-repo)
                       db-subs/projection-context #(deref context)
                       shui/dialog-open! #(capture-duplicate-pdf-dialog! dialog %1 %2)
                       shui/dialog-get (fn [_] [0 @dialog])
                       shui/button (fn [button-opts & _]
                                     (when-not @choice
                                       (reset! choice (:on-click button-opts))))
                       shui/dialog-close! (fn [_] (swap! closes inc))
                       notification/show! (fn [_ type & _]
                                            (swap! notifications conj type))
                       db-async/<resolve-duplicate-pdf
                       (fn [repo kept duplicate]
                         (swap! calls conj [repo kept duplicate])
                         (p/resolve! started true)
                         deferred)]
                      (editor-assets/show-duplicate-pdf-conflict!
                       "test-repo" assets #(swap! resumed inc))
                      (let [pending (@choice)]
                        (p/let [_ started]
                          (is (= [["test-repo" keep-uuid first-duplicate]] @calls))
                          (case scenario
                            :closed (swap! dialog assoc :open? false)
                            :reopened (swap! dialog assoc :close (fn []))
                            :graph-switched (reset! current-repo "other-test-repo")
                            :cutover (swap! context update :projection-epoch inc)
                            :restoring (state/set-state! :db/restoring? true))
                          (p/resolve! deferred {:status :resolved})
                          (p/let [_ pending]
                            (testing (name scenario)
                              (is (= [["test-repo" keep-uuid first-duplicate]] @calls)
                                  "A stale selection must not resolve another duplicate")
                              (is (zero? @resumed))
                              (is (zero? @closes)
                                  "An old reply must not close a new dialog")
                              (is (empty? @notifications)))))))]
            (p/recur (next remaining))))))))

(deftest-async duplicate-pdf-chooser-does-not-start-a-closed-selection
  (let [assets [{:block/uuid #uuid "72000000-0000-4000-8000-000000000061"
                 :block/title "Keep"}
                {:block/uuid #uuid "72000000-0000-4000-8000-000000000062"
                 :block/title "Duplicate"}]
        dialog (atom nil)
        choice (atom nil)
        calls (atom 0)
        resumed (atom 0)]
    (state/set-state! :db/restoring? false)
    (p/with-redefs
      [state/get-current-repo (constantly "test-repo")
       db-subs/projection-context (constantly {:graph-id "test-repo" :projection-epoch 1})
       shui/dialog-open! #(capture-duplicate-pdf-dialog! dialog %1 %2)
       shui/dialog-get (fn [_] [0 @dialog])
       shui/button (fn [button-opts & _]
                     (when-not @choice (reset! choice (:on-click button-opts))))
       db-async/<resolve-duplicate-pdf
       (fn [& _] (swap! calls inc) (p/resolved {:status :resolved}))
       shui/dialog-close! (fn [& _] (is false "A closed choice must not close another dialog"))
       notification/show! (fn [& _] (is false "A closed choice must not show result feedback"))]
      (editor-assets/show-duplicate-pdf-conflict! "test-repo" assets #(swap! resumed inc))
      (swap! dialog assoc :open? false)
      (p/let [_ (@choice)]
        (is (zero? @calls))
        (is (zero? @resumed))))))

(deftest-async duplicate-pdf-resume-rejection-reports-error-without-closing-a-new-dialog
  (let [assets [{:block/uuid #uuid "72000000-0000-4000-8000-000000000071"
                 :block/title "Keep"}
                {:block/uuid #uuid "72000000-0000-4000-8000-000000000072"
                 :block/title "Duplicate"}]
        dialog (atom nil)
        choice (atom nil)
        resume-started (p/deferred)
        resume-result (p/deferred)
        calls (atom 0)
        resumed (atom 0)
        closes (atom [])
        notifications (atom [])]
    (state/set-state! :db/restoring? false)
    (p/with-redefs
      [state/get-current-repo (constantly "test-repo")
       db-subs/projection-context (constantly {:graph-id "test-repo" :projection-epoch 1})
       shui/dialog-open! #(capture-duplicate-pdf-dialog! dialog %1 %2)
       shui/dialog-get (fn [_] [0 @dialog])
       shui/button (fn [button-opts & _]
                     (when-not @choice (reset! choice (:on-click button-opts))))
       db-async/<resolve-duplicate-pdf
       (fn [& _] (swap! calls inc) (p/resolved {:status :resolved}))
       shui/dialog-close! (fn [id]
                            (swap! closes conj id)
                            (swap! dialog assoc :open? false))
       notification/show! (fn [_ type & _] (swap! notifications conj type))
       editor-assets/<db-based-save-assets!
       (fn [& _]
         (swap! resumed inc)
         (p/resolve! resume-started true)
         resume-result)]
      (editor-assets/show-duplicate-pdf-conflict!
       "test-repo" [:synthetic-pdf] {} {:assets assets})
      (let [pending (@choice)]
        (p/let [_ resume-started]
          (is (= [:literature-duplicate-pdf] @closes))
          (is (= [:success] @notifications))
          (reset! dialog {:id :literature-library :open? true :close (fn [])})
          (p/reject! resume-result (ex-info "Synthetic resume failure" {}))
          (p/let [_ pending]
            (is (= 1 @calls))
            (is (= 1 @resumed))
            (is (= [:success :error] @notifications))
            (is (= [:literature-duplicate-pdf] @closes))
            (is (= :literature-library (:id @dialog)))
            (is (true? (:open? @dialog)))))))))
