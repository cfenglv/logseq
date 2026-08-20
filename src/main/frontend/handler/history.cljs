(ns ^:no-doc frontend.handler.history
  (:require [frontend.db :as db]
            [frontend.handler.editor :as editor]
            [frontend.handler.route :as route-handler]
            [frontend.persist-db.browser :as db-browser]
            [frontend.state :as state]
            [frontend.util :as util]
            [goog.functions :refer [debounce]]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(defn- restore-cursor!
  [{:keys [editor-cursors block-content undo?]}]
  (let [cursor (if undo?
                 (first editor-cursors)
                 (or (last editor-cursors) (first editor-cursors)))
        {:keys [selected-block-uuids selection-direction block-uuid container-id start-pos end-pos]} cursor
        selected-blocks (when (seq selected-block-uuids)
                          (->> selected-block-uuids
                               (mapcat util/get-blocks-by-id)
                               vec))
        pos (if undo? (or start-pos end-pos) (or end-pos start-pos))]
    (if (seq selected-blocks)
      (state/exit-editing-and-set-selected-blocks! selected-blocks selection-direction)
      (when-let [block (db/pull [:block/uuid block-uuid])]
        (editor/edit-block! block pos
                            {:container-id container-id
                             :custom-content block-content})))))

(defn- restore-app-state!
  [state]
  (let [route-data (:route-data state)
        current-route (:route-match @state/state)
        current-route-data (db-browser/get-route-data current-route)]
    (when (and (not= route-data current-route-data) route-data
               (contains? #{:home :page :page-block :all-journals} (:to route-data)))
      (route-handler/redirect! route-data))
    (swap! state/state merge (dissoc state :route-data))))

(defn- restore-cursor-and-state!
  [result]
  (state/set-state! :history/paused? true)
  (let [{:keys [ui-state-str undo?] :as data} result]
    (if ui-state-str
      (let [{:keys [old-state new-state]} (ldb/read-transit-str ui-state-str)]
        (if undo? (restore-app-state! old-state) (restore-app-state! new-state)))
      (restore-cursor! data)))
  (state/set-state! :history/paused? false))

(defonce ^:private *history-request-tail (atom (p/resolved nil)))

(defn- enqueue-history-action!
  [f]
  ;; Undo and redo mutate the same two worker-owned stacks. They must therefore
  ;; share one queue. Keep the queue usable after a failed action while still
  ;; returning the original rejection to that action's caller.
  (let [request (p/then @*history-request-tail (fn [_] (f)))
        settled-tail (p/catch request (fn [_] nil))]
    (reset! *history-request-tail settled-tail)
    request))

(defn- undo-aux!
  [e]
  (when-not (:editor/code-block-context @state/state)
    (enqueue-history-action!
     (fn []
       (state/set-state! :editor/op :undo)
       (when-let [repo (state/get-current-repo)]
         (util/stop e)
         (p/do!
          (state/set-state! [:editor/last-replace-ref-content-tx repo] nil)
          (editor/save-current-block!)
          (state/clear-editor-action!)
          (p/let [result (state/<invoke-db-worker :thread-api/undo-redo-undo repo)]
            (restore-cursor-and-state! result))))))))
(defonce undo! (debounce undo-aux! 20))

(defn- redo-aux!
  [e]
  (when-not (:editor/code-block-context @state/state)
    (enqueue-history-action!
     (fn []
       (state/set-state! :editor/op :redo)
       (when-let [repo (state/get-current-repo)]
         (util/stop e)
         (state/clear-editor-action!)
         (p/let [result (state/<invoke-db-worker :thread-api/undo-redo-redo repo)]
           (restore-cursor-and-state! result)))))))
(defonce redo! (debounce redo-aux! 20))
