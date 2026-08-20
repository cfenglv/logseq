(ns electron.updater-config)

(defn selfhost-version?
  [version]
  (boolean (re-find #"-selfhost(?:\.|$)" (or version ""))))

(def selfhost-nightly-feed-url
  "https://github.com/cfenglv/logseq/releases/download/nightly")

(defn- valid-nightly-date?
  [value]
  (if (nil? value)
    true
    (let [year (js/parseInt (subs value 0 4))
          month (js/parseInt (subs value 4 6))
          day (js/parseInt (subs value 6 8))
          leap? (or (zero? (mod year 400))
                    (and (zero? (mod year 4))
                         (not (zero? (mod year 100)))))
          days-in-month [0 31 (if leap? 29 28) 31 30 31 30
                         31 31 30 31 30 31]]
      (and (pos? year)
           (<= 1 month 12)
           (<= 1 day (nth days-in-month month))))))

(defn- selfhost-updater-version-match
  [version]
  (let [match (re-matches
               #"\d+\.\d+\.\d+-selfhost\.([1-9]\d*)(?:\.nightly\.(\d{8}))?"
               (or version ""))]
    (when (valid-nightly-date? (some-> match (nth 2)))
      match)))

(defn- selfhost-updater-revision
  [version]
  (some-> (selfhost-updater-version-match version)
          second
          js/parseInt))

(defn- selfhost-nightly?
  [version]
  (some? (some-> (selfhost-updater-version-match version)
                 (nth 2))))

(defn project-signed-macos-updater?
  [version platform]
  (and (= "darwin" platform)
       (let [revision (selfhost-updater-revision version)]
         (and revision (>= revision 5)))))

(defn- signed-macos-updater-channel?
  [version]
  ;; selfhost.4's ad-hoc signature pins Squirrel.Mac to that exact cdhash.
  ;; selfhost.5 is the manually installed start of a new signed trust chain.
  (project-signed-macos-updater? version "darwin"))

(defn updater-channel
  [version platform arch]
  (case platform
    "win32" (when (#{"x64" "arm64"} arch)
              (str "latest-" arch))
    "darwin" (when (#{"x64" "arm64"} arch)
               (if (selfhost-version? version)
                 (when (selfhost-updater-revision version)
                   (str (cond
                          (and (signed-macos-updater-channel? version)
                               (selfhost-nightly? version))
                          "selfhost-macos-v2-nightly-"

                          (signed-macos-updater-channel? version)
                          "selfhost-macos-v2-"

                          :else
                          "latest-")
                        arch))
                 (str "latest-" arch)))
    nil))

(defn updater-options
  [version platform arch]
  (let [selfhost? (selfhost-version? version)
        nightly? (and selfhost? (selfhost-nightly? version))]
    (cond-> {:channel (updater-channel version platform arch)
             ;; Stable selfhost clients stay on GitHub's production /latest
             ;; path. Nightly clients use the isolated rolling prerelease
             ;; assets through GenericProvider instead.
             :allow-prerelease? (when selfhost? nightly?)
             :allow-downgrade? false}
      nightly?
      (assoc :feed-url selfhost-nightly-feed-url))))
