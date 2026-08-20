(ns logseq.db-sync.storage
  (:require
   [cljs-bean.core :as bean]
   [clojure.string :as string]
   [datascript.core :as d]
   [datascript.storage :refer [IStorage]]
   [goog.crypt :as gcrypt]
   [goog.crypt.Sha256]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db-sync.common :as common]
   [logseq.db.common.normalize :as db-normalize]
   [logseq.db.common.sqlite :as common-sqlite]
   [logseq.db.frontend.schema :as db-schema]))

(def ^:private tx-log-outliner-op-migration-sql
  "alter table tx_log add column outliner_op TEXT")

(def ^:private tx-log-tx-id-migration-sql
  "alter table tx_log add column tx_id TEXT")

(def ^:private snapshot-download-frozen-migration-sql
  (str "alter table snapshot_download_generations "
       "add column frozen INTEGER not null default 1"))

(declare with-sql-transaction!)

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

(defn- ensure-snapshot-download-frozen-column!
  [sql]
  (try
    (common/sql-exec sql snapshot-download-frozen-migration-sql)
    (catch :default error
      (when-not (duplicate-column-error? error "frozen")
        (throw error)))))

(defn- reconcile-generationless-snapshot-downloads!
  "Adopt only complete eager exports created before the generation table was
  introduced. Incomplete reservations are deleted atomically so a rolling
  upgrade cannot return HTTP 200 with a body that later fails, or let orphan
  downloads consume the post-upgrade capacity limit."
  [sql]
  (with-sql-transaction!
   sql
   (fn []
     (common/sql-exec
      sql
      (str "insert into snapshot_download_generations "
           "(download_id, generation_key, legacy, lease_count, frozen) "
           "select d.download_id, 'pre-generation:' || d.download_id, "
           "0, 1, 1 from snapshot_downloads d "
           "left join snapshot_download_generations g "
           "on g.download_id = d.download_id "
           "where g.download_id is null and d.row_count = "
           "(select count(*) from snapshot_kvs_exports e "
           "where e.download_id = d.download_id)"))
     (common/sql-exec
      sql
      (str "delete from snapshot_kvs_exports where download_id in "
           "(select d.download_id from snapshot_downloads d "
           "left join snapshot_download_generations g "
           "on g.download_id = d.download_id "
           "where g.download_id is null)"))
     (common/sql-exec
      sql
      (str "delete from snapshot_downloads where download_id not in "
           "(select download_id from snapshot_download_generations)")))))

;; TODO: GC kvs table

(defn init-schema! [sql]
  (common/sql-exec sql "create table if not exists kvs (addr INTEGER primary key, content TEXT, addresses JSON)")
  ;; Bind an integrity proof to every raw live-KVS mutation without rescanning
  ;; a large graph on the healthy request path. SQLite updates this generation
  ;; in the same transaction as the KVS statement, so rollback restores both.
  (common/sql-exec
   sql
   (str "create table if not exists kvs_mutation_generations ("
        "scope TEXT primary key,"
        "generation INTEGER not null"
        ");"))
  (common/sql-exec
   sql
   (str "insert into kvs_mutation_generations (scope, generation) "
        "values ('live-kvs', 0) on conflict(scope) do nothing"))
  (doseq [[trigger event condition]
          [["kvs_mutation_generation_insert" "insert" nil]
           ["kvs_mutation_generation_update" "update"
            "old.content is not new.content or old.addresses is not new.addresses"]
           ;; Existing Durable Objects already have the update trigger above.
           ;; Add an idempotent migration trigger for address-only primary-key
           ;; moves instead of relying on CREATE TRIGGER IF NOT EXISTS to
           ;; replace it. A move that also changes payload is already covered
           ;; by the original trigger, so the generation advances once per row.
           ["kvs_mutation_generation_update_addr" "update"
            (str "old.addr is not new.addr "
                 "and old.content is new.content "
                 "and old.addresses is new.addresses")]
           ["kvs_mutation_generation_delete" "delete" nil]]]
    (common/sql-exec
     sql
     (str "create trigger if not exists " trigger " after " event
          " on kvs "
          (when condition (str "when " condition " "))
          "begin update kvs_mutation_generations "
          "set generation = generation + 1 where scope = 'live-kvs'; end")))
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
  (common/sql-exec
   sql
   (str "create table if not exists snapshot_download_generations ("
        "download_id TEXT primary key,"
        "generation_key TEXT not null,"
        "legacy INTEGER not null,"
        "lease_count INTEGER not null"
        ");"))
  (ensure-snapshot-download-frozen-column! sql)
  (reconcile-generationless-snapshot-downloads! sql)
  (common/sql-exec
   sql
   (str "create index if not exists snapshot_download_generation_lookup "
        "on snapshot_download_generations(legacy, generation_key)"))
  ;; A legacy v1 metadata response has no download identity in its stream URL.
  ;; Reserve the sealed live basis without copying it, then freeze every live
  ;; reservation in the same SQLite statement as the first KVS mutation. A
  ;; failed mutation therefore rolls back both the live write and its COW.
  (let [live-reservation
        (str "exists (select 1 from snapshot_download_generations "
             "where legacy = 1 and frozen = 0 limit 1)")]
    (doseq [[trigger event changed?]
            [["snapshot_download_cow_insert" "insert" nil]
             ["snapshot_download_cow_update" "update"
              (str "(old.addr is not new.addr "
                   "or old.content is not new.content "
                   "or old.addresses is not new.addresses)")]
             ["snapshot_download_cow_delete" "delete" nil]]]
      (common/sql-exec
       sql
       (str "create trigger if not exists " trigger " before " event
            " on kvs when "
            (when changed? (str changed? " and "))
            live-reservation " begin "
            "insert into snapshot_kvs_exports "
            "(download_id, addr, content, addresses) "
            "select g.download_id, k.addr, k.content, k.addresses "
            "from snapshot_download_generations g cross join kvs k "
            "where g.legacy = 1 and g.frozen = 0; "
            "update snapshot_download_generations set frozen = 1 "
            "where legacy = 1 and frozen = 0; end"))))
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
                        ");"))
  ;; A checksum proof must become stale whenever any protocol-critical
  ;; metadata changes, even when the live KVS root and server cursor do not.
  ;; Keep the generation outside sync_meta so those writes cannot bless
  ;; themselves by updating the values they are meant to prove.
  (common/sql-exec
   sql
   (str "create table if not exists sync_meta_mutation_generations ("
        "scope TEXT primary key,"
        "generation INTEGER not null"
        ");"))
  (common/sql-exec
   sql
   (str "insert into sync_meta_mutation_generations (scope, generation) "
        "values ('snapshot-checksums', 0) on conflict(scope) do nothing"))
  (let [critical-keys
        (str "('t','checksum','server-checksum-v2',"
             "'server-checksum-v2-t',"
             "'checksum-metadata-contract-version',"
             "'checksum-metadata-contract-t',"
             "'checksum-metadata-contract-checksum',"
             "'checksum-metadata-contract-server-checksum-v2')")]
    (doseq [[trigger event condition]
            [["sync_meta_snapshot_generation_insert" "insert"
              (str "new.key in " critical-keys)]
             ["sync_meta_snapshot_generation_update" "update"
              (str "new.key in " critical-keys
                   " and old.value is not new.value")]
             ["sync_meta_snapshot_generation_delete" "delete"
              (str "old.key in " critical-keys)]]]
      (common/sql-exec
       sql
       (str "create trigger if not exists " trigger " after " event
            " on sync_meta when " condition " begin "
            "update sync_meta_mutation_generations "
            "set generation = generation + 1 "
            "where scope = 'snapshot-checksums'; end"))))
  ;; Integrity proofs are Worker-internal state, not protocol metadata. Keep
  ;; them out of sync_meta so a logically equivalent KVS repair does not alter
  ;; the graph's legacy metadata contract.
  (common/sql-exec
   sql
   (str "create table if not exists integrity_attestations ("
        "scope TEXT primary key,"
        "value TEXT not null"
        ");")))

(def ^:private required-schema-columns
  {"kvs" #{"addr" "content" "addresses"}
   "kvs_mutation_generations" #{"scope" "generation"}
   "snapshot_kvs_staging" #{"addr" "content" "addresses"}
   "snapshot_downloads" #{"download_id" "t" "checksum" "row_count" "created_at"}
   "snapshot_kvs_exports" #{"download_id" "addr" "content" "addresses"}
   "snapshot_download_generations" #{"download_id" "generation_key"
                                     "legacy" "lease_count" "frozen"}
   "tx_log" #{"t" "tx" "created_at" "outliner_op" "tx_id"}
   "applied_client_txs" #{"identity" "payload_digest" "created_at"}
   "client_tx_uploads" #{"logical_tx_id" "session_id" "outliner_op"
                         "next_index" "status" "final_index"
                         "final_wire_digest" "completed_digest"
                         "created_at" "updated_at"}
   "client_tx_upload_chunks" #{"session_id" "chunk_index" "tx"
                                "wire_digest" "datom_count" "created_at"}
   "sync_meta" #{"key" "value"}
   "sync_meta_mutation_generations" #{"scope" "generation"}
   "integrity_attestations" #{"scope" "value"}})

(def ^:private required-schema-triggers
  #{"kvs_mutation_generation_insert"
    "kvs_mutation_generation_update"
    "kvs_mutation_generation_update_addr"
    "kvs_mutation_generation_delete"
    "snapshot_download_cow_insert"
    "snapshot_download_cow_update"
    "snapshot_download_cow_delete"
    "sync_meta_snapshot_generation_insert"
    "sync_meta_snapshot_generation_update"
    "sync_meta_snapshot_generation_delete"})

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
       "client_tx_upload_chunks_session"))
     (boolean
      (select-one
       sql
       (str "select 1 as present from sqlite_master "
            "where type = 'index' and name = ? limit 1")
       "snapshot_download_generation_lookup"))
     (every?
      (fn [trigger]
        (boolean
         (select-one
          sql
          (str "select 1 as present from sqlite_master "
               "where type = 'trigger' and name = ? limit 1")
          trigger)))
      required-schema-triggers)
     (boolean
      (select-one
       sql
       (str "select 1 as present from kvs_mutation_generations "
            "where scope = 'live-kvs' and generation >= 0 limit 1")))
     (boolean
      (select-one
       sql
       (str "select 1 as present from sync_meta_mutation_generations "
            "where scope = 'snapshot-checksums' "
            "and generation >= 0 limit 1"))))
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

(def ^:private snapshot-integrity-attestation-version
  "unique-index-ref-closure-checksum-generation-floor-v3")

(def ^:private snapshot-integrity-attestation-meta-key
  :snapshot-integrity-attestation)

(def ^:private snapshot-integrity-attestation-scope
  "live-snapshot")

(def ^:private live-kvs-mutation-generation-scope
  "live-kvs")

(def ^:private snapshot-checksum-mutation-generation-scope
  "snapshot-checksums")

(defn live-kvs-mutation-generation
  [sql]
  (some-> (select-one
           sql
           (str "select generation from kvs_mutation_generations "
                "where scope = ?")
           live-kvs-mutation-generation-scope)
          (aget "generation")))

(defn snapshot-checksum-mutation-generation
  [sql]
  (some-> (select-one
           sql
           (str "select generation from sync_meta_mutation_generations "
                "where scope = ?")
           snapshot-checksum-mutation-generation-scope)
          (aget "generation")))

(defn- sha256-field!
  [^js digest value]
  (let [value (if (nil? value) "" (str value))]
    ;; Length-prefix every field so nil/empty and concatenation boundaries
    ;; cannot alias one another.
    (.update digest (str (count value) ":" value "\n"))))

(defn snapshot-storage-root-digest
  "Digest DataScript's bounded persisted root and transaction tail. The
  separate SQLite mutation generation binds every folded child page without
  requiring a user-table scan on the healthy request path."
  [sql]
  (let [rows (common/get-sql-rows
              (common/sql-exec
               sql
               (str "select addr, content, addresses from kvs "
                    "where addr in (0, 1) order by addr")))]
    (when (= [0 1] (mapv #(aget % "addr") rows))
      (let [digest (goog.crypt.Sha256.)]
        (doseq [row rows]
          (sha256-field! digest (aget row "addr"))
          (sha256-field! digest (aget row "content"))
          (sha256-field! digest (aget row "addresses")))
        (gcrypt/byteArrayToHex (.digest digest))))))

(defn snapshot-integrity-attestation
  [sql]
  (when-let [value
             (or (some-> (select-one
                          sql
                          (str "select value from integrity_attestations "
                               "where scope = ?")
                          snapshot-integrity-attestation-scope)
                         (aget "value"))
                 ;; Read-only compatibility for local databases produced by
                 ;; pre-release candidates. New writes use only the dedicated
                 ;; internal table below.
                 (get-meta sql snapshot-integrity-attestation-meta-key))]
    (try
      (common/read-transit value)
      (catch :default _
        nil))))

(defn clear-snapshot-integrity-attestation!
  [sql]
  (common/sql-exec sql
                   "delete from integrity_attestations where scope = ?"
                   snapshot-integrity-attestation-scope)
  (delete-meta! sql snapshot-integrity-attestation-meta-key))

(defn tx-log-floor
  "Return the earliest cursor that the retained contiguous tx_log suffix can
  safely serve. A legacy snapshot may have no retained history at a positive
  server cursor; that cursor is its floor. Any gap or suffix that does not end
  at the current cursor is invalid. tx_id is deliberately not part of this
  legacy compatibility invariant."
  [sql]
  (let [row (first
             (common/get-sql-rows
              (common/sql-exec
               sql
               (str "select count(*) as row_count, min(t) as min_t, "
                    "max(t) as max_t from tx_log"))))
        row-count (or (some-> row (aget "row_count")) 0)
        min-t (some-> row (aget "min_t"))
        max-t (or (some-> row (aget "max_t")) 0)
        t (get-t sql)]
    (cond
      (and (zero? t) (zero? row-count))
      0

      (and (pos? t) (zero? row-count))
      t

      (and (pos? t)
           (pos? row-count)
           (number? min-t)
           (pos? min-t)
           (= t max-t)
           (= row-count (inc (- max-t min-t))))
      (dec min-t)

      :else
      nil)))

(defn tx-log-contiguous?
  "Prove that tx_log is a complete retained suffix ending at the current t."
  [sql]
  (number? (tx-log-floor sql)))

(defn- attested-snapshot-integrity-generation-at-t
  [sql expected-t]
  (let [{:keys [version generation t]}
        (snapshot-integrity-attestation sql)]
    (when (and (= snapshot-integrity-attestation-version version)
               (string? generation)
               (seq generation)
               (= expected-t t))
      generation)))

(defn eligible-snapshot-integrity-generation
  "Return the previously proven generation only while the retained history
  floor and server cursor still match. This intentionally ignores KVS root
  drift because a trusted DataScript listener calls it after persisting the
  new root but before sealing that transaction."
  [sql expected-t]
  (let [{attested-floor :tx-log-floor}
        (snapshot-integrity-attestation sql)
        current-floor (tx-log-floor sql)]
    (when (and (number? attested-floor)
               (= attested-floor current-floor))
      (attested-snapshot-integrity-generation-at-t sql expected-t))))

(defn- snapshot-integrity-attestation-current?
  [sql expected-t expected-floor]
  (let [{:keys [root-digest kvs-generation checksum server-checksum
                server-checksum-t checksum-metadata-generation]
         attested-floor :tx-log-floor
         :as attestation}
        (snapshot-integrity-attestation sql)]
    (and (attested-snapshot-integrity-generation-at-t sql expected-t)
         (= expected-t (get-t sql))
         (string? root-digest)
         (= root-digest (snapshot-storage-root-digest sql))
         (number? kvs-generation)
         (= kvs-generation (live-kvs-mutation-generation sql))
         (number? checksum-metadata-generation)
         (= checksum-metadata-generation
            (snapshot-checksum-mutation-generation sql))
         (= checksum (get-checksum sql))
         (= server-checksum (get-server-checksum sql))
         (= server-checksum-t (get-server-checksum-t sql))
         (= expected-floor attested-floor)
         (map? attestation))))

(defn snapshot-integrity-attested?
  [sql]
  (let [t (get-t sql)
        floor (tx-log-floor sql)]
    (and (number? floor)
         (snapshot-integrity-attestation-current? sql t floor))))

(defn snapshot-integrity-history-conflict?
  "Reject history drift only for the current floor-aware attestation format.
  Older attestations may be upgraded by a full validation, but a floor already
  proven by this Worker must never be silently moved by truncation or a gap."
  [sql]
  (let [{:keys [version t]
         attested-floor :tx-log-floor}
        (snapshot-integrity-attestation sql)]
    (and (= snapshot-integrity-attestation-version version)
         (or (not= t (get-t sql))
             (not (number? attested-floor))
             (not= attested-floor (tx-log-floor sql))))))

(def ^:dynamic *snapshot-integrity-write-chain*
  "A batch-local proof that tx_log was contiguous at :t. The Worker may bind
  this only around one synchronous, serial insert-only client batch. The
  cursor advances only after each entry's SQL transaction commits and its
  root/checksum attestation is current."
  nil)

(defn validated-snapshot-integrity-write-chain
  "Perform the full root/checksum/tx_log proof once and return a batch-local
  serial write chain. Missing or stale attestations return nil."
  [sql]
  (let [t (get-t sql)
        floor (tx-log-floor sql)]
    (when (and (number? floor)
               (snapshot-integrity-attestation-current? sql t floor))
      (let [generation
            (attested-snapshot-integrity-generation-at-t sql t)]
        (when generation
          {:sql sql
           :generation generation
           :floor floor
           :t (volatile! t)})))))

(defn batch-snapshot-integrity-generation
  "Return the active batch generation only while SQL and cursor still match
  the last committed entry. This does not replace the batch-entry full proof."
  [sql]
  (let [{chain-sql :sql generation :generation cursor :t}
        *snapshot-integrity-write-chain*
        expected-t (when cursor @cursor)]
    (when (and (identical? sql chain-sql)
               (number? expected-t)
               (= expected-t (get-t sql))
               (= generation
                  (attested-snapshot-integrity-generation-at-t
                   sql expected-t)))
      generation)))

(defn advance-snapshot-integrity-write-chain!
  "Advance the in-memory batch proof after an entry transaction committed.
  The new persisted attestation must bind the committed root and checksums."
  [sql]
  (let [{chain-sql :sql generation :generation floor :floor cursor :t}
        *snapshot-integrity-write-chain*
        committed-t (get-t sql)]
    (when (and (identical? sql chain-sql) cursor)
      (when-not (and (= generation
                        (attested-snapshot-integrity-generation-at-t
                         sql committed-t))
                     (snapshot-integrity-attestation-current?
                      sql committed-t floor))
        (throw (ex-info "committed batch entry is not integrity-attested"
                        {:type :db-sync/snapshot-write-integrity-invalid
                         :t committed-t})))
      (vreset! cursor committed-t))))

(defn- batch-chain-can-advance?
  [sql new-t generation]
  (let [{chain-sql :sql chain-generation :generation cursor :t}
        *snapshot-integrity-write-chain*
        previous-t (when cursor @cursor)]
    (and (identical? sql chain-sql)
         (number? previous-t)
         (= new-t (inc previous-t))
         (= new-t (get-t sql))
         (= generation chain-generation)
         (= generation
            (attested-snapshot-integrity-generation-at-t
             sql previous-t)))))

(defn- batch-chain-tx-log-floor
  [sql new-t generation]
  (when (batch-chain-can-advance? sql new-t generation)
    (:floor *snapshot-integrity-write-chain*)))

(defonce ^:private verified-snapshot-integrity-descriptors
  (js/WeakMap.))

(defn- descriptor-values!
  [descriptor]
  (or (.get verified-snapshot-integrity-descriptors descriptor)
      (throw (ex-info "snapshot integrity descriptor is not verified"
                      {:type
                       :db-sync/unverified-snapshot-integrity-descriptor}))))

(defn snapshot-integrity-descriptor-values
  "Return only protocol checksum values from a descriptor minted by a full
  DataScript recomputation. Plain maps/objects are never accepted."
  [descriptor]
  (select-keys (descriptor-values! descriptor)
               [:t :generation :checksum :server-checksum
                :server-checksum-t]))

(defn- mint-snapshot-integrity-descriptor!
  [sql {:keys [t generation checksum server-checksum server-checksum-t]
        :as values}]
  (let [root-digest (snapshot-storage-root-digest sql)
        kvs-generation (live-kvs-mutation-generation sql)
        current-floor (or (batch-chain-tx-log-floor sql t generation)
                          (tx-log-floor sql))
        checksum-metadata-generation
        (snapshot-checksum-mutation-generation sql)]
    (when-not (number? current-floor)
      (throw (ex-info "server cursor and tx log are not contiguous"
                      {:type :db-sync/tx-log-integrity
                       :t (get-t sql)})))
    (when-not (and (= t (get-t sql))
                   (string? generation)
                   (seq generation)
                   (string? root-digest)
                   (number? kvs-generation)
                   (number? checksum-metadata-generation)
                   (sync-checksum/valid-checksum? checksum)
                   (or (and (nil? server-checksum)
                            (nil? server-checksum-t))
                       (and (sync-checksum/valid-checksum? server-checksum)
                            (= t server-checksum-t))))
      (throw (ex-info "snapshot integrity descriptor inputs are invalid"
                      {:type :db-sync/snapshot-integrity-descriptor-invalid
                       :t t
                       :current-t (get-t sql)})))
    (let [descriptor #js {}]
      (.set verified-snapshot-integrity-descriptors
            descriptor
            (assoc values
                   :root-digest root-digest
                   :kvs-generation kvs-generation
                   :tx-log-floor current-floor
                   :checksum-metadata-generation
                   checksum-metadata-generation))
      descriptor)))

(defn verified-snapshot-integrity-descriptor
  "Mint a one-shot proof from the actual validated DataScript DB. Callers may
  inspect its checksum values, but cannot fabricate a descriptor from metadata
  already stored in sync_meta."
  [sql db t generation]
  (let [server-checksum (sync-checksum/recompute-server-checksum db)]
    (mint-snapshot-integrity-descriptor!
     sql
     {:t t
      :generation generation
      :checksum (sync-checksum/recompute-checksum db)
      :server-checksum server-checksum
      :server-checksum-t (when (string? server-checksum) t)})))

(defn- current-derived-snapshot-integrity-descriptor!
  "Internal transaction-listener capability. The listener derives these
  values from db-before/db-after and may mint only after writing the verified
  checksum contract in the same synchronous SQL transaction."
  [sql t generation]
  (when-not (checksum-metadata-verified? sql t)
    (throw (ex-info "derived checksum metadata is not verified"
                    {:type :db-sync/snapshot-integrity-descriptor-invalid
                     :t t})))
  (mint-snapshot-integrity-descriptor!
   sql
   {:t t
    :generation generation
    :checksum (get-checksum sql)
    :server-checksum (get-server-checksum sql)
    :server-checksum-t (get-server-checksum-t sql)}))

(defn- persist-snapshot-integrity-descriptor!
  [sql descriptor]
  (let [{:keys [t generation root-digest kvs-generation checksum
                server-checksum server-checksum-t
                checksum-metadata-generation]
         attested-tx-log-floor :tx-log-floor}
        (descriptor-values! descriptor)
        batch-floor (batch-chain-tx-log-floor sql t generation)
        current-floor (or batch-floor (tx-log-floor sql))]
    (when-not (and (= t (get-t sql))
                   (= root-digest (snapshot-storage-root-digest sql))
                   (= kvs-generation (live-kvs-mutation-generation sql))
                   (= attested-tx-log-floor current-floor)
                   (= checksum-metadata-generation
                      (snapshot-checksum-mutation-generation sql))
                   (= checksum (get-checksum sql))
                   (= server-checksum (get-server-checksum sql))
                   (= server-checksum-t (get-server-checksum-t sql))
                   (checksum-metadata-verified? sql t))
      (throw (ex-info "verified snapshot descriptor became stale"
                      {:type :db-sync/snapshot-integrity-descriptor-stale
                       :t t
                       :current-t (get-t sql)})))
    (when-not (number? current-floor)
      (throw (ex-info "server cursor and tx log are not contiguous"
                      {:type :db-sync/tx-log-integrity
                       :t (get-t sql)})))
    (common/sql-exec
     sql
     (str "insert into integrity_attestations (scope, value) values (?, ?)"
          " on conflict(scope) do update set value = excluded.value")
     snapshot-integrity-attestation-scope
     (common/write-transit
      {:version snapshot-integrity-attestation-version
       :generation generation
       :t t
       :root-digest root-digest
       :kvs-generation kvs-generation
       :tx-log-floor attested-tx-log-floor
       :checksum-metadata-generation checksum-metadata-generation
       :checksum checksum
       :server-checksum server-checksum
       :server-checksum-t server-checksum-t}))
    (.delete verified-snapshot-integrity-descriptors descriptor)
    ;; A local pre-release candidate may have written the proof into
    ;; sync_meta. Once the dedicated row is durable, remove that obsolete copy.
    (delete-meta! sql snapshot-integrity-attestation-meta-key)
    true))

(defn mark-snapshot-integrity-attested!
  "Low-level attestation writer. Only a verified one-shot descriptor is
  accepted. The legacy t/generation arity deliberately cannot seal metadata."
  ([sql descriptor]
   (persist-snapshot-integrity-descriptor! sql descriptor))
  ([sql _t _generation]
   (when-not (tx-log-contiguous? sql)
     (throw (ex-info "server cursor and tx log are not contiguous"
                     {:type :db-sync/tx-log-integrity
                      :t (get-t sql)})))
   false))

(def ^:dynamic *in-sql-transaction?* false)
(def ^:dynamic *snapshot-integrity-write-generation*
  "A generation proven immediately before a synchronous live write. Semantic
  API writes do not control the outliner tx-meta emitted by every nested
  transaction, so the enclosing Worker gate binds this value while the SQL
  transaction is open."
  nil)
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

(defn seal-verified-snapshot-integrity!
  "Atomically publish checksum metadata and an integrity attestation from a
  one-shot descriptor minted by `verified-snapshot-integrity-descriptor`.
  Any t/root/generation drift rejects before protocol metadata is changed."
  [sql descriptor]
  (with-sql-transaction!
   sql
   (fn []
     (let [{:keys [t root-digest kvs-generation
                   checksum-metadata-generation checksum server-checksum
                   server-checksum-t]
            attested-tx-log-floor :tx-log-floor}
           (descriptor-values! descriptor)
           current-floor (tx-log-floor sql)]
       (when-not (and (= t (get-t sql))
                      (= root-digest (snapshot-storage-root-digest sql))
                      (= kvs-generation (live-kvs-mutation-generation sql))
                      (= attested-tx-log-floor current-floor)
                      (= checksum-metadata-generation
                         (snapshot-checksum-mutation-generation sql)))
         (throw (ex-info "verified snapshot descriptor became stale"
                         {:type :db-sync/snapshot-integrity-descriptor-stale
                          :t t
                          :current-t (get-t sql)})))
       (set-checksum! sql checksum)
       (set-server-checksum! sql server-checksum server-checksum-t)
       (mark-checksum-metadata-verified! sql t)
       ;; The controlled writes above advance the critical metadata
       ;; generation. Bind the descriptor to that exact final generation;
       ;; untrusted setters have no access to this one-shot capability.
       (.set verified-snapshot-integrity-descriptors
             descriptor
             (assoc (descriptor-values! descriptor)
                    :checksum-metadata-generation
                    (snapshot-checksum-mutation-generation sql)))
       (mark-snapshot-integrity-attested! sql descriptor)
       {:checksum checksum
        :server-checksum server-checksum
        :server-checksum-t server-checksum-t}))))

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
   (append-tx! sql t tx-str created-at outliner-op tx-id nil))
  ([sql t tx-str created-at outliner-op tx-id integrity-generation]
   (with-sql-transaction!
    sql
    (fn []
      (let [batch-generation (batch-snapshot-integrity-generation sql)
            batch-chain? (and integrity-generation
                              (= integrity-generation batch-generation)
                              (= t (inc (get-t sql))))
            trusted-integrity-write?
            (or batch-chain?
                (and integrity-generation
                     (= integrity-generation
                        (eligible-snapshot-integrity-generation
                         sql (get-t sql)))
                     (= t (inc (get-t sql)))))
            max-t (if trusted-integrity-write?
                    (get-t sql)
                    (or (some-> (common/sql-exec
                                 sql "select max(t) as max_t from tx_log")
                                common/get-sql-rows
                                first
                                (aget "max_t"))
                        0))
            expected-t (inc max-t)]
        (when-not (= expected-t t)
          (throw (ex-info "tx log cursor is not the next contiguous value"
                          {:type :db-sync/tx-log-noncontiguous
                           :expected-t expected-t
                           :actual-t t
                           :max-t max-t})))
        ;; Reset SQLite's statement-local change counter so an adapter/fault
        ;; that silently drops the following insert cannot be mistaken for a
        ;; successful continuation of the proven batch-local history suffix.
        (common/sql-exec sql "update tx_log set t = t where 0")
        (common/sql-exec
         sql
         (str "insert into tx_log (t, tx, created_at, outliner_op, tx_id) "
              "values (?, ?, ?, ?, ?)")
         t
         tx-str
         created-at
         (outliner-op->sql outliner-op)
         (some-> tx-id str))
        (let [inserted-row-count
              (or (some-> (common/sql-exec sql "select changes() as n")
                          common/get-sql-rows
                          first
                          (aget "n"))
                  0)]
          (when-not (= 1 inserted-row-count)
            (throw (ex-info "tx log append did not persist exactly one row"
                            {:type :db-sync/tx-log-integrity
                             :t t
                             :inserted-row-count inserted-row-count})))))))))

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
     (inc chunk-index) now session-id)))

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

(defn fetch-canonical-tx-range
  "Return the exact persisted tx_log rows committed after `basis-t` through
  `committed-t`, including their client identity when one was supplied. This
  is intentionally separate from the legacy pull shape so old clients keep
  receiving the historical envelope unchanged."
  [sql basis-t committed-t]
  (let [rows (common/get-sql-rows
              (common/sql-exec
               sql
               (str "select t, tx, outliner_op, tx_id from tx_log "
                    "where t > ? and t <= ? order by t asc")
               basis-t committed-t))]
    (mapv (fn [row]
            (cond-> {:t (aget row "t")
                     :tx (aget row "tx")
                     :outliner-op (sql->outliner-op
                                   (aget row "outliner_op"))}
              (some? (aget row "tx_id"))
              (assoc :tx-id (uuid (aget row "tx_id")))))
          rows)))

(def ^:private kvs-storage-tables
  #{"kvs" "snapshot_kvs_staging"})

(defn- require-kvs-storage-table!
  [table]
  (when-not (contains? kvs-storage-tables table)
    (throw (ex-info "invalid snapshot storage table"
                    {:type :db-sync/invalid-snapshot-storage-table
                     :table table})))
  table)

(defn- upsert-addr-content! [sql table data]
  (require-kvs-storage-table! table)
  (doseq [item data]
    (common/sql-exec sql
                     (str "insert into " table
                          " (addr, content, addresses) values (?, ?, ?)"
                          " on conflict(addr) do update set content = excluded.content, addresses = excluded.addresses")
                     (aget item "addr")
                     (aget item "content")
                     (aget item "addresses"))))

(defn- restore-data-from-addr [sql table addr]
  (require-kvs-storage-table! table)
  (when-let [row (select-one sql
                             (str "select content, addresses from " table
                                  " where addr = ?")
                             addr)]
    (let [{:keys [content addresses]} (bean/->clj row)
          addresses (when addresses (js/JSON.parse addresses))
          data (common/read-transit content)]
      (if (and addresses (map? data))
        (assoc data :addresses addresses)
        data))))

(defn new-sqlite-storage
  ([sql]
   (new-sqlite-storage sql "kvs"))
  ([sql table]
   (require-kvs-storage-table! table)
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
         (upsert-addr-content! sql table data)))
     (-restore [_ addr]
       (restore-data-from-addr sql table addr)))))

(defn open-snapshot-conn
  "Open a raw snapshot table without the live tx-log/checksum listener."
  [sql table]
  (init-schema! sql)
  (let [snapshot-storage (new-sqlite-storage sql table)]
    (common-sqlite/get-storage-conn snapshot-storage db-schema/schema)))

(defn replace-snapshot-staging-from-db!
  "Replace only isolated snapshot staging with a fully replayed DataScript DB."
  [sql db]
  (when (d/storage db)
    (throw (ex-info "replayed snapshot DB must not retain source storage"
                    {:type :db-sync/snapshot-replay-retains-storage})))
  (common/sql-exec sql "delete from snapshot_kvs_staging")
  (d/store db (new-sqlite-storage sql "snapshot_kvs_staging")))

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
                requested-integrity-generation
                (or (:db-sync/snapshot-integrity-generation tx-meta)
                    *snapshot-integrity-write-generation*)
                batch-integrity-generation
                (batch-snapshot-integrity-generation sql)
                integrity-generation
                (when (= requested-integrity-generation
                         (or batch-integrity-generation
                             (eligible-snapshot-integrity-generation
                              sql prev-t)))
                  requested-integrity-generation)
                verified-checksum-metadata?
                (or (checksum-metadata-verified? sql prev-t)
                    (and (zero? prev-t)
                         (nil? prev-checksum)))]
            (append-tx! sql new-t tx-str created-at
                        (:outliner-op tx-meta) (:tx-id tx-meta)
                        integrity-generation)
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
                  (mark-checksum-metadata-verified! sql new-t))))
            (when (and integrity-generation
                       (not (:db-sync/skip-checksum-update? tx-meta)))
              (mark-snapshot-integrity-attested!
               sql
               (current-derived-snapshot-integrity-descriptor!
                sql new-t integrity-generation)))))))))

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
