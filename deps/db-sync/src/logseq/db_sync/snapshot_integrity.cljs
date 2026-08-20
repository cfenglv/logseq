(ns logseq.db-sync.snapshot-integrity
  (:require [datascript.core :as d]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db.frontend.property :as db-property]))

(def graph-created-at-ident
  :logseq.kv/graph-created-at)

(def ^:private repairable-detached-ident-ghosts
  ;; Catalog membership only classifies an ident as shipped schema. It never
  ;; authorizes repair by itself: strict-detached-ident-ghost-plan still proves
  ;; the exact EAVT/AEVT/AVET tear, schema-only shadow, canonical repetition,
  ;; and absence of inbound refs before any datom is removed.
  (into #{} (keys db-property/built-in-properties)))

(def ^:private shadow-replay-batch-size 1000)

(def ^:private allowed-shadow-schema-attrs
  ;; A detached shadow may only repeat DataScript schema declarations that
  ;; already exist verbatim on the AVET-selected canonical entity. Namespace
  ;; alone is not sufficient: arbitrary :db/* data must remain fail-closed.
  #{:db/ident
    :db/valueType
    :db/cardinality
    :db/unique
    :db/index
    :db/isComponent
    :db/doc
    :db/noHistory})

(defn graph-created-at-valid?
  [db]
  (when-let [eid (:db/id (d/entity db graph-created-at-ident))]
    (boolean (seq (d/datoms db :eavt eid :kv/value)))))

(defn- snapshot-index-error
  [phase data]
  (ex-info "snapshot indexes are inconsistent"
           (merge {:type :db-sync/snapshot-index-inconsistent
                   :phase phase}
                  data)))

(defn- system-kv-integrity-error
  [phase]
  (ex-info "required graph creation metadata is missing"
           {:type :db-sync/system-kv-integrity
            :phase phase
            :missing-system-kv-idents [graph-created-at-ident]}))

(defn- datom-signature
  [datom]
  [(:e datom) (:a datom) (:v datom) (:tx datom)])

(defn- unique-attrs
  [db]
  (into #{}
        (keep (fn [[attr schema]]
                (when (:db/unique schema)
                  attr)))
        (:schema db)))

(defn- ref-attrs
  [db]
  (into #{}
        (keep (fn [[attr schema]]
                (when (= :db.type/ref (:db/valueType schema))
                  attr)))
        (:schema db)))

(defn- eavt-attr-datoms
  [db attr]
  (filter #(= attr (:a %)) (d/datoms db :eavt)))

(defn- duplicate-unique-groups
  [db]
  (let [attrs (unique-attrs db)
        groups (reduce (fn [result datom]
                         (if (contains? attrs (:a datom))
                           (update result [(:a datom) (:v datom)]
                                   (fnil conj []) datom)
                           result))
                       {}
                       (d/datoms db :eavt))]
    (into []
          (keep (fn [[[attr value] datoms]]
                  (when (> (count datoms) 1)
                    {:attr attr
                     :value value
                     :datoms datoms})))
          groups)))

(defn- assert-aevt-group-repairable!
  [db canonical-eid {:keys [attr value datoms]}]
  (let [eavt-signatures (set (map datom-signature datoms))
        aevt-signatures
        (into #{}
              (comp (filter #(= value (:v %)))
                    (map datom-signature))
              (d/datoms db :aevt attr))
        canonical-signature
        (some (fn [datom]
                (when (= canonical-eid (:e datom))
                  (datom-signature datom)))
              datoms)
        canonical-only-signatures
        (when canonical-signature #{canonical-signature})]
    ;; Two bounded persistence tears have shipped: AEVT may retain the same
    ;; duplicate as EAVT, or AEVT may already agree with AVET and contain only
    ;; the canonical datom. Every partial, divergent, or fabricated AEVT shape
    ;; remains fail-closed.
    (when-not (or (= eavt-signatures aevt-signatures)
                  (= canonical-only-signatures aevt-signatures))
      (throw (snapshot-index-error
              :duplicate-aevt-mismatch
              {:attribute attr
               :entity-ids (mapv :e datoms)})))))

(defn- allowed-shadow-datom?
  [datom]
  (contains? allowed-shadow-schema-attrs (:a datom)))

(defn- canonical-repeats-shadow-schema?
  [db canonical-eid shadow-datom]
  (boolean
   (some #(= (:v shadow-datom) (:v %))
         (d/datoms db :eavt canonical-eid (:a shadow-datom)))))

(defn- ref-targeted?
  [db eid]
  (let [refs (ref-attrs db)]
    (boolean
     (some (fn [datom]
             (and (contains? refs (:a datom))
                  (= eid (:v datom))))
           (d/datoms db :eavt)))))

(defn- strict-detached-ident-ghost-plan
  [db duplicate-groups]
  (when (seq duplicate-groups)
    (when-not (= 1 (count duplicate-groups))
      (throw (snapshot-index-error
              :multiple-duplicate-values
              {:duplicate-count (count duplicate-groups)})))
    (let [{:keys [attr value datoms] :as duplicate}
          (first duplicate-groups)
          _ (when-not (and (= :db/ident attr)
                           (contains? repairable-detached-ident-ghosts value)
                           (= 2 (count datoms)))
              (throw (snapshot-index-error
                      :duplicate-not-repairable
                      {:attribute attr
                       :entity-ids (mapv :e datoms)
                       :known-repairable-ident?
                       (contains? repairable-detached-ident-ghosts value)})))
          avet-datoms (vec (d/datoms db :avet attr value))
          _ (when-not (= 1 (count avet-datoms))
              (throw (snapshot-index-error
                      :canonical-avet-missing
                      {:attribute attr
                       :entity-ids (mapv :e datoms)
                       :avet-count (count avet-datoms)})))
          canonical-eid (:e (first avet-datoms))
          duplicate-eids (set (map :e datoms))
          _ (when-not (contains? duplicate-eids canonical-eid)
              (throw (snapshot-index-error
                      :canonical-entity-not-in-eavt
                      {:attribute attr
                       :entity-ids (mapv :e datoms)
                       :canonical-eid canonical-eid})))
          _ (assert-aevt-group-repairable!
             db canonical-eid duplicate)
          shadow-eids (disj duplicate-eids canonical-eid)]
      (doseq [shadow-eid shadow-eids]
        (let [shadow-datoms (vec (d/datoms db :eavt shadow-eid))]
          (when (or (empty? shadow-datoms)
                    (not-every? allowed-shadow-datom? shadow-datoms))
            (throw (snapshot-index-error
                    :shadow-entity-has-user-data
                    {:attribute attr
                     :shadow-eid shadow-eid
                     :shadow-attributes
                     (mapv :a (take 16 shadow-datoms))})))
          (when-not (every? #(canonical-repeats-shadow-schema?
                             db canonical-eid %)
                            shadow-datoms)
            (throw (snapshot-index-error
                    :shadow-schema-not-repeated-by-canonical
                    {:attribute attr
                     :shadow-eid shadow-eid
                     :canonical-eid canonical-eid})))
          (when (ref-targeted? db shadow-eid)
            (throw (snapshot-index-error
                    :shadow-entity-is-referenced
                    {:attribute attr
                     :shadow-eid shadow-eid})))))
      {:canonical-eid canonical-eid
       :ident value
       :shadow-eids shadow-eids})))

(defn- assert-unique-index-integrity!
  [db]
  (let [duplicates (duplicate-unique-groups db)]
    (when (seq duplicates)
      (let [{:keys [attr datoms]} (first duplicates)]
        (throw (snapshot-index-error
                :duplicate-unique-value
                {:attribute attr
                 :entity-ids (mapv :e datoms)
                 :duplicate-count (count duplicates)}))))
    (doseq [attr (unique-attrs db)]
      (let [eavt-signatures
            (set (map datom-signature (eavt-attr-datoms db attr)))
            aevt-signatures
            (set (map datom-signature (d/datoms db :aevt attr)))]
        (when-not (= eavt-signatures aevt-signatures)
          (throw (snapshot-index-error
                  :eavt-aevt-mismatch
                  {:attribute attr}))))
      (let [eavt-datoms (eavt-attr-datoms db attr)
            eavt-count (reduce (fn [n _] (inc n)) 0 eavt-datoms)
            avet-count (reduce (fn [n _] (inc n))
                               0
                               (d/datoms db :avet attr))]
        (when-not (= eavt-count avet-count)
          (throw (snapshot-index-error
                  :eavt-avet-count-mismatch
                  {:attribute attr
                   :eavt-count eavt-count
                   :avet-count avet-count})))
        (doseq [datom (eavt-attr-datoms db attr)]
          (let [matches (vec (d/datoms db :avet attr (:v datom)))]
            (when-not (and (= 1 (count matches))
                           (= (datom-signature datom)
                              (datom-signature (first matches))))
              (throw (snapshot-index-error
                      :eavt-avet-datom-mismatch
                      {:attribute attr
                       :entity-id (:e datom)}))))))))
  db)

(defn- assert-reference-closure!
  [db]
  (let [refs (ref-attrs db)]
    (doseq [datom (d/datoms db :eavt)
            :when (contains? refs (:a datom))]
      (when-not (seq (d/datoms db :eavt (:v datom)))
        (throw (snapshot-index-error
                :dangling-reference
                {:attribute (:a datom)
                 :entity-id (:e datom)
                 :target-eid (:v datom)})))))
  db)

(defn- block-uuid-projection
  [db]
  (mapv (fn [datom] [(:e datom) (:v datom)])
        (d/datoms db :avet :block/uuid)))

(defn- schema-datom?
  [ident-eids schema-version-eid datom]
  (or (= schema-version-eid (:e datom))
      (and (contains? ident-eids (:e datom))
           (or (= :db/ident (:a datom))
               (= "db" (namespace (:a datom)))))))

(defn- repair-analysis
  [db trusted-graph-created-at]
  (let [duplicate-groups (duplicate-unique-groups db)
        ghost-plan (strict-detached-ident-ghost-plan db duplicate-groups)
        shadow-eids (or (:shadow-eids ghost-plan) #{})
        missing-created-at? (not (graph-created-at-valid? db))
        repair? (or (seq shadow-eids) missing-created-at?)]
    (if-not repair?
      (do
        (assert-unique-index-integrity! db)
        (assert-reference-closure! db)
        nil)
      (do
        (when (and missing-created-at?
                   (not (and (integer? trusted-graph-created-at)
                             (not (neg? trusted-graph-created-at)))))
          (throw (system-kv-integrity-error :snapshot-repair)))
        ;; A repairable ghost is still rejected if any unrelated reference is
        ;; dangling. strict-detached-ident-ghost-plan already rejects a ref to
        ;; the shadow itself.
        (assert-reference-closure! db)
        {:shadow-eids shadow-eids
         :missing-created-at? missing-created-at?
         :trusted-graph-created-at trusted-graph-created-at}))))

(defn repair-required?
  "Validate a snapshot without constructing a replay connection. Returns true
  only for the narrowly repairable built-in ghost or an authoritatively
  recoverable graph-created-at omission. Healthy graphs return false and all
  other inconsistencies fail closed."
  [conn trusted-graph-created-at]
  (boolean (repair-analysis @conn trusted-graph-created-at)))

(defn validate-legacy-indexes-without-created-at!
  "Validate a pre-system-KV snapshot without treating the absent
  graph-created-at entity as repairable. This narrow read-only gate exists
  only for the legacy v1 download fallback: every unique index and reference
  closure must already be exact, and no canonicalization is attempted."
  [conn]
  (let [db @conn]
    (assert-unique-index-integrity! db)
    (assert-reference-closure! db)
    db))

(defn prepare-repair-plan!
  "Materialize plain replay facts and equivalence witnesses from one source
  connection. The returned value retains no DataScript DB/connection, allowing
  callers to release that indexed root before constructing the clean shadow."
  [conn trusted-graph-created-at]
  (let [db-before @conn]
    (when-let [{:keys [shadow-eids missing-created-at?]
                :as analysis}
               (repair-analysis db-before trusted-graph-created-at)]
      (let [schema-version-eid
            (some-> (d/entity db-before :logseq.kv/schema-version) :db/id)
            ident-eids (into #{} (map :e)
                             (d/datoms db-before :avet :db/ident))
            replay-facts
            (into []
                  (comp
                   (remove #(contains? shadow-eids (:e %)))
                   (map (fn [datom]
                          [(schema-datom? ident-eids schema-version-eid datom)
                           (:e datom) (:a datom) (:v datom)])))
                  (d/datoms db-before :eavt))]
        (merge analysis
               {:replay-facts replay-facts
                :schema (:schema db-before)
                :expected-fact-count (count replay-facts)
                :legacy-checksum-before
                (sync-checksum/recompute-checksum db-before)
                :server-checksum-before
                (sync-checksum/recompute-server-checksum db-before)
                :block-uuids-before (block-uuid-projection db-before)
                :missing-created-at? missing-created-at?})))))

(defn- replay-fact-batches!
  [conn replay-facts schema?]
  (doseq [batch
          (partition-all
           shadow-replay-batch-size
           (filter (fn [[schema-fact?]] (= schema? schema-fact?))
                   replay-facts))]
    (d/transact!
     conn
     (mapv (fn [[_ e a v]] [:db/add e a v]) batch)
     {:sync-download-graph? true})))

(defn- replay-fact-present?
  [db [_schema? e a v]]
  (boolean (some #(= v (:v %)) (d/datoms db :eavt e a))))

(defn replay-repair-plan!
  "Replay a previously prepared plan into one clean shadow and re-prove the
  complete unique/ref closure, exact logical facts, user block UUIDs, and both
  checksum contracts."
  [{:keys [replay-facts schema expected-fact-count missing-created-at?
           trusted-graph-created-at legacy-checksum-before
           server-checksum-before block-uuids-before]}]
  (let [conn (d/create-conn schema)]
    ;; Schema declarations must precede ordinary facts, but both passes are
    ;; lazy views over the one bounded plain-fact vector.
    (replay-fact-batches! conn replay-facts true)
    (replay-fact-batches! conn replay-facts false)
    (when missing-created-at?
      (d/transact! conn
                   [{:db/ident graph-created-at-ident
                     :kv/value trusted-graph-created-at}]
                   {:sync-download-graph? true}))
    (let [db-after @conn
          _ (assert-unique-index-integrity! db-after)
          _ (assert-reference-closure! db-after)
          created-at-eid
          (some-> (d/entity db-after graph-created-at-ident) :db/id)
          created-at-datoms
          (if missing-created-at?
            (vec (d/datoms db-after :eavt created-at-eid))
            [])
          _ (when (and missing-created-at?
                       (or (empty? created-at-datoms)
                           (not-every?
                            #(contains? #{:db/ident :kv/value} (:a %))
                            created-at-datoms)))
              (throw (snapshot-index-error
                      :unexpected-system-repair-facts
                      {:entity-id created-at-eid})))
          after-count (reduce (fn [n _] (inc n)) 0
                              (d/datoms db-after :eavt))
          expected-after-count (+ expected-fact-count
                                  (count created-at-datoms))
          _ (when-not (= expected-after-count after-count)
              (throw (snapshot-index-error
                      :logical-fact-count-changed
                      {:expected-count expected-after-count
                       :actual-count after-count})))
          _ (when-not (every? #(replay-fact-present? db-after %)
                              replay-facts)
              (throw (snapshot-index-error
                      :logical-facts-changed
                      {:expected-count expected-fact-count})))
          _ (when-not (= block-uuids-before
                         (block-uuid-projection db-after))
              (throw (snapshot-index-error
                      :block-uuid-projection-changed
                      {:before-count (count block-uuids-before)
                       :after-count
                       (count (block-uuid-projection db-after))})))
          legacy-checksum-after
          (sync-checksum/recompute-checksum db-after)
          server-checksum-after
          (sync-checksum/recompute-server-checksum db-after)
          _ (when-not (and (= legacy-checksum-before
                              legacy-checksum-after)
                           (= server-checksum-before
                              server-checksum-after))
              (throw (snapshot-index-error
                      :checksum-changed
                      {:legacy-checksum-changed?
                       (not= legacy-checksum-before legacy-checksum-after)
                       :server-checksum-changed?
                       (not= server-checksum-before server-checksum-after)})))]
      conn)))

(defn validate-or-repair!
  "Validate every unique index in a snapshot connection. Return a clean
  shadow connection only for a strictly-proven repair; otherwise return nil.
  Callers persist the shadow into isolated storage before any live switch."
  [conn trusted-graph-created-at]
  (when-let [plan (prepare-repair-plan! conn trusted-graph-created-at)]
    (replay-repair-plan! plan)))
