(ns logseq.outliner.op-construct-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.common.uuid :as common-uuid]
            [logseq.db :as ldb]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.op.construct :as op-construct]
            [logseq.outliner.page :as outliner-page]
            [logseq.outliner.property :as outliner-property]))

(defn- run-direct-outdent
  [conn block]
  (let [{:keys [tx-data]}
        (#'outliner-core/indent-outdent-blocks
         conn [block] false
         :parent-original nil
         :logical-outdenting? nil)
        tx-report (d/with @conn tx-data {})]
    {:tx-data (:tx-data tx-report)
     :db-after (:db-after tx-report)}))

(deftest derive-history-outliner-ops-canonicalizes-create-page-and-builds-delete-inverse-test
  (testing "create-page forward op keeps created uuid and reverse op deletes that page"
    (let [conn (db-test/create-conn-with-blocks {:pages-and-blocks []})
          page-uuid (random-uuid)
          tx-data [{:e 1 :a :block/title :v "Created Page" :added true}
                   {:e 1 :a :block/uuid :v page-uuid :added true}]
          tx-meta {:outliner-op :create-page
                   :outliner-ops [[:create-page ["Created Page"
                                                 {:redirect? false
                                                  :split-namespace? true
                                                  :tags ()}]]]}
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn tx-data tx-meta)]
      (is (= :create-page (ffirst forward-outliner-ops)))
      (is (= page-uuid (get-in forward-outliner-ops [0 1 1 :uuid])))
      (is (= [[:delete-page [page-uuid {}]]]
             inverse-outliner-ops)))))

(deftest namespace-create-history-replays-created-identities-test
  (doseq [class? [false true]]
    (let [conn (db-test/create-conn-with-blocks {:pages-and-blocks []})
          db-before @conn
          {:keys [tx-data tx-meta page-uuid]}
          (outliner-page/create db-before "ns/child" {:class? class? :split-namespace? true})
          report (d/with db-before tx-data tx-meta)
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops db-before (:db-after report)
                                                  (:tx-data report) tx-meta)
          [_ [title opts]] (first forward-outliner-ops)
          replay (outliner-page/create db-before title opts)
          db-replayed (:db-after (d/with db-before (:tx-data replay) (:tx-meta replay)))
          identities (fn [db]
                       (into {} (keep (fn [id]
                                        (let [node (d/entity db id)]
                                          (when (contains? #{"ns" "child"} (:block/title node))
                                            [(:block/title node) (:block/uuid node)]))))
                             (d/q '[:find [?e ...] :where [?e :block/uuid]] db)))]
      (is (= page-uuid (:uuid opts)))
      (is (= 2 (count (:namespace-page-uuids opts))))
      (is (= (mapv (fn [id] [:delete-page [id {}]]) (reverse (:namespace-page-uuids opts)))
             inverse-outliner-ops))
      (is (= (identities (:db-after report)) (identities db-replayed))))))

(deftest deleted-inline-link-restore-payload-keeps-raw-title-test
  (let [class-id (random-uuid)
        block-id (random-uuid)
        raw-title (str "watch [[" class-id "]]")
        conn (db-test/create-conn-with-blocks
              {:classes {:Movie {:block/uuid class-id}}
               :pages-and-blocks [{:page {:block/title "Synthetic page"}
                                   :blocks [{:block/uuid block-id :block/title raw-title}]}]})
        {:keys [inverse-outliner-ops]}
        (op-construct/derive-history-outliner-ops
         @conn @conn [] {:outliner-op :delete-blocks
                         :outliner-ops [[:delete-blocks [[block-id] {}]]]})
        payload (get-in inverse-outliner-ops [0 1 0 0])]
    (is (= raw-title (:block/title payload)))
    (is (= #{[:block/uuid class-id]} (:block/refs payload)))))

(deftest namespace-create-history-keeps-reused-parent-and-inline-link-identities-test
    (let [class-id (random-uuid)
          conn (db-test/create-conn-with-blocks {:classes {:Movie {:block/uuid class-id}}
                                                :pages-and-blocks []})
          root (outliner-page/create @conn "Root" {})
          _ (d/transact! conn (:tx-data root) (:tx-meta root))
          db-before @conn
          title (str "ROOT/Middle/Watch [[" class-id "]]")
          {:keys [tx-data tx-meta page-uuid]}
          (outliner-page/create db-before title {:split-namespace? true
                                                 :properties {:block/refs [[:block/uuid class-id]]}})
          report (d/with db-before tx-data tx-meta)
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops db-before (:db-after report) (:tx-data report) tx-meta)
          opts (get-in forward-outliner-ops [0 1 1])
          ids (:namespace-page-uuids opts)
          replay (outliner-page/create db-before title opts)
          db-replayed (:db-after (d/with db-before (:tx-data replay) (:tx-meta replay)))]
      (is (= page-uuid (:uuid opts)))
      (is (= 3 (count ids)))
      (is (nil? (first ids)))
      (is (= [[:delete-page [(last ids) {}]] [:delete-page [(second ids) {}]]]
             inverse-outliner-ops))
      (is (= (:block/uuid (ldb/get-page db-before "Root"))
             (:block/uuid (ldb/get-page db-replayed "Root"))))
      (doseq [id (rest ids)]
        (is (= (:block/name (d/entity (:db-after report) [:block/uuid id]))
               (:block/name (d/entity db-replayed [:block/uuid id])))))))

(deftest namespace-create-history-selects-the-matching-class-parent-test
  (let [conn (db-test/create-conn-with-blocks {:pages-and-blocks []})
        create! (fn [title]
                  (let [result (outliner-page/create @conn title {:class? true :split-namespace? true})]
                    (d/transact! conn (:tx-data result) (:tx-meta result))))
        _ (create! "Other")
        _ (create! "Root/Middle")
        middle (ldb/get-page @conn "Middle")
        other (ldb/get-page @conn "Other")
        _ (d/transact! conn [[:db/add (:db/id middle) :logseq.property.class/extends (:db/id other)]])
        db-before @conn
        {:keys [tx-data page-uuid]}
        (outliner-page/create db-before "Leaf" {:class? true})
        ;; The helper consumes the resulting transaction, independently of the
        ;; interpreter's Entity assoc support in the namespace producer.
        tx-meta {:outliner-op :create-page
                 :outliner-ops [[:create-page ["ROOT/middle/Leaf" {:class? true :split-namespace? true}]]]}
        report (d/with db-before
                       (conj (vec tx-data) [:db/add [:block/uuid page-uuid]
                                           :logseq.property.class/extends (:db/id middle)]) tx-meta)
        {:keys [forward-outliner-ops inverse-outliner-ops]}
        (op-construct/derive-history-outliner-ops db-before (:db-after report) (:tx-data report) tx-meta)]
    (is (= [nil nil page-uuid] (get-in forward-outliner-ops [0 1 1 :namespace-page-uuids])))
    (is (= [[:delete-page [page-uuid {}]]] inverse-outliner-ops))))

(deftest derive-history-outliner-ops-handles-replace-empty-target-insert-inverse-test
  (testing "replace-empty-target insert keeps source uuid and inverse deletes target placeholder"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title ""}]}]})
          empty-target (db-test/find-block-by-content @conn "")
          parent-uuid (random-uuid)
          child-uuid (random-uuid)
          tx-meta {:outliner-op :insert-blocks
                   :outliner-ops [[:insert-blocks [[{:block/uuid parent-uuid
                                                     :block/title "paste parent"}
                                                    {:block/uuid child-uuid
                                                     :block/title "paste child"
                                                     :block/parent [:block/uuid parent-uuid]}]
                                                   (:db/id empty-target)
                                                   {:sibling? true
                                                    :replace-empty-target? true
                                                    :outliner-op :paste}]]]}
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)]
      (is (= parent-uuid
             (get-in forward-outliner-ops [0 1 0 0 :block/uuid])))
      (is (= true (get-in forward-outliner-ops [0 1 2 :keep-uuid?])))
      (is (some #(and (= :delete-blocks (first %))
                      (= [(:block/uuid empty-target)]
                         (vec (get-in % [1 0]))))
                (remove nil? inverse-outliner-ops)))
      (is (not-any? #(= :save-block (first %))
                    (remove nil? inverse-outliner-ops))))))

(deftest derive-history-outliner-ops-builds-upsert-property-inverse-delete-page-test
  (testing "upsert-property with qualified keyword builds delete-page inverse"
    (let [conn (db-test/create-conn-with-blocks {:pages-and-blocks []})
          property-id :user.property/custom-prop
          tx-meta {:outliner-op :upsert-property
                   :outliner-ops [[:upsert-property [property-id
                                                     {:logseq.property/type :default}
                                                     {:property-name "custom-prop"}]]]}
          {:keys [inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)
          expected-page-uuid (common-uuid/gen-uuid :db-ident-block-uuid property-id)]
      (is (= [[:delete-page [expected-page-uuid {}]]]
             inverse-outliner-ops)))))

(deftest derive-history-outliner-ops-new-property-with-existing-title-keeps-created-ident-test
  (let [conn (db-test/create-conn-with-blocks
              {:properties {:rating {:logseq.property/type :number}}
               :pages-and-blocks []})
        db-before @conn
        reports (atom [])]
    (d/listen! conn ::property-identity #(swap! reports conj %))
    (try
      (let [created (outliner-property/upsert-property!
                     conn nil {:logseq.property/type :default} {:property-name "rating"})
            {:keys [forward-outliner-ops inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             db-before @conn (mapcat :tx-data @reports)
             {:outliner-op :upsert-property
              :outliner-ops [[:upsert-property [nil {:logseq.property/type :default}
                                               {:property-name "rating"}]]]})]
        (is (not= :user.property/rating (:db/ident created)))
        (is (= (:db/ident created) (get-in forward-outliner-ops [0 1 0])))
        (is (= [[:delete-page [(:block/uuid created) {}]]] inverse-outliner-ops)))
      (finally (d/unlisten! conn ::property-identity)))))

(deftest derive-history-outliner-ops-upsert-property-update-builds-schema-restore-inverse-test
  (testing "upsert-property on existing property builds inverse upsert-property restore op"
    (let [conn (db-test/create-conn-with-blocks
                {:properties {:p-many {:logseq.property/type :default}}
                 :pages-and-blocks []})
          property-id :user.property/p-many
          _ (d/transact! conn [[:db/add property-id :logseq.property/classes :logseq.class/Root]])
          before-property (d/entity @conn property-id)
          expected-schema (-> (db-property/get-property-schema (into {} before-property))
                              (update :logseq.property/classes
                                      (fn [classes]
                                        (some->> classes
                                                 (map (fn [class]
                                                        (if-let [class-uuid (:block/uuid class)]
                                                          [:block/uuid class-uuid]
                                                          (:db/ident class))))
                                                 set))))
          tx-meta {:outliner-op :upsert-property
                   :outliner-ops [[:upsert-property [property-id
                                                     {:logseq.property/type :node
                                                      :db/cardinality :many}
                                                     {}]]]}
          {:keys [inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)]
      (is (= [[:upsert-property [property-id expected-schema {:property-name "p-many"}]]]
             inverse-outliner-ops))
      (is (every? (fn [class-ref]
                    (or (keyword? class-ref)
                        (and (vector? class-ref)
                             (= :block/uuid (first class-ref))
                             (uuid? (second class-ref)))))
                  (get-in inverse-outliner-ops [0 1 1 :logseq.property/classes]))))))

(deftest derive-history-outliner-ops-upsert-property-omits-many-to-one-with-values-test
  (testing "inverse of one-to-many with existing values omits :db/cardinality"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "b1" :build/properties {:note "text 1"}}]}])
          property-id :user.property/note
          before-property (d/entity @conn property-id)
          expected-schema (-> (db-property/get-property-schema (into {} before-property))
                              (dissoc :db/cardinality))
          tx-meta {:outliner-op :upsert-property
                   :outliner-ops [[:upsert-property [property-id
                                                     {:logseq.property/type :default
                                                      :db/cardinality :many}
                                                     {}]]]}
          {:keys [inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)]
      (is (= :db.cardinality/one (:db/cardinality before-property)))
      (is (= [[:upsert-property [property-id expected-schema {:property-name "note"}]]]
             inverse-outliner-ops))
      (is (nil? (get-in inverse-outliner-ops [0 1 1 :db/cardinality]))))))

(deftest derive-history-outliner-ops-delete-blocks-inverse-avoids-self-target-test
  (testing "delete-blocks inverse falls back to parent target when left sibling resolves to self"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "parent"
                             :build/children [{:block/title "child"}]}]}]})
          child (db-test/find-block-by-content @conn "child")
          child-id (:db/id child)
          child-uuid (:block/uuid child)
          parent-uuid (some-> child :block/parent :block/uuid)
          tx-meta {:outliner-op :delete-blocks
                   :outliner-ops [[:delete-blocks [[child-id] {}]]]}]
      ;; Simulate stale sibling lookup returning the same entity as the deleted root.
      (with-redefs [ldb/get-left-sibling (fn [_] child)]
        (let [{:keys [inverse-outliner-ops]}
              (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)
              insert-op (first inverse-outliner-ops)]
          (is (= :insert-blocks (first insert-op)))
          (is (= parent-uuid
                 (get-in insert-op [1 1])))
          (is (= false (get-in insert-op [1 2 :sibling?])))
          (is (not= child-uuid
                    (get-in insert-op [1 1]))))))))

(deftest compound-history-inverses-run-in-reverse-dependency-order-test
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks
               [{:page {:block/title "page"}
                 :blocks [{:block/title "target"}
                          {:block/title "source"
                           :build/children [{:block/title "nested"}]}]}]})
        target (db-test/find-block-by-content @conn "target")
        source (db-test/find-block-by-content @conn "source")
        nested (db-test/find-block-by-content @conn "nested")
        tx-meta {:outliner-op :delete-blocks
                 :outliner-ops [[:move-blocks [[(:db/id nested)]
                                                (:db/id target)
                                                {:sibling? false}]]
                                [:delete-blocks [[(:db/id source)] {}]]
                                [:save-block [(assoc (into {} target) :block/title "combined") nil]]]}
        {:keys [inverse-outliner-ops]}
        (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)]
    (is (= [:save-block :insert-blocks :move-blocks]
           (mapv first inverse-outliner-ops)))))

(deftest derive-history-outliner-ops-delete-blocks-with-stale-id-keeps-id-test
  (testing "delete-blocks derive-history keeps unresolved numeric ids in forward ops"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "parent"
                             :build/children [{:block/title "child"}]}]}]})
          child (db-test/find-block-by-content @conn "child")
          child-uuid (:block/uuid child)
          stale-id 99999999
          tx-meta {:outliner-op :delete-blocks
                   :outliner-ops [[:delete-blocks [[(:db/id child) stale-id] {}]]]}
          {:keys [forward-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn [] tx-meta)]
      (is (= [[:delete-blocks [[child-uuid stale-id] {}]]]
             forward-outliner-ops)))))

(deftest derive-history-outliner-ops-delete-blocks-prefers-retracted-tx-data-ids-test
  (testing "delete-blocks derive-history should prefer tx-data retractEntity ids over stale selection ids"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "parent"
                             :build/children [{:block/title "child"}]}]}]})
          child (db-test/find-block-by-content @conn "child")
          child-id (:db/id child)
          child-uuid (:block/uuid child)
          stale-id 99999999
          tx-report (d/with @conn [[:db/retractEntity child-id]] {})
          tx-meta {:outliner-op :delete-blocks
                   :outliner-ops [[:delete-blocks [[child-id stale-id] {}]]]}
          {:keys [forward-outliner-ops]}
          (op-construct/derive-history-outliner-ops
           @conn (:db-after tx-report) (:tx-data tx-report) tx-meta)]
      (is (= [[:delete-blocks [[child-uuid] {}]]]
             forward-outliner-ops)))))

(deftest derive-history-outliner-ops-move-blocks-resolves-target-id-from-tx-data-test
  (testing "move-blocks should resolve stale numeric target id using tx-data block uuid"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "child"}]}]})
          child (db-test/find-block-by-content @conn "child")
          stale-target-id 9999999
          target-uuid (random-uuid)
          tx-data [{:e stale-target-id :a :block/uuid :v target-uuid :added false}]
          tx-meta {:outliner-op :move-blocks
                   :outliner-ops [[:move-blocks [[(:db/id child)] stale-target-id {:sibling? true}]]]}
          {:keys [forward-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn tx-data tx-meta)]
      (is (= target-uuid
             (get-in forward-outliner-ops [0 1 1]))))))

(deftest derive-history-outliner-ops-save-block-resolves-retracted-ref-id-from-db-before-test
  (testing "save-block should canonicalize ref attrs using db-before when target ref is retracted in current tx"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "child"}]}]})
          child (db-test/find-block-by-content @conn "child")
          child-uuid (:block/uuid child)
          tag-uuid (random-uuid)
          _ (d/transact! conn [{:db/id -1
                                :block/uuid tag-uuid
                                :block/title "Tag"
                                :block/name "tag"
                                :block/tags :logseq.class/Tag}])
          tag-id (:db/id (d/entity @conn [:block/uuid tag-uuid]))
          tx-report (d/with @conn [[:db/retractEntity tag-id]] {})
          tx-meta {:outliner-op :save-block
                   :outliner-ops [[:save-block [{:block/uuid child-uuid
                                                 :block/tags #{tag-id}}
                                                {}]]]}
          {:keys [forward-outliner-ops]}
          (op-construct/derive-history-outliner-ops
           @conn (:db-after tx-report) (:tx-data tx-report) tx-meta)]
      (is (= #{[:block/uuid tag-uuid]}
             (get-in forward-outliner-ops [0 1 0 :block/tags]))))))

(deftest derive-history-outliner-ops-insert-blocks-canonicalizes-tag-collections-test
  (let [first-uuid (random-uuid)
        second-uuid (random-uuid)
        conn (db-test/create-conn-with-blocks
              {:classes {:First {:block/uuid first-uuid}
                         :Second {:block/uuid second-uuid}}
               :pages-and-blocks []})
        _ (d/transact! conn [{:db/ident :logseq.class/Class
                             :block/uuid (random-uuid)
                             :block/title "Synthetic class"}])
        db-before @conn
        first-id (:db/id (d/entity db-before [:block/uuid first-uuid]))
        second-id (:db/id (d/entity db-before [:block/uuid second-uuid]))
        first-ref [:block/uuid first-uuid]
        second-ref [:block/uuid second-uuid]]
    (doseq [[tags expected]
            [[[first-id] #{first-ref}]
             [[first-id second-id] #{first-ref second-ref}]
             [first-ref first-ref]
             [[first-ref second-ref] #{first-ref second-ref}]
             [[:logseq.class/Tag :logseq.class/Class] #{:logseq.class/Tag :logseq.class/Class}]
             [first-id first-ref]
             [#{first-id} #{first-ref}]
             [(list first-id) #{first-ref}]
             [:logseq.class/Tag :logseq.class/Tag]
             [[:db/ident :logseq.class/Tag] [:db/ident :logseq.class/Tag]]]]
      (testing (str "insert history preserves stable tags for " tags)
        (let [block-uuid (random-uuid)
              block {:block/uuid block-uuid
                     :block/title "New class object"
                     :block/parent first-ref
                     :block/tags tags}
              tx-meta {:outliner-op :insert-blocks
                       :outliner-ops [[:insert-blocks [[block] first-uuid {:sibling? false}]]]}
              report (d/with db-before
                             [(assoc block :db/id -1 :block/page first-ref :block/order "a0")]
                             tx-meta)]
          (is (= expected
                 (get-in (op-construct/derive-history-outliner-ops
                          db-before (:db-after report) (:tx-data report) tx-meta)
                         [:forward-outliner-ops 0 1 0 0 :block/tags]))))))))

(deftest sanitize-ref-value-preserves-schema-unique-lookup-refs-test
  (let [db @(db-test/create-conn)]
    (doseq [lookup [[:block/uuid (random-uuid)]
                   [:db/ident :logseq.class/Tag]
                   [:file/path "synthetic.md"]]]
      (is (= lookup (#'op-construct/sanitize-ref-value db lookup))))))

(deftest history-ref-metadata-survives-recanonicalization-test
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks [{:page {:block/title "Existing Reference"}}
                                  {:page {:block/title "Parent"}
                                   :blocks [{:block/title "target"}]}]})
        db-before @conn
        target (db-test/find-block-by-content db-before "target")
        created-uuid (random-uuid)
        created-ref {:block/uuid created-uuid
                     :block/title "Created Reference"
                     :block/name "created reference"
                     :block/tags :logseq.class/Page}
        report (d/with db-before [created-ref])
        db-after (:db-after report)
        existing-ref (select-keys (ldb/get-page db-after "Existing Reference")
                                  [:block/uuid :block/title])
        refs [created-ref existing-ref]
        expected-refs (mapv #(select-keys % [:block/uuid :block/title]) refs)
        block {:block/uuid (random-uuid)
               :block/title (str "Call [[" created-uuid "]]")
               :block/refs refs
               :block.temp/ast-title "discard"}
        payload (#'op-construct/sanitize-block-payload
                 db-after block {:created-uuids [created-uuid]
                                 :tx-data (:tx-data report)})
        resanitized (#'op-construct/sanitize-block-payload db-after payload)
        insert-ops (op-construct/canonicalize-insert-ops
                    db-after (:tx-data report)
                    [[:insert-blocks [[block] (:block/uuid target) {:sibling? true}]]])
        insert-payload (get-in insert-ops [0 1 0 0])
        rewritten (op-construct/rewrite-block-title-with-retracted-refs db-before payload)]
    (is (= (set (map (fn [ref] [:block/uuid (:block/uuid ref)]) refs))
           (:block/refs payload)))
    (is (= expected-refs (get payload op-construct/rebase-refs-key)))
    (is (= [created-uuid] (get payload op-construct/rebase-created-refs-key)))
    (is (not (contains? payload :block.temp/ast-title)))
    (is (= payload resanitized))
    (is (= expected-refs (get insert-payload op-construct/rebase-refs-key)))
    (is (= [created-uuid] (get insert-payload op-construct/rebase-created-refs-key)))
    (is (= insert-ops (op-construct/canonicalize-insert-ops
                      db-after (:tx-data report) insert-ops)))
    (is (= (:block/title payload) (:block/title rewritten)))
    (is (not (contains? rewritten op-construct/rebase-refs-key)))))

(deftest derive-history-outliner-ops-insert-blocks-resolves-retracted-ref-id-from-tx-data-test
  (testing "insert-blocks should canonicalize block ref attrs when ref entity is retracted in current tx"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "target"}]}]})
          target (db-test/find-block-by-content @conn "target")
          target-id (:db/id target)
          tag-uuid (random-uuid)
          _ (d/transact! conn [{:db/id -1
                                :block/uuid tag-uuid
                                :block/title "Tag"
                                :block/name "tag"
                                :block/tags :logseq.class/Tag}])
          tag-id (:db/id (d/entity @conn [:block/uuid tag-uuid]))
          tx-report (d/with @conn [[:db/retractEntity tag-id]] {})
          block-uuid (random-uuid)]
      (doseq [tags [#{tag-id} [tag-id]]]
        (let [tx-meta {:outliner-op :insert-blocks
                       :outliner-ops [[:insert-blocks [[{:block/uuid block-uuid
                                                       :block/title "new child"
                                                       :block/tags tags}]
                                                     target-id
                                                     {:sibling? true}]]]}]
          (is (= #{[:block/uuid tag-uuid]}
                 (get-in (op-construct/derive-history-outliner-ops
                          @conn (:db-after tx-report) (:tx-data tx-report) tx-meta)
                         [:forward-outliner-ops 0 1 0 0 :block/tags]))))))))

(deftest derive-history-outliner-ops-apply-template-undo-canonicalizes-template-block-refs-test
  (testing "apply-template undo explicit inverse should canonicalize nested template-block ref attrs"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "template"}
                            {:block/title "target"}]}]})
          template (db-test/find-block-by-content @conn "template")
          target (db-test/find-block-by-content @conn "target")
          template-uuid (:block/uuid template)
          target-uuid (:block/uuid target)
          inserted-uuid (random-uuid)
          tag-uuid (random-uuid)
          _ (d/transact! conn [{:db/id -1
                                :block/uuid tag-uuid
                                :block/title "Tag"
                                :block/name "tag"
                                :block/tags :logseq.class/Tag}])
          tag-id (:db/id (d/entity @conn [:block/uuid tag-uuid]))
          tx-report (d/with @conn [[:db/retractEntity tag-id]] {})
          tx-meta {:outliner-op :apply-template
                   :undo? true
                   :db-sync/inverse-outliner-ops
                   [[:apply-template [template-uuid
                                      target-uuid
                                      {:template-blocks [{:block/uuid inserted-uuid
                                                          :block/title "inserted"
                                                          :block/tags #{tag-id}}]
                                       :sibling? true}]]]}
          {:keys [inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops
           @conn (:db-after tx-report) (:tx-data tx-report) tx-meta)]
      (is (= #{[:block/uuid tag-uuid]}
             (get-in inverse-outliner-ops [0 1 2 :template-blocks 0 :block/tags]))))))

(deftest derive-history-outliner-ops-apply-template-captures-template-blocks-when-missing-in-op-test
  (testing "apply-template forward op should capture concrete template-blocks even if original op omitted them"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "template"
                             :build/children [{:block/title "template child 1"}
                                              {:block/title "template child 2"}]}
                            {:block/title "target"}]}]})
          template (db-test/find-block-by-content @conn "template")
          template-child-1 (db-test/find-block-by-content @conn "template child 1")
          template-child-2 (db-test/find-block-by-content @conn "template child 2")
          target (db-test/find-block-by-content @conn "target")
          inserted-child-1-uuid (random-uuid)
          inserted-child-2-uuid (random-uuid)
          tx-data [{:e 900001 :a :block/uuid :v inserted-child-1-uuid :added true}
                   {:e 900001 :a :block/parent :v (:db/id (:block/parent target)) :added true}
                   {:e 900002 :a :block/uuid :v inserted-child-2-uuid :added true}
                   {:e 900002 :a :block/parent :v (:db/id (:block/parent target)) :added true}]
          _ (d/transact! conn [[:db/add (:db/id template-child-1) :block/refs (:db/id template-child-2)]])
          tx-meta {:outliner-op :apply-template
                   :outliner-ops [[:apply-template [(:db/id template)
                                                    (:db/id target)
                                                    {:sibling? true}]]]}
          {:keys [forward-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn @conn tx-data tx-meta)]
      (is (= :apply-template (ffirst forward-outliner-ops)))
      (is (= true (get-in forward-outliner-ops [0 1 2 :keep-uuid?])))
      (is (= [inserted-child-1-uuid inserted-child-2-uuid]
             (mapv :block/uuid (get-in forward-outliner-ops [0 1 2 :template-blocks]))))
      (is (= #{[:block/uuid inserted-child-2-uuid]}
             (get-in forward-outliner-ops [0 1 2 :template-blocks 0 :block/refs]))))))

(deftest derive-history-outliner-ops-builds-delete-page-inverse-for-class-property-and-today-page-test
  (testing "delete-page inverse restores hard-retracted class/property/today pages with stable db/ident"
    (let [today (date-time-util/ms->journal-day (js/Date.))
          conn (db-test/create-conn-with-blocks
                {:classes {:Movie {}}
                 :properties {:rating {:logseq.property/type :number}}
                 :pages-and-blocks [{:page {:build/journal today}
                                     :blocks [{:block/title "today child"}]}]})
          class-page (ldb/get-page @conn "Movie")
          property-page (d/entity @conn :user.property/rating)
          today-page (db-test/find-journal-by-journal-day @conn today)
          today-child (db-test/find-block-by-content @conn "today child")
          class-inverse (:inverse-outliner-ops
                         (op-construct/derive-history-outliner-ops
                          @conn @conn [] {:outliner-op :delete-page
                                          :outliner-ops [[:delete-page [(:block/uuid class-page) {}]]]}))
          property-inverse (:inverse-outliner-ops
                            (op-construct/derive-history-outliner-ops
                             @conn @conn [] {:outliner-op :delete-page
                                             :outliner-ops [[:delete-page [(:block/uuid property-page) {}]]]}))
          today-inverse (:inverse-outliner-ops
                         (op-construct/derive-history-outliner-ops
                          @conn @conn [] {:outliner-op :delete-page
                                          :outliner-ops [[:delete-page [(:block/uuid today-page) {}]]]}))]
      (is (some #(= :create-page (first %)) class-inverse))
      (is (some #(= :save-block (first %)) class-inverse))
      (is (= (:db/ident class-page)
             (get-in (some #(when (= :save-block (first %)) %) class-inverse)
                     [1 0 :db/ident])))

      (is (some #(= :upsert-property (first %)) property-inverse))
      (is (some #(= :save-block (first %)) property-inverse))
      (is (= (:db/ident property-page)
             (get-in (some #(when (= :save-block (first %)) %) property-inverse)
                     [1 0 :db/ident])))

      (is (not-any? #(= :restore-recycled (first %)) today-inverse))
      (let [today-insert-op (some #(when (= :insert-blocks (first %)) %) today-inverse)]
        (is (some? today-insert-op))
        (is (= (:block/uuid today-page)
               (get-in today-insert-op [1 1])))
        (is (= (:block/uuid today-child)
               (get-in today-insert-op [1 0 0 :block/uuid])))))))

(deftest derive-history-outliner-ops-builds-inverse-for-all-supported-ops-test
  (let [conn (db-test/create-conn-with-blocks
              {:classes {:c1 {:build/class-properties [:p1]}}
               :properties {:p1 {:logseq.property/type :default}}
               :pages-and-blocks
               [{:page {:block/title "page"}
                 :blocks [{:block/title "parent"
                           :build/children [{:block/title "child-a"}
                                            {:block/title "child-b"}]}
                          {:block/title "prop-block-1"
                           :build/properties {:p1 "before-1"}}
                          {:block/title "prop-block-2"}]}]})
        page (db-test/find-page-by-title @conn "page")
        parent (db-test/find-block-by-content @conn "parent")
        child-a (db-test/find-block-by-content @conn "child-a")
        child-b (db-test/find-block-by-content @conn "child-b")]
    (testing ":save-block"
      (let [{:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn [] {:outliner-op :save-block
                             :outliner-ops [[:save-block [{:block/uuid (:block/uuid child-a)
                                                           :block/title "changed"} {}]]]})]
        (is (= :save-block (ffirst inverse-outliner-ops)))
        (is (= (:block/uuid child-a)
               (get-in inverse-outliner-ops [0 1 0 :block/uuid])))))

    (testing ":insert-blocks"
      (let [inserted-uuid (random-uuid)
            tx-data [{:e 999999 :a :block/uuid :v inserted-uuid :added true}]
            {:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn tx-data {:outliner-op :insert-blocks
                                  :outliner-ops [[:insert-blocks [[{:block/uuid inserted-uuid
                                                                    :block/title "new"}]
                                                                  (:db/id parent)
                                                                  {:sibling? false}]]]})]
        (is (= :delete-blocks (ffirst inverse-outliner-ops)))
        (is (= [inserted-uuid]
               (vec (get-in inverse-outliner-ops [0 1 0]))))))

    (testing ":move-blocks"
      (let [{:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn [] {:outliner-op :move-blocks
                             :outliner-ops [[:move-blocks [[(:db/id child-b)]
                                                           (:db/id parent)
                                                           {:sibling? false}]]]})]
        (is (= :move-blocks (ffirst inverse-outliner-ops)))
        (is (= [(:block/uuid child-b)]
               (get-in inverse-outliner-ops [0 1 0])))))

    (testing ":delete-blocks"
      (let [{:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn [] {:outliner-op :delete-blocks
                             :outliner-ops [[:delete-blocks [[(:db/id child-b)] {}]]]})]
        (is (= :insert-blocks (ffirst inverse-outliner-ops)))
        (is (= (:block/uuid child-b)
               (get-in inverse-outliner-ops [0 1 0 0 :block/uuid])))))

    (testing ":create-page"
      (let [page-uuid (random-uuid)
            tx-data [{:e 1 :a :block/title :v "P2" :added true}
                     {:e 1 :a :block/uuid :v page-uuid :added true}]
            {:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn tx-data {:outliner-op :create-page
                                  :outliner-ops [[:create-page ["P2" {:redirect? false}]]]})]
        (is (= [[:delete-page [page-uuid {}]]]
               inverse-outliner-ops))))

    (testing ":delete-page"
      (let [{:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn [] {:outliner-op :delete-page
                             :outliner-ops [[:delete-page [(:block/uuid page) {}]]]})]
        (is (= [[:restore-recycled [(:block/uuid page)]]]
               inverse-outliner-ops))))

    (testing ":upsert-property"
      (let [property-ident :user.property/test-inverse
            expected-page-uuid (common-uuid/gen-uuid :db-ident-block-uuid property-ident)
            {:keys [inverse-outliner-ops]}
            (op-construct/derive-history-outliner-ops
             @conn @conn [] {:outliner-op :upsert-property
                             :outliner-ops [[:upsert-property [property-ident
                                                               {:logseq.property/type :default}
                                                               {:property-name "test-inverse"}]]]})]
        (is (= [[:delete-page [expected-page-uuid {}]]]
               inverse-outliner-ops))))))

(deftest build-history-action-metadata-direct-outdent-builds-indent-outdent-forward-and-inverse-test
  (testing "direct outdent keeps canonical indent-outdent forward and inverse ops"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "parent"
                             :build/children [{:block/title "child-1"}
                                              {:block/title "child-2"}
                                              {:block/title "child-3"}]}]}]})
          child-3 (db-test/find-block-by-content @conn "child-3")
          {:keys [tx-data db-after]} (run-direct-outdent conn child-3)
          tx-meta {:outliner-op :move-blocks
                   :outliner-ops [[:indent-outdent-blocks [[(:db/id child-3)]
                                                           false
                                                           {:parent-original nil
                                                            :logical-outdenting? nil}]]]}
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn db-after tx-data tx-meta)]
      (is (= [[:indent-outdent-blocks [[(:block/uuid child-3)]
                                       false
                                       {:parent-original nil
                                        :logical-outdenting? nil}]]]
             forward-outliner-ops))
      (is (= [[:indent-outdent-blocks [[(:block/uuid child-3)]
                                       true
                                       {:parent-original nil
                                        :logical-outdenting? nil}]]]
             inverse-outliner-ops)))))

(deftest derive-history-outliner-ops-direct-outdent-with-extra-moved-blocks-keeps-semantic-ops-test
  (testing "direct outdent keeps semantic indent-outdent op and inverse"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks
                 [{:page {:block/title "page"}
                   :blocks [{:block/title "parent"
                             :build/children [{:block/title "child-1"}
                                              {:block/title "child-2"}
                                              {:block/title "child-3"}]}]}]})
          child-2 (db-test/find-block-by-content @conn "child-2")
          {:keys [tx-data db-after]} (run-direct-outdent conn child-2)
          tx-meta {:outliner-op :move-blocks
                   :outliner-ops [[:indent-outdent-blocks [[(:db/id child-2)]
                                                           false
                                                           {:parent-original nil
                                                            :logical-outdenting? nil}]]]}
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (op-construct/derive-history-outliner-ops @conn db-after tx-data tx-meta)]
      (is (= [[:indent-outdent-blocks [[(:block/uuid child-2)]
                                       false
                                       {:parent-original nil
                                        :logical-outdenting? nil}]]]
             forward-outliner-ops))
      (is (= [[:indent-outdent-blocks [[(:block/uuid child-2)]
                                       true
                                       {:parent-original nil
                                        :logical-outdenting? nil}]]]
             inverse-outliner-ops)))))
