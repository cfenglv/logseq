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

(defkeywords
  :literature {:doc "The shared bibliography route."}
  :literature-manual {:doc "Explicit manual bibliography entry dialog."}
  :literature-organization {:doc "Explicit one-off organization dialog."}
  :literature/organization {:doc "This device's current explicit PDF organization progress."}
  :allow-online? {:doc "Identifier-only metadata lookup permission for the current explicit operation."}
  :explicit? {:doc "A one-off user action independent of background import settings."}
  :register? {:doc "Apply verified recognition after extraction, rather than only preparing a manual form."}
  :organization-error {:doc "The current organization stopped with an unexpected error."}
  :literature/file-not-local {:doc "The selected local PDF could not be read through the native Asset protocol."}
  :literature-options {:doc "This graph's local import settings dialog."}
  :literature-pdf-reference-choice {:doc "One incoming PDF reference choice; no historical cleanup."}
  :literature/import-state {:doc "Transient projection of the existing local Zotero import result."}
  :notifier-error? {:doc "The current local Zotero notification task has not recovered from its last failure."}
  :literature-attachment? {:doc "Asset render context supplied by the bibliography attachment property."}
  :literature-action? {:doc "Explicit one-shot literature intent in the native Asset save options."}
  :on-assets-saved {:doc "Completion callback receiving inserted native Asset/link records."}
  :pdf-choices {:doc "Checksums mapped to the PDF identities chosen for this save only."}
  :include-kept? {:doc "Include explicitly ordinary PDFs in this on-demand metadata page."}
  :expected-repo {:doc "Graph captured by an explicit asynchronous page creation action."})
