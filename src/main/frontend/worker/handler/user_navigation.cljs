(ns frontend.worker.handler.user-navigation
  "Local KV operations in an isolated, account-owned original db worker."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.thread-api :refer [def-thread-api]]
            [frontend.worker.db-listener :as listener]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync :as sync]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.user-navigation :as navigation-sync]
            [logseq.common.user-navigation :as navigation]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db-sync.malli-schema :as schema]
            [logseq.db.frontend.schema :as db-schema]
            [malli.core :as ma]
            [promesa.core :as p]))

(defn- context! [repo]
  (let [context @worker-state/*user-navigation-context]
    (when-not (= repo (:repo context))
      (throw (ex-info "Not the private navigation runtime" {:code :navigation-context-closed})))
    context))

(defn- scope-values [db scope]
  (let [prefix (navigation/scope-prefix scope)]
    (into {} (map (fn [datom] [(:v datom) (:kv/value (d/entity db (:e datom)))]))
          (take-while #(and (= :db/ident (:a %)) (keyword? (:v %)) (= "logseq.kv" (namespace (:v %)))
                            (string/starts-with? (name (:v %)) prefix))
                      (d/seek-datoms db :avet :db/ident (keyword "logseq.kv" prefix))))))

(defn- projected-values [context db scope]
  (let [entries (scope-values db scope)
        candidate (get @(:candidates context) scope)
        seed {(navigation/ident :favorites-seed scope nil) (:favorites-seed candidate)
              (navigation/ident :recents-seed scope (:device-id context)) (:recents-seed candidate)}]
    (if candidate (merge (:values candidate) seed entries) entries)))

(defn- snapshot [repo scope]
  (let [context (context! repo)
        db @(worker-state/get-datascript-conn repo)
        values (projected-values context db scope)]
    (navigation/snapshot values scope [(:epoch context) (:max-tx db)])))

(defmethod listener/listen-db-changes :user-navigation/changed
  [_ {:keys [repo]} {:keys [db-before db-after tx-data]}]
  (when (= repo (:repo @worker-state/*user-navigation-context))
    (let [scopes (into #{} (keep (fn [datom]
                                (let [ident (or (:db/ident (d/entity db-after (:e datom)))
                                                (:db/ident (d/entity db-before (:e datom))))]
                                  (second (navigation/ident-parts ident))))) tx-data)]
    (when (seq scopes)
      (navigation-sync/retire-pulled-candidates! repo db-after)
      (shared-service/broadcast-to-clients! :user-navigation/changed {:scopes scopes})))))

(defn configure!
  [repo {:keys [user-id http-base ws-url] :as config}]
  (when-not (and (= #{:user-id :http-base :ws-url} (set (keys config)))
                 (every? #(and (string? %) (not (string/blank? %))) [user-id http-base ws-url]))
    (throw (ex-info "Invalid private navigation context" {:code :navigation-invalid-context})))
  (let [conn (worker-state/get-datascript-conn repo)
        graph-id (navigation/graph-id user-id)
        context-identity [repo http-base user-id graph-id]
        existing @worker-state/*user-navigation-context]
    (when (or (not (string/starts-with? repo "logseq_db_user-navigation-"))
              (not= #{repo} (set (keys @worker-state/*datascript-conns)))
              (d/entid @conn :logseq.class/Page)
              (and existing (not= context-identity (:identity existing))))
      (throw (ex-info "Private navigation needs an isolated empty-KV runtime" {:code :navigation-runtime-not-isolated})))
    (let [context (or existing
                      (let [db (worker-state/get-client-ops-conn repo)
                            device (client-op/insert-sync-meta-value-if-absent! db "user-navigation-device-id"
                                                                              (ldb/write-transit-str (str (random-uuid))))]
                        {:repo repo :user-id user-id :http-base http-base :identity context-identity :epoch (str (random-uuid))
                         :device-id (ldb/read-transit-str (:value device))
                         :candidates (atom (navigation-sync/load-candidates repo)) :denied-scopes (atom #{}) :initializing (atom nil)}))]
      (reset! worker-state/*user-navigation-context context)
      (ldb/register-transact-pipeline-fn! identity)
      (listener/listen-db-changes! repo conn :handler-keys #{:user-navigation/changed})
      (when (nil? (client-op/get-local-tx repo)) (client-op/update-local-tx repo 0))
      (d/transact! conn [{:db/ident :logseq.kv/graph-uuid :kv/value (uuid graph-id)}
                        {:db/ident :logseq.kv/graph-remote? :kv/value true}
                        {:db/ident :logseq.kv/graph-rtc-e2ee? :kv/value false}
                        {:db/ident :logseq.kv/schema-version :kv/value db-schema/version}])
      (client-op/update-graph-uuid repo graph-id)
      (reset! worker-state/*db-sync-config {:enabled? true :http-base http-base :ws-url ws-url})
      (listener/listen-db-changes! repo conn :handler-keys #{:db-sync :user-navigation/changed})
      {:device-id (:device-id context) :epoch (:epoch context)})))

(def-thread-api :thread-api/configure-user-navigation
  [repo config]
  (configure! repo config))

(def-thread-api :thread-api/user-navigation-snapshot
  [repo scope]
  (snapshot repo scope))

(def-thread-api :thread-api/user-navigation-add-scope
  [repo {:keys [scope] :as candidate}]
  (let [context (context! repo)
        candidate (assoc candidate :device-id (:device-id context))
        wire-candidate (cond-> candidate (contains? candidate :values) (update :values ldb/write-transit-str))]
    (when-not (and (ma/validate schema/user-navigation-init-request-schema {:scopes [wire-candidate]})
                   (schema/navigation-transfer-values-valid? (or (:values candidate) {}) scope (:device-id context)))
      (throw (ex-info "Invalid navigation initialization" {:code :navigation-invalid-candidate})))
    (when-not (contains? @(:candidates context) scope)
      (navigation-sync/save-candidates! repo (assoc @(:candidates context) scope candidate)))
    (navigation-sync/retire-pulled-candidates! repo @(worker-state/get-datascript-conn repo))
    (swap! (:denied-scopes context) disj scope)
    (-> (sync/initialize-private-navigation! repo) (p/catch (fn [_] nil)))
    (snapshot repo scope)))

(defn- write-value! [repo scope family discriminator value]
  (context! repo)
  (when-not (and (ma/validate schema/navigation-uuid-schema scope)
                 ((get schema/navigation-value-validators family) value))
    (throw (ex-info "Invalid navigation value" {:code :navigation-invalid-value})))
  (ldb/transact! (worker-state/get-datascript-conn repo)
                 [{:db/ident (navigation/ident family scope discriminator) :kv/value value}]
                 {:outliner-op :save-block})
  (snapshot repo scope))

(def-thread-api :thread-api/user-navigation-set-favorite
  [repo scope target selected?]
  (when-not (and (ma/validate schema/navigation-uuid-schema target) (boolean? selected?))
    (throw (ex-info "Invalid favorite" {:code :navigation-invalid-value})))
  (let [context (context! repo)
        db @(worker-state/get-datascript-conn repo)
        previous (get (projected-values context db scope) (navigation/ident :favorite scope target))]
    (write-value! repo scope :favorite target
                  (navigation/favorite-value previous selected? (str (random-uuid)) (common-util/time-ms)))))

(def-thread-api :thread-api/user-navigation-visit
  [repo scope target]
  (when-not (ma/validate schema/navigation-uuid-schema target)
    (throw (ex-info "Invalid recent page" {:code :navigation-invalid-value})))
  (let [context (context! repo)
        db @(worker-state/get-datascript-conn repo)
        previous (get (projected-values context db scope) (navigation/ident :visits scope (:device-id context)))]
    (write-value! repo scope :visits (:device-id context)
                  (navigation/visit-value previous target (common-util/time-ms)))))

(def-thread-api :thread-api/user-navigation-reorder
  [repo scope targets]
  (when-not (ma/validate schema/navigation-seed-schema targets)
    (throw (ex-info "Invalid favorite order" {:code :navigation-invalid-value})))
  (let [context (context! repo)
        db @(worker-state/get-datascript-conn repo)]
    (write-value! repo scope :order nil (navigation/order-value (projected-values context db scope) scope targets))))

(def-thread-api :thread-api/user-navigation-remove-recents
  [repo scope targets]
  (when-not (ma/validate schema/navigation-seed-schema targets)
    (throw (ex-info "Invalid recent pages" {:code :navigation-invalid-value})))
  (let [context (context! repo)
        db @(worker-state/get-datascript-conn repo)
        values (projected-values context db scope)
        targets (set targets)
        device (:device-id context)
        visits-key (navigation/ident :visits scope device)
        seed-key (navigation/ident :recents-seed scope device)]
    (ldb/transact! (worker-state/get-datascript-conn repo)
                   [{:db/ident visits-key :kv/value (vec (remove #(targets (first %)) (get values visits-key)))}
                    {:db/ident seed-key :kv/value (vec (remove targets (get values seed-key)))}]
                   {:outliner-op :save-block})
    (snapshot repo scope)))
