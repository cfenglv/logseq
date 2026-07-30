(ns frontend.worker.sync.apply-txs
  "Pending tx and remote tx application helpers for db sync."
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [datascript.core :as d]
   [frontend.worker.platform :as platform]
   [frontend.worker.shared-service :as shared-service]
   [frontend.worker.state :as worker-state]
   [frontend.worker-common.util :as worker-util]
   [frontend.worker.sync.assets :as sync-assets]
   [frontend.worker.sync.auth :as sync-auth]
   [frontend.worker.sync.client-op :as client-op]
   [frontend.worker.sync.const :as rtc-const]
   [frontend.worker.sync.crypt :as sync-crypt]
   [frontend.worker.sync.large-title :as sync-large-title]
   [frontend.worker.sync.presence :as sync-presence]
   [frontend.worker.sync.transport :as sync-transport]
   [frontend.worker.sync.util :as sync-util :refer [fail-fast]]
   [frontend.worker.undo-redo :as worker-undo-redo]
   [lambdaisland.glogi :as log]
   [logseq.common.util :as common-util]
   [logseq.common.version :as build-version]
   [logseq.db :as ldb]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db-sync.order :as sync-order]
   [logseq.db-sync.tx-sanitize :as tx-sanitize]
   [logseq.db.common.normalize :as db-normalize]
   [logseq.db.sqlite.util :as sqlite-util]
   [logseq.outliner.core :as outliner-core]
   [logseq.outliner.op :as outliner-op]
   [logseq.outliner.op.construct :as op-construct]
   [logseq.outliner.page :as outliner-page]
   [logseq.outliner.property :as outliner-property]
   [logseq.outliner.recycle :as outliner-recycle]
   [promesa.core :as p]
   [frontend.worker.sync.asset-db-listener :as asset-db-listener]))

(defonce *repo->latest-remote-tx (atom {}))
(defonce *repo->latest-remote-checksum (atom {}))
(defonce *repo->latest-remote-checksum-version (atom {}))
;; Debug-only gate to reproduce one-way sync:
;; still pull/rebase remote txs, but skip local tx batch uploads.
(defonce *repo->upload-stopped? (atom {}))

(def ^:private eager-remote-apply-snapshot-retries 3)
(def ^:private max-remote-apply-snapshot-retries 6)
(def ^:private remote-apply-snapshot-retry-base-delay-ms 50)
(def ^:private remote-apply-snapshot-retry-max-delay-ms 1000)
(def ^:private upload-response-timeout-ms (* 2 60 1000))
(def ^:private max-upload-request-datoms 5000)
(defonce ^:private *repo->large-upload-progress (atom {}))

(defn set-upload-stopped!
  [repo stopped?]
  (swap! *repo->upload-stopped? assoc repo (boolean stopped?))
  (boolean stopped?))

(defn upload-stopped?
  [repo]
  (true? (get @*repo->upload-stopped? repo)))

(defn- versioned-large-title-marker-txs
  [tx-entries]
  (->> tx-entries
       (mapcat :tx-data)
       (filterv
        (fn [item]
          (and (vector? item)
               (= :db/add (nth item 0 nil))
               (= sync-large-title/large-title-object-attr
                  (nth item 2 nil))
               (sync-large-title/large-title-object-v2?
                (nth item 3 nil)))))))

(declare enqueue-asset-task!
         apply-remote-txs!
         cap-upload-request-tx-entries
         commit-large-upload-progress!
         ref-attr?
         resolve-temp-id
         tx-temp-id->uuid
         reverse-local-txs!
         rebase-local-txs!
         repair-applied-txs!
         handle-local-tx!)

(defn- current-client [repo]
  (sync-presence/current-client worker-state/*db-sync-client repo))

(defn- client-ops-conn [repo]
  (sync-presence/client-ops-conn worker-state/get-client-ops-conn repo))

(defn- sync-counts [repo]
  (sync-presence/sync-counts
   {:get-datascript-conn worker-state/get-datascript-conn
    :get-client-ops-conn worker-state/get-client-ops-conn
    :get-pending-local-tx-count client-op/get-pending-local-tx-count
    :get-unpushed-asset-ops-count client-op/get-unpushed-asset-ops-count
    :get-missing-asset-upload-files sync-assets/get-missing-asset-upload-files
    :get-local-tx client-op/get-local-tx
    :get-local-checksum client-op/get-local-checksum
    :get-local-server-checksum client-op/get-local-server-checksum
    :get-graph-uuid client-op/get-graph-uuid
    :latest-remote-tx @*repo->latest-remote-tx
    :latest-remote-checksum @*repo->latest-remote-checksum
    :latest-remote-checksum-version @*repo->latest-remote-checksum-version}
   repo))

(defn- broadcast-rtc-state! [client]
  (when client
    (shared-service/broadcast-to-clients!
     :rtc-sync-state
     (sync-presence/rtc-state-payload sync-counts client))))

(def reverse-data-ignored-attrs
  #{:block/tx-id})

(def rtc-ignored-attrs
  (set/union
   reverse-data-ignored-attrs
   rtc-const/ignore-attrs-when-syncing
   rtc-const/ignore-entities-when-init-upload
   #{:block/pre-block?}))

(defn- remove-ignored-attrs [tx-data]
  (remove (fn [d] (contains? rtc-ignored-attrs (:a d))) tx-data))

(def ^:private synced-created-by-user-attrs
  #{:block/uuid
    :block/name
    :block/title
    :block/tags
    :block/created-at
    :block/updated-at
    :logseq.property.user/name
    :logseq.property.user/email})

(defn- current-user-uuid
  []
  (try
    (some-> (worker-state/get-id-token)
            worker-util/parse-jwt
            :sub
            uuid)
    (catch :default _
      nil)))

(defn- created-by-user-referenced?
  [db-after tx-data user-uuid]
  (boolean
   (some (fn [{:keys [a v added]}]
           (when (and added
                      (= :logseq.property/created-by-ref a))
             (= user-uuid
                (some-> (d/entity db-after v) :block/uuid))))
         tx-data)))

(defn- normalize-created-by-user-upsert
  "Return an idempotent upsert for the current graph-local user entity.

  A downloaded shared graph can contain a local user entity that was created by
  a non-persisted metadata transaction and therefore never reached the server.
  Re-sending the small identity record alongside transactions that reference it
  repairs those graphs and is a no-op once the server already has the entity."
  [db-after db-before tx-data]
  (when-let [user-uuid (current-user-uuid)]
    (when (created-by-user-referenced? db-after tx-data user-uuid)
      (when-let [user-id (:db/id (d/entity db-after [:block/uuid user-uuid]))]
        (->> (d/datoms db-after :eavt user-id)
             (filter (fn [{:keys [a]}]
                       (contains? synced-created-by-user-attrs a)))
             (keep (fn [datom]
                     (when-let [normalized
                                (db-normalize/normalize-datom db-after db-before datom)]
                       ;; Force a tempid for the user entity. A lookup ref would
                       ;; fail on a server that missed the original creation.
                       (assoc normalized 1 (str user-uuid))))))))))

(defn- normalize-tx-data [db-after db-before tx-data]
  (let [tx-data* (remove-ignored-attrs tx-data)
        created-by-user-upsert (normalize-created-by-user-upsert db-after db-before tx-data*)]
    (->> (concat created-by-user-upsert
                 (db-normalize/normalize-tx-data db-after db-before tx-data*))
         (remove (fn [[_op e]]
                   (contains? rtc-const/ignore-entities-when-init-upload e)))
         distinct)))

(declare replay-canonical-outliner-op!
         invalid-rebase-op!)

(defn reverse-tx-data [db-before db-after tx-data]
  (->> tx-data
       reverse
       (keep (fn [[e a v t added]]
               (let [reversed-datom (d/datom e a v t (not added))]
                 ;; trick: reverse the order of `db-before` and `db-after`
                 (db-normalize/normalize-datom db-before db-after reversed-datom))))
       (db-normalize/replace-attr-retract-with-retract-entity-v2 db-after)
       db-normalize/reorder-retract-entity))

(defn normalize-rebased-pending-tx
  [{:keys [db-before db-after tx-data]}]
  {:normalized-tx-data (normalize-tx-data db-after db-before tx-data)
   :reversed-datoms (reverse-tx-data db-before db-after tx-data)})

(defn- auth-headers []
  (sync-auth/auth-headers (worker-state/get-id-token)))

(defn- send! [ws message]
  (sync-transport/send! sync-transport/coerce-ws-client-message ws message))

(defn- ws-open? [ws]
  (sync-transport/ws-open? ws))

(defn- ws-ready-state
  [ws]
  (when ws
    (.-readyState ws)))

(defn- outliner-op->string
  [op]
  (cond
    (keyword? op) (name op)
    (some? op) (str op)
    :else nil))

(defn- report-upload-response-timeout!
  [client request]
  (let [repo (:repo client)
        ws (:ws client)
        online? (worker-state/online?)
        ws-open-state? (ws-open? ws)]
    (when (and online? ws-open-state?)
      (let [elapsed-ms (- (common-util/time-ms) (:sent-at request))
            outliner-ops (mapv outliner-op->string (:outliner-ops request))
            outliner-op-tag (when (seq outliner-ops)
                              (string/join "," outliner-ops))
            data (cond-> {:source "db-sync"
                          :operation "upload-tx-batch"
                          :repo repo
                          :graph-id (:graph-id client)
                          :timeout-ms upload-response-timeout-ms
                          :elapsed-ms elapsed-ms
                          :tx-count (count (:tx-ids request))
                          :t-before (:t-before request)
                          :latest-remote-tx (get @*repo->latest-remote-tx repo)
                          :current-local-tx (client-op/get-local-tx repo)
                          :online? online?
                          :ws-open? ws-open-state?
                          :ws-ready-state (ws-ready-state ws)}
                   outliner-op-tag
                   (assoc :outliner-op outliner-op-tag))]
        (log/error :db-sync/upload-response-timeout
                   (assoc data :tx-ids (:tx-ids request)))
        (try
          (platform/post-message!
           (platform/current)
           :capture-error
           {:error (ex-info "Sync upload request did not get response" data)
            :payload data
            :extra (cond-> {:tx-ids (mapv str (:tx-ids request))}
                     (seq outliner-ops)
                     (assoc :outliner-ops outliner-ops))})
          (catch :default report-error
            (log/error :db-sync/report-upload-response-timeout-failed
                       {:repo repo
                        :error report-error})))))))

(defn clear-upload-response-timeout!
  [client]
  (when-let [*upload-request (:upload-request client)]
    (let [request @*upload-request]
      (when-let [timer (:timer request)]
        (js/clearTimeout timer))
      (reset! *upload-request nil)
      (some-> request (dissoc :timer)))))

(defn- start-upload-response-timeout!
  [client request]
  (when-let [*upload-request (:upload-request client)]
    (when-not (:timer @*upload-request)
      (let [request' (assoc request :sent-at (common-util/time-ms))
            timer (js/setTimeout
                   (fn []
                     (when (= request' (dissoc @*upload-request :timer))
                       (reset! *upload-request nil)
                       (report-upload-response-timeout! client request')
                       (when-let [recover! (:upload-response-timeout-f client)]
                         (recover! request'))))
                   upload-response-timeout-ms)]
        (reset! *upload-request (assoc request' :timer timer))))))

(defn ack-upload-response!
  [repo client]
  (when-let [request (clear-upload-response-timeout! client)]
    (commit-large-upload-progress! repo (:large-upload-progress request))))

(defn upload-large-title! [repo graph-id title aes-key]
  (sync-large-title/upload-large-title!
   {:repo repo
    :graph-id graph-id
    :title title
    :aes-key aes-key
    :http-base (sync-auth/http-base-url @worker-state/*db-sync-config)
    :auth-headers (auth-headers)
    :fail-fast-f fail-fast
    :encrypt-text-value-f sync-crypt/<encrypt-text-value}))

(defn offload-large-titles [tx-data {:keys [upload-fn] :as opts}]
  (sync-large-title/offload-large-titles tx-data (assoc opts :upload-fn (or upload-fn upload-large-title!))))

(defn rehydrate-large-titles! [repo {:keys [download-fn] :as opts}]
  (sync-large-title/rehydrate-large-titles!
   repo
   (assoc opts
          :download-fn (or download-fn
                           (fn [repo* graph-id obj aes-key]
                             (sync-large-title/download-large-title!
                              {:repo repo*
                               :graph-id graph-id
                               :obj obj
                               :aes-key aes-key
                               :http-base (sync-auth/http-base-url @worker-state/*db-sync-config)
                               :auth-headers (auth-headers)
                               :fail-fast-f fail-fast
                               :decrypt-text-value-f sync-crypt/<decrypt-text-value
                               :strict-decrypt-text-value-f
                               sync-crypt/<decrypt-text-value-strict})))
          :get-conn-f worker-state/get-datascript-conn
          :graph-e2ee?-f sync-crypt/graph-e2ee?
          :ensure-graph-aes-key-f sync-crypt/<ensure-graph-aes-key
          :fail-fast-f fail-fast)))

(defn rehydrate-large-titles-from-db! [repo graph-id]
  (sync-large-title/rehydrate-large-titles-from-db!
   repo graph-id {:get-conn-f worker-state/get-datascript-conn
                  :rehydrate-large-titles!-f rehydrate-large-titles!}))

(defn request-asset-download! [repo asset-uuid]
  (sync-assets/request-asset-download!
   repo asset-uuid
   {:current-client-f current-client
    :enqueue-asset-task-f enqueue-asset-task!
    :broadcast-rtc-state!-f broadcast-rtc-state!}))

(defn- enqueue-asset-task! [client task]
  (sync-assets/enqueue-asset-task! client task))

(defn- derive-history-outliner-ops
  [db-before db-after tx-data tx-meta]
  (op-construct/derive-history-outliner-ops db-before db-after tx-data tx-meta))

(defn- rebase-history-ops
  [local-tx]
  {:forward-ops (seq (:forward-outliner-ops local-tx))
   :inverse-ops (seq (:inverse-outliner-ops local-tx))})

(defn- normalize-tx-data-for-rebase
  [tx-data]
  (some->> tx-data
           (mapv (fn [item]
                   (if (and (vector? item) (= 5 (count item)))
                     (let [[op e a v _t] item]
                       [op e a v])
                     item)))
           db-normalize/reorder-retract-entity))

(defn- inferred-outliner-ops?
  [tx-meta]
  (and (nil? (:outliner-ops tx-meta))
       (not (:undo? tx-meta))
       (not (:redo? tx-meta))
       (not= :batch-import-edn (:outliner-op tx-meta))))

(defn- tx-meta-outliner-op
  [tx-meta]
  (or (:outliner-op tx-meta)
      (when (:db-migrate? tx-meta)
        :db-migrate)))

(defn- apply-tx-meta
  [remote-tx]
  (let [outliner-op (:outliner-op remote-tx)]
    (cond-> {:transact-remote? true
             :persist-op? false
             :t (:t remote-tx)}
      outliner-op
      (assoc :outliner-op outliner-op)
      (= outliner-op :db-migrate)
      (assoc :db-migrate? true
             :skip-validate-db? true))))

(declare apply-history-action!)
(defn- persist-local-tx!
  [repo {:keys [db-before db-after tx-data tx-meta] :as tx-report} normalized-tx-data reversed-datoms]
  (when (client-ops-conn repo)
    (let [tx-id (or (:db-sync/tx-id tx-meta) (random-uuid))
          now (.now js/Date)
          outliner-op (tx-meta-outliner-op tx-meta)
          {:keys [forward-outliner-ops inverse-outliner-ops]}
          (derive-history-outliner-ops db-before db-after tx-data tx-meta)
          inferred-outliner-ops?' (inferred-outliner-ops? tx-meta)
          {:keys [should-inc-pending?]}
          (client-op/upsert-local-tx-entry!
           repo
           {:tx-id tx-id
            :created-at now
            :pending? true
            :failed? false
            :outliner-op outliner-op
            :undo-redo (cond
                         (:undo? tx-meta) :undo
                         (:redo? tx-meta) :redo
                         :else :none)
            :forward-outliner-ops forward-outliner-ops
            :inverse-outliner-ops inverse-outliner-ops
            :inferred-outliner-ops? inferred-outliner-ops?'
            :normalized-tx-data normalized-tx-data
            :reversed-tx-data reversed-datoms})]
      ;; (prn :debug :forward-outliner-ops)
      ;; (cljs.pprint/pprint forward-outliner-ops)
      ;; (prn :debug :inverse-outliner-ops)
      ;; (cljs.pprint/pprint inverse-outliner-ops)
      (worker-undo-redo/gen-undo-ops! repo tx-report tx-id
                                      {:apply-history-action! apply-history-action!})
      (when should-inc-pending?
        (client-op/adjust-pending-local-tx-count! repo 1)
        (when-let [client (current-client repo)]
          (broadcast-rtc-state! client)))
      tx-id)))

(defn- tx-item-block-uuid
  [db v]
  (cond
    (uuid? v)
    v

    (and (vector? v) (= :block/uuid (first v)) (uuid? (second v)))
    (second v)

    (number? v)
    (some-> (d/entity db v) :block/uuid)

    :else
    nil))

(defn- tx-item-entity
  [item]
  (if (vector? item)
    (second item)
    (:e item)))

(defn- tx-item-attr
  [item]
  (if (vector? item)
    (nth item 2 nil)
    (:a item)))

(defn- tx-item-add?
  [item]
  (if (vector? item)
    (= :db/add (first item))
    (true? (:added item))))

(defn- tx-item-retract?
  [item]
  (if (vector? item)
    (= :db/retract (first item))
    (false? (:added item))))

(defn- block-uuid-lookup-ref-value
  [v]
  (when (and (vector? v)
             (= :block/uuid (first v))
             (uuid? (second v)))
    (second v)))

(defn- tx-item-ref-block-uuids
  [item]
  (when (vector? item)
    (keep block-uuid-lookup-ref-value
          [(second item) (nth item 3 nil)])))

(defn- tx-data-has-block-uuid-ref?
  [tx-data]
  (boolean
   (some (fn [item]
           (seq (tx-item-ref-block-uuids item)))
         tx-data)))

(defn- tx-item-retract-entity-block-uuid
  [item]
  (when (and (vector? item)
             (contains? #{:db/retractEntity :db.fn/retractEntity} (first item)))
    (block-uuid-lookup-ref-value (second item))))

(defn- remote-txs-retract-entity-block-uuid-suffixes
  [remote-txs]
  (-> (reduce (fn [{:keys [deleted-block-uuids suffixes]} remote-tx]
                (let [deleted-block-uuids' (into deleted-block-uuids
                                                  (keep tx-item-retract-entity-block-uuid)
                                                  (:tx-data remote-tx))]
                  {:deleted-block-uuids deleted-block-uuids'
                   :suffixes (conj suffixes deleted-block-uuids')}))
              {:deleted-block-uuids #{}
               :suffixes '()}
              (reverse remote-txs))
      :suffixes
      vec))

(defn- tx-item-missing-deleted-block-ref?
  [db deleted-block-uuids item]
  (some (fn [block-uuid]
          (and (contains? deleted-block-uuids block-uuid)
               (nil? (d/entity db [:block/uuid block-uuid]))))
        (tx-item-ref-block-uuids item)))

(defn- tx-item-missing-block-ref?
  [db created-block-uuids item]
  (some (fn [block-uuid]
          (and (not (contains? created-block-uuids block-uuid))
               (nil? (d/entity db [:block/uuid block-uuid]))))
        (tx-item-ref-block-uuids item)))

(defn- tx-item-entity-block-uuid
  ([db item]
   (tx-item-entity-block-uuid db {} item))
  ([db temp-id->uuid item]
   (when (vector? item)
     (or (get temp-id->uuid (second item))
         (tx-item-block-uuid db (second item))))))

(defn- drop-stale-deleted-block-ref-ops
  [db deleted-block-uuids tx-data]
  (let [temp-id->uuid (tx-temp-id->uuid tx-data)
        stale-entity-block-uuids (->> tx-data
                                      (keep (fn [item]
                                              (when (tx-item-missing-deleted-block-ref?
                                                     db deleted-block-uuids item)
                                                (tx-item-entity-block-uuid db temp-id->uuid item))))
                                      set)]
    (remove (fn [item]
              (contains? stale-entity-block-uuids
                         (tx-item-entity-block-uuid db temp-id->uuid item)))
            tx-data)))

(defn- drop-missing-block-ref-ops
  [db tx-data]
  (let [temp-id->uuid (tx-temp-id->uuid tx-data)
        created-block-uuids (set (vals temp-id->uuid))
        stale-entity-block-uuids (->> tx-data
                                      (keep (fn [item]
                                              (when (tx-item-missing-block-ref?
                                                     db created-block-uuids item)
                                                (tx-item-entity-block-uuid db temp-id->uuid item))))
                                      set)]
    (remove (fn [item]
              (or (tx-item-missing-block-ref? db created-block-uuids item)
                  (contains? stale-entity-block-uuids
                             (tx-item-entity-block-uuid db temp-id->uuid item))))
            tx-data)))

(defn- drop-local-reversal-stale-target-ops
  [db-before-local-reversal db tx-data]
  (let [recreated-eids (->> tx-data
                            (filter #(and (tx-item-add? %)
                                          (= :block/uuid (tx-item-attr %))))
                            (map tx-item-entity)
                            set)]
    (remove (fn [item]
              (let [eid (tx-item-entity item)]
                (and (number? eid)
                     (not (contains? recreated-eids eid))
                     (:block/uuid (d/entity db-before-local-reversal eid))
                     (nil? (:block/uuid (d/entity db eid))))))
            tx-data)))

(defn- drop-stale-adds-after-remote-entity-delete
  [tx-data]
  (let [deleted-eids (->> tx-data
                          (filter #(and (tx-item-retract? %)
                                        (= :block/uuid (tx-item-attr %))))
                          (map tx-item-entity)
                          set)
        recreated-eids (->> tx-data
                            (filter #(and (tx-item-add? %)
                                          (= :block/uuid (tx-item-attr %))))
                            (map tx-item-entity)
                            set)
        stale-eids (set/difference deleted-eids recreated-eids)]
    (remove (fn [item]
              (and (tx-item-add? item)
                   (contains? stale-eids (tx-item-entity item))))
            tx-data)))

(defn- remote-txs-db-migrate?
  [remote-txs]
  (some #(= :db-migrate (:outliner-op %)) remote-txs))

(defn- upload-tempid?
  [value]
  (or (and (integer? value) (neg? value))
      (string? value)))

(defn- upload-tx-item-tempids
  [db item]
  (cond
    (and (map? item) (upload-tempid? (:db/id item)))
    #{(:db/id item)}

    (and (vector? item)
         (contains? #{:db/add :db/retract :db/cas :db.fn/cas} (first item))
         (<= 4 (count item)))
    (let [[_op entity attr value] item]
      (cond-> #{}
        (upload-tempid? entity)
        (conj entity)
        (and db
             (ref-attr? db attr)
             (upload-tempid? value))
        (conj value)))

    (and (vector? item)
         (contains? #{:db/retractEntity :db.fn/retractEntity} (first item))
         (= 2 (count item))
         (upload-tempid? (second item)))
    #{(second item)}

    :else
    #{}))

(defn- merge-upload-tx-ranges
  [ranges]
  (loop [remaining (sort-by first ranges)
         merged []]
    (if-let [[start end] (first remaining)]
      (if-let [[prev-start prev-end] (peek merged)]
        (if (<= start prev-end)
          (recur (next remaining)
                 (conj (pop merged) [prev-start (max prev-end end)]))
          (recur (next remaining)
                 (conj merged [start end])))
        (recur (next remaining) [[start end]]))
      merged)))

(defn- upload-tempid-range-by-start
  [db tx-data]
  (let [ranges-by-tempid
        (reduce-kv
         (fn [acc idx item]
           (reduce (fn [acc* tempid]
                     (update acc* tempid
                             (fn [[start end]]
                               [(if (some? start) (min start idx) idx)
                                (if (some? end) (max end idx) idx)])))
                   acc
                   (upload-tx-item-tempids db item)))
         {}
         tx-data)]
    (into {} (map (fn [[start end]] [start end])
                  (merge-upload-tx-ranges (vals ranges-by-tempid))))))

(defn- next-upload-tx-group
  [tx-data range-by-start idx]
  (if-let [end (get range-by-start idx)]
    [(inc end) (subvec tx-data idx (inc end))]
    [(inc idx) [(nth tx-data idx)]]))

(defn- next-large-upload-request-chunk
  [db tx-data start]
  (let [range-by-start (upload-tempid-range-by-start db tx-data)
        total (count tx-data)]
    (loop [idx start
           chunk []]
      (if (< idx total)
        (let [[next-idx group] (next-upload-tx-group tx-data range-by-start idx)
              next-count (+ (count chunk) (count group))]
          (if (and (seq chunk)
                   (> next-count max-upload-request-datoms))
            {:chunk chunk
             :next-index idx}
            (recur next-idx (into chunk group))))
        {:chunk chunk
         :next-index total}))))

(defn prepare-upload-tx-entries
  ([conn pending]
   (prepare-upload-tx-entries nil conn pending))
  ([repo conn pending]
   (let [entries (mapv (fn [{:keys [tx-id tx outliner-op]}]
                         {:tx-id tx-id
                          :outliner-op outliner-op
                          :tx-data (vec tx)})
                       pending)
         empty-tx-ids (->> entries
                           (filter (comp empty? :tx-data))
                           (mapv :tx-id))
         drop-txs (->> entries
                       (filter (comp empty? :tx-data))
                       (mapv (fn [{:keys [tx-id outliner-op]}]
                               {:tx-id tx-id
                                :outliner-op outliner-op
                                :reason :empty-tx-data})))
         tx-entries (filterv (comp seq :tx-data) entries)]
     {:tx-entries (if repo
                    (cap-upload-request-tx-entries repo (some-> conn deref) tx-entries)
                    tx-entries)
      :drop-tx-ids empty-tx-ids
      :drop-txs drop-txs})))

(defn- large-upload-progress-key
  [repo tx-id]
  [repo tx-id])

(defn- clear-large-upload-progress!
  [repo tx-ids]
  (let [progress-keys (->> tx-ids
                           (filter some?)
                           (map #(large-upload-progress-key repo %))
                           seq)]
    (when progress-keys
      (swap! *repo->large-upload-progress
             (fn [progress]
               (apply dissoc progress progress-keys))))))

(defn- large-upload-request-entry
  [repo db {:keys [tx-id tx-data] :as entry}]
  (let [total (count tx-data)
        progress-key (large-upload-progress-key repo tx-id)
        progress-start (get @*repo->large-upload-progress progress-key 0)
        start (if (< progress-start total) progress-start 0)
        {:keys [chunk next-index]} (next-large-upload-request-chunk db tx-data start)
        final? (>= next-index total)]
    (log/info :db-sync/large-upload-request-chunk
              {:repo repo
               :tx-id tx-id
               :start start
               :end next-index
               :total total
               :final? final?})
    (cond-> (assoc entry
                   :tx-data chunk
                   :large-upload-original-tx-id tx-id
                   :large-upload-next-index next-index
                   :large-upload-final? final?)
      (not final?) (dissoc :tx-id))))

(defn- cap-upload-request-tx-entries
  [repo db tx-entries]
  (loop [remaining tx-entries
         result []
         datom-count 0]
    (if-let [{:keys [tx-data] :as entry} (first remaining)]
      (let [entry-datom-count (count tx-data)
            next-datom-count (+ datom-count entry-datom-count)]
        (cond
          (and (empty? result) (> entry-datom-count max-upload-request-datoms))
          [(large-upload-request-entry repo db entry)]

          (> next-datom-count max-upload-request-datoms)
          result

          :else
          (recur (next remaining)
                 (conj result entry)
                 next-datom-count)))
      result)))

(defn- cap-upload-request-bytes
  [message-base tx-entries payload]
  (let [tx-entries (vec tx-entries)
        payload (vec payload)
        entry-count (count tx-entries)
        message-for-count (fn [n]
                            (assoc message-base :txs (subvec payload 0 n)))
        encoded-size (fn [n]
                       (sync-transport/encoded-message-byte-length
                        (message-for-count n)))]
    (if (zero? entry-count)
      {:tx-entries []
       :payload []
       :message (message-for-count 0)}
      (let [first-size (encoded-size 1)]
        (when (> first-size sync-transport/max-ws-message-bytes)
          (let [entry (first tx-entries)]
            (throw (ex-info "transaction exceeds safe websocket byte limit"
                            {:type :db-sync/tx-batch-too-large
                             :tx-id (some-> (:tx-id entry) str)
                             :encoded-bytes first-size
                             :max-bytes sync-transport/max-ws-message-bytes
                             :datom-count (count (:tx-data entry))}))))
        ;; JSON message size grows monotonically with appended transaction
        ;; entries. Binary search avoids repeatedly serializing every growing
        ;; prefix of a large offline backlog.
        (loop [low 1
               high entry-count
               selected-count 1]
          (if (> low high)
            (let [selected-entries (subvec tx-entries 0 selected-count)
                  selected-payload (subvec payload 0 selected-count)]
              {:tx-entries selected-entries
               :payload selected-payload
               :message (assoc message-base :txs selected-payload)})
            (let [middle (quot (+ low high) 2)
                  size (encoded-size middle)]
              (if (<= size sync-transport/max-ws-message-bytes)
                (recur (inc middle) high middle)
                (recur low (dec middle) selected-count)))))))))

(defn- commit-large-upload-progress!
  [repo tx-entries]
  (doseq [{:keys [large-upload-original-tx-id
                  large-upload-next-index
                  large-upload-final?]} tx-entries]
    (when large-upload-original-tx-id
      (let [progress-key (large-upload-progress-key repo large-upload-original-tx-id)]
        (if large-upload-final?
          (swap! *repo->large-upload-progress dissoc progress-key)
          (swap! *repo->large-upload-progress assoc progress-key large-upload-next-index))))))

(defn- large-upload-progress
  [tx-entries]
  (->> tx-entries
       (keep (fn [{:keys [large-upload-original-tx-id
                          large-upload-next-index
                          large-upload-final?]}]
               (when large-upload-original-tx-id
                 {:large-upload-original-tx-id large-upload-original-tx-id
                  :large-upload-next-index large-upload-next-index
                  :large-upload-final? large-upload-final?})))
       vec))

(defn pending-txs
  [repo & {:keys [limit]}]
  (client-op/get-pending-local-txs repo :limit limit))

(defn- pending-tx-by-id
  [repo tx-id]
  (client-op/get-local-tx-entry repo tx-id))

(defn mark-pending-txs-false!
  [repo tx-ids]
  (when (seq tx-ids)
    (clear-large-upload-progress! repo tx-ids)
    (when-let [pending-to-remove (client-op/mark-pending-txs-false! repo tx-ids)]
      (when (pos? pending-to-remove)
        (client-op/adjust-pending-local-tx-count! repo (- pending-to-remove)))
      (when-let [client (current-client repo)]
        (broadcast-rtc-state! client)))))

(defn mark-failed-txs!
  [repo tx-ids]
  (when (seq tx-ids)
    (clear-large-upload-progress! repo tx-ids)
    (when-let [pending-to-remove (client-op/mark-failed-txs! repo tx-ids)]
      (when (pos? pending-to-remove)
        (client-op/adjust-pending-local-tx-count! repo (- pending-to-remove)))
      (when-let [client (current-client repo)]
        (broadcast-rtc-state! client)))))

(defn- local-tx-has-replay-data?
  [local-tx]
  (or (seq (:tx local-tx))
      (seq (:reversed-tx local-tx))
      (seq (:forward-outliner-ops local-tx))
      (seq (:inverse-outliner-ops local-tx))))

(defn rollback-and-mark-failed-txs!
  [repo tx-ids]
  (let [rejected-tx-ids (->> tx-ids (filter uuid?) set)]
    (when (seq rejected-tx-ids)
      (when-let [conn (worker-state/get-datascript-conn repo)]
        (let [pending (pending-txs repo)
              rejected-and-after (->> pending
                                      (drop-while #(not (contains? rejected-tx-ids (:tx-id %))))
                                      vec)]
          (when (seq rejected-and-after)
            (let [rebase-db-before @conn
                  rebase-txs (->> rejected-and-after
                                  (remove #(contains? rejected-tx-ids (:tx-id %)))
                                  vec)
                  *rebase-tx-reports (atom [])
                  *rebase-results (atom [])]
              (try
                (let [tx-report (ldb/batch-transact-with-temp-conn!
                                 conn
                                 {:rtc-tx? true
                                  :tx-reject-rollback? true}
                                 (fn [conn]
                                   (reverse-local-txs! conn rejected-and-after)
                                   (reset! *rebase-results
                                           (rebase-local-txs! repo conn rebase-txs rebase-db-before)))
                                 {:listen-db
                                  (fn [{:keys [tx-meta tx-data] :as tx-report}]
                                    (when (and (= :rebase (:outliner-op tx-meta))
                                               (:db-sync/rebased-local? tx-meta)
                                               (seq tx-data))
                                      (swap! *rebase-tx-reports conj tx-report)))})]
                  (doseq [tx-report @*rebase-tx-reports]
                    (handle-local-tx! repo tx-report))
                  (repair-applied-txs! conn tx-report)
                  (let [replay-tx-ids (->> rebase-txs
                                           (filter local-tx-has-replay-data?)
                                           (map :tx-id)
                                           set)
                        stale-tx-ids (->> @*rebase-results
                                          (filter (fn [{:keys [status]}]
                                                    (contains? #{:failed :no-op} status)))
                                          (keep :tx-id)
                                          (filter replay-tx-ids)
                                          distinct
                                          vec)]
                    (mark-pending-txs-false! repo stale-tx-ids)))
                (finally
                  (reset! *rebase-tx-reports nil)
                  (reset! *rebase-results nil)
                  (worker-undo-redo/clear-history! repo)))))))
      (mark-failed-txs! repo tx-ids))))

(defn clear-pending-txs!
  [repo]
  (mark-pending-txs-false! repo (mapv :tx-id (pending-txs repo))))

(declare expected-stale-rebase-error?
         history-action-error-reason)

(defn- expected-history-action-error-reason?
  [reason]
  (contains? #{:invalid-history-action-ops
               :invalid-history-action-tx}
             reason))

(defn- inline-history-action
  [tx-meta]
  (let [forward-outliner-ops (or (:db-sync/forward-outliner-ops tx-meta)
                                 (:forward-outliner-ops tx-meta))
        inverse-outliner-ops (or (:db-sync/inverse-outliner-ops tx-meta)
                                 (:inverse-outliner-ops tx-meta))]
    (when (and (seq forward-outliner-ops) (seq inverse-outliner-ops))
      {:outliner-op (:outliner-op tx-meta)
       :forward-outliner-ops forward-outliner-ops
       :inverse-outliner-ops inverse-outliner-ops})))

(defn ^:large-vars/cleanup-todo apply-history-action!
  [repo tx-id undo? tx-meta]
  (if-let [conn (worker-state/get-datascript-conn repo)]
    (if-let [action (or (pending-tx-by-id repo tx-id)
                        (inline-history-action tx-meta))]
      (if (= :fix (:outliner-op action))
        {:applied? false
         :reason :unsupported-history-action
         :action action}
        (let [{:keys [tx reversed-tx forward-outliner-ops inverse-outliner-ops]} action
            ops (->> (if undo? inverse-outliner-ops forward-outliner-ops)
                     (filter (fn [op-entry]
                               (and (sequential? op-entry)
                                    (contains? op-construct/semantic-outliner-ops
                                               (first op-entry)))))
                     seq)
            tx-data (-> (if undo? reversed-tx tx)
                        normalize-tx-data-for-rebase)
            ops' (if (seq ops)
                   ops
                   [[:transact [tx-data nil]]])
            history-tx-id (let [provided-history-tx-id (:db-sync/tx-id tx-meta)]
                            (if (and (uuid? provided-history-tx-id)
                                     (not= provided-history-tx-id tx-id))
                              provided-history-tx-id
                              (random-uuid)))
            tx-meta' (cond-> {:outliner-op (:outliner-op action)
                              :local-tx? true
                              :gen-undo-ops? false
                              :persist-op? true
                              :undo? undo?
                              :redo? (:redo? tx-meta)
                              :db-sync/tx-id history-tx-id
                              :db-sync/source-tx-id (or (:db-sync/source-tx-id tx-meta)
                                                        tx-id)
                              :db-sync/forward-outliner-ops (if undo?
                                                              (:inverse-outliner-ops action)
                                                              (:forward-outliner-ops action))
                              :db-sync/inverse-outliner-ops (if undo?
                                                              (:forward-outliner-ops action)
                                                              (:inverse-outliner-ops action))})]
          ;; (prn :debug :undo? undo? :ops)
          ;; (cljs.pprint/pprint ops')
          ;; (cljs.pprint/pprint (select-keys action [:tx-id :outliner-op :forward-outliner-ops :inverse-outliner-ops]))
          ;; (prn :debug :tx-meta)
          ;; (cljs.pprint/pprint tx-meta)
        (if (seq ops')
          (try
            (when (seq ops)
              (op-construct/assert-no-numeric-entity-ids! @conn ops :history-action-ops))
            (ldb/batch-transact-with-temp-conn!
             conn
             tx-meta'
             (fn [conn]
               (doseq [op ops']
                 (replay-canonical-outliner-op! conn op nil))))
            {:applied? true
             :history-tx-id history-tx-id}
            (catch :default e
              (let [reason (history-action-error-reason e)]
                (when-not (expected-history-action-error-reason? reason)
                  (log/error ::undo-redo-failed e))
                {:applied? false
                 :reason reason
                 :action action})))
          {:applied? false :reason :unsupported-history-action
           :action action})))
      {:applied? false
       :reason :missing-history-action
       :tx-id tx-id})
    (fail-fast :db-sync/missing-db {:repo repo
                                    :op :apply-history-action})))

(defn flush-pending!
  [repo client]
  (let [inflight @(:inflight client)
        local-tx (client-op/get-local-tx repo)
        remote-tx (get @*repo->latest-remote-tx repo)
        conn (worker-state/get-datascript-conn repo)]
    (when (and conn (= local-tx remote-tx) (empty? inflight)) ; rebase
      (when-let [ws (:ws client)]
        (when (and (ws-open? ws) (worker-state/online?) (not (upload-stopped? repo)))
          (let [batch (pending-txs repo {:limit 50})]
            (when (seq batch)
              (let [{:keys [tx-entries drop-tx-ids drop-txs]} (prepare-upload-tx-entries repo conn batch)]
                (when (seq drop-tx-ids)
                  (log/info :db-sync/drop-tx-ids {:tx-ids drop-tx-ids
                                                  :drops drop-txs})
                  (mark-pending-txs-false! repo drop-tx-ids))
                (-> (p/let [aes-key (when (and (seq tx-entries) (sync-crypt/graph-e2ee? repo))
                                      (sync-crypt/<ensure-graph-aes-key repo (:graph-id client)))
                            _ (when (and (seq tx-entries) (sync-crypt/graph-e2ee? repo) (nil? aes-key))
                                (fail-fast :db-sync/missing-field {:repo repo :field :aes-key}))
                            tx-entries* (p/all
                                         (mapv (fn [{:keys [tx-data] :as tx-entry}]
                                                 (p/let [tx-data* (offload-large-titles
                                                                   tx-data
                                                                   {:repo repo
                                                                    :graph-id (:graph-id client)
                                                                    :aes-key aes-key})
                                                         tx-data** (if aes-key
                                                                     (sync-crypt/<encrypt-tx-data aes-key tx-data*)
                                                                     tx-data*)]
                                                   (assoc tx-entry :tx-data tx-data**)))
                                               tx-entries))
                            payload (mapv (fn [{:keys [tx-id tx-data outliner-op]}]
                                            (cond-> {:tx (sqlite-util/write-transit-str tx-data)}
                                              tx-id
                                              (assoc :tx-id (str tx-id))
                                              outliner-op
                                              (assoc :outliner-op outliner-op)))
                                          tx-entries*)
                            message-base {:type "tx/batch"
                                          :client-revision (build-version/revision)
                                          :t-before local-tx}
                            capped (cap-upload-request-bytes message-base tx-entries* payload)
                            tx-entries-to-send (:tx-entries capped)
                            marker-txs
                            (versioned-large-title-marker-txs
                             tx-entries-to-send)
                            tx-ids (into [] (keep :tx-id) tx-entries-to-send)]
                      (when (seq tx-entries-to-send)
                        (when (seq marker-txs)
                          ;; Persist only markers for the prefix that will
                          ;; actually be sent. Persisting markers from the
                          ;; capped tail could advance the local checksum
                          ;; before the corresponding tx reaches the server.
                          (ldb/transact!
                           conn
                           marker-txs
                           {:rtc-tx? true
                            :persist-op? false
                            :op :large-title-marker})
                          ;; Marker persistence changes the unversioned
                          ;; checksum from the logical large title to the old
                          ;; server's empty transport placeholder. Cache both
                          ;; representations before accepting an ack from
                          ;; either an old or a versioned server.
                          (client-op/update-local-checksum
                           repo
                           (sync-checksum/recompute-checksum @conn))
                          (client-op/update-local-server-checksum
                           repo
                           (sync-checksum/recompute-server-checksum @conn)))
                        (reset! (:inflight client) tx-ids)
                        (p/do!
                         (send! ws (:message capped))
                         (start-upload-response-timeout!
                          client
                          {:tx-ids tx-ids
                           :outliner-ops (->> tx-entries-to-send
                                              (keep :outliner-op)
                                              distinct
                                              vec)
                           :large-upload-progress (large-upload-progress tx-entries-to-send)
                           :t-before local-tx}))))
                    (p/catch (fn [error]
                               (sync-util/set-last-sync-error! client error)
                               (when (seq @(:inflight client))
                                 (when-let [recover! (:upload-send-failed-f client)]
                                   (recover! error)))
                               (log/error :db-sync/flush-pending-failed
                                          {:repo repo
                                           :diagnostic
                                           (dissoc
                                            (sync-util/error->diagnostic error)
                                            :at)}))))))))))))

(defn enqueue-flush-pending!
  [repo client]
  (if-let [send-queue (:send-queue client)]
    (swap! send-queue
           (fn [prev]
             (-> (or prev (p/resolved nil))
                 (p/catch (fn [_] nil))
                 (p/then (fn [_]
                           (flush-pending! repo client)))
                 (p/catch (fn [error]
                            (log/error :db-sync/flush-pending-queue-failed
                                       {:repo repo
                                        :diagnostic
                                        (dissoc
                                         (sync-util/error->diagnostic error)
                                         :at)}))))))
    (flush-pending! repo client)))

(defn- block-ref?
  [v]
  (or (number? v)
      (and (vector? v)
           (= :block/uuid (first v))
           (uuid? (second v)))))

(defn- block-entity
  [db ref]
  (when (block-ref? ref)
    (d/entity db ref)))

(defn- block-entity-ref
  [entity]
  (if-let [block-uuid (:block/uuid entity)]
    [:block/uuid block-uuid]
    (:db/id entity)))

(defn- block-descendants
  [entity]
  (letfn [(collect [block]
            (mapcat (fn [child]
                      (cons child (collect child)))
                    (sort-by :block/order (:block/_parent block))))]
    (collect entity)))

(defn- retract-entity-op?
  [item]
  (and (vector? item)
       (contains? #{:db/retractEntity :db.fn/retractEntity} (first item))))

(defn- expand-block-retracts-to-descendants
  [db tx-data]
  (let [explicit-retracts (->> tx-data
                               (keep (fn [item]
                                       (when (and (retract-entity-op? item)
                                                  (block-ref? (second item)))
                                         (some-> (block-entity db (second item)) :db/id))))
                               set)]
    (mapcat
     (fn [item]
       (if (and (retract-entity-op? item)
                (block-ref? (second item))
                (block-entity db (second item)))
         (let [root (block-entity db (second item))]
           (concat (->> (block-descendants root)
                        (remove #(contains? explicit-retracts (:db/id %)))
                        (map (fn [entity]
                               [:db/retractEntity (block-entity-ref entity)])))
                   [item]))
         [item]))
     tx-data)))

(defn- reverse-history-action!
  [conn local-tx]
  (if-let [tx-data (seq (:reversed-tx local-tx))]
              (let [db @conn
                    tx-data' (cond->> (some->> tx-data
                                              (map (partial resolve-temp-id db))
                                              normalize-tx-data-for-rebase)
                             (= :insert-blocks (:outliner-op local-tx))
                             (expand-block-retracts-to-descendants db))]
      (ldb/transact! conn
                     tx-data'
                     {:outliner-op (:outliner-op local-tx)
                      :reverse? true
                      :skip-validate-db? true}))
    ;; Built-in sync repair :fix rows are idempotent and can be queued without
    ;; reverse data; normal user edits must still carry reverse tx-data.
    (when-not (= :fix (:outliner-op local-tx))
      (invalid-rebase-op! :reverse-history-action
                          {:reason :missing-reversed-tx-data
                           :tx-id (:tx-id local-tx)
                           :outliner-op (:outliner-op local-tx)}))))

(defn- replace-uuid-str-with-eid
  [db v]
  (if (and (string? v) (common-util/uuid-string? v))
    (if-let [entity (d/entity db [:block/uuid (uuid v)])]
      (:db/id entity)
      v)
    v))

(defn- ref-attr?
  [db attr]
  (= :db.type/ref
     (or (get-in (d/schema db) [attr :db/valueType])
         (:db/valueType (d/entity db attr)))))

(defn- resolve-temp-id
  [db datom-v]
  (if (and (= (count datom-v) 5)
           (= (first datom-v) :db/add))
    (let [[op e a v t] datom-v
          e' (replace-uuid-str-with-eid db e)
          v' (if (ref-attr? db a)
               (replace-uuid-str-with-eid db v)
               v)]
      [op e' a v' t])
    datom-v))

(def sync-conflict-attrs
  #{:block/title})

(defn- tx-item-components
  [item]
  (when (and (vector? item) (>= (count item) 4))
    (let [[op e a v] item]
      (when (and (contains? #{:db/add :db/retract} op)
                 (contains? sync-conflict-attrs a))
        {:op op
         :e e
         :a a
         :v v}))))

(defn- tx-entity-uuid
  [db temp-id->uuid e]
  (cond
    (uuid? e) e

    (and (string? e) (common-util/uuid-string? e)) (uuid e)

    (and (vector? e)
         (= :block/uuid (first e))
         (uuid? (second e))) (second e)

    (and (number? e) (contains? temp-id->uuid e)) (get temp-id->uuid e)

    :else (some-> (d/entity db e) :block/uuid)))

(defn- tx-item-created-block-uuid-entry
  [item]
  (let [vector-add? (and (vector? item)
                         (= :db/add (first item)))
        datom-add? (and (not (vector? item))
                        (:added item))]
    (when (or vector-add? datom-add?)
      (let [e (if vector-add? (second item) (:e item))
            a (if vector-add? (nth item 2 nil) (:a item))
            v (if vector-add? (nth item 3 nil) (:v item))]
        (when (and (= :block/uuid a)
                   (or (number? e)
                       (string? e))
                   (uuid? v))
          [e v])))))

(defn- tx-temp-id->uuid
  [tx-data]
  (->> tx-data
       (keep tx-item-created-block-uuid-entry)
       (into {})))

(defn- drop-stale-rebase-target-ops
  "Drop updates to blocks that existed before rebase, were removed by remote
  txs, and are not recreated by this tx.

  Older pending :transact ops can contain numeric entity ids or bare UUID
  strings, while normalized rows use block UUID lookup refs. A missing lookup
  ref rejects the whole mixed tx; a stale numeric id or UUID string can instead
  create a partial entity without :block/uuid. Keep unrelated targets in the
  same pending tx so their local changes still rebase."
  [current-db rebase-db-before tx-data]
  (let [temp-id->uuid (tx-temp-id->uuid tx-data)
        created-block-uuids (into (set (vals temp-id->uuid))
                                  (comp (filter map?)
                                        (keep :block/uuid)
                                        (filter uuid?))
                                  tx-data)
        target-block-uuid (fn [item]
                            (let [target (if (map? item)
                                           (:db/id item)
                                           (tx-item-entity item))]
                              (tx-entity-uuid rebase-db-before temp-id->uuid target)))
        stale-block-uuids (->> tx-data
                               (keep target-block-uuid)
                               (remove created-block-uuids)
                               (filter #(nil? (d/entity current-db [:block/uuid %])))
                               set)]
    (remove (fn [item]
              (contains? stale-block-uuids (target-block-uuid item)))
            tx-data)))

(defn- local-conflict-block-uuids
  [db local-txs]
  (->> local-txs
       (mapcat :tx)
       (keep tx-item-components)
       (keep (fn [{:keys [e]}]
               (tx-entity-uuid db {} e)))
       set))

(defn- remote-sync-conflicts
  [db local-txs remote-txs]
  (let [local-block-uuids (local-conflict-block-uuids db local-txs)]
    (when (seq local-block-uuids)
      (->> remote-txs
           (mapcat (fn [{:keys [tx-data t]}]
                     (let [temp-id->uuid (tx-temp-id->uuid tx-data)]
                       (keep (fn [item]
                               (when-let [{:keys [op e a v]} (tx-item-components item)]
                                 (when (and (= :db/add op) (string? v))
                                   (when-let [block-uuid (tx-entity-uuid db temp-id->uuid e)]
                                     (let [current-value (some-> (d/entity db [:block/uuid block-uuid]) a)]
                                       (when (and (contains? local-block-uuids block-uuid)
                                                  (not= current-value v))
                                         {:block-uuid block-uuid
                                          :attr a
                                          :value v
                                          :remote-t t}))))))
                             tx-data))))
           distinct
           vec))))

(defn- broadcast-sync-conflicts!
  [repo conflicts]
  (doseq [block-uuid (distinct (map :block-uuid conflicts))]
    (shared-service/broadcast-to-clients!
     :sync-conflicts-updated
     {:repo repo
      :block-uuid block-uuid
      :conflicts (client-op/get-sync-conflicts repo block-uuid)})))

(defn- transact-remote-txs!
  [conn remote-txs & {:keys [db-before-local-reversal]}]
  (let [deleted-block-uuid-suffixes (remote-txs-retract-entity-block-uuid-suffixes remote-txs)]
    (loop [remaining remote-txs
           deleted-block-uuid-suffixes deleted-block-uuid-suffixes
           results []]
      (let [db @conn]
        (if-let [remote-tx (first remaining)]
          (let [deleted-block-uuids (first deleted-block-uuid-suffixes)
                tx-data (some->> (:tx-data remote-tx)
                                 (map (partial resolve-temp-id db))
                                 (tx-sanitize/sanitize-tx db)
                                 drop-stale-adds-after-remote-entity-delete)
                tx-data (cond->> tx-data
                          (seq deleted-block-uuids)
                          (drop-stale-deleted-block-ref-ops db deleted-block-uuids)

                          (tx-data-has-block-uuid-ref? tx-data)
                          (drop-missing-block-ref-ops db))
                tx-data (cond->> tx-data
                          db-before-local-reversal
                          (drop-local-reversal-stale-target-ops db-before-local-reversal db))
                tx-data (seq tx-data)
                tx-meta (apply-tx-meta remote-tx)
                report (ldb/transact! conn tx-data tx-meta)
                results' (cond-> results
                           tx-data
                           (conj {:tx-data tx-data
                                  :report report}))]
            (recur (next remaining)
                   (next deleted-block-uuid-suffixes)
                   results'))
          results)))))

(defn reverse-local-txs!
  [conn local-txs]
  (doall
   (->> local-txs
        reverse
        (map-indexed
         (fn [index local-tx]
           (try
             (reverse-history-action! conn local-tx)
             (catch :default e
               (if (expected-stale-rebase-error? e)
                 (do
                   (log/debug :db-sync/skip-stale-reverse-local-tx
                              {:index index
                               :tx-id (:tx-id local-tx)
                               :outliner-op (:outliner-op local-tx)
                               :error e})
                   {:tx-id (:tx-id local-tx)
                    :status :failed})
                 (do
                   (log/error ::reverse-local-tx-error
                              {:index index
                               :tx-id (:tx-id local-tx)
                               :outliner-op (:outliner-op local-tx)
                               :local-tx-count (count local-txs)
                               :error-name (or (.-name e) "Error")})
                   (throw e)))))))
        (keep identity)
        vec)))

(defn- invalid-rebase-op!
  [op data]
  (throw (ex-info "invalid rebase op" (assoc data :op op))))

(defn- expected-stale-rebase-error?
  [error]
  (let [data (ex-data error)]
    (or (= "invalid rebase op" (ex-message error))
        (= :entity-id/missing (:error data))
        (and (= :transact/unique (:error data))
             (= :block/uuid (:attribute data))))))

(defn- history-action-error-reason
  [error]
  (let [message (ex-message error)]
    (if (or (= "invalid rebase op" message)
            (= "Non-transact outliner ops contain numeric entity ids" message))
      :invalid-history-action-ops
      :error)))

(defn- replay-entity-id-value
  [db v]
  (cond
    (number? v)
    v

    (uuid? v)
    (some-> (d/entity db [:block/uuid v]) :db/id)

    (or (vector? v) (qualified-keyword? v))
    (some-> (d/entity db v) :db/id)

    :else
    v))

(defn- replay-entity-id-coll
  [db ids]
  (mapv #(or (replay-entity-id-value db %) %) ids))

(defn- rebase-find-existing-left-sibling
  [current-db target]
  (loop [sibling (ldb/get-left-sibling target)]
    (if (nil? sibling)
      nil
      (if-let [current-sibling (and sibling (d/entity current-db [:block/uuid (:block/uuid sibling)]))]
        current-sibling
        (recur (ldb/get-left-sibling sibling))))))

(defn- rebase-target-ref
  [target-id]
  (cond
    (and (vector? target-id)
         (= :block/uuid (first target-id))
         (uuid? (second target-id)))
    target-id

    (uuid? target-id)
    [:block/uuid target-id]

    (and (map? target-id) (uuid? (:block/uuid target-id)))
    [:block/uuid (:block/uuid target-id)]

    :else
    target-id))

(defn- rebase-resolve-target-and-sibling
  [current-db rebase-db-before target-id sibling?]
  (let [target-ref (rebase-target-ref target-id)
        target (d/entity current-db target-ref)
        target-before (when rebase-db-before
                        (d/entity rebase-db-before target-ref))
        parent-before (when rebase-db-before
                        (:block/parent (d/entity rebase-db-before target-ref)))]
    (cond
      target
      [target sibling?]

      (and target-before parent-before sibling?)
      (if-let [left-sibling (rebase-find-existing-left-sibling current-db target-before)]
        [left-sibling true]
        (when-let [parent (d/entity current-db [:block/uuid (:block/uuid parent-before)])]
          [parent false]))

      :else
      nil)))

(defn- template-parent-ref
  [parent]
  (cond
    (and (vector? parent) (= :block/uuid (first parent)))
    parent

    (uuid? parent)
    [:block/uuid parent]

    (and (map? parent) (uuid? (:block/uuid parent)))
    [:block/uuid (:block/uuid parent)]

    :else
    parent))

(defn- sanitize-template-block
  [current-db rebase-db-before block]
  (let [m (into {} block)
        block-id (:db/id m)
        block-uuid (or (:block/uuid m)
                       (when (number? block-id)
                         (or (some-> rebase-db-before (d/entity block-id) :block/uuid)
                             (some-> (d/entity current-db block-id) :block/uuid)))
                       (when (and (vector? block-id)
                                  (= :block/uuid (first block-id))
                                  (uuid? (second block-id)))
                         (second block-id)))]
    (cond-> (-> m
                (dissoc :db/id :block/order :block/page :block/tx-id)
                (update :block/parent template-parent-ref))
      (uuid? block-uuid)
      (assoc :block/uuid block-uuid))))

(defn- ^:large-vars/cleanup-todo replay-canonical-outliner-op!
  [conn [op args] rebase-db-before]
  (case op
    :save-block
    (let [[block opts] args
          db @conn
          block-uuid (:block/uuid block)
          block-ent (when block-uuid
                      (d/entity db [:block/uuid block-uuid]))
          block-base (dissoc block :db/id :block/order)
          block' (merge block-base
                        (op-construct/rewrite-block-title-with-retracted-refs db block-base))]
      (when (nil? block-ent)
        (invalid-rebase-op! op {:args args
                                :reason :missing-block}))
      (outliner-core/save-block! conn block' opts))

    :insert-blocks
    (let [[blocks target-id opts] args
          db @conn
          [target sibling?] (rebase-resolve-target-and-sibling db rebase-db-before target-id (:sibling? opts))]
      (when-not (and target (seq blocks))
        (invalid-rebase-op! op {:args args}))
      (outliner-core/insert-blocks! conn
                                    (mapv #(op-construct/rewrite-block-title-with-retracted-refs db %) blocks)
                                    target
                                    (assoc opts :sibling? sibling?)))

    :apply-template
    (let [[template-id target-id opts] args
          template-id' (replay-entity-id-value @conn template-id)
          target-id' (replay-entity-id-value @conn target-id)
          [target sibling?] (rebase-resolve-target-and-sibling @conn rebase-db-before target-id' (:sibling? opts))]
      (when-not (and template-id' (d/entity @conn template-id') target)
        (invalid-rebase-op! op {:args args
                                :reason :missing-template-or-target-block}))
      (let [template-uuid (:block/uuid (d/entity @conn template-id'))
            target-uuid (:block/uuid target)]
        (when-not (and (uuid? template-uuid) (uuid? target-uuid))
          (invalid-rebase-op! op {:args args
                                  :reason :missing-template-or-target-uuid}))
        (let [replace-empty-target? (:replace-empty-target? opts)
              template-blocks' (some->> (:template-blocks opts)
                                        (map-indexed
                                         (fn [idx block]
                                           (let [block' (sanitize-template-block @conn rebase-db-before block)
                                                 block'' (if (and replace-empty-target?
                                                                  (zero? idx)
                                                                  (nil? (:block/uuid block')))
                                                           ;; Keep replace-empty-target replay consistent with
                                                           ;; initial apply-template payload where the first
                                                           ;; block uuid is the target uuid.
                                                           (assoc block' :block/uuid target-uuid)
                                                           block')]
                                             (when (:block/uuid block'')
                                               block''))))
                                        (remove nil?)
                                        seq
                                        vec)
              opts' (cond-> (-> opts
                                (assoc :sibling? sibling?)
                                (dissoc :template-blocks))
                      template-blocks'
                      (assoc :template-blocks template-blocks'))]
          (outliner-op/apply-ops!
           conn
           [[:apply-template [template-uuid
                              target-uuid
                              opts']]]
           {:gen-undo-ops? false}))))

    :move-blocks
    (let [[ids target-id opts] args
          ids' (replay-entity-id-coll @conn ids)
          target-id' (replay-entity-id-value @conn target-id)
          blocks (keep #(d/entity @conn %) ids')
          [target sibling?] (rebase-resolve-target-and-sibling @conn rebase-db-before target-id' (:sibling? opts))]
      (when (or (empty? blocks) (nil? target))
        (invalid-rebase-op! op {:args args}))
      (when (seq blocks)
        (outliner-core/move-blocks! conn blocks target (assoc opts :sibling? sibling?))))

    :move-blocks-up-down
    (let [[ids up?] args
          ids' (replay-entity-id-coll @conn ids)
          blocks (keep #(d/entity @conn %) ids')]
      (when (seq blocks)
        (outliner-core/move-blocks-up-down! conn blocks up?)))

    :indent-outdent-blocks
    (let [[ids indent? opts] args
          ids' (replay-entity-id-coll @conn ids)
          blocks (keep #(d/entity @conn %) ids')]
      (when (empty? blocks)
        (invalid-rebase-op! op {:args args}))
      (when (seq blocks)
        (outliner-core/indent-outdent-blocks! conn blocks indent? opts)))

    :delete-blocks
    (let [[ids opts] args
          ids' (replay-entity-id-coll @conn ids)
          blocks (keep #(d/entity @conn %) ids')]
      ;; Keep delete replay idempotent under concurrent edits where blocks may already
      ;; be gone, but still leave a debug breadcrumb for malformed/missing targets.
      (when (empty? blocks)
        (log/debug :db-sync/drop-delete-blocks-replay
                   {:args args}))
      (when (seq blocks)
        (outliner-core/delete-blocks! conn blocks opts)))

    :create-page
    (let [[title opts] args
          page-uuid (:uuid opts)
          existing-page (or (when (uuid? page-uuid)
                              (d/entity @conn [:block/uuid page-uuid]))
                            (ldb/get-page @conn title))]
      (if (and existing-page
               (not (ldb/recycled? existing-page)))
        [(:block/title existing-page) (:block/uuid existing-page)]
        (outliner-page/create! conn title opts)))

    :delete-page
    (let [[page-uuid opts] args]
      (outliner-page/delete! conn page-uuid opts))

    :upsert-property
    (apply outliner-property/upsert-property! conn args)

    :restore-recycled
    (let [[root-id] args
          root-ref (cond
                     (and (vector? root-id)
                          (= :block/uuid (first root-id)))
                     root-id

                     (uuid? root-id)
                     [:block/uuid root-id]

                     :else
                     root-id)
          root (d/entity @conn root-ref)
          tx-data (when root
                    (seq (outliner-recycle/restore-tx-data @conn root)))]
      (when-not tx-data
        (invalid-rebase-op! op {:args args
                                :reason :invalid-restore-target}))
      (ldb/transact! conn tx-data
                     {:outliner-op :restore-recycled}))

    :recycle-delete-permanently
    (let [[root-id] args
          root-ref (cond
                     (and (vector? root-id)
                          (= :block/uuid (first root-id)))
                     root-id

                     (uuid? root-id)
                     [:block/uuid root-id]

                     :else
                     root-id)
          root (d/entity @conn root-ref)
          tx-data (when root
                    (seq (outliner-recycle/permanently-delete-tx-data @conn root)))]
      ;; Keep replay idempotent under concurrent edits where the recycled root may
      ;; already be permanently removed by a preceding remote tx.
      (when (seq tx-data)
        (ldb/transact! conn tx-data
                       {:outliner-op :recycle-delete-permanently})))

    (let [[tx-data tx-meta] args
          tx-data (cond->> tx-data
                    rebase-db-before
                    (drop-stale-rebase-target-ops @conn rebase-db-before))
          tx-data (expand-block-retracts-to-descendants @conn tx-data)]
      (when-let [tx-data (seq tx-data)]
        (ldb/transact! conn tx-data tx-meta)))))

(defn- rebase-local-op!
  [_repo conn local-tx rebase-db-before]
  (if (= :fix (:outliner-op local-tx))
    {:tx-id (:tx-id local-tx)
     :status :kept}
    (let [{:keys [forward-ops inverse-ops]} (rebase-history-ops local-tx)
          tx-meta {:outliner-op :rebase
                   :original-outliner-op (:outliner-op local-tx)
                   :db-sync/rebased-local? true
                   ;; Keep stable tx-id across rebases so one logical pending op
                   ;; doesn't fan out into duplicated pending rows.
                   :db-sync/tx-id (:tx-id local-tx)
                   :db-sync/forward-outliner-ops forward-ops
                   :db-sync/inverse-outliner-ops inverse-ops}
          forward-ops' (if (seq forward-ops)
                         forward-ops
                         (let [tx-data (-> (:tx local-tx) normalize-tx-data-for-rebase)]
                           [[:transact [tx-data (assoc tx-meta
                                                       :db-sync/suppress-stale-rebase-transact-failed-log?
                                                       true)]]]))]
      (try
        (let [rebase-tx-report
              (ldb/batch-transact-with-temp-conn!
               conn
               tx-meta
               (fn [conn]
                 (doseq [op forward-ops']
                   (replay-canonical-outliner-op! conn op rebase-db-before))))
              status (if rebase-tx-report :rebased :no-op)]
          {:tx-id (:tx-id local-tx)
           :status status})
        (catch :default error
          (let [drop-log {:tx-id (:tx-id local-tx)
                          :outliner-op (:outliner-op local-tx)
                          :undo? (:undo? local-tx)
                          :redo? (:redo? local-tx)
                          :diagnostic
                          (dissoc
                           (sync-util/error->diagnostic error)
                           :at)}]
            (when-not (expected-stale-rebase-error? error)
              (log/warn :db-sync/drop-op-driven-pending-tx drop-log))
            {:tx-id (:tx-id local-tx)
             :status :failed}))))))

(defn- rebase-local-txs!
  [repo conn local-txs rebase-db-before]
  (mapv (fn [local-tx]
          (rebase-local-op! repo conn local-tx rebase-db-before))
        local-txs))

(defn- fix-tx!
  [conn rebase-tx-report tx-meta]
  (sync-order/fix-duplicate-orders! conn
                                    (:tx-data rebase-tx-report)
                                    tx-meta))

(defn- sync-fix-tx-meta
  []
  {:outliner-op :fix
   :gen-undo-ops? false
   :db-sync/tx-id (random-uuid)})

(defn- repair-applied-txs!
  [conn tx-report]
  (when (seq (:tx-data tx-report))
    (fix-tx! conn tx-report (sync-fix-tx-meta))))

(defn- pending-tx-ids
  [local-txs]
  (mapv :tx-id local-txs))

(defn- pending-tx-snapshot-changed?
  [repo local-txs]
  (not= (pending-tx-ids local-txs)
        (pending-tx-ids (pending-txs repo))))

(defn- fail-if-pending-tx-snapshot-changed!
  [repo local-txs]
  (when (pending-tx-snapshot-changed? repo local-txs)
    (throw (ex-info "pending local tx snapshot changed during remote apply"
                    {:code :pending-tx-snapshot-changed
                     :repo repo
                           :local-tx-ids (pending-tx-ids local-txs)
                           :current-local-tx-ids (pending-tx-ids (pending-txs repo))}))))

(defn- apply-remote-tx-with-local-changes!
  [{:keys [repo conn local-txs remote-txs db-migrate? skip-final-validate?]}]
  (let [tx-meta (cond-> {:rtc-tx? true
                         :with-local-changes? true}
                  db-migrate?
                  (assoc :db-migrate? true
                         :outliner-op :db-migrate)
                  skip-final-validate?
                  (assoc :skip-validate-db? true))
        *rebase-tx-reports (atom [])
        *rebase-results (atom [])
        *remote-tx-results (atom [])
        rebase-db-before @conn
        conflicts (remote-sync-conflicts rebase-db-before local-txs remote-txs)]
    (try
      (when (seq conflicts)
        (client-op/add-sync-conflicts! repo conflicts)
        (broadcast-sync-conflicts! repo conflicts))
      (let [tx-report (ldb/batch-transact-with-temp-conn!
                       conn
                       tx-meta
                       (fn [conn]
                         (reverse-local-txs! conn local-txs)

                         (reset! *remote-tx-results
                                 (transact-remote-txs! conn remote-txs
                                                       :db-before-local-reversal rebase-db-before))

                         (reset! *rebase-results
                                 (rebase-local-txs! repo conn local-txs rebase-db-before)))

                       {:listen-db (fn [{:keys [tx-meta tx-data] :as tx-report}]
                                     (when (and (= :rebase (:outliner-op tx-meta))
                                                (:db-sync/rebased-local? tx-meta)
                                                (seq tx-data))
                                       (swap! *rebase-tx-reports conj tx-report)))
                        :before-commit #(fail-if-pending-tx-snapshot-changed! repo local-txs)})]
        (doseq [tx-report @*rebase-tx-reports]
          (handle-local-tx! repo tx-report))

        (repair-applied-txs! conn tx-report)
        ;; Mark only explicitly stale rebases as non-pending.
        ;; Do not infer stale via tx-id set-diff, which can hide still-valid
        ;; pending txs that should wait for server ack/reject.
        (let [stale-tx-ids (->> @*rebase-results
                                (filter (fn [{:keys [status]}]
                                          (contains? #{:failed :no-op} status)))
                                (keep :tx-id)
                                distinct
                                vec)]
          (mark-pending-txs-false! repo stale-tx-ids))
        {:remote-asset-tx-data (mapcat (comp :tx-data :report) @*remote-tx-results)})

      (catch :default e
        (js/console.error e)
        (throw e))
      (finally
        (reset! *rebase-tx-reports nil)
        (reset! *rebase-results nil)
        (worker-undo-redo/clear-history! repo)))))

(defn- apply-remote-tx-without-local-changes!
  [{:keys [repo conn local-txs remote-txs db-migrate? skip-final-validate?]}]
  (let [remote-tx-results (atom [])
        tx-report (ldb/batch-transact-with-temp-conn!
                   conn
                   (cond-> {:rtc-tx? true
                            :without-local-changes? true}
                     db-migrate?
                     (assoc :db-migrate? true
                            :outliner-op :db-migrate)
                     skip-final-validate?
                     (assoc :skip-validate-db? true))
                   (fn [conn]
                     (reset! remote-tx-results
                             (transact-remote-txs! conn remote-txs)))
                   {:before-commit #(fail-if-pending-tx-snapshot-changed! repo local-txs)})]
    (repair-applied-txs! conn tx-report)
    {:remote-asset-tx-data (mapcat (comp :tx-data :report) @remote-tx-results)}))

(defn- report-apply-remote-txs-error!
  [error {:keys [has-local-changes? remote-txs local-txs]}]
  (let [safe-error-data (select-keys (or (ex-data error) {})
                                     [:type :code :error :failed-tx-id
                                      :successful-tx-ids :missing-block-uuids])]
    (try
      (platform/post-message!
       (platform/current)
       :capture-error
       (cond-> {:error (js/Error. "Sync apply remote txs failed")
                :payload {:source "db-sync"
                          :operation "apply-remote-txs"
                          :has-local-changes? has-local-changes?
                          :remote-tx-count (count remote-txs)
                          :local-tx-count (count local-txs)}}
         (seq safe-error-data)
         (assoc :extra {:error-data safe-error-data})))
      (catch :default report-error
        (log/error :db-sync/report-apply-remote-txs-error-failed
                   {:error-name (or (.-name report-error) "Error")})))))

(defn- eager-remote-asset-download-owner?
  []
  (contains? #{:cli :electron}
             (try
               (platform/env-flag (platform/current) :owner-source)
               (catch :default _
                 nil))))

(defn- download-missing-remote-assets-for-owner!
  [repo client remote-tx-data]
  (when (and (:graph-id client)
             (eager-remote-asset-download-owner?))
    (let [candidates (some-> (worker-state/get-datascript-conn repo)
                             deref
                             (sync-assets/remote-asset-download-candidates-in-tx remote-tx-data))]
      (when (seq candidates)
        (sync-assets/download-remote-assets-if-missing! repo (:graph-id client) candidates)))))

(defn- finish-owner-asset-download!
  [repo download-result]
  (when download-result
    (if (= :cli
           (try
             (platform/env-flag (platform/current) :owner-source)
             (catch :default _
               nil)))
      ;; A CLI sync command promises a fully hydrated local graph when it
      ;; exits, so retain the awaited behavior there.
      download-result
      (do
        ;; Desktop text/database sync must not be serialized behind large
        ;; attachment downloads. The remote metadata is already committed, so
        ;; missing files can be hydrated safely in the background.
        (-> download-result
            (p/catch
             (fn [error]
               (log/error :db-sync/remote-asset-background-download-failed
                          {:repo repo
                           :error-name
                           (or (some-> error .-name) "Error")})
               nil)))
        nil))))

(defn- finish-apply-remote-txs!
  [repo client remote-tx-data remote-asset-tx-data]
  (when-let [*inflight (:inflight client)]
    (reset! *inflight []))

  (p/let [_ (when-let [result (rehydrate-large-titles! repo {:tx-data remote-tx-data
                                                             :graph-id (:graph-id client)})]
              (p/catch result
                       (fn [error]
                         (log/error :db-sync/large-title-rehydrate-failed
                                    {:repo repo
                                     :diagnostic
                                     (dissoc
                                      (sync-util/error->diagnostic error)
                                      :at)}))))
          asset-download
          (download-missing-remote-assets-for-owner!
           repo client remote-asset-tx-data)
          _ (finish-owner-asset-download! repo asset-download)]
    nil))

(defn- remote-apply-snapshot-retry-action
  [snapshot-retry-count]
  (if (< snapshot-retry-count eager-remote-apply-snapshot-retries)
    :immediate
    :delayed))

(defn- remote-apply-snapshot-retry-delay-ms
  [snapshot-retry-count]
  (let [delayed-attempt (max 0 (- snapshot-retry-count
                                  eager-remote-apply-snapshot-retries))]
    (min remote-apply-snapshot-retry-max-delay-ms
         (* remote-apply-snapshot-retry-base-delay-ms
            (js/Math.pow 2 delayed-attempt)))))

(defn- pending-tx-log-summary
  [txs]
  (let [ids (pending-tx-ids txs)]
    (cond-> {:count (count ids)}
      (seq ids) (assoc :first-tx-id (first ids)
                       :last-tx-id (last ids)))))

(defn- apply-remote-txs-with-retry!
  [{:keys [repo client has-local-changes? remote-txs local-txs apply-context remote-tx-data
           snapshot-retry-count]}]
  (let [apply-result (try
                       (if has-local-changes?
                         (apply-remote-tx-with-local-changes! apply-context)
                         (apply-remote-tx-without-local-changes! apply-context))
                       (catch :default error
                         (if (or (= :pending-tx-snapshot-changed (:code (ex-data error)))
                                 (pending-tx-snapshot-changed? repo local-txs))
                           (if (>= snapshot-retry-count
                                   max-remote-apply-snapshot-retries)
                             (throw
                              (ex-info
                               "remote apply deferred until local edits settle"
                               {:type :db-sync/remote-apply-deferred
                                :repo repo
                                :snapshot-retry-count snapshot-retry-count
                                :remote-tx-count (count remote-txs)}
                               error))
                             (let [error-data (ex-data error)
                                 current-local-txs (pending-txs repo)
                                 retry-payload {:repo repo
                                                :snapshot-retry-count snapshot-retry-count
                                                :remote-tx-count (count remote-txs)
                                                :local-txs (pending-tx-log-summary local-txs)
                                                :current-local-txs
                                                (pending-tx-log-summary current-local-txs)
                                                :error-code (or (:code error-data)
                                                                (:type error-data)
                                                                :snapshot-drift)}]
                             (case (remote-apply-snapshot-retry-action snapshot-retry-count)
                               :immediate
                               (do
                                 (log/warn :db-sync/retry-remote-apply-after-local-tx-snapshot-drift
                                           retry-payload)
                                 {::retry-delay-ms 0})

                               :delayed
                               (do
                                 (log/warn :db-sync/delay-remote-apply-after-local-tx-snapshot-drift
                                           retry-payload)
                                 {::retry-delay-ms
                                  (remote-apply-snapshot-retry-delay-ms
                                   snapshot-retry-count)}))))
                           (do
                             (log/error :db-sync/apply-remote-txs-failed
                                        {:repo repo
                                         :has-local-changes? has-local-changes?
                                         :remote-tx-count (count remote-txs)
                                         :local-tx-count (count local-txs)
                                         :remote-tx-ids (into [] (keep :tx-id) remote-txs)
                                         :local-tx-ids (pending-tx-ids local-txs)
                                         :error-code (or (:code (ex-data error))
                                                         (:type (ex-data error))
                                                         :apply-failed)
                                         :error-name (or (.-name error) "Error")})
                             (report-apply-remote-txs-error!
                              error
                              {:has-local-changes? has-local-changes?
                               :remote-txs remote-txs
                               :local-txs local-txs})
                             (throw
                              (ex-info
                               "remote transaction apply failed"
                               (cond-> {:type :db-sync/remote-apply-failed
                                        :repo repo
                                        :remote-tx-count (count remote-txs)
                                        :local-tx-count (count local-txs)}
                                 (:type (ex-data error))
                                 (assoc :cause-type (:type (ex-data error)))
                                 (:code (ex-data error))
                                 (assoc :cause-code (:code (ex-data error))))
                               error))))))]
    (if (contains? apply-result ::retry-delay-ms)
      apply-result
      (finish-apply-remote-txs! repo client remote-tx-data (:remote-asset-tx-data apply-result)))))

(defn- apply-remote-txs-once!
  [repo client remote-txs snapshot-retry-count]
  (if-let [conn (worker-state/get-datascript-conn repo)]
    (let [local-txs (pending-txs repo)
          has-local-changes? (boolean (seq local-txs))
          remote-tx-data* (mapcat :tx-data remote-txs)
          db-migrate? (remote-txs-db-migrate? remote-txs)
          apply-context {:repo repo
                         :conn conn
                         :local-txs local-txs
                         :remote-txs remote-txs
                         :temp-tx-meta {:rtc-tx? true
                                        :gen-undo-ops? false}
                         :db-migrate? db-migrate?
                         :skip-final-validate? db-migrate?}
          apply-args {:repo repo
                      :client client
                      :has-local-changes? has-local-changes?
                      :remote-txs remote-txs
                      :local-txs local-txs
                      :apply-context apply-context
                      :remote-tx-data remote-tx-data*
                      :snapshot-retry-count snapshot-retry-count}]
      (apply-remote-txs-with-retry! apply-args))
    (fail-fast :db-sync/missing-db {:repo repo :op :apply-remote-txs})))

(defn- retry-apply-remote-txs!
  [repo client remote-txs initial-snapshot-retry-count initial-delay-ms]
  ;; Keep a single outer promise for the whole retry period. Delayed attempts
  ;; are scheduled independently instead of returning a recursively nested
  ;; promise chain, so continuous editing cannot retain unbounded retry state.
  (js/Promise.
   (fn [resolve reject]
     (letfn [(schedule! [snapshot-retry-count delay-ms]
               (if (zero? delay-ms)
                 ;; Yield one microtask so DB listeners can persist a racing
                 ;; local edit before the next pending-tx snapshot is read.
                 (js/queueMicrotask #(attempt! snapshot-retry-count))
                 (js/setTimeout #(attempt! snapshot-retry-count) delay-ms)))
             (attempt! [snapshot-retry-count]
               (let [current (current-client repo)
                     generation (:connection-generation client)
                     current-generation? (or (nil? generation)
                                             (and current
                                                  (= generation
                                                     (:connection-generation current))))
                     result
                     (cond
                       (not current-generation?)
                       {::terminal-error
                        (ex-info "stale remote apply attempt"
                                 {:type :db-sync/stale-remote-apply
                                  :repo repo})}

                       :else
                       (try
                         (apply-remote-txs-once!
                          repo client remote-txs snapshot-retry-count)
                         (catch :default error
                           {::terminal-error error})))]
                 (cond
                   (and (map? result)
                        (contains? result ::terminal-error))
                   (reject (::terminal-error result))

                   (and (map? result)
                        (contains? result ::retry-delay-ms))
                   (schedule! (inc snapshot-retry-count)
                              (::retry-delay-ms result))

                   :else
                   (-> (p/resolved result)
                       (p/then resolve)
                       (p/catch reject)))))]
       (schedule! initial-snapshot-retry-count initial-delay-ms)))))

(defn apply-remote-txs!
  ([repo client remote-txs]
   (apply-remote-txs! repo client remote-txs 0))
  ([repo client remote-txs snapshot-retry-count]
   ;; Preserve the historical synchronous exception behavior for the first
   ;; attempt. Only snapshot drift enters the asynchronous retry driver.
   (let [result (apply-remote-txs-once!
                 repo client remote-txs snapshot-retry-count)]
     (if (and (map? result)
              (contains? result ::retry-delay-ms))
       (retry-apply-remote-txs!
        repo
        client
        remote-txs
        (inc snapshot-retry-count)
        (::retry-delay-ms result))
       result))))

(defn apply-remote-tx!
  [repo client tx-data]
  (apply-remote-txs! repo client [{:tx-data tx-data}]))

(defn- enqueue-local-tx-aux
  [repo {:keys [tx-data db-after db-before] :as tx-report}]
  (let [normalized (normalize-tx-data db-after db-before tx-data)
        reversed-datoms (reverse-tx-data db-before db-after tx-data)]
    ;; (prn :debug :reversed-datoms reversed-datoms)
    ;; (prn :debug :enqueue-local-tx :tx-data)
    ;; (cljs.pprint/pprint tx-data)
    ;; (prn :debug :enqueue-local-tx :normalized)
    ;; (cljs.pprint/pprint normalized)

    (when (seq normalized)
      (persist-local-tx! repo tx-report normalized reversed-datoms)
      (when-let [client @worker-state/*db-sync-client]
        (when (= repo (:repo client))
          (enqueue-flush-pending! repo client))))))

(defn- persistable-local-tx-meta?
  [tx-meta]
  (and (not (:rtc-tx? tx-meta))
       (not (:transact-remote? tx-meta))
       (not (:sync-download-graph? tx-meta))
       (:persist-op? tx-meta true)
       (or (not= :rebase (:outliner-op tx-meta))
           (:db-sync/rebased-local? tx-meta))))

(defn enqueue-local-tx!
  [repo {:keys [tx-meta tx-data] :as tx-report}]
  (when (worker-state/get-datascript-conn repo)
    (when (and (persistable-local-tx-meta? tx-meta)
               (not (and (:batch-tx-report? tx-meta) (not= :rebase (:outliner-op tx-meta))))
               (not (:reverse? tx-meta))
               (seq tx-data))
      (enqueue-local-tx-aux repo tx-report))))

(defn handle-local-tx!
  [repo {:keys [tx-data tx-meta db-after] :as tx-report}]
  (when (and (seq tx-data)
             (persistable-local-tx-meta? tx-meta))
    (enqueue-local-tx! repo tx-report)
    (asset-db-listener/generate-asset-ops repo tx-report)
    (when-let [client @worker-state/*db-sync-client]
      (when (and (= repo (:repo client))
                 (:kv/value (d/entity db-after :logseq.kv/graph-remote?)))
        (sync-assets/enqueue-asset-sync!
         repo client
         {:enqueue-asset-task-f enqueue-asset-task!
          :current-client-f current-client
          :broadcast-rtc-state!-f broadcast-rtc-state!
          :fail-fast-f fail-fast})))))
