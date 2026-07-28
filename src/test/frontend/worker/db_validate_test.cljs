(ns frontend.worker.db-validate-test
  (:require [cljs.test :refer [deftest is]]
            [datascript.core :as d]
            [frontend.worker.db.validate :as worker-db-validate]
            [frontend.worker.shared-service :as shared-service]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.frontend.validate :as db-validate]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]))

(defn- create-db-graph-conn
  []
  (let [conn (d/create-conn db-schema/schema)]
    (d/transact! conn (sqlite-create-graph/build-db-initial-data ""))
    conn))

(deftest validate-db-returns-count-fields-without-counts-wrapper
  (let [conn (create-db-graph-conn)]
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _args] nil)]
      (let [result (worker-db-validate/validate-db conn :fix false)
            validation-result (db-validate/validate-db @conn)
            expected-counts (assoc (db-validate/graph-counts @conn (:entities validation-result))
                                   :datoms (:datom-count validation-result))]
        (is (= expected-counts (select-keys result (keys expected-counts))))
        (is (not (contains? result :counts)))
        (is (not (contains? result :datom-count)))
        (is (every? number? (map result (keys expected-counts))))))))

(deftest validate-db-repairs-block-missing-uuid
  (let [conn (create-db-graph-conn)
        page-uuid (random-uuid)
        page-tx (:tempids
                 (d/transact! conn [{:db/id "page"
                                      :block/uuid page-uuid
                                      :block/created-at 1
                                      :block/updated-at 1
                                      :block/name "test page"
                                      :block/title "Test Page"
                                      :block/tags :logseq.class/Page}]))
        page-id (get page-tx "page")
        block-id (get (:tempids
                       (d/transact! conn [{:db/id "block"
                                           :block/created-at 1
                                           :block/updated-at 2
                                           :block/page page-id
                                           :block/parent page-id
                                           :block/order "a0"
                                           :block/title ""}]))
                      "block")]
    (is (seq (:errors (db-validate/validate-db @conn))))
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _args] nil)]
      (worker-db-validate/validate-db conn)
      (let [repaired-block (d/entity @conn block-id)]
        (is (uuid? (:block/uuid repaired-block)))
        (is (= page-id (:db/id (:block/page repaired-block))))
        (is (= page-id (:db/id (:block/parent repaired-block))))
        (is (empty? (:errors (worker-db-validate/validate-db conn))))))))

(deftest validate-db-repairs-invalid-pages-properties-and-classes
  (let [conn (create-db-graph-conn)
        journal-id (get (:tempids
                         (d/transact! conn [{:db/id "journal"
                                             :block/uuid (random-uuid)
                                             :block/created-at 1
                                             :block/journal-day 20260504
                                             :block/name "2026-05-04"
                                             :block/title "2026-05-04"
                                             :block/tags :logseq.class/Journal}]))
                        "journal")
        class-id (get (:tempids
                       (d/transact! conn [{:db/id "class"
                                           :block/uuid (random-uuid)
                                           :block/created-at 1
                                           :block/updated-at 2
                                           :block/name "imported"
                                           :block/title "imported"
                                           :block/tags :logseq.class/Tag
                                           :db/ident :user.class/imported
                                           :logseq.property.class/extends :logseq.class/Root
                                           :kv/value 1}]))
                      "class")]
    (d/transact! conn [[:db/add :logseq.property.class/extends :block/tags :logseq.class/Tag]
                       [:db/add :logseq.property.class/extends :logseq.property.class/extends :logseq.class/Root]])
    (is (= 3 (count (:errors (db-validate/validate-db @conn)))))
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _args] nil)]
      (let [result (worker-db-validate/validate-db conn)
            journal (d/entity @conn journal-id)
            property (d/entity @conn :logseq.property.class/extends)
            class (d/entity @conn class-id)]
        (is (empty? (:errors result)))
        (is (= 1 (:block/updated-at journal)))
        (is (= [:logseq.class/Property] (mapv :db/ident (:block/tags property))))
        (is (nil? (:logseq.property.class/extends property)))
        (is (nil? (:kv/value class)))
        (is (empty? (:errors (worker-db-validate/validate-db conn))))))))

(deftest validate-db-preserves-selfhost-4-pdf-annotation-missing-title
  (let [conn (create-db-graph-conn)
        page-id (get (:tempids
                      (d/transact! conn
                                   [{:db/id "pdf-page"
                                     :block/uuid #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                                     :block/created-at 1710000000000
                                     :block/updated-at 1710000001000
                                     :block/name "paper"
                                     :block/title "Paper"
                                     :block/tags :logseq.class/Page}]))
                     "pdf-page")
        annotation-uuid #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        ordinary-uuid #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
        hl-value {:id annotation-uuid
                  :page 7
                  :content {:text nil}
                  :position {:page 7}}
        tempids (:tempids
                 (d/transact!
                  conn
                  [{:db/id "legacy-pdf-annotation"
                    :block/uuid annotation-uuid
                    :block/created-at 1710000002000
                    :block/updated-at 1710000003000
                    :block/page page-id
                    :block/parent page-id
                    :block/order "a0"
                    :block/tags :logseq.class/Pdf-annotation
                    :logseq.property/ls-type :annotation
                    :logseq.property.pdf/hl-color :logseq.property/color.yellow
                    :logseq.property/asset page-id
                    :logseq.property.pdf/hl-page 7
                    :logseq.property.pdf/hl-value hl-value}
                   {:db/id "ordinary-missing-title"
                    :block/uuid ordinary-uuid
                    :block/created-at 1710000004000
                    :block/updated-at 1710000005000
                    :block/page page-id
                    :block/parent page-id
                    :block/order "a1"}]))
        annotation-id (get tempids "legacy-pdf-annotation")
        ordinary-id (get tempids "ordinary-missing-title")
        initial-invalid-ids
        (->> (:errors (db-validate/validate-db @conn))
             (map (comp :db/id :entity))
             set)]
    (is (= #{annotation-id ordinary-id} initial-invalid-ids)
        "the fixture must isolate the two legacy missing-title entities")
    (with-redefs [shared-service/broadcast-to-clients! (fn [& _args] nil)]
      (let [result (worker-db-validate/validate-db conn)
            annotation (d/entity @conn [:block/uuid annotation-uuid])
            ordinary-block (d/entity @conn [:block/uuid ordinary-uuid])]
        (is (some? annotation)
            "default validation repair must preserve the legacy PDF annotation")
        (is (= "" (:block/title annotation))
            "legacy missing annotation text must normalize to an empty title")
        (is (= annotation-uuid (:block/uuid annotation)))
        (is (= page-id (:db/id (:block/page annotation))))
        (is (= page-id (:db/id (:block/parent annotation))))
        (is (= "a0" (:block/order annotation)))
        (is (= [1710000002000 1710000003000]
               ((juxt :block/created-at :block/updated-at) annotation)))
        (is (= [:logseq.class/Pdf-annotation]
               (mapv :db/ident (:block/tags annotation))))
        (is (= :annotation (:logseq.property/ls-type annotation)))
        (is (= :logseq.property/color.yellow
               (:db/ident (:logseq.property.pdf/hl-color annotation))))
        (is (= page-id
               (:db/id (:logseq.property/asset annotation))))
        (is (= 7 (:logseq.property.pdf/hl-page annotation)))
        (is (= hl-value (:logseq.property.pdf/hl-value annotation)))
        (is (nil? ordinary-block)
            "ordinary non-PDF blocks missing title keep the existing deletion policy")
        (is (empty? (:errors result)))
        (is (empty? (:errors (worker-db-validate/validate-db conn)))
            "the repaired selfhost.4-shaped graph must validate cleanly")))))
