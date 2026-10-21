(ns frontend.handler.route-test
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [electron.ipc :as ipc]
            [frontend.db.async :as db-async]
            [frontend.db.conn :as conn]
            [frontend.db.utils :as db-utils]
            [frontend.date :as date]
            [frontend.handler.graph :as graph-handler]
            [frontend.handler.recent :as recent-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper :refer [load-test-files]]
            [promesa.core :as p]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]))

(use-fixtures :each {:before test-helper/start-test-db!
                     :after test-helper/destroy-test-db!})

(deftest route-change-clears-only-detached-selection-test
  (doseq [[path cleared?] [["/page/other" true] ["/page/current" false]]]
    (let [cleared (atom false)
          route {:path path}]
      (with-redefs [state/get-route-match (constantly {:path "/page/current"})
                    state/clear-selection! #(reset! cleared true)
                    state/swap-state! (fn [& _])
                    route-handler/update-page-title! (fn [_])
                    route-handler/update-page-label! (fn [_])]
        (route-handler/set-route-match! route)
        (is (= cleared? @cleared))))))

(defn- test-db
  []
  (conn/get-db test-helper/test-db))

(defn- deletion-test-window
  [previous-url calls]
  #js {:location (js/URL. "https://example.test/#/page/current?graph-id=graph")
       :navigation #js {:canGoBack (some? previous-url)
                        :currentEntry #js {:id "current-entry" :index 1}
                        :entries (fn [] #js [#js {:url previous-url} #js {}])}
       :history #js {:back #(swap! calls conj :back)}})

(defn- deletion-test-router
  []
  (rf/router [["/" {:name :home}]
              ["/all-pages" {:name :all-pages}]
              ["/page/:name" {:name :page}]
              ["/page/:name/block/:block-route-name" {:name :page-block}]]))

(deftest deletion-navigation-uses-only-live-same-graph-targets-test
  (async done
    (let [original-window (.-window js/global)
          original-history @rfe/history
          live {:db/id 1 :block/title "Previous"}
          cases [{:label "live page" :url "https://example.test/#/page/previous?graph-id=graph" :block live :expected :back}
                 {:label "live heading" :url "https://example.test/#/page/previous/block/heading?graph-id=graph" :block live :expected :back}
                 {:label "non-page view" :url "https://example.test/#/all-pages" :expected :back}
                 {:label "no previous entry"}
                 {:label "only forward entries" :url "https://example.test/#/all-pages" :forward-only? true}
                 {:label "native history unavailable" :no-navigation? true}
                 {:label "missing page" :url "https://example.test/#/page/missing?graph-id=graph"}
                 {:label "recycled page" :url "https://example.test/#/page/previous?graph-id=graph"
                  :block (assoc live :logseq.property/deleted-at 1)}
                 {:label "other graph" :url "https://example.test/#/page/previous?graph-id=other" :block live}
                 {:label "unbound page" :url "https://example.test/#/page/previous" :block live}
                 {:label "other origin" :url "https://other.example/#/all-pages"}
                 {:label "other application" :url "https://example.test/other/#/all-pages"}
                 {:label "unknown route" :url "https://example.test/#/missing-route"}]]
      (reset! rfe/history {:router (deletion-test-router)})
      (-> (p/loop [remaining cases]
            (when-let [{:keys [label url block expected forward-only? no-navigation?]} (first remaining)]
              (let [calls (atom [])
                    window (deletion-test-window url calls)]
                (when forward-only? (aset window "navigation" "canGoBack" false))
                (when no-navigation? (set! (.-navigation window) nil))
                (set! (.-window js/global) window)
                (p/let [_ (p/with-redefs [state/get-current-repo (constantly "repo")
                                         graph-handler/current-graph-id (constantly "graph")
                                         db-async/<get-block (fn [& _] (p/resolved block))
                                         route-handler/redirect! #(swap! calls conj %)]
                            (route-handler/<redirect-after-page-deletion!))]
                  (is (= [(or expected {:to :home :push false})] @calls) label)
                  (p/recur (next remaining))))))
          (p/catch #(is false (str %)))
          (p/finally (fn []
                       (set! (.-window js/global) original-window)
                       (reset! rfe/history original-history)
                       (done)))))))

(deftest deletion-navigation-does-not-overwrite-newer-user-navigation-test
  (async done
    (let [original-window (.-window js/global)
          original-history @rfe/history]
      (reset! rfe/history {:router (deletion-test-router)})
      (-> (p/loop [changes [:route :graph :entry]]
            (when-let [change (first changes)]
              (let [calls (atom [])
                    repo (atom "repo")
                    block (p/deferred)
                    queried (p/deferred)
                    window (deletion-test-window "https://example.test/#/page/previous?graph-id=graph" calls)]
                (set! (.-window js/global) window)
                (p/let [_ (p/with-redefs [state/get-current-repo #(deref repo)
                                         graph-handler/current-graph-id (constantly "graph")
                                         db-async/<get-block (fn [& _] (p/resolve! queried true) block)
                                         route-handler/redirect! #(swap! calls conj %)]
                            (let [result (route-handler/<redirect-after-page-deletion!)]
                              (p/do!
                               queried
                               (case change
                                 :route (set! (.. window -location -hash) "#/all-pages")
                                 :graph (reset! repo "other-repo")
                                 :entry (aset window "navigation" "currentEntry" "id" "new-entry"))
                               (p/resolve! block {:db/id 1 :block/title "Previous"})
                               result)))]
                  (is (empty? @calls) (name change))
                  (p/recur (next changes))))))
          (p/catch #(is false (str %)))
          (p/finally (fn []
                       (set! (.-window js/global) original-window)
                       (reset! rfe/history original-history)
                       (done)))))))

(deftest default-page-route
  (let [journal-uuid (random-uuid)]
    (load-test-files
     [{:page {:block/title "foo"}
       :blocks
       [{:block/title "b1"}
        {:block/title "B1"
         :build/properties {:logseq.property/heading 2}}
        {:block/title "b2"}
        {:block/title "Header 2"
         :build/properties {:logseq.property/heading 3}}
        {:block/title (str "Header 3 [[" journal-uuid "]]")
         :build/properties {:logseq.property/heading 2}}]}
      {:page {:build/journal 20221219
              :build/keep-uuid? true
              :block/uuid journal-uuid}}]))

  (testing ":page-block route"
    (let [block (ffirst
                 (db-utils/q (test-db)
                             '[:find (pull ?b [:block/uuid])
                               :where [?b :block/title "B1"]]))]
      (is (= {:to :page-block
              :path-params {:name "foo" :block-route-name "b1"}}
             (#'route-handler/default-page-route (:block/uuid block)
                                                 {:block-page-name "foo"
                                                  :block-route-name "b1"}))
          "Generates a page-block link if route-name is found")))

  (let [block (ffirst
               (db-utils/q (test-db)
                           '[:find (pull ?b [:block/uuid])
                             :where [?b :block/title "Header 2"]]))]
    (is (= {:to :page-block
            :path-params {:name "foo" :block-route-name "header 2"}}
           (#'route-handler/default-page-route (:block/uuid block)
                                               {:block-page-name "foo"
                                                :block-route-name "header 2"}))
        "Generates a page-block link for route-name with whitespace and properties is found")

    (let [block (test-helper/find-block-by-content #"Header 3")]
      (is (= {:to :page-block
              :path-params {:name "foo" :block-route-name "header 3 [[dec 19th, 2022]]"}}
             (#'route-handler/default-page-route (:block/uuid block)
                                                 {:block-page-name "foo"
                                                  :block-route-name "header 3 [[dec 19th, 2022]]"}))
          "Generates a page-block link for route name with page ref")))

  (testing ":page route"
    (let [uuid (-> (db-utils/q (test-db)
                               '[:find (pull ?b [:block/uuid])
                                 :where [?b :block/title "b2"]])
                   ffirst
                   :block/uuid)]
      (is (= {:to :page :path-params {:name (str uuid)}}
             (#'route-handler/default-page-route uuid))
          "Generates a page link if route-name is not found"))

    (is (= {:to :page :path-params {:name "page-name"}}
           (#'route-handler/default-page-route "page-name"))
        "Generates a page link if name is not a uuid")

    (is (= {:to :page :path-params {:name "page name"}}
           (#'route-handler/default-page-route "Page name"))
        "Generates a case insensitive page link")))

(deftest redirect-to-page-uses-worker-route-info-test
  (async done
    (let [source-uuid #uuid "11111111-1111-1111-1111-111111111111"
          calls (atom [])
          graph-id (atom "graph-uuid")]
      (p/with-redefs [state/get-current-repo (constantly "test")
                      state/<invoke-db-worker
                      (fn [& args]
                        (swap! calls conj (vec args))
                        (case (first args)
                          :thread-api/get-page-route-info
                          (p/resolved
                           (when (= "Alias" (last args))
                             {:page-id 42
                              :page-uuid #uuid "22222222-2222-2222-2222-222222222222"
                              :page-title "Alias"
                              :alias-source-id 43
                              :alias-source-uuid source-uuid}))

                          :thread-api/get-journal-page-by-day
                          (p/resolved {:db/id 99})

                          (p/resolved nil)))
                      date/today-journal-day (constantly 20260707)
                      graph-handler/current-graph-id (fn [] @graph-id)
                      recent-handler/add-page-to-recent!
                      (fn [& args]
                        (swap! calls conj (into [:recent] args)))
                      state/sidebar-add-block!
                      (fn [& args]
                        (swap! calls conj (vec args)))
                      rfe/push-state
                      (fn [& args]
                        (swap! calls conj (into [:push-state] args)))]
        (-> (p/do!
             (route-handler/redirect-to-page! "Page name")
             (route-handler/redirect-to-page! "Page name" {:anchor "ls-block-block-uuid"})
             (reset! graph-id nil)
             (route-handler/redirect-to-page! "Page name")
             (reset! graph-id "graph-uuid")
             (route-handler/redirect-to-page! "Alias")
             (route-handler/sidebar-journals!))
            (p/then
             (fn []
               (is (= [[:thread-api/get-page-route-info "test" "Page name"]
                       [:push-state :page {:name "page name"} {:graph-id "graph-uuid"}]
                       [:thread-api/get-page-route-info "test" "Page name"]
                       [:push-state :page {:name "page name"}
                        {:anchor "ls-block-block-uuid"
                         :graph-id "graph-uuid"}]
                       [:thread-api/get-page-route-info "test" "Page name"]
                       [:push-state :page {:name "page name"} nil]
                       [:thread-api/get-page-route-info "test" "Alias"]
                       [:thread-api/get-page-route-info "test" source-uuid]
                       [:recent 43 false]
                       [:push-state :page {:name (str source-uuid)} {:graph-id "graph-uuid"}]
                       [:thread-api/get-journal-page-by-day "test" 20260707]
                       ["test" 99 :page]]
                      @calls))))
            (p/catch
             (fn [error]
               (is false (str error))))
            (p/finally done))))))

(deftest route-title-and-label-use-worker-test
  (async done
    (let [block-id #uuid "33333333-3333-3333-3333-333333333333"
          block-title "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
          page-route {:data {:name :page}
                      :path-params {:name "Page"}}
          block-route {:data {:name :page}
                       :path-params {:name (str block-id)}}
          document #js {:title ""
                        :body #js {:dataset #js {:page ""}}}
          previous-document (.-document js/global)
          previous-state (state/get-state)
          previous-worker @state/*db-worker
          calls (atom [])]
      (set! (.-document js/global) document)
      (state/swap-state! assoc :git/current-repo "test")
      (reset! state/*db-worker
              (fn [& args]
                (swap! calls conj (vec args))
                (p/resolved
                 (case (last args)
                   "Page" {:page-title "Page"}
                   "33333333-3333-3333-3333-333333333333" {:block-title block-title}
                   nil))))
      (-> (p/do!
           (route-handler/update-page-title! page-route)
           (route-handler/update-page-label! page-route)
           (route-handler/update-page-title! block-route))
          (p/then
           (fn []
             (is (= [[:thread-api/get-route-title "test" "Page"]
                     [:thread-api/get-route-title "test" "Page"]
                     [:thread-api/get-route-title "test" (str block-id)]]
                    (filterv #(= :thread-api/get-route-title (first %)) @calls)))
             (is (= "Page" (.-page (.-dataset (.-body document)))))
             (is (= (str (subs block-title 0 48) "...")
                    (.-title document)))))
          (p/catch
           (fn [error]
             (is false (str error))))
          (p/finally
           (fn []
             (state/replace-state! previous-state)
             (reset! state/*db-worker previous-worker)
             (set! (.-document js/global) previous-document)
             (done)))))))

(defn- page-route
  [page-name]
  {:data {:name :page}
   :path-params {:name page-name}})

(deftest stale-route-title-resolve-does-not-overwrite-current-page-test
  (async done
    (let [left-route (page-route "Left")
          current-route (page-route "Current")
          left-title (p/deferred)
          document #js {:title "stale"
                        :body #js {:dataset #js {:page ""}}}
          previous-document (.-document js/global)
          previous-state (state/get-state)
          previous-worker @state/*db-worker
          window-titles (atom [])]
      (set! (.-document js/global) document)
      (state/swap-state! assoc :git/current-repo "test")
      (reset! state/*db-worker
              (fn [_api _repo route-name]
                (case route-name
                  "Left" left-title
                  "Current" (p/resolved {:page-title "Current"})
                  (p/resolved nil))))
      (-> (p/with-redefs [ipc/ipc (fn [channel title]
                                    (when (= :set-window-title channel)
                                      (swap! window-titles conj title)))]
            (p/do!
             (do (route-handler/update-page-title! left-route) nil)
             (route-handler/update-page-title! current-route)
             (is (= "Current" (.-title document)))
             (p/resolve! left-title {:page-title "Left"})
             (p/delay 10)
             (is (= "Current" (.-title document))
                 "A stale Back/forward title resolve must not overwrite the current page.")
             (is (= ["Current"] (vec (distinct @window-titles))))))
          (p/catch
           (fn [error]
             (is false (str error))))
          (p/finally
           (fn []
             (state/replace-state! previous-state)
             (reset! state/*db-worker previous-worker)
             (set! (.-document js/global) previous-document)
             (done)))))))

(deftest set-route-match-updates-title-after-back-navigation-test
  (async done
    (let [left-route (page-route "Left")
          previous-route (page-route "Previous")
          document #js {:title ""
                        :body #js {:dataset #js {:page ""}}}
          previous-document (.-document js/global)
          previous-state (state/get-state)
          previous-worker @state/*db-worker]
      (set! (.-document js/global) document)
      (state/swap-state! assoc :git/current-repo "test")
      (reset! state/*db-worker
              (fn [_api _repo route-name]
                (p/resolved {:page-title route-name})))
      (-> (p/do!
           (route-handler/set-route-match! left-route)
           (p/delay 0)
           (is (= "Left" (.-title document)))
           ;; popstate / history.back uses the same on-navigate -> set-route-match! path
           (route-handler/set-route-match! previous-route)
           (p/delay 0)
           (is (= "Previous" (.-title document)))
           (route-handler/set-route-match! left-route)
           (p/delay 0)
           (is (= "Left" (.-title document))))
          (p/catch
           (fn [error]
             (is false (str error))))
          (p/finally
           (fn []
             (state/replace-state! previous-state)
             (reset! state/*db-worker previous-worker)
             (set! (.-document js/global) previous-document)
             (done)))))))
