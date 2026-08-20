(ns logseq.db-sync.worker-system-kv-guard-test
  (:require
   ["better-sqlite3" :as sqlite3]
   [cljs.test :refer [async deftest is testing]]
   [clojure.string :as string]
   [datascript.core :as d]
   [datascript.db :as ddb]
   [logseq.db.frontend.kv-entity :as kv-entity]
   [logseq.db-sync.checksum :as sync-checksum]
   [logseq.db-sync.common :as common]
   [logseq.db-sync.index :as index]
   [logseq.db-sync.protocol :as protocol]
   [logseq.db-sync.snapshot :as snapshot]
   [logseq.db-sync.snapshot-integrity :as snapshot-integrity]
   [logseq.db-sync.storage :as storage]
   [logseq.db-sync.worker.handler.assets :as assets-handler]
   [logseq.db-sync.worker.handler.semantic :as semantic-handler]
   [logseq.db-sync.worker.handler.sync :as sync-handler]
   [logseq.db-sync.worker.handler.ws :as ws-handler]
   [logseq.db-sync.worker.presence :as presence]
   [logseq.db-sync.worker.ws :as ws]
   [logseq.db.sqlite.export :as sqlite-export]
   [logseq.outliner.core :as outliner-core]
   [me.tonsky.persistent-sorted-set :as sorted-set]
   [promesa.core :as p]))

(def sqlite (if (find-ns 'nbb.core) (aget sqlite3 "default") sqlite3))

(def ^:private protected-system-kv :logseq.kv/graph-created-at)
(def ^:private observed-torn-ident
  :logseq.property.view/gallery-card-height)
(def ^:private alternate-known-built-in-ident
  :logseq.property.view/gallery-card-width)

(defn- select-sql?
  [sql]
  (string/starts-with? (-> sql string/trim string/lower-case) "select"))

(defn- run-sql
  [^js stmt args]
  (.apply (.-run stmt) stmt (to-array args)))

(defn- all-sql
  [^js stmt args]
  (.apply (.-all stmt) stmt (to-array args)))

(defn- new-memory-sql
  []
  (let [db (new sqlite ":memory:" nil)]
    #js {:_db db
         :exec (fn [sql-str & args]
                 (let [stmt (.prepare db sql-str)]
                   (if (select-sql? sql-str)
                     (all-sql stmt args)
                     (do
                       (run-sql stmt args)
                       nil))))
         :close (fn [] (.close db))}))

(defn- with-memory-sql
  [f]
  (let [sql (new-memory-sql)]
    (try
      (f sql)
      (finally
        (.close sql)))))

(defn- with-memory-sql-async
  [f]
  (let [sql (new-memory-sql)]
    (-> (f sql)
        (p/finally #(.close sql)))))

(defn- with-memory-sql-pair-async
  [f]
  (let [source-sql (new-memory-sql)
        destination-sql (new-memory-sql)]
    (-> (f source-sql destination-sql)
        (p/finally (fn []
                     (.close source-sql)
                     (.close destination-sql))))))

(defn- with-memory-sql-triplet-async
  [f]
  (let [first-sql (new-memory-sql)
        second-sql (new-memory-sql)
        destination-sql (new-memory-sql)]
    (-> (f first-sql second-sql destination-sql)
        (p/finally (fn []
                     (.close first-sql)
                     (.close second-sql)
                     (.close destination-sql))))))

(defn- json-body
  [response]
  (p/let [text (.text response)]
    (js->clj (js/JSON.parse text) :keywordize-keys true)))

(defn- request-info
  ([path]
   (request-info path "GET" nil))
  ([path method body]
   (let [request (js/Request.
                  (str "http://localhost" path)
                  (clj->js (cond-> {:method method}
                             (some? body) (assoc :body body))))]
     {:request request
      :url (js/URL. (.-url request))})))

(defn- handle-route!
  [self path handler]
  (let [{:keys [request url]} (request-info path)]
    (sync-handler/handle {:self self
                          :request request
                          :url url
                          :route {:handler handler}})))

(defn- v2-metadata!
  [self]
  (handle-route! self
                 "/sync/synthetic-graph/snapshot/download-v2?graph-id=synthetic-graph"
                 :sync/snapshot-download-v2))

(defn- v1-metadata!
  [self]
  (handle-route! self
                 "/sync/synthetic-graph/snapshot/download?graph-id=synthetic-graph"
                 :sync/snapshot-download))

(defn- stream-rows!
  [self url]
  (p/let [response (sync-handler/handle-http
                    self
                    (js/Request. url #js {:method "GET"}))
          buffer (.arrayBuffer response)]
    {:response response
     :rows (snapshot/finalize-framed-buffer (js/Uint8Array. buffer))}))

(defn- concat-uint8-chunks
  [chunks]
  (let [size (reduce + (map #(.-byteLength ^js %) chunks))
        result (js/Uint8Array. size)]
    (loop [offset 0
           chunks (seq chunks)]
      (if-let [chunk (first chunks)]
        (do
          (.set result chunk offset)
          (recur (+ offset (.-byteLength ^js chunk)) (next chunks)))
        result))))

(defn- read-stream-tail!
  [reader chunks]
  (p/let [part (.read reader)]
    (if (.-done part)
      (concat-uint8-chunks chunks)
      (read-stream-tail! reader (conj chunks (.-value part))))))

(defn- seed-valid-graph!
  [conn label]
  (let [page-uuid (random-uuid)
        block-uuid (random-uuid)
        created-at (+ 1700000000000 (rand-int 1000000))]
    (d/transact!
     conn
     [{:db/ident protected-system-kv
       :kv/value created-at}
      {:block/uuid page-uuid
       :block/name (str "synthetic-" label)
       :block/title (str "Synthetic " label)}
      {:block/uuid block-uuid
       :block/title "before-concurrent-tx"
       :block/order "a0"
       :block/parent [:block/uuid page-uuid]
       :block/page [:block/uuid page-uuid]}])
    {:page-uuid page-uuid
     :block-uuid block-uuid
     :created-at created-at}))

(defn- kvs-rows
  [sql]
  (mapv (fn [row]
          [(aget row "addr")
           (aget row "content")
           (aget row "addresses")])
        (common/get-sql-rows
         (common/sql-exec
          sql
          "select addr, content, addresses from kvs order by addr"))))

(defn- persist-index-torn-ident!
  "Persist the exact observed raw-index tear: EAVT/AEVT contain a duplicate
  unique :db/ident at a detached shadow eid while AVET still resolves only the
  canonical entity. This bypasses transact validation intentionally and is
  test-only corruption setup."
  [conn shadow-eid canonical-eid ident]
  (let [canonical-datom
        (first (d/datoms @conn :eavt canonical-eid :db/ident ident))
        shadow-datom
        (d/datom shadow-eid :db/ident ident (:tx canonical-datom))
        torn-db
        (-> @conn
            (update :eavt sorted-set/conj shadow-datom
                    ddb/cmp-datoms-eavt-quick)
            (update :aevt sorted-set/conj shadow-datom
                    ddb/cmp-datoms-aevt-quick))]
    (is (some? canonical-datom)
        "the canonical built-in ident must exist before tearing indexes")
    (d/store torn-db)
    torn-db))

(defn- persist-eavt-only-torn-ident!
  "Persist the other bounded-index split: EAVT retains the detached shadow,
  while both lookup indexes AEVT and AVET select only the canonical entity."
  [conn shadow-eid canonical-eid ident]
  (let [canonical-datom
        (first (d/datoms @conn :eavt canonical-eid :db/ident ident))
        shadow-datom
        (d/datom shadow-eid :db/ident ident (:tx canonical-datom))
        torn-db
        (update @conn :eavt sorted-set/conj shadow-datom
                ddb/cmp-datoms-eavt-quick)]
    (is (some? canonical-datom))
    (d/store torn-db)
    torn-db))

(defn- persist-aevt-split-shape!
  [conn shadow-eid canonical-eid ident shape]
  (let [db @conn
        canonical-datom
        (first (d/datoms db :eavt canonical-eid :db/ident ident))
        shadow-datom
        (first (d/datoms db :eavt shadow-eid :db/ident ident))
        different-eid-datom
        (d/datom (+ canonical-eid 10) :db/ident ident
                 (:tx canonical-datom))
        wrong-canonical-tx-datom
        (d/datom canonical-eid :db/ident ident
                 (inc (:tx canonical-datom)))
        wrong-shadow-tx-datom
        (d/datom shadow-eid :db/ident ident
                 (inc (:tx shadow-datom)))
        selected
        (case shape
          :empty []
          :shadow-only [shadow-datom]
          :different-eid-only [different-eid-datom]
          :extra [canonical-datom different-eid-datom]
          :wrong-canonical-tx [wrong-canonical-tx-datom]
          :wrong-shadow-tx [canonical-datom wrong-shadow-tx-datom])
        existing-ident-datoms
        (filter #(= ident (:v %))
                (d/datoms db :aevt :db/ident))
        cleared-aevt (reduce disj (:aevt db) existing-ident-datoms)
        shaped-aevt
        (reduce (fn [index datom]
                  (sorted-set/conj index datom
                                   ddb/cmp-datoms-aevt-quick))
                cleared-aevt
                selected)
        shaped-db (assoc db :aevt shaped-aevt)]
    (d/store shaped-db)
    shaped-db))

(defn- seed-index-torn-ident-snapshot!
  [sql ident]
  (storage/init-schema! sql)
  (let [conn (storage/open-conn sql)
        canonical-eid 121
        shadow-eid 1
        canonical-uuid (random-uuid)
        _ (d/transact!
           conn
           [{:db/id canonical-eid
             :db/ident ident
             :block/uuid canonical-uuid
             :block/title "Gallery card height"}])
        graph-state (seed-valid-graph! conn "index-torn-gallery")
        _ (persist-index-torn-ident!
           conn shadow-eid canonical-eid ident)
        restored-conn (storage/open-conn sql)]
    (is (= [shadow-eid canonical-eid]
           (mapv :e
                 (filter #(= ident (:v %))
                         (d/datoms @restored-conn :eavt))))
        "EAVT must expose both the detached shadow and canonical ident")
    (is (= [canonical-eid]
           (mapv :e
                 (d/datoms @restored-conn :avet
                           :db/ident ident)))
        "AVET must expose only the canonical ident")
    (assoc graph-state
           :canonical-eid canonical-eid
           :canonical-uuid canonical-uuid
           :conn restored-conn
           :shadow-eid shadow-eid)))

(defn- seed-index-torn-gallery-snapshot!
  [sql]
  (seed-index-torn-ident-snapshot! sql observed-torn-ident))

(defn- seed-eavt-only-torn-gallery-snapshot!
  [sql]
  (storage/init-schema! sql)
  (let [conn (storage/open-conn sql)
        canonical-eid 121
        shadow-eid 1
        canonical-uuid (random-uuid)
        _ (d/transact!
           conn
           [{:db/id canonical-eid
             :db/ident observed-torn-ident
             :block/uuid canonical-uuid
             :block/title "Gallery card height"}])
        graph-state (seed-valid-graph! conn "eavt-only-torn-gallery")
        _ (persist-eavt-only-torn-ident!
           conn shadow-eid canonical-eid observed-torn-ident)
        restored-conn (storage/open-conn sql)]
    (is (= [shadow-eid canonical-eid]
           (mapv :e
                 (filter #(= observed-torn-ident (:v %))
                         (d/datoms @restored-conn :eavt)))))
    (is (= [canonical-eid]
           (mapv :e
                 (filter #(= observed-torn-ident (:v %))
                         (d/datoms @restored-conn :aevt :db/ident)))))
    (is (= [canonical-eid]
           (mapv :e
                 (d/datoms @restored-conn :avet
                           :db/ident observed-torn-ident))))
    (assoc graph-state
           :canonical-eid canonical-eid
           :canonical-uuid canonical-uuid
           :conn restored-conn
           :shadow-eid shadow-eid)))

(defn- persist-raw-datom!
  [conn datom include-avet?]
  (let [db @conn
        db' (cond-> (-> db
                        (update :eavt sorted-set/conj datom
                                ddb/cmp-datoms-eavt-quick)
                        (update :aevt sorted-set/conj datom
                                ddb/cmp-datoms-aevt-quick))
              include-avet?
              (update :avet sorted-set/conj datom
                      ddb/cmp-datoms-avet-quick))]
    (d/store db')
    db'))

(defn- snapshot-datoms-in-client-import-order
  [conn]
  (let [db @conn
        schema-version-eid
        (some-> (d/entity db :logseq.kv/schema-version) :db/id)
        ident-eids (into #{}
                         (map :e)
                         (d/datoms db :avet :db/ident))
        schema-datom?
        (fn [datom]
          (or (= schema-version-eid (:e datom))
              (and (contains? ident-eids (:e datom))
                   (or (= :db/ident (:a datom))
                       (= "db" (namespace (:a datom)))))))
        ordered-datoms
        (fn [pred]
          (sequence
           (comp (filter pred)
                 (map #(select-keys % [:e :a :v])))
           (d/datoms db :eavt)))]
    (concat (ordered-datoms schema-datom?)
            (ordered-datoms #(not (schema-datom? %))))))

(defn- replay-snapshot-rows-like-selfhost-five!
  [rows]
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (doseq [[addr content addresses] rows]
        (common/sql-exec
         sql
         (str "insert or replace into kvs (addr, content, addresses) "
              "values (?, ?, ?)")
         addr content addresses))
      (let [source-conn (storage/open-conn sql)
            target-conn (d/create-conn (:schema @source-conn))]
        (doseq [datoms (partition-all
                        1000
                        (snapshot-datoms-in-client-import-order source-conn))]
          (d/transact!
           target-conn
           (mapv (fn [{:keys [e a v]}]
                   [:db/add e a v])
                 datoms)
           {:sync-download-graph? true}))
        target-conn))))

(defn- snapshot-state-counts
  [sql]
  (let [count-table
        (fn [table]
          (-> (common/sql-exec
               sql (str "select count(*) as n from " table))
              common/get-sql-rows
              first
              (aget "n")))]
    {:downloads (count-table "snapshot_downloads")
     :exports (count-table "snapshot_kvs_exports")}))

(defn- snapshot-download-generation-rows
  [sql]
  (common/get-sql-rows
   (common/sql-exec
    sql
    (str "select download_id, generation_key, legacy, lease_count "
         "from snapshot_download_generations order by download_id"))))

(defn- snapshot-state-counts-for-download
  [sql download-id]
  (let [count-for
        (fn [table]
          (-> (common/sql-exec
               sql
               (str "select count(*) as n from " table
                    " where download_id = ?")
               download-id)
              common/get-sql-rows
              first
              (aget "n")))]
    {:downloads (count-for "snapshot_downloads")
     :exports (count-for "snapshot_kvs_exports")}))

(defn- consume-v2-stream-response!
  [response]
  (let [status (.-status response)]
    (if (= 200 status)
      (-> (.arrayBuffer response)
          (p/then
           (fn [buffer]
             {:rows
              (snapshot/finalize-framed-buffer
               (js/Uint8Array. buffer))}))
          (p/catch (fn [error] {:stream-error error})))
      (-> (json-body response)
          (p/then (fn [body] {:body body}))
          (p/catch (fn [error] {:body-error error}))))))

(defn- db-facts
  [db]
  (set (d/q '[:find ?entity ?attribute ?value
              :where [?entity ?attribute ?value]]
            db)))

(defn- rows-projection
  [rows block-uuid]
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (doseq [[addr content addresses] rows]
        (common/sql-exec
         sql
         (str "insert or replace into kvs (addr, content, addresses) "
              "values (?, ?, ?)")
         addr content addresses))
      (let [conn (storage/open-conn sql)]
        {:created-at (:kv/value (d/entity @conn protected-system-kv))
         :title (:block/title
                 (d/entity @conn [:block/uuid block-uuid]))
         :facts (db-facts @conn)}))))

(defn- download-id-from-url
  [url]
  (.get (.-searchParams (js/URL. url)) "download-id"))

(defn- frame-bytes
  [^js payload]
  (let [result (js/Uint8Array. (+ 4 (.-byteLength payload)))
        view (js/DataView. (.-buffer result))]
    (.setUint32 view 0 (.-byteLength payload) false)
    (.set result payload 4)
    result))

(defn- snapshot-upload-request
  [query rows]
  (let [body (frame-bytes (snapshot/encode-rows rows))]
    (js/Request.
     (str "http://localhost/sync/synthetic-graph/snapshot/upload-v2"
          "?graph-id=synthetic-graph&" query)
     #js {:method "POST"
          :body body})))

(defn- legacy-snapshot-upload-request
  [query rows]
  (let [body (frame-bytes (snapshot/encode-rows rows))]
    (js/Request.
     (str "http://localhost/sync/synthetic-graph/snapshot/upload"
          "?graph-id=synthetic-graph&" query)
     #js {:method "POST"
          :body body})))

(defn- malformed-legacy-snapshot-upload-request
  [query]
  (js/Request.
   (str "http://localhost/sync/synthetic-graph/snapshot/upload"
        "?graph-id=synthetic-graph&" query)
   #js {:method "POST"
        ;; Declares a ten-byte frame but deliberately supplies one byte.
        ;; This reaches the real stream parser and fails at end-of-stream.
        :body (js/Uint8Array. #js [0 0 0 10 1])}))

(defn- malformed-staged-snapshot-upload-request
  [query]
  (js/Request.
   (str "http://localhost/sync/synthetic-graph/snapshot/upload-v2"
        "?graph-id=synthetic-graph&" query)
   #js {:method "POST"
        :body (js/Uint8Array. #js [0 0 0 10 1])}))

(defn- handle-legacy-upload!
  [self request]
  (sync-handler/handle
   {:self self
    :request request
    :url (js/URL. (.-url request))
    :route {:handler :sync/snapshot-upload}}))

(defn- await-handler-result
  [result]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(settle [value]
               (if (and (some? value)
                        (fn? (.-then value)))
                 (try
                   (.then value settle reject)
                   (catch :default error
                     (reject error)))
                 (resolve value)))]
       (settle result)))))

(defn- observe-handler-result
  [result]
  (-> (await-handler-result result)
      (.then (fn [response] {:response response}))
      (.catch (fn [error] {:error error}))))

(def ^:private cleanup-contract-deadline-ms 1500)

(defn- observe-handler-result-with-cleanup-deadline
  [result]
  (js/Promise.race
   #js [(observe-handler-result result)
        (p/let [_ (p/delay cleanup-contract-deadline-ms)]
          {:timeout? true})]))

(defn- handler-failure-observed?
  [{:keys [response error]}]
  (or (some? error)
      (= 500 (some-> response .-status))))

(deftest observe-handler-result-awaits-generic-thenables-test
  (testing "the test oracle must await a handler thenable before inspecting gates"
    (async done
      (let [inner (js/Promise.resolve
                   (js/Response. "failure" #js {:status 500}))
            ;; Some handler/macro wrappers expose a thenable without being a
            ;; native js/Promise instance. Model that exact observable shape.
            wrapper #js {:then (fn [resolve reject]
                                 (.then inner resolve reject))}]
        (-> (observe-handler-result wrapper)
            (p/then
             (fn [{:keys [response] :as result}]
               (is (handler-failure-observed? result))
               (is (= 500 (.-status response)))
               (is (not (fn? (some-> response .-then)))
                   "the observed response itself must already be settled")))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest handle-http-catches-generic-thenable-rejections-test
  (testing "a non-native thenable rejection is normalized to the HTTP 500 boundary"
    (async done
      (let [self #js {}
            request (js/Request.
                     "http://localhost/sync/synthetic-graph/tx/batch"
                     #js {:method "POST" :body "{}"})
            outer-thenable
            #js {:then (fn [_resolve reject]
                         (js/setTimeout
                          #(reject
                            (js/Error. "synthetic generic thenable"))
                          0)
                         nil)}]
        (-> (p/with-redefs
              [common/read-json (fn [_] outer-thenable)]
              (observe-handler-result
               (sync-handler/handle-http self request)))
            (p/then
             (fn [{:keys [response error]}]
               (is (nil? error))
               (is (= 500 (some-> response .-status)))))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest handle-http-assimilates-async-nonchaining-json-thenable-test
  (testing "an async thenable whose .then returns nil cannot escape the HTTP lifecycle"
    (async done
      (let [work
            (with-memory-sql-async
             (fn [sql]
               (storage/init-schema! sql)
               (let [conn (storage/open-conn sql)
                     {:keys [block-uuid]}
                     (seed-valid-graph! conn "async-json-thenable")
                     self #js {:env #js {"DB" nil}
                               :sql sql :conn conn :schema-ready true}
                     t-before (storage/get-t sql)
                     payload
                     {:type "tx/batch"
                      :t-before t-before
                      :txs [{:outliner-op :save-block
                             :tx (protocol/tx->transit
                                  [[:db/add [:block/uuid block-uuid]
                                    :block/title
                                    "async-thenable-applied"]])}]}
                     request
                     (js/Request.
                      (str "http://localhost/sync/synthetic-graph/tx/batch"
                           "?graph-id=synthetic-graph")
                      #js {:method "POST" :body "{}"})]
                 (p/let [sealed (v2-metadata! self)
                         _ (is (= 200 (.-status sealed)))
                         observed
                         (p/with-redefs
                           [common/read-json
                            (fn [_]
                              #js {:then
                                   (fn [resolve _reject]
                                     (js/setTimeout
                                      #(resolve (clj->js payload)) 0)
                                     nil)})]
                           (let [result
                                 (sync-handler/handle-http self request)]
                             (is (fn? (some-> result .-then))
                                 (str "the handler must return the "
                                      "assimilated lifecycle"))
                             (js/Promise.race
                              #js [(observe-handler-result result)
                                   (js/Promise.
                                    (fn [resolve _reject]
                                      (js/setTimeout
                                       #(resolve {:timeout? true}) 100)))])))
                         _ (p/delay 5)
                         {:keys [response error]} observed]
                   (is (not (:timeout? observed))
                       "the assimilated handler must settle within the bound")
                   (is (nil? error))
                   (is (= 200 (some-> response .-status)))
                   (is (= (inc t-before) (storage/get-t sql)))
                   (is (= "async-thenable-applied"
                          (:block/title
                           (d/entity @(.-conn self)
                                     [:block/uuid block-uuid]))))))))]
        (-> work
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest semantic-patch-assimilates-async-nonchaining-handler-rejection-test
  (testing "a generic handler thenable settles before a real semantic PATCH retry"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! conn "semantic-handler-thenable")
                   self #js {:env #js {"DB" nil}
                             :sql sql :conn conn :schema-ready true}
                   request
                   (fn [title]
                     (js/Request.
                      (str "http://localhost/semantic/blocks/" block-uuid
                           "?graph-id=synthetic-graph")
                      #js {:method "PATCH"
                           :headers #js {"content-type" "application/json"}
                           :body (js/JSON.stringify #js {:title title})}))]
               (p/let [sealed (v2-metadata! self)
                       _ (is (= 200 (.-status sealed)))
                       failed
                       (p/with-redefs
                         [semantic-handler/handle
                          (fn [_]
                            #js {:then
                                 (fn [_resolve reject]
                                   (js/queueMicrotask
                                    #(reject
                                      (js/Error.
                                       "synthetic semantic handler rejection")))
                                   nil)})]
                         (let [result
                               (sync-handler/handle-http
                                self (request "must-not-apply"))]
                           (is (fn? (some-> result .-then))
                               "HTTP must retain the generic thenable lifecycle")
                           (observe-handler-result result)))
                       _ (is (= 500 (some-> failed :response .-status)))
                       _ (is (nil? (:error failed)))
                       _ (is (some? (.-conn self)))
                       retry-response
                       (sync-handler/handle-http
                        self (request "semantic-handler-retry-ok"))]
                 (is (= 200 (.-status retry-response)))
                 (is (= "semantic-handler-retry-ok"
                        (:block/title
                         (d/entity @(.-conn self)
                                   [:block/uuid block-uuid]))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(defn- table-rows
  [sql table order-by]
  (mapv #(js->clj % :keywordize-keys true)
        (common/get-sql-rows
         (common/sql-exec
          sql
          (str "select * from " table
               (when order-by (str " order by " order-by)))))))

(defn- live-durable-state
  [sql]
  {:kvs (kvs-rows sql)
   :t (storage/get-t sql)
   :checksum (storage/get-checksum sql)
   :server-checksum (storage/get-server-checksum sql)
   :server-checksum-t (storage/get-server-checksum-t sql)
   :tx-log (table-rows sql "tx_log" "t")
   :applied (table-rows sql "applied_client_txs" "identity")})

(defn- snapshot-download-durable-state
  [sql]
  {:live (live-durable-state sql)
   :downloads (table-rows sql "snapshot_downloads" "download_id")
   :exports (table-rows sql "snapshot_kvs_exports" "download_id, addr")
   :generations
   (table-rows sql "snapshot_download_generations" "download_id")
   :legacy-queue
   (storage/get-meta sql :legacy-snapshot-download-queue)})

(defn- db-checksums
  [conn]
  {:checksum (sync-checksum/recompute-checksum @conn)
   :server-checksum (sync-checksum/recompute-server-checksum @conn)})

(defn- persist-current-checksums!
  [sql conn]
  (let [{:keys [checksum server-checksum] :as result}
        (db-checksums conn)
        t (storage/get-t sql)]
    (storage/with-sql-transaction!
     sql
     (fn []
       (storage/set-checksum! sql checksum)
       (storage/set-server-checksum! sql server-checksum t)
       (storage/mark-checksum-metadata-verified! sql t)))
    result))

(defn- staging-rows
  [sql]
  (mapv (fn [row]
          [(:addr row) (:content row) (:addresses row)])
        (table-rows sql "snapshot_kvs_staging" "addr")))

(defn- sqlite-table-names
  [sql]
  (mapv #(aget % "name")
        (common/get-sql-rows
         (common/sql-exec
          sql
          (str "select name from sqlite_master where type = 'table' "
               "order by name")))))

(deftest invalid-base64-asset-stream-settles-as-http-error-test
  (testing "an invalid encoded stream rejects through the returned Promise instead of escaping"
    (async done
      (let [bucket #js {:put (fn [_key body _options]
                               (.arrayBuffer (js/Response. body)))}
            request (js/Request.
                     "http://localhost/asset"
                     #js {:method "POST" :body "not base64!"})]
        (-> (assets-handler/<put-stream!
             bucket "synthetic/image.png" (.-body request)
             {:size 4
              :content-type "text/plain"
              :checksum (apply str (repeat 64 "a"))
              :asset-type "png"
              :encoding "base64"})
            (p/then
             (fn [response]
               (is (= 400 (.-status response)))))
            (p/catch
             (fn [error]
               (is false (str "escaped rejection: " error))))
            (p/finally done))))))

(deftest r2-native-rejection-settles-open-base64-pipe-without-delete-test
  (testing "an immediate native R2 rejection settles every pipe before returning the original failure"
    (async done
      (let [cancelled (atom 0)
            encoder (js/TextEncoder.)
            source
            (js/ReadableStream.
             #js {:start
                  (fn [controller]
                    (.enqueue controller (.encode encoder "AQIDBA==")))
                  :cancel (fn [_reason]
                            (swap! cancelled inc))})
            bucket
            #js {:put (fn [_key _body _options]
                        (js/Promise.reject
                         (js/Error. "synthetic native R2 rejection")))}]
        (-> (p/let [observed
                    (observe-handler-result
                     (assets-handler/<put-stream!
                            bucket "synthetic/no-delete.png" source
                            {:size 4 :content-type "text/plain"
                             :checksum (apply str (repeat 64 "c"))
                             :asset-type "png" :encoding "base64"}))]
              (is (= "synthetic native R2 rejection"
                     (some-> observed :error .-message)))
              (is (false? (.-locked source))
                  "every upload pipe must release its source before the failure settles")
              (let [reader (.getReader source)]
                (p/let [_ (.cancel reader "test cleanup")]
                  (is (= 1 @cancelled)))))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest r2-rejection-without-abort-controller-preserves-original-error-test
  (testing "explicit stream cancellation works without AbortController and a failed delete cannot mask R2"
    (async done
      (let [original-abort-controller (.-AbortController js/globalThis)
            source-controller (atom nil)
            cancelled (atom 0)
            delete-count (atom 0)
            encoder (js/TextEncoder.)
            source
            (js/ReadableStream.
             #js {:start
                  (fn [controller]
                    (reset! source-controller controller)
                    (.enqueue controller (.encode encoder "AQIDBA==")))
                  :cancel (fn [_reason]
                            (swap! cancelled inc))})
            bucket
            #js {:put (fn [_key _body _options]
                        (js/Promise.reject
                         (js/Error. "synthetic original R2 failure")))
                 :delete (fn [_key]
                           (swap! delete-count inc)
                           (js/Promise.reject
                            (js/Error. "synthetic cleanup failure")))}]
        (aset js/globalThis "AbortController" js/undefined)
        (-> (p/let [observed
                    (js/Promise.race
                     #js [(observe-handler-result
                           (assets-handler/<put-stream!
                            bucket "synthetic/delete-reject.png" source
                            {:size 4 :content-type "text/plain"
                             :checksum (apply str (repeat 64 "d"))
                             :asset-type "png" :encoding "base64"
                             :cleanup-on-failure? true}))
                          (p/let [_ (p/delay 100)]
                            {:timeout? true})])]
              (is (not (:timeout? observed))
                  "R2 failure cleanup must be bounded without AbortController")
              (is (= "synthetic original R2 failure"
                     (some-> observed :error .-message))
                  "cleanup failure must not replace the upload error")
              (is (= 1 @delete-count))
              (is (false? (.-locked source)))
              (if (false? (.-locked source))
                (let [reader (.getReader source)]
                  (p/let [_ (.cancel reader "test cleanup")]
                    (is (= 1 @cancelled))))
                (when-let [controller @source-controller]
                  (try
                    (.close controller)
                    (catch :default _ nil)))))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally
             (fn []
               (aset js/globalThis "AbortController" original-abort-controller)
               (done))))))))

(deftest r2-failure-with-nonsettling-cancel-is-bounded-test
  (testing "a failed R2 put cannot wait forever for request-stream cancellation"
    (async done
      (let [original-error (js/Error. "synthetic bounded R2 failure")
            cancel-count (atom 0)
            never-settles (js/Promise. (fn [_resolve _reject]))
            source
            (js/ReadableStream.
             #js {:cancel (fn [_reason]
                            (swap! cancel-count inc)
                            never-settles)})
            bucket
            #js {:put (fn [_key _body _options]
                        (js/Promise.reject original-error))}]
        (-> (p/let [observed
                    (observe-handler-result-with-cleanup-deadline
                     (assets-handler/<put-stream!
                      bucket "synthetic/nonsettling-cancel.png" source
                      {:size 4
                       :content-type "application/octet-stream"
                       :checksum (apply str (repeat 64 "f"))
                       :asset-type "png"}))]
              (is (not (:timeout? observed))
                  "cleanup must not keep a failed upload request pending")
              (is (identical? original-error (:error observed))
                  "bounded cleanup must preserve the original R2 error")
              (is (= 1 @cancel-count)))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest semantic-r2-cleanup-is-bounded-and-preserves-db-error-test
  (testing "R2 deletion failure cannot hide or indefinitely delay the semantic DB failure"
    (async done
      (let [cases
            [{:label "delete rejects"
              :delete! (fn []
                         (js/Promise.reject
                          (js/Error. "synthetic R2 cleanup rejection")))}
             {:label "delete never settles"
              :delete! (fn []
                         (js/Promise. (fn [_resolve _reject])))}]
            run-case
            (fn [{:keys [label delete!]}]
              (with-memory-sql-async
                (fn [sql]
                  (storage/init-schema! sql)
                  (let [seed-conn (sqlite-export/create-conn)
                        page-uuid (random-uuid)
                        created-at 1760000000000
                        _ (d/transact!
                           seed-conn
                           [{:db/ident protected-system-kv
                             :kv/value created-at}
                            {:block/uuid page-uuid
                             :block/name (str "semantic-cleanup-page-" label)
                             :block/title "Semantic cleanup target"
                             :block/tags :logseq.class/Page
                             :block/created-at created-at
                             :block/updated-at created-at}])
                        _ (d/store @seed-conn
                                   (storage/new-sqlite-storage sql))
                        conn (storage/open-conn sql)
                        original-error
                        (js/Error. (str "semantic DB failure: " label))
                        delete-count (atom 0)
                        bucket
                        #js {:put (fn [_key _body _options]
                                    (p/resolved #js {}))
                             :delete (fn [_key]
                                       (swap! delete-count inc)
                                       (delete!))}
                        self #js {:env #js {"DB" nil
                                           "LOGSEQ_SYNC_ASSETS" bucket}
                                  :sql sql :conn conn :schema-ready true}
                        checksum (apply str (repeat 64 "e"))
                        request
                        (js/Request.
                         (str "http://localhost/semantic/assets"
                              "?graph-id=synthetic-graph"
                              "&file-name=cleanup.png"
                              "&page-id=" page-uuid
                              "&size=4&checksum=" checksum)
                         #js {:method "POST"
                              :headers #js {"content-type"
                                            "application/octet-stream"}
                              :body (js/Uint8Array. #js [1 2 3 4])})]
                    (p/with-redefs
                      [outliner-core/insert-blocks!
                       (fn [& _] (throw original-error))]
                      (p/let [observed
                              (observe-handler-result-with-cleanup-deadline
                               (sync-handler/handle-http self request))
                              response (:response observed)
                              body (when response (json-body response))]
                        (is (not (:timeout? observed))
                            (str label " must settle within the cleanup bound"))
                        (is (= 500 (some-> response .-status)) label)
                        (is (= (.-message original-error) (:error body))
                            (str label " must preserve the semantic DB error"))
                        (is (= 1 @delete-count) label)))))))]
        (-> (p/loop [remaining cases]
              (if-let [test-case (first remaining)]
                (p/let [_ (run-case test-case)]
                  (p/recur (next remaining)))
                nil))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest direct-r2-failure-preserves-existing-object-and-empty-base64-test
  (testing "direct overwrite failure never deletes old data and a nil size-zero base64 body stays valid"
    (async done
      (let [key "synthetic/existing.png"
            objects (atom {key :old-object})
            delete-count (atom 0)
            uploaded-bodies (atom [])
            failing-bucket
            #js {:put (fn [_key _body _options]
                        (p/rejected (js/Error. "synthetic overwrite failure")))
                 :delete (fn [delete-key]
                           (swap! delete-count inc)
                           (swap! objects dissoc delete-key)
                           (p/resolved nil))}
            empty-bucket
            #js {:put (fn [_key body _options]
                        (swap! uploaded-bodies conj body)
                        (p/resolved #js {}))}]
        (-> (p/let [failed
                    (observe-handler-result
                     (assets-handler/<put-stream!
                      failing-bucket key (js/Uint8Array. #js [1])
                      {:size 1 :content-type "image/png"
                       :checksum (apply str (repeat 64 "e"))
                       :asset-type "png"}))
                    empty-response
                    (assets-handler/<put-stream!
                     empty-bucket "synthetic/empty.png" nil
                     {:size 0 :content-type "image/png"
                      :checksum (apply str (repeat 64 "0"))
                      :asset-type "png" :encoding "base64"})]
              (is (= "synthetic overwrite failure"
                     (some-> failed :error .-message)))
              (is (= {key :old-object} @objects))
              (is (zero? @delete-count))
              (is (= 200 (.-status empty-response)))
              (is (= 1 (count @uploaded-bodies)))
              (is (zero? (.-byteLength (first @uploaded-bodies)))))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest r2-put-rejection-cleans-object-and-preserves-semantic-retry-test
  (testing "a side-effecting R2 rejection leaves no object and cannot poison later streams or PATCH"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [seed-conn (sqlite-export/create-conn)
                   {:keys [page-uuid block-uuid]}
                   (seed-valid-graph! seed-conn "r2-rejection-retry")
                   created-at 1760000000000
                   _ (d/transact!
                      seed-conn
                      [{:block/uuid page-uuid
                        :block/tags :logseq.class/Page
                        :block/created-at created-at
                        :block/updated-at created-at}
                       {:block/uuid block-uuid
                        :block/created-at created-at
                        :block/updated-at created-at}])
                   _ (d/store @seed-conn (storage/new-sqlite-storage sql))
                   conn (storage/open-conn sql)
                   objects (atom {})
                   put-count (atom 0)
                   delete-count (atom 0)
                   bucket
                   #js {:put
                        (fn [key body _options]
                          (swap! put-count inc)
                          (if (string/starts-with? key "synthetic-graph/")
                            (do
                              ;; Model R2 accepting the object before its
                              ;; completion Promise reports a transport error.
                              (swap! objects assoc key :partial)
                              (p/rejected
                               (js/Error. "synthetic R2 completion failure")))
                            (p/let [buffer
                                    (.arrayBuffer (js/Response. body))]
                              (swap! objects assoc
                                     key (js/Uint8Array. buffer))
                              #js {})))
                        :delete
                        (fn [key]
                          (swap! delete-count inc)
                          (swap! objects dissoc key)
                          (p/resolved nil))}
                   self #js {:env #js {"DB" nil
                                       "LOGSEQ_SYNC_ASSETS" bucket}
                             :sql sql :conn conn :schema-ready true}
                   checksum (apply str (repeat 64 "f"))
                   asset-request
                   (js/Request.
                    (str "http://localhost/semantic/assets"
                         "?graph-id=synthetic-graph"
                         "&file-name=failed.png"
                         "&size=4&checksum=" checksum "&encoding=base64")
                    #js {:method "POST"
                         :headers #js {"content-type" "text/plain"}
                         :body "AQIDBA=="})
                   patch-request
                   (js/Request.
                    (str "http://localhost/semantic/blocks/" block-uuid
                         "?graph-id=synthetic-graph")
                    #js {:method "PATCH"
                         :headers #js {"content-type" "application/json"}
                         :body (js/JSON.stringify
                                #js {:title "after-r2-retry"})})
                   direct-request
                   (fn [body]
                     (js/Request.
                      "http://localhost/direct-asset"
                      #js {:method "POST" :body body}))]
               (p/let [sealed (v2-metadata! self)]
                 (is (= 200 (.-status sealed)))
                 (is (storage/snapshot-integrity-attested? sql))
                 (p/let [failed-response
                         (sync-handler/handle-http self asset-request)]
                   (is (= 500 (.-status failed-response)))
                   (is (= 1 @put-count))
                   (is (empty? @objects)
                       "a put that reports failure after its side effect must be deleted")
                   (is (= 1 @delete-count))
                   (p/let [invalid-response
                           (assets-handler/<put-stream!
                            bucket "synthetic/invalid.png"
                            (.-body (direct-request "not base64!"))
                            {:size 4 :content-type "text/plain"
                             :checksum (apply str (repeat 64 "a"))
                             :asset-type "png" :encoding "base64"})]
                     (is (= 400 (.-status invalid-response)))
                     (is (= 1 @delete-count))
                     (is (empty? @objects))
                     (p/let [valid-response
                             (assets-handler/<put-stream!
                              bucket "synthetic/valid.png"
                              (.-body (direct-request "AQIDBA=="))
                              {:size 4 :content-type "text/plain"
                               :checksum (apply str (repeat 64 "b"))
                               :asset-type "png" :encoding "base64"})]
                       (is (= 200 (.-status valid-response)))
                       (is (= 1 @delete-count)
                           "a successful put never issues a cleanup delete")
                       (p/let [patch-response
                               (sync-handler/handle-http self patch-request)]
                         (is (= 200 (.-status patch-response)))
                         (is (= "after-r2-retry"
                                (:block/title
                                 (d/entity @(.-conn self)
                                           [:block/uuid block-uuid]))))
                         (is (= #{"synthetic/valid.png"}
                                (set (keys @objects))))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest semantic-asset-write-cannot-cross-mid-await-snapshot-activation-test
  (testing "an asset request paused in R2 cannot write through its captured pre-activation connection"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [page-uuid (random-uuid)
                   created-at 1760000000000
                   source-seed-conn (sqlite-export/create-conn)
                   _ (d/transact!
                      source-seed-conn
                      [{:db/ident protected-system-kv
                        :kv/value created-at}
                       {:block/uuid page-uuid
                        :block/name "semantic-overlap-page"
                        :block/title "Snapshot B page"
                        :block/tags :logseq.class/Page
                        :block/created-at created-at
                        :block/updated-at created-at}])
                   _ (d/store @source-seed-conn
                              (storage/new-sqlite-storage source-sql))
                   source-conn (storage/open-conn source-sql)
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   conn-a-seed (sqlite-export/create-conn)
                   _ (d/transact!
                      conn-a-seed
                      [{:db/ident protected-system-kv
                        :kv/value created-at}
                       {:block/uuid page-uuid
                        :block/name "semantic-overlap-page"
                        :block/title "Snapshot A page"
                        :block/tags :logseq.class/Page
                        :block/created-at created-at
                        :block/updated-at created-at}])
                   _ (d/store @conn-a-seed
                              (storage/new-sqlite-storage destination-sql))
                   conn-a (storage/open-conn destination-sql)
                   _ (persist-current-checksums! destination-sql conn-a)
                   t-a (storage/get-t destination-sql)
                   descriptor-a
                   (storage/verified-snapshot-integrity-descriptor
                    destination-sql @conn-a t-a (str (random-uuid)))
                   _ (storage/seal-verified-snapshot-integrity!
                      destination-sql descriptor-a)
                   conn-a-facts-before (db-facts @conn-a)
                   paused-resolve* (atom nil)
                   paused
                   (js/Promise.
                    (fn [resolve _reject]
                      (reset! paused-resolve* resolve)))
                   resume-resolve* (atom nil)
                   resume
                   (js/Promise.
                    (fn [resolve _reject]
                      (reset! resume-resolve* resolve)))
                   objects (atom {})
                   put-count (atom 0)
                   delete-count (atom 0)
                   bucket
                   #js {:put
                        (fn [key body _options]
                          (let [attempt (swap! put-count inc)]
                            (p/let [buffer
                                    (.arrayBuffer (js/Response. body))
                                    _ (swap! objects assoc
                                             key (js/Uint8Array. buffer))
                                    _ (when (= 1 attempt)
                                        (@paused-resolve* true))
                                    _ (when (= 1 attempt) resume)]
                              #js {})))
                        :delete
                        (fn [key]
                          (swap! delete-count inc)
                          (swap! objects dissoc key)
                          (p/resolved nil))}
                   self #js {:env #js {"DB" nil
                                       "LOGSEQ_SYNC_ASSETS" bucket}
                             :sql destination-sql
                             :conn conn-a
                             :schema-ready true}
                   asset-request
                   (fn [file-name checksum]
                     (js/Request.
                      (str "http://localhost/semantic/assets"
                           "?graph-id=synthetic-graph"
                           "&file-name=" file-name
                           "&title=" file-name
                           "&page-id=" page-uuid
                           "&size=4&checksum=" checksum)
                      #js {:method "POST"
                           :headers
                           #js {"content-type" "application/octet-stream"}
                           :body
                           (js/Blob.
                            #js [(js/Uint8Array. #js [1 2 3 4])])}))
                   first-checksum (apply str (repeat 64 "a"))
                   retry-checksum (apply str (repeat 64 "b"))
                   activation-request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=semantic-mid-await-activation"
                         "&checksum=" source-checksum
                         "&row-count=" (count source-rows))
                    source-rows)
                   activation-state
                   (fn []
                     {:live (live-durable-state destination-sql)
                      :sync-meta (table-rows destination-sql
                                             "sync_meta" "key")
                      :attestation
                      (storage/snapshot-integrity-attestation
                       destination-sql)
                      :kvs-generation
                      (storage/live-kvs-mutation-generation
                       destination-sql)
                      :checksum-generation
                      (storage/snapshot-checksum-mutation-generation
                       destination-sql)
                      :snapshot-counts
                      (snapshot-state-counts destination-sql)})]
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [& _] (p/resolved true))
                  ws/broadcast! (fn [& _] nil)]
                 (let [paused-request
                       (sync-handler/handle-http
                        self (asset-request "stale.png" first-checksum))]
                   (p/let [_ paused
                           _ (is (identical? conn-a (.-conn self))
                                 "the request captured A before the R2 await")
                           activation-response
                           (sync-handler/handle-http self activation-request)
                           _ (is (= 200 (.-status activation-response)))
                           conn-b (.-conn self)
                           _ (is (not (identical? conn-a conn-b))
                                 "activation must publish a new live connection")
                           _ (is (= source-rows (kvs-rows destination-sql)))
                           _ (is (= "Snapshot B page"
                                    (:block/title
                                     (d/entity @conn-b
                                               [:block/uuid page-uuid]))))
                           b-state-before (activation-state)
                           b-facts-before (db-facts @conn-b)
                           _ (@resume-resolve* true)
                           stale-response paused-request]
                     (is (= 409 (.-status stale-response))
                         "a captured pre-activation write must fail before DataScript mutation")
                     (is (identical? conn-b (.-conn self))
                         "rejecting A must not discard or reopen B")
                     (is (= conn-a-facts-before (db-facts @conn-a))
                         "the stale connection must remain completely untouched")
                     (is (= b-state-before (activation-state))
                         "the activated durable snapshot must remain exact")
                     (is (= b-facts-before (db-facts @(.-conn self)))
                         "the activated in-memory snapshot must remain exact")
                     (is (empty? @objects)
                         "the rejected request cleans its newly uploaded object")
                     (is (= 1 @delete-count))
                     (p/let [retry-response
                             (sync-handler/handle-http
                             self
                              (asset-request "fresh.png" retry-checksum))
                             retry-body (json-body retry-response)
                             asset
                             (when-let [asset-uuid (:uuid retry-body)]
                               (d/entity @(.-conn self)
                                         [:block/uuid (uuid asset-uuid)]))]
                       (is (= 201 (.-status retry-response)))
                       (is (= "fresh.png" (:title retry-body)))
                       (is (= page-uuid
                              (:block/uuid (:block/page asset))))
                       (is (= "Snapshot B page"
                              (:block/title
                               (d/entity @(.-conn self)
                                         [:block/uuid page-uuid]))))
                       (is (= 2 @put-count))
                       (is (= 1 @delete-count))
                       (is (= 1 (count @objects))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(declare apply-entry!)

(defn- seed-live-with-idempotency-marker!
  [sql label]
  (let [conn (storage/open-conn sql)
        {:keys [block-uuid] :as state} (seed-valid-graph! conn label)
        response
        (apply-entry!
         #js {:sql sql :conn conn :schema-ready true}
         {:tx-id (random-uuid)
          :outliner-op :save-block
          :tx (protocol/tx->transit
               [[:db/add [:block/uuid block-uuid]
                 :block/title "live-before-legacy-upload"]])})]
    (is (= "tx/batch/ok" (:type response)))
    (assoc state :conn conn)))

(defn- apply-entry!
  ([^js self entry]
   (apply-entry! self entry (storage/get-t (.-sql self))))
  ([^js self entry t-before]
   (with-redefs [ws/broadcast! (fn [& _] nil)]
     (sync-handler/handle-tx-batch! self nil [entry] t-before))))

(deftest v2-download-repairs-detached-gallery-ident-index-tear-test
  (testing "selfhost.5 can replay an observed EAVT/AVET-torn snapshot without a unique collision"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (let [{:keys [canonical-eid canonical-uuid shadow-eid conn
                           block-uuid]}
                   (seed-index-torn-gallery-snapshot! sql)
                   _ (persist-current-checksums! sql conn)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}
                   durable-before (dissoc (live-durable-state sql) :kvs)]
               (p/let [metadata-response (v2-metadata! self)
                       metadata (json-body metadata-response)
                       stream-result (stream-rows! self (:url metadata))
                       imported-conn
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows stream-result))
                       live-after (storage/open-conn sql)
                       _ (is (= durable-before
                                (dissoc (live-durable-state sql) :kvs))
                             "repair preserves cursor/checksums/tx-log/idempotency")
                       ^js restarted-self
                       #js {:env
                            #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql sql
                            :conn nil
                            :schema-ready true}
                       t-before (storage/get-t sql)
                       tx-count-before (count (table-rows sql "tx_log" "t"))
                       tx-response
                       (apply-entry!
                        restarted-self
                        {:tx-id (random-uuid)
                         :outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "after-canonical-repair"]])})
                       pull-after
                       (sync-handler/pull-response restarted-self t-before)
                       validation-count (atom 0)
                       original-validator snapshot-integrity/validate-or-repair!
                       second-metadata-response
                       (p/with-redefs
                         [snapshot-integrity/validate-or-repair!
                          (fn [& args]
                            (swap! validation-count inc)
                            (apply original-validator args))]
                         (v2-metadata! restarted-self))]
                 (is (= 200 (.-status metadata-response)))
                 (is (= 200 (.-status (:response stream-result))))
                 (is (= canonical-eid
                        (:db/id
                         (d/entity @imported-conn observed-torn-ident))))
                 (is (= canonical-uuid
                        (:block/uuid
                         (d/entity @imported-conn observed-torn-ident))))
                 (is (empty? (d/datoms @imported-conn :eavt shadow-eid)))
                 (is (= [canonical-eid]
                        (mapv :e
                              (d/datoms @live-after :avet
                                        :db/ident observed-torn-ident))))
                 (is (= [canonical-eid]
                        (mapv :e
                              (filter #(= observed-torn-ident (:v %))
                                      (d/datoms @live-after :eavt)))))
                 (is (= "tx/batch/ok" (:type tx-response)))
                 (is (= (inc t-before) (storage/get-t sql)))
                 (is (= (inc tx-count-before)
                        (count (table-rows sql "tx_log" "t"))))
                 (is (= (inc t-before) (:t pull-after)))
                 (is (= "after-canonical-repair"
                        (:block/title
                         (d/entity @(.-conn restarted-self)
                                   [:block/uuid block-uuid]))))
                 (is (storage/snapshot-integrity-attested? sql))
                 (is (= 200 (.-status second-metadata-response)))
                 (is (some? (.-conn restarted-self)))
                 (is (zero? @validation-count)
                     "a root/t/generation attestation makes repeated healthy download constant-cost")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest eavt-only-strict-gallery-ghost-is-repairable-test
  (testing "EAVT may retain the detached ghost after AEVT and AVET already select the canonical entity"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (let [{:keys [canonical-eid shadow-eid conn block-uuid]}
                   (seed-eavt-only-torn-gallery-snapshot! sql)
                   diagnostic
                   (try
                     {:plan (snapshot-integrity/prepare-repair-plan! conn nil)}
                     (catch :default error
                       {:reason (select-keys (ex-data error)
                                             [:type :phase :attribute
                                             :entity-ids])}))
                   _ (persist-current-checksums! sql conn)
                   sync-meta-before (table-rows sql "sync_meta" "key")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (is (map? (:plan diagnostic))
                   (str "typed repair refusal: " (pr-str (:reason diagnostic))))
               (p/let [response (v2-metadata! self)
                       live-after (storage/open-conn sql)]
                 (is (= 200 (.-status response)))
                 (is (= [canonical-eid]
                        (mapv :e
                              (filter #(= observed-torn-ident (:v %))
                                      (d/datoms @live-after :eavt)))))
                 (is (empty? (d/datoms @live-after :eavt shadow-eid)))
                 (is (= sync-meta-before
                        (table-rows sql "sync_meta" "key"))
                     "repair must not add internal proof rows to protocol metadata")
                 (is (nil? (storage/get-meta
                            sql :snapshot-integrity-attestation)))
                 (is (some #{"integrity_attestations"}
                           (sqlite-table-names sql))
                     "the exact-root proof has dedicated durable storage")
                 (is (map? (storage/snapshot-integrity-attestation sql)))
                 (when (= 200 (.-status response))
                   (let [t-before (storage/get-t sql)
                         tx-response
                         (apply-entry!
                          self
                          {:tx-id (random-uuid)
                           :outliner-op :save-block
                           :tx (protocol/tx->transit
                                [[:db/add [:block/uuid block-uuid]
                                  :block/title "after-eavt-only-repair"]])})]
                     (is (= "tx/batch/ok" (:type tx-response)))
                     (is (= (inc t-before) (storage/get-t sql)))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest eavt-only-strict-gallery-ghost-is-repaired-before-v2-upload-activation-test
  (async done
    (-> (with-memory-sql-pair-async
         (fn [source-sql destination-sql]
           (let [{:keys [canonical-eid canonical-uuid shadow-eid conn]}
                 (seed-eavt-only-torn-gallery-snapshot! source-sql)
                 source-checksum
                 (sync-checksum/recompute-checksum @conn)
                 source-rows (kvs-rows source-sql)
                 _ (storage/init-schema! destination-sql)
                 destination-conn (storage/open-conn destination-sql)
                 old-state (seed-valid-graph! destination-conn
                                              "before-eavt-only-upload")
                 self #js {:env #js {"DB" nil}
                           :sql destination-sql
                           :conn destination-conn
                           :schema-ready true}
                 request
                 (snapshot-upload-request
                  (str "reset=true&finished=true"
                       "&upload-id=" (random-uuid)
                       "&checksum=" source-checksum
                       "&row-count=" (count source-rows))
                  source-rows)]
             (p/with-redefs
               [sync-handler/<set-graph-ready-for-use!
                (fn [& _] (p/resolved true))]
               (p/let [response (sync-handler/handle-http self request)
                       live-after (storage/open-conn destination-sql)]
                 (is (= 200 (.-status response)))
                 (is (= canonical-eid
                        (:db/id (d/entity @live-after observed-torn-ident))))
                 (is (= canonical-uuid
                        (:block/uuid
                         (d/entity @live-after observed-torn-ident))))
                 (is (empty? (d/datoms @live-after :eavt shadow-eid)))
                 (is (nil? (d/entity
                            @live-after
                            [:block/uuid (:block-uuid old-state)])))
                 (is (= 0 (storage/get-t destination-sql)))
                 (is (storage/snapshot-integrity-attested? destination-sql))
                 (is (sync-handler/snapshot-upload-finished? self)))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest eavt-only-strict-gallery-ghost-is-repaired-on-first-v1-export-test
  (async done
    (-> (with-memory-sql-async
         (fn [sql]
           (let [{:keys [canonical-eid shadow-eid conn block-uuid]}
                 (seed-eavt-only-torn-gallery-snapshot! sql)
                 _ (persist-current-checksums! sql conn)
                 self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                           :sql sql
                           :conn conn
                           :schema-ready true}]
             (p/let [metadata-response (v1-metadata! self)
                     metadata (json-body metadata-response)
                     stream (stream-rows! self (:url metadata))
                     imported
                     (replay-snapshot-rows-like-selfhost-five! (:rows stream))
                     live-after (storage/open-conn sql)]
               (is (= 200 (.-status metadata-response)))
               (is (= 200 (.-status (:response stream))))
               (is (= canonical-eid
                      (:db/id (d/entity @imported observed-torn-ident))))
               (is (empty? (d/datoms @imported :eavt shadow-eid)))
               (is (empty? (d/datoms @live-after :eavt shadow-eid)))
               (is (= "before-concurrent-tx"
                      (:block/title
                       (d/entity @imported [:block/uuid block-uuid]))))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest divergent-aevt-shapes-remain-fail-closed-test
  (testing "only exact duplicate or AVET-selected canonical-only AEVT is repairable"
    (doseq [shape [:empty
                   :shadow-only
                   :different-eid-only
                   :extra
                   :wrong-canonical-tx
                   :wrong-shadow-tx]]
      (with-memory-sql
        (fn [sql]
          (let [{:keys [canonical-eid shadow-eid conn]}
                (seed-eavt-only-torn-gallery-snapshot! sql)
                _ (persist-aevt-split-shape!
                   conn shadow-eid canonical-eid observed-torn-ident shape)
                reopened (storage/open-conn sql)
                rows-before (kvs-rows sql)
                error-data
                (try
                  (snapshot-integrity/prepare-repair-plan! reopened nil)
                  nil
                  (catch :default error
                    (ex-data error)))]
            (is (= :db-sync/snapshot-index-inconsistent
                   (:type error-data))
                (name shape))
            (is (= :duplicate-aevt-mismatch (:phase error-data))
                (name shape))
            (is (= rows-before (kvs-rows sql))
                (str (name shape) " is diagnostic-only and does not mutate"))))))))

(deftest v2-download-repairs-strict-ghost-for-any-known-built-in-ident-test
  (testing "strict detached ghosts are classified by the current built-in catalog, not one observed ident"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (let [{:keys [canonical-eid canonical-uuid shadow-eid conn
                           block-uuid]}
                   (seed-index-torn-ident-snapshot!
                    sql alternate-known-built-in-ident)
                   _ (persist-current-checksums! sql conn)
                   durable-before (dissoc (live-durable-state sql) :kvs)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (p/let [metadata-response (v2-metadata! self)
                       live-after (storage/open-conn sql)]
                 (is (= 200 (.-status metadata-response)))
                 (is (= durable-before
                        (dissoc (live-durable-state sql) :kvs))
                     "repair preserves non-KVS durability at the same cursor")
                 (is (= [canonical-eid]
                        (mapv :e
                              (filter
                               #(= alternate-known-built-in-ident (:v %))
                               (d/datoms @live-after :eavt)))))
                 (is (empty? (d/datoms @live-after :eavt shadow-eid))
                     "the raw reopened durable root no longer contains the ghost")
                 (is (= canonical-uuid
                        (:block/uuid
                         (d/entity @live-after
                                   alternate-known-built-in-ident))))
                 (when (= 200 (.-status metadata-response))
                   (let [t-before (storage/get-t sql)
                         tx-count-before (count (table-rows sql "tx_log" "t"))
                         tx-response
                         (apply-entry!
                          self
                          {:tx-id (random-uuid)
                           :outliner-op :save-block
                           :tx (protocol/tx->transit
                                [[:db/add [:block/uuid block-uuid]
                                  :block/title "after-alternate-built-in-repair"]])})]
                     (is (= "tx/batch/ok" (:type tx-response)))
                     (is (= (inc t-before) (storage/get-t sql)))
                     (is (= (inc tx-count-before)
                            (count (table-rows sql "tx_log" "t"))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v2-upload-canonicalizes-strict-ghost-for-any-known-built-in-ident-test
  (testing "a strict known built-in ghost is canonicalized in staging before activation"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [{:keys [canonical-eid canonical-uuid shadow-eid conn
                           block-uuid]}
                   (seed-index-torn-ident-snapshot!
                    source-sql alternate-known-built-in-ident)
                   source-checksum
                   (sync-checksum/recompute-checksum @conn)
                   source-rows (kvs-rows source-sql)
                   destination-conn (storage/open-conn destination-sql)
                   old-state (seed-valid-graph! destination-conn
                                                "before-strict-ghost-upload")
                   ready-events (atom [])
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn destination-conn
                             :schema-ready true}
                   request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=" (random-uuid)
                         "&checksum=" source-checksum
                         "&row-count=" (count source-rows))
                    source-rows)]
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id ready?]
                    (swap! ready-events conj ready?)
                    (p/resolved true))]
                 (p/let [response (sync-handler/handle-http self request)
                         live-after (storage/open-conn destination-sql)]
                   (is (= 200 (.-status response)))
                   (is (= canonical-eid
                          (:db/id
                           (d/entity @live-after
                                     alternate-known-built-in-ident))))
                   (is (= canonical-uuid
                          (:block/uuid
                           (d/entity @live-after
                                     alternate-known-built-in-ident))))
                   (is (empty? (d/datoms @live-after :eavt shadow-eid)))
                   (is (nil? (d/entity
                              @live-after
                              [:block/uuid (:block-uuid old-state)])))
                   (is (= 0 (storage/get-t destination-sql)))
                   (is (empty? (staging-rows destination-sql)))
                   (is (sync-handler/snapshot-upload-finished? self))
                   (is (= [true] @ready-events))
                   (when (= 200 (.-status response))
                     (let [tx-response
                           (apply-entry!
                            self
                            {:tx-id (random-uuid)
                             :outliner-op :save-block
                             :tx (protocol/tx->transit
                                  [[:db/add [:block/uuid block-uuid]
                                    :block/title "after-strict-ghost-upload"]])})]
                       (is (= "tx/batch/ok" (:type tx-response)))
                       (is (= 1 (storage/get-t destination-sql))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest concurrent-v1-v2-freeze-orders-after-strict-built-in-repair-test
  (testing "concurrent old/new metadata freeze one repaired generation and a following tx is pullable"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (let [{:keys [shadow-eid conn block-uuid]}
                   (seed-index-torn-ident-snapshot!
                    sql alternate-known-built-in-ident)
                   _ (persist-current-checksums! sql conn)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (p/let [responses (p/all [(v1-metadata! self)
                                         (v2-metadata! self)])
                       v1-response (first responses)
                       v2-response (second responses)
                       v1-metadata (json-body v1-response)
                       v2-metadata (json-body v2-response)]
                 (is (= 200 (.-status v1-response)))
                 (is (= 200 (.-status v2-response)))
                 (when (and (= 200 (.-status v1-response))
                            (= 200 (.-status v2-response)))
                   (p/let [snapshot-t (:t v2-metadata)
                           tx-response
                           (apply-entry!
                            self
                            {:tx-id (random-uuid)
                             :outliner-op :save-block
                             :tx (protocol/tx->transit
                                  [[:db/add [:block/uuid block-uuid]
                                    :block/title "after-concurrent-repair"]])}
                            snapshot-t)
                           v1-stream (stream-rows! self (:url v1-metadata))
                           v2-stream (stream-rows! self (:url v2-metadata))
                           v1-projection
                           (rows-projection (:rows v1-stream) block-uuid)
                           v2-projection
                           (rows-projection (:rows v2-stream) block-uuid)
                           pull (sync-handler/pull-response self snapshot-t)
                           live-after (storage/open-conn sql)]
                     (is (= "tx/batch/ok" (:type tx-response)))
                     (is (= snapshot-t (:t v2-metadata)))
                     (is (= "before-concurrent-tx" (:title v1-projection)))
                     (is (= v1-projection v2-projection))
                     (is (= "pull/ok" (:type pull)))
                     (is (= (inc snapshot-t) (:t pull)))
                     (is (= 1 (count (:txs pull))))
                     (is (empty? (d/datoms @live-after :eavt shadow-eid)))
                     (is (= {:downloads 0 :exports 0}
                            (snapshot-state-counts sql)))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v1-and-v2-download-heal-stale-same-cursor-checksum-metadata-test
  (testing "a replayable frozen snapshot and its advertised checksum come from one validated root"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]} (seed-valid-graph! conn "stale-same-t")
                   t (storage/get-t sql)
                   {:keys [checksum server-checksum]} (db-checksums conn)
                   stale-checksum (if (= checksum "0000000000000000")
                                    "1111111111111111"
                                    "0000000000000000")
                   stale-server-checksum
                   (if (= server-checksum "2222222222222222")
                     "3333333333333333"
                     "2222222222222222")
                   mark-stale!
                   (fn []
                     (storage/with-sql-transaction!
                      sql
                      (fn []
                        (storage/set-checksum! sql stale-checksum)
                        (storage/set-server-checksum!
                         sql stale-server-checksum t)
                        (storage/mark-checksum-metadata-verified! sql t)
                        (storage/clear-snapshot-integrity-attestation!
                         sql))))
                   _ (mark-stale!)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (is (storage/checksum-metadata-verified? sql t)
                   "the legacy checksum marker is superficially valid")
               (is (not= checksum (storage/get-checksum sql)))
               (p/let [v2-response (v2-metadata! self)
                       v2-body (json-body v2-response)
                       v2-stream (stream-rows! self (:url v2-body))
                       v2-imported
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows v2-stream))
                       _ (is (= 200 (.-status v2-response)))
                       _ (is (= 200 (.-status (:response v2-stream))))
                       _ (is (= checksum (:checksum v2-body)))
                       _ (is (= checksum
                                (sync-checksum/recompute-checksum
                                 @v2-imported))
                             ".5 finalize sees the checksum of the downloaded DB")
                       _ (is (= block-uuid
                                (:block/uuid
                                 (d/entity @v2-imported
                                           [:block/uuid block-uuid]))))
                       _ (is (= checksum (storage/get-checksum sql)))
                       _ (is (= server-checksum
                                (storage/get-server-checksum sql)))
                       _ (is (= t (storage/get-server-checksum-t sql)))
                       _ (is (storage/checksum-metadata-verified? sql t))
                       _ (is (storage/snapshot-integrity-attested? sql))
                       _ (mark-stale!)
                       v1-response (v1-metadata! self)
                       v1-body (json-body v1-response)
                       v1-stream (stream-rows! self (:url v1-body))
                       v1-imported
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows v1-stream))
                       pull (sync-handler/pull-response self t)]
                 (is (= 200 (.-status v1-response)))
                 (is (= 200 (.-status (:response v1-stream))))
                 (is (= checksum
                        (sync-checksum/recompute-checksum @v1-imported)))
                 (is (= checksum (:checksum pull)))
                 (is (= server-checksum (:server-checksum pull)))
                 (is (= sync-checksum/server-checksum-version
                        (:checksum-version pull)))
                 (is (storage/snapshot-integrity-attested? sql))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest same-cursor-stale-checksum-hooks-cannot-self-seal-integrity-test
  (testing "metadata hooks cannot attest stale checksums for an unchanged live KVS root"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! conn "same-t-stale-metadata-hooks")
                   t (storage/get-t sql)
                   {:keys [checksum server-checksum]}
                   (persist-current-checksums! sql conn)
                   initial-generation (str (random-uuid))
                   initial-descriptor
                   (storage/verified-snapshot-integrity-descriptor
                    sql @conn t initial-generation)
                   _ (storage/seal-verified-snapshot-integrity!
                      sql initial-descriptor)
                   cross-generation-descriptor
                   (storage/verified-snapshot-integrity-descriptor
                    sql @conn t initial-generation)
                   stale-t-descriptor
                   (storage/verified-snapshot-integrity-descriptor
                    sql @conn t initial-generation)
                   stale-t-rejected?
                   (try
                     (storage/with-sql-transaction!
                      sql
                      (fn []
                        (storage/set-t! sql (inc t))
                        (storage/seal-verified-snapshot-integrity!
                         sql stale-t-descriptor)))
                     false
                     (catch :default _
                       true))
                   stale-checksum (if (= checksum "0000000000000000")
                                    "1111111111111111"
                                    "0000000000000000")
                   stale-server-checksum
                   (if (= server-checksum "2222222222222222")
                     "3333333333333333"
                     "2222222222222222")
                   stale-marked?
                   (storage/with-sql-transaction!
                    sql
                    (fn []
                      (storage/set-checksum! sql stale-checksum)
                      (storage/set-server-checksum!
                       sql stale-server-checksum t)
                      (storage/mark-checksum-metadata-verified! sql t)
                      ;; Model a stale metadata writer using the legacy public
                      ;; hook. Without a verified descriptor it may dirty the
                      ;; proof, but can never bless current sync_meta values.
                      (storage/mark-snapshot-integrity-attested!
                       sql t initial-generation)))
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (is (storage/checksum-metadata-verified? sql t)
                   "the legacy metadata marker is superficially coherent")
               (is stale-t-rejected?
                   "a descriptor cannot cross a server cursor generation")
               (is (= t (storage/get-t sql))
                   "the rejected stale-t seal rolls back atomically")
               (is (false? stale-marked?))
               (is (not (storage/snapshot-integrity-attested? sql))
                   "critical metadata mutation dirties the prior proof")
               (is (thrown? js/Error
                            (storage/seal-verified-snapshot-integrity!
                             sql #js {}))
                   "a plain object cannot forge a verified descriptor")
               (is (thrown? js/Error
                            (storage/seal-verified-snapshot-integrity!
                             sql cross-generation-descriptor))
                   "a descriptor cannot cross a critical metadata generation")
               (is (not= checksum (storage/get-checksum sql)))
               (p/let [v2-response (v2-metadata! self)
                       v2-body (json-body v2-response)
                       v2-stream (stream-rows! self (:url v2-body))
                       v2-imported
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows v2-stream))
                       v1-response (v1-metadata! self)
                       v1-body (json-body v1-response)
                       v1-stream (stream-rows! self (:url v1-body))
                       v1-imported
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows v1-stream))
                       pull (sync-handler/pull-response self t)]
                 (is (= 200 (.-status v2-response)))
                 (is (= 200 (.-status (:response v2-stream))))
                 (is (= checksum (:checksum v2-body))
                     ".5 metadata must advertise the downloaded DB checksum")
                 (is (= checksum
                        (sync-checksum/recompute-checksum @v2-imported)))
                 (is (= 200 (.-status v1-response)))
                 (is (= 200 (.-status (:response v1-stream))))
                 (is (= checksum
                        (sync-checksum/recompute-checksum @v1-imported)))
                 (is (= checksum (:checksum pull)))
                 (is (= server-checksum (:server-checksum pull)))
                 (is (= checksum (storage/get-checksum sql)))
                 (is (= server-checksum
                        (storage/get-server-checksum sql)))
                 (is (= t (storage/get-server-checksum-t sql)))
                 (is (= block-uuid
                        (:block/uuid
                         (d/entity @v2-imported
                                   [:block/uuid block-uuid]))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest cold-http-snapshot-metadata-revalidates-same-cursor-checksum-hooks-test
  (testing "the public snapshot metadata endpoints cannot authorize stale checksum metadata"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [seed-conn (storage/open-conn sql)
                   _ (seed-valid-graph! seed-conn "cold-http-same-t")
                   t (storage/get-t sql)
                   {:keys [checksum server-checksum]}
                   (persist-current-checksums! sql seed-conn)
                   initial-descriptor
                   (storage/verified-snapshot-integrity-descriptor
                    sql @seed-conn t (str (random-uuid)))
                   _ (storage/seal-verified-snapshot-integrity!
                      sql initial-descriptor)
                   stale-checksum (if (= checksum "0000000000000000")
                                    "1111111111111111"
                                    "0000000000000000")
                   stale-server-checksum
                   (if (= server-checksum "2222222222222222")
                     "3333333333333333"
                     "2222222222222222")
                   _ (storage/with-sql-transaction!
                      sql
                      (fn []
                        (storage/set-checksum! sql stale-checksum)
                        (storage/set-server-checksum!
                         sql stale-server-checksum t)
                        (storage/mark-checksum-metadata-verified! sql t)))
                   graph-ready? (atom false)
                   ready-set-values (atom [])
                   self #js {:env #js {"DB" :synthetic-d1
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn nil
                             :schema-ready false}
                   metadata-request
                   (fn [suffix]
                     (js/Request.
                      (str "http://localhost/sync/synthetic-graph/snapshot/"
                           suffix "?graph-id=synthetic-graph")))]
               (is (storage/checksum-metadata-verified? sql t))
               (is (not (storage/snapshot-integrity-attested? sql)))
               (p/with-redefs
                 [index/<graph-ready-for-use?
                  (fn [& _] (p/resolved @graph-ready?))
                  index/<graph-created-at
                  (fn [& _] (p/resolved 1760000000000))
                  index/<graph-ready-for-use-set!
                  (fn [_db _graph-id value]
                    (reset! graph-ready? value)
                    (swap! ready-set-values conj value)
                    (p/resolved #js {:success true
                                     :meta #js {:changes 1}}))]
                 (p/let [v2-response
                         (sync-handler/handle-http
                          self (metadata-request "download-v2"))
                         v2-body (json-body v2-response)
                         v1-response
                         (sync-handler/handle-http
                          self (metadata-request "download"))]
                   (is (= 200 (.-status v2-response)))
                   (is (= checksum (:checksum v2-body)))
                   (is (= 200 (.-status v1-response)))
                   (is (= checksum (storage/get-checksum sql)))
                   (is (= server-checksum
                          (storage/get-server-checksum sql)))
                   (is (storage/snapshot-integrity-attested? sql))
                   (is (= [true] @ready-set-values)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest cold-schema-ready-http-metadata-bootstraps-without-attestation-test
  (testing "schema readiness never substitutes for opening and validating the live connection"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [seed-conn (storage/open-conn sql)
                   _ (seed-valid-graph! seed-conn "cold-schema-ready")
                   t (storage/get-t sql)
                   {:keys [checksum server-checksum]}
                   (persist-current-checksums! sql seed-conn)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn nil
                             :schema-ready true}
                   request
                   (js/Request.
                    (str "http://localhost/sync/synthetic-graph/snapshot/"
                         "download-v2?graph-id=synthetic-graph"))]
               (storage/clear-snapshot-integrity-attestation! sql)
               (is (storage/checksum-metadata-verified? sql t))
               (is (nil? (.-conn self)))
               (is (not (storage/snapshot-integrity-attested? sql)))
               (p/let [response (sync-handler/handle-http self request)
                       body (json-body response)]
                 (is (= 200 (.-status response)))
                 (is (= checksum (:checksum body)))
                 (is (= checksum (storage/get-checksum sql)))
                 (is (= server-checksum
                        (storage/get-server-checksum sql)))
                 (is (some? (.-conn self)))
                 (is (storage/snapshot-integrity-attested? sql))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest legacy-snapshot-base-with-empty-history-downloads-and-extends-test
  (testing "a legacy snapshot cursor is a durable history floor, not a forged full log"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [seed-conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! seed-conn "legacy-history-floor")
                   {:keys [checksum server-checksum]}
                   (db-checksums seed-conn)
                   stale-checksum (if (= checksum "0000000000000000")
                                    "1111111111111111"
                                    "0000000000000000")
                   stale-server-checksum
                   (if (= server-checksum "2222222222222222")
                     "3333333333333333"
                     "2222222222222222")
                   _ (storage/with-sql-transaction!
                      sql
                      (fn []
                        (common/sql-exec sql "delete from tx_log")
                        (storage/set-t! sql 7)
                        (storage/set-checksum! sql stale-checksum)
                        (storage/set-server-checksum!
                         sql stale-server-checksum 7)
                        (storage/mark-checksum-metadata-verified! sql 7)
                        (storage/clear-snapshot-integrity-attestation! sql)))
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn nil
                             :schema-ready true}]
               (is (storage/checksum-metadata-verified? sql 7))
               (p/let [v2-response (v2-metadata! self)
                       v2-body (json-body v2-response)
                       v1-response (v1-metadata! self)
                       v1-body (json-body v1-response)]
                 (is (= 200 (.-status v2-response)))
                 (is (= 200 (.-status v1-response)))
                 (when (and (= 200 (.-status v2-response))
                            (= 200 (.-status v1-response)))
                   (p/let [v2-stream (stream-rows! self (:url v2-body))
                           v1-stream (stream-rows! self (:url v1-body))
                           downloaded-checksum (storage/get-checksum sql)
                           downloaded-server-checksum
                           (storage/get-server-checksum sql)
                           downloaded-attestation
                           (storage/snapshot-integrity-attestation sql)
                           _ (set! (.-conn self) nil)
                           restarted-self
                           #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                         "false"}
                                :sql sql
                                :conn nil
                                :schema-ready true}
                           _ (is (= :db-sync/tx-log-noncontiguous
                                    (try
                                      (storage/append-tx!
                                       sql 8 "untrusted-direct-append"
                                       1700000000000 :save-block nil)
                                      nil
                                      (catch :default error
                                        (:type (ex-data error))))))
                           tx-response
                           (apply-entry!
                            restarted-self
                            {:outliner-op :save-block
                             :tx (protocol/tx->transit
                                  [[:db/add [:block/uuid block-uuid]
                                    :block/title "after-legacy-floor"]])}
                            7)
                           explicit-old-pull
                           (sync-handler/handle-http
                            restarted-self
                            (js/Request.
                             (str "http://localhost/sync/synthetic-graph/pull"
                                  "?graph-id=synthetic-graph&since=6")))
                           watermark-pull
                           (sync-handler/handle-http
                            restarted-self
                            (js/Request.
                             (str "http://localhost/sync/synthetic-graph/pull"
                                  "?graph-id=synthetic-graph")))
                           watermark-body (json-body watermark-pull)
                           pull-below-floor
                           (sync-handler/pull-response restarted-self 6)
                           pull-from-floor
                           (sync-handler/pull-response restarted-self 7)]
                     (is (= 200 (.-status (:response v2-stream))))
                     (is (= 200 (.-status (:response v1-stream))))
                     (is (= checksum (:checksum v2-body)))
                     (is (= checksum downloaded-checksum))
                     (is (= server-checksum
                            downloaded-server-checksum))
                     (is (= 7
                            (:tx-log-floor downloaded-attestation)))
                     (is (= "tx/batch/ok" (:type tx-response)))
                     (is (= 8 (:t tx-response)))
                     (is (= 409 (.-status explicit-old-pull))
                         "an explicit cursor below the snapshot base must not receive a fake partial log")
                     (is (= 200 (.-status watermark-pull)))
                     (is (= 8 (:t watermark-body)))
                     (is (= {:type "error"
                             :message "snapshot required"}
                            pull-below-floor))
                     (is (= "pull/ok" (:type pull-from-floor)))
                     (is (= [8] (mapv :t (:txs pull-from-floor))))
                     (let [{:keys [generation]
                            committed-floor :tx-log-floor}
                           (storage/snapshot-integrity-attestation sql)]
                       (is (= 7 committed-floor))
                       (common/sql-exec sql "delete from tx_log where t = 8")
                       (is (not (storage/snapshot-integrity-attested? sql)))
                       (is (nil?
                            (storage/eligible-snapshot-integrity-generation
                             sql 8)))
                       (is (= :db-sync/tx-log-noncontiguous
                              (try
                                (storage/append-tx!
                                 sql 9 "must-not-reseal-truncated-history"
                                 1700000000000 :save-block nil generation)
                                nil
                                (catch :default error
                                  (:type (ex-data error)))))
                       (p/let [corrupt-pull
                               (sync-handler/handle-http
                                restarted-self
                                (js/Request.
                                 (str "http://localhost/sync/synthetic-graph/pull"
                                      "?graph-id=synthetic-graph&since=8")))]
                         (is (= 409 (.-status corrupt-pull))))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest legacy-snapshot-history-floor-supports-large-client-tx-test
  (testing "large client transactions extend, rather than recreate, a legacy history suffix"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [page-uuid]}
                   (seed-valid-graph! conn "legacy-floor-large-tx")
                   _ (storage/with-sql-transaction!
                      sql
                      (fn []
                        (common/sql-exec sql "delete from tx_log")
                        (storage/set-t! sql 7)
                        (storage/clear-snapshot-integrity-attestation! sql)))
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql
                             :conn nil
                             :schema-ready true}
                   tx-data
                   (mapv (fn [i]
                           {:block/uuid (random-uuid)
                            :block/title (str "large-floor-child-" i)
                            :block/order (str "a" i)
                            :block/parent [:block/uuid page-uuid]
                            :block/page [:block/uuid page-uuid]})
                         (range 501))]
               (p/let [metadata-response (v2-metadata! self)
                       response
                       (apply-entry!
                        self
                        {:outliner-op :save-block
                         :tx (protocol/tx->transit tx-data)}
                        7)
                       attestation
                       (storage/snapshot-integrity-attestation sql)
                       retained-txs (storage/fetch-tx-since sql 7)]
                 (is (= 200 (.-status metadata-response)))
                 (is (= "tx/batch/ok" (:type response)))
                 (is (= 9 (:t response) (storage/get-t sql))
                     "501 input entities are persisted as two bounded internal chunks")
                 (is (= [8 9] (mapv :t retained-txs)))
                 (is (= 7 (:tx-log-floor attestation)))
                 (is (storage/snapshot-integrity-attested? sql))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest verified-descriptor-allows-legacy-only-checksum-test
  (testing "an unavailable server-v2 checksum does not reject a healthy legacy graph"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]} (seed-valid-graph! conn "legacy-only")
                   _ (d/transact!
                      conn
                      [[:db/add [:block/uuid block-uuid]
                        :block/title (apply str (repeat 5000 "x"))]])
                   t (storage/get-t sql)
                   descriptor
                   (storage/verified-snapshot-integrity-descriptor
                    sql @conn t (str (random-uuid)))
                   descriptor-values
                   (storage/snapshot-integrity-descriptor-values descriptor)
                   _ (storage/seal-verified-snapshot-integrity!
                      sql descriptor)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql :conn conn :schema-ready true}]
               (is (string? (:checksum descriptor-values)))
               (is (nil? (:server-checksum descriptor-values)))
               (is (nil? (:server-checksum-t descriptor-values)))
               (is (storage/checksum-metadata-verified? sql t))
               (is (storage/snapshot-integrity-attested? sql))
               (p/let [response (v1-metadata! self)
                       body (json-body response)
                       stream (stream-rows! self (:url body))
                       imported
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows stream))]
                 (is (= 200 (.-status response)))
                 (is (= 200 (.-status (:response stream))))
                 (is (= (storage/get-checksum sql)
                        (sync-checksum/recompute-checksum @imported)))
                 (is (nil? (storage/get-server-checksum sql)))
                 (is (nil? (storage/get-server-checksum-t sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest same-cursor-raw-root-replacement-invalidates-attestation-and-heals-fresh-client-test
  (testing "a stale client remains mismatched, while a fresh .5 restore receives the validated remote root"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [replacement-sql live-sql]
             (doseq [sql [replacement-sql live-sql]]
               (storage/init-schema! sql))
             (let [old-conn (storage/open-conn live-sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! old-conn "client-old-root")
                   old-checksums (persist-current-checksums! live-sql old-conn)
                   t (storage/get-t live-sql)
                   _ (storage/set-server-checksum!
                      live-sql (:server-checksum old-checksums) t)
                   _ (storage/mark-checksum-metadata-verified! live-sql t)
                   first-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql live-sql :conn old-conn :schema-ready true}
                   replacement-conn (storage/open-conn replacement-sql)
                   {replacement-page-uuid :page-uuid}
                   (seed-valid-graph! replacement-conn "remote-new-root")
                   _ (d/transact!
                      replacement-conn
                      [{:db/ident :logseq.kv/graph-rtc-e2ee?
                        :kv/value true}])
                   replacement-rows (kvs-rows replacement-sql)
                   replacement-checksums (db-checksums replacement-conn)]
               (p/let [old-metadata-response (v2-metadata! first-self)
                       old-metadata (json-body old-metadata-response)
                       old-stream (stream-rows! first-self (:url old-metadata))
                       old-client
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows old-stream))
                       _ (is (storage/snapshot-integrity-attested? live-sql))
                       old-attestation
                       (storage/snapshot-integrity-attestation live-sql)
                       _ (storage/with-sql-transaction!
                          live-sql
                          (fn []
                            (common/sql-exec live-sql "delete from kvs")
                            (doseq [[addr content addresses] replacement-rows]
                              (common/sql-exec
                               live-sql
                               (str "insert into kvs "
                                    "(addr, content, addresses) values (?, ?, ?)")
                               addr content addresses))))
                       _ (is (= t (storage/get-t live-sql)))
                       _ (is (= old-attestation
                                (storage/snapshot-integrity-attestation live-sql))
                             "the stale marker is superficially unchanged")
                       _ (is (not (storage/snapshot-integrity-attested? live-sql))
                             "the exact raw root digest invalidates it")
                       ^js restarted-self
                       #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql live-sql :conn nil :schema-ready true}
                       fresh-metadata-response (v2-metadata! restarted-self)
                       fresh-metadata (json-body fresh-metadata-response)
                       fresh-stream
                       (stream-rows! restarted-self (:url fresh-metadata))
                       fresh-client
                       (replay-snapshot-rows-like-selfhost-five!
                        (:rows fresh-stream))]
                 (is (= 200 (.-status old-metadata-response)))
                 (is (= 200 (.-status fresh-metadata-response)))
                 (is (= (:checksum replacement-checksums)
                        (:checksum fresh-metadata)))
                 (is (= (:checksum replacement-checksums)
                        (sync-checksum/recompute-checksum @fresh-client)))
                 (is (= (:server-checksum replacement-checksums)
                        (storage/get-server-checksum live-sql)))
                 (is (= t (storage/get-server-checksum-t live-sql)))
                 (is (= "synthetic-remote-new-root"
                        (:block/name
                         (d/entity @fresh-client
                                   [:block/uuid replacement-page-uuid]))))
                 (is (not= (sync-checksum/recompute-checksum @old-client)
                           (:checksum fresh-metadata))
                     "an unreconstructed old client remains honestly mismatched")
                 (is (= block-uuid
                        (:block/uuid
                         (d/entity @old-client [:block/uuid block-uuid])))
                     "the Worker does not mutate or forge the stale client")
                 (is (storage/snapshot-integrity-attested? live-sql))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest same-cursor-child-kvs-replacement-invalidates-attestation-test
  (testing "a valid child-page replacement cannot retain an addr-0/1-only proof"
    (async done
      (-> (with-memory-sql-async
           (fn [live-sql]
             (storage/init-schema! live-sql)
             (let [page-uuid (random-uuid)
                   block-uuid (random-uuid)
                   created-at 1760000000000
                   live-conn (storage/open-conn live-sql)
                   _ (d/transact!
                      live-conn
                      [{:db/ident protected-system-kv
                        :kv/value created-at}
                       {:block/uuid page-uuid
                        :block/name "same-root-page"
                        :block/title "Same root page"}
                       {:block/uuid block-uuid
                        :block/title "old-child-value"
                        :block/order "a0"
                        :block/parent [:block/uuid page-uuid]
                        :block/page [:block/uuid page-uuid]}])
                   ;; Fold the target datoms out of addr 1 into persistent
                   ;; index pages, then leave an unrelated bounded tail.
                   _ (d/transact!
                      live-conn
                      (mapv (fn [i]
                              {:block/uuid (random-uuid)
                               :block/title (str "same-root-filler-" i)
                               :block/order (str "f" i)
                               :block/parent [:block/uuid page-uuid]
                               :block/page [:block/uuid page-uuid]})
                            (range 600)))
                   _ (d/transact!
                      live-conn
                      [{:block/uuid page-uuid
                        :block/updated-at 1760000000001}])
                   old-checksums (persist-current-checksums!
                                  live-sql live-conn)
                   self #js {:env
                             #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql live-sql
                             :conn live-conn
                             :schema-ready true}
                   child-rows
                   (->> (table-rows live-sql "kvs" "addr")
                        (filterv
                         (fn [{:keys [addr content]}]
                           (and (< 1 addr)
                                (string/includes?
                                 content "old-child-value")))))]
               (is (seq child-rows)
                   "the target fact must live outside the bounded root")
               (is (not-any?
                    (fn [{:keys [content]}]
                      (string/includes? content "old-child-value"))
                    (filterv #(<= (:addr %) 1)
                             (table-rows live-sql "kvs" "addr"))))
               (p/let [sealed (v2-metadata! self)
                       _ (is (= 200 (.-status sealed)))
                       attestation-before
                       (storage/snapshot-integrity-attestation live-sql)
                       _ (storage/with-sql-transaction!
                          live-sql
                          (fn []
                            (doseq [{:keys [addr content]} child-rows]
                              (common/sql-exec
                               live-sql
                               "update kvs set content = ? where addr = ?"
                               (string/replace content
                                               "old-child-value"
                                               "new-child-value")
                               addr))))
                       drifted-conn (storage/open-conn live-sql)]
                 (is (= attestation-before
                        (storage/snapshot-integrity-attestation live-sql))
                     "the persisted proof is superficially unchanged")
                 (is (= "new-child-value"
                        (:block/title
                         (d/entity @drifted-conn
                                   [:block/uuid block-uuid])))
                     "the child-page edit is a valid alternate snapshot")
                 (is (not= (:checksum old-checksums)
                           (sync-checksum/recompute-checksum @drifted-conn)))
                 (is (not (storage/snapshot-integrity-attested? live-sql))
                     "every persisted KVS mutation must invalidate the proof")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest live-kvs-mutation-generation-is-transactional-test
  (testing "material live mutations advance once per row while rollback and rejected writes do not"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [initial (storage/live-kvs-mutation-generation sql)]
          (is (= 0 initial))
          (try
            (storage/with-sql-transaction!
             sql
             (fn []
               (common/sql-exec
                sql
                (str "insert into kvs (addr, content, addresses) "
                     "values (991, 'rollback', null)"))
               (throw (js/Error. "synthetic rollback"))))
            (catch :default _))
          (is (= initial (storage/live-kvs-mutation-generation sql)))
          (is (empty?
               (common/get-sql-rows
                (common/sql-exec sql "select addr from kvs where addr = 991"))))
          (common/sql-exec
           sql
           (str "insert into kvs (addr, content, addresses) "
                "values (991, 'first', null)"))
          (is (= (inc initial)
                 (storage/live-kvs-mutation-generation sql)))
          (common/sql-exec
           sql
           (str "insert into kvs (addr, content, addresses) "
                "values (991, 'first', null) "
                "on conflict(addr) do update set content = excluded.content, "
                "addresses = excluded.addresses"))
          (is (= (inc initial)
                 (storage/live-kvs-mutation-generation sql))
              "an identical upsert is not a material mutation")
          (try
            (common/sql-exec
             sql
             (str "insert into kvs (addr, content, addresses) "
                  "values (991, 'conflict', null)"))
            (catch :default _))
          (is (= (inc initial)
                 (storage/live-kvs-mutation-generation sql))
              "a rejected primary-key write never advances the proof")
          (common/sql-exec
           sql "update kvs set content = 'second' where addr = 991")
          (is (= (+ 2 initial)
                 (storage/live-kvs-mutation-generation sql)))
          (common/sql-exec sql "delete from kvs where addr = 991")
          (is (= (+ 3 initial)
                 (storage/live-kvs-mutation-generation sql))))))))

(deftest deep-kvs-address-move-invalidates-attestation-atomically-test
  (testing "a raw deep-page primary-key move dirties integrity, while rollback preserves the sealed root"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [page-uuid]}
                   (seed-valid-graph! conn "deep-address-move")
                   _ (d/transact!
                      conn
                      (mapv (fn [i]
                              {:block/uuid (random-uuid)
                               :block/title (str "deep-address-filler-" i)
                               :block/order (str "f" i)
                               :block/parent [:block/uuid page-uuid]
                               :block/page [:block/uuid page-uuid]})
                            (range 600)))
                   _ (persist-current-checksums! sql conn)
                   t (storage/get-t sql)
                   descriptor
                   (storage/verified-snapshot-integrity-descriptor
                    sql @conn t (str (random-uuid)))
                   _ (storage/seal-verified-snapshot-integrity!
                      sql descriptor)
                   deep-row (last (table-rows sql "kvs" "addr"))
                   old-addr (:addr deep-row)
                   new-addr (+ old-addr 1000000)
                   generation-before
                   (storage/live-kvs-mutation-generation sql)
                   attestation-before
                   (storage/snapshot-integrity-attestation sql)
                   snapshot-counts-before (snapshot-state-counts sql)
                   self #js {:env
                             #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (is (< 1 old-addr)
                   "the synthetic move must target a persisted deep page")
               (try
                 (storage/with-sql-transaction!
                  sql
                  (fn []
                    (common/sql-exec
                     sql "update kvs set addr = ? where addr = ?"
                     new-addr old-addr)
                    (throw (js/Error. "synthetic address rollback"))))
                 (catch :default _))
               (is (= generation-before
                      (storage/live-kvs-mutation-generation sql))
                   "rollback restores the mutation generation")
               (is (= attestation-before
                      (storage/snapshot-integrity-attestation sql)))
               (is (= [old-addr]
                      (->> (table-rows sql "kvs" "addr")
                           (filter #(contains? #{old-addr new-addr}
                                               (:addr %)))
                           (mapv :addr)))
                   "rollback preserves the original raw page address")
               (is (storage/snapshot-integrity-attested? sql))
               (common/sql-exec
                sql "update kvs set addr = ? where addr = ?"
                new-addr old-addr)
               (is (= (inc generation-before)
                      (storage/live-kvs-mutation-generation sql))
                   "changing the primary-key address is a material KVS mutation")
               (is (not (storage/snapshot-integrity-attested? sql))
                   "the old exact-root proof cannot authorize a moved page")
               (p/let [response (v2-metadata! self)]
                 (is (not= 200 (.-status response))
                     "a structurally torn raw store cannot be downloaded")
                 (is (= snapshot-counts-before
                        (snapshot-state-counts sql))
                     "failed validation cannot publish a snapshot export")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest pre-generation-attestation-bootstraps-and-persists-on-restart-test
  (testing "a pre-migration proof is dirty, then one full verify seals the current generation"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "generation-migration")
                   _ (persist-current-checksums! sql conn)
                   self #js {:env
                             #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql :conn conn :schema-ready true}]
               (p/let [first-response (v2-metadata! self)
                       _ (is (= 200 (.-status first-response)))
                       attestation
                       (storage/snapshot-integrity-attestation sql)
                       _ (common/sql-exec
                          sql
                          (str "update integrity_attestations set value = ? "
                               "where scope = 'live-snapshot'")
                          (common/write-transit
                           (dissoc attestation :kvs-generation)))
                       _ (is (not (storage/snapshot-integrity-attested? sql)))
                       restarted-self
                       #js {:env
                            #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql sql :conn nil :schema-ready false}
                       restarted-response (v2-metadata! restarted-self)
                       sealed
                       (storage/snapshot-integrity-attestation sql)]
                 (is (= 200 (.-status restarted-response)))
                 (is (= (storage/live-kvs-mutation-generation sql)
                        (:kvs-generation sealed)))
                 (is (storage/snapshot-integrity-attested? sql))
                 (is (some? (aget restarted-self "conn")))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v2-download-fails-closed-when-origin-misses-registered-system-kv-test
  (testing "a Worker must never freeze a snapshot that delegates system-KV repair to a client"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "missing-kv-origin")
                   _ (d/transact! conn
                                  [[:db/retractEntity protected-system-kv]])
                   first-self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                                   :sql sql
                                   :conn conn
                                   :schema-ready true}
                   restarted-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql sql
                        :conn nil
                        :schema-ready true}]
               (is (contains? kv-entity/kv-entities protected-system-kv)
                   "the fixture must target a registered Logseq system KV")
               (is (nil? (d/entity @conn protected-system-kv)))
               (p/let [first-response (v2-metadata! first-self)
                       first-body (json-body first-response)
                       restarted-response (v2-metadata! restarted-self)
                       restarted-body (json-body restarted-response)]
                 (is (not (<= 200 (.-status first-response) 299))
                     "the original DO instance must reject the invalid origin")
                 (is (not (string? (:url first-body)))
                     "no frozen stream identity may escape")
                 (is (not (<= 200 (.-status restarted-response) 299))
                     "a DO restart must not bypass the guard")
                 (is (not (string? (:url restarted-body))))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql))
                     "rejected requests must not consume frozen-download capacity")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest missing-created-at-v2-falls-back-to-replayable-v1-without-live-mutation-test
  (testing "a healthy pre-system-KV graph keeps the old download path without weakening corruption gates"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [healthy-sql damaged-sql]
             (storage/init-schema! healthy-sql)
             (storage/init-schema! damaged-sql)
             (let [healthy-conn (storage/open-conn healthy-sql)
                   _ (seed-valid-graph! healthy-conn "legacy-missing-created-at")
                   _ (d/transact!
                      healthy-conn [[:db/retractEntity protected-system-kv]])
                   healthy-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql healthy-sql
                        :conn healthy-conn
                        :schema-ready true}
                   restarted-healthy-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql healthy-sql
                        :conn nil
                        :schema-ready true}
                   healthy-live-before (live-durable-state healthy-sql)
                   healthy-sync-meta-before
                   (table-rows healthy-sql "sync_meta" "key")
                   healthy-attestation-before
                   (table-rows healthy-sql "integrity_attestations" "scope")
                   healthy-generation-before
                   (storage/live-kvs-mutation-generation healthy-sql)
                   healthy-facts-before (db-facts @healthy-conn)
                   {damaged-conn :conn}
                   (seed-index-torn-ident-snapshot!
                    damaged-sql :synthetic.unknown/not-a-built-in)
                   _ (d/transact!
                      damaged-conn [[:db/retractEntity protected-system-kv]])
                   damaged-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql damaged-sql
                        :conn (storage/open-conn damaged-sql)
                        :schema-ready true}
                   damaged-before (live-durable-state damaged-sql)]
               (p/let [v2-response (v2-metadata! healthy-self)
                       v2-body (json-body v2-response)
                       restarted-v2-response
                       (v2-metadata! restarted-healthy-self)
                       restarted-v2-body (json-body restarted-v2-response)
                       healthy-after-v2 (live-durable-state healthy-sql)
                       sync-meta-after-v2
                       (table-rows healthy-sql "sync_meta" "key")
                       attestation-after-v2
                       (table-rows
                        healthy-sql "integrity_attestations" "scope")
                       v1-response (v1-metadata! restarted-healthy-self)
                       v1-body (json-body v1-response)
                       pre-system-frozen-state
                       (snapshot-state-counts healthy-sql)
                       v1-stream
                       (when (= 200 (.-status v1-response))
                         (stream-rows!
                          restarted-healthy-self (:url v1-body)))
                       imported
                       (when v1-stream
                         (replay-snapshot-rows-like-selfhost-five!
                          (:rows v1-stream)))
                       damaged-v2-response (v2-metadata! damaged-self)
                       damaged-v2-body (json-body damaged-v2-response)
                       damaged-v1-response (v1-metadata! damaged-self)
                       damaged-v1-body (json-body damaged-v1-response)]
                 (is (= 404 (.-status v2-response))
                     ".5 must receive its only supported fallback signal")
                 (is (not (string? (:url v2-body))))
                 (is (= 404 (.-status restarted-v2-response))
                     "a DO restart must preserve the same fallback contract")
                 (is (not (string? (:url restarted-v2-body))))
                 (is (= healthy-live-before healthy-after-v2))
                 (is (= healthy-sync-meta-before sync-meta-after-v2))
                 (is (= healthy-attestation-before attestation-after-v2))
                 (is (= healthy-generation-before
                        (storage/live-kvs-mutation-generation healthy-sql)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts healthy-sql))
                     "v2 fallback probing must not freeze or mutate anything")
                 (is (= 200 (.-status v1-response)))
                 (is (= {:ok true
                         :key "stream/synthetic-graph.snapshot"
                         :url "http://localhost/sync/synthetic-graph/snapshot/stream"}
                        v1-body))
                 (is (= {:downloads 1
                         :exports (count (kvs-rows healthy-sql))}
                        pre-system-frozen-state)
                     "the pre-system-KV fallback remains one eager export")
                 (is (= 200 (some-> v1-stream :response .-status)))
                 (is (= (kvs-rows healthy-sql) (:rows v1-stream)))
                 (is (= healthy-facts-before
                        (some-> imported deref db-facts))
                     "the legacy stream fully replays the healthy pre-system-KV DB")
                 (is (= healthy-live-before
                        (live-durable-state healthy-sql))
                     "legacy export must not rewrite the live graph")
                 (is (= healthy-sync-meta-before
                        (table-rows healthy-sql "sync_meta" "key")))
                 (is (= healthy-attestation-before
                        (table-rows
                         healthy-sql "integrity_attestations" "scope")))
                 (is (= healthy-generation-before
                        (storage/live-kvs-mutation-generation healthy-sql)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts healthy-sql))
                     "a consumed legacy fallback leaves no persistent export")
                 (is (empty? (snapshot-download-generation-rows healthy-sql)))
                 (is (= 409 (.-status damaged-v2-response))
                     "v2 404 is never a blanket exemption for index damage")
                 (is (not (string? (:url damaged-v2-body))))
                 (is (= 409 (.-status damaged-v1-response))
                     "missing metadata never excuses an unknown unique-index tear")
                 (is (not (string? (:url damaged-v1-body))))
                 (is (= damaged-before (live-durable-state damaged-sql)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts damaged-sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest missing-created-at-never-masks-dangling-reference-test
  (testing "the v2 404 classifier and v1 fallback both retain reference closure"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [page-uuid]}
                   (seed-valid-graph! conn "missing-created-at-dangling-ref")
                   _ (d/transact!
                      conn
                      [[:db/retractEntity protected-system-kv]
                       {:block/uuid (random-uuid)
                        :block/title "dangling reference"
                        :block/parent 999999
                        :block/page [:block/uuid page-uuid]}])
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}
                   before (live-durable-state sql)]
               (is (empty? (d/datoms @conn :eavt 999999)))
               (p/let [v2-response (v2-metadata! self)
                       v2-body (json-body v2-response)
                       v1-response (v1-metadata! self)
                       v1-body (json-body v1-response)]
                 (is (= 409 (.-status v2-response)))
                 (is (not (string? (:url v2-body))))
                 (is (= 409 (.-status v1-response)))
                 (is (not (string? (:url v1-body))))
                 (is (= before (live-durable-state sql)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v2-download-restores-missing-system-kv-only-from-authoritative-d1-test
  (testing ".5 receives v2 instead of a non-fallback 409 when D1 proves graph creation time"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "d1-created-at")
                   _ (d/transact! conn
                                  [[:db/retractEntity protected-system-kv]])
                   authoritative-created-at 1700000123456
                   self #js {:env #js {"DB" #js {}
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (p/with-redefs
                 [index/<graph-ready-for-use?
                  (fn [_db _graph-id] (p/resolved true))
                  index/<graph-created-at
                  (fn [_db graph-id]
                    (is (= "synthetic-graph" graph-id))
                    (p/resolved authoritative-created-at))]
                 (p/let [response (v2-metadata! self)
                         body (json-body response)
                         stream-result (stream-rows! self (:url body))
                         imported
                         (replay-snapshot-rows-like-selfhost-five!
                          (:rows stream-result))]
                   (is (= 200 (.-status response)))
                   (is (= 200 (.-status (:response stream-result))))
                   (is (= authoritative-created-at
                          (:kv/value
                           (d/entity @imported protected-system-kv))))
                   (is (= authoritative-created-at
                          (:kv/value
                           (d/entity @(.-conn self)
                                     protected-system-kv))))
                   (is (storage/snapshot-integrity-attested? sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v2-upload-cannot-activate-snapshot-missing-registered-system-kv-test
  (testing "a checksum-valid but system-KV-incomplete upload is not a valid graph"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (storage/init-schema! source-sql)
             (storage/init-schema! destination-sql)
             (let [source-conn (storage/open-conn source-sql)
                   source-state (seed-valid-graph! source-conn "bad-upload")
                   _ (d/transact! source-conn
                                  [[:db/retractEntity protected-system-kv]])
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   source-rows (kvs-rows source-sql)
                   destination-conn (storage/open-conn destination-sql)
                   destination-state
                   (seed-valid-graph! destination-conn "live-before-upload")
                   live-rows-before (kvs-rows destination-sql)
                   ready-events (atom [])
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn destination-conn
                             :schema-ready true}
                   request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=" (random-uuid)
                         "&checksum=" source-checksum
                         "&row-count=" (count source-rows))
                    source-rows)]
               (is (nil? (d/entity @source-conn protected-system-kv)))
               (is (sync-checksum/valid-checksum? source-checksum)
                   "the rejected upload is checksum-valid under the legacy contract")
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id ready?]
                    (swap! ready-events conj ready?)
                    (p/resolved true))]
                 (p/let [response
                         (sync-handler/handle
                          {:self self
                           :request request
                           :url (js/URL. (.-url request))
                           :route {:handler :sync/snapshot-upload-v2}})
                         _body (json-body response)
                         persisted-conn (storage/open-conn destination-sql)]
                   (is (not (<= 200 (.-status response) 299))
                       "an incomplete registered-KV snapshot must not commit")
                   (is (= live-rows-before (kvs-rows destination-sql))
                       "rejection must preserve the previously active graph")
                   (is (not-any? true? @ready-events)
                       "the incomplete graph must never be marked ready")
                   (is (= (:created-at destination-state)
                          (:kv/value
                           (d/entity @persisted-conn protected-system-kv))))
                   (is (nil?
                        (d/entity @persisted-conn
                                  [:block/uuid (:block-uuid source-state)]))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v1-and-v2-upload-reject-client-checksum-that-disagrees-with-canonical-staging-test
  (testing "neither upload protocol can publish a client-declared stale checksum"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph! source-conn "checksum-source")
                   source-rows (kvs-rows source-sql)
                   correct-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   wrong-checksum
                   (if (= correct-checksum "0000000000000000")
                     "1111111111111111"
                     "0000000000000000")
                   destination-conn (storage/open-conn destination-sql)
                   _ (seed-valid-graph! destination-conn "checksum-live")
                   _ (persist-current-checksums!
                      destination-sql destination-conn)
                   live-before (live-durable-state destination-sql)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn destination-conn
                             :schema-ready true}
                   ready-events (atom [])]
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id value]
                    (swap! ready-events conj value)
                    (p/resolved true))]
                 (p/let [v2-response
                         (sync-handler/handle-http
                          self
                          (snapshot-upload-request
                           (str "reset=true&finished=true"
                                "&upload-id=" (random-uuid)
                                "&checksum=" wrong-checksum
                                "&row-count=" (count source-rows))
                           source-rows))
                         _ (is (= 409 (.-status v2-response)))
                         _ (is (= live-before
                                  (live-durable-state destination-sql)))
                         _ (is (empty? (staging-rows destination-sql)))
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         v1-response
                         (handle-legacy-upload!
                          self
                         (legacy-snapshot-upload-request
                           (str "reset=true&finished=true&checksum="
                                wrong-checksum)
                           source-rows))]
                   (is (= 409 (.-status v1-response)))
                   (is (= live-before
                          (live-durable-state destination-sql)))
                   (is (empty? (staging-rows destination-sql)))
                   (is (sync-handler/snapshot-upload-finished? self))
                   (is (empty? @ready-events))
                   (is (not= wrong-checksum correct-checksum))
                   (p/let [retry-response
                           (sync-handler/handle-http
                            self
                            (snapshot-upload-request
                             (str "reset=true&finished=true"
                                  "&upload-id=" (random-uuid)
                                  "&checksum=" correct-checksum
                                  "&row-count=" (count source-rows))
                             source-rows))]
                     (is (= 200 (.-status retry-response)))
                     (is (= source-rows (kvs-rows destination-sql)))
                     (is (empty? (staging-rows destination-sql)))
                     (is (sync-handler/snapshot-upload-finished? self))
                     (is (= [true] @ready-events)
                         "validation failure never leaves readiness or upload gates stuck")))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest download-rejects-every-unproven-unique-index-canonicalization-test
  (testing "user data, references, unknown idents, and other unique attrs never auto-merge"
    (async done
      (let [cases
            [{:label "known ghost carrying user data"
              :setup
              (fn [sql]
                (let [{:keys [conn shadow-eid] :as state}
                      (seed-index-torn-gallery-snapshot! sql)
                      datom (d/datom shadow-eid :block/title
                                     "must-not-be-merged"
                                     (:max-tx @conn))]
                  (persist-raw-datom! conn datom false)
                  (assoc state :conn (storage/open-conn sql))))}
             {:label "known ghost referenced by a block"
              :setup
              (fn [sql]
                (let [{:keys [conn shadow-eid page-uuid] :as state}
                      (seed-index-torn-gallery-snapshot! sql)]
                  (d/transact!
                   conn
                   [{:block/uuid (random-uuid)
                     :block/title "references-shadow"
                     :block/parent shadow-eid
                     :block/page [:block/uuid page-uuid]}])
                  (assoc state :conn (storage/open-conn sql))))}
             {:label "unknown duplicated ident"
              :setup
              (fn [sql]
                (seed-index-torn-ident-snapshot!
                 sql :synthetic.unknown/not-a-built-in))}
             {:label "built-in namespace lookalike is not catalog membership"
              :setup
              (fn [sql]
                (seed-index-torn-ident-snapshot!
                 sql :logseq.property.view/not-a-current-built-in))}
             {:label "user-defined custom property is not a built-in"
              :setup
              (fn [sql]
                (seed-index-torn-ident-snapshot!
                 sql :user.property/custom))}
             {:label "registered optional system KV is not a built-in property"
              :setup
              (fn [sql]
                (seed-index-torn-ident-snapshot!
                 sql :logseq.kv/graph-remote?))}
             {:label "a different unique attribute"
              :setup
              (fn [sql]
                (storage/init-schema! sql)
                (let [conn (storage/open-conn sql)
                      _ (seed-valid-graph! conn "other-unique")
                      canonical-eid 121
                      shadow-eid 120
                      duplicated-uuid (random-uuid)
                      _ (d/transact!
                         conn
                         [{:db/id canonical-eid
                           :block/uuid duplicated-uuid
                           :block/title "canonical-uuid"}])
                      canonical-datom
                      (first (d/datoms @conn :eavt canonical-eid
                                       :block/uuid duplicated-uuid))
                      shadow-datom
                      (d/datom shadow-eid :block/uuid duplicated-uuid
                               (:tx canonical-datom))]
                  (persist-raw-datom! conn shadow-datom false)
                  {:conn (storage/open-conn sql)}))}]]
        (-> (p/loop [remaining cases]
              (if-let [{:keys [label setup]} (first remaining)]
                (p/let [_
                        (with-memory-sql-async
                         (fn [sql]
                           (let [{:keys [conn]} (setup sql)
                                 live-before (live-durable-state sql)
                                 self
                                 #js {:env
                                      #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                           "false"}
                                      :sql sql
                                      :conn conn
                                      :schema-ready true}]
                             (p/let [response (v2-metadata! self)
                                     body (json-body response)]
                               (is (= 409 (.-status response)) label)
                               (is (not (string? (:url body))) label)
                               (is (= live-before
                                      (live-durable-state sql))
                                   (str label " preserves live atomically"))
                               (is (empty? (staging-rows sql)) label)
                               (is (= {:downloads 0 :exports 0}
                                      (snapshot-state-counts sql))
                                   label)))))]
                  (p/recur (next remaining)))
                nil))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest v2-metadata-and-stream-share-one-frozen-system-kv-state-test
  (testing "a concurrent tx is pulled after, never mixed into, the frozen snapshot"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid created-at]}
                   (seed-valid-graph! conn "v2-frozen")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}]
               (p/let [metadata-response (v2-metadata! self)
                       metadata (json-body metadata-response)
                       snapshot-t (:t metadata)
                       download-id (download-id-from-url (:url metadata))
                       frozen-row
                       (-> (common/sql-exec
                            sql
                            (str "select download_id, t from snapshot_downloads "
                                 "where download_id = ?")
                            download-id)
                           common/get-sql-rows
                           first)
                       tx-response
                       (apply-entry!
                        self
                        {:tx-id (random-uuid)
                         :outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "after-concurrent-tx"]])}
                        snapshot-t)
                       restarted-self
                       #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql sql
                            :conn nil
                            :schema-ready true}
                       stream-result (stream-rows! restarted-self (:url metadata))
                       projection (rows-projection (:rows stream-result) block-uuid)
                       pull (sync-handler/pull-response restarted-self snapshot-t)]
                 (is (= 200 (.-status metadata-response)))
                 (is (= true (:ok metadata)))
                 (is (= "stream/synthetic-graph.snapshot" (:key metadata)))
                 (is (number? snapshot-t))
                 (is (string? (:checksum metadata)))
                 (is (pos? (:row-count metadata)))
                 (is (= download-id (aget frozen-row "download_id")))
                 (is (= snapshot-t (aget frozen-row "t"))
                     "metadata and exported rows use one persisted identity")
                 (is (= "tx/batch/ok" (:type tx-response)))
                 (is (= (inc snapshot-t) (:t tx-response)))
                 (is (= 200 (.-status (:response stream-result))))
                 (is (= created-at (:created-at projection))
                     "the frozen stream itself contains the registered KV")
                 (is (= "before-concurrent-tx" (:title projection)))
                 (is (= "after-concurrent-tx"
                        (:block/title
                         (d/entity @conn
                                   [:block/uuid block-uuid]))))
                 (is (= "pull/ok" (:type pull)))
                 (is (= (inc snapshot-t) (:t pull)))
                 (is (= 1 (count (:txs pull))))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest repeated-v2-requests-survive-do-restart-with-independent-identities-test
  (async done
    (-> (with-memory-sql-async
         (fn [sql]
           (storage/init-schema! sql)
           (let [conn (storage/open-conn sql)
                 {:keys [block-uuid created-at]}
                 (seed-valid-graph! conn "repeat-v2")
                 self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                           :sql sql
                           :conn conn
                           :schema-ready true}]
             (p/let [response-a (v2-metadata! self)
                     metadata-a (json-body response-a)
                     response-b (v2-metadata! self)
                     metadata-b (json-body response-b)
                     id-a (download-id-from-url (:url metadata-a))
                     id-b (download-id-from-url (:url metadata-b))
                     frozen-before-stream
                     (snapshot-state-counts sql)
                     restarted-a
                     #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                          :sql sql :conn nil :schema-ready true}
                     stream-a (stream-rows! restarted-a (:url metadata-a))
                     projection-a (rows-projection (:rows stream-a) block-uuid)
                     restarted-b
                     #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                          :sql sql :conn nil :schema-ready true}
                     stream-b (stream-rows! restarted-b (:url metadata-b))
                     projection-b (rows-projection (:rows stream-b) block-uuid)]
               (is (= 200 (.-status response-a)))
               (is (= 200 (.-status response-b)))
               (is (not= id-a id-b))
               (is (= (:t metadata-a) (:t metadata-b)))
               (is (= {:downloads 2
                       :exports (* 2 (count (kvs-rows sql)))}
                      frozen-before-stream)
                   "v2 keeps one eager immutable export per download id")
               (is (= created-at (:created-at projection-a)))
               (is (= projection-a projection-b)
                   "both persistent frozen identities describe the same origin")
               (is (= {:downloads 0 :exports 0}
                      (snapshot-state-counts sql)))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest legacy-v1-download-and-live-stream-shape-remain-compatible-test
  (async done
    (-> (with-memory-sql-async
         (fn [sql]
           (storage/init-schema! sql)
           (let [conn (storage/open-conn sql)
                 {:keys [block-uuid created-at]}
                 (seed-valid-graph! conn "legacy-v1")
                 self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                           :sql sql
                           :conn conn
                           :schema-ready true}]
             (p/let [metadata-response (v1-metadata! self)
                     metadata (json-body metadata-response)
                     reserved-state (snapshot-state-counts sql)
                     tx-response
                     (apply-entry!
                      self
                      {:outliner-op :save-block
                       :tx (protocol/tx->transit
                            [[:db/add [:block/uuid block-uuid]
                              :block/title "legacy-live-after-metadata"]])})
                     stream-result (stream-rows! self (:url metadata))
                     projection (rows-projection (:rows stream-result) block-uuid)]
               (is (= 200 (.-status metadata-response)))
               (is (= {:ok true
                       :key "stream/synthetic-graph.snapshot"
                       :url "http://localhost/sync/synthetic-graph/snapshot/stream"}
                      metadata)
                   "v1 metadata is not replaced by the v2 frozen envelope")
               (is (= {:downloads 1 :exports 0} reserved-state)
                   "the unchanged v1 wire binds a durable live reservation")
               (is (= "tx/batch/ok" (:type tx-response)))
               (is (= 200 (.-status (:response stream-result))))
               (is (= created-at (:created-at projection)))
               (is (= "before-concurrent-tx" (:title projection))
                   "v1 metadata and stream must never mix snapshot generations")
               (is (= {:downloads 0 :exports 0}
                      (snapshot-state-counts sql)))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest repeated-v1-metadata-reuses-one-current-live-reservation-test
  (testing "same-basis v1 metadata leases share one live reservation without copying KVS"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! conn "legacy-v1-shared-freeze")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}
                   original-exec (.-exec sql)
                   export-copy-count (atom 0)]
               (set! (.-exec sql)
                     (fn [sql-str & args]
                       (let [normalized (-> sql-str
                                            string/trim
                                            string/lower-case)]
                         (when (and
                                (string/includes?
                                 normalized
                                 "insert into snapshot_kvs_exports")
                                (string/includes? normalized "select ?"))
                           (swap! export-copy-count inc))
                         (.apply original-exec sql
                                 (to-array (cons sql-str args))))))
               (p/let [response-a (v1-metadata! self)
                       metadata-a (json-body response-a)
                       response-b (v1-metadata! self)
                       metadata-b (json-body response-b)
                       frozen-state (snapshot-state-counts sql)
                       generation-rows
                       (snapshot-download-generation-rows sql)
                       leased-download-id
                       (some-> generation-rows first (aget "download_id"))
                       queued-download-ids
                       (common/read-transit
                        (storage/get-meta
                         sql :legacy-snapshot-download-queue))
                       stream-results
                       (p/all [(stream-rows! self (:url metadata-a))
                               (stream-rows! self (:url metadata-b))])
                       projection-a
                       (rows-projection (:rows (first stream-results))
                                        block-uuid)
                       projection-b
                       (rows-projection (:rows (second stream-results))
                                        block-uuid)]
                 (is (= 200 (.-status response-a)))
                 (is (= 200 (.-status response-b)))
                 (is (= {:ok true
                         :key "stream/synthetic-graph.snapshot"
                         :url "http://localhost/sync/synthetic-graph/snapshot/stream"}
                        metadata-a metadata-b)
                     "the v1 response envelope stays byte-for-byte compatible")
                 (is (zero? @export-copy-count)
                     "same-basis metadata must not copy the full KVS")
                 (is (= 1 (:downloads frozen-state)))
                 (is (= 1 (count generation-rows)))
                 (is (= 2 (aget (first generation-rows) "lease_count")))
                 (is (= [leased-download-id leased-download-id]
                        queued-download-ids)
                     "each v1 response has a queue lease on the same export")
                 (is (= projection-a projection-b))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))
                 (is (empty? (snapshot-download-generation-rows sql))
                     "the second completed stream releases the reservation")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest completed-v1-stream-releases-reservation-and-next-metadata-still-does-not-copy-test
  (testing "completed v1 streams leave no cache rows and later metadata creates a copy-free reservation"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "legacy-v1-completed-cache")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}
                   original-exec (.-exec sql)
                   export-copy-count (atom 0)]
               (set! (.-exec sql)
                     (fn [sql-str & args]
                       (let [normalized (-> sql-str
                                            string/trim
                                            string/lower-case)]
                         (when (and
                                (string/includes?
                                 normalized
                                 "insert into snapshot_kvs_exports")
                                (string/includes? normalized "select ?"))
                           (swap! export-copy-count inc))
                         (.apply original-exec sql
                                 (to-array (cons sql-str args))))))
               (p/let [first-response (v1-metadata! self)
                       first-metadata (json-body first-response)
                       first-stream (stream-rows! self (:url first-metadata))
                       state-after-first
                       (snapshot-state-counts sql)
                       second-response (v1-metadata! self)
                       second-metadata (json-body second-response)
                       state-before-second-stream
                       (snapshot-state-counts sql)
                       leased-generation-before-second-stream
                       (first (snapshot-download-generation-rows sql))
                       second-stream (stream-rows! self (:url second-metadata))
                       state-after-second
                       (snapshot-state-counts sql)
                       generations-after-second
                       (snapshot-download-generation-rows sql)]
                 (is (= 200 (.-status first-response)))
                 (is (= 200 (.-status (:response first-stream))))
                 (is (= {:downloads 0 :exports 0} state-after-first)
                     "the completed stream leaves no persistent cache")
                 (is (= 200 (.-status second-response)))
                 (is (= first-metadata second-metadata))
                 (is (zero? @export-copy-count)
                     "a later metadata request must still avoid a KVS copy")
                 (is (= {:downloads 1 :exports 0}
                        state-before-second-stream)
                     "metadata persists only a live basis reservation")
                 (is (= 1
                        (aget leased-generation-before-second-stream
                              "lease_count")))
                 (is (= 200 (.-status (:response second-stream))))
                 (is (= {:downloads 0 :exports 0} state-after-second))
                 (is (empty? generations-after-second))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest repeated-v1-metadata-leases-do-not-consume-physical-export-capacity-test
  (testing "bounded same-basis consumers share one physical export without false 429s"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "legacy-v1-many-leases")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}
                   request-count 8]
               (p/let [responses
                       (p/all
                        (mapv (fn [_] (v1-metadata! self))
                              (range request-count)))
                       metadata
                       (p/all (mapv json-body responses))
                       generation-rows
                       (snapshot-download-generation-rows sql)
                       download-id
                       (some-> generation-rows first (aget "download_id"))
                       queued-download-ids
                       (common/read-transit
                        (storage/get-meta
                         sql :legacy-snapshot-download-queue))
                       frozen-state (snapshot-state-counts sql)
                       streams
                       (p/all
                        (mapv #(stream-rows! self (:url %)) metadata))]
                 (is (every? #(= 200 (.-status %)) responses))
                 (is (= 1 (:downloads frozen-state)))
                 (is (= 1 (count generation-rows)))
                 (is (= request-count
                        (aget (first generation-rows) "lease_count")))
                 (is (= (vec (repeat request-count download-id))
                        queued-download-ids))
                 (is (= request-count (count streams)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))
                 (is (empty? (snapshot-download-generation-rows sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest repeated-v1-metadata-lease-cap-is-atomic-test
  (testing "the bounded consumer cap rejects one request without another copy or queue leak"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "legacy-v1-lease-cap")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}
                   original-exec (.-exec sql)
                   export-copy-count (atom 0)]
               (set! (.-exec sql)
                     (fn [sql-str & args]
                       (let [normalized (-> sql-str
                                            string/trim
                                            string/lower-case)]
                         (when (and
                                (string/includes?
                                 normalized
                                 "insert into snapshot_kvs_exports")
                                (string/includes? normalized "select ?"))
                           (swap! export-copy-count inc))
                         (.apply original-exec sql
                                 (to-array (cons sql-str args))))))
               (p/let [accepted-responses
                       (p/all
                        (mapv (fn [_] (v1-metadata! self)) (range 64)))
                       accepted-metadata
                       (p/all (mapv json-body accepted-responses))
                       rejected-response (v1-metadata! self)
                       rejected-body (json-body rejected-response)
                       generation-rows
                       (snapshot-download-generation-rows sql)
                       download-id
                       (some-> generation-rows first (aget "download_id"))
                       queued-download-ids
                       (common/read-transit
                        (storage/get-meta
                         sql :legacy-snapshot-download-queue))
                       streams
                       (p/all
                        (mapv #(stream-rows! self (:url %))
                              accepted-metadata))]
                 (is (every? #(= 200 (.-status %)) accepted-responses))
                 (is (= 429 (.-status rejected-response)))
                 (is (= {:error "snapshot download busy; retry later"}
                        rejected-body))
                 (is (zero? @export-copy-count))
                 (is (= 1 (count generation-rows)))
                 (is (= 64 (aget (first generation-rows) "lease_count")))
                 (is (= (vec (repeat 64 download-id))
                        queued-download-ids)
                     "the rejected request must not add an unserviceable queue item")
                 (is (= 64 (count streams)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))
                 (is (empty? (snapshot-download-generation-rows sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v1-metadata-generation-change-is-busy-until-the-fixed-url-claim-is-consumed-test
  (testing "a fixed v1 stream URL never guesses between queued generations"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! conn "legacy-v1-generation-change")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}]
               (p/let [response-a (v1-metadata! self)
                       metadata-a (json-body response-a)
                       tx-response
                       (apply-entry!
                        self
                        {:outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "after-generation-change"]])})
                       busy-response (v1-metadata! self)
                       busy-body (json-body busy-response)
                       generations-before-old-stream
                       (snapshot-download-generation-rows sql)
                       stream-a (stream-rows! self (:url metadata-a))
                       response-b (v1-metadata! self)
                       metadata-b (json-body response-b)
                       generations-before-new-stream
                       (snapshot-download-generation-rows sql)
                       stream-b (stream-rows! self (:url metadata-b))
                       projection-a
                       (rows-projection (:rows stream-a) block-uuid)
                       projection-b
                       (rows-projection (:rows stream-b) block-uuid)]
                 (is (= 200 (.-status response-a)))
                 (is (= "tx/batch/ok" (:type tx-response)))
                 (is (= 429 (.-status busy-response)))
                 (is (= {:error "snapshot download busy; retry later"}
                        busy-body))
                 (is (= 200 (.-status response-b)))
                 (is (= metadata-a metadata-b)
                     "generation binding stays internal to the unchanged v1 envelope")
                 (is (= 1 (count generations-before-old-stream))
                     "the newer basis is not queued behind an ambiguous fixed URL")
                 (is (= 1 (count generations-before-new-stream)))
                 (is (= "before-concurrent-tx" (:title projection-a)))
                 (is (= "after-generation-change" (:title projection-b)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))
                 (is (empty? (snapshot-download-generation-rows sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v1-live-reservation-cow-rolls-back-with-the-live-mutation-test
  (testing "a failed write rolls back both its KVS change and its lazy snapshot copy"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! conn "legacy-v1-cow-rollback")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}
                   live-before (live-durable-state sql)
                   rollback-write!
                   (fn []
                     (try
                       (storage/with-sql-transaction!
                        sql
                        (fn []
                          (common/sql-exec
                           sql
                           "update kvs set content = ? where addr = 0"
                           "synthetic-invalid-root")
                          (throw
                           (js/Error. "synthetic write rollback"))))
                       :unexpected-success
                       (catch :default error {:error error})))]
               (p/let [metadata-response (v1-metadata! self)
                       metadata (json-body metadata-response)
                       _ (is (= {:downloads 1 :exports 0}
                                (snapshot-state-counts sql))
                             "metadata records a live reservation only")
                       fault (rollback-write!)
                       _ (is (instance? js/Error (:error fault)))
                       _ (is (= live-before (live-durable-state sql)))
                       _ (is (= {:downloads 1 :exports 0}
                                (snapshot-state-counts sql))
                             "the rolled-back COW leaves a retryable reservation")
                       tx-response
                       (apply-entry!
                        self
                        {:outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "after-cow-rollback"]])})
                       _ (is (= "tx/batch/ok" (:type tx-response)))
                       _ (is (pos? (:exports (snapshot-state-counts sql)))
                             "the successful retry freezes the old basis once")
                       old-stream (stream-rows! self (:url metadata))
                       old-projection
                       (rows-projection (:rows old-stream) block-uuid)]
                 (is (= 200 (.-status metadata-response)))
                 (is (= "before-concurrent-tx" (:title old-projection)))
                 (is (= "after-cow-rollback"
                        (:block/title
                         (d/entity @(.-conn self)
                                   [:block/uuid block-uuid]))))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v1-live-stream-switches-to-the-frozen-basis-after-its-first-frame-test
  (testing "a transaction between stream pulls cannot mix snapshot generations"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [page-uuid block-uuid]}
                   (seed-valid-graph! conn "legacy-v1-mid-stream-cow")
                   _ (d/transact!
                      conn
                      (mapv
                       (fn [i]
                         {:block/uuid (random-uuid)
                          :block/title (str "stream-padding-" i)
                          :block/order (str "a" (+ i 1))
                          :block/parent [:block/uuid page-uuid]
                          :block/page [:block/uuid page-uuid]})
                       (range 400)))
                   kvs-row-count (count (kvs-rows sql))
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}]
               (is (> kvs-row-count 256)
                   (str "fixture must cross a stream batch: " kvs-row-count))
               (p/let [metadata-response (v1-metadata! self)
                       metadata (json-body metadata-response)
                       stream-response
                       (sync-handler/handle-http
                        self (js/Request. (:url metadata)))
                       reader (.getReader (.-body stream-response))
                       first-part (.read reader)
                       _ (is (false? (.-done first-part)))
                       _ (is (= {:downloads 1 :exports 0}
                                (snapshot-state-counts sql)))
                       tx-response
                       (apply-entry!
                        self
                        {:outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "after-first-stream-frame"]])})
                       _ (is (= "tx/batch/ok" (:type tx-response)))
                       _ (is (pos? (:exports (snapshot-state-counts sql)))
                             "the next live write freezes the full old basis")
                       framed-buffer
                       (read-stream-tail! reader [(.-value first-part)])
                       rows
                       (snapshot/finalize-framed-buffer framed-buffer)
                       projection (rows-projection rows block-uuid)]
                 (is (= 200 (.-status metadata-response)))
                 (is (= 200 (.-status stream-response)))
                 (is (= kvs-row-count (count rows)))
                 (is (= "before-concurrent-tx" (:title projection)))
                 (is (= "after-first-stream-frame"
                        (:block/title
                         (d/entity @(.-conn self)
                                   [:block/uuid block-uuid]))))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest v1-frozen-export-row-count-corruption-fails-closed-and-cleans-lease-test
  (testing "a partial frozen export is never streamed as a complete snapshot"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! conn "legacy-v1-partial-export")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                       "false"}
                             :sql sql :conn conn :schema-ready true}]
               (p/let [metadata-response (v1-metadata! self)
                       metadata (json-body metadata-response)
                       tx-response
                       (apply-entry!
                        self
                        {:outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "after-partial-export-freeze"]])})
                       _ (is (= "tx/batch/ok" (:type tx-response)))
                       download-id
                       (some-> (snapshot-download-generation-rows sql)
                               first
                               (aget "download_id"))
                       _ (common/sql-exec
                          sql
                          (str "delete from snapshot_kvs_exports "
                               "where download_id = ? and addr = "
                               "(select max(addr) from snapshot_kvs_exports "
                               "where download_id = ?)")
                          download-id download-id)
                       outcome
                       (-> (stream-rows! self (:url metadata))
                           (p/then (fn [value] {:value value}))
                           (p/catch (fn [error] {:error error})))]
                 (is (= 200 (.-status metadata-response)))
                 (is (nil? (:value outcome)))
                 (is (instance? js/Error (:error outcome)))
                 (is (= {:downloads 0 :exports 0}
                        (snapshot-state-counts sql))
                     "a rejected stream releases its exact reservation")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest legacy-v1-frozen-download-cancel-and-ttl-clean-up-test
  (testing "v1 internal generations survive restart and are reclaimed on cancel or expiry"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "legacy-v1-cleanup")
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql :conn conn :schema-ready true}]
               (p/let [metadata-response (v1-metadata! self)
                       metadata (json-body metadata-response)
                       sibling-metadata-response (v1-metadata! self)
                       sibling-metadata (json-body sibling-metadata-response)
                       restarted-self
                       #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql sql :conn nil :schema-ready true}
                       stream-response
                       (sync-handler/handle-http
                        restarted-self (js/Request. (:url metadata)))
                       _ (.cancel (.-body stream-response))
                       generation-after-first-cancel
                       (first (snapshot-download-generation-rows sql))
                       _ (is (= {:downloads 1}
                                (select-keys
                                 (snapshot-state-counts sql) [:downloads]))
                             "one cancelled lease must not delete its sibling export")
                       _ (is (= 1
                                (aget generation-after-first-cancel
                                      "lease_count")))
                       sibling-stream
                       (stream-rows! restarted-self (:url sibling-metadata))
                       _ (is (= {:downloads 0 :exports 0}
                                (snapshot-state-counts sql))
                             "the final sibling stream reclaims the shared generation")
                       second-metadata-response (v1-metadata! restarted-self)
                       _ (is (= 200 (.-status second-metadata-response)))
                       expired-sibling-response (v1-metadata! restarted-self)
                       _ (is (= 200 (.-status expired-sibling-response)))
                       old-row
                       (first
                        (common/get-sql-rows
                         (common/sql-exec
                          sql
                          "select download_id from snapshot_downloads order by created_at limit 1")))
                       old-id (some-> old-row (aget "download_id"))
                       _ (when old-id
                           (common/sql-exec
                            sql
                            "update snapshot_downloads set created_at = 0 where download_id = ?"
                            old-id))
                       third-metadata-response (v1-metadata! restarted-self)
                       _ (is (= 200 (.-status third-metadata-response)))
                       active-ids
                       (mapv #(aget % "download_id")
                             (common/get-sql-rows
                              (common/sql-exec
                               sql
                               "select download_id from snapshot_downloads order by created_at")))
                       active-generation-ids
                       (mapv #(aget % "download_id")
                             (snapshot-download-generation-rows sql))
                       active-queue
                       (common/read-transit
                        (storage/get-meta
                         sql :legacy-snapshot-download-queue))]
                 (is (= 200 (.-status metadata-response)))
                 (is (= 200 (.-status sibling-metadata-response)))
                 (is (= 200 (.-status stream-response)))
                 (is (= 200 (.-status (:response sibling-stream))))
                 (is (string? old-id))
                 (is (= 1 (count active-ids)))
                 (is (not-any? #(= old-id %) active-ids)
                     "TTL cleanup must remove the expired id from storage and the v1 queue")
                 (is (= active-ids active-generation-ids)
                     "TTL cleanup removes the matching generation lease atomically")
                 (is (= active-ids active-queue)
                     "TTL cleanup removes every duplicate lease queue entry")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest snapshot-download-ttl-preserves-active-v1-stream-test
  (testing "TTL cleanup reclaims an idle reservation without invalidating an active v1 reader"
    (async done
      (let [original-date-now (.-now js/Date)
            clock (atom 1000000)]
        (aset js/Date "now" (fn [] @clock))
        (-> (with-memory-sql-async
             (fn [sql]
               (storage/init-schema! sql)
               (let [conn (storage/open-conn sql)
                     {:keys [page-uuid]}
                     (seed-valid-graph! conn "active-v1-ttl")
                     _ (d/transact!
                        conn
                        (mapv
                         (fn [i]
                           {:block/uuid (random-uuid)
                            :block/title (str "active-v1-ttl-padding-" i)
                            :block/order (str "a" (+ i 1))
                            :block/parent [:block/uuid page-uuid]
                            :block/page [:block/uuid page-uuid]})
                         (range 800)))
                     self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                         "false"}
                               :sql sql :conn conn :schema-ready true}
                     expected-rows (kvs-rows sql)
                     _ (is (> (count expected-rows) 512)
                           (str "fixture must leave unread source rows after "
                                "the first frame and one queued pull: "
                                (count expected-rows)))]
                 (p/let [v1-response (v1-metadata! self)
                         v1-body (json-body v1-response)
                         v2-idle-response (v2-metadata! self)
                         v2-idle-body (json-body v2-idle-response)
                         idle-download-id
                         (.get (.-searchParams (js/URL. (:url v2-idle-body)))
                               "download-id")
                         active-download-id
                         (some (fn [row]
                                 (when (= 1 (aget row "legacy"))
                                   (aget row "download_id")))
                               (snapshot-download-generation-rows sql))
                         stream-response
                         (sync-handler/handle-http
                          self (js/Request. (:url v1-body)))
                         reader (.getReader (.-body stream-response))
                         first-part (.read reader)
                         _ (is (false? (.-done first-part)))
                         _ (is (pos? (.-byteLength (.-value first-part))))
                         _ (reset! clock 1600001)
                         cleanup-trigger-response (v2-metadata! self)
                         generation-ids-after-cleanup
                         (set (map #(aget % "download_id")
                                   (snapshot-download-generation-rows sql)))
                         stream-outcome
                         (-> (read-stream-tail!
                              reader [(.-value first-part)])
                             (p/then (fn [payload] {:payload payload}))
                             (p/catch (fn [error] {:error error})))
                         replayed-rows
                         (when-let [payload (:payload stream-outcome)]
                           (snapshot/finalize-framed-buffer payload))]
                   (is (= 200 (.-status v1-response)))
                   (is (= 200 (.-status v2-idle-response)))
                   (is (string? active-download-id))
                   (is (string? idle-download-id))
                   (is (= 200 (.-status cleanup-trigger-response)))
                   (is (not (contains? generation-ids-after-cleanup
                                       idle-download-id))
                       "an unclaimed reservation must still be reclaimed")
                   (is (nil? (:error stream-outcome))
                       "the active reader must not fail after unrelated cleanup")
                   (when-not (:error stream-outcome)
                     (is (= expected-rows replayed-rows)
                         (str "the preserved stream must remain a complete "
                              "exact snapshot")))))))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally
             (fn []
               (aset js/Date "now" original-date-now)
               (done))))))))

(deftest malformed-v1-download-queue-fails-closed-without-storage-mutation-test
  (testing "metadata and fixed-URL stream entrypoints reject a corrupt durable queue"
    (async done
      (let [cases
            [{:label :metadata
              :malformed "not-transit"
              :request! (fn [self _url] (v1-metadata! self))}
             {:label :stream
              :malformed (common/write-transit {:not "a queue"})
              :request! (fn [self url]
                          (sync-handler/handle-http
                           self (js/Request. url #js {:method "GET"})))}]]
        (-> (p/all
             (mapv
              (fn [{:keys [label malformed request!]}]
                (with-memory-sql-async
                  (fn [sql]
                    (storage/init-schema! sql)
                    (let [conn (storage/open-conn sql)
                          _ (seed-valid-graph! conn (str "bad-v1-queue-"
                                                       (name label)))
                          self
                          #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP"
                                        "false"}
                               :sql sql :conn conn :schema-ready true}]
                      (p/let [metadata-response (v1-metadata! self)
                              metadata (json-body metadata-response)
                              _ (storage/set-meta!
                                 sql :legacy-snapshot-download-queue malformed)
                              state-before
                              (snapshot-download-durable-state sql)
                              response (request! self (:url metadata))
                              body (json-body response)]
                        (is (= 200 (.-status metadata-response)) (name label))
                        (is (= 409 (.-status response)) (name label))
                        (is (= {:error "snapshot download queue inconsistent"}
                               body)
                            (name label))
                        (is (= state-before
                               (snapshot-download-durable-state sql))
                            (str (name label)
                                 " cannot repair, erase, claim, or append a corrupt queue")))))))
              cases))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest snapshot-activation-waits-for-outstanding-v1-download-lease-test
  (testing "activation retains staging until the fixed-URL v1 lease is consumed"
    (async done
      (-> (with-memory-sql-async
           (fn [source-sql]
             (storage/init-schema! source-sql)
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph! source-conn "lease-activation-new")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)]
               (with-memory-sql-async
                 (fn [sql]
                   (storage/init-schema! sql)
                   (let [conn (storage/open-conn sql)
                         _ (seed-valid-graph! conn "lease-activation-old")
                         old-rows (kvs-rows sql)
                         self
                         #js {:env #js {"DB" nil
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                              :sql sql :conn conn :schema-ready true}
                         upload-request
                         (fn [reset? rows]
                           (snapshot-upload-request
                            (str "reset=" (if reset? "true" "false")
                                 "&finished=true"
                                 "&upload-id=lease-serialized-activation"
                                 "&checksum=" source-checksum
                                 "&row-count=" (count source-rows))
                            rows))]
                     (p/with-redefs
                       [sync-handler/<set-graph-ready-for-use!
                        (fn [& _] (p/resolved true))]
                       (p/let [v1-response (v1-metadata! self)
                               v1-metadata (json-body v1-response)
                               download-state-before
                               (snapshot-download-durable-state sql)
                               blocked-response
                               (sync-handler/handle-http
                                self (upload-request true source-rows))
                               blocked-body (json-body blocked-response)
                               download-state-after
                               (snapshot-download-durable-state sql)
                               live-after-block (kvs-rows sql)
                               staged-after-block (staging-rows sql)
                               finished-after-block
                               (sync-handler/snapshot-upload-finished? self)
                               old-stream-response
                               (sync-handler/handle-http
                                self (js/Request. (:url v1-metadata)))
                               old-stream-body
                               (if (= 200 (.-status old-stream-response))
                                 (p/let [buffer
                                         (.arrayBuffer old-stream-response)]
                                   {:rows
                                    (snapshot/finalize-framed-buffer
                                     (js/Uint8Array. buffer))})
                                 (p/let [body (json-body old-stream-response)]
                                   {:body body}))
                               retry-response
                               (sync-handler/handle-http
                                self (upload-request false []))]
                         (is (= 200 (.-status v1-response)))
                         (is (= 409 (.-status blocked-response)))
                         (is (= {:error "snapshot download in progress"}
                                blocked-body))
                         (is (= download-state-before download-state-after)
                             "blocked activation preserves the old basis, queue, and lease")
                         (is (= old-rows live-after-block)
                             "the new snapshot cannot replace live before the lease drains")
                         (is (= source-rows staged-after-block)
                             "validated upload staging remains available for retry")
                         (is (not finished-after-block))
                         (is (= 200 (.-status old-stream-response)))
                         (is (= old-rows (:rows old-stream-body))
                             "the already-issued fixed URL still replays its original basis")
                         (is (= 200 (.-status retry-response)))
                         (is (= source-rows (kvs-rows sql)))
                         (is (sync-handler/snapshot-upload-finished? self))
                         (is (= {:downloads 0 :exports 0}
                                (snapshot-state-counts sql)))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest selfhost-one-through-five-http-pull-and-ws-wire-shapes-remain-compatible-test
  (doseq [revision ["2.0.1-selfhost.1"
                    "2.0.1-selfhost.2"
                    "2.0.1-selfhost.3"
                    "2.0.1-selfhost.4"
                    "2.0.1-selfhost.5"]]
    (testing revision
      (with-memory-sql
        (fn [sql]
          (storage/init-schema! sql)
          (let [conn (storage/open-conn sql)
                {:keys [block-uuid]} (seed-valid-graph! conn revision)
                self #js {:sql sql
                          :conn conn
                          :graph-id "synthetic-graph"
                          :schema-ready true}
                start-t (storage/get-t sql)
                http-response
                (with-redefs [ws/broadcast! (fn [& _] nil)]
                  (sync-handler/handle-tx-batch!
                   self nil
                   [{:tx (protocol/tx->transit
                          [[:db/add [:block/uuid block-uuid]
                            :block/title "old-http-wire"]])
                     :outliner-op :save-block}]
                   start-t
                   {:client-revision revision}))
                pull (sync-handler/pull-response self start-t)
                socket #js {:readyState 1}
                sent (atom nil)
                ws-message
                {:type "tx/batch"
                 :client-revision revision
                 :t-before (:t http-response)
                 :txs [{:tx (protocol/tx->transit
                             [[:db/add [:block/uuid block-uuid]
                               :block/title "old-ws-wire"]])
                        :outliner-op :save-block}]}]
            (is (= "tx/batch/ok" (:type http-response)) revision)
            (is (= "pull/ok" (:type pull)) revision)
            (is (= (:t http-response) (:t pull)) revision)
            (is (= 1 (count (:txs pull))) revision)
            (is (string? (:checksum pull)) revision)
            (with-redefs [presence/get-user (fn [& _]
                                              {:user-id "synthetic-user"
                                               :username "synthetic"})
                          ws/broadcast! (fn [& _] nil)
                          ws/send! (fn [_target message]
                                     (reset! sent message))]
              (ws-handler/handle-ws-message!
               self socket (protocol/encode-message ws-message)))
            (is (= "tx/batch/ok" (:type @sent)) revision)
            (is (= "old-ws-wire"
                   (:block/title
                    (d/entity @conn [:block/uuid block-uuid])))
                revision)))))))

(deftest unknown-or-malicious-system-kv-wire-is-fail-closed-and-atomic-test
  (doseq [[label tx-data forbidden-ident]
          [["unregistered logseq.kv entity"
            [{:db/ident :logseq.kv/not-registered
              :kv/value "untrusted"}]
            :logseq.kv/not-registered]
           ["registered ident rewrite"
            [[:db/add protected-system-kv
              :db/ident :untrusted.remote/forged-system-kv]]
            :untrusted.remote/forged-system-kv]
           ["incomplete registered KV op"
            [[:db/add protected-system-kv :kv/value]]
            :untrusted.remote/incomplete-system-kv]]]
    (testing label
      (with-memory-sql
        (fn [sql]
          (storage/init-schema! sql)
          (let [conn (storage/open-conn sql)
                {:keys [created-at]} (seed-valid-graph! conn label)
                self #js {:sql sql :conn conn :schema-ready true}
                t-before (storage/get-t sql)
                facts-before (db-facts @conn)
                response
                (apply-entry!
                 self
                 {:tx-id (random-uuid)
                  :outliner-op :save-block
                  :tx (protocol/tx->transit tx-data)}
                 t-before)]
            (is (= "tx/reject" (:type response)) label)
            (is (= t-before (storage/get-t sql)) label)
            (is (= facts-before (db-facts @conn)) label)
            (is (nil? (d/entity @conn forbidden-ident)) label)
            (is (= created-at
                   (:kv/value (d/entity @conn protected-system-kv)))
                label)))))))

(deftest registered-system-kv-update-is-idempotent-across-do-restart-test
  (with-memory-sql
    (fn [sql]
      (storage/init-schema! sql)
      (let [conn (storage/open-conn sql)
            {:keys [created-at]} (seed-valid-graph! conn "do-restart")
            first-self #js {:sql sql :conn conn :schema-ready true}
            t-before (storage/get-t sql)
            next-value (+ created-at 1000)
            tx-id (random-uuid)
            entry {:tx-id tx-id
                   :outliner-op :save-block
                   :tx (protocol/tx->transit
                        [[:db/retract protected-system-kv
                          :kv/value created-at]
                         [:db/add protected-system-kv
                          :kv/value next-value]])}
            first-response (apply-entry! first-self entry t-before)
            restarted-self #js {:sql sql :conn nil :schema-ready true}
            repeated-response
            (apply-entry! restarted-self entry (:t first-response))
            persisted-conn (.-conn restarted-self)]
        (is (= "tx/batch/ok" (:type first-response)))
        (is (= "tx/batch/ok" (:type repeated-response)))
        (is (= (inc t-before) (:t first-response)))
        (is (= (:t first-response) (:t repeated-response))
            "a repeated accepted tx-id must not advance the durable cursor")
        (is (= 1 (count (storage/fetch-tx-since sql t-before))))
        (is (= next-value
               (:kv/value
                (d/entity @persisted-conn protected-system-kv))))))))

(deftest legacy-v1-interrupted-reset-can-be-replaced-without-admin-reset-test
  (testing "an abandoned v1 multipart start is isolated and a new reset takes over"
    (async done
      (-> (with-memory-sql-triplet-async
           (fn [old-source-sql new-source-sql destination-sql]
             (doseq [sql [old-source-sql new-source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [old-source-conn (storage/open-conn old-source-sql)
                   _ (seed-valid-graph! old-source-conn "old-v1-session")
                   old-rows (kvs-rows old-source-sql)
                   old-checksum (sync-checksum/recompute-checksum @old-source-conn)
                   old-split (max 1 (quot (count old-rows) 2))
                   old-first (subvec old-rows 0 old-split)
                   old-late (subvec old-rows old-split)
                   new-source-conn (storage/open-conn new-source-sql)
                   _ (seed-valid-graph! new-source-conn "new-v1-session")
                   new-rows (kvs-rows new-source-sql)
                   new-checksum (sync-checksum/recompute-checksum @new-source-conn)
                   new-server-checksum
                   (sync-checksum/recompute-server-checksum @new-source-conn)
                   {:keys [conn]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "live-interrupted-v1")
                   live-before (live-durable-state destination-sql)
                   ready? (atom true)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   first-request
                   (legacy-snapshot-upload-request
                    "reset=true&finished=false" old-first)
                   first-url (js/URL. (.-url first-request))
                   retry-request
                   (legacy-snapshot-upload-request
                    (str "reset=true&finished=true&checksum=" new-checksum)
                    new-rows)]
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id value]
                    (reset! ready? value)
                    (p/resolved true))]
                 (p/let [first-response
                         (handle-legacy-upload! self first-request)
                         _ (is (= 200 (.-status first-response))
                               "the historical first-chunk response shape remains 200")
                         _ (is (nil? (.get (.-searchParams first-url)
                                           "upload-id"))
                               "legacy .1-.5 upload does not gain a v2 upload-id")
                         _ (is (nil? (.get (.-searchParams first-url)
                                           "row-count"))
                               "legacy .1-.5 upload does not gain a v2 row-count")
                         _ (is (= live-before
                                  (live-durable-state destination-sql))
                               "an unfinished reset must not replace live durable state")
                         _ (is (= old-first (staging-rows destination-sql))
                               "only isolated staging may contain the unfinished chunk")
                         retry-response
                         (handle-legacy-upload! self retry-request)
                         _ (is (= 200 (.-status retry-response))
                               "reset=true must take over without admin reset")
                         _ (is (= new-rows (kvs-rows destination-sql)))
                         _ (is (= 0 (storage/get-t destination-sql)))
                         _ (is (= new-checksum
                                  (storage/get-checksum destination-sql)))
                         _ (is (= new-server-checksum
                                  (storage/get-server-checksum
                                   destination-sql)))
                         _ (is (= 0 (storage/get-server-checksum-t
                                     destination-sql)))
                         _ (is (empty? (storage/fetch-tx-since
                                        destination-sql 0)))
                         _ (is (empty? (table-rows
                                        destination-sql
                                        "applied_client_txs" "identity")))
                         _ (is (empty? (staging-rows destination-sql)))
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         _ (is (true? @ready?))
                         committed-state (live-durable-state destination-sql)
                         late-response
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           (str "reset=false&finished=true&checksum=" old-checksum)
                           old-late))
                         late-body (json-body late-response)
                         _ (is (= 409 (.-status late-response)))
                         _ (is (= {:error
                                   "legacy snapshot upload session missing"}
                                  late-body))
                         _ (is (= committed-state
                                  (live-durable-state destination-sql))
                               "a late chunk from the replaced session is inert")
                         race-responses
                         (p/all
                          [(handle-legacy-upload!
                            self
                            (legacy-snapshot-upload-request
                             (str "reset=true&finished=true&checksum="
                                  new-checksum)
                             new-rows))
                           (handle-legacy-upload!
                            self
                            (legacy-snapshot-upload-request
                             (str "reset=true&finished=true&checksum="
                                  new-checksum)
                             new-rows))])
                         race-statuses (mapv #(.-status %) race-responses)
                         _ (is (every? #{200 409} race-statuses)
                               "a simultaneous duplicate reset is either serialized or accepted")
                         _ (is (some #{200} race-statuses)
                               "at least one competing complete reset commits")
                         final-retry
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           (str "reset=true&finished=true&checksum=" new-checksum)
                           new-rows))]
                   (is (= 200 (.-status final-retry))
                       "cleanup after competing resets is idempotent and retryable")
                   (is (= new-rows (kvs-rows destination-sql)))
                   (is (empty? (staging-rows destination-sql)))
                   (is (sync-handler/snapshot-upload-finished? self)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest legacy-v1-oversize-failure-restores-gates-and-live-state-test
  (testing "SQLITE_TOOBIG is retryable and does not require an admin reset"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (storage/init-schema! source-sql)
             (storage/init-schema! destination-sql)
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph! source-conn "oversize-retry")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   {:keys [block-uuid conn]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "live-before-oversize")
                   live-before (live-durable-state destination-sql)
                   ready? (atom true)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   failed-request
                   (legacy-snapshot-upload-request
                    (str "reset=true&finished=true&checksum=" source-checksum)
                    source-rows)]
               (p/let [failed-response
                       (p/with-redefs
                         [sync-handler/import-snapshot-stream!
                          (fn
                            ([_self _stream _reset?]
                             (p/rejected
                              (js/Error. "SQLITE_TOOBIG: synthetic")))
                            ([_self _stream _reset? _import-f]
                             (p/rejected
                              (js/Error. "SQLITE_TOOBIG: synthetic"))))
                          sync-handler/<set-graph-ready-for-use!
                          (fn [_self _graph-id value]
                            (reset! ready? value)
                            (p/resolved true))]
                         (sync-handler/handle-http self failed-request))
                       _ (is (= 413 (.-status failed-response)))
                       _ (is (= live-before
                                (live-durable-state destination-sql))
                             "413 must preserve t/checksums/log/idempotency/live rows")
                       _ (is (empty? (staging-rows destination-sql)))
                       _ (is (sync-handler/snapshot-upload-finished? self))
                       _ (is (true? @ready?))
                       ordinary-response
                       (apply-entry!
                        self
                        {:tx-id (random-uuid)
                         :outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "ordinary-after-413"]])})
                       _ (is (= "tx/batch/ok" (:type ordinary-response))
                             "ordinary sync gate must recover after 413")
                       retry-response
                       (p/with-redefs
                         [sync-handler/<set-graph-ready-for-use!
                          (fn [_self _graph-id value]
                            (reset! ready? value)
                            (p/resolved true))]
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           (str "reset=true&finished=true&checksum="
                                source-checksum)
                           source-rows)))]
                 (is (= 200 (.-status retry-response)))
                 (is (= source-rows (kvs-rows destination-sql)))
                 (is (sync-handler/snapshot-upload-finished? self))
                 (is (true? @ready?))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest legacy-v1-handler-failure-restores-gates-and-is-retryable-test
  (testing "an unexpected handler failure returns 500 without poisoning the graph"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (storage/init-schema! source-sql)
             (storage/init-schema! destination-sql)
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph! source-conn "handler-retry")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   {:keys [block-uuid conn]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "live-before-handler-failure")
                   live-before (live-durable-state destination-sql)
                   ready? (atom true)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   failed-request
                   (malformed-legacy-snapshot-upload-request
                    (str "reset=true&finished=true&checksum=" source-checksum))]
               (p/let [failed-result
                       (p/with-redefs
                         [sync-handler/<set-graph-ready-for-use!
                          (fn [_self _graph-id value]
                            (reset! ready? value)
                            (p/resolved true))]
                         (observe-handler-result
                          (handle-legacy-upload! self failed-request)))
                       _ (is (handler-failure-observed? failed-result))
                       _ (is (= live-before
                                (live-durable-state destination-sql)))
                       _ (is (empty? (staging-rows destination-sql)))
                       _ (is (sync-handler/snapshot-upload-finished? self))
                       _ (is (true? @ready?))
                       ordinary-response
                       (apply-entry!
                        self
                        {:tx-id (random-uuid)
                         :outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "ordinary-after-500"]])})
                       _ (is (= "tx/batch/ok" (:type ordinary-response)))
                       retry-response
                       (p/with-redefs
                         [sync-handler/<set-graph-ready-for-use!
                          (fn [_self _graph-id value]
                            (reset! ready? value)
                            (p/resolved true))]
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           (str "reset=true&finished=true&checksum="
                                source-checksum)
                           source-rows)))]
                 (is (= 200 (.-status retry-response)))
                 (is (= source-rows (kvs-rows destination-sql)))
                 (is (sync-handler/snapshot-upload-finished? self))
                 (is (true? @ready?))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest legacy-v1-late-chunk-cannot-complete-a-new-session-test
  (testing "a delayed final chunk from a failed session cannot activate mixed rows"
    (async done
      (-> (with-memory-sql-triplet-async
           (fn [old-source-sql new-source-sql destination-sql]
             (doseq [sql [old-source-sql new-source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [old-conn (storage/open-conn old-source-sql)
                   _ (seed-valid-graph! old-conn "late-old")
                   old-rows (kvs-rows old-source-sql)
                   old-checksum (sync-checksum/recompute-checksum @old-conn)
                   old-split (max 1 (quot (count old-rows) 2))
                   old-first (subvec old-rows 0 old-split)
                   old-final (subvec old-rows old-split)
                   new-conn (storage/open-conn new-source-sql)
                   _ (seed-valid-graph! new-conn "late-new")
                   new-rows (kvs-rows new-source-sql)
                   new-checksum (sync-checksum/recompute-checksum @new-conn)
                   new-split (max 1 (quot (count new-rows) 2))
                   new-first (subvec new-rows 0 new-split)
                   {:keys [conn]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "live-before-late-chunk")
                   live-before (live-durable-state destination-sql)
                   ready? (atom true)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}]
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id value]
                    (reset! ready? value)
                    (p/resolved true))]
                 (p/let [old-start
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           "reset=true&finished=false" old-first))
                         _ (is (= 200 (.-status old-start)))
                         failed-old-final
                         (observe-handler-result
                          (handle-legacy-upload!
                           self
                           (malformed-legacy-snapshot-upload-request
                            (str "reset=false&finished=true&checksum="
                                 old-checksum))))
                         _ (is (handler-failure-observed?
                                failed-old-final))
                         _ (is (= live-before
                                  (live-durable-state destination-sql)))
                         new-start
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           "reset=true&finished=false" new-first))
                         _ (is (= 200 (.-status new-start)))
                         late-old-final
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           (str "reset=false&finished=true&checksum=" old-checksum)
                           old-final))
                         _late-body (json-body late-old-final)
                         _ (is (not (<= 200 (.-status late-old-final) 299))
                               "mixed old/new staging must fail integrity")
                         _ (is (= live-before
                                  (live-durable-state destination-sql))
                               "late bytes never contaminate live state")
                         _ (is (empty? (staging-rows destination-sql))
                               "failed mixed staging is cleaned")
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         retry
                         (handle-legacy-upload!
                          self
                          (legacy-snapshot-upload-request
                           (str "reset=true&finished=true&checksum=" new-checksum)
                           new-rows))]
                   (is (= 200 (.-status retry)))
                   (is (= new-rows (kvs-rows destination-sql)))
                   (is (empty? (staging-rows destination-sql)))
                   (is (sync-handler/snapshot-upload-finished? self))
                   (is (true? @ready?)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest staged-v2-generic-handler-failure-clears-every-gate-and-retries-test
  (testing "an unexpected staged parser failure cannot strand ready=false or an upload lease"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph! source-conn "staged-generic-retry")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   {:keys [conn block-uuid]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "live-before-staged-generic-failure")
                   live-before (live-durable-state destination-sql)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   failed-request
                   (malformed-staged-snapshot-upload-request
                    "reset=true&finished=false&upload-id=failed-generation")]
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [& _] (p/resolved true))]
                 (p/let [failed (observe-handler-result
                                 (sync-handler/handle-http self failed-request))
                       _ (is (handler-failure-observed? failed))
                       _ (is (= live-before
                                (live-durable-state destination-sql)))
                       _ (is (empty? (staging-rows destination-sql)))
                       _ (is (sync-handler/snapshot-upload-finished? self))
                       _ (doseq [key [:snapshot-upload-id
                                      :snapshot-upload-status
                                      :snapshot-upload-started-at]]
                           (is (nil? (storage/get-meta destination-sql key))
                               (str key " must not retain a stale lease")))
                       ordinary-response
                       (apply-entry!
                        self
                        {:outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title "ordinary-after-v2-abort"]])})
                       _ (is (= "tx/batch/ok" (:type ordinary-response)))
                       _ (is (= "ordinary-after-v2-abort"
                                (:block/title
                                 (d/entity @(.-conn self)
                                           [:block/uuid block-uuid]))))
                       retry-response
                       (sync-handler/handle-http
                        self
                        (snapshot-upload-request
                         (str "reset=true&finished=true"
                              "&upload-id=retry-generation"
                              "&row-count=" (count source-rows)
                              "&checksum=" source-checksum)
                         source-rows))]
                   (is (= 200 (.-status retry-response)))
                   (is (= source-rows (kvs-rows destination-sql)))
                   (is (sync-handler/snapshot-upload-finished? self)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest attested-live-snapshot-recovers-a-failed-d1-ready-update-test
  (testing "post-commit D1 failure is retried only from an attested live root; an empty graph stays unready"
    (async done
      (-> (with-memory-sql-triplet-async
           (fn [source-sql destination-sql empty-sql]
             (doseq [sql [source-sql destination-sql empty-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph! source-conn "ready-recovery-source")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   destination-conn (storage/open-conn destination-sql)
                   _ (seed-valid-graph!
                      destination-conn "ready-recovery-before")
                   ready? (atom false)
                   set-attempts (atom 0)
                   self #js {:env #js {"DB" :synthetic-d1
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql destination-sql
                             :conn destination-conn
                             :schema-ready true}
                   empty-self
                   #js {:env #js {"DB" :synthetic-d1
                                  "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql empty-sql
                        :conn (storage/open-conn empty-sql)
                        :schema-ready true}
                   upload-request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=ready-recovery"
                         "&row-count=" (count source-rows)
                         "&checksum=" source-checksum)
                    source-rows)]
               (p/with-redefs
                 [index/<graph-ready-for-use?
                  (fn [& _] (p/resolved @ready?))
                  index/<graph-created-at
                  (fn [& _] (p/resolved 1760000000000))
                  index/<graph-ready-for-use-set!
                  (fn [& _]
                    (let [attempt (swap! set-attempts inc)]
                      (if (= 1 attempt)
                        (p/rejected (js/Error. "synthetic D1 outage"))
                        (do
                          (reset! ready? true)
                          (p/resolved #js {:success true
                                          :meta #js {:changes 1}})))))]
                 (p/let [failed-upload
                         (observe-handler-result
                          (sync-handler/handle-http self upload-request))
                         _ (is (handler-failure-observed? failed-upload))
                         _ (is (= source-rows (kvs-rows destination-sql)))
                         _ (is (storage/checksum-metadata-verified?
                                destination-sql 0)
                               "the committed checksum descriptor survives D1 failure")
                         _ (is (storage/snapshot-integrity-attested?
                                destination-sql))
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         healed-metadata (v2-metadata! self)
                         _ (is (= 200 (.-status healed-metadata)))
                         _ (is (true? @ready?))
                         _ (reset! ready? false)
                         empty-metadata (v2-metadata! empty-self)]
                   (is (= 2 @set-attempts)
                       "the next request retries the failed D1 projection")
                   (is (= 409 (.-status empty-metadata))
                       "brand-new unvalidated ready=false storage is not self-healed")
                   (is (= 2 @set-attempts)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest committed-v2-upload-retries-only-d1-ready-projection-test
  (testing "a post-commit D1 failure preserves the verified activation receipt across restart"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! source-conn "d1-ready-receipt-source")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   source-server-checksum
                   (sync-checksum/recompute-server-checksum @source-conn)
                   old-conn (storage/open-conn destination-sql)
                   _ (seed-valid-graph!
                      old-conn "d1-ready-receipt-old-live")
                   original-exec (.-exec destination-sql)
                   live-copy-count (atom 0)
                   attestation-write-count (atom 0)
                   ready-attempts (atom 0)
                   ready? (atom false)
                   upload-id "d1-ready-receipt"
                   upload-request
                   (fn []
                     (snapshot-upload-request
                      (str "reset=true&finished=true"
                           "&upload-id=" upload-id
                           "&checksum=" source-checksum
                           "&row-count=" (count source-rows))
                      source-rows))
                   self #js {:env #js {"DB" :synthetic-d1
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql destination-sql
                             :conn old-conn
                             :schema-ready true}
                   activation-contract-state
                   (fn []
                     {:session
                      (#'sync-handler/snapshot-upload-session
                       destination-sql)
                      :attestation
                      (storage/snapshot-integrity-attestation destination-sql)
                      :checksum (storage/get-checksum destination-sql)
                      :server-checksum
                      (storage/get-server-checksum destination-sql)
                      :server-checksum-t
                      (storage/get-server-checksum-t destination-sql)
                      :kvs-generation
                      (storage/live-kvs-mutation-generation destination-sql)
                      :checksum-generation
                      (storage/snapshot-checksum-mutation-generation
                       destination-sql)})]
               (is (= 5 (count source-rows)))
               (is (string? source-server-checksum))
               (is (nil?
                    (.get (.-searchParams
                           (js/URL. (.-url (upload-request))))
                          "server-checksum-v2"))
                   "the v2 upload declares only the legacy checksum")
               (aset destination-sql "exec"
                     (fn [sql-str & args]
                       (let [normalized (string/lower-case sql-str)]
                         (when (string/includes?
                                normalized
                                (str "insert into kvs (addr, content, addresses) "
                                     "select addr, content, addresses from "
                                     "snapshot_kvs_staging"))
                           (swap! live-copy-count inc))
                         (when (string/includes?
                                normalized
                                "insert into integrity_attestations")
                           (swap! attestation-write-count inc))
                         (apply original-exec sql-str args))))
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id value]
                    (let [attempt (swap! ready-attempts inc)]
                      (if (= 1 attempt)
                        (p/rejected
                         (js/Error. "synthetic D1 ready projection failure"))
                        (do
                          (reset! ready? value)
                          (p/resolved #js {:success true
                                           :meta #js {:changes 1}})))))]
                 (p/let [first-response
                         (sync-handler/handle-http self (upload-request))
                         session-after-failure
                         (#'sync-handler/snapshot-upload-session
                          destination-sql)
                         activation-after-failure
                         (activation-contract-state)
                         _ (is (= 500 (.-status first-response)))
                         _ (is (= {:upload-id upload-id
                                   :status "committed"}
                                  session-after-failure)
                               "the verified activation receipt survives D1 failure")
                         _ (is (= source-rows (kvs-rows destination-sql)))
                         _ (is (storage/snapshot-integrity-attested?
                                destination-sql))
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         _ (is (= 1 @live-copy-count))
                         _ (is (= 1 @attestation-write-count))
                         ^js restarted-self
                         #js {:env #js {"DB" :synthetic-d1
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                              :sql destination-sql
                              :conn nil
                              :schema-ready true}
                         retry-response
                         (sync-handler/handle-http
                          restarted-self (upload-request))
                         activation-after-retry
                         (activation-contract-state)
                         live-copy-count-after-retry @live-copy-count
                         attestation-write-count-after-retry
                         @attestation-write-count
                         tx-response
                         (apply-entry!
                          restarted-self
                          {:outliner-op :save-block
                           :tx (protocol/tx->transit
                                [[:db/add [:block/uuid block-uuid]
                                  :block/title "after-ready-retry"]])}
                          0)]
                   (is (= 200 (.-status retry-response)))
                   (is (storage/checksum-metadata-verified?
                        destination-sql 1)
                       "the following ordinary tx advances the verified descriptor")
                   (is (true? @ready?))
                   (is (= 2 @ready-attempts))
                   (is (= activation-after-failure activation-after-retry)
                       "D1 retry cannot rewrite the committed receipt, checksums, generations, or attestation")
                   (is (= 1 live-copy-count-after-retry)
                       "same-id retry must not copy the already committed live DB")
                   (is (= 1 attestation-write-count-after-retry)
                       "same-id retry must not reseal the already verified root")
                   (is (= "tx/batch/ok" (:type tx-response)))
                   (is (= 1 (:t tx-response)))
                   (is (= "after-ready-retry"
                          (:block/title
                           (d/entity @(.-conn restarted-self)
                                     [:block/uuid block-uuid])))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest healthy-legacy-write-bootstraps-integrity-without-requiring-tx-id-test
  (testing "a .1-.4 style ordinary write seals a healthy unmarked graph and still accepts nil tx-id"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              {:keys [block-uuid]} (seed-valid-graph! conn "legacy-first-write")
              self #js {:sql sql :conn conn :schema-ready true}
              response
              (apply-entry!
               self
               {:outliner-op :save-block
                :tx (protocol/tx->transit
                     [[:db/add [:block/uuid block-uuid]
                       :block/title "legacy-first-write-ok"]])})]
          (is (= "tx/batch/ok" (:type response)))
          (is (storage/snapshot-integrity-attested? sql)
              "the successful first write must advance a bootstrapped generation")
          (is (nil? (:tx_id (last (table-rows sql "tx_log" "t"))))))))))

(deftest healthy-legacy-bootstrap-does-not-open-a-second-datascript-connection-test
  (testing "first validation of a healthy legacy graph uses the sole live connection"
    (with-memory-sql
      (fn [sql]
        (storage/init-schema! sql)
        (let [conn (storage/open-conn sql)
              {:keys [block-uuid]} (seed-valid-graph! conn "single-conn-bootstrap")
              self #js {:sql sql :conn conn :schema-ready true}
              shadow-opens (atom 0)
              original-open-snapshot-conn storage/open-snapshot-conn
              response
              (with-redefs
                [storage/open-snapshot-conn
                 (fn [& args]
                   (swap! shadow-opens inc)
                   (apply original-open-snapshot-conn args))]
                (apply-entry!
                 self
                 {:outliner-op :save-block
                  :tx (protocol/tx->transit
                       [[:db/add [:block/uuid block-uuid]
                         :block/title "single-live-conn-ok"]])}))]
          (is (= "tx/batch/ok" (:type response)))
          (is (zero? @shadow-opens)
              "healthy bootstrap must not materialize a second DataScript DB")
          (is (storage/snapshot-integrity-attested? sql)))))))

(deftest repair-releases-live-conn-before-opening-staging-shadow-test
  (testing "repair never keeps the live and staging DataScript roots resident together"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (let [{:keys [conn]} (seed-index-torn-gallery-snapshot! sql)
                   _ (persist-current-checksums! sql conn)
                   self #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql
                             :conn conn
                             :schema-ready true}
                   live-presence-at-shadow-open (atom [])
                   original-open-snapshot-conn storage/open-snapshot-conn]
               (p/with-redefs
                 [storage/open-snapshot-conn
                  (fn [& args]
                    (swap! live-presence-at-shadow-open
                           conj (some? (.-conn self)))
                    (apply original-open-snapshot-conn args))]
                 (p/let [response (v2-metadata! self)]
                   (is (= 200 (.-status response)))
                   (is (seq @live-presence-at-shadow-open)
                       "repair must validate a staged root")
                   (is (every? false? @live-presence-at-shadow-open)
                       "the live connection must be released before staging replay")
                   (is (some? (.-conn self))
                       "the final committed live root is reopened"))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest concurrent-bootstrap-is-single-flight-and-released-after-completion-test
  (testing "two simultaneous first downloads share one authoritative bootstrap"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (d/transact!
                      conn
                      [{:block/uuid (random-uuid)
                        :block/name "missing-created-at-before-bootstrap"
                        :block/title "Missing created at before bootstrap"}])
                   graph-created-at-calls (atom 0)
                   self #js {:env #js {"DB" :synthetic-d1
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql :conn conn :schema-ready true}]
               (p/with-redefs
                 [index/<graph-ready-for-use?
                  (fn [& _] (p/resolved true))
                  index/<graph-created-at
                  (fn [& _]
                    (swap! graph-created-at-calls inc)
                    (p/let [_ (p/delay 10)]
                      1760000000000))]
                 (p/let [responses (p/all [(v2-metadata! self)
                                           (v2-metadata! self)])]
                   (is (= [200 200] (mapv #(.-status %) responses)))
                   (is (= 1 @graph-created-at-calls)
                       "only one full bootstrap may cross the D1 await")
                   (is (storage/snapshot-integrity-attested? sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest failed-bootstrap-flight-is-released-for-a-clean-retry-test
  (testing "a transient authoritative lookup failure cannot poison later healthy requests"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (d/transact!
                      conn
                      [{:block/uuid (random-uuid)
                        :block/name "bootstrap-retry"
                        :block/title "Bootstrap retry"}])
                   calls (atom 0)
                   self #js {:env #js {"DB" :synthetic-d1
                                       "DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                             :sql sql :conn conn :schema-ready true}]
               (p/with-redefs
                 [index/<graph-ready-for-use?
                  (fn [& _] (p/resolved true))
                  index/<graph-created-at
                  (fn [& _]
                    (if (= 1 (swap! calls inc))
                      (p/rejected (js/Error. "transient D1 failure"))
                      (p/resolved 1760000000000)))]
                 (p/let [failed (observe-handler-result (v2-metadata! self))
                         _ (is (handler-failure-observed? failed))
                         retried (v2-metadata! self)]
                   (is (= 200 (.-status retried)))
                   (is (= 2 @calls))
                   (is (nil? (aget self "snapshotIntegrityBootstrap")))
                   (is (storage/snapshot-integrity-attested? sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest hibernated-websocket-first-message-bootstraps-before-hello-test
  (testing "a socket restored without handle-ws cannot read or write an unvalidated live root"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "hibernated-first-message")
                   self #js {:sql sql
                             :conn conn
                             :graph-id "synthetic-graph"
                             :schema-ready true}
                   socket #js {:readyState 1}
                   sent (atom [])]
               (p/with-redefs
                 [presence/get-user
                  (fn [& _] {:user-id "synthetic-user"
                             :username "synthetic"})
                  presence/get-graph-id
                  (fn [& _] "synthetic-graph")
                  ws/send!
                  (fn [_ message] (swap! sent conj message))]
                 (p/let [_ (await-handler-result
                            (ws-handler/handle-ws-message!
                             self socket
                             (protocol/encode-message
                              {:type "hello" :client "synthetic"})))]
                   (is (storage/snapshot-integrity-attested? sql))
                   (is (= "hello" (:type (last @sent)))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest hibernated-websocket-cold-no-tx-id-write-bootstraps-and-pulls-test
  (testing "a cold restored .1-style socket may write first and advance exactly one cursor"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [seed-conn (storage/open-conn sql)
                   {:keys [block-uuid]} (seed-valid-graph!
                                         seed-conn "hibernated-old-write")
                   t-before (storage/get-t sql)
                   self #js {:env #js {"DB" :synthetic-d1}
                             :sql sql
                             :conn nil
                             :schema-ready false}
                   attachment* (atom nil)
                   socket #js {:readyState 1
                               :serializeAttachment
                               (fn [attachment]
                                 (reset! attachment* attachment))
                               :deserializeAttachment
                               (fn [] @attachment*)}
                   user {:user-id "synthetic-user"
                         :username "synthetic"}
                   sent (atom [])
                   tx-message
                   {:type "tx/batch"
                    :t-before t-before
                    :txs [{:outliner-op :save-block
                           :tx (protocol/tx->transit
                                [[:db/add [:block/uuid block-uuid]
                                  :block/title "hibernated-old-write-ok"]])}]}]
               (presence/set-connection-context!
                socket user "synthetic-graph")
               (swap! (presence/presence* self) assoc socket user)
               (p/with-redefs
                 [index/<user-has-access-to-graph?
                  (fn [_db graph-id user-id]
                    (p/resolved
                     (and (= "synthetic-graph" graph-id)
                          (= "synthetic-user" user-id))))
                  ws/broadcast! (fn [& _] nil)
                  ws/send! (fn [_ message] (swap! sent conj message))]
                 (p/let [_ (await-handler-result
                            (ws-handler/handle-ws-message!
                             self socket
                             (protocol/encode-message tx-message)))
                         tx-response (last @sent)
                         _ (await-handler-result
                            (ws-handler/handle-ws-message!
                             self socket
                             (protocol/encode-message
                              {:type "pull" :since t-before})))
                         pull-response (last @sent)]
                   (is (= "tx/batch/ok" (:type tx-response)))
                   (is (= (inc t-before) (:t tx-response)))
                   (is (= "pull/ok" (:type pull-response)))
                   (is (= (inc t-before) (:t pull-response)))
                   (is (= 1 (count (:txs pull-response))))
                   (is (= "hibernated-old-write-ok"
                          (:block/title
                           (d/entity @(.-conn self)
                                     [:block/uuid block-uuid]))))
                   (is (nil? (:tx_id
                              (last (table-rows sql "tx_log" "t")))))
                   (is (storage/snapshot-integrity-attested? sql)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest hibernated-websocket-with-authorized-restoration-returns-awaitable-bootstrap-test
  (testing "an authorized restored socket without attachment settles its cold .1 write and pull"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [seed-conn (storage/open-conn sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! seed-conn "hibernated-authorized-stub")
                   t-before (storage/get-t sql)
                   self #js {:env #js {"DB" :synthetic-d1}
                             :sql sql
                             :conn nil
                             :schema-ready false}
                   sent (atom [])
                   socket #js {:readyState 1
                               :send (fn [raw]
                                       (swap! sent conj
                                              (protocol/parse-message raw)))}
                   open-count (atom 0)
                   original-open-conn storage/open-conn
                   tx-message
                   {:type "tx/batch"
                    :t-before t-before
                    :txs [{:outliner-op :save-block
                           :tx (protocol/tx->transit
                                [[:db/add [:block/uuid block-uuid]
                                  :block/title
                                  "hibernated-authorized-stub-ok"]])}]}]
               (p/with-redefs
                 [presence/get-user
                  (fn [& _] {:user-id "synthetic-user"
                             :username "synthetic"})
                  presence/get-graph-id (fn [& _] "synthetic-graph")
                  index/<user-has-access-to-graph?
                  (fn [_db graph-id user-id]
                    (p/resolved
                     (and (= "synthetic-graph" graph-id)
                          (= "synthetic-user" user-id))))
                  storage/open-conn
                  (fn [& args]
                    (swap! open-count inc)
                    (apply original-open-conn args))
                  ws/broadcast! (fn [& _] nil)]
                 (let [tx-result
                       (ws-handler/handle-ws-message!
                        self socket (protocol/encode-message tx-message))]
                   (is (fn? (some-> tx-result .-then))
                       "the Worker event must retain an awaitable lifecycle")
                   (p/let [_ (await-handler-result tx-result)
                           tx-response (last @sent)
                           pull-result
                           (ws-handler/handle-ws-message!
                            self socket
                            (protocol/encode-message
                             {:type "pull" :since t-before}))
                           _ (await-handler-result pull-result)
                           pull-response (last @sent)]
                     (is (= 1 @open-count)
                         "cold bootstrap opens exactly one live DataScript connection")
                     (is (true? (.-schema-ready self)))
                     (is (some? (.-conn self)))
                     (is (= "tx/batch/ok" (:type tx-response)))
                     (is (= (inc t-before) (:t tx-response)))
                     (is (= "pull/ok" (:type pull-response)))
                     (is (= (inc t-before) (:t pull-response)))
                     (is (= 1 (count (:txs pull-response))))
                     (is (= "hibernated-authorized-stub-ok"
                            (:block/title
                             (d/entity @(.-conn self)
                                       [:block/uuid block-uuid]))))
                     (is (nil? (:tx_id
                                (last (table-rows sql "tx_log" "t")))))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest hibernated-websocket-without-restored-authorization-closes-1008-test
  (testing "a production socket without attachment or presence is never authorized from message data"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "hibernated-no-auth")
                   self #js {:env #js {"DB" :synthetic-d1}
                             :sql sql
                             :conn nil
                             :graph-id "synthetic-graph"
                             :schema-ready false}
                   closes (atom [])
                   socket #js {:readyState 1
                               :close (fn [code reason]
                                        (swap! closes conj [code reason]))}
                   sent (atom [])
                   access-calls (atom 0)
                   t-before (storage/get-t sql)
                   message
                   {:type "tx/batch"
                    :t-before t-before
                    :txs [{:outliner-op :save-block
                           :tx (protocol/tx->transit [])}]}]
               (p/with-redefs
                 [index/<user-has-access-to-graph?
                  (fn [& _]
                    (swap! access-calls inc)
                    (p/resolved true))
                  presence/broadcast-online-users! (fn [& _] nil)
                  ws/send! (fn [_ value] (swap! sent conj value))]
                 (let [result
                       (ws-handler/handle-ws-message!
                        self socket (protocol/encode-message message))]
                   (p/let [_ (await-handler-result result)]
                     (is (= [[1008 "graph access revoked"]] @closes))
                     (is (zero? @access-calls)
                         "no D1 authorization query is possible without a user identity")
                     (is (empty? @sent))
                     (is (= t-before (storage/get-t sql)))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest staged-upload-final-metadata-failure-keeps-old-live-conn-test
  (testing "a last in-transaction attestation failure never publishes the staged connection"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   {:keys [block-uuid]}
                   (seed-valid-graph! source-conn "publish-after-commit-source")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   {:keys [conn]
                    live-block-uuid :block-uuid}
                   (seed-live-with-idempotency-marker!
                    destination-sql "publish-after-commit-live")
                   _ (persist-current-checksums! destination-sql conn)
                   live-before (live-durable-state destination-sql)
                   original-exec (.-exec destination-sql)
                   fail-final-attestation? (atom true)
                   ready-events (atom [])
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   upload-request
                   (fn [upload-id]
                     (snapshot-upload-request
                      (str "reset=true&finished=true"
                           "&upload-id=" upload-id
                           "&checksum=" source-checksum
                           "&row-count=" (count source-rows))
                      source-rows))]
               (aset destination-sql "exec"
                     (fn [sql-str & args]
                       (if (and (string/includes?
                                 (string/lower-case sql-str)
                                 "insert into integrity_attestations")
                                (compare-and-set!
                                 fail-final-attestation? true false))
                         (throw (js/Error.
                                 "synthetic final attestation failure"))
                         (apply original-exec sql-str args))))
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [_self _graph-id value]
                    (swap! ready-events conj value)
                    (p/resolved true))]
                 (p/let [failed
                         (observe-handler-result
                          (sync-handler/handle-http
                           self (upload-request "faulted-generation")))
                         _ (is (handler-failure-observed? failed))
                         _ (is (= live-before
                                  (live-durable-state destination-sql))
                               "the last metadata failure rolls back live SQL")
                         _ (is (some? (.-conn self))
                               "failure reopens the transactionally preserved live root")
                         _ (is (not (identical? conn (.-conn self)))
                               "the memory-safe path does not retain the released old connection")
                         _ (is (= "live-before-legacy-upload"
                                  (:block/title
                                   (d/entity @(.-conn self)
                                             [:block/uuid live-block-uuid]))))
                         _ (is (nil?
                                (d/entity @(.-conn self)
                                          [:block/uuid block-uuid])))
                         _ (is (empty? (staging-rows destination-sql)))
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         _ (is (empty? @ready-events)
                               "failed activation is never projected ready")
                         retry
                         (sync-handler/handle-http
                          self (upload-request "retry-generation"))]
                   (is (= 200 (.-status retry)))
                   (is (= source-rows (kvs-rows destination-sql)))
                   (is (not (identical? conn (.-conn self))))
                   (is (= [true] @ready-events)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(def ^:private upload-control-meta-keys
  [:snapshot-upload-id
   :snapshot-upload-status
   :snapshot-uploading?
   :snapshot-upload-started-at
   :snapshot-upload-committed-checksum
   :snapshot-upload-committed-row-count])

(defn- upload-control-state
  [sql]
  (into {}
        (map (fn [key] [key (storage/get-meta sql key)]))
        upload-control-meta-keys))

(defn- replace-upload-control-state!
  [sql state]
  (doseq [key upload-control-meta-keys]
    (storage/delete-meta! sql key))
  (doseq [[key value] state
          :when (some? value)]
    (storage/set-meta! sql key value)))

(defn- upload-gated-live-state
  [sql]
  {:live (live-durable-state sql)
   :control (upload-control-state sql)
   :staging (staging-rows sql)
   :attestation (storage/snapshot-integrity-attestation sql)
   :kvs-generation (storage/live-kvs-mutation-generation sql)
   :checksum-generation
   (storage/snapshot-checksum-mutation-generation sql)})

(deftest inconsistent-snapshot-upload-tuples-block-every-live-mutation-test
  (testing "partial or contradictory upload receipts fail closed for tx, Semantic, and activation"
    (async done
      (let [cases
            [{:label :active-marker-missing
              :state {:snapshot-upload-id "tuple-active-missing"
                      :snapshot-upload-status "active"
                      :snapshot-upload-started-at 1760000000000}}
             {:label :legacy-marker-false
              :state {:snapshot-upload-id "legacy-v1:tuple-false"
                      :snapshot-upload-status "legacy-active"
                      :snapshot-upload-started-at 1760000000000
                      :snapshot-uploading? false}}
             {:label :active-marker-malformed
              :state {:snapshot-upload-id "tuple-active-malformed"
                      :snapshot-upload-status "active"
                      :snapshot-upload-started-at 1760000000000
                      :snapshot-uploading? "sometimes"}}
             {:label :false-marker-alone
              :state {:snapshot-uploading? false}}
             {:label :active-id-missing
              :state {:snapshot-upload-status "active"
                      :snapshot-upload-started-at 1760000000000
                      :snapshot-uploading? true}}
             {:label :legacy-id-status-mismatch
              :state {:snapshot-upload-id "tuple-not-legacy"
                      :snapshot-upload-status "legacy-active"
                      :snapshot-upload-started-at 1760000000000
                      :snapshot-uploading? true}}
             {:label :active-start-missing
              :state {:snapshot-upload-id "tuple-start-missing"
                      :snapshot-upload-status "active"
                      :snapshot-uploading? true}}
             {:label :active-start-malformed
              :state {:snapshot-upload-id "tuple-start-malformed"
                      :snapshot-upload-status "active"
                      :snapshot-upload-started-at "1760000000000junk"
                      :snapshot-uploading? true}}
             {:label :committed-checksum-missing
              :state {:snapshot-upload-id "tuple-committed-checksum"
                      :snapshot-upload-status "committed"
                      :snapshot-upload-started-at 1760000000000
                      :snapshot-uploading? false
                      :snapshot-upload-committed-row-count 5}}
             {:label :committed-row-count-missing
              :state {:snapshot-upload-id "tuple-committed-row-count"
                      :snapshot-upload-status "committed"
                      :snapshot-upload-started-at 1760000000000
                      :snapshot-uploading? false
                      :snapshot-upload-committed-checksum
                      "0000000000000000"}}]]
        (-> (with-memory-sql-async
             (fn [source-sql]
               (storage/init-schema! source-sql)
               (let [source-conn (storage/open-conn source-sql)
                     _ (seed-valid-graph! source-conn "tuple-source")
                     source-rows (kvs-rows source-sql)
                     source-checksum
                     (sync-checksum/recompute-checksum @source-conn)]
                 (p/all
                  (mapv
                   (fn [{:keys [label state]}]
                     (with-memory-sql-async
                       (fn [sql]
                         (storage/init-schema! sql)
                         (let [{:keys [conn page-uuid block-uuid]}
                               (seed-live-with-idempotency-marker!
                                sql (name label))
                               initialized
                               (apply-entry!
                                #js {:sql sql :conn conn :schema-ready true}
                                {:tx-id (random-uuid)
                                 :outliner-op :save-block
                                 :tx
                                 (protocol/tx->transit
                                  [[:db/add [:block/uuid page-uuid]
                                    :block/created-at 1760000000000]
                                   [:db/add [:block/uuid page-uuid]
                                    :block/updated-at 1760000000000]
                                   [:db/add [:block/uuid block-uuid]
                                    :block/created-at 1760000000001]
                                   [:db/add [:block/uuid block-uuid]
                                    :block/updated-at 1760000000001]])})
                               _ (is (= "tx/batch/ok" (:type initialized))
                                     (name label))
                               _ (replace-upload-control-state! sql state)
                               _ (common/sql-exec
                                  sql
                                  (str "insert into snapshot_kvs_staging "
                                       "(addr, content, addresses) "
                                       "values (991, 'tuple-sentinel', null)"))
                               self #js {:env #js {"DB" nil}
                                         :sql sql
                                         :conn conn
                                         :schema-ready true}
                               state-before (upload-gated-live-state sql)
                               t-before (storage/get-t sql)
                               patch-request
                               (js/Request.
                                (str "http://localhost/semantic/blocks/"
                                     block-uuid
                                     "?graph-id=synthetic-graph")
                                #js {:method "PATCH"
                                     :headers
                                     #js {"content-type"
                                          "application/json"}
                                     :body
                                     (js/JSON.stringify
                                      #js {:title "must-not-apply"})})
                               activation-request
                               (snapshot-upload-request
                                (str "reset=true&finished=true"
                                     "&upload-id=attempt-" (name label)
                                     "&checksum=" source-checksum
                                     "&row-count=" (count source-rows))
                                source-rows)]
                           (is (not (sync-handler/snapshot-upload-finished?
                                    self))
                               (str (name label)
                                    " must not be treated as idle"))
                           (let [ordinary
                                 (apply-entry!
                                  self
                                  {:tx-id (random-uuid)
                                   :outliner-op :save-block
                                   :tx
                                   (protocol/tx->transit
                                    [[:db/add [:block/uuid block-uuid]
                                      :block/title "must-not-apply"]])})]
                             (is (= "tx/reject" (:type ordinary))
                                 (name label))
                             (is (= t-before (storage/get-t sql))
                                 (name label))
                             (is (= state-before
                                    (upload-gated-live-state sql))
                                 (name label)))
                           (p/let [semantic-response
                                   (sync-handler/handle-http
                                    self patch-request)
                                   _ (is (= 409 (.-status semantic-response))
                                         (name label))
                                   _ (is (= state-before
                                            (upload-gated-live-state sql))
                                         (name label))
                                   activation-response
                                   (p/with-redefs
                                     [sync-handler/<set-graph-ready-for-use!
                                      (fn [& _] (p/resolved true))]
                                     (sync-handler/handle-http
                                      self activation-request))]
                             (is (= 409 (.-status activation-response))
                                 (name label))
                             (is (= state-before
                                    (upload-gated-live-state sql))
                                 (name label)))))))
                   cases)))))
            (p/catch (fn [error]
                       (is false (str error))))
            (p/finally done))))))

(deftest complete-snapshot-upload-tuples-retain-idle-active-and-receipt-semantics-test
  (testing "all-absent is idle, complete active tuples block, and an exact committed receipt retries"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [{:keys [conn block-uuid]}
                   (seed-live-with-idempotency-marker! sql "tuple-valid")
                   self #js {:env #js {"DB" nil}
                             :sql sql :conn conn :schema-ready true}
                   idle-write
                   (apply-entry!
                    self
                    {:tx-id (random-uuid)
                     :outliner-op :save-block
                     :tx
                     (protocol/tx->transit
                      [[:db/add [:block/uuid block-uuid]
                        :block/title "idle-write"]])})
                   _ (is (= "tx/batch/ok" (:type idle-write)))
                   _ (is (sync-handler/snapshot-upload-finished? self))
                   _ (replace-upload-control-state!
                      sql
                      {:snapshot-upload-id "tuple-valid-active"
                       :snapshot-upload-status "active"
                       :snapshot-upload-started-at 1760000000000
                       :snapshot-uploading? true})
                   _ (is (not (sync-handler/snapshot-upload-finished? self)))
                   _ (replace-upload-control-state!
                      sql
                      {:snapshot-upload-id "legacy-v1:tuple-valid"
                       :snapshot-upload-status "legacy-active"
                       :snapshot-upload-started-at 1760000000000
                       :snapshot-uploading? true})
                   _ (is (not (sync-handler/snapshot-upload-finished? self)))
                   _ (replace-upload-control-state! sql {})
                   checksum (storage/get-checksum sql)
                   row-count (count (kvs-rows sql))
                   receipt-id "tuple-valid-committed"
                   _ (replace-upload-control-state!
                      sql
                      {:snapshot-upload-id receipt-id
                       :snapshot-upload-status "committed"
                       :snapshot-upload-started-at 1760000000000
                       :snapshot-uploading? false
                       :snapshot-upload-committed-checksum checksum
                       :snapshot-upload-committed-row-count row-count})
                   state-before (upload-gated-live-state sql)
                   retry-request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=" receipt-id
                         "&checksum=" checksum
                         "&row-count=" row-count)
                    [])]
               (is (sync-handler/snapshot-upload-finished? self))
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [& _] (p/resolved true))]
                 (p/let [response
                         (sync-handler/handle-http self retry-request)]
                   (is (= 200 (.-status response)))
                   (is (= state-before (upload-gated-live-state sql))
                       "an exact receipt retry cannot recopy or reseal live"))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest activation-rollback-reopen-failure-still-cleans-gate-and-retries-test
  (testing "a recovery reopen fault cannot skip abort after activation rolls back"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph!
                      source-conn "activation-reopen-fault-source")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   {:keys [conn block-uuid]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "activation-reopen-fault-live")
                   live-before (live-durable-state destination-sql)
                   original-exec (.-exec destination-sql)
                   activation-failed? (atom false)
                   reopen-failed? (atom false)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   upload-request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=activation-reopen-fault"
                         "&checksum=" source-checksum
                         "&row-count=" (count source-rows))
                    source-rows)]
               (aset destination-sql "exec"
                     (fn [sql-str & args]
                       (let [normalized (string/lower-case sql-str)]
                         (cond
                           (and (string/includes?
                                 normalized
                                 "insert into integrity_attestations")
                                (compare-and-set!
                                 activation-failed? false true))
                           (throw
                            (js/Error.
                             "synthetic activation attestation failure"))

                           (and @activation-failed?
                                (string/includes?
                                 normalized
                                 "create table if not exists kvs ")
                                (compare-and-set! reopen-failed? false true))
                           (throw
                            (js/Error.
                             "synthetic recovery reopen failure"))

                           :else
                           (apply original-exec sql-str args)))))
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [& _] (p/resolved true))]
                 (p/let [failed
                         (observe-handler-result
                          (sync-handler/handle-http self upload-request))
                         _ (is (handler-failure-observed? failed))
                         _ (is @activation-failed?)
                         _ (is @reopen-failed?)
                         _ (is (= live-before
                                  (live-durable-state destination-sql))
                               "the failed activation rolls live SQL back exactly")
                         _ (is (empty? (staging-rows destination-sql)))
                         _ (is (= (zipmap upload-control-meta-keys
                                          (repeat nil))
                                  (upload-control-state destination-sql))
                               "reopen failure cannot strand the upload lease")
                         _ (is (sync-handler/snapshot-upload-finished? self))
                         _ (is (some? (.-conn self))
                               "bounded recovery retries the old live reopen")
                         ordinary
                         (apply-entry!
                          self
                          {:tx-id (random-uuid)
                           :outliner-op :save-block
                           :tx (protocol/tx->transit
                                [[:db/add [:block/uuid block-uuid]
                                  :block/title
                                  "ordinary-after-reopen-fault"]])})]
                   (is (= "tx/batch/ok" (:type ordinary)))
                   (is (= (inc (:t live-before)) (:t ordinary))))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest generic-v2-abort-retries-one-atomic-cleanup-failure-test
  (testing "an abort transaction fault is retried before ordinary sync resumes"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [{:keys [conn block-uuid]}
                   (seed-live-with-idempotency-marker!
                    sql "generic-abort-cleanup-fault")
                   live-before (live-durable-state sql)
                   original-exec (.-exec sql)
                   staging-delete-count (atom 0)
                   cleanup-failed? (atom false)
                   self #js {:env #js {"DB" nil}
                             :sql sql
                             :conn conn
                             :schema-ready true}
                   request
                   (malformed-staged-snapshot-upload-request
                    (str "reset=true&finished=false"
                         "&upload-id=generic-abort-cleanup-fault"))]
               (aset sql "exec"
                     (fn [sql-str & args]
                       (let [normalized (string/lower-case sql-str)]
                         (if (and (string/includes?
                                   normalized
                                   "delete from snapshot_kvs_staging")
                                  (= 2 (swap! staging-delete-count inc))
                                  (compare-and-set!
                                   cleanup-failed? false true))
                           (throw
                            (js/Error.
                             "synthetic abort cleanup transaction failure"))
                           (apply original-exec sql-str args)))))
               (p/let [failed
                       (observe-handler-result
                        (sync-handler/handle-http self request))
                       _ (is (handler-failure-observed? failed))
                       _ (is @cleanup-failed?)
                       _ (is (= live-before (live-durable-state sql)))
                       _ (is (empty? (staging-rows sql)))
                       _ (is (= (zipmap upload-control-meta-keys
                                        (repeat nil))
                                (upload-control-state sql))
                             "the idempotent abort must retry once")
                       _ (is (sync-handler/snapshot-upload-finished? self))
                       _ (is (identical? conn (.-conn self)))
                       ordinary
                       (apply-entry!
                        self
                        {:tx-id (random-uuid)
                         :outliner-op :save-block
                         :tx (protocol/tx->transit
                              [[:db/add [:block/uuid block-uuid]
                                :block/title
                                "ordinary-after-abort-cleanup-fault"]])})]
                 (is (= "tx/batch/ok" (:type ordinary)))
                 (is (= (inc (:t live-before)) (:t ordinary)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest twice-failed-v2-cleanup-stays-closed-and-allows-explicit-retry-test
  (testing "two cleanup faults fail closed without claiming success; a later reset recovers"
    (async done
      (-> (with-memory-sql-pair-async
           (fn [source-sql destination-sql]
             (doseq [sql [source-sql destination-sql]]
               (storage/init-schema! sql))
             (let [source-conn (storage/open-conn source-sql)
                   _ (seed-valid-graph!
                      source-conn "twice-failed-cleanup-source")
                   source-rows (kvs-rows source-sql)
                   source-checksum
                   (sync-checksum/recompute-checksum @source-conn)
                   {:keys [conn]}
                   (seed-live-with-idempotency-marker!
                    destination-sql "twice-failed-cleanup-live")
                   live-before (live-durable-state destination-sql)
                   original-exec (.-exec destination-sql)
                   staging-delete-count (atom 0)
                   cleanup-fault-count (atom 0)
                   self #js {:env #js {"DB" nil}
                             :sql destination-sql
                             :conn conn
                             :schema-ready true}
                   failed-request
                   (malformed-staged-snapshot-upload-request
                    (str "reset=true&finished=false"
                         "&upload-id=twice-failed-cleanup"))
                   retry-request
                   (snapshot-upload-request
                    (str "reset=true&finished=true"
                         "&upload-id=cleanup-explicit-retry"
                         "&checksum=" source-checksum
                         "&row-count=" (count source-rows))
                    source-rows)]
               (aset destination-sql "exec"
                     (fn [sql-str & args]
                       (let [normalized (string/lower-case sql-str)
                             delete-number
                             (when (string/includes?
                                    normalized
                                    "delete from snapshot_kvs_staging")
                               (swap! staging-delete-count inc))]
                         (if (contains? #{2 3} delete-number)
                           (do
                             (swap! cleanup-fault-count inc)
                             (throw
                              (js/Error.
                               "synthetic persistent cleanup failure")))
                           (apply original-exec sql-str args)))))
               (p/with-redefs
                 [sync-handler/<set-graph-ready-for-use!
                  (fn [& _] (p/resolved true))]
                 (p/let [failed-response
                         (sync-handler/handle-http self failed-request)
                         _ (is (= 500 (.-status failed-response)))
                         _ (is (= 2 @cleanup-fault-count))
                         _ (is (= live-before
                                  (live-durable-state destination-sql)))
                         _ (is (false?
                                (sync-handler/snapshot-upload-finished? self))
                               "failed recovery must not claim the gate is open")
                         _ (is (= "twice-failed-cleanup"
                                  (:upload-id
                                   (#'sync-handler/snapshot-upload-session
                                    destination-sql))))
                         retry-response
                         (sync-handler/handle-http self retry-request)]
                   (is (= 200 (.-status retry-response)))
                   (is (= source-rows (kvs-rows destination-sql)))
                   (is (empty? (staging-rows destination-sql)))
                   (is (sync-handler/snapshot-upload-finished? self)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest admin-reset-fallback-is-one-atomic-storage-transition-test
  (testing "a middle DROP failure preserves every table and live fact; a clean retry resets once"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [{:keys [conn]}
                   (seed-live-with-idempotency-marker!
                    sql "admin-reset-atomic")
                   tables-before (sqlite-table-names sql)
                   live-before (live-durable-state sql)
                   original-exec (.-exec sql)
                   fail? (atom true)
                   state #js {:storage #js {}
                              :getWebSockets (fn [] #js [])}
                   self #js {:sql sql
                             :conn conn
                             :state state
                             :schema-ready true}]
               (aset sql "exec"
                     (fn [sql-str & args]
                       (if (and @fail?
                                (string/includes?
                                 (string/lower-case sql-str)
                                 "drop table if exists tx_log"))
                         (do
                           (reset! fail? false)
                           (throw (js/Error. "synthetic middle DROP failure")))
                         (apply original-exec sql-str args))))
               (p/let [failed
                       (observe-handler-result
                        (sync-handler/handle
                         {:self self
                          :route {:handler :sync/admin-reset}}))
                       _ (is (handler-failure-observed? failed))
                       tables-after-failure (sqlite-table-names sql)
                       _ (is (= tables-before tables-after-failure)
                             "DDL rollback must restore every dropped table")
                       _ (when (= tables-before tables-after-failure)
                           (is (= live-before (live-durable-state sql))))
                       _ (is (identical? conn (.-conn self)))
                       retry-response
                       (sync-handler/handle
                        {:self self
                         :route {:handler :sync/admin-reset}})]
                 (is (= 200 (.-status retry-response)))
                 (is (storage/schema-ready? sql))
                 (is (empty? (kvs-rows sql)))
                 (is (nil? (.-conn self)))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest snapshot-download-reservation-schema-upgrades-across-do-restart-test
  (with-memory-sql
    (fn [sql]
      (common/sql-exec
       sql
       (str "create table snapshot_download_generations ("
            "download_id TEXT primary key,"
            "generation_key TEXT not null,"
            "legacy INTEGER not null,"
            "lease_count INTEGER not null)"))
      (common/sql-exec
       sql
       (str "insert into snapshot_download_generations "
            "(download_id, generation_key, legacy, lease_count) "
            "values ('legacy-row', 'legacy-generation', 1, 1)"))
      (storage/init-schema! sql)
      (let [migrated
            (first
             (common/get-sql-rows
              (common/sql-exec
               sql
               (str "select frozen from snapshot_download_generations "
                    "where download_id = 'legacy-row'"))))]
        (is (= 1 (aget migrated "frozen"))
            "pre-upgrade eager exports default to the frozen state")
        (is (storage/schema-ready? sql)))
      ;; A cold DO repeats idempotent schema initialization against the same
      ;; Durable Object database rather than recreating its storage.
      (storage/init-schema! sql)
      (is (storage/schema-ready? sql))
      (is (= 1
             (some-> (common/sql-exec
                      sql
                      (str "select frozen from snapshot_download_generations "
                           "where download_id = 'legacy-row'"))
                     common/get-sql-rows
                     first
                     (aget "frozen")))))))

(deftest base-era-complete-v2-export-never-returns-200-with-a-failing-body-after-upgrade-test
  (testing "a real base-era reservation has downloads and exports but no generation row or table"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   {:keys [block-uuid created-at]}
                   (seed-valid-graph! conn "base-era-complete-export")
                   old-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql sql :conn conn :schema-ready true}]
               (p/let [metadata-response (v2-metadata! old-self)
                       metadata (json-body metadata-response)
                       download-id (download-id-from-url (:url metadata))
                       base-state
                       (snapshot-state-counts-for-download sql download-id)
                       _ (common/sql-exec
                          sql "drop table snapshot_download_generations")
                       _ (is (not-any?
                              #(= "snapshot_download_generations" %)
                              (sqlite-table-names sql))
                             "the persisted pre-upgrade shape has no generation table")
                       ;; Model both the rolling upgrade and a later cold DO
                       ;; restart against exactly the same durable SQLite data.
                       _ (storage/init-schema! sql)
                       _ (storage/init-schema! sql)
                       restarted-self
                       #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql sql :conn nil :schema-ready true}
                       stream-response
                       (sync-handler/handle-http
                        restarted-self
                        (js/Request. (:url metadata) #js {:method "GET"}))
                       status (.-status stream-response)
                       state-before-body
                       (snapshot-state-counts-for-download sql download-id)
                       body-outcome
                       (consume-v2-stream-response! stream-response)
                       state-after-body
                       (snapshot-state-counts-for-download sql download-id)
                       projection
                       (when-let [rows (:rows body-outcome)]
                         (rows-projection rows block-uuid))]
                 (is (= 200 (.-status metadata-response)))
                 (is (= 1 (:downloads base-state)))
                 (is (pos? (:exports base-state))
                     "the base-era export is complete before the upgrade")
                 (is (contains? #{200 410} status)
                     "the upgrade must either safely serve or explicitly expire the old URL")
                 (case status
                   200
                   (do
                     (is (nil? (:stream-error body-outcome))
                         "HTTP 200 commits to a fully consumable framed body")
                     (when-not (:stream-error body-outcome)
                       (is (= created-at (:created-at projection)))
                       (is (= "before-concurrent-tx" (:title projection)))))

                   410
                   (do
                     (is (= {:downloads 0 :exports 0} state-before-body)
                         "410 cleanup is durable before the response body is read")
                     (is (= {:error "snapshot download expired"}
                            (:body body-outcome))))

                   (is false (str "unexpected status " status)))
                 (is (= {:downloads 0 :exports 0} state-after-body)
                     "a served or expired old reservation cannot remain orphaned")))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest base-era-incomplete-v2-reservations-do-not-consume-capacity-after-upgrade-test
  (testing "generationless orphan rows are removed before a new reservation is capacity-checked"
    (async done
      (-> (with-memory-sql-async
           (fn [sql]
             (storage/init-schema! sql)
             (let [conn (storage/open-conn sql)
                   _ (seed-valid-graph! conn "base-era-orphan-capacity")
                   old-self
                   #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                        :sql sql :conn conn :schema-ready true}]
               (p/let [old-response-a (v2-metadata! old-self)
                       old-metadata-a (json-body old-response-a)
                       old-response-b (v2-metadata! old-self)
                       old-metadata-b (json-body old-response-b)
                       old-id-a (download-id-from-url (:url old-metadata-a))
                       old-id-b (download-id-from-url (:url old-metadata-b))
                       _ (common/sql-exec
                          sql
                          "delete from snapshot_kvs_exports where download_id in (?, ?)"
                          old-id-a old-id-b)
                       _ (common/sql-exec
                          sql "drop table snapshot_download_generations")
                       _ (is (= {:downloads 2 :exports 0}
                                (snapshot-state-counts sql))
                             "two incomplete base-era reservations fill the old limit")
                       _ (storage/init-schema! sql)
                       _ (storage/init-schema! sql)
                       restarted-self
                       #js {:env #js {"DB_SYNC_SNAPSHOT_STREAM_GZIP" "false"}
                            :sql sql :conn nil :schema-ready true}
                       new-response (v2-metadata! restarted-self)
                       new-body (json-body new-response)
                       new-id (when (string? (:url new-body))
                                (download-id-from-url (:url new-body)))]
                 (is (= 200 (.-status old-response-a)))
                 (is (= 200 (.-status old-response-b)))
                 (is (= 200 (.-status new-response))
                     "old generationless orphans must not cause a false 429")
                 (is (= [{:downloads 0 :exports 0}
                         {:downloads 0 :exports 0}]
                        [(snapshot-state-counts-for-download sql old-id-a)
                         (snapshot-state-counts-for-download sql old-id-b)])
                     "both incomplete pre-upgrade reservations are reclaimed")
                 (when (= 200 (.-status new-response))
                   (is (string? new-id))
                   (is (not (contains? #{old-id-a old-id-b} new-id)))
                   (is (= 1 (:downloads (snapshot-state-counts sql)))
                       "only the newly serviceable reservation occupies capacity"))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))
