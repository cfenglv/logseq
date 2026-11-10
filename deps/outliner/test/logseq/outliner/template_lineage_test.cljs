(ns logseq.outliner.template-lineage-test
  (:require [cljs.test :refer [deftest is]]
            [datascript.core :as d]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.db :as ldb]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.op :as outliner-op]
            [logseq.outliner.op.construct :as op-construct]
            [logseq.outliner.property :as outliner-property]
            [logseq.outliner.recycle :as outliner-recycle]
            [logseq.outliner.template-lineage :as lineage]))

(defn- body [conn page]
  (vec (rest (ldb/get-block-and-children @conn (:block/uuid page) {:include-property-block? true}))))

(defn- by-title [conn page title]
  (first (filter #(= title (:block/title %)) (body conn page))))

(defn- fixture []
  (let [summary-id (random-uuid)
        section-title (str "Section " (page-ref/->page-ref summary-id))
        conn (db-test/create-conn-with-blocks
              {:properties {:related {:logseq.property/type :node}}
               :pages-and-blocks
               [{:page {:block/title "Base" :build/tags [:logseq.class/Template]}
                 :blocks [{:block/title section-title
                           :build/properties {:related [:block/uuid summary-id]
                                              :logseq.property/heading 2}
                           :build/children [{:block/title "Base note"}]}
                          {:block/title "Summary" :block/uuid summary-id :build/keep-uuid? true}]}
                {:page {:block/title "Extended" :build/tags [:logseq.class/Template]}}
                {:page {:block/title "Other extension" :build/tags [:logseq.class/Template]}}
                {:page {:block/title "Target"}}]})
        pages (mapv #(ldb/get-page @conn %) ["Base" "Extended" "Other extension" "Target"])
        [base extended other] pages]
    (doseq [target [extended other]]
      (outliner-op/apply-ops! conn [[:apply-template [(:block/uuid base) (:block/uuid target) {:sibling? false}]]] {}))
    {:conn conn :base base :extended extended :other other :target (last pages)}))

(defn- prepared [conn templates]
  (mapcat #(lineage/prepare-blocks @conn %) templates))

(defn- section [conn page]
  (first (filter #(some? (:logseq.property/heading %)) (body conn page))))

(deftest template-copy-records-every-direct-source
  (let [{:keys [conn base extended other]} (fixture)
        source-nodes (body conn base)
        copies (body conn extended)]
    (is (= 3 (count copies)))
    (is (= (mapv :block/uuid source-nodes)
           (mapv #(-> % :logseq.property/template-source-block :block/uuid) copies)))
    (is (every? #(not= (:block/uuid %) (-> % :logseq.property/template-source-block :block/uuid)) copies))
    (outliner-op/apply-ops! conn [[:apply-template [(:block/uuid extended) (:block/uuid other) {:sibling? false}]]] {})
    (let [new-copies (filter #(contains? (set (map :block/uuid copies))
                                       (-> % :logseq.property/template-source-block :block/uuid))
                             (body conn other))]
      (is (= 3 (count new-copies)))
      (is (every? :logseq.property/template-source-block new-copies)))))

(deftest complete-templates-share-equal-structure-and-keep-additions
  (let [{:keys [conn base extended other target]} (fixture)]
    (doseq [[template title] [[extended "Extra one"] [other "Extra two"]]]
      (outliner-core/insert-blocks! conn [{:block/title title}] (section conn template) {:sibling? false}))
    (let [{:keys [blocks aliases]} (lineage/coalesce @conn (prepared conn [base extended other]))]
      (is (= 5 (count blocks)))
      (is (= 6 (count aliases)))
      (outliner-core/insert-blocks! conn blocks target
                                    {:sibling? false :insert-template? true :template/aliases aliases})
      (let [result (body conn target)
            summary (by-title conn target "Summary")
            heading (section conn target)
            children (filter #(= (:db/id heading) (:db/id (:block/parent %))) result)]
        (is (= 5 (count result)))
        (is (= #{"Base note" "Extra one" "Extra two"} (set (map :block/title children))))
        (is (= (str "Section " (page-ref/->page-ref (:block/uuid summary))) (:block/title heading)))
        (is (= (:block/uuid summary) (-> heading :user.property/related :block/uuid)))
        (is (= (count children) (count (set (map :block/order children)))))
        (is (= 3 (count (body conn base))))
        (is (every? #(not= (:block/uuid %) (-> % :logseq.property/template-source-block :block/uuid)) result))))))

(deftest changed-property-keeps-both-branches
  (let [{:keys [conn base extended]} (fixture)
        copied (section conn extended)]
    (ldb/transact! conn [[:db/add (:db/id copied) :logseq.property/heading 3]])
    (let [{:keys [blocks aliases]} (lineage/coalesce @conn (prepared conn [base extended]))]
      (is (= 5 (count blocks)))
      (is (= 2 (count (filter :logseq.property/heading blocks))))
      (is (= 2 (count (filter #(= "Base note" (:block/title %)) blocks))))
      (is (= 1 (count aliases))))))

(deftest legacy-used-template-marker-does-not-prove-node-origin
  (let [{:keys [conn base extended]} (fixture)]
    (doseq [block (body conn extended)]
      (ldb/transact! conn [[:db/retract (:db/id block) :logseq.property/template-source-block]]))
    (is (some :logseq.property/used-template (body conn extended)))
    (let [{:keys [blocks aliases]} (lineage/coalesce @conn (prepared conn [base extended]))]
      (is (= 6 (count blocks)))
      (is (empty? aliases)))))

(deftest incompatible-source-order-keeps-official-stacking
  (let [{:keys [conn base extended]} (fixture)
        summary (by-title conn extended "Summary")]
    (ldb/transact! conn [[:db/add (:db/id summary) :block/order "Zz"]])
    (let [{:keys [blocks aliases]} (lineage/coalesce @conn (prepared conn [base extended]))]
      (is (= 6 (count blocks)))
      (is (empty? aliases)))))

(deftest lost-source-does-not-block-complete-template-use
  (let [{:keys [conn base extended target]} (fixture)]
    (outliner-core/delete-blocks! conn (filter #(= (:db/id base) (:db/id (:block/parent %))) (body conn base)) {})
    (outliner-op/apply-ops! conn [[:apply-template [(:block/uuid extended) (:block/uuid target) {:sibling? false}]]] {})
    (is (= 3 (count (body conn target))))
    (is (by-title conn target "Summary"))))

(deftest native-permanent-source-delete-keeps-complete-template-body
  (let [{:keys [conn base extended target]} (fixture)
        before (mapv :block/title (body conn extended))]
    (d/transact! conn (outliner-recycle/recycle-page-tx-data @conn base {}))
    (outliner-recycle/permanently-delete! conn (:block/uuid base))
    (is (nil? (d/entity @conn [:block/uuid (:block/uuid base)])))
    (is (= before (mapv :block/title (body conn extended))))
    (is (not-any? :logseq.property/template-source-block (body conn extended)))
    (outliner-op/apply-ops! conn [[:apply-template [(:block/uuid extended) (:block/uuid target) {:sibling? false}]]] {})
    (is (= 3 (count (body conn target))))
    (is (every? :logseq.property/template-source-block (body conn target)))))

(deftest concrete-history-keeps-real-source-through-replay
  (let [{:keys [conn extended target]} (fixture)
        report (atom nil)
        operation [:apply-template [(:block/uuid extended) (:block/uuid target) {:sibling? false}]]]
    (d/listen! conn ::history #(reset! report %))
    (outliner-op/apply-ops! conn [operation] {})
    (let [{:keys [db-before db-after tx-data]} @report
          {:keys [forward-outliner-ops]}
          (op-construct/derive-history-outliner-ops db-before db-after tx-data
                                                  {:outliner-op :apply-template :outliner-ops [operation]})
          concrete (get-in forward-outliner-ops [0 1 2 :template-blocks])
          actual (body conn target)
          titles (mapv :block/title actual)]
      (is (= 3 (count concrete)))
      (is (= (mapv #(-> % :logseq.property/template-source-block :block/uuid) actual)
             (mapv #(second (:logseq.property/template-source-block %)) concrete)))
      (is (every? #(not= (:block/uuid %) (second (:logseq.property/template-source-block %))) concrete))
      (outliner-core/delete-blocks! conn
                                    (filter #(= (:db/id target) (:db/id (:block/parent %))) actual) {})
      (outliner-core/save-block! conn {:db/id (:db/id (section conn extended)) :block/title "Changed source"})
      (outliner-op/apply-ops! conn forward-outliner-ops {})
      (is (= titles (mapv :block/title (body conn target))))
      (is (every? :logseq.property/template-source-block (body conn target)))
      (outliner-core/delete-blocks! conn
                                    (filter #(= (:db/id target) (:db/id (:block/parent %))) (body conn target)) {})
      (let [source-section (first (:block/_parent (d/entity @conn (:db/id extended))))]
        (d/transact! conn (outliner-recycle/recycle-blocks-tx-data @conn [source-section] {}))
        (outliner-recycle/permanently-delete! conn (:block/uuid source-section)))
      (outliner-op/apply-ops! conn forward-outliner-ops {})
      (is (= titles (mapv :block/title (body conn target))))
      (let [{:keys [db-before db-after tx-data]} @report
            replay (op-construct/derive-history-outliner-ops db-before db-after tx-data
                                                             {:outliner-op :apply-template
                                                              :outliner-ops forward-outliner-ops})
            concrete (get-in replay [:forward-outliner-ops 0 1 2 :template-blocks])]
        (is (every? #(if-let [source (:logseq.property/template-source-block %)]
                       (some? (d/entity @conn source))
                       true)
                    concrete))))))

(deftest native-copy-and-structured-import-preserve-source-identities
  (let [{:keys [conn base extended target]} (fixture)
        copies (mapv #(into {} %) (body conn extended))
        expected-sources (mapv #(-> % :logseq.property/template-source-block :block/uuid) copies)]
    (outliner-op/apply-ops! conn [[:rename-page [(:block/uuid base) "Renamed base"]]] {})
    (is (= 3 (count (:blocks (lineage/coalesce @conn (prepared conn [base extended]))))))
    (outliner-core/insert-blocks! conn copies target {:sibling? false})
    (is (= expected-sources
           (mapv #(-> % :logseq.property/template-source-block :block/uuid) (body conn target))))
    (is (not= (mapv :block/uuid copies) (mapv :block/uuid (body conn target))))
    (let [export-map (sqlite-export/build-export @conn {:export-type :graph})
          imported (sqlite-export/create-conn)
          txs (sqlite-export/build-import export-map @imported {})]
      (d/transact! imported (sqlite-export/import-tx-data txs))
      (let [restored (d/entity @imported [:block/uuid (:block/uuid target)])]
        (is (= (mapv :block/title (body conn target)) (mapv :block/title (body imported restored))))
        (is (= expected-sources
               (mapv #(-> % :logseq.property/template-source-block :block/uuid) (body imported restored))))))))

(deftest owned-text-property-content-is-compared-and-preserved
  (let [conn (db-test/create-conn-with-blocks
              {:properties {:details {:logseq.property/type :default}}
               :pages-and-blocks
               [{:page {:block/title "Base"} :blocks [{:block/title "Section"}]}
                {:page {:block/title "Extended"}}
                {:page {:block/title "Target"}}]})
        base (ldb/get-page @conn "Base")
        extended (ldb/get-page @conn "Extended")
        target (ldb/get-page @conn "Target")
        source-section (first (:block/_parent base))]
    (outliner-property/create-property-text-block! conn (:db/id source-section) :user.property/details "Shared details" {})
    (let [value (:user.property/details (d/entity @conn (:db/id source-section)))]
      (outliner-core/insert-blocks! conn [{:block/title "Property note"}] value {:sibling? false}))
    (outliner-op/apply-ops! conn [[:apply-template [(:block/uuid base) (:block/uuid extended) {:sibling? false}]]] {})
    (let [{:keys [blocks aliases]} (lineage/coalesce @conn (prepared conn [base extended]))]
      (outliner-core/insert-blocks! conn blocks target {:sibling? false :insert-template? true :template/aliases aliases})
      (let [heading (first (filter #(= "Section" (:block/title %)) (body conn target)))
            value (:user.property/details heading)]
        (is (= 1 (count (filter #(= "Section" (:block/title %)) (body conn target)))))
        (is (= "Shared details" (:block/title value)))
        (is (= ["Property note"] (mapv :block/title (:block/_parent value))))))
    (let [copy (first (filter #(= "Section" (:block/title %)) (body conn extended)))
          value (:user.property/details copy)]
      (outliner-core/save-block! conn {:db/id (:db/id value) :block/title "Different details"})
      (is (= 2 (count (filter #(= "Section" (:block/title %))
                             (:blocks (lineage/coalesce @conn (prepared conn [base extended]))))))))))

(deftest explicit-legacy-source-association-keeps-the-complete-tree
  (let [{:keys [conn base extended]} (fixture)
        original (section conn base)
        copy (section conn extended)
        before (mapv (juxt :block/uuid :block/title :block/order) (body conn extended))]
    (outliner-property/remove-block-property! conn (:db/id copy) :logseq.property/template-source-block)
    (outliner-property/set-block-property! conn (:db/id copy) :logseq.property/template-source-block (:db/id original))
    (is (= before (mapv (juxt :block/uuid :block/title :block/order) (body conn extended))))
    (is (= (:block/uuid original)
           (-> (d/entity @conn (:db/id copy)) :logseq.property/template-source-block :block/uuid)))
    (is (thrown? js/Error
                 (outliner-property/set-block-property! conn (:db/id copy)
                                                       :logseq.property/template-source-block
                                                       (:db/id (ldb/get-page @conn "Target")))))))
