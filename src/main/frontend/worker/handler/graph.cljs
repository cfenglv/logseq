(ns frontend.worker.handler.graph
  "Graph metadata, favorites, and overview operations for the db worker."
  (:require [cljs.cache :as cache]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.cache :as common.cache]
            [frontend.common.thread-api :refer [def-thread-api]]
            [frontend.worker.graph-view :as graph-view]
            [frontend.worker.plain-value :as worker-plain]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [logseq.common.config :as common-config]
            [logseq.common.user-navigation :as navigation]
            [logseq.common.util :as common-util]
            [logseq.common.uuid :as common-uuid]
            [logseq.db :as ldb]
            [logseq.outliner.op :as outliner-op]))

(defn- favorite-page
  [db]
  (ldb/get-page db common-config/favorites-page-name))

(defn- favorite-block
  [db page-block-uuid]
  (let [page-block-id (:db/id (d/entity db [:block/uuid page-block-uuid]))]
    (when-let [page (and page-block-id (favorite-page db))]
      (some (fn [block]
              (when (= page-block-id (:db/id (:block/link block)))
                block))
            (ldb/get-page-blocks db (:db/id page))))))

(defn- entity->plain-map
  [db entity]
  (some-> (worker-plain/entity-forward-map db entity {})
          worker-plain/with-explicit-ref-fields-recursive))

(def-thread-api :thread-api/get-favorite-pages
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [db @conn]
      (when-let [page (favorite-page db)]
        (->> (ldb/sort-by-order (:block/_parent page))
             (keep (fn [block]
                     (some->> (:block/link block)
                              (entity->plain-map db))))
             (remove ldb/recycled?)
             vec)))))

(def-thread-api :thread-api/favorited-page?
  [repo page-block-uuid]
  (let [db @(worker-state/get-datascript-conn repo)]
    (boolean (favorite-block db page-block-uuid))))

(def-thread-api :thread-api/get-recent-pages
  [repo page-ids]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [db @conn]
      (->> page-ids
           distinct
           (take 20)
           (keep #(some->> (d/entity db %)
                           (entity->plain-map db)))
           (filter ldb/page?)
           (remove ldb/hidden?)
           (remove (fn [entity]
                     (or (and (ldb/property? entity)
                              (true? (:logseq.property/hide? entity)))
                         (string/blank? (:block/title entity)))))
           vec))))

(defn- favorite-page-ops
  [db page-block-uuid]
  (when-let [page (and (d/entity db [:block/uuid page-block-uuid])
                       (favorite-page db))]
    [[:insert-blocks [[(ldb/build-favorite-tx page-block-uuid)]
                      (:block/uuid page)
                      {}]]]))

(defn- unfavorite-page-ops
  [db page-block-uuid]
  (when-let [block (favorite-block db page-block-uuid)]
    [[:delete-blocks [[(:block/uuid block)] {}]]]))

(def-thread-api :thread-api/set-page-favorite
  [repo page-block-uuid favorite?]
  (let [conn (worker-state/get-datascript-conn repo)
        db @conn
        favorited? (boolean (favorite-block db page-block-uuid))
        ops (cond
              (and favorite? (not favorited?)) (favorite-page-ops db page-block-uuid)
              (and (not favorite?) favorited?) (unfavorite-page-ops db page-block-uuid))]
    (when (seq ops)
      (outliner-op/apply-ops! conn ops nil))
    nil))

(defn- page-block-db-id
  [db page-block-uuid]
  (let [page-block-uuid' (if (string? page-block-uuid)
                           (parse-uuid page-block-uuid)
                           page-block-uuid)]
    (:db/id (d/entity db [:block/uuid page-block-uuid']))))

(defn- reorder-favorites-ops
  [db favorite-page-uuids]
  (when-let [page (favorite-page db)]
    (let [page-block-ids (keep #(page-block-db-id db %) favorite-page-uuids)
          current-blocks (ldb/sort-by-order (ldb/get-page-blocks db (:db/id page)))]
      (->> (map vector page-block-ids current-blocks)
           (keep (fn [[page-block-id block]]
                   (when (not= page-block-id (:db/id (:block/link block)))
                     [:save-block [(assoc block :block/link page-block-id) nil]])))
           vec))))

(def-thread-api :thread-api/reorder-favorites
  [repo favorite-page-uuids]
  (let [conn (worker-state/get-datascript-conn repo)
        db @conn
        ops (reorder-favorites-ops db favorite-page-uuids)]
    (when (seq ops)
      (outliner-op/apply-ops! conn ops nil))
    nil))

(def-thread-api :thread-api/build-graph
  [repo option]
  (let [conn (worker-state/get-datascript-conn repo)]
    (graph-view/build-graph @conn option)))

(def ^:private *get-all-page-titles-cache (volatile! (cache/lru-cache-factory {})))

(defn- get-all-page-titles
  [db]
  (let [pages (ldb/get-all-pages db)]
    (sort (map :block/title pages))))

(def ^:private get-all-page-titles-with-cache
  (common.cache/cache-fn
   *get-all-page-titles-cache
   (fn [repo]
     (let [db @(worker-state/get-datascript-conn repo)]
       [[repo (:max-tx db)]
        [db]]))
   get-all-page-titles))

(def-thread-api :thread-api/get-all-page-titles
  [repo]
  (get-all-page-titles-with-cache repo))

(def-thread-api :thread-api/mobile-logs
  []
  @worker-state/*log)

(def-thread-api :thread-api/get-key-value
  [repo key]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (ldb/get-key-value @conn key)))

(def-thread-api :thread-api/get-rtc-graph-uuid
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (ldb/get-graph-rtc-uuid @conn)))

(def-thread-api :thread-api/get-graph-uuid
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (or (ldb/get-graph-rtc-uuid @conn)
        (ldb/get-graph-local-uuid @conn))))

(defn- new-local-graph-uuid
  []
  (uuid (str "00000000" (subs (str (common-uuid/gen-uuid)) 8))))

(def-thread-api :thread-api/ensure-local-graph-uuid
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (or (ldb/get-graph-local-uuid @conn)
        (let [local-graph-uuid (new-local-graph-uuid)]
          (d/transact! conn
                       [(ldb/kv :logseq.kv/local-graph-uuid local-graph-uuid)]
                       {:graph-open/ensure-local-graph-uuid? true})
          local-graph-uuid))))

(defn- navigation-domain-key [service user-id scope]
  (str "user-navigation-local/" service "/" (or user-id "anonymous") "/" scope))

(defn- resolved-recents [db page-ids]
  (->> page-ids (keep #(some-> (d/entity db %) :block/uuid str)) distinct
       (take navigation/recents-limit) vec))

(defn- navigation-source [repo service user-id legacy-recents]
  (when-not (and (string? service) (not (string/blank? service))
                 (or (nil? user-id) (and (string? user-id) (not (string/blank? user-id))))
                 (vector? legacy-recents) (every? integer? legacy-recents))
    (throw (ex-info "Invalid navigation source" {:code :navigation-invalid-context})))
  (let [conn (worker-state/get-datascript-conn repo)
        local-scope (str ((get @frontend.common.thread-api/*thread-apis :thread-api/ensure-local-graph-uuid) repo))
        db @conn
        ops-db (worker-state/get-client-ops-conn repo)
        binding (when user-id
                  (-> (client-op/insert-sync-meta-value-if-absent!
                       ops-db "user-navigation-legacy-recents-owner"
                       (ldb/write-transit-str [local-scope service user-id (resolved-recents db legacy-recents)]))
                      :value ldb/read-transit-str))
        recents (if user-id
                  (if (= [local-scope service user-id] (vec (take 3 binding))) (nth binding 3) [])
                  (resolved-recents db legacy-recents))
        key (navigation-domain-key service user-id local-scope)
        device (str (random-uuid))
        favorites (->> ((get @frontend.common.thread-api/*thread-apis :thread-api/get-favorite-pages) repo)
                       (mapv #(str (:block/uuid %))))
        initial {:device-id device :transfer-id (str (random-uuid))
                 :values {(navigation/ident :favorites-seed local-scope nil) favorites
                          (navigation/ident :recents-seed local-scope device) recents}}
        encoded (:value (client-op/insert-sync-meta-value-if-absent! ops-db key (ldb/write-transit-str initial)))
        domain (ldb/read-transit-str encoded)]
    {:local-scope local-scope :remote-scope (some-> (ldb/get-graph-rtc-uuid db) str)
     :key key :encoded encoded :domain domain}))

(def-thread-api :thread-api/user-navigation-source
  [repo service user-id legacy-recents]
  (let [{:keys [local-scope remote-scope domain]} (navigation-source repo service user-id legacy-recents)]
    {:local-scope local-scope :remote-scope remote-scope :domain domain
     :snapshot (navigation/snapshot (:values domain) local-scope [(:transfer-id domain)])}))

(def-thread-api :thread-api/user-navigation-local
  [repo service user-id legacy-recents operation args]
  (let [{:keys [local-scope key encoded domain]} (navigation-source repo service user-id legacy-recents)
        {:keys [device-id values]} domain
        [target selected?] args
        visits-key (navigation/ident :visits local-scope device-id)
        seed-key (navigation/ident :recents-seed local-scope device-id)
        values (case operation
                 :favorite (let [ident (navigation/ident :favorite local-scope target)]
                             (when-not (and (common-util/uuid-string? target) (boolean? selected?))
                               (throw (ex-info "Invalid favorite" {:code :navigation-invalid-value})))
                             (assoc values ident (navigation/favorite-value (get values ident) selected? (str (random-uuid)) (common-util/time-ms))))
                 :visit (do
                          (when-not (common-util/uuid-string? target)
                            (throw (ex-info "Invalid recent page" {:code :navigation-invalid-value})))
                          (assoc values visits-key (navigation/visit-value (get values visits-key) target (common-util/time-ms))))
                 :reorder (do
                            (when-not (and (vector? target) (every? common-util/uuid-string? target))
                              (throw (ex-info "Invalid favorite order" {:code :navigation-invalid-value})))
                            (assoc values (navigation/ident :order local-scope nil) (navigation/order-value values local-scope target)))
                 :remove-recents (let [targets (set target)]
                                   (when-not (and (vector? target) (every? common-util/uuid-string? target))
                                     (throw (ex-info "Invalid recent pages" {:code :navigation-invalid-value})))
                                   (assoc values visits-key (vec (remove #(targets (first %)) (get values visits-key)))
                                          seed-key (vec (remove targets (get values seed-key)))))
                 (throw (ex-info "Unknown navigation operation" {:code :navigation-invalid-operation})))]
    (when-not (client-op/compare-and-set-sync-meta-value! (worker-state/get-client-ops-conn repo)
                                                        key encoded (ldb/write-transit-str (assoc domain :values values)))
      (throw (ex-info "Navigation source changed" {:code :navigation-save-failed})))
    (navigation/snapshot values local-scope [(:transfer-id domain)])))

(def-thread-api :thread-api/user-navigation-nodes
  [repo targets recent?]
  (when-not (and (vector? targets) (every? common-util/uuid-string? targets) (boolean? recent?))
    (throw (ex-info "Invalid navigation targets" {:code :navigation-invalid-value})))
  (let [db @(worker-state/get-datascript-conn repo)]
    (->> targets (keep #(d/entity db [:block/uuid (uuid %)]))
         (remove ldb/hidden?)
         (filter #(or (not recent?) (and (ldb/page? %) (not (string/blank? (:block/title %))))))
         (mapv #(entity->plain-map db %)))))
