(ns electron.updater-config)

(defn selfhost-version?
  [version]
  (boolean (re-find #"-selfhost(?:\.|$)" (or version ""))))

(defn updater-channel
  [platform arch]
  (case platform
    "win32" (when (#{"x64" "arm64"} arch)
              (str "latest-" arch))
    "darwin" (when (#{"x64" "arm64"} arch)
               (str "latest-" arch))
    nil))

(defn updater-options
  [version platform arch]
  {:channel (updater-channel platform arch)
   ;; electron-updater treats any SemVer prerelease component as opting into
   ;; GitHub prereleases. Selfhost releases deliberately use a SemVer
   ;; prerelease identifier while being published as production releases, so
   ;; they must use GitHub's /releases/latest selection path.
   :allow-prerelease? (when (selfhost-version? version) false)
   :allow-downgrade? false})
