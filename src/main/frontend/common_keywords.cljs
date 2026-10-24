(ns frontend.common-keywords
  "There are some keywords scattered throughout the codebase."
  (:require [logseq.common.defkeywords :refer [defkeyword defkeywords]]))

(defkeyword :deferred "The operation has not run; its caller retains the input.")
(defkeyword :db-sync/rebased-new-insert?
  "A still-unapplied single-block creation is being replanned in the existing local replay.")
(defkeyword :repair-active "An existing graph repair is holding background imports.")
(defkeyword :literature-zotero/repair-active
  "Retry a Zotero notification from its original cursor after graph repair.")

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

(defkeyword :literature "The shared bibliography route.")
(defkeyword :literature-manual "Explicit manual bibliography entry dialog.")
(defkeyword :literature-organization "Explicit one-off organization dialog.")
(defkeyword :allow-online? "Identifier-only metadata lookup permission for the current explicit operation.")
(defkeyword :explicit? "A one-off user action independent of background import settings.")
(defkeyword :register? "Apply verified recognition after extraction, rather than only preparing a manual form.")
(defkeyword :organization-error "The current organization stopped with an unexpected error.")
(defkeyword :literature-options "This graph's local import settings dialog.")
(defkeyword :literature-pdf-reference-choice "One incoming PDF reference choice; no historical cleanup.")
(defkeyword :notifier-error? "The current local Zotero notification task has not recovered from its last failure.")
(defkeyword :literature-attachment? "Asset render context supplied by the bibliography attachment property.")
(defkeyword :literature-action? "Explicit one-shot literature intent in the native Asset save options.")
(defkeyword :on-assets-saved "Completion callback receiving inserted native Asset/link records.")
(defkeyword :pdf-choices "Checksums mapped to the PDF identities chosen for this save only.")
(defkeyword :include-kept? "Include explicitly ordinary PDFs in this on-demand metadata page.")
(defkeyword :expected-repo "Graph captured by an explicit asynchronous page creation action.")

(defkeywords
  :literature/organization {:doc "This device's current explicit PDF organization progress."}
  :literature/file-not-local {:doc "The selected local PDF could not be read through the native Asset protocol."}
  :literature/import-state {:doc "Transient projection of the existing local Zotero import result."})
