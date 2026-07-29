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
(def *set-quit-dirty-state! (atom nil))
(def *default-is-update-supported (atom nil))
(def debug (partial logger/debug "[updater]"))
(def electron-version version)

(def ^:private <native-import
  ;; Keep import() out of Closure's AST: the Electron main process must load
  ;; the packaged ESM at runtime, while Closure cannot transpile dynamic imports.
  (js/Function. "moduleUrl" "return import(moduleUrl);"))

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
            module-promise (<native-import module-url)]
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

(defn- install-selfhost-update-support-policy!
  []
  (when (updater-config/selfhost-version? electron-version)
    (let [default-is-update-supported
          (or @*default-is-update-supported
              (let [support! (.bind (.-isUpdateSupported autoUpdater)
                                    autoUpdater)]
                (reset! *default-is-update-supported support!)
                support!))]
      (set!
       (.-isUpdateSupported autoUpdater)
       (fn [^js update-info]
         ;; Preserve electron-updater's minimumSystemVersion check first, then
         ;; fail closed if provider metadata crosses stable/nightly tracks.
         (-> (js/Promise.resolve
              (default-is-update-supported update-info))
             (.then
              (fn [default-supported?]
                (if-not default-supported?
                  false
                  (-> (<project-signature-module!)
                      (.then
                       (fn [^js signature-module]
                         (boolean
                          (.selfhostUpdateInfoAllowed
                           signature-module
                           #js {:arch (.-arch js/process)
                                :currentVersion electron-version
                                :platform (.-platform js/process)
                                :updateInfo update-info}))))
                      (.catch
                       (fn [error]
                         (logger/warn
                          "[updater/support] refusing invalid selfhost update"
                          error)
                         false))))))))))))

(defn- configure-auto-updater!
  []
  (let [platform (.-platform js/process)
        arch (.-arch js/process)
        {:keys [channel feed-url allow-prerelease? allow-downgrade?] :as options}
        (updater-config/updater-options electron-version platform arch)]
    (when feed-url
      (.setFeedURL autoUpdater
                   (bean/->js {:provider "generic"
                               :url feed-url
                               :channel channel})))
    (when (some? allow-prerelease?)
      (set! (.-allowPrerelease autoUpdater) allow-prerelease?))
    (when channel
      (set! (.-channel autoUpdater) channel))
    ;; Keep the original downgrade policy even though setting channel flips it on.
    (set! (.-allowDowngrade autoUpdater) allow-downgrade?)
    (install-selfhost-update-support-policy!)
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
          (when-let [set-quit-dirty-state! @*set-quit-dirty-state!]
            (set-quit-dirty-state! true))
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

(defn run-project-signed-install!
  "Runs the project-signed install handoff through one injectable production
  sequence. Verification must finish before the detached child is created.
  The dirty guard is disabled only after the child emits `spawn`, immediately
  before quitting. Every failure restores the guard and is surfaced exactly
  once through `emit-error!`."
  ([{:keys [verify! spawn-child! spawn! spawn-install! set-dirty!
            set-quit-dirty-state! set-quit-dirty! quit! quit-app!
            emit-error!]}]
   (let [;; `spawn-install!` is the public role-oriented test seam and is
         ;; intentionally zero-arity. Production `spawn-child!` consumes the
         ;; verified helper payload, so normalize both at this one boundary.
         spawn-child! (cond
                        (fn? spawn-child!) spawn-child!
                        (fn? spawn!) spawn!
                        (fn? spawn-install!) (fn [_verified] (spawn-install!))
                        :else nil)
         set-dirty! (or set-dirty!
                        set-quit-dirty-state!
                        set-quit-dirty!)
         quit! (or quit! quit-app!)
         required! (fn [label f]
                     (when-not (fn? f)
                       (throw (js/Error. (str "missing updater injection " label))))
                     f)
         verify! (required! "verify!" verify!)
         spawn-child! (required! "spawn-child!" spawn-child!)
         set-dirty! (required! "set-dirty!" set-dirty!)
         quit! (required! "quit!" quit!)
         emit-error! (required! "emit-error!" emit-error!)]
     (-> (js/Promise.resolve)
         (.then (fn [] (verify!)))
         (.then
          (fn [verified]
            (if-not verified
              (throw (js/Error. "project updater preflight did not verify"))
              (js/Promise.
               (fn [resolve reject]
                 (try
                   (let [child (spawn-child! verified)
                         settled? (atom false)]
                     (.once child "error"
                            (fn [error]
                              (when (compare-and-set! settled? false true)
                                (reject error))))
                     (.once child "spawn"
                            (fn []
                              (when (compare-and-set! settled? false true)
                                (try
                                  (.unref child)
                                  (set-dirty! false)
                                  (quit!)
                                  (resolve true)
                                  (catch :default error
                                    (reject error)))))))
                   (catch :default error
                     (reject error))))))))
         (.catch
          (fn [error]
            ;; This function is also the terminal IPC handler. Consume the
            ;; rejection after restoring state and notifying the renderer so a
            ;; child-process `error` event cannot become an unhandled Promise.
            (try
              (set-dirty! true)
              (catch :default restore-error
                (logger/warn "[updater/install] failed to restore quit guard"
                             restore-error)))
            (try
              (emit-error! error)
              (catch :default emit-error
                (logger/warn "[updater/install] failed to emit install error"
                             emit-error)))
            false)))))
  ([verify! spawn-child! set-dirty! quit! emit-error!]
   (run-project-signed-install!
    {:verify! verify!
     :spawn-child! spawn-child!
     :set-dirty! set-dirty!
     :quit! quit!
     :emit-error! emit-error!})))

(defn- emit-install-error!
  [error]
  (logger/warn "[updater/install]" error)
  (when-let [win @*win]
    (emit-update! win "error" (normalize-error error))
    (emit-completed! win)))

(defn- <project-signed-install!
  []
  (run-project-signed-install!
   {:verify!
    (fn []
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
                (.then (fn []
                         {:helper helper
                          :arguments arguments})))))
        (throw (js/Error. "no verified project-signed update is downloaded"))))
    :spawn-child!
    (fn [{:keys [helper arguments]}]
      (.spawn child-process
              helper
              (bean/->js arguments)
              #js {:detached true
                   :stdio "ignore"}))
    :set-dirty!
    (or @*set-quit-dirty-state!
        (fn [_]
          (throw (js/Error. "updater quit-state callback is unavailable"))))
    :quit! #(.quit app)
    :emit-error! emit-install-error!}))

(defn- <legacy-install!
  []
  (-> (js/Promise.resolve)
      (.then
       (fn []
         (if-let [set-quit-dirty-state! @*set-quit-dirty-state!]
           (try
             (set-quit-dirty-state! false)
             (.quitAndInstall autoUpdater false true)
             (catch :default error
               (set-quit-dirty-state! true)
               (throw error)))
           (throw (js/Error. "updater quit-state callback is unavailable")))))
      (.catch
       (fn [error]
         (when-let [set-quit-dirty-state! @*set-quit-dirty-state!]
           (set-quit-dirty-state! true))
         (emit-install-error! error)
         (throw error)))))

(defn install-downloaded-update!
  []
  (if (project-signed-macos-updater?)
    (<project-signed-install!)
    (<legacy-install!)))

(defn init-updater
  [{:keys [^js win set-quit-dirty-state!] :as _opts}]
  (configure-auto-updater!)
  (reset! *set-quit-dirty-state! set-quit-dirty-state!)
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
       (reset! *project-update nil)
       (reset! *set-quit-dirty-state! nil))))
