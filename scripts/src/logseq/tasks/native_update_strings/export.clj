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
                   :description (native-text :updater/install-error-desc dicts locale)}])))

(defn export!
  [dicts output]
  (when-not (and (string? output) (seq output))
    (throw (ex-info "A native update text output path is required" {})))
  (spit output (str (json/generate-string (strings dicts)) "\n")))
