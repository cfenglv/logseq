(ns frontend.common-keywords
  "There are some keywords scattered throughout the codebase."
  (:require [logseq.common.defkeywords :refer [defkeywords]]))

(defkeywords
  :block/uuid      {:doc "block's uuid"}
  :block/name      {:doc "block name, lowercase, only page-blocks have this attr"}
  :block/raw-title {:doc "like `:block/title`,
                          but when eval `(:block/raw-title block-entity)`, return raw title of this block"}
  :kv/value        {:doc "Used to store key-value, the value could be anything,
                          e.g. {:db/ident :logseq.kv/xxx :kv/value value}"}
  :user-profile/state {:doc "Current service/account profile metadata and in-memory avatar images."}
  :user-profile/self? {:doc "Whether the displayed profile belongs to the current account."}
  :user-profile/context {:doc "Service and authenticated account captured by a sync connection."}
  :user-profile/repo {:doc "Graph context of received profile metadata."}
  :user-profile/error {:doc "A live profile refresh failed without reversing the saved profile."})
