(ns frontend.modules.outliner.pipeline
  (:require [clojure.string :as string]
            [frontend.db.subs :as db-subs]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.db :as ldb]))

(defn- update-editing-block-title-if-changed!
  [blocks]
  (when-let [editing-block-uuid (:block/uuid (state/get-edit-block))]
    (when-let [title (:block/title (get blocks editing-block-uuid))]
      (let [editing-title (state/get-edit-content)]
        (when (not= (string/trim (or editing-title ""))
                    (string/trim title))
          (state/set-edit-content! title))))))

(defn- recover-dirty-edit-if-needed!
  [blocks deleted delta-applied?]
  (when (state/editing?)
    (when-let [{editing-block-uuid :block/uuid
                canonical-title :block/title
                :as editing-block} (state/get-edit-block)]
      (let [draft (state/get-edit-content)
            dirty? (and (string? draft)
                        (not= draft canonical-title))]
        (when dirty?
          (cond
            (contains? deleted editing-block-uuid)
            (do
              (when delta-applied?
                (state/pub-event!
                 [:editor/recover-deleted-dirty-draft editing-block draft]))
              true)

            (when-let [incoming-title
                       (:block/title (get blocks editing-block-uuid))]
              (not= incoming-title canonical-title))
            (do
              ;; The remote/current canonical title is already materialized.
              ;; Saving the active draft once makes it an ordinary semantic op,
              ;; whose inverse keeps that canonical title available to undo.
              (when delta-applied?
                (state/pub-event! [:editor/save-current-block]))
              true)

            :else
            false))))))

(defn- current-page-deleted?
  [current-page deleted]
  (and current-page
       (some #(= current-page (str %)) (keys deleted))))

(defn- current-page-recycled?
  [current-page blocks]
  (and current-page
       (some (fn [[block-uuid block]]
               (and (= current-page (str block-uuid))
                    (ldb/recycled? block)))
             blocks)))

(defn- publish-plugin-hook!
  [tx-meta {:keys [blocks deleted]}]
  (when (and state/lsp-enabled?
             (seq blocks)
             (<= (count blocks) 1000))
    (state/pub-event!
     [:plugin/hook-db-tx
      {:blocks (vec (vals blocks))
       :deleted-block-uuids (set (keys deleted))
       :tx-data []
       :tx-meta tx-meta}])))

(defn invoke-hooks
  [{:keys [repo tx-meta delta]}]
  (if (and delta (db-subs/future-projection? delta))
    ;; A missed compact cutover broadcast is recovered from the next official
    ;; delta. Drop this one response and let mounted slots reload from G+1.
    (state/pub-event!
     [:db/projection-committed
      {:repo repo :projection-epoch (:projection-epoch delta)}])
    (let [current-projection? (or (nil? delta)
                                  (db-subs/current-projection? delta))
          delta-applied? (when delta
                           (db-subs/apply-delta! delta))]
      (when current-projection?
      (let [{:keys [initial-pages? end?]} tx-meta
            current-page (state/get-current-page)
            blocks (:blocks delta)
            deleted (:deleted delta)]
        (when (= repo (state/get-current-repo))
          (let [deleted-ids (not-empty (keep :db/id (vals deleted)))
                recycled-ids (keep (fn [block]
                                     (when (and (ldb/page? block)
                                                (ldb/recycled? block))
                                       (:db/id block)))
                                   (vals blocks))]
            (when deleted-ids
              (state/sidebar-remove-deleted-block! deleted-ids))
            (when-let [removed-page-ids (not-empty (concat deleted-ids recycled-ids))]
              (state/remove-pages-from-recent! removed-page-ids)))
          (when (and (current-page-deleted? current-page deleted)
                     (not (util/mobile?)))
            (route-handler/redirect-to-home!))

          (cond
            initial-pages?
            (when end?
              (state/pub-event! [:init/commands])
              (ui-handler/re-render-root!))

            :else
            (do
              (when (current-page-recycled? current-page blocks)
                (route-handler/redirect! {:to :home :push false}))

              (let [external-edit? (not= (:client-id tx-meta)
                                         (:client-id (state/get-state)))
                    refresh-edit? (or external-edit?
                                      (= :apply-template (:outliner-op tx-meta)))
                    recovered-dirty-edit?
                    (and external-edit?
                         (recover-dirty-edit-if-needed!
                          blocks deleted delta-applied?))]
                (when (and refresh-edit?
                           (not recovered-dirty-edit?))
                  (update-editing-block-title-if-changed! blocks)))

              (state/set-state! :editor/start-pos nil)

              (when-not (:graph/importing (state/get-state))
                (publish-plugin-hook! tx-meta delta)))))

        (when (= (:outliner-op tx-meta) :delete-page)
          (state/pub-event! [:page/deleted (:deleted-page tx-meta) tx-meta]))

        (when (= (:outliner-op tx-meta) :rename-page)
          (state/pub-event! [:page/renamed repo (:data tx-meta)])))))))
