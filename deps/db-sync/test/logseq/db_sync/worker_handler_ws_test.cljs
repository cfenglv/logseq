(ns logseq.db-sync.worker-handler-ws-test
  (:require [cljs-bean.core :as bean]
            [cljs.test :refer [async deftest is]]
            [datascript.core :as d]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.protocol :as protocol]
            [logseq.db-sync.storage :as storage]
            [logseq.db-sync.test-sql :as test-sql]
            [logseq.db-sync.worker.handler.ws :as ws-handler]
            [logseq.db-sync.worker.presence :as presence]
            [logseq.db-sync.worker.ws :as ws]
            [promesa.core :as p]))

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
               self #js {:env #js {"DB" (fake-d1 [])}
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
                         (is (nil? (presence/get-user self socket)))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest websocket-access-check-is-cached-for-active-connection-test
  (async done
         (let [query-count* (atom 0)
               sent* (atom [])
               socket (js-obj)
               self #js {:env #js {"DB" #js {}}
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
               (p/finally done)))))

(deftest presence-message-broadcast-excludes-source-client-test
  (let [source-ws #js {:readyState 1}
        peer-ws #js {:readyState 1}
        send-events (atom [])
        self #js {:state #js {:getWebSockets (fn [] #js [source-ws peer-ws])}}
        raw (protocol/encode-message {:type "presence"
                                      :editing-block-uuid "block-1"})]
    (with-redefs [presence/get-user (fn [_self _ws] {:user-id "user-1"})
                  presence/update-presence! (fn [_self _ws _patch] nil)
                  ws/send! (fn [target msg]
                             (swap! send-events conj {:ws target
                                                      :msg msg}))]
      (ws-handler/handle-ws-message! self source-ws raw))

    (is (= [peer-ws]
           (mapv :ws @send-events)))
    (is (= [{:type "presence"
             :editing-block-uuid "block-1"
             :user-id "user-1"}]
           (mapv :msg @send-events)))))

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
  (let [sql (test-sql/make-sql)
        conn (storage/open-conn sql)
        ws #js {:readyState 1}
        sent (atom nil)
        self #js {:conn conn
                  :schema-ready true
                  :sql sql}
        raw (protocol/encode-message {:type "hello"
                                      :client "test"})]
    (d/transact! conn [{:block/uuid (random-uuid)
                        :block/title "hello"}])
    (with-redefs [ws/send! (fn [_target msg]
                             (reset! sent msg))]
      (ws-handler/handle-ws-message! self ws raw))
    (is (= "hello" (:type @sent)))
    (is (number? (:t @sent)))
    (is (string? (:checksum @sent)))
    (is (= ["tx-upload-staged-v1"]
           (:capabilities @sent))
        "new-server WS hello advertises the staged upload capability")))

(deftest hello-message-omits-nil-checksum-test
  (let [sql (test-sql/make-sql)
        conn (storage/open-conn sql)
        ws #js {:readyState 1}
        sent (atom nil)
        self #js {:conn conn
                  :schema-ready true
                  :sql sql}
        raw (protocol/encode-message {:type "hello"
                                      :client "test"})]
    (with-redefs [ws/send! (fn [_target msg]
                             (reset! sent msg))]
      (ws-handler/handle-ws-message! self ws raw))
    (is (= "hello" (:type @sent)))
    (is (= ["tx-upload-staged-v1"]
           (:capabilities @sent))
        "capability advertisement does not depend on graph contents")
    (is (false? (contains? @sent :checksum)))))

(deftest tx-batch-message-adds-graph-and-user-context-to-transact-meta-test
  (let [sql (test-sql/make-sql)
        conn (storage/open-conn sql)
        ws #js {:readyState 1}
        sent (atom nil)
        tx-metas (atom [])
        self #js {:conn conn
                  :graph-id "graph-ws"
                  :schema-ready true
                  :sql sql}
        raw (protocol/encode-message
             {:type "tx/batch"
              :client-revision "revision-ws"
              :t-before 0
              :txs [{:tx (protocol/tx->transit [[:db/add -1 :block/title "ws context"]])
                     :outliner-op :save-block}]})]
    (with-redefs [presence/get-user (fn [_self _ws]
                                      {:user-id "user-1"
                                       :username "alice"})
                  ws/broadcast! (fn [& _] nil)
                  ws/send! (fn [_target msg]
                             (reset! sent msg))]
      (d/listen! conn ::capture-ws-context-tx-meta
                 (fn [tx-report]
                   (swap! tx-metas conj (:tx-meta tx-report))))
      (try
        (ws-handler/handle-ws-message! self ws raw)
        (finally
          (d/unlisten! conn ::capture-ws-context-tx-meta))))
    (is (= "tx/batch/ok" (:type @sent)))
    (is (= ["tx-upload-staged-v1"]
           (:capabilities @sent))
        "WS tx responses keep capability discovery available")
    (is (some #(= {:op :apply-client-tx
                   :outliner-op :save-block
                   :graph-id "graph-ws"
                   :client-revision "revision-ws"
                   :username "alice"}
                  (select-keys % [:op :outliner-op :graph-id :client-revision :username]))
              @tx-metas))))

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
  (let [sql (test-sql/make-sql)
        conn (storage/open-conn sql)
        attachment* (atom nil)
        ws #js {:readyState 1
                :serializeAttachment (fn [attachment]
                                       (reset! attachment* attachment))
                :deserializeAttachment (fn []
                                         @attachment*)}
        initial-self #js {:graph-id "graph-before-hibernation"}
        restored-self #js {:conn conn
                           :schema-ready true
                           :sql sql}
        user {:user-id "user-1"
              :username "alice"}
        tx-metas* (atom [])
        raw (protocol/encode-message
             {:type "tx/batch"
              :t-before 0
              :txs [{:tx (protocol/tx->transit
                          [[:db/add -1 :block/title "after hibernation"]])
                     :outliner-op :save-block}]})]
    (presence/add-presence! initial-self ws user "graph-before-hibernation")
    (is (= "graph-before-hibernation"
           (presence/attachment->graph-id (.deserializeAttachment ws))))
    (swap! (presence/presence* restored-self)
           assoc
           ws
           (presence/attachment->user (.deserializeAttachment ws)))
    (with-redefs [ws/broadcast! (fn [& _] nil)
                  ws/send! (fn [& _] nil)]
      (d/listen! conn ::capture-restored-graph-context
                 (fn [tx-report]
                   (swap! tx-metas* conj (:tx-meta tx-report))))
      (try
        (ws-handler/handle-ws-message! restored-self ws raw)
        (finally
          (d/unlisten! conn ::capture-restored-graph-context))))
    (is (some #(= "graph-before-hibernation" (:graph-id %))
              @tx-metas*))))

(deftest websocket-connection-is-rejected-while-snapshot-upload-is-in-progress-test
  (async done
         (let [accepted (atom [])
               presence-events (atom [])
               sql (test-sql/make-sql)
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
                         (is (empty? @presence-events))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest websocket-connection-uses-graph-id-from-sync-path-test
  (async done
         (let [seen-graph-id (atom ::unset)
               bind-args* (atom nil)
               sql (test-sql/make-sql)
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
                         (is (= 409 (.-status response)))
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))
