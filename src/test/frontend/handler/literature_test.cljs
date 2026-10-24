(ns frontend.handler.literature-test
  (:require [logseq.db.frontend.property :as db-property]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [cljs.test :refer [deftest is testing]]
            ["/frontend/extensions/pdf/literature" :as js-literature]
            [clojure.string :as string]
            [datascript.core :as d]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db.async :as db-async]
            [frontend.db.subs :as db-subs]
            [frontend.db.transact :as db-transact]
            [frontend.fs :as fs]
            [frontend.handler.assets :as assets]
            [frontend.handler.literature :as literature]
            [frontend.handler.literature-metadata :as metadata]
            [frontend.handler.literature-zotero :as zotero]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [frontend.test.helper :include-macros true :refer [deftest-async]]
            [frontend.util :as util]
            [frontend.worker.handler.block :as worker-block]
            [frontend.worker.handler.page :as worker-page]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [logseq.db.frontend.asset :as db-asset]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.op :as outliner-op]
            [promesa.core :as p]))

(def destination-page-uuid #uuid "79000000-0000-4000-8000-000000000099")

(deftest-async shared-source-actions-do-not-require-personal-zotero-settings
  (let [repo "synthetic-shared-graph"
        paper-uuid #uuid "76000000-0000-4000-8000-000000000001"
        target-uuid #uuid "76000000-0000-4000-8000-000000000002"
        source {:key-uuid #uuid "76000000-0000-4000-8000-000000000003"
                :binding-uuid #uuid "76000000-0000-4000-8000-000000000004"
                :source-key "zotero:synthetic:item:SHARED01" :active? true}
        calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    storage/get (fn [& _] (throw (ex-info "Personal settings must not be read" {})))
                    db-async/<paper-literature-sources
                    (fn [graph paper]
                      (swap! calls conj [:read graph paper])
                      (p/resolved {:status :ok :sources [source]}))
                    db-async/<correct-literature-source-record
                    (fn [graph paper selected action target]
                      (swap! calls conj [:correct graph paper selected action target])
                      (p/resolved {:status :updated}))]
      (p/let [listed (literature/<paper-sources! repo paper-uuid)
              moved (literature/<correct-paper-source! repo paper-uuid source :reassign target-uuid)
              unlinked (literature/<correct-paper-source! repo paper-uuid source :unlink nil)]
        (is (= [source] (:sources listed)))
        (is (= :updated (:status moved)))
        (is (= :updated (:status unlinked)))
        (is (= [[:read repo paper-uuid]
                [:correct repo paper-uuid source :reassign target-uuid]
                [:correct repo paper-uuid source :unlink nil]] @calls))))))

(deftest-async shared-source-actions-stop-at-an-inactive-graph
  (let [calls (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly "other-graph")
                    state/get-state (constantly false)
                    db-async/<paper-literature-sources (fn [& _] (swap! calls inc))
                    db-async/<correct-literature-source-record (fn [& _] (swap! calls inc))]
      (p/let [listed (literature/<paper-sources! "previous-graph" (random-uuid))
              changed (literature/<correct-paper-source!
                       "previous-graph" (random-uuid) {} :unlink nil)]
        (is (= :inactive-graph (:reason listed)))
        (is (= :inactive-graph (:reason changed)))
        (is (zero? @calls))))))

(deftest-async manual-create-uses-native-user-transaction-without-zotero
  (let [repo "synthetic-repo"
        conn (db-test/create-conn)
        calls (atom [])]
    (d/transact! conn (sqlite-create-graph/build-properties
                                 (select-keys db-property/built-in-properties
                                              db-property/literature-property-idents)))
    (sqlite-build/create-blocks
     conn {:pages-and-blocks [{:page {:block/title "Manual bibliography"
                                    :block/uuid destination-page-uuid}}]})
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    state/get-editor-info (constantly nil)
                    storage/get (constantly {})
                    state/<invoke-db-worker
                    (fn [op graph page-uuid]
                      (is (= :thread-api/get-page-route-info op))
                      (is (= repo graph))
                      (p/resolved (#'worker-page/page-route-info @conn page-uuid)))
                    db-async/<get-block
                    (fn [graph block-uuid _opts]
                      (is (= repo graph))
                      (p/resolved (:block (worker-block/get-block-and-children
                                           @conn block-uuid {:children? false}))))
                    db-transact/apply-outliner-ops
                    (fn [ui-conn ops opts]
                      (is (nil? ui-conn))
                      (swap! calls conj {:ops ops :opts opts})
                      (p/resolved (outliner-op/apply-ops! conn ops opts)))]
      (p/let [first-result (literature/<create-manual-paper! repo destination-page-uuid " Manual title ")
              second-result (literature/<create-manual-paper! repo destination-page-uuid "Manual title")
              empty-result (literature/<create-manual-paper! repo destination-page-uuid " ")]
        (is (every? #(= :created (:status %)) [first-result second-result empty-result]))
        (is (= 3 (count (set (map :paper-uuid [first-result second-result empty-result])))))
        (is (= 3 (count @calls)))
        (doseq [{:keys [ops opts]} @calls]
          (is (= [:insert-blocks] (mapv first ops)))
          (is (= {:outliner-op :insert-blocks} opts)))
        (doseq [[result title] [[first-result "Manual title"]
                               [second-result "Manual title"]
                               [empty-result ""]]]
          (let [saved (d/entity @conn [:block/uuid (:paper-uuid result)])]
            (is (= title (:block/title saved)))
            (is (true? (:logseq.property/literature-item? saved)))
            (is (nil? (:logseq.property/literature-source-key saved)))
            (is (nil? (:logseq.property/literature-assets saved)))))))))

(deftest-async manual-create-rejects-destination-hidden-by-its-parent
  (let [repo "synthetic-repo"
        conn (db-test/create-conn)
        parent-uuid (random-uuid)
        mutations (atom 0)]
    (d/transact! conn (sqlite-create-graph/build-properties
                                 (select-keys db-property/built-in-properties
                                              db-property/literature-property-idents)))
    (sqlite-build/create-blocks
     conn {:pages-and-blocks [{:page {:block/title "Hidden parent" :block/uuid parent-uuid}}
                             {:page {:block/title "Child bibliography" :block/uuid destination-page-uuid}}]})
    (d/transact! conn [{:db/id [:block/uuid parent-uuid] :logseq.property/hide? true}
                      {:db/id [:block/uuid destination-page-uuid] :block/parent [:block/uuid parent-uuid]}])
    (is (false? (ldb/hidden? (:block (worker-block/get-block-and-children
                                     @conn destination-page-uuid {:children? false})))))
    (is (true? (:hidden? (#'worker-page/page-route-info @conn destination-page-uuid))))
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    state/get-editor-info (constantly nil)
                    storage/get (constantly {repo {:enabled? true}})
                    db-async/<get-block
                    (fn [_ block-uuid _opts]
                      (p/resolved (:block (worker-block/get-block-and-children
                                           @conn block-uuid {:children? false}))))
                    state/<invoke-db-worker
                    (fn [op graph page-uuid]
                      (is (= :thread-api/get-page-route-info op))
                      (is (= repo graph))
                      (p/resolved (#'worker-page/page-route-info @conn page-uuid)))
                    db-transact/apply-outliner-ops
                    (fn [_ ops opts]
                      (swap! mutations inc)
                      (p/resolved (outliner-op/apply-ops! conn ops opts)))]
      (p/let [result (literature/<create-manual-paper! repo destination-page-uuid "Invisible paper")]
        (is (= :destination-unavailable (:reason result)))
        (is (zero? @mutations))
        (is (empty? (d/datoms @conn :avet :logseq.property/literature-item? true)))))))

(deftest-async manual-create-blocks-invalid-and-switched-graphs
  (let [repo "synthetic-repo"
        active-repo (atom repo)
        restoring? (atom true)
        reads (atom 0)
        target (atom nil)
        switch-on-read? (atom false)]
    (p/with-redefs [state/get-current-repo #(deref active-repo)
                    state/get-state (fn [_] @restoring?)
                    storage/get (constantly {})
                    state/<invoke-db-worker (fn [& _] (p/resolved {:hidden? (ldb/hidden? @target)}))
                    db-async/<get-block
                    (fn [& _]
                      (swap! reads inc)
                      (when @switch-on-read? (reset! active-repo "other-synthetic-repo"))
                      (p/resolved @target))
                    db-transact/apply-outliner-ops
                    (fn [& _] (is false "an invalid target must not submit a mutation"))]
      (p/let [restoring (literature/<create-manual-paper! repo destination-page-uuid "Title")]
        (is (= :inactive-graph (:reason restoring)))
        (is (zero? @reads))
        (reset! restoring? false)
        (p/let [_ (p/doseq [destination [nil
                                         {:block/tags [{:db/ident :logseq.class/Tag}]}
                                         {:block/tags [{:db/ident :logseq.class/Property}]}
                                         {:block/tags [{:db/ident :logseq.class/Page}]
                                          :logseq.property/hide? true}
                                         {:block/tags [{:db/ident :logseq.class/Page}]
                                          :logseq.property/deleted-at 1}]]
                    (reset! target destination)
                    (p/let [result (literature/<create-manual-paper! repo destination-page-uuid "Title")]
                      (is (= :destination-unavailable (:reason result)))))
                switched (do (reset! target {:block/tags [{:db/ident :logseq.class/Page}]})
                             (reset! switch-on-read? true)
                             (literature/<create-manual-paper! repo destination-page-uuid "Title"))]
          (is (= :inactive-graph (:reason switched))))))))

(deftest-async manual-create-does-not-report-worker-failure-as-created
  (let [repo "synthetic-repo"
        reads (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    state/get-editor-info (constantly nil)
                    storage/get (constantly {repo {:enabled? true}})
                    state/<invoke-db-worker
                    (fn [& _] (p/resolved {:hidden? false}))
                    db-async/<get-block
                    (fn [& _]
                      (swap! reads inc)
                      (p/resolved {:block/tags [{:db/ident :logseq.class/Page}]}))
                    db-transact/apply-outliner-ops
                    (fn [& _] (p/rejected (ex-info "synthetic mutation failure" {})))]
      (-> (literature/<create-manual-paper! repo destination-page-uuid "Title")
          (p/then (fn [_] (is false "failed insert must reject")))
          (p/catch (fn [_] (is (= 1 @reads))))))))

(def complete-extraction
  {:status :complete
   :reason :page-end
   :page-height 800
   :info {:title "Bounded Graph Synchronization for Shared Research Notes"
          :author "Alice Example and Bob Researcher"}
   :items [{:text "Bounded Graph Synchronization for Shared Research Notes"
            :y 710 :font-size 22}
           {:text "Alice Example and Bob Researcher"
            :y 675 :font-size 11}
           {:text "doi: 10.5555/bound.2026.001."
            :y 620 :font-size 10}]})

(deftest-async activation-only-enables-local-tasks
  (let [stored (atom {})
        repo "synthetic-repo"]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                    storage/set (fn [key value] (swap! stored assoc key value))
                    ]
      (is (false? (literature/auto-identification-enabled? repo)))
      (p/let [result (literature/<activate! repo)]
        (is (= :enabled (:status result)))
        (is (= {:enabled? true
                :auto-identification? false
                :online-metadata? false
                :zotero-binding nil
                :last-zotero-binding nil}
               (literature/load-settings repo)))
        (literature/set-auto-identification-enabled! repo true)
        (is (true? (literature/auto-identification-enabled? repo)))))))

(deftest-async disabling-local-literature-stops-source-admission-and-can-reenable
  (let [repo "synthetic-repo"
        binding (assoc (literature/local-zotero-binding
                        #uuid "79000000-0000-4000-8000-000000000001" nil)
                       :destination-page-uuid destination-page-uuid
                       :zotero-library-key "LOCAL001")
        stored (atom {:ls-literature-settings
                      {repo {:enabled? true
                             :auto-identification? true
                             :zotero-binding binding}}})
        cancelled (atom 0)
        reconciled (atom [])]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                    storage/set (fn [key value] (swap! stored assoc key value))
                    state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    literature/cancel-active-identification!
                    (fn [] (swap! cancelled inc))
                    literature/reconcile-zotero-lifecycle!
                    (fn [repo']
                      (swap! reconciled conj (:enabled? (literature/load-settings repo'))))
                    zotero/<import-scope!
                    (fn [_ still-authorized? _]
                      (p/resolved {:status (if (still-authorized?) :complete :cancelled)}))]
      (let [disabled (literature/disable! repo)]
        (is (false? (:enabled? disabled)))
        (is (= binding (:zotero-binding disabled)))
        (is (= 1 @cancelled))
        (is (= [false] @reconciled))
        (is (false? (literature/auto-identification-enabled? repo)))
        (p/let [blocked (literature/<import-zotero-now! repo)
                _ (literature/<activate! repo)
                resumed (literature/<import-zotero-now! repo)]
          (is (= :cancelled (:status blocked)))
          (is (= :complete (:status resumed)))
          (is (= [false true] @reconciled))
          (is (= binding (:zotero-binding (literature/load-settings repo)))))))))



(deftest zotero-binding-is-local-sanitized-and-requires-activation
  (let [repo "synthetic-repo"
        graph-binding-uuid #uuid "79000000-0000-4000-8000-000000000001"
        requested {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                   :graph-binding-uuid graph-binding-uuid
                   :source-identity
                   {:kind :local
                    :binding-uuid "79000000-0000-4000-8000-000000000002"}
                   :scope {:kind :library}
                   :token "must-not-be-stored"
                   :profile-path "/private/profile"}
        stored (atom {})]
    (with-redefs [storage/get (fn [key] (get @stored key))
                  storage/set (fn [key value] (swap! stored assoc key value))]
      (is (thrown? js/Error
                   (literature/set-zotero-binding! repo requested)))
      (swap! stored assoc :ls-literature-settings
             {repo {:enabled? true}})
      (is (= {:enabled? true
              :auto-identification? false
              :online-metadata? false
              :zotero-binding
              {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
               :graph-binding-uuid graph-binding-uuid
               :source-identity
               {:kind :local
                :binding-uuid "79000000-0000-4000-8000-000000000002"}
               :scope {:kind :library}}
              :last-zotero-binding
              {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
               :graph-binding-uuid graph-binding-uuid
               :source-identity
               {:kind :local
                :binding-uuid "79000000-0000-4000-8000-000000000002"}
               :scope {:kind :library}}}
             (literature/set-zotero-binding! repo requested)))
      (is (not (re-find #"token|private/profile" (pr-str @stored))))
      (is (thrown? js/Error
                   (literature/set-zotero-binding!
                    repo
                    (assoc-in requested
                              [:source-identity :binding-uuid]
                              "invalid")))))))

(deftest local-zotero-binding-builds-only-library-or-valid-collection-scope
  (let [binding-uuid #uuid "79000000-0000-4000-8000-000000000011"]
    (is (= {:graph-binding-uuid binding-uuid
            :source-identity
            {:kind :local
             :binding-uuid "79000000-0000-4000-8000-000000000011"}
            :scope {:kind :library}}
           (literature/local-zotero-binding binding-uuid nil)))
    (is (= {:kind :collection :key "ABCD1234"}
           (:scope (literature/local-zotero-binding
                    binding-uuid "abcd1234"))))
    (is (nil? (literature/local-zotero-binding
               binding-uuid "too-short")))
    (is (nil? (literature/local-zotero-binding binding-uuid "")))
    (is (nil? (literature/local-zotero-binding binding-uuid "   ")))
    (is (nil? (literature/local-zotero-binding "invalid" nil)))))

(deftest-async local-zotero-connect-rejects-empty-collection-before-any-io
  (let [repo "synthetic-repo"
        calls (atom [])]
    (p/with-redefs [storage/get (constantly {repo {:enabled? true}})
                    storage/set (fn [& _] (swap! calls conj :stored))
                    zotero/<read-library-key! (fn []
                                                 (swap! calls conj :zotero-read)
                                                 (p/resolved "LOCAL001"))
                    db-async/<ensure-literature-source-binding (fn [& _]
                                                                  (swap! calls conj :graph-write)
                                                                  (p/resolved nil))]
      (p/let [empty-result (literature/<connect-local-zotero!
                            repo #uuid "79000000-0000-4000-8000-000000000012" "")
              blank-result (literature/<connect-local-zotero!
                            repo #uuid "79000000-0000-4000-8000-000000000012" "   ")]
        (is (= {:status :blocked :reason :invalid-binding} empty-result blank-result))
        (is (empty? @calls))))))

(deftest-async local-zotero-connect-rejects-a-collection-selected-from-another-library
  (let [repo "synthetic-repo"
        writes (atom [])]
    (p/with-redefs [literature/load-settings (constantly {:enabled? true})
                    state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    zotero/<read-library-key! (fn [] (p/resolved "OTHER001"))
                    db-async/<ensure-literature-source-binding
                    (fn [& args] (swap! writes conj args))
                    literature/set-zotero-binding!
                    (fn [& args] (swap! writes conj args))]
      (p/let [result (literature/<connect-local-zotero!
                     repo #uuid "79000000-0000-4000-8000-000000000001"
                     "COLL1000" "LOCAL001")]
        (is (= {:status :blocked :reason :library-identity-mismatch} result))
        (is (empty? @writes))))))

(deftest-async local-zotero-connect-saves-only-after-the-graph-binding-exists
  (let [repo "synthetic-repo"
        binding-uuid #uuid "79000000-0000-4000-8000-000000000012"
        stored (atom {:ls-literature-settings {repo {:enabled? true}}})
        ensure-calls (atom [])
        reconciled (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    zotero/<read-library-key! (fn [] (p/resolved "LOCAL001"))
                    storage/get (fn [key] (get @stored key))
                    storage/set (fn [key value] (swap! stored assoc key value))
                    db-async/<ensure-literature-source-binding
                    (fn [repo' binding-uuid' destination]
                      (swap! ensure-calls conj [repo' binding-uuid' destination])
                      (p/resolved {:status :created
                                   :binding-uuid binding-uuid'
                                   :destination-page-uuid destination-page-uuid}))
                    literature/reconcile-zotero-lifecycle!
                    (fn [repo'] (swap! reconciled conj repo'))]
      (p/let [result (literature/<connect-local-zotero!
                       repo binding-uuid "abcd1234")]
        (is (= :connected (:status result)))
        (is (= [[repo binding-uuid]] (mapv #(subvec % 0 2) @ensure-calls)))
        (is (string? (get-in @ensure-calls [0 2 :title])))
        (is (= destination-page-uuid (get-in result [:binding :destination-page-uuid])))
        (is (= [repo] @reconciled))
        (is (= {:kind :collection :key "ABCD1234"}
               (get-in (literature/load-settings repo)
                       [:zotero-binding :scope])))))))

(deftest-async zotero-disconnect-preserves-the-same-device-reconnect-identity
  (let [repo "synthetic-repo"
        binding-uuid #uuid "79000000-0000-4000-8000-000000000014"
        stored (atom {:ls-literature-settings {repo {:enabled? true}}})
        ensure-calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    zotero/<read-library-key! (fn [] (p/resolved "LOCAL001"))
                    storage/get (fn [key] (get @stored key))
                    storage/set (fn [key value] (swap! stored assoc key value))
                    db-async/<ensure-literature-source-binding
                    (fn [repo' binding-uuid' destination]
                      (swap! ensure-calls conj [repo' binding-uuid' destination])
                      (p/resolved {:status :existing
                                   :binding-uuid binding-uuid'
                                   :destination-page-uuid destination-page-uuid}))
                    literature/reconcile-zotero-lifecycle! (constantly nil)]
      (p/let [first-result (literature/<connect-local-zotero!
                                     repo binding-uuid "abcd1234")]
        (is (= :connected (:status first-result)))
        (let [connected (:zotero-binding (literature/load-settings repo))]
          (literature/set-zotero-binding! repo nil)
          (let [disconnected (literature/load-settings repo)]
            (is (nil? (:zotero-binding disconnected)))
            (is (= connected (:last-zotero-binding disconnected)))
            (p/let [second-result (literature/<connect-local-zotero!
                                           repo
                                           (:graph-binding-uuid
                                            (:last-zotero-binding disconnected))
                                           (get-in disconnected
                                                   [:last-zotero-binding
                                                    :scope :key]))]
              (is (= :connected (:status second-result)))
              (is (= connected (:zotero-binding
                                (literature/load-settings repo))))
              (is (= [[repo binding-uuid] [repo binding-uuid]]
                     (mapv #(subvec % 0 2) @ensure-calls)))
              (is (= {:page-uuid destination-page-uuid} (get-in @ensure-calls [1 2]))))))))))

(deftest-async local-zotero-connect-does-not-save-a-failed-binding
  (let [repo "synthetic-repo"
        binding-uuid #uuid "79000000-0000-4000-8000-000000000013"
        stored (atom {:ls-literature-settings {repo {:enabled? true}}})
        writes (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    zotero/<read-library-key! (fn [] (p/resolved "LOCAL001"))
                    storage/get (fn [key] (get @stored key))
                    storage/set (fn [_key _value] (swap! writes inc))
                    db-async/<ensure-literature-source-binding
                    (fn [_repo _binding-uuid _destination]
                      (p/resolved {:status :blocked
                                   :reason :binding-title-conflict}))]
      (p/let [result (literature/<connect-local-zotero!
                       repo binding-uuid nil)]
        (is (= {:status :blocked :reason :binding-title-conflict}
               result))
        (is (zero? @writes))
        (is (nil? (:zotero-binding (literature/load-settings repo))))))))

(deftest-async on-demand-zotero-import-reuses-the-current-binding-and-worker
  (let [repo "synthetic-repo"
        paper-uuid #uuid "79000000-0000-4000-8000-000000000010"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        stored (atom {:ls-literature-settings
                      {repo {:enabled? true
                             :zotero-binding binding}}})
        worker-calls (atom [])]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                    state/get-current-repo (constantly repo)
                    state/get-state (fn [key]
                                      (when (= :db/restoring? key) false))
                    zotero/<import-scope!
                    (fn [binding' still-authorized? register!]
                      (is (= binding binding'))
                      (is (true? (still-authorized?)))
                      (p/let [result (register!
                                     (:graph-binding-uuid binding)
                                     {:classification :bibliographic-item
                                      :source-key "zotero:local:79000000-0000-4000-8000-000000000002:item:AAAA1111"
                                      :title "Synthetic"})]
                        {:status :complete :worker-result result}))
                    zotero/<read-pdf-attachments!
                    (fn [_binding _source-key _still-authorized?]
                      (p/resolved {:status :ready :attachments []}))
                    db-async/<register-literature-source-record
                    (fn [repo' binding-uuid destination source-record]
                      (swap! worker-calls conj
                             [repo' binding-uuid destination source-record])
                      (p/resolved {:status :created :paper-uuid paper-uuid}))]
      (p/let [result (literature/<import-zotero-now! repo)]
        (is (= :complete (:status result)))
        (is (= :created (get-in result [:worker-result :status])))
        (is (= repo (ffirst @worker-calls)))
        (is (= destination-page-uuid (get-in @worker-calls [0 2])))
        (is (= (:graph-binding-uuid binding)
               (second (first @worker-calls))))))))

(defn- zotero-native-pdf-fixture
  []
  (let [repo "synthetic-zotero-pdf-repo"
        binding (assoc (literature/local-zotero-binding
                        #uuid "79000000-0000-4000-8000-000000000001" nil)
                       :destination-page-uuid destination-page-uuid
                       :zotero-library-key "LOCAL001")
        source-key "zotero:local:79000000-0000-4000-8000-000000000001:item:AAAA1111"]
    {:repo repo
     :binding binding
     :stored (atom {:ls-literature-settings {repo {:enabled? true :zotero-binding binding}}})
     :source-record {:classification :bibliographic-item :source-key source-key :title "Synthetic paper"}
     :context {:item-key "AAAA1111" :source-key source-key :kind :parent}
     :attachment {:item-key "BBBB2222" :parent-item-key "AAAA1111"
                  :filename "中文 paper#?%.pdf" :content-type "application/pdf"
                  :link-mode "linked_file" :file-url "file:///synthetic/zotero/%E4%B8%AD%E6%96%87%20paper%23%3F%25.pdf"}
     :source-path "/synthetic/zotero/中文 paper#?%.pdf"
     :repo-dir "/synthetic/graph"
     ;; The sentinels must not enter the File when a typed-array view is read.
     :payload (.subarray (js/Uint8Array. #js [99 37 80 68 70 45 49 46 55 10 88]) 1 10)
     :byte-values [37 80 68 70 45 49 46 55 10]
     :checksum "0716f9264c9fe19f5d7455276107f3ddcc1d3497f63d60689a73558ae8a1bf5e"
     :asset-uuid #uuid "79000000-0000-4000-8000-000000000020"
     :paper-uuid #uuid "79000000-0000-4000-8000-000000000021"
     :placement-uuid #uuid "79000000-0000-4000-8000-000000000022"}))

(defn- <assert-three-entrypoint-pdf-reuse!
  [{:keys [repo registered children-reads files reads source-path saved-path checksum content-checks links asset-uuid paper-uuid binding source-record readbacks placement-queries native-inserts writes byte-values]}]
  (p/let [results (p/loop [entries (seq [:now :refresh :notify]) results []]
                   (if-let [entry (first entries)]
                     (p/let [result (case entry
                                     :now (literature/<import-zotero-now! repo)
                                     :refresh (literature/<refresh-zotero-items! repo ["AAAA1111"])
                                     :notify (literature/<poll-zotero-notifications-once!
                                              repo {:session "synthetic-session" :cursor 7}))]
                       (p/recur (next entries) (conj results result)))
                     results))]
    (is (= [:complete :complete :complete] (mapv :status results)))
    (is (= 3 (count @registered)))
    (is (= 3 @children-reads) "Already imported sources still read current PDF children")
    (is (= 3 (count @files)))
    (is (= 3 (get (frequencies @reads) source-path 0)))
    (is (zero? (get (frequencies @reads) saved-path 0))
        "Existing file bytes are not copied into the renderer")
    (is (= (repeat 3 [saved-path 9 checksum]) @content-checks)
        "Existing file bytes are verified by the native streaming helper")
    (is (= 3 (count @links)))
    (doseq [[graph actual-asset actual-paper options] @links]
      (is (= [repo asset-uuid paper-uuid] [graph actual-asset actual-paper])
          "The source commit uses the existing Asset UUID")
      (is (true? (:automatic? options)))
      (is (= {:binding-uuid (:graph-binding-uuid binding)
              :source-key (:source-key source-record)}
             (dissoc (:source-context options) :reference-uuid :asset-block)))
      (is (= [asset-uuid "Supplementary Information"]
             ((juxt :block/uuid :block/title) (get-in options [:source-context :asset-block]))))
      (is (uuid? (get-in options [:source-context :reference-uuid]))))
    (is (>= (count (filter #(= [repo asset-uuid {:children? false}] %) @readbacks)) 3))
    (is (empty? @placement-queries) "Paper placement is part of the source commit")
    (is (zero? @native-inserts) "The renderer does not separately insert Paper attachments")
    (is (zero? @writes))
    (p/loop [remaining (seq @files)]
      (when-let [^js file (first remaining)]
        (is (instance? js/File file))
        (is (= "中文 paper#?%.pdf" (.-name file)))
        (is (= "application/pdf" (.-type file)))
        (p/let [buffer (.arrayBuffer file)]
          (is (= byte-values (js->clj (js/Array.from (js/Uint8Array. buffer)))))
          (p/recur (next remaining)))))))

(defn- <with-reusable-zotero-pdf!
  [{:keys [stored repo reviews repo-dir admit! children-reads binding source-record attachment registered paper-uuid source-path saved-path content-checks checksum reads payload files original-prepare native-asset writes readbacks asset-uuid links placement-queries placement-uuid native-inserts byte-values]}]
  (p/with-redefs [storage/get (fn [key] (get @stored key))
                 state/get-current-repo (constantly repo)
                 state/get-state (fn [path]
                                   (if (= :db/restoring? path) false
                                       (get-in @reviews (if (coll? path) path [path]))))
                 state/update-state! (fn [path f & args]
                                       (swap! reviews update-in path #(apply f % args)))
                 util/electron? (constantly true)
                 config/db-based-graph? (constantly true)
                 config/get-repo-dir (constantly repo-dir)
                 zotero/<import-scope! admit!
                 zotero/<import-item-keys! (fn [binding' _keys authorized? register!]
                                           (admit! binding' authorized? register!))
                 zotero/<process-notifier-cycle!
                 (fn [binding' notification-state authorized? register! _signal]
                   (p/let [result (admit! binding' authorized? register!)]
                     (assoc result :notification-state (assoc notification-state :cursor 8))))
                 zotero/<read-pdf-attachments!
                 (fn [binding' source-key authorized? & _]
                   (swap! children-reads inc)
                   (is (= binding binding'))
                   (is (= (:source-key source-record) source-key))
                   (is (true? (authorized?)))
                   (p/resolved {:status :ready :parent-item-key "AAAA1111" :attachments [attachment]}))
                 db-async/<register-literature-source-record
                 (fn [repo' binding-uuid destination record]
                   (swap! registered conj [repo' binding-uuid destination record])
                   (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                 ipc/ipc (fn [method path & [size actual-checksum]]
                           (is (contains? #{source-path saved-path} path))
                           (case method
                             :stat (p/resolved {:size 9})
                             :fileContentMatches
                             (do
                               (swap! content-checks conj [path size actual-checksum])
                               (p/resolved (= [saved-path 9 checksum]
                                              [path size actual-checksum])))
                             :readFileRaw (do (swap! reads conj path) (p/resolved payload))
                             (throw (ex-info "Unexpected native request" {:method method}))))
                 assets/prepare-asset-block (fn [repo' file opts]
                                              (swap! files conj (if (map? file) (:src file) file))
                                              (original-prepare repo' file opts))
                 assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                 db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                 db-async/<find-reusable-pdf-by-checksum
                 (fn [repo' actual-checksum]
                   (is (= [repo checksum] [repo' actual-checksum]))
                   (p/resolved {:status :found :literature-status :found
                                :assets [(select-keys native-asset [:block/uuid :block/title
                                                                   :logseq.property.asset/size
                                                                   :logseq.property.asset/checksum])]}))
                 fs/file-exists? (fn [& _] (p/resolved true))
                 fs/write-asset-file! (fn [& _] (swap! writes inc) (p/resolved nil))
                 db-async/<get-block
                 (fn [repo' uuid opts]
                   (swap! readbacks conj [repo' uuid opts])
                   (p/resolved (if (= uuid asset-uuid) native-asset
                                   {:db/id 102 :block/uuid uuid :block/title "Target"})))
                 db-async/<link-pdf-to-literature
                 (fn [repo' actual-asset-uuid actual-paper-uuid options]
                   (swap! links conj [repo' actual-asset-uuid actual-paper-uuid options])
                   (p/resolved {:status :already-linked :paper {:block/uuid paper-uuid}}))
                 db-async/<q (fn [repo' _opts _query & params]
                               (swap! placement-queries conj [repo' params])
                               (p/resolved placement-uuid))
                 state/<invoke-db-worker (fn [& _]
                                           (swap! native-inserts inc)
                                           (p/resolved {:result :synthetic-insert-complete}))]
    (<assert-three-entrypoint-pdf-reuse! {:repo repo :registered registered :children-reads children-reads
      :files files :reads reads :source-path source-path :saved-path saved-path :checksum checksum
      :content-checks content-checks :links links :asset-uuid asset-uuid :paper-uuid paper-uuid
      :binding binding :source-record source-record :readbacks readbacks :placement-queries placement-queries
      :native-inserts native-inserts :writes writes :byte-values byte-values})))

(deftest-async zotero-three-entrypoints-read-already-imported-pdf-and-reuse-its-native-asset
  (let [{:keys [repo binding stored source-record context attachment source-path repo-dir
                payload byte-values checksum asset-uuid paper-uuid placement-uuid]}
        (update (zotero-native-pdf-fixture) :attachment assoc :title "Supplementary Information")
        saved-path (str repo-dir "/assets/" asset-uuid ".pdf")
        native-asset {:db/id 101 :block/uuid asset-uuid :block/title "Existing annotated PDF"
                      :block/parent {:block/uuid destination-page-uuid}
                      :block/tags [{:db/ident :logseq.class/Asset}]
                      :logseq.property.asset/type "pdf" :logseq.property.asset/size 9
                      :logseq.property.asset/checksum checksum}
        reviews (atom {:literature/review-candidates {repo {}}})
        original-prepare assets/prepare-asset-block
        files (atom [])
        reads (atom [])
        content-checks (atom [])
        children-reads (atom 0)
        registered (atom [])
        links (atom [])
        readbacks (atom [])
        placement-queries (atom [])
        native-inserts (atom 0)
        writes (atom 0)
        admit! (fn [binding' still-authorized? register!]
                 (is (= binding binding'))
                 (is (true? (still-authorized?)))
                 (p/let [result (register! (:graph-binding-uuid binding) source-record context)]
                   {:status :complete :worker-result result}))]
    (<with-reusable-zotero-pdf! {:stored stored :repo repo :reviews reviews :repo-dir repo-dir :admit! admit!
      :children-reads children-reads :binding binding :source-record source-record :attachment attachment
      :registered registered :paper-uuid paper-uuid :source-path source-path :saved-path saved-path
      :content-checks content-checks :checksum checksum :reads reads :payload payload :files files
      :original-prepare original-prepare :native-asset native-asset :writes writes :readbacks readbacks
      :asset-uuid asset-uuid :links links :placement-queries placement-queries :placement-uuid placement-uuid
      :native-inserts native-inserts :byte-values byte-values})))

(deftest-async zotero-kept-pdf-skips-automatic-placement-and-advances-notifier-cursor
  (let [{:keys [repo binding stored source-record context attachment source-path repo-dir
                payload checksum asset-uuid paper-uuid]}
        (zotero-native-pdf-fixture)
        saved-path (str repo-dir "/assets/" asset-uuid ".pdf")
        native-asset {:db/id 101 :block/uuid asset-uuid :block/title "Ordinary PDF"
                      :block/parent {:block/uuid destination-page-uuid}
                      :block/tags [{:db/ident :logseq.class/Asset}]
                      :logseq.property.asset/type "pdf" :logseq.property.asset/size 9
                      :logseq.property.asset/checksum checksum}
        notification-state {:session "synthetic-session" :cursor 7}
        link-result (atom {:status :blocked :reason :automatic-excluded})
        links (atom [])
        notifier-requests (atom [])
        placement-queries (atom 0)
        inserts (atom 0)
        writes (atom 0)
        unlinks (atom 0)
        admit! (fn [binding' authorized? register!]
                 (is (= binding binding'))
                 (is (true? (authorized?)))
                 (p/let [result (register! (:graph-binding-uuid binding) source-record context)]
                   {:status :complete :worker-result result}))]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                   state/get-current-repo (constantly repo)
                   state/get-state (constantly false)
                   util/electron? (constantly true)
                   config/db-based-graph? (constantly true)
                   config/get-repo-dir (constantly repo-dir)
                   zotero/<import-scope! admit!
                   zotero/<import-item-keys! (fn [binding' _keys authorized? register!]
                                             (admit! binding' authorized? register!))
                   zotero/<notifier-request!
                   (fn [current-state & _]
                     (swap! notifier-requests conj current-state)
                     (p/resolved {:session "synthetic-session" :cursor 8
                                  :rescan false :zotero-library-key "LOCAL001" :keys ["AAAA1111"]}))
                   zotero/<read-pdf-attachments!
                   (fn [_binding _source-key authorized? & _]
                     (is (true? (authorized?)))
                     (p/resolved {:status :ready :attachments [attachment]}))
                   db-async/<register-literature-source-record
                   (fn [& _] (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                   ipc/ipc (fn [method path & _]
                             (is (contains? #{source-path saved-path} path))
                             (case method
                               :stat (p/resolved {:size 9})
                               :fileContentMatches (p/resolved true)
                               :readFileRaw (p/resolved payload)
                               (throw (ex-info "Unexpected native request" {:method method}))))
                   assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                   db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                   db-async/<find-reusable-pdf-by-checksum
                   (fn [& _] (p/resolved {:status :found :literature-status :missing
                                         :assets [native-asset]}))
                   db-async/<get-block (fn [_ actual-uuid _]
                                        (p/resolved (when (= asset-uuid actual-uuid) native-asset)))
                   db-async/<link-pdf-to-literature
                   (fn [repo' actual-asset actual-paper options]
                     (swap! links conj [repo' actual-asset actual-paper options])
                     (p/resolved @link-result))
                   db-async/<q (fn [& _] (swap! placement-queries inc) (p/resolved nil))
                   state/<invoke-db-worker (fn [& _] (swap! inserts inc)
                                             (p/resolved {:result :synthetic-insert-complete}))
                   fs/write-asset-file! (fn [& _] (swap! writes inc) (p/resolved nil))
                   fs/unlink! (fn [& _] (swap! unlinks inc) (p/resolved nil))]
      (p/let [imported (literature/<import-zotero-now! repo)
              refreshed (literature/<refresh-zotero-items! repo ["AAAA1111"])
              notified (literature/<poll-zotero-notifications-once! repo notification-state)]
        (is (= [:complete :complete :complete] (mapv :status [imported refreshed notified])))
        (is (= {:session "synthetic-session" :cursor 8} (:notification-state notified))
            "The real notifier cycle accepts a completed import that honors Keep")
        (is (= 3 (count @links)))
        (doseq [[graph actual-asset actual-paper options] @links]
          (is (= [repo asset-uuid paper-uuid] [graph actual-asset actual-paper]))
          (is (true? (:automatic? options)))
          (is (= {:binding-uuid (:graph-binding-uuid binding)
                  :source-key (:source-key source-record)}
                 (dissoc (:source-context options) :reference-uuid :asset-block)))
          (is (uuid? (get-in options [:source-context :reference-uuid]))))
        (is (zero? @placement-queries) "Keep cannot add a linked reference under the Paper")
        (is (zero? @inserts))
        (is (zero? @writes))
        (is (zero? @unlinks))
        (reset! link-result {:status :blocked :reason :target-paper-unavailable})
        (p/let [failure (-> (literature/<poll-zotero-notifications-once! repo notification-state)
                           (p/then (fn [result] {:fulfilled? true :result result}))
                           (p/catch (fn [error] {:fulfilled? false :error error})))]
          (is (false? (:fulfilled? failure)) "Other link failures still prevent notifier ACK")
          (is (= :literature-zotero/attachment-link-failed
                 (some-> failure :error ex-data :type)))
          (is (= [notification-state notification-state] @notifier-requests))
          (is (zero? @placement-queries))
          (is (zero? @inserts))
          (is (zero? @unlinks)))))))

(deftest-async zotero-pdf-write-failure-rejects-the-notifier-cycle-without-ack
  (let [{:keys [repo stored source-record attachment source-path repo-dir payload paper-uuid]}
        (zotero-native-pdf-fixture)
        notification-state {:session "synthetic-session" :cursor 7}
        failure (ex-info "Synthetic native asset write failed" {:type :synthetic/write-failed})
        writes (atom 0)
        inserts (atom 0)
        links (atom 0)]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                   state/get-current-repo (constantly repo)
                   state/get-state (constantly false)
                   util/electron? (constantly true)
                   config/db-based-graph? (constantly true)
                   config/get-repo-dir (constantly repo-dir)
                   zotero/<notifier-request! (fn [& _]
                                              (p/resolved {:session "synthetic-session" :cursor 8
                                                           :rescan false :zotero-library-key "LOCAL001" :keys ["AAAA1111"]}))
                   zotero/<local-api-request!
                   (fn [& _]
                     (p/resolved (js/JSON.stringify
                                  (clj->js [{:key "AAAA1111" :library {:type "user" :id 0}
                                             :data {:key "AAAA1111" :itemType "journalArticle"
                                                    :title (:title source-record) :creators []}}]))))
                   zotero/<read-pdf-attachments! (fn [& _]
                                                 (p/resolved {:status :ready :parent-item-key "AAAA1111"
                                                              :attachments [attachment]}))
                   db-async/<register-literature-source-record
                   (fn [& _] (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                   ipc/ipc (fn [method path & _]
                             (is (= source-path path))
                             (case method :stat (p/resolved {:size 9})
                                   :fileContentMatches (p/resolved true)
                                   :readFileRaw (p/resolved payload)
                                   (throw (ex-info "Unexpected native request" {:method method}))))
                   assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                   db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                   db-async/<find-reusable-pdf-by-checksum (fn [& _]
                                                          (p/resolved {:status :missing :assets []}))
                   db-async/<get-block (fn [_ uuid _]
                                        (p/resolved {:db/id 102 :block/uuid uuid :block/title "Target"}))
                   fs/write-asset-file! (fn [& _] (swap! writes inc) (p/rejected failure))
                   state/<invoke-db-worker (fn [& _] (swap! inserts inc)
                                             (p/resolved {:result :synthetic-insert-complete}))
                   db-async/<link-pdf-to-literature (fn [& _] (swap! links inc)
                                                   (p/resolved {:status :linked}))]
      (p/let [outcome (-> (literature/<poll-zotero-notifications-once! repo notification-state)
                         (p/then (fn [result] {:fulfilled? true :result result}))
                         (p/catch (fn [error] {:fulfilled? false :error error})))]
        (is (false? (:fulfilled? outcome)) "A failed asset write cannot complete or return an advanced cursor")
        (is (= :synthetic/write-failed (some-> outcome :error ex-data :type)))
        (is (= 1 @writes))
        (is (zero? @inserts))
        (is (zero? @links))
        (is (= {:session "synthetic-session" :cursor 7} notification-state))))))

(deftest-async zotero-in-flight-pdf-read-stops-writing-after-binding-closes
  (let [{:keys [repo stored source-record attachment source-path repo-dir payload paper-uuid]}
        (zotero-native-pdf-fixture)
        read-started-resolve (atom nil)
        read-resolve (atom nil)
        read-started (js/Promise. (fn [resolve _] (reset! read-started-resolve resolve)))
        waiting-read (js/Promise. (fn [resolve _] (reset! read-resolve resolve)))
        writes (atom 0)
        inserts (atom 0)
        links (atom 0)]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                   state/get-current-repo (constantly repo)
                   state/get-state (constantly false)
                   util/electron? (constantly true)
                   config/db-based-graph? (constantly true)
                   config/get-repo-dir (constantly repo-dir)
                   zotero/<notifier-request! (fn [& _]
                                              (p/resolved {:session "synthetic-session" :cursor 8
                                                           :rescan false :zotero-library-key "LOCAL001" :keys ["AAAA1111"]}))
                   zotero/<local-api-request!
                   (fn [& _]
                     (p/resolved (js/JSON.stringify
                                  (clj->js [{:key "AAAA1111" :library {:type "user" :id 0}
                                             :data {:key "AAAA1111" :itemType "journalArticle"
                                                    :title (:title source-record) :creators []}}]))))
                   zotero/<read-pdf-attachments! (fn [& _]
                                                 (p/resolved {:status :ready :parent-item-key "AAAA1111"
                                                              :attachments [attachment]}))
                   db-async/<register-literature-source-record
                   (fn [& _] (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                   ipc/ipc (fn [method path & _]
                             (is (= source-path path))
                             (case method
                               :stat (p/resolved {:size 9})
                               :fileContentMatches (p/resolved true)
                               :readFileRaw (do (@read-started-resolve :read-started) waiting-read)
                               (throw (ex-info "Unexpected native request" {:method method}))))
                   assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                   db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                   db-async/<find-reusable-pdf-by-checksum (fn [& _]
                                                          (p/resolved {:status :missing :assets []}))
                   db-async/<get-block (fn [_ uuid _]
                                        (p/resolved {:db/id 102 :block/uuid uuid :block/title "Target"}))
                   fs/write-asset-file! (fn [& _] (swap! writes inc) (p/resolved nil))
                   state/<invoke-db-worker (fn [& _] (swap! inserts inc)
                                             (p/resolved {:result :synthetic-insert-complete}))
                   db-async/<link-pdf-to-literature (fn [& _] (swap! links inc)
                                                   (p/resolved {:status :linked}))]
      (let [pending (-> (literature/<poll-zotero-notifications-once!
                         repo {:session "synthetic-session" :cursor 7})
                        (p/catch (fn [error] {:status :rejected :error error})))]
        (p/let [first-event (p/race [read-started (p/then pending (constantly :finished-before-read))])]
          (is (= :read-started first-event))
          (swap! stored assoc-in [:ls-literature-settings repo :enabled?] false)
          (@read-resolve payload)
          (p/let [result pending]
            (is (contains? #{:cancelled :rejected} (:status result)))
            (is (not= 8 (get-in result [:notification-state :cursor])))
            (is (zero? @writes))
            (is (zero? @inserts))
            (is (zero? @links))))))))

(deftest-async zotero-nil-metadata-saves-a-native-pdf-for-manual-review
  (let [{:keys [repo binding stored context attachment repo-dir payload byte-values]}
        (zotero-native-pdf-fixture)
        standalone-context (assoc context :kind :standalone)
        standalone-attachment (assoc attachment :item-key "AAAA1111" :parent-item-key nil
                                     :filename "standalone" :file-url "file:///synthetic/zotero/standalone")
        physical-path "/synthetic/zotero/standalone"
        original-prepare assets/prepare-asset-block
        prepared (atom nil)
        native-blocks (atom {})
        readbacks (atom [])
        file (atom nil)
        reads (atom [])
        writes (atom [])
        registered (atom 0)
        links (atom 0)
        reviews (atom {:literature/review-candidates {repo {}}})]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                   state/get-current-repo (constantly repo)
                   state/get-state (fn [path]
                                     (if (= :db/restoring? path) false
                                         (get-in @reviews (if (coll? path) path [path]))))
                   state/set-state! (fn [path value & _] (swap! reviews assoc-in path value))
                   state/update-state! (fn [path f & args]
                                         (swap! reviews update-in path #(apply f % args)))
                   util/electron? (constantly true)
                   config/db-based-graph? (constantly true)
                   config/get-repo-dir (constantly repo-dir)
                   zotero/<import-scope!
                   (fn [_ _ register!]
                     (p/let [result (register! (:graph-binding-uuid binding) nil standalone-context)]
                       {:status :complete :worker-result result}))
                   zotero/<read-pdf-attachments! (fn [& _]
                                                 (p/resolved {:status :ready :attachments [standalone-attachment]}))
                   db-async/<register-literature-source-record
                   (fn [& _] (swap! registered inc) (p/resolved {:status :manual}))
                   ipc/ipc (fn [method path & _]
                             (is (= physical-path path) "The temporary .pdf suffix must not change the physical read path")
                             (case method :stat (p/resolved {:size 9})
                                   :fileContentMatches (p/resolved true)
                                   :readFileRaw (do (swap! reads conj path) (p/resolved payload))
                                   (throw (ex-info "Unexpected native request" {:method method}))))
                   assets/prepare-asset-block
                   (fn [repo' actual-file opts]
                     (reset! file actual-file)
                     (p/let [result (original-prepare repo' actual-file opts)]
                       (reset! prepared result)
                       result))
                   assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                   db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                   db-async/<find-reusable-pdf-by-checksum (fn [& _]
                                                          (p/resolved {:status :missing :assets []}))
                   fs/write-asset-file! (fn [repo' file-path buffer]
                                          (swap! writes conj [repo' file-path buffer])
                                          (p/resolved nil))
                   state/<invoke-db-worker
                   (fn [api repo' ops _opts]
                     (is (= :thread-api/apply-outliner-ops api))
                     (is (= repo repo'))
                     (doseq [[op [blocks target _]] ops]
                       (is (= :insert-blocks op))
                       (doseq [block blocks]
                         (swap! native-blocks assoc (:block/uuid block)
                                (assoc block :db/id 103 :block/parent {:block/uuid target}
                                             :block/tags [{:db/ident :logseq.class/Asset}]))))
                     (p/resolved {:result :synthetic-insert-complete}))
                   db-async/<get-block
                   (fn [repo' uuid opts]
                     (swap! readbacks conj [repo' uuid opts])
                     (p/resolved (or (get @native-blocks uuid)
                                     (when (= uuid destination-page-uuid)
                                       {:db/id 102 :block/uuid uuid :block/title "Destination"}))))
                   db-async/<q (fn [& _] (p/resolved nil))
                   db-async/<link-pdf-to-literature (fn [& _] (swap! links inc)
                                                   (p/resolved {:status :linked}))]
      (p/let [result (literature/<import-zotero-now! repo)]
        (is (= :complete (:status result)))
        (is (zero? @registered) "Missing metadata must not register a fabricated Paper")
        (is (zero? @links))
        (is (= [physical-path] @reads))
        (is (= 1 (count @writes)))
        (is (= 1 (count @native-blocks)))
        (when-let [actual-asset (first (vals @native-blocks))]
          (let [actual-uuid (:block/uuid actual-asset)]
            (is (= "pdf" (:logseq.property.asset/type actual-asset)))
            (is (= "standalone" (:block/title actual-asset)))
            (is (= (get-in @prepared [:asset-block :block/uuid]) actual-uuid))
            (is (= [repo (str actual-uuid ".pdf")] (subvec (first @writes) 0 2)))
            (is (some #(= [repo actual-uuid {:children? false}] %) @readbacks)
                "The Asset must be read back by its UUID rather than taken from the insert envelope")
            (is (= actual-uuid (get-in @reviews [:literature/review-candidates repo (str actual-uuid) :asset-uuid])))
            (is (= :manual (get-in @reviews [:literature/review-candidates repo (str actual-uuid) :extraction :reason])))))
        (when-let [^js actual-file @file]
          (is (instance? js/File actual-file))
          (is (= "standalone.pdf" (.-name actual-file)))
          (is (= "application/pdf" (.-type actual-file)))
          (p/let [buffer (.arrayBuffer actual-file)]
            (is (= byte-values (js->clj (js/Array.from (js/Uint8Array. buffer)))))
            (is (= byte-values (js->clj (js/Array.from (js/Uint8Array. (nth (first @writes) 2))))))))))))

(defn- <assert-uncommitted-zotero-recycling!
  [{:keys [mode active-files readbacks unlinks repo notification-state write-failure writes prepared recycled-files notifier-requests inserts cleanup-failure cleanup-warnings links]}]
  (p/let [_ (p/loop [remaining (seq [{:entry :now} {:entry :refresh} {:entry :notify} {:entry :notify}
                           {:entry :refresh :cleanup-reject? true}
                           {:entry :refresh :write-reject? true}])]
    (when-let [scenario (first remaining)]
      (reset! mode scenario)
      (let [files-before @active-files
            reads-before (count @readbacks)
            cleanup-before (count @unlinks)]
        (p/let [outcome (-> (case (:entry scenario)
                             :now (literature/<import-zotero-now! repo)
                             :refresh (literature/<refresh-zotero-items! repo ["AAAA1111"])
                             :notify (literature/<poll-zotero-notifications-once! repo notification-state))
                           (p/then (fn [value] {:fulfilled? true :value value}))
                           (p/catch (fn [error] {:fulfilled? false :error error})))]
          (is (false? (:fulfilled? outcome)) "Failed imports cannot fulfill with a next notifier cursor")
          (if (:write-reject? scenario)
            (do
              (is (identical? write-failure (:error outcome)))
              (is (= reads-before (count @readbacks)) "A failed write adds no lookup")
              (is (= cleanup-before (count @unlinks)) "A failed write adds no recycle")
              (is (= files-before @active-files)))
            (let [written (last @writes)
                  actual-uuid (get-in @prepared [:asset-block :block/uuid])]
              (is (= :literature-zotero/attachment-insert-failed (some-> outcome :error ex-data :type)))
              (is (= "Zotero attachment was not inserted at its destination" (.-message (:error outcome)))
                  "Cleanup failure must preserve the insertion error")
              (is (= (str actual-uuid ".pdf") (:filename written)))
              (is (= written (last @unlinks)))
              (if (:cleanup-reject? scenario)
                (is (= written (get @active-files (:path written))))
                (do
                  (is (= files-before @active-files) "Retry leaves no new PDF in active assets")
                  (is (= written (last @recycled-files))
                      "Existing unlink semantics preserve bytes in recycle, rather than erase the disk")))))
          (p/recur (next remaining))))))]
    (is (= [notification-state notification-state] @notifier-requests)
        "Both failed notifier attempts ACK the original cursor, never the response cursor")
    (is (= 5 (count @inserts)))
    (is (= 5 (count @unlinks)))
    (is (= 4 (count @recycled-files)))
    (is (= [cleanup-failure] @cleanup-warnings)
        "Successful recycle must not report the original insert error as a cleanup failure")
    (is (= 6 (count (set (map :filename @writes)))) "Each retry owns a different new UUID file")
    (is (zero? @links))))

(defn- <with-uncommitted-zotero-pdfs!
  [{:keys [stored repo repo-dir cleanup-warnings admit! notifier-requests standalone-attachment source-path payload original-prepare prepared checksum byte-values writes mode write-failure active-files inserts readbacks unlinks cleanup-failure recycled-files links notification-state]}]
  (p/with-redefs [storage/get (fn [key] (get @stored key))
                 state/get-current-repo (constantly repo)
                 state/get-state (constantly false)
                 util/electron? (constantly true)
                 config/db-based-graph? (constantly true)
                 config/get-repo-dir (constantly repo-dir)
                 log/log (fn [_ _ data _]
                           (when-let [error (:literature-zotero/asset-cleanup-failed data)]
                             (swap! cleanup-warnings conj error))
                           nil)
                 zotero/<import-scope! admit!
                 zotero/<import-item-keys! (fn [binding' _keys authorized? register!]
                                           (admit! binding' authorized? register!))
                 zotero/<notifier-request!
                 (fn [current-state & _]
                   (swap! notifier-requests conj current-state)
                   (p/resolved {:session "synthetic-session" :cursor 8
                                :rescan false :zotero-library-key "LOCAL001" :keys ["AAAA1111"]}))
                 zotero/<read-pdf-attachments! (fn [& _]
                                               (p/resolved {:status :ready :attachments [standalone-attachment]}))
                 ipc/ipc (fn [method path & _]
                           (is (= source-path path))
                           (case method :stat (p/resolved {:size 9})
                                 :fileContentMatches (p/resolved true)
                                 :readFileRaw (p/resolved payload)
                                 (throw (ex-info "Unexpected native request" {:method method}))))
                 assets/prepare-asset-block
                 (fn [repo' file opts]
                   (p/let [candidate (original-prepare repo' file opts)]
                     (reset! prepared candidate)
                     candidate))
                 assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                 db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                 db-async/<find-reusable-pdf-by-checksum (fn [& _]
                                                        (p/resolved {:status :missing :assets []}))
                 fs/write-asset-file!
                 (fn [repo' filename buffer]
                   (p/let [actual-checksum (db-asset/<get-file-array-buffer-checksum buffer)]
                     (let [file {:repo repo' :filename filename :path (str repo-dir "/assets/" filename)
                                 :checksum actual-checksum
                                 :payload (js->clj (js/Array.from (assets/->uint8 buffer)))}]
                       (is (= [repo checksum byte-values] [repo' actual-checksum (:payload file)]))
                       (swap! writes conj file)
                       (if (:write-reject? @mode)
                         (p/rejected write-failure)
                         (do (swap! active-files assoc (:path file) file)
                             (p/resolved nil))))))
                 state/<invoke-db-worker
                 (fn [api repo' ops _opts]
                   (is (= [:thread-api/apply-outliner-ops repo] [api repo']))
                   (let [[op [[block] target _]] (first ops)]
                     (is (= :insert-blocks op))
                     (is (= destination-page-uuid target))
                     (swap! inserts conj (:block/uuid block)))
                   (p/resolved {:result nil}))
                 db-async/<get-block
                 (fn [repo' actual-uuid opts]
                   (swap! readbacks conj [repo' actual-uuid opts])
                   (js/Promise.resolve nil))
                 fs/unlink!
                 (fn [repo' filepath _opts]
                   (let [file (get @active-files filepath)]
                     (is (= repo repo'))
                     (is (some? file) "Cleanup addresses exactly a file written by this import")
                     (is (= [repo (get-in @prepared [:asset-block :block/uuid]) {:children? false}]
                            (last @readbacks))
                         "The current UUID is checked before its owned file is recycled")
                     (swap! unlinks conj file)
                     (if (:cleanup-reject? @mode)
                       (p/rejected cleanup-failure)
                       (do (swap! active-files dissoc filepath)
                           (swap! recycled-files conj file)
                           (p/resolved nil)))))
                 db-async/<link-pdf-to-literature (fn [& _] (swap! links inc)
                                                 (p/resolved {:status :linked}))]
    (<assert-uncommitted-zotero-recycling! {:mode mode :active-files active-files :readbacks readbacks
      :unlinks unlinks :repo repo :notification-state notification-state :write-failure write-failure
      :writes writes :prepared prepared :recycled-files recycled-files :notifier-requests notifier-requests
      :inserts inserts :cleanup-failure cleanup-failure :cleanup-warnings cleanup-warnings :links links})))

(deftest-async zotero-uncommitted-new-pdf-is-recycled-before-retry-and-notifier-ack
  (let [{:keys [repo binding stored context attachment source-path repo-dir payload byte-values checksum]}
        (zotero-native-pdf-fixture)
        standalone-context (assoc context :kind :standalone)
        standalone-attachment (assoc attachment :item-key "AAAA1111" :parent-item-key nil
                                     :link-mode "imported_file")
        notification-state {:session "synthetic-session" :cursor 7}
        original-prepare assets/prepare-asset-block
        prepared (atom nil)
        mode (atom {})
        active-files (atom {})
        recycled-files (atom [])
        writes (atom [])
        unlinks (atom [])
        readbacks (atom [])
        inserts (atom [])
        links (atom 0)
        notifier-requests (atom [])
        cleanup-warnings (atom [])
        cleanup-failure (ex-info "Synthetic asset recycle failed" {:type :synthetic/recycle-failed})
        write-failure (ex-info "Synthetic asset write failed" {:type :synthetic/write-failed})
        admit! (fn [_ authorized? register!]
                 (is (true? (authorized?)))
                 (p/let [result (register! (:graph-binding-uuid binding) nil standalone-context)]
                   {:status :complete :worker-result result}))]
    (<with-uncommitted-zotero-pdfs! {:stored stored :repo repo :repo-dir repo-dir :cleanup-warnings cleanup-warnings
      :admit! admit! :notifier-requests notifier-requests :standalone-attachment standalone-attachment
      :source-path source-path :payload payload :original-prepare original-prepare :prepared prepared
      :checksum checksum :byte-values byte-values :writes writes :mode mode :write-failure write-failure
      :active-files active-files :inserts inserts :readbacks readbacks :unlinks unlinks
      :cleanup-failure cleanup-failure :recycled-files recycled-files :links links
      :notification-state notification-state})))

(defn- <assert-rejected-zotero-commits!
  [{:keys [mode stored initial-stored files committed readbacks unlinks renderer-inserts source-commits repo notification-state readback-failure failure notifier-requests prepared repo-dir byte-values checksum]}]
  (p/loop [remaining (seq [{:flow :standalone :state :committed}
                          {:flow :standalone :state :lookup-rejected}
                          {:flow :paper :state :uncommitted}
                          {:flow :paper :state :committed}
                          {:flow :paper :state :lookup-rejected}
                          {:flow :paper :state :readback-rejected}
                          {:flow :paper :state :binding-closed}])]
    (when-let [scenario (first remaining)]
      (reset! mode scenario)
      (reset! stored initial-stored)
      (reset! files {})
      (reset! committed {})
      (reset! readbacks [])
      (reset! unlinks [])
      (reset! renderer-inserts 0)
      (reset! source-commits 0)
      (p/let [outcome (-> (literature/<poll-zotero-notifications-once! repo notification-state)
                         (p/then (fn [value] {:fulfilled? true :value value}))
                         (p/catch (fn [error] {:fulfilled? false :error error})))]
        (if (= :binding-closed (:state scenario))
          (do
            (is (true? (:fulfilled? outcome)))
            (is (= :cancelled (get-in outcome [:value :status])))
            (is (nil? (get-in outcome [:value :notification-state]))
                "Disabling after the write cannot ACK the response cursor"))
          (do
            (is (false? (:fulfilled? outcome)))
            (is (identical? (if (= :readback-rejected (:state scenario)) readback-failure failure)
                            (:error outcome))
                "The original error survives the current-UUID cleanup check")))
        (is (= (repeat (count @notifier-requests) notification-state) @notifier-requests)
            "A rejected import never ACKs the response cursor")
        (is (= (if (and (= :paper (:flow scenario))
                        (not= :binding-closed (:state scenario))) 1 0)
               @source-commits))
        (is (= (if (= :standalone (:flow scenario)) 1 0) @renderer-inserts))
        (let [actual-uuid (get-in @prepared [:asset-block :block/uuid])
              filepath (str repo-dir "/assets/" actual-uuid ".pdf")]
          (is (= [repo actual-uuid {:children? false}] (last @readbacks)))
          (if (contains? #{:uncommitted :binding-closed} (:state scenario))
            (do
              (is (empty? @files))
              (is (= [filepath] @unlinks)))
            (do
              (is (= {filepath {:payload byte-values :checksum checksum}} @files)
                  "Committed or unconfirmed UUIDs keep their exact written bytes")
              (is (empty? @unlinks))))
          (when (contains? #{:committed :readback-rejected} (:state scenario))
            (is (= actual-uuid (:block/uuid (get @committed actual-uuid))))))
        (p/recur (next remaining))))))

(defn- <with-rejected-zotero-commits!
  [{:keys [stored repo repo-dir binding mode source-record context standalone-context notifier-requests paper-uuid attachment source-path payload original-prepare prepared files renderer-inserts committed failure source-commits readbacks lookup-failure readback-failure unlinks initial-stored notification-state byte-values checksum]}]
  (p/with-redefs [storage/get (fn [key] (get @stored key))
                 state/get-current-repo (constantly repo)
                 state/get-state (constantly false)
                 util/electron? (constantly true)
                 config/db-based-graph? (constantly true)
                 config/get-repo-dir (constantly repo-dir)
                 zotero/<import-scope!
                 (fn [_ _ register!]
                   (p/let [result (register! (:graph-binding-uuid binding)
                                            (when (= :paper (:flow @mode)) source-record)
                                            (if (= :paper (:flow @mode)) context standalone-context))]
                     {:status :complete :worker-result result}))
                 zotero/<import-item-keys!
                 (fn [binding' _keys authorized? register!]
                   (zotero/<import-scope! binding' authorized? register!))
                 zotero/<notifier-request!
                 (fn [current-state & _]
                   (swap! notifier-requests conj current-state)
                   (p/resolved {:session "synthetic-session" :cursor 8
                                :rescan false :zotero-library-key "LOCAL001" :keys ["AAAA1111"]}))
                 db-async/<register-literature-source-record
                 (fn [& _] (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                 zotero/<read-pdf-attachments! (fn [& _]
                                               (p/resolved {:status :ready :attachments [attachment]}))
                 ipc/ipc (fn [method path & _]
                           (is (= source-path path))
                           (case method :stat (p/resolved {:size 9})
                                 :fileContentMatches (p/resolved true)
                                 :readFileRaw (p/resolved payload)
                                 (throw (ex-info "Unexpected native request" {:method method}))))
                 assets/prepare-asset-block (fn [repo' file opts]
                                              (p/let [candidate (original-prepare repo' file opts)]
                                                (reset! prepared candidate)
                                                candidate))
                 assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                 db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                 db-async/<find-reusable-pdf-by-checksum (fn [& _]
                                                        (p/resolved {:status :missing :assets []}))
                 fs/write-asset-file!
                 (fn [repo' filename buffer]
                   (p/let [actual-checksum (db-asset/<get-file-array-buffer-checksum buffer)]
                     (is (= repo repo'))
                     (swap! files assoc (str repo-dir "/assets/" filename)
                            {:payload (js->clj (js/Array.from (assets/->uint8 buffer)))
                             :checksum actual-checksum})
                     (when (= :binding-closed (:state @mode))
                       (swap! stored update-in [:ls-literature-settings repo] dissoc :zotero-binding))))
                 state/<invoke-db-worker
                 (fn [_ _ ops _opts]
                   (swap! renderer-inserts inc)
                   (when (= :committed (:state @mode))
                     (let [[_ [[block] target _]] (first ops)]
                       (swap! committed assoc (:block/uuid block)
                              (assoc block :db/id 103 :block/parent {:block/uuid target}
                                           :block/tags [{:db/ident :logseq.class/Asset}]))))
                   (p/rejected failure))
                 db-async/<link-pdf-to-literature
                 (fn [graph actual-uuid target options]
                   (swap! source-commits inc)
                   (is (= [repo paper-uuid] [graph target]))
                   (is (true? (:automatic? options)))
                   (is (= {:binding-uuid (:graph-binding-uuid binding)
                           :source-key (:source-key source-record)}
                          (select-keys (:source-context options) [:binding-uuid :source-key])))
                   (is (uuid? (get-in options [:source-context :reference-uuid])))
                   (is (= actual-uuid (get-in options [:source-context :asset-block :block/uuid])))
                   (when (contains? #{:committed :readback-rejected} (:state @mode))
                     (swap! committed assoc actual-uuid
                            (assoc (get-in options [:source-context :asset-block])
                                   :block/parent {:block/uuid target}
                                   :block/tags [{:db/ident :logseq.class/Asset}])))
                   (if (= :readback-rejected (:state @mode))
                     (p/resolved {:status :linked})
                     (p/rejected failure)))
                 db-async/<get-block
                 (fn [repo' actual-uuid opts]
                   (swap! readbacks conj [repo' actual-uuid opts])
                   (case (:state @mode)
                     :lookup-rejected (p/rejected lookup-failure)
                     :readback-rejected (if (= 1 (count @readbacks))
                                          (p/rejected readback-failure)
                                          (p/resolved (get @committed actual-uuid)))
                     (p/resolved (get @committed actual-uuid))))
                 fs/unlink! (fn [_ filepath _]
                              (swap! unlinks conj filepath)
                              (swap! files dissoc filepath)
                              (p/resolved nil))]
    (<assert-rejected-zotero-commits! {:mode mode :stored stored :initial-stored initial-stored :files files
      :committed committed :readbacks readbacks :unlinks unlinks :renderer-inserts renderer-inserts
      :source-commits source-commits :repo repo :notification-state notification-state
      :readback-failure readback-failure :failure failure :notifier-requests notifier-requests
      :prepared prepared :repo-dir repo-dir :byte-values byte-values :checksum checksum})))

(deftest-async zotero-rejected-pdf-commit-recycles-only-a-confirmed-unregistered-file
  (let [{:keys [repo binding stored source-record context attachment source-path repo-dir payload byte-values
                checksum paper-uuid]}
        (zotero-native-pdf-fixture)
        standalone-context (assoc context :kind :standalone)
        initial-stored @stored
        original-prepare assets/prepare-asset-block
        prepared (atom nil)
        mode (atom nil)
        files (atom {})
        committed (atom {})
        readbacks (atom [])
        unlinks (atom [])
        renderer-inserts (atom 0)
        source-commits (atom 0)
        notification-state {:session "synthetic-session" :cursor 7}
        notifier-requests (atom [])
        failure (ex-info "Synthetic native commit reply failed" {:type :synthetic/commit-reply-failed})
        readback-failure (ex-info "Synthetic committed UUID read failed" {:type :synthetic/committed-read-failed})
        lookup-failure (ex-info "Synthetic current UUID read failed" {:type :synthetic/read-failed})]
    (<with-rejected-zotero-commits! {:stored stored :repo repo :repo-dir repo-dir :binding binding :mode mode
      :source-record source-record :context context :standalone-context standalone-context
      :notifier-requests notifier-requests :paper-uuid paper-uuid :attachment attachment
      :source-path source-path :payload payload :original-prepare original-prepare :prepared prepared
      :files files :renderer-inserts renderer-inserts :committed committed :failure failure
      :source-commits source-commits :readbacks readbacks :lookup-failure lookup-failure
      :readback-failure readback-failure :unlinks unlinks :initial-stored initial-stored
      :notification-state notification-state :byte-values byte-values :checksum checksum})))

(deftest-async zotero-reused-pdf-keeps-existing-bytes-after-source-commit-failure
  (let [{:keys [repo binding stored source-record context attachment source-path repo-dir payload byte-values
                checksum asset-uuid paper-uuid]}
        (zotero-native-pdf-fixture)
        saved-path (str repo-dir "/assets/" asset-uuid ".pdf")
        original-file {:payload byte-values :checksum checksum}
        files (atom {saved-path original-file})
        native-asset {:db/id 101 :block/uuid asset-uuid :block/title "Existing annotated PDF"
                      :block/parent {:block/uuid destination-page-uuid}
                      :block/tags [{:db/ident :logseq.class/Asset}]
                      :logseq.property.asset/type "pdf" :logseq.property.asset/size 9
                      :logseq.property.asset/checksum checksum}
        mode (atom nil)
        writes (atom 0)
        unlinks (atom [])
        inserts (atom 0)
        placement-queries (atom 0)
        link-failure (ex-info "Synthetic existing PDF link failed" {:type :synthetic/link-failed})
        admit! (fn [_ _ register!]
                 (p/let [result (register! (:graph-binding-uuid binding) source-record context)]
                   {:status :complete :worker-result result}))]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                   state/get-current-repo (constantly repo)
                   state/get-state (constantly false)
                   util/electron? (constantly true)
                   config/db-based-graph? (constantly true)
                   config/get-repo-dir (constantly repo-dir)
                   zotero/<import-scope! admit!
                   zotero/<import-item-keys! (fn [binding' _keys authorized? register!]
                                             (admit! binding' authorized? register!))
                   zotero/<read-pdf-attachments! (fn [& _]
                                                 (p/resolved {:status :ready :attachments [attachment]}))
                   db-async/<register-literature-source-record
                   (fn [& _] (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                   ipc/ipc (fn [method path & _]
                             (is (contains? #{source-path saved-path} path))
                             (case method :stat (p/resolved {:size 9})
                                   :fileContentMatches (p/resolved true)
                                   :readFileRaw (p/resolved payload)
                                   (throw (ex-info "Unexpected native request" {:method method}))))
                   assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                   db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                   db-async/<find-reusable-pdf-by-checksum
                   (fn [& _] (p/resolved {:status :found :literature-status :found :assets [native-asset]}))
                   db-async/<get-block (fn [_ actual-uuid _]
                                        (p/resolved (when (= actual-uuid asset-uuid) native-asset)))
                   fs/write-asset-file! (fn [& _] (swap! writes inc) (p/resolved nil))
                   fs/unlink! (fn [_ filepath _]
                                (swap! unlinks conj filepath)
                                (swap! files dissoc filepath)
                                (p/resolved nil))
                   db-async/<link-pdf-to-literature
                   (fn [repo' actual-asset actual-paper options]
                     (is (= [repo asset-uuid paper-uuid]
                            [repo' actual-asset actual-paper]))
                     (is (true? (:automatic? options)))
                     (is (= {:binding-uuid (:graph-binding-uuid binding)
                             :source-key (:source-key source-record)}
                            (dissoc (:source-context options) :reference-uuid :asset-block)))
                     (is (uuid? (get-in options [:source-context :reference-uuid])))
                     (if (= :link-rejected @mode)
                       (p/rejected link-failure)
                       (p/resolved {:status :blocked :reason :target-paper-unavailable})))
                   db-async/<q (fn [& _] (swap! placement-queries inc) (p/resolved nil))
                   state/<invoke-db-worker (fn [& _]
                                             (swap! inserts inc)
                                             (p/resolved nil))]
      (p/let [_ (p/loop [remaining (seq [:link-rejected :link-blocked])]
        (when-let [scenario (first remaining)]
          (reset! mode scenario)
          (p/let [outcome (-> (if (= :link-rejected scenario)
                               (literature/<import-zotero-now! repo)
                               (literature/<refresh-zotero-items! repo ["AAAA1111"]))
                             (p/then (fn [value] {:fulfilled? true :value value}))
                             (p/catch (fn [error] {:fulfilled? false :error error})))]
            (is (false? (:fulfilled? outcome)))
            (if (= :link-rejected scenario)
              (is (identical? link-failure (:error outcome)))
              (is (= :literature-zotero/attachment-link-failed
                     (some-> outcome :error ex-data :type))))
            (is (= {saved-path original-file} @files) "Reuse failures cannot recycle existing PDF bytes")
            (is (zero? @writes))
            (is (empty? @unlinks))
            (p/recur (next remaining)))))]
        (p/let [actual-checksum (db-asset/<get-file-array-buffer-checksum payload)]
          (is (= checksum actual-checksum))
          (is (zero? @inserts) "A rejected source commit adds no renderer placement")
          (is (zero? @placement-queries)))))))

(defn- <assert-delayed-stale-zotero-commits!
  [{:keys [mode files saved-path original-file writes unlinks commits reads readbacks pending repo notification-state renderer-ops placement-queries blocked cleanup-failure lookup-failure source-path paper-uuid binding source-record repo-dir asset-uuid notifier-requests]}]
  (p/let [_ (p/loop [scenarios (seq [:new :reused :new-cleanup-rejected :new-cleanup-read-unknown])]
              (when-let [scenario (first scenarios)]
                (let [resolve-commit (atom nil)
                      resolve-started (atom nil)
                      started (js/Promise. (fn [resolve _] (reset! resolve-started resolve)))
                      waiting (js/Promise. (fn [resolve _] (reset! resolve-commit resolve)))
                      completed? (atom false)]
                  (reset! mode scenario)
                  (reset! files {saved-path original-file})
                  (doseq [a [writes unlinks commits reads readbacks]] (reset! a []))
                  (reset! pending {:started @resolve-started :promise waiting})
                  (let [attempt (-> (literature/<poll-zotero-notifications-once! repo notification-state)
                                    (p/then (fn [result]
                                              (reset! completed? true)
                                              {:fulfilled? true :value result}))
                                    (p/catch (fn [error]
                                               (reset! completed? true)
                                               {:fulfilled? false :error error})))]
                    (p/let [_ started
                            _ (do
                                (is (false? @completed?) "Notifier remains pending until source commit replies")
                                (is (= 1 (count @commits)))
                                (is (zero? @renderer-ops))
                                (is (zero? @placement-queries))
                                (@resolve-commit blocked))
                            outcome attempt]
                      (if (contains? #{:new :reused} scenario)
                        (do
                          (is (true? (:fulfilled? outcome)))
                          (is (= :complete (get-in outcome [:value :status])))
                          (is (= {:session "synthetic-session" :cursor 8}
                                 (get-in outcome [:value :notification-state])))
                          (is (= {:blocked 1} (get-in outcome [:value :import :result-counts])))
                          (is (= [:source-link-unavailable]
                                 (mapv :reason (get-in outcome [:value :import :results])))))
                        (do
                          (is (false? (:fulfilled? outcome)))
                          (is (identical? (if (= :new-cleanup-rejected scenario)
                                            cleanup-failure lookup-failure)
                                          (:error outcome)))
                          (is (nil? (:value outcome))
                              "A failed normal stale cleanup cannot return an advanced notifier cursor")))
                      (is (= 1 (count (filter #(= [:readFileRaw source-path] %) @reads))))
                      (let [[graph actual-asset actual-paper options] (first @commits)
                            context (:source-context options)]
                        (is (= [repo paper-uuid] [graph actual-paper]))
                        (is (true? (:automatic? options)))
                        (is (= {:binding-uuid (:graph-binding-uuid binding)
                                :source-key (:source-key source-record)}
                               (select-keys context [:binding-uuid :source-key])))
                        (is (uuid? (:reference-uuid context)))
                        (if (not= :reused scenario)
                          (let [filepath (str repo-dir "/assets/" actual-asset ".pdf")]
                            (is (= actual-asset (get-in context [:asset-block :block/uuid])))
                            (is (= [filepath] @writes))
                            (if (= :new-cleanup-read-unknown scenario)
                              (is (empty? @unlinks))
                              (is (= [filepath] @unlinks)))
                            (if (= :new scenario)
                              (is (= {saved-path original-file} @files))
                              (is (= {saved-path original-file filepath original-file} @files)
                                  "A failed cleanup retains the exact new and existing PDF bytes")))
                          (do
                            (is (= asset-uuid actual-asset))
                            (is (= asset-uuid (get-in context [:asset-block :block/uuid])))
                            (is (empty? @writes))
                            (is (empty? @unlinks))
                            (is (= {saved-path original-file} @files)))))
                      (p/recur (next scenarios)))))))]
    (is (= (repeat 4 notification-state) @notifier-requests))
    (is (zero? @renderer-ops))
    (is (zero? @placement-queries))))

(defn- <with-delayed-zotero-commits!
  [{:keys [stored repo repo-dir notifier-requests source-record paper-uuid attachment reads source-path saved-path payload mode native-asset checksum writes files readbacks lookup-failure asset-uuid commits pending unlinks cleanup-failure renderer-ops placement-queries original-file notification-state blocked binding]}]
  (p/with-redefs [storage/get (fn [key] (get @stored key))
                 state/get-current-repo (constantly repo)
                 state/get-state (constantly false)
                 util/electron? (constantly true)
                 config/db-based-graph? (constantly true)
                 config/get-repo-dir (constantly repo-dir)
                 zotero/<notifier-request!
                 (fn [current-state & _]
                   (swap! notifier-requests conj current-state)
                   (p/resolved {:session "synthetic-session" :cursor 8
                                :rescan false :zotero-library-key "LOCAL001" :keys ["AAAA1111"]}))
                 zotero/<local-api-request!
                 (fn [& _]
                   (p/resolved (js/JSON.stringify
                                (clj->js [{:key "AAAA1111" :library {:type "user" :id 0}
                                           :data {:key "AAAA1111" :itemType "journalArticle"
                                                  :title (:title source-record) :creators []}}]))))
                 db-async/<register-literature-source-record
                 (fn [& _] (p/resolved {:status :already-imported :paper-uuid paper-uuid}))
                 zotero/<read-pdf-attachments!
                 (fn [& _]
                   (p/resolved {:status :ready
                                :attachments [attachment (assoc attachment :item-key "CCCC3333"
                                                                :filename "second.pdf"
                                                                :file-url "file:///synthetic/zotero/second.pdf")]}))
                 ipc/ipc
                 (fn [method filepath & _]
                   (swap! reads conj [method filepath])
                   (is (contains? #{source-path saved-path} filepath)
                       "A stale first attachment must not start the second attachment")
                   (case method :stat (p/resolved {:size 9})
                         :fileContentMatches (p/resolved true)
                         :readFileRaw (p/resolved payload)
                         (throw (ex-info "Unexpected native request" {:method method}))))
                 assets/ensure-assets-dir! (fn [_] (p/resolved [repo-dir "assets"]))
                 db-async/<get-asset-with-checksum (fn [& _] (p/resolved nil))
                 db-async/<find-reusable-pdf-by-checksum
                 (fn [& _]
                   (p/resolved (if (= :reused @mode)
                                 {:status :found :literature-status :found :assets [native-asset]}
                                 {:status :missing :assets []})))
                 fs/write-asset-file!
                 (fn [graph filename buffer]
                   (p/let [actual-checksum (db-asset/<get-file-array-buffer-checksum buffer)]
                     (is (= [repo checksum] [graph actual-checksum]))
                     (let [filepath (str repo-dir "/assets/" filename)
                           file {:payload (js->clj (js/Array.from (assets/->uint8 buffer)))
                                 :checksum actual-checksum}]
                       (swap! writes conj filepath)
                       (swap! files assoc filepath file))))
                 db-async/<get-block
                 (fn [_ uuid _]
                   (swap! readbacks conj uuid)
                   (if (= :new-cleanup-read-unknown @mode)
                     (p/rejected lookup-failure)
                     (p/resolved (when (= uuid asset-uuid) native-asset))))
                 db-async/<link-pdf-to-literature
                 (fn [graph actual-asset actual-paper options]
                   (swap! commits conj [graph actual-asset actual-paper options])
                   ((:started @pending))
                   (:promise @pending))
                 fs/unlink!
                 (fn [_ filepath _]
                   (is (not= saved-path filepath) "Existing PDF bytes cannot be recycled")
                   (is (= (last @readbacks) (second (last @commits)))
                       "Cleanup first checks the exact new Asset UUID")
                   (swap! unlinks conj filepath)
                   (if (= :new-cleanup-rejected @mode)
                     (p/rejected cleanup-failure)
                     (do (swap! files dissoc filepath)
                         (p/resolved nil))))
                 state/<invoke-db-worker (fn [& _] (swap! renderer-ops inc) (p/resolved nil))
                 db-async/<q (fn [& _] (swap! placement-queries inc) (p/resolved nil))]
    (<assert-delayed-stale-zotero-commits! {:mode mode :files files :saved-path saved-path
      :original-file original-file :writes writes :unlinks unlinks :commits commits :reads reads
      :readbacks readbacks :pending pending :repo repo :notification-state notification-state
      :renderer-ops renderer-ops :placement-queries placement-queries :blocked blocked
      :cleanup-failure cleanup-failure :lookup-failure lookup-failure :source-path source-path
      :paper-uuid paper-uuid :binding binding :source-record source-record :repo-dir repo-dir
      :asset-uuid asset-uuid :notifier-requests notifier-requests})))

(deftest-async zotero-delayed-stale-source-commit-stops-attachments-and-completes-notifier
  (let [{:keys [repo binding stored source-record attachment source-path repo-dir payload byte-values
                checksum asset-uuid paper-uuid]}
        (zotero-native-pdf-fixture)
        saved-path (str repo-dir "/assets/" asset-uuid ".pdf")
        original-file {:payload byte-values :checksum checksum}
        native-asset {:block/uuid asset-uuid :block/title "Existing annotated PDF"
                      :block/parent {:block/uuid destination-page-uuid}
                      :block/tags [{:db/ident :logseq.class/Asset}]
                      :logseq.property.asset/type "pdf" :logseq.property.asset/size 9
                      :logseq.property.asset/checksum checksum}
        notification-state {:session "synthetic-session" :cursor 7}
        blocked {:status :blocked :reason :source-link-unavailable}
        cleanup-failure (ex-info "Synthetic stale attachment recycle failed" {:type :synthetic/cleanup-failed})
        lookup-failure (ex-info "Synthetic stale attachment UUID read failed" {:type :synthetic/read-failed})
        mode (atom nil)
        pending (atom nil)
        files (atom {})
        writes (atom [])
        unlinks (atom [])
        commits (atom [])
        reads (atom [])
        readbacks (atom [])
        notifier-requests (atom [])
        renderer-ops (atom 0)
        placement-queries (atom 0)]
    (<with-delayed-zotero-commits! {:stored stored :repo repo :repo-dir repo-dir
      :notifier-requests notifier-requests :source-record source-record :paper-uuid paper-uuid
      :attachment attachment :reads reads :source-path source-path :saved-path saved-path :payload payload
      :mode mode :native-asset native-asset :checksum checksum :writes writes :files files :readbacks readbacks
      :lookup-failure lookup-failure :asset-uuid asset-uuid :commits commits :pending pending :unlinks unlinks
      :cleanup-failure cleanup-failure :renderer-ops renderer-ops :placement-queries placement-queries
      :original-file original-file :notification-state notification-state :blocked blocked :binding binding})))

(deftest-async changing-the-destination-invalidates-the-in-flight-import
  (let [repo "synthetic-repo"
        binding (assoc (literature/local-zotero-binding
                        #uuid "79000000-0000-4000-8000-000000000001" nil)
                       :destination-page-uuid destination-page-uuid
                       :zotero-library-key "LOCAL001")
        stored (atom {:ls-literature-settings
                      {repo {:enabled? true :zotero-binding binding}}})]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                    state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    zotero/<import-scope!
                    (fn [_binding still-authorized? _register!]
                      (is (true? (still-authorized?)))
                      (swap! stored assoc-in
                             [:ls-literature-settings repo :zotero-binding :destination-page-uuid]
                             (random-uuid))
                      (is (false? (still-authorized?)))
                      (p/resolved {:status :cancelled}))]
      (p/let [result (literature/<import-zotero-now! repo)]
        (is (= :cancelled (:status result)))))))

(deftest-async on-demand-metadata-check-keeps-the-source-record-transient
  (let [repo "synthetic-repo"
        paper-uuid #uuid "79000000-0000-4000-8000-000000000010"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        source-key (str "zotero:local:"
                        (get-in binding [:source-identity :binding-uuid])
                        ":item:AAAA1111")
        source-record {:classification :bibliographic-item
                       :source-key source-key
                       :title "Updated title"
                       :doi "10.5555/updated"}
        stored {:ls-literature-settings
                {repo {:enabled? true :zotero-binding binding}}}
        calls (atom [])]
    (p/with-redefs
     [storage/get (fn [key] (get stored key))
      state/get-current-repo (constantly repo)
      state/get-state (fn [key]
                        (when (= :db/restoring? key) false))
      db-async/<find-literature-source-record-link
      (fn [repo' binding-uuid paper-uuid']
        (swap! calls conj [:find repo' binding-uuid paper-uuid'])
        (p/resolved {:status :found :source-key source-key}))
      zotero/<read-source-record!
      (fn [binding' source-key' still-authorized?]
        (swap! calls conj [:read binding' source-key'])
        (is (still-authorized?))
        (p/resolved {:status :ready :source-record source-record}))
      db-async/<plan-linked-literature-source-record-metadata
      (fn [repo' binding-uuid paper-uuid' source-record']
        (swap! calls conj
               [:plan repo' binding-uuid paper-uuid' source-record'])
        (p/resolved {:status :manual
                     :reason :metadata-review
                     :paper-uuid paper-uuid'
                     :changes [{:field :title
                                :kind :different-value
                                :current "Old title"
                                :source "Updated title"}]}))]
      (p/let [result (literature/<check-zotero-metadata! repo paper-uuid)]
        (is (= :manual (:status result)))
        (is (= [:find :read :plan] (mapv first @calls)))
        (is (= source-record (last (last @calls))))))))

(deftest-async on-demand-metadata-check-stops-at-an-ambiguous-source
  (let [repo "synthetic-repo"
        paper-uuid #uuid "79000000-0000-4000-8000-000000000010"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        stored {:ls-literature-settings
                {repo {:enabled? true :zotero-binding binding}}}
        reads (atom 0)]
    (p/with-redefs
     [storage/get (fn [key] (get stored key))
      state/get-current-repo (constantly repo)
      state/get-state (fn [key]
                        (when (= :db/restoring? key) false))
      db-async/<find-literature-source-record-link
      (fn [& _]
        (p/resolved {:status :conflict
                     :source-keys ["source-a" "source-b"]}))
      zotero/<read-source-record!
      (fn [& _] (swap! reads inc))]
      (p/let [result (literature/<check-zotero-metadata! repo paper-uuid)]
        (is (= :conflict (:status result)))
        (is (zero? @reads))))))

(deftest-async metadata-acceptance-re-reads-before-the-worker-write
  (let [repo "synthetic-repo"
        paper-uuid #uuid "79000000-0000-4000-8000-000000000010"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        source-key (str "zotero:local:"
                        (get-in binding [:source-identity :binding-uuid])
                        ":item:AAAA1111")
        source-record {:classification :bibliographic-item
                       :source-key source-key
                       :title "Fresh title"
                       :doi "10.5555/fresh"}
        accepted [{:field :title
                   :kind :different-value
                   :current "Old title"
                   :source "Fresh title"}]
        stored {:ls-literature-settings
                {repo {:enabled? true :zotero-binding binding}}}
        calls (atom [])]
    (p/with-redefs
     [storage/get (fn [key] (get stored key))
      state/get-current-repo (constantly repo)
      state/get-state (fn [key]
                        (when (= :db/restoring? key) false))
      db-async/<find-literature-source-record-link
      (fn [& _]
        (swap! calls conj :find)
        (p/resolved {:status :found :source-key source-key}))
      zotero/<read-source-record!
      (fn [_binding _source-key still-authorized?]
        (swap! calls conj :read)
        (is (still-authorized?))
        (p/resolved {:status :ready :source-record source-record}))
      db-async/<apply-literature-source-record-metadata
      (fn [repo' binding-uuid paper-uuid' source-record' accepted']
        (swap! calls conj :apply)
        (is (= [repo (:graph-binding-uuid binding) paper-uuid
                source-record accepted]
               [repo' binding-uuid paper-uuid' source-record' accepted']))
        (p/resolved {:status :updated :updated-fields [:title]}))]
      (p/let [result (literature/<apply-zotero-metadata!
                       repo paper-uuid accepted)]
        (is (= {:status :updated :updated-fields [:title]} result))
        (is (= [:find :read :apply] @calls))))))

(deftest-async metadata-acceptance-does-not-submit-after-local-disable
  (let [repo "synthetic-repo"
        paper-uuid #uuid "79000000-0000-4000-8000-000000000010"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        source-key (str "zotero:local:"
                        (get-in binding [:source-identity :binding-uuid])
                        ":item:AAAA1111")
        stored (atom {:ls-literature-settings
                      {repo {:enabled? true :zotero-binding binding}}})
        reading (p/deferred)
        release-read (p/deferred)
        writes (atom 0)]
    (p/with-redefs
     [storage/get (fn [key] (get @stored key))
      storage/set (fn [key value] (swap! stored assoc key value))
      state/get-current-repo (constantly repo)
      state/get-state (constantly false)
      literature/cancel-active-identification! (fn [] nil)
      literature/reconcile-zotero-lifecycle! (fn [_] nil)
      db-async/<find-literature-source-record-link
      (fn [& _] (p/resolved {:status :found :source-key source-key}))
      zotero/<read-source-record!
      (fn [_binding _source-key still-authorized?]
        (is (still-authorized?))
        (p/resolve! reading true)
        release-read)
      db-async/<apply-literature-source-record-metadata
      (fn [& _]
        (swap! writes inc)
        (p/resolved {:status :updated}))]
      (let [pending (literature/<apply-zotero-metadata!
                     repo paper-uuid [{:field :title :source "Fresh title"}])]
        (p/let [_ reading
                _ (literature/disable! repo)
                _ (p/resolve! release-read
                              {:status :ready
                               :source-record {:source-key source-key
                                               :title "Fresh title"}})
                result pending]
          (is (= {:status :cancelled :reason :binding-inactive} result))
          (is (zero? @writes)))))))

(deftest-async metadata-acceptance-stops-after-the-local-binding-is-removed
  (let [calls (atom 0)]
    (p/with-redefs
     [storage/get (constantly nil)
      zotero/<read-source-record! (fn [& _] (swap! calls inc))
      db-async/<apply-literature-source-record-metadata
      (fn [& _] (swap! calls inc))]
      (p/let [result (literature/<apply-zotero-metadata!
                       "synthetic-repo"
                       #uuid "79000000-0000-4000-8000-000000000010"
                       [{:field :title
                         :kind :different-value
                         :current "Old title"
                         :source "Fresh title"}])]
        (is (= {:status :blocked :reason :missing-zotero-binding} result))
        (is (zero? @calls))))))

(deftest-async zotero-import-does-nothing-without-a-local-binding
  (let [stored (atom {})]
    (p/with-redefs [storage/get (fn [key] (get @stored key))]
      (p/let [result (literature/<import-zotero-now! "synthetic-repo")]
        (is (= {:status :blocked :reason :missing-zotero-binding}
               result))))))

(deftest-async zotero-lifecycle-owns-one-cancellable-long-poll
  (let [repo "synthetic-repo"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        stored {:ls-literature-settings
                {repo {:enabled? true :zotero-binding binding}}}
        started (p/deferred)
        calls (atom 0)
        signal* (atom nil)
        original-state (rfx/snapshot)]
    (literature/shutdown-zotero-lifecycle!)
    (rfx/replace-state! (assoc original-state
                               :git/current-repo repo
                               :db/restoring? false))
    (p/with-redefs [util/electron? (constantly true)
                    storage/get (fn [key] (get stored key))
                    state/get-current-repo (constantly repo)
                    state/get-state (fn [key]
                                      (when (= :db/restoring? key) false))
                    zotero/<process-notifier-cycle!
                    (fn [binding' notification-state still-authorized?
                         _register! signal]
                      (is (= binding binding'))
                      (is (= {:session nil :cursor 0} notification-state))
                      (is (still-authorized?))
                      (swap! calls inc)
                      (reset! signal* signal)
                      (p/resolve! started true)
                      (p/create
                       (fn [resolve _reject]
                         (.addEventListener
                          signal
                          "abort"
                          #(resolve {:status :cancelled
                                     :reason :binding-inactive})
                          #js {:once true}))))]
      (let [started-result (literature/reconcile-zotero-lifecycle! repo)
            run (:run started-result)]
        (p/let [_ started
                running-result (literature/reconcile-zotero-lifecycle! repo)
                stop-result (literature/stop-zotero-sync! :synthetic-stop)
                outcome run]
          (is (= :started (:status started-result)))
          (is (= :running (:status running-result)))
          (is (= 1 @calls))
          (is (= {:status :stopped :reason :synthetic-stop} stop-result))
          (is (.-aborted ^js @signal*))
          (is (= {:status :cancelled :reason :synthetic-stop} outcome))
          (literature/shutdown-zotero-lifecycle!)
          (rfx/replace-state! original-state))))))

(deftest-async zotero-notifier-recovery-preserves-the-import-result
  (let [repo "synthetic-notifier-recovery"
        binding (assoc (literature/local-zotero-binding
                        #uuid "79000000-0000-4000-8000-000000000031" nil)
                       :zotero-library-key "LOCAL001"
                       :destination-page-uuid destination-page-uuid)
        stored {:ls-literature-settings {repo {:enabled? true :zotero-binding binding}}}
        original-state (rfx/snapshot)
        retried (p/deferred)
        recovered (p/deferred)
        healthy (p/deferred)
        calls (atom 0)
        import-path [:literature/import-state repo]
        import-result {:status :working :counts {:created 2 :blocked 1}}]
    (literature/shutdown-zotero-lifecycle!)
    (rfx/replace-state! (-> original-state
                           (assoc :git/current-repo repo :db/restoring? false)
                           (assoc-in import-path import-result)))
    (p/with-redefs [util/electron? (constantly true)
                    storage/get (fn [key] (get stored key))
                    state/get-current-repo (constantly repo)
                    state/get-state (fn [key] (get (rfx/snapshot) key))
                    zotero/<process-notifier-cycle!
                    (fn [_ _notification-state _ _ signal]
                      (case (swap! calls inc)
                        1 (p/rejected (ex-info "Synthetic unavailable notifier" {:status 503}))
                        2 (do (p/resolve! retried true) healthy)
                        (do
                          (p/resolve! recovered true)
                          (p/create (fn [resolve _]
                                      (.addEventListener signal "abort"
                                                         #(resolve {:status :cancelled})
                                                         #js {:once true}))))))]
      (-> (p/let [_ (literature/reconcile-zotero-lifecycle! repo)
                  _ retried]
            (is (= (assoc import-result :notifier-error? true)
                   (get-in (rfx/snapshot) import-path)))
            (state/update-state! import-path #(assoc % :status :failed))
            (p/resolve! healthy {:status :idle :notification-state {:session "synthetic" :cursor 0}})
            (p/let [_ recovered]
              (is (= {:status :failed :counts {:created 2 :blocked 1} :notifier-error? false}
                     (get-in (rfx/snapshot) import-path)))
              (is (= 3 @calls))))
          (p/finally (fn []
                       (literature/shutdown-zotero-lifecycle!)
                       (rfx/replace-state! original-state)))))))

(deftest-async replaced-zotero-notifier-cannot-clear-the-current-error
  (let [repo "synthetic-notifier-replaced"
        binding (assoc (literature/local-zotero-binding
                        #uuid "79000000-0000-4000-8000-000000000032" nil)
                       :zotero-library-key "LOCAL001"
                       :destination-page-uuid destination-page-uuid)
        stored {:ls-literature-settings {repo {:enabled? true :zotero-binding binding}}}
        original-state (rfx/snapshot)
        first-started (p/deferred)
        second-started (p/deferred)
        old-result (p/deferred)
        calls (atom 0)
        import-path [:literature/import-state repo]]
    (literature/shutdown-zotero-lifecycle!)
    (rfx/replace-state! (assoc original-state :git/current-repo repo :db/restoring? false))
    (p/with-redefs [util/electron? (constantly true)
                    storage/get (fn [key] (get stored key))
                    state/get-current-repo (constantly repo)
                    state/get-state (fn [key] (get (rfx/snapshot) key))
                    zotero/<process-notifier-cycle!
                    (fn [_ _ _ _ signal]
                      (if (= 1 (swap! calls inc))
                        (do (p/resolve! first-started true) old-result)
                        (do
                          (p/resolve! second-started true)
                          (p/create (fn [resolve _]
                                      (.addEventListener signal "abort"
                                                         #(resolve {:status :cancelled})
                                                         #js {:once true}))))))]
      (-> (p/let [_ (literature/reconcile-zotero-lifecycle! repo)
                  _ first-started
                  _ (literature/stop-zotero-sync!)
                  _ (literature/reconcile-zotero-lifecycle! repo)
                  _ second-started
                  _ (state/update-state! import-path #(assoc % :notifier-error? true))
                  _ (p/resolve! old-result {:status :idle :notification-state {:session "old" :cursor 0}})
                  _ (p/delay 0)]
            (is (true? (get-in (rfx/snapshot) (conj import-path :notifier-error?))))
            (is (= 2 @calls)))
          (p/finally (fn []
                       (literature/shutdown-zotero-lifecycle!)
                       (rfx/replace-state! original-state)))))))

(deftest-async zotero-lifecycle-cancels-on-graph-change-without-a-polling-timer
  (let [repo "synthetic-repo"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        stored {:ls-literature-settings
                {repo {:enabled? true :zotero-binding binding}}}
        started (p/deferred)
        signal* (atom nil)
        original-state (rfx/snapshot)]
    (literature/shutdown-zotero-lifecycle!)
    (rfx/replace-state! (assoc original-state
                               :git/current-repo repo
                               :db/restoring? false))
    (p/with-redefs [util/electron? (constantly true)
                    storage/get (fn [key] (get stored key))
                    state/get-current-repo #(-> (rfx/snapshot) :git/current-repo)
                    state/get-state (fn [key]
                                      (when (= :db/restoring? key)
                                        (:db/restoring? (rfx/snapshot))))
                    zotero/<process-notifier-cycle!
                    (fn [_ _ _ _ signal]
                      (reset! signal* signal)
                      (p/resolve! started true)
                      (p/create
                       (fn [resolve _reject]
                         (.addEventListener
                          signal
                          "abort"
                          #(resolve {:status :cancelled
                                     :reason :binding-inactive})
                          #js {:once true}))))]
      (let [{:keys [run]} (literature/reconcile-zotero-lifecycle! repo)]
        (p/let [_ started
                _ (rfx/replace-state!
                   (assoc (rfx/snapshot) :git/current-repo "other-repo"))
                outcome run]
          (is (.-aborted ^js @signal*))
          (is (= {:status :cancelled :reason :graph-changed} outcome))
          (is (= :idle (:status (literature/stop-zotero-sync!))))
          (literature/shutdown-zotero-lifecycle!)
          (rfx/replace-state! original-state))))))

(defn- <connect-across-graph-change
  [pause-at reject?]
  (let [repo "synthetic-connect-a"
        other-repo "synthetic-connect-b"
        binding-uuid #uuid "79000000-0000-4000-8000-000000000021"
        other-binding (assoc (literature/local-zotero-binding
                             #uuid "79000000-0000-4000-8000-000000000022" nil)
                            :zotero-library-key "LOCAL002"
                            :destination-page-uuid destination-page-uuid)
        stored (atom {:ls-literature-settings
                      {repo {:enabled? true}
                       other-repo {:enabled? true :zotero-binding other-binding}}})
        original-state (rfx/snapshot)
        release (p/deferred)
        paused (p/deferred)
        started (p/deferred)
        signal* (atom nil)
        graph-writes (atom 0)
        pause! (fn [value]
                 (p/resolve! paused true)
                 (p/then release (constantly value)))]
    (literature/shutdown-zotero-lifecycle!)
    (rfx/replace-state! (assoc original-state :git/current-repo repo :db/restoring? false))
    (p/with-redefs
     [util/electron? (constantly true)
      storage/get (fn [key] (get @stored key))
      storage/set (fn [key value] (swap! stored assoc key value))
      state/get-current-repo #(-> (rfx/snapshot) :git/current-repo)
      state/get-state (fn [key] (get (rfx/snapshot) key))
      zotero/<read-library-key!
      (fn [] (if (= :library pause-at) (pause! "LOCAL001") (p/resolved "LOCAL001")))
      db-async/<ensure-literature-source-binding
      (fn [_ uuid _]
        (swap! graph-writes inc)
        (let [result {:status :created :binding-uuid uuid
                      :destination-page-uuid destination-page-uuid}]
          (if (= :graph pause-at) (pause! result) (p/resolved result))))
      zotero/<process-notifier-cycle!
      (fn [_ _ _ _ signal]
        (reset! signal* signal)
        (p/resolve! started true)
        (p/create
         (fn [resolve _]
           (.addEventListener signal "abort"
                              #(resolve {:status :cancelled :reason :binding-inactive})
                              #js {:once true}))))]
      (-> (p/let [pending [(p/catch (literature/<connect-local-zotero! repo binding-uuid nil)
                                  #(hash-map :status :failed :type (:type (ex-data %))))]
                  _ (when pause-at paused)
                  _ (when pause-at
                      (rfx/replace-state! (assoc (rfx/snapshot) :git/current-repo other-repo)))
                  _ (when pause-at (literature/reconcile-zotero-lifecycle! other-repo))
                  _ (when pause-at started)
                  signal @signal*
                  _ (when pause-at
                      (if reject?
                        (p/reject! release (ex-info "Synthetic connection failure" {:type :synthetic/connect-failed}))
                        (p/resolve! release true)))
                  result (first pending)
                  _ started]
            (if pause-at
              (do
                (is (= (if reject? :failed :cancelled) (:status result)))
                (is (nil? (literature/zotero-binding repo)))
                (is (= other-binding (literature/zotero-binding other-repo)))
                (is (false? (.-aborted signal)))
                (is (= :running (:status (literature/reconcile-zotero-lifecycle! other-repo))))
                (is (identical? signal @signal*))
                (is (= (if (= :graph pause-at) 1 0) @graph-writes))
                (rfx/replace-state! (assoc (rfx/snapshot) :git/current-repo "synthetic-connect-c"))
                (is (.-aborted signal)))
              (do
                (is (= :connected (:status result)))
                (is (= binding-uuid (:graph-binding-uuid (literature/zotero-binding repo))))
                (is (= 1 @graph-writes)))))
          (p/finally
           (fn []
             (literature/shutdown-zotero-lifecycle!)
             (rfx/replace-state! original-state)))))))

(deftest-async local-zotero-connect-preserves-the-new-graphs-importer
  (p/doseq [[pause-at reject?] [[nil false] [:library false] [:graph false]
                               [:library true] [:graph true]]]
    (testing (str pause-at " reject=" reject?)
      (<connect-across-graph-change pause-at reject?))))

(deftest-async zotero-lifecycle-stops-for-repair-and-restarts-afterward
  (let [repo "synthetic-repo"
        binding {:zotero-library-key "LOCAL001"
                 :destination-page-uuid destination-page-uuid
                 :graph-binding-uuid
                 #uuid "79000000-0000-4000-8000-000000000001"
                 :source-identity
                 {:kind :local
                  :binding-uuid "79000000-0000-4000-8000-000000000002"}
                 :scope {:kind :library}}
        stored {:ls-literature-settings
                {repo {:enabled? true :zotero-binding binding}}}
        first-started (p/deferred)
        second-started (p/deferred)
        signals (atom [])
        original-state (rfx/snapshot)]
    (literature/shutdown-zotero-lifecycle!)
    (rfx/replace-state! (assoc original-state
                               :git/current-repo repo
                               :db/restoring? false))
    (p/with-redefs [util/electron? (constantly true)
                    storage/get (fn [key] (get stored key))
                    state/get-current-repo (constantly repo)
                    state/get-state (fn [key]
                                      (when (= :db/restoring? key)
                                        (:db/restoring? (rfx/snapshot))))
                    zotero/<process-notifier-cycle!
                    (fn [_ _ _ _ signal]
                      (let [call-index (count (swap! signals conj signal))]
                        (p/resolve! (if (= 1 call-index)
                                      first-started
                                      second-started)
                                    true)
                        (p/create
                         (fn [resolve _reject]
                           (.addEventListener
                            signal
                            "abort"
                            #(resolve {:status :cancelled
                                       :reason :binding-inactive})
                            #js {:once true})))))]
      (let [{first-run :run} (literature/reconcile-zotero-lifecycle! repo)]
        (p/let [_ first-started
                _ (rfx/replace-state!
                   (assoc (rfx/snapshot) :db/restoring? true))
                first-outcome first-run
                _ (rfx/replace-state!
                   (assoc (rfx/snapshot) :db/restoring? false))
                _ second-started
                {second-run :run :as running}
                (literature/reconcile-zotero-lifecycle! repo)
                _ (literature/stop-zotero-sync! :test-complete)
                second-outcome second-run]
          (is (= {:status :cancelled :reason :db-restoring}
                 first-outcome))
          (is (= :running (:status running)))
          (is (= 2 (count @signals)))
          (is (every? #(.-aborted ^js %) @signals))
          (is (= {:status :cancelled :reason :test-complete}
                 second-outcome))
          (literature/shutdown-zotero-lifecycle!)
          (rfx/replace-state! original-state))))))

(deftest complete-first-page-produces-review-candidates
  (let [result (literature/extraction->candidates complete-extraction)]
    (is (:usable? result))
    (is (= "Bounded Graph Synchronization for Shared Research Notes"
           (:title result)))
    (is (= "Alice Example and Bob Researcher" (:author-text result)))
    (is (= [{:kind :doi
             :value "10.5555/bound.2026.001"
             :y 620
             :reference-context? false}]
           (:identifiers result)))
    (is (false? (get-in result [:conflicts :title?])))))

(deftest multi-line-title-beats-a-larger-short-logo
  (let [title "Measuring data rot: An analysis of the continued availability of shared data from a Single University"
        result (literature/extraction->candidates
                {:status :complete
                 :reason :page-end
                 :page-height 792
                 :info {:title title :author "Kristin A. Briney"}
                 :items [{:text "PLOS ONE" :x 35 :y 744 :font-size 20.64}
                         {:text "Measuring data rot: An analysis of the"
                          :x 200 :y 668 :font-size 18}
                         {:text "continued availability of shared data from a"
                          :x 200 :y 646 :font-size 18}
                         {:text "Single University" :x 200 :y 623 :font-size 18}
                         {:text "Kristin A. Briney" :x 200 :y 598 :font-size 9}
                         {:text "doi: 10.1371/journal.pone.0304781"
                          :x 36 :y 560 :font-size 8}]})]
    (is (= title (:title result)))
    (is (= "Kristin A. Briney" (:author-text result)))
    (is (false? (get-in result [:conflicts :title?])))))

(deftest title-lines-preserve-a-wrapped-hyphen
  (let [result (literature/extraction->candidates
                {:status :complete
                 :reason :page-end
                 :page-height 792
                 :info {}
                 :items [{:text "Predicting the sequence-"
                          :x 120 :y 690 :font-size 18}
                         {:text "dependent backbone dynamics"
                          :x 120 :y 668 :font-size 18}
                         {:text "Sanbo Qin" :x 120 :y 640 :font-size 10}]})]
    (is (= "Predicting the sequence-dependent backbone dynamics"
           (:title result)))
    (is (= "Sanbo Qin" (:author-text result)))))

(deftest title-line-rejoins-small-cap-glyph-runs
  (let [title "DATA MANAGEMENT PLAN FOR HEALTHCARE"
        result (literature/extraction->candidates
                {:status :complete
                 :reason :page-end
                 :page-height 792
                 :info {:title title :author "Alexandru Stanciu"}
                 :items [{:text "D" :x 85.4 :y 681 :width 12.4 :font-size 17.2}
                         {:text "ATA" :x 98.1 :y 681 :width 27.2 :font-size 13.8}
                         {:text " " :x 125.3 :y 681 :width 5.1 :font-size 13.8}
                         {:text "M" :x 130.4 :y 681 :width 15.3 :font-size 17.2}
                         {:text "ANAGEMENT" :x 146.6 :y 681 :width 93 :font-size 13.8}
                         {:text " " :x 239.6 :y 681 :width 5.1 :font-size 13.8}
                         {:text "P" :x 244.7 :y 681 :width 9.6 :font-size 17.2}
                         {:text "LAN FOR" :x 255.2 :y 681 :width 63.6 :font-size 13.8}
                         {:text " " :x 318.8 :y 681 :width 5.1 :font-size 13.8}
                         {:text "H" :x 324 :y 681 :width 12.4 :font-size 17.2}
                         {:text "EALTHCARE" :x 337.3 :y 681 :width 87.4 :font-size 13.8}
                         {:text "Alexandru Stanciu"
                          :x 272 :y 573 :width 80 :font-size 10}
                         {:text "doi: 10.1101/2023.04.21.23288932"
                          :x 137 :y 783 :width 160 :font-size 8}]})]
    (is (= title (:title result)))
    (is (false? (get-in result [:conflicts :title?])))))

(deftest title-seed-uses-the-whole-korean-line
  (let [title "일본 대학에서 쓰이는 한국어 입문 교재의 어휘 항목"
        result (literature/extraction->candidates
                {:status :complete
                 :reason :page-end
                 :page-height 729
                 :info {}
                 :items [{:text "일본" :x 92 :y 607 :width 27 :font-size 13.5}
                         {:text " " :x 119 :y 607 :width 7 :font-size 13.5}
                         {:text "대학에서" :x 126 :y 607 :width 54 :font-size 13.5}
                         {:text " " :x 180 :y 607 :width 7 :font-size 13.5}
                         {:text "쓰이는 한국어 입문 교재의 어휘 항목"
                          :x 187 :y 607 :width 236 :font-size 13.5}
                         {:text "李 安九（岡山大学）"
                          :x 328 :y 581 :width 115 :font-size 11.5}
                         {:text "검토한 선행 연구에서는 본문의 긴 행을 포함한다"
                          :x 72 :y 474 :width 340 :font-size 10}]})]
    (is (= title (:title result)))
    (is (string/includes? (:author-text result) "李 安九"))))

(defn- <extract-budget-fixture
  [info text max-chars]
  (let [original (aget js/globalThis "pdfjsLib")
        original-worker (aget js/globalThis "Worker")
        calls (atom [])
        read? (atom false)
        reader #js {"read" (fn []
                            (p/resolved
                             (if (compare-and-set! read? false true)
                               #js {"done" false
                                    "value" #js {"items" (if (seq text)
                                                           #js [#js {"str" text}]
                                                           #js [])}}
                               #js {"done" true})))
                    "cancel" (fn [_] (p/resolved nil))}
        page #js {"getViewport" (fn [_] #js {"height" 800 "width" 600})
                  "streamTextContent" (fn [] #js {"getReader" (fn [] reader)})
                  "cleanup" (fn [] (swap! calls conj :page-cleanup))}
        document #js {"numPages" 1
                      "getMetadata" (fn [] (p/resolved #js {"info" (clj->js info)}))
                      "getPage" (fn [_]
                                  (swap! calls conj :get-page)
                                  (p/resolved page))}
        pdfjs #js {"PDFWorker" (fn []
                                (this-as worker
                                  (aset worker "destroy"
                                        (fn [] (swap! calls conj :worker-destroy)))
                                  nil))
                   "getDocument" (fn [_]
                                   #js {"promise" (p/resolved document)
                                        "destroy" (fn [] (p/resolved nil))})}]
    (aset js/globalThis "pdfjsLib" pdfjs)
    (aset js/globalThis "Worker"
          (fn [_source _options]
            (this-as worker
              (aset worker "terminate" (fn [] (swap! calls conj :port-terminate)))
              nil)))
    (-> (js-literature/extractFirstPageLiterature
         (js/Uint8Array. 0) #js {"maxChars" max-chars
                               "workerSrc" "synthetic-worker.mjs"})
        (p/then (fn [result]
                  {:result (js->clj result :keywordize-keys true)
                   :calls @calls}))
        (p/finally (fn []
                     (aset js/globalThis "pdfjsLib" original)
                     (aset js/globalThis "Worker" original-worker))))))

(deftest-async pdf-info-and-page-text-share-the-character-budget
  (p/let [large-title (<extract-budget-fixture {"Title" (string/join (repeat 131072 "T"))}
                                              "body" 20000)
          combined (<extract-budget-fixture {"Title" (string/join (repeat 10000 "T"))
                                             "Author" (string/join (repeat 10001 "A"))}
                                             "body" 20000)
          at-limit (<extract-budget-fixture {"Title" "1234567" "Author" "ABC"} "body" 10)
          empty-page (<extract-budget-fixture {"Title" "1234567" "Author" "ABC"} "" 10)
          normal (<extract-budget-fixture {"Title" "  Test  " "Author" " ABC "} "Hi" 10)]
    (doseq [value [large-title combined]]
      (is (= "incomplete" (get-in value [:result :status])))
      (is (= "character-limit" (get-in value [:result :reason])))
      (is (nil? (get-in value [:result :info])))
      (is (not (some #{:get-page} (:calls value))))
      (is (some #{:worker-destroy} (:calls value)))
      (is (some #{:port-terminate} (:calls value))))
    (is (= "incomplete" (get-in at-limit [:result :status])))
    (is (= "character-limit" (get-in at-limit [:result :reason])))
    (is (= [] (get-in at-limit [:result :items])))
    (is (= 10 (get-in at-limit [:result :chars])))
    (is (= "complete" (get-in empty-page [:result :status])))
    (is (= 10 (get-in empty-page [:result :chars])))
    (is (= "complete" (get-in normal [:result :status])))
    (is (= {:title "Test" :author "ABC"} (get-in normal [:result :info])))
    (is (= ["Hi"] (mapv :text (get-in normal [:result :items]))))
    (is (= 9 (get-in normal [:result :chars])))
    (is (some #{:port-terminate} (:calls normal)))))

(deftest-async extraction-reuses-the-packaged-pdf-cmaps
  (let [received-options (atom nil)]
    (p/with-redefs [js-literature/extractFirstPageLiterature
                    (fn [_pdf-bytes options]
                      (reset! received-options options)
                      (p/resolved #js {:status "complete"
                                       :reason "page-end"
                                       :pagesRequested #js [1]
                                       :items #js []}))]
      (p/let [result (literature/<extract-first-page #js [] nil)]
        (is (= :complete (:status result)))
        (is (contains? #{"./js/pdfjs/cmaps/"
                         "./static/js/pdfjs/cmaps/"}
                       (.-cMapUrl @received-options)))
        (is (= (string/replace (.-cMapUrl @received-options) #"cmaps/$" "pdf.worker.mjs")
               (.-workerSrc @received-options)))
        (is (= 600 (aget @received-options "maxItems")))
        (is (true? (.-cMapPacked @received-options)))))))

(deftest line-wrapped-doi-prefix-is-not-a-second-identifier
  (let [result (literature/extraction->candidates
                (assoc complete-extraction
                       :items [{:text "A complete paper title"
                                :y 710 :font-size 22}
                               {:text "An Author" :y 680 :font-size 11}
                               {:text "https://doi.org/10.1371/journal."
                                :y 610 :font-size 9}
                               {:text "10.1371/journal.pone.0311359"
                                :y 60 :font-size 8}]))]
    (is (= [{:kind :doi
             :value "10.1371/journal.pone.0311359"
             :y 60
             :reference-context? false}]
           (:identifiers result)))))

(deftest stored-review-retains-summary-and-discards-page-items
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65368"
        state* (atom {})]
    (with-redefs [state/get-current-repo (constantly repo)
                  state/get-state (fn [key]
                                    (when (= :db/restoring? key) false))
                  state/set-state! (fn [path value & _]
                                     (swap! state* assoc-in path value))]
      (literature/store-review-result!
       repo "/repo" "assets"
       {:asset-uuid asset-uuid
        :extraction (assoc complete-extraction
                           :version "4.2.67"
                           :elapsed-ms 42)
        :candidates {:title "Stored title" :identifiers []}})
      (let [stored (get-in @state*
                           [:literature/review-candidates repo
                            (str asset-uuid)])]
        (is (= {:status :complete
                :reason :page-end
                :version "4.2.67"
                :elapsed-ms 42}
               (:extraction stored)))
        (is (nil? (get-in stored [:extraction :items])))
        (is (= "Stored title" (get-in stored [:candidates :title])))))))

(deftest references-and-incomplete-extractions-stay-nonautomatic
  (testing "an identifier after a References heading is marked as citation context"
    (let [result (literature/extraction->candidates
                  (assoc complete-extraction
                         :items [{:text "A Paper" :y 710 :font-size 22}
                                 {:text "References" :y 300 :font-size 14}
                                 {:text "10.5555/other.2025.002" :y 270 :font-size 10}]))]
      (is (true? (get-in result [:identifiers 0 :reference-context?])))
      (is (false? (get-in result [:conflicts :title?])))))
  (testing "deadline and size limits never become usable candidates"
    (is (= {:status :incomplete
            :reason :deadline
            :usable? false
            :identifiers []}
           (literature/extraction->candidates
            {:status :incomplete
             :reason :deadline
             :items [{:text "10.5555/partial.1"}]})))))

(deftest ordinary-attachment-without-identity-has-no-identifier-candidate
  (let [result (literature/extraction->candidates
                {:status :complete
                 :reason :page-end
                 :page-height 800
                 :info {:title "Invoice 2026-09"}
                 :items [{:text "Invoice 2026-09" :y 710 :font-size 22}
                         {:text "Total: 100.00" :y 620 :font-size 11}]})]
    (is (:usable? result))
    (is (empty? (:identifiers result)))))

(deftest review-values-accept-only-one-non-reference-identifier-per-kind
  (let [review {:candidates
                {:title "  Extracted title  "
                 :metadata {:title "Metadata title"}
                 :identifiers [{:kind :doi
                                :value "10.5555/accepted"
                                :reference-context? false}
                               {:kind :doi
                                :value "10.5555/reference"
                                :reference-context? true}
                               {:kind :arxiv
                                :value "2401.00001"
                                :reference-context? false}
                               {:kind :arxiv
                                :value "2401.00002"
                                :reference-context? false}]}}]
    (is (= {:doi "10.5555/accepted"}
           (literature/review-identifiers review)))
    (is (= "Extracted title" (literature/review-title review)))
    (is (= "Metadata title"
           (literature/review-title
            (assoc-in review [:candidates :title] "  "))))))

(deftest automatic-review-evidence-rejects-ambiguity-and-incomplete-input
  (let [review {:extraction {:status :complete}
                :candidates {:usable? true
                             :title "Existing Paper"
                             :author-text "Alice Example and Bob Researcher"
                             :metadata {:author "Alice Example"}
                             :conflicts {:title? false}
                             :identifiers [{:kind :doi
                                            :value "10.5555/existing"
                                            :reference-context? false}
                                           {:kind :arxiv
                                            :value "2401.12345v2"
                                            :reference-context? false}
                                           {:kind :doi
                                            :value "10.5555/reference"
                                            :reference-context? true}]}}]
    (is (= {:status :ready
            :evidence {:identifiers {:doi "10.5555/existing"
                                     :arxiv "2401.12345v2"}
                       :title "Existing Paper"
                       :author-texts ["Alice Example and Bob Researcher"
                                      "Alice Example"]
                       :extraction-status :complete
                       :usable? true
                       :title-conflict? false
                       :identifier-ambiguous? false}}
           (literature/automatic-review-evidence review)))
    (is (= {:status :blocked :reason :ambiguous-identifiers}
           (literature/automatic-review-evidence
            (update-in review [:candidates :identifiers]
                       conj
                       {:kind :doi
                        :value "10.5555/second"
                        :reference-context? false}))))
    (is (= {:status :blocked :reason :incomplete-extraction}
           (literature/automatic-review-evidence
            (assoc-in review [:extraction :status] :incomplete))))
    (is (= {:status :blocked :reason :title-conflict}
           (literature/automatic-review-evidence
            (assoc-in review [:candidates :conflicts :title?] true))))))

(deftest-async automatic-review-links-only-while-the-same-graph-opt-in-is-active
  (let [repo "repo"
        other-repo "other-repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65368"
        blocked-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65369"
        disabled-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65370"
        restoring-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65371"
        current-repo (atom repo)
        restoring? (atom false)
        settings (atom {repo {:enabled? true
                              :auto-identification? true
                              :online-metadata? false}})
        state* (atom {:literature/review-candidates {repo {}}})
        worker-calls (atom [])
        result-for (fn [uuid]
                     {:asset-uuid uuid
                      :file-path (str uuid ".pdf")
                      :extraction {:status :complete :reason :page-end}
                      :candidates (literature/extraction->candidates
                                   complete-extraction)})
        read-state (fn [path]
                     (if (= :db/restoring? path)
                       @restoring?
                       (get-in @state* (if (coll? path) path [path]))))]
    (p/with-redefs [state/get-current-repo #(deref current-repo)
                    state/get-state read-state
                    state/set-state! (fn [path value & _]
                                       (swap! state* assoc-in path value))
                    state/update-state! (fn [path f & _]
                                          (swap! state* update-in path f))
                    storage/get (fn [_] @settings)
                    db-async/<auto-link-pdf-to-literature
                    (fn [repo' uuid evidence]
                      (swap! worker-calls conj [repo' uuid evidence])
                      (p/resolved
                       (if (= blocked-uuid uuid)
                         {:status :blocked :reason :identifier-not-found}
                         {:status :linked
                          :action :link
                          :paper-uuid (random-uuid)
                          :asset-uuid uuid})))]
      (p/let [linked (literature/<store-and-maybe-auto-link!
                       repo "/repo" "assets" (result-for asset-uuid))
              blocked (literature/<store-and-maybe-auto-link!
                        repo "/repo" "assets" (result-for blocked-uuid))
              _ (swap! settings assoc-in [repo :auto-identification?] false)
              disabled (literature/<store-and-maybe-auto-link!
                         repo "/repo" "assets" (result-for disabled-uuid))
              _ (reset! current-repo other-repo)
              inactive (literature/<store-and-maybe-auto-link!
                         repo "/repo" "assets"
                         (result-for (random-uuid)))
              _ (reset! current-repo repo)
              _ (reset! restoring? true)
              restoring (literature/<store-and-maybe-auto-link!
                          repo "/repo" "assets" (result-for restoring-uuid))]
        (is (= :linked (:status linked)))
        (is (= :blocked (:status blocked)))
        (is (= :automatic-identification-disabled (:reason disabled)))
        (is (= :inactive-graph (:reason inactive)))
        (is (= :inactive-graph (:reason restoring)))
        (is (= 2 (count @worker-calls)))
        (is (nil? (get-in @state*
                          [:literature/review-candidates repo
                           (str asset-uuid)])))
        (is (= {:status :blocked :reason :identifier-not-found}
               (get-in @state*
                       [:literature/review-candidates repo
                        (str blocked-uuid) :automatic-result])))
        (is (some? (get-in @state*
                           [:literature/review-candidates repo
                            (str disabled-uuid)])))
        (is (nil? (get-in @state*
                           [:literature/review-candidates repo
                            (str restoring-uuid)])))))))

(deftest-async automatic-link-failure-keeps-review-and-completes-the-batch
  (let [repo "synthetic-auto-link-batch"
        failed-uuid #uuid "84000000-0000-4000-8000-000000000001"
        linked-uuid #uuid "84000000-0000-4000-8000-000000000002"
        state* (atom {})
        received (atom [])
        remaining (atom 2)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (fn [path]
                                      (get-in @state* (if (coll? path) path [path])))
                    state/set-state! (fn [path value & _]
                                       (swap! state* assoc-in path value))
                    state/update-state! (fn [path f & _]
                                          (swap! state* update-in path f))
                    storage/get (constantly {repo {:enabled? true :auto-identification? true}})
                    literature/<identify-saved-pdf!
                    (fn [_dir _path entry _signal]
                      (p/resolved
                       (assoc entry :extraction {:status :complete :reason :page-end}
                              :candidates (literature/extraction->candidates complete-extraction))))
                    db-async/<auto-link-pdf-to-literature
                    (fn [_repo asset-uuid _evidence]
                      (if (= failed-uuid asset-uuid)
                        (p/rejected (js/Error. "Synthetic automatic-link failure"))
                        (p/resolved {:status :linked :asset-uuid asset-uuid})))]
      (p/let [batch (literature/schedule-new-pdf-identification!
                    repo "/synthetic" "assets"
                    [{:asset-uuid failed-uuid} {:asset-uuid linked-uuid}]
                    :on-result
                    (fn [extracted]
                      (p/let [result (literature/<store-and-maybe-auto-link!
                                     repo "/synthetic" "assets" extracted)]
                        (swap! received conj (:status result))
                        (swap! remaining dec))))]
        (is (= :complete (:status batch)))
        (is (= [:failed :linked] @received))
        (is (zero? @remaining))
        (is (= {:status :failed :reason :auto-link-error}
               (get-in @state* [:literature/review-candidates repo (str failed-uuid) :automatic-result])))
        (is (nil? (get-in @state* [:literature/review-candidates repo (str linked-uuid)])))))))

(deftest-async unknown-doi-uses-one-opted-in-metadata-lookup-after-native-save
  (let [repo "synthetic-repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65372"
        settings (atom {repo {:enabled? true
                              :auto-identification? true
                              :online-metadata? true}})
        state* (atom {:literature/review-candidates {repo {}}})
        current-repo (atom repo)
        worker-calls (atom [])
        lookup-calls (atom [])
        review {:asset-uuid asset-uuid
                :file-path (str asset-uuid ".pdf")
                :extraction {:status :complete :reason :page-end}
                :candidates (literature/extraction->candidates
                             complete-extraction)}]
    (p/with-redefs [state/get-current-repo #(deref current-repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path)
                                        false
                                        (get-in @state*
                                                (if (coll? path) path [path]))))
                    state/set-state! (fn [path value & _]
                                       (swap! state* assoc-in path value))
                    state/update-state! (fn [path f & _]
                                          (swap! state* update-in path f))
                    storage/get (fn [_] @settings)
                    storage/set (fn [_ value] (reset! settings value))
                    util/electron? (constantly true)
                    db-async/<auto-link-pdf-to-literature
                    (fn [& args]
                      (swap! worker-calls conj [:link args])
                      (p/resolved {:status :blocked
                                   :reason :identifier-not-found}))
                    metadata/<lookup-record!
                    (fn [ids _signal]
                      (swap! lookup-calls conj ids)
                      (p/resolved
                       {:status :ready
                        :record {:provider :crossref
                                 :doi "10.5555/bound.2026.001"
                                 :title "Bounded Graph Synchronization for Shared Research Notes"
                                 :authors "Example, Alice"
                                 :type "journal-article"}}))
                    db-async/<register-verified-pdf-as-literature
                    (fn [& args]
                      (swap! worker-calls conj [:register args])
                      (p/resolved {:status :created
                                   :asset-uuid asset-uuid}))]
      (p/let [created (literature/<store-and-maybe-auto-link!
                       repo "/synthetic" "assets" review)
              _ (literature/set-online-metadata-enabled! repo false)
              disabled (literature/<store-and-maybe-auto-link!
                         repo "/synthetic" "assets"
                         (assoc review :asset-uuid (random-uuid)))]
        (is (= :created (:status created)))
        (is (= :identifier-not-found (:reason disabled)))
        (is (= [{:doi "10.5555/bound.2026.001"}] @lookup-calls))
        (is (= [:link :register :link] (mapv first @worker-calls)))
        (is (nil? (get-in @state*
                          [:literature/review-candidates repo
                           (str asset-uuid)])))))))

(deftest-async ordinary-pdf-choice-clears-review-only-after-worker-kept
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65380"
        review {:asset-uuid asset-uuid}
        state* (atom {:literature/review-candidates {repo {(str asset-uuid) review}}})
        committed (p/deferred)
        calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                   state/get-state (fn [path]
                                     (if (= :db/restoring? path) false
                                         (get-in @state* (if (coll? path) path [path]))))
                   state/update-state! (fn [path f & args]
                                         (swap! state* update-in path #(apply f % args)))
                   db-async/<keep-pdf-as-ordinary
                   (fn [repo' actual-uuid]
                     (swap! calls conj [repo' actual-uuid])
                     committed)]
      (let [pending (literature/<keep-pdf! repo asset-uuid)]
        (is (= review (literature/review-candidate repo asset-uuid))
            "The local review must remain until the worker commits Keep")
        (p/resolve! committed {:status :kept :asset-uuid asset-uuid})
        (p/let [result pending]
          (is (= :kept (:status result)))
          (is (= [[repo asset-uuid]] @calls))
          (is (nil? (literature/review-candidate repo asset-uuid))))))))

(deftest-async ordinary-pdf-choice-retains-review-on-blocked-failed-or-inactive
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65381"
        review {:asset-uuid asset-uuid}
        state* (atom {:literature/review-candidates {repo {(str asset-uuid) review}}})
        current-repo (atom repo)
        failure (ex-info "Synthetic ordinary-PDF choice failed" {:type :synthetic/keep-failed})
        failed (p/deferred)
        reply (atom (p/resolved {:status :blocked :reason :asset-already-linked}))
        calls (atom [])]
    (p/with-redefs [state/get-current-repo #(deref current-repo)
                   state/get-state (fn [path]
                                     (if (= :db/restoring? path) false
                                         (get-in @state* (if (coll? path) path [path]))))
                   state/update-state! (fn [path f & args]
                                         (swap! state* update-in path #(apply f % args)))
                   db-async/<keep-pdf-as-ordinary
                   (fn [repo' actual-uuid]
                     (swap! calls conj [repo' actual-uuid])
                     @reply)]
      (p/let [blocked (literature/<keep-pdf! repo asset-uuid)]
        (is (= {:status :blocked :reason :asset-already-linked} blocked))
        (is (= review (literature/review-candidate repo asset-uuid)))
        (do (reset! reply failed) nil)
        (let [pending (-> (literature/<keep-pdf! repo asset-uuid)
                          (p/then (fn [result] {:fulfilled? true :result result}))
                          (p/catch (fn [error] {:fulfilled? false :error error})))]
          (is (= review (literature/review-candidate repo asset-uuid)))
          (p/reject! failed failure)
          (p/let [rejected pending]
            (is (false? (:fulfilled? rejected)))
            (is (identical? failure (:error rejected)))
            (is (= review (literature/review-candidate repo asset-uuid)))
            (reset! current-repo "other-repo")
            (p/let [inactive (literature/<keep-pdf! repo asset-uuid)]
              (is (= {:status :blocked :reason :inactive-graph} inactive))
              (is (= [[repo asset-uuid] [repo asset-uuid]] @calls))
              (is (= review (literature/review-candidate repo asset-uuid))))))))))

(deftest-async explicit-registration-clears-only-a-successful-review-candidate
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65364"
        state* (atom {:literature/review-candidates
                      {repo {(str asset-uuid)
                             {:asset-uuid asset-uuid
                              :candidates
                              {:identifiers [{:kind :doi
                                              :value "10.5555/accepted"
                                              :reference-context? false}]}}}}})
        read-state (fn [path]
                     (if (= :db/restoring? path)
                       false
                       (get-in @state* (if (coll? path) path [path]))))]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state read-state
                    state/update-state! (fn [path f & _]
                                          (swap! state* update-in path f))
                    db-async/<register-pdf-as-literature
                    (fn [repo' asset-uuid' title identifiers]
                      (is (= [repo asset-uuid "Accepted title"
                              {:doi "10.5555/accepted"}]
                             [repo' asset-uuid' title identifiers]))
                      (p/resolved {:status :created
                                   :paper-uuid (random-uuid)}))]
      (p/let [result (literature/<register-review-candidate!
                       repo asset-uuid "Accepted title")]
        (is (= :created (:status result)))
        (is (nil? (get-in @state*
                          [:literature/review-candidates repo
                           (str asset-uuid)])))))))

(deftest-async existing-pdf-can-be-reviewed-and-registered-with-edited-identifiers
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65366"
        asset {:block/uuid asset-uuid
               :block/title "existing-paper.pdf"
               :block/tags [{:db/ident :logseq.class/Asset}]
               :logseq.property.asset/type "pdf"
               :logseq.property.asset/checksum "existing-checksum"
               :logseq.property.asset/size 84}
        state* (atom {:literature/review-candidates {repo {}}})
        read-state (fn [path]
                     (if (= :db/restoring? path)
                       false
                       (get-in @state* (if (coll? path) path [path]))))]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state read-state
                    state/set-state! (fn [path value & _]
                                       (swap! state* assoc-in path value))
                    state/update-state! (fn [path f & _]
                                          (swap! state* update-in path f))
                    config/get-repo-dir (constantly "/repo")
                    db-async/<register-pdf-as-literature
                    (fn [repo' asset-uuid' title identifiers]
                      (is (= [repo asset-uuid "Existing paper"
                              {:doi "10.5555/edited"
                               :arxiv "2401.00001v2"}]
                             [repo' asset-uuid' title identifiers]))
                      (p/resolved {:status :linked}))]
      (let [review (literature/ensure-manual-review! repo asset)]
        (is (= {:asset-uuid asset-uuid
                :asset-title "existing-paper.pdf"
                :file-path (str asset-uuid ".pdf")
                :checksum "existing-checksum"
                :byte-size 84
                :repo-dir "/repo"
                :asset-dir-rpath "assets"
                :extraction {:status :not-run :reason :manual}
                :candidates {:status :not-run
                             :reason :manual
                             :usable? false
                             :identifiers []}}
               review))
        (is (identical? review
                        (literature/ensure-manual-review! repo asset))))
      (p/let [result (literature/<register-pdf!
                       repo asset-uuid "Existing paper"
                       {:doi "10.5555/edited"
                        :arxiv "2401.00001v2"})]
        (is (= :linked (:status result)))
        (is (nil? (literature/review-candidate repo asset-uuid)))))))

(deftest-async manual-review-does-not-reuse-extraction-after-pdf-fingerprint-changes
  (let [repo "synthetic-review-graph"
        asset-uuid #uuid "86000000-0000-4000-8000-000000000001"
        other-uuid #uuid "86000000-0000-4000-8000-000000000002"
        asset {:block/uuid asset-uuid
               :block/title "current.pdf"
               :block/tags [{:db/ident :logseq.class/Asset}]
               :logseq.property.asset/type "pdf"
               :logseq.property.asset/checksum "old-checksum"
               :logseq.property.asset/size 84}
        cached {:asset-uuid asset-uuid
                :file-path (str asset-uuid ".pdf")
                :checksum "old-checksum"
                :byte-size 84
                :extraction {:status :complete :reason :page-end}
                :candidates {:status :ready :usable? true
                             :title "Title extracted from old PDF"
                             :identifiers [{:kind :doi :value "10.5555/old-pdf"}]}}
        other-review {:asset-uuid other-uuid :extraction {:status :failed :reason :read-error}}
        state* (atom {:literature/review-candidates
                      {repo {(str asset-uuid) cached (str other-uuid) other-review}}})]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path) false
                                          (get-in @state* (if (coll? path) path [path]))))
                    state/set-state! (fn [path value & _] (swap! state* assoc-in path value))
                    config/get-repo-dir (constantly "/synthetic-review-graph")]
      (is (identical? cached (literature/ensure-manual-review! repo asset))
          "An unchanged PDF keeps its complete extraction")
      (is (identical? cached (literature/ensure-manual-review!
                             repo (assoc asset :logseq.property.asset/literature-auto-excluded? true)))
          "Keep remains available for an explicit manual review")
      (doseq [changed [(assoc asset :logseq.property.asset/checksum "new-checksum")
                       (assoc asset :logseq.property.asset/size 85)]]
        (swap! state* assoc-in [:literature/review-candidates repo (str asset-uuid)] cached)
        (let [review (literature/ensure-manual-review! repo changed)]
          (is (= [(:logseq.property.asset/checksum changed)
                  (:logseq.property.asset/size changed)]
                 [(:checksum review) (:byte-size review)]))
          (is (= :not-run (get-in review [:extraction :status]))
              "Old extraction is not reused after either fingerprint field changes")
          (is (empty? (get-in review [:candidates :identifiers])))
          (is (nil? (get-in review [:candidates :title])))
          (is (identical? review (literature/review-candidate repo asset-uuid)))
          (is (identical? other-review (literature/review-candidate repo other-uuid))))))))

(deftest-async manual-review-rejects-current-non-pdf-or-deleted-assets
  (let [repo "synthetic-review-graph"
        asset-uuid #uuid "86000000-0000-4000-8000-000000000003"
        other-uuid #uuid "86000000-0000-4000-8000-000000000004"
        asset {:block/uuid asset-uuid
               :block/title "current.pdf"
               :block/tags [{:db/ident :logseq.class/Asset}]
               :logseq.property.asset/type "pdf"
               :logseq.property.asset/checksum "same-checksum"
               :logseq.property.asset/size 84}
        cached {:asset-uuid asset-uuid :checksum "same-checksum" :byte-size 84
                :extraction {:status :complete}
                :candidates {:identifiers [{:kind :doi :value "10.5555/cached"}]}}
        other-review {:asset-uuid other-uuid :extraction {:status :pending}}
        state* (atom {:literature/review-candidates
                      {repo {(str asset-uuid) cached (str other-uuid) other-review}}})
        io-calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path) false
                                          (get-in @state* (if (coll? path) path [path]))))
                    state/set-state! (fn [path value & _] (swap! state* assoc-in path value))
                    config/get-repo-dir (constantly "/synthetic-review-graph")
                    fs/read-file-raw (fn [& _] (swap! io-calls conj :file-read))
                    db-async/<q (fn [& _] (swap! io-calls conj :query))]
      (doseq [invalid [(assoc asset :logseq.property.asset/type "png")
                       (assoc asset :logseq.property/deleted-at 1)]]
        (is (nil? (literature/ensure-manual-review! repo invalid))
            "A cached extraction cannot make a non-PDF or recycled Asset reviewable")
        (is (identical? other-review (literature/review-candidate repo other-uuid))))
      (is (empty? @io-calls)
          "The synchronous helper only uses the supplied current Asset metadata"))))

(defn- current-review-test-db
  []
  (d/db-with
   (d/empty-db {:db/ident {:db/unique :db.unique/identity}
                :block/uuid {:db/unique :db.unique/identity}
                :block/tags {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                :logseq.property/literature-assets {:db/valueType :db.type/ref
                                                   :db/cardinality :db.cardinality/many}})
   [{:db/id 1 :db/ident :logseq.class/Asset}
    {:db/id 2 :block/uuid #uuid "87000000-0000-4000-8000-000000000002"
     :block/title (apply str (repeat 300 "a")) :block/tags [1]
     :logseq.property.asset/type "pdf"
     :logseq.property.asset/checksum "current-checksum"
     :logseq.property.asset/size 84
     :logseq.property.asset/literature-auto-excluded? true}
    {:db/id 3 :block/uuid #uuid "87000000-0000-4000-8000-000000000003"
     :block/title "image.png" :block/tags [1] :logseq.property.asset/type "png"}
    {:db/id 4 :block/uuid #uuid "87000000-0000-4000-8000-000000000004"
     :block/title "recycled.pdf" :block/tags [1] :logseq.property.asset/type "pdf"
     :logseq.property/deleted-at 1}
    {:db/id 5 :block/uuid #uuid "87000000-0000-4000-8000-000000000005"
     :block/title "ordinary.pdf" :logseq.property.asset/type "pdf"}
    {:db/id 6 :block/uuid #uuid "87000000-0000-4000-8000-000000000006"
     :block/title "" :block/tags [1] :logseq.property.asset/type "pdf"}
    {:db/id 20 :block/uuid #uuid "87000000-0000-4000-8000-000000000020"
     :block/title "Unmarked owner" :logseq.property/literature-assets [2]}
    {:db/id 21 :block/uuid #uuid "87000000-0000-4000-8000-000000000021"
     :block/title "Deleted Paper" :logseq.property/literature-item? true
     :logseq.property/literature-assets [2] :logseq.property/deleted-at 1}]))

(deftest-async prepare-review-queries-current-native-pdf-metadata-without-io
  (let [repo "synthetic-review-graph"
        asset-uuid #uuid "87000000-0000-4000-8000-000000000002"
        db (current-review-test-db)
        old-review {:asset-uuid asset-uuid :checksum "old-checksum" :byte-size 84
                    :extraction {:status :complete}
                    :candidates {:title "Old extraction"
                                 :identifiers [{:kind :doi :value "10.5555/old"}]}}
        state* (atom {:literature/review-candidates {repo {(str asset-uuid) old-review}}})
        queries (atom [])
        io-calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path) false
                                          (get-in @state* (if (coll? path) path [path]))))
                    state/set-state! (fn [path value & _] (swap! state* assoc-in path value))
                    db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                    config/get-repo-dir (constantly "/synthetic-review-graph")
                    db-async/<q (fn [graph opts query & inputs]
                                  (is (= [repo {}] [graph opts]))
                                  (swap! queries conj inputs)
                                  (p/resolved (apply d/q query db inputs)))
                    fs/read-file-raw (fn [& _] (swap! io-calls conj :read))
                    fs/write-asset-file! (fn [& _] (swap! io-calls conj :write))
                    state/<invoke-db-worker (fn [& _] (swap! io-calls conj :worker-write))
                    db-transact/transact (fn [& _] (swap! io-calls conj :transaction))]
      (p/let [ready (literature/<prepare-review! repo asset-uuid)
              missing-size (literature/<prepare-review!
                            repo #uuid "87000000-0000-4000-8000-000000000006")
              invalid (p/all
                       (mapv #(literature/<prepare-review! repo %)
                             [#uuid "87000000-0000-4000-8000-000000000003"
                              #uuid "87000000-0000-4000-8000-000000000004"
                              #uuid "87000000-0000-4000-8000-000000000005"
                              #uuid "87000000-0000-4000-8000-000000000099"]))]
        (is (= :ready (:status ready)))
        (is (= asset-uuid (get-in ready [:asset :block/uuid])))
        (is (= (apply str (repeat 300 "a")) (get-in ready [:asset :block/title])))
        (is (true? (get-in ready [:asset :logseq.property.asset/literature-auto-excluded?]))
            "Keep does not block an explicit manual continuation")
        (is (= "current-checksum" (get-in ready [:review :checksum])))
        (is (= :not-run (get-in ready [:review :extraction :status])))
        (is (= (apply str (repeat 240 "a"))
               (:title (first (:entries (literature/review-candidate-page repo nil))))))
        (is (= "" (get-in missing-size [:review :asset-title])))
        (is (empty? (get-in ready [:review :candidates :identifiers])))
        (is (= :ready (:status missing-size)))
        (is (= "" (get-in missing-size [:asset :block/title])))
        (is (not (contains? (:asset missing-size) :logseq.property.asset/size)))
        (is (= (repeat 4 :unavailable) (map :status invalid)))
        (is (= 6 (count @queries)) "Each selected UUID uses one real Datascript query")
        (is (empty? @io-calls) "Review preparation neither reads files nor writes graph data")))))

(deftest-async prepare-review-uses-all-current-owners-and-clears-only-a-resolved-candidate
  (let [repo "synthetic-review-graph"
        asset-uuid #uuid "87000000-0000-4000-8000-000000000002"
        first-paper #uuid "87000000-0000-4000-8000-000000000010"
        second-paper #uuid "87000000-0000-4000-8000-000000000011"
        other-uuid #uuid "87000000-0000-4000-8000-000000000006"
        review {:asset-uuid asset-uuid :extraction {:status :failed}}
        other {:asset-uuid other-uuid :extraction {:status :pending}}
        state* (atom {:literature/review-candidates
                      {repo {(str asset-uuid) review (str other-uuid) other}}})
        db* (atom (d/db-with (current-review-test-db)
                            [{:db/id 10 :block/uuid first-paper
                              :block/title (apply str (repeat 300 "p"))
                              :logseq.property/literature-item? true
                              :logseq.property/literature-assets [2]}]))
        queries (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path) false
                                          (get-in @state* (if (coll? path) path [path]))))
                    state/update-state! (fn [path f & _] (swap! state* update-in path f))
                    db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                    db-async/<q (fn [_ _ query & inputs]
                                  (swap! queries inc)
                                  (p/resolved (apply d/q query @db* inputs)))]
      (p/let [linked (literature/<prepare-review! repo asset-uuid)]
        (is (= {:status :linked :paper {:block/uuid first-paper
                                       :block/title (apply str (repeat 240 "p"))}} linked))
        (is (nil? (literature/review-candidate repo asset-uuid)))
        (is (identical? other (literature/review-candidate repo other-uuid)))
        (swap! state* assoc-in [:literature/review-candidates repo (str asset-uuid)] review)
        (swap! db* d/db-with [{:db/id 11 :block/uuid second-paper :block/title ""
                              :logseq.property/literature-item? true
                              :logseq.property/literature-assets [2]}])
        (p/let [conflict (literature/<prepare-review! repo asset-uuid)]
          (is (= :conflict (:status conflict)))
          (is (= [first-paper second-paper] (mapv :block/uuid (:papers conflict))))
          (is (= [(apply str (repeat 240 "p")) ""] (mapv :block/title (:papers conflict))))
          (is (identical? review (literature/review-candidate repo asset-uuid)))
          (is (identical? other (literature/review-candidate repo other-uuid)))
          (is (= 2 @queries)))))))

(deftest-async prepare-review-rejects-late-graph-repair-or-projection-results
  (p/doseq [change [:graph :repair :projection]]
    (let [repo "synthetic-review-graph"
          asset-uuid #uuid "87000000-0000-4000-8000-000000000002"
          db (current-review-test-db)
          review {:asset-uuid asset-uuid :checksum "old-checksum" :extraction {:status :complete}}
          state* (atom {:literature/review-candidates {repo {(str asset-uuid) review}}})
          active-repo (atom repo)
          restoring? (atom false)
          context (atom {:graph-id repo :projection-epoch 1})
          reply (p/deferred)
          query-result (atom nil)
          queries (atom 0)]
      (p/with-redefs [state/get-current-repo #(deref active-repo)
                      state/get-state (fn [path]
                                        (if (= :db/restoring? path) @restoring?
                                            (get-in @state* (if (coll? path) path [path]))))
                      state/set-state! (fn [path value & _] (swap! state* assoc-in path value))
                      db-subs/projection-context #(deref context)
                      db-async/<q (fn [_ _ query & inputs]
                                    (swap! queries inc)
                                    (reset! query-result (apply d/q query db inputs))
                                    reply)]
        (let [pending (literature/<prepare-review! repo asset-uuid)]
          (case change
            :graph (reset! active-repo "other-graph")
            :repair (reset! restoring? true)
            :projection (swap! context update :projection-epoch inc))
          (p/resolve! reply @query-result)
          (p/let [result pending]
            (is (= {:status :unavailable} result) (str change))
            (is (identical? review (literature/review-candidate repo asset-uuid)))
            (is (= 1 @queries))))))))

(deftest-async prepare-review-checks-entry-context-and-preserves-candidates-on-query-failure
  (let [repo "synthetic-review-graph"
        asset-uuid #uuid "87000000-0000-4000-8000-000000000002"
        review {:asset-uuid asset-uuid :extraction {:status :failed}}
        state* (atom {:literature/review-candidates {repo {(str asset-uuid) review}}})
        active-repo (atom "other-graph")
        restoring? (atom false)
        context (atom {:graph-id "other-graph" :projection-epoch 1})
        failure (ex-info "Synthetic worker query failure" {})
        calls (atom 0)]
    (p/with-redefs [state/get-current-repo #(deref active-repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path) @restoring?
                                          (get-in @state* (if (coll? path) path [path]))))
                    db-subs/projection-context #(deref context)
                    db-async/<q (fn [& _] (swap! calls inc) (p/rejected failure))]
      (p/let [wrong-graph (literature/<prepare-review! repo asset-uuid)
              _ (reset! active-repo repo)
              wrong-context (literature/<prepare-review! repo asset-uuid)
              _ (reset! context {:graph-id repo :projection-epoch 1})
              _ (reset! restoring? true)
              repairing (literature/<prepare-review! repo asset-uuid)
              _ (reset! restoring? false)
              invalid-uuid (literature/<prepare-review! repo nil)
              rejected (-> (literature/<prepare-review! repo asset-uuid)
                           (p/then (fn [_] :unexpected-success))
                           (p/catch (fn [error] {:error error})))]
        (is (= (repeat 4 {:status :unavailable})
               [wrong-graph wrong-context repairing invalid-uuid]))
        (is (identical? failure (:error rejected)))
        (is (identical? review (literature/review-candidate repo asset-uuid)))
        (is (= 1 @calls))))))

(deftest-async review-candidate-pages-are-graph-local-bounded-and-do-not-drop-records
  (let [repo "synthetic-review-graph"
        active-repo (atom repo)
        records (into {}
                      (map (fn [n]
                             (let [asset-uuid (uuid (str "88000000-0000-4000-8000-"
                                                       (.padStart (str n) 12 "0")))]
                               [(str asset-uuid)
                                {:asset-uuid asset-uuid :file-path (str n ".pdf")
                                 :extraction {:status :failed :reason :read-error}
                                 :automatic-result {:reason :missing-identifiers}
                                 :candidates {:title (apply str (repeat 300 "t"))}}]))
                           (range 1 53)))
        state* (atom {:literature/review-candidates
                      {repo (assoc records "88000000-0000-4000-8000-000000000000" nil)
                       "other-graph" {"not-current" {:asset-uuid (random-uuid)}}}})
        before @state*
        calls (atom [])]
    (p/with-redefs [state/get-current-repo #(deref active-repo)
                    state/get-state (fn [path]
                                      (if (= :db/restoring? path) false
                                          (get-in @state* (if (coll? path) path [path]))))
                    db-async/<q (fn [& _] (swap! calls conj :query))
                    fs/read-file-raw (fn [& _] (swap! calls conj :file-read))]
      (let [first-page (literature/review-candidate-page repo nil)
            second-page (literature/review-candidate-page repo (:next-cursor first-page))]
        (is (= :ok (:status first-page)))
        (is (= 50 (count (:entries first-page))))
        (is (= 2 (count (:entries second-page))))
        (is (= (mapv (comp :asset-uuid val) (sort-by key records))
               (mapv :asset-uuid (concat (:entries first-page) (:entries second-page)))))
        (is (= (str (:asset-uuid (peek (:entries first-page)))) (:next-cursor first-page)))
        (is (nil? (:next-cursor second-page)))
        (is (every? #(= 240 (count (:title %))) (:entries first-page)))
        (is (every? #(= [:failed :missing-identifiers] [(:review-status %) (:reason %)])
                    (:entries first-page)))
        (reset! active-repo "other-graph")
        (is (= {:status :unavailable :entries [] :next-cursor nil}
               (literature/review-candidate-page repo nil)))
        (is (= before @state*) "Listing or closing the fold does not mutate its candidates")
        (is (empty? @calls) "Summary pagination uses only graph-local memory")
        (p/resolved nil)))))

(defn- unregistered-pdf-test-db
  []
  (d/db-with
   (d/empty-db {:db/ident {:db/unique :db.unique/identity}
                :block/uuid {:db/unique :db.unique/identity}
                :block/tags {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                :logseq.property/literature-assets {:db/valueType :db.type/ref
                                                   :db/cardinality :db.cardinality/many}})
   (into [{:db/id 1 :db/ident :logseq.class/Asset}]
         (map (fn [eid]
                (cond-> {:db/id eid
                         :block/uuid (uuid (str "85000000-0000-4000-8000-"
                                                (.padStart (str eid) 12 "0")))
                         :block/title (case eid
                                        2 (apply str (repeat 300 "x"))
                                        3 ""
                                        (str "PDF " eid))
                         :block/tags [1]
                         :logseq.property.asset/type "pdf"}
                  (= eid 4) (assoc :logseq.property.asset/literature-auto-excluded? true)))
              (range 2 54)))))

(deftest-async unregistered-pdf-pages-do-not-skip-lookahead-or-require-size
  (let [repo "synthetic-graph"
        db (unregistered-pdf-test-db)
        calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                    db-async/<q (fn [graph opts query & inputs]
                                  (is (= repo graph))
                                  (is (= {} opts))
                                  (swap! calls conj inputs)
                                  (p/resolved (apply d/q query db inputs)))]
      (p/let [first-page (literature/<unregistered-pdfs! repo nil {:include-kept? true})
              second-page (literature/<unregistered-pdfs! repo (:next-cursor first-page) {:include-kept? true})]
        (is (= :ok (:status first-page)))
        (is (= (vec (range 2 52)) (mapv :db/id (:assets first-page))))
        (is (= 51 (:after (:next-cursor first-page))))
        (is (= [52 53] (mapv :db/id (:assets second-page))))
        (is (nil? (:next-cursor second-page)))
        (is (= 240 (count (:block/title (first (:assets first-page))))))
        (is (= "" (:block/title (second (:assets first-page)))))
        (is (true? (:logseq.property.asset/literature-auto-excluded?
                    (nth (:assets first-page) 2))))
        (is (= [50 2] (mapv #(count (first %)) [(second @calls) (last @calls)])))
        (is (= 52 (count (d/q '[:find ?asset :where
                               [?asset :logseq.property.asset/type "pdf"]] db))))))))

(deftest-async unregistered-pdf-page-rechecks-facts-between-queries
  (let [repo "synthetic-graph"
        db* (atom (unregistered-pdf-test-db))
        calls (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                    db-async/<q
                    (fn [_graph _opts query & inputs]
                      (let [result (apply d/q query @db* inputs)]
                        (when (= 1 (swap! calls inc))
                          (swap! db* d/db-with
                                 [{:db/id 100
                                   :block/uuid #uuid "85000000-0000-4000-8000-000000000100"
                                   :block/title "Now linked"
                                   :logseq.property/literature-item? true
                                   :logseq.property/literature-assets [2]}
                                  [:db/add 3 :logseq.property/deleted-at 1]
                                  [:db/add 4 :logseq.property.asset/type "png"]]))
                        (p/resolved result)))]
      (p/let [page (literature/<unregistered-pdfs! repo nil {:include-kept? true})]
        (is (= (vec (range 5 52)) (mapv :db/id (:assets page))))
        (is (= 51 (:after (:next-cursor page))))
        (is (= 2 @calls))))))

(deftest-async unregistered-pdf-query-stops-on-graph-change-or-repair
  (let [repo "synthetic-graph"
        active-repo (atom "other-graph")
        restoring? (atom false)
        context (atom {:graph-id "other-graph" :projection-epoch 1})
        calls (atom 0)]
    (p/with-redefs [state/get-current-repo #(deref active-repo)
                    state/get-state (fn [k] (when (= k :db/restoring?) @restoring?))
                    db-subs/projection-context #(deref context)
                    db-async/<q (fn [& _]
                                  (swap! calls inc)
                                  (reset! active-repo "other-graph")
                                  (p/resolved [2 3]))]
      (p/let [inactive (literature/<unregistered-pdfs! repo nil)
              _ (reset! active-repo repo)
              mismatched-context (literature/<unregistered-pdfs! repo nil)
              _ (reset! context {:graph-id repo :projection-epoch 1})
              _ (reset! restoring? true)
              restoring (literature/<unregistered-pdfs! repo nil)
              _ (reset! restoring? false)
              changed (literature/<unregistered-pdfs! repo nil)]
        (is (= [:unavailable :unavailable :unavailable :unavailable]
               (mapv :status [inactive mismatched-context restoring changed])))
        (is (every? empty? (map :assets [inactive mismatched-context restoring changed])))
        (is (= 1 @calls))))))

(deftest-async unregistered-pdf-cursor-resets-after-same-graph-cutover
  (let [repo "synthetic-graph"
        db (unregistered-pdf-test-db)
        context (atom {:graph-id repo :projection-epoch 1})
        queries (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context #(deref context)
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (swap! queries conj inputs)
                                  (p/resolved (apply d/q query db inputs)))]
      (p/let [first-page (literature/<unregistered-pdfs! repo nil)
              _ (swap! context update :projection-epoch inc)
              repaired-page (literature/<unregistered-pdfs! repo (:next-cursor first-page))]
        (is (= :ok (:status repaired-page)))
        (is (= @context (:projection-context repaired-page)))
        (is (= (:assets first-page) (:assets repaired-page)))
        (is (= [0 0] (mapv first (take-nth 2 @queries))))
        (is (= @context (get-in repaired-page [:next-cursor :projection-context])))))))

(deftest-async unregistered-pdf-page-rejects-cutovers-during-either-query
  (let [repo "synthetic-graph"
        db (unregistered-pdf-test-db)
        context (atom {:graph-id repo :projection-epoch 1})
        change-after (atom 1)
        calls (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context #(deref context)
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (let [result (apply d/q query db inputs)]
                                    (when (= @change-after (swap! calls inc))
                                      (swap! context update :projection-epoch inc))
                                    (p/resolved result)))]
      (p/let [changed-after-ids (literature/<unregistered-pdfs! repo nil)
              _ (reset! calls 0)
              _ (reset! change-after 2)
              changed-after-rows (literature/<unregistered-pdfs! repo nil)]
        (is (= [:unavailable :unavailable]
               (mapv :status [changed-after-ids changed-after-rows])))
        (is (every? empty? (map :assets [changed-after-ids changed-after-rows])))
        (is (every? nil? (map :next-cursor [changed-after-ids changed-after-rows])))
        (is (= 2 @calls))))))

(defn- history-test-uuid
  [eid]
  (uuid (str "89000000-0000-4000-8000-" (.padStart (str eid) 12 "0"))))

(defn- history-test-db
  [entities]
  (d/db-with
   (d/empty-db {:db/ident {:db/unique :db.unique/identity}
                :block/uuid {:db/unique :db.unique/identity}
                :block/alias {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                :block/tags {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                :logseq.property/created-from-property {:db/valueType :db.type/ref}
                :logseq.property/literature-source-target {:db/valueType :db.type/ref}
                :logseq.property/literature-source-key {:db/valueType :db.type/ref
                                                       :db/cardinality :db.cardinality/many}
                :logseq.property/literature-assets {:db/valueType :db.type/ref
                                                   :db/cardinality :db.cardinality/many}})
   (into [{:db/id 1 :db/ident :logseq.class/Asset}
          {:db/id 2 :db/ident :logseq.property/literature-source-key}]
         entities)))

(defn- history-test-asset
  [eid checksum]
  {:db/id eid :block/uuid (history-test-uuid eid) :block/title (str "PDF " eid)
   :block/tags [1] :logseq.property.asset/type "pdf"
   :logseq.property.asset/checksum checksum})

(defn- history-test-key
  [eid binding source-key]
  {:db/id eid :block/uuid (history-test-uuid eid) :block/title source-key
   :logseq.property/created-from-property 2
   :logseq.property/literature-source-target binding})

(defn- history-test-paper
  [eid assets source-keys]
  (cond-> {:db/id eid :block/uuid (history-test-uuid eid)
           :block/title (str "Paper " eid) :logseq.property/literature-item? true}
    (seq assets) (assoc :logseq.property/literature-assets assets)
    (seq source-keys) (assoc :logseq.property/literature-source-key source-keys)))

(defn- history-test-entities
  []
  (let [prefix (apply str (repeat 240 "s"))
        source-key (str prefix "A")]
    (into
     (mapv (fn [eid]
             (cond-> (history-test-asset eid (case eid 10 "shared" 61 "shared"
                                                 11 "pair" 12 "pair" (str "hash-" eid)))
               (= eid 10) (assoc :block/title (apply str (repeat 300 "a"))
                                :logseq.property.asset/literature-auto-excluded? true)
               (= eid 11) (assoc :logseq.property.asset/size 20)))
           (range 10 62))
     [{:db/id 100 :block/uuid (history-test-uuid 100)}
      {:db/id 101 :block/uuid (history-test-uuid 101)}
      {:db/id 102 :block/uuid (history-test-uuid 102) :logseq.property/deleted-at 1}
      {:db/id 103 :block/title "Binding without UUID"}
      (history-test-key 201 100 source-key)
      (history-test-key 202 100 source-key)
      (history-test-key 203 100 (str prefix "B"))
      (history-test-key 204 101 source-key)
      (history-test-key 205 100 source-key)
      (assoc (history-test-key 206 100 source-key) :logseq.property/deleted-at 1)
      (dissoc (history-test-key 207 100 source-key) :logseq.property/created-from-property)
      (dissoc (history-test-key 208 100 source-key) :block/uuid)
      (dissoc (history-test-key 209 100 source-key) :block/title)
      (history-test-key 210 102 source-key)
      (history-test-key 211 103 source-key)
      (dissoc (history-test-key 212 100 source-key) :logseq.property/literature-source-target)
      (assoc (history-test-paper 1000 [10 11] [201 202])
             :block/title (apply str (repeat 300 "p")))
      (history-test-paper 1001 [10] [202])
      (history-test-paper 1002 [61] [203])
      (assoc (history-test-paper 1003 [10] [201]) :logseq.property/deleted-at 1)
      (assoc (history-test-paper 1004 [10] [201]) :logseq.property/literature-item? false)
      (dissoc (history-test-paper 1005 [10] [201]) :block/uuid)
      (dissoc (history-test-paper 1006 [10] [201]) :block/title)
      (dissoc (history-test-paper 1007 [10] [201]) :logseq.property/literature-item?)
      (history-test-paper 1008 [] [203])
      (history-test-paper 1009 [] [204])
      (history-test-paper 1010 [] [204])
      (history-test-paper 1011 [] [206])
      (history-test-paper 1012 [] [207])
      (history-test-paper 1013 [] [208])
      (history-test-paper 1014 [] [209])
      (history-test-paper 1015 [] [210])
      (history-test-paper 1016 [] [211])
      (history-test-paper 1017 [] [212])
      (assoc (history-test-asset 62 "shared") :logseq.property.asset/type "png")
      (assoc (history-test-asset 63 "shared") :logseq.property/deleted-at 1)
      (dissoc (history-test-asset 64 "shared") :block/uuid)
      (dissoc (history-test-asset 65 "shared") :block/title)
      (dissoc (history-test-asset 66 "shared") :block/tags)
      (history-test-asset 67 " ")
      (history-test-asset 68 123)])))

;; Expected rows enumerate fixture maps and edges, without a second Datalog
;; query or a call to the handler's row/DTO helpers.
(defn- history-row-oracle
  [entities kind after]
  (let [by-id (into {} (map (juxt :db/id identity) entities))
        live? #(and (:block/uuid %) (contains? % :block/title)
                    (not (contains? % :logseq.property/deleted-at)))
        pdf? #(and (live? %) (= "pdf" (:logseq.property.asset/type %))
                   (some #{1} (:block/tags %)))
        canonical? #(and (live? %) (= 2 (:logseq.property/created-from-property %))
                         (let [binding (get by-id (:logseq.property/literature-source-target %))]
                           (and (:block/uuid binding)
                                (not (contains? binding :logseq.property/deleted-at)))))
        papers (filter #(and (live? %) (true? (:logseq.property/literature-item? %))) entities)
        subjects (->> entities
                      (filter #(case kind
                                 :asset-owner (pdf? %)
                                 :checksum (and (pdf? %) (string? (:logseq.property.asset/checksum %))
                                                (not (string/blank? (:logseq.property.asset/checksum %))))
                                 :source-owner (canonical? %)))
                      (filter #(> (:db/id %) after))
                      (sort-by :db/id)
                      (take 51)
                      vec)
        rows (keep (fn [subject]
                     (let [eid (:db/id subject)
                           title (:block/title subject)
                           short-title (subs title 0 (min 240 (count title)))
                           checksum (:logseq.property.asset/checksum subject)
                           binding (:logseq.property/literature-source-target subject)
                           matches (case kind
                                     :asset-owner (filter #(some #{eid} (:logseq.property/literature-assets %)) papers)
                                     :checksum (filter #(and (pdf? %) (= checksum (:logseq.property.asset/checksum %))) entities)
                                     :source-owner
                                     (let [keys (->> entities
                                                     (filter #(and (canonical? %) (= title (:block/title %))
                                                                   (= binding (:logseq.property/literature-source-target %))))
                                                     (map :db/id)
                                                     set)]
                                       (filter #(some keys (:logseq.property/literature-source-key %)) papers)))
                           match-count (count (set (map :db/id matches)))]
                       (when (pos? match-count)
                         (into [eid (:block/uuid subject) short-title]
                               (case kind
                                 :asset-owner [match-count]
                                 :checksum [checksum match-count]
                                 :source-owner [(:block/uuid (get by-id binding)) title match-count])))))
                   (take 50 subjects))]
    {:raw (mapv :db/id subjects) :rows (set rows)}))

(deftest-async history-queries-match-independent-enumeration-and-full-identities
  (let [repo "synthetic-history-graph"
        entities (history-test-entities)
        db (history-test-db entities)
        context {:graph-id repo :projection-epoch 1}
        results (atom [])
        source-key (str (apply str (repeat 240 "s")) "A")]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly context)
                    db-async/<q (fn [graph opts query & inputs]
                                  (is (= [repo {}] [graph opts]))
                                  (let [result (apply d/q query db inputs)]
                                    (swap! results conj {:inputs inputs :result result})
                                    (p/resolved result)))]
      (p/doseq [kind [:asset-owner :checksum :source-owner]]
        (reset! results [])
        (p/let [page (literature/<history-conflicts! repo kind nil)]
          (let [expected (history-row-oracle entities kind 0)]
            (is (= (:raw expected) (:result (first @results))) (str kind " Q1"))
            (is (= (:rows expected) (set (:result (second @results)))) (str kind " every Q2 column"))
            (is (= (vec (take 50 (:raw expected))) (first (:inputs (second @results)))))
            (is (= :ok (:status page)))
            (is (= context (:projection-context page)))
            (case kind
              :asset-owner (is (= [[(history-test-uuid 10) 2]]
                                  (mapv (juxt :identity :count) (:entries page))))
              :checksum (is (= [["shared" 2] ["pair" 2]]
                               (mapv (juxt :identity :count) (:entries page))))
              :source-owner
              (do
                (is (= [[[(history-test-uuid 100) source-key] 2]
                        [[(history-test-uuid 100) (str (apply str (repeat 240 "s")) "B")] 2]
                        [[(history-test-uuid 101) source-key] 2]]
                       (mapv (juxt :identity :count) (:entries page))))
                (is (= [(history-test-uuid 201) (history-test-uuid 203) (history-test-uuid 204)]
                       (mapv :key-uuid (:entries page))))
                (is (= 1 (count (set (map :title (:entries page))))))
                (is (nil? (:next-cursor page)))))))))))

(deftest-async history-raw-pagination-keeps-empty-pages-lookahead-and-final-nil
  (let [repo "synthetic-history-graph"
        entities (into [{:db/id 100 :block/uuid (history-test-uuid 100)}
                        (history-test-paper 1000 [60 61] [160])
                        (history-test-paper 1001 [60 61] [161])]
                       (concat (map #(history-test-asset % (if (< % 60) (str "hash-" %) "tail-hash"))
                                    (range 10 62))
                               (map #(history-test-key % 100 (if (< % 160) (str "key-" %) "tail-key"))
                                    (range 110 162))))
        db (history-test-db entities)
        context {:graph-id repo :projection-epoch 1}
        calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly context)
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (let [result (apply d/q query db inputs)]
                                    (swap! calls conj {:inputs inputs :result result})
                                    (p/resolved result)))]
      (p/doseq [kind [:asset-owner :checksum :source-owner]]
        (reset! calls [])
        (p/let [first-page (literature/<history-conflicts! repo kind nil)
                second-page (literature/<history-conflicts! repo kind (:next-cursor first-page))
                empty-page (literature/<history-conflicts! repo kind
                            {:repo repo :kind kind :projection-context context :after 9999})]
          (let [first-expected (history-row-oracle entities kind 0)
                after (if (= kind :source-owner) 159 59)
                second-expected (history-row-oracle entities kind after)]
            (is (= 51 (count (:result (first @calls)))))
            (is (= (:raw first-expected) (:result (first @calls))))
            (is (= (:rows first-expected) (set (:result (second @calls)))))
            (is (empty? (:entries first-page)))
            (when (not= kind :checksum)
              (is (empty? (:result (second @calls))) "Raw 51 with empty Q2 must retain Next"))
            (is (= {:repo repo :kind kind :projection-context context :after after}
                   (:next-cursor first-page)))
            (is (= (:raw second-expected) (:result (nth @calls 2))))
            (is (= (:rows second-expected) (set (:result (nth @calls 3)))))
            (is (= (if (= kind :asset-owner) 2 1) (count (:entries second-page))))
            (is (nil? (:next-cursor second-page)))
            (is (nil? (:result (last @calls))) "Empty min 51 aggregate is nil")
            (is (= :ok (:status empty-page)))
            (is (empty? (:entries empty-page)))
            (is (nil? (:next-cursor empty-page)))
            (is (= 5 (count @calls)) "Empty raw result has no Q2")))))))

(deftest-async history-complete-groups-keep-all-members-owners-and-physical-edges
  (let [repo "synthetic-history-graph"
        db (history-test-db (history-test-entities))
        context {:graph-id repo :projection-epoch 1}
        source-key (str (apply str (repeat 240 "s")) "A")
        calls (atom 0)
        plan-calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly context)
                    db-async/<duplicate-pdf-plans
                    (fn [graph asset-uuids]
                      (is (= repo graph))
                      (swap! plan-calls conj asset-uuids)
                      (p/resolved (into {} (map (fn [asset-uuid]
                                                 [asset-uuid {:status :ready
                                                              :annotation-count 0
                                                              :reference-count 0}]) asset-uuids))))
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (swap! calls inc)
                                  (p/resolved (apply d/q query db inputs)))]
      (p/let [asset (literature/<history-conflict-group! repo :asset-owner
                     {:asset-uuid (history-test-uuid 10)})
              checksum (literature/<history-conflict-group! repo :checksum
                        {:asset-uuid (history-test-uuid 10) :checksum "shared"})
              orphan (literature/<history-conflict-group! repo :checksum
                      {:asset-uuid (history-test-uuid 11) :checksum "pair"})
              source (literature/<history-conflict-group! repo :source-owner
                      {:key-uuid (history-test-uuid 201) :binding-uuid (history-test-uuid 100)
                       :source-key source-key})]
        (is (= (repeat 4 :ok) (map :status [asset checksum orphan source])))
        (is (= (repeat 4 context) (map :projection-context [asset checksum orphan source])))
        (is (= [(history-test-uuid 10)] (mapv :block/uuid (:members asset))))
        (is (= [[(history-test-uuid 1000) (history-test-uuid 1001)]]
               (mapv #(mapv :block/uuid (:papers %)) (:members asset))))
        (is (= [(history-test-uuid 10) (history-test-uuid 61)]
               (mapv :block/uuid (:members checksum))) "Complete checksum group includes a member beyond raw 50")
        (is (true? (:logseq.property.asset/literature-auto-excluded? (first (:members checksum)))))
        (is (not (contains? (first (:members checksum)) :logseq.property.asset/size)))
        (is (= [(history-test-uuid 1002)]
               (mapv :block/uuid (:papers (second (:members checksum))))))
        (is (= [20 nil] (mapv :logseq.property.asset/size (:members orphan))))
        (is (= [[(history-test-uuid 1000)] []]
               (mapv #(mapv :block/uuid (:papers %)) (:members orphan))))
        (is (= [{:key-uuid (history-test-uuid 201) :binding-uuid (history-test-uuid 100)
                 :source-key source-key
                 :papers [{:block/uuid (history-test-uuid 1000) :block/title (apply str (repeat 240 "p"))}]}
                {:key-uuid (history-test-uuid 202) :binding-uuid (history-test-uuid 100)
                 :source-key source-key
                 :papers [{:block/uuid (history-test-uuid 1000) :block/title (apply str (repeat 240 "p"))}
                          {:block/uuid (history-test-uuid 1001) :block/title "Paper 1001"}]}
                {:key-uuid (history-test-uuid 205) :binding-uuid (history-test-uuid 100)
                 :source-key source-key :papers []}]
               (:members source)) "Each actual key keeps its own exact edges, including the zero-owner key")
        (is (= 4 @calls) "Each selected group is one fresh DB query")
        (is (= [[(history-test-uuid 10) (history-test-uuid 61)]
                [(history-test-uuid 11) (history-test-uuid 12)]]
               @plan-calls))))))

(deftest-async history-detail-rechecks-anchor-identity-and-current-facts
  (let [repo "synthetic-history-graph"
        db* (atom (history-test-db (history-test-entities)))
        source-entry {:key-uuid (history-test-uuid 201) :binding-uuid (history-test-uuid 100)
                      :source-key (str (apply str (repeat 240 "s")) "A")}]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (p/resolved (apply d/q query @db* inputs)))]
      (p/let [asset-page (literature/<history-conflicts! repo :asset-owner nil)
              _ (swap! db* d/db-with [(history-test-paper 1020 [10] [])])
              fresh-owners (literature/<history-conflict-group! repo :asset-owner (first (:entries asset-page)))
              page (literature/<history-conflicts! repo :checksum nil)
              _ (swap! db* d/db-with [[:db/add 10 :logseq.property.asset/checksum "changed"]])
              changed-checksum (literature/<history-conflict-group! repo :checksum (first (:entries page)))
              _ (swap! db* d/db-with [[:db/add 201 :block/title "changed"]])
              changed-key (literature/<history-conflict-group! repo :source-owner source-entry)
              _ (swap! db* d/db-with [[:db/add 201 :block/title (:source-key source-entry)]
                                    [:db/add 201 :logseq.property/literature-source-target 101]])
              changed-binding (literature/<history-conflict-group! repo :source-owner source-entry)
              _ (swap! db* d/db-with [[:db/add 201 :logseq.property/literature-source-target 100]
                                    [:db/add 100 :logseq.property/deleted-at 1]])
              deleted-binding (literature/<history-conflict-group! repo :source-owner source-entry)
              _ (swap! db* d/db-with [[:db/add 10 :logseq.property/deleted-at 1]])
              deleted-asset (literature/<history-conflict-group! repo :asset-owner {:asset-uuid (history-test-uuid 10)})
              missing-key (literature/<history-conflict-group! repo :source-owner
                           (assoc source-entry :key-uuid (history-test-uuid 9999)))]
        (is (= [(history-test-uuid 1000) (history-test-uuid 1001) (history-test-uuid 1020)]
               (mapv :block/uuid (:papers (first (:members fresh-owners)))))
            "Selected details use fresh owners rather than the summary's snapshot")
        (is (= (repeat 6 {:status :unavailable :members []})
               [changed-checksum changed-key changed-binding deleted-binding deleted-asset missing-key]))))))

(deftest-async history-cursor-resets-for-repo-kind-and-projection-and-rechecks-q2
  (let [repo "synthetic-history-graph"
        db* (atom (history-test-db (history-test-entities)))
        context (atom {:graph-id repo :projection-epoch 1})
        calls (atom [])
        change-facts? (atom false)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context #(deref context)
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (let [result (apply d/q query @db* inputs)]
                                    (swap! calls conj inputs)
                                    (when (and @change-facts? (number? (first inputs)))
                                      (reset! change-facts? false)
                                      (swap! db* d/db-with
                                             (mapv #(vector :db/add % :logseq.property/deleted-at 1)
                                                   (take 50 result))))
                                    (p/resolved result)))]
      (p/let [page (literature/<history-conflicts! repo :checksum nil)
              cursor (:next-cursor page)
              _ (p/doseq [changed [(assoc cursor :repo "other-graph")
                                   (assoc cursor :kind :source-owner)
                                   (assoc cursor :projection-context {:graph-id repo :projection-epoch 0})]]
                  (reset! calls [])
                  (p/let [restarted (literature/<history-conflicts! repo :checksum changed)]
                    (is (= 0 (ffirst @calls)))
                    (is (= (:entries page) (:entries restarted)))))
              _ (swap! context update :projection-epoch inc)
              repaired (literature/<history-conflicts! repo :checksum cursor)
              _ (reset! change-facts? true)
              changed (literature/<history-conflicts! repo :checksum nil)]
        (is (= (:entries page) (:entries repaired)))
        (is (= @context (:projection-context repaired)))
        (is (empty? (:entries changed)) "Q2 sees facts that changed after Q1")
        (is (= 59 (get-in changed [:next-cursor :after])) "Cursor advances over raw subjects, even with empty Q2")))))

(deftest-async history-guards-entry-and-each-actual-query-without-swallowing-errors
  (let [repo "synthetic-history-graph"
        db (history-test-db (history-test-entities))
        active-repo (atom repo)
        restoring? (atom false)
        context (atom {:graph-id repo :projection-epoch 1})
        queries (atom 0)
        change-after (atom nil)
        change (atom nil)
        failure (ex-info "Synthetic history query failure" {})
        reject? (atom false)
        entry {:asset-uuid (history-test-uuid 10)}]
    (p/with-redefs [state/get-current-repo #(deref active-repo)
                    state/get-state (fn [k] (when (= k :db/restoring?) @restoring?))
                    db-subs/projection-context #(deref context)
                    db-async/<q (fn [_graph _opts query & inputs]
                                  (let [n (swap! queries inc)]
                                    (when (= n @change-after)
                                      (case @change
                                        :graph (reset! active-repo "other-graph")
                                        :repair (reset! restoring? true)
                                        :projection (swap! context update :projection-epoch inc)))
                                    (if @reject?
                                      (p/rejected failure)
                                      (p/resolved (apply d/q query db inputs)))))]
      (p/let [_ (p/doseq [boundary [:graph :repair :context :kind :anchor]]
                  (reset! active-repo (if (= boundary :graph) "other-graph" repo))
                  (reset! restoring? (= boundary :repair))
                  (reset! context {:graph-id (if (= boundary :context) "other-graph" repo) :projection-epoch 1})
                  (reset! queries 0)
                  (p/let [page (literature/<history-conflicts! repo (if (= boundary :kind) :invalid :asset-owner) nil)
                          group (literature/<history-conflict-group! repo
                                  (if (= boundary :kind) :invalid :asset-owner)
                                  (if (= boundary :anchor) {} entry))]
                    (when (not= boundary :anchor)
                      (is (= :unavailable (:status page))))
                    (is (= :unavailable (:status group)))
                    (is (= (if (= boundary :anchor) 2 0) @queries))))
              _ (p/doseq [boundary [:graph :repair :projection]]
                  (p/doseq [phase [1 2]]
                    (reset! active-repo repo)
                    (reset! restoring? false)
                    (reset! context {:graph-id repo :projection-epoch 1})
                    (reset! queries 0)
                    (reset! change boundary)
                    (reset! change-after phase)
                    (p/let [page (literature/<history-conflicts! repo :asset-owner nil)]
                      (is (= {:status :unavailable :entries [] :next-cursor nil} page))
                      (is (= phase @queries) "A stale Q1 must not issue the actual Q2"))))
              _ (p/doseq [boundary [:graph :repair :projection]]
                  (reset! active-repo repo)
                  (reset! restoring? false)
                  (reset! context {:graph-id repo :projection-epoch 1})
                  (reset! queries 0)
                  (reset! change boundary)
                  (reset! change-after 1)
                  (p/let [group (literature/<history-conflict-group! repo :asset-owner entry)]
                    (is (= {:status :unavailable :members []} group))
                    (is (= 1 @queries))))
              _ (p/doseq [read [(fn [] (literature/<history-conflicts! repo :asset-owner nil))
                               (fn [] (literature/<history-conflict-group! repo :asset-owner entry))]]
                  (reset! active-repo repo)
                  (reset! restoring? false)
                  (reset! context {:graph-id repo :projection-epoch 1})
                  (reset! queries 0)
                  (reset! change-after nil)
                  (let [pending (read)]
                    (is (= 1 @queries) "The first actual query runs immediately after its context guard")
                    (reset! active-repo "other-graph")
                    (p/let [result pending]
                      (is (= :unavailable (:status result)))
                      (is (= 1 @queries)))))]
        (reset! active-repo repo)
        (reset! restoring? false)
        (reset! context {:graph-id repo :projection-epoch 1})
        (reset! change-after nil)
        (reset! reject? true)
        (p/doseq [read [(fn [] (literature/<history-conflicts! repo :asset-owner nil))
                       (fn [] (literature/<history-conflict-group! repo :asset-owner entry))]]
          (p/let [result (-> (read) (p/then (constantly :unexpected-success))
                            (p/catch (fn [error] {:error error})))]
            (is (identical? failure (:error result)))))))))

(deftest-async existing-paper-search-is-explicit-and-selected-link-clears-review
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65367"
        paper-uuid #uuid "93f02d4b-0ef5-450f-95a8-48bc9fe65367"
        search-calls (atom [])
        state* (atom {:literature/review-candidates
                      {repo {(str asset-uuid) {:asset-uuid asset-uuid}}}})
        read-state (fn [path]
                     (if (= :db/restoring? path)
                       false
                       (get-in @state* (if (coll? path) path [path]))))]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state read-state
                    state/update-state! (fn [path f & _]
                                          (swap! state* update-in path f))
                    db-async/<search-literature-papers
                    (fn [repo' query]
                      (swap! search-calls conj [repo' query])
                      (p/resolved {:status :ok
                                   :papers [{:block/uuid paper-uuid
                                             :block/title "Existing paper"}]
                                   :truncated? false}))
                    db-async/<link-pdf-to-literature
                    (fn [repo' asset-uuid' paper-uuid']
                      (is (= [repo asset-uuid paper-uuid]
                             [repo' asset-uuid' paper-uuid']))
                      (p/resolved {:status :linked
                                   :paper-uuid paper-uuid}))]
      (p/let [short-result (literature/<search-existing-papers! repo "a")
              search-result (literature/<search-existing-papers! repo "李明")
              link-result (literature/<link-pdf-to-paper!
                           repo asset-uuid paper-uuid)]
        (is (= :idle (:status short-result)))
        (is (= [[repo "李明"]] @search-calls))
        (is (= paper-uuid (get-in search-result [:papers 0 :block/uuid])))
        (is (= :linked (:status link-result)))
        (is (nil? (literature/review-candidate repo asset-uuid)))))))

(deftest-async retry-uses-only-the-saved-lightweight-entry
  (let [repo "repo"
        asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65365"
        review {:asset-uuid asset-uuid
                :file-path "paper.pdf"
                :checksum "checksum"
                :byte-size 42
                :repo-dir "/repo"
                :asset-dir-rpath "assets"
                :candidates {:identifiers []}}
        state* (atom {:literature/review-candidates
                      {repo {(str asset-uuid) review}}})
        read-state (fn [path]
                     (if (= :db/restoring? path)
                       false
                       (get-in @state* (if (coll? path) path [path]))))]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state read-state
                    state/set-state! (fn [path value & _]
                                       (swap! state* assoc-in path value))
                    literature/schedule-new-pdf-identification!
                    (fn [repo' repo-dir asset-dir-rpath entries & {:keys [on-result]}]
                      (is (= [repo "/repo" "assets"]
                             [repo' repo-dir asset-dir-rpath]))
                      (is (= [{:asset-uuid asset-uuid
                               :file-path "paper.pdf"
                               :checksum "checksum"
                               :byte-size 42}]
                             entries))
                      (on-result (assoc (first entries)
                                        :candidates {:title "Retried"
                                                     :identifiers []}))
                      (p/resolved {:status :complete}))]
      (p/let [result (literature/<retry-review-candidate! repo asset-uuid)]
        (is (= :complete (:status result)))
        (is (= "Retried"
               (get-in @state*
                       [:literature/review-candidates repo (str asset-uuid)
                        :candidates :title])))))))

(deftest-async scheduler-reads-saved-paths-sequentially-and-rejects-overlap
  (let [first-extraction (p/deferred)
        reads (atom [])
        active (atom 0)
        max-active (atom 0)
        callbacks (atom [])
        entries [{:asset-uuid #uuid "27a6d184-cbbc-4a23-8dbf-d4d7e1cefe4c"
                  :file-path "first.pdf"
                  :checksum "714519ba92124708119ff85f79905eab53dd7704ce422ad250a4f2ab9a813cb8"
                  :byte-size 100}
                 {:asset-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65364"
                  :file-path "second.pdf"
                  :checksum "9af934aaf747daf8486f1e2df1a831df98dbb50a3fd0e6e0f57df34e9dfd4a2f"
                  :byte-size 100}]]
    (p/with-redefs [state/get-current-repo (constantly "repo")
                    state/get-state (fn [key]
                                      (when (= :db/restoring? key) false))
                    fs/read-file-raw (fn [dir path]
                                       (swap! reads conj [dir path])
                                       (p/resolved path))
                    literature/<extract-first-page
                    (fn [pdf-bytes _signal]
                      (let [current (swap! active inc)]
                        (swap! max-active max current)
                        (-> (if (= "assets/first.pdf" pdf-bytes)
                              first-extraction
                              (p/resolved complete-extraction))
                            (p/finally #(swap! active dec)))))]
      (let [first-task (literature/schedule-new-pdf-identification!
                        "repo" "/repo" "assets" entries
                        :on-result #(swap! callbacks conj %))]
        (p/let [overlap (literature/schedule-new-pdf-identification!
                         "repo" "/repo" "assets" entries)
                _ (p/resolve! first-extraction complete-extraction)
                result first-task]
          (is (= :busy (:status overlap)))
          (is (= :complete (:status result)))
          (is (= [["/repo" "assets/first.pdf"]
                  ["/repo" "assets/second.pdf"]]
                 @reads))
          (is (= 1 @max-active))
          (is (every? #(nil? (get-in % [:extraction :items])) @callbacks))
          (is (= (mapv :asset-uuid entries)
                 (mapv :asset-uuid @callbacks))))))))

(deftest-async scheduler-reads-electron-pdfs-through-native-assets
  (let [asset-uuid #uuid "da885129-0068-4608-a648-72f66caf37a4"
        payload (.-buffer (js/Uint8Array. #js [37 80 68 70]))
        read-calls (atom [])
        callbacks (atom [])]
    (p/with-redefs [util/electron? (constantly true)
                    state/get-current-repo (constantly "repo")
                    state/get-state (constantly false)
                    js/fetch (fn [url options]
                               (swap! read-calls conj url)
                               (is (instance? js/AbortSignal (.-signal options)))
                               (p/resolved #js {:ok true
                                                :arrayBuffer (fn [] (p/resolved payload))}))
                    ipc/ipc (fn [& _]
                              (throw (js/Error. "Unexpected whole-file IPC read")))
                    fs/read-file-raw (fn [& _]
                                       (throw (js/Error. "Unexpected encoded PDF read")))
                    literature/<extract-first-page
                    (fn [pdf-bytes _signal]
                      (is (identical? payload pdf-bytes))
                      (is (= [37 80 68 70] (vec (array-seq (js/Uint8Array. pdf-bytes)))))
                      (p/resolved complete-extraction))]
      (p/let [result (literature/schedule-new-pdf-identification!
                      "repo" "/synthetic-graph" "assets"
                      [{:asset-uuid asset-uuid :file-path "paper 测试.pdf" :byte-size 4}]
                      :on-result #(swap! callbacks conj %))]
        (is (= :complete (:status result)))
        (is (= ["assets:///synthetic-graph/assets/paper%20%E6%B5%8B%E8%AF%95.pdf"] @read-calls))
        (is (= :complete (get-in (first @callbacks) [:extraction :status])))
        (is (nil? (:pdf-bytes (first @callbacks))))))))

(deftest-async scheduler-waits-for-each-result-consumer
  (let [first-uuid #uuid "27a6d184-cbbc-4a23-8dbf-d4d7e1cefe4d"
        second-uuid #uuid "83f02d4b-0ef5-450f-95a8-48bc9fe65365"
        callback-started (p/deferred)
        release-first-callback (p/deferred)
        reads (atom [])]
    (p/with-redefs [state/get-current-repo (constantly "repo")
                    state/get-state (constantly false)
                    fs/read-file-raw (fn [_dir path]
                                       (swap! reads conj path)
                                       (p/resolved path))
                    literature/<extract-first-page
                    (fn [& _] (p/resolved complete-extraction))]
      (let [task (literature/schedule-new-pdf-identification!
                  "repo" "/repo" "assets"
                  [{:asset-uuid first-uuid :file-path "first.pdf" :byte-size 100}
                   {:asset-uuid second-uuid :file-path "second.pdf" :byte-size 100}]
                  :on-result
                  (fn [{:keys [asset-uuid]}]
                    (if (= first-uuid asset-uuid)
                      (do
                        (p/resolve! callback-started true)
                        release-first-callback)
                      (p/resolved nil))))]
        (p/let [_ callback-started]
          (is (= ["assets/first.pdf"] @reads))
          (p/resolve! release-first-callback true)
          (p/let [result task]
            (is (= :complete (:status result)))
            (is (= ["assets/first.pdf" "assets/second.pdf"] @reads))))))))

(deftest-async scheduler-skips-large-pdfs-without-reading-them
  (let [large-uuid #uuid "da885129-0068-4608-a648-72f66caf37a2"
        small-uuid #uuid "51f28432-8535-436e-83e1-bc5641d79a88"
        reads (atom [])
        callbacks (atom [])]
    (p/with-redefs [state/get-current-repo (constantly "repo")
                    state/get-state (constantly false)
                    fs/read-file-raw (fn [_dir path]
                                       (swap! reads conj path)
                                       (p/resolved "bytes"))
                    literature/<extract-first-page (fn [& _]
                                                     (p/resolved complete-extraction))]
      (p/let [result (literature/schedule-new-pdf-identification!
                      "repo" "/repo" "assets"
                      [{:asset-uuid large-uuid
                        :file-path "large.pdf"
                        :byte-size (inc (* 32 1024 1024))}
                       {:asset-uuid small-uuid
                        :file-path "small.pdf"
                        :byte-size (* 32 1024 1024)}]
                      :on-result #(swap! callbacks conj %))]
        (is (= :complete (:status result)))
        (is (= ["assets/small.pdf"] @reads))
        (is (= [large-uuid small-uuid] (mapv :asset-uuid @callbacks)))
        (is (= {:status :skipped :reason :file-size-limit}
               (:extraction (first @callbacks))))
        (is (false? (get-in (first @callbacks) [:candidates :usable?])))))))

(deftest-async scheduler-retains-a-manual-review-record-after-read-failure
  (let [asset-uuid #uuid "da885129-0068-4608-a648-72f66caf37a3"
        callbacks (atom [])]
    (p/with-redefs [util/electron? (constantly true)
                    state/get-current-repo (constantly "repo")
                    state/get-state (constantly false)
                    js/fetch (fn [& _]
                               (p/resolved #js {:ok false :status 404
                                                :arrayBuffer (fn []
                                                               (throw (js/Error. "Unexpected failed-response read")))}))]
      (p/let [result (literature/schedule-new-pdf-identification!
                      "repo" "/repo" "assets"
                      [{:asset-uuid asset-uuid
                        :file-path "failed.pdf"
                        :byte-size 100}]
                      :on-result #(swap! callbacks conj %))]
        (is (= :complete (:status result)))
        (is (= 1 (count @callbacks)))
        (is (= {:status :failed :reason :file-not-local}
               (:extraction (first @callbacks))))
        (is (false? (get-in (first @callbacks) [:candidates :usable?])))))))

(deftest-async scheduler-cancels-when-the-current-graph-changes
  (let [repo (atom "repo")
        read-started (p/deferred)]
    (p/with-redefs [util/electron? (constantly true)
                    state/get-current-repo #(deref repo)
                    state/get-state (constantly false)
                    js/fetch
                    (fn [_url options]
                      (let [signal (.-signal options)]
                        (p/resolve! read-started signal)
                        (p/create
                         (fn [_resolve reject]
                           (.addEventListener
                            signal
                            "abort"
                            #(reject (js/DOMException. "Cancelled" "AbortError"))
                            #js {:once true})))))
                    literature/<extract-first-page
                    (fn [& _] (throw (js/Error. "Unexpected extraction after cancelled read")))]
      (let [task (literature/schedule-new-pdf-identification!
                  "repo" "/repo" "assets"
                  [{:asset-uuid (random-uuid) :file-path "paper.pdf" :byte-size 100}])]
        (p/let [signal read-started
                _ (reset! repo "other-repo")
                result task]
          (is (.-aborted signal))
          (is (= :cancelled (:status result))))))))

(deftest-async reconnecting-to-a-different-library-uses-a-new-source-identity
  (let [repo "synthetic-repo"
        first-uuid #uuid "79000000-0000-4000-8000-000000000014"
        library-key (atom "LOCAL001")
        stored (atom {:ls-literature-settings {repo {:enabled? true}}})]
    (p/with-redefs [storage/get (fn [key] (get @stored key))
                   storage/set (fn [key value] (swap! stored assoc key value))
                   state/get-current-repo (constantly repo)
                   state/get-state (constantly false)
                   zotero/<read-library-key! (fn [] (p/resolved @library-key))
                   db-async/<ensure-literature-source-binding
                   (fn [_repo uuid _destination]
                     (p/resolved {:status :existing :binding-uuid uuid
                                  :destination-page-uuid destination-page-uuid}))
                   literature/reconcile-zotero-lifecycle! (constantly nil)]
      (p/let [a (literature/<connect-local-zotero! repo first-uuid nil)]
        (literature/set-zotero-binding! repo nil)
        (reset! library-key "OTHER002")
        (p/let [b (literature/<connect-local-zotero! repo first-uuid nil)]
          (is (= :connected (:status b)))
          (is (not= (get-in a [:binding :graph-binding-uuid])
                    (get-in b [:binding :graph-binding-uuid])))
          (is (= "OTHER002" (get-in b [:binding :zotero-library-key])))
          (is (= destination-page-uuid (get-in b [:binding :destination-page-uuid]))))))))


(deftest-async scheduler-checks-saved-content-before-extraction
  (let [healthy (.-buffer (js/Uint8Array. #js [37 80 68 70]))
        changed (.-buffer (js/Uint8Array. #js [37 80 68 71]))
        reads (atom [])
        extracted (atom [])
        callbacks (atom [])]
    (p/let [checksum (db-asset/<get-file-array-buffer-checksum healthy)]
      (p/with-redefs [util/electron? (constantly false)
                      state/get-current-repo (constantly "repo")
                      state/get-state (constantly false)
                      fs/read-file-raw (fn [_dir path]
                                         (swap! reads conj path)
                                         (p/resolved (if (= "assets/changed.pdf" path) changed healthy)))
                      literature/<extract-first-page
                      (fn [payload _signal]
                        (swap! extracted conj payload)
                        (p/resolved complete-extraction))]
        (p/let [result (literature/schedule-new-pdf-identification!
                        "repo" "/repo" "assets"
                        [{:asset-uuid (random-uuid) :file-path "changed.pdf" :checksum checksum :byte-size 4}
                         {:asset-uuid (random-uuid) :file-path "healthy.pdf" :checksum checksum :byte-size 4}]
                        :on-result #(swap! callbacks conj %))]
          (is (= :complete (:status result)))
          (is (= ["assets/changed.pdf" "assets/healthy.pdf"] @reads))
          (is (= 1 (count @extracted)))
          (is (identical? healthy (first @extracted)))
          (is (= {:status :failed :reason :content-mismatch} (:extraction (first @callbacks))))
          (is (false? (get-in (first @callbacks) [:candidates :usable?])))
          (is (= :complete (get-in (second @callbacks) [:extraction :status]))))))))

(deftest library-window-bounds-visible-papers-and-keeps-lookahead
  (let [schema {:block/uuid {:db/unique :db.unique/identity}
                :block/parent {:db/valueType :db.type/ref}
                :logseq.property/literature-authors {:db/valueType :db.type/ref}
                :logseq.property/literature-date {:db/valueType :db.type/ref}
                :logseq.property/literature-doi {:db/valueType :db.type/ref}
                :logseq.property/literature-arxiv {:db/valueType :db.type/ref}}
        db (d/db-with
            (d/empty-db schema)
            (concat [{:db/id 1000 :block/uuid (random-uuid) :logseq.property/hide? true}]
                    (for [eid (range 2 57)]
                      (cond-> {:db/id eid :block/uuid (random-uuid)
                               :block/title (apply str (repeat 300 "题"))
                               :logseq.property/literature-item? true}
                        (> eid 54) (assoc :block/parent 1000)))))
        window (fn [db before]
                 (literature/library-window
                  [(ffirst (d/q literature/library-window-query db before literature/library-hidden-rules))]))
        first-window (window db js/Number.MAX_SAFE_INTEGER)
        second-window (window db (nth first-window 49))
        rows (d/q literature/library-rows-query db (vec (take 50 first-window))
                  literature/library-hidden-rules)]
    (is (= (vec (range 54 3 -1)) first-window))
    (is (= [4 3 2] second-window) "The 51st item starts the next page")
    (is (= 50 (count rows)))
    (is (every? #(= 240 (count (nth % 2))) rows))
    (is (= 56 (first (window (d/db-with db [[:db/retract 1000 :logseq.property/hide? true]])
                            js/Number.MAX_SAFE_INTEGER))))))

(deftest-async ordinary-pdf-filter-applies-before-the-page-window
  (let [repo "synthetic-graph" db (unregistered-pdf-test-db)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                    state/get-state (constantly false)
                    db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                    db-async/<q (fn [_ _ query & inputs] (p/resolved (apply d/q query db inputs)))]
      (p/let [page (literature/<unregistered-pdfs! repo nil)]
        (is (= 50 (count (:assets page))))
        (is (not (some #(= 4 (:db/id %)) (:assets page))))
        (is (= 52 (:after (:next-cursor page))))))))


(deftest-async history-organization-pages-serially-and-preserves-new-pdf-permission
  (let [repo "synthetic-graph"
        db* (atom (unregistered-pdf-test-db))
        local (atom {})
        extracted (atom [])
        registered (atom [])
        metadata-calls (atom 0)
        started (p/deferred)
        release-first (p/deferred)
        queued-id #uuid "85000000-0000-4000-8000-000000000999"
        result-for (fn [entry]
                     (assoc entry :extraction {:status :complete :reason :page-end}
                            :candidates {:usable? true :title "A Verified Paper"
                                         :author-text "Alice Example"
                                         :conflicts {:title? false}
                                         :identifiers [{:kind :doi :value "10.5555/verified"
                                                        :reference-context? false}]}))]
    (p/with-redefs [state/get-current-repo (constantly repo)
                   state/get-state (fn [key] (if (vector? key) (get-in @local key) (get @local key)))
                   state/set-state! (fn [path value & _] (swap! local assoc-in path value))
                   state/update-state! (fn [path f & args] (apply swap! local update-in path f args))
                   db-subs/projection-context (constantly {:graph-id repo :projection-epoch 1})
                   storage/get (constantly {})
                   storage/set (fn [& _] (throw (js/Error. "One-off organization must not change settings")))
                   config/get-repo-dir (constantly "/synthetic-graph")
                   util/electron? (constantly true)
                   db-async/<q (fn [_ _ query & inputs] (p/resolved (apply d/q query @db* inputs)))
                   literature/<identify-saved-pdf!
                   (fn [_ _ entry _]
                     (swap! extracted conj (:asset-uuid entry))
                     (if (= 1 (count @extracted))
                       (do (p/resolve! started true) (p/then release-first #(result-for entry)))
                       (p/resolved (result-for entry))))
                   db-async/<auto-link-pdf-to-literature (fn [& _] (p/resolved {:status :blocked :reason :identifier-not-found}))
                   metadata/<lookup-record! (fn [& _] (swap! metadata-calls inc) (p/resolved {:status :ready :record {}}))
                   db-async/<register-verified-pdf-as-literature
                   (fn [_ asset-uuid _ _]
                     (swap! registered conj asset-uuid)
                     (swap! db* d/db-with [{:db/id (+ 1000 (count @registered))
                                           :block/uuid (random-uuid) :block/title "Registered"
                                           :logseq.property/literature-item? true
                                           :logseq.property/literature-assets [[:block/uuid asset-uuid]]}])
                     (p/resolved {:status :created}))]
      (let [task (literature/<organize-existing-pdfs! repo true)]
        (p/let [_ started
                queued (literature/schedule-new-pdf-identification!
                        repo "/synthetic-graph" "assets"
                        [{:asset-uuid queued-id :file-path "new.pdf" :byte-size 100}]
                        :on-result #(literature/<store-and-maybe-auto-link! repo "/synthetic-graph" "assets" %))
                _ (p/resolve! release-first true)
                result task
                remaining (literature/<unregistered-pdfs! repo nil)]
          (is (= :queued (:status queued)))
          (is (= :complete (:status result)))
          (is (= {:checked 51 :created 51 :linked 0 :pending 0 :unavailable 0}
                 (select-keys result [:checked :created :linked :pending :unavailable])))
          (is (= queued-id (second @extracted)) "Newly saved PDFs run before the next historical file")
          (is (= 51 @metadata-calls) "The queued ordinary import retains its disabled online setting")
          (is (= 51 (count (distinct @registered))))
          (is (not (some #{queued-id} @registered)))
          (is (empty? (:assets remaining))))))))

(deftest-async history-network-pause-and-projection-cutover-do-not-commit-stale-results
  (let [repo "synthetic-graph"
        local (atom {})
        context (atom {:graph-id repo :projection-epoch 1})
        asset-id #uuid "85000000-0000-4000-8000-000000000002"
        calls (atom 0)
        commits (atom 0)]
    (p/with-redefs [state/get-current-repo (constantly repo)
                   state/get-state (fn [key] (if (vector? key) (get-in @local key) (get @local key)))
                   state/set-state! (fn [path value & _] (swap! local assoc-in path value))
                   state/update-state! (fn [path f & args] (apply swap! local update-in path f args))
                   db-subs/projection-context #(deref context)
                   config/get-repo-dir (constantly "/synthetic-graph")
                   util/electron? (constantly true)
                   literature/<unregistered-pdfs! (fn [& _] (p/resolved {:status :ok :assets [{:db/id 2 :block/uuid asset-id :block/title "PDF"}]}))
                   literature/<prepare-review! (fn [& _] (p/resolved {:status :ready :asset {:block/uuid asset-id}}))
                   literature/<identify-saved-pdf! (fn [_ _ entry _] (p/resolved (assoc entry :extraction {:status :complete}
                                                                                   :candidates {:usable? true :title "Paper"
                                                                                                :author-text "Alice Example"
                                                                                                :identifiers [{:kind :doi :value "10.5555/verified"}]})))
                   db-async/<auto-link-pdf-to-literature (fn [& _] (p/resolved {:status :blocked :reason :identifier-not-found}))
                   metadata/<lookup-record!
                   (fn [& _]
                     (if (= 1 (swap! calls inc))
                       (p/resolved {:status :blocked :reason :metadata-unavailable})
                       (do (swap! context update :projection-epoch inc)
                           (p/resolved {:status :ready :record {}}))))
                   db-async/<register-verified-pdf-as-literature (fn [& _] (swap! commits inc) (p/resolved {:status :created}))]
      (p/let [paused (literature/<organize-existing-pdfs! repo true)
              stale (literature/<organize-existing-pdfs! repo true)]
        (is (= :paused (:status paused)))
        (is (= :metadata-unavailable (:reason paused)))
        (is (= 0 (:checked paused)))
        (is (nil? (:cursor paused)))
        (is (= :paused (:status stale)))
        (is (= 0 (:checked stale)))
        (is (= 0 @commits))))))


(deftest-async filename-arxiv-is-only-an-identifier-candidate
  (let [repo "synthetic-graph" local (atom {}) evidence (atom [])
        base {:asset-uuid (random-uuid) :extraction {:status :complete}
              :candidates {:usable? true :title "Verified Paper" :author-text "Alice Example" :identifiers []}}]
    (p/with-redefs [state/get-current-repo (constantly repo)
                   state/get-state (fn [key] (when (vector? key) (get-in @local key)))
                   state/set-state! (fn [path value & _] (swap! local assoc-in path value))
                   state/update-state! (fn [path f & args] (apply swap! local update-in path f args))
                   db-async/<auto-link-pdf-to-literature
                   (fn [_ _ candidate] (swap! evidence conj candidate) (p/resolved {:status :blocked :reason :identifier-not-found}))]
      (p/let [named (literature/<apply-review-result! repo "/synthetic" "assets"
                                                     (assoc base :asset-title "2401.12345v2_1234_0") {:explicit? true :allow-online? false})
              ordinary (literature/<apply-review-result! repo "/synthetic" "assets"
                                                        (assoc base :asset-title "Report 2026") {:explicit? true :allow-online? false})]
        (is (= :identifier-not-found (:reason named)))
        (is (= {:arxiv "2401.12345v2"} (:identifiers (first @evidence))))
        (is (= :missing-identifiers (:reason ordinary)))
        (is (= 1 (count @evidence)))))))


(deftest numbered-inline-doi-citations-do-not-override-the-paper-arxiv
  (let [result (literature/extraction->candidates
                (assoc complete-extraction :info {:title "A Verified Preprint" :author "Alice Example"}
                       :items [{:text "A Verified Preprint" :y 730 :font-size 22}
                               {:text "Alice Example" :y 700 :font-size 11}
                               {:text "Statistics are available at doi.org/10.5555/companion-data" :y 460 :font-size 10}
                               {:text "[10]." :y 460 :font-size 10}
                               {:text "arXiv:2401.12345v2" :y 200 :font-size 8}]))
        evidence (literature/automatic-review-evidence {:extraction {:status :complete} :candidates result})]
    (is (= :ready (:status evidence)))
    (is (= {:arxiv "2401.12345v2"} (get-in evidence [:evidence :identifiers])))
    (is (true? (:reference-context? (first (filter #(= :doi (:kind %)) (:identifiers result))))))))


(deftest visible-embedded-title-survives-subtitle-fonts-and-larger-section-headings
  (let [extraction {:status :complete :reason :page-end :page-height 800
                    :info {:title "First verified title: A smaller subtitle" :author "Alice Example"}
                    :items [{:text "First verified title" :y 750 :font-size 20}
                            {:text "A smaller subtitle" :y 725 :font-size 16}
                            {:text "Alice Example" :y 700 :font-size 11}
                            {:text "1. Introduction" :y 450 :font-size 24}]}
        result (literature/extraction->candidates extraction)]
    (is (= "First verified title: A smaller subtitle" (:title result)))
    (is (string/includes? (:author-text result) "Alice Example"))
    (is (not= "stale-document.docx"
              (:title (literature/extraction->candidates (assoc-in extraction [:info :title] "stale-document.docx")))))))

(deftest contents-reference-row-is-not-a-bibliography-boundary
  (let [result (literature/extraction->candidates
                (assoc complete-extraction :items [{:text "A Verified Preprint" :y 750 :font-size 20}
                                                   {:text "Alice Example" :y 720 :font-size 11}
                                                   {:text "References" :x 70 :y 500 :font-size 10}
                                                   {:text "20" :x 250 :y 500 :font-size 10}
                                                   {:text "arXiv:2401.12345v2" :x 10 :y 200 :font-size 8}]))]
    (is (= [{:kind :arxiv :value "2401.12345v2" :y 200 :reference-context? false}]
           (:identifiers result)))))


(deftest-async manual-doi-lookup-sends-only-the-normalized-identifier
  (let [calls (atom [])]
    (p/with-redefs [state/get-current-repo (constantly "isolated-doi-graph")
                    state/get-state (constantly false)
                    metadata/<lookup-record!
                    (fn [identifiers signal]
                      (swap! calls conj [identifiers signal])
                      (p/resolved {:status :ready :record {:doi (:doi identifiers)}}))]
      (p/let [result (literature/<lookup-doi! "isolated-doi-graph" "https://doi.org/10.5555/MANUAL" nil)
              invalid (literature/<lookup-doi! "isolated-doi-graph" "not-a-doi" nil)
              inactive (literature/<lookup-doi! "another-graph" "10.5555/manual" nil)]
        (is (= :ready (:status result)))
        (is (= [[{:doi "10.5555/manual"} nil]] @calls))
        (is (= :invalid-identifier (:reason invalid)))
        (is (= :inactive-graph (:reason inactive)))))))
