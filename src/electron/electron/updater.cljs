(ns electron.updater
  (:require [cljs-bean.core :as bean]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [electron.updater-config :as updater-config]
            [electron.utils :refer [*win prod?]]
            [frontend.version :refer [version]]
            ["child_process" :as child-process]
            ["electron" :refer [app ipcMain]]
            ["fs" :as fs]
            ["path" :as node-path]
            ["url" :refer [pathToFileURL]]
            ["electron-updater" :refer [autoUpdater]]))

(def *update-pending (atom nil))
(def *downloaded-update (atom nil))
(def *project-update (atom nil))
(def *project-signature-module (atom nil))
(def debug (partial logger/debug "[updater]"))
(def electron-version version)

(declare normalize-payload emit-update! emit-update-downloaded! emit-completed!)

(defn- project-signed-macos-updater?
  []
  (updater-config/project-signed-macos-updater?
   electron-version
   (.-platform js/process)))

(defn- <project-signature-module!
  []
  (or @*project-signature-module
      (let [module-url (-> (node-path/join (.-resourcesPath js/process)
                                           "project-updater-signature.mjs")
                           pathToFileURL
                           (.-href))
            module-promise (js* "import(~{})" module-url)]
        (reset! *project-signature-module module-promise)
        module-promise)))

(defn- <validate-project-update-info
  [update-info]
  (-> (<project-signature-module!)
      (.then
       (fn [^js signature-module]
         (.validateProjectUpdateSignature
          signature-module
          #js {:arch (.-arch js/process)
               :currentVersion electron-version
               :updateInfo update-info})))))

(defn- downloaded-zip
  [paths]
  (let [zip-paths (->> (array-seq paths)
                       (filter #(and (string? %)
                                     (.endsWith (.toLowerCase %) ".zip"))))]
    (when-not (= 1 (count zip-paths))
      (throw (js/Error. "project updater requires exactly one downloaded ZIP")))
    (first zip-paths)))

(defn- remember-project-update!
  [^js win update-info ^js manifest paths]
  (let [archive (downloaded-zip paths)
        payload (normalize-payload update-info)]
    (reset! *project-update
            {:archive archive
             :arch (.-arch manifest)
             :sha512 (.-sha512 manifest)
             :signature (.-signature manifest)
             :size (.-size manifest)
             :version (.-version manifest)})
    (reset! *downloaded-update payload)
    (logger/info "[project-update-downloaded]"
                 {:archive archive
                  :arch (.-arch manifest)
                  :version (.-version manifest)})
    (emit-update! win "update-downloaded" payload)
    (emit-update-downloaded! payload)
    (emit-completed! win)))

(defn- <download-project-update!
  [^js win update-info]
  (-> (<validate-project-update-info update-info)
      (.then
       (fn [manifest]
         (-> (.downloadUpdate autoUpdater)
             (.then #(remember-project-update!
                      win update-info manifest %)))))))

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

(defn- configure-auto-updater!
  []
  (let [platform (.-platform js/process)
        arch (.-arch js/process)
        {:keys [channel allow-prerelease? allow-downgrade?] :as options}
        (updater-config/updater-options electron-version platform arch)]
    (when (some? allow-prerelease?)
      (set! (.-allowPrerelease autoUpdater) allow-prerelease?))
    (when channel
      (set! (.-channel autoUpdater) channel))
    ;; Keep the original downgrade policy even though setting channel flips it on.
    (set! (.-allowDowngrade autoUpdater) allow-downgrade?)
    (debug "configure-auto-updater" (assoc options
                                           :platform platform
                                           :arch arch
                                           :version electron-version)))
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
          ;; The project-signed macOS path only becomes installable after the
          ;; download promise yields the exact ZIP path and the signed manifest
          ;; has been bound to it. Squirrel's event cannot cross that boundary.
          (when-not (project-signed-macos-updater?)
            (let [payload (normalize-payload info)]
              (reset! *downloaded-update payload)
              (logger/info "[update-downloaded]" payload)
              (emit-update! win "update-downloaded" payload)
              (emit-update-downloaded! payload)
              (emit-completed! win))))

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
  (let [project-signed? (project-signed-macos-updater?)]
    ;; electron-updater may download the ZIP, but Squirrel must never install
    ;; it for the project-signed chain.
    (set! (.-autoDownload autoUpdater)
          (and auto-download? (not project-signed?)))
  (-> (.checkForUpdates autoUpdater)
      (.then
       (fn [^js result]
         (cond
           (and project-signed?
                auto-download?
                (true? (.-isUpdateAvailable result)))
           (<download-project-update! win (.-updateInfo result))

           ;; Manual checks without auto download need an explicit terminal event.
           (not auto-download?)
           (emit-completed! win))))
      (.catch
       (fn [error]
         (logger/warn "[updater/check]" error)
         (emit-update! win "error" (normalize-error error))
         (emit-completed! win))))))

(defn- init-auto-updater!
  [^js win]
  (when (and prod? (not= false (cfgs/get-item :auto-update)))
    (debug "init-auto-updater")
    (<check-for-updates! win true)))

(defn- project-helper-arguments
  [{:keys [archive arch sha512 signature size version]} target]
  ["--archive" archive
   "--target" target
   "--arch" arch
   "--version" version
   "--sha512" sha512
   "--size" size
   "--parent-pid" (str (.-pid js/process))
   "--relaunch" "true"
   "--signature" signature])

(defn- <verify-project-update!
  [helper arguments]
  (js/Promise.
   (fn [resolve reject]
     (.execFile
      child-process
      helper
      (bean/->js (conj arguments "--verify-only"))
      #js {:encoding "utf8"
           :maxBuffer (* 1024 1024)}
      (fn [error stdout stderr]
        (if error
          (reject
           (js/Error.
            (str "project updater preflight failed: "
                 (or (not-empty stderr)
                     (not-empty stdout)
                     (.-message error)))))
          (resolve true)))))))

(defn- <launch-project-update!
  [helper arguments]
  (js/Promise.
   (fn [resolve reject]
     (let [child (.spawn child-process
                         helper
                         (bean/->js arguments)
                         #js {:detached true
                              :stdio "ignore"})]
       (.once child "error" reject)
       (.once child "spawn"
              (fn []
                (.unref child)
                (resolve true)
                (.quit app)))))))

(defn install-downloaded-update!
  []
  (if-not (project-signed-macos-updater?)
    (.quitAndInstall autoUpdater false true)
    (if-let [{:keys [archive arch sha512 signature size version]} @*project-update]
      (let [helper (node-path/join (.-resourcesPath js/process)
                                   "sidecar"
                                   "logseq-project-updater")
            target (node-path/resolve
                    (node-path/dirname (.-execPath js/process))
                    "../..")
            helper-stat (fs/lstatSync helper)]
        (when (or (.isSymbolicLink helper-stat)
                  (not (.isFile helper-stat))
                  (zero? (bit-and (.-mode helper-stat) 73)))
          (throw (js/Error. "project updater helper is not a real executable file")))
        (let [arguments (project-helper-arguments
                         {:archive archive
                          :arch arch
                          :sha512 sha512
                          :signature signature
                          :size size
                          :version version}
                         target)]
          ;; Keep the App running until the production helper has completed
          ;; signature, archive, bundle, architecture, and upgrade validation.
          ;; The detached install repeats all checks after quit to close TOCTOU.
          (-> (<verify-project-update! helper arguments)
              (.then #(<launch-project-update! helper arguments)))))
      (throw (js/Error. "no verified project-signed update is downloaded")))))

(defn init-updater
  [{:keys [^js win] :as _opts}]
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
        install-listener (fn [_e _quit-app?]
                           (install-downloaded-update!))
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
       (reset! *project-update nil))))
