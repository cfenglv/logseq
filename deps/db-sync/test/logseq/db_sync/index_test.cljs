(ns logseq.db-sync.index-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as string]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.node.storage :as node-storage]
            [promesa.core :as p]))

(def ^:private graph-e2ee-migration-sql
  "alter table graphs add column graph_e2ee integer default 1")
(def ^:private graph-ready-for-use-migration-sql
  "alter table graphs add column graph_ready_for_use integer default 1")
(def ^:private graph-members-graph-id-created-at-index-sql
  "create index if not exists idx_graph_members_graph_id_created_at on graph_members (graph_id, created_at)")
(def ^:private graphs-user-id-updated-at-index-sql
  "create index if not exists idx_graphs_user_id_updated_at on graphs (user_id, updated_at desc)")
(def ^:private users-email-index-sql
  "create index if not exists idx_users_email on users (email)")

(deftest index-list-includes-graph-e2ee-flag-test
  (async done
         (let [rows #js [#js {"graph_id" "graph-1"
                              "graph_name" "Graph 1"
                              "schema_version" "65"
                              "role" "manager"
                              "invited_by" nil
                              "created_at" 10
                              "updated_at" 20
                              "graph_e2ee" 0}
                         #js {"graph_id" "graph-2"
                              "graph_name" "Graph 2"
                              "schema_version" "65"
                              "role" "member"
                              "invited_by" "u1"
                              "created_at" 11
                              "updated_at" 21
                              "graph_e2ee" 1
                              "graph_ready_for_use" 0}]]
           (-> (p/with-redefs [common/<d1-all (fn [& _]
                                                (p/resolved #js {:results rows}))
                               common/get-sql-rows (fn [result]
                                                     (aget result "results"))]
                 (index/<index-list :db "user-1"))
               (p/then (fn [graphs]
                         (is (= 2 (count graphs)))
                         (is (= false (:graph-e2ee? (first graphs))))
                         (is (= true (:graph-e2ee? (second graphs))))
                         (is (= true (:graph-ready-for-use? (first graphs))))
                         (is (= false (:graph-ready-for-use? (second graphs))))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest semantic-graphs-list-is-bounded-and-excludes-e2ee-test
  (async done
         (let [call (atom nil)
               rows #js [#js {"graph_id" "graph-1" "graph_name" "test-mcp"
                              "updated_at" 20 "created_at" 10 "schema_version" "65"
                              "graph_ready_for_use" 1 "role" "manager" "invited_by" nil}
                         #js {"graph_id" "graph-2" "graph_name" "test-mcp-2"
                              "updated_at" 19 "created_at" 11 "schema_version" "65"
                              "graph_ready_for_use" 1 "role" "member" "invited_by" "u2"}]]
           (-> (p/with-redefs [common/<d1-all
                               (fn [_db sql & args]
                                 (reset! call {:sql sql :args args})
                                 (p/resolved #js {:results rows}))
                               common/get-sql-rows (fn [result] (aget result "results"))]
                 (index/<semantic-graphs-list :db "user-1" {:name "test-mcp" :limit 1 :cursor nil}))
               (p/then (fn [{:keys [graphs next-cursor]}]
                         (is (= 1 (count graphs)))
                         (is (string? next-cursor))
                         (is (string/includes? (:sql @call) "g.graph_e2ee = 0"))
                         (is (string/includes? (:sql @call) "limit ?"))
                         (is (= 2 (last (:args @call))))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest index-upsert-persists-graph-e2ee-flag-test
  (async done
         (let [called (atom nil)]
           (-> (p/with-redefs [common/now-ms (fn [] 1234)
                               common/<d1-run (fn [_db sql & args]
                                                (reset! called {:sql sql
                                                                :args args})
                                                (p/resolved {:ok true}))]
                 (index/<index-upsert! :db "graph-1" "Graph 1" "user-1" "65" false))
               (p/then (fn [_]
                         (is (string/includes? (:sql @called) "graph_e2ee"))
                         (is (= ["graph-1" "Graph 1" "user-1" "65" 0 1 1234 1234]
                                (:args @called)))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest index-upsert-persists-graph-ready-for-use-flag-test
  (async done
         (let [called (atom nil)]
           (-> (p/with-redefs [common/now-ms (fn [] 1234)
                               common/<d1-run (fn [_db sql & args]
                                                (reset! called {:sql sql
                                                                :args args})
                                                (p/resolved {:ok true}))]
                 (index/<index-upsert! :db "graph-1" "Graph 1" "user-1" "65" false false))
               (p/then (fn [_]
                         (is (string/includes? (:sql @called) "graph_ready_for_use"))
                         (is (= ["graph-1" "Graph 1" "user-1" "65" 0 0 1234 1234]
                                (:args @called)))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest user-upsert-does-not-bind-undefined-optional-claims-test
  (async done
         (let [called (atom nil)]
           (-> (p/with-redefs [common/now-ms (fn [] 1234)
                               common/<d1-run (fn [_db sql & args]
                                                (reset! called {:sql sql :args args})
                                                (p/resolved {:ok true}))]
                 (index/<user-upsert! :db #js {"sub" "minimal-claims-user"}))
               (p/then (fn [_]
                         (is (string/includes? (:sql @called) "insert into users"))
                         (is (every? #(not (identical? js/undefined %)) (:args @called)))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest index-init-runs-graph-e2ee-migration-test
  (async done
         (let [sql-calls (atom [])]
           (-> (p/with-redefs [common/<d1-all (fn [& _]
                                                (p/resolved #js {:results #js []}))
                               common/get-sql-rows (fn [result]
                                                     (aget result "results"))
                               common/<d1-run (fn [_db sql & _args]
                                                (swap! sql-calls conj (string/lower-case sql))
                                                (p/resolved {:ok true}))]
                 (index/<index-init! :db))
               (p/then (fn [_]
                         (is (some #(string/includes? % graph-e2ee-migration-sql)
                                   @sql-calls))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest index-init-runs-graph-ready-for-use-migration-test
  (async done
         (let [sql-calls (atom [])]
           (-> (p/with-redefs [common/<d1-all (fn [& _]
                                                (p/resolved #js {:results #js []}))
                               common/get-sql-rows (fn [result]
                                                     (aget result "results"))
                               common/<d1-run (fn [_db sql & _args]
                                                (swap! sql-calls conj (string/lower-case sql))
                                                (p/resolved {:ok true}))]
                 (index/<index-init! :db))
               (p/then (fn [_]
                         (is (some #(string/includes? % graph-ready-for-use-migration-sql)
                                   @sql-calls))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest index-init-ignores-duplicate-graph-e2ee-column-error-test
  (async done
         (let [sql-calls (atom [])]
           (-> (p/with-redefs [common/<d1-all (fn [& _]
                                                (p/resolved #js {:results #js []}))
                               common/get-sql-rows (fn [result]
                                                     (aget result "results"))
                               common/<d1-run (fn [_db sql & _args]
                                                (let [sql' (string/lower-case sql)]
                                                  (swap! sql-calls conj sql')
                                                  (if (string/includes? sql' graph-e2ee-migration-sql)
                                                    (p/rejected (ex-info "duplicate column name: graph_e2ee" {}))
                                                    (p/resolved {:ok true}))))]
                 (index/<index-init! :db))
               (p/then (fn [_]
                         (is (some #(string/includes? % graph-e2ee-migration-sql)
                                   @sql-calls))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest index-init-creates-indexes-test
  (async done
         (let [sql-calls (atom [])]
           (-> (p/with-redefs [common/<d1-all (fn [& _]
                                                (p/resolved #js {:results #js []}))
                               common/get-sql-rows (fn [result]
                                                     (aget result "results"))
                               common/<d1-run (fn [_db sql & _args]
                                                (swap! sql-calls conj (string/lower-case sql))
                                                (p/resolved {:ok true}))]
                 (index/<index-init! :db))
               (p/then (fn [_]
                         (is (some #(string/includes? % graph-members-graph-id-created-at-index-sql)
                                   @sql-calls))
                         (is (some #(string/includes? % graphs-user-id-updated-at-index-sql)
                                   @sql-calls))
                         (is (some #(string/includes? % users-email-index-sql)
                                   @sql-calls))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest user-profile-real-sqlite-storage-test
  (async done
         (let [dir (.mkdtempSync fs (node-path/join (.tmpdir os) "db-sync-profile-test-"))
               new-db (node-storage/open-index-db (node-path/join dir "new"))
               old-db (node-storage/open-index-db (node-path/join dir "old"))
               new-id (str "profile-new-" (random-uuid))
               old-id (str "profile-old-" (random-uuid))]
           (-> (p/let [_ (common/<d1-run old-db
                                        (str "create table users (id TEXT primary key, email TEXT, "
                                             "email_verified INTEGER, username TEXT, created_at INTEGER)"))
                       _ (common/<d1-run old-db "insert into users (id, username) values (?, ?)"
                                        old-id "Original")
                       _ (index/<index-init! new-db)
                       _ (index/<index-init! old-db)
                       _ (index/<index-init! old-db)
                       old-profile (index/<user-profile old-db old-id)
                       _ (is (= {:display-name nil :avatar-png-base64 nil
                                 :has-avatar? false :profile-revision 0 :username "Original"}
                                (select-keys old-profile [:display-name :avatar-png-base64
                                                          :has-avatar? :profile-revision :username])))
                       _ (index/<user-upsert! new-db #js {:sub new-id :email "user@example.com"
                                                        "cognito:username" "Account"})
                       saved (index/<user-profile-save! new-db new-id
                                                        {:display-name "测试 User" :avatar-png-base64 "cG5n"
                                                         :expected-revision 0})
                       _ (is (= 1 (:profile-revision saved)))
                       _ (is (true? (:has-avatar? saved)))
                       _ (index/<user-upsert! new-db #js {:sub new-id :email "changed@example.com"
                                                        "cognito:username" "New account name"})
                       after-login (index/<user-profile new-db new-id)
                       _ (is (= (select-keys saved [:display-name :avatar-png-base64 :profile-revision])
                                (select-keys after-login [:display-name :avatar-png-base64 :profile-revision])))
                       _ (is (= "New account name" (:username after-login)))
                       conflict (index/<user-profile-save! new-db new-id
                                                           {:display-name "Stale" :avatar-png-base64 nil
                                                            :expected-revision 0})
                       _ (is (nil? conflict))
                       after-conflict (index/<user-profile new-db new-id)
                       _ (is (= after-login after-conflict))
                       _ (index/<graph-member-upsert! new-db "profile-graph" new-id "member" nil)
                       members (index/<graph-members-list new-db "profile-graph")
                       _ (is (= "测试 User" (:display-name (first members))))
                       _ (is (true? (:has-avatar? (first members))))
                       _ (is (not (contains? (first members) :avatar-png-base64)))
                       metadata (index/<user-profile-metadata new-db new-id)
                       _ (is (= (select-keys saved [:user-id :display-name :has-avatar? :profile-revision]) metadata))
                       removed (index/<user-profile-save! new-db new-id
                                                          {:display-name nil :avatar-png-base64 nil
                                                           :expected-revision 1})]
                 (is (= {:display-name nil :avatar-png-base64 nil :has-avatar? false :profile-revision 2}
                        (select-keys removed [:display-name :avatar-png-base64 :has-avatar? :profile-revision]))))
               (p/catch (fn [error] (is false (str error))))
               (p/finally (fn []
                            (.close new-db)
                            (.close old-db)
                            (.rmSync fs dir #js {:recursive true})
                            (done)))))))

(deftest user-profile-d1-update-result-test
  (async done
         (-> (p/with-redefs [common/<d1-run (fn [_db _sql & _args]
                                            (p/resolved #js {:success true :meta #js {:changes 1}}))
                             index/<user-profile (fn [_db user-id]
                                                   (p/resolved {:user-id user-id :profile-revision 1}))]
               (p/let [saved (index/<user-profile-save! :db "profile-user"
                                                      {:display-name "User" :avatar-png-base64 nil
                                                       :expected-revision 0})]
                 (is (= {:user-id "profile-user" :profile-revision 1} saved))))
             (p/then (fn [_]
                       (p/with-redefs [common/<d1-run (fn [& _]
                                                      (p/resolved #js {:success true :meta #js {:changes 0}}))]
                         (p/let [conflict (index/<user-profile-save! :db "profile-user" {})]
                           (is (nil? conflict))))))
             (p/then (fn [_]
                       (p/doseq [result [#js {} #js {:meta #js {}} #js {:changes 2}]]
                         (p/with-redefs [common/<d1-run (fn [& _] (p/resolved result))]
                           (-> (index/<user-profile-save! :db "profile-user" {})
                               (p/then (fn [_] (is false "Invalid database result must fail")))
                               (p/catch (fn [error]
                                          (is (= "invalid user profile update result" (ex-message error))))))))))
             (p/catch (fn [error] (is false (str error))))
             (p/finally done))))
