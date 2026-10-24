(ns logseq.db-sync.worker.handler.user-navigation
  "JWT-only private graph initialization; daily changes use ordinary RTC."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [lambdaisland.glogi :as log]
            [logseq.common.user-navigation :as navigation]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.malli-schema :as schema]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.worker.auth :as auth]
            [logseq.db-sync.worker.http :as http]
            [logseq.db-sync.worker.ws :as ws]
            [malli.core :as ma]
            [promesa.core :as p]))

(defn scope-values
  [db scope]
  (let [prefix (navigation/scope-prefix scope)]
    (into {} (map (fn [datom] [(:v datom) (:kv/value (d/entity db (:e datom)))]))
          (take-while #(and (= :db/ident (:a %)) (keyword? (:v %))
                            (= "logseq.kv" (namespace (:v %)))
                            (string/starts-with? (name (:v %)) prefix))
                      (d/seek-datoms db :avet :db/ident (keyword "logseq.kv" prefix))))))

(defn- decode-candidate
  [{:keys [scope device-id values] :as candidate}]
  (if values
    (let [decoded (common/read-transit values)]
      (when (schema/navigation-transfer-values-valid? decoded scope device-id)
        (assoc candidate :values decoded)))
    (assoc candidate :values {})))

(defn- <read-candidates
  [request]
  (-> (p/let [raw (common/read-json request)
              body (js->clj raw :keywordize-keys true)]
        (when (ma/validate schema/user-navigation-init-request-schema body)
          (let [candidates (mapv decode-candidate (:scopes body))]
            (when (every? some? candidates) candidates))))
      (p/catch (fn [_] nil))))

(defn <handle-public
  "The caller supplies the existing Worker/Node Graph fetch adapter."
  [request env <graph-fetch]
  (-> (p/let [claims (auth/auth-claims request env)
              user-id (some-> claims (aget "sub"))]
        (if-not (string? user-id)
          (http/unauthorized)
          (p/let [candidates (<read-candidates request)]
            (if (nil? candidates)
              (http/bad-request "invalid navigation initialization")
              (p/let [db (aget env "DB")
                      graph-id (index/<provision-navigation-graph! db user-id)
                      checked (p/all
                               (map (fn [{:keys [scope] :as candidate}]
                                      (p/let [access? (index/<user-has-access-to-graph? db scope user-id)
                                              private? (index/<private-navigation-graph? db scope)]
                                        (assoc candidate :allowed? (and access? (not private?))))) candidates))
                      allowed (filterv :allowed? checked)
                      denied (mapv (fn [item] {:scope (:scope item) :status "forbidden"}) (remove :allowed? checked))
                      payload {:scopes (mapv #(dissoc % :allowed?) allowed)}
                      response (<graph-fetch graph-id (common/write-transit payload))]
                (if-not (.-ok response)
                  response
                  (p/let [result (common/read-json response)]
                    (http/json-response nil {:graph-id graph-id
                                             :scopes (into (vec (js->clj (aget result "scopes") :keywordize-keys true)) denied)}))))))))
      (p/catch (fn [error]
                 (log/error :db-sync/navigation-init-failed {:status (:status (ex-data error))})
                 (http/error-response "navigation-server-error" 500)))))

(defn handle-graph
  "Runs synchronously inside the existing Graph owner after outer JWT and ACL."
  [^js self request]
  (if-not (= "/user-navigation/init" (.-pathname (js/URL. (.-url request))))
    (http/not-found)
    (p/let [raw (.text request)
          payload (common/read-transit raw)]
    (when-not (.-conn self)
      (set! (.-conn self) (storage/open-conn (.-sql self))))
    (let [conn (.-conn self)
          db-before @conn
          sql (.-sql self)
          conn-state @(:atom conn)
          results (try
                    (storage/with-sql-transaction!
                sql
                (fn []
                  (mapv (fn [{:keys [scope] :as candidate}]
                          (let [values (navigation/initialization-values (scope-values @conn scope) scope candidate)]
                            (when (seq values)
                              (d/transact! conn (mapv (fn [[key value]] {:db/ident key :kv/value value}) values)
                                           {:outliner-op :save-block}))
                            {:scope scope :status "initialized"})) (:scopes payload))))
                    (catch :default error
                      (reset! (:atom conn) conn-state)
                      (throw error)))]
      (when (not= db-before @conn)
        (ws/broadcast! self nil {:type "changed" :t (storage/get-t sql)}))
      (http/json-response nil {:scopes results})))))
