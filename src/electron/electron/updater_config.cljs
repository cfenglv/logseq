(ns electron.updater-config)

(def update-channel "latest")

(def ^:private supported-targets
  #{["darwin" "arm64"]
    ["darwin" "x64"]
    ["win32" "x64"]
    ["win32" "arm64"]
    ["linux" "arm64"]
    ["linux" "x64"]})

(defn updater-channel
  [platform arch]
  (when (contains? supported-targets [platform arch])
    (case platform
      ("darwin" "win32") (str update-channel "-" arch)
      "linux" update-channel)))

(defn updater-options
  [platform arch]
  (when-let [channel (updater-channel platform arch)]
    {:channel channel
     :allow-prerelease? false
     :allow-downgrade? false}))
