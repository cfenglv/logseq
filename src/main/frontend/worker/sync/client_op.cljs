(ns frontend.worker.sync.client-op
  "Store client sync metadata and ops in sqlite tables.
   DataScript client-op storage is deprecated and unsupported."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.worker.state :as worker-state]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [logseq.db.sqlite.util :as sqlite-util]
            [malli.core :as ma]
            [malli.transform :as mt]))

(def op-schema
  [:multi {:dispatch first}
   [:update-asset
    [:catn
     [:op :keyword]
     [:t :int]
     [:value [:map
              [:block-uuid :uuid]]]]]
   [:remove-asset
    [:catn
     [:op :keyword]
     [:t :int]
     [:value [:map
              [:block-uuid :uuid]]]]]])

(def ops-schema [:sequential op-schema])
(def ops-coercer (ma/coercer ops-schema mt/json-transformer nil
                             #(do (log/error ::bad-ops (:value %))
                                  (ma/-fail! ::ops-schema (select-keys % [:value])))))

(defonce *repo->pending-local-tx-count (atom {}))

(def ^:private sqlite-schema-ready-key "__logseq_client_ops_schema_ready_v3")
(def ^:private sqlite-mode-key "__logseq_client_ops_sqlite_mode")
(def ^:private sync-meta-table-sql
  "create table if not exists sync_meta (key text primary key, value text)")
(def ^:private client-ops-table-sql
  (str "create table if not exists client_ops ("
       "id integer primary key autoincrement,"
       "kind text not null,"
       "created_at integer not null,"
       "tx_id text unique,"
       "pending integer not null default 0,"
       "failed integer not null default 0,"
       "outliner_op text,"
       "undo_redo text,"
       "forward_outliner_ops text,"
       "inverse_outliner_ops text,"
       "inferred_outliner_ops integer,"
       "normalized_tx_data text,"
       "reversed_tx_data text,"
       "asset_uuid text,"
       "asset_op text,"
       "asset_t integer,"
       "asset_value text"
       ")"))
(def ^:private pending-index-sql
  "create index if not exists idx_client_ops_pending_created on client_ops(kind, pending, created_at, id)")
(def ^:private asset-index-sql
  "create index if not exists idx_client_ops_asset_uuid on client_ops(kind, asset_uuid)")
(def ^:private sync-conflicts-table-sql
  (str "create table if not exists sync_conflicts ("
       "id integer primary key autoincrement,"
       "block_uuid text not null,"
       "attr text not null,"
       "value text not null,"
       "remote_t integer,"
       "created_at integer not null,"
       "unique(block_uuid, attr, value)"
       ")"))
(def ^:private sync-conflicts-block-index-sql
  "create index if not exists idx_sync_conflicts_block_uuid on sync_conflicts(block_uuid, created_at)")
(def ^:private client-tx-upload-state-table-sql
  (str "create table if not exists client_tx_upload_state ("
       "logical_tx_id text primary key,"
       "format_version integer not null,"
       "kind text not null,"
       "source_digest text not null,"
       "state text not null,"
       "updated_at integer not null"
       ")"))

(defn- client-ops-store
  [repo]
  (worker-state/get-client-ops-conn repo))

(declare ensure-sqlite-schema!)

(defn- detect-sqlite-mode
  [^js db]
  (or (gobj/get db sqlite-mode-key)
      (let [mode
            (cond
              (not db) nil
              (fn? (gobj/get db "prepare"))
              (try
                (let [^js stmt (.prepare db "select 1")]
                  (try
                    (if (fn? (gobj/get stmt "run"))
                      :better-sqlite
                      :sqlite-wasm)
                    (finally
                      (when (fn? (gobj/get stmt "finalize"))
                        (.finalize stmt)))))
                (catch :default _
                  :sqlite-wasm))
              (fn? (gobj/get db "exec")) :sqlite-wasm
              :else nil)]
        (when mode
          (try
            (gobj/set db sqlite-mode-key mode)
            (catch :default _
              nil)))
        mode)))

(defn- sqlite-db?
  [conn]
  (some? (detect-sqlite-mode conn)))

(defn- sqlite-store-or-throw
  [repo]
  (when-let [store (client-ops-store repo)]
    (if (sqlite-db? store)
      (do
        (ensure-sqlite-schema! store)
        store)
      (throw (ex-info "Legacy DataScript client-op storage is unsupported. Please back up the graph and re-download it."
                      {:type :db-sync/legacy-client-ops-storage
                       :repo repo})))))

(defn- parse-uuid-str
  [v]
  (when (string? v)
    (try
      (uuid v)
      (catch :default _
        nil))))

(defn- kw->str
  [v]
  (cond
    (keyword? v) (name v)
    (string? v) v
    :else nil))

(defn- str->kw
  [v]
  (when (string? v)
    (keyword v)))

(defn- qualified-kw->str
  [v]
  (cond
    (qualified-keyword? v) (subs (str v) 1)
    (keyword? v) (name v)
    (string? v) v
    :else nil))

(defn- str->qualified-kw
  [v]
  (when (string? v)
    (keyword v)))

(defn- bool->int [v] (if v 1 0))
(defn- int->bool [v] (not (or (nil? v) (= 0 v) (= false v))))

(defn- normalize-op-entries
  [ops]
  (let [ops' (some-> ops seq vec)]
    (cond
      (nil? ops')
      nil

      (and (keyword? (first ops'))
           (vector? (second ops')))
      [ops']

      :else
      ops')))

(defn- sqlite-run!
  [^js db sql params]
  (case (detect-sqlite-mode db)
    :better-sqlite
    (let [^js stmt (.prepare db sql)
          run-fn (gobj/get stmt "run")]
      (if (seq params)
        (.apply run-fn stmt (to-array params))
        (.call run-fn stmt)))

    :sqlite-wasm
    (.exec db #js {:sql sql
                   :bind (into-array params)})

    nil))

(defn- sqlite-rows
  [^js db sql params]
  (case (detect-sqlite-mode db)
    :better-sqlite
    (let [^js stmt (.prepare db sql)
          all-fn (gobj/get stmt "all")]
      (vec (if (seq params)
             (.apply all-fn stmt (to-array params))
             (.call all-fn stmt))))

    :sqlite-wasm
    (let [^js result (.exec db #js {:sql sql
                                :bind (into-array params)
                                :rowMode "object"
                                :returnValue "resultRows"})]
      (cond
        (nil? result) []
        (array? result) (vec result)
        (fn? (gobj/get result "toArray")) (vec (.toArray result))
        :else []))

    []))

(defn- sqlite-row
  [db sql params]
  (first (sqlite-rows db sql params)))

(defn- sqlite-with-tx!
  [^js db f]
  (case (detect-sqlite-mode db)
    :better-sqlite
    (let [tx-fn (.transaction db (fn [] (f db)))]
      (tx-fn))

    :sqlite-wasm
    (if (fn? (gobj/get db "transaction"))
      (.transaction db (fn [tx] (f tx)))
      (f db))

    (f db)))

(defn ensure-sqlite-schema!
  [db]
  (when (sqlite-db? db)
    (when-not (true? (gobj/get db sqlite-schema-ready-key))
      (sqlite-with-tx!
       db
       (fn [tx]
         (sqlite-run! tx sync-meta-table-sql [])
         (sqlite-run! tx client-ops-table-sql [])
         (sqlite-run! tx sync-conflicts-table-sql [])
         (sqlite-run! tx client-tx-upload-state-table-sql [])
         (sqlite-run! tx pending-index-sql [])
         (sqlite-run! tx asset-index-sql [])
         (sqlite-run! tx sync-conflicts-block-index-sql [])))
      (try
        (gobj/set db sqlite-schema-ready-key true)
        (catch :default _
          nil)))))

(defn read-sync-meta-value
  "Read one reserved sync_meta value while holding the client-ops transaction."
  [db k]
  (ensure-sqlite-schema! db)
  (sqlite-with-tx!
   db
   (fn [tx]
     (if-let [row (sqlite-row tx "select value from sync_meta where key = ?" [k])]
       {:found? true :value (aget row "value")}
       {:found? false}))))

(defn insert-sync-meta-value-if-absent!
  "Insert a reserved sync_meta value without replacing an existing row."
  [db k encoded-value]
  (ensure-sqlite-schema! db)
  (sqlite-with-tx!
   db
   (fn [tx]
     (if-let [row (sqlite-row tx "select value from sync_meta where key = ?" [k])]
       {:inserted? false :value (aget row "value")}
       (do
         (sqlite-run! tx
                      "insert into sync_meta (key, value) values (?, ?)"
                      [k encoded-value])
         {:inserted? true :value encoded-value})))))

(defn compare-and-set-sync-meta-value!
  "Replace one whole encoded sync_meta value iff it still equals expected-value."
  [db k expected-value replacement-value]
  (ensure-sqlite-schema! db)
  (sqlite-with-tx!
   db
   (fn [tx]
     (let [row (sqlite-row tx "select value from sync_meta where key = ?" [k])]
       (if (and row (= expected-value (aget row "value")))
         (do
           (sqlite-run! tx
                        "update sync_meta set value = ? where key = ?"
                        [replacement-value k])
           true)
         false)))))

(defn commit-repair-activation-and-sync-meta!
  "Atomically replace the activation value and advance the two existing
  canonical sync_meta fields to one verified repair target basis. A stale
  activation CAS changes none of the three rows."
  [db activation-key expected-value replacement-value remote-t checksum]
  (when-not (and (integer? remote-t) (>= remote-t 0) (string? checksum))
    (throw (ex-info "Invalid repair sync_meta target"
                    {:type :db-sync/invalid-repair-sync-meta-target
                     :remote-t remote-t})))
  (ensure-sqlite-schema! db)
  (sqlite-with-tx!
   db
   (fn [tx]
     (let [activation-row
           (sqlite-row tx "select value from sync_meta where key = ?"
                       [activation-key])
           current-t
           (some-> (sqlite-row tx
                               "select value from sync_meta where key = 'local-tx'"
                               [])
                   (aget "value")
                   (js/parseInt 10))]
       (if (and activation-row
                (= expected-value (aget activation-row "value")))
         (do
           (when (and current-t (< remote-t current-t))
             (throw (ex-info "Repair remote cursor would move backward"
                             {:type :db-sync/repair-remote-cursor-regression
                              :current-t current-t
                              :target-t remote-t})))
           (sqlite-run! tx
                        "update sync_meta set value = ? where key = ?"
                        [replacement-value activation-key])
           (sqlite-run!
            tx
            (str "insert into sync_meta (key, value) values (?, ?)"
                 " on conflict(key) do update set value = excluded.value")
            ["local-tx" (str remote-t)])
           (sqlite-run!
            tx
            (str "insert into sync_meta (key, value) values (?, ?)"
                 " on conflict(key) do update set value = excluded.value")
            ["checksum" checksum])
           true)
         false)))))

(defn- repair-local-observation-in-tx
  [tx]
  (let [sequence-row (sqlite-row
                      tx
                      "select seq from sqlite_sequence where name = 'client_ops'"
                      [])
        journal-row (sqlite-row
                     tx
                     (str "select "
                          "sum(case when kind = 'tx' and pending = 1 then 1 else 0 end) as pending_count "
                          "from client_ops")
                     [])
        local-t (some-> (sqlite-row tx
                                    "select value from sync_meta where key = 'local-tx'"
                                    [])
                        (aget "value")
                        (js/parseInt 10))]
    {:remote-t local-t
     :journal-high-water (or (some-> sequence-row (aget "seq")) 0)
     :pending-count (or (some-> journal-row (aget "pending_count")) 0)
     :legacy-anchor (some-> (sqlite-row
                             tx
                             "select value from sync_meta where key = 'checksum'"
                             [])
                            (aget "value"))}))

(defn read-repair-local-observation
  "Read the journal/cursor observation used by one repair suspect check.
  This is a cold-path read transaction; it does not create a mirrored cursor."
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-with-tx! store repair-local-observation-in-tx)))

(defn read-repair-completion-observation
  "Read the durable journal/cursor evidence used to retire one committed repair.
  Only pending official tx rows at or below the repair membership watermark
  prevent completion; newer rows remain owned by the normal RTC path."
  [repo journal-high-water]
  (when-not (and (integer? journal-high-water) (>= journal-high-water 0))
    (throw (ex-info "Invalid repair completion high-water"
                    {:type :db-sync/invalid-repair-completion-high-water
                     :journal-high-water journal-high-water})))
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-with-tx!
     store
     (fn [tx]
       (let [observation (repair-local-observation-in-tx tx)
             pending-row
             (sqlite-row
              tx
              (str "select count(*) as pending_through_target "
                   "from client_ops "
                   "where kind = 'tx' and pending = 1 and id <= ?")
              [journal-high-water])]
         (assoc observation
                :pending-through-target
                (or (some-> pending-row (aget "pending_through_target")) 0)))))))

(defn- sqlite-get-meta
  [db k]
  (some-> (sqlite-row db "select value from sync_meta where key = ?" [(name k)])
          (aget "value")))

(defn- sqlite-set-meta!
  [db k v]
  (sqlite-run! db
               (str "insert into sync_meta (key, value) values (?, ?)"
                    " on conflict(key) do update set value = excluded.value")
               [(name k) (str v)]))

(defn update-graph-uuid
  [repo graph-uuid]
  {:pre [(some? graph-uuid)]}
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-set-meta! store :graph-uuid graph-uuid)))

(defn get-graph-uuid
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-get-meta store :graph-uuid)))

(defn get-local-tx
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (when-let [result (sqlite-get-meta store :local-tx)]
      (js/parseInt result 10))))

(defn update-local-tx
  [repo t]
  {:pre [(and (integer? t) (>= t 0))]}
  (let [store (sqlite-store-or-throw repo)
        prev-t (get-local-tx repo)]
    (assert (some? store) repo)
    (when (and prev-t (< t prev-t))
      (throw (ex-info "local-tx should be monotonically increasing"
                      {:repo repo
                       :prev-t prev-t
                       :new-t t})))
    (sqlite-set-meta! store :local-tx t)))

(defn reset-local-tx
  "Should be used only when uploading a graph"
  [repo]
  (let [store (sqlite-store-or-throw repo)]
    (assert (some? store) repo)
    (sqlite-set-meta! store :local-tx 0)))

(defn update-local-checksum
  [repo checksum]
  {:pre [(some? checksum)]}
  (let [store (sqlite-store-or-throw repo)]
    (assert (some? store) repo)
    (sqlite-set-meta! store :db-sync/checksum checksum)))

(defn get-pending-local-tx-count
  [repo]
  (if-let [cached (get @*repo->pending-local-tx-count repo)]
    cached
    (let [count' (if-let [store (sqlite-store-or-throw repo)]
                   (or (some-> (sqlite-row store
                                            "select count(*) as c from client_ops where kind = 'tx' and pending = 1"
                                            [])
                               (aget "c"))
                       0)
                   0)]
      (swap! *repo->pending-local-tx-count assoc repo count')
      count')))

(defn adjust-pending-local-tx-count!
  [repo delta]
  (swap! *repo->pending-local-tx-count
         (fn [m]
           (let [base (or (get m repo) 0)
                 next (max 0 (+ base delta))]
             (assoc m repo next)))))

(defn get-local-checksum
  [repo]
  (let [store (sqlite-store-or-throw repo)]
    (assert (some? store) repo)
    (sqlite-get-meta store :db-sync/checksum)))

(defn read-local-checksum-and-sync-meta-value
  "Read the existing checksum and one reserved cold-path value in the single
  query already needed at a checksum-ready RTC boundary."
  [repo key]
  (let [store (sqlite-store-or-throw repo)]
    (assert (some? store) repo)
    (let [rows (sqlite-rows
                store
                "select key, value from sync_meta where key in ('checksum', ?)"
                [key])
          values (into {}
                       (map (fn [row]
                              [(aget row "key") (aget row "value")]))
                       rows)]
      {:local-checksum (get values "checksum")
       :reserved-value (get values key)})))

(defn rtc-db-graph?
  "Is RTC enabled"
  [repo]
  (or (exists? js/process)
      (some? (get-graph-uuid repo))))

(defn- row->pending-local-tx
  [row]
  (let [tx-id (parse-uuid-str (aget row "tx_id"))]
    (when tx-id
      {:tx-id tx-id
       :outliner-op (str->kw (aget row "outliner_op"))
       :forward-outliner-ops (or (normalize-op-entries
                                  (sqlite-util/read-transit-str (aget row "forward_outliner_ops")))
                                 [])
       :inverse-outliner-ops (or (normalize-op-entries
                                  (sqlite-util/read-transit-str (aget row "inverse_outliner_ops")))
                                 [])
       :inferred-outliner-ops? (int->bool (aget row "inferred_outliner_ops"))
       :db-sync/undo-redo (str->kw (aget row "undo_redo"))
       :tx (sqlite-util/read-transit-str (aget row "normalized_tx_data"))
       :reversed-tx (sqlite-util/read-transit-str (aget row "reversed_tx_data"))})))

(defn upsert-local-tx-entry!
  [repo {:keys [tx-id created-at pending? failed? outliner-op undo-redo
                forward-outliner-ops inverse-outliner-ops inferred-outliner-ops?
                normalized-tx-data reversed-tx-data]
         :or {pending? true failed? false}}]
  {:pre [(some? tx-id)]}
  (let [store (sqlite-store-or-throw repo)]
    (assert (some? store) repo)
    (let [tx-id-str (str tx-id)
          existing (sqlite-row store
                               "select pending, created_at from client_ops where kind = 'tx' and tx_id = ?"
                               [tx-id-str])
          should-inc-pending? (not= 1 (some-> existing (aget "pending")))
          forward-outliner-ops' (or (normalize-op-entries forward-outliner-ops) [])
          inverse-outliner-ops' (or (normalize-op-entries inverse-outliner-ops) [])
          created-at' (or (some-> existing (aget "created_at"))
                          created-at
                          (.now js/Date))]
      (sqlite-run! store
                   (str "insert into client_ops ("
                        "kind, created_at, tx_id, pending, failed, outliner_op, undo_redo, "
                        "forward_outliner_ops, inverse_outliner_ops, inferred_outliner_ops, "
                        "normalized_tx_data, reversed_tx_data"
                        ") values ('tx', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        " on conflict(tx_id) do update set "
                        "created_at = excluded.created_at,"
                        "pending = excluded.pending,"
                        "failed = excluded.failed,"
                        "outliner_op = excluded.outliner_op,"
                        "undo_redo = excluded.undo_redo,"
                        "forward_outliner_ops = excluded.forward_outliner_ops,"
                        "inverse_outliner_ops = excluded.inverse_outliner_ops,"
                        "inferred_outliner_ops = excluded.inferred_outliner_ops,"
                        "normalized_tx_data = excluded.normalized_tx_data,"
                        "reversed_tx_data = excluded.reversed_tx_data")
                   [created-at'
                    tx-id-str
                    (bool->int pending?)
                    (bool->int failed?)
                    (kw->str outliner-op)
                    (kw->str undo-redo)
                    (sqlite-util/write-transit-str forward-outliner-ops')
                    (sqlite-util/write-transit-str inverse-outliner-ops')
                    (bool->int inferred-outliner-ops?)
                    (sqlite-util/write-transit-str (or normalized-tx-data []))
                    (sqlite-util/write-transit-str (or reversed-tx-data []))])
      {:tx-id tx-id
       :created-at created-at'
       :should-inc-pending? should-inc-pending?})))

(defn get-local-tx-entry
  [repo tx-id]
  (when (uuid? tx-id)
    (when-let [store (sqlite-store-or-throw repo)]
      (some-> (sqlite-row store
                          (str "select tx_id, outliner_op, undo_redo, "
                               "forward_outliner_ops, inverse_outliner_ops, inferred_outliner_ops, "
                               "normalized_tx_data, reversed_tx_data "
                               "from client_ops where kind = 'tx' and tx_id = ? limit 1")
                          [(str tx-id)])
              (row->pending-local-tx)))))

(defn get-pending-local-txs
  [repo & {:keys [limit]}]
  (when-let [store (sqlite-store-or-throw repo)]
    (let [sql (str "select tx_id, outliner_op, undo_redo, "
                   "forward_outliner_ops, inverse_outliner_ops, inferred_outliner_ops, "
                   "normalized_tx_data, reversed_tx_data "
                   "from client_ops where kind = 'tx' and pending = 1 "
                   "order by created_at asc, id asc"
                   (when (number? limit) " limit ?"))
          rows (sqlite-rows store sql (if (number? limit) [limit] []))]
      (->> rows
           (keep row->pending-local-tx)
           vec))))

(defn read-repair-local-batch
  "Capture the next repair journal watermark and its ordered pending row IDs in
  one read transaction. Membership uses id <= high-water; bodies are decoded
  in bounded pages without changing the official created_at ASC, id ASC order."
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-with-tx!
     store
     (fn [tx]
       (let [observation (repair-local-observation-in-tx tx)
             high-water (:journal-high-water observation)
             rows (sqlite-rows
                   tx
                   (str "select id from client_ops "
                        "where kind = 'tx' and pending = 1 and id <= ? "
                        "order by created_at asc, id asc")
                   [high-water])]
         {:observation observation
          :pending-ids (mapv #(aget % "id") rows)})))))

(defn read-repair-local-page
  "Decode one fixed ordered membership page captured by
  read-repair-local-batch. Missing or malformed rows fail closed."
  [repo ids]
  (let [ids (vec ids)]
    (if (seq ids)
      (if-let [store (sqlite-store-or-throw repo)]
        (sqlite-with-tx!
         store
         (fn [tx]
           (let [placeholders (string/join "," (repeat (count ids) "?"))
                 rows (sqlite-rows
                       tx
                       (str "select id, tx_id, outliner_op, undo_redo, "
                            "forward_outliner_ops, inverse_outliner_ops, inferred_outliner_ops, "
                            "normalized_tx_data, reversed_tx_data "
                            "from client_ops where id in (" placeholders ")")
                       ids)
                 row-by-id (into {} (map (fn [row] [(aget row "id") row])) rows)
                 pending-txs (mapv (fn [id]
                                     (some-> (get row-by-id id)
                                             row->pending-local-tx))
                                   ids)]
             (when (some nil? pending-txs)
               (throw (ex-info "Repair journal membership changed during page read"
                               {:type :db-sync/repair-journal-membership-changed})))
             pending-txs)))
        (throw (ex-info "Repair journal disappeared during page read"
                        {:type :db-sync/repair-journal-membership-changed
                         :repo repo})))
      [])))

(defn add-sync-conflicts!
  [repo conflicts]
  (when-let [store (sqlite-store-or-throw repo)]
    (let [now (.now js/Date)]
      (doseq [{:keys [block-uuid attr value remote-t]} conflicts]
        (when (and (uuid? block-uuid)
                   (qualified-keyword? attr)
                   (string? value))
          (let [attr-str (qualified-kw->str attr)]
            (sqlite-run! store
                         "delete from sync_conflicts where block_uuid = ? and attr = ?"
                         [(str block-uuid) attr-str])
            (when-not (string/blank? value)
              (sqlite-run! store
                           (str "insert into sync_conflicts "
                                "(block_uuid, attr, value, remote_t, created_at) "
                                "values (?, ?, ?, ?, ?) "
                                "on conflict(block_uuid, attr, value) do update set "
                                "remote_t = excluded.remote_t, "
                                "created_at = excluded.created_at")
                           [(str block-uuid)
                            attr-str
                            value
                            remote-t
                            now]))))))))

(defn- sync-conflict-row->map
  [row]
  {:id (aget row "id")
   :block-uuid (parse-uuid-str (aget row "block_uuid"))
   :attr (str->qualified-kw (aget row "attr"))
   :value (aget row "value")
   :remote-t (aget row "remote_t")
   :created-at (aget row "created_at")})

(defn get-all-sync-conflicts
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (->> (sqlite-rows store
                      (str "select id, block_uuid, attr, value, remote_t, created_at "
                           "from sync_conflicts order by created_at desc, id desc")
                      [])
         (map sync-conflict-row->map)
         (group-by :block-uuid))))

(defn get-sync-conflicts
  [repo block-uuid]
  (when (uuid? block-uuid)
    (when-let [store (sqlite-store-or-throw repo)]
      (->> (sqlite-rows store
                        (str "select id, block_uuid, attr, value, remote_t, created_at "
                             "from sync_conflicts where block_uuid = ? "
                             "order by created_at desc, id desc")
                        [(str block-uuid)])
           (mapv sync-conflict-row->map)))))

(defn clear-sync-conflicts!
  [repo block-uuid]
  (when (uuid? block-uuid)
    (when-let [store (sqlite-store-or-throw repo)]
      (sqlite-run! store
                   "delete from sync_conflicts where block_uuid = ?"
                   [(str block-uuid)]))))

(defn- pending-tx-id?
  [store tx-id]
  (let [row (sqlite-row store
                        "select pending from client_ops where kind = 'tx' and tx_id = ?"
                        [(str tx-id)])]
    (= 1 (some-> row (aget "pending")))))

(defn- valid-client-tx-upload-state?
  [format-version kind source-digest state]
  (and (= 1 format-version)
       (contains? #{:single-wire-v1 :staged-large-upload-v1} kind)
       (string? source-digest)
       (map? state)
       (= format-version (:format-version state))
       (= kind (:kind state))
       (= source-digest (:source-digest state))
       (case kind
         :single-wire-v1
         (and (keyword? (:outliner-op state))
              (map? (:wire-entry state)))

         :staged-large-upload-v1
         (and (keyword? (:outliner-op state))
              (string? (:session-id state))
              (vector? (:boundaries state))
              (integer? (:source-next-index state))
              (integer? (:chunk-seq state)))

         false)))

(defn- client-tx-upload-state-row->entry
  [row]
  (let [logical-tx-id (parse-uuid-str (aget row "logical_tx_id"))
        format-version (aget row "format_version")
        kind (str->kw (aget row "kind"))
        source-digest (aget row "source_digest")
        state (some-> (aget row "state") sqlite-util/read-transit-str)]
    (when-not (and logical-tx-id
                   (valid-client-tx-upload-state?
                    format-version kind source-digest state))
      (throw (ex-info "Invalid client transaction upload state"
                      {:type :db-sync/invalid-client-tx-upload-state
                       :logical-tx-id logical-tx-id})))
    [logical-tx-id state]))

(defn get-client-tx-upload-state
  [repo logical-tx-id]
  (when (uuid? logical-tx-id)
    (when-let [store (sqlite-store-or-throw repo)]
      (when-let [row (sqlite-row store
                                 (str "select logical_tx_id, format_version, kind, source_digest, state "
                                      "from client_tx_upload_state where logical_tx_id = ?")
                                 [(str logical-tx-id)])]
        (second (client-tx-upload-state-row->entry row))))))

(defn get-client-tx-upload-states
  [repo logical-tx-ids]
  (when-let [store (sqlite-store-or-throw repo)]
    (let [logical-tx-ids (->> logical-tx-ids (filter uuid?) distinct vec)]
      (if (seq logical-tx-ids)
        (->> (sqlite-rows
              store
              (str "select logical_tx_id, format_version, kind, source_digest, state "
                   "from client_tx_upload_state where logical_tx_id in ("
                   (string/join "," (repeat (count logical-tx-ids) "?")) ")")
              (mapv str logical-tx-ids))
             (map client-tx-upload-state-row->entry)
             (into {}))
        {}))))

(defn put-client-tx-upload-state!
  [repo logical-tx-id state]
  {:pre [(uuid? logical-tx-id)
         (= 1 (:format-version state))
         (contains? #{:single-wire-v1 :staged-large-upload-v1}
                    (:kind state))
         (string? (:source-digest state))]}
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-run! store
                 (str "insert into client_tx_upload_state "
                      "(logical_tx_id, format_version, kind, source_digest, state, updated_at) "
                      "values (?, ?, ?, ?, ?, ?) "
                      "on conflict(logical_tx_id) do update set "
                      "format_version = excluded.format_version, "
                      "kind = excluded.kind, "
                      "source_digest = excluded.source_digest, "
                      "state = excluded.state, "
                      "updated_at = excluded.updated_at")
                 [(str logical-tx-id)
                  (:format-version state)
                  (kw->str (:kind state))
                  (:source-digest state)
                  (sqlite-util/write-transit-str state)
                  (.now js/Date)])
    state))

(defn- finish-pending-txs!
  [repo tx-ids failed?]
  (when-let [store (sqlite-store-or-throw repo)]
    (let [tx-ids (->> tx-ids (filter uuid?) distinct vec)]
      (sqlite-with-tx!
       store
       (fn [tx]
         (let [pending-to-remove (->> tx-ids
                                      (filter (fn [tx-id]
                                                (pending-tx-id? tx tx-id)))
                                      count)]
           (doseq [tx-id tx-ids]
             (sqlite-run! tx
                          (if failed?
                            "update client_ops set pending = 0, failed = 1 where kind = 'tx' and tx_id = ?"
                            "update client_ops set pending = 0 where kind = 'tx' and tx_id = ?")
                          [(str tx-id)])
             (sqlite-run! tx
                          "delete from client_tx_upload_state where logical_tx_id = ?"
                          [(str tx-id)]))
           pending-to-remove))))))

(defn mark-pending-txs-false!
  [repo tx-ids]
  (finish-pending-txs! repo tx-ids false))

(defn mark-failed-txs!
  [repo tx-ids]
  (finish-pending-txs! repo tx-ids true))

(defn history-action-ops-by-tx-id
  [repo tx-id]
  (when-let [entry (get-local-tx-entry repo tx-id)]
    {:db-sync/forward-outliner-ops (some-> (:forward-outliner-ops entry) seq vec)
     :db-sync/inverse-outliner-ops (some-> (:inverse-outliner-ops entry) seq vec)}))

(defn- local-asset-op-map
  [op-type t value]
  (let [asset-uuid (:block-uuid value)]
    (case op-type
      :update-asset {:block/uuid asset-uuid
                     :update-asset [:update-asset t value]}
      :remove-asset {:block/uuid asset-uuid
                     :remove-asset [:remove-asset t value]}
      nil)))

(defn- sqlite-asset-op-by-uuid
  [store block-uuid]
  (when-let [row (sqlite-row store
                             (str "select asset_uuid, asset_op, asset_t, asset_value "
                                  "from client_ops where kind = 'asset' and asset_uuid = ? limit 1")
                             [(str block-uuid)])]
    (let [op-type (str->kw (aget row "asset_op"))
          t (aget row "asset_t")
          value (or (some-> (aget row "asset_value") sqlite-util/read-transit-str)
                    {:block-uuid block-uuid})]
      (local-asset-op-map op-type t value))))

(defn- sqlite-upsert-asset-op!
  [store op-type t value]
  (let [block-uuid (:block-uuid value)]
    (sqlite-with-tx!
     store
     (fn [tx]
       (sqlite-run! tx "delete from client_ops where kind = 'asset' and asset_uuid = ?"
                    [(str block-uuid)])
       (sqlite-run! tx
                    (str "insert into client_ops ("
                         "kind, created_at, asset_uuid, asset_op, asset_t, asset_value"
                         ") values ('asset', ?, ?, ?, ?, ?)")
                    [(.now js/Date)
                     (str block-uuid)
                     (kw->str op-type)
                     t
                     (sqlite-util/write-transit-str value)])))))

;;; asset ops
(defn add-asset-ops
  [repo asset-ops]
  (let [store (sqlite-store-or-throw repo)
        ops (ops-coercer asset-ops)]
    (assert (some? store) repo)
    (letfn [(already-removed? [remove-op t]
              (some-> remove-op second (> t)))
            (update-after-remove? [update-op t]
              (some-> update-op second (> t)))]
      (doseq [op ops]
        (let [[op-type t value] op
              {:keys [block-uuid]} value
              existing-op (sqlite-asset-op-by-uuid store block-uuid)]
          (case op-type
            :update-asset
            (let [remove-asset-op (:remove-asset existing-op)]
              (when-not (already-removed? remove-asset-op t)
                (sqlite-upsert-asset-op! store :update-asset t value)))

            :remove-asset
            (let [update-asset-op (:update-asset existing-op)]
              (when-not (update-after-remove? update-asset-op t)
                (sqlite-upsert-asset-op! store :remove-asset t value)))

            nil)))
      nil)))

(defn add-all-exists-asset-as-ops
  [repo]
  (let [conn (worker-state/get-datascript-conn repo)
        _ (assert (some? conn))
        asset-block-uuids (->> (d/datoms @conn :avet :logseq.property.asset/type)
                               (keep (fn [d]
                                       (:block/uuid (d/entity @conn (:e d)))))
                               distinct)
        ops (map (fn [block-uuid] [:update-asset 1 {:block-uuid block-uuid}])
                 asset-block-uuids)]
    (add-asset-ops repo ops)))

(defn get-unpushed-asset-ops-count
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (or (some-> (sqlite-row store
                            "select count(*) as c from client_ops where kind = 'asset'"
                            [])
                (aget "c"))
        0)))

(defn get-all-asset-ops
  [repo]
  (when-let [store (sqlite-store-or-throw repo)]
    (->> (sqlite-rows store
                      "select asset_op, asset_t, asset_value from client_ops where kind = 'asset' order by id asc"
                      [])
         (keep (fn [row]
                 (let [op-type (str->kw (aget row "asset_op"))
                       t (aget row "asset_t")
                       value (some-> (aget row "asset_value") sqlite-util/read-transit-str)]
                   (when (and op-type (map? value) (:block-uuid value))
                     (local-asset-op-map op-type t value)))))
         vec)))

(defn remove-asset-op
  [repo asset-uuid]
  (when-let [store (sqlite-store-or-throw repo)]
    (sqlite-run! store
                 "delete from client_ops where kind = 'asset' and asset_uuid = ?"
                 [(str asset-uuid)])))

(defn cleanup-finished-history-ops!
  [repo protected-tx-ids]
  (if-let [store (sqlite-store-or-throw repo)]
    (let [protected-tx-ids (set protected-tx-ids)
          tx-id-rows (sqlite-rows store
                                  (str "select tx_id from client_ops "
                                       "where kind = 'tx' and pending = 0 and tx_id is not null")
                                  [])
          removable-tx-ids (->> tx-id-rows
                                (keep (fn [row]
                                        (let [tx-id (parse-uuid-str (aget row "tx_id"))]
                                          (when (and (uuid? tx-id)
                                                     (not (contains? protected-tx-ids tx-id)))
                                            tx-id))))
                                vec)]
      (when (seq removable-tx-ids)
        (doseq [tx-id removable-tx-ids]
          (sqlite-run! store
                       "delete from client_ops where kind = 'tx' and tx_id = ?"
                       [(str tx-id)])))
      (count removable-tx-ids))
    0))
