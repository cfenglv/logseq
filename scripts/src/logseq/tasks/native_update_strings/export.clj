(ns logseq.tasks.native-update-strings.export
  "Build-time dictionary export for the one-time native updater."
  (:require [cheshire.core :as json]))

(defn- native-text
  [key dicts locale]
  (let [value (get-in dicts [locale key])]
    (when-not (string? value)
      (throw (ex-info "Native update text must be a translated string"
                      {:locale locale :key key})))
    value))

(defn strings
  [dicts]
  (into {}
        (for [locale [:en :zh-CN]]
          [locale {:title (native-text :updater/install-error-title dicts locale)
                   :description (native-text :updater/install-error-desc dicts locale)
                   :retry-description (native-text :updater/update-retry-desc dicts locale)
                   :version (native-text :updater/update-version dicts locale)
                   :preparing (native-text :updater/preparing-update dicts locale)
                   :downloading (native-text :updater/downloading-update dicts locale)
                   :progress (native-text :updater/download-progress dicts locale)
                   :verifying (native-text :updater/verifying-update dicts locale)
                   :installing (native-text :updater/installing-update dicts locale)
                   :retry (native-text :updater/retry-update dicts locale)
                   :close (native-text :ui/close dicts locale)}])))

(defn export!
  [dicts output]
  (when-not (and (string? output) (seq output))
    (throw (ex-info "A native update text output path is required" {})))
  (spit output (str (json/generate-string (strings dicts)) "\n")))
