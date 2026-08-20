(ns logseq.db-sync.worker-handler-ws-test
  (:require ["better-sqlite3" :as sqlite3]
            [cljs-bean.core :as bean]
            [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [datascript.core :as d]
            [goog.object :as gobj]
            [logseq.db-sync.checksum :as sync-checksum]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.worker.handler.ws :as ws-handler]
            [logseq.db-sync.worker.presence :as presence]
            [logseq.db-sync.worker.ws :as ws]
            [logseq.db.sqlite.export :as sqlite-export]
            [promesa.core :as p]))

(def sqlite (if (find-ns 'nbb.core) (aget sqlite3 "default") sqlite3))

(defn- ws-memory-sql
  []
  (let [db (new sqlite ":memory:" nil)
        sql #js {:_db db
                 :exec (fn [sql-str & args]
                         (let [^js stmt (.prepare db sql-str)]
                           (if (string/starts-with?
                                (-> sql-str string/trim string/lower-case)
                                "select")
                             (.apply (.-all stmt) stmt (to-array args))
                             (do
                               (.apply (.-run stmt) stmt (to-array args))
                               nil))))}]
    (storage/init-schema! sql)
    sql))

(defn- attest-ws-live!
  ([sql conn]
   (attest-ws-live! sql conn true))
  ([sql conn include-legacy?]
   (let [t (storage/get-t sql)]
     (if include-legacy?
       (storage/set-checksum! sql (sync-checksum/recompute-checksum @conn))
       (storage/delete-meta! sql :checksum))
     (storage/set-server-checksum!
      sql (sync-checksum/recompute-server-checksum @conn) t)
     (storage/mark-checksum-metadata-verified! sql t)
     (when-let [attest! (some-> (gobj/get js/globalThis "logseq")
                                (gobj/get "db_sync")
                                (gobj/get "storage")
                                (gobj/get
                                 "mark_snapshot_integrity_attested_BANG_"))]
       (attest! sql t (str (random-uuid)))))
   conn))

(defn- ws-live-conn!
  ([sql]
   (ws-live-conn! sql true))
  ([sql include-legacy?]
   (let [seed-conn (sqlite-export/create-conn)]
     (d/transact!
      seed-conn
      [{:db/ident :logseq.kv/graph-created-at
        :kv/value 1760000000000}])
     (d/store @seed-conn (storage/new-sqlite-storage sql))
     (let [conn (storage/open-conn sql)]
       (attest-ws-live! sql conn include-legacy?)))))

(defn- ws-page-block-conn!
  [sql label page-uuid block-uuid]
  (let [seed-conn (sqlite-export/create-conn)]
    (d/transact!
     seed-conn
     [{:db/ident :logseq.kv/graph-created-at
       :kv/value 1760000000000}
      {:block/uuid page-uuid
       :block/name (str label "-page")
       :block/title (str label " page")
       :block/tags :logseq.class/Page
       :block/created-at 1
       :block/updated-at 1}
      {:block/uuid block-uuid
       :block/title "before"
       :block/page [:block/uuid page-uuid]
       :block/parent [:block/uuid page-uuid]
       :block/order "a0"
       :block/created-at 2
       :block/updated-at 2}])
    (d/store @seed-conn (storage/new-sqlite-storage sql))
    (let [conn (storage/open-conn sql)]
      (attest-ws-live! sql conn))))

(defn- fake-d1
  ([rows]
   (fake-d1 rows nil))
  ([rows bind-args*]
   (let [stmt (js-obj)]
     (aset stmt "bind"
           (fn [& args]
             (when bind-args*
               (reset! bind-args* args))
             stmt))
     (aset stmt "all"
           (fn []
             (p/resolved #js {:results (clj->js rows)})))
     #js {:prepare (fn [_sql] stmt)})))

(deftest revoked-member-is-closed-before-next-websocket-message-test
  (async done
         (let [closed* (atom nil)
               sent* (atom [])
               socket (js-obj)
               sql (ws-memory-sql)
               conn (storage/open-conn sql)
               _ (d/transact!
                  conn
                  [{:db/ident :logseq.kv/graph-created-at
                    :kv/value 1760000000000}])
               self #js {:env #js {"DB" (fake-d1 [])}
                         :sql sql
                         :conn conn
                         :schema-ready true
                         :graph-id "graph-1"
                         :state #js {:getWebSockets (fn [] #js [socket])}}
               raw (protocol/encode-message {:type "hello" :client "test"})]
           (aset socket "readyState" 1)
           (aset socket "serializeAttachment" (fn [_attachment] nil))
           (aset socket "deserializeAttachment" (fn [] nil))
           (aset socket "send" (fn [message]
                                 (swap! sent* conj message)))
           (aset socket "close"
                 (fn [code reason]
                   (aset socket "readyState" 2)
                   (reset! closed* [code reason])))
           (presence/add-presence!
            self socket {:user-id "revoked-user"} "graph-1")
           (-> (ws-handler/handle-ws-message! self socket raw)
               (p/then (fn [_]
                         (is (= [1008 "graph access revoked"] @closed*))
                         (is (empty? @sent*))
                         (is (nil? (presence/get-user self socket)))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest websocket-access-check-is-cached-for-active-connection-test
  (async done
         (let [query-count* (atom 0)
               sent* (atom [])
               socket (js-obj)
               sql (ws-memory-sql)
               conn (ws-live-conn! sql)
               self #js {:env #js {"DB" #js {}}
                         :sql sql
                         :conn conn
                         :schema-ready true
                         :graph-id "graph-cache"
                         :state #js {:getWebSockets (fn [] #js [socket])}}
               raw (protocol/encode-message {:type "ping"})]
           (aset socket "readyState" 1)
           (aset socket "serializeAttachment" (fn [_attachment] nil))
           (aset socket "deserializeAttachment" (fn [] nil))
           (presence/add-presence!
            self socket {:user-id "cached-user"} "graph-cache")
           (-> (p/with-redefs
                 [index/<user-has-access-to-graph?
                  (fn [_db _graph-id _user-id]
                    (swap! query-count* inc)
                    (p/resolved true))
                  ws/send!
                  (fn [_socket message]
                    (swap! sent* conj message))]
                 (p/let [_ (ws-handler/handle-ws-message!
                            self socket raw)
                         _ (ws-handler/handle-ws-message!
                            self socket raw)]
                   (is (= 1 @query-count*))
                   (is (= [{:type "pong"} {:type "pong"}]
                          @sent*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest presence-message-broadcast-excludes-source-client-test
  (async done
         (let [source-ws #js {:readyState 1}
               peer-ws #js {:readyState 1}
               send-events (atom [])
               sql (ws-memory-sql)
               conn (ws-live-conn! sql)
               self #js {:sql sql
                         :conn conn
                         :schema-ready true
                         :state #js {:getWebSockets
                                     (fn [] #js [source-ws peer-ws])}}
               raw (protocol/encode-message {:type "presence"
                                             :editing-block-uuid "block-1"})]
           (-> (p/with-redefs
                 [presence/get-user (fn [_self _ws] {:user-id "user-1"})
                  presence/update-presence! (fn [_self _ws _patch] nil)
                  ws/send! (fn [target msg]
                             (swap! send-events conj {:ws target
                                                      :msg msg}))]
                 (ws-handler/handle-ws-message! self source-ws raw))
               (p/then
                (fn [_]
                  (is (= [peer-ws]
                         (mapv :ws @send-events)))
                  (is (= [{:type "presence"
                           :editing-block-uuid "block-1"
                           :user-id "user-1"}]
                         (mapv :msg @send-events)))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest broadcast-send-failure-isolated-from-healthy-peers-test
  (let [bad-close* (atom nil)
        healthy-raw* (atom [])
        bad-ws #js {:readyState 1
                    :send (fn [_raw]
                            (throw (js/Error. "stale socket")))
                    :close (fn [code reason]
                             (reset! bad-close* [code reason]))}
        healthy-ws #js {:readyState 1
                        :send (fn [raw]
                                (swap! healthy-raw* conj raw))}
        self #js {:state
                  #js {:getWebSockets (fn [] #js [bad-ws healthy-ws])}}]
    (is (nil? (ws/broadcast! self nil {:type "changed" :t 7})))
    (is (= [1011 "send failed"] @bad-close*))
    (is (= [{:type "changed" :t 7}]
           (mapv #(-> % js/JSON.parse
                      (js->clj :keywordize-keys true))
                 @healthy-raw*)))))

(deftest hello-message-includes-checksum-test
  (async done
         (let [sql (ws-memory-sql)
               page-uuid (random-uuid)
               block-uuid (random-uuid)
               conn (ws-page-block-conn! sql "hello" page-uuid block-uuid)
               socket #js {:readyState 1}
               sent (atom nil)
               self #js {:conn conn
                         :schema-ready true
                         :sql sql}
               raw (protocol/encode-message {:type "hello"
                                             :client "test"})]
           (-> (p/with-redefs
                 [ws/send! (fn [_target msg]
                             (reset! sent msg))]
                 (ws-handler/handle-ws-message! self socket raw))
               (p/then
                (fn [_]
                  (is (= "hello" (:type @sent)))
                  (is (number? (:t @sent)))
                  (is (string? (:checksum @sent)))
                  (is (= ["tx-upload-staged-v1"
                          "canonical-structural-move-v1"]
                         (:capabilities @sent))
                      "new-server WS hello advertises the staged upload capability")))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest hello-message-bootstraps-missing-legacy-checksum-test
  (async done
         (let [sql (ws-memory-sql)
               conn (ws-live-conn! sql false)
               socket #js {:readyState 1}
               sent (atom nil)
               self #js {:conn conn
                         :schema-ready true
                         :sql sql}
               raw (protocol/encode-message {:type "hello"
                                             :client "test"})]
           (-> (p/with-redefs
                 [ws/send! (fn [_target msg]
                             (reset! sent msg))]
                 (ws-handler/handle-ws-message! self socket raw))
               (p/then
                (fn [_]
                  (is (= "hello" (:type @sent)))
                  (is (= ["tx-upload-staged-v1"
                          "canonical-structural-move-v1"]
                         (:capabilities @sent))
                      "capability advertisement does not depend on legacy checksum availability")
                  (is (string? (:checksum @sent))
                      "integrity bootstrap republishes the legacy checksum")))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest tx-batch-message-adds-graph-and-user-context-to-transact-meta-test
  (async done
         (let [sql (ws-memory-sql)
               page-uuid (random-uuid)
               block-uuid (random-uuid)
               conn (ws-page-block-conn! sql "ws-context" page-uuid block-uuid)
               socket #js {:readyState 1}
               sent (atom nil)
               tx-metas (atom [])
               self #js {:conn conn
                         :graph-id "graph-ws"
                         :schema-ready true
                         :sql sql}
               raw (protocol/encode-message
                    {:type "tx/batch"
                     :client-revision "revision-ws"
                     :t-before (storage/get-t sql)
                     :txs [{:tx (protocol/tx->transit
                                  [[:db/add [:block/uuid block-uuid]
                                    :block/title "ws context"]])
                            :outliner-op :save-block}]})]
           (d/listen! conn ::capture-ws-context-tx-meta
                      (fn [tx-report]
                        (swap! tx-metas conj (:tx-meta tx-report))))
           (-> (p/with-redefs
                 [presence/get-user (fn [_self _ws]
                                      {:user-id "user-1"
                                       :username "alice"})
                  ws/broadcast! (fn [& _] nil)
                  ws/send! (fn [_target msg]
                             (reset! sent msg))]
                 (ws-handler/handle-ws-message! self socket raw))
               (p/then
                (fn [_]
                  (is (= "tx/batch/ok" (:type @sent)))
                  (is (= ["tx-upload-staged-v1"
                          "canonical-structural-move-v1"]
                         (:capabilities @sent))
                      "WS tx responses keep capability discovery available")
                  (is (some #(= {:op :apply-client-tx
                                 :outliner-op :save-block
                                 :graph-id "graph-ws"
                                 :client-revision "revision-ws"
                                 :username "alice"}
                                (select-keys
                                 %
                                 [:op :outliner-op :graph-id
                                  :client-revision :username]))
                            @tx-metas))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (d/unlisten! conn ::capture-ws-context-tx-meta)
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest selfhost-one-four-and-five-wire-shapes-remain-compatible-test
  (testing "the deployed Worker accepts additive client generations without requiring new fields"
    (async done
           (let [cases [{:label "selfhost.1"}
                        {:label "selfhost.4"
                         :client-revision "selfhost.4-runtime"}
                        {:label "selfhost.5"
                         :client-revision "selfhost.5-runtime"
                         :tx-id (random-uuid)}]
                 run-case!
                 (fn [{:keys [label client-revision tx-id]}]
                   (let [sql (ws-memory-sql)
                         socket #js {:readyState 1}
                         sent* (atom nil)
                         page-uuid (random-uuid)
                         block-uuid (random-uuid)
                         conn (ws-page-block-conn!
                               sql (str "compat-" label)
                               page-uuid block-uuid)
                         self #js {:conn conn
                                   :graph-id "compat-graph"
                                   :schema-ready true
                                   :sql sql}
                         tx-entry (cond->
                                    {:tx (protocol/tx->transit
                                          [[:db/add [:block/uuid block-uuid]
                                            :block/title "after"]])
                                     :outliner-op :save-block}
                                    tx-id (assoc :tx-id tx-id))
                         message
                         (cond-> {:type "tx/batch"
                                  :t-before (storage/get-t sql)
                                  :txs [tx-entry]}
                           client-revision
                           (assoc :client-revision client-revision))]
                     (-> (p/with-redefs
                           [presence/get-user (fn [& _]
                                                {:user-id "compat-user"
                                                 :username "compat"})
                            ws/broadcast! (fn [& _] nil)
                            ws/send! (fn [_target response]
                                       (reset! sent* response))]
                           (ws-handler/handle-ws-message!
                            self socket (protocol/encode-message message)))
                         (p/then
                          (fn [_]
                            (is (= "tx/batch/ok" (:type @sent*)) label)
                            (is (= "after"
                                   (:block/title
                                    (d/entity @conn
                                              [:block/uuid block-uuid])))
                                label)))
                         (p/finally (fn []
                                      (.close (aget sql "_db")))))))]
             (-> (p/loop [remaining cases]
                   (if-let [test-case (first remaining)]
                     (p/let [_ (run-case! test-case)]
                       (p/recur (rest remaining)))
                     nil))
                 (p/catch (fn [error]
                            (is false (str error))))
                 (p/finally done))))))

(deftest ws-send-serializes-tx-reject-uuids-as-strings-test
  (let [raw* (atom nil)
        ws #js {:readyState 1
                :send (fn [raw] (reset! raw* raw))}
        success-tx-id (random-uuid)
        failed-tx-id (random-uuid)]
    (ws/send! ws {:type "tx/reject"
                  :reason "db transact failed"
                  :t 3
                  :success-tx-ids [success-tx-id]
                  :failed-tx-id failed-tx-id})
    (let [message (-> @raw* js/JSON.parse (js->clj :keywordize-keys true))]
      (is (= "tx/reject" (:type message)))
      (is (= [(str success-tx-id)] (:success-tx-ids message)))
      (is (= (str failed-tx-id) (:failed-tx-id message))))))

(deftest online-users-broadcast-restored-attachment-user-test
  (let [attachment* (atom nil)
        ws #js {:readyState 1
                :serializeAttachment (fn [attachment]
                                       (reset! attachment* attachment))
                :deserializeAttachment (fn []
                                         @attachment*)}
        self #js {}
        restored-self #js {}
        user {:user-id "user-1"
              :email "user@example.com"
              :username "alice"}
        sent (atom nil)]
    (presence/add-presence! self ws user)
    (is (= {:presence/user user}
           (bean/->clj (.deserializeAttachment ws))))
    (swap! (presence/presence* restored-self)
           assoc
           ws
           (presence/attachment->user (.deserializeAttachment ws)))
    (with-redefs [ws/broadcast! (fn [_self _sender message]
                                  (reset! sent message))]
      (presence/broadcast-online-users! restored-self))
    (is (= {:type "online-users"
            :online-users [user]}
           @sent))))

(deftest restored-attachment-preserves-graph-context-for-tx-batch-test
  (async done
         (let [sql (ws-memory-sql)
               page-uuid (random-uuid)
               block-uuid (random-uuid)
               conn (ws-page-block-conn!
                     sql "restored-context" page-uuid block-uuid)
               attachment* (atom nil)
               socket #js {:readyState 1
                           :serializeAttachment
                           (fn [attachment]
                             (reset! attachment* attachment))
                           :deserializeAttachment (fn [] @attachment*)}
               initial-self #js {:graph-id "graph-before-hibernation"}
               restored-self #js {:conn conn
                                  :schema-ready true
                                  :sql sql}
               user {:user-id "user-1"
                     :username "alice"}
               tx-metas* (atom [])
               raw (protocol/encode-message
                    {:type "tx/batch"
                     :t-before (storage/get-t sql)
                     :txs [{:tx (protocol/tx->transit
                                 [[:db/add [:block/uuid block-uuid]
                                   :block/title "after hibernation"]])
                            :outliner-op :save-block}]})]
           (presence/add-presence!
            initial-self socket user "graph-before-hibernation")
           (is (= "graph-before-hibernation"
                  (presence/attachment->graph-id
                   (.deserializeAttachment socket))))
           (swap! (presence/presence* restored-self)
                  assoc
                  socket
                  (presence/attachment->user
                   (.deserializeAttachment socket)))
           (d/listen! conn ::capture-restored-graph-context
                      (fn [tx-report]
                        (swap! tx-metas* conj (:tx-meta tx-report))))
           (-> (p/with-redefs
                 [ws/broadcast! (fn [& _] nil)
                  ws/send! (fn [& _] nil)]
                 (ws-handler/handle-ws-message! restored-self socket raw))
               (p/then
                (fn [_]
                  (is (some #(= "graph-before-hibernation" (:graph-id %))
                            @tx-metas*))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (d/unlisten! conn
                                         ::capture-restored-graph-context)
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest websocket-connection-is-rejected-while-snapshot-upload-is-in-progress-test
  (async done
         (let [accepted (atom [])
               presence-events (atom [])
               sql (ws-memory-sql)
               self #js {:sql sql
                         :schema-ready true
                         :state #js {:acceptWebSocket (fn [socket]
                                                       (swap! accepted conj socket))}}
               request (js/Request. "http://localhost/sync/graph-1/ws?graph-id=graph-1"
                                    #js {:method "GET"})]
           (storage/set-meta! sql :snapshot-uploading? true)
           (-> (ws-handler/handle-ws self request)
               (p/then (fn [response]
                         (is (= 409 (.-status response)))
                         (is (empty? @accepted))
                         (is (empty? @presence-events))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))

(deftest websocket-connection-uses-graph-id-from-sync-path-test
  (async done
         (let [seen-graph-id (atom ::unset)
               bind-args* (atom nil)
               sql (ws-memory-sql)
               self #js {:sql sql
                         :schema-ready true
                         :env #js {"DB" (fake-d1
                                         [#js {"graph_ready_for_use" 0}]
                                         bind-args*)}}
               request (js/Request. "http://localhost/sync/graph-from-path"
                                    #js {:method "GET"})]
           (-> (ws-handler/handle-ws self request)
               (p/then (fn [response]
                         (reset! seen-graph-id (first @bind-args*))
                         (is (= "graph-from-path" @seen-graph-id))
                         (is (= 409 (.-status response)))))
               (p/catch (fn [error]
                          (is false (str error))))
               (p/finally (fn []
                            (.close (aget sql "_db"))
                            (done)))))))
