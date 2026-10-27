(ns frontend.worker.handler.transaction
  "Transaction, outliner operation, and worker-state synchronization operations."
  (:require
   [datascript.core :as d]
   [frontend.common.thread-api :as thread-api :refer [def-thread-api]]
   [frontend.worker-common.util :as worker-util]
   [frontend.worker.db-listener :as db-listener]
   [frontend.worker.handler.block :as block-handler]
   [frontend.worker.plain-value :as worker-plain]
   [frontend.worker.shared-service :as shared-service]
   [frontend.worker.state :as worker-state]
   [frontend.worker.undo-redo :as worker-undo-redo]
   [lambdaisland.glogi :as log]
   [logseq.common.util :as common-util]
   [logseq.db :as ldb]
   [logseq.db.common.order :as db-order]
   [logseq.db.frontend.literature :as literature]
   [logseq.outliner.op :as outliner-op]
   [logseq.outliner.recycle :as outliner-recycle]))

(def ^:private recycle-gc-kv :logseq.kv/recycle-last-gc-at)

(goog-define OUTLINER-PERF-LOGGING false)

(def ^:private e2e-perf-op-names
  #{[:insert-blocks]
    [:save-block :insert-blocks]
    [:delete-blocks]})

(defn maybe-run-recycle-gc!
  [conn]
  (let [now (common-util/time-ms)
        last-gc-at (:kv/value (d/entity @conn recycle-gc-kv))]
    ;; Another client may still have an offline restore or edit. Automatic
    ;; permanent deletion is local-only; remote graphs use explicit deletion.
    (when (and (not (:kv/value (d/entity @conn :logseq.kv/graph-remote?)))
               (or (not (number? last-gc-at))
                   (> (- now last-gc-at) outliner-recycle/gc-interval-ms)))
      (outliner-recycle/gc! conn {:now-ms now})
      (ldb/transact! conn [{:db/ident recycle-gc-kv
                            :kv/value now}]
                     {:persist-op? false
                      :skip-validate-db? true}))))

(def-thread-api :thread-api/set-context
  [context]
  (when context (worker-state/update-context! context))
  nil)

(defn- transact!
  [repo tx-data tx-meta context]
  (assert (some? repo))
  (worker-state/set-db-latest-tx-time! repo)
  (let [conn (worker-state/get-datascript-conn repo)]
    (assert (some? conn) {:repo repo})
    (try
      (let [tx-data' (if (contains? #{:insert-blocks} (:outliner-op tx-meta))
                       (map (fn [tx]
                              (if (and (map? tx) (nil? (:block/order tx)))
                                (assoc tx :block/order (db-order/gen-key nil))
                                tx)) tx-data)
                       tx-data)
            _ (when context (worker-state/set-context! context))
            tx-meta' (dissoc tx-meta :insert-blocks?)]
        (when-not (and (:create-today-journal? tx-meta)
                       (:today-journal-name tx-meta)
                       (seq tx-data')
                       (ldb/get-page @conn (:today-journal-name tx-meta)))
          (worker-util/profile "Worker db transact"
                               (ldb/transact! conn tx-data' tx-meta')))
        (maybe-run-recycle-gc! conn)
        nil)
      (catch :default error
        (log/error ::worker-transact-failed {:tx-meta tx-meta
                                             :tx-count (count tx-data)
                                             :error error})
        (throw error)))))

(def-thread-api :thread-api/transact
  [repo tx-data tx-meta context]
  (transact! repo tx-data tx-meta context))



(def-thread-api :thread-api/literature-add-pdf-filenames
  [repo pdf-filenames]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection :repo repo})))
        tx-data (literature/build-add-pdf-filenames-tx @conn pdf-filenames)]
    (when (seq tx-data)
      (transact! repo tx-data
                 {:outliner-op :transact :local-tx? true :persist-op? true
                  :gen-undo-ops? false
                  :data {:literature/pdf-filenames true}}
                 nil))
    nil))

(def-thread-api :thread-api/literature-ensure-source-binding
  [repo binding-uuid destination]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))
        plan (literature/build-source-connection-plan @conn binding-uuid destination)]
    (if (= :ready (:status plan))
      (do
        (transact! repo
                   (:tx-data plan)
                   (assoc (:tx-meta plan)
                          :local-tx? true
                          :data {:literature/ensure-source-binding true})
                   nil)
        (-> plan
            (dissoc :tx-data :tx-meta)
            (assoc :status (case (:action plan)
                             :restore :restored
                             :created))))
      (dissoc plan :tx-data :tx-meta))))

(def-thread-api :thread-api/literature-register-pdf
  [repo asset-uuid title identifiers & [metadata]]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))
        paper-uuid (literature/source-record-paper-uuid identifiers)]
    (if paper-uuid
      (let [repair-status (get @thread-api/*thread-apis
                               :thread-api/db-sync-repair-status)]
        (if (:operation (repair-status repo))
          {:status :deferred :reason :repair-active}
          (outliner-op/apply-ops!
           conn
           [[:literature-register-identified-pdf
             (cond-> [asset-uuid title identifiers] (seq metadata) (conj metadata))]]
           {:persist-op? true
            :data {:literature/register-pdf true}})))
      (let [plan (literature/build-register-pdf-plan
                  @conn asset-uuid title identifiers (random-uuid) metadata)]
        (if (= :ready (:status plan))
          (do
            ;; Identifier-free explicit creation preserves its original path.
            (transact! repo
                       (:tx-data plan)
                       {:outliner-op :transact
                        :local-tx? true
                        :persist-op? true
                        :data {:literature/register-pdf true}}
                       nil)
            (-> plan
                (dissoc :tx-data)
                (assoc :status (case (:action plan)
                                 :create :created
                                 :link :linked))))
          (dissoc plan :tx-data))))))

(def-thread-api :thread-api/literature-register-verified-pdf
  [repo asset-uuid evidence metadata]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))
        repair-status (get @thread-api/*thread-apis
                           :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (outliner-op/apply-ops!
       conn
       [[:literature-register-verified-pdf [asset-uuid evidence metadata]]]
       {:persist-op? true
        :gen-undo-ops? false
        :data {:literature/register-verified-pdf true}}))))

(def-thread-api :thread-api/literature-register-source-record
  [repo binding-uuid destination-page-uuid source-record]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))
        repair-status (get @thread-api/*thread-apis
                           :thread-api/db-sync-repair-status)]
    ;; Repair claims require a settled queue. Check its existing receipt in the
    ;; same worker turn as the write, so an import cannot enter after a claim.
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (outliner-op/apply-ops!
       conn
       [[:literature-register-source-record
         [binding-uuid destination-page-uuid source-record]]]
       {:persist-op? true
        :gen-undo-ops? false
        :data {:literature/register-source-record true}}))))

(def-thread-api :thread-api/literature-paper-sources
  [repo paper-uuid]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection :repo repo})))]
    (literature/paper-sources @conn paper-uuid)))

(def-thread-api :thread-api/literature-correct-source-record
  [repo paper-uuid source action target-paper-uuid]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection :repo repo})))
        repair-status (get @thread-api/*thread-apis :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (let [plan (literature/build-correct-source-record-plan
                  @conn paper-uuid source action target-paper-uuid)]
        (if (= :ready (:status plan))
          (do
            (outliner-op/apply-ops!
             conn (:ops plan)
             {:persist-op? true :gen-undo-ops? true
              :db-sync/forward-outliner-ops (:ops plan)
              :db-sync/inverse-outliner-ops (:inverse-ops plan)
              :data {:literature/correct-source-record true}})
            (-> plan (dissoc :ops :inverse-ops) (assoc :status :updated)))
          (dissoc plan :ops :inverse-ops))))))

(def-thread-api :thread-api/literature-apply-source-record-metadata
  [repo binding-uuid paper-uuid source-record accepted-changes]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))
        plan (literature/build-apply-source-record-metadata-plan
              @conn binding-uuid paper-uuid source-record accepted-changes)]
    (if (= :ready (:status plan))
      (do
        ;; Revalidation and all native writes stay in this worker request.
        (outliner-op/apply-ops!
         conn
         (:ops plan)
         {:data {:literature/apply-source-record-metadata true}})
        (-> plan
            (dissoc :ops)
            (assoc :status :updated
                   :updated-fields (mapv :field (:changes plan)))))
      (dissoc plan :ops))))


(def-thread-api :thread-api/literature-apply-doi-metadata
  [repo paper-uuid record accepted-changes asset-uuid]
  (let [conn (worker-state/get-datascript-conn repo)
        repair-status (get @thread-api/*thread-apis :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (let [plan (literature/build-apply-doi-metadata-plan
                  @conn paper-uuid record accepted-changes asset-uuid)]
        (if (= :ready (:status plan))
          (do
            (outliner-op/apply-ops! conn (:ops plan)
                                  {:persist-op? true :gen-undo-ops? true})
            (-> plan (dissoc :ops) (assoc :status :updated)))
          (dissoc plan :ops))))))

(def-thread-api :thread-api/literature-add-doi-paper
  [repo destination-page-uuid record]
  (let [conn (worker-state/get-datascript-conn repo)
        repair-status (get @thread-api/*thread-apis :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (let [plan (literature/build-add-doi-paper-plan @conn destination-page-uuid record)]
        (if (= :ready (:status plan))
          (do
            (outliner-op/apply-ops! conn (:ops plan)
                                  {:persist-op? true :gen-undo-ops? true})
            (-> plan (dissoc :ops) (assoc :status :created)))
          (dissoc plan :ops))))))

(defn- apply-manual-literature-plan!
  [repo build-plan dry-run? result-status]
  (let [conn (worker-state/get-datascript-conn repo)
        repair-status (get @thread-api/*thread-apis :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (let [plan (build-plan @conn)]
        (if (= :ready (:status plan))
          (do
            (when-not dry-run?
              (outliner-op/apply-ops! conn (:ops plan)
                                    {:persist-op? true :gen-undo-ops? true}))
            (assoc (dissoc plan :ops) :status (if dry-run? :planned result-status)))
          (dissoc plan :ops))))))

(def-thread-api :thread-api/literature-import-paper
  [repo destination-page-uuid record dry-run?]
  (apply-manual-literature-plan!
   repo #(literature/build-import-paper-plan % destination-page-uuid record) dry-run? :created))

(def-thread-api :thread-api/literature-update-paper
  [repo paper-uuid patch dry-run?]
  (apply-manual-literature-plan!
   repo #(literature/build-update-paper-plan % paper-uuid patch) dry-run? :updated))

(def-thread-api :thread-api/literature-keep-pdf
  [repo asset-uuid]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection :repo repo})))
        repair-status (get @thread-api/*thread-apis :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (let [plan (literature/build-keep-pdf-plan @conn asset-uuid)]
        (if (= :ready (:status plan))
          (do
            (transact! repo (:tx-data plan)
                       {:outliner-op :transact :local-tx? true :persist-op? true
                        :data {:literature/keep-pdf true}}
                       nil)
            (-> plan (dissoc :tx-data) (assoc :status :kept)))
          (dissoc plan :tx-data))))))

(def-thread-api :thread-api/literature-link-pdf
  [repo asset-uuid target-paper-uuid & [options]]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))
        repair-status (get @thread-api/*thread-apis :thread-api/db-sync-repair-status)]
    (if (:operation (repair-status repo))
      {:status :deferred :reason :repair-active}
      (if (:automatic? options)
        (outliner-op/apply-ops!
         conn [[:literature-auto-link-pdf [asset-uuid target-paper-uuid (:source-context options)]]]
         {:persist-op? true :gen-undo-ops? false
          :data {:literature/auto-link-pdf true}})
        (let [plan (literature/build-link-pdf-plan @conn asset-uuid target-paper-uuid)]
          (if (= :ready (:status plan))
            (do
              (transact! repo (:tx-data plan)
                         {:outliner-op :transact :local-tx? true :persist-op? true
                          :data {:literature/link-pdf true}}
                         nil)
              (-> plan (dissoc :tx-data) (assoc :status :linked)))
            (dissoc plan :tx-data)))))))

(def-thread-api :thread-api/literature-rename-pdf-titles
  [repo paper-uuid]
  (let [conn (worker-state/get-datascript-conn repo)
        plan (literature/build-rename-paper-pdfs-plan @conn paper-uuid)]
    (if (= :ready (:status plan))
      (do
        (when (seq (:tx-data plan))
          (transact! repo (:tx-data plan)
                     {:outliner-op :transact :local-tx? true :persist-op? true
                      :data {:literature/rename-pdf-titles true}} nil))
        (-> plan (dissoc :tx-data) (assoc :status :complete)))
      (dissoc plan :tx-data))))

(def-thread-api :thread-api/literature-auto-link-pdf
  [repo asset-uuid evidence]
  ;; Empty metadata preserves the link-only path, including identifier-not-found
  ;; for the renderer's optional metadata lookup, and rechecks Keep on replay.
  ((get @thread-api/*thread-apis :thread-api/literature-register-verified-pdf)
   repo asset-uuid evidence {}))

(def-thread-api :thread-api/literature-resolve-duplicate-pdf
  [repo keep-uuid duplicate-uuid & [comparison]]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection :repo repo})))]
    (outliner-op/apply-ops!
     conn [[:literature-merge-duplicate-pdf
            (cond-> [keep-uuid duplicate-uuid] comparison (conj comparison))]]
     {:persist-op? true :gen-undo-ops? true})))


(defn- perf-time-ms []
  (if (and (exists? js/performance)
           (.-now js/performance))
    (.now js/performance)
    (js/Date.now)))

(defn- log-outliner-op-perf!
  [data]
  (when (:perf-id data)
    (cond
      goog.DEBUG
      (log/info :db-worker/outliner-op-perf
                (assoc data :worker-apply-ms (:apply-ms data)))

      (and OUTLINER-PERF-LOGGING
           (contains? e2e-perf-op-names (:op-names data)))
      (log/info :db-worker/outliner-op-perf
                (select-keys (assoc data :worker-apply-ms (:apply-ms data))
                             [:op-names :worker-apply-ms])))))

(def-thread-api :thread-api/apply-outliner-ops
  [repo ops opts]
  (let [conn (or (worker-state/get-datascript-conn repo)
                 (throw (ex-info "Missing worker graph connection"
                                 {:type :db/missing-connection
                                  :repo repo})))]
    (try
      ;; Folded into this call so the client pays one worker roundtrip
      ;; per outliner op instead of two; must run before apply-ops! so undo
      ;; records pre-op editor state.
      (worker-undo-redo/set-pending-editor-info! repo (:pending-editor-info opts))
      (let [started-at (perf-time-ms)
            perf-id (:ui/perf-id opts)
            editor-row-uuids (:editor-row-uuids opts)
            operation-opts (dissoc opts
                                   :affected-block-uuids
                                   :editor-row-uuids
                                   :pending-editor-info
                                   :return-updated-blocks?)
            apply-started-at (perf-time-ms)
            operation-result (worker-util/profile
                              "apply outliner ops"
                              (outliner-op/apply-ops! conn ops operation-opts))
            applied-at (perf-time-ms)
            delta (db-listener/take-outliner-op-delta! perf-id)
            listener-perf (db-listener/take-outliner-op-perf! perf-id)
            listener-at (perf-time-ms)
            editor-rows (when (seq editor-row-uuids)
                          (select-keys
                           (:blocks (block-handler/canonical-blocks
                                     @conn editor-row-uuids))
                           editor-row-uuids))
            response (cond-> {:result (worker-plain/worker-plain-value
                                       @conn operation-result)}
                       delta (assoc :delta delta)
                       (seq editor-row-uuids)
                       (assoc :editor-row-uuids editor-row-uuids
                              :editor-rows editor-rows))
            plain-at (perf-time-ms)
            perf-data {:apply-ms (- applied-at apply-started-at)
                       :listener-ms (- listener-at applied-at)
                       :plain-ms (- plain-at listener-at)
                       :total-ms (- plain-at started-at)
                       :listener listener-perf}
            response-with-perf (cond-> response
                                 goog.DEBUG (assoc :perf perf-data))]
        (log-outliner-op-perf!
         (merge (dissoc perf-data :listener)
                {:perf-id perf-id
                 :op-names (mapv first ops)
                 :op-count (count ops)}))
        response-with-perf)
      (catch :default error
        (let [data (ex-data error)
              {:keys [type payload]} (when (map? data) data)]
          (case type
            :notification
            (do
              (log/error ::apply-outliner-ops-failed error)
              (shared-service/broadcast-to-clients! :notification [(:message payload) (:type payload) (:clear? payload) (:uid payload) (:timeout payload)
                                                                   (select-keys payload [:i18n-key :i18n-args])])
              (throw error))
            (throw error)))))))

(def-thread-api :thread-api/sync-app-state
  [new-state]
  (when (and (contains? new-state :git/current-repo)
             (nil? (:git/current-repo new-state)))
    (log/error :thread-api/sync-app-state {:reason :nil-current-repo}))
  (worker-state/set-new-state! new-state)
  nil)
