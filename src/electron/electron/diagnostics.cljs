(ns electron.diagnostics
  "Local diagnostic log access; paths are resolved by the main process."
  (:require ["electron" :refer [app shell]]
            ["electron-log" :as electron-log]
            ["fs" :as fs]
            ["path" :as node-path]
            [electron.logger :as logger]))

(defn context
  []
  {:platform (.-platform js/process)
   :arch (.-arch js/process)
   :private-values [(.getPath app "home") (.getPath app "userData")
                    (.getAppPath app) (.-resourcesPath js/process)]})

(defn log-path
  [source graph-dir]
  (case source
    :app (.-path (.getFile (.. electron-log -transports -file)))
    :sync (when (and graph-dir (fs/existsSync graph-dir))
            (when-let [name (last (sort (filter #(re-matches #"db-worker-node-\d{8}\.log" %)
                                              (array-seq (fs/readdirSync graph-dir)))))]
              (node-path/join graph-dir name)))))

(defn open-log!
  [source graph-dir]
  (if-let [file-path (log-path source graph-dir)]
    (if (fs/existsSync file-path)
      (do (.showItemInFolder shell file-path) {:opened? true})
      {:opened? false :reason :not-created})
    {:opened? false :reason :not-created}))

(defn record-renderer-error!
  [record]
  (logger/error :renderer/exception
                (select-keys record [:source :at :logger :name :code :message :stack :component-stack]))
  true)

(defn renderer-gone!
  [^js details]
  (logger/error :renderer/process-gone
                {:reason (.-reason details) :exit-code (.-exitCode details)}))
