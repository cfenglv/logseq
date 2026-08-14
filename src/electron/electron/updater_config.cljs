(ns electron.updater-config)

(def release-line-id "selfhost-official-architecture-v1")
(def source-version "2.0.1-selfhost.6")
(def synthetic-forward-target-version "2.0.1-selfhost.7")

(def ^:private supported-targets
  #{["darwin" "arm64"]
    ["darwin" "x64"]
    ["win32" "x64"]
    ["linux" "arm64"]
    ["linux" "x64"]})

(defn release-line-version?
  [version]
  (contains? #{source-version synthetic-forward-target-version} version))

(defn updater-channel
  [version platform arch]
  (when (and (release-line-version? version)
             (contains? supported-targets [platform arch]))
    release-line-id))

(defn updater-options
  [version platform arch]
  {:channel (updater-channel version platform arch)
   :allow-prerelease? false
   :allow-downgrade? false})
