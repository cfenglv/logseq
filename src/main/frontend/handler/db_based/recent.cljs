(ns frontend.handler.db-based.recent
  "Fns related to recent pages feature"
  (:require [frontend.handler.user-navigation :as navigation]
            [frontend.state :as state]
            [promesa.core :as p]))

(defn add-page-to-recent!
  [db-id _click-from-recent?]
  (assert db-id (number? db-id))
  (when-not (:db/restoring? (state/get-state))
    (if (navigation/enabled?)
      (p/let [page (state/<invoke-db-worker :thread-api/pull (state/get-current-repo) [:block/uuid] db-id)]
        (when-let [id (:block/uuid page)] (navigation/<visit! id)))
      (let [pages (state/get-recent-pages)]
      (when-not ((set pages) db-id)
        (let [new-pages (vec (take 15 (distinct (cons db-id pages))))]
          (state/set-recent-pages! new-pages)))))))

(defn get-recent-pages
  []
  (when-let [repo (state/get-current-repo)]
    (if (navigation/enabled?)
      (navigation/<nodes true)
      (state/<invoke-db-worker :thread-api/get-recent-pages repo (state/get-recent-pages)))))
