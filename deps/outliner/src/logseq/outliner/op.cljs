(ns logseq.outliner.op
  "Transact outliner ops"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.defkeywords :refer [defkeyword defkeywords]]
            [logseq.common.util :as common-util]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.literature :as literature]
            [logseq.db.frontend.literature-source :as literature-source]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.template :as outliner-template]
            [logseq.outliner.page :as outliner-page]
            [logseq.outliner.property :as outliner-property]
            [logseq.outliner.recycle :as outliner-recycle]
            [malli.core :as m]
            [logseq.outliner.op.construct :as op-construct]))

(defkeywords
  ::missing-parent-original {:doc "The linked-reference parent supplied to an outdent no longer exists."})

(defkeyword :literature-register-source-record
  "Replan one literature source registration against the graph on replay.")

(defkeyword :literature-register-identified-pdf
  "Replan one DOI/arXiv PDF registration against the graph on replay.")

(defkeyword :literature-register-verified-pdf
  "Replan one metadata-verified PDF registration against the graph on replay.")

(defkeyword :literature-auto-link-pdf
  "Recheck an automatic source attachment association against the current graph.")

(defkeyword :literature-merge-duplicate-pdf
  "Replan an exact PDF merge against the current graph during native replay.")

(defn merge-duplicate-pdf!
  [conn keep-uuid duplicate-uuid & [comparison]]
  (let [plan (literature/build-duplicate-pdf-demotion-plan
              @conn keep-uuid duplicate-uuid {:comparison comparison})]
    (if (= :ready (:status plan))
      (do
        (ldb/transact! conn (:tx-data plan) {:outliner-op :literature-merge-duplicate-pdf})
        (-> plan (dissoc :tx-data) (assoc :status :resolved)))
      (dissoc plan :tx-data))))

(defkeyword :source-context
  "The exact source identity and prepared native Asset used for an attachment commit.")

(defkeyword :reference-uuid
  "The stable UUID reserved for an attachment's native linked reference.")

(defn auto-link-literature-pdf!
  [conn asset-uuid paper-uuid {:keys [binding-uuid source-key asset-block reference-uuid]}]
  (let [owner (literature-source/find-source-record-owner @conn binding-uuid source-key)]
    (if-not (and (= :found (:status owner)) (= paper-uuid (:paper-uuid owner)))
      {:status :blocked :reason :source-link-unavailable}
      (let [paper (d/entity @conn [:block/uuid paper-uuid])
            new-asset? (and asset-block (nil? (d/entity @conn [:block/uuid asset-uuid])))
            _ (when asset-block (assert (= asset-uuid (:block/uuid asset-block))))
            ;; Plan before any native write so a blocked association cannot leave
            ;; an inserted Asset behind. The final writes share the original batch.
            plan-db (if new-asset?
                      (d/db-with @conn [(assoc asset-block :block/tags #{[:db/ident :logseq.class/Asset]})])
                      @conn)
            plan (literature/build-auto-associate-pdf-plan
                  plan-db asset-uuid paper-uuid (:block/title asset-block))]
        (if (contains? #{:ready :already-linked} (:status plan))
          (do
            (when new-asset?
              (outliner-core/insert-blocks! conn [asset-block] paper
                                            {:keep-uuid? true :bottom? true :sibling? false}))
            (when (= :ready (:status plan))
              (ldb/transact! conn (:tx-data plan) {:outliner-op :literature-auto-link-pdf}))
            (let [asset (d/entity @conn [:block/uuid asset-uuid])
                  outside? (not= paper-uuid (:block/uuid (:block/parent asset)))
                  position (when outside?
                             (d/q '[:find ?u . :in $ ?asset-uuid ?paper-uuid
                                  :where
                                  [?a :block/uuid ?asset-uuid]
                                  [?p :block/link ?a]
                                  [?t :block/uuid ?paper-uuid]
                                  [?p :block/parent ?t]
                                  [?p :block/uuid ?u]
                                  (not [?p :logseq.property/deleted-at])]
                                  @conn asset-uuid paper-uuid))]
              (when (and outside? (nil? position))
                (outliner-core/insert-blocks!
                 conn [{:block/uuid reference-uuid :block/title "" :block/link [:block/uuid asset-uuid]}]
                 paper {:keep-uuid? true :bottom? true :sibling? false})))
            (cond-> (dissoc plan :tx-data)
              (= :ready (:status plan)) (assoc :status :linked)))
          (dissoc plan :tx-data))))))

(defn register-literature-verified-pdf!
  [conn asset-uuid evidence metadata]
  (let [plan (if (empty? metadata)
               (literature/build-auto-link-pdf-plan @conn asset-uuid evidence)
               (literature/build-auto-register-pdf-plan
                @conn asset-uuid evidence metadata))]
    (if (= :ready (:status plan))
      (do
        (ldb/transact! conn (:tx-data plan)
                       {:outliner-op :literature-register-verified-pdf})
        (-> plan
            (dissoc :tx-data)
            (assoc :status (case (:action plan)
                             :create :created
                             :link :linked))))
      (dissoc plan :tx-data))))

(defn register-literature-identified-pdf!
  [conn asset-uuid title identifiers & [metadata]]
  (let [paper-uuid (literature/source-record-paper-uuid identifiers)
        plan (literature/build-register-pdf-plan
              @conn asset-uuid title identifiers paper-uuid metadata)]
    (if (= :ready (:status plan))
      (do
        (ldb/transact! conn (:tx-data plan)
                       {:outliner-op :literature-register-identified-pdf})
        (-> plan
            (dissoc :tx-data)
            (assoc :status (case (:action plan)
                             :create :created
                             :link :linked))))
      (dissoc plan :tx-data))))

(defn register-literature-source-record!
  [conn binding-uuid destination-page-uuid source-record & [rebase-db-before]]
  (let [plan (literature/build-register-source-record-plan
              @conn binding-uuid destination-page-uuid source-record)]
    (if (= :ready (:status plan))
      (do
        (ldb/transact! conn
                       (if rebase-db-before
                         ;; The pending datoms already own these value orders.
                         ;; Regenerating them makes repair disagree with its ACK.
                         (mapv (fn [item]
                                 (if-let [order (when (:logseq.property/created-from-property item)
                                                  (:block/order
                                                   (d/entity rebase-db-before
                                                             [:block/uuid (:block/uuid item)])))]
                                   (assoc item :block/order order)
                                   item))
                               (:tx-data plan))
                         (:tx-data plan))
                       {:outliner-op :literature-register-source-record})
        (-> plan
            (dissoc :tx-data)
            (assoc :status (case (:action plan)
                             :create :created
                             :link :linked))))
      (dissoc plan :tx-data))))

(def ^:private ^:large-vars/data-var op-schema
  [:multi {:dispatch first}
   ;; blocks
   [:save-block
    [:catn
     [:op :keyword]
     [:args [:tuple ::block ::option]]]]
   [:insert-blocks
    [:catn
     [:op :keyword]
     [:args [:tuple ::blocks ::block-id ::option]]]]
   [:apply-template
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::block-id ::option]]]]
   [:delete-blocks
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids ::option]]]]
   [:move-blocks
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids ::block-id ::option]]]]
   [:move-blocks-up-down
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids :boolean]]]]
   [:indent-outdent-blocks
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids :boolean ::option]]]]
   [:collapse-expand-blocks
    [:catn
     [:op :keyword]
     [:args [:tuple ::blocks ::option]]]]

   ;; properties
   [:upsert-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::maybe-property-id ::schema ::option]]]]
   [:set-block-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::property-id ::value]]]]
   [:remove-block-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::property-id]]]]
   [:delete-property-value
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::property-id ::value]]]]
   [:batch-delete-property-value
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids ::property-id ::value]]]]
   [:create-property-text-block
    [:catn
     [:op :keyword]
     [:args [:tuple ::maybe-block-id ::property-id ::value ::option]]]]
   [:collapse-expand-block-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::property-id :boolean]]]]
   [:batch-set-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids ::property-id ::value ::option]]]]
   [:batch-remove-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-ids ::property-id]]]]
   [:class-add-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::class-id ::property-id]]]]
   [:class-remove-property
    [:catn
     [:op :keyword]
     [:args [:tuple ::class-id ::property-id]]]]
   [:upsert-closed-value
    [:catn
     [:op :keyword]
     [:args [:tuple ::property-id ::option]]]]
   [:delete-closed-value
    [:catn
     [:op :keyword]
     [:args [:tuple ::property-id ::block-id]]]]
   [:add-existing-values-to-closed-values
    [:catn
     [:op :keyword]
     [:args [:tuple ::property-id ::values]]]]

   [:batch-import-edn
    [:catn
     [:op :keyword]
     [:args [:tuple ::import-edn ::option]]]]

   ;; transact
   [:transact
    [:catn
     [:op :keyword]
     [:args [:tuple ::tx-data ::tx-meta]]]]

   [:literature-register-source-record
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::block-id map?]]]]

   [:literature-register-identified-pdf
    [:catn
     [:op :keyword]
     [:args [:or [:tuple ::block-id ::title map?]
                 [:tuple ::block-id ::title map? map?]]]]]

   [:literature-register-verified-pdf
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id map? map?]]]]

   [:literature-auto-link-pdf
    [:catn
     [:op :keyword]
     [:args [:tuple ::block-id ::block-id
             [:map [:binding-uuid uuid?] [:source-key :string]
              [:reference-uuid uuid?] [:asset-block {:optional true} map?]]]]]]

   [:literature-merge-duplicate-pdf
    [:catn [:op :keyword] [:args [:or [:tuple ::block-id ::block-id]
                                    [:tuple ::block-id ::block-id map?]]]]]

   ;; page ops
   [:create-page
    [:catn
     [:op :keyword]
     [:args [:tuple ::title ::option]]]]

   [:rename-page
    [:catn
     [:op :keyword]
     [:args [:tuple ::uuid ::title]]]]

   [:delete-page
    [:catn
     [:op :keyword]
     [:args [:tuple ::uuid ::option]]]]

   [:restore-recycled
    [:catn
     [:op :keyword]
     [:args [:tuple ::uuid]]]]

   [:recycle-delete-permanently
    [:catn
     [:op :keyword]
     [:args [:tuple ::uuid]]]]

   [:toggle-reaction
    [:catn
     [:op :keyword]
     [:args [:tuple ::uuid ::emoji-id ::maybe-uuid]]]]])

(def ^:private ops-schema
  [:schema {:registry {::block map?
                       ::schema map?
                       ::block-id uuid?
                       ::maybe-block-id [:maybe ::block-id]
                       ::block-ids [:sequential ::block-id]
                       ::class-id ::block-id
                       ::emoji-id string?
                       ::property-id qualified-keyword?
                       ::maybe-property-id [:maybe ::property-id]
                       ::maybe-uuid [:maybe :uuid]
                       ::value :any
                       ::values [:sequential ::value]
                       ::option [:maybe map?]
                       ::import-edn map?
                       ::blocks [:sequential ::block]
                       ::uuid uuid?
                       ::title string?
                       ::tx-data [:sequential :any]
                       ::tx-meta [:maybe map?]}}
   [:sequential op-schema]])

(def ^:private ops-validator (m/validator ops-schema))

(defn- reaction-user-id
  [reaction]
  (:db/id (:logseq.property/created-by-ref reaction)))

(defn- toggle-reaction!
  [conn target-uuid emoji-id user-uuid]
  (when-let [target (d/entity @conn [:block/uuid target-uuid])]
    (let [user-id (when user-uuid
                    (:db/id (d/entity @conn [:block/uuid user-uuid])))
          reactions (:logseq.property.reaction/_target target)
          match? (fn [reaction]
                   (and (= emoji-id (:logseq.property.reaction/emoji-id reaction))
                        (if user-id
                          (= user-id (reaction-user-id reaction))
                          (nil? (reaction-user-id reaction)))))
          existing (some (fn [reaction] (when (match? reaction) reaction)) reactions)]
      (if existing
        (do
          (ldb/transact! conn [[:db/retractEntity (:db/id existing)]]
                         {:outliner-op :toggle-reaction})
          true)
        (let [now (common-util/time-ms)
              reaction-tx (cond-> {:block/uuid (d/squuid)
                                   :block/created-at now
                                   :logseq.property.reaction/emoji-id emoji-id
                                   :logseq.property.reaction/target (:db/id target)}
                            user-id
                            (assoc :logseq.property/created-by-ref user-id))]
          (ldb/transact! conn [reaction-tx]
                         {:outliner-op :toggle-reaction})
          true)))))

(defn- import-edn-data
  [conn *result export-map {:keys [tx-meta validate-scope] :as import-options}]
  (let [{:keys [error] :as txs}
        (try (sqlite-export/build-import export-map @conn (dissoc import-options :tx-meta :validate-scope))
             (catch :default e
               (js/console.error "Import EDN error: " e)
               {:error "An unexpected error occurred building the import. See the javascript console for details."}))
        validation (when-not error
                     (sqlite-export/validate-import-txs
                      txs
                      @conn
                      (cond-> {:edn-label "Imported EDN"}
                        validate-scope (assoc :validate-scope validate-scope))))]
    ;; (cljs.pprint/pprint txs)
    (if (or error (:error validation))
      (reset! *result {:error (or error (:error validation))})
      (try
        (ldb/transact! conn (:tx-data validation)
                       (merge {::sqlite-export/imported-data? true} tx-meta))
        (catch :default e
          (js/console.error "Unexpected Import EDN error:" e)
          (reset! *result {:error (str "Unexpected Import EDN error: " (pr-str (ex-message e)))}))))))

(defn- apply-insert-blocks-op!
  [conn *result [blocks target-block-id opts]]
  (when-let [target-block (d/entity @conn [:block/uuid target-block-id])]
    (let [result (outliner-core/insert-blocks! conn blocks target-block opts)]
      (reset! *result result))))

(defn- template-children-blocks
  [db template-id]
  (when-let [template (d/entity db template-id)]
    (let [template-blocks (some->> (ldb/get-block-and-children db (:block/uuid template)
                                                               {:include-property-block? true})
                                   rest)]
      (when (seq template-blocks)
        (cons (assoc (into {} (first template-blocks))
                     :db/id (:db/id (first template-blocks))
                     :logseq.property/used-template (:db/id template))
              (map (fn [block]
                     (assoc (into {} block) :db/id (:db/id block)))
                   (rest template-blocks)))))))

(defn- journal-title
  [db journal-day]
  (date-time-util/int->journal-title
   journal-day
   (:logseq.property.journal/title-format (d/entity db :logseq.class/Journal))))

(defn- ensure-template-journal-pages!
  [conn blocks]
  (doseq [journal-day (outliner-template/dynamic-template-journal-days blocks)]
    (when-not (ldb/get-journal-page-by-day @conn journal-day)
      (outliner-page/create! conn (journal-title @conn journal-day) {:journal? true}))))

(defn- apply-template-op!
  [conn *result [template-id target-block-id opts]]
  (when-let [target (d/entity @conn [:block/uuid target-block-id])]
    (let [blocks (or (some-> (:template-blocks opts) seq vec)
                     (template-children-blocks @conn [:block/uuid template-id]))
          _ (ensure-template-journal-pages! conn blocks)
          blocks (outliner-template/resolve-dynamic-template-blocks @conn target blocks)]
      (when (seq blocks)
        (let [sibling? (:sibling? opts)
              sibling?' (cond
                          (some? sibling?)
                          sibling?

                          (seq (:block/_parent target))
                          false

                          :else
                          true)
              result (outliner-core/insert-blocks! conn blocks target
                                                   (assoc opts
                                                          :sibling? sibling?'
                                                          :insert-template? true
                                                          :outliner-op :insert-template-blocks))]
          (reset! *result result))))))

(defn- resolve-indent-outdent-opts
  [db opts]
  (if-let [parent-original-uuid (get-in opts [:parent-original :block/uuid])]
    (let [parent-original (or (d/entity db [:block/uuid parent-original-uuid])
                              (throw (ex-info "Missing outdent parent original"
                                              {:type ::missing-parent-original
                                               :block/uuid parent-original-uuid})))]
      (assoc opts :parent-original parent-original))
    opts))

(defn- ^:large-vars/cleanup-todo apply-op!
  [conn opts' *result [op args]]
  (case op
    :literature-merge-duplicate-pdf
    (reset! *result (apply merge-duplicate-pdf! conn args))

    :literature-register-source-record
    (reset! *result (apply register-literature-source-record! conn args))

    :literature-register-identified-pdf
    (reset! *result (apply register-literature-identified-pdf! conn args))

    :literature-register-verified-pdf
    (reset! *result (apply register-literature-verified-pdf! conn args))

    :literature-auto-link-pdf
    (reset! *result (apply auto-link-literature-pdf! conn args))

    ;; blocks
    :save-block
    (apply outliner-core/save-block! conn args)

    :insert-blocks
    (apply-insert-blocks-op! conn *result args)

    :apply-template
    (apply-template-op! conn *result args)

    :delete-blocks
    (let [[block-ids opts] args
          blocks (keep #(d/entity @conn [:block/uuid %]) block-ids)]
      (outliner-core/delete-blocks! conn blocks (merge opts opts')))

    :move-blocks
    (let [[block-ids target-block-id opts] args
          blocks (keep #(d/entity @conn [:block/uuid %]) block-ids)
          target-block (d/entity @conn [:block/uuid target-block-id])]
      (when (and target-block (seq blocks))
        (outliner-core/move-blocks! conn blocks target-block opts)))

    :move-blocks-up-down
    (let [[block-ids up?] args
          blocks (keep #(d/entity @conn [:block/uuid %]) block-ids)]
      (when (seq blocks)
        (outliner-core/move-blocks-up-down! conn blocks up?)))

    :indent-outdent-blocks
    (let [[block-ids indent? opts] args
          blocks (keep #(d/entity @conn [:block/uuid %]) block-ids)
          opts (resolve-indent-outdent-opts @conn opts)]
      (when (seq blocks)
        (outliner-core/indent-outdent-blocks! conn blocks indent? opts)))

    :collapse-expand-blocks
    (let [[blocks opts] args]
      (ldb/transact! conn blocks opts))

    ;; properties
    :upsert-property
    (reset! *result (apply outliner-property/upsert-property! conn args))

    :set-block-property
    (apply outliner-property/set-block-property! conn args)

    :remove-block-property
    (apply outliner-property/remove-block-property! conn args)

    :delete-property-value
    (apply outliner-property/delete-property-value! conn args)

    :create-property-text-block
    (let [[block-id property-id v opts] args
          block-id' (when block-id [:block/uuid block-id])]
      (outliner-property/create-property-text-block! conn block-id' property-id v opts))

    :batch-set-property
    (apply outliner-property/batch-set-property! conn args)

    :batch-remove-property
    (apply outliner-property/batch-remove-property! conn args)

    :batch-delete-property-value
    (apply outliner-property/batch-delete-property-value! conn args)

    :class-add-property
    (let [[class-id property-id] args]
      (outliner-property/class-add-property! conn [:block/uuid class-id] property-id))

    :class-remove-property
    (let [[class-id property-id] args]
      (outliner-property/class-remove-property! conn [:block/uuid class-id] property-id))

    :upsert-closed-value
    (apply outliner-property/upsert-closed-value! conn args)

    :delete-closed-value
    (let [[property-id value-block-id] args]
      (outliner-property/delete-closed-value! conn property-id [:block/uuid value-block-id]))

    :add-existing-values-to-closed-values
    (apply outliner-property/add-existing-values-to-closed-values! conn args)

    :batch-import-edn
    (apply import-edn-data conn *result args)

    :transact
    (apply ldb/transact! conn args)

    :create-page
    (let [[title options] args]
      (reset! *result (outliner-page/create! conn title (or options {}))))

    :rename-page
    (let [[page-uuid new-title] args]
      (if (string/blank? new-title)
        (throw (ex-info "Page name shouldn't be blank" {:block/uuid page-uuid
                                                        :block/title new-title}))
        (outliner-core/save-block! conn
                                   {:block/uuid page-uuid
                                    :block/title new-title})))

    :delete-page
    (let [[page-uuid opts] args]
      (reset! *result (outliner-page/delete! conn page-uuid (merge opts opts'))))

    :restore-recycled
    (let [[root-uuid] args]
      (reset! *result (outliner-recycle/restore! conn root-uuid)))

    :recycle-delete-permanently
    (let [[root-uuid] args]
      (reset! *result (outliner-recycle/permanently-delete! conn root-uuid)))

    :toggle-reaction
    (reset! *result (apply toggle-reaction! conn args))
    nil))

(defn- import-edn-op?
  [[op _args]]
  (= :batch-import-edn op))

(defn apply-ops!
  [conn ops opts]
  (assert (ops-validator ops) ops)
  (let [semantic-ops (atom [])
        single-op-outliner-op (when (= 1 (count ops))
                                (first (first ops)))
        opts' (cond-> (assoc opts
                             :transact-opts {:conn conn}
                             :local-tx? true
                             :db-sync/tx-id (or (:db-sync/tx-id opts) (random-uuid)))
                (and single-op-outliner-op
                     (nil? (:outliner-op opts)))
                (assoc :outliner-op single-op-outliner-op)

                (= :rename-page single-op-outliner-op)
                (assoc :data (let [[page-uuid new-title] (second (first ops))
                                   page (d/entity @conn [:block/uuid page-uuid])]
                               {:page-id (:db/id page)
                                :old-name (:block/title page)
                                :new-name new-title}))

                (some import-edn-op? ops)
                (assoc ::sqlite-export/imported-data? true))
        *result (atom nil)]

    (ldb/batch-transact-with-temp-conn!
     conn (dissoc opts' :additional-tx :transact-opts :current-block)
     (fn [temp-conn _tx-data]
       (doseq [[op args :as op-entry] ops]
         (let [result (apply-op! temp-conn opts' *result op-entry)
               [_ [blocks target-id insert-opts]] (first (get-in result [:tx-meta :outliner-ops]))
               op-entry (case op
                          :save-block (first (get-in result [:tx-meta :outliner-ops]))
                          :insert-blocks (when result [:insert-blocks [blocks target-id insert-opts]])
                          :apply-template (when result [:apply-template [(first args) target-id
                                                                         (assoc insert-opts :template-blocks blocks)]])
                          op-entry)]
           (when (and op-entry (contains? op-construct/semantic-outliner-ops op))
             (swap! semantic-ops conj op-entry))))
       (when (seq (:additional-tx opts'))
         (ldb/transact! temp-conn (:additional-tx opts') {})))
     :transform-tx-meta #(assoc % :outliner-ops @semantic-ops))

    @*result))
