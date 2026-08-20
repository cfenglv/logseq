(ns electron.updater-macos
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private helper-timeout-ms 2000)

(defn app-bundle-path
  [executable-path]
  (-> executable-path
      node-path/dirname
      node-path/dirname
      node-path/dirname))

(defn- helper-arguments
  [{:keys [archive metadata target]}]
  ["--archive" archive
   "--metadata" metadata
   "--parent-pid" (str (.-pid js/process))
   "--relaunch" "true"
   "--target" target])

(defn discard-attempt!
  [{:keys [directory]}]
  (when directory
    (fs/rmSync directory #js {:recursive true :force true})))

(defn prepare-and-verify!
  [{:keys [downloaded-file signed-metadata]} executable-path]
  (let [directory (fs/mkdtempSync (node-path/join (os/tmpdir) "logseq-selfhost6-update-"))
        attempt {:directory directory
                 :archive downloaded-file
                 :helper (node-path/join (.-resourcesPath js/process) "updater" "ProjectUpdater")
                 :metadata (node-path/join directory "metadata.json")
                 :target (app-bundle-path executable-path)}]
    (try
      (fs/accessSync (:helper attempt) (.-X_OK fs/constants))
      (fs/writeFileSync (:metadata attempt)
                        (str (js/JSON.stringify signed-metadata) "\n")
                        #js {:mode 384})
      (js/Promise.
       (fn [resolve reject]
         (.execFile child-process
                    (:helper attempt)
                    (clj->js (conj (helper-arguments attempt) "--verify-only"))
                    #js {:encoding "utf8"
                         :maxBuffer (* 1024 1024)
                         :timeout helper-timeout-ms}
                    (fn [error _stdout stderr]
                      (if error
                        (do
                          (discard-attempt! attempt)
                          (reject (ex-info "macOS update helper preflight failed"
                                           {:code :macos-update-helper-preflight-failed
                                            :detail (or (not-empty stderr) (.-message ^js error))})))
                        (resolve attempt))))))
      (catch :default error
        (discard-attempt! attempt)
        (throw error)))))

(defn spawn-handoff!
  [attempt]
  (js/Promise.
   (fn [resolve reject]
     (try
       (let [^js child (.spawn child-process
                               (:helper attempt)
                               (clj->js (helper-arguments attempt))
                               #js {:detached true
                                    :stdio "ignore"})
             settled? (atom false)]
         (.once child "error"
                (fn [error]
                  (when (compare-and-set! settled? false true)
                    (discard-attempt! attempt)
                    (reject error))))
         (.once child "spawn"
                (fn []
                  (when (compare-and-set! settled? false true)
                    (.unref child)
                    (resolve true)))))
       (catch :default error
         (discard-attempt! attempt)
         (reject error))))))
