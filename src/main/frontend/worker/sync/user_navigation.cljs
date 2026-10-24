(ns frontend.worker.sync.user-navigation
  "One private initialization step around the original RTC connection owner."
  (:require [datascript.core :as d]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.auth :as sync-auth]
            [frontend.worker.sync.util :as sync-util]
            [logseq.common.user-navigation :as navigation]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(def candidates-meta-key "user-navigation-init-candidates")

(defn private-client? [client]
  (some? (:user-navigation/context client)))

(defn active-client? [client]
  (let [current @worker-state/*db-sync-client]
    (and current (identical? (:inflight client) (:inflight current))
         (= (:user-navigation/context client) (:identity @worker-state/*user-navigation-context)))))

(defn assert-active! [client]
  (when-not (active-client? client)
    (throw (ex-info "Private navigation context closed" {:code :navigation-context-closed}))))

(defn load-candidates [repo]
  (let [db (worker-state/get-client-ops-conn repo)
        stored (client-op/insert-sync-meta-value-if-absent! db candidates-meta-key (ldb/write-transit-str {}))]
    (ldb/read-transit-str (:value stored))))

(defn save-candidates! [repo candidates]
  (let [db (worker-state/get-client-ops-conn repo)
        stored (client-op/read-sync-meta-value db candidates-meta-key)]
    (when-not (client-op/compare-and-set-sync-meta-value! db candidates-meta-key (:value stored)
                                                       (ldb/write-transit-str candidates))
      (throw (ex-info "Private navigation initialization changed" {:code :navigation-context-changed})))
    (reset! (:candidates @worker-state/*user-navigation-context) candidates)))

(defn- pulled-candidate? [db scope {:keys [device-id transfer-id]}]
  (every? #(some? (d/entity db %))
          (cond-> [(navigation/ident :favorites-seed scope nil)
                   (navigation/ident :recents-seed scope device-id)]
            transfer-id (conj (navigation/ident :transfer-once scope transfer-id)))))

(defn retire-pulled-candidates! [repo db]
  (let [context @worker-state/*user-navigation-context
        candidates @(:candidates context)
        remaining (into {} (remove (fn [[scope candidate]] (pulled-candidate? db scope candidate))) candidates)]
    (when (not= candidates remaining)
      (save-candidates! repo remaining))))

(defn- eligible-candidates [context]
  (apply dissoc @(:candidates context) @(:denied-scopes context)))

(defn <initialize!
  [client token]
  (let [context @worker-state/*user-navigation-context]
    (assert-active! client)
    (when-not (= (:user-id context) (sync-auth/get-user-uuid token))
      (throw (ex-info "Private navigation requires the current account" {:code :navigation-auth-mismatch})))
    (if-let [inflight @(:initializing context)]
      inflight
      (let [request
            (p/loop []
              (assert-active! client)
              (let [candidates (eligible-candidates context)
                    body {:scopes (mapv (fn [candidate]
                                         (cond-> candidate
                                           (contains? candidate :values) (update :values ldb/write-transit-str)))
                                       (vals candidates))}]
                (p/let [response (sync-util/fetch-json
                                 (str (:http-base context) "/user/navigation/init")
                                 {:method "POST" :headers {"content-type" "application/json"}
                                  :body (js/JSON.stringify (clj->js body))} {})]
                  (assert-active! client)
                  (when-not (= (:graph-id client) (:graph-id response))
                    (throw (ex-info "Unexpected private navigation graph" {:code :navigation-identity-mismatch})))
                  (swap! (:denied-scopes context) into (keep #(when (= "forbidden" (:status %)) (:scope %)) (:scopes response)))
                  (let [requested (apply dissoc candidates @(:denied-scopes context))]
                    (when (not= requested (eligible-candidates context)) (p/recur))))))]
        (reset! (:initializing context) request)
        (p/finally request (fn []
                            (when (identical? request @(:initializing context))
                              (reset! (:initializing context) nil))))))))

(defn retryable-error? [error]
  (let [{:keys [code status]} (ex-data error)]
    (and (not (contains? #{:navigation-context-closed :navigation-auth-mismatch :navigation-identity-mismatch
                          :missing-refresh-token :missing-oauth-token-url :missing-oauth-client-id} code))
         (not (contains? #{401 403 404} status)))))
