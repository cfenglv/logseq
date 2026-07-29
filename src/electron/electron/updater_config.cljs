(ns electron.updater-config)

(defn selfhost-version?
  [version]
  (boolean (re-find #"-selfhost(?:\.|$)" (or version ""))))

(defn- signed-macos-updater-channel?
  [version]
  ;; selfhost.4's ad-hoc signature pins Squirrel.Mac to that exact cdhash.
  ;; selfhost.5 is the manually installed start of a new signed trust chain.
  (let [revision (some-> (re-find #"-selfhost\.([1-9]\d*)(?:-|$)"
                                  (or version ""))
                         second
                         js/parseInt)]
    (and revision (>= revision 5))))

(defn updater-channel
  [version platform arch]
  (case platform
    "win32" (when (#{"x64" "arm64"} arch)
              (str "latest-" arch))
    "darwin" (when (#{"x64" "arm64"} arch)
               (str (if (signed-macos-updater-channel? version)
                      "selfhost-macos-v2-"
                      "latest-")
                    arch))
    nil))

(defn updater-options
  [version platform arch]
  {:channel (updater-channel version platform arch)
   ;; electron-updater treats any SemVer prerelease component as opting into
   ;; GitHub prereleases. Selfhost releases deliberately use a SemVer
   ;; prerelease identifier while being published as production releases, so
   ;; they must use GitHub's /releases/latest selection path.
   :allow-prerelease? (when (selfhost-version? version) false)
   :allow-downgrade? false})
