(ns logseq.db.frontend.literature-source
  "Read-only source identity checks used during native replay."
  (:require [datascript.core :as d]))

(def ^:private source-record-link-query
  '[:find ?source-key
    :in $ ?binding-uuid ?paper-uuid
    :where
    [?binding :block/uuid ?binding-uuid]
    [?paper :logseq.property/literature-source-key ?key-node]
    [?key-node :block/title ?source-key]
    [?key-node :logseq.property/literature-source-target ?binding]
    [?paper :block/uuid ?paper-uuid]
    [?paper :logseq.property/literature-item? true]
    (not [?binding :logseq.property/deleted-at])
    (not [?key-node :logseq.property/deleted-at])
    (not [?paper :logseq.property/deleted-at])])

(defn find-source-record-link
  "Finds the unique live source key relating one binding to one literature
   paper. Multiple keys remain a conflict instead of choosing one silently."
  [db binding-uuid paper-uuid]
  (let [source-keys (if (and (uuid? binding-uuid) (uuid? paper-uuid))
                      (->> (d/q source-record-link-query
                                db binding-uuid paper-uuid)
                           (map first)
                           distinct
                           sort
                           vec)
                      [])]
    (case (count source-keys)
      0 {:status :missing}
      1 {:status :found
         :binding-uuid binding-uuid
         :paper-uuid paper-uuid
         :source-key (first source-keys)}
      {:status :conflict
       :binding-uuid binding-uuid
       :paper-uuid paper-uuid
       :source-keys source-keys})))

(def ^:private source-record-owner-query
  '[:find ?paper-uuid
    :in $ ?binding-uuid ?source-key
    :where
    [?binding :block/uuid ?binding-uuid]
    [?key-node :block/title ?source-key]
    [?key-node :logseq.property/literature-source-target ?binding]
    [?paper :logseq.property/literature-source-key ?key-node]
    [?paper :block/uuid ?paper-uuid]
    [?paper :logseq.property/literature-item? true]
    (not [?binding :logseq.property/deleted-at])
    (not [?key-node :logseq.property/deleted-at])
    (not [?paper :logseq.property/deleted-at])])

(defn find-source-record-owner
  "Finds the unique live Paper owning an exact source key within one binding."
  [db binding-uuid source-key]
  (let [paper-uuids (if (and (uuid? binding-uuid) (string? source-key))
                      (->> (d/q source-record-owner-query db binding-uuid source-key)
                           (map first)
                           distinct
                           (sort-by str)
                           vec)
                      [])]
    (case (count paper-uuids)
      0 {:status :missing}
      1 {:status :found
         :binding-uuid binding-uuid
         :source-key source-key
         :paper-uuid (first paper-uuids)}
      {:status :conflict
       :binding-uuid binding-uuid
       :source-key source-key
       :paper-uuids paper-uuids})))
