(ns frontend.worker.db-core
  "Core db-worker logic without host-specific bootstrap."
  (:require
   [cljs-bean.core :as bean]
   [clojure.set]
   [clojure.string :as string]
   [datascript.core :as d]
   [datascript.storage :refer [IStorage] :as storage]
   [frontend.common.thread-api :as thread-api :refer [def-thread-api]]
   [frontend.worker-common.util :as worker-util]
   [frontend.worker.db-listener :as db-listener]
   [frontend.worker.db.fix :as db-fix]
   [frontend.worker.db.migrate :as db-migrate]
   [frontend.worker.db.validate :as worker-db-validate]
   [frontend.worker.handler.cli]
   [frontend.worker.handler.comments]
   [frontend.worker.handler.export]
   [frontend.worker.handler.flashcard]
   [frontend.worker.handler.graph]
   [frontend.worker.handler.markdown]
   [frontend.worker.handler.maintenance]
   [frontend.worker.handler.page]
   [frontend.worker.handler.property]
   [frontend.worker.handler.query]
   [frontend.worker.handler.render-resource.engine]
   [frontend.worker.handler.search :as search-handler]
   [frontend.worker.handler.sync]
   [frontend.worker.handler.transaction :as transaction-handler]
   [frontend.worker.handler.undo-redo]
   [frontend.worker.handler.view]
   [frontend.worker.pipeline :as worker-pipeline]
   [frontend.worker.platform :as platform]
   [frontend.worker.publish]
   [frontend.worker.repair-commit-fence :as repair-commit-fence]
   [frontend.worker.search :as search]
   [frontend.worker.shared-service :as shared-service]
   [frontend.worker.state :as worker-state]
   [frontend.worker.sync :as db-sync]
   [frontend.worker.sync.client-op :as client-op]
   [frontend.worker.sync.crypt :as sync-crypt]
   [frontend.worker.sync.download :as sync-download]
   [frontend.worker.thread-atom]
   [frontend.worker.undo-redo :as worker-undo-redo]
   [goog.functions :as gfun]
   [lambdaisland.glogi :as log]
   [logseq.common.graph-dir :as graph-dir]
   [logseq.common.util :as common-util]
   [logseq.db :as ldb]
   [logseq.db.common.entity-plus :as entity-plus]
   [logseq.db.common.order :as db-order]
   [logseq.db.common.sqlite :as common-sqlite]
   [logseq.db.frontend.asset :as db-asset]
   [logseq.db.frontend.class :as db-class]
   [logseq.db.frontend.property :as db-property]
   [logseq.db.frontend.schema :as db-schema]
   [logseq.db.sqlite.create-graph :as sqlite-create-graph]
   [logseq.db.sqlite.util :as sqlite-util]
   [logseq.graph-parser.exporter :as gp-exporter]
   [promesa.core :as p]
   [shadow.resource :as rc]))

(defonce *sqlite worker-state/*sqlite)
(defonce *sqlite-conns worker-state/*sqlite-conns)
(defonce *vector-indexes worker-state/*vector-indexes)
(defonce *datascript-conns worker-state/*datascript-conns)
(defonce *client-ops-conns worker-state/*client-ops-conns)
(defonce *opfs-pools worker-state/*opfs-pools)
(defonce *publishing? (atom false))
(defonce ^:private *node-pools (atom {}))

(defonce ^:private *client-ops-cleanup-timers (atom {}))
(defonce ^:private *wal-checkpoint-timers (atom {}))
(def ^:private client-ops-cleanup-interval-ms (* 3 60 60 1000))
(def ^:private wal-checkpoint-idle-ms 2000)
(def ^:private wal-checkpoint-sql "PRAGMA wal_checkpoint(TRUNCATE)")
(def ^:private default-graph-config-content (rc/inline "templates/config.edn"))

(defn- resolve-initial-config
  [config]
  (if (some? config)
    config
    default-graph-config-content))

(defn- node-runtime?
  []
  (= :node (platform/env-flag (platform/current) :runtime)))

(defn- storage-pool-name
  [graph]
  (if (node-runtime?)
    (graph-dir/repo->graph-dir-key graph)
    (worker-util/get-pool-name graph)))

(defn- get-storage-pool
  [graph]
  (if (node-runtime?)
    (or (get @*node-pools graph)
        (worker-state/get-opfs-pool graph))
    (worker-state/get-opfs-pool graph)))

(defn- remember-storage-pool!
  [graph pool]
  (if (node-runtime?)
    (swap! *node-pools assoc graph pool)
    (swap! *opfs-pools assoc graph pool)))

(defn- forget-storage-pool!
  [graph]
  (if (node-runtime?)
    (do
      (swap! *node-pools dissoc graph)
      (swap! *opfs-pools dissoc graph))
    (swap! *opfs-pools dissoc graph)))

(defn- <get-opfs-pool
  [graph]
  (when-not @*publishing?
    (or (get-storage-pool graph)
        (p/let [storage (platform/storage (platform/current))
                ^js pool ((:install-opfs-pool storage) @*sqlite (storage-pool-name graph))]
          (remember-storage-pool! graph pool)
          pool))))

(defn- init-sqlite-module!
  []
  (when-not @*sqlite
    (p/let [publishing? (platform/env-flag (platform/current) :publishing?)
            sqlite (platform/sqlite-init! (platform/current))]
      (reset! *publishing? publishing?)
      (reset! *sqlite (or sqlite ::sqlite-initialized))
      nil)))

(def repo-path "/db.sqlite")
(def client-ops-repo-path (str "client-ops" repo-path))
(def ^:private previous-repo-path "/db.previous.sqlite")
(def ^:private repair-target-path "/repair-target.sqlite")
(def ^:private previous-artifact-paths
  {:previous-path previous-repo-path
   :previous-wal-path (str previous-repo-path "-wal")
   :previous-shm-path (str previous-repo-path "-shm")})

(def ^:private activation-record-key "selfhost.activation-record.v1")
(def ^:private empty-activation-record
  {:format-version 1
   :record-kind :selfhost-activation
   :committed {:projection-epoch 0}
   :previous nil
   :operation nil
   :prepared-swap nil})
(def ^:private activation-fields
  #{:format-version :record-kind :committed :previous :operation :prepared-swap})
(def ^:private operation-fields
  #{:operation-id :graph-id :target-projection-epoch :start-basis :target-basis
    :disposition :attempt-count :next-retry-at-ms :last-error})
(def ^:private prepared-swap-fields
  #{:operation-id :source-artifact-identity :source-sha256 :target-artifact-identity
    :target-sha256 :target-basis :written-at-ms})
(def ^:private basis-fields
  #{:remote-t :server-checkpoint-identity :journal-high-water :checksum-basis})
(def ^:private checksum-basis-fields
  #{:version :legacy-checksum :server-recomputed-checksum :server-t :legacy-anchor :metadata-proof})
(def ^:private sha256-pattern #"^[0-9a-f]{64}$")

(defn- exact-fields?
  [value fields]
  (and (map? value) (= fields (set (keys value)))))

(defn- non-empty-string?
  [value]
  (and (string? value) (not (string/blank? value))))

(defn- non-negative-integer?
  [value]
  (and (integer? value) (>= value 0)))

(defn- valid-basis?
  [basis graph-id]
  (let [checksum-basis (:checksum-basis basis)
        remote-t (:remote-t basis)
        legacy-anchor (:legacy-anchor checksum-basis)
        recomputed (:server-recomputed-checksum checksum-basis)]
    (and (exact-fields? basis basis-fields)
         (non-empty-string? graph-id)
         (non-negative-integer? remote-t)
         (non-negative-integer? (:journal-high-water basis))
         (exact-fields? checksum-basis checksum-basis-fields)
         (= 1 (:version checksum-basis))
         (non-empty-string? (:legacy-checksum checksum-basis))
         (non-empty-string? recomputed)
         (= remote-t (:server-t checksum-basis))
         (= legacy-anchor recomputed)
         (= (str "sync-do-checkpoint-v1:" graph-id ":" remote-t ":"
                 legacy-anchor)
            (:server-checkpoint-identity basis))
         (= (str "authenticated-diagnostics-v1:" graph-id ":" remote-t ":"
                 legacy-anchor ":" recomputed)
            (:metadata-proof checksum-basis)))))

(defn- basis-not-before?
  [target-basis start-basis]
  (and (>= (:remote-t target-basis) (:remote-t start-basis))
       (>= (:journal-high-water target-basis)
           (:journal-high-water start-basis))))

(defn- valid-last-error?
  [last-error]
  (or (nil? last-error)
      (and (exact-fields? last-error #{:type :at-ms})
           (non-empty-string? (:type last-error))
           (non-negative-integer? (:at-ms last-error)))))

(defn- valid-operation?
  [operation committed-epoch prepared-swap previous]
  (or (nil? operation)
      (let [start-basis (:start-basis operation)
            target-basis (:target-basis operation)
            target-epoch (:target-projection-epoch operation)
            target-committed? (= committed-epoch target-epoch)]
        (and (exact-fields? operation operation-fields)
             (uuid? (:operation-id operation))
             (non-empty-string? (:graph-id operation))
             (or (= (inc committed-epoch) target-epoch)
                 (and target-committed?
                      (nil? prepared-swap)
                      previous
                      (= :repairing (:disposition operation))
                      (some? target-basis)))
             (valid-basis? start-basis (:graph-id operation))
             (or (nil? target-basis)
                 (and (valid-basis? target-basis (:graph-id operation))
                      (basis-not-before? target-basis start-basis)))
             (contains? #{:repairing :local-only} (:disposition operation))
             (pos-int? (:attempt-count operation))
             (or (nil? (:next-retry-at-ms operation))
                 (non-negative-integer? (:next-retry-at-ms operation)))
             (valid-last-error? (:last-error operation))))))

(defn- valid-prepared-swap?
  [prepared-swap operation]
  (or (nil? prepared-swap)
      (and operation
           (= :repairing (:disposition operation))
           (exact-fields? prepared-swap prepared-swap-fields)
           (= (:operation-id operation) (:operation-id prepared-swap))
           (non-empty-string? (:source-artifact-identity prepared-swap))
           (string? (:source-sha256 prepared-swap))
           (re-matches sha256-pattern (:source-sha256 prepared-swap))
           (non-empty-string? (:target-artifact-identity prepared-swap))
           (not= (:source-artifact-identity prepared-swap)
                 (:target-artifact-identity prepared-swap))
           (string? (:target-sha256 prepared-swap))
           (re-matches sha256-pattern (:target-sha256 prepared-swap))
           (not= (:source-sha256 prepared-swap) (:target-sha256 prepared-swap))
           (= (:target-basis operation) (:target-basis prepared-swap))
           (valid-basis? (:target-basis prepared-swap) (:graph-id operation))
           (non-negative-integer? (:written-at-ms prepared-swap)))))

(defn- valid-activation-record?
  [record]
  (let [committed (:committed record)
        previous (:previous record)
        operation (:operation record)
        committed-epoch (:projection-epoch committed)]
    (and (exact-fields? record activation-fields)
         (= 1 (:format-version record))
         (= :selfhost-activation (:record-kind record))
         (exact-fields? committed #{:projection-epoch})
         (non-negative-integer? committed-epoch)
         (or (nil? previous)
             (and (exact-fields? previous #{:projection-epoch})
                  (non-negative-integer? (:projection-epoch previous))
                  (< (:projection-epoch previous) committed-epoch)))
         (valid-operation? operation committed-epoch (:prepared-swap record) previous)
         (valid-prepared-swap? (:prepared-swap record) operation))))

(defn- decode-activation-record
  [raw]
  (let [record (try
                 (ldb/read-transit-str raw)
                 (catch :default _
                   nil))]
    (when-not (valid-activation-record? record)
      (throw (ex-info "Invalid self-host activation record"
                      {:type :selfhost6/invalid-activation-record})))
    record))

(defn- reconcile-prepared-activation
  [record canonical-sha]
  (let [{:keys [source-sha256 target-sha256]} (:prepared-swap record)]
    (cond
      (= canonical-sha source-sha256)
      (assoc record :prepared-swap nil)

      (= canonical-sha target-sha256)
      (assoc record
             :committed {:projection-epoch (get-in record [:operation :target-projection-epoch])}
             :previous (:committed record)
             :prepared-swap nil)

      :else
      (throw (ex-info "Canonical graph does not match prepared swap"
                      {:type :selfhost6/ambiguous-prepared-swap})))))

(defn- initialize-activation-record!
  [client-ops-db]
  (let [empty-raw (ldb/write-transit-str empty-activation-record)
        {:keys [value]} (client-op/insert-sync-meta-value-if-absent!
                         client-ops-db activation-record-key empty-raw)]
    {:raw value
     :record (decode-activation-record value)}))

(defn- <reconcile-activation-record!
  [pool client-ops-db {:keys [raw record]}]
  (if-not (:prepared-swap record)
    (p/resolved record)
    (p/let [{:keys [canonical-sha256]}
            (platform/inspect-db-artifact-set
             (platform/current) pool
             {:canonical-path repo-path
              :wal-path (str repo-path "-wal")
              :shm-path (str repo-path "-shm")})]
      (let [prepared (:prepared-swap record)
            target-side? (= canonical-sha256 (:target-sha256 prepared))
            next-record (reconcile-prepared-activation record canonical-sha256)
            next-raw (ldb/write-transit-str next-record)
            committed?
            (if target-side?
              (client-op/commit-repair-activation-and-sync-meta!
               client-ops-db activation-record-key raw next-raw
               (get-in prepared [:target-basis :remote-t])
               (get-in prepared
                       [:target-basis :checksum-basis :legacy-checksum]))
              (client-op/compare-and-set-sync-meta-value!
               client-ops-db activation-record-key raw next-raw))]
        (when-not committed?
          (throw (ex-info "Activation record changed during startup reconciliation"
                          {:type :selfhost6/activation-record-cas-mismatch})))
        next-record))))

(defn- read-activation-record!
  [client-ops-db]
  (let [{:keys [found? value]}
        (client-op/read-sync-meta-value client-ops-db activation-record-key)]
    (when-not found?
      (throw (ex-info "Missing self-host activation record"
                      {:type :selfhost6/missing-activation-record})))
    {:raw value
     :record (decode-activation-record value)}))

(defn- update-activation-record!
  [client-ops-db update-fn]
  (let [{:keys [raw record]} (read-activation-record! client-ops-db)
        next-record (update-fn record)
        _ (when-not (valid-activation-record? next-record)
            (throw (ex-info "Repair commit produced an invalid activation record"
                            {:type :selfhost6/invalid-activation-record})))
        next-raw (ldb/write-transit-str next-record)]
    (when-not (client-op/compare-and-set-sync-meta-value!
               client-ops-db activation-record-key raw next-raw)
      (throw (ex-info "Activation record changed during repair commit"
                      {:type :selfhost6/activation-record-cas-mismatch})))
    next-record))

(defn- persist-repair-target-basis!
  [client-ops-db operation-id target-basis]
  (update-activation-record!
   client-ops-db
   (fn [record]
     (let [operation (:operation record)]
       (when-not (and (= operation-id (:operation-id operation))
                      (= :repairing (:disposition operation))
                      (nil? (:prepared-swap record))
                      (valid-basis? target-basis (:graph-id operation))
                      (basis-not-before? target-basis (:start-basis operation)))
         (throw (ex-info "Repair target basis cannot be committed"
                         {:type :selfhost6/repair-target-basis-rejected
                          :operation-id operation-id})))
       (assoc-in record [:operation :target-basis] target-basis)))))

(defn- persist-prepared-swap!
  [client-ops-db operation-id target-basis artifact-proof]
  (update-activation-record!
   client-ops-db
   (fn [record]
     (let [operation (:operation record)
           prepared-swap
           {:operation-id operation-id
            :source-artifact-identity (:source-artifact-identity artifact-proof)
            :source-sha256 (:source-sha256 artifact-proof)
            :target-artifact-identity (:target-artifact-identity artifact-proof)
            :target-sha256 (:target-sha256 artifact-proof)
            :target-basis target-basis
            :written-at-ms (js/Date.now)}]
       (when-not (and (= operation-id (:operation-id operation))
                      (= target-basis (:target-basis operation))
                      (nil? (:prepared-swap record)))
         (throw (ex-info "Repair prepared proof cannot be written"
                         {:type :selfhost6/repair-prepared-proof-rejected
                          :operation-id operation-id})))
       (assoc record :prepared-swap prepared-swap)))))

(defn- roll-forward-repair-commit!
  [repo client-ops-db operation-id target-sha256]
  (let [{:keys [raw record]} (read-activation-record! client-ops-db)
        _ (when-not (= operation-id (get-in record [:operation :operation-id]))
            (throw (ex-info "Repair operation changed after canonical rename"
                            {:type :selfhost6/repair-operation-changed
                             :operation-id operation-id})))
        prepared (:prepared-swap record)
        next-record (reconcile-prepared-activation record target-sha256)
        next-raw (ldb/write-transit-str next-record)]
    (when-not
     (client-op/commit-repair-activation-and-sync-meta!
      client-ops-db activation-record-key raw next-raw
      (get-in prepared [:target-basis :remote-t])
      (get-in prepared [:target-basis :checksum-basis :legacy-checksum]))
      (throw (ex-info "Activation record changed after canonical rename"
                      {:type :selfhost6/activation-record-cas-mismatch
                       :operation-id operation-id})))
    (swap! db-sync/*repo->latest-remote-tx
           assoc repo (get-in prepared [:target-basis :remote-t]))
    (swap! db-sync/*repo->latest-remote-checksum
           assoc repo (get-in prepared
                              [:target-basis :checksum-basis :legacy-anchor]))
    next-record))

(defn- claim-repair-operation!
  [repo graph-id start-basis]
  (when-not (and (non-empty-string? graph-id) (valid-basis? start-basis graph-id))
    (throw (ex-info "Invalid repair claim basis"
                    {:type :selfhost6/invalid-repair-claim
                     :repo repo})))
  (let [client-ops-db (or (worker-state/get-client-ops-conn repo)
                          (throw (ex-info "Missing client-ops connection"
                                          {:type :selfhost6/missing-client-ops
                                           :repo repo})))
        claim-once
        (fn []
          (let [{:keys [raw record]} (read-activation-record! client-ops-db)]
            (if-let [operation (:operation record)]
              (if (= graph-id (:graph-id operation))
                {:claimed? false :operation operation}
                (throw (ex-info "Repair operation belongs to another graph"
                                {:type :selfhost6/repair-operation-graph-mismatch
                                 :repo repo})))
              (let [operation {:operation-id (random-uuid)
                               :graph-id graph-id
                               :target-projection-epoch
                               (inc (get-in record [:committed :projection-epoch]))
                               :start-basis start-basis
                               :target-basis nil
                               :disposition :repairing
                               :attempt-count 1
                               :next-retry-at-ms nil
                               :last-error nil}
                    next-record (assoc record :operation operation)
                    next-raw (ldb/write-transit-str next-record)]
                (if (client-op/compare-and-set-sync-meta-value!
                     client-ops-db activation-record-key raw next-raw)
                  {:claimed? true :operation operation}
                  nil)))))]
    (or (claim-once)
        ;; One bounded re-read resolves a concurrent elected-owner request.
        (let [{:keys [record]} (read-activation-record! client-ops-db)]
          (if-let [operation (:operation record)]
            (if (= graph-id (:graph-id operation))
              {:claimed? false :operation operation}
              (throw (ex-info "Repair operation belongs to another graph"
                              {:type :selfhost6/repair-operation-graph-mismatch
                               :repo repo})))
            (throw (ex-info "Activation record changed during repair claim"
                            {:type :selfhost6/activation-record-cas-mismatch
                             :repo repo})))))))

(defn- repair-operation-status
  [repo]
  (when-let [client-ops-db (worker-state/get-client-ops-conn repo)]
    (let [record (:record (read-activation-record! client-ops-db))]
      {:committed (:committed record)
       :previous (:previous record)
       :operation (:operation record)
       :prepared-swap? (some? (:prepared-swap record))})))

(defn- repair-operation-status-from-value
  [raw]
  (when raw
    (let [record (decode-activation-record raw)]
      {:committed (:committed record)
       :previous (:previous record)
       :operation (:operation record)
       :prepared-swap? (some? (:prepared-swap record))})))

(defn- record-repair-failure!
  [repo operation-id {:keys [disposition next-retry-at-ms error-type at-ms]}]
  (when-not (and (contains? #{:repairing :local-only} disposition)
                 (or (nil? next-retry-at-ms)
                     (non-negative-integer? next-retry-at-ms))
                 (non-empty-string? error-type)
                 (non-negative-integer? at-ms))
    (throw (ex-info "Invalid repair failure transition"
                    {:type :selfhost6/invalid-repair-failure-transition})))
  (let [client-ops-db (or (worker-state/get-client-ops-conn repo)
                          (throw (ex-info "Missing client-ops connection"
                                          {:type :selfhost6/missing-client-ops
                                           :repo repo})))]
    (update-activation-record!
     client-ops-db
     (fn [record]
       (let [operation (:operation record)
             committed-epoch (get-in record [:committed :projection-epoch])]
         (when-not (and (= operation-id (:operation-id operation))
                        (= :repairing (:disposition operation))
                        (= (inc committed-epoch)
                           (:target-projection-epoch operation))
                        (nil? (:prepared-swap record))
                        (or (= :repairing disposition)
                            (nil? next-retry-at-ms)))
           (throw (ex-info "Repair failure cannot change this operation"
                           {:type :selfhost6/repair-failure-transition-rejected
                            :repo repo
                            :operation-id operation-id})))
         (assoc record :operation
                (assoc operation
                       :disposition disposition
                       :next-retry-at-ms next-retry-at-ms
                       :last-error {:type error-type :at-ms at-ms})))))))

(defn- claim-repair-retry!
  [repo operation-id explicit? now-ms]
  (let [client-ops-db (or (worker-state/get-client-ops-conn repo)
                          (throw (ex-info "Missing client-ops connection"
                                          {:type :selfhost6/missing-client-ops
                                           :repo repo})))]
    (:operation
     (update-activation-record!
      client-ops-db
      (fn [record]
        (let [operation (:operation record)
              disposition (:disposition operation)
              retry-at (:next-retry-at-ms operation)
              committed-epoch (get-in record [:committed :projection-epoch])]
          (when-not (and (= operation-id (:operation-id operation))
                         (= (inc committed-epoch)
                            (:target-projection-epoch operation))
                         (nil? (:prepared-swap record))
                         (if explicit?
                           (= :local-only disposition)
                           (and (= :repairing disposition)
                                (non-negative-integer? retry-at)
                                (>= now-ms retry-at))))
            (throw (ex-info "Repair retry cannot claim this operation"
                            {:type :selfhost6/repair-retry-rejected
                             :repo repo
                             :operation-id operation-id})))
          (assoc record :operation
                 (assoc operation
                        :disposition :repairing
                        :attempt-count (inc (:attempt-count operation))
                        :next-retry-at-ms nil
                        :last-error nil))))))))

(defn- complete-repair-operation!
  [repo operation-id verification]
  (let [client-ops-db (or (worker-state/get-client-ops-conn repo)
                          (throw (ex-info "Missing client-ops connection"
                                          {:type :selfhost6/missing-client-ops
                                           :repo repo})))
        record
        (update-activation-record!
         client-ops-db
         (fn [record]
           (let [operation (:operation record)
                 target (:target-basis operation)
                 committed-epoch (get-in record [:committed :projection-epoch])
                 current-remote-t (:remote-t verification)
                 current-high-water (:journal-high-water verification)
                 checksums (map verification
                                [:local-checksum :remote-checksum
                                 :canonical-checksum])]
             (when-not
              (and (= operation-id (:operation-id operation))
                   (= :repairing (:disposition operation))
                   (= committed-epoch (:target-projection-epoch operation))
                   (nil? (:prepared-swap record))
                   (= (:graph-id operation) (:graph-id verification))
                   (non-negative-integer? current-remote-t)
                   (>= current-remote-t (:remote-t target))
                   (non-negative-integer? current-high-water)
                   (>= current-high-water (:journal-high-water target))
                   (zero? (:pending-through-target verification))
                   (every? non-empty-string? checksums)
                   (apply = checksums)
                   (or (> current-remote-t (:remote-t target))
                       (= (:canonical-checksum verification)
                          (get-in target [:checksum-basis :legacy-checksum]))))
              (throw (ex-info "Repair completion basis is not converged"
                              {:type :selfhost6/repair-completion-rejected
                               :repo repo
                               :operation-id operation-id})))
             (assoc record :operation nil))))]
    {:completed? true
     :operation-id operation-id
     :previous (:previous record)}))

(defn- clear-previous-metadata!
  [client-ops-db expected-previous]
  (update-activation-record!
   client-ops-db
   (fn [record]
     (if (and (nil? (:operation record))
              (= expected-previous (:previous record)))
       (assoc record :previous nil)
       record))))

(defn- <cleanup-previous-artifact!
  [repo]
  (let [client-ops-db (worker-state/get-client-ops-conn repo)
        activation-row (when client-ops-db
                         (client-op/read-sync-meta-value
                          client-ops-db activation-record-key))
        record (when (:found? activation-row)
                 (decode-activation-record (:value activation-row)))
        previous (:previous record)]
    (if-not (and client-ops-db previous (nil? (:operation record)))
      (p/resolved {:cleaned? false})
      (p/let [_ (platform/<cleanup-db-previous!
                 (platform/current) (get-storage-pool repo)
                 previous-artifact-paths)
              _ (clear-previous-metadata! client-ops-db previous)]
        {:cleaned? true}))))

(defn- repair-operation-for-staging!
  [repo operation-id]
  (let [client-ops-db (or (worker-state/get-client-ops-conn repo)
                          (throw (ex-info "Missing client-ops connection"
                                          {:type :selfhost6/missing-client-ops
                                           :repo repo})))
        record (:record (read-activation-record! client-ops-db))
        operation (:operation record)]
    (when-not (and (= operation-id (:operation-id operation))
                   (= :repairing (:disposition operation))
                   (= (inc (get-in record [:committed :projection-epoch]))
                      (:target-projection-epoch operation))
                   (nil? (:prepared-swap record)))
      (throw (ex-info "Repair operation cannot build a staging target"
                      {:type :selfhost6/repair-operation-not-buildable
                       :repo repo
                       :operation-id operation-id})))
    operation))

(defn- <build-repair-staging!
  [repo operation-id]
  (let [operation (repair-operation-for-staging! repo operation-id)]
    (-> (sync-download/<build-repair-staging! repo operation)
        (p/then
         (fn [result]
           {:operation-id operation-id
            :target-basis (:target-basis result)
            :remote-count (get-in result [:remote-result :remote-count])
            :local-count (get-in result [:local-result :local-count])}))
        (p/catch
         (fn [error]
           (throw (ex-info "Repair staging build failed"
                           {:type :selfhost6/repair-staging-build-failed
                            :repo repo
                            :operation-id operation-id
                            :cause-type (:type (ex-data error))}
                           error)))))))

(defn- checkpoint-db-strict!
  [^Object db]
  (when db
    (.exec db wal-checkpoint-sql)))

(defn- close-canonical-for-repair!
  [repo]
  (let [{:keys [db search client-ops]} (get @*sqlite-conns repo)]
    (when-not (and db search client-ops)
      (throw (ex-info "Canonical graph handles are not open for repair commit"
                      {:type :selfhost6/repair-canonical-not-open
                       :repo repo})))
    (when-let [timer (get @*wal-checkpoint-timers repo)]
      (js/clearTimeout timer))
    (swap! *wal-checkpoint-timers dissoc repo)
    (checkpoint-db-strict! db)
    (checkpoint-db-strict! search)
    (when-let [vector-index (worker-state/get-vector-index repo)]
      (when-let [close-fn (:close! vector-index)]
        (close-fn)))
    (swap! *vector-indexes dissoc repo)
    (swap! *datascript-conns dissoc repo)
    (search-handler/clear-search-index-builds! repo)
    (.close db)
    (.close search)
    (swap! *sqlite-conns assoc repo {:client-ops client-ops})
    client-ops))

(declare <create-or-open-db!)

(defn- <reopen-canonical-after-repair!
  [repo client-ops-db]
  (try
    (.close client-ops-db)
    (catch :default error
      ;; A failed reopen may already have closed this handle. Startup below is
      ;; still the single interpreter for the durable prepared proof.
      (log/warn :db-worker/repair-client-ops-already-closed
                {:repo repo :error error})))
  (swap! *sqlite-conns dissoc repo)
  (swap! *client-ops-conns dissoc repo)
  (<create-or-open-db! repo {}))

(defn- <reconcile-after-repair-commit-error!
  [repo client-ops-db]
  ;; Reopen through the normal startup path. It opens a fresh client-ops
  ;; handle, reconciles source-side or target-side prepared proof before the
  ;; canonical handle, and therefore also works when a failed reopen already
  ;; closed the old handle.
  (<reopen-canonical-after-repair! repo client-ops-db))

(defn- <capture-async-error
  [f]
  (try
    (-> (f)
        (p/then (constantly {:error nil}))
        (p/catch (fn [error] {:error error}))
        (p/then :error))
    (catch :default error
      (p/resolved error))))

(defn- <recover-repair-commit-error!
  [repo operation-id canonical-closed? client-ops-db staging error]
  (p/let [recovery-error
          (when (and canonical-closed? client-ops-db)
            (<capture-async-error
             #(<reconcile-after-repair-commit-error! repo client-ops-db)))
          cleanup-error
          (when staging
            (<capture-async-error
             #(sync-download/<cleanup-repair-staging! staging)))]
    (cond
      recovery-error
      (throw
       (ex-info "Repair commit failed and canonical recovery failed"
                {:type :selfhost6/repair-commit-recovery-failed
                 :repo repo
                 :operation-id operation-id
                 :original-error error
                 :recovery-error recovery-error
                 :cleanup-error cleanup-error}
                error))

      cleanup-error
      (throw
       (ex-info "Repair commit failed and staging cleanup failed"
                {:type :selfhost6/repair-commit-cleanup-failed
                 :repo repo
                 :operation-id operation-id
                 :original-error error
                 :cleanup-error cleanup-error}
                error))

      :else
      (throw error))))

(defn- <commit-repair-staging!
  [repo operation-id]
  ;; Desktop repair commits require the Node storage adapter's one-rename
  ;; primitive. Fail before entering the fence or touching either database.
  (platform/require-db-artifact-swap! (platform/current))
  ;; The full snapshot/tail build stays outside the fence. Only final local
  ;; catch-up, checkpoint, proof, rename and reopen drain graph calls.
  (p/let [operation (repair-operation-for-staging! repo operation-id)
          initial-staging (sync-download/<build-repair-staging! repo operation)
          owner-token (repair-commit-fence/<enter! repo operation-id)]
    (let [canonical-closed? (atom false)
          client-ops* (atom nil)
          staging* (atom initial-staging)]
      (-> (p/let [staging (sync-download/<finalize-repair-staging! initial-staging)
                  _ (reset! staging* staging)
                  target-basis (:target-basis staging)
                  client-ops-db (worker-state/get-client-ops-conn repo)
                  _ (reset! client-ops* client-ops-db)
                  _ (persist-repair-target-basis!
                     client-ops-db operation-id target-basis)
                  staging (sync-download/seal-repair-staging! staging)
                  _ (reset! staging* staging)
                  canonical-pool (get-storage-pool repo)
                  _ (close-canonical-for-repair! repo)
                  _ (reset! canonical-closed? true)
                  paths {:canonical-path repo-path
                         :canonical-wal-path (str repo-path "-wal")
                         :canonical-shm-path (str repo-path "-shm")
                         :previous-path previous-repo-path
                         :target-path repair-target-path
                         :target-wal-path (str repair-target-path "-wal")
                         :target-shm-path (str repair-target-path "-shm")}
                  artifact-proof (platform/prepare-db-artifact-swap!
                                  (platform/current) canonical-pool
                                  (get-in staging [:target :pool]) paths)
                  _ (persist-prepared-swap!
                     client-ops-db operation-id target-basis artifact-proof)
                  rename-result (platform/commit-db-artifact-swap!
                                 (platform/current) canonical-pool
                                 (get-in staging [:target :pool]) paths)
                  _ (roll-forward-repair-commit!
                     repo client-ops-db operation-id
                     (:target-sha256 artifact-proof))
                  _ (<reopen-canonical-after-repair! repo client-ops-db)
                  _ (reset! canonical-closed? false)
                  _ (worker-undo-redo/clear-history! repo)
                  _ (-> (search-handler/<rebuild-blocks-indice! repo true)
                        (p/catch
                         (fn [error]
                           (log/error :db-worker/repair-search-rebuild-start-failed
                                      {:repo repo :error error})
                           nil)))
                  projection-epoch
                  (get-in (read-activation-record!
                           (worker-state/get-client-ops-conn repo))
                          [:record :committed :projection-epoch])
                  _ (shared-service/broadcast-to-clients!
                     :notification
                     [nil :warning nil nil nil
                      {:i18n-key :sync/repair-undo-reset-warning}])
                  _ (shared-service/broadcast-to-clients!
                     :projection-committed
                     {:repo repo :projection-epoch projection-epoch})
                  _ (sync-download/<cleanup-repair-staging! staging)]
            {:operation-id operation-id
             :projection-epoch projection-epoch
             :target-basis target-basis
             :source-sha256 (:source-sha256 artifact-proof)
             :target-sha256 (:target-sha256 artifact-proof)
             :rename-count (:rename-count rename-result)})
          (p/catch
           (fn [error]
             (<recover-repair-commit-error!
              repo operation-id @canonical-closed? @client-ops* @staging* error)))
          (p/finally
           (fn []
             (when (repair-commit-fence/owner? repo owner-token)
               (repair-commit-fence/release! repo owner-token))))))))

(defn- resolve-db-path
  [repo pool path]
  (let [storage (platform/storage (platform/current))]
    (if-let [f (:resolve-db-path storage)]
      (f repo pool path)
      path)))

(defn- checkpoint-db!
  ([^Object db]
   (checkpoint-db! nil db))
  ([repo ^Object db]
   (when (and db (fn? (.-exec db)))
     (try
       (.exec db wal-checkpoint-sql)
       (catch :default e
         (log/warn :db-worker/wal-checkpoint-failed
                   (cond-> {:error e}
                     repo (assoc :repo repo))))))))

(defn- schedule-wal-checkpoint!
  [repo db]
  (when-let [timer (get @*wal-checkpoint-timers repo)]
    (js/clearTimeout timer))
  (let [timer (js/setTimeout
               (fn []
                 (swap! *wal-checkpoint-timers dissoc repo)
                 (checkpoint-db! repo db))
               wal-checkpoint-idle-ms)]
    (swap! *wal-checkpoint-timers assoc repo timer)))

(defn- <export-db-file
  ([repo]
   (<export-db-file repo repo-path))
  ([repo path]
   (p/let [^js pool (<get-opfs-pool repo)]
     (when pool
       (let [storage (platform/storage (platform/current))]
         ((:export-file storage) pool path))))))

(defn- ->uint8array
  [data]
  (cond
    (instance? js/Uint8Array data)
    data

    (js/ArrayBuffer.isView data)
    (js/Uint8Array. (.-buffer data) (.-byteOffset data) (.-byteLength data))

    (instance? js/ArrayBuffer data)
    (js/Uint8Array. data)

    (array? data)
    (js/Uint8Array. data)

    :else
    data))

(defn- <export-db-file-with-paths
  [repo path-candidates]
  (let [paths (->> path-candidates
                   (filter string?)
                   (remove string/blank?)
                   distinct
                   vec)]
    (letfn [(try-export [remaining-paths]
              (if-let [path (first remaining-paths)]
                (-> (<export-db-file repo path)
                    (p/then (fn [result]
                              (let [payload (->uint8array result)]
                                (if (instance? js/Uint8Array payload)
                                  payload
                                  (try-export (subvec remaining-paths 1))))))
                    (p/catch (fn [_]
                               (try-export (subvec remaining-paths 1)))))
                (p/resolved nil)))]
      (try-export paths))))

(defn- <import-db
  [^js pool data]
  (let [storage (platform/storage (platform/current))]
    ((:import-db storage) pool repo-path data)))

(defn- import-state-summary
  [import-state]
  (into {}
        (map (fn [[k v]]
               [k (if (satisfies? IDeref v) @v v)]))
        import-state))

(defn- file-content
  [file]
  (or (:file/content file)
      (:content file)
      ""))

(defn- import-file-payload
  [payload]
  (cond
    (instance? js/Uint8Array payload)
    payload

    (instance? js/ArrayBuffer payload)
    (js/Uint8Array. payload)

    (array? payload)
    (js/Uint8Array. payload)

    :else
    nil))

(defn- <read-and-stage-import-asset
  [file assets buffer-handler staged-assets]
  (when-let [payload (some-> file :asset/payload import-file-payload)]
    (let [buffer (.-buffer payload)
          asset-type (db-asset/asset-path->type (:path file))
          asset-id (d/squuid)
          asset-name (some-> (:path file) gp-exporter/asset-path->name)
          size (or (:asset/size file) (.-byteLength payload))]
      (p/let [checksum (db-asset/<get-file-array-buffer-checksum buffer)
              {:keys [with-edn-content pdf-annotation?]} (buffer-handler payload)
              asset-data (with-edn-content
                           {:size size
                            :type asset-type
                            :path (:path file)
                            :checksum checksum
                            :asset-id asset-id})]
        (swap! assets assoc asset-name asset-data)
        (when-not pdf-annotation?
          (swap! staged-assets conj {:path (:path file)
                                     :asset-id asset-id
                                     :asset-type asset-type
                                     :payload payload}))))))

(defn- finalize-import-render-revisions!
  [conn]
  (let [db @conn
        entity-ids (d/q '[:find [?e ...]
                          :where
                          [?e :block/uuid]
                          [?e :block/title]
                          [(missing? $ ?e :block/tx-id)]]
                        db)]
    (when (seq entity-ids)
      (let [tx-id (inc (:max-tx db))]
        (ldb/transact! conn
                       (mapv (fn [entity-id]
                               {:db/id entity-id
                                :block/tx-id tx-id})
                             entity-ids)
                       {::gp-exporter/imported-data? true})))))

(defn- <import-file-graph!
  [repo config-file files opts]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [notifications (atom [])
          staged-assets (atom [])
          options (-> opts
                      (assoc :notify-user #(swap! notifications conj %)
                             :log-fn (fn [& args]
                                       (log/info :import-file-graph {:args args}))
                             :<read-file (fn [file] (p/resolved (file-content file)))
                             :<get-file-stat (constantly nil)
                             :<read-and-copy-asset (fn [file assets buffer-handler]
                                                     (<read-and-stage-import-asset file assets buffer-handler staged-assets)))
                      (dissoc :set-ui-state))]
      (p/let [result (gp-exporter/export-file-graph conn conn config-file files options)
              _ (finalize-import-render-revisions! conn)
              validation (worker-db-validate/validate-db conn :fix false)]
        {:files (:files result)
         :import-state (import-state-summary (:import-state result))
         :notifications @notifications
         :staged-assets @staged-assets
         :validation {:errors (:errors validation)
                      :invalid-entity-ids (:invalid-entity-ids validation)}}))))

(defn upsert-addr-content!
  "Upsert addr+data-seq. Update sqlite-cli/upsert-addr-content! when making changes"
  [db data]
  (assert (some? db) "sqlite db not exists")
  (.transaction
   db
   (fn [tx]
     (doseq [item data]
       (.exec tx #js {:sql "INSERT INTO kvs (addr, content, addresses) values ($addr, $content, $addresses) on conflict(addr) do update set content = $content, addresses = $addresses"
                      :bind item})))))

(defn restore-data-from-addr
  "Update sqlite-cli/restore-data-from-addr when making changes"
  [db addr]
  (assert (some? db) "sqlite db not exists")
  (when-let [result (-> (.exec db #js {:sql "select content, addresses from kvs where addr = ?"
                                       :bind #js [addr]
                                       :rowMode "array"})
                        first)]
    (let [[content addresses] (bean/->clj result)
          addresses (when addresses
                      (js/JSON.parse addresses))
          data (sqlite-util/read-transit-str content)]
      (if (and addresses (map? data))
        (assoc data :addresses addresses)
        data))))

(defn new-sqlite-storage
  "Update sqlite-cli/new-sqlite-storage when making changes"
  [repo ^Object db]
  (reify IStorage
    (-store [_ addr+data-seq _delete-addrs]
      (let [data (map
                  (fn [[addr data]]
                    (let [data' (if (map? data) (dissoc data :addresses) data)
                          addresses (when (map? data)
                                      (when-let [addresses (:addresses data)]
                                        (js/JSON.stringify (bean/->js addresses))))]
                      #js {:$addr addr
                           :$content (sqlite-util/write-transit-str data')
                           :$addresses addresses}))
                  addr+data-seq)]
        (upsert-addr-content! db data)
        (schedule-wal-checkpoint! repo db)
        nil))

    (-restore [_ addr]
      (restore-data-from-addr db addr))))

(defn- close-db-aux!
  [repo ^Object db ^Object search ^Object client-ops]
  (when-let [timer (get @*wal-checkpoint-timers repo)]
    (js/clearTimeout timer))
  (swap! *wal-checkpoint-timers dissoc repo)
  (checkpoint-db! repo db)
  (checkpoint-db! repo search)
  (checkpoint-db! repo client-ops)
  (sync-download/close-import-state-for-repo! repo)
  (sync-download/close-repair-staging-for-repo! repo)
  (when-let [timer (get @*client-ops-cleanup-timers repo)]
    (js/clearInterval timer))
  (swap! *client-ops-cleanup-timers dissoc repo)
  (swap! *sqlite-conns dissoc repo)
  (when-let [vector-index (worker-state/get-vector-index repo)]
    (when-let [close-fn (:close! vector-index)]
      (close-fn)))
  (swap! *vector-indexes dissoc repo)
  (swap! *datascript-conns dissoc repo)
  (swap! *client-ops-conns dissoc repo)
  (worker-state/clear-projection-epoch! repo)
  (swap! client-op/*repo->pending-local-tx-count dissoc repo)
  (search-handler/clear-search-index-builds! repo)
  (when db (.close db))
  (when search (.close search))
  (when client-ops (.close client-ops))
  (when-let [^js pool (get-storage-pool repo)]
    (when (exists? (.-pauseVfs pool))
      (.pauseVfs pool)))
  (forget-storage-pool! repo))

(defn- close-other-dbs!
  [repo]
  (doseq [[r {:keys [db search client-ops]}] @*sqlite-conns]
    (when-not (graph-dir/same-repo? repo r)
      (close-db-aux! r db search client-ops))))

(defn close-db!
  [repo]
  (let [{:keys [db search client-ops]} (get @*sqlite-conns repo)]
    (close-db-aux! repo db search client-ops)))

(defn- <invalidate-search-db!
  [repo]
  (if-let [search-db (worker-state/get-sqlite-conn repo :search)]
    (do
      (search/truncate-table! search-db)
      (search/truncate-vector-index! (worker-state/get-vector-index repo))
      (p/resolved nil))
    (when-not @*publishing?
      (p/let [pool (<get-opfs-pool repo)
              search-path (resolve-db-path repo pool (str "search" repo-path))
              search-db (platform/sqlite-open (platform/current)
                                              {:sqlite @*sqlite
                                               :pool pool
                                               :path search-path
                                               :mode "c"})]
        (try
          (search/truncate-table! search-db)
          (finally
            (.close search-db)))
        nil))))

(defn- vector-index-path
  [repo pool]
  (resolve-db-path repo pool "search/vector"))

(declare enable-sqlite-wal-mode!)

(defn- <prepare-client-ops!
  [pool client-ops-db]
  (try
    (enable-sqlite-wal-mode! client-ops-db)
    (common-sqlite/create-kvs-table! client-ops-db)
    (let [activation (initialize-activation-record! client-ops-db)]
      (-> (<reconcile-activation-record! pool client-ops-db activation)
          (p/catch (fn [error]
                     (.close client-ops-db)
                     (throw error)))))
    (catch :default error
      (.close client-ops-db)
      (p/rejected error))))

(defn- close-startup-acquisitions!
  [repo close-fns]
  (doseq [close-fn (rseq @close-fns)]
    (try
      (close-fn)
      (catch :default error
        (log/warn :db-worker/startup-cleanup-failed
                  {:repo repo :error error}))))
  (worker-state/clear-projection-epoch! repo))

(defn- get-dbs
  [repo]
  (if @*publishing?
    (p/let [db (platform/sqlite-open (platform/current)
                                     {:sqlite @*sqlite
                                      :path "/db.sqlite"
                                      :mode "c"})
            search-db (platform/sqlite-open (platform/current)
                                            {:sqlite @*sqlite
                                             :path "/search-db.sqlite"
                                             :mode "c"})]
      [db search-db nil nil])
    (let [close-fns (atom [])]
      (-> (p/let [^js pool (<get-opfs-pool repo)
                  capacity (when (exists? (.-getCapacity pool))
                             (.getCapacity pool))
                  _ (when (and (some? capacity) (zero? capacity))
                      (.unpauseVfs pool))
                  db-path (resolve-db-path repo pool repo-path)
                  search-path (resolve-db-path repo pool (str "search" repo-path))
                  current-platform (platform/current)
                  vector-path (vector-index-path repo pool)
                  client-ops-path (resolve-db-path repo pool (str "client-ops-" repo-path))
                  _ (log/info :db-worker/get-dbs-open {:repo repo :client-ops-path client-ops-path})
                  client-ops-db (platform/sqlite-open (platform/current)
                                                      {:sqlite @*sqlite
                                                       :pool pool
                                                       :path client-ops-path})
                  activation-record (<prepare-client-ops! pool client-ops-db)
                  _ (swap! close-fns conj #(.close client-ops-db))
                  _ (worker-state/set-projection-epoch!
                     repo (get-in activation-record [:committed :projection-epoch]))
                  _ (log/info :db-worker/get-dbs-open {:repo repo :db-path db-path})
                  db (platform/sqlite-open (platform/current)
                                           {:sqlite @*sqlite
                                            :pool pool
                                            :path db-path})
                  _ (swap! close-fns conj #(.close db))
                  _ (log/info :db-worker/get-dbs-open {:repo repo :search-path search-path})
                  search-db (platform/sqlite-open (platform/current)
                                                  {:sqlite @*sqlite
                                                   :pool pool
                                                   :path search-path})
                  _ (swap! close-fns conj #(.close search-db))
                  vector-index (when (get-in current-platform [:vector :open-index])
                                 (platform/vector-open
                                  current-platform
                                  {:path vector-path
                                   :dimension (platform/embedding-dimension current-platform)}))
                  _ (when-let [close-fn (:close! vector-index)]
                      (swap! close-fns conj close-fn))]
            [db search-db client-ops-db vector-index])
          (p/catch (fn [error]
                     (close-startup-acquisitions! repo close-fns)
                     (throw error)))))))

(defn- enable-sqlite-wal-mode!
  [^Object db]
  (.exec db "PRAGMA locking_mode=exclusive")
  (.exec db "PRAGMA journal_mode=WAL"))

(defn- disable-sqlite-auto-checkpoint!
  [^Object db]
  (.exec db "PRAGMA wal_autocheckpoint=0"))

(defn- run-client-ops-cleanup!
  [repo]
  (let [protected-tx-ids (worker-undo-redo/referenced-history-tx-ids repo)]
    (client-op/cleanup-finished-history-ops! repo protected-tx-ids)
    nil))

(defn- ensure-client-ops-cleanup-timer!
  [repo]
  (when (and (not @*publishing?)
             repo
             (nil? (get @*client-ops-cleanup-timers repo)))
    (let [timer (js/setInterval (fn []
                                  (run-client-ops-cleanup! repo))
                                client-ops-cleanup-interval-ms)]
      (swap! *client-ops-cleanup-timers assoc repo timer))
    nil))

(defn- handle-migrate-result-local-txs!
  [repo migrate-result]
  (doseq [tx-report (:upgrade-result-coll migrate-result)]
    (db-sync/handle-local-tx! repo tx-report)))

(def ^:private built-in-sync-repair-tx-id
  #uuid "00000000-0000-4000-8000-652665286528")

(def ^:private built-in-sync-repair-properties
  [:logseq.property.repeat/repeat-type
   :logseq.property.comments/blocks])

(def ^:private built-in-sync-repair-classes
  [:logseq.class/Comments
   :logseq.class/Comment])

(def ^:private built-in-sync-repair-unordered-classes
  #{:logseq.class/Comments
    :logseq.class/Comment})

;; Fixed so duplicate repair txs from multiple clients converge on the same datoms.
(def ^:private built-in-sync-repair-timestamp 0)

(defn- stable-built-in-sync-repair-item
  [order item]
  (if (and (map? item) (:block/uuid item))
    (cond-> (assoc item
                   :block/created-at built-in-sync-repair-timestamp
                   :block/updated-at built-in-sync-repair-timestamp)
      (not (contains? built-in-sync-repair-unordered-classes (:db/ident item)))
      (assoc :block/order order))
    item))

(defn- built-in-sync-repair-tx-data
  []
  (let [properties built-in-sync-repair-properties
        new-properties (->> (select-keys db-property/built-in-properties properties)
                            sqlite-create-graph/build-properties
                            (map (fn [b] (assoc b :logseq.property/built-in? true))))
        new-classes (->> (select-keys db-class/built-in-classes built-in-sync-repair-classes)
                         (#(sqlite-create-graph/build-initial-classes* % (zipmap properties properties)))
                         (map (fn [b] (assoc b :logseq.property/built-in? true))))
        new-class-idents (keep (fn [class]
                                 (when-let [db-ident (:db/ident class)]
                                   {:db/ident db-ident}))
                               new-classes)
        tx-data (vec (concat new-class-idents new-properties new-classes))
        block-item-count (count (filter #(and (map? %) (:block/uuid %)) tx-data))
        orders (db-order/gen-n-keys block-item-count nil nil :max-key-atom (atom nil))
        *orders (atom orders)]
    (mapv (fn [item]
            (stable-built-in-sync-repair-item
             (when (and (map? item) (:block/uuid item))
               (let [order (first @*orders)]
                 (swap! *orders rest)
                 order))
             item))
          tx-data)))

(defn- enqueue-built-in-sync-repair!
  [repo]
  (when-not (client-op/get-local-tx-entry repo built-in-sync-repair-tx-id)
    (let [{:keys [should-inc-pending?]}
          (client-op/upsert-local-tx-entry!
           repo
           {:tx-id built-in-sync-repair-tx-id
            :created-at 0
            :pending? true
            :failed? false
            :outliner-op :fix
            :undo-redo :none
            :forward-outliner-ops []
            :inverse-outliner-ops []
            :inferred-outliner-ops? false
            :normalized-tx-data (built-in-sync-repair-tx-data)
            :reversed-tx-data []})]
      (when should-inc-pending?
        (client-op/adjust-pending-local-tx-count! repo 1)))))

(defn- maybe-enqueue-built-in-sync-repair!
  [repo conn migrate-result initial-data-exists?]
  (when (and (nil? migrate-result)
             initial-data-exists?
             (true? (:kv/value (d/entity @conn :logseq.kv/graph-remote?))))
    (enqueue-built-in-sync-repair! repo)))

(defn- debug-transit-raw->datoms
  [raw]
  (let [db-or-datoms (ldb/read-transit-str raw)]
    (if (d/db? db-or-datoms)
      (vec (d/datoms db-or-datoms :eavt))
      db-or-datoms)))

(defn- bootstrap-transact!
  [conn tx-data]
  (when (seq tx-data)
    (d/transact! conn tx-data {:initial-db? true})))

(defn- ensure-canonical-revisions!
  [conn]
  (let [db @conn
        tx-id (inc (:max-tx db))
        tx-data (keep (fn [datom]
                        (let [entity (d/entity db (:e datom))]
                          (when-not (nat-int? (:block/tx-id entity))
                            {:db/id (:db/id entity)
                             :block/tx-id tx-id})))
                      (d/datoms db :avet :block/uuid))]
    (bootstrap-transact! conn tx-data)))

(defn- <create-or-open-db!
  [repo {:keys [config datoms debug-transit-raw sync-download-graph? creating-remote-graph?] :as opts}]
  (let [datoms (or datoms
                   (when debug-transit-raw
                     (debug-transit-raw->datoms debug-transit-raw)))]
    (when creating-remote-graph?
      (when (and (worker-state/get-sqlite-conn repo :client-ops)
                 (nil? (client-op/get-local-tx repo)))
        (client-op/update-local-tx repo 0)))
    (when-not (worker-state/get-sqlite-conn repo)
      (p/let [[db search-db client-ops-db vector-index] (get-dbs repo)
              dbs [db search-db]
              storage (new-sqlite-storage repo db)]
        (swap! *sqlite-conns assoc repo {:db db
                                         :search search-db
                                         :client-ops client-ops-db})
        (when vector-index
          (swap! *vector-indexes assoc repo vector-index))
        (doseq [db' dbs]
          (enable-sqlite-wal-mode! db'))
        (disable-sqlite-auto-checkpoint! db)
        (common-sqlite/create-kvs-table! db)
        (search/create-tables-and-triggers! search-db)
        (ldb/register-transact-pipeline-fn! worker-pipeline/transact-pipeline)
        (ldb/register-debounce-fn! (gfun/debounce d/store 1000))
        (let [conn (common-sqlite/get-storage-conn storage db-schema/schema)
              _ (db-fix/check-and-fix-schema! conn)
              _ (when datoms
                  (let [ident-eids (into #{}
                                         (comp (filter (fn [datom]
                                                         (= (:a datom) :db/ident)))
                                               (map :e))
                                         datoms)
                        to-tx (fn [d] [:db/add (:e d) (:a d) (:v d)])
                        batch-size 20000
                        ident-batches (->> datoms
                                           (filter #(contains? ident-eids (:e %)))
                                           (map to-tx)
                                           (partition-all batch-size))
                        _ (doseq [batch ident-batches]
                            (bootstrap-transact! conn batch))
                        non-ident-batches (->> datoms
                                               (remove #(contains? ident-eids (:e %)))
                                               (map to-tx)
                                               (partition-all batch-size))]
                    (doseq [batch non-ident-batches]
                      (bootstrap-transact! conn batch))))
              client-ops-conn (when-not @*publishing? client-ops-db)
              initial-data-exists? (when (nil? datoms)
                                     (and (d/entity @conn :logseq.class/Root)
                                          (= "db" (:kv/value (d/entity @conn :logseq.kv/db-type)))))]
          (swap! *datascript-conns assoc repo conn)
          (swap! *client-ops-conns assoc repo client-ops-conn)
          (when creating-remote-graph?
            (when (nil? (client-op/get-local-tx repo))
              (client-op/update-local-tx repo 0)))
          (ensure-client-ops-cleanup-timer! repo)
          (let [initial-tx-report (when-not (or initial-data-exists?
                                                (seq datoms)
                                                sync-download-graph?)
                                    (let [config (resolve-initial-config config)
                                          initial-data (sqlite-create-graph/build-db-initial-data
                                                        config (select-keys opts [:import-type :graph-git-sha :creating-remote-graph?]))]
                                      (bootstrap-transact! conn initial-data)))]
            (when-not sync-download-graph?
              (let [migrate-result (db-migrate/migrate conn)]
                (if migrate-result
                  (handle-migrate-result-local-txs! repo migrate-result)
                  (maybe-enqueue-built-in-sync-repair! repo conn migrate-result initial-data-exists?)))
              (transaction-handler/maybe-run-recycle-gc! conn))

            (ensure-canonical-revisions! conn)

            (when initial-tx-report
              (db-sync/handle-local-tx! repo initial-tx-report))

            (db-listener/listen-db-changes! repo conn)

            (<cleanup-previous-artifact! repo)))))))


(defn- <list-all-dbs
  []
  (p/let [storage (platform/storage (platform/current))
          graph-names ((:list-graphs storage))]
    (p/all (map (fn [graph-name]
                  (p/let [repo (str sqlite-util/db-version-prefix graph-name)]
                    {:name repo}))
                graph-names))))

(def-thread-api :thread-api/list-db
  []
  (<list-all-dbs))

(defn- <db-exists?
  [graph]
  (let [storage (platform/storage (platform/current))]
    ((:db-exists? storage) graph)))

(defn- remove-vfs!
  [^js pool]
  (when pool
    (let [storage (platform/storage (platform/current))]
      ((:remove-vfs! storage) pool))))

(def-thread-api :thread-api/init
  []
  (thread-api/register-invoke-wrapper-fn!
   (fn [qkw args invoke]
     (if (= qkw :thread-api/db-sync-commit-repair)
       (invoke)
       (repair-commit-fence/<with-repo-call! (first args) invoke))))
  (init-sqlite-module!))

(defn- db-sync-dbs-open?
  [repo]
  (and (some? (worker-state/get-datascript-conn repo))
       (some? (worker-state/get-client-ops-conn repo))))

(declare start-db!)
(def-thread-api :thread-api/db-sync-start
  [repo]
  (if (db-sync-dbs-open? repo)
    (db-sync/start! repo)
    (p/do!
     (start-db! repo {:close-other-db? false})
     (db-sync/start! repo))))

;; [graph service]
(defonce *service (atom []))

(defn- remote-binary-function
  [qualified-kw-str & args]
  (let [qkw (keyword qualified-kw-str)]
    (vswap! thread-api/*profile update qkw inc)
    (if-let [f (@thread-api/*thread-apis qkw)]
      (p/let [result (thread-api/invoke-with-wrapper qkw args #(apply f args))]
        (if (instance? js/Uint8Array result)
          (let [transfer-fn (get-in (platform/current) [:storage :transfer])]
            (if (fn? transfer-fn)
              (transfer-fn result #js [(.-buffer result)])
              result))
          result))
      (throw (ex-info (str "not found thread-api: " qualified-kw-str) {})))))

(defonce fns {"remoteInvoke" thread-api/remote-function
              "remoteInvokeBinary" remote-binary-function})

(defn- start-db!
  [repo {:keys [close-other-db?]
         :or {close-other-db? true}
         :as opts}]
  (p/do!
   (when close-other-db?
     (close-other-dbs! repo))
   (when @shared-service/*master-client?
     (<create-or-open-db! repo (dissoc opts :close-other-db?)))
   nil))

(def-thread-api :thread-api/create-or-open-db
  [repo opts]
  (when-not (graph-dir/same-repo? repo (worker-state/get-current-repo)) ; graph switched
    (reset! worker-state/*deleted-block-uuid->db-id {}))
  (p/let [_ (start-db! repo opts)
          conn (or (worker-state/get-datascript-conn repo)
                   (throw (ex-info "Missing worker graph connection"
                                   {:type :db/missing-connection
                                    :repo repo})))]
    {:schema (:schema @conn)
     :projection-epoch (worker-state/get-projection-epoch repo)}))

(def-thread-api :thread-api/get-projection-epoch
  [repo]
  (when (worker-state/get-datascript-conn repo)
    (worker-state/get-projection-epoch repo)))

(def-thread-api :thread-api/unsafe-unlink-db
  [repo]
  (p/let [pool (<get-opfs-pool repo)
          _ (sync-crypt/cancel-ui-requests! {:reason :unsafe-unlink-db
                                             :repo repo})
          _ (close-db! repo)
          _result (remove-vfs! pool)]
    nil))

(def-thread-api :thread-api/close-db
  [repo]
  (sync-crypt/cancel-ui-requests! {:reason :close-db
                                   :repo repo})
  (close-db! repo)
  nil)

(def-thread-api :thread-api/db-sync-close-db
  [repo]
  (sync-crypt/cancel-ui-requests! {:reason :db-sync-close-db
                                   :repo repo})
  (close-db! repo))

(def-thread-api :thread-api/db-sync-invalidate-search-db
  [repo]
  (<invalidate-search-db! repo))

(def-thread-api :thread-api/db-sync-recreate-lock
  [repo]
  (if-let [recreate-lock-fn (get-in (platform/current) [:env :recreate-lock-fn])]
    (recreate-lock-fn repo)
    nil))

(def-thread-api :thread-api/db-sync-rehydrate-large-titles
  [repo graph-id]
  (db-sync/rehydrate-large-titles-from-db! repo graph-id))

(def-thread-api :thread-api/db-sync-claim-repair
  [repo graph-id start-basis]
  (claim-repair-operation! repo graph-id start-basis))

(def-thread-api :thread-api/db-sync-build-repair-staging
  [repo operation-id]
  (<build-repair-staging! repo operation-id))

(def-thread-api :thread-api/db-sync-commit-repair
  [repo operation-id]
  (<commit-repair-staging! repo operation-id))

(def-thread-api :thread-api/db-sync-repair-status
  [repo]
  (repair-operation-status repo))

(def-thread-api :thread-api/db-sync-repair-status-from-value
  [raw]
  (repair-operation-status-from-value raw))

(def-thread-api :thread-api/db-sync-record-repair-failure
  [repo operation-id transition]
  (record-repair-failure! repo operation-id transition))

(def-thread-api :thread-api/db-sync-claim-repair-retry
  [repo operation-id explicit? now-ms]
  (claim-repair-retry! repo operation-id explicit? now-ms))

(def-thread-api :thread-api/db-sync-complete-repair
  [repo operation-id verification]
  (p/let [result (complete-repair-operation!
                  repo operation-id verification)
          cleanup (<cleanup-previous-artifact! repo)]
    (assoc result :previous-cleanup cleanup)))

(def-thread-api :thread-api/db-sync-retry-repair
  [repo operation-id]
  (sync-download/<explicit-retry-repair! repo operation-id))

(def-thread-api :thread-api/db-sync-import-prepare
  [repo reset? graph-id graph-e2ee? & [total-datoms]]
  (sync-download/prepare-import! repo reset? graph-id graph-e2ee? total-datoms))

(def-thread-api :thread-api/db-sync-import-rows-chunk
  [rows graph-id import-id]
  (sync-download/import-rows-chunk! rows graph-id import-id))

(def-thread-api :thread-api/db-sync-import-finalize
  [repo graph-id remote-tx import-id]
  (sync-download/finalize-import! repo graph-id remote-tx import-id))

(def-thread-api :thread-api/release-access-handles
  [repo]
  (sync-download/close-import-state-for-repo! repo)
  (when-let [^js pool (get-storage-pool repo)]
    (when (exists? (.-pauseVfs pool))
      (.pauseVfs pool))
    nil))

(def-thread-api :thread-api/db-exists
  [repo]
  (<db-exists? repo))

(def-thread-api :thread-api/export-db-binary
  [repo]
  (when-let [^js db (worker-state/get-sqlite-conn repo :db)]
    (checkpoint-db! repo db))
  (p/let [data (<export-db-file repo)]
    (->uint8array data)))

(def-thread-api :thread-api/export-client-ops-db-binary
  [repo]
  (when-let [^js db (worker-state/get-sqlite-conn repo :client-ops)]
    (checkpoint-db! repo db))
  (let [^js client-ops-db (worker-state/get-sqlite-conn repo :client-ops)
        ^js pool (get-storage-pool repo)
        db-filename (some-> client-ops-db .-filename)
        db-file-name (subs repo-path 1)
        flat-client-ops-path (str "client-ops-" db-file-name)
        resolved-client-ops-path (when pool
                                   (resolve-db-path repo pool (str "client-ops-" repo-path)))
        export-paths [db-filename
                      resolved-client-ops-path
                      flat-client-ops-path
                      (str "/" flat-client-ops-path)
                      client-ops-repo-path
                      (str "/" client-ops-repo-path)
                      (str "client-ops" repo-path)
                      (str "/client-ops" repo-path)
                      (str "client-ops-" repo-path)
                      (str "/client-ops-" repo-path)]]
    (<export-db-file-with-paths repo export-paths)))

(def-thread-api :thread-api/backup-db-sqlite
  [repo dst-path]
  (when-not (string/blank? repo)
    (let [db (worker-state/get-sqlite-conn repo :db)
          backup-db-fn (get-in (platform/current) [:sqlite :backup-db])]
      (when-not db
        (throw (ex-info "graph not opened" {:code :graph-not-opened
                                            :repo repo})))
      (when-not (fn? backup-db-fn)
        (throw (ex-info "platform sqlite backup not supported"
                        {:code :backup-not-supported
                         :repo repo})))
      (checkpoint-db! repo db)
      (p/let [_ (backup-db-fn db dst-path)]
        {:path dst-path}))))

(def-thread-api :thread-api/import-db-binary
  [repo data]
  (when-not (string/blank? repo)
    (p/let [_ (close-db! repo)
            pool (<get-opfs-pool repo)
            _ (<import-db pool data)
            _ (start-db! repo {:import-type :sqlite-db})]
      nil)))

(def-thread-api :thread-api/import-file-graph
  [repo config-file files opts]
  (<import-file-graph! repo config-file files opts))

(comment
  (def-thread-api :general/dangerousRemoveAllDbs
    []
    (p/let [r (<list-all-dbs)
            dbs (ldb/read-transit-str r)]
      (p/all (map #(.unsafeUnlinkDB this (:name %)) dbs)))))

(defn- on-become-master
  [repo start-opts]
  (log/info :db-worker/on-become-master-start {:repo repo
                                               :import-type (:import-type start-opts)})
  (p/let [_ (init-sqlite-module!)
          _ (when-not (:import-type start-opts)
              (start-db! repo start-opts))]
    (when-not (:import-type start-opts)
      (assert (some? (worker-state/get-datascript-conn repo))))
    nil))

(def broadcast-data-types
  (set (map
        common-util/keyword->string
        [:sync-db-changes
         :projection-committed
         :sync-conflicts-updated
         :notification
         :log
         :add-repo
         :rtc-log
         :rtc-sync-state])))

(defn- <init-service!
  [graph start-opts]
  (let [[prev-graph service] @*service]
    (cond
      (nil? graph)
      (do
        (some-> prev-graph close-db!)
        nil)

      (and (= graph prev-graph) service)
      service

      :else
      (do
        (when (and prev-graph (not= graph prev-graph))
          (close-db! prev-graph))
        (log/info :db-worker/init-service {:graph graph
                                           :prev-graph prev-graph
                                           :import-type (:import-type start-opts)})
        (let [service-promise (shared-service/<create-service
                               graph
                               (bean/->js fns)
                               #(on-become-master graph start-opts)
                               broadcast-data-types
                               {:import? (:import-type? start-opts)})]
          (reset! *service [graph service-promise])
          (p/let [service service-promise]
            (assert (p/promise? (get-in service [:status :ready])))
            (when (identical? service-promise (second @*service))
              (reset! *service [graph service]))
            service))))))

(defn- notify-invalid-data
  [{:keys [tx-meta]} errors]
  ;; don't notify on production when undo/redo failed
  (when-not (and (or (:undo? tx-meta) (:redo? tx-meta))
                 (not worker-util/dev?))
    (shared-service/broadcast-to-clients! :notification
                                          [nil :error nil nil nil
                                           {:i18n-key :storage/invalid-data-writing}])
    (platform/post-message! (platform/current)
                            :capture-error
                            {:error (ex-info "Invalid data writing to db" tx-meta)
                             :payload {}
                             :extra {:errors (str errors)
                                     :tx-meta tx-meta}})))

(defn- build-proxy-object
  []
  (->>
   fns
   (map
    (fn [[k f]]
      [k
       (fn [& args]
         (let [[_graph service] @*service
               method-k (keyword (first args))]
           (cond
             (= k "remoteInvokeBinary")
             (apply f args)

             (= :thread-api/create-or-open-db method-k)
             ;; because shared-service operates at the graph level,
             ;; creating a new database or switching to another one requires re-initializing the service.
             (let [payload (last args)
                   payload' (cond
                              (string? payload) (ldb/read-transit-str payload)
                              (array? payload) (js->clj payload :keywordize-keys true)
                              :else payload)
                   [graph opts] payload'
                   service-promise (<init-service! graph opts)]
               (p/let [service service-promise
                       client-id (:client-id service)]
                 (when client-id
                   (platform/post-message! (platform/current)
                                           :record-worker-client-id
                                           {:client-id client-id}))
                 (get-in service [:status :ready])
                 ;; wait for service ready
                 (js-invoke (:proxy service) k args)))

             (or (= :thread-api/sync-app-state method-k)
                 (nil? service))
             ;; only proceed down this branch before shared-service is initialized
             (apply f args)

             :else
             ;; ensure service is ready
             (p/let [service service
                     _ready-value (get-in service [:status :ready])]
               (js-invoke (:proxy service) k args)))))]))
   (into {})
   bean/->js))

(defn init-core!
  [platform']
  (platform/set-platform! platform')
  (ldb/register-transact-invalid-callback-fn! notify-invalid-data)
  (build-proxy-object))

(comment
  (defn <remove-all-files!
    "!! Dangerous: use it only for development."
    []
    (p/let [all-files (<list-all-files)
            files (filter #(= (.-kind %) "file") all-files)
            dirs (filter #(= (.-kind %) "directory") all-files)
            _ (p/all (map (fn [file] (.remove file)) files))]
      (p/all (map (fn [dir] (.remove dir)) dirs)))))
