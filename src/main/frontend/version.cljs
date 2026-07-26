(ns ^:no-doc frontend.version
  (:require [clojure.string :as string]))

(defonce version "2.0.1-selfhost.4")

(def selfhost-build?
  (string/includes? version "-selfhost."))

(def releases-url
  (if selfhost-build?
    "https://github.com/cfenglv/logseq/releases"
    "https://github.com/logseq/logseq/releases"))

(def changelog-url
  (if selfhost-build?
    releases-url
    "https://docs.logseq.com/#/page/changelog"))

(defn commit-url
  [revision]
  (str (if selfhost-build?
         "https://github.com/cfenglv/logseq/commit/"
         "https://github.com/logseq/logseq/commit/")
       revision))
