(ns frontend.worker.sync.handle-message
  "WebSocket message handlers for db sync."
  (:require [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync.apply-txs :as sync-apply]
            [frontend.worker.sync.assets :as sync-assets]
            [frontend.worker.sync.auth :as sync-auth]
            [frontend.worker.sync.client-op :as client-op]
            [frontend.worker.sync.crypt :as sync-crypt]
            [frontend.worker.sync.log-and-state :as sync-log-state]
            [frontend.worker.sync.presence :as sync-presence]
            [frontend.worker.sync.transport :as sync-transport]
            [frontend.worker.sync.util :as sync-util]
            [lambdaisland.glogi :as log]
            [logseq.db-sync.checksum :as sync-checksum]
            [promesa.core :as p]))

(defn- fail-fast
  [tag data]
  (log/error tag data)
  (throw (ex-info (name tag) data)))

(defn- sync-counts
  [repo]
  (sync-presence/sync-counts
   {:get-datascript-conn worker-state/get-datascript-conn
    :get-client-ops-conn worker-state/get-client-ops-conn
    :get-pending-local-tx-count client-op/get-pending-local-tx-count
    :get-unpushed-asset-ops-count client-op/get-unpushed-asset-ops-count
    :get-missing-asset-upload-files sync-assets/get-missing-asset-upload-files
    :get-local-tx client-op/get-local-tx
    :get-local-checksum client-op/get-local-checksum
    :get-local-server-checksum client-op/get-local-server-checksum
    :get-graph-uuid client-op/get-graph-uuid
    :latest-remote-tx @sync-apply/*repo->latest-remote-tx
    :latest-remote-checksum @sync-apply/*repo->latest-remote-checksum
    :latest-remote-checksum-version
    @sync-apply/*repo->latest-remote-checksum-version}
   repo))

(defn- broadcast-rtc-state!
  [client]
  (when client
    (shared-service/broadcast-to-clients!
     :rtc-sync-state
     (sync-presence/rtc-state-payload sync-counts client))))

(defn- update-online-users!
  [client users]
  (sync-presence/update-online-users! broadcast-rtc-state! client users))

(defn- update-user-presence!
  [client user-id* editing-block-uuid]
  (sync-presence/update-user-presence! broadcast-rtc-state! client user-id* editing-block-uuid))

(defn- get-user-uuid
  []
  (sync-auth/get-user-uuid (worker-state/get-id-token)))

(defn- send!
  [ws message]
  (sync-transport/send! sync-transport/coerce-ws-client-message ws message))

(defn- ws-open?
  [ws]
  (sync-transport/ws-open? ws))

(defn- enqueue-asset-task!
  [client task]
  (sync-assets/enqueue-asset-task! client task))

(defn- enqueue-send-task!
  [client task]
  (if-let [queue (:send-queue client)]
    (swap! queue
           (fn [prev]
             (-> (or prev (p/resolved nil))
                 (p/catch (fn [_] nil))
                 (p/then (fn [_] (task)))
                 (p/catch (fn [error]
                            (log/error :db-sync/send-queue-task-failed
                                       {:repo (:repo client)
                                        :diagnostic
                                        (dissoc
                                         (sync-util/error->diagnostic error)
                                         :at)}))))))
    (task)))

(defn- current-client
  [repo]
  (sync-presence/current-client worker-state/*db-sync-client repo))

(defn- value-shape
  [value]
  (cond
    (nil? value) :nil
    (number? value) :number
    (string? value) :string
    (uuid? value) :uuid
    (sequential? value) :sequential
    (map? value) :map
    :else :other))

(defn- require-number
  [value context]
  (when-not (number? value)
    (fail-fast :db-sync/invalid-field
               (assoc context :value-shape (value-shape value)))))

(defn- require-non-negative
  [value context]
  (require-number value context)
  (when (neg? value)
    (fail-fast :db-sync/invalid-field
               (assoc context :value-shape (value-shape value)))))

(defn- require-seq
  [value context]
  (when-not (sequential? value)
    (fail-fast :db-sync/invalid-field
               (assoc context :value-shape (value-shape value)))))

(defn- require-uuid
  [value context]
  (when-not (uuid? value)
    (fail-fast :db-sync/invalid-field
               (assoc context :value-shape (value-shape value)))))

(defn- parse-transit
  [value context]
  (sync-transport/parse-transit fail-fast value context))

(defn- request-pull!
  [client since]
  (when (and (:ws client) (ws-open? (:ws client)))
    (enqueue-send-task!
     client
     (fn []
       (when (and (:ws client) (ws-open? (:ws client)))
         (if-let [*pending (:pending-pull-since client)]
           (let [pending @*pending]
             (when (or (nil? pending) (< since pending))
               (reset! *pending since)
               (send! (:ws client) {:type "pull" :since since})))
           (send! (:ws client) {:type "pull" :since since})))))))

(defn- clear-pending-pull!
  [client]
  (when-let [*pending (:pending-pull-since client)]
    (reset! *pending nil)))

(def ^:private deferred-pull-delay-ms 250)

(defn- schedule-deferred-pull!
  [repo client]
  (js/setTimeout
   (fn []
     ;; Read the cursor again: no cursor is advanced by a deferred apply, but a
     ;; concurrent acknowledgement may have legitimately advanced it.
     (request-pull! client (client-op/get-local-tx repo)))
   deferred-pull-delay-ms))

(defn- pending-local-tx?
  [repo]
  (pos? (or (client-op/get-pending-local-tx-count repo) 0)))

(defn- mark-sync-succeeded!
  [client]
  (when-let [sync-succeeded-f (:sync-succeeded-f client)]
    (sync-succeeded-f)))

(defn- synced-checksum-ready?
  [repo client local-t remote-t]
  (and (= local-t remote-t)
       (not (pending-local-tx? repo))
       (empty? @(:inflight client))))

(defn- checksum-compare-ready?
  [repo client local-t remote-t]
  (synced-checksum-ready? repo client local-t remote-t))

(defn- verify-sync-checksum!
  [repo client local-tx remote-tx
   {:keys [checksum server-checksum checksum-version]}
   context]
  (when (checksum-compare-ready? repo client local-tx remote-tx)
    (let [conn (worker-state/get-datascript-conn repo)
          local-checksum
          (or (client-op/get-local-checksum repo)
              (when conn
                (let [computed (sync-checksum/recompute-checksum @conn)]
                  (client-op/update-local-checksum repo computed)
                  computed)))
          versioned-checksum?
          (and (= sync-checksum/server-checksum-version checksum-version)
               (string? server-checksum))
          local-server-checksum
          (when versioned-checksum?
            (or (client-op/get-local-server-checksum repo)
                (when conn
                  (let [computed
                        (sync-checksum/recompute-server-checksum @conn)]
                    (client-op/update-local-server-checksum repo computed)
                    computed))))
          legacy-match? (or (not (string? checksum))
                            (= local-checksum checksum))
          versioned-match? (and versioned-checksum?
                                (= local-server-checksum server-checksum))
          mismatch-data
          (cond-> (merge context
                         {:type :db-sync/checksum-mismatch
                          :repo repo
                          :message-type (:type context)
                          :local-tx local-tx
                          :remote-tx remote-tx
                          :local-checksum local-checksum
                          :remote-checksum checksum})
            versioned-checksum?
            (assoc :checksum-version checksum-version
                   :local-server-checksum local-server-checksum
                   :remote-server-checksum server-checksum))]
      (cond
        ;; A recognized advertised checksum is authoritative. A matching
        ;; historical value must not hide a stale/corrupt server checksum.
        (and versioned-checksum? (not versioned-match?))
        (do
          (sync-log-state/rtc-log :rtc.log/checksum-mismatch mismatch-data)
          (fail-fast :db-sync/checksum-mismatch mismatch-data))

        ;; The versioned representation intentionally bridges logical large
        ;; titles and their server transport placeholders.
        (and versioned-match? (not legacy-match?))
        (let [compatibility-data
              (assoc mismatch-data
                     :compatibility :versioned-server-checksum)]
          (sync-log-state/rtc-log
           :rtc.log/checksum-mismatch compatibility-data)
          (log/warn :db-sync/versioned-server-checksum compatibility-data))

        ;; Old/unknown servers retain the strict historical comparison.
        (not legacy-match?)
        (do
          (sync-log-state/rtc-log :rtc.log/checksum-mismatch mismatch-data)
          (fail-fast :db-sync/checksum-mismatch mismatch-data)))))
  nil)

(defn- handle-tx-reject!
  [repo client message local-tx]
  (sync-apply/clear-upload-response-timeout! client)
  (let [reason (:reason message)
        remote-tx (:t message)
        error-detail (:error-detail message)
        success-tx-ids (:success-tx-ids message)
        failed-tx-id (:failed-tx-id message)
        missing-block-uuids (:missing-block-uuids message)]
    (when (nil? reason)
      (fail-fast :db-sync/missing-field
                 {:repo repo :type "tx/reject" :field :reason}))
    (when (contains? message :t)
      (require-non-negative remote-tx {:repo repo :type "tx/reject"}))
    (when (and (contains? message :error-detail)
               (not (string? error-detail)))
      (fail-fast :db-sync/invalid-field
                 {:repo repo
                  :type "tx/reject"
                  :field :error-detail
                  :value-shape (value-shape error-detail)}))
    (when (contains? message :success-tx-ids)
      (require-seq success-tx-ids {:repo repo :type "tx/reject" :field :success-tx-ids})
      (doseq [tx-id success-tx-ids]
        (require-uuid tx-id {:repo repo :type "tx/reject" :field :success-tx-ids})))
    (when (contains? message :failed-tx-id)
      (require-uuid failed-tx-id {:repo repo :type "tx/reject" :field :failed-tx-id}))
    (when (contains? message :missing-block-uuids)
      (require-seq missing-block-uuids {:repo repo :type "tx/reject" :field :missing-block-uuids})
      (doseq [block-uuid missing-block-uuids]
        (require-uuid block-uuid {:repo repo :type "tx/reject" :field :missing-block-uuids})))
    (case reason
      "stale"
      (request-pull! client local-tx)

      (let [inflight @(:inflight client)
            inflight-set (set inflight)
            successful-tx-ids (->> (or success-tx-ids [])
                                   (filter inflight-set)
                                   vec)
            failed-tx-id (when (and failed-tx-id (contains? inflight-set failed-tx-id))
                           failed-tx-id)
            partial-success? (and (seq successful-tx-ids)
                                  (number? remote-tx))
            next-local-tx (when partial-success?
                            (max local-tx remote-tx))
            data (when-let [raw-data (:data message)]
                   (parse-transit raw-data
                                  {:repo repo
                                   :type "tx/reject"
                                   :reason reason
                                   :field :data}))
            rejected-outliner-op (when (map? data) (:outliner-op data))
            rejected-data (cond-> {:type :db-sync/tx-rejected
                                   :repo repo
                                   :message-type "tx/reject"
                                   :reason reason}
                            (contains? message :t) (assoc :t remote-tx)
                            (seq successful-tx-ids) (assoc :success-tx-ids successful-tx-ids)
                            (some? failed-tx-id) (assoc :failed-tx-id failed-tx-id)
                            (seq missing-block-uuids) (assoc :missing-block-uuids (vec missing-block-uuids))
                            (some? error-detail) (assoc :error-detail error-detail)
                            (some? data) (assoc :has-rejected-data? true)
                            (some? rejected-outliner-op)
                            (assoc :rejected-outliner-op rejected-outliner-op))]
        (if (or (contains? message :success-tx-ids)
                (contains? message :failed-tx-id))
          (do
            (sync-apply/mark-pending-txs-false! repo successful-tx-ids)
            (when failed-tx-id
              (sync-apply/rollback-and-mark-failed-txs! repo [failed-tx-id])))
          ;; Backward compatibility for older servers without per-tx reject metadata.
          (sync-apply/rollback-and-mark-failed-txs! repo inflight))
        ;; The server commits txs before the rejected tx and reports the resulting
        ;; cursor in :t. Advance the local cursor so rebased pending txs can resume.
        (when partial-success?
          (client-op/update-local-tx repo next-local-tx))
        (reset! (:inflight client) [])
        (broadcast-rtc-state! client)
        (let [resume-local-tx (or next-local-tx local-tx)
              latest-remote-tx (get @sync-apply/*repo->latest-remote-tx repo)]
          (if (and (number? latest-remote-tx)
                   (> latest-remote-tx resume-local-tx))
            (request-pull! client resume-local-tx)
            ;; A reject can identify the first failed entry without accepting
            ;; anything before it. Entries later in the same inflight batch
            ;; remain pending and must be scheduled again.
            (sync-apply/enqueue-flush-pending! repo client)))
        (sync-log-state/rtc-log :rtc.log/tx-rejected rejected-data)
        (fail-fast :db-sync/tx-rejected
                   rejected-data)))))

(defn- handle-hello!
  [repo client local-tx remote-tx checksum-fields]
  (require-non-negative remote-tx {:repo repo :type "hello"})
  (when (< remote-tx local-tx)
    (fail-fast :db-sync/server-cursor-regressed
               {:type :db-sync/server-cursor-regressed
                :repo repo
                :message-type "hello"
                :local-tx local-tx
                :remote-tx remote-tx}))
  (verify-sync-checksum! repo client local-tx remote-tx checksum-fields {:type "hello"})
  (broadcast-rtc-state! client)
  (if (> remote-tx local-tx)
    ;; Reconnects after a lost upload acknowledgement commonly see the server
    ;; ahead of the local cursor. Pull and rebase first; uploading against the
    ;; stale cursor in parallel only creates an avoidable tx/reject race.
    (request-pull! client local-tx)
    (do
      (mark-sync-succeeded! client)
      (sync-apply/enqueue-flush-pending! repo client)))
  (sync-assets/enqueue-asset-sync!
   repo client
   {:enqueue-asset-task-f enqueue-asset-task!
    :current-client-f current-client
    :broadcast-rtc-state!-f broadcast-rtc-state!
    :fail-fast-f fail-fast})
  (log/info :db-sync/handle-hello
            {:empty-inflight? (empty? @(:inflight client))
             :online? (worker-state/online?)
             :ws-open? (when-let [ws (:ws client)]
                         (ws-open? ws))
             :pending-txs-count (count (sync-apply/pending-txs repo {:limit 50}))}))

(defn- handle-online-users!
  [repo client message]
  (let [users (:online-users message)]
    (when (and (some? users) (not (sequential? users)))
      (fail-fast :db-sync/invalid-field
                 {:repo repo :type "online-users" :field :online-users}))
    (update-online-users! client (or users []))))

(defn- handle-presence!
  [client message]
  (let [{:keys [user-id editing-block-uuid]} message]
    (when-not (= (get-user-uuid) user-id)
      (update-user-presence! client user-id editing-block-uuid))))

(defn- handle-tx-batch-ok!
  [repo client remote-tx checksum-fields]
  (require-non-negative remote-tx {:repo repo :type "tx/batch/ok"})
  (sync-apply/ack-upload-response! repo client)
  (let [current-local-tx (client-op/get-local-tx repo)
        next-local-tx (max current-local-tx remote-tx)]
    (client-op/update-local-tx repo next-local-tx)
    (sync-util/clear-last-sync-error! client)
    (broadcast-rtc-state! client)
    (sync-apply/mark-pending-txs-false! repo @(:inflight client))
    (reset! (:inflight client) [])
    (verify-sync-checksum! repo client next-local-tx remote-tx checksum-fields {:type "tx/batch/ok"})
    (mark-sync-succeeded! client)
    (sync-apply/enqueue-flush-pending! repo client)))

(defn- update-latest-remote-state!
  [repo message]
  (let [message-type (:type message)
        remote-tx (:t message)
        checksum-version (:checksum-version message)
        versioned-checksum?
        (and (= sync-checksum/server-checksum-version checksum-version)
             (string? (:server-checksum message)))
        remote-checksum (if versioned-checksum?
                          (:server-checksum message)
                          (:checksum message))
        has-checksum? (if versioned-checksum?
                        (contains? message :server-checksum)
                        (contains? message :checksum))
        latest-remote-tx (get @sync-apply/*repo->latest-remote-tx repo)
        authoritative? (contains? #{"hello" "changed"} message-type)
        stale-remote-tx? (and (number? remote-tx)
                              (number? latest-remote-tx)
                              (< remote-tx latest-remote-tx)
                              (not authoritative?))]
    (when (number? remote-tx)
      (if authoritative?
        (swap! sync-apply/*repo->latest-remote-tx assoc repo remote-tx)
        (swap! sync-apply/*repo->latest-remote-tx
               update repo
               (fn [prev]
                 (if (number? prev)
                   (max prev remote-tx)
                   remote-tx)))))
    (when (and has-checksum? (not stale-remote-tx?))
      (swap! sync-apply/*repo->latest-remote-checksum assoc repo remote-checksum)
      (swap! sync-apply/*repo->latest-remote-checksum-version
             assoc repo
             (when versioned-checksum? checksum-version)))
    {:stale-remote-tx? stale-remote-tx?
     :latest-remote-tx-before latest-remote-tx}))

(declare handle-pull-ok! handle-changed!)

(defn- validate-local-tx!
  [repo message local-tx]
  (let [message-type (:type message)]
    (when (contains? #{"hello" "tx/batch/ok" "pull/ok" "changed" "tx/reject"} message-type)
      (let [validate? (and (integer? local-tx) (>= local-tx 0))]
       (when-not validate?
         (throw (ex-info "Invalid local tx"
                         {:repo repo
                          :message-type message-type
                          :local-tx local-tx})))))))

(defn- validate-pull-history!
  [repo local-tx remote-tx txs]
  (require-seq txs {:repo repo :type "pull/ok" :field :txs})
  (let [expected-count (- remote-tx local-tx)
        tx-count (count txs)
        contiguous? (and (integer? remote-tx)
                         (= expected-count tx-count)
                         (every? true?
                                 (map-indexed
                                  (fn [idx tx]
                                    (= (+ local-tx idx 1) (:t tx)))
                                  txs)))]
    (when-not contiguous?
      (fail-fast :db-sync/pull-history-gap
                 {:type :db-sync/pull-history-gap
                  :repo repo
                  :local-tx local-tx
                  :remote-tx remote-tx
                  :expected-first-t (inc local-tx)
                  :expected-count expected-count
                  :actual-first-t (some-> txs first :t)
                  :actual-last-t (some-> txs last :t)
                  :actual-count tx-count}))))

(defn handle-message!
  [repo client raw]
  (let [message (-> raw
                    sync-transport/parse-message
                    sync-transport/coerce-ws-server-message)]
    (when-not (map? message)
      (fail-fast :db-sync/response-parse-failed
                 {:type :db-sync/response-parse-failed
                  :repo repo
                  :payload-bytes
                  (if (string? raw)
                    (sync-transport/encoded-string-byte-length raw)
                    0)}))
    (let [local-tx (client-op/get-local-tx repo)
          remote-tx (:t message)
          checksum-fields (select-keys message
                                       [:checksum
                                        :checksum-version
                                        :server-checksum])]
      (validate-local-tx! repo message local-tx)
      (update-latest-remote-state! repo message)
      (case (:type message)
        "hello" (handle-hello! repo client local-tx remote-tx checksum-fields)
        "online-users" (handle-online-users! repo client message)
        "presence" (handle-presence! client message)
        "tx/batch/ok" (handle-tx-batch-ok! repo client remote-tx checksum-fields)
        "pull/ok" (handle-pull-ok! repo client local-tx remote-tx checksum-fields message)
        "changed" (handle-changed! repo client local-tx remote-tx)
        "tx/reject" (handle-tx-reject! repo client message local-tx)
        "error" (fail-fast :db-sync/server-error
                           {:type :db-sync/server-error
                            :repo repo
                            :message-type "error"})
        "pong" nil
        (fail-fast :db-sync/invalid-field
                   {:repo repo :type (:type message)})))))

(defn- handle-pull-failed!
  [client error]
  (if-let [pull-failed-f (:pull-failed-f client)]
    (pull-failed-f error)
    (do
      (sync-util/set-last-sync-error! client error)
      (clear-pending-pull! client)))
  (p/rejected error))

(defn- handle-pull-ok!
  [repo client local-tx remote-tx checksum-fields message]
  (try
    (cond
      (< remote-tx local-tx)
      (fail-fast :db-sync/server-cursor-regressed
                 {:type :db-sync/server-cursor-regressed
                  :repo repo
                  :message-type "pull/ok"
                  :local-tx local-tx
                  :remote-tx remote-tx})

      (> remote-tx local-tx)
      (let [txs (:txs message)]
        (require-non-negative remote-tx {:repo repo :type "pull/ok"})
        (validate-pull-history! repo local-tx remote-tx txs)
        (let [remote-txs (mapv (fn [data]
                                 {:t (:t data)
                                  :outliner-op (:outliner-op data)
                                  :tx-data (parse-transit (:tx data)
                                                          {:repo repo :type "pull/ok"})})
                               txs)]
          (->
           (p/let [graph-e2ee? (sync-crypt/graph-e2ee? repo)
                   aes-key (sync-crypt/<ensure-graph-aes-key repo (:graph-id client))
                   _ (when (and graph-e2ee? (nil? aes-key))
                       (fail-fast :db-sync/missing-field {:repo repo :field :aes-key}))
                   remote-txs* (if aes-key
                                 (p/all (mapv (fn [{:keys [tx-data] :as remote-tx}]
                                                (p/let [tx-data* (sync-crypt/<decrypt-tx-data aes-key tx-data)]
                                                  (assoc remote-tx :tx-data tx-data*)))
                                              remote-txs))
                                 (p/resolved remote-txs))
                   _ (try
                       (sync-apply/apply-remote-txs! repo client remote-txs*)
                       (catch :default e
                         (log/error ::apply-remote-tx e)
                         (throw e)))]
             (client-op/update-local-tx repo remote-tx)
             (clear-pending-pull! client)
             (broadcast-rtc-state! client)
             (verify-sync-checksum! repo client remote-tx remote-tx checksum-fields {:type "pull/ok"})
             (mark-sync-succeeded! client)
             (sync-apply/enqueue-flush-pending! repo client))
           (p/then (fn [_]
                     (sync-util/clear-last-sync-error! client)))
           (p/catch (fn [error]
                      (if (= :db-sync/remote-apply-deferred
                             (:type (ex-data error)))
                        (do
                          ;; Do not treat ordinary continuous editing as a
                          ;; connection failure. Release the receive queue,
                          ;; preserve the local cursor, and coalesce recovery
                          ;; into a fresh pull after a short quiet period.
                          (clear-pending-pull! client)
                          (schedule-deferred-pull! repo client)
                          (log/info :db-sync/remote-apply-deferred
                                    {:repo repo
                                     :local-tx local-tx
                                     :remote-tx remote-tx})
                          (p/resolved nil))
                        (handle-pull-failed! client error)))))))
      :else
      (do
        (clear-pending-pull! client)
        (mark-sync-succeeded! client)
        (sync-apply/enqueue-flush-pending! repo client)
        (p/resolved nil)))
    (catch :default error
      (handle-pull-failed! client error))))

(defn- handle-changed!
  [repo client local-tx remote-tx]
  (require-non-negative remote-tx {:repo repo :type "changed"})
  (broadcast-rtc-state! client)
  (when (< local-tx remote-tx)
    (request-pull! client local-tx)))
