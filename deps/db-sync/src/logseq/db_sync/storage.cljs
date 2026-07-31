(ns logseq.db-sync.storage
  (:require
   [cljs-bean.core :as bean]
   [clojure.string :as string]
   [datascript.core :as d]
   [datascript.storage :refer [IStorage]]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db-sync.common :as common]
   [logseq.db.common.normalize :as db-normalize]
   [logseq.db.common.sqlite :as common-sqlite]
   [logseq.db.frontend.schema :as db-schema]))

(def ^:private tx-log-outliner-op-migration-sql
  "alter table tx_log add column outliner_op TEXT")

(def ^:private tx-log-tx-id-migration-sql
  "alter table tx_log add column tx_id TEXT")

(defn- duplicate-column-error?
  [error column-name]
  (let [message (-> (or (ex-message error) (some-> error .-message) (str error))
                    string/lower-case)]
    (and (string/includes? message "duplicate column")
         (string/includes? message (string/lower-case column-name)))))

(defn- ensure-tx-log-outliner-op-column!
  [sql]
  (try
    (common/sql-exec sql tx-log-outliner-op-migration-sql)
    (catch :default error
      (when-not (duplicate-column-error? error "outliner_op")
        (throw error)))))

(defn- ensure-tx-log-tx-id-column!
  [sql]
  (try
    (common/sql-exec sql tx-log-tx-id-migration-sql)
    (catch :default error
      (when-not (duplicate-column-error? error "tx_id")
        (throw error)))))

;; TODO: GC kvs table

(defn init-schema! [sql]
  (common/sql-exec sql "create table if not exists kvs (addr INTEGER primary key, content TEXT, addresses JSON)")
  (common/sql-exec sql
                   "create table if not exists snapshot_kvs_staging (addr INTEGER primary key, content TEXT, addresses JSON)")
  (common/sql-exec
   sql
   (str "create table if not exists snapshot_downloads ("
        "download_id TEXT primary key,"
        "t INTEGER not null,"
        "checksum TEXT,"
        "row_count INTEGER not null,"
        "created_at INTEGER not null"
        ");"))
  (common/sql-exec
   sql
   (str "create table if not exists snapshot_kvs_exports ("
        "download_id TEXT not null,"
        "addr INTEGER not null,"
        "content TEXT,"
        "addresses JSON,"
        "primary key(download_id, addr)"
        ");"))
  ;; The composite primary key already provides the lookup/order index.
  (common/sql-exec sql
                   "drop index if exists snapshot_kvs_exports_download_id")
  (common/sql-exec sql
                   (str "create table if not exists tx_log ("
                        "t INTEGER primary key,"
                        "tx TEXT not null,"
                        "created_at INTEGER"
                        ");"))
  (ensure-tx-log-outliner-op-column! sql)
  (ensure-tx-log-tx-id-column! sql)
  (common/sql-exec
   sql
   (str "create table if not exists applied_client_txs ("
        "identity TEXT primary key,"
        "payload_digest TEXT not null,"
        "created_at INTEGER not null"
        ");"))
  (common/sql-exec
   sql
   (str "create unique index if not exists applied_client_tx_identity "
        "on applied_client_txs(identity)"))
  (common/sql-exec
   sql
   (str "create table if not exists client_tx_uploads ("
        "logical_tx_id TEXT primary key,"
        "session_id TEXT not null,"
        "outliner_op TEXT,"
        "next_index INTEGER not null,"
        "status TEXT not null,"
        "final_index INTEGER,"
        "final_wire_digest TEXT,"
        "completed_digest TEXT,"
        "created_at INTEGER not null,"
        "updated_at INTEGER not null"
        ");"))
  (common/sql-exec
   sql
   (str "create table if not exists client_tx_upload_chunks ("
        "session_id TEXT not null,"
        "chunk_index INTEGER not null,"
        "tx TEXT not null,"
        "wire_digest TEXT not null,"
        "datom_count INTEGER not null,"
        "created_at INTEGER not null,"
        "primary key(session_id, chunk_index)"
        ");"))
  (common/sql-exec
   sql
   (str "create index if not exists client_tx_upload_chunks_session "
        "on client_tx_upload_chunks(session_id, chunk_index)"))
  (common/sql-exec sql
                   (str "create table if not exists sync_meta ("
                        "key TEXT primary key,"
                        "value TEXT"
                        ");")))

(def ^:private required-schema-columns
  {"kvs" #{"addr" "content" "addresses"}
   "snapshot_kvs_staging" #{"addr" "content" "addresses"}
   "snapshot_downloads" #{"download_id" "t" "checksum" "row_count" "created_at"}
   "snapshot_kvs_exports" #{"download_id" "addr" "content" "addresses"}
   "tx_log" #{"t" "tx" "created_at" "outliner_op" "tx_id"}
   "applied_client_txs" #{"identity" "payload_digest" "created_at"}
   "client_tx_uploads" #{"logical_tx_id" "session_id" "outliner_op"
                         "next_index" "status" "final_index"
                         "final_wire_digest" "completed_digest"
                         "created_at" "updated_at"}
   "client_tx_upload_chunks" #{"session_id" "chunk_index" "tx"
                                "wire_digest" "datom_count" "created_at"}
   "sync_meta" #{"key" "value"}})

(declare select-one)

(defn schema-ready?
  "Return true only when every table/column/index needed by the sync handler is
  queryable. Used after a rejected idempotent DDL statement; table existence
  alone is not sufficient evidence that an online migration completed."
  [sql]
  (try
    (and
     (every?
      (fn [[table required-columns]]
        (let [rows (common/get-sql-rows
                    (common/sql-exec
                     sql
                     (str "select name from pragma_table_info('" table "')")))
              actual-columns (into #{} (map #(aget % "name")) rows)]
          (every? actual-columns required-columns)))
      required-schema-columns)
     (boolean
     (select-one
       sql
       (str "select 1 as present from sqlite_master "
            "where type = 'index' and name = ? limit 1")
       "applied_client_tx_identity"))
     (boolean
      (select-one
       sql
       (str "select 1 as present from sqlite_master "
            "where type = 'index' and name = ? limit 1")
       "client_tx_upload_chunks_session")))
    (catch :default _
      false)))

(defn- select-one [sql sql-str & args]
  (first (common/get-sql-rows (apply common/sql-exec sql sql-str args))))

(defn get-meta [sql k]
  (when-let [row (select-one sql "select value from sync_meta where key = ?" (name k))]
    (aget row "value")))

(defn set-meta! [sql k v]
  (common/sql-exec sql
                   (str "insert into sync_meta (key, value) values (?, ?)"
                        " on conflict(key) do update set value = excluded.value")
                   (name k)
                   (str v)))

(defn delete-meta! [sql k]
  (common/sql-exec sql
                   "delete from sync_meta where key = ?"
                   (name k)))

(defn get-checksum [sql]
  (get-meta sql :checksum))

(defn set-checksum! [sql checksum]
  (set-meta! sql :checksum checksum))

(defn get-server-checksum [sql]
  (get-meta sql :server-checksum-v2))

(defn get-server-checksum-t [sql]
  (when-let [value (get-meta sql :server-checksum-v2-t)]
    (js/parseInt value 10)))

(defn set-server-checksum! [sql checksum t]
  (if (string? checksum)
    (do
      (set-meta! sql :server-checksum-v2 checksum)
      (set-meta! sql :server-checksum-v2-t t))
    (do
      (delete-meta! sql :server-checksum-v2)
      (delete-meta! sql :server-checksum-v2-t))))

(def ^:private checksum-metadata-contract-version
  "server-db-v2+legacy-v1-verified-v2")

(def ^:private absent-checksum-marker "")

(defn- checksum->metadata-marker
  [checksum]
  (or checksum absent-checksum-marker))

(defn checksum-metadata-verified?
  [sql t]
  (let [checksum (get-checksum sql)
        server-checksum (get-server-checksum sql)
        server-checksum-t (get-server-checksum-t sql)]
    (and (= checksum-metadata-contract-version
            (get-meta sql :checksum-metadata-contract-version))
         (= t
            (some-> (get-meta sql :checksum-metadata-contract-t)
                    (js/parseInt 10)))
         (= (checksum->metadata-marker checksum)
            (get-meta sql :checksum-metadata-contract-checksum))
         (= (checksum->metadata-marker server-checksum)
            (get-meta sql
                      :checksum-metadata-contract-server-checksum-v2))
         (or (and (zero? t) (nil? checksum))
             (sync-checksum/valid-checksum? checksum))
         (or (and (nil? server-checksum)
                  (nil? server-checksum-t))
             (and (sync-checksum/valid-checksum? server-checksum)
                  (= t server-checksum-t))))))

(defn mark-checksum-metadata-verified!
  [sql t]
  (set-meta! sql :checksum-metadata-contract-version
             checksum-metadata-contract-version)
  (set-meta! sql :checksum-metadata-contract-t t)
  (set-meta! sql :checksum-metadata-contract-checksum
             (checksum->metadata-marker (get-checksum sql)))
  (set-meta! sql :checksum-metadata-contract-server-checksum-v2
             (checksum->metadata-marker (get-server-checksum sql))))

(defn get-t [sql]
  (let [value (get-meta sql :t)]
    (if (string? value)
      (js/parseInt value 10)
      0)))

(defn set-t! [sql t]
  (set-meta! sql :t t))

(def ^:dynamic *in-sql-transaction?* false)
(defonce ^:private sql->transaction-sync (js/WeakMap.))

(defn register-transaction-sync!
  [sql transaction-sync-f]
  (when (and sql (fn? transaction-sync-f))
    (.set sql->transaction-sync sql transaction-sync-f))
  sql)

(defn with-sql-transaction!
  [sql f]
  (if *in-sql-transaction?*
    (f)
    (let [f' (fn []
               (binding [*in-sql-transaction?* true]
                 (f)))]
      (if-let [transaction-sync (.get sql->transaction-sync sql)]
        (transaction-sync f')
        (if-let [db (aget sql "_db")]
          (let [transaction (.-transaction db)]
            (if (fn? transaction)
              (let [tx-fn (.call transaction db f')]
                (tx-fn))
              (f')))
          (f'))))))

(defn set-initial-checksum! [sql checksum]
  (with-sql-transaction!
    sql
    (fn []
      (let [existing-checksum (get-checksum sql)
            current-t (get-t sql)]
        (cond
          (and (some? existing-checksum)
               (not= existing-checksum checksum))
          (throw (ex-info "Cannot overwrite existing checksum with snapshot checksum"
                          {:type :db-sync/stale-snapshot-checksum
                           :existing-checksum existing-checksum
                           :snapshot-checksum checksum}))

          (and (nil? existing-checksum)
               (pos? current-t))
          (throw (ex-info "Cannot initialize checksum after tx history advanced"
                          {:type :db-sync/snapshot-checksum-after-tx-log
                           :t current-t
                           :snapshot-checksum checksum}))

          (nil? existing-checksum)
          (set-checksum! sql checksum))))))

(defn- outliner-op->sql [outliner-op]
  (cond
    (keyword? outliner-op) (name outliner-op)
    (string? outliner-op) outliner-op
    :else nil))

(defn- sql->outliner-op [value]
  (when (string? value)
    (keyword value)))

(defn append-tx!
  ([sql t tx-str created-at outliner-op]
   (append-tx! sql t tx-str created-at outliner-op nil))
  ([sql t tx-str created-at outliner-op tx-id]
   (common/sql-exec
    sql
    (str "insert into tx_log (t, tx, created_at, outliner_op, tx_id) "
         "values (?, ?, ?, ?, ?)"
         " on conflict(t) do update set tx = excluded.tx, "
         "created_at = excluded.created_at, outliner_op = excluded.outliner_op, "
         "tx_id = excluded.tx_id")
    t
    tx-str
    created-at
    (outliner-op->sql outliner-op)
    (some-> tx-id str))))

(defn tx-id-applied?
  [sql tx-id]
  (boolean
   (and tx-id
        (select-one sql
                    "select 1 as applied from tx_log where tx_id = ? limit 1"
                    (str tx-id)))))

(defn set-tx-id-for-t!
  [sql t tx-id]
  (when tx-id
    (common/sql-exec sql
                     "update tx_log set tx_id = ? where t = ?"
                     (str tx-id)
                     t)))

(defn applied-client-tx-records
  "Fetch all requested idempotency identities with one indexed query."
  [sql identities]
  (let [identities (->> identities (filter string?) distinct vec)]
    (if (seq identities)
      (let [placeholders (string/join "," (repeat (count identities) "?"))
            rows (common/get-sql-rows
                  (apply common/sql-exec
                         sql
                         (str "select identity, payload_digest "
                              "from applied_client_txs where identity in ("
                              placeholders ")")
                         identities))]
        (into {}
              (map (fn [row]
                     [(aget row "identity") (aget row "payload_digest")]))
              rows))
      {})))

(defn record-applied-client-tx!
  [sql identity payload-digest]
  (when identity
    (common/sql-exec
     sql
     (str "insert into applied_client_txs "
          "(identity, payload_digest, created_at) values (?, ?, ?)")
     identity
     payload-digest
     (common/now-ms))))

(defn client-tx-upload
  [sql logical-tx-id]
  (when-let [row (select-one
                  sql
                  (str "select logical_tx_id, session_id, outliner_op, "
                       "next_index, status, final_index, final_wire_digest, "
                       "completed_digest, created_at, updated_at "
                       "from client_tx_uploads where logical_tx_id = ?")
                  (str logical-tx-id))]
    {:logical-tx-id (aget row "logical_tx_id")
     :session-id (aget row "session_id")
     :outliner-op (some-> (aget row "outliner_op") keyword)
     :next-index (aget row "next_index")
     :status (aget row "status")
     :final-index (aget row "final_index")
     :final-wire-digest (aget row "final_wire_digest")
     :completed-digest (aget row "completed_digest")
     :created-at (aget row "created_at")
     :updated-at (aget row "updated_at")}))

(defn start-client-tx-upload!
  [sql logical-tx-id session-id outliner-op]
  (let [now (common/now-ms)]
    (common/sql-exec
     sql
     (str "insert into client_tx_uploads "
          "(logical_tx_id, session_id, outliner_op, next_index, status, "
          "created_at, updated_at) values (?, ?, ?, 0, 'active', ?, ?)")
     (str logical-tx-id)
     session-id
     (some-> outliner-op name)
     now
     now)))

(defn replace-client-tx-upload!
  [sql logical-tx-id old-session-id new-session-id outliner-op]
  (common/sql-exec sql
                   "delete from client_tx_upload_chunks where session_id = ?"
                   old-session-id)
  (let [now (common/now-ms)]
    (common/sql-exec
     sql
     (str "update client_tx_uploads set session_id = ?, outliner_op = ?, "
          "next_index = 0, status = 'active', final_index = null, "
          "final_wire_digest = null, completed_digest = null, "
          "created_at = ?, updated_at = ? where logical_tx_id = ?")
     new-session-id
     (some-> outliner-op name)
     now
     now
     (str logical-tx-id))))

(defn client-tx-upload-chunk
  [sql session-id chunk-index]
  (when-let [row (select-one
                  sql
                  (str "select session_id, chunk_index, tx, wire_digest, "
                       "datom_count, created_at from client_tx_upload_chunks "
                       "where session_id = ? and chunk_index = ?")
                  session-id
                  chunk-index)]
    {:session-id (aget row "session_id")
     :chunk-index (aget row "chunk_index")
     :tx (aget row "tx")
     :wire-digest (aget row "wire_digest")
     :datom-count (aget row "datom_count")
     :created-at (aget row "created_at")}))

(defn append-client-tx-upload-chunk!
  [sql session-id chunk-index tx wire-digest datom-count]
  (let [now (common/now-ms)]
    (common/sql-exec
     sql
     (str "insert into client_tx_upload_chunks "
          "(session_id, chunk_index, tx, wire_digest, datom_count, created_at) "
          "values (?, ?, ?, ?, ?, ?)")
     session-id chunk-index tx wire-digest datom-count now)
    (common/sql-exec
     sql
     (str "update client_tx_uploads set next_index = ?, updated_at = ? "
          "where session_id = ? and status = 'active'")
     (+ chunk-index datom-count) now session-id)))

(defn client-tx-upload-chunks
  [sql session-id]
  (let [rows (common/get-sql-rows
              (common/sql-exec
               sql
               (str "select chunk_index, tx, wire_digest, datom_count "
                    "from client_tx_upload_chunks where session_id = ? "
                    "order by chunk_index asc")
               session-id))]
    (mapv (fn [row]
            {:chunk-index (aget row "chunk_index")
             :tx (aget row "tx")
             :wire-digest (aget row "wire_digest")
             :datom-count (aget row "datom_count")})
          rows)))

(defn complete-client-tx-upload!
  [sql logical-tx-id session-id final-index final-wire-digest completed-digest]
  (common/sql-exec
   sql
   (str "update client_tx_uploads set status = 'completed', "
        "final_index = ?, final_wire_digest = ?, completed_digest = ?, "
        "updated_at = ? where logical_tx_id = ? and session_id = ? "
        "and status = 'active'")
   final-index final-wire-digest completed-digest (common/now-ms)
   (str logical-tx-id) session-id)
  ;; Completion consolidates an arbitrarily large upload into one bounded row.
  (common/sql-exec sql
                   "delete from client_tx_upload_chunks where session_id = ?"
                   session-id))

(defn fetch-tx-since [sql since-t]
  (let [rows (common/get-sql-rows
              (common/sql-exec sql
                               "select t, tx, outliner_op from tx_log where t > ? order by t asc"
                               since-t))]
    (mapv (fn [row]
            {:t (aget row "t")
             :tx (aget row "tx")
             :outliner-op (sql->outliner-op (aget row "outliner_op"))})
          rows)))

(defn- upsert-addr-content! [sql data]
  (doseq [item data]
    (common/sql-exec sql
                     (str "insert into kvs (addr, content, addresses) values (?, ?, ?)"
                          " on conflict(addr) do update set content = excluded.content, addresses = excluded.addresses")
                     (aget item "addr")
                     (aget item "content")
                     (aget item "addresses"))))

(defn- restore-data-from-addr [sql addr]
  (when-let [row (select-one sql "select content, addresses from kvs where addr = ?" addr)]
    (let [{:keys [content addresses]} (bean/->clj row)
          addresses (when addresses (js/JSON.parse addresses))
          data (common/read-transit content)]
      (if (and addresses (map? data))
        (assoc data :addresses addresses)
        data))))

(defn new-sqlite-storage [sql]
  (reify IStorage
    (-store [_ addr+data-seq _delete-addrs]
      (let [data (map
                  (fn [[addr data]]
                    (let [data' (if (map? data) (dissoc data :addresses) data)
                          addresses (when (map? data)
                                      (when-let [addresses (:addresses data)]
                                        (js/JSON.stringify (bean/->js addresses))))]
                      #js {"addr" addr
                           "content" (common/write-transit data')
                           "addresses" addresses}))
                  addr+data-seq)]
        (upsert-addr-content! sql data)))
    (-restore [_ addr]
      (restore-data-from-addr sql addr))))

(defn- append-tx-for-tx-report
  [sql {:keys [db-after db-before tx-data tx-meta] :as tx-report}]
  (when-not (or (:db-sync/skip-tx-log? tx-meta)
                (empty? tx-data))
    (let [created-at (common/now-ms)
          normalized-data (->> tx-data
                               (db-normalize/normalize-tx-data db-after db-before)
                               vec)
          tx-str (common/write-transit normalized-data)]
      (with-sql-transaction!
        sql
        (fn []
          (let [prev-t (get-t sql)
                new-t (inc prev-t)
                prev-checksum (get-checksum sql)
                verified-checksum-metadata?
                (or (checksum-metadata-verified? sql prev-t)
                    (and (zero? prev-t)
                         (nil? prev-checksum)))]
            (append-tx! sql new-t tx-str created-at
                        (:outliner-op tx-meta) (:tx-id tx-meta))
            (set-t! sql new-t)
            (when-not (:db-sync/skip-checksum-update? tx-meta)
              (let [checksum (sync-checksum/update-checksum
                              prev-checksum
                              (assoc tx-report :tx-data normalized-data))
                    prev-server-checksum (get-server-checksum sql)
                    prev-server-checksum-t (get-server-checksum-t sql)]
                ;; Keep the historical checksum untouched for older clients.
                ;; A rollback through an older server can advance `t` without
                ;; maintaining the additive versioned metadata. Never extend a
                ;; stale checksum and then stamp it with the new cursor.
                (set-checksum! sql checksum)
                (when (or verified-checksum-metadata?
                          (string? prev-server-checksum))
                  (set-server-checksum!
                   sql
                   (if (and (string? prev-server-checksum)
                            (= prev-t prev-server-checksum-t))
                     ((if verified-checksum-metadata?
                        sync-checksum/update-verified-server-checksum
                        sync-checksum/update-server-checksum)
                      prev-server-checksum
                      (assoc tx-report :tx-data normalized-data))
                     (sync-checksum/recompute-server-checksum db-after))
                   new-t))
                (when verified-checksum-metadata?
                  (mark-checksum-metadata-verified! sql new-t))))))))))

(defn- listen-db-updates!
  [sql conn]
  (d/listen! conn ::listen-db-updates
             (fn [tx-report]
               (append-tx-for-tx-report sql tx-report))))

(defn open-conn
  [sql]
  (init-schema! sql)
  (let [storage (new-sqlite-storage sql)
        schema db-schema/schema
        conn (common-sqlite/get-storage-conn storage schema)]
    (listen-db-updates! sql conn)
    conn))
