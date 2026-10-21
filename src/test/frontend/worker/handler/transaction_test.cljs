(ns frontend.worker.handler.transaction-test
  (:require [logseq.db.frontend.property :as db-property]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [cljs.test :refer [deftest is]]
            [datascript.core :as d]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.db-listener :as db-listener]
            [frontend.worker.handler.block :as block-handler]
            [frontend.worker.handler.transaction :as transaction]
            [frontend.worker.state :as worker-state]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.literature :as literature]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.op :as outliner-op]
            [logseq.outliner.op.construct :as op-construct]
            [logseq.outliner.property :as outliner-property]
            [logseq.outliner.recycle :as recycle]))

(deftest rename-page-publishes-page-lifecycle-data-test
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "Before"}
                :blocks [{:block/title "Unchanged child"}]}])
        page (ldb/get-page @conn "before")
        page-uuid (:block/uuid page)
        reports (atom [])]
    (d/listen! conn ::rename #(swap! reports conj %))
    (outliner-op/apply-ops! conn [[:rename-page [page-uuid "After 中文"]]] {})
    (is (= 1 (count @reports)))
    (is (= "After 中文" (:block/title (d/entity @conn [:block/uuid page-uuid]))))
    (is (= {:page-id (:db/id page) :old-name "Before" :new-name "After 中文"}
           (get-in (first @reports) [:tx-meta :data])))
    (is (= :rename-page (get-in (first @reports) [:tx-meta :outliner-op])))
    (is (= [[:rename-page [page-uuid "After 中文"]]]
           (get-in (first @reports) [:tx-meta :outliner-ops])))
    (reset! reports [])
    (outliner-op/apply-ops! conn [[:rename-page [page-uuid "Final"]]]
                             {:outliner-op :rename-page :data {:old-name "stale"}})
    (is (= {:page-id (:db/id page) :old-name "After 中文" :new-name "Final"}
           (get-in (first @reports) [:tx-meta :data])))
    (is (= "Unchanged child"
           (:block/title (first (:block/_parent (d/entity @conn [:block/uuid page-uuid]))))))))

(deftest automatic-recycle-gc-respects-remote-graph-boundary-test
  (doseq [remote? [true false]]
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "retained-page"}
                  :blocks [{:block/title "retained-text"
                            :build/properties {:default "retained-property"}
                            :build/children [{:block/title "retained-child"}]}]}])
          page (ldb/get-page @conn "retained-page")
          page-id (:db/id page)
          reports (atom [])]
      (d/transact! conn [{:db/ident :logseq.kv/graph-remote? :kv/value remote?}])
      (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {:now-ms 0})
                     {:outliner-op :delete-page})
      (let [before @conn]
        (d/listen! conn ::gc #(swap! reports conj %))
        (with-redefs [common-util/time-ms (constantly (* 31 24 3600 1000))]
          (transaction/maybe-run-recycle-gc! conn))
        (if remote?
          (do
            (is (true? (identical? before @conn))
                "Automatic GC must not mutate even an offline remote graph.")
            (is (zero? (count @reports)))
            (is (some? (d/entity @conn page-id)))
            (is (nil? (d/entity @conn :logseq.kv/recycle-last-gc-at))))
          (do
            (is (nil? (d/entity @conn page-id)))
            (is (= (* 31 24 3600 1000)
                   (:kv/value (d/entity @conn :logseq.kv/recycle-last-gc-at)))))))
      (d/unlisten! conn ::gc))))
(deftest source-import-uses-replayable-background-operation
  (let [repo "literature-replay-operation-test"
        binding-uuid #uuid "11111111-1111-4111-8111-111111111111"
        destination-uuid #uuid "22222222-2222-4222-8222-222222222222"
        source-record {:classification :bibliographic-item
                       :source-key "zotero:synthetic:item:REPLAY"
                       :doi "10.5555/replay"
                       :title "Unaccepted source title"
                       :authors "Unaccepted source author"}
        register! (get @thread-api/*thread-apis
                       :thread-api/literature-register-source-record)
        conn (d/create-conn)
        calls (atom [])]
    (with-redefs [thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (constantly {:operation nil})))
                  worker-state/get-datascript-conn (constantly conn)
                  outliner-op/apply-ops!
                  (fn [_conn ops opts]
                    (swap! calls conj [ops opts])
                    {:status :created})]
      (is (= :created (:status (register! repo binding-uuid destination-uuid source-record))))
      (is (= [[[:literature-register-source-record
                [binding-uuid destination-uuid source-record]]]]
             (mapv first @calls)))
      (is (every? #(= false (:gen-undo-ops? (second %))) @calls))
      (is (every? :persist-op? (map second @calls))))))

(deftest identified-pdf-registration-uses-replayable-operation-and-repair-gate
  (let [repo "literature-pdf-replay-operation-test"
        asset-uuid #uuid "11111111-1111-4111-8111-111111111112"
        identifiers {:doi "10.5555/identified-pdf"}
        register! (get @thread-api/*thread-apis :thread-api/literature-register-pdf)
        conn (d/create-conn)
        repair-state (atom {:operation {:disposition :repairing}})
        calls (atom [])]
    (with-redefs [thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (fn [_] @repair-state)))
                  worker-state/get-datascript-conn (constantly conn)
                  outliner-op/apply-ops!
                  (fn [_ ops opts]
                    (swap! calls conj [ops opts])
                    {:status :created})]
      (is (= {:status :deferred :reason :repair-active}
             (register! repo asset-uuid "Synthetic Paper" identifiers)))
      (is (empty? @calls))
      (reset! repair-state {:operation nil})
      (is (= :created (:status
                       (register! repo asset-uuid "Synthetic Paper" identifiers))))
      (is (= [[[:literature-register-identified-pdf
                [asset-uuid "Synthetic Paper" identifiers]]]]
             (mapv first @calls)))
      (is (true? (-> @calls first second :persist-op?))))))

(deftest identified-pdf-journal-keeps-only-replay-evidence
  (let [asset-uuid #uuid "11111111-1111-4111-8111-111111111112"
        op [:literature-register-identified-pdf
            [asset-uuid "Synthetic Paper"
             {:doi "10.5555/identified-pdf" :arxiv "2401.12345"
              :pdf-text "Not journal data"}]]]
    (is (= [:literature-register-identified-pdf
            [asset-uuid "Synthetic Paper"
             {:doi "10.5555/identified-pdf" :arxiv "2401.12345"}]]
           (#'op-construct/canonicalize-semantic-outliner-op nil nil op)))))

(deftest verified-pdf-registration-uses-replayable-operation-and-repair-gate
  (let [repo "literature-verified-pdf-test"
        asset-uuid #uuid "11111111-1111-4111-8111-111111111113"
        evidence {:identifiers {:doi "10.5555/verified"}
                  :title "Verified Paper"}
        metadata {:provider :crossref
                  :doi "10.5555/verified"
                  :title "Verified Paper"
                  :authors "Example, Alice"
                  :type "journal-article"}
        register! (get @thread-api/*thread-apis
                       :thread-api/literature-register-verified-pdf)
        repair-state (atom {:operation {:disposition :repairing}})
        calls (atom [])]
    (with-redefs [thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (fn [_] @repair-state)))
                  worker-state/get-datascript-conn (constantly (d/create-conn))
                  outliner-op/apply-ops!
                  (fn [_ ops opts]
                    (swap! calls conj [ops opts])
                    {:status :created})]
      (is (= {:status :deferred :reason :repair-active}
             (register! repo asset-uuid evidence metadata)))
      (is (empty? @calls))
      (reset! repair-state {:operation nil})
      (is (= :created (:status (register! repo asset-uuid evidence metadata))))
      (is (= [[[:literature-register-verified-pdf
                [asset-uuid evidence metadata]]]]
             (mapv first @calls)))
      (is (true? (-> @calls first second :persist-op?))))
    (is (= [:literature-register-verified-pdf
            [asset-uuid evidence metadata]]
           (#'op-construct/canonicalize-semantic-outliner-op
            nil nil
            [:literature-register-verified-pdf
             [asset-uuid (-> evidence
                             (assoc :pdf-text "private bytes")
                             (assoc-in [:identifiers :pdf-text]
                                       "private bytes"))
              (assoc metadata :profile-path "private path")]])))
    (let [arxiv-evidence (assoc evidence :identifiers {:arxiv "2401.12345v2"})
          arxiv-metadata {:provider :arxiv
                          :arxiv "2401.12345"
                          :arxiv-record-id "2401.12345v2"
                          :title "Verified Paper"
                          :authors "Example, Alice"
                          :type "preprint"}]
      (is (= [:literature-register-verified-pdf
              [asset-uuid arxiv-evidence arxiv-metadata]]
             (#'op-construct/canonicalize-semantic-outliner-op
              nil nil
              [:literature-register-verified-pdf
               [asset-uuid arxiv-evidence
                (assoc arxiv-metadata :pdf-bytes "private bytes")]]))))))

(deftest automatic-pdf-associations-preserve-intent-and-repair-gate
  (let [asset-uuid #uuid "11111111-1111-4111-8111-111111111114"
        paper-uuid #uuid "22222222-2222-4222-8222-222222222224"
        context {:binding-uuid #uuid "33333333-3333-4333-8333-333333333334"
                 :source-key "zotero:synthetic:item:AUTOMATIC"
                 :reference-uuid #uuid "44444444-4444-4444-8444-444444444444"}
        evidence {:identifiers {:doi "10.5555/automatic"} :title "Synthetic Paper"}
        auto-pdf! (get @thread-api/*thread-apis :thread-api/literature-auto-link-pdf)
        link! (get @thread-api/*thread-apis :thread-api/literature-link-pdf)
        repair-state (atom {:operation :repairing})
        calls (atom [])]
    (with-redefs [thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (fn [_] @repair-state)))
                  worker-state/get-datascript-conn (constantly (d/create-conn))
                  outliner-op/apply-ops!
                  (fn [_ ops opts]
                    (swap! calls conj [ops opts])
                    {:status :linked})]
      (doseq [invoke [#(auto-pdf! "synthetic-auto" asset-uuid evidence)
                      #(link! "synthetic-auto" asset-uuid paper-uuid
                              {:automatic? true :source-context context})]]
        (is (= {:status :deferred :reason :repair-active} (invoke))))
      (is (empty? @calls))
      (reset! repair-state {:operation nil})
      (is (= :linked (:status (auto-pdf! "synthetic-auto" asset-uuid evidence))))
      (is (= :linked (:status
                     (link! "synthetic-auto" asset-uuid paper-uuid
                            {:automatic? true :source-context context}))))
      (is (= [[[:literature-register-verified-pdf [asset-uuid evidence {}]]]
              [[:literature-auto-link-pdf [asset-uuid paper-uuid context]]]]
             (mapv first @calls)))
      (is (every? #(and (:persist-op? (second %))
                       (false? (:gen-undo-ops? (second %)))) @calls)))
    (let [op [:literature-auto-link-pdf [asset-uuid paper-uuid context]]]
      (is (= op (#'op-construct/canonicalize-semantic-outliner-op nil nil op))))))

(deftest source-pdf-journal-keeps-only-native-replay-context
  (let [asset-uuid #uuid "72000000-0000-4000-8000-000000000461"
        paper-uuid #uuid "71000000-0000-4000-8000-000000000461"
        asset-block {:block/uuid asset-uuid
                     :block/title "Synthetic source PDF"
                     :block/tags #{:logseq.class/Asset}
                     :logseq.property.asset/type "pdf"
                     :logseq.property.asset/size 10
                     :logseq.property.asset/checksum (apply str (repeat 64 "0"))}
        context {:binding-uuid #uuid "73000000-0000-4000-8000-000000000461"
                 :source-key "zotero:synthetic:item:JOURNAL"
                 :reference-uuid #uuid "74000000-0000-4000-8000-000000000461"
                 :asset-block asset-block}
        supplied-context (-> context
                             (assoc :binding {:token "synthetic-discarded-token"}
                                    :file-path "synthetic-discarded-path"
                                    :download-url "https://example.invalid/discarded")
                             (update :asset-block assoc
                                     :file-bytes "synthetic-discarded-bytes"
                                     :logseq.property.asset/external-url
                                     "https://example.invalid/discarded"
                                     :block/parent paper-uuid))]
    (is (= [:literature-auto-link-pdf [asset-uuid paper-uuid context]]
           (#'op-construct/canonicalize-semantic-outliner-op
            nil nil [:literature-auto-link-pdf
                     [asset-uuid paper-uuid supplied-context]])))))

(deftest late-source-pdf-worker-request-blocks-before-native-asset-writes
  (doseq [new-asset? [true false]]
    (let [conn (db-test/create-conn)
          binding-uuid #uuid "73000000-0000-4000-8000-000000000462"
          paper-a #uuid "71000000-0000-4000-8000-000000000462"
          paper-b #uuid "71000000-0000-4000-8000-000000000463"
          asset-uuid #uuid "72000000-0000-4000-8000-000000000462"
          reference-uuid #uuid "74000000-0000-4000-8000-000000000462"
          annotation-uuid #uuid "75000000-0000-4000-8000-000000000462"
          source-key "zotero:synthetic:item:LATEPDF"
          link! (get @thread-api/*thread-apis :thread-api/literature-link-pdf)
          asset-block {:block/uuid asset-uuid :block/title "Synthetic PDF"
                       :block/tags #{:logseq.class/Asset}
                       :logseq.property.asset/type "pdf"
                       :logseq.property.asset/size 10
                       :logseq.property.asset/checksum (apply str (repeat 64 "0"))}
          context (cond-> {:binding-uuid binding-uuid
                           :source-key source-key
                           :reference-uuid reference-uuid}
                    new-asset? (assoc :asset-block asset-block))]
      (d/transact! conn (sqlite-create-graph/build-properties
                                 (select-keys db-property/built-in-properties
                                              db-property/literature-property-idents)))
      (sqlite-build/create-blocks
       conn {:pages-and-blocks
             [{:page {:block/title "Synthetic source binding" :block/uuid binding-uuid}}
              {:page {:block/title "Synthetic source bibliography"}
               :blocks [{:block/uuid paper-a :block/title "Former source Paper"
                         :build/properties {:logseq.property/literature-item? true}}
                        (cond-> {:block/uuid paper-b :block/title "Current source Paper"
                                 :build/properties {:logseq.property/literature-item? true
                                                    :logseq.property/literature-source-key #{source-key}}}
                          (not new-asset?)
                          (assoc :build/children
                                 [{:block/uuid asset-uuid :block/title "Synthetic PDF"
                                   :build/tags [:logseq.class/Asset]
                                   :build/properties
                                   (select-keys asset-block [:logseq.property.asset/type
                                                             :logseq.property.asset/size
                                                             :logseq.property.asset/checksum])
                                   :build/children [{:block/uuid annotation-uuid
                                                     :block/title "Preserved PDF annotation"}]}]))]}]})
      (let [key-node (first (:logseq.property/literature-source-key
                            (d/entity @conn [:block/uuid paper-b])))]
        (outliner-property/set-block-property!
         conn (:db/id key-node) :logseq.property/literature-source-target
         (:db/id (d/entity @conn [:block/uuid binding-uuid]))))
      (when-not new-asset?
        (outliner-property/set-block-property!
         conn [:block/uuid paper-b] :logseq.property/literature-assets
         (:db/id (d/entity @conn [:block/uuid asset-uuid]))))
      (let [before @conn
            reports (atom [])]
        (d/listen! conn ::late-source-pdf #(swap! reports conj %))
        (try
          (with-redefs [worker-state/get-datascript-conn (constantly conn)
                        thread-api/*thread-apis
                        (volatile! (assoc @thread-api/*thread-apis
                                          :thread-api/db-sync-repair-status
                                          (constantly {:operation nil})))]
            (is (= {:status :blocked :reason :source-link-unavailable}
                   (link! "synthetic-late-source-pdf" asset-uuid paper-a
                          {:automatic? true :source-context context}))))
          (is (identical? before @conn))
          (is (empty? @reports))
          (is (nil? (d/entity @conn [:block/uuid reference-uuid])))
          (if new-asset?
            (is (nil? (d/entity @conn [:block/uuid asset-uuid])))
            (do
              (is (= paper-b (:block/uuid (:block/parent
                                          (d/entity @conn [:block/uuid asset-uuid])))))
              (is (= "Preserved PDF annotation"
                     (:block/title (d/entity @conn [:block/uuid annotation-uuid]))))))
          (finally
            (d/unlisten! conn ::late-source-pdf)))))))

(deftest keep-pdf-defers-during-repair-and-does-not-acknowledge-failed-commit
  (let [keep! (get @thread-api/*thread-apis :thread-api/literature-keep-pdf)
        asset-uuid #uuid "11111111-1111-4111-8111-111111111115"
        repair-state (atom {:operation :repairing})
        failure (js/Error. "Synthetic commit failure")
        plans (atom 0)
        writes (atom [])]
    (with-redefs [thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status (fn [_] @repair-state)))
                  worker-state/get-datascript-conn (constantly (d/create-conn))
                  literature/build-keep-pdf-plan
                  (fn [_ uuid]
                    (swap! plans inc)
                    {:status :ready :action :keep :asset-uuid uuid
                     :tx-data [[:db/add [:block/uuid uuid]
                                :logseq.property.asset/literature-auto-excluded? true]]})
                  transaction/transact!
                  (fn [_ tx meta _]
                    (swap! writes conj [tx meta])
                    (throw failure))]
      (is (= {:status :deferred :reason :repair-active}
             (keep! "synthetic-keep" asset-uuid)))
      (is (zero? @plans))
      (is (empty? @writes))
      (reset! repair-state {:operation nil})
      (is (identical? failure (try (keep! "synthetic-keep" asset-uuid)
                                  nil (catch :default e e))))
      (is (= 1 (count @writes)))
      (is (true? (-> @writes first second :persist-op?))))))

(deftest source-import-journal-excludes-unaccepted-metadata
  (let [binding-uuid #uuid "11111111-1111-4111-8111-111111111111"
        destination-uuid #uuid "22222222-2222-4222-8222-222222222222"
        source-record {:classification :bibliographic-item
                       :source-key "zotero:synthetic:item:REPLAY"
                       :doi "10.5555/replay"
                       :title "Unaccepted source title"
                       :authors "Unaccepted source author"
                       :publication "Unaccepted source journal"}
        op [:literature-register-source-record
            [binding-uuid destination-uuid source-record]]]
    (is (= [:literature-register-source-record
            [binding-uuid destination-uuid
             (select-keys source-record [:classification :source-key :doi])]]
           (#'op-construct/canonicalize-semantic-outliner-op nil nil op)))))

(deftest source-import-journal-retains-created-metadata
  (let [binding-uuid #uuid "11111111-1111-4111-8111-111111111111"
        destination-uuid #uuid "22222222-2222-4222-8222-222222222222"
        source-record {:classification :bibliographic-item
                       :source-key "zotero:synthetic:item:CREATE"
                       :doi "10.5555/create-replay"
                       :title "Accepted source title"
                       :authors "Accepted source author"
                       :date "2026"
                       :publication "Accepted source journal"
                       :type "journal-article"
                       :url "https://example.invalid/paper"
                       :citekey "Synthetic2026"}
        report (d/with
                (d/empty-db {:logseq.property/literature-source-key
                             {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})
                [{:db/id -1 :logseq.property/literature-item? true
                  :logseq.property/literature-source-key [-2]}
                 {:db/id -2 :block/title (:source-key source-record)}])
        op [:literature-register-source-record [binding-uuid destination-uuid source-record]]]
    (is (= op (#'op-construct/canonicalize-semantic-outliner-op
               (:db-after report) (:tx-data report) op)))
    (let [paper-id (get (:tempids report) -1)
          linked-record (assoc source-record :source-key "zotero:synthetic:item:LINK"
                               :title "Unaccepted replacement title")
          link-report (d/with (:db-after report)
                              [{:db/id paper-id :logseq.property/literature-source-key [-3]}
                               {:db/id -3 :block/title (:source-key linked-record)}])
          link-op [:literature-register-source-record [binding-uuid destination-uuid linked-record]]]
      (is (= [:literature-register-source-record
              [binding-uuid destination-uuid
               (select-keys linked-record [:classification :source-key :doi :arxiv])]]
             (#'op-construct/canonicalize-semantic-outliner-op
              (:db-after link-report) (:tx-data link-report) link-op))))))

(deftest source-import-defers-without-planning-or-writing-during-repair
  (let [repo "literature-repair-test"
        conn (d/create-conn)
        binding-uuid #uuid "33333333-3333-4333-8333-333333333333"
        destination-uuid #uuid "44444444-4444-4444-8444-444444444444"
        source-record {:classification :bibliographic-item
                       :source-key "zotero:synthetic:item:REPAIR"
                       :doi "10.5555/repair"}
        register! (get @thread-api/*thread-apis
                       :thread-api/literature-register-source-record)
        repair-state (atom {:operation {:disposition :repairing}})
        calls (atom [])
        reads (atom [])]
    (with-redefs [thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (fn [graph]
                                      (swap! reads conj graph)
                                      @repair-state)))
                  worker-state/get-datascript-conn (constantly conn)
                  outliner-op/apply-ops!
                  (fn [_conn ops _opts]
                    (swap! calls conj ops)
                    {:status :created})]
      (doseq [disposition [:repairing :local-only]]
        (reset! repair-state {:operation {:disposition disposition}})
        (is (= {:status :deferred :reason :repair-active}
               (register! repo binding-uuid destination-uuid source-record)))
        (is (empty? @calls)))
      (reset! repair-state {:operation nil :committed {:projection-epoch 2}})
      (is (= :created (:status (register! repo binding-uuid destination-uuid source-record))))
      (is (= 1 (count @calls)))
      ;; Local graphs have no repair receipt; use the same import path.
      (reset! repair-state nil)
      (is (= :created (:status (register! repo binding-uuid destination-uuid source-record))))
      (is (= 2 (count @calls)))
      (is (= (repeat 4 repo) @reads)))))

(deftest source-correction-defers-during-repair-and-preserves-user-transaction
  (let [repo "synthetic-source-correction"
        conn (d/create-conn)
        paper-uuid #uuid "75000000-0000-4000-8000-000000000001"
        target-uuid #uuid "75000000-0000-4000-8000-000000000002"
        source {:key-uuid #uuid "75000000-0000-4000-8000-000000000003"
                :binding-uuid #uuid "75000000-0000-4000-8000-000000000004"
                :source-key "zotero:synthetic:item:CORRECT01" :active? true}
        correct! (get @thread-api/*thread-apis :thread-api/literature-correct-source-record)
        repair-state (atom {:operation :repairing})
        plans (atom [])
        writes (atom [])
        ops [[:transact [[[:db/retract [:block/uuid paper-uuid]
                          :logseq.property/literature-source-key
                          [:block/uuid (:key-uuid source)]]
                         [:db/add [:block/uuid target-uuid]
                          :logseq.property/literature-source-key
                          [:block/uuid (:key-uuid source)]]] {}]]]
        inverse-ops [[:transact [[[:db/add [:block/uuid paper-uuid]
                                  :logseq.property/literature-source-key
                                  [:block/uuid (:key-uuid source)]]] {}]]]]
    (with-redefs [worker-state/get-datascript-conn (constantly conn)
                  thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (fn [_] @repair-state)))
                  literature/build-correct-source-record-plan
                  (fn [db paper source' action target]
                    (swap! plans conj [db paper source' action target])
                    {:status :ready :ops ops :inverse-ops inverse-ops})
                  outliner-op/apply-ops!
                  (fn [conn' ops' opts]
                    (swap! writes conj [conn' ops' opts]))]
      (is (= {:status :deferred :reason :repair-active}
             (correct! repo paper-uuid source :reassign target-uuid)))
      (is (empty? @plans))
      (is (empty? @writes))
      (reset! repair-state {:operation nil})
      (is (= {:status :updated} (correct! repo paper-uuid source :reassign target-uuid)))
      (is (= [[@conn paper-uuid source :reassign target-uuid]] @plans))
      (is (= 1 (count @writes)))
      (let [[conn' ops' opts] (first @writes)]
        (is (identical? conn conn'))
        (is (= ops ops'))
        (is (true? (:persist-op? opts)))
        (is (true? (:gen-undo-ops? opts)))
        (is (= ops (:db-sync/forward-outliner-ops opts)))
        (is (= inverse-ops (:db-sync/inverse-outliner-ops opts)))))))

(deftest stale-source-correction-and-commit-failure-are-not-successful
  (let [conn (d/create-conn)
        correct! (get @thread-api/*thread-apis :thread-api/literature-correct-source-record)
        mode (atom :stale)
        writes (atom 0)]
    (with-redefs [worker-state/get-datascript-conn (constantly conn)
                  thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status
                                    (constantly {:operation nil})))
                  literature/build-correct-source-record-plan
                  (fn [& _]
                    (if (= :stale @mode)
                      {:status :blocked :reason :source-link-unavailable}
                      {:status :ready :ops [[:transact [[] {}]]]}))
                  outliner-op/apply-ops!
                  (fn [& _]
                    (swap! writes inc)
                    (throw (ex-info "Synthetic commit failure" {})))]
      (is (= {:status :blocked :reason :source-link-unavailable}
             (correct! "synthetic" (random-uuid) {} :unlink nil)))
      (is (zero? @writes))
      (reset! mode :commit-failed)
      (is (thrown-with-msg? cljs.core/ExceptionInfo #"Synthetic commit failure"
                           (correct! "synthetic" (random-uuid) {} :unlink nil)))
      (is (= 1 @writes)))))

(defn- source-owner-unlink-fixture
  []
  (let [conn (db-test/create-conn)
        paper-uuid #uuid "71000000-0000-4000-8000-000000000496"
        other-paper-uuid #uuid "71000000-0000-4000-8000-000000000497"
        binding-uuid #uuid "73000000-0000-4000-8000-000000000496"
        source-key "zotero:synthetic:item:OWNER496"]
    (d/transact! conn (sqlite-create-graph/build-properties
                                 (select-keys db-property/built-in-properties
                                              db-property/literature-property-idents)))
    (sqlite-build/create-blocks
     conn {:pages-and-blocks
           [{:page {:block/title "Synthetic owner source binding" :block/uuid binding-uuid}}
            {:page {:block/title "Synthetic owner bibliography"}
             :blocks [{:block/uuid paper-uuid :block/title "Selected source Paper"
                       :build/properties {:logseq.property/literature-item? true
                                          :logseq.property/literature-source-key #{source-key}}}
                      {:block/uuid other-paper-uuid :block/title "Other source Paper"
                       :build/properties {:logseq.property/literature-item? true}}]}]})
    (let [key-node (first (:logseq.property/literature-source-key
                          (d/entity @conn [:block/uuid paper-uuid])))
          source {:key-uuid (:block/uuid key-node) :binding-uuid binding-uuid
                  :source-key source-key :active? true}]
      (outliner-property/set-block-property!
       conn (:db/id key-node) :logseq.property/literature-source-target
       (:db/id (d/entity @conn [:block/uuid binding-uuid])))
      (d/transact! conn [[:db/add [:block/uuid other-paper-uuid]
                         :logseq.property/literature-source-key [:block/uuid (:key-uuid source)]]])
      {:conn conn :paper-uuid paper-uuid :other-paper-uuid other-paper-uuid :source source})))

(deftest source-owner-unlink-through-protected-api-preserves-native-user-context
  (let [{:keys [conn paper-uuid other-paper-uuid source]} (source-owner-unlink-fixture)
        correct! (get @thread-api/*thread-apis :thread-api/literature-correct-source-record)
        repair-state (atom {:operation :repairing})
        apply-ops! outliner-op/apply-ops!
        writes (atom [])
        before @conn
        key-ref [:block/uuid (:key-uuid source)]
        key-before (d/pull before '[*] key-ref)
        other-before (d/pull before '[*] [:block/uuid other-paper-uuid])
        context {:paper-uuid paper-uuid :source source :action :unlink-owner
                 :target-paper-uuid nil :inverse? false}
        forward [[:transact [[[:db/retract [:block/uuid paper-uuid]
                               :logseq.property/literature-source-key key-ref]]
                             {:literature/correct-source-record context}]]]
        inverse [[:transact [[[:db/add [:block/uuid paper-uuid]
                               :logseq.property/literature-source-key key-ref]]
                             {:literature/correct-source-record (assoc context :inverse? true)}]]]]
    (with-redefs [worker-state/get-datascript-conn (constantly conn)
                  thread-api/*thread-apis
                  (volatile! (assoc @thread-api/*thread-apis
                                    :thread-api/db-sync-repair-status (fn [_] @repair-state)))
                  outliner-op/apply-ops!
                  (fn [conn' ops opts]
                    (swap! writes conj [conn' ops opts])
                    (apply-ops! conn' ops opts))]
      (is (= {:status :deferred :reason :repair-active}
             (correct! "synthetic-owner-unlink" paper-uuid source :unlink-owner nil)))
      (is (identical? before @conn))
      (is (empty? @writes))
      (reset! repair-state {:operation nil})
      (is (= :updated (:status (correct! "synthetic-owner-unlink" paper-uuid source :unlink-owner nil))))
      (is (= 1 (count @writes)))
      (let [[conn' ops opts] (first @writes)]
        (is (identical? conn conn'))
        (is (= forward ops))
        (is (true? (:persist-op? opts)))
        (is (true? (:gen-undo-ops? opts)))
        (is (= forward (:db-sync/forward-outliner-ops opts)))
        (is (= inverse (:db-sync/inverse-outliner-ops opts))))
      (is (empty? (:logseq.property/literature-source-key
                   (d/entity @conn [:block/uuid paper-uuid]))))
      (is (= key-before (d/pull @conn '[*] key-ref)))
      (is (= other-before (d/pull @conn '[*] [:block/uuid other-paper-uuid]))))))

(deftest source-owner-unlink-worker-rechecks-edge-and-does-not-mask-commit-failure
  (doseq [scenario [:stale-edge :commit-failed]]
    (let [{:keys [conn paper-uuid source]} (source-owner-unlink-fixture)
          correct! (get @thread-api/*thread-apis :thread-api/literature-correct-source-record)
          writes (atom 0)]
      (when (= :stale-edge scenario)
        (d/transact! conn [[:db/retract [:block/uuid paper-uuid]
                           :logseq.property/literature-source-key [:block/uuid (:key-uuid source)]]]))
      (let [before @conn]
        (with-redefs [worker-state/get-datascript-conn (constantly conn)
                      thread-api/*thread-apis
                      (volatile! (assoc @thread-api/*thread-apis
                                        :thread-api/db-sync-repair-status (constantly {:operation nil})))
                      outliner-op/apply-ops!
                      (fn [& _]
                        (swap! writes inc)
                        (throw (ex-info "Synthetic owner correction commit failure" {})))]
          (if (= :stale-edge scenario)
            (do
              (is (= :blocked (:status (correct! "synthetic-owner-unlink" paper-uuid source :unlink-owner nil))))
              (is (zero? @writes)))
            (do
              (is (thrown-with-msg? cljs.core/ExceptionInfo #"Synthetic owner correction commit failure"
                                   (correct! "synthetic-owner-unlink" paper-uuid source :unlink-owner nil)))
              (is (= 1 @writes))))
          (is (identical? before @conn)))))))

(deftest apply-outliner-ops-returns-stored-delta-and-canonical-editor-rows-test
  (let [repo "transaction-handler-test"
        conn (d/create-conn)
        perf-id #uuid "11111111-1111-1111-1111-111111111111"
        first-row-uuid #uuid "22222222-2222-2222-2222-222222222222"
        second-row-uuid #uuid "33333333-3333-3333-3333-333333333333"
        editor-row-uuids [second-row-uuid first-row-uuid]
        first-row {:block/uuid first-row-uuid :block/tx-id 6}
        second-row {:block/uuid second-row-uuid :block/tx-id 7}
        editor-rows {first-row-uuid first-row
                     second-row-uuid second-row}
        delta {:graph-id repo
               :rev 7
               :blocks {}
               :deleted {}
               :children {}
               :affected-keys #{[:graph]}}
        result {:cursor-position 3}
        previous-conns @worker-state/*datascript-conns
        taken-perf-ids (atom [])
        canonical-calls (atom [])
        hydration-calls (atom 0)]
    (try
      (reset! worker-state/*datascript-conns {repo conn})
      (with-redefs [outliner-op/apply-ops! (fn [_conn _ops _opts]
                                             result)
                    db-listener/take-outliner-op-delta!
                    (fn [requested-perf-id]
                      (swap! taken-perf-ids conj requested-perf-id)
                      delta)
                    db-listener/take-outliner-op-perf! (fn [_perf-id] nil)
                    block-handler/canonical-blocks
                    (fn [db row-uuids]
                      (swap! canonical-calls conj [db row-uuids])
                      {:basis-rev 7 :blocks editor-rows})
                    block-handler/get-block-and-children
                    (fn [& _args]
                      (swap! hydration-calls inc)
                      nil)]
        (let [apply-ops! (get @thread-api/*thread-apis
                              :thread-api/apply-outliner-ops)
              response (apply-ops! repo
                                   [[:save-block []]]
                                   {:affected-block-uuids #{}
                                    :editor-row-uuids editor-row-uuids
                                    :ui/perf-id perf-id})]
          (is (= [perf-id] @taken-perf-ids))
          (is (= result (:result response)))
          (is (identical? delta (:delta response))
              "The direct response must return the delta stored by the database listener.")
          (is (= editor-row-uuids (:editor-row-uuids response))
              "Editor row UUIDs must retain operation order.")
          (is (= editor-rows (:editor-rows response))
              "Editor rows are a complete canonical response, not a subset of the render delta.")
          (is (= [[@conn editor-row-uuids]] @canonical-calls))
          (is (zero? @hydration-calls)
              "The origin handler must not reconstruct updated block payloads.")
          (is (not-any? #(contains? response %)
                        [:affected-page-uuids
                         :deleted-block-uuids
                         :entity-updated-block-uuids
                         :render-invalidated-block-uuids
                         :structural-parent-uuids
                         :updated-blocks]))))
      (finally
        (reset! worker-state/*datascript-conns previous-conns)))))
