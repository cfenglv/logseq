(ns electron.updater-config)

(def release-line-id "selfhost-official-architecture-v1")
(def source-version "2.0.1-selfhost.7")
(def synthetic-forward-target-version "2.0.1-selfhost.8")

(def ^:private supported-targets
  #{["darwin" "arm64"]
    ["darwin" "x64"]
    ["win32" "x64"]
    ["win32" "arm64"]
    ["linux" "arm64"]
    ["linux" "x64"]})

(defn release-line-version?
  [version]
  (contains? #{source-version synthetic-forward-target-version} version))

(defn updater-channel
  [version platform arch]
  (when (and (release-line-version? version)
             (contains? supported-targets [platform arch]))
    (case platform
      ("darwin" "win32") (str release-line-id "-" arch)
      "linux" release-line-id)))

(defn updater-options
  [version platform arch]
  (when-let [channel (updater-channel version platform arch)]
    {:channel channel
     :allow-prerelease? false
     :allow-downgrade? false}))
