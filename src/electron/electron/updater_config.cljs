(ns electron.updater-config)

(def release-line-id "selfhost-official-architecture-v1")
(def source-version "2.0.1-selfhost.7")
(def synthetic-forward-target-version "2.0.1-selfhost.8")
(def provider-base-url
  "https://github.com/cfenglv/logseq/releases/download/selfhost-official-architecture-v1")

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
    release-line-id))

(defn updater-options
  [version platform arch]
  (when-let [channel (updater-channel version platform arch)]
    {:provider "generic"
     :feed-url provider-base-url
     :channel channel
     :allow-prerelease? false
     :allow-downgrade? false}))
