(ns frontend.worker.db-sync-backup-memory-test-runner
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.db-core :as db-core]
            [frontend.worker.platform :as platform]
            [frontend.worker.platform.node :as platform-node]
            [frontend.worker.state :as worker-state]
            [promesa.core :as p]))

(def ^:private repo "db-sync-backup-memory-test")
(def ^:private main-db-bytes (* 64 1024 1024))
(def ^:private client-ops-db-bytes (* 32 1024 1024))
(def ^:private max-external-memory-growth (* 32 1024 1024))

(defonce ^:private *root-dir (atom nil))
(defonce ^:private *open-dbs (atom []))

(defn- ensure!
  [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- close-db!
  [^js db]
  (when db
    (try
      (.close db)
      (catch :default _error
        nil))))

(defn- cleanup!
  []
  (doseq [db @*open-dbs]
    (close-db! db))
  (reset! *open-dbs [])
  (reset! worker-state/*sqlite-conns {})
  (when-let [root-dir @*root-dir]
    (try
      (fs/rmSync root-dir #js {:recursive true :force true})
      (catch :default _error
        nil)))
  (reset! *root-dir nil))

(defn- collect-garbage!
  []
  (when-let [gc-fn (.-gc js/global)]
    (gc-fn)))

(defn- run-memory-gate!
  []
  (let [root-dir
        (fs/mkdtempSync
         (node-path/join (os/tmpdir) "logseq-db-sync-backup-memory-"))
        backup!
        (get @thread-api/*thread-apis
             :thread-api/db-sync-export-local-backup)
        commit!
        (get @thread-api/*thread-apis
             :thread-api/db-sync-commit-local-backup)]
    (reset! *root-dir root-dir)
    (p/let [node-platform
            (platform-node/node-platform {:root-dir root-dir})
            _ (platform/set-platform! node-platform)
            _ (reset! worker-state/*sqlite #js {})
            storage (:storage node-platform)
            sqlite (:sqlite node-platform)
            pool (#'db-core/<get-opfs-pool repo)
            resolve-path
            (fn [path]
              ((:resolve-db-path storage) repo pool path))
            db ((:open-db sqlite)
                {:path (resolve-path db-core/repo-path)})
            client-ops-db
            ((:open-db sqlite)
             {:path
              (resolve-path (str "client-ops-" db-core/repo-path))})
            _ (reset! *open-dbs [db client-ops-db])
            _ (.exec
               db
               (str "create table payload (data blob); "
                    "insert into payload values "
                    "(zeroblob(" main-db-bytes "))"))
            _ (.exec
               client-ops-db
               (str "create table payload (data blob); "
                    "insert into payload values "
                    "(zeroblob(" client-ops-db-bytes "))"))
            _ (reset! worker-state/*sqlite-conns
                      {repo {:db db
                             :client-ops client-ops-db}})
            _ (collect-garbage!)
            external-before (.-external (js/process.memoryUsage))
            backup (backup! repo)
            _ (collect-garbage!)
            external-after (.-external (js/process.memoryUsage))
            backup-path (resolve-path "/db-sync-backup.sqlite")
            client-backup-path
            (resolve-path "/db-sync-client-ops-backup.sqlite")
            backup-size (.-size (fs/statSync backup-path))
            client-backup-size
            (.-size (fs/statSync client-backup-path))
            external-growth (- external-after external-before)
            _ (ensure! (true? (:durable? backup))
                       "backup must be durable"
                       {:type :db-sync/non-durable-backup})
            _ (ensure! (> backup-size (* 60 1024 1024))
                       "main backup is unexpectedly small"
                       {:type :db-sync/main-backup-too-small
                        :size backup-size})
            _ (ensure! (> client-backup-size (* 28 1024 1024))
                       "client ops backup is unexpectedly small"
                       {:type :db-sync/client-ops-backup-too-small
                        :size client-backup-size})
            _ (ensure! (< external-growth max-external-memory-growth)
                       "storage copy retained database-sized buffers"
                       {:type :db-sync/backup-buffered-files
                        :external-growth external-growth})
            _ (commit! repo backup)]
      {:backup-size backup-size
       :client-backup-size client-backup-size
       :external-growth external-growth})))

(defn main
  []
  (->
   (run-memory-gate!)
   (p/then
    (fn [{:keys [backup-size client-backup-size external-growth]}]
      (.log js/console
            "db-sync backup memory gate passed"
            #js {:backupBytes backup-size
                 :clientOpsBackupBytes client-backup-size
                 :externalMemoryGrowth external-growth})))
   (p/catch
    (fn [error]
      (.error js/console
              "db-sync backup memory gate failed"
              (or (.-name error) "Error"))
      (set! (.-exitCode js/process) 1)))
   (p/finally cleanup!)))
