(ns logseq.db-sync.worker-handler-sync-test
  (:require ["better-sqlite3" :as sqlite3]
            [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.snapshot :as snapshot]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.test-sql :as test-sql]
            [logseq.db-sync.worker.handler.sync :as sync-handler]
            [logseq.db-sync.worker.asset-link :as asset-link]
            [logseq.db-sync.worker.ws :as ws]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.page :as outliner-page]
            [logseq.outliner.property :as outliner-property]
            [promesa.core :as p]))

(def sqlite (if (find-ns 'nbb.core) (aget sqlite3 "default") sqlite3))

(defn- select-sql?
  [sql]
  (string/starts-with? (-> sql string/trim string/lower-case) "select"))

(defn- run-sql
  [^js stmt args]
  (.apply (.-run stmt) stmt (to-array args)))

(defn- all-sql
  [^js stmt args]
  (.apply (.-all stmt) stmt (to-array args)))

(defn- with-memory-sql
  [f]
  (let [db (new sqlite ":memory:" nil)
        sql #js {:_db db
                 :exec (fn [sql-str & args]
                         (let [stmt (.prepare db sql-str)]
                           (if (select-sql? sql-str)
                             (all-sql stmt args)
                             (do
                               (run-sql stmt args)
                               nil))))
                 :close (fn []
                          (.close db))}]
    (try
      (f sql)
      (finally
        (.close sql)))))

(defn- with-memory-sql-async [f]
  (let [db (new sqlite ":memory:" nil)
        sql #js {:_db db
                 :exec (fn [sql-str & args]
                         (let [stmt (.prepare db sql-str)]
                           (if (select-sql? sql-str)
                             (all-sql stmt args)
                             (do (run-sql stmt args) nil))))
                 :close (fn [] (.close db))}]
    (-> (f sql)
        (p/finally #(.close sql)))))

(defn- semantic-json-request [path method body]
  (js/Request. (str "http://localhost" path)
               (clj->js (cond-> {:method method
                                 :headers {"content-type" "application/json"}}
                          body (assoc :body (js/JSON.stringify (clj->js body)))))))

(defn- json-body [response]
  (p/let [text (.text response)]
    (js->clj (js/JSON.parse text) :keywordize-keys true)))

(deftest current-server-checksum-recomputes-after-old-server-cursor-advance-test
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            self #js {:sql sql :conn conn :schema-ready true}
            page-uuid (random-uuid)]
        (d/transact! conn
                     [{:block/uuid page-uuid
                       :block/name "before-downgrade"
                       :block/title "Before downgrade"}])
        (let [checksum-before (sync-handler/current-server-checksum self)
              checksum-t-before (storage/get-server-checksum-t sql)]
          ;; Model an old server: DB and cursor advance while the new metadata
          ;; keys are left untouched.
          (d/transact! conn
                       [[:db/add [:block/uuid page-uuid]
                         :block/title
                         "Changed by old server"]]
                       {:db-sync/skip-checksum-update? true})
          (is (> (storage/get-t sql) checksum-t-before))
          (is (= checksum-before (storage/get-server-checksum sql)))
          (let [checksum-after (sync-handler/current-server-checksum self)]
            (is (not= checksum-before checksum-after))
            (is (= (sync-checksum/recompute-server-checksum @conn)
                   checksum-after))
            (is (= (storage/get-t sql)
                   (storage/get-server-checksum-t sql)))))))))

(deftest checksum-response-repairs-same-cursor-persisted-drift-test
  (testing "a restarted worker must verify the DB instead of trusting checksum metadata at the same t"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              block-uuid (random-uuid)]
          (d/transact!
           conn
           [{:block/uuid page-uuid
             :block/name "same-cursor-drift"
             :block/title "Same cursor drift"}
            {:block/uuid block-uuid
             :block/title "content"
             :block/order "a0"
             :block/parent [:block/uuid page-uuid]
             :block/page [:block/uuid page-uuid]}])
          (let [current-t (storage/get-t sql)
                expected-legacy (sync-checksum/recompute-checksum @conn)
                expected-server (sync-checksum/recompute-server-checksum @conn)
                stale-legacy "aaaaaaaaaaaaaaaa"
                stale-server "bbbbbbbbbbbbbbbb"]
            (is (not= stale-legacy expected-legacy))
            (is (not= stale-server expected-server))
            (storage/set-checksum! sql stale-legacy)
            (storage/set-server-checksum! sql stale-server current-t)
            ;; Persisted graphs from the older deployed Worker have no
            ;; verification watermark even though their checksum cursor may
            ;; equal the graph cursor.
            (storage/delete-meta! sql :checksum-metadata-contract-version)
            (storage/delete-meta! sql :checksum-metadata-contract-t)
            (let [restarted-self #js {:sql sql
                                      :conn nil
                                      :schema-ready true}
                  fields (sync-handler/checksum-response-fields restarted-self)]
              (is (= expected-legacy (:checksum fields)))
              (is (= expected-server (:server-checksum fields)))
              (is (= expected-legacy (storage/get-checksum sql)))
              (is (= expected-server (storage/get-server-checksum sql)))
              (is (= current-t (storage/get-server-checksum-t sql))))))))))

(deftest tx-batch-migrates-persisted-drift-before-extending-checksum-test
  (testing "the first HTTP-style batch after upgrade repairs old metadata before applying new txs"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact!
                 conn
                 [{:block/uuid page-uuid
                   :block/name "batch-checksum-migration"
                   :block/title "Before migration"}])
              t-before (storage/get-t sql)
              _ (storage/set-checksum! sql "aaaaaaaaaaaaaaaa")
              _ (storage/set-server-checksum!
                 sql "bbbbbbbbbbbbbbbb" t-before)
              _ (storage/delete-meta!
                 sql :checksum-metadata-contract-version)
              _ (storage/delete-meta! sql :checksum-metadata-contract-t)
              tx-entry
              {:tx (protocol/tx->transit
                    [[:db/add [:block/uuid page-uuid]
                      :block/title
                      "After migration"]])
               :tx-id (random-uuid)
               :outliner-op :save-block}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              response
              (with-redefs [ws/broadcast! (fn [& _] nil)]
                (sync-handler/handle-tx-batch!
                 self nil [tx-entry] t-before))]
          (is (= "tx/batch/ok" (:type response)))
          (is (= (sync-checksum/recompute-checksum @conn)
                 (:checksum response))
              "the v1 field used by legacy clients stays strict")
          (is (= (sync-checksum/recompute-server-checksum @conn)
                 (:server-checksum response)))
          (is (storage/checksum-metadata-verified?
               sql
               (storage/get-t sql))))))))

(deftest legacy-large-title-marker-omits-v2-but-keeps-old-client-checksum-test
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            page-uuid (random-uuid)
            block-uuid (random-uuid)
            self #js {:sql sql :conn conn :schema-ready true}]
        (d/transact!
         conn
         [{:block/uuid page-uuid
           :block/name "legacy-large-title-page"
           :block/title "Legacy large title page"}
          {:block/uuid block-uuid
           :block/title ""
           :block/page [:block/uuid page-uuid]
           :block/parent [:block/uuid page-uuid]
           :logseq.property.sync/large-title-object
           {:asset-uuid "legacy-title"
            :asset-type "txt"}}])
        (let [fields (sync-handler/checksum-response-fields self)]
          (is (string? (:checksum fields))
              "the historical field remains available to old clients")
          (is (nil? (:checksum-version fields)))
          (is (nil? (:server-checksum fields))))))))

(deftest large-title-marker-state-is-bound-to-current-checksums-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   self #js {:sql sql :conn conn :schema-ready true}
                   page-uuid (random-uuid)
                   block-uuid (random-uuid)
                   marker {:asset-uuid "confirmed-large-title"
                           :asset-type "txt"
                           :payload-format "utf8-plain-v1"
                           :payload-digest-alg "sha256-v1"
                           :payload-digest (apply str (repeat 64 "a"))}
                   request
                   (js/Request.
                    "http://localhost/checksum/large-title-markers?graph-id=graph-1"
                    #js {:method "GET"})]
               (d/transact!
                conn
                [{:block/uuid page-uuid
                  :block/name "large-title-marker-state"
                  :block/title "Large title marker state"}
                 {:block/uuid block-uuid
                  :block/title ""
                  :block/page [:block/uuid page-uuid]
                  :block/parent [:block/uuid page-uuid]
                  :logseq.property.sync/large-title-object marker}])
               (->
                (p/let [response (sync-handler/handle-http self request)
                        body (json-body response)]
                  (is (= 200 (.-status response)))
                  (is (= (storage/get-t sql) (:t body)))
                  (is (= (sync-checksum/recompute-checksum @conn)
                         (:checksum body)))
                  (is (= sync-checksum/server-checksum-version
                         (:checksum-version body)))
                  (is (= (sync-checksum/recompute-server-checksum @conn)
                         (:server-checksum body)))
                  (is (= [{:block-uuid (str block-uuid)
                           :marker marker}]
                         (:large-title-markers body))))
                (p/then (fn [] (done)))
                (p/catch (fn [error]
                           (is false (str error))
                           (done)))))))))

(deftest semantic-create-page-delegates-to-outliner-and-broadcasts-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql :conn (storage/open-conn sql) :schema-ready true}
                   calls (atom [])
                   broadcasts (atom [])
                   page-id (random-uuid)
                   request (semantic-json-request "/semantic/pages?graph-id=graph-1" "POST" {:title "Inbox"})]
               (-> (p/with-redefs [outliner-page/create! (fn [conn title opts]
                                                           (swap! calls conj [conn title opts])
                                                           [title page-id])
                                   ws/broadcast! (fn [_ sender message]
                                                   (swap! broadcasts conj [sender message]))]
                     (p/let [response (sync-handler/handle-http self request)
                             body (json-body response)]
                       (is (= 201 (.-status response)))
                       (is (= "Inbox" (:title body)))
                       (is (= (str page-id) (:uuid body)))
                       (is (= 1 (count @calls)))
                       (is (= "Inbox" (second (first @calls))))
                       (is (= 1 (count @broadcasts)))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-delete-page-delegates-to-outliner-and-broadcasts-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql :conn (storage/open-conn sql) :schema-ready true}
                   page-id (random-uuid)
                   _ (d/transact! (.-conn self) [{:db/ident :logseq.class/Page}
                                                  {:block/uuid page-id :block/name "inbox" :block/title "Inbox"
                                                   :block/tags :logseq.class/Page}])
                   calls (atom [])
                   broadcasts (atom [])
                   request (semantic-json-request (str "/semantic/pages/" page-id "?graph-id=graph-1") "DELETE" nil)]
               (-> (p/with-redefs [outliner-page/delete! (fn [conn id]
                                                           (swap! calls conj [conn id]))
                                   ws/broadcast! (fn [_ sender message]
                                                   (swap! broadcasts conj [sender message]))]
                     (p/let [response (sync-handler/handle-http self request)]
                       (is (= 204 (.-status response)))
                       (is (= [[(.-conn self) page-id]] @calls))
                       (is (= 1 (count @broadcasts)))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-block-write-routes-delegate-to-outliner-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql :conn (storage/open-conn sql) :schema-ready true}
                   page-id (random-uuid)
                   block-id (random-uuid)
                   reference-id (random-uuid)
                   created-reference-id (random-uuid)
                   _ (d/transact! (.-conn self) [{:db/ident :logseq.class/Page}
                                                  {:block/uuid page-id :block/name "page" :block/title "Page"
                                                   :block/tags :logseq.class/Page}
                                                  {:block/uuid reference-id :block/name "reference" :block/title "Reference"
                                                   :block/tags :logseq.class/Page}
                                                  {:block/uuid block-id :block/title "Existing"
                                                   :block/page [:block/uuid page-id]
                                                   :block/parent [:block/uuid page-id]
                                                   :block/order "a0"}])
                   calls (atom [])
                   requests [(semantic-json-request (str "/semantic/blocks/" page-id "/children?graph-id=graph-1") "POST"
                                                    {:position "append"
                                                     :blocks [{:title (str "New block links to [[Reference]], [[New Reference]], and [["
                                                                           block-id "]]")
                                                               :children [{:title "Nested block links to [[Reference]]"}]}]})
                             (semantic-json-request (str "/semantic/blocks/" block-id "?graph-id=graph-1") "PATCH"
                                                    {:title "Edited block links to [[Reference]]"})
                             (semantic-json-request (str "/semantic/blocks/" block-id "?graph-id=graph-1") "DELETE" nil)]]
               (-> (p/with-redefs [outliner-core/insert-blocks! (fn [_ blocks target opts]
                                                                  (swap! calls conj [:insert blocks target opts])
                                                                  {:tx-data []})
                                   outliner-core/save-block! (fn [_ block & opts]
                                                               (swap! calls conj [:save block opts])
                                                               {:tx-data []})
                                   outliner-core/delete-blocks! (fn [_ blocks opts]
                                                                 (swap! calls conj [:delete blocks opts])
                                                                 {:tx-data []})
                                   outliner-page/create! (fn [conn title _]
                                                           (d/transact! conn [{:block/uuid created-reference-id
                                                                              :block/name "new reference"
                                                                              :block/title title
                                                                              :block/tags :logseq.class/Page}])
                                                           [title created-reference-id])
                                   ws/broadcast! (fn [& _] nil)]
                     (p/let [responses (p/all (map #(sync-handler/handle-http self %) requests))
                              insert-body (json-body (first responses))]
                       (is (= [201 200 204] (mapv #(.-status %) responses)))
                       (is (uuid? (some-> (get-in insert-body [:blocks 0 :children 0 :uuid]) uuid)))
                       (let [[[_ inserted-blocks _ _] [_ nested-blocks _ _]] (filter #(= :insert (first %)) @calls)
                             [_ saved-block _] (first (filter #(= :save (first %)) @calls))
                             expected-refs #{reference-id created-reference-id block-id}]
                         (is (= (str "New block links to [[" reference-id "]], [[" created-reference-id
                                     "]], and [[" block-id "]]")
                                (:block/title (first inserted-blocks))))
                         (is (= (set (map #(vector :block/uuid %) expected-refs))
                                (set (:block/refs (first inserted-blocks)))))
                         (is (= (str "Nested block links to [[" reference-id "]]")
                                (:block/title (first nested-blocks))))
                         (is (= #{[:block/uuid reference-id]} (set (:block/refs (first nested-blocks)))))
                         (is (= (str "Edited block links to [[" reference-id "]]")
                                (:block/title saved-block)))
                         (is (= #{[:block/uuid reference-id]} (set (:block/refs saved-block)))))
                       (is (= #{:insert :save :delete} (set (map first @calls))))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-read-routes-return-pages-page-tree-and-search-results-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   page-id (random-uuid)
                   block-id (random-uuid)
                   child-id (random-uuid)
                   hidden-id (random-uuid)
                   second-root-id (random-uuid)
                   _ (d/transact! conn [{:db/ident :logseq.class/Page}
                                        {:block/uuid page-id
                                         :block/name "inbox"
                                         :block/title "Inbox"
                                         :block/tags :logseq.class/Page}
                                        {:block/uuid block-id
                                         :block/title "Review roadmap"
                                         :block/page [:block/uuid page-id]
                                         :block/parent [:block/uuid page-id]
                                         :block/order "a0"}
                                        {:block/uuid child-id
                                         :block/title "Child block"
                                         :block/page [:block/uuid page-id]
                                         :block/parent [:block/uuid block-id]
                                         :block/order "a0"}
                                        {:block/uuid hidden-id
                                         :block/title "Hidden roadmap"
                                         :logseq.property/hide? true
                                         :block/page [:block/uuid page-id]
                                         :block/parent [:block/uuid page-id]
                                         :block/order "00"}
                                        {:block/uuid second-root-id
                                         :block/title "Second root"
                                         :block/page [:block/uuid page-id]
                                         :block/parent [:block/uuid page-id]
                                         :block/order "a1"}])
                   self #js {:sql sql :conn conn :schema-ready true}
                   requests [(semantic-json-request "/semantic/pages?graph-id=graph-1" "GET" nil)
                             (semantic-json-request (str "/semantic/pages/" page-id "/blocks?graph-id=graph-1&limit=1") "GET" nil)
                             (semantic-json-request "/semantic/search?graph-id=graph-1&q=roadmap&types=blocks" "GET" nil)
                             (semantic-json-request "/semantic/search?graph-id=graph-1&q=roadmap" "GET" nil)
                             (semantic-json-request "/semantic/search?graph-id=graph-1&q=roadmap&types=" "GET" nil)]]
               (-> (p/let [responses (p/all (map #(sync-handler/handle-http self %) requests))
                            bodies (p/all (map json-body responses))]
                     (is (= [200 200 200 200 200] (mapv #(.-status %) responses)) (pr-str bodies))
                     (is (= "Inbox" (get-in bodies [0 :blocks 0 :title])))
                     (is (= "Review roadmap" (get-in bodies [1 :blocks 0 :title])))
                     (is (= "Child block" (get-in bodies [1 :blocks 0 :children 0 :title])))
                     (is (string? (get-in bodies [1 :next-cursor])))
                     (doseq [index [2 3 4]]
                       (is (= (str block-id) (get-in bodies [index :results 0 :uuid])))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-move-blocks-moves-all-addressed-blocks-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   page-id (random-uuid)
                   block-ids [(random-uuid) (random-uuid)]
                   target-id (random-uuid)
                   _ (d/transact! conn
                                  (into [{:db/ident :logseq.class/Page}
                                         {:block/uuid page-id :block/name "page" :block/title "Page"
                                          :block/tags :logseq.class/Page}]
                                        (map-indexed
                                         (fn [index block-id]
                                           {:block/uuid block-id
                                            :block/title (str "Block " index)
                                            :block/page [:block/uuid page-id]
                                            :block/parent [:block/uuid page-id]
                                            :block/order (str "a" index)})
                                         (conj block-ids target-id))))
                   self #js {:sql sql :conn conn :schema-ready true}
                   calls (atom [])
                   valid-request (semantic-json-request "/semantic/block-moves?graph-id=graph-1" "POST"
                                                        {:block-ids (mapv str block-ids)
                                                         :target-id (str target-id)
                                                         :position "last-child"})
                   missing-request (semantic-json-request "/semantic/block-moves?graph-id=graph-1" "POST"
                                                          {:block-ids [(str (random-uuid))]
                                                           :target-id (str target-id)
                                                           :position "last-child"})]
               (-> (p/with-redefs [outliner-core/move-blocks! (fn [_ blocks target opts]
                                                                (swap! calls conj [blocks target opts]))
                                   ws/broadcast! (fn [& _] nil)]
                     (p/let [valid-response (sync-handler/handle-http self valid-request)
                              valid-body (json-body valid-response)
                              missing-response (sync-handler/handle-http self missing-request)]
                       (is (= 200 (.-status valid-response)))
                       (is (= (mapv str block-ids) (:uuids valid-body)))
                       (is (= 400 (.-status missing-response)))
                       (is (= 1 (count @calls)))
                       (let [[blocks target opts] (first @calls)]
                         (is (= block-ids (mapv :block/uuid blocks)))
                         (is (= target-id (:block/uuid target)))
                         (is (= {:sibling? false :bottom? true} opts)))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-collection-routes-use-cursor-pagination-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   pages (mapv (fn [title]
                                 {:block/uuid (random-uuid)
                                  :block/name (string/lower-case title)
                                  :block/title title
                                  :block/tags :logseq.class/Page})
                               ["Alpha" "中文页面" "日本語ページ"])
                   _ (d/transact! conn (into [{:db/ident :logseq.class/Page}] pages))
                   self #js {:sql sql :conn conn :schema-ready true}
                   first-request (semantic-json-request "/semantic/pages?graph-id=graph-1&limit=2" "GET" nil)]
               (-> (p/let [first-response (sync-handler/handle-http self first-request)
                            first-body (json-body first-response)
                            cursor (:next-cursor first-body)
                            second-response (sync-handler/handle-http
                                             self
                                             (semantic-json-request
                                              (str "/semantic/pages?graph-id=graph-1&limit=2&cursor="
                                                   (js/encodeURIComponent cursor))
                                              "GET" nil))
                            second-body (json-body second-response)]
                     (is (= 2 (count (:blocks first-body))))
                     (is (string? cursor))
                     (is (= 1 (count (:blocks second-body))))
                     (is (= #{"Alpha" "中文页面" "日本語ページ"}
                            (set (map :title (concat (:blocks first-body) (:blocks second-body))))))
                     (is (nil? (:next-cursor second-body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-list-routes-filter-by-created-and-updated-time-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   old-page-id (random-uuid)
                   new-page-id (random-uuid)
                   target-tag-id (random-uuid)
                   old-tag-id (random-uuid)
                   new-tag-id (random-uuid)
                   old-property-id (random-uuid)
                   new-property-id (random-uuid)
                   old-object-id (random-uuid)
                   new-object-id (random-uuid)
                   old-task-id (random-uuid)
                   new-task-id (random-uuid)
                   _ (d/transact!
                      conn
                      [{:block/uuid old-page-id :block/name "old timed page" :block/title "Old timed page"
                        :block/tags :logseq.class/Page :block/created-at 1000 :block/updated-at 1000}
                       {:block/uuid new-page-id :block/name "new timed page" :block/title "New timed page"
                        :block/tags :logseq.class/Page :block/created-at 3000 :block/updated-at 3000}
                       {:db/ident :user.class/TimeTarget :block/uuid target-tag-id
                        :block/name "time target" :block/title "Time target"
                        :block/tags :logseq.class/Tag :block/created-at 3000 :block/updated-at 3000}
                       {:db/ident :user.class/OldTimed :block/uuid old-tag-id
                        :block/name "old timed tag" :block/title "Old timed tag"
                        :block/tags :logseq.class/Tag :block/created-at 1000 :block/updated-at 1000}
                       {:db/ident :user.class/NewTimed :block/uuid new-tag-id
                        :block/name "new timed tag" :block/title "New timed tag"
                        :block/tags :logseq.class/Tag :block/created-at 3000 :block/updated-at 3000}
                       {:db/ident :user.property/old-timed :block/uuid old-property-id
                        :block/name "old timed property" :block/title "Old timed property"
                        :block/tags :logseq.class/Property :logseq.property/type :default
                        :db/cardinality :db.cardinality/one :block/created-at 1000 :block/updated-at 1000}
                       {:db/ident :user.property/new-timed :block/uuid new-property-id
                        :block/name "new timed property" :block/title "New timed property"
                        :block/tags :logseq.class/Property :logseq.property/type :default
                        :db/cardinality :db.cardinality/one :block/created-at 3000 :block/updated-at 3000}
                       {:block/uuid old-object-id :block/title "Old timed object"
                        :block/page [:block/uuid old-page-id] :block/parent [:block/uuid old-page-id]
                        :block/order "a0" :block/tags :user.class/TimeTarget
                        :block/created-at 1000 :block/updated-at 1000}
                       {:block/uuid new-object-id :block/title "New timed object"
                        :block/page [:block/uuid new-page-id] :block/parent [:block/uuid new-page-id]
                        :block/order "a0" :block/tags :user.class/TimeTarget
                        :block/created-at 3000 :block/updated-at 3000}
                       {:block/uuid old-task-id :block/title "Old timed task"
                        :block/page [:block/uuid old-page-id] :block/parent [:block/uuid old-page-id]
                        :block/order "a1" :block/tags :logseq.class/Task
                        :logseq.property/status :logseq.property/status.todo
                        :block/created-at 1000 :block/updated-at 1000}
                       {:block/uuid new-task-id :block/title "New timed task"
                        :block/page [:block/uuid new-page-id] :block/parent [:block/uuid new-page-id]
                        :block/order "a1" :block/tags :logseq.class/Task
                        :logseq.property/status :logseq.property/status.todo
                        :block/created-at 3000 :block/updated-at 3000}])
                   self #js {:sql sql :conn conn :schema-ready true}
                   paths ["/semantic/pages?graph-id=graph-1&created-after=2000"
                          "/semantic/tasks?graph-id=graph-1&updated-after=2000"
                          "/semantic/tags?graph-id=graph-1&created-after=2000"
                          (str "/semantic/tags/" target-tag-id "/objects?graph-id=graph-1&updated-after=2000")
                          "/semantic/properties?graph-id=graph-1&created-after=2000"
                          "/semantic/search?graph-id=graph-1&q=timed&types=blocks&updated-after=2000"]
                   keys [:blocks :tasks :tags :objects :properties :results]]
               (-> (p/let [responses (p/all (map #(sync-handler/handle-http
                                                   self (semantic-json-request % "GET" nil)) paths))
                            bodies (p/all (map json-body responses))
                            invalid-response (sync-handler/handle-http
                                              self (semantic-json-request
                                                    "/semantic/pages?graph-id=graph-1&created-after=week"
                                                    "GET" nil))]
                     (is (= [200 200 200 200 200 200] (mapv #(.-status %) responses)))
                     (doseq [[body response-key] (map vector bodies keys)]
                       (let [titles (set (map :title (get body response-key)))]
                         (is (some #(string/starts-with? % "New timed") titles)
                             (str response-key " " titles))
                         (is (not-any? #(string/starts-with? % "Old timed") titles)
                             (str response-key " " titles))))
                     (is (= 400 (.-status invalid-response))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-collection-route-rejects-invalid-cursor-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql :conn (storage/open-conn sql) :schema-ready true}
                   request (semantic-json-request "/semantic/pages?graph-id=graph-1&cursor=not-a-cursor" "GET" nil)]
               (-> (p/let [response (sync-handler/handle-http self request)
                            body (json-body response)]
                     (is (= 400 (.-status response)))
                     (is (= "invalid cursor" (:error body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-reverse-reference-routes-use-indexed-pagination-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   page-id (random-uuid)
                   tag-id (random-uuid)
                   hidden-id (random-uuid)
                   block-ids [(random-uuid) (random-uuid)]
                   _ (d/transact! conn [{:db/ident :logseq.class/Page}
                                        {:db/ident :logseq.class/Tag}
                                        {:block/uuid page-id :block/name "target" :block/title "Target"
                                         :block/tags :logseq.class/Page}
                                        {:block/uuid tag-id :block/name "project" :block/title "Project"
                                         :block/tags :logseq.class/Tag}
                                        {:block/uuid hidden-id :block/title "Hidden"
                                         :logseq.property/hide? true
                                         :block/tags [:block/uuid tag-id]
                                         :block/refs [:block/uuid page-id]}
                                        {:block/uuid (first block-ids) :block/title "Alpha"
                                         :block/tags [:block/uuid tag-id]
                                         :block/refs [:block/uuid page-id]}
                                        {:block/uuid (second block-ids) :block/title "Beta"
                                         :block/tags [:block/uuid tag-id]
                                         :block/refs [:block/uuid page-id]}])
                   self #js {:sql sql :conn conn :schema-ready true}
                   requests [(semantic-json-request
                              (str "/semantic/tags/" tag-id "/objects?graph-id=graph-1&limit=1") "GET" nil)
                             (semantic-json-request
                              (str "/semantic/pages/" page-id "/references?graph-id=graph-1&limit=1") "GET" nil)]]
               (-> (p/let [responses (p/all (map #(sync-handler/handle-http self %) requests))
                            bodies (p/all (map json-body responses))]
                     (is (= [200 200] (mapv #(.-status %) responses)))
                     (is (= ["Alpha"] (mapv :title (:objects (first bodies)))))
                     (is (= ["Alpha"] (mapv :title (:references (second bodies)))))
                     (is (every? string? (map :next-cursor bodies))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-collection-route-rejects-invalid-cursor-element-types-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql :conn (storage/open-conn sql) :schema-ready true}
                   cursor (js/btoa (js/JSON.stringify #js ["a" #js {}]))
                   request (semantic-json-request
                            (str "/semantic/pages?graph-id=graph-1&cursor=" (js/encodeURIComponent cursor))
                            "GET" nil)]
               (-> (p/let [response (sync-handler/handle-http self request)
                            body (json-body response)]
                     (is (= 400 (.-status response)))
                     (is (= "invalid cursor" (:error body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-page-routes-exclude-tags-and-properties-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   page-id (random-uuid)
                   tag-id (random-uuid)
                   property-id (random-uuid)
                   _ (d/transact! conn [{:db/ident :logseq.class/Page}
                                        {:db/ident :logseq.class/Tag}
                                        {:db/ident :logseq.class/Property}
                                        {:block/uuid page-id :block/name "page" :block/title "Page"
                                         :block/tags :logseq.class/Page}
                                        {:block/uuid tag-id :block/name "tag" :block/title "Tag"
                                         :block/tags :logseq.class/Tag}
                                        {:block/uuid property-id :block/name "property" :block/title "Property"
                                         :block/tags :logseq.class/Property}])
                   self #js {:sql sql :conn conn :schema-ready true}
                   requests [(semantic-json-request "/semantic/pages?graph-id=graph-1" "GET" nil)
                             (semantic-json-request (str "/semantic/pages/" tag-id "?graph-id=graph-1") "GET" nil)
                             (semantic-json-request (str "/semantic/pages/" property-id "?graph-id=graph-1") "GET" nil)]]
               (-> (p/let [responses (p/all (map #(sync-handler/handle-http self %) requests))
                            list-body (json-body (first responses))]
                     (is (= [200 404 404] (mapv #(.-status %) responses)))
                     (is (= ["Page"] (mapv :title (:blocks list-body)))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-set-block-property-resolves-class-values-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   page-id (random-uuid)
                   block-ids (repeatedly 4 random-uuid)
                   task (d/entity @conn :logseq.class/Task)
                   tags-property (d/entity @conn :block/tags)
                   _ (d/transact! conn
                                  (into [{:block/uuid page-id :block/name "page" :block/title "Page"
                                          :block/tags :logseq.class/Page
                                          :block/created-at 1 :block/updated-at 1}]
                                        (map-indexed
                                         (fn [index block-id]
                                           {:block/uuid block-id :block/title (str "Block " index)
                                            :block/page [:block/uuid page-id]
                                            :block/parent [:block/uuid page-id]
                                            :block/order (str "a" index)
                                            :block/created-at 1 :block/updated-at 1})
                                         block-ids)))
                   self #js {:sql sql :conn conn :schema-ready true}
                   property-id (:block/uuid tags-property)
                   values [(str (:block/uuid task)) "logseq.class/Task" "Task" [(str (:block/uuid task))]]
                   request-for (fn [block-id value]
                                 (semantic-json-request
                                  (str "/semantic/blocks/" block-id "/properties/" property-id
                                       "?graph-id=graph-1")
                                  "PUT" {:value value}))]
               (-> (p/let [response-1 (sync-handler/handle-http self (request-for (nth block-ids 0) (nth values 0)))
                            response-2 (sync-handler/handle-http self (request-for (nth block-ids 1) (nth values 1)))
                            response-3 (sync-handler/handle-http self (request-for (nth block-ids 2) (nth values 2)))
                            response-4 (sync-handler/handle-http self (request-for (nth block-ids 3) (nth values 3)))
                            invalid-response (sync-handler/handle-http
                                              self (request-for (first block-ids) "Missing Class"))
                            invalid-body (json-body invalid-response)]
                     (is (= [200 200 200 200]
                            (mapv #(.-status %) [response-1 response-2 response-3 response-4])))
                     (doseq [block-id block-ids]
                       (is (= #{:logseq.class/Task}
                              (set (map :db/ident (:block/tags (d/entity @conn [:block/uuid block-id])))))))
                     (is (= 400 (.-status invalid-response)))
                     (is (= "property value must resolve to an existing class by UUID, ident, or title"
                            (:error invalid-body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-batch-set-many-property-can-append-or-reset-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   page-id (random-uuid)
                   block-id (random-uuid)
                   project-id (random-uuid)
                   task (d/entity @conn :logseq.class/Task)
                   tags-property (d/entity @conn :block/tags)
                   _ (d/transact! conn
                                  [{:block/uuid page-id :block/name "page" :block/title "Page"
                                    :block/tags :logseq.class/Page
                                    :block/created-at 1 :block/updated-at 1}
                                   {:db/ident :user.class/Project
                                    :block/uuid project-id :block/name "project" :block/title "Project"
                                    :block/tags :logseq.class/Tag}
                                   {:block/uuid block-id :block/title "Block"
                                    :block/page [:block/uuid page-id]
                                    :block/parent [:block/uuid page-id]
                                    :block/order "a0" :block/tags :user.class/Project
                                    :block/created-at 1 :block/updated-at 1}])
                   self #js {:sql sql :conn conn :schema-ready true}
                   request-for (fn [body]
                                 (semantic-json-request
                                  "/semantic/block-properties/batch-set?graph-id=graph-1"
                                  "POST" body))
                   entry {:block-id (str block-id)
                          :property-id (str (:block/uuid tags-property))
                          :value [(str (:block/uuid task))]}]
               (-> (p/let [append-response (sync-handler/handle-http
                                            self (request-for {:entries [entry]}))
                            _ (is (= 200 (.-status append-response)))
                            _ (is (= #{:user.class/Project :logseq.class/Task}
                                     (set (map :db/ident
                                               (:block/tags (d/entity @conn [:block/uuid block-id]))))))
                            reset-response (sync-handler/handle-http
                                            self (request-for {:entries [entry]
                                                               :isResetExistingValues true}))]
                     (is (= 200 (.-status reset-response)))
                     (is (= #{:logseq.class/Task}
                            (set (map :db/ident
                                      (:block/tags (d/entity @conn [:block/uuid block-id])))))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-status-property-accepts-built-in-aliases-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   page-id (random-uuid)
                   block-ids (repeatedly 3 random-uuid)
                   status-property (d/entity @conn :logseq.property/status)
                   _ (d/transact! conn
                                  (into [{:block/uuid page-id :block/name "page" :block/title "Page"
                                          :block/tags :logseq.class/Page
                                          :block/created-at 1 :block/updated-at 1}]
                                        (map-indexed
                                         (fn [index block-id]
                                           {:block/uuid block-id :block/title (str "Block " index)
                                            :block/page [:block/uuid page-id]
                                            :block/parent [:block/uuid page-id]
                                            :block/order (str "a" index)
                                            :block/created-at 1 :block/updated-at 1})
                                         block-ids)))
                   self #js {:sql sql :conn conn :schema-ready true}
                   property-id (:block/uuid status-property)
                   single-request (semantic-json-request
                                   (str "/semantic/blocks/" (first block-ids) "/properties/"
                                        property-id "?graph-id=graph-1")
                                   "PUT" {:value "TODO"})
                   batch-request (semantic-json-request
                                  "/semantic/block-properties/batch-set?graph-id=graph-1"
                                  "POST" {:entries [{:block-id (str (second block-ids))
                                                     :property-id (str property-id)
                                                     :value "DONE"}]})
                   priority-request (semantic-json-request
                                     (str "/semantic/blocks/" (nth block-ids 2)
                                          "/properties/Priority?graph-id=graph-1")
                                     "PUT" {:value "urgent"})
                   invalid-property-request (semantic-json-request
                                             (str "/semantic/blocks/" (nth block-ids 2)
                                                  "/properties/Missing%20Property?graph-id=graph-1")
                                             "PUT" {:value "urgent"})]
               (-> (p/let [single-response (sync-handler/handle-http self single-request)
                            batch-response (sync-handler/handle-http self batch-request)
                            priority-response (sync-handler/handle-http self priority-request)
                            invalid-property-response (sync-handler/handle-http self invalid-property-request)
                            invalid-property-body (json-body invalid-property-response)]
                     (is (= [200 200 200]
                            (mapv #(.-status %) [single-response batch-response priority-response])))
                     (is (= [:logseq.property/status.todo :logseq.property/status.done]
                            (mapv #(-> (d/entity @conn [:block/uuid %])
                                       :logseq.property/status :db/ident)
                                  (take 2 block-ids))))
                     (is (= :logseq.property/priority.urgent
                            (-> (d/entity @conn [:block/uuid (nth block-ids 2)])
                                :logseq.property/priority :db/ident)))
                     (is (= 400 (.-status invalid-property-response)))
                     (is (= "property not found" (:error invalid-property-body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-property-routes-accept-ident-and-title-selectors-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   property (outliner-property/upsert-property!
                             conn nil {:logseq.property/type :default
                                       :db/cardinality :db.cardinality/one}
                             {:property-name "API estimate"})
                   property-ident (:db/ident property)
                   self #js {:sql sql :conn conn :schema-ready true}
                   get-request (fn [selector]
                                 (semantic-json-request
                                  (str "/semantic/properties/"
                                       (js/encodeURIComponent selector)
                                       "?graph-id=graph-1") "GET" nil))]
               (-> (p/let [ident-selector (str (namespace property-ident) "/" (name property-ident))
                            ident-response (sync-handler/handle-http self (get-request ident-selector))
                            ident-body (json-body ident-response)
                            title-response (sync-handler/handle-http self (get-request "API estimate"))
                            title-body (json-body title-response)]
                     (is (= 200 (.-status ident-response)))
                     (is (= ident-selector (:ident ident-body)))
                     (is (= 200 (.-status title-response)))
                     (is (= (:uuid ident-body) (:uuid title-body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-property-routes-reject-invalid-schema-and-built-in-delete-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   status-id (:block/uuid (d/entity @conn :logseq.property/status))
                   self #js {:sql sql :conn conn :schema-ready true}
                   invalid-create (semantic-json-request
                                   "/semantic/properties?graph-id=graph-1" "POST"
                                   {:title "Broken" :type "not-a-property-type"})
                   invalid-update (semantic-json-request
                                   (str "/semantic/properties/" status-id "?graph-id=graph-1") "PATCH"
                                   {:cardinality "not-a-cardinality"})
                   delete-built-in (semantic-json-request
                                    (str "/semantic/properties/" status-id "?graph-id=graph-1") "DELETE" nil)]
               (-> (p/let [create-response (sync-handler/handle-http self invalid-create)
                            update-response (sync-handler/handle-http self invalid-update)
                            delete-response (sync-handler/handle-http self delete-built-in)]
                     (is (= 400 (.-status create-response)))
                     (is (= 400 (.-status update-response)))
                     (is (= 400 (.-status delete-response)))
                     (is (some? (d/entity @conn :logseq.property/status))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-task-routes-create-and-list-db-tasks-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   page-id (random-uuid)
                   todo-id (random-uuid)
                   second-todo-id (random-uuid)
                   done-id (random-uuid)
                   hidden-id (random-uuid)
                   custom-status-id (random-uuid)
                   _ (d/transact! conn [{:block/uuid page-id :block/name "page" :block/title "Page"
                                         :block/tags :logseq.class/Page
                                         :block/created-at 1 :block/updated-at 1}
                                        {:block/uuid todo-id :block/title "Alpha todo"
                                         :block/tags :logseq.class/Task
                                         :logseq.property/status :logseq.property/status.todo
                                         :block/created-at 1 :block/updated-at 1}
                                        {:block/uuid second-todo-id :block/title "Beta todo"
                                         :block/tags :logseq.class/Task
                                         :logseq.property/status :logseq.property/status.todo
                                         :block/created-at 1 :block/updated-at 1}
                                        {:block/uuid done-id :block/title "Completed"
                                         :block/tags :logseq.class/Task
                                         :logseq.property/status :logseq.property/status.done
                                         :block/created-at 1 :block/updated-at 1}
                                        {:block/uuid hidden-id :block/title "Hidden todo"
                                         :block/tags :logseq.class/Task
                                         :logseq.property/status :logseq.property/status.todo
                                         :logseq.property/hide? true
                                         :block/created-at 1 :block/updated-at 1}])
                   _ (outliner-property/upsert-closed-value!
                      conn :logseq.property/status {:id custom-status-id :value "Blocked"})
                   self #js {:sql sql :conn conn :schema-ready true}
                   list-request (semantic-json-request
                                 "/semantic/tasks?graph-id=graph-1&status=todo&limit=1" "GET" nil)
                   create-request (semantic-json-request
                                   "/semantic/tasks?graph-id=graph-1" "POST"
                                   {:title "Ship task API" :page-id (str page-id)
                                    :status (str custom-status-id) :priority "high"})
                   invalid-request (semantic-json-request
                                    "/semantic/tasks?graph-id=graph-1" "POST"
                                    {:title "Invalid task" :page-id (str page-id)
                                     :status "not-a-status"})
                   status-property-id (:block/uuid (d/entity @conn :logseq.property/status))
                   property-request (semantic-json-request
                                     (str "/semantic/properties/" status-property-id "?graph-id=graph-1")
                                     "GET" nil)]
               (-> (p/let [list-response (sync-handler/handle-http self list-request)
                            list-body (json-body list-response)
                            create-response (sync-handler/handle-http self create-request)
                            create-body (json-body create-response)
                            invalid-response (sync-handler/handle-http self invalid-request)
                            invalid-body (json-body invalid-response)
                            property-response (sync-handler/handle-http self property-request)
                            property-body (json-body property-response)]
                     (is (= 200 (.-status list-response)))
                     (is (= ["Alpha todo"] (mapv :title (:tasks list-body))))
                     (is (= ["logseq.property/status.todo"]
                            (mapv #(get-in % [:status :ident]) (:tasks list-body))))
                     (is (string? (:next-cursor list-body)))
                     (is (= 201 (.-status create-response)))
                     (is (= "Ship task API" (:title create-body)))
                     (is (= (str custom-status-id) (get-in create-body [:status :uuid])))
                     (is (= "logseq.property/priority.high" (get-in create-body [:priority :ident])))
                     (let [created (d/entity @conn [:block/uuid (uuid (:uuid create-body))])]
                       (is (= #{:logseq.class/Task} (set (map :db/ident (:block/tags created)))))
                       (is (= custom-status-id
                              (:block/uuid (:logseq.property/status created))))
                       (is (= :logseq.property/priority.high
                              (:db/ident (:logseq.property/priority created)))))
                     (is (= 400 (.-status invalid-response)))
                     (is (= "invalid task status" (:error invalid-body)))
                     (is (= 200 (.-status property-response)))
                     (is (contains? (set (map :uuid (:choices property-body)))
                                    (str custom-status-id))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-create-task-uses-one-transaction-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   tx-reports (atom [])
                   self #js {:sql sql :conn conn :schema-ready true}
                   request (semantic-json-request
                            "/semantic/tasks?graph-id=graph-1" "POST"
                            {:title "Atomic task" :status "TODO" :priority "HIGH"
                             :scheduled 20260713 :deadline 20260714})]
               (d/listen! conn ::semantic-create-task-tx
                          #(swap! tx-reports conj %))
               (-> (p/let [response (sync-handler/handle-http self request)
                            body (json-body response)]
                     (d/unlisten! conn ::semantic-create-task-tx)
                     (is (= 201 (.-status response)))
                     (is (= 1 (count @tx-reports)))
                     (is (= 1 (count (d/datoms @conn :avet :block/journal-day))))
                     (is (= "logseq.property/status.todo" (get-in body [:status :ident])))
                     (is (= "logseq.property/priority.high" (get-in body [:priority :ident])))
                     (is (= 20260713 (:scheduled body)))
                     (is (= 20260714 (:deadline body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (d/unlisten! conn ::semantic-create-task-tx)
                              (is false (str error))
                              (done)))))))))

(deftest semantic-invalid-task-does-not-create-today-journal-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   self #js {:sql sql :conn conn :schema-ready true}
                   request (semantic-json-request
                            "/semantic/tasks?graph-id=graph-1" "POST"
                            {:title "Invalid task" :status "not-a-status"})]
               (-> (p/let [response (sync-handler/handle-http self request)
                            body (json-body response)]
                     (is (= 400 (.-status response)))
                     (is (= "invalid task status" (:error body)))
                     (is (empty? (d/datoms @conn :avet :block/journal-day))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-asset-get-returns-valid-temporary-link-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   asset-id (random-uuid)
                   env #js {"ASSET_LINK_SECRET" "test-asset-link-secret"}
                   _ (d/transact! conn [{:block/uuid asset-id
                                         :block/title "diagram.png"
                                         :logseq.property.asset/type "png"
                                         :logseq.property.asset/size 42}])
                   self #js {:sql sql :conn conn :schema-ready true :env env}
                   request (semantic-json-request
                            (str "/semantic/assets/" asset-id "?graph-id=graph-1") "GET" nil)]
               (-> (p/let [response (sync-handler/handle-http self request)
                            body (json-body response)
                            link-request (js/Request. (:url body))
                            valid? (asset-link/<valid-request? link-request env)]
                     (is (= 200 (.-status response)))
                     (is (= (str asset-id) (:uuid body)))
                     (is (true? valid?)))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-asset-list-and-upload-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   page-id (random-uuid)
                   old-asset-id (random-uuid)
                   puts (atom [])
                   deletes (atom [])
                   bucket #js {:put (fn [key payload options]
                                      (swap! puts conj [key payload options])
                                      (p/resolved #js {}))
                              :delete (fn [key]
                                        (swap! deletes conj key)
                                        (p/resolved nil))}
                   env #js {"LOGSEQ_SYNC_ASSETS" bucket}
                   _ (d/transact! conn [{:block/uuid page-id :block/name "page" :block/title "Page"
                                         :block/tags :logseq.class/Page
                                         :block/created-at 1 :block/updated-at 1}
                                        {:block/uuid old-asset-id :block/title "Old asset"
                                         :logseq.property.asset/type "png"
                                         :logseq.property.asset/size 1
                                         :logseq.property.asset/checksum "old"
                                         :block/tags :logseq.class/Asset
                                         :block/created-at 1000 :block/updated-at 1000}])
                   self #js {:sql sql :conn conn :schema-ready true :env env}
                   checksum (apply str (repeat 64 "a"))
                   upload-request (fn [filename title size file-checksum]
                                    (js/Request.
                                     (str "http://localhost/semantic/assets?graph-id=graph-1"
                                          "&file-name=" (js/encodeURIComponent filename)
                                          "&title=" (js/encodeURIComponent title)
                                          "&page-id=" page-id
                                          "&size=" size
                                          "&checksum=" file-checksum)
                                     #js {:method "POST"
                                          :headers #js {"content-type" "application/octet-stream"}
                                          :body (js/Blob. #js [(js/Uint8Array. #js [1 2 3 4])])}))]
               (-> (p/let [create-response (sync-handler/handle-http
                                            self (upload-request "photo.png" "Photo" 4 checksum))
                            create-body (json-body create-response)
                            list-response (sync-handler/handle-http
                                           self (semantic-json-request
                                                 "/semantic/assets?graph-id=graph-1&created-after=2000"
                                                 "GET" nil))
                            list-body (json-body list-response)
                            duplicate-response (sync-handler/handle-http
                                                self (upload-request "copy.png" "Copy" 4 checksum))
                            failed-response (p/with-redefs
                                              [outliner-core/insert-blocks!
                                               (fn [& _] (throw (js/Error. "insert failed")))]
                                              (sync-handler/handle-http
                                               self (upload-request "broken.pdf" "Broken" 4
                                                                    (apply str (repeat 64 "b")))))
                            oversized-response (sync-handler/handle-http
                                                self (upload-request "huge.zip" "Huge" 104857601
                                                                     (apply str (repeat 64 "c"))))]
                     (is (= 201 (.-status create-response)))
                     (is (= "Photo" (:title create-body)))
                     (is (= "png" (:type create-body)))
                     (is (= 4 (:size create-body)))
                     (is (= 64 (count (:checksum create-body))))
                     (is (= 2 (count @puts)))
                     (when-let [[key payload options] (first @puts)]
                       (is (= (str "graph-1/" (:uuid create-body) ".png") key))
                       (is (fn? (.-getReader payload)))
                       (is (= "application/octet-stream" (aget (aget options "httpMetadata") "contentType")))
                       (is (= (:checksum create-body) (aget (aget options "customMetadata") "checksum"))))
                     (when (string? (:uuid create-body))
                       (let [asset (d/entity @conn [:block/uuid (uuid (:uuid create-body))])]
                         (is (= page-id (:block/uuid (:block/page asset))))
                         (is (= #{:logseq.class/Asset} (set (map :db/ident (:block/tags asset)))))
                         (is (= {:checksum checksum :type "png"}
                                (:logseq.property.asset/remote-metadata asset)))))
                     (is (= 200 (.-status list-response)))
                     (is (= [(:uuid create-body)] (mapv :uuid (:assets list-body))))
                     (is (= 409 (.-status duplicate-response)))
                     (is (= 500 (.-status failed-response)))
                     (is (= 1 (count @deletes)))
                     (is (some-> (first @deletes) (string/ends-with? ".pdf") true?))
                     (is (= 413 (.-status oversized-response)))
                     (is (= 2 (count @puts))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest semantic-base64-asset-upload-decodes-before-r2-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (sqlite-export/create-conn)
                   page-id (random-uuid)
                   uploaded (atom [])
                   bucket #js {:put (fn [_key payload _options]
                                      (p/let [buffer (.arrayBuffer (js/Response. payload))]
                                        (swap! uploaded conj (js/Uint8Array. buffer))
                                        #js {}))
                              :delete (fn [_] (p/resolved nil))}
                   _ (d/transact! conn [{:block/uuid page-id :block/name "page" :block/title "Page"
                                         :block/tags :logseq.class/Page}])
                   self #js {:sql sql :conn conn :schema-ready true
                             :env #js {"LOGSEQ_SYNC_ASSETS" bucket}}
                   request (fn [body checksum]
                             (js/Request.
                              (str "http://localhost/semantic/assets?graph-id=graph-1"
                                   "&file-name=image.png&page-id=" page-id
                                   "&size=4&checksum=" checksum "&encoding=base64")
                              #js {:method "POST"
                                   :headers #js {"content-type" "text/plain"}
                                   :body body}))]
               (-> (p/let [response (sync-handler/handle-http
                                     self (request "AQIDBA==" (apply str (repeat 64 "d"))))
                            invalid-response (sync-handler/handle-http
                                              self (request "not base64!" (apply str (repeat 64 "e"))))]
                     (is (= 201 (.-status response)))
                     (let [^js payload (first @uploaded)]
                       (is (= 4 (.-byteLength payload)))
                       (is (= 1 (aget payload 0)))
                       (is (= 2 (aget payload 1)))
                       (is (= 3 (aget payload 2)))
                       (is (= 4 (aget payload 3))))
                     (is (= 400 (.-status invalid-response)))
                     (is (= 1 (count @uploaded))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(defn- seeded-rng
  [seed0]
  (let [state (atom (bit-or (long seed0) 0))]
    (fn []
      (let [s (swap! state
                     (fn [x]
                       (let [x (bit-xor x (bit-shift-left x 13))
                             x (bit-xor x (bit-shift-right x 17))
                             x (bit-xor x (bit-shift-left x 5))]
                         (bit-or x 0))))]
        (/ (double (unsigned-bit-shift-right s 0)) 4294967296.0)))))

(defn- rand-int*
  [rng n]
  (js/Math.floor (* (rng) n)))

(defn- pick-rand
  [rng coll]
  (when (seq coll)
    (nth coll (rand-int* rng (count coll)))))

(defn- block-uuids-by-predicate
  [db pred]
  (->> (d/datoms db :avet :block/uuid)
       (map :e)
       distinct
       (keep (fn [eid]
               (let [ent (d/entity db eid)
                     uuid (:block/uuid ent)]
                 (when (and uuid (pred ent))
                   (str uuid)))))
       vec))

(defn- page-uuids
  [db]
  (block-uuids-by-predicate db #(some? (:block/name %))))

(defn- non-page-block-uuids
  [db]
  (block-uuids-by-predicate db #(nil? (:block/name %))))

(defn- all-block-uuids
  [db]
  (block-uuids-by-predicate db (constantly true)))

(defn- gen-server-tx-entry
  [rng db step]
  (let [page-ids (page-uuids db)
        block-ids (non-page-block-uuids db)
        all-ids (all-block-uuids db)
        op (rand-int* rng 6)]
    (case op
      ;; Explicit empty rebase no-op
      0 {:tx (protocol/tx->transit [])
         :outliner-op :rebase}

      ;; stale retract in :fix should be sanitized away (often no-op)
      1 {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid (random-uuid)]]])
         :outliner-op :fix}

      ;; update title
      2 (if-let [target-id (pick-rand rng all-ids)]
          {:tx (protocol/tx->transit [[:db/add [:block/uuid (uuid target-id)]
                                       :block/title
                                       (str "server-fuzz-title-" step)]])
           :outliner-op :save-block}
          {:tx (protocol/tx->transit [])
           :outliner-op :rebase})

      ;; move block parent/page
      3 (if (and (seq block-ids) (seq page-ids))
          (let [child (pick-rand rng block-ids)
                parent (or (pick-rand rng block-ids)
                           child)
                page (pick-rand rng page-ids)]
            {:tx (protocol/tx->transit [[:db/add [:block/uuid (uuid child)]
                                         :block/parent
                                         [:block/uuid (uuid parent)]]
                                       [:db/add [:block/uuid (uuid child)]
                                        :block/page
                                        [:block/uuid (uuid page)]]])
             :outliner-op :move-blocks})
          {:tx (protocol/tx->transit [])
           :outliner-op :rebase})

      ;; add block
      4 (if (seq page-ids)
          (let [page (pick-rand rng page-ids)
                parent (or (pick-rand rng block-ids)
                           page)
                new-uuid (random-uuid)]
            {:tx (protocol/tx->transit [{:db/id -1
                                         :block/uuid new-uuid
                                         :block/title (str "server-fuzz-add-" step)
                                         :block/order (str "a" (rand-int* rng 9))
                                         :block/parent [:block/uuid (uuid parent)]
                                         :block/page [:block/uuid (uuid page)]}])
             :outliner-op :insert-blocks})
          {:tx (protocol/tx->transit [])
           :outliner-op :rebase})

      ;; delete non-page block
      (if-let [victim (pick-rand rng block-ids)]
        {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid (uuid victim)]]])
         :outliner-op :delete-blocks}
        {:tx (protocol/tx->transit [])
         :outliner-op :rebase}))))

(defn- empty-sql []
  #js {:exec (fn [& _] #js [])})

(defn- make-server-self
  []
  (let [sql (test-sql/make-sql)
        conn (storage/open-conn sql)
        self #js {:sql sql
                  :conn conn
                  :schema-ready true}]
    {:sql sql
     :conn conn
     :self self}))

(defn- apply-entries!
  [^js self entries]
  (loop [t-before (storage/get-t (.-sql self))
         remaining entries]
    (if-let [entry (first remaining)]
      (let [response (with-redefs [ws/broadcast! (fn [& _] nil)]
                       (sync-handler/handle-tx-batch! self nil [entry] t-before))]
        (is (= "tx/batch/ok" (:type response)))
        (recur (:t response) (next remaining)))
      t-before)))

(defn- apply-batch-with-t!
  [^js self t-before entries]
  (with-redefs [ws/broadcast! (fn [& _] nil)]
    (sync-handler/handle-tx-batch! self nil entries t-before)))

(defn- assert-server-checksum-step!
  [sql conn prev-t prev-checksum response label]
  (let [stored-checksum (storage/get-checksum sql)
        recomputed-checksum (sync-checksum/recompute-checksum @conn)
        stored-server-checksum (storage/get-server-checksum sql)
        recomputed-server-checksum
        (sync-checksum/recompute-server-checksum @conn)
        new-t (storage/get-t sql)
        accepted? (= "tx/batch/ok" (:type response))
        advanced? (> new-t prev-t)]
    (is (= new-t (:t response))
        (str label " response.t should match storage t"))
    (is (= recomputed-server-checksum stored-server-checksum)
        (str label " versioned checksum should equal full recompute"))
    (is (= new-t (storage/get-server-checksum-t sql))
        (str label " versioned checksum watermark should match t"))
    (if accepted?
      (if advanced?
        (do
          (is (string? stored-checksum)
              (str label " stored checksum missing after mutation"))
          (is (= recomputed-checksum stored-checksum)
              (str label " stored checksum should equal full recompute")))
        (is (= prev-checksum stored-checksum)
            (str label " checksum changed on no-op accepted batch")))
      (do
        (is (= "tx/reject" (:type response))
            (str label " expected tx rejection"))
        (is (= prev-t new-t)
            (str label " rejected tx should not change t"))
        (is (= prev-checksum stored-checksum)
            (str label " rejected tx should not change checksum"))))
    {:accepted? accepted?
     :advanced? advanced?
     :t new-t
     :checksum stored-checksum}))

(defn- block-placement
  [db block-uuid]
  (let [ent (d/pull db [{:block/parent [:block/uuid :block/name]}
                        {:block/page [:block/uuid :block/name]}
                        :block/order]
                    [:block/uuid block-uuid])]
    {:parent-uuid (get-in ent [:block/parent :block/uuid])
     :parent-page? (boolean (get-in ent [:block/parent :block/name]))
     :page-uuid (get-in ent [:block/page :block/uuid])
     :order (:block/order ent)}))

(defn- no-op-rebase-entry
  []
  {:tx (protocol/tx->transit [])
   :outliner-op :rebase})

(defn- tx-entry-applicable?
  [db {:keys [tx]}]
  (try
    (d/with db (protocol/transit->tx tx))
    true
    (catch :default _
      false)))

(defn- tx-entries-applicable?
  [db entries]
  (every? (partial tx-entry-applicable? db) entries))

(defn- make-insert-command
  [rng db step]
  (let [pages (page-uuids db)
        blocks (non-page-block-uuids db)]
    (if-let [page-id (pick-rand rng pages)]
      (let [parent-id (or (pick-rand rng blocks) page-id)
            new-uuid (random-uuid)
            entry {:tx (protocol/tx->transit [{:db/id -1
                                               :block/uuid new-uuid
                                               :block/title (str "rand-insert-" step)
                                               :block/order (str "a" step "-" (rand-int* rng 9))
                                               :block/parent [:block/uuid (uuid parent-id)]
                                               :block/page [:block/uuid (uuid page-id)]}])
                   :outliner-op :insert-blocks}
            inverse {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid new-uuid]]])
                     :outliner-op :delete-blocks}]
        {:forward [entry]
         :inverse [inverse]
         :undoable? true})
      {:forward [(no-op-rebase-entry)]
       :undoable? false})))

(defn- make-title-command
  [rng db step]
  (if-let [target-id (pick-rand rng (all-block-uuids db))]
    (let [target-uuid (uuid target-id)
          old-title (or (:block/title (d/pull db [:block/title] [:block/uuid target-uuid])) "")
          new-title (str "rand-title-" step)]
      {:forward [{:tx (protocol/tx->transit [[:db/add [:block/uuid target-uuid]
                                              :block/title
                                              new-title]])
                  :outliner-op :save-block}]
       :inverse [{:tx (protocol/tx->transit [[:db/add [:block/uuid target-uuid]
                                              :block/title
                                              old-title]])
                  :outliner-op :save-block}]
       :undoable? true})
    {:forward [(no-op-rebase-entry)]
     :undoable? false}))

(defn- make-move-like-command
  [db target-id new-parent-id new-page-id new-order outliner-op]
  (let [target-uuid (uuid target-id)
        placement (block-placement db target-uuid)]
    (if (and (:parent-uuid placement) (:page-uuid placement))
      {:forward [{:tx (protocol/tx->transit [[:db/add [:block/uuid target-uuid]
                                              :block/parent
                                              [:block/uuid (uuid new-parent-id)]]
                                            [:db/add [:block/uuid target-uuid]
                                             :block/page
                                             [:block/uuid (uuid new-page-id)]]
                                            [:db/add [:block/uuid target-uuid]
                                             :block/order
                                             new-order]])
                  :outliner-op outliner-op}]
       :inverse [{:tx (protocol/tx->transit [[:db/add [:block/uuid target-uuid]
                                              :block/parent
                                              [:block/uuid (:parent-uuid placement)]]
                                            [:db/add [:block/uuid target-uuid]
                                             :block/page
                                             [:block/uuid (:page-uuid placement)]]
                                            [:db/add [:block/uuid target-uuid]
                                             :block/order
                                             (:order placement)]])
                  :outliner-op outliner-op}]
       :undoable? true}
      {:forward [(no-op-rebase-entry)]
       :undoable? false})))

(defn- make-random-move-command
  [rng db step]
  (let [blocks (non-page-block-uuids db)
        pages (page-uuids db)]
    (if (and (seq blocks) (seq pages))
      (let [target-id (pick-rand rng blocks)
            parent-candidates (vec (remove #{target-id} (concat blocks pages)))
            parent-id (or (pick-rand rng parent-candidates) (pick-rand rng pages))
            page-id (pick-rand rng pages)]
        (make-move-like-command db target-id parent-id page-id (str "m" step) :move-blocks))
      {:forward [(no-op-rebase-entry)]
       :undoable? false})))

(defn- make-random-indent-command
  [rng db step]
  (let [blocks (non-page-block-uuids db)
        pages (page-uuids db)]
    (if (and (seq blocks) (seq pages))
      (let [child-id (pick-rand rng blocks)
            parent-candidates (vec (remove #{child-id} blocks))
            parent-id (or (pick-rand rng parent-candidates)
                          (pick-rand rng pages))
            page-id (pick-rand rng pages)]
        (make-move-like-command db child-id parent-id page-id (str "i" step) :indent-blocks))
      {:forward [(no-op-rebase-entry)]
       :undoable? false})))

(defn- make-random-outdent-command
  [rng db step]
  (let [candidates (->> (non-page-block-uuids db)
                        (keep (fn [block-id]
                                (let [placement (block-placement db (uuid block-id))]
                                  (when (and (:parent-uuid placement)
                                             (not (:parent-page? placement))
                                             (:page-uuid placement))
                                    block-id))))
                        vec)]
    (if-let [child-id (pick-rand rng candidates)]
      (let [child-uuid (uuid child-id)
            placement (block-placement db child-uuid)
            parent-placement (block-placement db (:parent-uuid placement))]
        (if-let [grandparent-uuid (:parent-uuid parent-placement)]
          (make-move-like-command db child-id (str grandparent-uuid) (str (:page-uuid placement)) (str "o" step "-" (rand-int* rng 9)) :outdent-blocks)
          {:forward [(no-op-rebase-entry)]
           :undoable? false}))
      {:forward [(no-op-rebase-entry)]
       :undoable? false})))

(defn- make-random-delete-entry
  [rng db]
  (if-let [victim-id (pick-rand rng (non-page-block-uuids db))]
    {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid (uuid victim-id)]]])
     :outliner-op :delete-blocks}
    (no-op-rebase-entry)))

(defn- make-stale-add-after-delete-conflict
  [rng db step]
  (let [blocks (non-page-block-uuids db)
        pages (page-uuids db)]
    (when (and (seq blocks) (seq pages))
      (let [victim-id (pick-rand rng blocks)
            page-id (pick-rand rng pages)
            stale-child-uuid (random-uuid)]
        {:delete-entry {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid (uuid victim-id)]]])
                        :outliner-op :delete-blocks}
         :stale-add-entry {:tx (protocol/tx->transit [{:db/id -1
                                                       :block/uuid stale-child-uuid
                                                       :block/title (str "stale-child-" step)
                                                       :block/order (str "c" step)
                                                       :block/parent [:block/uuid (uuid victim-id)]
                                                       :block/page [:block/uuid (uuid page-id)]}])
                           :outliner-op :insert-blocks}}))))

(defn- request-url
  ([]
   (request-url "/sync/graph-1/snapshot/download-v2?graph-id=graph-1"))
  ([path]
   (let [request (js/Request. (str "http://localhost" path)
                              #js {:method "GET"})]
     {:request request
      :url (js/URL. (.-url request))})))

(defn- passthrough-compression-stream-constructor []
  (js* "function(_format){ return new TransformStream(); }"))

(deftest snapshot-download-uses-gzip-encoding-when-compression-supported-test
  (async done
         (let [sql (empty-sql)
               conn (d/create-conn db-schema/schema)
               self #js {:env #js {}
                         :conn conn
                         :schema-ready true
                         :sql sql}
               {:keys [request url]} (request-url)
               original-compression-stream (.-CompressionStream js/globalThis)
               restore! #(aset js/globalThis "CompressionStream" original-compression-stream)]
           (aset js/globalThis
                 "CompressionStream"
                 (passthrough-compression-stream-constructor))
           (-> (p/let [resp (sync-handler/handle {:self self
                                                  :request request
                                                  :url url
                                                  :route {:handler :sync/snapshot-download-v2}})
                       text (.text resp)
                       body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                 (is (= 200 (.-status resp)))
                 (is (= true (:ok body)))
                 (is (= "stream/graph-1.snapshot" (:key body)))
                 (is (string/starts-with?
                      (:url body)
                      "http://localhost/sync/graph-1/snapshot/stream-v2?download-id="))
                 (is (= 0 (:t body)))
                 (is (= "gzip" (:content-encoding body))))
               (p/then (fn []
                         (restore!)
                         (done)))
               (p/catch (fn [error]
                          (restore!)
                          (is false (str error))
                          (done)))))))

(deftest snapshot-download-omits-gzip-encoding-when-disabled-in-env-test
  (async done
         (let [sql (empty-sql)
               conn (d/create-conn db-schema/schema)
               self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                         :conn conn
                         :schema-ready true
                         :sql sql}
               {:keys [request url]} (request-url)]
           (-> (p/let [resp (sync-handler/handle {:self self
                                                  :request request
                                                  :url url
                                                  :route {:handler :sync/snapshot-download-v2}})
                       text (.text resp)
                       body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                 (is (= 200 (.-status resp)))
                 (is (= true (:ok body)))
                 (is (not (contains? body :content-encoding)))
                 (done))
               (p/then (fn []
                         nil))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest legacy-snapshot-download-keeps-v1-live-stream-shape-test
  (async done
         (let [sql (empty-sql)
               self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                         :conn nil
                         :schema-ready true
                         :sql sql}
               {:keys [request url]}
               (request-url
                "/sync/graph-1/snapshot/download?graph-id=graph-1")]
           (-> (p/with-redefs
                 [sync-handler/<ready-for-sync?
                  (fn [_self _graph-id] (p/resolved true))]
                 (p/let [resp
                         (sync-handler/handle
                          {:self self
                           :request request
                           :url url
                           :route {:handler :sync/snapshot-download}})
                         body (json-body resp)]
                   (is (= 200 (.-status resp)))
                   (is (= {:ok true
                           :key "stream/graph-1.snapshot"
                           :url "http://localhost/sync/graph-1/snapshot/stream"}
                          body))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest snapshot-v2-stream-requires-valid-frozen-download-id-test
  (async done
         (let [sql (empty-sql)
               self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                         :conn nil
                         :schema-ready true
                         :sql sql}
               {:keys [request url]}
               (request-url
                "/sync/graph-1/snapshot/stream-v2?graph-id=graph-1")]
           (-> (p/let [resp (sync-handler/handle
                             {:self self
                              :request request
                              :url url
                              :route {:handler :sync/snapshot-stream-v2}})
                       body (json-body resp)]
                 (is (= 410 (.-status resp)))
                 (is (= {:error "snapshot download expired"} body)))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally done)))))

(deftest snapshot-v2-stream-expiry-deletes-frozen-export-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [download-id "expired-download"
                   _ (common/sql-exec
                      sql
                      (str "insert into snapshot_downloads "
                           "(download_id, t, checksum, row_count, created_at) "
                           "values (?, ?, ?, ?, ?)")
                      download-id 7 "0000000000000000" 1 0)
                   _ (common/sql-exec
                      sql
                      (str "insert into snapshot_kvs_exports "
                           "(download_id, addr, content, addresses) "
                           "values (?, ?, ?, ?)")
                      download-id 1 "snapshot-row" nil)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :conn nil
                             :schema-ready true
                             :sql sql}
                   {:keys [request url]}
                   (request-url
                    (str "/sync/graph-1/snapshot/stream-v2"
                         "?graph-id=graph-1&download-id=" download-id))]
               (-> (p/let [response
                           (sync-handler/handle
                            {:self self
                             :request request
                             :url url
                             :route {:handler :sync/snapshot-stream-v2}})
                           body (json-body response)
                           downloads
                           (common/get-sql-rows
                            (common/sql-exec
                             sql
                             "select download_id from snapshot_downloads"))
                           exports
                           (common/get-sql-rows
                            (common/sql-exec
                             sql
                             "select download_id from snapshot_kvs_exports"))]
                     (is (= 410 (.-status response)))
                     (is (= {:error "snapshot download expired"} body))
                     (is (empty? downloads))
                     (is (empty? exports)))
                   (p/catch (fn [error]
                              (is false (str error))))
                   (p/finally done)))))))

(deftest snapshot-download-repairs-checksum-before-freezing-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   page-uuid (random-uuid)
                   block-uuid (random-uuid)
                   _ (d/transact!
                      conn
                      [{:block/uuid page-uuid
                        :block/name "snapshot-checksum-repair"
                        :block/title "Snapshot checksum repair"}
                       {:block/uuid block-uuid
                        :block/title "content"
                        :block/order "a0"
                        :block/parent [:block/uuid page-uuid]
                        :block/page [:block/uuid page-uuid]}])
                   current-t (storage/get-t sql)
                   expected-checksum
                   (sync-checksum/recompute-checksum @conn)
                   _ (storage/set-checksum! sql "aaaaaaaaaaaaaaaa")
                   _ (storage/set-server-checksum!
                      sql "bbbbbbbbbbbbbbbb" current-t)
                   _ (storage/delete-meta!
                      sql :checksum-metadata-contract-version)
                   _ (storage/delete-meta!
                      sql :checksum-metadata-contract-t)
                   self #js {:env
                             #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :conn nil
                             :schema-ready true
                             :sql sql}
                   {:keys [request url]} (request-url)]
               (-> (p/with-redefs
                     [sync-handler/<ready-for-sync?
                      (fn [_self _graph-id] (p/resolved true))]
                     (p/let [metadata-response
                             (sync-handler/handle
                              {:self self
                               :request request
                               :url url
                               :route {:handler
                                       :sync/snapshot-download-v2}})
                             metadata (json-body metadata-response)
                             stream-response
                             (sync-handler/handle-http
                              self
                              (js/Request. (:url metadata)
                                           #js {:method "GET"}))
                             buffer (.arrayBuffer stream-response)
                             rows
                             (snapshot/finalize-framed-buffer
                              (js/Uint8Array. buffer))
                             restored-checksum
                             (with-memory-sql
                               (fn [restored-sql]
                                 (storage/init-schema! restored-sql)
                                 (doseq [[addr content addresses] rows]
                                   (common/sql-exec
                                    restored-sql
                                    (str "insert or replace into kvs "
                                         "(addr, content, addresses) "
                                         "values (?, ?, ?)")
                                    addr content addresses))
                                 (let [restored-conn
                                       (storage/open-conn restored-sql)]
                                   (sync-checksum/recompute-checksum
                                    @restored-conn))))]
                       (is (= 200 (.-status metadata-response)))
                       (is (= expected-checksum (:checksum metadata)))
                       (is (= expected-checksum restored-checksum)
                           "downloaded DB state must match its advertised checksum")
                       (is (= (count rows) (:row-count metadata)))
                       (is (= expected-checksum
                              (storage/get-checksum sql)))
                       (is (storage/checksum-metadata-verified?
                            sql current-t))))
                   (p/catch (fn [error]
                              (is false (str error))))
                   (p/finally done)))))))

(deftest snapshot-download-stream-is-frozen-at-metadata-watermark-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (common/sql-exec sql
                              "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                              1 "snapshot-row" nil)
             (storage/set-t! sql 7)
             (storage/set-checksum! sql "1111111111111111")
             ;; This fixture exercises snapshot freezing, not checksum
             ;; migration, and its hand-written row is not a Datascript store.
             (storage/mark-checksum-metadata-verified! sql 7)
             (let [self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :conn nil
                             :schema-ready true
                             :sql sql}
                   {:keys [request url]} (request-url)]
               (-> (p/with-redefs [sync-handler/<ready-for-sync?
                                    (fn [_self _graph-id] (p/resolved true))]
                     (p/let [metadata-response
                             (sync-handler/handle
                              {:self self
                               :request request
                               :url url
                               :route {:handler :sync/snapshot-download-v2}})
                             metadata (json-body metadata-response)
                             _ (common/sql-exec
                                sql
                                "update kvs set content = ? where addr = ?"
                                "live-row-after-metadata" 1)
                             _ (common/sql-exec
                                sql
                                "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                                2 "new-live-row" nil)
                             stream-response
                             (sync-handler/handle-http
                              self
                              (js/Request. (:url metadata) #js {:method "GET"}))
                             buffer (.arrayBuffer stream-response)
                             payload (js/Uint8Array. buffer)
                             rows (snapshot/finalize-framed-buffer payload)
                             active-downloads
                             (common/get-sql-rows
                              (common/sql-exec
                               sql
                               "select download_id from snapshot_downloads"))]
                       (is (= 200 (.-status metadata-response)))
                       (is (= 7 (:t metadata)))
                       (is (= "1111111111111111" (:checksum metadata)))
                       (is (= 1 (:row-count metadata)))
                       (is (= [[1 "snapshot-row" nil]] rows)
                           "the stream must not include writes committed after metadata")
                       (is (empty? active-downloads)
                           "a fully consumed stream must release its frozen export")))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest snapshot-download-capacity-is-bounded-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :conn nil
                             :schema-ready true
                             :sql sql}
                   {:keys [request url]} (request-url)
                   download! #(sync-handler/handle
                               {:self self
                                :request request
                                :url url
                                :route {:handler :sync/snapshot-download-v2}})]
               (-> (p/with-redefs [sync-handler/<ready-for-sync?
                                    (fn [_self _graph-id] (p/resolved true))]
                     (p/let [first-response (download!)
                             second-response (download!)
                             third-response (download!)
                             third-body (json-body third-response)]
                       (is (= 200 (.-status first-response)))
                       (is (= 200 (.-status second-response)))
                       (is (= 429 (.-status third-response)))
                       (is (= {:error "snapshot download busy; retry later"}
                              third-body))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest snapshot-download-metadata-cleans-expired-capacity-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (doseq [download-id ["expired-1" "expired-2"]]
               (common/sql-exec
                sql
                (str "insert into snapshot_downloads "
                     "(download_id, t, checksum, row_count, created_at) "
                     "values (?, ?, ?, ?, ?)")
                download-id 7 "0000000000000000" 1 0)
               (common/sql-exec
                sql
                (str "insert into snapshot_kvs_exports "
                     "(download_id, addr, content, addresses) "
                     "values (?, ?, ?, ?)")
                download-id 1 "stale" nil))
             ;; Keep this capacity-only fixture from materializing an empty
             ;; Datascript store while verifying checksum metadata.
             (storage/mark-checksum-metadata-verified! sql 0)
             (let [self #js {:env
                             #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :conn nil
                             :schema-ready true
                             :sql sql}
                   {:keys [request url]} (request-url)]
               (-> (p/with-redefs
                     [sync-handler/<ready-for-sync?
                      (fn [_self _graph-id] (p/resolved true))]
                     (p/let [response
                             (sync-handler/handle
                              {:self self
                               :request request
                               :url url
                               :route
                               {:handler :sync/snapshot-download-v2}})
                             downloads
                             (common/get-sql-rows
                              (common/sql-exec
                               sql
                               "select download_id from snapshot_downloads"))
                             exports
                             (common/get-sql-rows
                              (common/sql-exec
                               sql
                               "select download_id from snapshot_kvs_exports"))]
                       (is (= 200 (.-status response)))
                       (is (= 1 (count downloads)))
                       (is (empty? exports))))
                   (p/catch (fn [error]
                              (is false (str error))))
                   (p/finally done)))))))

(deftest snapshot-download-cancel-releases-frozen-export-idempotently-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (common/sql-exec sql
                              "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                              1 "snapshot-row" nil)
             (let [self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :conn nil
                             :schema-ready true
                             :sql sql}
                   {:keys [request url]} (request-url)]
               (-> (p/with-redefs [sync-handler/<ready-for-sync?
                                    (fn [_self _graph-id] (p/resolved true))]
                     (p/let [metadata-response
                             (sync-handler/handle
                              {:self self
                               :request request
                               :url url
                               :route {:handler :sync/snapshot-download-v2}})
                             metadata (json-body metadata-response)
                             stream-url (js/URL. (:url metadata))
                             download-id (.get (.-searchParams stream-url)
                                               "download-id")
                             cancel-url
                             (str "http://localhost/sync/graph-1/snapshot/download-v2"
                                  "?graph-id=graph-1&download-id="
                                  (js/encodeURIComponent download-id))
                             cancel-request
                             (js/Request. cancel-url #js {:method "DELETE"})
                             cancel-response
                             (sync-handler/handle
                              {:self self
                               :request cancel-request
                               :url (js/URL. cancel-url)
                               :route
                               {:handler :sync/snapshot-download-v2-cancel}})
                             cancel-again-response
                             (sync-handler/handle
                              {:self self
                               :request cancel-request
                               :url (js/URL. cancel-url)
                               :route
                               {:handler :sync/snapshot-download-v2-cancel}})
                             expired-response
                             (sync-handler/handle
                              {:self self
                               :request (js/Request. (:url metadata))
                               :url stream-url
                               :route {:handler :sync/snapshot-stream-v2}})
                             download-rows
                             (common/get-sql-rows
                              (common/sql-exec
                               sql
                               "select download_id from snapshot_downloads"))
                             export-rows
                             (common/get-sql-rows
                              (common/sql-exec
                               sql
                               "select download_id from snapshot_kvs_exports"))]
                       (is (= 200 (.-status metadata-response)))
                       (is (= 200 (.-status cancel-response)))
                       (is (= 200 (.-status cancel-again-response)))
                       (is (= 410 (.-status expired-response)))
                       (is (empty? download-rows))
                       (is (empty? export-rows))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest snapshot-download-stream-route-returns-framed-kvs-rows-test
  (async done
         (let [rows [[1 "row-1" nil]
                     [2 "row-2" nil]]
               sql (empty-sql)
               conn (d/create-conn db-schema/schema)
               self #js {:env #js {}
                         :conn conn
                         :schema-ready true
                         :sql sql}
               {:keys [request]} (request-url "/sync/graph-1/snapshot/stream?graph-id=graph-1")
               original-compression-stream (.-CompressionStream js/globalThis)
               restore! #(aset js/globalThis "CompressionStream" original-compression-stream)]
           (aset js/globalThis
                 "CompressionStream"
                 (passthrough-compression-stream-constructor))
           (-> (p/with-redefs [sync-handler/fetch-snapshot-kvs-rows (fn [_sql last-addr _limit]
                                                                      (if (neg? last-addr) rows []))
                               sync-handler/snapshot-row-count (fn [_sql] (count rows))]
                 (p/let [resp (sync-handler/handle-http self request)
                         encoding (.get (.-headers resp) "content-encoding")
                         content-type (.get (.-headers resp) "content-type")
                         buf (.arrayBuffer resp)
                         payload (js/Uint8Array. buf)
                         rows (snapshot/finalize-framed-buffer payload)
                         addrs (mapv first rows)]
                 (is (= 200 (.-status resp)))
                 (is (= "gzip" encoding))
                 (is (= "application/transit+json" content-type))
                 (is (= 2 (count rows)))
                 (is (= (sort addrs) addrs))
                 (is (every? (fn [[addr content _addresses]]
                               (and (int? addr)
                                    (string? content)))
                             rows)))
                 (is (= [[1 "row-1" nil]
                         [2 "row-2" nil]]
                        rows)))
               (p/then (fn []
                         (restore!)
                         (done)))
               (p/catch (fn [error]
                          (restore!)
                          (is false (str error))
                          (done)))))))

(deftest snapshot-download-stream-route-returns-uncompressed-framed-kvs-rows-when-disabled-in-env-test
  (async done
         (let [rows [[1 "row-1" nil]
                     [2 "row-2" nil]]
               sql (empty-sql)
               conn (d/create-conn db-schema/schema)
               self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                         :conn conn
                         :schema-ready true
                         :sql sql}
               {:keys [request]} (request-url "/sync/graph-1/snapshot/stream?graph-id=graph-1")]
           (-> (p/with-redefs [sync-handler/fetch-snapshot-kvs-rows (fn [_sql last-addr _limit]
                                                                      (if (neg? last-addr) rows []))
                               sync-handler/snapshot-row-count (fn [_sql] (count rows))]
                 (p/let [resp (sync-handler/handle-http self request)
                         encoding (.get (.-headers resp) "content-encoding")
                         content-type (.get (.-headers resp) "content-type")
                         buf (.arrayBuffer resp)
                         payload (js/Uint8Array. buf)
                         rows (snapshot/finalize-framed-buffer payload)
                         addrs (mapv first rows)]
                   (is (= 200 (.-status resp)))
                   (is (nil? encoding))
                   (is (= "application/transit+json" content-type))
                   (is (= 2 (count rows)))
                   (is (= (sort addrs) addrs))
                   (is (= [[1 "row-1" nil]
                           [2 "row-2" nil]]
                          rows))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(defn- drain-snapshot-frame-payloads
  [rows]
  (loop [pending [(vec rows)]
         payloads []]
    (if-let [{:keys [payload pending]} (#'sync-handler/next-snapshot-frame pending)]
      (recur pending (conj payloads payload))
      payloads)))

(deftest snapshot-download-frame-payloads-bound-multi-row-frames-test
  (let [large-content (apply str (repeat 600000 "x"))
        rows [[1 large-content nil]
              [2 large-content nil]]
        payloads (drain-snapshot-frame-payloads rows)]
    (is (= 2 (count payloads)))
    (is (every? #(<= (.-byteLength %) (* 1024 1024)) payloads))
    (is (= rows (vec (mapcat snapshot/decode-rows payloads)))))

  (testing "a single legacy oversized row remains atomic and readable"
    (let [oversized-content (apply str (repeat 1100000 "x"))
          row [1 oversized-content nil]
          payloads (drain-snapshot-frame-payloads [row])]
      (is (= 1 (count payloads)))
      (is (= [row] (snapshot/decode-rows (first payloads)))))))

(deftest next-snapshot-frame-does-not-eagerly-encode-pending-splits-test
  (let [large-content (apply str (repeat 600000 "x"))
        rows [[1 large-content nil]
              [2 large-content nil]]
        encode-rows snapshot/encode-rows
        encode-count (atom 0)]
    (with-redefs [snapshot/encode-rows
                  (fn [batch]
                    (swap! encode-count inc)
                    (encode-rows batch))]
      (let [{:keys [payload pending]}
            (#'sync-handler/next-snapshot-frame [rows])]
        ;; One attempt for the oversized two-row batch and one for the first
        ;; split. The second split remains unencoded until the next pull.
        (is (= 2 @encode-count))
        (is (= [(second rows)] (first pending)))
        (is (= [(first rows)] (snapshot/decode-rows payload)))))))

(deftest ensure-schema-fallback-validates-existing-schema-test
  (async done
         (let [self #js {:sql (empty-sql)}
               schema-validations (atom 0)
               {:keys [request url]} (request-url "/sync/graph-1/pull?graph-id=graph-1&since=0")]
           (-> (p/with-redefs [storage/init-schema! (fn [_]
                                                      (throw (js/Error. "ddl rejected")))
                               storage/schema-ready? (fn [_]
                                                       (swap! schema-validations inc)
                                                       true)
                               storage/fetch-tx-since (fn [_ _] [])
                               storage/get-t (fn [_] 7)
                               sync-handler/current-checksum (fn [_] "checksum-ok")
                               sync-handler/current-server-checksum
                               (fn [_] "server-checksum-ok")]
                 (p/let [resp (sync-handler/handle {:self self
                                                    :request request
                                                    :url url
                                                    :route {:handler :sync/pull}})
                         text (.text resp)
                         body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                   (is (= 200 (.-status resp)))
                   (is (= 7 (:t body)))
                   (is (= "checksum-ok" (:checksum body)))
                   (is (= sync-checksum/server-checksum-version
                          (:checksum-version body)))
                   (is (= "server-checksum-ok" (:server-checksum body)))
                   (is (= 1 @schema-validations))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest sync-repair-blocks-http-route-is-not-found-test
  (let [{:keys [self]} (make-server-self)
        {:keys [request]} (request-url "/sync/graph-1/repair/blocks?graph-id=graph-1")]
    (is (= 404 (.-status (sync-handler/handle-http self request))))))

(deftest tx-batch-rejects-stale-lookup-entity-updates-test
  (testing "stale lookup-ref entity updates reject the tx batch"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          missing-uuid (random-uuid)
          created-uuid (random-uuid)
          tx-data [[:db/add [:block/uuid missing-uuid] :block/title "stale" 1]
                   [:db/add [:block/uuid missing-uuid] :block/updated-at 1773188050934 1]
                   [:db/add "temp-1" :block/uuid created-uuid 2]
                   [:db/add "temp-1" :block/title "ok" 2]]
          tx-entry {:tx (protocol/tx->transit tx-data)
                    :outliner-op :save-block}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] 0))]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= 0 (:t response)))
      (is (= [missing-uuid] (:missing-block-uuids response)))
      (is (nil? (d/entity @conn [:block/uuid created-uuid])))
      (is (nil? (d/entity @conn [:block/uuid missing-uuid])))
      (let [pull-response (sync-handler/pull-response self 0)]
        (is (= "pull/ok" (:type pull-response)))
        (is (empty? (:txs pull-response)))))))

(deftest tx-batch-adds-request-context-to-transact-meta-test
  (testing "graph id and client revision should be present in transact failure diagnostics"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          tx-metas (atom [])
          tx-entry {:tx (protocol/tx->transit [[:db/add -1 :block/title "context"]])
                    :outliner-op :save-block}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (d/listen! conn ::capture-request-context-tx-meta
                                (fn [tx-report]
                                  (swap! tx-metas conj (:tx-meta tx-report))))
                     (try
                       (sync-handler/handle-tx-batch!
                        self
                        nil
                        [tx-entry]
                        0
                        {:graph-id "graph-context"
                         :client-revision "revision-context"})
                       (finally
                         (d/unlisten! conn ::capture-request-context-tx-meta))))]
      (is (= "tx/batch/ok" (:type response)))
      (is (some #(= {:op :apply-client-tx
                     :outliner-op :save-block
                     :graph-id "graph-context"
                     :client-revision "revision-context"}
                    (select-keys % [:op :outliner-op :graph-id :client-revision]))
                @tx-metas)))))

(deftest tx-batch-rejects-move-blocks-when-target-is-missing-test
  (testing "move-blocks against a block missing on the server should request client repair"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          page-uuid (random-uuid)
          parent-uuid (random-uuid)
          missing-block-uuid (random-uuid)
          _ (d/transact! conn [{:block/uuid page-uuid
                                :block/name "stale-move-page"
                                :block/title "stale-move-page"}
                               {:block/uuid parent-uuid
                                :block/title "existing-parent"
                                :block/order "a0"
                                :block/parent [:block/uuid page-uuid]
                                :block/page [:block/uuid page-uuid]}])
          t-before (storage/get-t sql)
          checksum-before (storage/get-checksum sql)
          tx-id (random-uuid)
          tx-entry {:tx-id tx-id
                    :tx (protocol/tx->transit
                         [[:db/retract [:block/uuid missing-block-uuid]
                           :block/parent
                           [:block/uuid page-uuid]
                           537062408]
                          [:db/add [:block/uuid missing-block-uuid]
                           :block/parent
                           [:block/uuid parent-uuid]
                           537062408]
                          [:db/retract [:block/uuid missing-block-uuid]
                           :block/order
                           "a0"
                           537062408]
                          [:db/add [:block/uuid missing-block-uuid]
                           :block/order
                           "a3"
                           537062408]])
                    :outliner-op :move-blocks}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= t-before (:t response)))
      (is (= tx-id (:failed-tx-id response)))
      (is (= [missing-block-uuid] (:missing-block-uuids response)))
      (is (= checksum-before (storage/get-checksum sql)))
      (is (empty? (storage/fetch-tx-since sql t-before)))
      (is (empty? @changed-messages)))))

(deftest tx-batch-delete-blocks-ignores-redundant-updates-and-deletes-descendants-test
  (testing "delete-blocks should retract current descendants even when tx-data omits them"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          page-uuid (random-uuid)
          parent-uuid (random-uuid)
          child-uuid (random-uuid)
          property-value-uuid (random-uuid)
          now 1783043128375
          _ (d/transact! conn [{:db/ident :user.property/sync-report}
                               {:block/uuid page-uuid
                                :block/name "delete-descendants-page"
                                :block/title "delete-descendants-page"
                                :block/created-at now
                                :block/updated-at now}
                               {:block/uuid parent-uuid
                                :block/title "parent"
                                :block/parent [:block/uuid page-uuid]
                                :block/page [:block/uuid page-uuid]
                                :block/order "a0"
                                :block/created-at now
                                :block/updated-at now}
                               {:block/uuid child-uuid
                                :block/title "child"
                                :block/parent [:block/uuid parent-uuid]
                                :block/page [:block/uuid page-uuid]
                                :block/order "a1"
                                :block/created-at now
                                :block/updated-at now}
                               {:block/uuid property-value-uuid
                                :block/title "property value"
                                :block/parent [:block/uuid child-uuid]
                                :block/page [:block/uuid page-uuid]
                                :block/order "a4"
                                :logseq.property/created-from-property :user.property/sync-report
                                :block/created-at now
                                :block/updated-at now}])
          t-before (storage/get-t sql)
          tx-entry {:tx (protocol/tx->transit
                         [[:db/add [:block/uuid child-uuid]
                           :block/parent
                           [:block/uuid page-uuid]
                           537866038]
                          [:db/add [:block/uuid property-value-uuid]
                           :block/updated-at
                           (inc now)
                           537866038]
                          [:db/retractEntity [:block/uuid parent-uuid]]
                          [:db/retractEntity [:block/uuid child-uuid]]])
                    :outliner-op :delete-blocks}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
      (is (nil? (d/entity @conn [:block/uuid child-uuid])))
      (is (nil? (d/entity @conn [:block/uuid property-value-uuid]))))))

(deftest tx-batch-keeps-created-by-ref-lookup-payload-test
  (testing "created-by lookup payload is preserved for save-block tx"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          page-uuid (random-uuid)
          missing-user-uuid (random-uuid)
          missing-user-ref [:block/uuid missing-user-uuid]
          tx-entry {:tx (protocol/tx->transit [[:db/add -1 :block/uuid page-uuid]
                                               [:db/add -1 :block/name "created-by-sanitize-page"]
                                               [:db/add -1 :block/title "created-by-sanitize-page"]
                                               [:db/add -1 :logseq.property/created-by-ref missing-user-ref]])
                    :outliner-op :save-block}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] 0))
          page (d/entity @conn [:block/uuid page-uuid])]
      (is (= "tx/batch/ok" (:type response)))
      (is (= 1 (:t response)))
      (is (some? page))
      (is (= "created-by-sanitize-page" (:block/title page)))
      (is (= missing-user-ref (:logseq.property/created-by-ref page))))))

(deftest tx-batch-rejects-missing-page-ref-lookups-test
  (testing "missing page refs/tags lookup refs reject create-page tx"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          page-uuid (random-uuid)
          missing-ref-uuid (random-uuid)
          missing-tag-uuid (random-uuid)
          missing-user-uuid (random-uuid)
          tx-entry {:tx (protocol/tx->transit [[:db/add -1 :block/uuid page-uuid]
                                               [:db/add -1 :block/name "optional-ref-sanitize-page"]
                                               [:db/add -1 :block/title "optional-ref-sanitize-page"]
                                               [:db/add -1 :block/refs [:block/uuid missing-ref-uuid]]
                                               [:db/add -1 :block/tags [:block/uuid missing-tag-uuid]]
                                               [:db/add -1 :logseq.property/created-by-ref [:block/uuid missing-user-uuid]]])
                    :outliner-op :create-page}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] 0))
          page (d/entity @conn [:block/uuid page-uuid])]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= 0 (:t response)))
      (is (nil? page)))))

(deftest tx-batch-rejects-while-snapshot-upload-is-in-progress-test
  (let [sql (test-sql/make-sql)
        conn (d/create-conn db-schema/schema)
        self #js {:sql sql
                  :conn conn
                  :schema-ready true}
        tx-data [[:db/add -1 :block/title "blocked"]]
        tx-entry {:tx (protocol/tx->transit tx-data)
                  :outliner-op :save-block}
        response (with-redefs [storage/get-meta (fn [_ k]
                                                  (when (= :snapshot-uploading? k)
                                                    "true"))]
                   (sync-handler/handle-tx-batch! self nil [tx-entry] 0))]
    (is (= "tx/reject" (:type response)))
    (is (= "snapshot upload in progress" (:reason response)))))

(deftest tx-batch-applies-db-migration-entry-test
  (testing "db migration entries apply with migration transact semantics"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          tx-entry {:tx (protocol/tx->transit [[:db/add -1 :block/title "migration-only"]])
                    :outliner-op :db-migrate}
          tx-metas (atom [])
          _ (d/listen! conn ::capture-db-migrate-tx-meta
                       (fn [tx-report]
                         (swap! tx-metas conj (:tx-meta tx-report))))
          response (try
                     (with-redefs [ws/broadcast! (fn [& _] nil)]
                       (sync-handler/handle-tx-batch! self nil [tx-entry] 0))
                     (finally
                       (d/unlisten! conn ::capture-db-migrate-tx-meta)))]
      (is (= "tx/batch/ok" (:type response)))
      (is (= 1 (:t response)))
      (is (some (fn [tx-meta]
                  (and (:db-migrate? tx-meta)
                       (:skip-validate-db? tx-meta)))
                @tx-metas))
      (is (= "migration-only"
             (:block/title (first (d/q '[:find [(pull ?e [:block/title]) ...]
                                    :where [?e :block/title "migration-only"]]
                                  @conn))))))))

(defn- large-block-insert-tx
  [page-uuid block-count]
  (vec
   (mapcat (fn [idx]
             (let [block-uuid (random-uuid)
                   eid (str block-uuid)]
               [[:db/add eid :block/uuid block-uuid idx]
                [:db/add eid :block/title (str "large-op-block-" idx) idx]
                [:db/add eid :block/page [:block/uuid page-uuid] idx]
                [:db/add eid :block/parent [:block/uuid page-uuid] idx]
                [:db/add eid :block/order "a0" idx]
                [:db/add eid :block/created-at idx idx]
                [:db/add eid :block/updated-at idx idx]]))
           (range block-count))))

(defn- block-title-prefix-count
  [db prefix]
  (->> (d/datoms db :avet :block/title)
       (filter (fn [datom]
                 (string/starts-with? (:v datom) prefix)))
       count))

(deftest tx-batch-applies-large-entry-in-ordered-chunks-test
  (testing "large tx entries are executed as ordered chunks while preserving final state"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-op-page"
                                    :block/title "large-op-page"}])
              t-before (storage/get-t sql)
              block-count 1200
              tx-data (large-block-insert-tx page-uuid block-count)
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              tx-report-count (atom 0)
              response (try
                         (d/listen! conn ::large-entry-chunks
                                    (fn [_tx-report]
                                      (swap! tx-report-count inc)))
                         (with-redefs [sync-checksum/recompute-checksum
                                       (fn [_db]
                                         (throw (ex-info "large tx should update checksum incrementally"
                                                         {:type :test/full-checksum-recompute})))
                                       ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))
                         (finally
                           (d/unlisten! conn ::large-entry-chunks)))]
          (is (= "tx/batch/ok" (:type response)))
          (is (> @tx-report-count 1))
          (is (= (+ t-before @tx-report-count) (:t response)))
          (is (= @tx-report-count (count (storage/fetch-tx-since sql t-before))))
          (is (= block-count
                  (block-title-prefix-count @conn "large-op-block-"))))))))

(deftest tx-batch-applies-large-entry-with-negative-tempids-in-ordered-chunks-test
  (testing "large tx entries with tempids are still accepted and chunked safely"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-tempid-page"
                                    :block/title "large-tempid-page"}])
              t-before (storage/get-t sql)
              tx-data (vec
                       (mapcat (fn [idx]
                                 (let [eid (- -1 idx)]
                                   [[:db/add eid :block/uuid (random-uuid)]
                                    [:db/add eid :block/title (str "large-tempid-block-" idx)]
                                    [:db/add eid :block/page [:block/uuid page-uuid]]
                                    [:db/add eid :block/parent [:block/uuid page-uuid]]
                                    [:db/add eid :block/order "a0"]
                                    [:db/add eid :block/created-at idx]
                                    [:db/add eid :block/updated-at idx]]))
                               (range 300)))
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              tx-report-count (atom 0)
              response (try
                         (d/listen! conn ::large-tempid-entry-chunks
                                    (fn [_tx-report]
                                      (swap! tx-report-count inc)))
                         (with-redefs [ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))
                         (finally
                           (d/unlisten! conn ::large-tempid-entry-chunks)))]
          (is (= "tx/batch/ok" (:type response)))
          (is (> @tx-report-count 1))
          (is (= (+ t-before @tx-report-count) (:t response)))
          (is (= @tx-report-count (count (storage/fetch-tx-since sql t-before))))
          (is (= 300 (block-title-prefix-count @conn "large-tempid-block-"))))))))

(deftest tx-batch-applies-large-entry-with-dependent-blocks-across-chunks-test
  (testing "large tx chunking preserves ordered parent-before-child dependencies"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-dependency-page"
                                    :block/title "large-dependency-page"}])
              t-before (storage/get-t sql)
              parent-uuid (random-uuid)
              child-uuid (random-uuid)
              parent-eid "large-dependency-parent"
              child-eid "large-dependency-child"
              filler-tx (large-block-insert-tx page-uuid 70)
              parent-tx [[:db/add parent-eid :block/uuid parent-uuid]
                         [:db/add parent-eid :block/title "large-dependency-parent"]
                         [:db/add parent-eid :block/page [:block/uuid page-uuid]]
                         [:db/add parent-eid :block/parent [:block/uuid page-uuid]]
                         [:db/add parent-eid :block/order "z0"]
                         [:db/add parent-eid :block/created-at 1]
                         [:db/add parent-eid :block/updated-at 1]]
              child-tx [[:db/add child-eid :block/uuid child-uuid]
                        [:db/add child-eid :block/title "large-dependency-child"]
                        [:db/add child-eid :block/page [:block/uuid page-uuid]]
                        [:db/add child-eid :block/parent [:block/uuid parent-uuid]]
                        [:db/add child-eid :block/order "z1"]
                        [:db/add child-eid :block/created-at 2]
                        [:db/add child-eid :block/updated-at 2]]
              tx-data (vec (concat filler-tx parent-tx child-tx))
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              tx-data-counts (atom [])
              response (try
                         (d/listen! conn ::large-dependent-block-chunks
                                    (fn [{:keys [tx-data]}]
                                      (swap! tx-data-counts conj (count tx-data))))
                         (with-redefs [ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))
                         (finally
                           (d/unlisten! conn ::large-dependent-block-chunks)))]
          (is (= "tx/batch/ok" (:type response)))
          (is (= [497 7] @tx-data-counts))
          (is (= @tx-data-counts
                 (mapv (comp count protocol/transit->tx :tx)
                       (storage/fetch-tx-since sql t-before))))
          (is (= parent-uuid
                 (get-in (d/pull @conn [{:block/parent [:block/uuid]}]
                                 [:block/uuid child-uuid])
                         [:block/parent :block/uuid])))
          (is (= 70 (block-title-prefix-count @conn "large-op-block-")))
          (is (= 3 (block-title-prefix-count @conn "large-dependency-"))))))))

(deftest tx-batch-applies-large-delete-entry-with-descendants-across-chunks-test
  (testing "delete-blocks descendant expansion is still applied in ordered chunks"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              parent-uuid (random-uuid)
              child-count 520
              child-uuids (repeatedly child-count random-uuid)
              _ (d/transact! conn
                             (vec
                              (concat
                               [{:block/uuid page-uuid
                                 :block/name "large-delete-page"
                                 :block/title "large-delete-page"}
                                {:block/uuid parent-uuid
                                 :block/title "large-delete-parent"
                                 :block/page [:block/uuid page-uuid]
                                 :block/parent [:block/uuid page-uuid]
                                 :block/order "d0"
                                 :block/created-at 1
                                 :block/updated-at 1}]
                               (map-indexed
                                (fn [idx child-uuid]
                                  {:block/uuid child-uuid
                                   :block/title (str "large-delete-child-" idx)
                                   :block/page [:block/uuid page-uuid]
                                   :block/parent [:block/uuid parent-uuid]
                                   :block/order (str "d" (inc idx))
                                   :block/created-at idx
                                   :block/updated-at idx})
                                child-uuids))))
              t-before (storage/get-t sql)
              tx-entry {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid parent-uuid]]])
                        :tx-id (random-uuid)
                        :outliner-op :delete-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              tx-report-count (atom 0)
              response (try
                         (d/listen! conn ::large-delete-block-chunks
                                    (fn [_tx-report]
                                      (swap! tx-report-count inc)))
                         (with-redefs [ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))
                         (finally
                           (d/unlisten! conn ::large-delete-block-chunks)))]
          (is (= "tx/batch/ok" (:type response)))
          (is (> @tx-report-count 1))
          (is (> (:t response) t-before))
          (is (pos? (count (storage/fetch-tx-since sql t-before))))
          (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
          (is (every? nil? (map #(d/entity @conn [:block/uuid %]) child-uuids)))
          (is (zero? (block-title-prefix-count @conn "large-delete-child-"))))))))

(deftest tx-batch-rolls-back-large-entry-when-live-chunk-fails-test
  (testing "large tx live chunk failure leaves no persisted partial chunks"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-rollback-page"
                                    :block/title "large-rollback-page"}])
              t-before (storage/get-t sql)
              checksum-before (storage/get-checksum sql)
              missing-parent-uuid (random-uuid)
              tx-data* (large-block-insert-tx page-uuid 1200)
              [_op entity _attr _value tx] (nth tx-data* 150)
              tx-data (assoc tx-data*
                             150 [:db/add entity :block/page [:block/uuid missing-parent-uuid] tx])
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              response (with-redefs [ws/broadcast! (fn [& _] nil)]
                         (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
          (is (= "tx/reject" (:type response)))
          (is (= "db transact failed" (:reason response)))
          (is (= t-before (storage/get-t sql)))
          (is (= checksum-before (storage/get-checksum sql)))
          (is (empty? (storage/fetch-tx-since sql t-before)))
          (is (zero? (block-title-prefix-count @(.-conn self) "large-op-block-"))))))))

(deftest tx-batch-rolls-back-large-entry-when-later-live-chunk-fails-test
  (testing "large tx later chunk failure leaves no persisted partial chunks"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-later-rollback-page"
                                    :block/title "large-later-rollback-page"}])
              t-before (storage/get-t sql)
              checksum-before (storage/get-checksum sql)
              missing-parent-uuid (random-uuid)
              tx-data* (large-block-insert-tx page-uuid 1200)
              [_op entity _attr _value tx] (nth tx-data* 700)
              tx-data (assoc tx-data*
                             700 [:db/add entity :block/page [:block/uuid missing-parent-uuid] tx])
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              response (with-redefs [ws/broadcast! (fn [& _] nil)]
                         (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
          (is (= "tx/reject" (:type response)))
          (is (= "db transact failed" (:reason response)))
          (is (= t-before (storage/get-t sql)))
          (is (= checksum-before (storage/get-checksum sql)))
          (is (empty? (storage/fetch-tx-since sql t-before)))
          (is (zero? (block-title-prefix-count @(.-conn self) "large-op-block-"))))))))

(deftest tx-batch-large-entry-uses-bounded-visible-tx-reports-test
  (testing "large tx chunks are visible and each tx report stays bounded"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-validation-page"
                                    :block/title "large-validation-page"}])
              t-before (storage/get-t sql)
              block-count 1200
              tx-data (large-block-insert-tx page-uuid block-count)
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              tx-data-counts (atom [])
              response (try
                         (d/listen! conn ::large-visible-tx-report-size
                                    (fn [{:keys [tx-data]}]
                                      (swap! tx-data-counts conj (count tx-data))))
                         (with-redefs [ws/broadcast! (fn [& _] nil)]
                           (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))
                         (finally
                           (d/unlisten! conn ::large-visible-tx-report-size)))]
          (is (= "tx/batch/ok" (:type response)))
          (is (= (count tx-data) (reduce + @tx-data-counts)))
          (is (every? #(<= % 500) @tx-data-counts)))))))

(deftest tx-batch-large-entry-updates-checksum-once-for-logical-tx-test
  (testing "large tx chunks append visible tx-log rows but update stored checksum once for the full logical tx"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-checksum-page"
                                    :block/title "large-checksum-page"}])
              t-before (storage/get-t sql)
              block-count 1200
              tx-data (large-block-insert-tx page-uuid block-count)
              tx-entry {:tx (protocol/tx->transit tx-data)
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              original-set-checksum! storage/set-checksum!
              checksum-updates (atom [])
              response (with-redefs [storage/set-checksum! (fn [sql* checksum]
                                                             (swap! checksum-updates conj checksum)
                                                             (original-set-checksum! sql* checksum))
                                     ws/broadcast! (fn [& _] nil)]
                         (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
          (is (= "tx/batch/ok" (:type response)))
          (is (> (:t response) (inc t-before)))
          (is (= 1 (count @checksum-updates)))
          (is (= (sync-checksum/recompute-checksum @conn)
                 (storage/get-checksum sql))))))))

(deftest large-entry-recomputes-versioned-checksum-after-rollback-write-test
  (testing "a mutation-first upgrade cannot bless stale versioned metadata"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid page-uuid
                                    :block/name "large-rollback-page"
                                    :block/title "Before rollback"}])
              _ (storage/set-server-checksum!
                 sql
                 (sync-checksum/recompute-server-checksum @conn)
                 (storage/get-t sql))
              _ (d/transact!
                 conn
                 [[:db/add [:block/uuid page-uuid]
                   :block/title
                   "Changed by old server"]]
                 {:db-sync/skip-checksum-update? true})
              t-before (storage/get-t sql)
              tx-entry {:tx (protocol/tx->transit
                             (large-block-insert-tx page-uuid 1200))
                        :tx-id (random-uuid)
                        :outliner-op :insert-blocks}
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              response
              (with-redefs [ws/broadcast! (fn [& _] nil)]
                (sync-handler/handle-tx-batch!
                 self nil [tx-entry] t-before))]
          (is (= "tx/batch/ok" (:type response)))
          (is (= (sync-checksum/recompute-server-checksum @conn)
                 (storage/get-server-checksum sql)))
          (is (= (storage/get-t sql)
                 (storage/get-server-checksum-t sql))))))))

(defn- concat-bytes
  [^js a ^js b]
  (let [result (js/Uint8Array. (+ (.-byteLength a) (.-byteLength b)))]
    (.set result a 0)
    (.set result b (.-byteLength a))
    result))

(defn- snapshot-upload-request
  ([query body]
   (snapshot-upload-request false query body))
  ([v2? query body]
   (js/Request.
    (str "http://localhost/sync/graph-1/snapshot/upload"
         (when v2? "-v2")
         "?graph-id=graph-1&"
         query)
    #js {:method "POST"
         :body body})))

(defn- kvs-rows
  [sql]
  (mapv (fn [row]
          [(aget row "addr")
           (aget row "content")
           (aget row "addresses")])
        (common/get-sql-rows
         (common/sql-exec sql "select addr, content, addresses from kvs order by addr"))))

(deftest snapshot-upload-v2-requires-session-and-integrity-fields-test
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (common/sql-exec sql
                       "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                       99 "live-row" nil)
      (let [frame (#'sync-handler/frame-bytes
                   (snapshot/encode-rows [[1 "staged-row" nil]]))
            self #js {:sql sql
                      :conn nil
                      :schema-ready true
                      :env #js {"DB" nil}}
            status-for
            (fn [query]
              (let [request (snapshot-upload-request true query frame)]
                (.-status
                 (sync-handler/handle
                  {:self self
                   :request request
                   :url (js/URL. (.-url request))
                   :route {:handler :sync/snapshot-upload-v2}}))))]
        (is (= 400
               (status-for
                "reset=true&finished=true&checksum=0000000000000000&row-count=1")))
        (is (= 400
               (status-for
                "reset=true&finished=true&upload-id=upload-1&row-count=1")))
        (is (= 400
               (status-for
                (str "reset=true&finished=true&upload-id=upload-1"
                     "&checksum=0000000000000000"))))
        (is (= [[99 "live-row" nil]]
               (kvs-rows sql)))))))

(deftest interrupted-snapshot-upload-does-not-replace-live-kvs-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (common/sql-exec sql
                               "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                               99 "live-row" nil)
              (let [valid-frame (#'sync-handler/frame-bytes
                                 (snapshot/encode-rows [[1 "staged-row" nil]]))
                    incomplete-frame (js/Uint8Array. #js [0 0 0 5 1])
                    request (snapshot-upload-request
                             true
                             (str "reset=true&finished=true"
                                  "&checksum=0000000000000000"
                                  "&row-count=1&upload-id=upload-1")
                             (concat-bytes valid-frame incomplete-frame))]
                (-> (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                    (fn [_self _graph-id _ready?]
                                      (p/resolved true))]
                      (sync-handler/handle
                       {:self #js {:sql sql
                                   :conn nil
                                   :schema-ready true
                                   :env #js {"DB" nil}}
                        :request request
                        :url (js/URL. (.-url request))
                        :route {:handler :sync/snapshot-upload-v2}}))
                    (p/then (fn [_]
                              (is false "expected malformed snapshot upload to fail")))
                    (p/catch (fn [_error]
                               (is (= [[99 "live-row" nil]]
                                      (kvs-rows sql)))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest snapshot-upload-commits-staged-kvs-only-after-finished-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (common/sql-exec sql
                               "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                               99 "live-row" nil)
              (let [self #js {:sql sql
                              :conn nil
                              :schema-ready true
                              :env #js {"DB" nil}}
                    first-frame (#'sync-handler/frame-bytes
                                 (snapshot/encode-rows [[1 "staged-row-1" nil]]))
                    final-frame (#'sync-handler/frame-bytes
                                 (snapshot/encode-rows [[2 "staged-row-2" nil]]))
                    first-request (snapshot-upload-request
                                   true
                                   "reset=true&finished=false&upload-id=upload-1"
                                   first-frame)
                    final-request (snapshot-upload-request
                                   true
                                   (str "reset=false&finished=true"
                                        "&checksum=0000000000000000"
                                        "&row-count=2&upload-id=upload-1")
                                   final-frame)]
                (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                (fn [_self _graph-id _ready?]
                                  (p/resolved true))]
                  (p/let [first-response (sync-handler/handle
                                          {:self self
                                           :request first-request
                                           :url (js/URL. (.-url first-request))
                                           :route {:handler :sync/snapshot-upload-v2}})
                          _ (is (= 200 (.-status first-response)))
                          _ (is (= [[99 "live-row" nil]]
                                   (kvs-rows sql)))
                          final-response (sync-handler/handle
                                          {:self self
                                           :request final-request
                                           :url (js/URL. (.-url final-request))
                                           :route {:handler :sync/snapshot-upload-v2}})]
                    (is (= 200 (.-status final-response)))
                    (is (= [[1 "staged-row-1" nil]
                            [2 "staged-row-2" nil]]
                           (kvs-rows sql)))
                    (is (= "0000000000000000"
                           (storage/get-checksum sql))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest snapshot-upload-row-count-mismatch-preserves-live-kvs-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (common/sql-exec sql
                               "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                               99 "live-row" nil)
              (let [frame (#'sync-handler/frame-bytes
                           (snapshot/encode-rows [[1 "staged-row" nil]]))
                    request
                    (snapshot-upload-request
                     true
                     (str "reset=true&finished=true&upload-id=upload-1"
                          "&checksum=0000000000000000&row-count=2")
                     frame)]
                (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                (fn [_self _graph-id _ready?]
                                  (p/resolved true))]
                  (p/let [response
                          (sync-handler/handle
                           {:self #js {:sql sql
                                       :conn nil
                                       :schema-ready true
                                       :env #js {"DB" nil}}
                            :request request
                            :url (js/URL. (.-url request))
                            :route {:handler :sync/snapshot-upload-v2}})
                          body (json-body response)]
                    (is (= 409 (.-status response)))
                    (is (= {:error "snapshot row count mismatch"} body))
                    (is (= [[99 "live-row" nil]]
                           (kvs-rows sql))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest newer-v2-snapshot-upload-replaces-abandoned-session-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (common/sql-exec sql
                               "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                               99 "live-row" nil)
              (let [self #js {:sql sql
                              :conn nil
                              :schema-ready true
                              :env #js {"DB" nil}}
                    make-frame (fn [rows]
                                 (#'sync-handler/frame-bytes
                                  (snapshot/encode-rows rows)))
                    request-1 (snapshot-upload-request
                               true
                               "reset=true&finished=false&upload-id=upload-1"
                               (make-frame [[1 "stale-row" nil]]))
                    request-2 (snapshot-upload-request
                               true
                               "reset=true&finished=false&upload-id=upload-2"
                               (make-frame [[2 "fresh-row" nil]]))
                    stale-final-request (snapshot-upload-request
                                         true
                                         (str "reset=false&finished=true"
                                              "&checksum=0000000000000000"
                                              "&row-count=2&upload-id=upload-1")
                                         (make-frame [[3 "stale-final-row" nil]]))
                    fresh-final-request (snapshot-upload-request
                                         true
                                         (str "reset=false&finished=true"
                                              "&checksum=0000000000000000"
                                              "&row-count=2&upload-id=upload-2")
                                         (make-frame [[4 "fresh-final-row" nil]]))
                    handle-request (fn [request]
                                     (sync-handler/handle
                                      {:self self
                                       :request request
                                       :url (js/URL. (.-url request))
                                       :route {:handler :sync/snapshot-upload-v2}}))]
                (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                (fn [_self _graph-id _ready?]
                                  (p/resolved true))]
                  (p/let [_ (handle-request request-1)
                          second-response (handle-request request-2)
                          _ (is (= 200 (.-status second-response)))
                          _ (is (= [[99 "live-row" nil]]
                                   (kvs-rows sql)))
                          stale-final-response
                          (handle-request stale-final-request)
                          stale-final-body (json-body stale-final-response)
                          _ (is (= 409 (.-status stale-final-response)))
                          _ (is (= {:error "snapshot upload session replaced"}
                                   stale-final-body))
                          fresh-final-response
                          (handle-request fresh-final-request)]
                    (is (= 200 (.-status fresh-final-response)))
                    (is (= [[2 "fresh-row" nil]
                            [4 "fresh-final-row" nil]]
                           (kvs-rows sql))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest legacy-fallback-aborts-v2-staging-without-mixing-rows-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (common/sql-exec sql
                               "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                               99 "live-row" nil)
              (let [self #js {:sql sql
                              :conn nil
                              :schema-ready true
                              :env #js {"DB" nil}}
                    make-frame (fn [rows]
                                 (#'sync-handler/frame-bytes
                                  (snapshot/encode-rows rows)))
                    v2-partial-request
                    (snapshot-upload-request
                     true
                     "reset=true&finished=false&upload-id=upload-1"
                     (make-frame [[1 "staged-v2-row" nil]]))
                    legacy-request
                    (snapshot-upload-request
                     "reset=true&finished=true&checksum=legacy-checksum"
                     (make-frame [[2 "legacy-row" nil]]))
                    stale-v2-final-request
                    (snapshot-upload-request
                     true
                     (str "reset=false&finished=true"
                          "&checksum=0000000000000000"
                          "&row-count=2&upload-id=upload-1")
                     (make-frame [[3 "stale-v2-row" nil]]))
                    handle-request
                    (fn [request handler]
                      (sync-handler/handle
                       {:self self
                        :request request
                        :url (js/URL. (.-url request))
                        :route {:handler handler}}))]
                (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                (fn [_self _graph-id _ready?]
                                  (p/resolved true))]
                  (p/let [v2-response
                          (handle-request v2-partial-request
                                          :sync/snapshot-upload-v2)
                          _ (is (= 200 (.-status v2-response)))
                          legacy-response
                          (handle-request legacy-request
                                          :sync/snapshot-upload)
                          _ (is (= 200 (.-status legacy-response)))
                          _ (is (= [[2 "legacy-row" nil]]
                                   (kvs-rows sql)))
                          stale-response
                          (handle-request stale-v2-final-request
                                          :sync/snapshot-upload-v2)
                          stale-body (json-body stale-response)]
                    (is (= 409 (.-status stale-response)))
                    (is (= {:error "snapshot upload session replaced"}
                           stale-body))
                    (is (= [[2 "legacy-row" nil]]
                           (kvs-rows sql))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest legacy-v1-snapshot-upload-without-upload-id-still-commits-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (let [frame (#'sync-handler/frame-bytes
                           (snapshot/encode-rows [[7 "legacy-row" nil]]))
                    request (snapshot-upload-request
                             "reset=true&finished=true&checksum=legacy-checksum"
                             frame)]
                (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                (fn [_self _graph-id _ready?]
                                  (p/resolved true))]
                  (p/let [response (sync-handler/handle
                                    {:self #js {:sql sql
                                                :conn nil
                                                :schema-ready true
                                                :env #js {"DB" nil}}
                                     :request request
                                     :url (js/URL. (.-url request))
                                     :route {:handler :sync/snapshot-upload}})]
                    (is (= 200 (.-status response)))
                    (is (= [[7 "legacy-row" nil]]
                           (kvs-rows sql)))
                    (is (= "legacy-checksum"
                           (storage/get-checksum sql))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest legacy-v1-multi-chunk-snapshot-upload-without-upload-id-still-commits-test
  (async done
         (->
          (with-memory-sql-async
            (fn [sql]
              (storage/init-schema! sql)
              (common/sql-exec sql
                               "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                               99 "live-row" nil)
              (let [self #js {:sql sql
                              :conn nil
                              :schema-ready true
                              :env #js {"DB" nil}}
                    first-frame (#'sync-handler/frame-bytes
                                 (snapshot/encode-rows [[7 "legacy-row-1" nil]]))
                    final-frame (#'sync-handler/frame-bytes
                                 (snapshot/encode-rows [[8 "legacy-row-2" nil]]))
                    first-request (snapshot-upload-request
                                   "reset=true&finished=false"
                                   first-frame)
                    final-request (snapshot-upload-request
                                   "reset=false&finished=true&checksum=legacy-checksum"
                                   final-frame)]
                (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                (fn [_self _graph-id _ready?]
                                  (p/resolved true))]
                  (p/let [first-response
                          (sync-handler/handle
                           {:self self
                            :request first-request
                            :url (js/URL. (.-url first-request))
                            :route {:handler :sync/snapshot-upload}})
                          _ (is (= 200 (.-status first-response)))
                          _ (is (= [[7 "legacy-row-1" nil]]
                                   (kvs-rows sql)))
                          final-response
                          (sync-handler/handle
                           {:self self
                            :request final-request
                            :url (js/URL. (.-url final-request))
                            :route {:handler :sync/snapshot-upload}})]
                    (is (= 200 (.-status final-response)))
                    (is (= [[7 "legacy-row-1" nil]
                            [8 "legacy-row-2" nil]]
                           (kvs-rows sql)))
                    (is (= "legacy-checksum"
                           (storage/get-checksum sql))))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done))))))

(deftest concurrent-legacy-snapshot-upload-cannot-replace-active-session-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql
                             :conn nil
                             :schema-ready true
                             :env #js {"DB" nil}}
                   first-request
                   (snapshot-upload-request
                    "reset=true&finished=false"
                    (#'sync-handler/frame-bytes
                     (snapshot/encode-rows [[7 "first-client-row" nil]])))
                   second-request
                   (snapshot-upload-request
                    "reset=true&finished=false"
                    (#'sync-handler/frame-bytes
                     (snapshot/encode-rows [[8 "second-client-row" nil]])))
                   handle-request
                   (fn [request]
                     (sync-handler/handle
                      {:self self
                       :request request
                       :url (js/URL. (.-url request))
                       :route {:handler :sync/snapshot-upload}}))]
               (-> (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                    (fn [_self _graph-id _ready?]
                                      (p/resolved true))]
                     (p/let [first-response (handle-request first-request)
                             second-response (handle-request second-request)
                             second-body (json-body second-response)]
                       (is (= 200 (.-status first-response)))
                       (is (= 409 (.-status second-response)))
                       (is (= {:error "legacy snapshot upload already in progress"}
                              second-body))
                       (is (= [[7 "first-client-row" nil]]
                              (kvs-rows sql)))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest legacy-v1-upload-cannot-resume-without-active-session-test
  (async done
         (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [self #js {:sql sql
                             :conn nil
                             :schema-ready true
                             :env #js {"DB" nil}}
                   delayed-request
                   (snapshot-upload-request
                    "reset=false&finished=true"
                    (#'sync-handler/frame-bytes
                     (snapshot/encode-rows [[8 "delayed-row" nil]])))]
               (-> (p/with-redefs [sync-handler/<set-graph-ready-for-use!
                                    (fn [_self _graph-id _ready?]
                                      (p/resolved true))]
                     (p/let [response
                             (sync-handler/handle
                              {:self self
                               :request delayed-request
                               :url (js/URL. (.-url delayed-request))
                               :route {:handler :sync/snapshot-upload}})
                             body (json-body response)]
                       (is (= 409 (.-status response)))
                       (is (= {:error "legacy snapshot upload session missing"}
                              body))
                       (is (empty? (kvs-rows sql)))))
                   (p/then (fn [] (done)))
                   (p/catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest finished-snapshot-upload-persists-provided-checksum-test
  (async done
         (let [sql (test-sql/make-sql)
               checksum "1be70518babe8784"
               conn (d/create-conn db-schema/schema)
               self #js {:sql sql
                         :conn conn
                         :schema-ready true
                         :env #js {"DB" nil}}
               request (js/Request. (str "http://localhost/sync/graph-1/snapshot/upload?graph-id=graph-1&finished=true&checksum=" checksum)
                                    #js {:method "POST"
                                         :body (js/Uint8Array. 0)})]
           (d/transact! conn [{:block/uuid (random-uuid)
                               :block/title "uploaded"}])
           (is (nil? (storage/get-checksum sql)))
           (-> (p/with-redefs [sync-handler/import-snapshot-stream! (fn
                                                                      ([_self _stream _reset?]
                                                                       (p/resolved 0))
                                                                      ([_self _stream _reset? _import-f]
                                                                       (p/resolved 0)))
                               sync-handler/<set-graph-ready-for-use! (fn [_self _graph-id _graph-ready-for-use?]
                                                                        (p/resolved true))]
                 (p/let [resp (sync-handler/handle {:self self
                                                    :request request
                                                    :url (js/URL. (.-url request))
                                                    :route {:handler :sync/snapshot-upload}})
                         text (.text resp)
                         body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                   (is (= 200 (.-status resp)))
                   (is (= {:ok true :count 0} body))
                   (is (= checksum (storage/get-checksum sql)))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest snapshot-upload-returns-413-when-sqlite-row-is-too-large-test
  (async done
         (let [sql (test-sql/make-sql)
               conn (d/create-conn db-schema/schema)
               self #js {:sql sql
                         :conn conn
                         :schema-ready true
                         :env #js {"DB" nil}}
               request (js/Request. "http://localhost/sync/graph-1/snapshot/upload?graph-id=graph-1&finished=true"
                                    #js {:method "POST"
                                         :body (js/Uint8Array. 0)})]
           (-> (p/with-redefs [sync-handler/import-snapshot-stream! (fn
                                                                      ([_self _stream _reset?]
                                                                       (p/rejected (js/Error. "string or blob too big: SQLITE_TOOBIG")))
                                                                      ([_self _stream _reset? _import-f]
                                                                       (p/rejected (js/Error. "string or blob too big: SQLITE_TOOBIG"))))
                               sync-handler/<set-graph-ready-for-use! (fn [_self _graph-id _graph-ready-for-use?]
                                                                        (p/resolved true))]
                 (p/let [resp (sync-handler/handle {:self self
                                                    :request request
                                                    :url (js/URL. (.-url request))
                                                    :route {:handler :sync/snapshot-upload}})
                         text (.text resp)
                         body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                   (is (= 413 (.-status resp)))
                   (is (= {:error "snapshot row too large"} body))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest import-snapshot-stream-first-non-empty-chunk-applies-reset-test
  (async done
         (let [rows [[42 "payload" nil]]
               frame (#'sync-handler/frame-bytes (snapshot/encode-rows rows))
               stream (js/ReadableStream.
                       #js {:start (fn [controller]
                                     (.enqueue controller frame)
                                     (.close controller))})
               applied (atom [])
               self #js {:sql (test-sql/make-sql)
                         :conn (d/create-conn db-schema/schema)
                         :schema-ready true}]
           (-> (p/with-redefs [sync-handler/import-snapshot!
                               (fn [_self rows* reset?]
                                 (swap! applied conj {:rows rows*
                                                      :reset? reset?}))]
                 (p/let [count (#'sync-handler/import-snapshot-stream! self stream true)]
                   (is (= 1 count))
                   (is (= [{:rows rows
                            :reset? true}]
                          @applied))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest tx-batch-rejects-when-a-tx-entry-fails-test
  (testing "db transact failure rejects the batch"
    (let [sql (test-sql/make-sql)
          conn (d/create-conn db-schema/schema)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          tx-entry-1 {:tx (protocol/tx->transit [[:db/add -1 :block/title "ok"]])
                      :outliner-op :save-block}
          tx-entry-2 {:tx (protocol/tx->transit [[:db/add -2 :block/title "bad"]])
                      :outliner-op :save-block}
          apply-calls (atom 0)
          apply-tx-entry (fn [_conn tx-entry]
                           (swap! apply-calls inc)
                           (when (= 2 @apply-calls)
                             (throw (ex-info "DB write failed with invalid data"
                                             {:tx-entry tx-entry}))))
          response (with-redefs [ws/broadcast! (fn [& _] nil)
                                 sync-handler/apply-tx-entry! (fn
                                                                ([conn tx-entry]
                                                                 (apply-tx-entry conn tx-entry))
                                                                ([_self conn tx-entry _request-context]
                                                                 (apply-tx-entry conn tx-entry)))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry-1 tx-entry-2] 0))]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= 0 (:t response)))
      (is (nil? (:data response)))
      (is (= 2 @apply-calls)))))

(deftest committed-tx-response-survives-stale-peer-broadcast-failure-test
  (let [sql (test-sql/make-sql)
        conn (storage/open-conn sql)
        sender #js {:readyState 1}
        bad-closed* (atom nil)
        bad-peer #js {:readyState 1
                      :send (fn [_raw]
                              (throw (js/Error. "stale peer")))
                      :close (fn [code reason]
                               (reset! bad-closed* [code reason]))}
        healthy-messages* (atom [])
        healthy-peer #js {:readyState 1
                          :send (fn [raw]
                                  (swap! healthy-messages* conj
                                         (-> raw
                                             js/JSON.parse
                                             (js->clj
                                              :keywordize-keys true))))}
        self #js {:sql sql
                  :conn conn
                  :schema-ready true
                  :state #js {:getWebSockets
                              (fn []
                                #js [sender bad-peer healthy-peer])}}
        tx-entry {:tx (protocol/tx->transit
                       [[:db/add -1 :block/title "committed"]])
                  :outliner-op :save-block}
        response (sync-handler/handle-tx-batch!
                  self sender [tx-entry] 0)]
    (is (= "tx/batch/ok" (:type response)))
    (is (= 1 (:t response)))
    (is (= [1011 "send failed"] @bad-closed*))
    (is (= [{:type "changed" :t 1}]
           @healthy-messages*))))

(deftest tx-batch-reject-includes-success-and-failed-tx-ids-test
  (testing "partial failure returns success and failed tx ids and broadcasts changed once"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          success-tx-id (random-uuid)
          failed-tx-id (random-uuid)
          success-block-uuid (random-uuid)
          missing-uuid (random-uuid)
          tx-entry-1 {:tx-id success-tx-id
                      :tx (protocol/tx->transit [{:db/id -1
                                                  :block/uuid success-block-uuid
                                                  :block/title "ok"}])
                      :outliner-op :save-block}
          tx-entry-2 {:tx-id failed-tx-id
                      :tx (protocol/tx->transit [[:db/add [:block/uuid missing-uuid] :block/title "stale" 1]])
                      :outliner-op :save-block}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry-1 tx-entry-2] 0))]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= 1 (:t response)))
      (is (= [success-tx-id] (:success-tx-ids response)))
      (is (= failed-tx-id (:failed-tx-id response)))
      (is (= [{:type "changed" :t 1}] @changed-messages))
      (is (some? (d/entity @conn [:block/uuid success-block-uuid])))
      (is (nil? (d/entity @conn [:block/uuid missing-uuid]))))))

(deftest tx-batch-accepts-stale-delete-as-idempotent-test
  (doseq [outliner-op [:delete-blocks :delete-page]]
    (testing (str outliner-op " succeeds when the target is already absent")
      (let [sql (test-sql/make-sql)
            conn (storage/open-conn sql)
            self #js {:sql sql
                      :conn conn
                      :schema-ready true}
            stale-delete-tx-id (random-uuid)
            missing-delete-uuid (random-uuid)
            t-before (storage/get-t sql)
            checksum-before (storage/get-checksum sql)
            stale-delete-entry {:tx-id stale-delete-tx-id
                                :tx (protocol/tx->transit
                                     [[:db/retractEntity [:block/uuid missing-delete-uuid]]])
                                :outliner-op outliner-op}
            changed-messages (atom [])
            response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                   (swap! changed-messages conj payload))]
                       (sync-handler/handle-tx-batch! self nil [stale-delete-entry] t-before))]
        (is (= "tx/batch/ok" (:type response)))
        (is (= t-before (:t response)))
        (is (= checksum-before (:checksum response)))
        (is (empty? @changed-messages))))))

(deftest tx-batch-rejects-empty-delete-input-test
  (testing "an originally empty delete remains invalid"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          empty-delete-tx-id (random-uuid)
          t-before (storage/get-t sql)
          empty-delete-entry {:tx-id empty-delete-tx-id
                              :tx (protocol/tx->transit [])
                              :outliner-op :delete-blocks}
          response (sync-handler/handle-tx-batch! self nil [empty-delete-entry] t-before)]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= empty-delete-tx-id (:failed-tx-id response))))))

(deftest tx-batch-reports-stale-delete-success-before-later-failure-test
  (testing "an idempotent delete is acknowledged before a later tx fails"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          stale-delete-tx-id (random-uuid)
          later-failed-tx-id (random-uuid)
          missing-delete-uuid (random-uuid)
          missing-update-uuid (random-uuid)
          t-before (storage/get-t sql)
          stale-delete-entry {:tx-id stale-delete-tx-id
                              :tx (protocol/tx->transit
                                   [[:db/retractEntity [:block/uuid missing-delete-uuid]]])
                              :outliner-op :delete-blocks}
          later-failed-entry {:tx-id later-failed-tx-id
                              :tx (protocol/tx->transit
                                   [[:db/add [:block/uuid missing-update-uuid] :block/title "stale" 1]])
                              :outliner-op :save-block}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [stale-delete-entry later-failed-entry] t-before))]
      (is (= "tx/reject" (:type response)))
      (is (= "db transact failed" (:reason response)))
      (is (= t-before (:t response)))
      (is (= later-failed-tx-id (:failed-tx-id response)))
      (is (= [stale-delete-tx-id] (:success-tx-ids response)))
      (is (empty? @changed-messages)))))

(deftest tx-batch-ignores-empty-rebase-entry-test
  (testing "empty rebase entry is a no-op: no t increment, no tx-log append, no changed broadcast"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          t-before (storage/get-t sql)
          tx-entry {:tx (protocol/tx->transit [])
                    :outliner-op :rebase}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (= t-before (:t response)))
      (is (empty? (storage/fetch-tx-since sql t-before)))
      (is (empty? @changed-messages)))))

(deftest tx-batch-mixed-empty-rebase-and-real-entry-test
  (testing "empty rebase entry is ignored while real tx still applies"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          t-before (storage/get-t sql)
          noop-rebase-entry {:tx (protocol/tx->transit [])
                             :outliner-op :rebase}
          block-uuid (random-uuid)
          real-entry {:tx (protocol/tx->transit [[:db/add -1 :block/uuid block-uuid]
                                                 [:db/add -1 :block/title "applied"]])
                      :outliner-op :save-block}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [noop-rebase-entry real-entry] t-before))
          txs (storage/fetch-tx-since sql t-before)]
      (is (= "tx/batch/ok" (:type response)))
      (is (= (inc t-before) (:t response)))
      (is (= 1 (count txs)))
      (is (= :save-block (:outliner-op (first txs))))
      (is (= [{:type "changed" :t (inc t-before)}] @changed-messages)))))

(deftest tx-batch-ignores-stale-rebase-with-missing-lookup-entity-test
  (testing "stale rebase lookup refs to missing entities are treated as no-op"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          page-uuid (random-uuid)
          parent-uuid (random-uuid)
          missing-block-uuid (random-uuid)
          _ (d/transact! conn [{:block/uuid page-uuid
                                :block/name "rebase-stale-page"
                                :block/title "rebase-stale-page"}
                               {:block/uuid parent-uuid
                                :block/title "existing-parent"
                                :block/order "a0"
                                :block/parent [:block/uuid page-uuid]
                                :block/page [:block/uuid page-uuid]}])
          t-before (storage/get-t sql)
          checksum-before (storage/get-checksum sql)
          tx-entry {:tx (protocol/tx->transit
                         [[:db/retract [:block/uuid missing-block-uuid]
                           :block/parent
                           [:block/uuid page-uuid]
                           536882158]
                          [:db/add [:block/uuid missing-block-uuid]
                           :block/parent
                           [:block/uuid parent-uuid]
                           536882158]
                          [:db/retract [:block/uuid missing-block-uuid]
                           :block/order
                           "a100001V"
                           536882158]
                          [:db/add [:block/uuid missing-block-uuid]
                           :block/order
                           "a0"
                           536882158]])
                    :outliner-op :rebase}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (= t-before (:t response)))
      (is (= checksum-before (storage/get-checksum sql)))
      (is (empty? (storage/fetch-tx-since sql t-before)))
      (is (empty? @changed-messages)))))

(deftest tx-batch-acknowledges-noop-stale-rebase-with-tx-id-test
  (testing "an accepted no-op should acknowledge its client tx id"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          tx-id (random-uuid)
          page-uuid (random-uuid)
          parent-uuid (random-uuid)
          missing-block-uuid (random-uuid)
          _ (d/transact! conn [{:block/uuid page-uuid
                                :block/name "rebase-stale-page-with-tx-id"
                                :block/title "rebase-stale-page-with-tx-id"}
                               {:block/uuid parent-uuid
                                :block/title "existing-parent"
                                :block/order "a0"
                                :block/parent [:block/uuid page-uuid]
                                :block/page [:block/uuid page-uuid]}])
          t-before (storage/get-t sql)
          checksum-before (storage/get-checksum sql)
          tx-entry {:tx-id tx-id
                    :tx (protocol/tx->transit
                         [[:db/retract [:block/uuid missing-block-uuid]
                           :block/parent
                           [:block/uuid page-uuid]
                           536882158]
                          [:db/add [:block/uuid missing-block-uuid]
                           :block/parent
                           [:block/uuid parent-uuid]
                           536882158]])
                    :outliner-op :rebase}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (= t-before (:t response)))
      (is (nil? (:failed-tx-id response)))
      (is (= checksum-before (storage/get-checksum sql)))
      (is (empty? (storage/fetch-tx-since sql t-before)))
      (is (empty? @changed-messages)))))

(deftest tx-batch-ignores-stale-fix-with-missing-lookup-entity-test
  (testing "stale fix lookup refs to missing entities are treated as no-op"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          page-uuid (random-uuid)
          sibling-uuid (random-uuid)
          missing-block-uuid (random-uuid)
          _ (d/transact! conn [{:block/uuid page-uuid
                                :block/name "fix-stale-page"
                                :block/title "fix-stale-page"}
                               {:block/uuid sibling-uuid
                                :block/title "existing-sibling"
                                :block/order "a5Uzl"
                                :block/parent [:block/uuid page-uuid]
                                :block/page [:block/uuid page-uuid]}])
          t-before (storage/get-t sql)
          checksum-before (storage/get-checksum sql)
          tx-entry {:tx (protocol/tx->transit
                         [[:db/retract [:block/uuid missing-block-uuid]
                           :block/order
                           "a5Uzl"
                           536871101]
                          [:db/add [:block/uuid missing-block-uuid]
                           :block/order
                           "a5c"
                           536871101]
                          [:db/retract [:block/uuid sibling-uuid]
                           :block/order
                           "a5Uzl"
                           536871101]
                          [:db/add [:block/uuid sibling-uuid]
                           :block/order
                           "a5k"
                           536871101]])
                    :outliner-op :fix}
          changed-messages (atom [])
          response (with-redefs [ws/broadcast! (fn [_self _sender payload]
                                                 (swap! changed-messages conj payload))]
                     (sync-handler/handle-tx-batch! self nil [tx-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (= t-before (:t response)))
      (is (= checksum-before (storage/get-checksum sql)))
      (is (empty? (storage/fetch-tx-since sql t-before)))
      (is (empty? @changed-messages)))))

(deftest server-incremental-checksum-matches-full-recompute-fuzz-test
  (testing "server stored checksum stays equal to full recompute across randomized tx/rebase/no-op sequences"
    (doseq [seed (range 1 11)]
      (let [sql (test-sql/make-sql)
            conn (storage/open-conn sql)
            self #js {:sql sql
                      :conn conn
                      :schema-ready true}
            page-uuid (random-uuid)
            root-block-uuid (random-uuid)
            _ (d/transact! conn [{:block/uuid page-uuid
                                  :block/name (str "server-fuzz-page-" seed)
                                  :block/title (str "server-fuzz-page-" seed)}
                                 {:block/uuid root-block-uuid
                                  :block/title (str "server-fuzz-root-" seed)
                                  :block/order "a0"
                                  :block/parent [:block/uuid page-uuid]
                                  :block/page [:block/uuid page-uuid]}])
            rng (seeded-rng seed)]
        (sync-handler/current-server-checksum self)
        (loop [step 0
               prev-t (storage/get-t sql)
               prev-checksum (storage/get-checksum sql)]
          (when (< step 60)
            (let [entry (gen-server-tx-entry rng @conn step)
                  response (with-redefs [ws/broadcast! (fn [& _] nil)]
                             (sync-handler/handle-tx-batch! self nil [entry] prev-t))
                  new-t (:t response)
                  stored-checksum (storage/get-checksum sql)
                  recomputed-checksum (sync-checksum/recompute-checksum @conn)
                  stored-server-checksum (storage/get-server-checksum sql)
                  recomputed-server-checksum
                  (sync-checksum/recompute-server-checksum @conn)]
              (is (= "tx/batch/ok" (:type response))
                  (str "expected tx/batch/ok at seed " seed " step " step))
              (is (= new-t (storage/get-t sql))
                  (str "t mismatch at seed " seed " step " step))
              (is (= recomputed-server-checksum stored-server-checksum)
                  (str "versioned checksum mismatch at seed " seed
                       " step " step))
              (is (= new-t (storage/get-server-checksum-t sql))
                  (str "versioned checksum watermark mismatch at seed "
                       seed " step " step))
              (if (> new-t prev-t)
                (do
                  (is (string? stored-checksum)
                      (str "stored checksum missing after mutation at seed " seed " step " step))
                  (is (= recomputed-checksum stored-checksum)
                      (str "checksum mismatch at seed " seed " step " step
                           " recomputed=" recomputed-checksum
                           " stored=" stored-checksum)))
                (is (= prev-checksum stored-checksum)
                    (str "checksum changed on no-op batch at seed " seed " step " step)))
              (recur (inc step) new-t stored-checksum))))))))

(deftest server-checksum-is-invariant-across-commuting-batch-order-test
  (testing "server checksum converges when commuting tx entries are applied in opposite order"
    (let [page-uuid (random-uuid)
          block-a-uuid (random-uuid)
          block-b-uuid (random-uuid)
          seed-db! (fn [conn]
                     (d/transact! conn [{:block/uuid page-uuid
                                         :block/name "server-order-page"
                                         :block/title "server-order-page"}
                                        {:block/uuid block-a-uuid
                                         :block/title "A0"
                                         :block/order "a0"
                                         :block/page [:block/uuid page-uuid]
                                         :block/parent [:block/uuid page-uuid]}
                                        {:block/uuid block-b-uuid
                                         :block/title "B0"
                                         :block/order "a1"
                                         :block/page [:block/uuid page-uuid]
                                         :block/parent [:block/uuid page-uuid]}]))
          entry-a {:tx (protocol/tx->transit [[:db/add [:block/uuid block-a-uuid]
                                               :block/title
                                               "A1"]])
                   :outliner-op :save-block}
          entry-b {:tx (protocol/tx->transit [[:db/add [:block/uuid block-b-uuid]
                                               :block/order
                                               "a9"]])
                   :outliner-op :save-block}
          {:keys [self conn sql]} (make-server-self)
          _ (seed-db! conn)
          _ (apply-entries! self [entry-a entry-b])
          checksum-ab (storage/get-checksum sql)
          recompute-ab (sync-checksum/recompute-checksum @conn)
          pull-ab [(d/pull @conn [:block/title :block/order] [:block/uuid block-a-uuid])
                   (d/pull @conn [:block/title :block/order] [:block/uuid block-b-uuid])]
          {:keys [self conn sql]} (make-server-self)
          _ (seed-db! conn)
          _ (apply-entries! self [entry-b entry-a])
          checksum-ba (storage/get-checksum sql)
          recompute-ba (sync-checksum/recompute-checksum @conn)
          pull-ba [(d/pull @conn [:block/title :block/order] [:block/uuid block-a-uuid])
                   (d/pull @conn [:block/title :block/order] [:block/uuid block-b-uuid])]]
      (is (= recompute-ab checksum-ab))
      (is (= recompute-ba checksum-ba))
      (is (= checksum-ab checksum-ba))
      (is (= pull-ab pull-ba)))))

(deftest server-checksum-is-invariant-across-tx-partitioning-test
  (testing "server checksum converges when identical tx-data is sent as one entry or split entries"
    (let [page-uuid (random-uuid)
          block-a-uuid (random-uuid)
          block-b-uuid (random-uuid)
          seed-db! (fn [conn]
                     (d/transact! conn [{:block/uuid page-uuid
                                         :block/name "server-partition-page"
                                         :block/title "server-partition-page"}
                                        {:block/uuid block-a-uuid
                                         :block/title "A0"
                                         :block/order "a0"
                                         :block/page [:block/uuid page-uuid]
                                         :block/parent [:block/uuid page-uuid]}
                                        {:block/uuid block-b-uuid
                                         :block/title "B0"
                                         :block/order "a1"
                                         :block/page [:block/uuid page-uuid]
                                         :block/parent [:block/uuid page-uuid]}]))
          datom-a [:db/add [:block/uuid block-a-uuid] :block/title "A2"]
          datom-b [:db/add [:block/uuid block-b-uuid] :block/order "a8"]
          one-entry {:tx (protocol/tx->transit [datom-a datom-b])
                     :outliner-op :save-block}
          split-entry-a {:tx (protocol/tx->transit [datom-a])
                         :outliner-op :save-block}
          split-entry-b {:tx (protocol/tx->transit [datom-b])
                         :outliner-op :save-block}
          {:keys [self conn sql]} (make-server-self)
          _ (seed-db! conn)
          _ (apply-entries! self [one-entry])
          checksum-one (storage/get-checksum sql)
          recompute-one (sync-checksum/recompute-checksum @conn)
          pull-one [(d/pull @conn [:block/title :block/order] [:block/uuid block-a-uuid])
                    (d/pull @conn [:block/title :block/order] [:block/uuid block-b-uuid])]
          {:keys [self conn sql]} (make-server-self)
          _ (seed-db! conn)
          _ (apply-entries! self [split-entry-a split-entry-b])
          checksum-split (storage/get-checksum sql)
          recompute-split (sync-checksum/recompute-checksum @conn)
          pull-split [(d/pull @conn [:block/title :block/order] [:block/uuid block-a-uuid])
                      (d/pull @conn [:block/title :block/order] [:block/uuid block-b-uuid])]]
      (is (= recompute-one checksum-one))
      (is (= recompute-split checksum-split))
      (is (= checksum-one checksum-split))
      (is (= pull-one pull-split)))))

(deftest server-checksum-remains-correct-under-random-outliner-conflicts-test
  (testing "random insert/move/indent/outdent/delete with stale-client conflicts and undo/redo keeps checksum correct"
    (doseq [seed (range 31 35)]
      (let [{:keys [self conn sql]} (make-server-self)
            page-uuid (random-uuid)
            root-uuid (random-uuid)
            child-a-uuid (random-uuid)
            child-b-uuid (random-uuid)
            _ (d/transact! conn [{:block/uuid page-uuid
                                  :block/name (str "outliner-fuzz-page-" seed)
                                  :block/title (str "outliner-fuzz-page-" seed)}
                                 {:block/uuid root-uuid
                                  :block/title "root"
                                  :block/order "a0"
                                  :block/page [:block/uuid page-uuid]
                                  :block/parent [:block/uuid page-uuid]}
                                 {:block/uuid child-a-uuid
                                  :block/title "child-a"
                                  :block/order "a1"
                                  :block/page [:block/uuid page-uuid]
                                  :block/parent [:block/uuid root-uuid]}
                                 {:block/uuid child-b-uuid
                                  :block/title "child-b"
                                  :block/order "a2"
                                  :block/page [:block/uuid page-uuid]
                                  :block/parent [:block/uuid root-uuid]}])
            rng (seeded-rng (* seed 7919))]
        (loop [step 0
               t-before (storage/get-t sql)
               checksum-before (storage/get-checksum sql)
               undo-stack []
               redo-stack []]
          (when (< step 80)
            (let [db @conn
                  op (rand-int* rng 11)]
              (cond
                ;; explicit conflict scenario: delete parent then stale client inserts child under deleted parent
                (= op 0)
                (if-let [{:keys [delete-entry stale-add-entry]} (make-stale-add-after-delete-conflict rng db step)]
                  (let [delete-response (apply-batch-with-t! self t-before [delete-entry])
                        delete-state (assert-server-checksum-step! sql conn t-before checksum-before delete-response
                                                                   (str "seed " seed " step " step " delete-before-stale-add"))
                        stale-response (apply-batch-with-t! self (:t delete-state) [stale-add-entry])
                        stale-state (assert-server-checksum-step! sql conn (:t delete-state) (:checksum delete-state) stale-response
                                                                  (str "seed " seed " step " step " stale-add-after-delete"))]
                    (is (= "tx/reject" (:type stale-response))
                        (str "seed " seed " step " step " stale child insert should be rejected"))
                    (recur (inc step) (:t stale-state) (:checksum stale-state) undo-stack redo-stack))
                  (let [noop-response (apply-batch-with-t! self t-before [(no-op-rebase-entry)])
                        noop-state (assert-server-checksum-step! sql conn t-before checksum-before noop-response
                                                                 (str "seed " seed " step " step " fallback-noop"))]
                    (recur (inc step) (:t noop-state) (:checksum noop-state) undo-stack redo-stack)))

                ;; undo
                (= op 1)
                (if-let [{:keys [forward inverse]} (peek undo-stack)]
                  (let [entries (if (tx-entries-applicable? db inverse)
                                  inverse
                                  [(no-op-rebase-entry)])
                        response (apply-batch-with-t! self t-before entries)
                        state (assert-server-checksum-step! sql conn t-before checksum-before response
                                                            (str "seed " seed " step " step " undo"))]
                    (recur (inc step)
                           (:t state)
                           (:checksum state)
                           (pop undo-stack)
                           (if (:advanced? state)
                             (conj redo-stack {:forward forward :inverse inverse})
                             redo-stack)))
                  (let [noop-response (apply-batch-with-t! self t-before [(no-op-rebase-entry)])
                        noop-state (assert-server-checksum-step! sql conn t-before checksum-before noop-response
                                                                 (str "seed " seed " step " step " undo-noop"))]
                    (recur (inc step) (:t noop-state) (:checksum noop-state) undo-stack redo-stack)))

                ;; redo
                (= op 2)
                (if-let [{:keys [forward inverse]} (peek redo-stack)]
                  (let [entries (if (tx-entries-applicable? db forward)
                                  forward
                                  [(no-op-rebase-entry)])
                        response (apply-batch-with-t! self t-before entries)
                        state (assert-server-checksum-step! sql conn t-before checksum-before response
                                                            (str "seed " seed " step " step " redo"))]
                    (recur (inc step)
                           (:t state)
                           (:checksum state)
                           (if (:advanced? state)
                             (conj undo-stack {:forward forward :inverse inverse})
                             undo-stack)
                           (pop redo-stack)))
                  (let [noop-response (apply-batch-with-t! self t-before [(no-op-rebase-entry)])
                        noop-state (assert-server-checksum-step! sql conn t-before checksum-before noop-response
                                                                 (str "seed " seed " step " step " redo-noop"))]
                    (recur (inc step) (:t noop-state) (:checksum noop-state) undo-stack redo-stack)))

                :else
                (let [command (case op
                                3 (make-insert-command rng db step)
                                4 (make-random-move-command rng db step)
                                5 (make-random-indent-command rng db step)
                                6 (make-random-outdent-command rng db step)
                                7 (make-title-command rng db step)
                                8 {:forward [(make-random-delete-entry rng db)]
                                   :undoable? false}
                                9 (make-random-move-command rng db step)
                                10 (make-random-indent-command rng db step)
                                {:forward [(no-op-rebase-entry)]
                                 :undoable? false})
                      entries (if (tx-entries-applicable? db (:forward command))
                                (:forward command)
                                [(no-op-rebase-entry)])
                      response (apply-batch-with-t! self t-before entries)
                      state (assert-server-checksum-step! sql conn t-before checksum-before response
                                                          (str "seed " seed " step " step " op " op))
                      command-applied? (and (:undoable? command) (:advanced? state))
                      next-undo (if command-applied?
                                  (conj undo-stack {:forward (:forward command)
                                                    :inverse (:inverse command)})
                                  undo-stack)
                      next-redo (if (:advanced? state) [] redo-stack)]
                  (recur (inc step) (:t state) (:checksum state) next-undo next-redo))))))))))

(defn- seed-page-with-block-tree!
  [conn]
  (let [page-uuid (random-uuid)
        parent-uuid (random-uuid)
        child-a-uuid (random-uuid)
        child-b-uuid (random-uuid)
        now 1775549093572]
    (d/transact! conn [{:block/uuid page-uuid
                        :block/name "sync-repro-page"
                        :block/title "sync-repro-page"
                        :block/created-at now
                        :block/updated-at now}
                       {:block/uuid parent-uuid
                        :block/title "parent"
                        :block/parent [:block/uuid page-uuid]
                        :block/page [:block/uuid page-uuid]
                        :block/order "a0"
                        :block/created-at now
                        :block/updated-at now}
                       {:block/uuid child-a-uuid
                        :block/title "child-a"
                        :block/parent [:block/uuid parent-uuid]
                        :block/page [:block/uuid page-uuid]
                        :block/order "a1"
                        :block/created-at now
                        :block/updated-at now}
                       {:block/uuid child-b-uuid
                        :block/title "child-b"
                        :block/parent [:block/uuid parent-uuid]
                        :block/page [:block/uuid page-uuid]
                        :block/order "a2"
                        :block/created-at now
                        :block/updated-at now}])
    {:page-uuid page-uuid
     :parent-uuid parent-uuid
     :child-a-uuid child-a-uuid
     :child-b-uuid child-b-uuid}))

(deftest tx-batch-stale-retract-block-includes-current-descendants-test
  (testing "stale block retract should still delete descendants attached in current db"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          {:keys [parent-uuid child-a-uuid child-b-uuid]} (seed-page-with-block-tree! conn)
          t-before (storage/get-t sql)
          stale-delete-entry {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid parent-uuid]]])
                              :outliner-op :delete-blocks}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (sync-handler/handle-tx-batch! self nil [stale-delete-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (number? (:t response)))
      (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
      (is (nil? (d/entity @conn [:block/uuid child-a-uuid])))
      (is (nil? (d/entity @conn [:block/uuid child-b-uuid]))))))

(deftest tx-batch-stale-retract-page-includes-current-page-tree-test
  (testing "stale page retract should still delete page tree to avoid orphan blocks"
    (let [sql (test-sql/make-sql)
          conn (storage/open-conn sql)
          self #js {:sql sql
                    :conn conn
                    :schema-ready true}
          {:keys [page-uuid parent-uuid child-a-uuid child-b-uuid]} (seed-page-with-block-tree! conn)
          t-before (storage/get-t sql)
          stale-delete-entry {:tx (protocol/tx->transit [[:db/retractEntity [:block/uuid page-uuid]]])
                              :outliner-op :delete-page}
          response (with-redefs [ws/broadcast! (fn [& _] nil)]
                     (sync-handler/handle-tx-batch! self nil [stale-delete-entry] t-before))]
      (is (= "tx/batch/ok" (:type response)))
      (is (number? (:t response)))
      (is (nil? (d/entity @conn [:block/uuid page-uuid])))
      (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
      (is (nil? (d/entity @conn [:block/uuid child-a-uuid])))
      (is (nil? (d/entity @conn [:block/uuid child-b-uuid]))))))

(deftest current-cursor-corrupt-checksum-metadata-is-never-published-test
  (testing "hello/ack fields and a clean snapshot advertise checksums for the actual frozen DB"
    (async done
           (with-memory-sql-async
             (fn [sql]
               (storage/init-schema! sql)
               (let [conn (storage/open-conn sql)
                     page-uuid (random-uuid)
                     block-uuid (random-uuid)
                     _ (d/transact!
                        conn
                        [{:block/uuid page-uuid
                          :block/name "same-cursor-drift-page"
                          :block/title "Same cursor drift page"}
                         {:block/uuid block-uuid
                          :block/title "content remains intact"
                          :block/page [:block/uuid page-uuid]
                          :block/parent [:block/uuid page-uuid]
                          :block/order "a0"}])
                     self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                               :sql sql
                               :conn conn
                               :schema-ready true}
                     current-t (storage/get-t sql)
                     expected-legacy
                     (sync-checksum/recompute-checksum @conn)
                     expected-versioned
                     (sync-checksum/recompute-server-checksum @conn)
                     corrupt-legacy
                     (if (= expected-legacy "0000000000000000")
                       "ffffffffffffffff"
                       "0000000000000000")
                     corrupt-versioned
                     (if (= expected-versioned "0000000000000000")
                       "ffffffffffffffff"
                       "0000000000000000")
                     _ (storage/set-checksum! sql corrupt-legacy)
                     _ (storage/set-server-checksum!
                        sql corrupt-versioned current-t)
                     response-fields
                     (sync-handler/checksum-response-fields self)
                     {:keys [request url]} (request-url)]
                 (-> (p/with-redefs
                       [sync-handler/<ready-for-sync?
                        (fn [_self _graph-id] (p/resolved true))]
                       (p/let [response
                               (sync-handler/handle
                                {:self self
                                 :request request
                                 :url url
                                 :route {:handler :sync/snapshot-download-v2}})
                               metadata (json-body response)]
                         (is (= expected-legacy (:checksum response-fields))
                             "wire checksum must describe the live DB, not corrupt stored metadata")
                         (is (= expected-versioned
                                (:server-checksum response-fields))
                             "versioned wire checksum must be independently validated at the same cursor")
                         (is (= sync-checksum/server-checksum-version
                                (:checksum-version response-fields)))
                         (is (= expected-legacy (:checksum metadata))
                             "clean snapshot finalize receives the checksum of its frozen rows")
                         (is (= current-t (:t metadata)))
                         (is (= expected-legacy (storage/get-checksum sql))
                             "validated legacy metadata is persisted for subsequent responses")
                         (is (= expected-versioned
                                (storage/get-server-checksum sql))
                             "validated versioned metadata is persisted for subsequent responses")
                         (is (= current-t
                                (storage/get-server-checksum-t sql)))))
                     (p/then (fn [] (done)))
                     (p/catch (fn [error]
                                (is false (str error))
                                (done))))))))))

(deftest upgrade-from-legacy-metadata-keeps-v1-checksum-and-adds-v2-test
  (testing "a .4/v1 client keeps its historical checksum while the upgraded server migrates v2 metadata"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              _ (d/transact!
                 conn
                 [{:block/uuid page-uuid
                   :block/name "legacy-upgrade-page"
                   :block/title "Legacy upgrade page"}])
              current-t (storage/get-t sql)
              expected-legacy (sync-checksum/recompute-checksum @conn)
              expected-versioned
              (sync-checksum/recompute-server-checksum @conn)
              _ (storage/set-checksum! sql expected-legacy)
              _ (storage/set-server-checksum! sql nil current-t)
              self #js {:sql sql
                        :conn nil
                        :schema-ready true}
              fields (sync-handler/checksum-response-fields self)]
          (is (= expected-legacy (:checksum fields))
              "legacy clients must still receive the unchanged v1 field")
          (is (= sync-checksum/server-checksum-version
                 (:checksum-version fields)))
          (is (= expected-versioned (:server-checksum fields))
              "new clients receive an additive migrated field")
          (is (= expected-legacy (storage/get-checksum sql))
              "v2 migration must not rewrite legacy metadata")
          (is (= current-t (storage/get-server-checksum-t sql))))))))

(deftest rebase-backlog-followed-by-large-delete-keeps-wire-checksums-recomputable-test
  (testing "thirteen rebases plus one >500-datom delete converge incrementally and after restart"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              page-uuid (random-uuid)
              parent-uuid (random-uuid)
              child-uuids (vec (repeatedly 520 random-uuid))
              _ (d/transact!
                 conn
                 (vec
                  (concat
                   [{:block/uuid page-uuid
                     :block/name "backlog-large-delete-page"
                     :block/title "Backlog large delete page"}
                    {:block/uuid parent-uuid
                     :block/title "delete root"
                     :block/page [:block/uuid page-uuid]
                     :block/parent [:block/uuid page-uuid]
                     :block/order "a0"
                     :block/created-at 1
                     :block/updated-at 1}]
                   (map-indexed
                    (fn [idx child-uuid]
                      {:block/uuid child-uuid
                       :block/title (str "delete child " idx)
                       :block/page [:block/uuid page-uuid]
                       :block/parent [:block/uuid parent-uuid]
                       :block/order (str "a" (inc idx))
                       :block/created-at (+ idx 2)
                       :block/updated-at (+ idx 2)})
                    child-uuids))))
              self #js {:sql sql
                        :conn conn
                        :schema-ready true}
              initial-fields (sync-handler/checksum-response-fields self)
              t-before (storage/get-t sql)
              _ (storage/set-checksum!
                 sql
                 (if (= "0000000000000000" (:checksum initial-fields))
                   "ffffffffffffffff"
                   "0000000000000000"))
              _ (storage/set-server-checksum!
                 sql
                 (if (= "0000000000000000"
                        (:server-checksum initial-fields))
                   "ffffffffffffffff"
                   "0000000000000000")
                 t-before)
              rebase-entries
              (mapv
               (fn [idx]
                 {:tx (protocol/tx->transit
                       [[:db/add [:block/uuid page-uuid]
                         :block/updated-at
                         (+ 2000 idx)]])
                  :tx-id (random-uuid)
                  :outliner-op :rebase})
               (range 13))
              delete-entry
              {:tx (protocol/tx->transit
                    [[:db/retractEntity [:block/uuid parent-uuid]]])
               :tx-id (random-uuid)
               :outliner-op :delete-blocks}
              response
              (with-redefs [ws/broadcast! (fn [& _] nil)]
                (sync-handler/handle-tx-batch!
                 self nil (conj rebase-entries delete-entry) t-before))
              recomputed-legacy
              (sync-checksum/recompute-checksum @conn)
              recomputed-versioned
              (sync-checksum/recompute-server-checksum @conn)
              restarted-self #js {:sql sql
                                   :conn nil
                                   :schema-ready true}
              restart-fields
              (sync-handler/checksum-response-fields restarted-self)]
          (is (= "tx/batch/ok" (:type response)))
          (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
          (is (every? nil?
                      (map #(d/entity @conn [:block/uuid %])
                           child-uuids)))
          (is (= recomputed-legacy (:checksum response))
              "tx/batch/ok must not extend corrupt legacy metadata")
          (is (= recomputed-versioned (:server-checksum response))
              "tx/batch/ok must not extend same-cursor corrupt versioned metadata")
          (is (= recomputed-legacy (storage/get-checksum sql))
              "incremental legacy metadata must converge")
          (is (= recomputed-versioned
                 (storage/get-server-checksum sql))
              "incremental versioned metadata must converge")
          (is (= (:t response)
                 (storage/get-server-checksum-t sql)))
          (is (= recomputed-legacy (:checksum restart-fields))
              "restart hello fields must not revive a stale checksum")
          (is (= recomputed-versioned
                 (:server-checksum restart-fields))
              "restart hello fields must not trigger repair-required"))))))

(deftest sync-pull-is-blocked-when-graph-is-not-ready-for-use-test
  (async done
         (let [self #js {:env #js {"DB" :db}
                         :sql (empty-sql)}
               {:keys [request url]} (request-url "/sync/graph-1/pull?graph-id=graph-1&since=0")]
           (-> (p/with-redefs [index/<graph-ready-for-use? (fn [_db _graph-id]
                                                             (p/resolved false))]
                 (p/let [resp (sync-handler/handle {:self self
                                                    :request request
                                                    :url url
                                                    :route {:handler :sync/pull}})
                         text (.text resp)
                         body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                   (is (= 409 (.-status resp)))
                   (is (= "graph not ready" (:error body)))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest snapshot-download-is-blocked-when-graph-is-not-ready-for-use-test
  (async done
         (let [bucket #js {:put (fn [& _]
                                  (throw (js/Error. "should-not-upload-snapshot")))}
               self #js {:env #js {"DB" :db
                                   "LOGSEQ_SYNC_ASSETS" bucket}
                         :sql (empty-sql)}
               {:keys [request url]} (request-url)]
           (-> (p/with-redefs [index/<graph-ready-for-use? (fn [_db _graph-id]
                                                             (p/resolved false))]
                 (p/let [resp (sync-handler/handle {:self self
                                                    :request request
                                                    :url url
                                                    :route {:handler :sync/snapshot-download-v2}})
                         text (.text resp)
                         body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                   (is (= 409 (.-status resp)))
                   (is (= "graph not ready" (:error body)))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest sync-http-errors-do-not-expose-internal-details-test
  (async done
         (let [self #js {}
               request (js/Request. "http://localhost/health")]
           (-> (p/with-redefs [sync-handler/handle
                               (fn [_request-context]
                                 (js/Promise.reject
                                  (ex-info "secret sync detail"
                                           {:sql "select private_data"})))]
                 (p/let [response (sync-handler/handle-http self request)
                         text (.text response)
                         body (js->clj (js/JSON.parse text) :keywordize-keys true)]
                   (is (= 500 (.-status response)))
                   (is (= {:error "server error"} body))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(defn- seed-tx-batch-scale-graph!
  [sql conn block-count]
  (let [page-uuid (random-uuid)
        block-uuids (mapv (fn [_] (random-uuid)) (range block-count))
        tx-data (into [{:block/uuid page-uuid
                        :block/name "tx-batch-scale"
                        :block/title "Tx batch scale"}]
                      (map-indexed
                       (fn [idx block-uuid]
                         {:block/uuid block-uuid
                          :block/title (str "seed-" idx)
                          :block/order (str "a" idx)
                          :block/parent [:block/uuid page-uuid]
                          :block/page [:block/uuid page-uuid]})
                       block-uuids))]
    ;; Fixture construction is not part of the measured request. Persist the
    ;; graph without creating one enormous synthetic client tx, then establish
    ;; the same verified checksum metadata a healthy Durable Object has before
    ;; receiving tx/batch.
    (d/transact! conn tx-data {:db-sync/skip-tx-log? true})
    (storage/set-t! sql 0)
    (storage/set-checksum! sql (sync-checksum/recompute-checksum @conn))
    (storage/set-server-checksum!
     sql
     (sync-checksum/recompute-server-checksum @conn)
     0)
    (storage/mark-checksum-metadata-verified! sql 0)
    {:page-uuid page-uuid
     :block-uuid (first block-uuids)}))

(deftest small-tx-batch-does-not-scan-the-whole-graph-per-entry-test
  (testing "11 small edits on a large verified graph have request-sized checksum work"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              self #js {:sql sql :conn conn :schema-ready true}
              {:keys [block-uuid]}
              (seed-tx-batch-scale-graph! sql conn 2048)
              entries
              (mapv (fn [idx]
                      {:tx-id (random-uuid)
                       :tx (protocol/tx->transit
                            [[:db/add [:block/uuid block-uuid]
                              :block/title (str "edit-" idx)]])
                       :outliner-op :save-block})
                    (range 11))
              whole-graph-validations (atom 0)
              server-db-v2-valid?*
              #_{:clj-kondo/ignore [:private-call]}
              sync-checksum/server-db-v2-valid?
              response
              (with-redefs
                [sync-checksum/server-db-v2-valid?
                 (fn [db]
                   (swap! whole-graph-validations inc)
                   (server-db-v2-valid?* db))
                 ws/broadcast! (fn [& _] nil)]
                (sync-handler/handle-tx-batch!
                 self nil entries 0))]
          (is (= "tx/batch/ok" (:type response)))
          (is (= 11 (:t response)))
          (is (= "edit-10"
                 (:block/title
                  (d/entity @conn [:block/uuid block-uuid]))))
          (is (zero? @whole-graph-validations)
              (str "a verified small batch must not enumerate every block; observed "
                   @whole-graph-validations
                   " whole-graph versioned-checksum validations (each walks "
                   ":avet/:block/uuid) for 11 entries")))))))

(defn- cas-title-entry
  [block-uuid from-title to-title tx-id]
  {:tx-id tx-id
   :tx (protocol/tx->transit
        [[:db.fn/cas [:block/uuid block-uuid]
          :block/title from-title to-title]])
   :outliner-op :save-block})

(deftest reset-after-partial-commit-retries-eleven-txs-exactly-once-test
  (testing "a reset/lost response can replay all stable tx ids without duplicating or rejecting committed work"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              block-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid block-uuid
                                    :block/name "ack-loss"
                                    :block/title "state-0"}])
              initial-t (storage/get-t sql)
              tx-ids (mapv (fn [_] (random-uuid)) (range 11))
              entries (mapv (fn [idx tx-id]
                              (cas-title-entry
                               block-uuid
                               (str "state-" idx)
                               (str "state-" (inc idx))
                               tx-id))
                            (range 11)
                            tx-ids)
              apply-entry*
              #_{:clj-kondo/ignore [:private-call]}
              sync-handler/apply-tx-entry!
              apply-attempts (atom 0)
              interrupted-response
              (with-redefs
                [sync-handler/apply-tx-entry!
                 (fn
                   ([conn* entry]
                    (apply-entry* conn* entry))
                   ([self* conn* entry context]
                    (if (= 6 (swap! apply-attempts inc))
                      (throw (js/Error. "simulated Durable Object reset"))
                      (apply-entry* self* conn* entry context))))
                 ws/broadcast! (fn [& _] nil)]
                (sync-handler/handle-tx-batch!
                 #js {:sql sql :conn conn :schema-ready true}
                 nil entries initial-t))]
          ;; The response is deliberately treated as lost. These assertions
          ;; only prove the reset point left five durable commits behind.
          (is (= "tx/reject" (:type interrupted-response)))
          (is (= (take 5 tx-ids)
                 (:success-tx-ids interrupted-response)))
          (is (= (nth tx-ids 5)
                 (:failed-tx-id interrupted-response)))
          (is (= "state-5"
                 (:block/title
                  (d/entity @conn [:block/uuid block-uuid]))))
          (is (= (+ initial-t 5) (storage/get-t sql)))

          ;; Model a fresh DO after the reset. The client did not receive an
          ;; ACK, so it must be safe to send the complete original batch again.
          (let [retry-t (storage/get-t sql)
                restarted-self #js {:sql sql :conn nil :schema-ready true}
                retry-response
                (with-redefs [ws/broadcast! (fn [& _] nil)]
                  (sync-handler/handle-tx-batch!
                   restarted-self nil entries retry-t))
                restarted-conn (.-conn restarted-self)
                committed (storage/fetch-tx-since sql initial-t)]
            (is (= "tx/batch/ok" (:type retry-response)))
            (is (= (+ initial-t 11) (:t retry-response))
                "the five pre-reset entries must not advance t twice")
            (is (= 11 (count committed))
                "each logical tx must have one durable tx_log row")
            (is (= "state-11"
                   (:block/title
                    (d/entity @restarted-conn
                              [:block/uuid block-uuid]))))))))))

(deftest legacy-tx-batch-without-tx-id-remains-compatible-test
  (testing "the unversioned v1 tx/batch shape keeps accepting entries without tx-id"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              self #js {:sql sql :conn conn :schema-ready true}
              block-uuid (random-uuid)
              _ (d/transact! conn [{:block/uuid block-uuid
                                    :block/name "legacy-batch"
                                    :block/title "before"}])
              t-before (storage/get-t sql)
              response
              (with-redefs [ws/broadcast! (fn [& _] nil)]
                (sync-handler/handle-tx-batch!
                 self nil
                 [{:tx (protocol/tx->transit
                        [[:db/add [:block/uuid block-uuid]
                          :block/title "after"]])
                   :outliner-op :save-block}]
                 t-before))]
          (is (= "tx/batch/ok" (:type response)))
          (is (= (inc t-before) (:t response)))
          (is (= "after"
                 (:block/title
                  (d/entity @conn [:block/uuid block-uuid])))))))))
