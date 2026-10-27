(ns logseq.db.frontend.literature-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.clj-fractional-indexing :as fractional-indexing]
            [logseq.db.common.order :as db-order]
            [logseq.db.common.reference :as db-reference]
            [logseq.db.frontend.literature :as literature]
            [logseq.db.frontend.literature-source :as literature-source]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.validate :as db-validate]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.op :as outliner-op]
            [logseq.outliner.op.construct :as op-construct]
            [logseq.outliner.property :as outliner-property]))

(def destination-page #uuid "79000000-0000-4000-8000-000000000099")

(def paper-a #uuid "71000000-0000-4000-8000-000000000001")
(def paper-b #uuid "71000000-0000-4000-8000-000000000002")
(def paper-c #uuid "71000000-0000-4000-8000-000000000003")
(def paper-no-id #uuid "71000000-0000-4000-8000-000000000004")
(def paper-incomplete #uuid "71000000-0000-4000-8000-000000000005")
(def pdf-a #uuid "72000000-0000-4000-8000-000000000001")
(def pdf-b #uuid "72000000-0000-4000-8000-000000000002")
(def pdf-duplicate #uuid "72000000-0000-4000-8000-000000000003")
(def image-a #uuid "72000000-0000-4000-8000-000000000004")

(def source-key-a "zotero/users/synthetic/items/AAAAAAAA")
(def source-key-conflict "zotero/users/synthetic/items/CONFLICT")

(defn- enable-literature!
  [conn]
  (d/transact! conn (sqlite-create-graph/build-properties
                                 (select-keys db-property/built-in-properties
                                              db-property/literature-property-idents))))

(defn- source-value-nodes
  [db source-key]
  (d/q '[:find ?value-node ?paper-title
         :in $ ?source-key
         :where
         [?paper :logseq.property/literature-source-key ?value-node]
         [?paper :block/title ?paper-title]
         [?value-node :block/title ?source-key]]
       db
       source-key))

(defn- block-uuid-by-title
  [db title]
  (d/q '[:find ?uuid .
         :in $ ?title
         :where
         [?block :block/title ?title]
         [?block :block/uuid ?uuid]]
       db
       title))

(defn- create-literature-conn
  []
  (let [conn (db-test/create-conn)]
    (enable-literature! conn)
    ;; The native paste flow persists Assets before literature classification.
    (sqlite-build/create-blocks
     conn
     {:pages-and-blocks
      [{:page {:block/title "Synthetic assets"}
        :blocks [{:block/uuid pdf-a
                  :block/title "paper-a"
                  :build/tags [:logseq.class/Asset]
                  :build/properties
                  {:logseq.property.asset/type "pdf"
                   :logseq.property.asset/size 100
                   :logseq.property.asset/checksum "same-checksum"}}
                 {:block/uuid pdf-b
                  :block/title "paper-b"
                  :build/tags [:logseq.class/Asset]
                  :build/properties
                  {:logseq.property.asset/type "pdf"
                   :logseq.property.asset/size 120
                   :logseq.property.asset/checksum "variant-checksum"}}
                 {:block/uuid pdf-duplicate
                  :block/title "paper-a-copy"
                  :build/tags [:logseq.class/Asset]
                  :build/properties
                  {:logseq.property.asset/type "pdf"
                   :logseq.property.asset/size 100
                   :logseq.property.asset/checksum "same-checksum"}}
                 {:block/uuid image-a
                  :block/title "figure"
                  :build/tags [:logseq.class/Asset]
                  :build/properties
                  {:logseq.property.asset/type "png"
                   :logseq.property.asset/size 100
                   :logseq.property.asset/checksum "same-checksum"}}]}]})
    (sqlite-build/create-blocks
     conn
     {:pages-and-blocks
      [{:page {:block/title "Synthetic bibliography" :block/uuid destination-page}
        :blocks [{:block/uuid paper-a
                  :block/title "Paper A"
                  :build/properties
                  {:logseq.property/literature-item? true
                   :logseq.property/literature-source-key
                   #{source-key-a source-key-conflict}
                   :logseq.property/literature-authors "Alice Example; Bob Researcher"
                   :logseq.property/literature-type "journal-article"
                   :logseq.property/literature-doi "10.1234/example"
                   :logseq.property/literature-arxiv "2101.12345"
                   :logseq.property/literature-assets
                   #{[:block/uuid pdf-a]
                     [:block/uuid pdf-b]
                     [:block/uuid image-a]}}}
                 {:block/uuid paper-b
                  :block/title "Paper B"
                  :build/properties
                  {:logseq.property/literature-item? true
                   :logseq.property/literature-source-key #{source-key-conflict}
                   :logseq.property/literature-authors "Carol Example"
                   :logseq.property/literature-type "preprint"
                   :logseq.property/literature-doi "10.9999/other"
                   :logseq.property/literature-arxiv "2201.00001"}}
                 {:block/uuid paper-no-id
                  :block/title "Paper without identifiers"
                  :build/properties
                  {:logseq.property/literature-item? true}}
                 {:block/uuid paper-incomplete
                  :block/title "Incomplete paper"
                  :build/properties
                  {:logseq.property/literature-item? true
                   :logseq.property/literature-doi "10.7777/incomplete"}}]}
       {:page {:block/title "Synthetic source binding A"}}
       {:page {:block/title "Synthetic source binding B"}}
       {:page {:block/title "Synthetic ordinary notes"}
        :blocks [{:block/title source-key-a}]}]})
    (let [binding-a (block-uuid-by-title @conn "Synthetic source binding A")
          binding-b (block-uuid-by-title @conn "Synthetic source binding B")
          binding-a-id (:db/id (d/entity @conn [:block/uuid binding-a]))
          binding-b-id (:db/id (d/entity @conn [:block/uuid binding-b]))]
      (doseq [[value-node paper-title] (source-value-nodes @conn source-key-a)]
        (when (= "Paper A" paper-title)
          (outliner-property/set-block-property!
           conn value-node :logseq.property/literature-source-target binding-a-id)))
      (doseq [[value-node paper-title] (source-value-nodes @conn source-key-conflict)]
        (outliner-property/set-block-property!
         conn
         value-node
         :logseq.property/literature-source-target
         (if (= "Paper A" paper-title) binding-a-id binding-b-id))))
    conn))

(deftest manual-papers-use-native-insert-without-pdf-or-source
  (let [conn (create-literature-conn)
        records (mapv (fn [title]
                        {:block/uuid (random-uuid)
                         :block/title title
                         :logseq.property/literature-item? true})
                      ["Manual paper" "Manual paper" ""])
        reports (atom [])
        existing-title (:block/title (d/entity @conn [:block/uuid paper-a]))]
    (d/listen! conn ::manual-create #(swap! reports conj %))
    (doseq [block records]
      (outliner-op/apply-ops!
       conn [[:insert-blocks [[block] destination-page
                             {:keep-uuid? true :bottom? true :sibling? false}]]]
       {:persist-op? true}))
    (d/unlisten! conn ::manual-create)
    (is (= 3 (count @reports)))
    (doseq [[block report] (map vector records @reports)]
      (let [saved (d/entity @conn [:block/uuid (:block/uuid block)])]
        (is (= (:block/title block) (:block/title saved)))
        (is (true? (:logseq.property/literature-item? saved)))
        (is (= destination-page (:block/uuid (:block/parent saved))))
        (is (= destination-page (:block/uuid (:block/page saved))))
        (is (string? (:block/order saved)))
        (is (nil? (:block/name saved)))
        (is (every? nil? (map #(get saved %)
                             [:logseq.property/literature-doi
                              :logseq.property/literature-authors
                              :logseq.property/literature-source-key
                              :logseq.property/literature-assets])))
        (is (true? (get-in report [:tx-meta :local-tx?])))
        (is (= [:insert-blocks]
               (mapv first (get-in report [:tx-meta :outliner-ops]))))
        (is (not (false? (get-in report [:tx-meta :gen-undo-ops?]))))))
    (is (= 2 (count (d/q '[:find ?id :where
                           [?id :logseq.property/literature-item? true]
                           [?id :block/title "Manual paper"]]
                         @conn))))
    (is (= existing-title (:block/title (d/entity @conn [:block/uuid paper-a]))))
    (let [paper-uuid (:block/uuid (first records))
          paper-id (:db/id (d/entity @conn [:block/uuid paper-uuid]))]
      (outliner-property/set-block-property!
       conn paper-id :logseq.property/literature-authors "Alice Manual")
      (let [plan (literature/build-link-pdf-plan @conn pdf-duplicate paper-uuid)]
        (is (= :ready (:status plan)))
        (d/transact! conn (:tx-data plan)))
      (let [paper (d/entity @conn [:block/uuid paper-uuid])]
        (is (= paper-uuid (:block/uuid paper)))
        (is (= "Alice Manual" (:block/title (:logseq.property/literature-authors paper))))
        (is (= #{pdf-duplicate} (set (map :block/uuid (literature/paper-pdfs @conn paper-uuid)))))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(defn- first-search-paper
  [db candidate-uuid]
  (first (literature/search-papers-for-candidate db candidate-uuid)))

(deftest native-alias-candidates-resolve-the-existing-literature-owner
  (let [conn (create-literature-conn)
        paper-id (:db/id (d/entity @conn [:block/uuid paper-a]))
        asset-id (:db/id (d/entity @conn [:block/uuid pdf-a]))]
    (doseq [[owner-id title] [[paper-id "Native paper alias"]
                             [asset-id "Native PDF alias"]]]
      (outliner-property/set-block-property! conn owner-id :block/alias title))
    (let [paper-alias (block-uuid-by-title @conn "Native paper alias")
          pdf-alias (block-uuid-by-title @conn "Native PDF alias")
          before @conn]
      (is (= paper-a (:block/uuid (first-search-paper @conn paper-alias))))
      (is (= paper-a (:block/uuid (first-search-paper @conn pdf-alias))))
      (is (identical? before @conn) "Resolving a native alias does not write the graph")
      (d/transact! conn [[:db/retract paper-id :logseq.property/literature-assets asset-id]])
      (is (nil? (first-search-paper @conn pdf-alias))
          "A PDF alias follows the current bibliography association")
      (is (= paper-a (:block/uuid (first-search-paper @conn paper-alias))))
      (d/transact! conn [[:db/retract paper-id :block/alias [:block/uuid paper-alias]]])
      (is (nil? (first-search-paper @conn paper-alias))
          "Removing the native alias removes its literature search result"))))

(deftest accepted-other-names-resolve-only-current-owners
  (let [conn (create-literature-conn)
        property :logseq.property/literature-aliases
        asset-id (:db/id (d/entity @conn [:block/uuid pdf-a]))
        paper-id (:db/id (d/entity @conn [:block/uuid paper-a]))]
    (when-not (d/entity @conn property)
      (outliner-property/upsert-property!
       conn property
       {:logseq.property/type :default :db/cardinality :many
        :logseq.property/public? true} {}))
    (outliner-property/set-block-property! conn asset-id property "renamed-paper.pdf")
    (outliner-property/set-block-property! conn paper-id property "Team accepted name")
    (doseq [owner-id [asset-id paper-id]]
      (let [value (first (get (d/entity @conn owner-id) property))
            candidate-uuid (:block/uuid value)]
        (is (= #{paper-a}
               (set (map :block/uuid
                         (literature/search-papers-for-candidate @conn candidate-uuid)))))
        (d/transact! conn [[:db/retract owner-id property (:db/id value)]])
        (is (empty? (literature/search-papers-for-candidate @conn candidate-uuid))
            "A detached name cannot produce a stale bibliography result")))))

(deftest pdf-filenames-use-idempotent-native-property-values
  (let [conn (create-literature-conn)
        names {pdf-a #{"paper.pdf" "/synthetic/renamed-paper.pdf"
                       "C:\\synthetic\\renamed-paper.pdf"}
               pdf-b #{"renamed-paper.pdf"}}
        tx-data (literature/build-add-pdf-filenames-tx @conn names)
        original (d/entity @conn [:block/uuid pdf-a])
        original-title (:block/title original)
        original-checksum (:logseq.property.asset/checksum original)]
    (is (seq tx-data))
    (d/transact! conn tx-data)
    (let [asset (d/entity @conn [:block/uuid pdf-a])
          values (:logseq.property/literature-aliases asset)
          other (d/entity @conn [:block/uuid pdf-b])]
      (is (= #{"paper.pdf" "renamed-paper.pdf"} (set (map :block/title values))))
      (is (= original-title (:block/title asset)))
      (is (= original-checksum (:logseq.property.asset/checksum asset)))
      (is (= 1 (count (:logseq.property/literature-aliases other)))
          "Distinct files can have the same basename")
      (is (empty? (literature/build-add-pdf-filenames-tx @conn names)))
      (doseq [value values]
        (is (= #{paper-a}
               (set (map :block/uuid
                         (literature/search-papers-for-candidate @conn (:block/uuid value)))))))
      (is (empty? (literature/build-add-pdf-filenames-tx @conn {image-a #{"figure.png"}})))
      (let [value (first (filter #(= "renamed-paper.pdf" (:block/title %)) values))]
        (d/transact! conn [[:db/add (:db/id value) :block/title "Team accepted filename"]])
        (d/transact! conn (literature/build-add-pdf-filenames-tx @conn names))
        (is (= #{"paper.pdf" "renamed-paper.pdf" "Team accepted filename"}
               (set (map :block/title (:logseq.property/literature-aliases
                                      (d/entity @conn [:block/uuid pdf-a]))))))
        (is (= "Team accepted filename" (:block/title (d/entity @conn (:db/id value))))
            "Pasting the original name must preserve an edited native value"))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest pdf-title-candidates-resolve-current-paper-associations
  (let [conn (create-literature-conn)
        paper-a-id (:db/id (d/entity @conn [:block/uuid paper-a]))
        paper-b-id (:db/id (d/entity @conn [:block/uuid paper-b]))
        asset-id (:db/id (d/entity @conn [:block/uuid pdf-a]))
        papers (fn [asset-uuid]
                 (set (map :block/uuid
                           (literature/search-papers-for-candidate @conn asset-uuid))))
        db-before @conn]
    (testing "indexed PDF titles resolve their existing Paper without graph writes"
      (is (= #{paper-a} (papers pdf-a)))
      (is (= #{paper-a} (papers pdf-b)))
      (is (empty? (papers pdf-duplicate)))
      (is (empty? (papers image-a)))
      (is (identical? db-before @conn)))
    (testing "all live owners are returned, including after a file title changes"
      (d/transact! conn [[:db/add paper-b-id :logseq.property/literature-assets asset-id]
                         [:db/add asset-id :block/title "renamed-file"]])
      (is (= #{paper-a paper-b} (papers pdf-a)))
      (d/transact! conn [[:db/add paper-b-id :logseq.property/hide? true]])
      (is (= #{paper-a} (papers pdf-a)))
      (d/transact! conn [[:db/retract paper-b-id :logseq.property/hide? true]
                         [:db/retract paper-a-id :logseq.property/literature-assets asset-id]])
      (is (= #{paper-b} (papers pdf-a)))
      (d/transact! conn [[:db/add asset-id :logseq.property/deleted-at 1]])
      (is (empty? (papers pdf-a))))))

(deftest search-candidates-resolve-only-current-public-literature-values
  (let [conn (create-literature-conn)
        paper-id (:db/id (d/entity @conn [:block/uuid paper-a]))
        public-properties [:logseq.property/literature-authors
                           :logseq.property/literature-doi
                           :logseq.property/literature-arxiv
                           :logseq.property/literature-type
                           :logseq.property/literature-date
                           :logseq.property/literature-publication
                           :logseq.property/literature-url
                           :logseq.property/literature-citekey]]
    (doseq [[property value] [[:logseq.property/literature-date "2026"]
                             [:logseq.property/literature-publication "Synthetic Journal"]
                             [:logseq.property/literature-url "https://example.test/paper"]
                             [:logseq.property/literature-citekey "Alice2026Verified"]]]
      (outliner-property/set-block-property! conn paper-id property value))
    (let [paper (d/entity @conn [:block/uuid paper-a])
          candidate-uuids (mapv #(:block/uuid (get paper %)) public-properties)
          db-before @conn]
      (testing "the original Paper title candidate still resolves"
        (is (= paper-a (:block/uuid (first-search-paper @conn paper-a)))))
      (testing "an indexed accepted metadata value resolves to the Paper that currently owns it"
        (doseq [[property candidate-uuid] (map vector public-properties candidate-uuids)]
          (is (= paper-a (:block/uuid (first-search-paper @conn candidate-uuid)))
              (str property))))
      (testing "lookup does not transact or retain mutable graph state"
        (is (identical? db-before @conn)))
      (testing "hidden source values and their target page cannot stand in for a Paper"
        (doseq [source-node (:logseq.property/literature-source-key paper)]
          (is (nil? (first-search-paper @conn (:block/uuid source-node)))))
        (is (nil? (first-search-paper
                   @conn (block-uuid-by-title @conn "Synthetic source binding A")))))
      (testing "ordinary same-text child nodes do not resolve to their parent Paper"
        (let [ordinary-uuid #uuid "7d000000-0000-4000-8000-000000000001"]
          (d/transact! conn [{:block/uuid ordinary-uuid
                             :block/title "Alice Example; Bob Researcher"
                             :block/page (:db/id (:block/page paper))
                             :block/parent paper-id}])
          (is (nil? (first-search-paper @conn ordinary-uuid)))))
      (testing "a stale title-index candidate for a detached value is ignored"
        (let [authors (:logseq.property/literature-authors paper)]
          (d/transact! conn [[:db/retract paper-id :logseq.property/literature-authors (:db/id authors)]])
          (is (nil? (first-search-paper @conn (:block/uuid authors))))
          (d/transact! conn [[:db/add paper-id :logseq.property/literature-authors (:db/id authors)]])))
      (testing "the native parent, created property and current field must agree"
        (let [authors (:logseq.property/literature-authors paper)
              authors-id (:db/id authors)
              authors-uuid (:block/uuid authors)]
          (d/transact! conn [[:db/add authors-id :block/parent [:block/uuid paper-b]]])
          (is (nil? (first-search-paper @conn authors-uuid)))
          (d/transact! conn [[:db/add authors-id :block/parent paper-id]
                             [:db/add authors-id :logseq.property/created-from-property
                              :logseq.property/literature-doi]])
          (is (nil? (first-search-paper @conn authors-uuid)))
          (d/transact! conn [[:db/add authors-id :logseq.property/created-from-property
                              :logseq.property/literature-authors]])))
      (testing "currently hidden or deleted public value nodes are ignored even with a stale index"
        (let [authors (:logseq.property/literature-authors paper)]
          (doseq [[attribute value] [[:logseq.property/hide? true]
                                     [:logseq.property/deleted-at 1]]]
            (d/transact! conn [[:db/add (:db/id authors) attribute value]])
            (is (nil? (first-search-paper @conn (:block/uuid authors))))
            (d/transact! conn [[:db/retract (:db/id authors) attribute value]]))))
      (testing "a currently hidden Paper cannot be reached through its own or metadata title"
        (d/transact! conn [[:db/add paper-id :logseq.property/hide? true]])
        (is (nil? (first-search-paper @conn paper-a)))
        (is (nil? (first-search-paper @conn (first candidate-uuids))))
        (d/transact! conn [[:db/retract paper-id :logseq.property/hide? true]]))
      (testing "hidden or recycled native ancestors suppress both Paper and metadata candidates"
        (doseq [[attribute value] [[:logseq.property/hide? true]
                                   [:logseq.property/deleted-at 1]]]
          (let [page-id (:db/id (:block/page paper))]
            (d/transact! conn [[:db/add page-id attribute value]])
            (is (nil? (first-search-paper @conn paper-a)))
            (is (nil? (first-search-paper @conn (first candidate-uuids))))
            (d/transact! conn [[:db/retract page-id attribute value]]))))
      (testing "a deleted Paper and all its metadata candidates are ignored"
        (d/transact! conn [[:db/add paper-id :logseq.property/deleted-at 1]])
        (is (nil? (first-search-paper @conn paper-a)))
        (doseq [candidate-uuid candidate-uuids]
          (is (nil? (first-search-paper @conn candidate-uuid))))))))





(deftest identifier-lookup-is-exact-and-conflict-aware
  (let [conn (create-literature-conn)]
    (testing "DOI and arXiv URL variants resolve to one accepted paper"
      (is (= {:status :found
              :papers [{:block/uuid paper-a
                        :block/title "Paper A"
                        :matched-by #{[:doi "10.1234/example"]
                                      [:arxiv "2101.12345"]}}]}
             (literature/find-paper-by-identifiers
              @conn
              {:doi "https://doi.org/10.1234/EXAMPLE"
               :arxiv "https://arxiv.org/pdf/2101.12345v3.pdf"}))))
    (testing "identifiers pointing at different papers remain an explicit conflict"
      (let [result (literature/find-paper-by-identifiers
                    @conn
                    {:doi "doi:10.1234/example"
                     :arxiv "arXiv:2201.00001v2"})]
        (is (= :conflict (:status result)))
        (is (= #{paper-a paper-b}
               (set (map :block/uuid (:papers result)))))))
    (testing "invalid and unsupported identifiers fail instead of becoming no-match"
      (is (thrown-with-msg? js/Error #"Invalid DOI"
                            (literature/find-paper-by-identifiers @conn {:doi "example"})))
      (is (thrown-with-msg? js/Error #"Unsupported literature identifier"
                            (literature/find-paper-by-identifiers @conn {:isbn "x"}))))))

(deftest source-key-lookup-requires-a-valid-source-relation
  (let [conn (create-literature-conn)]
    (testing "an ordinary block with the same text is not a source match"
      (is (= {:status :found
              :papers [{:block/uuid paper-a
                        :block/title "Paper A"
                        :matched-by #{[:source-key source-key-a]}}]}
             (literature/find-paper-by-source-key @conn source-key-a))))
    (testing "duplicate source keys targeting different papers are conflicts"
      (let [result (literature/find-paper-by-source-key @conn source-key-conflict)]
        (is (= :conflict (:status result)))
        (is (= #{paper-a paper-b}
               (set (map :block/uuid (:papers result)))))))))

(deftest explicit-pdf-registration-plan-is-current-db-checked-and-idempotent
  (testing "an accepted identifier links the PDF to the existing paper"
    (let [conn (create-literature-conn)
          plan (literature/build-register-pdf-plan
                @conn pdf-duplicate "Paper A" {:doi "10.1234/example"} paper-c)]
      (is (= :ready (:status plan)))
      (is (= :link (:action plan)))
      (is (= paper-a (:paper-uuid plan)))
      (d/transact! conn (:tx-data plan))
      (is (= #{pdf-a pdf-b pdf-duplicate}
             (set (map :block/uuid (literature/paper-pdfs @conn paper-a)))))
      (is (= :already-linked
             (:status (literature/build-register-pdf-plan
                       @conn pdf-duplicate "Ignored" {} paper-c))))))

  (testing "a missing identifier creates one sibling paper with native values"
    (let [conn (create-literature-conn)
          plan (literature/build-register-pdf-plan
                @conn pdf-duplicate "Paper C" {:doi "10.5555/new-paper"
                                                :arxiv "2401.12345v2"}
                paper-c)]
      (is (= :ready (:status plan)))
      (is (= :create (:action plan)))
      (is (seq (:tx-data plan)))
      (d/transact! conn (:tx-data plan))
      (is (= {:status :found
              :papers [{:block/uuid paper-c
                        :block/title "Paper C"
                        :matched-by #{[:asset pdf-duplicate]}}]}
             (literature/find-paper-by-asset @conn pdf-duplicate)))
      (is (= paper-c
             (get-in (literature/find-paper-by-identifiers
                      @conn {:doi "10.5555/new-paper"
                             :arxiv "2401.12345"})
                     [:papers 0 :block/uuid])))
      (is (empty? (:errors (db-validate/validate-db @conn))))))

  (testing "conflicting identifiers and non-PDF assets do not produce tx data"
    (let [conn (create-literature-conn)]
      (is (= {:status :blocked
              :reason :identifier-conflict
              :papers [{:matched-by #{[:doi "10.1234/example"]}
                        :block/uuid paper-a
                        :block/title "Paper A"}
                       {:matched-by #{[:arxiv "2201.00001"]}
                        :block/uuid paper-b
                        :block/title "Paper B"}]}
             (literature/build-register-pdf-plan
              @conn pdf-duplicate "Conflict" {:doi "10.1234/example"
                                               :arxiv "2201.00001"}
              paper-c)))
      (is (= {:status :blocked :reason :asset-unavailable}
             (literature/build-register-pdf-plan
              @conn image-a "Image" {} paper-c)))
      (is (= {:status :blocked :reason :invalid-asset-position}
             (literature/build-register-pdf-plan
              @conn pdf-duplicate "Missing UUID" {} nil))))))

(deftest pdf-registration-names-native-assets-after-the-paper
  (doseq [[identifiers title expected-paper expected-title]
          [[{:doi "10.5555/new-pdf-name"} "A verified paper title" paper-c "A verified paper title"]
           [{:doi "10.1234/example"} "Incoming filename" paper-a "Paper A"]]]
    (let [conn (create-literature-conn)
          before (select-keys (d/entity @conn [:block/uuid pdf-duplicate])
                              [:db/id :block/uuid :logseq.property.asset/type
                               :logseq.property.asset/checksum :logseq.property.asset/size])
          plan (literature/build-register-pdf-plan @conn pdf-duplicate title identifiers paper-c)]
      (is (= :ready (:status plan)))
      (d/transact! conn (:tx-data plan))
      (let [asset (d/entity @conn [:block/uuid pdf-duplicate])]
        (is (= expected-title (:block/title asset)))
        (is (= before (select-keys asset (keys before))))
        (is (contains? (set (map :block/title (:logseq.property/literature-aliases asset)))
                       "paper-a-copy")))
      (is (= expected-paper (:block/uuid (first (:papers (literature/find-paper-by-asset @conn pdf-duplicate))))))
      (is (= :already-linked
             (:status (literature/build-register-pdf-plan @conn pdf-duplicate title identifiers paper-c)))))))

(deftest explicit-pdf-renaming-keeps-multiple-attachment-names-and-native-references
  (let [conn (create-literature-conn)
        citation-title (str "Read [[" pdf-a "]]")]
    (d/transact! conn (:tx-data (outliner-core/save-block
                                @conn {:block/uuid paper-no-id :block/title citation-title} {})))
    (let [refs-before (mapv :db/id (:block/refs (d/entity @conn [:block/uuid paper-no-id])))
          asset-id-before (:db/id (d/entity @conn [:block/uuid pdf-a]))
          plan (literature/build-rename-paper-pdfs-plan @conn paper-a)]
      (is (= :ready (:status plan)))
      (is (= 0 (:count plan)))
      (is (empty? (:tx-data plan)))
      (is (= ["paper-a" "paper-b"]
             (mapv #(:block/title (d/entity @conn [:block/uuid %])) [pdf-a pdf-b])))
      (d/transact! conn [[:db/retract [:block/uuid paper-a]
                          :logseq.property/literature-assets [:block/uuid pdf-b]]])
      (let [plan (literature/build-rename-paper-pdfs-plan @conn paper-a)]
        (is (= 1 (:count plan)))
        (d/transact! conn (:tx-data plan))
        (is (= "Paper A" (:block/title (d/entity @conn [:block/uuid pdf-a]))))
        (is (= "paper-b" (:block/title (d/entity @conn [:block/uuid pdf-b])))))
      (is (= "figure" (:block/title (d/entity @conn [:block/uuid image-a]))))
      (is (= citation-title (:block/title (d/entity @conn [:block/uuid paper-no-id]))))
      (is (= refs-before (mapv :db/id (:block/refs (d/entity @conn [:block/uuid paper-no-id])))))
      (is (= asset-id-before (:db/id (d/entity @conn [:block/uuid pdf-a]))))
      (is (= 0 (:count (literature/build-rename-paper-pdfs-plan @conn paper-a))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest zotero-attachment-names-survive-association-and-native-replay
  (let [conn (create-literature-conn)
        new-pdf #uuid "72000000-0000-4000-8000-000000000096"
        context {:binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
                 :source-key source-key-a
                 :reference-uuid #uuid "74000000-0000-4000-8000-000000000096"}
        block {:block/uuid new-pdf :block/title "Supplementary Information"
               :block/tags #{:logseq.class/Asset}
               :logseq.property.asset/type "pdf"
               :logseq.property.asset/size 140
               :logseq.property.asset/checksum "supplement-checksum"}
        import! (fn [id title]
                  (outliner-op/auto-link-literature-pdf!
                   conn id paper-a (assoc context :asset-block (assoc block :block/uuid id :block/title title))))]
    (is (= :linked (:status (import! new-pdf "Supplementary Information"))))
    (is (= :already-linked (:status (import! new-pdf "Supplementary Information"))))
    (is (= "Supplementary Information" (:block/title (d/entity @conn [:block/uuid new-pdf]))))
    (is (= 1 (count (filter #(= new-pdf (:block/uuid %))
                           (literature/paper-pdfs @conn paper-a)))))
    (d/transact! conn (:tx-data (outliner-core/save-block
                                @conn {:block/uuid pdf-a :block/title "Paper A"} {})))
    (let [id-before (:db/id (d/entity @conn [:block/uuid pdf-a]))]
      (is (= :linked (:status (import! pdf-a "Article PDF"))))
      (is (= "Article PDF" (:block/title (d/entity @conn [:block/uuid pdf-a]))))
      (is (= id-before (:db/id (d/entity @conn [:block/uuid pdf-a]))))
      (is (contains? (set (map :block/title (:logseq.property/literature-aliases
                                            (d/entity @conn [:block/uuid pdf-a]))))
                     "Paper A")))
    (is (= :already-linked (:status (import! pdf-b "Publisher PDF"))))
    (is (= "paper-b" (:block/title (d/entity @conn [:block/uuid pdf-b]))))
    (is (= :linked (:status (import! pdf-duplicate "Source PDF"))))
    (is (= "paper-a-copy" (:block/title (d/entity @conn [:block/uuid pdf-duplicate]))))
    (is (= 0 (:count (literature/build-rename-paper-pdfs-plan @conn paper-a))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest metadata-title-change-follows-automatic-pdf-names-and-keeps-manual-names
  (let [conn (create-literature-conn)
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        record {:classification :bibliographic-item :source-key source-key-conflict
                :title "Updated Paper B" :doi "10.9999/other"}]
    (d/transact! conn [[:db/retract [:block/uuid paper-a] :logseq.property/literature-assets [:block/uuid pdf-a]]
                       [:db/retract [:block/uuid paper-a] :logseq.property/literature-assets [:block/uuid pdf-b]]
                       [:db/add [:block/uuid paper-b] :logseq.property/literature-assets [:block/uuid pdf-a]]
                       [:db/add [:block/uuid paper-b] :logseq.property/literature-assets [:block/uuid pdf-b]]])
    (doseq [[id title] [[pdf-a "Paper B"] [pdf-b "My named version"]]]
      (d/transact! conn (:tx-data (outliner-core/save-block @conn {:block/uuid id :block/title title} {}))))
    (let [review (literature/build-linked-source-record-metadata-plan @conn binding-b paper-b record)
          accepted (mapv #(select-keys % [:field :kind :current :source]) (:changes review))
          plan (literature/build-apply-source-record-metadata-plan @conn binding-b paper-b record accepted)]
      (is (= :ready (:status plan)))
      (outliner-op/apply-ops! conn (:ops plan) {})
      (is (= "Updated Paper B" (:block/title (d/entity @conn [:block/uuid pdf-a]))))
      (is (= "My named version" (:block/title (d/entity @conn [:block/uuid pdf-b]))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest identified-pdf-registration-refuses-an-occupied-derived-uuid
  (let [conn (create-literature-conn)
        identifiers {:doi "10.5555/occupied-pdf"}
        paper-uuid (literature/source-record-paper-uuid identifiers)]
    (d/transact! conn [{:block/uuid paper-uuid
                        :block/title "Unrelated existing block"}])
    (is (= {:status :blocked :reason :paper-uuid-conflict}
           (literature/build-register-pdf-plan
            @conn pdf-duplicate "Synthetic Paper" identifiers paper-uuid)))
    (is (nil? (:logseq.property/literature-item?
               (d/entity @conn [:block/uuid paper-uuid]))))))

(deftest zotero-source-binding-page-is-ordinary-and-idempotent
  (let [conn (create-literature-conn)
        binding-uuid #uuid "7a000000-0000-4000-8000-000000000001"
        title (literature/source-binding-title binding-uuid)
        plan (literature/build-source-binding-plan @conn binding-uuid)]
    (is (= :ready (:status plan)))
    (is (= :create (:action plan)))
    (is (= binding-uuid (:binding-uuid plan)))
    (is (= "Zotero literature · 7a000000" title))
    (d/transact! conn (:tx-data plan) (:tx-meta plan))
    (let [binding (d/entity @conn [:block/uuid binding-uuid])]
      (is (= title (:block/title binding)))
      (is (nil? (:logseq.property/literature-item? binding)))
      (is (nil? (:logseq.property/deleted-at binding))))
    (is (= {:status :existing
            :binding-uuid binding-uuid
            :title title}
           (literature/build-source-binding-plan @conn binding-uuid)))
    (is (= {:status :blocked :reason :invalid-binding-uuid}
           (literature/build-source-binding-plan @conn "invalid")))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest source-record-link-lookup-is-binding-scoped-and-fails-closed
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")]
    (is (= {:status :found
            :binding-uuid binding-b
            :paper-uuid paper-b
            :source-key source-key-conflict}
           (literature-source/find-source-record-link @conn binding-b paper-b)))
    (is (= {:status :missing}
           (literature-source/find-source-record-link @conn binding-a paper-b)))
    (is (= {:status :missing}
           (literature-source/find-source-record-link @conn "invalid" paper-b)))
    (is (= {:status :conflict
            :binding-uuid binding-a
            :paper-uuid paper-a
            :source-keys [source-key-a source-key-conflict]}
           (literature-source/find-source-record-link @conn binding-a paper-a)))
    (let [source-node-id
          (some (fn [[value-node paper-title]]
                  (when (= "Paper B" paper-title)
                    value-node))
                (source-value-nodes @conn source-key-conflict))]
      (d/transact! conn [{:db/id source-node-id
                          :logseq.property/deleted-at 1}])
      (is (= {:status :missing}
             (literature-source/find-source-record-link @conn binding-b paper-b))))))

(deftest source-record-owner-lookup-is-exact-and-binding-scoped
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        before @conn]
    (testing "distinct keys on one Paper do not make either exact key ambiguous"
      (doseq [source-key [source-key-a source-key-conflict]]
        (is (= {:status :found :binding-uuid binding-a
                :source-key source-key :paper-uuid paper-a}
               (literature-source/find-source-record-owner @conn binding-a source-key)))))
    (testing "the same key in another binding has an independent owner"
      (is (= {:status :found :binding-uuid binding-b
              :source-key source-key-conflict :paper-uuid paper-b}
             (literature-source/find-source-record-owner @conn binding-b source-key-conflict)))
      (is (= {:status :missing}
             (literature-source/find-source-record-owner @conn binding-b source-key-a))))
    (is (= {:status :missing}
           (literature-source/find-source-record-owner @conn binding-a "unknown-source-key")))
    (is (identical? before @conn))))

(deftest source-record-owner-lookup-follows-reassignment-and-unlink
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        source-node-id (ffirst (source-value-nodes @conn source-key-a))]
    (is (= paper-a (:paper-uuid
                   (literature-source/find-source-record-owner @conn binding-a source-key-a))))
    (d/transact! conn [[:db/retract [:block/uuid paper-a]
                       :logseq.property/literature-source-key source-node-id]
                      [:db/add [:block/uuid paper-b]
                       :logseq.property/literature-source-key source-node-id]])
    (is (= {:status :found :binding-uuid binding-a
            :source-key source-key-a :paper-uuid paper-b}
           (literature-source/find-source-record-owner @conn binding-a source-key-a)))
    (d/transact! conn [[:db/retract [:block/uuid paper-b]
                       :logseq.property/literature-source-key source-node-id]])
    (is (= {:status :missing}
           (literature-source/find-source-record-owner @conn binding-a source-key-a)))
    (is (= source-key-a (:block/title (d/entity @conn source-node-id))))))

(deftest source-record-owner-lookup-excludes-soft-deleted-nodes
  (doseq [deleted-node [:binding :source-key :paper]]
    (let [conn (create-literature-conn)
          binding-a (block-uuid-by-title @conn "Synthetic source binding A")
          source-node-id (ffirst (source-value-nodes @conn source-key-a))
          node-id (case deleted-node
                    :binding [:block/uuid binding-a]
                    :source-key source-node-id
                    :paper [:block/uuid paper-a])]
      (d/transact! conn [[:db/add node-id :logseq.property/deleted-at 1]])
      (is (= {:status :missing}
             (literature-source/find-source-record-owner @conn binding-a source-key-a))
          (str "Deleted " (name deleted-node) " cannot authorize an attachment")))))

(deftest source-record-owner-lookup-rejects-duplicate-live-owners
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        source-node-id (ffirst (source-value-nodes @conn source-key-a))]
    (d/transact! conn [[:db/add [:block/uuid paper-b]
                       :logseq.property/literature-source-key source-node-id]])
    (is (= {:status :conflict :binding-uuid binding-a :source-key source-key-a
            :paper-uuids [paper-a paper-b]}
           (literature-source/find-source-record-owner @conn binding-a source-key-a)))
    (d/transact! conn [[:db/add [:block/uuid paper-b] :logseq.property/deleted-at 1]])
    (is (= {:status :found :binding-uuid binding-a
            :source-key source-key-a :paper-uuid paper-a}
           (literature-source/find-source-record-owner @conn binding-a source-key-a)))))

(defn- correction-source
  [db]
  (let [key-node (d/entity db (ffirst (source-value-nodes db source-key-a)))]
    {:key-uuid (:block/uuid key-node)
     :source-key source-key-a
     :binding-uuid (block-uuid-by-title db "Synthetic source binding A")
     :active? true}))

(deftest explicit-source-correction-retains-native-key-and-bibliography
  (doseq [action [:unlink :reassign]]
    (let [conn (create-literature-conn)
          source (correction-source @conn)
          before @conn
          key-before (d/pull before
                             '[:block/uuid :block/title
                               {:block/parent [:block/uuid]}
                               {:logseq.property/created-from-property [:db/ident]}]
                             [:block/uuid (:key-uuid source)])
          facts #(d/pull-many %
                             '[:block/uuid :block/title
                               {:logseq.property/literature-doi [:block/uuid :block/title]}
                               {:logseq.property/literature-authors [:block/uuid :block/title]}
                               {:logseq.property/literature-assets [:block/uuid]}]
                             [[:block/uuid paper-a] [:block/uuid paper-b]])
          assets-before (mapv #(d/pull before '[*] [:block/uuid %])
                              [pdf-a pdf-b image-a])
          plan (literature/build-correct-source-record-plan
                before paper-a source action (when (= action :reassign) paper-b))]
      (is (= :ready (:status plan)))
      (is (identical? before @conn) "Planning is read-only.")
      (is (= [:transact] (mapv first (:ops plan))))
      (outliner-op/apply-ops! conn (:ops plan) {:persist-op? true})
      (is (= key-before (d/pull @conn
                               '[:block/uuid :block/title
                                 {:block/parent [:block/uuid]}
                                 {:logseq.property/created-from-property [:db/ident]}]
                               [:block/uuid (:key-uuid source)])))
      (is (= (facts before) (facts @conn)) "Source correction leaves accepted bibliography intact.")
      (is (= assets-before (mapv #(d/pull @conn '[*] [:block/uuid %])
                                [pdf-a pdf-b image-a])))
      (is (= (if (= action :unlink) :missing :found)
             (:status (literature-source/find-source-record-owner
                       @conn (:binding-uuid source) source-key-a))))
      (when (= action :reassign)
        (is (= paper-b (:paper-uuid
                       (literature-source/find-source-record-owner
                        @conn (:binding-uuid source) source-key-a)))))
      (when (= action :unlink)
        (is (= :blocked
               (:status (literature/build-register-source-record-plan
                         @conn (:binding-uuid source) destination-page
                         {:classification :bibliographic-item
                          :source-key source-key-a :doi "10.1234/example"}))))
        (is (some #(= (:key-uuid source) (:block/uuid %))
                  (:logseq.property/literature-source-key
                   (d/entity @conn [:block/uuid paper-a]))))))))

(deftest source-correction-rechecks-stale-and-conflicting-identities
  (doseq [scenario [:different-title :different-binding :wrong-key
                    :owner-moved :ambiguous-owner :deleted-paper
                    :deleted-target :deleted-key :deleted-binding
                    :wrong-property :unlinked]]
    (let [conn (create-literature-conn)
          source (correction-source @conn)
          key-ref [:block/uuid (:key-uuid source)]
          source' (case scenario
                    :different-title (assoc source :source-key "another-record")
                    :different-binding (assoc source :binding-uuid
                                              (block-uuid-by-title @conn "Synthetic source binding B"))
                    :wrong-key (assoc source :key-uuid pdf-a)
                    source)]
      (case scenario
        :owner-moved (d/transact! conn [[:db/retract [:block/uuid paper-a]
                                        :logseq.property/literature-source-key key-ref]
                                       [:db/add [:block/uuid paper-b]
                                        :logseq.property/literature-source-key key-ref]])
        :ambiguous-owner (d/transact! conn [[:db/add [:block/uuid paper-b]
                                            :logseq.property/literature-source-key key-ref]])
        :deleted-paper (d/transact! conn [[:db/add [:block/uuid paper-a]
                                          :logseq.property/deleted-at 1]])
        :deleted-target (d/transact! conn [[:db/add [:block/uuid paper-b]
                                           :logseq.property/deleted-at 1]])
        :deleted-key (d/transact! conn [[:db/add key-ref :logseq.property/deleted-at 1]])
        :deleted-binding (d/transact! conn [[:db/add [:block/uuid (:binding-uuid source)]
                                            :logseq.property/deleted-at 1]])
        :wrong-property (d/transact! conn [[:db/add key-ref
                                           :logseq.property/created-from-property
                                           :logseq.property/literature-doi]])
        :unlinked (d/transact! conn [[:db/retract key-ref
                                      :logseq.property/literature-source-target
                                      [:block/uuid (:binding-uuid source)]]])
        nil)
      (let [before @conn
            plan (literature/build-correct-source-record-plan
                  before paper-a source' :reassign paper-b)]
        (is (= :blocked (:status plan)) (name scenario))
        (is (nil? (:ops plan)) (name scenario))
        (is (identical? before @conn) (name scenario))))))

(deftest source-correction-context-replays-forward-and-exact-inverse
  (doseq [action [:unlink :reassign]]
    (let [conn (create-literature-conn)
          source (correction-source @conn)
          plan (literature/build-correct-source-record-plan
                @conn paper-a source action (when (= :reassign action) paper-b))
          forward (get-in plan [:ops 0 1 1 :literature/correct-source-record])
          inverse (get-in plan [:inverse-ops 0 1 1 :literature/correct-source-record])
          before (d/pull @conn '[*] [:block/uuid pdf-a])]
      (is (= {:paper-uuid paper-a :source source :action action
              :target-paper-uuid (when (= :reassign action) paper-b) :inverse? false}
             forward))
      (is (= (assoc forward :inverse? true) inverse))
      (doseq [context [forward inverse forward inverse]]
        (let [db @conn
              replay (literature/build-correct-source-record-replay-plan db context)]
          (is (= :ready (:status replay)))
          (is (identical? db @conn))
          (outliner-op/apply-ops! conn [[:transact [(:tx-data replay) {}]]] {})))
      (is (= paper-a (:paper-uuid (literature-source/find-source-record-owner
                                  @conn (:binding-uuid source) source-key-a))))
      (is (= before (d/pull @conn '[*] [:block/uuid pdf-a]))))))

(deftest source-correction-replay-does-not-follow-a-concurrent-owner
  (doseq [action [:unlink :reassign]
          inverse? [false true]]
    (let [conn (create-literature-conn)
          source (correction-source @conn)
          plan (literature/build-correct-source-record-plan @conn paper-a source action paper-b)
          context (get-in plan [(if inverse? :inverse-ops :ops) 0 1 1
                               :literature/correct-source-record])
          key-ref [:block/uuid (:key-uuid source)]]
      (when inverse?
        (outliner-op/apply-ops! conn (:ops plan) {}))
      (d/transact! conn [[:db/retract [:block/uuid (if (and inverse? (= :reassign action))
                                                  paper-b paper-a)]
                         :logseq.property/literature-source-key key-ref]
                        [:db/add [:block/uuid paper-no-id]
                         :logseq.property/literature-source-key key-ref]])
      (let [before @conn
            replay (literature/build-correct-source-record-replay-plan before context)]
        (is (= :blocked (:status replay)) (str action " inverse=" inverse?))
        (is (nil? (:tx-data replay)))
        (is (identical? before @conn))))))

(deftest source-unlink-inverse-requires-the-unchanged-inactive-key
  (doseq [scenario [:changed-title :changed-property :changed-binding
                    :deleted-paper :deleted-key :deleted-binding
                    :additional-owner :missing-owner :new-active-record]]
    (let [conn (create-literature-conn)
          source (correction-source @conn)
          plan (literature/build-correct-source-record-plan @conn paper-a source :unlink nil)
          inverse (get-in plan [:inverse-ops 0 1 1 :literature/correct-source-record])
          key-ref [:block/uuid (:key-uuid source)]]
      (outliner-op/apply-ops! conn (:ops plan) {})
      (case scenario
        :changed-title (d/transact! conn [[:db/add key-ref :block/title "Changed source"]])
        :changed-property (d/transact! conn [[:db/add key-ref :logseq.property/created-from-property
                                            :logseq.property/literature-doi]])
        :changed-binding (d/transact! conn [[:db/add key-ref :logseq.property/literature-source-target
                                           [:block/uuid (block-uuid-by-title @conn "Synthetic source binding B")]]])
        :deleted-paper (d/transact! conn [[:db/add [:block/uuid paper-a] :logseq.property/deleted-at 1]])
        :deleted-key (d/transact! conn [[:db/add key-ref :logseq.property/deleted-at 1]])
        :deleted-binding (d/transact! conn [[:db/add [:block/uuid (:binding-uuid source)]
                                           :logseq.property/deleted-at 1]])
        :additional-owner (d/transact! conn [[:db/add [:block/uuid paper-no-id]
                                            :logseq.property/literature-source-key key-ref]])
        :missing-owner (d/transact! conn [[:db/retract [:block/uuid paper-a]
                                         :logseq.property/literature-source-key key-ref]])
        :new-active-record
        (let [paper-id (:db/id (d/entity @conn [:block/uuid paper-no-id]))]
          (outliner-property/set-block-property! conn paper-id :logseq.property/literature-source-key source-key-a)
          (let [new-key (first (:logseq.property/literature-source-key (d/entity @conn paper-id)))]
            (outliner-property/set-block-property! conn (:db/id new-key)
                                                   :logseq.property/literature-source-target
                                                   (:db/id (d/entity @conn [:block/uuid (:binding-uuid source)]))))))
      (let [before @conn
            replay (literature/build-correct-source-record-replay-plan before inverse)]
        (is (= :blocked (:status replay)) (name scenario))
        (is (nil? (:tx-data replay)))
        (is (identical? before @conn))))))

(defn- source-owner-unlink-fixture
  [conflict-shape]
  (let [conn (create-literature-conn)
        source (correction-source @conn)
        key-ref [:block/uuid (:key-uuid source)]
        other-key (first (:logseq.property/literature-source-key
                          (d/entity @conn [:block/uuid paper-b])))]
    (case conflict-shape
      :shared-key (d/transact! conn [[:db/add [:block/uuid paper-b]
                                     :logseq.property/literature-source-key key-ref]])
      :distinct-keys (d/transact! conn [[:db/add (:db/id other-key) :block/title source-key-a]
                                       [:db/add (:db/id other-key)
                                        :logseq.property/literature-source-target
                                        [:block/uuid (:binding-uuid source)]]])
      :single-owner nil)
    {:conn conn :source source}))

(deftest source-owner-unlink-removes-only-the-selected-edge
  (doseq [conflict-shape [:shared-key :distinct-keys :single-owner]]
    (let [{:keys [conn source]} (source-owner-unlink-fixture conflict-shape)
          key-ref [:block/uuid (:key-uuid source)]
          annotation-uuid #uuid "74000000-0000-4000-8000-000000000496"]
      (outliner-op/apply-ops!
       conn [[:insert-blocks [[{:block/uuid annotation-uuid
                               :block/title "Preserved PDF annotation"}]
                             pdf-a {:keep-uuid? true :bottom? true :sibling? false}]]]
       {})
      (let [before @conn
            selected-keys (set (map :block/uuid
                                   (:logseq.property/literature-source-key
                                    (d/entity before [:block/uuid paper-a]))))
            paper-facts #(d/pull %
                                '[:block/uuid :block/title :block/tags :block/order
                                  :logseq.property/literature-item?
                                  {:block/parent [:block/uuid]}
                                  {:block/page [:block/uuid]}
                                  {:logseq.property/literature-doi [:block/uuid :block/title]}
                                  {:logseq.property/literature-arxiv [:block/uuid :block/title]}
                                  {:logseq.property/literature-authors [:block/uuid :block/title]}
                                  {:logseq.property/literature-type [:block/uuid :block/title]}
                                  {:logseq.property/literature-assets [:block/uuid]}]
                                [:block/uuid paper-a])
            protected-uuids [paper-b paper-no-id paper-incomplete
                             pdf-a pdf-b pdf-duplicate image-a annotation-uuid
                             (:key-uuid source) (:binding-uuid source)]
            protected-before (d/pull-many before '[*] (mapv #(vector :block/uuid %) protected-uuids))
            plan (literature/build-correct-source-record-plan before paper-a source :unlink-owner nil)]
        (when (not= :single-owner conflict-shape)
          (is (= :conflict (:status (literature-source/find-source-record-owner
                                    before (:binding-uuid source) source-key-a))))
          (doseq [old-action [:unlink :reassign]]
            (is (= :blocked (:status (literature/build-correct-source-record-plan
                                      before paper-a source old-action paper-no-id))))))
        (is (= :ready (:status plan)) (name conflict-shape))
        (is (identical? before @conn))
        (is (= [[:db/retract [:block/uuid paper-a]
                 :logseq.property/literature-source-key key-ref]]
               (get-in plan [:ops 0 1 0])))
        (is (= [[:db/add [:block/uuid paper-a]
                 :logseq.property/literature-source-key key-ref]]
               (get-in plan [:inverse-ops 0 1 0])))
        (outliner-op/apply-ops! conn (:ops plan) {:persist-op? true})
        (is (= (disj selected-keys (:key-uuid source))
               (set (map :block/uuid (:logseq.property/literature-source-key
                                     (d/entity @conn [:block/uuid paper-a]))))))
        (is (= (paper-facts before) (paper-facts @conn)))
        (is (= protected-before
               (d/pull-many @conn '[*] (mapv #(vector :block/uuid %) protected-uuids))))
        (is (= (:binding-uuid source)
               (:block/uuid (:logseq.property/literature-source-target
                             (d/entity @conn key-ref)))))
        (is (= (if (= :single-owner conflict-shape)
                 {:status :missing}
                 {:status :found :binding-uuid (:binding-uuid source)
                  :source-key source-key-a :paper-uuid paper-b})
               (literature-source/find-source-record-owner
                @conn (:binding-uuid source) source-key-a)))))))

(deftest source-owner-unlink-rechecks-the-exact-live-source-edge
  (doseq [scenario [:wrong-key :not-paper :unlinked-binding :owner-moved :replacement-key]]
    (let [{:keys [conn source]} (source-owner-unlink-fixture :shared-key)
          key-ref [:block/uuid (:key-uuid source)]
          source' (case scenario
                    :wrong-key (assoc source :key-uuid pdf-a)
                    source)]
      (case scenario
        :not-paper (d/transact! conn [[:db/retract [:block/uuid paper-a] :logseq.property/literature-item? true]])
        :unlinked-binding (d/transact! conn [[:db/retract key-ref :logseq.property/literature-source-target
                                             [:block/uuid (:binding-uuid source)]]])
        :owner-moved (d/transact! conn [[:db/retract [:block/uuid paper-a]
                                        :logseq.property/literature-source-key key-ref]])
        :replacement-key
        (let [other-key (first (remove #(= (:key-uuid source) (:block/uuid %))
                                      (:logseq.property/literature-source-key
                                       (d/entity @conn [:block/uuid paper-a]))))]
          (d/transact! conn [[:db/retract [:block/uuid paper-a]
                             :logseq.property/literature-source-key key-ref]
                            [:db/add (:db/id other-key) :block/title source-key-a]]))
        nil)
      (let [before @conn
            context {:paper-uuid paper-a :source source' :action :unlink-owner
                     :target-paper-uuid nil :inverse? false}
            plan (literature/build-correct-source-record-plan before paper-a source' :unlink-owner nil)
            replay (literature/build-correct-source-record-replay-plan before context)]
        (is (= :blocked (:status plan)) (name scenario))
        (is (nil? (:ops plan)))
        (is (nil? (:inverse-ops plan)))
        (is (= :blocked (:status replay)) (name scenario))
        (is (nil? (:tx-data replay)))
        (is (identical? before @conn))))))

(deftest source-owner-unlink-context-replays-the-exact-edge-in-both-directions
  (doseq [conflict-shape [:shared-key :distinct-keys :single-owner]]
    (let [{:keys [conn source]} (source-owner-unlink-fixture conflict-shape)
          key-ref [:block/uuid (:key-uuid source)]
          plan (literature/build-correct-source-record-plan @conn paper-a source :unlink-owner nil)
          forward (get-in plan [:ops 0 1 1 :literature/correct-source-record])
          inverse (get-in plan [:inverse-ops 0 1 1 :literature/correct-source-record])
          key-before (d/pull @conn '[*] key-ref)
          other-before (d/pull @conn '[*] [:block/uuid paper-b])]
      (is (= {:paper-uuid paper-a :source source :action :unlink-owner
              :target-paper-uuid nil :inverse? false} forward))
      (is (= (assoc forward :inverse? true) inverse))
      (doseq [context [forward inverse forward inverse]]
        (let [before @conn
              replay (literature/build-correct-source-record-replay-plan before context)]
          (is (= :ready (:status replay)))
          (is (identical? before @conn))
          (is (= [[(if (:inverse? context) :db/add :db/retract)
                   [:block/uuid paper-a] :logseq.property/literature-source-key key-ref]]
                 (:tx-data replay)))
          (outliner-op/apply-ops! conn [[:transact [(:tx-data replay) {}]]] {})))
      (is (contains? (set (map :block/uuid (:logseq.property/literature-source-key
                                          (d/entity @conn [:block/uuid paper-a]))))
                     (:key-uuid source)))
      (is (= key-before (d/pull @conn '[*] key-ref)))
      (is (= other-before (d/pull @conn '[*] [:block/uuid paper-b])))
      ;; Undo restores the selected historical relationship, including a conflict;
      ;; a change to other owners does not grant authority to edit their edges.
      (outliner-op/apply-ops! conn (:ops plan) {})
      (d/transact! conn [[:db/add [:block/uuid paper-no-id]
                         :logseq.property/literature-source-key key-ref]])
      (let [other-owner-before (d/pull @conn '[*] [:block/uuid paper-no-id])
            replay (literature/build-correct-source-record-replay-plan @conn inverse)]
        (is (= :ready (:status replay)))
        (is (= [[:db/add [:block/uuid paper-a]
                 :logseq.property/literature-source-key key-ref]] (:tx-data replay)))
        (outliner-op/apply-ops! conn [[:transact [(:tx-data replay) {}]]] {})
        (is (= other-owner-before (d/pull @conn '[*] [:block/uuid paper-no-id])))
        (is (= other-before (d/pull @conn '[*] [:block/uuid paper-b])))
        (is (= :conflict (:status (literature-source/find-source-record-owner
                                  @conn (:binding-uuid source) source-key-a))))))))

(deftest source-owner-unlink-inverse-rejects-stale-or-already-restored-edges
  (doseq [scenario [:changed-title :changed-property :changed-binding :unlinked-binding
                    :deleted-paper :not-paper :deleted-key :deleted-binding
                    :already-restored :replacement-key]]
    (let [{:keys [conn source]} (source-owner-unlink-fixture :shared-key)
          key-ref [:block/uuid (:key-uuid source)]
          plan (literature/build-correct-source-record-plan @conn paper-a source :unlink-owner nil)
          inverse (get-in plan [:inverse-ops 0 1 1 :literature/correct-source-record])]
      (outliner-op/apply-ops! conn (:ops plan) {})
      (case scenario
        :changed-title (d/transact! conn [[:db/add key-ref :block/title "Changed source"]])
        :changed-property (d/transact! conn [[:db/add key-ref :logseq.property/created-from-property
                                            :logseq.property/literature-doi]])
        :changed-binding (d/transact! conn [[:db/add key-ref :logseq.property/literature-source-target
                                           [:block/uuid (block-uuid-by-title @conn "Synthetic source binding B")]]])
        :unlinked-binding (d/transact! conn [[:db/retract key-ref :logseq.property/literature-source-target
                                             [:block/uuid (:binding-uuid source)]]])
        :deleted-paper (d/transact! conn [[:db/add [:block/uuid paper-a] :logseq.property/deleted-at 1]])
        :not-paper (d/transact! conn [[:db/retract [:block/uuid paper-a] :logseq.property/literature-item? true]])
        :deleted-key (d/transact! conn [[:db/add key-ref :logseq.property/deleted-at 1]])
        :deleted-binding (d/transact! conn [[:db/add [:block/uuid (:binding-uuid source)]
                                           :logseq.property/deleted-at 1]])
        :already-restored (d/transact! conn [[:db/add [:block/uuid paper-a]
                                             :logseq.property/literature-source-key key-ref]])
        :replacement-key
        (let [other-key (first (:logseq.property/literature-source-key
                               (d/entity @conn [:block/uuid paper-a])))]
          (d/transact! conn [[:db/add (:db/id other-key) :block/title source-key-a]])))
      (let [before @conn
            replay (literature/build-correct-source-record-replay-plan before inverse)]
        (is (= :blocked (:status replay)) (name scenario))
        (is (nil? (:tx-data replay)))
        (is (identical? before @conn))))))

(deftest source-connection-uses-a-public-page-and-reuses-its-uuid
  (let [conn (create-literature-conn)
        binding-uuid (random-uuid)
        before @conn
        plan (literature/build-source-connection-plan
              @conn binding-uuid {:title "Public destination"})]
    (is (= :ready (:status plan)))
    (is (identical? before @conn))
    (is (= 2 (count (get-in plan [:tx-meta :outliner-ops]))))
    (d/transact! conn (:tx-data plan))
    (let [destination (:destination-page-uuid plan)
          request {:page-uuid destination}
          title-plan (literature/build-source-connection-plan
                      @conn binding-uuid {:title "Public destination"})]
      (is (= destination (:destination-page-uuid title-plan)))
      (is (= :existing (:status title-plan)))
      (is (empty? (:tx-data title-plan)))
      (outliner-op/apply-ops! conn [[:rename-page [destination "Renamed library"]]] {})
      (let [reconnect (literature/build-source-connection-plan @conn binding-uuid request)]
        (is (= :existing (:status reconnect)))
        (is (= destination (:destination-page-uuid reconnect)))
        (is (empty? (:tx-data reconnect))))
      (d/transact! conn [[:db/add [:block/uuid destination] :logseq.property/deleted-at 1]])
      (doseq [destination-request [request {:title "Renamed library"}]]
        (let [before @conn
              blocked (literature/build-source-connection-plan
                       @conn (random-uuid) destination-request)]
          (is (= {:status :blocked :reason :destination-unavailable} blocked))
          (is (identical? before @conn))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest source-connection-does-not-write-a-binding-for-an-unavailable-destination
  (let [conn (create-literature-conn)
        binding-uuid (random-uuid)]
    (doseq [target [(random-uuid) binding-uuid paper-a
                    (:block/uuid (d/entity @conn :logseq.class/Page))]]
      (let [before @conn
            plan (literature/build-source-connection-plan
                  @conn binding-uuid {:page-uuid target})]
        (is (= {:status :blocked :reason :destination-unavailable} plan))
        (is (identical? before @conn))
        (is (nil? (d/entity @conn [:block/uuid binding-uuid])))))))

(deftest source-registration-checks-the-current-public-destination
  (doseq [change [:recycle :hide :move-under-source :move-under-other-page :delete]]
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
          target [:block/uuid destination-page]
          record {:classification :bibliographic-item
                  :source-key "zotero:user:synthetic:item:DEST0001"
                  :doi "10.5555/destination-check"}]
      (case change
        :recycle (d/transact! conn [[:db/add target :logseq.property/deleted-at 1]])
        :hide (d/transact! conn [[:db/add target :logseq.property/hide? true]])
        :move-under-source (d/transact! conn [[:db/add target :block/parent [:block/uuid binding-uuid]]])
        :move-under-other-page (d/transact! conn [[:db/add target :block/parent
                                                 [:block/uuid (block-uuid-by-title @conn "Synthetic source binding B")]]])
        :delete (outliner-op/apply-ops!
                 conn [[:delete-page [destination-page {}]]
                       [:recycle-delete-permanently [destination-page]]] {}))
      (let [before @conn
            plan (literature/build-register-source-record-plan
                  @conn binding-uuid destination-page record)]
        (is (= {:status :blocked :reason :destination-unavailable} plan))
        (is (identical? before @conn))))))

(deftest deleting-the-source-retains-the-public-paper-and-identifiers
  (let [conn (create-literature-conn)
        binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
        record {:classification :bibliographic-item
                :source-key "zotero:user:synthetic:item:PUBLIC01"
                :doi "10.5555/public-survival"}
        plan (literature/build-register-source-record-plan
              @conn binding-uuid destination-page record)]
    (d/transact! conn (:tx-data plan))
    (outliner-op/apply-ops! conn [[:delete-page [binding-uuid {}]]
                                [:recycle-delete-permanently [binding-uuid]]] {})
    (let [paper (d/entity @conn [:block/uuid (:paper-uuid plan)])]
      (is (= destination-page (:block/uuid (:block/parent paper))))
      (is (= destination-page (:block/uuid (:block/page paper))))
      (is (= "10.5555/public-survival" (:block/title (:logseq.property/literature-doi paper))))
      (is (empty? (:logseq.property/literature-source-key paper)))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest source-record-registration-preserves-retained-inactive-links
  (let [conn (create-literature-conn)
        binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
        binding-id (:db/id (d/entity @conn [:block/uuid binding-uuid]))
        source-node (ffirst (source-value-nodes @conn source-key-a))
        record {:classification :bibliographic-item
                :source-key source-key-a
                :doi "10.1234/example"}]
    (outliner-property/remove-block-property!
     conn source-node :logseq.property/literature-source-target)
    (let [before @conn
          plan (literature/build-register-source-record-plan
                before binding-uuid destination-page record)]
      (is (= :blocked (:status plan)))
      (is (nil? (:tx-data plan)))
      (is (= before @conn)))
    (outliner-property/set-block-property!
     conn source-node :logseq.property/literature-source-target binding-id)
    (is (= :already-imported
           (:status (literature/build-register-source-record-plan
                     @conn binding-uuid destination-page record))))
    (doseq [node [source-node (:db/id (d/entity @conn [:block/uuid paper-a]))]]
      (d/transact! conn [[:db/add node :logseq.property/deleted-at 1]])
      (let [plan (literature/build-register-source-record-plan
                  @conn binding-uuid destination-page record)]
        (is (= :blocked (:status plan)))
        (is (nil? (:tx-data plan))))
      (d/transact! conn [[:db/retract node :logseq.property/deleted-at]]))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest source-record-registration-does-not-treat-ordinary-text-as-a-link
  (let [conn (create-literature-conn)
        binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
        source-key "zotero:user:synthetic:item:UNSEEN01"
        record {:classification :bibliographic-item
                :source-key source-key
                :doi "10.5555/unseen-source"}]
    (sqlite-build/create-blocks
     conn
     {:pages-and-blocks
      [{:page {:block/title "Ordinary source-key notes"}
        :blocks [{:block/title source-key
                  :build/properties {:logseq.property/description source-key}}]}]})
    (let [plan (literature/build-register-source-record-plan
                @conn binding-uuid destination-page record)]
      (is (= :ready (:status plan)))
      (is (= :create (:action plan))))))

(defn- assert-initial-source-registration!
  []
  (testing "a new identifier seeds present metadata only once"
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
          source-record {:classification :bibliographic-item
                         :source-key "zotero:user:synthetic:item:NEW00001"
                         :title "Source-created paper"
                         :authors "Example, Alice"
                         :date "2026"
                         :publication "Synthetic Journal"
                         :type "JOURNAL-ARTICLE"
                         :doi "10.5555/source-created"
                         :url "https://example.invalid/source-created"
                         :citekey "Example2026"}
          expected-paper-uuid (literature/source-record-paper-uuid source-record)
          plan (literature/build-register-source-record-plan
                @conn binding-uuid destination-page source-record)]
      (is (= :ready (:status plan)))
      (is (= :create (:action plan)))
      (is (= expected-paper-uuid (:paper-uuid plan)))
      (d/transact! conn (:tx-data plan))
      (let [paper (d/entity @conn [:block/uuid expected-paper-uuid])]
        (is (= "Source-created paper" (:block/title paper)))
        (is (= destination-page (:block/uuid (:block/parent paper))))
        (is (= "10.5555/source-created"
               (:block/title (:logseq.property/literature-doi paper))))
        (is (= "Example, Alice"
               (:block/title (:logseq.property/literature-authors paper))))
        (is (= "2026" (:block/title (:logseq.property/literature-date paper))))
        (is (= "Synthetic Journal"
               (:block/title (:logseq.property/literature-publication paper))))
        (is (= "journal-article"
               (:block/title (:logseq.property/literature-type paper))))
        (is (= "https://example.invalid/source-created"
               (:block/title (:logseq.property/literature-url paper))))
        (is (= "Example2026"
               (:block/title (:logseq.property/literature-citekey paper)))))
      (let [review (literature/build-linked-source-record-metadata-plan
                    @conn binding-uuid expected-paper-uuid source-record)]
        (is (= :already-imported (:status review)))
        (is (empty? (:changes review))))
      (is (= expected-paper-uuid
             (get-in (literature/find-paper-by-source-key
                      @conn (:source-key source-record))
                     [:papers 0 :block/uuid])))
      (is (= expected-paper-uuid
             (get-in (literature/find-paper-by-identifiers
                      @conn {:doi "10.5555/source-created"})
                     [:papers 0 :block/uuid])))
      (is (= :already-imported
             (:status (literature/build-register-source-record-plan
                       @conn binding-uuid destination-page source-record))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest source-record-registration-is-current-db-checked-and-idempotent
  (assert-initial-source-registration!)

  (testing "an exact identifier links the source without creating another paper"
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
          source-record {:classification :bibliographic-item
                         :source-key "zotero:user:synthetic:item:LINK0001"
                         :title "Provider title may differ"
                         :doi "https://doi.org/10.1234/EXAMPLE"}
          plan (literature/build-register-source-record-plan
                @conn binding-uuid destination-page source-record)]
      (is (= :ready (:status plan)))
      (is (= :link (:action plan)))
      (is (= paper-a (:paper-uuid plan)))
      (d/transact! conn (:tx-data plan))
      (is (= paper-a
             (get-in (literature/find-paper-by-source-key
                      @conn (:source-key source-record))
                     [:papers 0 :block/uuid])))
      (is (nil? (d/entity @conn [:block/uuid paper-c])))))

  (testing "identifier-free exact bibliography and child items stop before a write"
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
          datom-count (count (d/datoms @conn :eavt))
          exact-result
          (literature/build-register-source-record-plan
           @conn binding-uuid destination-page
           {:classification :bibliographic-item
            :source-key "zotero:user:synthetic:item:MANUAL01"
            :title "Paper A"
            :authors "Alice Example; Bob Researcher"})
          child-result
          (literature/build-register-source-record-plan
           @conn binding-uuid destination-page
           {:classification :child-item
            :source-key "zotero:user:synthetic:item:CHILD001"
            :title "Paper PDF"})]
      (is (= {:status :manual
              :reason :exact-bibliography-candidate}
             (select-keys exact-result [:status :reason])))
      (is (= [paper-a] (mapv :block/uuid (:papers exact-result))))
      (is (= {:status :blocked :reason :not-top-level-bibliography}
             child-result))
      (is (= datom-count (count (d/datoms @conn :eavt))))))

  (testing "identifier-free source records wait for explicit review"
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")]
      (is (= {:status :manual :reason :missing-stable-identifier}
             (literature/build-register-source-record-plan
              @conn binding-uuid destination-page
              {:classification :bibliographic-item
               :source-key "zotero:user:synthetic:item:NOIDENT1"
               :title "Unique source-only title"})))))

  (testing "missing source identity, identifier-free title or binding is rejected"
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
          base-record {:classification :bibliographic-item
                       :source-key "zotero:user:synthetic:item:VALID001"
                       :title "Valid title"}]
      (is (= {:status :blocked :reason :missing-source-key}
             (literature/build-register-source-record-plan
              @conn binding-uuid destination-page (assoc base-record :source-key " "))))
      (is (= {:status :blocked :reason :missing-title}
             (literature/build-register-source-record-plan
              @conn binding-uuid destination-page (assoc base-record :title nil))))
      (let [identified-plan
            (literature/build-register-source-record-plan
             @conn binding-uuid destination-page
             (assoc base-record :title nil :doi "10.5555/missing-title"))]
        (is (= :ready (:status identified-plan)))
        (is (= :create (:action identified-plan))))
      (is (= {:status :blocked :reason :source-binding-unavailable}
             (literature/build-register-source-record-plan
              @conn (random-uuid) destination-page base-record))))))

(deftest source-record-creation-appends-after-existing-siblings-with-unrelated-global-order
  (let [conn (create-literature-conn)
        binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
        existing-orders (into {}
                              (map (fn [uuid]
                                     [uuid (:block/order (d/entity @conn [:block/uuid uuid]))]))
                              [paper-a paper-b])
        unrelated-order (fractional-indexing/generate-key-between
                         (db-order/get-max-order @conn) nil)
        _ (d/transact! conn [{:db/id [:block/uuid pdf-a]
                              :block/order unrelated-order}])
        record-a {:classification :bibliographic-item
                  :source-key "zotero:user:synthetic:item:ORDER0001"
                  :title "Order test A"
                  :doi "10.5555/order-a"}
        record-b {:classification :bibliographic-item
                  :source-key "zotero:user:synthetic:item:ORDER0002"
                  :title "Order test B"
                  :doi "10.5555/order-b"}
        plan-a (literature/build-register-source-record-plan
                @conn binding-uuid destination-page record-a)
        paper-a-uuid (literature/source-record-paper-uuid record-a)
        order-a (some (fn [tx]
                        (when (= paper-a-uuid (:block/uuid tx))
                          (:block/order tx)))
                      (:tx-data plan-a))]
    (is (= :ready (:status plan-a)))
    (is (pos? (compare order-a unrelated-order)))
    (d/transact! conn (:tx-data plan-a))
    (let [plan-b (literature/build-register-source-record-plan
                  @conn binding-uuid destination-page record-b)
          paper-b-uuid (literature/source-record-paper-uuid record-b)
          order-b (some (fn [tx]
                          (when (= paper-b-uuid (:block/uuid tx))
                            (:block/order tx)))
                        (:tx-data plan-b))]
      (is (= :ready (:status plan-b)))
      (is (pos? (compare order-b order-a)))
      (d/transact! conn (:tx-data plan-b))
      (is (= destination-page
             (:block/uuid (:block/parent
                           (d/entity @conn [:block/uuid paper-b-uuid])))))
      (is (= existing-orders
             (into {}
                   (map (fn [uuid]
                          [uuid (:block/order
                                 (d/entity @conn [:block/uuid uuid]))]))
                   [paper-a paper-b])))
      (is (= :already-imported
             (:status (literature/build-register-source-record-plan
                       @conn binding-uuid destination-page record-a))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest source-record-values-survive-independently-of-the-paper
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        records [{:classification :bibliographic-item
                  :source-key "zotero:user:synthetic-a:item:OWNED001"
                  :doi "10.5555/owned-sources"}
                 {:classification :bibliographic-item
                  :source-key "zotero:user:synthetic-b:item:OWNED001"
                  :doi "10.5555/owned-sources"}]]
    (doseq [[binding record expected-action]
            [[binding-a (first records) :create]
             [binding-b (second records) :link]]]
      (let [plan (literature/build-register-source-record-plan @conn binding destination-page record)]
        (is (= :ready (:status plan)))
        (is (= expected-action (:action plan)))
        (d/transact! conn (:tx-data plan))
        (is (empty? (:errors (db-validate/validate-db @conn))))))
    (let [paper-uuid (literature/source-record-paper-uuid (first records))
          paper (d/entity @conn [:block/uuid paper-uuid])
          source-values (:logseq.property/literature-source-key paper)]
      (is (= 2 (count source-values)))
      (is (= (set (map :source-key records)) (set (map :block/title source-values))))
      (is (= #{binding-a binding-b}
             (set (map #(-> % :logseq.property/literature-source-target :block/uuid)
                       source-values))))
      (doseq [source-value source-values]
        (let [binding-uuid (:block/uuid (:logseq.property/literature-source-target source-value))]
          (is (= binding-uuid (:block/uuid (:block/parent source-value))))
          (is (= binding-uuid (:block/uuid (:block/page source-value))))))
      (doseq [binding [binding-a binding-b]]
        (is (nil? (:logseq.property/literature-source-key
                   (d/entity @conn [:block/uuid binding])))))
      (let [before @conn
            repeat-plan (literature/build-register-source-record-plan
                         before binding-b destination-page (second records))]
        (is (= :already-imported (:status repeat-plan)))
        (is (nil? (:tx-data repeat-plan)))
        (is (identical? before @conn))))))

(deftest identifier-free-source-manual-creation-preserves-bibliography
  (let [conn (create-literature-conn)
        binding (block-uuid-by-title @conn "Synthetic source binding A")]
    (doseq [[type paper-uuid] [["book" #uuid "79000000-0000-4000-8000-000000000201"]
                             ["report" #uuid "79000000-0000-4000-8000-000000000202"]
                             ["webpage" #uuid "79000000-0000-4000-8000-000000000203"]]]
      (let [record {:classification :bibliographic-item
                    :source-key (str "zotero:synthetic:manual:" type)
                    :title (str "Synthetic " type)
                    :authors "Synthetic Author"
                    :date "2026"
                    :publication "Synthetic Publisher"
                    :type type
                    :url "https://example.invalid/item"}
            _ (is (= :manual (:status (literature/build-register-source-record-plan
                                       @conn binding destination-page record))))
            chosen (assoc record :paper-uuid paper-uuid :create? true)
            plan (literature/build-register-source-record-plan
                  @conn binding destination-page chosen)]
        (is (= [:ready :create paper-uuid]
               ((juxt :status :action :paper-uuid) plan)))
        (d/transact! conn (:tx-data plan))
        (let [paper (d/entity @conn [:block/uuid paper-uuid])
              repeat-plan (literature/build-register-source-record-plan
                           @conn binding destination-page record)]
          (is (= (:title record) (:block/title paper)))
          (doseq [[field property] [[:authors :logseq.property/literature-authors]
                                   [:date :logseq.property/literature-date]
                                   [:publication :logseq.property/literature-publication]
                                   [:type :logseq.property/literature-type]
                                   [:url :logseq.property/literature-url]]]
            (is (= (get record field) (:block/title (get paper property)))))
          (is (= [:already-imported paper-uuid]
                 ((juxt :status :paper-uuid) repeat-plan)))
          (is (nil? (:tx-data repeat-plan))))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest identifier-free-source-manual-link-keeps-shared-metadata-and-requires-live-target
  (let [conn (create-literature-conn)
        binding (block-uuid-by-title @conn "Synthetic source binding A")
        record {:classification :bibliographic-item
                :source-key "zotero:synthetic:manual:existing"
                :title "Different incoming title"
                :authors "Different incoming author"
                :type "book"
                :paper-uuid paper-a
                :create? false}
        metadata #(d/pull @conn '[:block/title
                                 {:logseq.property/literature-authors [:block/title]}
                                 {:logseq.property/literature-type [:block/title]}]
                         [:block/uuid paper-a])
        before (metadata)
        plan (literature/build-register-source-record-plan @conn binding destination-page record)]
    (is (= [:ready :link paper-a] ((juxt :status :action :paper-uuid) plan)))
    (d/transact! conn (:tx-data plan))
    (is (= before (metadata)))
    (is (= :found (:status (literature/find-paper-by-source-key @conn (:source-key record)))))
    (let [pending-choice (assoc record :paper-uuid paper-no-id :source-key "zotero:synthetic:manual:deleted")]
      (is (= :ready (:status (literature/build-register-source-record-plan
                             @conn binding destination-page pending-choice))))
      (d/transact! conn [[:db/add [:block/uuid paper-no-id] :logseq.property/deleted-at 1]])
      (is (= {:status :blocked :reason :paper-uuid-conflict}
             (literature/build-register-source-record-plan
              @conn binding destination-page pending-choice))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest source-record-registration-does-not-recreate-removed-paper-links
  (doseq [removal [:delete-paper :remove-property]]
    (let [conn (create-literature-conn)
          binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
          record {:classification :bibliographic-item
                  :source-key "zotero:user:synthetic:item:REMOVED1"
                  :doi "10.5555/removed-source"}
          plan (literature/build-register-source-record-plan @conn binding-uuid destination-page record)
          paper-uuid (:paper-uuid plan)]
      (d/transact! conn (:tx-data plan))
      (let [paper (d/entity @conn [:block/uuid paper-uuid])
            key-uuid (:block/uuid (first (:logseq.property/literature-source-key paper)))]
        (case removal
          :delete-paper (outliner-op/apply-ops! conn [[:delete-blocks [[paper-uuid] {}]]] {})
          :remove-property (outliner-property/remove-block-property!
                            conn (:db/id paper) :logseq.property/literature-source-key))
        (is (some? (d/entity @conn [:block/uuid key-uuid])))
        (let [before @conn
              repeat-plan (literature/build-register-source-record-plan before binding-uuid destination-page record)]
          (is (= {:status :blocked :reason :source-link-unavailable} repeat-plan))
          (is (identical? before @conn)))
        (is (empty? (:errors (db-validate/validate-db @conn))))))))

(deftest source-record-first-import-seeds-known-metadata-without-later-overwrite
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        a {:classification :bibliographic-item
           :source-key "zotero:synthetic:A:INITIAL"
           :doi "10.5555/first-metadata"
           :title "First source title"
           :authors "First author"}
        b (assoc a :source-key "zotero:synthetic:B:INITIAL"
                 :title "Other source title" :authors "Other author")
        first-plan (literature/build-register-source-record-plan
                    @conn binding-a destination-page a)
        paper-uuid (:paper-uuid first-plan)]
    (is (= :create (:action first-plan)))
    (d/transact! conn (:tx-data first-plan))
    (let [late-plan (literature/build-register-source-record-plan
                     @conn binding-b destination-page b)]
      (is (= :link (:action late-plan)))
      (d/transact! conn (:tx-data late-plan)))
    (let [paper (d/entity @conn [:block/uuid paper-uuid])]
      (is (= "First source title" (:block/title paper)))
      (is (= "First author"
             (:block/title (:logseq.property/literature-authors paper))))
      (is (nil? (:logseq.property/literature-date paper)))
      (is (nil? (:logseq.property/literature-publication paper)))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest source-record-paper-uuid-normalizes-strong-identifiers
  (is (= (literature/source-record-paper-uuid
          {:doi "https://doi.org/10.5555/DETERMINISTIC"})
         (literature/source-record-paper-uuid
          {:doi "doi:10.5555/deterministic"})))
  (is (= (literature/source-record-paper-uuid
          {:arxiv "arXiv:2401.12345v3"})
         (literature/source-record-paper-uuid
          {:arxiv "2401.12345"})))
  (is (nil? (literature/source-record-paper-uuid
             {:source-key "zotero:user:synthetic:item:NOIDENT2"}))))

(deftest source-record-metadata-plan-is-read-only-and-requires-review
  (let [conn (create-literature-conn)
        before-db @conn
        before-datoms (count (d/datoms before-db :eavt))]
    (is (= {:status :already-imported
            :paper {:block/uuid paper-a :block/title "Paper A"}}
           (literature/build-source-record-metadata-plan
            @conn paper-a
            {:title "Paper A"
             :authors "Alice Example; Bob Researcher"
             :type "JOURNAL-ARTICLE"
             :doi "https://doi.org/10.1234/EXAMPLE"
             :arxiv "https://arxiv.org/pdf/2101.12345v3.pdf"})))
    (let [plan (literature/build-source-record-metadata-plan
                @conn paper-a
                {:title "Corrected Paper A"
                 :authors "Alice Example; Bob Researcher; Carol Example"
                 :publication "Synthetic Journal"
                 :doi "10.1234/example"})]
      (is (= {:status :manual
              :reason :metadata-review
              :paper-uuid paper-a}
             (select-keys plan [:status :reason :paper-uuid])))
      (is (= [[:title :different-value]
              [:authors :different-value]
              [:publication :missing-without-permission]]
             (mapv (juxt :field :kind) (:changes plan))))
      (is (nil? (:tx-data plan))))
    (let [plan (literature/build-source-record-metadata-plan
                @conn paper-a
                {:title "Paper A"
                 :doi "10.9999/changed"})]
      (is (= {:status :blocked
              :reason :identifier-metadata-conflict
              :paper-uuid paper-a}
             (select-keys plan [:status :reason :paper-uuid])))
      (is (= [[:doi :identity-conflict]]
             (mapv (juxt :field :kind) (:changes plan)))))
    (is (identical? before-db @conn))
    (is (= before-datoms (count (d/datoms @conn :eavt))))))

(deftest linked-source-record-metadata-plan-rechecks-the-exact-link
  (let [conn (create-literature-conn)
        binding-a (block-uuid-by-title @conn "Synthetic source binding A")
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        record {:classification :bibliographic-item
                :source-key source-key-conflict
                :title "Updated Paper B"
                :doi "10.9999/other"}]
    (is (= {:status :manual
            :reason :metadata-review
            :paper-uuid paper-b}
           (select-keys
            (literature/build-linked-source-record-metadata-plan
             @conn binding-b paper-b record)
            [:status :reason :paper-uuid])))
    (is (= {:status :blocked :reason :source-link-mismatch}
           (literature/build-linked-source-record-metadata-plan
            @conn binding-b paper-b
            (assoc record :source-key source-key-a))))
    (is (= {:status :blocked
            :reason :source-link-conflict
            :source-keys [source-key-a source-key-conflict]}
           (literature/build-linked-source-record-metadata-plan
            @conn binding-a paper-a record)))
    (is (= {:status :blocked :reason :source-link-unavailable}
           (literature/build-linked-source-record-metadata-plan
            @conn binding-a paper-b record)))))

(deftest accepted-source-metadata-builds-one-replayable-native-transaction
  (let [conn (create-literature-conn)
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        source-record {:classification :bibliographic-item
                       :source-key source-key-conflict
                       :title "Updated Paper B"
                       :authors "Carol Example; Dana Researcher"
                       :publication "Synthetic Journal"
                       :doi "10.9999/other"}
        review (literature/build-linked-source-record-metadata-plan
                @conn binding-b paper-b source-record)
        authors-before (:logseq.property/literature-authors
                        (d/entity @conn [:block/uuid paper-b]))
        accepted (mapv #(select-keys % [:field :kind :current :source])
                       (:changes review))
        apply-plan (literature/build-apply-source-record-metadata-plan
                    @conn binding-b paper-b source-record accepted)
        alternate-record (assoc source-record :publication "Alternate Journal")
        alternate-review (literature/build-linked-source-record-metadata-plan
                          @conn binding-b paper-b alternate-record)
        alternate-plan (literature/build-apply-source-record-metadata-plan
                        @conn binding-b paper-b alternate-record
                        (mapv #(select-keys % [:field :kind :current :source])
                              (:changes alternate-review)))
        publication-node-uuid (some (fn [tx]
                                      (when (= "Synthetic Journal"
                                               (:block/title tx))
                                        (:block/uuid tx)))
                                    (get-in apply-plan [:ops 0 1 0]))
        alternate-publication-node-uuid
        (some (fn [tx]
                (when (= "Alternate Journal" (:block/title tx))
                  (:block/uuid tx)))
              (get-in alternate-plan [:ops 0 1 0]))]
    (is (= :manual (:status review)))
    (is (= [:title :authors :publication]
           (mapv :field (:changes review))))
    (is (= {:status :ready
            :action :apply-source-record-metadata
            :paper-uuid paper-b}
           (select-keys apply-plan [:status :action :paper-uuid])))
    (is (= [:transact]
           (mapv first (:ops apply-plan))))
    (is (uuid? publication-node-uuid))
    (is (= publication-node-uuid alternate-publication-node-uuid))
    (outliner-op/apply-ops!
     conn (:ops apply-plan)
     {:data {:literature/test-metadata-accept true}})
    (let [paper (d/entity @conn [:block/uuid paper-b])
          authors (:logseq.property/literature-authors paper)
          publication (:logseq.property/literature-publication paper)]
      (is (= "Updated Paper B" (:block/title paper)))
      ;; Literature entries intentionally remain ordinary blocks. A title
      ;; update must not turn them into pages by adding :block/name.
      (is (nil? (:block/name paper)))
      (is (= (:block/uuid authors-before) (:block/uuid authors)))
      (is (= "Carol Example; Dana Researcher" (:block/title authors)))
      (is (= "Synthetic Journal" (:block/title publication)))
      (is (= (:db/id paper) (:db/id (:block/parent publication))))
      (is (= :logseq.property/literature-publication
             (:db/ident (:logseq.property/created-from-property publication)))))
    (is (= :already-imported
           (:status (literature/build-linked-source-record-metadata-plan
                     @conn binding-b paper-b source-record))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest metadata-acceptance-is-exact-and-stale-safe
  (let [conn (create-literature-conn)
        binding-b (block-uuid-by-title @conn "Synthetic source binding B")
        source-record {:classification :bibliographic-item
                       :source-key source-key-conflict
                       :title "Updated Paper B"
                       :doi "10.9999/other"}
        review (literature/build-linked-source-record-metadata-plan
                @conn binding-b paper-b source-record)
        accepted [(select-keys (first (:changes review))
                               [:field :kind :current :source])]
        subset-plan (literature/build-apply-source-record-metadata-plan
                     @conn binding-b paper-b source-record accepted)]
    (is (= [:title] (mapv :field (:changes subset-plan))))
    (is (= 1 (count (:ops subset-plan))))
    (is (= {:status :blocked :reason :no-metadata-fields-selected}
           (literature/build-apply-source-record-metadata-plan
            @conn binding-b paper-b source-record [])))
    (is (= {:status :blocked :reason :duplicate-metadata-fields}
           (literature/build-apply-source-record-metadata-plan
            @conn binding-b paper-b source-record
            [(first accepted) (first accepted)])))
    (is (= {:status :blocked :reason :metadata-plan-stale}
           (literature/build-apply-source-record-metadata-plan
            @conn binding-b paper-b
            (assoc source-record :title "Changed again in Zotero")
            accepted)))
    (outliner-core/save-block!
     conn {:block/uuid paper-b :block/title "Changed in graph"})
    (is (= {:status :blocked :reason :metadata-plan-stale}
           (literature/build-apply-source-record-metadata-plan
            @conn binding-b paper-b source-record accepted)))))

(deftest source-record-metadata-plan-never-autofills-marker-looking-values
  (let [conn (create-literature-conn)
        marker-uuid #uuid "74000000-0000-4000-8000-000000000001"
        marker-token (str "[[" marker-uuid "]]")
        paper-eid (:db/id (d/entity @conn [:block/uuid paper-incomplete]))]
    (sqlite-build/create-blocks
     conn {:pages-and-blocks
           [{:page {:block/uuid marker-uuid
                    :block/title "Synthetic marker-looking page"
                    :build/keep-uuid? true}}]})
    (outliner-property/set-block-property!
     conn paper-eid :logseq.property/literature-authors marker-token)
    (let [db-before-plan @conn
          authors-before-plan (:block/title (:logseq.property/literature-authors
                                              (d/entity db-before-plan [:block/uuid paper-incomplete])))
          plan (literature/build-source-record-metadata-plan
                db-before-plan paper-incomplete
                {:title "Incomplete paper"
                 :authors "Example, Alice"
                 :date "2026"
                 :doi "10.7777/incomplete"})]
      (is (= :manual (:status plan)))
      (is (= [[:authors :different-value]
              [:date :missing-without-permission]]
             (mapv (juxt :field :kind) (:changes plan))))
      (is (identical? db-before-plan @conn))
      (is (= authors-before-plan
             (:block/title (:logseq.property/literature-authors
                            (d/entity @conn [:block/uuid paper-incomplete]))))))))

(deftest source-record-metadata-plan-has-bounded-hot-path-cost
  (let [conn (create-literature-conn)
        record {:title "Paper A"
                :authors "Alice Example; Bob Researcher"
                :type "journal-article"
                :doi "10.1234/example"
                :arxiv "2101.12345"}
        before (count (d/datoms @conn :eavt))
        start (js/performance.now)
        statuses (frequencies
                  (map (fn [_]
                         (:status
                          (literature/build-source-record-metadata-plan
                           @conn paper-a record)))
                       (range 10000)))
        elapsed (- (js/performance.now) start)]
    (js/console.log (str "literature metadata plan 10000 records: " elapsed "ms"))
    (is (= {:already-imported 10000} statuses))
    (is (= before (count (d/datoms @conn :eavt))))))

(deftest explicitly-selected-paper-link-is-revalidated-and-idempotent
  (let [conn (create-literature-conn)
        plan (literature/build-link-pdf-plan @conn pdf-duplicate paper-no-id)]
    (testing "a selected literature item needs no DOI or arXiv identifier"
      (is (= :ready (:status plan)))
      (is (= :link (:action plan)))
      (is (= paper-no-id (:paper-uuid plan)))
      (d/transact! conn (:tx-data plan))
      (is (= #{pdf-duplicate}
             (set (map :block/uuid
                       (literature/paper-pdfs @conn paper-no-id)))))
      (let [existing (literature/build-link-pdf-plan @conn pdf-duplicate paper-no-id)]
        (is (= :already-linked (:status existing)))
        (is (= paper-no-id (:paper-uuid existing))
            "Success feedback can open the already linked bibliography entry.")))
    (testing "one PDF cannot be moved to another paper by a stale selection"
      (is (= {:status :blocked
              :reason :asset-linked-to-another-paper
              :paper {:block/uuid paper-no-id
                      :block/title "Paper without identifiers"
                      :matched-by #{[:asset pdf-duplicate]}}}
             (literature/build-link-pdf-plan @conn pdf-duplicate paper-b))))
    (testing "a missing or non-literature target is rejected"
      (is (= {:status :blocked :reason :target-paper-unavailable}
             (literature/build-link-pdf-plan @conn pdf-b (random-uuid))))
      (is (= {:status :blocked :reason :target-paper-unavailable}
             (literature/build-link-pdf-plan @conn pdf-b image-a))))))

(def complete-auto-evidence
  {:identifiers {:doi "https://doi.org/10.1234/EXAMPLE"
                 :arxiv "https://arxiv.org/pdf/2101.12345v3.pdf"}
   :title "Paper A"
   :author-texts ["Alice Example and Bob Researcher"]
   :extraction-status :complete
   :usable? true
   :title-conflict? false
   :identifier-ambiguous? false})

(deftest explicitly-kept-pdf-is-excluded-from-automatic-link-and-registration
  (let [conn (create-literature-conn)
        marker :logseq.property.asset/literature-auto-excluded?
        evidence (assoc complete-auto-evidence
                        :identifiers {:doi "10.5555/new-paper"}
                        :title "A Newly Verified Paper"
                        :author-texts ["Alice Example"])
        metadata {:provider :crossref
                  :doi "10.5555/new-paper"
                  :title "A Newly Verified Paper"
                  :authors "Example, Alice"
                  :type "journal-article"}]
    ;; Seed the native checkbox independently of the implementation under test.
    (when-not (d/entity @conn marker)
      (d/transact!
       conn
       (map sqlite-create-graph/mark-block-as-built-in
            (sqlite-create-graph/build-properties
             {marker {:title "Synthetic ordinary PDF choice"
                      :schema {:type :checkbox :public? false :hide? true}}}))))
    (outliner-property/set-block-property!
     conn (:db/id (d/entity @conn [:block/uuid pdf-duplicate])) marker true)
    (is (= :checkbox (:logseq.property/type (d/entity @conn marker))))
    (is (true? (get (d/entity @conn [:block/uuid pdf-duplicate]) marker)))
    (let [before @conn]
      (doseq [[path plan]
              [["existing paper automatic link"
                (literature/build-auto-link-pdf-plan
                 before pdf-duplicate complete-auto-evidence)]
               ["existing paper automatic registration"
                (literature/build-auto-register-pdf-plan
                 before pdf-duplicate complete-auto-evidence {})]
               ["new paper automatic registration"
                (literature/build-auto-register-pdf-plan
                 before pdf-duplicate evidence metadata)]
               ["target paper automatic association"
                (literature/build-auto-associate-pdf-plan
                 before pdf-duplicate paper-no-id)]]]
        (is (= [:blocked :automatic-excluded]
               ((juxt :status :reason) plan)) path)
        (is (nil? (:tx-data plan)) path))
      (is (identical? before @conn)))
    (outliner-property/set-block-property!
     conn (:db/id (d/entity @conn [:block/uuid pdf-duplicate])) marker false)
    (is (= :ready (:status (literature/build-auto-link-pdf-plan
                           @conn pdf-duplicate complete-auto-evidence))))
    (is (= [:ready :create]
           ((juxt :status :action)
            (literature/build-auto-register-pdf-plan
             @conn pdf-duplicate evidence metadata))))
    (is (= :ready (:status (literature/build-auto-associate-pdf-plan
                           @conn pdf-duplicate paper-no-id))))))

(deftest explicit-pdf-actions-clear-keep-with-the-relation-in-one-transaction
  (doseq [[path build-plan build-blocked-plan target]
          [["selected paper"
            #(literature/build-link-pdf-plan % pdf-duplicate paper-no-id)
            #(literature/build-link-pdf-plan % pdf-duplicate (random-uuid))
            paper-no-id]
           ["existing paper by identifier"
            #(literature/build-register-pdf-plan
              % pdf-duplicate "Paper A" {:doi "10.1234/example"} paper-c)
            #(literature/build-register-pdf-plan
              % pdf-duplicate "Conflict"
              {:doi "10.1234/example" :arxiv "2201.00001"} paper-c)
            paper-a]
           ["new paper without identifiers"
            #(literature/build-register-pdf-plan
              % pdf-duplicate "New explicit paper" {} paper-c)
            #(literature/build-register-pdf-plan
              % pdf-duplicate "" {} paper-c)
            paper-c]]]
    (let [conn (create-literature-conn)
          marker :logseq.property.asset/literature-auto-excluded?
          reports (atom [])]
      (d/transact! conn (:tx-data (literature/build-keep-pdf-plan @conn pdf-duplicate)))
      (let [before @conn
            blocked (build-blocked-plan before)]
        (is (= :blocked (:status blocked)) path)
        (is (nil? (:tx-data blocked)) path)
        (is (identical? before @conn) path)
        (is (true? (get (d/entity @conn [:block/uuid pdf-duplicate]) marker)) path))
      (let [plan (build-plan @conn)]
        (is (= :ready (:status plan)) path)
        (d/listen! conn ::manual-keep #(swap! reports conj %))
        (d/transact! conn (:tx-data plan))
        (d/unlisten! conn ::manual-keep))
      (is (= 1 (count @reports)) path)
      (let [report (first @reports)
            asset-id (:db/id (d/entity (:db-before report) [:block/uuid pdf-duplicate]))
            paper-id (:db/id (d/entity (:db-after report) [:block/uuid target]))
            changes (set (map (juxt :e :a :v :added) (:tx-data report)))]
        (is (contains? changes [asset-id marker true false]) path)
        (is (contains? changes
                       [paper-id :logseq.property/literature-assets asset-id true]) path)
        (is (nil? (get (d/entity (:db-after report) [:block/uuid pdf-duplicate]) marker)) path)
        (is (= target
               (get-in (literature/find-paper-by-asset (:db-after report) pdf-duplicate)
                       [:papers 0 :block/uuid])) path)))))

(deftest keep-and-automatic-association-recheck-current-db-in-either-order
  (testing "association first makes Keep refuse the registered Asset"
    (let [conn (create-literature-conn)
          context {:binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
                   :source-key source-key-a
                   :reference-uuid #uuid "74000000-0000-4000-8000-000000000095"}]
      (is (= :linked (:status (outliner-op/auto-link-literature-pdf!
                              conn pdf-duplicate paper-a context))))
      (let [before @conn
            keep-plan (literature/build-keep-pdf-plan before pdf-duplicate)]
        (is (= [:blocked :asset-already-linked] ((juxt :status :reason) keep-plan)))
        (is (nil? (:tx-data keep-plan)))
        (is (identical? before @conn))
        (is (nil? (:logseq.property.asset/literature-auto-excluded?
                   (d/entity @conn [:block/uuid pdf-duplicate])))))))
  (testing "Keep first makes the real automatic outliner operation refuse association"
    (let [conn (create-literature-conn)
          context {:binding-uuid (block-uuid-by-title @conn "Synthetic source binding A")
                   :source-key source-key-a
                   :reference-uuid #uuid "74000000-0000-4000-8000-000000000095"}]
      (d/transact! conn (:tx-data (literature/build-keep-pdf-plan @conn pdf-duplicate)))
      (let [before @conn
            automatic (outliner-op/auto-link-literature-pdf!
                       conn pdf-duplicate paper-a context)]
        (is (= [:blocked :automatic-excluded]
               ((juxt :status :reason) automatic)))
        (is (identical? before @conn))
        (is (true? (:logseq.property.asset/literature-auto-excluded?
                    (d/entity @conn [:block/uuid pdf-duplicate]))))
        (is (= :missing (:status (literature/find-paper-by-asset @conn pdf-duplicate))))))))

(deftest automatic-pdf-registration-creates-only-a-verified-complete-paper
  (let [conn (create-literature-conn)
        second-pdf-uuid #uuid "72000000-0000-4000-8000-000000000095"
        evidence (assoc complete-auto-evidence
                        :identifiers {:doi "10.5555/new-paper"}
                        :title "A Newly Verified Paper"
                        :author-texts ["Alice Example"])
        metadata {:provider :crossref
                  :doi "10.5555/new-paper"
                  :title "A Newly Verified Paper"
                  :authors "Example, Alice"
                  :type "journal-article"
                  :date "2026"
                  :publication "Synthetic Journal"}
        paper-uuid (literature/source-record-paper-uuid
                    {:doi "10.5555/new-paper"})
        plan (literature/build-auto-register-pdf-plan
              @conn pdf-duplicate evidence metadata)]
    (is (= [:ready :create paper-uuid]
           ((juxt :status :action :paper-uuid) plan)))
    (d/transact! conn (:tx-data plan))
    (let [paper (d/entity @conn [:block/uuid paper-uuid])]
      (is (= "A Newly Verified Paper" (:block/title paper)))
      (is (= "Example, Alice"
             (get-in paper [:logseq.property/literature-authors :block/title])))
      (is (= "journal-article"
             (get-in paper [:logseq.property/literature-type :block/title])))
      (is (= "2026"
             (get-in paper [:logseq.property/literature-date :block/title])))
      (is (= "Synthetic Journal"
             (get-in paper [:logseq.property/literature-publication :block/title])))
      (is (= :found
             (:status (literature/find-paper-by-asset @conn pdf-duplicate)))))
    (sqlite-build/create-blocks
     conn
     {:pages-and-blocks
      [{:page {:block/title "Second synthetic PDF"}
        :blocks [{:block/uuid second-pdf-uuid
                  :block/title "Different bytes of the same paper"
                  :build/tags [:logseq.class/Asset]
                  :build/properties
                  {:logseq.property.asset/type "pdf"
                   :logseq.property.asset/size 220
                   :logseq.property.asset/checksum "second-checksum"}}]}]})
    (let [second-plan (literature/build-auto-register-pdf-plan
                       @conn second-pdf-uuid evidence metadata)]
      (is (= [:ready :link paper-uuid]
             ((juxt :status :action :paper-uuid) second-plan)))
      (d/transact! conn (:tx-data second-plan))
      (is (= #{pdf-duplicate second-pdf-uuid}
             (set (map :block/uuid
                       (literature/paper-pdfs @conn paper-uuid)))))
      (is (= "Example, Alice"
             (get-in (d/entity @conn [:block/uuid paper-uuid])
                     [:logseq.property/literature-authors :block/title]))))
    (is (= :already-linked
           (:status (literature/build-auto-register-pdf-plan
                     @conn pdf-duplicate evidence metadata)))))
  (let [conn (create-literature-conn)
        evidence (assoc complete-auto-evidence
                        :identifiers {:doi "10.5555/new-paper"}
                        :title "A Newly Verified Paper"
                        :author-texts ["Alice Example"])
        metadata {:provider :crossref
                  :doi "10.5555/new-paper"
                  :title "A Newly Verified Paper"
                  :authors "Example, Alice"
                  :type "journal-article"}]
    (doseq [[reason altered-evidence altered-metadata]
            [[:metadata-identifier-mismatch evidence
              (assoc metadata :doi "10.5555/other")]
             [:title-mismatch evidence
              (assoc metadata :title "A Different Paper")]
             [:author-mismatch evidence
              (assoc metadata :authors "Malice Example")]
             [:unsupported-type evidence
              (assoc metadata :type "dataset")]
             [:missing-authors evidence
              (dissoc metadata :authors)]
             [:metadata-identifier-mismatch
              (assoc evidence :identifiers {:doi "10.5555/new-paper"
                                            :arxiv "2401.00001"})
              metadata]]]
      (let [plan (literature/build-auto-register-pdf-plan
                  @conn pdf-duplicate altered-evidence altered-metadata)]
        (is (= reason (:reason plan)))
        (is (nil? (:tx-data plan)))))
    (is (= :asset-unavailable
           (:reason (literature/build-auto-register-pdf-plan
                     @conn image-a evidence metadata))))
    (is (= :asset-linked-to-another-paper
           (:reason (literature/build-auto-register-pdf-plan
                     @conn pdf-a evidence metadata))))
    (is (= :ready
           (:status (literature/build-auto-register-pdf-plan
                     @conn pdf-duplicate
                     (assoc evidence :identifiers {:doi "10.7777/incomplete"}
                            :title "Incomplete paper")
                     (assoc metadata :doi "10.7777/incomplete"
                            :title "Incomplete paper")))))))

(deftest automatic-pdf-registration-preserves-arxiv-paper-identity
  (let [conn (create-literature-conn)
        evidence (assoc complete-auto-evidence
                        :identifiers {:arxiv "2401.98765v2"}
                        :title "A Verified Preprint"
                        :author-texts ["Alice Example"])
        metadata {:provider :arxiv
                  :arxiv "2401.98765"
                  :arxiv-record-id "2401.98765v2"
                  :title "A Verified Preprint"
                  :authors "Alice Example"
                  :type "preprint"
                  :date "2024-01-01"}
        paper-uuid (literature/source-record-paper-uuid
                    {:arxiv "2401.98765"})
        plan (literature/build-auto-register-pdf-plan
              @conn pdf-duplicate evidence metadata)]
    (is (= [:ready :create paper-uuid]
           ((juxt :status :action :paper-uuid) plan)))
    (d/transact! conn (:tx-data plan))
    (let [paper (d/entity @conn [:block/uuid paper-uuid])]
      (is (= "A Verified Preprint" (:block/title paper)))
      (is (= "2401.98765"
             (get-in paper [:logseq.property/literature-arxiv :block/title])))
      (is (= "Alice Example"
             (get-in paper [:logseq.property/literature-authors :block/title])))
      (is (= "preprint"
             (get-in paper [:logseq.property/literature-type :block/title]))))
    (is (= :already-linked
           (:status (literature/build-auto-register-pdf-plan
                     @conn pdf-duplicate evidence metadata))))
    (is (= :unsupported-metadata-provider
           (:reason (literature/build-auto-register-pdf-plan
                     @(create-literature-conn) pdf-duplicate evidence
                     (assoc metadata :provider :crossref)))))
    (is (= :metadata-version-mismatch
           (:reason (literature/build-auto-register-pdf-plan
                     @(create-literature-conn) pdf-duplicate evidence
                     (assoc metadata :arxiv-record-id "2401.98765v3")))))))

(deftest automatic-pdf-link-accepts-zotero-family-given-author-order
  (let [conn (create-literature-conn)
        paper-id (:db/id (d/entity @conn [:block/uuid paper-a]))]
    (outliner-property/set-block-property!
     conn paper-id :logseq.property/literature-authors
     "Example, Alice; Researcher, Bob")
    (is (= :ready
           (:status (literature/build-auto-link-pdf-plan
                     @conn pdf-duplicate complete-auto-evidence))))
    (is (= :blocked
           (:status (literature/build-auto-link-pdf-plan
                     @conn pdf-duplicate
                     (assoc complete-auto-evidence
                            :author-texts ["Malice Example"])))))))

(defn- assert-current-pdf-catalog-ownership!
  []
  (testing "missing catalog metadata and current asset ownership are rechecked"
    (let [conn (create-literature-conn)
          incomplete-paper-id (:db/id (d/entity @conn [:block/uuid paper-incomplete]))
          incomplete-evidence (assoc complete-auto-evidence
                                     :identifiers {:doi "10.7777/incomplete"}
                                     :title "Incomplete paper")]
      (is (= {:status :blocked :reason :missing-type}
             (literature/build-auto-link-pdf-plan
              @conn pdf-duplicate incomplete-evidence)))
      (outliner-property/set-block-property!
       conn incomplete-paper-id :logseq.property/literature-type "dataset")
      (is (= {:status :blocked :reason :unsupported-type}
             (literature/build-auto-link-pdf-plan
              @conn pdf-duplicate incomplete-evidence)))
      (outliner-property/set-block-property!
       conn incomplete-paper-id :logseq.property/literature-type "journal-article")
      (is (= {:status :blocked :reason :missing-authors}
             (literature/build-auto-link-pdf-plan
              @conn pdf-duplicate incomplete-evidence)))
      (is (= {:status :blocked :reason :asset-linked-to-another-paper}
             (select-keys
              (literature/build-auto-link-pdf-plan
               @conn pdf-a
               (assoc complete-auto-evidence
                      :identifiers {:doi "10.9999/other"}
                      :title "Paper B"
                      :author-texts ["Carol Example"]))
              [:status :reason])))
      (outliner-property/set-block-property!
       conn
       (:db/id (d/entity @conn [:block/uuid pdf-duplicate]))
       :logseq.property/deleted-at
       1)
      (is (= {:status :blocked :reason :asset-unavailable}
             (literature/build-auto-link-pdf-plan
              @conn pdf-duplicate complete-auto-evidence))))))

(deftest automatic-pdf-link-requires-complete-bibliographic-evidence
  (testing "all identifiers, title, author and type link one existing item only"
    (let [conn (create-literature-conn)
          plan (literature/build-auto-link-pdf-plan
                @conn pdf-duplicate complete-auto-evidence)]
      (is (= :ready (:status plan)))
      (is (= :link (:action plan)))
      (is (= paper-a (:paper-uuid plan)))
      (d/transact! conn (:tx-data plan))
      (is (= [paper-a]
             (mapv :block/uuid (:papers (literature/find-paper-by-asset @conn pdf-duplicate)))))
      (is (= :already-linked
             (:status (literature/build-auto-link-pdf-plan
                       @conn pdf-duplicate complete-auto-evidence))))))

  (testing "unknown, partial and conflicting identifiers never produce tx data"
    (let [conn (create-literature-conn)]
      (doseq [[reason evidence]
              [[:identifier-not-found
                (assoc complete-auto-evidence
                       :identifiers {:doi "10.5555/unknown"})]
               [:partial-identifier-match
                (assoc complete-auto-evidence
                       :identifiers {:doi "10.1234/example"
                                     :arxiv "2401.99999"})]
               [:identifier-conflict
                (assoc complete-auto-evidence
                       :identifiers {:doi "10.1234/example"
                                     :arxiv "2201.00001"})]
               [:ambiguous-identifiers
                (assoc complete-auto-evidence :identifier-ambiguous? true)]]]
        (let [datom-count (count (d/datoms @conn :eavt))
              plan (literature/build-auto-link-pdf-plan
                    @conn pdf-duplicate evidence)]
          (is (= {:status :blocked :reason reason}
                 (select-keys plan [:status :reason])))
          (is (nil? (:tx-data plan)))
          (is (= datom-count (count (d/datoms @conn :eavt))))))))

  (testing "missing or contradictory extraction evidence is rejected"
    (let [conn (create-literature-conn)]
      (doseq [[reason evidence]
              [[:incomplete-extraction
                (assoc complete-auto-evidence :extraction-status :incomplete)]
               [:unusable-extraction
                (assoc complete-auto-evidence :usable? false)]
               [:title-conflict
                (assoc complete-auto-evidence :title-conflict? true)]
               [:missing-title
                (assoc complete-auto-evidence :title "  ")]
               [:missing-author-evidence
                (assoc complete-auto-evidence :author-texts [])]
               [:title-mismatch
                (assoc complete-auto-evidence :title "Different paper")]
               [:author-mismatch
                (assoc complete-auto-evidence :author-texts ["Different Author"])]
               [:author-mismatch
                (assoc complete-auto-evidence :author-texts ["Malice Example"])]]]
        (is (= {:status :blocked :reason reason}
               (select-keys
                (literature/build-auto-link-pdf-plan
                 @conn pdf-duplicate evidence)
                [:status :reason]))))))

  (assert-current-pdf-catalog-ownership!)

  (testing "CJK layout spaces do not cause a false title or author mismatch"
    (let [conn (create-literature-conn)
          paper-id (:db/id (d/entity @conn [:block/uuid paper-a]))]
      (d/transact! conn [[:db/add paper-id :block/title "日 本 大 学"]])
      (outliner-property/set-block-property!
       conn paper-id :logseq.property/literature-authors "李 安九")
      (is (= :ready
             (:status
              (literature/build-auto-link-pdf-plan
               @conn pdf-duplicate
               (assoc complete-auto-evidence
                      :title "日本大学"
                      :author-texts ["李安九"]))))))))

(deftest reused-pdf-reports-existing-registration-status
  (let [conn (create-literature-conn)
        asset-id (:db/id (d/entity @conn [:block/uuid pdf-b]))
        paper-a-id (:db/id (d/entity @conn [:block/uuid paper-a]))
        paper-b-id (:db/id (d/entity @conn [:block/uuid paper-b]))]
    (is (= :found (:literature-status
                   (literature/find-reusable-pdf-by-checksum @conn "variant-checksum"))))
    (d/transact! conn [[:db/retract paper-a-id :logseq.property/literature-assets asset-id]])
    (let [db-before @conn
          result (literature/find-reusable-pdf-by-checksum db-before "variant-checksum")]
      (is (= :found (:status result)))
      (is (= :missing (:literature-status result)))
      (is (= [pdf-b] (mapv :block/uuid (:assets result))))
      (is (identical? db-before @conn)))
    (d/transact! conn [[:db/add paper-a-id :logseq.property/literature-assets asset-id]
                       [:db/add paper-b-id :logseq.property/literature-assets asset-id]])
    (is (= :conflict (:literature-status
                      (literature/find-reusable-pdf-by-checksum @conn "variant-checksum"))))))

(deftest pdf-lookup-and-paper-relations-preserve-native-assets
  (let [conn (create-literature-conn)]
    (testing "a unique PDF checksum returns its native Asset"
      (is (= {:status :found
              :assets [{:block/uuid pdf-b
                        :block/title "paper-b"
                        :logseq.property.asset/size 120
                        :logseq.property.asset/checksum "variant-checksum"}]}
             (literature/find-pdf-by-checksum @conn "variant-checksum")))
      (is (= {:status :found
              :literature-status :found
              :assets [{:block/uuid pdf-b
                        :block/title "paper-b"
                        :logseq.property.asset/size 120
                        :logseq.property.asset/checksum "variant-checksum"}]}
             (literature/find-reusable-pdf-by-checksum
              @conn
              "variant-checksum"))))
    (testing "duplicate PDF identities are conflicts and an image does not join them"
      (let [result (literature/find-pdf-by-checksum @conn "same-checksum")]
        (is (= :conflict (:status result)))
        (is (= #{pdf-a pdf-duplicate}
               (set (map :block/uuid (:assets result)))))))
    (testing "one paper keeps multiple byte-distinct PDF Assets and excludes a linked image"
      (is (= #{pdf-a pdf-b}
             (set (map :block/uuid (literature/paper-pdfs @conn paper-a))))))
    (testing "recycled assets stop participating without changing remaining UUIDs"
      (outliner-property/set-block-property!
       conn
       (:db/id (d/entity @conn [:block/uuid pdf-duplicate]))
       :logseq.property/deleted-at
       1)
      (is (= {:status :found
              :assets [{:block/uuid pdf-a
                        :block/title "paper-a"
                        :logseq.property.asset/size 100
                        :logseq.property.asset/checksum "same-checksum"}]}
             (literature/find-pdf-by-checksum @conn "same-checksum")))
      (is (= #{pdf-a pdf-b}
             (set (map :block/uuid (literature/paper-pdfs @conn paper-a))))))))

(deftest checksum-lookup-retains-pdf-assets-without-size
  (let [conn (create-literature-conn)]
    (d/transact! conn [[:db/retract [:block/uuid pdf-duplicate]
                        :logseq.property.asset/size 100]])
    (let [before @conn
          {:keys [status assets]} (literature/find-pdf-by-checksum @conn "same-checksum")]
      (is (= :conflict status))
      (is (= #{pdf-a pdf-duplicate} (set (map :block/uuid assets))))
      (is (= 100 (:logseq.property.asset/size
                  (first (filter #(= pdf-a (:block/uuid %)) assets)))))
      (is (nil? (:logseq.property.asset/size
                 (first (filter #(= pdf-duplicate (:block/uuid %)) assets)))))
      (is (= before @conn)))))

(deftest missing-size-is-not-exact-duplicate-demotion-evidence
  (doseq [missing [[pdf-a] [pdf-duplicate] [pdf-a pdf-duplicate]]]
    (let [conn (create-literature-conn)]
      (d/transact! conn (mapv #(vector :db/retract [:block/uuid %]
                                      :logseq.property.asset/size 100) missing))
      (let [before @conn
            plan (literature/build-duplicate-pdf-demotion-plan
                  @conn pdf-a pdf-duplicate)]
        (is (= :blocked (:status plan)) (pr-str missing))
        (is (= :not-exact-duplicate (:reason plan)) (pr-str missing))
        (is (nil? (:tx-data plan)))
        (is (= before @conn))))))

(deftest exact-unused-duplicate-pdf-can-be-demoted-to-a-native-link
  (let [conn (create-literature-conn)
        duplicate-ref [:block/uuid pdf-duplicate]
        duplicate-before (d/entity @conn duplicate-ref)
        position-before {:parent-id (:db/id (:block/parent duplicate-before))
                         :page-id (:db/id (:block/page duplicate-before))
                         :order (:block/order duplicate-before)}]
    (d/transact! conn [[:db/add duplicate-ref :logseq.property.asset/external-url
                        "file:///synthetic/paper-a-copy.pdf"]
                       [:db/add duplicate-ref :logseq.property.asset/align :center]
                       [:db/add duplicate-ref :logseq.property.asset/last-visit-page 2]])
    (let [{:keys [status keep-uuid duplicate-uuid tx-data] :as plan}
          (literature/build-duplicate-pdf-demotion-plan
           @conn pdf-a pdf-duplicate)]
      (is (= :ready status) (pr-str plan))
      (is (= pdf-a keep-uuid))
      (is (= pdf-duplicate duplicate-uuid))
      (is (seq tx-data))
      (d/transact! conn tx-data))
    (let [duplicate-after (d/entity @conn duplicate-ref)
          asset-attributes (->> (d/datoms @conn :eavt (:db/id duplicate-after))
                                (map :a)
                                (filter #(= "logseq.property.asset" (namespace %))))
          tag-idents (into #{} (keep :db/ident) (:block/tags duplicate-after))]
      (is (= position-before
             {:parent-id (:db/id (:block/parent duplicate-after))
              :page-id (:db/id (:block/page duplicate-after))
              :order (:block/order duplicate-after)}))
      (is (= "paper-a-copy" (:block/title duplicate-after)))
      (is (= pdf-a (:block/uuid (:block/link duplicate-after))))
      (is (empty? asset-attributes))
      (is (not (contains? tag-idents :logseq.class/Asset)))
      (is (= {:status :found
              :assets [{:block/uuid pdf-a
                        :block/title "paper-a"
                        :logseq.property.asset/size 100
                        :logseq.property.asset/checksum "same-checksum"}]}
             (literature/find-pdf-by-checksum @conn "same-checksum")))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest stale-pdf-viewer-inserts-new-annotations-on-the-kept-pdf
  (let [conn (create-literature-conn)
        annotation (random-uuid)
        note (random-uuid)
        old (d/entity @conn [:block/uuid pdf-duplicate])
        value {:id annotation :page 1 :content {:text "New offline selection"}}
        plan (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-duplicate)]
    (d/transact! conn (:tx-data plan))
    (outliner-core/insert-blocks!
     conn [{:block/uuid annotation :block/title "New offline selection"
            :logseq.property/ls-type :annotation :logseq.property/asset (:db/id old)
            :logseq.property.pdf/hl-page 1 :logseq.property.pdf/hl-value value}]
     (d/entity @conn [:block/uuid pdf-duplicate]) {:sibling? false :keep-uuid? true})
    (outliner-core/insert-blocks!
     conn [{:block/uuid note :block/title "Ordinary linked-block child"}]
     (d/entity @conn [:block/uuid pdf-duplicate]) {:sibling? false :keep-uuid? true})
    (let [saved (d/entity @conn [:block/uuid annotation])]
      (is (= pdf-a (:block/uuid (:block/parent saved))))
      (is (= pdf-a (:block/uuid (:logseq.property/asset saved))))
      (is (= value (:logseq.property.pdf/hl-value saved)))
      (is (= #{annotation}
             (set (d/q '[:find [?u ...] :in $ ?asset :where
                         [?a :logseq.property/asset ?asset] [?a :block/uuid ?u]]
                       @conn (:db/id (d/entity @conn [:block/uuid pdf-a]))))))
      (is (= pdf-duplicate (:block/uuid (:block/parent (d/entity @conn [:block/uuid note])))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest compared-pdf-merge-retains-identity-and-replay-evidence
  (let [conn (create-literature-conn)
        annotation (random-uuid)
        note (random-uuid)
        old-ref (random-uuid)
        parent (d/entity @conn [:block/uuid pdf-b])
        value {:id annotation :page 1 :position {:rects [{:x1 1 :y1 2 :x2 3 :y2 4}]}}
        comparison {:rule "pdf-pages-v1" :paper-uuid paper-a
                    :assets {pdf-a {:checksum "same-checksum" :size 100}
                             pdf-b {:checksum "variant-checksum" :size 120}}}]
    (sqlite-build/create-blocks
     conn {:pages-and-blocks
           [{:page {:block/title "Compared PDF reference"}
             :blocks [{:block/uuid old-ref :block/title (str "[[" pdf-b "]] [[" annotation "]]")}]}]})
    (d/transact! conn
                 [{:block/uuid annotation :block/title "Compared annotation"
                   :block/created-at 1 :block/updated-at 1
                   :block/parent (:db/id parent) :block/page (:db/id (:block/page parent))
                   :block/order "a0" :logseq.property/ls-type :annotation
                   :logseq.property/asset (:db/id parent) :logseq.property.pdf/hl-page 1
                   :logseq.property.pdf/hl-value value}
                  {:block/uuid note :block/title "Compared PDF note"
                   :block/created-at 1 :block/updated-at 1 :block/parent [:block/uuid annotation]
                   :block/page (:db/id (:block/page parent)) :block/order "a0"}])
    (is (= :not-exact-duplicate
           (:reason (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-b))))
    (doseq [changed [(d/db-with @conn [[:db/add [:block/uuid pdf-b] :logseq.property.asset/checksum "replaced"]])
                     (d/db-with @conn [[:db/retract [:block/uuid paper-a]
                                       :logseq.property/literature-assets [:block/uuid pdf-b]]])]]
      (is (= :comparison-changed
             (:reason (literature/build-duplicate-pdf-demotion-plan
                        changed pdf-a pdf-b {:comparison comparison})))))
    (let [reports (atom [])
          reference-before (d/pull @conn '[*] [:block/uuid old-ref])
          op [:literature-merge-duplicate-pdf [pdf-a pdf-b comparison]]]
      (d/listen! conn ::compared-merge #(swap! reports conj %))
      (is (= :resolved (:status (outliner-op/apply-ops! conn [op] {:persist-op? true}))))
      (let [report (last @reports)
            history (op-construct/derive-history-outliner-ops
                      (:db-before report) (:db-after report) (:tx-data report) (:tx-meta report))]
        (is (= [op] (:forward-outliner-ops history)) "Comparison identity survives native history serialization"))
      (is (= pdf-a (:block/uuid (:logseq.property/asset (d/entity @conn [:block/uuid annotation])))))
      (is (= value (:logseq.property.pdf/hl-value (d/entity @conn [:block/uuid annotation]))))
      (is (= annotation (:block/uuid (:block/parent (d/entity @conn [:block/uuid note])))))
      (is (= reference-before (d/pull @conn '[*] [:block/uuid old-ref])))
      (is (= pdf-a (:block/uuid (:block/link (d/entity @conn [:block/uuid pdf-b])))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest duplicate-pdf-merges-children-references-and-native-aliases
  (let [conn (create-literature-conn)
        duplicate-id (:db/id (d/entity @conn [:block/uuid pdf-duplicate]))
        keep-id (:db/id (d/entity @conn [:block/uuid pdf-a]))
        child-uuid #uuid "72000000-0000-4000-8000-000000000005"
        note-uuid #uuid "72000000-0000-4000-8000-000000000006"
        ref-uuid #uuid "72000000-0000-4000-8000-000000000007"
        value {:id child-uuid :page 1 :content {:text "Keep this highlight"}
               :position {:rects [{:x1 1 :y1 2 :x2 3 :y2 4}]}}
        parent (d/entity @conn [:block/uuid pdf-duplicate])]
    (sqlite-build/create-blocks
     conn {:pages-and-blocks
           [{:page {:block/title "Synthetic annotation references"}
             :blocks [{:block/uuid ref-uuid
                       :block/title (str "[[" pdf-duplicate "]] [[" child-uuid "]]")}]}]})
    (d/transact! conn
                 [{:block/uuid child-uuid :block/title "Keep this highlight"
                   :block/created-at 1 :block/updated-at 1
                   :block/parent duplicate-id :block/page (:db/id (:block/page parent))
                   :block/order "a0" :logseq.property/ls-type :annotation
                   :logseq.property/asset duplicate-id :logseq.property.pdf/hl-page 1
                   :logseq.property.pdf/hl-value value}
                  {:block/uuid note-uuid :block/title "A nested note"
                   :block/created-at 1 :block/updated-at 1
                   :block/parent [:block/uuid child-uuid]
                   :block/page (:db/id (:block/page parent)) :block/order "a0"}])
    (let [before @conn
          ref-before (d/pull @conn '[*] [:block/uuid ref-uuid])
          plan (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-duplicate)]
      (is (= :ready (:status plan)))
      (is (= before @conn) "Preview never changes the source database")
      (d/transact! conn (:tx-data plan))
      (let [child (d/entity @conn [:block/uuid child-uuid])]
        (is (= keep-id (:db/id (:block/parent child))))
        (is (= keep-id (:db/id (:logseq.property/asset child))))
        (is (= value (:logseq.property.pdf/hl-value child)))
        (is (= "A nested note" (:block/title (d/entity @conn [:block/uuid note-uuid]))))
        (is (= child-uuid (:block/uuid (:block/parent (d/entity @conn [:block/uuid note-uuid]))))))
      (is (= ref-before (d/pull @conn '[*] [:block/uuid ref-uuid])))
      (is (contains? (db-reference/literature-reference-block-ids
                      @conn (:db/id (d/entity @conn [:block/uuid paper-a])))
                     (:db/id (d/entity @conn [:block/uuid ref-uuid]))))
      (is (= pdf-a (:block/uuid (:block/link (d/entity @conn duplicate-id)))))
      (is (= [paper-a] (map :block/uuid (literature/search-papers-for-candidate @conn pdf-duplicate))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest duplicate-pdf-transfers-native-alias-and-filename-values
  (let [conn (create-literature-conn)
        alias-uuid #uuid "72000000-0000-4000-8000-000000000081"
        duplicate-ref [:block/uuid pdf-duplicate]
        keep-ref [:block/uuid pdf-a]]
    (sqlite-build/create-blocks
     conn {:pages-and-blocks [{:page {:block/uuid alias-uuid :block/title "Original PDF filename alias"}
                               :blocks []}]})
    (d/transact! conn [[:db/add duplicate-ref :block/alias [:block/uuid alias-uuid]]])
    (d/transact! conn (literature/build-add-pdf-filenames-tx
                       @conn {pdf-duplicate #{"original-file-name.pdf"}}))
    (let [filename (first (:logseq.property/literature-aliases (d/entity @conn duplicate-ref)))
          filename-uuid (:block/uuid filename)
          plan (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-duplicate)]
      (is (= :ready (:status plan)))
      (d/transact! conn (:tx-data plan))
      (is (= #{alias-uuid} (set (map :block/uuid (:block/alias (d/entity @conn keep-ref))))))
      (is (empty? (:block/alias (d/entity @conn duplicate-ref))))
      (let [filename (d/entity @conn [:block/uuid filename-uuid])]
        (is (= "original-file-name.pdf" (:block/title filename)))
        (is (= pdf-a (:block/uuid (:block/parent filename))))
        (is (= filename-uuid (:block/uuid (first (:logseq.property/literature-aliases
                                                 (d/entity @conn keep-ref)))))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest duplicate-pdf-bibliography-only-ownership-moves-atomically
  (doseq [keep-already-linked? [false true]]
    (let [conn (create-literature-conn)
          owner (d/entity @conn [:block/uuid paper-a])
          keep-id (:db/id (d/entity @conn [:block/uuid pdf-a]))
          duplicate-id (:db/id (d/entity @conn [:block/uuid pdf-duplicate]))]
      (when-not keep-already-linked?
        (d/transact! conn [[:db/retract (:db/id owner)
                            :logseq.property/literature-assets keep-id]]))
      (d/transact! conn [[:db/add (:db/id owner)
                          :logseq.property/literature-assets duplicate-id]
                         [:db/add (:db/id owner) :block/refs duplicate-id]])
      (let [before @conn
            plan (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-duplicate)]
        (is (= :ready (:status plan)))
        (is (= before @conn) "Preview is read-only")
        (d/transact! conn (:tx-data plan))
        (is (= #{pdf-a pdf-b image-a}
               (set (map :block/uuid
                         (:logseq.property/literature-assets
                          (d/entity @conn [:block/uuid paper-a]))))))
        (is (= pdf-a (:block/uuid (:block/link (d/entity @conn duplicate-id)))))
        (is (not (contains? (set (map :db/id (:block/refs (d/entity @conn (:db/id owner)))))
                            duplicate-id)))
        (is (empty? (:errors (db-validate/validate-db @conn))))))))

(deftest duplicate-pdf-does-not-create-a-bibliography-owner-conflict
  (let [conn (create-literature-conn)
        paper-b-id (:db/id (d/entity @conn [:block/uuid paper-b]))
        duplicate-id (:db/id (d/entity @conn [:block/uuid pdf-duplicate]))]
    (d/transact! conn [[:db/add paper-b-id :logseq.property/literature-assets duplicate-id]])
    (let [before @conn
          plan (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-duplicate)]
      (is (= {:status :blocked :reason :conflicting-owners} plan))
      (is (= before @conn)))))

(deftest duplicate-pdf-preserves-body-refs-on-the-bibliography-owner
  (let [conn (create-literature-conn)
        owner-id (:db/id (d/entity @conn [:block/uuid paper-a]))
        duplicate-id (:db/id (d/entity @conn [:block/uuid pdf-duplicate]))
        title (str "Paper A [[" pdf-duplicate "]]")]
    (d/transact! conn [[:db/add owner-id :logseq.property/literature-assets duplicate-id]
                       [:db/add owner-id :block/refs duplicate-id]
                       [:db/add owner-id :block/title title]])
    (let [plan (literature/build-duplicate-pdf-demotion-plan @conn pdf-a pdf-duplicate)]
      (is (= :ready (:status plan)))
      (d/transact! conn (:tx-data plan))
      (is (= title (:block/title (d/pull @conn '[:block/title] owner-id))))
      (is (some #{duplicate-id} (map :db/id (:block/refs (d/entity @conn owner-id)))))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest duplicate-pdf-demotion-rechecks-current-asset-identity
  (testing "incomplete graph definitions block the transaction planner"
    (is (= {:status :blocked :reason :disabled}
           (literature/build-duplicate-pdf-demotion-plan
            (d/db-with @(db-test/create-conn)
                       [[:db/retractEntity :logseq.property/literature-item?]])
            pdf-a pdf-duplicate))))
  (testing "changed bytes cannot be resolved using a stale conflict choice"
    (let [conn (create-literature-conn)]
      (d/transact! conn [[:db/add [:block/uuid pdf-duplicate]
                          :logseq.property.asset/size
                          101]])
      (is (= {:status :blocked :reason :not-exact-duplicate}
             (literature/build-duplicate-pdf-demotion-plan
              @conn pdf-a pdf-duplicate)))))
  (testing "the selected Asset cannot be demoted into a link to itself"
    (let [conn (create-literature-conn)]
      (is (= {:status :blocked :reason :same-asset}
             (literature/build-duplicate-pdf-demotion-plan
              @conn pdf-a pdf-a))))))


(deftest unused-literature-condition-option-does-not-block-native-save
  (let [conn (create-literature-conn)
        value-node (:logseq.property/literature-authors
                    (d/entity @conn [:block/uuid paper-b]))
        value-uuid (:block/uuid value-node)
        options {:literature/if-pending
                 {:paper-uuid paper-b
                  :pending-marker-uuid
                  #uuid "74000000-0000-4000-8000-000000000099"}}
        plan (outliner-core/save-block
              @conn {:block/uuid value-uuid :block/title "Human author"} options)]
    (is (seq (:tx-data plan)))
    (d/transact! conn (:tx-data plan))
    (is (= "Human author"
           (:block/title (d/entity @conn [:block/uuid value-uuid]))))))

(deftest verified-pdf-links-an-edited-incomplete-paper-without-overwriting-it
  (let [conn (create-literature-conn)
        evidence (assoc complete-auto-evidence :identifiers {:doi "10.7777/incomplete"}
                        :title "Original publisher title" :author-texts ["Alice Example"])
        metadata {:provider :crossref :doi "10.7777/incomplete"
                  :title "Original publisher title" :authors "Example, Alice"
                  :type "journal-article"}
        original (d/pull @conn '[*] [:block/uuid paper-incomplete])
        plan (literature/build-auto-register-pdf-plan @conn pdf-duplicate evidence metadata)]
    (is (= [:ready :link paper-incomplete] ((juxt :status :action :paper-uuid) plan)))
    (d/transact! conn (:tx-data plan))
    (is (= (dissoc original :logseq.property/literature-assets)
           (dissoc (d/pull @conn '[*] [:block/uuid paper-incomplete]) :logseq.property/literature-assets)))
    (is (= :already-linked (:status (literature/build-auto-register-pdf-plan @conn pdf-duplicate evidence metadata))))))

(deftest verified-old-arxiv-with-doi-creates-one-native-paper
  (let [conn (create-literature-conn)
        evidence (assoc complete-auto-evidence
                        :identifiers {:arxiv "quant-ph/9906129v1" :doi "10.5555/dual-old"}
                        :title "A Verified Preprint" :author-texts ["Alice Example"])
        metadata {:provider :arxiv :arxiv "quant-ph/9906129" :arxiv-record-id "quant-ph/9906129v1"
                  :doi "10.5555/dual-old" :title "A Verified Preprint"
                  :authors "Alice Example" :type "preprint"}
        plan (literature/build-auto-register-pdf-plan @conn pdf-duplicate evidence metadata)]
    (is (= [:ready :create] ((juxt :status :action) plan)))
    (d/transact! conn (:tx-data plan))
    (is (= :found (:status (literature/find-paper-by-identifiers @conn {:doi "10.5555/dual-old" :arxiv "quant-ph/9906129v3"}))))
    (is (= :already-linked (:status (literature/build-auto-register-pdf-plan @conn pdf-duplicate evidence metadata))))))


(def manual-doi-record
  {:provider :crossref :doi "10.5555/manual-correction"
   :title "Corrected synthetic paper" :authors "Ada Example; Bo Researcher"
   :date "2026-01-02" :publication "Synthetic Journal"
   :type "journal-article" :url "https://doi.org/10.5555/manual-correction"})

(defn- accepted-doi-changes [plan]
  (mapv #(select-keys % [:field :kind :current :source]) (:changes plan)))

(deftest manual-doi-registration-preserves-metadata-in-native-op-replay
  (let [conn (create-literature-conn)
        op [:literature-register-identified-pdf
            [pdf-duplicate (:title manual-doi-record)
             (select-keys manual-doi-record [:doi]) manual-doi-record]]
        canonical (#'op-construct/canonicalize-semantic-outliner-op @conn [] op)
        result (outliner-op/apply-ops! conn [canonical] {})
        paper-uuid (literature/source-record-paper-uuid manual-doi-record)
        paper (d/entity @conn [:block/uuid paper-uuid])
        replay-conn (create-literature-conn)]
    (is (= :created (:status result)))
    (is (= (:authors manual-doi-record)
           (get-in canonical [1 3 :authors])))
    (is (nil? (get-in canonical [1 3 :provider])))
    (is (= (:date manual-doi-record) (:block/title (:logseq.property/literature-date paper))))
    (outliner-op/apply-ops! replay-conn [canonical] {})
    (is (= (:authors manual-doi-record)
           (:block/title (:logseq.property/literature-authors
                          (d/entity @replay-conn [:block/uuid paper-uuid])))))
    (is (= :already-linked (:status (outliner-op/apply-ops! replay-conn [canonical] {}))))
    (is (= 1 (count (:papers (literature/find-paper-by-identifiers @replay-conn {:doi (:doi manual-doi-record)})))))
    (is (empty? (:errors (db-validate/validate-db @replay-conn))))))

(deftest manual-doi-correction-keeps-native-identities-and-old-references
  (let [conn (create-literature-conn)
        annotation #uuid "74000000-0000-4000-8000-000000000991"
        note #uuid "74000000-0000-4000-8000-000000000992"]
    (outliner-op/apply-ops!
     conn [[:save-block [{:block/uuid pdf-a :block/title "Paper A"} {}]]
           [:insert-blocks [[{:block/uuid annotation :block/title "Existing highlight"}]
                            pdf-a {:sibling? false :bottom? true :keep-uuid? true}]]
           [:insert-blocks [[{:block/uuid note
                              :block/title (str "((" paper-a ")) ((" pdf-a ")) ((" annotation "))")}]
                            destination-page {:sibling? false :bottom? true :keep-uuid? true}]]] {})
    (let [annotation-before (d/pull @conn [:block/uuid :block/title :block/parent] [:block/uuid annotation])
          note-before (d/pull @conn [:block/uuid :block/title :block/refs] [:block/uuid note])
          old-paper-id (:db/id (d/entity @conn [:block/uuid paper-a]))
          old-asset-id (:db/id (d/entity @conn [:block/uuid pdf-a]))
          old-arxiv (:block/uuid (:logseq.property/literature-arxiv (d/entity @conn [:block/uuid paper-a])))
          before @conn
          plan (literature/build-doi-metadata-plan @conn paper-a manual-doi-record)
          applied (literature/build-apply-doi-metadata-plan @conn paper-a manual-doi-record (accepted-doi-changes plan))]
      (is (identical? before @conn))
      (is (= :manual (:status plan)))
      (is (= :identifier-metadata-conflict
             (:reason (literature/build-source-record-metadata-plan @conn paper-a manual-doi-record))))
      (is (= :ready (:status applied)))
      (outliner-op/apply-ops! conn (:ops applied) {})
      (let [paper (d/entity @conn [:block/uuid paper-a])]
        (is (= old-paper-id (:db/id paper)))
        (is (= old-asset-id (:db/id (d/entity @conn [:block/uuid pdf-a]))))
        (is (= (:title manual-doi-record) (:block/title paper)))
        (is (= (:doi manual-doi-record) (:block/title (:logseq.property/literature-doi paper))))
        (is (= old-arxiv (:block/uuid (:logseq.property/literature-arxiv paper))))
        (is (= "2101.12345" (:block/title (d/entity @conn [:block/uuid old-arxiv])))))
      (is (= (:title manual-doi-record) (:block/title (d/entity @conn [:block/uuid pdf-a]))))
      (is (= "paper-b" (:block/title (d/entity @conn [:block/uuid pdf-b]))))
      (is (= annotation-before (d/pull @conn [:block/uuid :block/title :block/parent] [:block/uuid annotation])))
      (is (= note-before (d/pull @conn [:block/uuid :block/title :block/refs] [:block/uuid note])))
      (is (empty? (:errors (db-validate/validate-db @conn)))))))

(deftest manual-doi-duplicate-reassociates-only-the-selected-pdf
  (let [conn (create-literature-conn)
        record (assoc manual-doi-record :doi "10.9999/other")
        target-before (d/pull @conn [:block/uuid :block/title {:logseq.property/literature-authors [:block/title]}]
                              [:block/uuid paper-b])
        plan (literature/build-doi-metadata-plan @conn paper-a record)
        selection [{:field :assets :kind :different-value :current paper-a :source paper-b}]
        applied (literature/build-apply-doi-metadata-plan @conn paper-a record selection pdf-a)]
    (is (= :existing (:status plan)))
    (is (= paper-b (get-in plan [:paper :block/uuid])))
    (is (= #{pdf-a pdf-b} (set (map :block/uuid (:assets plan)))))
    (is (= :reassign (:action applied)))
    (outliner-op/apply-ops! conn (:ops applied) {})
    (is (= paper-b (:block/uuid (first (:papers (literature/find-paper-by-asset @conn pdf-a))))))
    (is (= paper-a (:block/uuid (first (:papers (literature/find-paper-by-asset @conn pdf-b))))))
    (is (= target-before (d/pull @conn [:block/uuid :block/title {:logseq.property/literature-authors [:block/title]}]
                                [:block/uuid paper-b])))
    (is (= "Paper A" (:block/title (d/entity @conn [:block/uuid paper-a]))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest manual-doi-correction-refuses-a-stale-preview
  (let [conn (create-literature-conn)
        plan (literature/build-doi-metadata-plan @conn paper-a manual-doi-record)]
    (outliner-op/apply-ops! conn [[:save-block [{:block/uuid paper-a :block/title "Concurrent edit"} {}]]] {})
    (let [before @conn
          result (literature/build-apply-doi-metadata-plan @conn paper-a manual-doi-record (accepted-doi-changes plan))]
      (is (= :metadata-plan-stale (:reason result)))
      (is (identical? before @conn))
      (is (= "Concurrent edit" (:block/title (d/entity @conn [:block/uuid paper-a])))))))

(deftest doi-only-entry-uses-native-insertion-and-metadata-without-an-asset
  (let [conn (create-literature-conn)
        before @conn
        plan (literature/build-add-doi-paper-plan @conn destination-page manual-doi-record)
        paper-uuid (literature/source-record-paper-uuid manual-doi-record)]
    (is (= :ready (:status plan)))
    (is (= before @conn) "Planning must be read-only")
    (is (= paper-uuid (:paper-uuid plan)))
    (outliner-op/apply-ops! conn (:ops plan) {})
    (let [paper (d/entity @conn [:block/uuid paper-uuid])]
      (is (= destination-page (:block/uuid (:block/parent paper))))
      (is (= (:title manual-doi-record) (:block/title paper)))
      (is (= (:doi manual-doi-record) (:block/title (:logseq.property/literature-doi paper))))
      (is (= (:authors manual-doi-record) (:block/title (:logseq.property/literature-authors paper))))
      (is (= (:date manual-doi-record) (:block/title (:logseq.property/literature-date paper))))
      (is (empty? (:logseq.property/literature-assets paper)))
      (is (empty? (:logseq.property/literature-source-key paper))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest doi-only-add-reuses-current-entry-without-overwriting-it
  (let [conn (create-literature-conn)
        plan (literature/build-add-doi-paper-plan @conn destination-page manual-doi-record)]
    (outliner-op/apply-ops! conn (:ops plan) {})
    (let [before @conn
          duplicate (literature/build-add-doi-paper-plan
                     @conn destination-page (assoc manual-doi-record :title "Different provider title"))]
      (is (= :existing (:status duplicate)))
      (is (= (:paper-uuid plan) (:paper-uuid duplicate)))
      (is (nil? (:ops duplicate)))
      (is (= before @conn))
      (is (= 1 (count (:papers (literature/find-paper-by-identifiers @conn {:doi (:doi manual-doi-record)}))))))))

(deftest doi-only-add-checks-identifier-and-destination-before-inserting
  (let [conn (create-literature-conn)
        before @conn]
    (is (thrown-with-msg? js/Error #"Invalid DOI"
                         (literature/build-add-doi-paper-plan @conn destination-page
                                                              (assoc manual-doi-record :doi "invalid"))))
    (is (= :destination-unavailable
           (:reason (literature/build-add-doi-paper-plan @conn pdf-a manual-doi-record))))
    (is (= before @conn))))

(deftest doi-only-add-accepts-a-journal-offered-by-the-native-destination-picker
  (let [conn (create-literature-conn)]
    (d/transact! conn [[:db/add [:block/uuid destination-page] :block/tags :logseq.class/Journal]])
    (let [plan (literature/build-add-doi-paper-plan @conn destination-page manual-doi-record)]
      (is (= :ready (:status plan)))
      (outliner-op/apply-ops! conn (:ops plan) {})
      (is (= destination-page
             (:block/uuid (:block/parent (d/entity @conn [:block/uuid (:paper-uuid plan)]))))))))

(deftest manual-cli-import-supports-arxiv-and-identifier-free-records
  (let [conn (create-literature-conn)
        records [{:title "CLI arXiv paper" :arxiv "arXiv:2601.00002v2" :authors "A. Example"}
                 {:title "CLI manual paper" :uuid paper-c :date "2026"}
                 {:title "CLI title only"}]]
    (doseq [record records]
      (let [before @conn
            plan (literature/build-import-paper-plan @conn destination-page record)]
        (is (= :ready (:status plan)))
        (is (identical? before @conn))
        (outliner-op/apply-ops! conn (:ops plan) {})
        (let [paper (d/entity @conn [:block/uuid (:paper-uuid plan)])]
          (is (= (:title record) (:block/title paper)))
          (is (= destination-page (:block/uuid (:block/parent paper))))
          (is (empty? (:logseq.property/literature-source-key paper))))))
    (let [again (literature/build-import-paper-plan @conn destination-page
                                                  {:title "Must not replace" :arxiv "2601.00002"})]
      (is (= :existing (:status again)))
      (is (= "CLI arXiv paper" (:block/title (d/entity @conn [:block/uuid (:paper-uuid again)])))))
    (is (= :existing (:status (literature/build-import-paper-plan @conn destination-page
                                                                {:title "No replacement" :uuid paper-c}))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest manual-cli-patch-preserves-identities-and-supports-explicit-clearing
  (let [conn (create-literature-conn)
        original-assets (set (map :block/uuid (:logseq.property/literature-assets
                                               (d/entity @conn [:block/uuid paper-a]))))
        plan (literature/build-update-paper-plan @conn paper-a
                                                 {:title "CLI changed title" :authors nil :citekey "Example2026"})]
    (is (= :ready (:status plan)))
    (outliner-op/apply-ops! conn (:ops plan) {})
    (let [paper (d/entity @conn [:block/uuid paper-a])]
      (is (= "CLI changed title" (:block/title paper)))
      (is (nil? (:logseq.property/literature-authors paper)))
      (is (= "Example2026" (:block/title (:logseq.property/literature-citekey paper))))
      (is (= original-assets (set (map :block/uuid (:logseq.property/literature-assets paper)))))
      (is (= "10.1234/example" (:block/title (:logseq.property/literature-doi paper)))))
    (let [manual (literature/build-update-paper-plan @conn paper-no-id {:authors "Manual Example"})]
      (is (= :ready (:status manual)))
      (outliner-op/apply-ops! conn (:ops manual) {})
      (is (= :unchanged (:status (literature/build-update-paper-plan @conn paper-no-id {:authors "Manual Example"})))))
    (is (empty? (:errors (db-validate/validate-db @conn))))))

(deftest manual-cli-conflicts-and-invalid-fields-do-not-mutate-the-graph
  (let [conn (create-literature-conn)
        before @conn]
    (is (= :identifier-conflict (:reason (literature/build-update-paper-plan @conn paper-a {:doi "10.9999/other"}))))
    (is (= :missing-title (:reason (literature/build-update-paper-plan @conn paper-a {:title nil}))))
    (is (= :unsupported-metadata-field (:reason (literature/build-update-paper-plan @conn paper-a {:source-key "invented"}))))
    (is (= :destination-unavailable (:reason (literature/build-import-paper-plan @conn pdf-a {:title "No invalid parent"}))))
    (is (identical? before @conn))))
