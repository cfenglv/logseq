(ns frontend.modules.outliner.pipeline-test
  (:require [cljs.test :refer [deftest is]]
            [frontend.db.subs :as db-subs]
            [frontend.handler.route :as route-handler]
            [frontend.modules.outliner.pipeline :as pipeline]
            [frontend.state :as state]
            [frontend.util :as util]))

(deftest deletion-resolves-named-route-identity-before-applying-delta-test
  (let [repo "named-route-deletion-test"
        page-uuid (random-uuid)]
    (doseq [[route ready? permanent? same-repo? expected]
            [["Named Page" true false true 1]
             ["Named Page" true true true 1]
             [(str page-uuid) false true true 1]
             ["Named Page" false true true 0]
             ["Named Page" true true false 0]]]
      (let [applied? (atom false)
            redirects (atom 0)
            delta {:blocks (if permanent? {} {page-uuid {:logseq.property/deleted-at 1}})
                   :deleted (if permanent? {page-uuid {:db/id 42}} {})}]
        (with-redefs [db-subs/future-projection? (constantly false)
                      db-subs/current-projection? (constantly true)
                      db-subs/resource-snapshot
                      (fn [key]
                        (is (= [:page-identity "named page"] key))
                        (if (and ready? (not @applied?))
                          {:status :ready :value page-uuid}
                          {:status :loading}))
                      db-subs/apply-delta! (fn [_] (reset! applied? true))
                      state/get-current-repo (constantly (if same-repo? repo "other-graph"))
                      state/get-current-page (constantly route)
                      state/get-edit-block (constantly nil)
                      state/editing? (constantly false)
                      state/sidebar-remove-deleted-block! (constantly nil)
                      state/remove-pages-from-recent! (constantly nil)
                      state/set-state! (constantly nil)
                      state/pub-event! (constantly nil)
                      util/mobile? (constantly false)
                      route-handler/<redirect-after-page-deletion! #(swap! redirects inc)]
          (pipeline/invoke-hooks {:repo repo :tx-meta {} :delta delta}))
        (is @applied?)
        (is (= expected @redirects))))))

(deftest compact-worker-broadcast-applies-the-exact-delta-once-and-keeps-page-events-test
  (let [original-state (state/get-state)
        state-calls (atom [])
        applied-deltas (atom [])
        published-events (atom [])
        repo "broadcast-render-delta-test"
        delta {:graph-id repo
               :projection-epoch 0
               :rev 7
               :blocks {}
               :deleted {}
               :children {}
               :affected-keys #{[:graph]}}
        rename-data {:old-name "before" :new-name "after"}
        tx-meta {:client-id "client"
                 :outliner-op :rename-page
                 :data rename-data}]
    (try
      (state/replace-state! {:client-id "client"})
      (with-redefs [db-subs/apply-delta! (fn [value]
                                           (swap! applied-deltas conj value)
                                           true)
                    db-subs/current-projection? (constantly true)
                    state/get-current-repo (constantly repo)
                    state/get-current-page (constantly nil)
                    state/set-state! (fn [& args]
                                       (swap! state-calls conj args))
                    state/pub-event! (fn [event]
                                       (swap! published-events conj event))]
        (pipeline/invoke-hooks {:repo repo
                                :tx-meta tx-meta
                                :delta delta})
        (is (= 1 (count @applied-deltas)))
        (is (identical? delta (first @applied-deltas))
            "The broadcast entry point must pass the worker-owned delta through untouched.")
        (is (not-any? #(= :db/latest-transacted-entity-uuids (first %))
                      @state-calls))
        (is (= [[:page/renamed repo rename-data]] @published-events)
            "Page lifecycle events remain a narrow non-renderer side effect."))
      (finally
        (state/replace-state! original-state)))))

(deftest deleted-blocks-are-removed-from-the-sidebar-test
  (let [original-state (state/get-state)
        removed-ids (atom nil)
        repo "sidebar-tombstone-test"
        deleted-uuid (random-uuid)
        delta {:graph-id repo
               :projection-epoch 0
               :rev 9
               :blocks {}
               :deleted {deleted-uuid {:rev 9 :db/id 42}}
               :children {}
               :affected-keys #{}}
        tx-meta {:client-id "client"}]
    (try
      (state/replace-state! {:client-id "client"})
      (with-redefs [db-subs/apply-delta! (constantly true)
                    db-subs/current-projection? (constantly true)
                    state/get-current-repo (constantly repo)
                    state/get-current-page (constantly nil)
                    state/sidebar-remove-deleted-block! (fn [ids]
                                                          (reset! removed-ids ids))]
        (pipeline/invoke-hooks {:repo repo
                                :tx-meta tx-meta
                                :delta delta})
        (is (= [42] @removed-ids)
            "Deleted block tombstones drop the matching sidebar entries."))
      (finally
        (state/replace-state! original-state)))))

(deftest recycled-pages-are-removed-from-recents-test
  (let [original-state (state/get-state)
        repo "recycled-page-recents-test"
        page-uuid (random-uuid)
        delta {:graph-id repo
               :projection-epoch 0
               :rev 10
               :blocks {page-uuid {:db/id 42
                                   :block/uuid page-uuid
                                   :block/tags [{:db/ident :logseq.class/Page}]
                                   :logseq.property/deleted-at 1}}
               :deleted {}
               :children {}
               :affected-keys #{[:entity page-uuid]}}
        tx-meta {:client-id "client"
                 :outliner-op :delete-page
                 :deleted-page "Deleted page"}]
    (try
      (state/replace-state! {:client-id "client"
                             :git/current-repo repo
                             :ui/recent-pages {repo [42 7]}})
      (with-redefs [db-subs/apply-delta! (constantly true)
                    db-subs/current-projection? (constantly true)
                    state/get-current-page (constantly nil)
                    state/pub-event! (constantly nil)]
        (pipeline/invoke-hooks {:repo repo
                                :tx-meta tx-meta
                                :delta delta})
        (is (= [7] (state/get-recent-pages))
            "Recycling a page removes only that page from recent history."))
      (finally
        (state/replace-state! original-state)))))

(deftest hard-deleted-pages-are-removed-from-recents-test
  (let [original-state (state/get-state)
        repo "hard-deleted-page-recents-test"
        page-uuid (random-uuid)
        delta {:graph-id repo
               :projection-epoch 0
               :rev 11
               :blocks {}
               :deleted {page-uuid {:rev 11 :db/id 42}}
               :children {}
               :affected-keys #{[:entity page-uuid]}}
        tx-meta {:client-id "client"
                 :outliner-op :delete-page
                 :deleted-page "Deleted page"}]
    (try
      (state/replace-state! {:client-id "client"
                             :git/current-repo repo
                             :ui/recent-pages {repo [42 7]}})
      (with-redefs [db-subs/apply-delta! (constantly true)
                    db-subs/current-projection? (constantly true)
                    state/get-current-page (constantly nil)
                    state/sidebar-remove-deleted-block! (constantly nil)
                    state/pub-event! (constantly nil)]
        (pipeline/invoke-hooks {:repo repo
                                :tx-meta tx-meta
                                :delta delta})
        (is (= [7] (state/get-recent-pages))
            "Hard deletion removes only that page from recent history."))
      (finally
        (state/replace-state! original-state)))))

(deftest stale-projection-broadcast-applies-no-lifecycle-side-effects-test
  (let [repo "stale-projection-broadcast-test"
        delta {:graph-id repo
               :projection-epoch 1
               :rev 12
               :blocks {}
               :deleted {}
               :children {}
               :affected-keys #{[:graph]}}
        applied-deltas (atom [])
        published-events (atom [])]
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly false)
                  db-subs/apply-delta! #(swap! applied-deltas conj %)
                  state/pub-event! #(swap! published-events conj %)]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:outliner-op :rename-page
                                        :data {:old-name "old" :new-name "new"}}
                              :delta delta}))
    (is (= [delta] @applied-deltas)
        "The canonical store still owns validation and stale-delta rejection.")
    (is (empty? @published-events)
        "A stale projection cannot publish plugin or page lifecycle effects.")))

(deftest future-projection-delta-requests-cutover-without-applying-side-effects-test
  (let [repo "future-projection-broadcast-test"
        delta {:graph-id repo
               :projection-epoch 3
               :rev 12
               :blocks {}
               :deleted {}
               :children {}
               :affected-keys #{}}
        applied-deltas (atom [])
        published-events (atom [])]
    (with-redefs [db-subs/future-projection? (constantly true)
                  db-subs/apply-delta! #(swap! applied-deltas conj %)
                  state/pub-event! #(swap! published-events conj %)]
      (pipeline/invoke-hooks {:repo repo :tx-meta {} :delta delta}))
    (is (empty? @applied-deltas))
    (is (= [[:db/projection-committed
             {:repo repo :projection-epoch 3}]]
           @published-events))))

(deftest external-title-update-freezes-one-dirty-draft-semantic-op-test
  (let [repo "dirty-draft-update-test"
        block-uuid (random-uuid)
        events (atom [])
        set-content (atom [])
        delta {:graph-id repo
               :projection-epoch 0
               :rev 13
               :blocks {block-uuid {:block/uuid block-uuid
                                    :block/title "remote title"}}
               :deleted {}
               :children {}
               :affected-keys #{[:entity block-uuid]}}]
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly true)
                  db-subs/block-snapshot (constantly {:status :ready
                                                      :value {:block/uuid block-uuid
                                                              :block/title "base title"}})
                  db-subs/apply-delta! (constantly true)
                  state/get-current-repo (constantly repo)
                  state/get-current-page (constantly nil)
                  state/get-edit-block (constantly {:block/uuid block-uuid
                                                    :block/title "base title"})
                  state/get-edit-content (constantly "local dirty draft")
                  state/editing? (constantly true)
                  state/set-edit-content! #(swap! set-content conj %)
                  state/pub-event! #(swap! events conj %)]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "other-window"}
                              :delta delta}))
    (is (= [[:editor/save-current-block]]
           (filterv #(= :editor/save-current-block (first %)) @events)))
    (is (empty? @set-content)
        "The incoming title must not replace the active dirty text.")))

(deftest already-saved-editor-content-is-not-recovered-as-a-dirty-draft-test
  (let [repo "saved-editor-external-update-test"
        block-uuid (random-uuid)
        events (atom [])
        set-content (atom [])
        delta {:graph-id repo
               :projection-epoch 0
               :rev 14
               :blocks {block-uuid {:block/uuid block-uuid
                                    :block/title "remote title"}}
               :deleted {}
               :children {}
               :affected-keys #{[:entity block-uuid]}}]
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly true)
                  db-subs/block-snapshot (constantly {:status :ready
                                                      :value {:block/uuid block-uuid
                                                              :block/title "already saved"}})
                  db-subs/apply-delta! (constantly true)
                  state/get-current-repo (constantly repo)
                  state/get-current-page (constantly nil)
                  ;; Editor state can retain the title from when editing began.
                  state/get-edit-block (constantly {:block/uuid block-uuid
                                                    :block/title "opening title"})
                  state/get-edit-content (constantly "already saved")
                  state/editing? (constantly true)
                  state/set-edit-content! #(swap! set-content conj %)
                  state/pub-event! #(swap! events conj %)]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "other-window"}
                              :delta delta}))
    (is (empty? (filter #(= :editor/save-current-block (first %)) @events))
        "Saved editor text must not create a recovery transaction.")
    (is (= ["remote title"] @set-content)
        "The official clean-editor refresh path remains active.")))

(deftest distinct-external-title-deltas-do-not-resave-the-same-dirty-draft-test
  (let [repo "dirty-draft-feedback-test"
        block-uuid (random-uuid)
        input (atom #js {})
        draft (atom "local dirty draft")
        events (atom [])
        delta (fn [rev title]
                {:graph-id repo
                 :projection-epoch 0
                 :rev rev
                 :blocks {block-uuid {:block/uuid block-uuid
                                      :block/title title}}
                 :deleted {}
                 :children {}
                 :affected-keys #{[:entity block-uuid]}})]
    (reset! @#'pipeline/*recovered-dirty-draft nil)
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly true)
                  db-subs/block-snapshot (constantly {:status :ready
                                                      :value {:block/uuid block-uuid
                                                              :block/title "base title"}})
                  db-subs/apply-delta! (constantly true)
                  state/get-current-repo (constantly repo)
                  state/get-current-page (constantly nil)
                  state/get-edit-block (constantly {:block/uuid block-uuid
                                                    :block/title "base title"})
                  state/get-edit-content #(deref draft)
                  state/get-input #(deref input)
                  state/editing? (constantly true)
                  state/set-edit-content! (constantly nil)
                  state/pub-event! #(swap! events conj %)]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "peer-a"}
                              :delta (delta 13 "remote title a")})
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "peer-b"}
                              :delta (delta 14 "remote title b")})
      (is (= [[:editor/save-current-block]]
             (filterv #(= :editor/save-current-block (first %)) @events))
          "The same editor input and draft may recover only once across distinct external deltas.")
      (reset! input #js {})
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "peer-c"}
                              :delta (delta 15 "remote title c")})
      (is (= 2 (count (filter #(= :editor/save-current-block (first %)) @events)))
          "A new editor input may recover the same text again.")
      (reset! draft "new user input")
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "peer-d"}
                              :delta (delta 16 "remote title d")})
      (is (= 3 (count (filter #(= :editor/save-current-block (first %)) @events)))
          "New user input permits one new recovery operation."))))

(deftest duplicate-title-delta-neither-overwrites-nor-resaves-dirty-draft-test
  (let [repo "dirty-draft-duplicate-test"
        block-uuid (random-uuid)
        events (atom [])
        set-content (atom [])
        delta {:graph-id repo
               :projection-epoch 0
               :rev 13
               :blocks {block-uuid {:block/uuid block-uuid
                                    :block/title "remote title"}}
               :deleted {}
               :children {}
               :affected-keys #{[:entity block-uuid]}}]
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly true)
                  db-subs/block-snapshot (constantly {:status :ready
                                                      :value {:block/uuid block-uuid
                                                              :block/title "base title"}})
                  db-subs/apply-delta! (constantly nil)
                  state/get-current-repo (constantly repo)
                  state/get-current-page (constantly nil)
                  state/get-edit-block (constantly {:block/uuid block-uuid
                                                    :block/title "base title"})
                  state/get-edit-content (constantly "local dirty draft")
                  state/editing? (constantly true)
                  state/set-edit-content! #(swap! set-content conj %)
                  state/pub-event! #(swap! events conj %)]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "other-window"}
                              :delta delta}))
    (is (empty? (filter #(= :editor/save-current-block (first %)) @events))
        "A replayed delta cannot create a second recovery operation.")
    (is (empty? @set-content)
        "A replayed delta still cannot overwrite the dirty text.")))

(deftest external-delete-recovers-dirty-draft-with-one-insert-event-test
  (let [repo "dirty-draft-delete-test"
        block-uuid (random-uuid)
        editing-block {:block/uuid block-uuid
                       :block/title "base title"
                       :block/page {:db/id 42}}
        events (atom [])
        delta {:graph-id repo
               :projection-epoch 0
               :rev 14
               :blocks {}
               :deleted {block-uuid {:db/id 99 :rev 14}}
               :children {}
               :affected-keys #{[:entity block-uuid]}}]
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly true)
                  db-subs/block-snapshot (constantly {:status :ready
                                                      :value editing-block})
                  db-subs/apply-delta! (constantly true)
                  state/get-current-repo (constantly repo)
                  state/get-current-page (constantly nil)
                  state/get-edit-block (constantly editing-block)
                  state/get-edit-content (constantly "local dirty draft")
                  state/editing? (constantly true)
                  state/sidebar-remove-deleted-block! (constantly nil)
                  state/pub-event! #(swap! events conj %)]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta {:client-id "other-window"}
                              :delta delta}))
    (is (= [[:editor/recover-deleted-dirty-draft
             editing-block
             "local dirty draft"]]
           @events))))

(defn- recycled-page-delta
  [page-uuid]
  {:graph-id "delete-nav-test"
   :rev 12
   :blocks {page-uuid {:db/id 42
                       :block/uuid page-uuid
                       :block/tags [{:db/ident :logseq.class/Page}]
                       :logseq.property/deleted-at 1}}
   :deleted {}
   :children {}
   :affected-keys #{[:entity page-uuid]}})

(defn- hard-deleted-page-delta
  [page-uuid]
  {:graph-id "delete-nav-test"
   :rev 13
   :blocks {}
   :deleted {page-uuid {:rev 13 :db/id 42}}
   :children {}
   :affected-keys #{[:entity page-uuid]}})

(defn- invoke-delete-hooks
  [{:keys [current-page delta tx-meta]}]
  (let [repo "delete-nav-test"
        nav-calls (atom [])]
    (with-redefs [db-subs/future-projection? (constantly false)
                  db-subs/current-projection? (constantly true)
                  db-subs/apply-delta! (constantly true)
                  state/get-current-repo (constantly repo)
                  state/get-current-page (constantly current-page)
                  state/sidebar-remove-deleted-block! (constantly nil)
                  state/pub-event! (constantly nil)
                  route-handler/<redirect-after-page-deletion! (fn []
                                                        (swap! nav-calls conj :previous))
                  route-handler/redirect-to-home! (fn [& _]
                                                    (swap! nav-calls conj :home))]
      (pipeline/invoke-hooks {:repo repo
                              :tx-meta (merge {:client-id "client"
                                               :outliner-op :delete-page
                                               :deleted-page "Page B"}
                                              tx-meta)
                              :delta delta})
      @nav-calls)))

(deftest recycling-current-page-goes-to-previous-route-test
  (let [original-state (state/get-state)
        page-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (is (= [:previous]
             (invoke-delete-hooks
              {:current-page (str page-uuid)
               :delta (recycled-page-delta page-uuid)}))
          "Deleting the current page goes back to the previous page/block, not home.")
      (finally
        (state/replace-state! original-state)))))

(deftest recycling-other-page-does-not-navigate-test
  (let [original-state (state/get-state)
        page-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (is (= []
             (invoke-delete-hooks
              {:current-page (str (random-uuid))
               :delta (recycled-page-delta page-uuid)}))
          "Deleting a page that is not the current route must not steal navigation.")
      (finally
        (state/replace-state! original-state)))))

(deftest hard-deleting-current-page-goes-to-previous-route-test
  (let [original-state (state/get-state)
        page-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (is (= [:previous]
             (invoke-delete-hooks
              {:current-page (str page-uuid)
               :delta (hard-deleted-page-delta page-uuid)}))
          "Permanently deleting the current page goes back to the previous page/block.")
      (finally
        (state/replace-state! original-state)))))

(deftest hard-deleting-other-page-does-not-navigate-test
  (let [original-state (state/get-state)
        page-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (is (= []
             (invoke-delete-hooks
              {:current-page (str (random-uuid))
               :delta (hard-deleted-page-delta page-uuid)}))
          "Permanently deleting a non-current page must not steal navigation.")
      (finally
        (state/replace-state! original-state)))))

(deftest deleting-current-page-on-mobile-goes-to-previous-route-once-test
  (let [original-state (state/get-state)
        page-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (with-redefs [util/mobile? (constantly true)]
        (doseq [delta [(recycled-page-delta page-uuid)
                       (hard-deleted-page-delta page-uuid)
                       (assoc (recycled-page-delta page-uuid)
                              :deleted (:deleted (hard-deleted-page-delta page-uuid)))]]
          (is (= [:previous]
                 (invoke-delete-hooks
                  {:current-page (str page-uuid)
                   :delta delta}))
              "Recycling or permanently deleting the current mobile page navigates exactly once.")))
      (finally
        (state/replace-state! original-state)))))

(deftest recycling-current-block-goes-to-previous-route-test
  (let [original-state (state/get-state)
        block-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (is (= [:previous]
             (invoke-delete-hooks
              {:current-page (str block-uuid)
               :delta {:graph-id "delete-nav-test"
                       :rev 14
                       :blocks {block-uuid {:db/id 99
                                            :block/uuid block-uuid
                                            :logseq.property/deleted-at 1}}
                       :deleted {}
                       :children {}
                       :affected-keys #{[:entity block-uuid]}}}))
          "Deleting the focused block also returns to the previous page/block.")
      (finally
        (state/replace-state! original-state)))))

(deftest recycling-current-page-during-initial-pages-does-not-navigate-test
  (let [original-state (state/get-state)
        page-uuid (random-uuid)]
    (try
      (state/replace-state! {:client-id "client"})
      (is (= []
             (invoke-delete-hooks
              {:current-page (str page-uuid)
               :delta (recycled-page-delta page-uuid)
               :tx-meta {:initial-pages? true}}))
          "Graph load must not treat recycled pages as a user delete navigation.")
      (finally
        (state/replace-state! original-state)))))
