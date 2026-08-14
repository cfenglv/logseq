(ns electron.updater
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.db-worker :as db-worker]
            [electron.logger :as logger]
            [electron.updater-config :as updater-config]
            [electron.updater-install :as updater-install]
            [electron.updater-macos :as updater-macos]
            [electron.updater-target :as updater-target]
            [electron.utils :refer [*win prod?]]
            [frontend.version :refer [version]]
            [promesa.core :as p]
            ["electron" :refer [app ipcMain]]
            ["electron-updater" :refer [autoUpdater]]
            ["node:path" :as node-path]))

(def *update-pending (atom nil))
(def *downloaded-update (atom nil))
(def *downloaded-target (atom nil))
(def debug (partial logger/debug "[updater]"))
(def electron-version version)

(defn- emit-update!
  [^js win type payload]
  (when-let [web-contents (and win (. ^js win -webContents))]
    (.send web-contents "updates-callback"
           (bean/->js {:type type :payload payload}))))

(defn- emit-completed!
  [^js win]
  (emit-update! win "completed" nil))

(defn- normalize-payload
  [payload]
  (when payload
    (bean/->clj payload)))

(defn- normalize-error
  [^js e]
  {:message (or (.-message e) (str e))})

(defn- emit-update-downloaded!
  [payload]
  (when-let [web-contents (and @*win (. ^js @*win -webContents))]
    (.send web-contents "auto-updater-downloaded" (bean/->js payload))))

(defn- downloaded-file-sha512
  [^js info]
  (let [downloaded-name (some-> (.-downloadedFile info) node-path/basename)
        matches (->> (array-seq (or (.-files info) #js []))
                     (filter (fn [^js file]
                               (let [url-path (some-> (.-url file)
                                                     (string/split #"[?#]" 2)
                                                     first)]
                                 (= downloaded-name (some-> url-path node-path/basename))))))]
    (when (= 1 (count matches))
      (.-sha512 ^js (first matches)))))

(defn- configure-auto-updater!
  []
  (let [platform (.-platform js/process)
        arch (.-arch js/process)
        {:keys [channel allow-prerelease? allow-downgrade?] :as options}
        (updater-config/updater-options electron-version platform arch)]
    (when channel
      (set! (.-channel autoUpdater) channel)
      (set! (.-allowPrerelease autoUpdater) allow-prerelease?)
      ;; Setting channel enables downgrade in electron-updater; restore policy.
      (set! (.-allowDowngrade autoUpdater) allow-downgrade?))
    (debug "configure-auto-updater" (assoc options
                                           :platform platform
                                           :arch arch)))
  (set! (.-autoInstallOnAppQuit autoUpdater) false)
  (set! (.-autoDownload autoUpdater) false))

(defn- register-auto-updater-listeners!
  [^js win]
  (let [checking-handler
        (fn []
          (emit-update! win "checking-for-update" nil))

        available-handler
        (fn [info]
          (emit-update! win "update-available" (normalize-payload info)))

        not-available-handler
        (fn [info]
          (emit-update! win "update-not-available" (normalize-payload info))
          (emit-completed! win))

        progress-handler
        (fn [progress]
          (emit-update! win "download-progress" (normalize-payload progress)))

        downloaded-handler
        (fn [info]
          (let [payload (normalize-payload info)]
            (reset! *downloaded-update payload)
            (reset! *downloaded-target
                    {:downloaded-file (.-downloadedFile ^js info)
                     :signed-metadata (.-selfhostUpdateSignature ^js info)
                     :verified-archive-sha512 (downloaded-file-sha512 info)
                     ;; electron-updater emits update-downloaded only after its
                     ;; downloaded-file checksum validation has completed.
                     :archive-digest-verified true})
            (logger/info "[update-downloaded]" payload)
            (emit-update! win "update-downloaded" payload)
            (emit-update-downloaded! payload)
            (emit-completed! win)))

        error-handler
        (fn [error]
          (logger/warn "[updater/error]" error)
          (emit-update! win "error" (normalize-error error))
          (emit-completed! win))]
    (.on autoUpdater "checking-for-update" checking-handler)
    (.on autoUpdater "update-available" available-handler)
    (.on autoUpdater "update-not-available" not-available-handler)
    (.on autoUpdater "download-progress" progress-handler)
    (.on autoUpdater "update-downloaded" downloaded-handler)
    (.on autoUpdater "error" error-handler)
    #(do
       (.off autoUpdater "checking-for-update" checking-handler)
       (.off autoUpdater "update-available" available-handler)
       (.off autoUpdater "update-not-available" not-available-handler)
       (.off autoUpdater "download-progress" progress-handler)
       (.off autoUpdater "update-downloaded" downloaded-handler)
       (.off autoUpdater "error" error-handler))))

(defn- <check-for-updates!
  [^js win auto-download?]
  (debug "check-for-updates" {:auto-download? auto-download?})
  (set! (.-autoDownload autoUpdater) auto-download?)
  (-> (.checkForUpdates autoUpdater)
      (.then
       (fn [_]
         ;; Manual checks without auto download need an explicit terminal event.
         (when-not auto-download?
           (emit-completed! win))))
      (.catch
       (fn [error]
         (logger/warn "[updater/check]" error)
         (emit-update! win "error" (normalize-error error))
         (emit-completed! win)))))

(defn- init-auto-updater!
  [^js win]
  (when (and prod? (not= false (cfgs/get-item :auto-update)))
    (debug "init-auto-updater")
    (<check-for-updates! win true)))

(defn- run-downloaded-install!
  [^js win {:keys [set-dirty! restart! quit!]}]
  (let [mac? (= "darwin" (.-platform js/process))
        mac-attempt* (atom nil)]
    (updater-install/run-install!
     {:preflight! (fn []
                    (if-let [target @*downloaded-target]
                      (p/let [_ (updater-target/preflight-downloaded-target! target)
                              attempt (when mac?
                                        (updater-macos/prepare-and-verify! target (.getPath app "exe")))]
                        (reset! mac-attempt* attempt)
                        true)
                      (p/rejected (ex-info "no verified update target is downloaded"
                                           {:code :missing-downloaded-update-target}))))
      :begin-quiesce! #(db-worker/begin-update-quiesce! db-worker/manager)
      :stop-active! #(db-worker/stop-active-for-update! db-worker/manager %)
      :handoff! (fn []
                  (if mac?
                    (updater-macos/spawn-handoff! @mac-attempt*)
                    (do
                      (.quitAndInstall autoUpdater false true)
                      (p/resolved true))))
      :commit-quiesce! (fn [token]
                         (db-worker/commit-update-quiesce! db-worker/manager token)
                         (when mac? (quit!)))
      :resume-quiesce! #(db-worker/resume-update-quiesce! db-worker/manager %)
      :set-dirty! set-dirty!
      :restart! restart!
      :emit-error! (fn [error]
                     (when-let [attempt @mac-attempt*]
                       (updater-macos/discard-attempt! attempt))
                     (logger/warn "[updater/install]" error)
                     (emit-update! win "error" (normalize-error error))
                     (emit-completed! win))})))

(defn init-updater
  [{:keys [^js win set-dirty! restart! quit!] :as _opts}]
  (configure-auto-updater!)
  (let [dispose-listeners! (register-auto-updater-listeners! win)
        check-channel "check-for-updates"
        install-channel "install-updates"
        get-downloaded-channel "get-downloaded-update"
        check-listener (fn [_e & args]
                         (when-not @*update-pending
                           (reset! *update-pending true)
                           (let [auto-download? (true? (first args))]
                             (-> (<check-for-updates! win auto-download?)
                                 (.finally #(reset! *update-pending nil))))))
        install-listener (fn [_e]
                           (run-downloaded-install! win {:set-dirty! set-dirty!
                                                        :restart! restart!
                                                        :quit! quit!}))
        get-downloaded-listener (fn [_e]
                                  (some-> @*downloaded-update bean/->js))]
    (init-auto-updater! win)
    (.handle ipcMain check-channel check-listener)
    (.handle ipcMain install-channel install-listener)
    (.handle ipcMain get-downloaded-channel get-downloaded-listener)
    #(do
       (dispose-listeners!)
       (.removeHandler ipcMain install-channel)
       (.removeHandler ipcMain check-channel)
       (.removeHandler ipcMain get-downloaded-channel)
       (reset! *update-pending nil)
       (reset! *downloaded-target nil))))
