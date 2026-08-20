(ns frontend.db.raw-parent-delete-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.db :as ldb]
            [logseq.db-sync.tx-sanitize :as tx-sanitize]
            [logseq.db.common.delete-blocks :as delete-blocks]
            [logseq.db.common.entity-plus :as entity-plus]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.recycle :as recycle]))

(defn- property-tree-block
  [root-title ordinary-title value-title value-uuid nested-title nested-uuid]
  {:block/title root-title
   :build/children [{:block/title ordinary-title}]
   :build/properties
   {:user.property/raw-parent-delete-value
    {:build/property-value :block
     :block/title value-title
     :block/uuid value-uuid
     :build/children [{:block/title nested-title
                       :block/uuid nested-uuid}]}}})

(defn- create-property-tree-conn
  [pages-and-blocks]
  (db-test/create-conn-with-blocks
   {:properties
    {:user.property/raw-parent-delete-value {:logseq.property/type :default}}
    :pages-and-blocks pages-and-blocks}))

(defn- retract-eids
  [db tx-data]
  (keep (fn [item]
          (when (and (vector? item)
                     (contains? #{:db/retractEntity :db.fn/retractEntity} (first item)))
            (some-> (d/entity db (second item)) :db/id)))
        tx-data))

(defn- raw-child-ids
  [parent]
  (set (map :db/id
            (entity-plus/lookup-kv-then-entity parent :block/_raw-parent))))

(defn- ordinary-child-ids
  [parent]
  (set (map :db/id (:block/_parent parent))))

(deftest delete-blocks-expands-raw-parent-generated-subtree-once-test
  (testing "delete-blocks includes a raw-parent property value subtree without duplicating ordinary retracts"
    (let [property-value-uuid #uuid "11111111-1111-1111-1111-111111111111"
          nested-child-uuid #uuid "22222222-2222-2222-2222-222222222222"
          conn (create-property-tree-conn
                [{:page {:block/title "Target page"}
                  :blocks [(property-tree-block
                            "Delete target"
                            "Ordinary child"
                            "Generated value"
                            property-value-uuid
                            "Generated nested child"
                            nested-child-uuid)
                           {:block/title "Unrelated sibling"}]}])
          parent (db-test/find-block-by-content @conn "Delete target")
          ordinary-child (db-test/find-block-by-content @conn "Ordinary child")
          property-value (d/entity @conn [:block/uuid property-value-uuid])
          nested-child (d/entity @conn [:block/uuid nested-child-uuid])
          unrelated (db-test/find-block-by-content @conn "Unrelated sibling")
          expected-eids #{(:db/id parent)
                          (:db/id ordinary-child)
                          (:db/id property-value)
                          (:db/id nested-child)}
          expanded (delete-blocks/expand-delete-blocks-tx
                    @conn
                    [[:db/retractEntity (:db/id parent)]]
                    {:outliner-op :delete-blocks})
          expanded-eids (vec (retract-eids @conn expanded))
          retract-counts (frequencies expanded-eids)]
      (is (contains? (raw-child-ids parent) (:db/id property-value))
          "fixture property value is reachable from the raw parent relation")
      (is (not (contains? (ordinary-child-ids parent) (:db/id property-value)))
          "fixture property value is hidden from ordinary reverse children")
      (is (= (:db/id property-value) (:db/id (:block/parent nested-child))))
      (is (= expected-eids (set expanded-eids))
          "the generated value, nested child, and ordinary child are all retracted")
      (is (every? #(= 1 (get retract-counts %)) expected-eids)
          "each subtree entity has exactly one retract")
      (is (not (contains? (set expanded-eids) (:db/id unrelated)))
          "an unrelated sibling is retained"))))

(deftest sync-delete-expands-raw-parent-generated-subtree-once-test
  (testing "sync deletion includes raw-parent generated descendants and preserves unrelated entities"
    (let [property-value-uuid #uuid "33333333-3333-3333-3333-333333333333"
          nested-child-uuid #uuid "44444444-4444-4444-4444-444444444444"
          conn (create-property-tree-conn
                [{:page {:block/title "Sync target page"}
                  :blocks [(property-tree-block
                            "Sync delete target"
                            "Sync ordinary child"
                            "Sync generated value"
                            property-value-uuid
                            "Sync generated nested child"
                            nested-child-uuid)
                           {:block/title "Sync unrelated sibling"}]}])
          parent (db-test/find-block-by-content @conn "Sync delete target")
          ordinary-child (db-test/find-block-by-content @conn "Sync ordinary child")
          property-value (d/entity @conn [:block/uuid property-value-uuid])
          nested-child (d/entity @conn [:block/uuid nested-child-uuid])
          unrelated (db-test/find-block-by-content @conn "Sync unrelated sibling")
          expected-eids #{(:db/id parent)
                          (:db/id ordinary-child)
                          (:db/id property-value)
                          (:db/id nested-child)}
          sanitized (tx-sanitize/sanitize-tx
                     @conn
                     [[:db/retractEntity [:block/uuid (:block/uuid parent)]]]
                     {:drop-missing-retract-ops? true
                      :drop-ops-targeting-retracted-entities? true
                      :retract-touched-descendants? true})
          sanitized-eids (vec (retract-eids @conn sanitized))
          retract-counts (frequencies sanitized-eids)]
      (is (contains? (raw-child-ids parent) (:db/id property-value))
          "fixture property value is reachable from the raw parent relation")
      (is (not (contains? (ordinary-child-ids parent) (:db/id property-value)))
          "fixture property value is hidden from ordinary reverse children")
      (is (= (:db/id property-value) (:db/id (:block/parent nested-child))))
      (is (= expected-eids (set sanitized-eids))
          "sync emits retracts for the root, ordinary child, generated value, and nested child")
      (is (every? #(= 1 (get retract-counts %)) expected-eids)
          "sync emits exactly one retract for every subtree entity")
      (is (not (contains? (set sanitized-eids) (:db/id unrelated)))
          "sync does not retract an unrelated sibling"))))

(deftest permanently-delete-recycled-page-removes-raw-parent-generated-subtree-test
  (testing "permanent page deletion removes generated property values and their nested children only"
    (let [value-uuid #uuid "55555555-5555-5555-5555-555555555555"
          nested-uuid #uuid "66666666-6666-6666-6666-666666666666"
          conn (create-property-tree-conn
                [{:page {:block/title "Permanently deleted page"}
                  :blocks [(property-tree-block
                            "Permanently deleted root"
                            "Permanently deleted ordinary child"
                            "Permanently deleted generated value"
                            value-uuid
                            "Permanently deleted generated nested child"
                            nested-uuid)]}
                 {:page {:block/title "Permanent delete unrelated page"}
                  :blocks [{:block/title "Permanent delete unrelated block"}]}])
          page (ldb/get-page @conn "Permanently deleted page")
          root (db-test/find-block-by-content @conn "Permanently deleted root")
          ordinary-child (db-test/find-block-by-content
                          @conn "Permanently deleted ordinary child")
          generated-value (d/entity @conn [:block/uuid value-uuid])
          generated-child (d/entity @conn [:block/uuid nested-uuid])
          unrelated-page (ldb/get-page @conn "Permanent delete unrelated page")
          unrelated-block (db-test/find-block-by-content
                           @conn "Permanent delete unrelated block")
          deleted-uuids (map :block/uuid
                             [page root ordinary-child generated-value generated-child])
          retained-uuids (map :block/uuid [unrelated-page unrelated-block])]
      (ldb/transact! conn
                     (recycle/recycle-page-tx-data @conn page {:now-ms 1000})
                     {:outliner-op :delete-page})
      (is (true? (recycle/permanently-delete! conn (:block/uuid page))))
      (doseq [uuid deleted-uuids]
        (is (nil? (d/entity @conn [:block/uuid uuid]))
            (str "permanent deletion retracts " uuid)))
      (doseq [uuid retained-uuids]
        (is (some? (d/entity @conn [:block/uuid uuid]))
            (str "permanent deletion retains unrelated entity " uuid))))))

(deftest recycle-gc-removes-expired-raw-parent-generated-subtree-only-test
  (testing "GC deletes expired generated subtrees while retaining fresh recycled and live entities"
    (let [now-ms 4000000000
          day-ms (* 24 60 60 1000)
          expired-value-uuid #uuid "77777777-7777-7777-7777-777777777777"
          expired-nested-uuid #uuid "88888888-8888-8888-8888-888888888888"
          fresh-value-uuid #uuid "99999999-9999-9999-9999-999999999999"
          fresh-nested-uuid #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          conn (create-property-tree-conn
                [{:page {:block/title "Expired recycled page"}
                  :blocks [(property-tree-block
                            "Expired root"
                            "Expired ordinary child"
                            "Expired generated value"
                            expired-value-uuid
                            "Expired generated nested child"
                            expired-nested-uuid)]}
                 {:page {:block/title "Fresh recycled page"}
                  :blocks [(property-tree-block
                            "Fresh root"
                            "Fresh ordinary child"
                            "Fresh generated value"
                            fresh-value-uuid
                            "Fresh generated nested child"
                            fresh-nested-uuid)]}
                 {:page {:block/title "Live unrelated page"}
                  :blocks [{:block/title "Live unrelated block"}]}])
          expired-page (ldb/get-page @conn "Expired recycled page")
          expired-root (db-test/find-block-by-content @conn "Expired root")
          expired-ordinary (db-test/find-block-by-content @conn "Expired ordinary child")
          expired-value (d/entity @conn [:block/uuid expired-value-uuid])
          expired-nested (d/entity @conn [:block/uuid expired-nested-uuid])
          fresh-page (ldb/get-page @conn "Fresh recycled page")
          fresh-root (db-test/find-block-by-content @conn "Fresh root")
          fresh-ordinary (db-test/find-block-by-content @conn "Fresh ordinary child")
          fresh-value (d/entity @conn [:block/uuid fresh-value-uuid])
          fresh-nested (d/entity @conn [:block/uuid fresh-nested-uuid])
          live-page (ldb/get-page @conn "Live unrelated page")
          live-block (db-test/find-block-by-content @conn "Live unrelated block")
          expired-uuids (map :block/uuid
                             [expired-page expired-root expired-ordinary
                              expired-value expired-nested])
          fresh-uuids (map :block/uuid
                           [fresh-page fresh-root fresh-ordinary fresh-value fresh-nested])
          live-uuids (map :block/uuid [live-page live-block])]
      (ldb/transact! conn
                     (recycle/recycle-page-tx-data
                      @conn expired-page {:now-ms (- now-ms (* 31 day-ms))})
                     {:outliner-op :delete-page})
      (ldb/transact! conn
                     (recycle/recycle-page-tx-data
                      @conn fresh-page {:now-ms (- now-ms (* 29 day-ms))})
                     {:outliner-op :delete-page})
      (is (true? (ldb/recycled? (d/entity
                                 @conn [:block/uuid (:block/uuid fresh-page)]))))
      (is (true? (recycle/gc! conn {:now-ms now-ms})))
      (doseq [uuid expired-uuids]
        (is (nil? (d/entity @conn [:block/uuid uuid]))
            (str "GC retracts expired entity " uuid)))
      (doseq [uuid fresh-uuids]
        (is (some? (d/entity @conn [:block/uuid uuid]))
            (str "GC retains fresh recycled entity " uuid)))
      (is (true? (ldb/recycled? (d/entity
                                 @conn [:block/uuid (:block/uuid fresh-page)])))
          "the unexpired page remains in recycle")
      (doseq [uuid live-uuids]
        (is (some? (d/entity @conn [:block/uuid uuid]))
            (str "GC retains live unrelated entity " uuid))))))
