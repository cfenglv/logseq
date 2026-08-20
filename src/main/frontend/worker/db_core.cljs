(ns frontend.worker.db-core
  "Core db-worker logic without host-specific bootstrap."
  (:require
   [cljs-bean.core :as bean]
   [cljs.cache :as cache]
   [clojure.set]
   [clojure.string :as string]
   [datascript.core :as d]
   [datascript.storage :refer [IStorage] :as storage]
   [frontend.common.cache :as common.cache]
   [frontend.common.graph-view :as graph-view]
   [frontend.common.missionary :as c.m]
   [frontend.common.thread-api :as thread-api :refer [def-thread-api]]
   [frontend.worker-common.util :as worker-util]
   [frontend.worker.db-listener :as db-listener]
   [frontend.worker.db.fix :as db-fix]
   [frontend.worker.db.migrate :as db-migrate]
   [frontend.worker.db.validate :as worker-db-validate]
   [frontend.worker.export :as worker-export]
   [frontend.worker.markdown-mirror :as markdown-mirror]
   [frontend.worker.pipeline :as worker-pipeline]
   [frontend.worker.platform :as platform]
   [frontend.worker.publish]
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
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.api.db-based.tools :as api-tools]
   [logseq.cli.common.db-worker :as cli-db-worker]
   [logseq.common.graph-dir :as graph-dir]
   [logseq.common.util :as common-util]
   [logseq.db :as ldb]
   [logseq.db.common.initial-data :as common-initial-data]
   [logseq.db.common.order :as db-order]
   [logseq.db.common.reference :as db-reference]
   [logseq.db.common.sqlite :as common-sqlite]
   [logseq.db.common.view :as db-view]
   [logseq.db.frontend.class :as db-class]
   [logseq.db.frontend.entity-util :as entity-util]
   [logseq.db.frontend.property :as db-property]
   [logseq.db.frontend.schema :as db-schema]
   [logseq.db.sqlite.create-graph :as sqlite-create-graph]
   [logseq.db.sqlite.export :as sqlite-export]
   [logseq.db.sqlite.gc :as sqlite-gc]
   [logseq.db.sqlite.util :as sqlite-util]
   [logseq.outliner.op :as outliner-op]
   [logseq.outliner.recycle :as outliner-recycle]
   [logseq.publishing.html :as publish-html]
   [me.tonsky.persistent-sorted-set :as set :refer [BTSet]]
   [missionary.core :as m]
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

(def search-db-version
  "Current search index version, stored in PRAGMA user_version.
  Bump to force a rebuild when the index format changes."
  2)
(def ^:private recycle-gc-kv :logseq.kv/recycle-last-gc-at)

(def ^:private search-index-build-batch-size 200)
(def ^:private vector-embedding-batch-size 32)
(def ^:private vector-embedding-parallelism 2)
(def ^:private vector-embedding-max-batch-chars (* vector-embedding-batch-size 2048))
(def ^:private vector-embedding-max-title-length 2048)
(def ^:private query-embedding-timeout-ms 50)
(def ^:private search-index-build-time-budget-ms 8)
(def ^:private search-index-build-idle-status-ttl-ms 2000)
(def ^:private search-index-build-pause-ms 300)
(defonce ^:private *search-index-build-ids (atom {}))
(defonce ^:private *vector-index-rebuild-ids (atom {}))
(defonce ^:private *client-ops-cleanup-timers (atom {}))
(def ^:private client-ops-cleanup-interval-ms (* 3 60 60 1000))
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
(def ^:private sync-backup-db-path "/db-sync-backup.sqlite")
(def ^:private sync-backup-client-ops-path
  "/db-sync-client-ops-backup.sqlite")
(def ^:private sync-backup-marker-path "/db-sync-recovery.sqlite")

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

(defn- <import-db-at-path
  [^js pool path data]
  (let [storage (platform/storage (platform/current))]
    ((:import-db storage) pool path data)))

(defn- <import-db
  [^js pool data]
  (<import-db-at-path pool repo-path data))

(defn- <unlink-db-file!
  [^js pool path]
  (let [storage (platform/storage (platform/current))
        unlink-f (:unlink-db-file! storage)]
    (when-not (fn? unlink-f)
      (throw (ex-info "platform storage/unlink-db-file! missing"
                      {:path path})))
    (p/resolved (unlink-f pool path))))

(defn- <export-pool-file-if-present
  [repo path]
  (-> (<export-db-file repo path)
      (p/catch (fn [_] nil))))

(defn- open-sync-backup-marker-db
  [repo pool]
  (platform/sqlite-open
   (platform/current)
   {:sqlite @*sqlite
    :pool pool
    :path (resolve-db-path repo pool sync-backup-marker-path)
    :mode "c"}))

(defn- ensure-sync-backup-marker-schema!
  [^js db]
  (.exec db
         (str "create table if not exists recovery ("
              "id integer primary key check (id = 1), "
              "phase text not null, "
              "has_client_ops integer not null)")))

(defn- <write-sync-backup-marker!
  [repo phase has-client-ops?]
  (p/let [pool (<get-opfs-pool repo)
          ^js db (open-sync-backup-marker-db repo pool)]
    (try
      (ensure-sync-backup-marker-schema! db)
      (.exec db
             #js {:sql
                  (str "insert into recovery (id, phase, has_client_ops) "
                       "values (1, ?, ?) "
                       "on conflict(id) do update set "
                       "phase = excluded.phase, "
                       "has_client_ops = excluded.has_client_ops")
                  :bind #js [phase (if has-client-ops? 1 0)]})
      (finally
        (.close db)))))

(defn- <read-sync-backup-marker
  [repo]
  (p/let [marker-bytes
          (<export-pool-file-if-present repo sync-backup-marker-path)]
    (when (and marker-bytes
               (number? (.-byteLength marker-bytes))
               (pos? (.-byteLength marker-bytes)))
      (when (< (.-byteLength marker-bytes) 512)
        (throw
         (ex-info "snapshot recovery marker is truncated"
                  {:type :db-sync/invalid-snapshot-recovery-marker
                   :repo repo
                   :marker-size (.-byteLength marker-bytes)})))
      (p/let [pool (<get-opfs-pool repo)
              ^js db (open-sync-backup-marker-db repo pool)]
        (try
          (ensure-sync-backup-marker-schema! db)
          (if-let [row
                   (first
                    (.exec db
                           #js {:sql
                                (str "select phase, has_client_ops "
                                     "from recovery where id = 1")
                                :rowMode "object"}))]
            {:phase (aget row "phase")
             :has-client-ops? (= 1 (aget row "has_client_ops"))}
            (throw
             (ex-info "snapshot recovery marker row is missing"
                      {:type :db-sync/invalid-snapshot-recovery-marker
                       :repo repo})))
          (finally
            (.close db)))))))

(defn- <cleanup-sync-backup-files!
  [repo]
  (p/let [pool (<get-opfs-pool repo)
          _ (<unlink-db-file! pool sync-backup-db-path)
          _ (<unlink-db-file! pool sync-backup-client-ops-path)
          _ (<unlink-db-file! pool sync-backup-marker-path)]
    nil))

(defn- <cleanup-committed-sync-backup-files!
  [repo]
  ;; A committed marker means the live graph is authoritative. Cleanup is
  ;; deliberately non-fatal: the marker is deleted last, so an interrupted
  ;; cleanup is safe and will be retried on the next open.
  (-> (<cleanup-sync-backup-files! repo)
      (p/catch
       (fn [error]
         (log/warn :db-sync/snapshot-backup-cleanup-failed
                   {:repo repo
                    :error-name (or (.-name error) "Error")})
         nil))))

(defn- <reset-sync-target-files!
  [repo]
  (p/let [pool (<get-opfs-pool repo)
          _ (<unlink-db-file! pool repo-path)
          _ (<unlink-db-file! pool (str "search" repo-path))
          _ (<unlink-db-file! pool (str "client-ops-" repo-path))]
    nil))

(defn- <restore-durable-sync-backup!
  [repo {:keys [has-client-ops?]}]
  (p/let [db-data (<export-db-file repo sync-backup-db-path)
          client-ops-data
          (when has-client-ops?
            (<export-db-file repo sync-backup-client-ops-path))
          pool (<get-opfs-pool repo)
          _ (<reset-sync-target-files! repo)
          _ (<import-db-at-path pool repo-path db-data)
          _ (when client-ops-data
              (<import-db-at-path
               pool
               (str "client-ops-" repo-path)
               client-ops-data))]
    nil))

(defn- <recover-pending-sync-backup!
  [repo]
  (p/let [marker (<read-sync-backup-marker repo)]
    (if-not marker
      nil
      (case (:phase marker)
        "pending"
        (p/let [_ (<restore-durable-sync-backup! repo marker)
                ;; Commit the restored target before cleanup. If the process
                ;; crashes again, startup must retain the restored graph
                ;; rather than require sidecars that may already be gone.
                _ (<write-sync-backup-marker!
                   repo "committed" (:has-client-ops? marker))
                _ (<cleanup-committed-sync-backup-files! repo)]
          (log/warn :db-sync/recovered-interrupted-snapshot-activation
                    {:repo repo})
          :restored)

        "committed"
        (p/let [_ (<cleanup-committed-sync-backup-files! repo)]
          :committed)

        (throw
         (ex-info "snapshot recovery marker phase is invalid"
                  {:type :db-sync/invalid-snapshot-recovery-marker
                   :repo repo
                   :phase (:phase marker)}))))))

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
  [^Object db]
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
        (upsert-addr-content! db data)))

    (-restore [_ addr]
      (restore-data-from-addr db addr))))

(defn- close-db-aux!
  [repo ^Object db ^Object search ^Object client-ops
   & [{:keys [preserve-sync-import?]}]]
  (checkpoint-db! repo db)
  (checkpoint-db! repo search)
  (checkpoint-db! repo client-ops)
  (when-not preserve-sync-import?
    (sync-download/close-import-state-for-repo! repo))
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
  (swap! client-op/*repo->pending-local-tx-count dissoc repo)
  (swap! *search-index-build-ids dissoc repo)
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
  ([repo]
   (close-db! repo nil))
  ([repo opts]
   (let [{:keys [db search client-ops]} (get @*sqlite-conns repo)]
     (close-db-aux! repo db search client-ops opts))))

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

(defn reset-db!
  [repo db-transit-str]
  (when-let [conn (get @*datascript-conns repo)]
    (let [new-db (ldb/read-transit-str db-transit-str)
          new-db' (update new-db :eavt (fn [^BTSet s]
                                         (set! (.-storage s) (.-storage (:eavt @conn)))
                                         s))]
      (d/reset-conn! conn new-db' {:reset-conn! true})
      (d/reset-schema! conn (:schema new-db)))))

(defn- vector-index-path
  [repo pool]
  (resolve-db-path repo pool "search/vector"))

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
    (p/let [^js pool (<get-opfs-pool repo)
            capacity (when (exists? (.-getCapacity pool))
                       (.getCapacity pool))
            _ (when (and (some? capacity) (zero? capacity))
                (.unpauseVfs pool))
            db-path (resolve-db-path repo pool repo-path)
            search-path (resolve-db-path repo pool (str "search" repo-path))
            current-platform (platform/current)
            vector-path (vector-index-path repo pool)
            client-ops-path (resolve-db-path repo pool (str "client-ops-" repo-path))
            _ (log/info :db-worker/get-dbs-open {:repo repo :db-path db-path})
            db (platform/sqlite-open (platform/current)
                                     {:sqlite @*sqlite
                                      :pool pool
                                      :path db-path})
            _ (log/info :db-worker/get-dbs-open {:repo repo :search-path search-path})
            search-db (platform/sqlite-open (platform/current)
                                            {:sqlite @*sqlite
                                             :pool pool
                                             :path search-path})
            vector-index (when (get-in current-platform [:vector :open-index])
                           (platform/vector-open current-platform
                                                 {:path vector-path
                                                  :dimension (platform/embedding-dimension current-platform)}))
            _ (log/info :db-worker/get-dbs-open {:repo repo :client-ops-path client-ops-path})
            client-ops-db (platform/sqlite-open (platform/current)
                                                {:sqlite @*sqlite
                                                 :pool pool
                                                 :path client-ops-path})]
      [db search-db client-ops-db vector-index])))

(defn- enable-sqlite-wal-mode!
  [^Object db]
  (.exec db "PRAGMA locking_mode=exclusive")
  (.exec db "PRAGMA journal_mode=WAL"))

(defn- gc-sqlite-dbs!
  "Gc main db weekly and rtc ops db each time when opening it"
  [sqlite-db datascript-conn {:keys [full-gc?]}]
  (let [last-gc-at (:kv/value (d/entity @datascript-conn :logseq.kv/graph-last-gc-at))]
    (when (or full-gc?
              (nil? last-gc-at)
              (not (number? last-gc-at))
              (> (- (common-util/time-ms) last-gc-at) (* 30 24 3600 1000))) ; 1 month ago
      (log/info :gc-sqlite-dbs "gc current graph")
      (sqlite-gc/gc-kvs-table! sqlite-db {:full-gc? full-gc?})
      (.exec sqlite-db "VACUUM")
      (ldb/transact! datascript-conn [{:db/ident :logseq.kv/graph-last-gc-at
                                       :kv/value (common-util/time-ms)}]
                     {:skip-validate-db? true
                      :persist-op? false}))))

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

(defn- maybe-run-recycle-gc!
  [conn]
  (let [now (common-util/time-ms)
        last-gc-at (:kv/value (d/entity @conn recycle-gc-kv))]
    (when (or (not (number? last-gc-at))
              (> (- now last-gc-at) outliner-recycle/gc-interval-ms))
      (outliner-recycle/gc! conn {:now-ms now})
      (ldb/transact! conn [{:db/ident recycle-gc-kv
                            :kv/value now}]
                     {:persist-op? false
                      :skip-validate-db? true}))))

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

(defn- <create-or-open-db!
  [repo {:keys [config datoms sync-download-graph? creating-remote-graph?] :as opts}]
  (when creating-remote-graph?
    (when (and (worker-state/get-sqlite-conn repo :client-ops)
               (nil? (client-op/get-local-tx repo)))
      (client-op/update-local-tx repo 0)))
  (when-not (worker-state/get-sqlite-conn repo)
    (p/let [recovery-result (<recover-pending-sync-backup! repo)
            [db search-db client-ops-db vector-index] (get-dbs repo)
            dbs (cond-> [db search-db]
                  client-ops-db (conj client-ops-db))
            storage (new-sqlite-storage db)]
      (swap! *sqlite-conns assoc repo {:db db
                                       :search search-db
                                       :client-ops client-ops-db})
      (when vector-index
        (swap! *vector-indexes assoc repo vector-index))
      (when (= :restored recovery-result)
        (search/truncate-vector-index! vector-index))
      (doseq [db' dbs]
        (enable-sqlite-wal-mode! db'))
      (common-sqlite/create-kvs-table! db)
      (when-not @*publishing? (common-sqlite/create-kvs-table! client-ops-db))
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
                          (d/transact! conn batch {:initial-db? true}))
                      non-ident-batches (->> datoms
                                             (remove #(contains? ident-eids (:e %)))
                                             (map to-tx)
                                             (partition-all batch-size))]
                  (doseq [batch non-ident-batches]
                    (d/transact! conn batch {:initial-db? true}))))
            client-ops-conn (when-not @*publishing? client-ops-db)
            initial-data-exists? (when (nil? datoms)
                                   (and (d/entity @conn :logseq.class/Root)
                                        (= "db" (:kv/value (d/entity @conn :logseq.kv/db-type)))))]
        (swap! *datascript-conns assoc repo conn)
        (swap! *client-ops-conns assoc repo client-ops-conn)
        (when-not @*publishing?
          (client-op/ensure-sqlite-schema! client-ops-db))
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
                                    (ldb/transact! conn initial-data
                                                   {:initial-db? true})))]
          (when-not sync-download-graph?
            (let [migrate-result (db-migrate/migrate conn)]
              (if migrate-result
                (handle-migrate-result-local-txs! repo migrate-result)
                (maybe-enqueue-built-in-sync-repair! repo conn migrate-result initial-data-exists?)))
            (gc-sqlite-dbs! db conn {})
            (maybe-run-recycle-gc! conn))

          (when initial-tx-report
            (db-sync/handle-local-tx! repo initial-tx-report))

          (db-listener/listen-db-changes! repo (get @*datascript-conns repo))

          nil)))))

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

(defn- get-search-db
  [repo]
  (worker-state/get-sqlite-conn repo :search))

(defn- search-index-version
  [^js search-db]
  (aget (aget (.exec search-db #js {:sql "PRAGMA user_version" :rowMode "array"}) 0) 0))

(defn- expected-vector-index-metadata
  []
  {:embedding-model-id (platform/embedding-model-id (platform/current))
   :embedding-dimension (platform/embedding-dimension (platform/current))
   :context-version search/vector-context-version})

(defn- persist-vector-index-metadata!
  [repo]
  (when-let [set-metadata! (:set-metadata! (worker-state/get-vector-index repo))]
    (set-metadata! (expected-vector-index-metadata))))

(declare <embed-index-batches vector-embedding-batches)

(defn- start-vector-index-rebuild!
  [repo build-id]
  (swap! *vector-index-rebuild-ids assoc repo build-id))

(defn- active-vector-index-rebuild?
  [repo build-id]
  (= build-id (get @*vector-index-rebuild-ids repo)))

(defn- clear-vector-index-rebuild!
  [repo build-id]
  (swap! *vector-index-rebuild-ids
         (fn [builds]
           (if (= build-id (get builds repo))
             (dissoc builds repo)
             builds))))

(defn- schedule-vector-index-rebuild!
  [repo build-id indexed-blocks]
  (when (worker-state/get-vector-index repo)
    (start-vector-index-rebuild! repo build-id)
    (let [indexed-blocks (vec indexed-blocks)]
      (-> (if (seq indexed-blocks)
            (p/let [vector-blocks (<embed-index-batches (vector-embedding-batches indexed-blocks))]
              (when (active-vector-index-rebuild? repo build-id)
                (when-let [vector-index (worker-state/get-vector-index repo)]
                  (search/upsert-vector-blocks! vector-index vector-blocks))))
            (p/resolved nil))
          (p/then (fn [_]
                    (when (active-vector-index-rebuild? repo build-id)
                      (persist-vector-index-metadata! repo))))
          (p/catch (fn [error]
                     (when (active-vector-index-rebuild? repo build-id)
                       (log/error :search/vector-index-rebuild-failed {:repo repo
                                                                       :error error}))))
          (p/finally (fn []
                       (clear-vector-index-rebuild! repo build-id))))))
  nil)

(defn- start-search-index-build!
  [repo]
  (let [build-id (str (random-uuid))]
    (swap! *search-index-build-ids assoc repo build-id)
    build-id))

(defn- clear-search-index-build!
  [repo build-id]
  (swap! *search-index-build-ids
         (fn [builds]
           (if (= build-id (get builds repo))
             (dissoc builds repo)
             builds))))

(defn- ensure-active-search-index-build!
  [repo build-id]
  (when-not (= build-id (get @*search-index-build-ids repo))
    (throw (ex-info "stale search index build"
                    {:type :search/stale-index-build
                     :repo repo
                     :build-id build-id}))))

(defn- report-search-index-progress!
  [repo payload]
  (if (node-runtime?)
    (do
      (platform/post-message! (platform/current)
                              :thread-api/search-index-build-progress
                              [repo payload])
      (p/resolved nil))
    (-> (worker-state/<invoke-main-thread :thread-api/search-index-build-progress repo payload)
        (p/catch (fn [_error] nil)))))

(def-thread-api :thread-api/init
  []
  (init-sqlite-module!))

(def-thread-api :thread-api/set-db-sync-config
  [config]
  (reset! worker-state/*db-sync-config (worker-state/non-auth-db-sync-config config))
  nil)

(def-thread-api :thread-api/get-db-sync-config
  []
  (worker-state/non-auth-db-sync-config @worker-state/*db-sync-config))

(def-thread-api :thread-api/db-sync-status
  [repo]
  (db-sync/status repo))

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

(def-thread-api :thread-api/db-sync-resume
  [repo]
  (if (db-sync-dbs-open? repo)
    (db-sync/resume! repo)
    (p/do!
     (start-db! repo {:close-other-db? false})
     (db-sync/start! repo))))

(def-thread-api :thread-api/db-sync-stop
  []
  (db-sync/stop!))

(def-thread-api :thread-api/db-sync-update-presence
  [editing-block-uuid]
  (db-sync/update-presence! editing-block-uuid))

(def-thread-api :thread-api/db-sync-request-asset-download
  [repo asset-uuid]
  (db-sync/request-asset-download! repo asset-uuid))

(def-thread-api :thread-api/db-sync-download-missing-assets
  [repo graph-id]
  (db-sync/download-missing-assets! repo graph-id))

(def-thread-api :thread-api/db-sync-retry-asset-upload
  [repo]
  (db-sync/retry-asset-upload! repo))

(def-thread-api :thread-api/db-sync-grant-graph-access
  [repo graph-id target-email]
  (sync-crypt/<grant-graph-access! repo graph-id target-email))

(def-thread-api :thread-api/db-sync-ensure-user-rsa-keys
  [& [opts]]
  (sync-crypt/ensure-user-rsa-keys! opts))

(def-thread-api :thread-api/db-sync-list-remote-graphs
  []
  (db-sync/list-remote-graphs!))

(def-thread-api :thread-api/db-sync-upload-graph
  [repo]
  (db-sync/upload-graph! repo))

(def-thread-api :thread-api/db-sync-create-remote-graph
  [repo graph-e2ee? graph-ready-for-use?]
  (db-sync/create-remote-graph! repo {:graph-e2ee? graph-e2ee?
                                      :graph-ready-for-use? graph-ready-for-use?}))

(def-thread-api :thread-api/db-sync-stop-upload
  [repo]
  (db-sync/stop-upload! repo))

(def-thread-api :thread-api/db-sync-resume-upload
  [repo]
  (db-sync/resume-upload! repo))

(def-thread-api :thread-api/db-sync-upload-stopped?
  [repo]
  (db-sync/upload-stopped? repo))

(def-thread-api :thread-api/db-sync-get-block-conflicts
  [repo block-uuid]
  (client-op/get-sync-conflicts repo block-uuid))

(def-thread-api :thread-api/db-sync-clear-block-conflicts
  [repo block-uuid]
  (client-op/clear-sync-conflicts! repo block-uuid)
  (shared-service/broadcast-to-clients!
   :sync-conflicts-updated
   {:repo repo
    :block-uuid block-uuid
    :conflicts []}))

(def-thread-api :thread-api/db-sync-download-graph-by-id
  [repo graph-id graph-e2ee?]
  (sync-download/download-graph-by-id!
   repo graph-id graph-e2ee?
   {:failure-handler db-sync/quarantine-after-snapshot-failure!}))

;; [graph service]
(defonce *service (atom []))

(defn- remote-binary-function
  [qualified-kw-str & args]
  (let [qkw (keyword qualified-kw-str)]
    (vswap! thread-api/*profile update qkw inc)
    (if-let [f (@thread-api/*thread-apis qkw)]
      (p/let [result (apply f args)]
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
  (start-db! repo opts))

(def-thread-api :thread-api/q
  [repo inputs]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (apply d/q (first inputs) @conn (rest inputs))))

(def-thread-api :thread-api/datoms
  [repo & args]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [result (apply d/datoms @conn args)]
      (map (fn [d] [(:e d) (:a d) (:v d) (:tx d) (:added d)]) result))))

(def-thread-api :thread-api/pull
  [repo selector id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [eid (if (and (vector? id) (= :block/name (first id)))
                (:db/id (ldb/get-page @conn (second id)))
                id)]
      (some->> eid
               (d/pull @conn selector)
               (common-initial-data/with-parent @conn)))))

(def ^:private *get-blocks-cache (volatile! (cache/lru-cache-factory {} :threshold 1000)))

(defn- sanitize-block-result
  [result]
  (cond-> result
    (:block result)
    (update :block common-util/remove-nils-non-nested)

    (:children result)
    (update :children common-util/fast-remove-nils)))

(def ^:private get-blocks-with-cache
  (common.cache/cache-fn
   *get-blocks-cache
   (fn [repo requests]
     (let [db (some-> (worker-state/get-datascript-conn repo) deref)]
       [[repo (:max-tx db) requests]
        [db requests]]))
   (fn [db requests]
     (when db
       (->> requests
            (mapv (fn [{:keys [id opts]}]
                    (let [id' (if (and (string? id) (common-util/uuid-string? id)) (uuid id) id)]
                      (-> (common-initial-data/get-block-and-children db id' opts)
                          sanitize-block-result
                          (assoc :id id)))))
            ldb/write-transit-str)))))

(def-thread-api :thread-api/get-blocks
  [repo requests]
  (let [requests (ldb/read-transit-str requests)]
    (get-blocks-with-cache repo requests)))

(def-thread-api :thread-api/get-block-refs
  [repo id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (->> (db-reference/get-linked-references @conn id)
         :ref-blocks
         (map (fn [b] (assoc (into {} b) :db/id (:db/id b)))))))

(def-thread-api :thread-api/get-block-refs-count
  [repo id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (ldb/get-block-refs-count @conn id)))

(def-thread-api :thread-api/get-block-source
  [repo id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (:db/id (first (:block/_alias (d/entity @conn id))))))

(defn- search-blocks
  [repo q option]
  (let [search-db (get-search-db repo)
        conn (worker-state/get-datascript-conn repo)
        vector-index (worker-state/get-vector-index repo)]
    (search/search-blocks conn search-db vector-index q option)))

(defn- validate-embedding-count!
  [blocks embeddings]
  (when-not (= (count blocks) (count embeddings))
    (throw (ex-info "embedding result count mismatch"
                    {:block-count (count blocks)
                     :embedding-count (count embeddings)
                     :model-id (platform/embedding-model-id (platform/current))}))))

(defn- embeddable-index-block?
  [{:keys [id page title]}]
  (and id page (not (string/blank? (str title)))))

(defn- vector-embedding-title
  [block-or-title]
  (let [title (if (map? block-or-title)
                (or (:vector-title block-or-title)
                    (:title block-or-title))
                block-or-title)
        title (str title)]
    (if (> (count title) vector-embedding-max-title-length)
      (subs title 0 vector-embedding-max-title-length)
      title)))

(defn- vector-embedding-batches
  [blocks]
  (loop [remaining (seq blocks)
         batch []
         batch-chars 0
         result []]
    (if-let [block (first remaining)]
      (let [text (vector-embedding-title block)
            text-chars (count text)
            full? (or (>= (count batch) vector-embedding-batch-size)
                      (and (seq batch)
                           (> (+ batch-chars text-chars)
                              vector-embedding-max-batch-chars)))]
        (if full?
          (recur remaining [] 0 (conj result batch))
          (recur (next remaining)
                 (conj batch block)
                 (+ batch-chars text-chars)
                 result)))
      (cond-> result
        (seq batch) (conj batch)))))

(defn- <embed-index-batch
  ([batch]
   (<embed-index-batch #(platform/embed-texts (platform/current) %) batch))
  ([embed-texts-fn batch]
   (p/let [embeddings (embed-texts-fn (mapv vector-embedding-title batch))
           _ (validate-embedding-count! batch embeddings)]
     (mapv (fn [block embedding]
             (assoc block :embedding embedding))
           batch
           embeddings))))

(defn- <embed-index-batch-with-fallback
  ([batch]
   (<embed-index-batch-with-fallback #(platform/embed-texts (platform/current) %) batch))
  ([embed-texts-fn batch]
   (-> (<embed-index-batch embed-texts-fn batch)
       (p/catch
        (fn [error]
          (if (= 1 (count batch))
            (throw error)
            (let [split-index (quot (count batch) 2)
                  left (subvec (vec batch) 0 split-index)
                  right (subvec (vec batch) split-index)]
              (p/let [left-embedded (<embed-index-batch-with-fallback embed-texts-fn left)
                      right-embedded (<embed-index-batch-with-fallback embed-texts-fn right)]
                (into left-embedded right-embedded)))))))))

(defn- pop-embedding-batch!
  [queue]
  (let [selected (atom nil)]
    (swap! queue
           (fn [items]
             (if (seq items)
               (do
                 (reset! selected (first items))
                 (subvec items 1))
               items)))
    @selected))

(defn- <embed-index-batches
  ([batches]
   (<embed-index-batches batches nil))
  ([batches on-batch-embedded]
   (let [batches (vec batches)]
     (if (empty? batches)
       (p/resolved [])
       (let [queue (atom (mapv vector (range (count batches)) batches))
             results (atom {})
             worker-count (min vector-embedding-parallelism (count batches))]
         (letfn [(worker []
                   (if-let [[idx batch] (pop-embedding-batch! queue)]
                     (-> (<embed-index-batch-with-fallback batch)
                         (p/then (fn [embedded]
                                   (swap! results assoc idx embedded)
                                   (when on-batch-embedded
                                     (on-batch-embedded (count embedded)))
                                   (worker))))
                     (p/resolved nil)))]
           (p/let [_ (p/all (mapv (fn [_] (worker)) (range worker-count)))]
             (into [] (mapcat (fn [idx]
                                (get @results idx))
                              (range (count batches)))))))))))

(defn- <embed-index-blocks
  [repo blocks]
  (let [blocks (vec (filter embeddable-index-block? blocks))]
    (if (and (seq blocks) (worker-state/get-vector-index repo))
      (<embed-index-batches (vector-embedding-batches blocks))
      (p/resolved []))))

(defn- schedule-vector-index-upsert!
  [repo blocks]
  (when (and (seq blocks) (worker-state/get-vector-index repo))
    (-> (<embed-index-blocks repo blocks)
        (p/then (fn [vector-blocks]
                  (when (seq vector-blocks)
                    (search/upsert-vector-blocks! (worker-state/get-vector-index repo) vector-blocks))))
        (p/catch (fn [error]
                   (log/error :search/vector-index-upsert-failed {:repo repo
                                                                  :error error})))))
  nil)

(defn- <search-blocks
  [repo q option]
  (let [vector-index (worker-state/get-vector-index repo)]
    (if (and vector-index
             (:feature/enable-semantic-search? option)
             (not (:page-only? option))
             (not (:query-embedding option))
             (not (string/blank? q)))
      (-> (p/let [embeddings (-> (platform/embed-texts (platform/current) [q])
                                  (p/timeout query-embedding-timeout-ms))
                  _ (validate-embedding-count! [{:title q}] embeddings)]
            (search-blocks repo q (assoc option :query-embedding (first embeddings))))
          (p/catch (fn [error]
                     (log/warn :search/query-embedding-failed {:repo repo
                                                               :error error})
                     (search-blocks repo q option))))
      (p/resolved (search-blocks repo q option)))))

(def-thread-api :thread-api/block-refs-check
  [repo id {:keys [unlinked?]}]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [db @conn
          block (d/entity db id)]
      (if unlinked?
        (let [title (string/lower-case (:block/title block))
              result (search-blocks repo title {:limit 100})]
          (boolean (some (fn [b]
                           (let [block (d/entity db (:db/id b))]
                             (and (not= id (:db/id block))
                                  (not ((set (map :db/id (:block/refs block))) id))
                                  (string/includes? (string/lower-case (:block/title block)) title)))) result)))
        (some? (first (common-initial-data/get-block-refs db (:db/id block))))))))

(def-thread-api :thread-api/get-block-parents
  [repo id depth]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [block-id (:block/uuid (d/entity @conn id))]
      (->> (ldb/get-block-parents @conn block-id {:depth (or depth 3)})
           (map (fn [b]
                  (-> (into {} b)
                      (assoc :db/id (:db/id b)
                             :block/title (:block/title b)))))))))

(def-thread-api :thread-api/set-context
  [context]
  (when context (worker-state/update-context! context))
  nil)

(defn- transact-now!
  [repo tx-data tx-meta context]
  (assert (some? repo))
  (worker-state/set-db-latest-tx-time! repo)
  (let [conn (worker-state/get-datascript-conn repo)]
    (assert (some? conn) {:repo repo})
    (try
      (let [tx-data' (if (contains? #{:insert-blocks} (:outliner-op tx-meta))
                       (map (fn [m]
                              (if (and (map? m) (nil? (:block/order m)))
                                (assoc m :block/order (db-order/gen-key nil))
                                m)) tx-data)
                       tx-data)
            _ (when context (worker-state/set-context! context))
            tx-meta' (cond-> tx-meta
                       true
                       (dissoc :insert-blocks?))]
        (when-not (and (:create-today-journal? tx-meta)
                       (:today-journal-name tx-meta)
                       (seq tx-data')
                       (ldb/get-page @conn (:today-journal-name tx-meta))) ; today journal created already

          ;; (prn :debug :transact :tx-data tx-data' :tx-meta tx-meta')

          (worker-util/profile "Worker db transact"
                               (ldb/transact! conn tx-data' tx-meta')))
        (maybe-run-recycle-gc! conn)
        nil)
      (catch :default e
        (log/error ::worker-transact-failed
                   {:tx-meta (select-keys tx-meta [:op :outliner-op])
                    :tx-count (count tx-data)
                    :error-code (or (:error (ex-data e))
                                    (:type (ex-data e))
                                    :transact-failed)
                    :error-name (or (.-name e) "Error")})
        (throw e)))))

(def-thread-api :thread-api/transact
  [repo tx-data tx-meta context]
  (if-let [activation-promise
           (worker-state/snapshot-activation-promise repo)]
    (p/then activation-promise
            (fn []
              (transact-now! repo tx-data tx-meta context)))
    (transact-now! repo tx-data tx-meta context)))

(def-thread-api :thread-api/undo-redo-set-pending-editor-info
  [repo editor-info]
  (worker-undo-redo/set-pending-editor-info! repo editor-info)
  nil)

(def-thread-api :thread-api/undo-redo-record-editor-info
  [repo editor-info]
  (worker-undo-redo/record-editor-info! repo editor-info)
  nil)

(def-thread-api :thread-api/undo-redo-record-ui-state
  [repo ui-state-str]
  (worker-undo-redo/record-ui-state! repo ui-state-str)
  nil)

(def-thread-api :thread-api/undo-redo-undo
  [repo]
  (worker-undo-redo/undo repo))

(def-thread-api :thread-api/undo-redo-redo
  [repo]
  (worker-undo-redo/redo repo))

(def-thread-api :thread-api/undo-redo-clear-history
  [repo]
  (worker-undo-redo/clear-history! repo)
  nil)

(def-thread-api :thread-api/undo-redo-get-debug-state
  [repo]
  (worker-undo-redo/get-debug-state repo))

(def-thread-api :thread-api/get-initial-data
  [repo opts]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (if (:file-graph-import? opts)
      {:schema (:schema @conn)
       :initial-data (vec (d/datoms @conn :eavt))}
      (common-initial-data/get-initial-data @conn))))

(def-thread-api :thread-api/build-publishing-html
  [repo options]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (publish-html/build-html @conn options)))

(def-thread-api :thread-api/reset-db
  [repo db-transit]
  (reset-db! repo db-transit)
  nil)

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
  [repo & [opts]]
  (sync-crypt/cancel-ui-requests! {:reason :db-sync-close-db
                                   :repo repo})
  (close-db! repo opts))

(def-thread-api :thread-api/db-sync-invalidate-search-db
  [repo]
  (<invalidate-search-db! repo))

(def-thread-api :thread-api/db-sync-recreate-lock
  [repo]
  (if-let [recreate-lock-fn (get-in (platform/current) [:env :recreate-lock-fn])]
    (recreate-lock-fn repo)
    nil))

(def-thread-api :thread-api/db-sync-export-local-backup
  [repo]
  (p/let [^js db (worker-state/get-sqlite-conn repo :db)
          source-existed? (if db true (<db-exists? repo))]
    (if-not source-existed?
      ;; An explicit negative result is materially different from nil: nil
      ;; used to mean only that the graph was not open in this worker, which
      ;; could cause a closed existing graph to be mistaken for a fresh one.
      {:source-existed? false}
      (do
        (when db
          (checkpoint-db! repo db))
        (when-let [^js client-ops-db
                   (worker-state/get-sqlite-conn repo :client-ops)]
          (checkpoint-db! repo client-ops-db))
        (p/let [pool (<get-opfs-pool repo)
                storage (platform/storage (platform/current))
                copy-db-file! (:copy-db-file! storage)
                ^js client-ops-db
                (worker-state/get-sqlite-conn repo :client-ops)
                db-source-path
                (or (some-> db .-filename)
                    (resolve-db-path repo pool repo-path))
                client-ops-source-path
                (when client-ops-db
                  (or (some-> client-ops-db .-filename)
                      (resolve-db-path
                       repo pool (str "client-ops-" repo-path))))
                db-data
                (when-not copy-db-file!
                  (<export-db-file repo))
                client-ops-data
                (when (or (not copy-db-file!)
                          (nil? client-ops-db))
                  ((@thread-api/*thread-apis
                    :thread-api/export-client-ops-db-binary)
                   repo))
                has-client-ops?
                (or (some? client-ops-source-path)
                    (some? client-ops-data))
                _ (<unlink-db-file! pool sync-backup-db-path)
                _ (<unlink-db-file! pool sync-backup-client-ops-path)
                _ (<unlink-db-file! pool sync-backup-marker-path)
                _ (if copy-db-file!
                    (copy-db-file!
                     pool db-source-path sync-backup-db-path)
                    (<import-db-at-path
                     pool sync-backup-db-path (->uint8array db-data)))
                _ (if (and copy-db-file! client-ops-source-path)
                    (copy-db-file!
                     pool
                     client-ops-source-path
                     sync-backup-client-ops-path)
                    (when client-ops-data
                      (<import-db-at-path
                       pool
                       sync-backup-client-ops-path
                       (->uint8array client-ops-data))))
                ;; Write the recovery marker last. Before this point the live
                ;; database has not been touched, so orphaned backup files are
                ;; harmless. Once this commits, startup recovery can always
                ;; restore the durable sidecar files.
                _ (<write-sync-backup-marker!
                   repo "pending" has-client-ops?)]
          {:durable? true
           :has-client-ops? has-client-ops?
           :source-existed? true})))))

(def-thread-api :thread-api/db-sync-reset-target-preserving-backup
  [repo]
  (p/let [_ (sync-crypt/cancel-ui-requests!
             {:reason :db-sync-reset-target-preserving-backup
              :repo repo})
          _ (close-db! repo {:preserve-sync-import? true})
          _ (<reset-sync-target-files! repo)]
    nil))

(def-thread-api :thread-api/db-sync-discard-failed-target
  [repo]
  (p/let [_ (sync-crypt/cancel-ui-requests!
             {:reason :db-sync-discard-failed-target
              :repo repo})
          _ (close-db! repo {:preserve-sync-import? true})
          _ (<reset-sync-target-files! repo)]
    nil))

(def-thread-api :thread-api/db-sync-restore-local-backup
  [repo {:keys [durable? db-data client-ops-data]}]
  (if durable?
    (p/let [_ (sync-crypt/cancel-ui-requests!
               {:reason :db-sync-restore-local-backup
                :repo repo})
            _ (close-db! repo {:preserve-sync-import? true})
            _ (<recover-pending-sync-backup! repo)
            recreate-lock-fn
            (get-in (platform/current) [:env :recreate-lock-fn])
            _ (when (fn? recreate-lock-fn)
                (recreate-lock-fn repo))
            _ (<create-or-open-db! repo {:close-other-db? true})]
      nil)
    (when db-data
    (p/let [_ (sync-crypt/cancel-ui-requests!
               {:reason :db-sync-restore-local-backup
                :repo repo})
            _ (close-db! repo {:preserve-sync-import? true})
            pool (<get-opfs-pool repo)
            _ (remove-vfs! pool)
            recreate-lock-fn
            (get-in (platform/current) [:env :recreate-lock-fn])
            _ (when (fn? recreate-lock-fn)
                (recreate-lock-fn repo))
            _ (<import-db pool db-data)
            _ (when client-ops-data
                (<import-db-at-path pool
                                    (str "client-ops-" repo-path)
                                    client-ops-data))
            _ (<create-or-open-db! repo {:close-other-db? true})]
      nil))))

(def-thread-api :thread-api/db-sync-commit-local-backup
  [repo {:keys [durable? has-client-ops?]}]
  (when durable?
    (p/let [_ (<write-sync-backup-marker!
               repo "committed" has-client-ops?)]
      ;; Once the marker is committed, cleanup failure is not an activation
      ;; failure: startup will retain the new graph and retry sidecar cleanup.
      (<cleanup-committed-sync-backup-files! repo))))

(def-thread-api :thread-api/db-sync-discard-local-backup
  [repo {:keys [durable?]}]
  (when durable?
    ;; The activation pre-reset proof failed, so live files are still the only
    ;; authority. Remove the pending marker and sidecars without restoring the
    ;; now-stale copy over newer local input.
    (<cleanup-sync-backup-files! repo)))

(def-thread-api :thread-api/db-sync-rehydrate-large-titles
  [repo graph-id]
  (db-sync/rehydrate-large-titles-from-db! repo graph-id))

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

(def-thread-api :thread-api/search-blocks
  [repo q option]
  (<search-blocks repo q option))

(def-thread-api :thread-api/search-upsert-blocks
  [repo blocks]
  (when-let [db (get-search-db repo)]
    (search/upsert-blocks! db (bean/->js blocks))
    (schedule-vector-index-upsert! repo blocks)
    nil))

(def-thread-api :thread-api/search-delete-blocks
  [repo ids]
  (when-let [db (get-search-db repo)]
    (search/delete-vector-blocks! (worker-state/get-vector-index repo) ids)
    (search/delete-blocks! db ids)
    nil))

(def-thread-api :thread-api/search-truncate-tables
  [repo]
  (when-let [db (get-search-db repo)]
    (search/truncate-vector-index! (worker-state/get-vector-index repo))
    (search/truncate-table! db)
    nil))

(def-thread-api :thread-api/search-build-blocks-indice
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (search/build-blocks-indice @conn)))

(defn- take-search-index-batch
  [items batch-size time-budget-ms]
  (let [deadline (+ (common-util/time-ms) time-budget-ms)]
    (loop [batch (transient [])
           remaining (seq items)
           n 0]
      (if (or (nil? remaining)
              (>= n batch-size)
              (and (pos? n) (>= (common-util/time-ms) deadline)))
        [(persistent! batch) remaining]
        (recur (conj! batch (first remaining))
               (next remaining)
               (inc n))))))

(defn- search-index-input-idle?
  [repo]
  (if (node-runtime?)
    true
    (let [status-map @(:thread-atom/search-input-idle-status @worker-state/*state)
          {:keys [idle? ts]} (get status-map repo)
          fresh? (and (number? ts)
                      (<= (- (common-util/time-ms) ts)
                          search-index-build-idle-status-ttl-ms))]
      (if (and fresh? (boolean? idle?))
        idle?
        true))))

(defn- <wait-for-search-index-idle!
  [repo build-id]
  (p/loop []
    (ensure-active-search-index-build! repo build-id)
    (if (search-index-input-idle? repo)
      nil
      (p/let [_ (js/Promise. (fn [resolve] (js/setTimeout resolve search-index-build-pause-ms)))]
        (p/recur)))))

(defn- <build-blocks-index!
  "Build FTS/vector index in batches with yielding. Sets user_version to search-db-version on completion."
  [repo search-db conn build-id]
  (ensure-active-search-index-build! repo build-id)
  (let [db @conn
        blocks (->> (d/datoms db :avet :block/uuid)
                    (keep #(d/entity db (:e %)))
                    (remove search/hidden-entity?)
                    vec)
        total (count blocks)
        vector-index (worker-state/get-vector-index repo)
        index-opts {:include-vector-title? (some? vector-index)}
        progress-for-fts (fn [processed]
                           (if (zero? total)
                             100
                             (min 100 (int (* 100 (/ processed total))))))
        report-progress! (fn [progress processed total]
                           (report-search-index-progress! repo {:build-id build-id
                                                                :status :running
                                                                :stage :search-index
                                                                :progress progress
                                                                :processed processed
                                                                :total total}))]
    (p/do!
     (report-search-index-progress! repo {:build-id build-id
                                          :status :running
                                          :stage :search-index
                                          :progress 0
                                          :processed 0
                                          :total total})
     (<wait-for-search-index-idle! repo build-id)
     (ensure-active-search-index-build! repo build-id)
     (search/truncate-table! search-db)
     (search/truncate-vector-index! vector-index)
     (p/loop [remaining (seq blocks)
              processed 0
              last-progress 0
              indexed-blocks []]
       (ensure-active-search-index-build! repo build-id)
       (if (seq remaining)
         (let [[batch remaining'] (take-search-index-batch remaining
                                                           search-index-build-batch-size
                                                           search-index-build-time-budget-ms)
               processed' (+ processed (count batch))
               indexed (vec (keep #(search/block->index % index-opts) batch))
               indexed-blocks' (into indexed-blocks indexed)
               progress (progress-for-fts processed')
               should-report? (> progress last-progress)]
           (p/let [_ (when (seq indexed)
                       (search/upsert-blocks! search-db (bean/->js indexed)))
                   _ (when should-report?
                       (report-progress! progress processed' total))
                   _ (js/Promise. (fn [resolve] (js/setTimeout resolve 0)))]
             (p/recur remaining' processed' (if should-report? progress last-progress) indexed-blocks')))
         (do
           (ensure-active-search-index-build! repo build-id)
           (schedule-vector-index-rebuild! repo build-id indexed-blocks)
           (p/let [_ (do
                       (.exec search-db (str "PRAGMA user_version = " search-db-version))
                       (report-search-index-progress! repo {:build-id build-id
                                                            :status :completed
                                                            :stage :search-index
                                                            :progress 100
                                                            :processed total
                                                            :total total}))]
             nil)))))))

(def-thread-api :thread-api/search-build-blocks-indice-in-worker
  [repo & [force?]]
  (p/let [search-db (get-search-db repo)]
    (when search-db
      (let [version (search-index-version search-db)]
        (if (and (= version search-db-version)
                 (not force?))
          version
         (when-let [conn (worker-state/get-datascript-conn repo)]
           (let [build-id (start-search-index-build! repo)]
              (-> (report-search-index-progress! repo {:build-id build-id
                                                       :status :running
                                                       :stage :search-index
                                                       :progress 0
                                                       :processed 0
                                                       :total 0})
                  (p/then (fn [_]
                            (js/Promise. (fn [resolve] (js/setTimeout resolve 0)))))
                  (p/then (fn [_]
                            (<build-blocks-index! repo search-db conn build-id)))
                  (p/catch (fn [error]
                             (when-not (= :search/stale-index-build (:type (ex-data error)))
                               (log/error :search/index-build-failed {:repo repo
                                                                      :error error}))))
                  (p/finally (fn []
                               (when (= build-id (get @*search-index-build-ids repo))
                                 (report-search-index-progress! repo {:build-id build-id
                                                                      :status :idle}))
                               (clear-search-index-build! repo build-id))))
              :started)))))))

(def-thread-api :thread-api/search-build-pages-indice
  [_repo]
  nil)

(def-thread-api :thread-api/apply-outliner-ops
  [repo ops opts]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (try
      (worker-util/profile
       "apply outliner ops"
       (outliner-op/apply-ops! conn ops opts))
      (catch :default e
        (let [data (ex-data e)
              {:keys [type payload]} (when (map? data) data)]
          (case type
            :notification
            (do
              (log/error ::apply-outliner-ops-failed e)
              (shared-service/broadcast-to-clients! :notification [(:message payload) (:type payload) (:clear? payload) (:uid payload) (:timeout payload)
                                                                   (select-keys payload [:i18n-key :i18n-args])])
              ;; re-throw as CLI needs to see notification
              (throw e))
            (throw e)))))))

(def-thread-api :thread-api/sync-app-state
  [new-state]
  (when (and (contains? new-state :git/current-repo)
             (nil? (:git/current-repo new-state)))
    (log/warn :thread-api/sync-app-state-ignored-current-repo
              {:reason :missing-current-repo}))
  (worker-state/set-new-state! (cond-> new-state
                                 (nil? (:git/current-repo new-state))
                                 (dissoc :git/current-repo)))
  nil)

(def-thread-api :thread-api/markdown-mirror-set-enabled
  [repo enabled?]
  (markdown-mirror/set-enabled! repo enabled?)
  nil)

(def-thread-api :thread-api/markdown-mirror-flush
  [repo]
  (markdown-mirror/<flush-repo! repo {}))

(def-thread-api :thread-api/markdown-mirror-regenerate
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (markdown-mirror/<mirror-repo! repo @conn {})))

(def-thread-api :thread-api/export-get-debug-datoms
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-export/get-debug-datoms conn)))

(def-thread-api :thread-api/export-get-all-page->content
  [repo options]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-export/get-all-page->content @conn options)))

(def-thread-api :thread-api/validate-db
  [repo & [opts]]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-db-validate/validate-db conn opts)))

(defn- checksum-diagnostics
  [repo]
  {:local-checksum (client-op/get-local-checksum repo)
   :remote-checksum (get @db-sync/*repo->latest-remote-checksum repo)})

(def-thread-api :thread-api/recompute-checksum-diagnostics
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [result (worker-db-validate/recompute-checksum-diagnostics repo conn (checksum-diagnostics repo))
          recomputed-checksum (:recomputed-checksum result)]
      (when (and (some? recomputed-checksum)
                 (worker-state/get-client-ops-conn repo))
        (client-op/update-local-checksum repo recomputed-checksum)
        (client-op/update-local-server-checksum
         repo
         (sync-checksum/recompute-server-checksum @conn)))
      (cond-> result
        (some? recomputed-checksum)
        (assoc :local-checksum recomputed-checksum)))))

;; Returns an export-edn map for given repo. When there's an unexpected error, a map
;; with key :export-edn-error is returned
(def-thread-api :thread-api/export-edn
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (try
      (sqlite-export/build-export @conn options)
      (catch :default e
        (js/console.error "export-edn error: " e)
        (js/console.error "Stack:\n" (.-stack e))
        (platform/post-message! (platform/current)
                                :notification
                                [nil :error nil nil nil
                                 {:i18n-key :export/error-unexpected}])
        {:export-edn-error (.-message e)}))))

(def-thread-api :thread-api/import-edn
  [repo export-edn]
  (let [conn (worker-state/get-datascript-conn repo)]
    (when-not conn
      (throw (ex-info "graph not opened" {:code :graph-not-opened :repo repo})))
    (let [txs (sqlite-export/build-import export-edn @conn {})
          validation (sqlite-export/validate-import-txs txs @conn)]
      (if-let [error (:error validation)]
        {:error error}
        (let [tx-data (:tx-data validation)
              tx-meta (cond-> {::sqlite-export/imported-data? true}
                        ;; :datoms format imports all datoms including built-in ones. Add :initial-db?
                        ;; to keep pipeline from reverting their import
                        (= :datoms (::sqlite-export/graph-format export-edn))
                        (assoc :initial-db? true))]
          (ldb/transact! conn tx-data tx-meta)
          {:tx-count (count tx-data)})))))

(def-thread-api :thread-api/get-view-data
  [repo view-id option]
  (let [db @(worker-state/get-datascript-conn repo)]
    (db-view/get-view-data db view-id option)))

(def-thread-api :thread-api/get-class-objects
  [repo class-id]
  (let [db @(worker-state/get-datascript-conn repo)]
    (->> (db-class/get-class-objects db class-id)
         (map entity-util/entity->map))))

(def-thread-api :thread-api/get-property-values
  [repo {:keys [property-ident] :as option}]
  (let [conn (worker-state/get-datascript-conn repo)]
    (db-view/get-property-values @conn property-ident option)))

(def-thread-api :thread-api/get-bidirectional-properties
  [repo {:keys [target-id]}]
  (let [conn (worker-state/get-datascript-conn repo)]
    (worker-util/profile "get-bidirectional-properties"
                         (ldb/get-bidirectional-properties @conn target-id))))

(def-thread-api :thread-api/build-graph
  [repo option]
  (let [conn (worker-state/get-datascript-conn repo)]
    (graph-view/build-graph @conn option)))

(def ^:private *get-all-page-titles-cache (volatile! (cache/lru-cache-factory {})))
(defn- get-all-page-titles
  [db]
  (let [pages (ldb/get-all-pages db)]
    (sort (map :block/title pages))))

(def ^:private get-all-page-titles-with-cache
  (common.cache/cache-fn
   *get-all-page-titles-cache
   (fn [repo]
     (let [db @(worker-state/get-datascript-conn repo)]
       [[repo (:max-tx db)] ;cache-key
        [db]             ;f-args
        ]))
   get-all-page-titles))

(def-thread-api :thread-api/get-all-page-titles
  [repo]
  (get-all-page-titles-with-cache repo))

(def-thread-api :thread-api/gc-graph
  [repo]
  (let [{:keys [db]} (get @*sqlite-conns repo)
        conn (get @*datascript-conns repo)]
    (when (and db conn)
      (gc-sqlite-dbs! db conn {:full-gc? true})
      nil)))

(def-thread-api :thread-api/mobile-logs
  []
  @worker-state/*log)

(def-thread-api :thread-api/get-rtc-graph-uuid
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (ldb/get-graph-rtc-uuid @conn)))

;; Cli specific fns start with 'cli-'
(def-thread-api :thread-api/cli-list-properties
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-properties @conn options)))

(def-thread-api :thread-api/cli-list-tags
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-tags @conn options)))

(def-thread-api :thread-api/cli-list-pages
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-pages @conn options)))

(def-thread-api :thread-api/cli-list-tasks
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-tasks @conn options)))

(def-thread-api :thread-api/cli-list-nodes
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-nodes @conn options)))

;; API server specific fns start with 'api-'
(def-thread-api :thread-api/api-get-page-data
  [repo page-title]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/get-page-data @conn page-title)))

(def-thread-api :thread-api/api-list-properties
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/list-properties @conn options)))

(def-thread-api :thread-api/api-list-tags
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/list-tags @conn options)))

(def-thread-api :thread-api/api-list-pages
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/list-pages @conn options)))

(def-thread-api :thread-api/api-build-upsert-nodes-edn
  [repo ops]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/build-upsert-nodes-edn @conn ops)))

(comment
  (def-thread-api :general/dangerousRemoveAllDbs
    []
    (p/let [r (<list-all-dbs)
            dbs (ldb/read-transit-str r)]
      (p/all (map #(.unsafeUnlinkDB this (:name %)) dbs)))))

(defn- on-become-master
  [repo start-opts]
  (js/Promise.
   (m/sp
     (log/info :db-worker/on-become-master-start {:repo repo
                                                  :import-type (:import-type start-opts)})
     (c.m/<? (init-sqlite-module!))
     (when-not (:import-type start-opts)
       (c.m/<? (start-db! repo start-opts))
       (assert (some? (worker-state/get-datascript-conn repo))))
     nil)))

(def broadcast-data-types
  (set (map
        common-util/keyword->string
        [:sync-db-changes
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
        (p/let [service (shared-service/<create-service graph
                                                        (bean/->js fns)
                                                        #(on-become-master graph start-opts)
                                                        broadcast-data-types
                                                        {:import? (:import-type? start-opts)})]
          (assert (p/promise? (get-in service [:status :ready])))
          (reset! *service [graph service])
          service)))))

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
                   [graph opts] payload']
               (p/let [service (<init-service! graph opts)
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
             (p/let [_ready-value (get-in service [:status :ready])]
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
