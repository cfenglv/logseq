(ns frontend.worker.sync.presence
  "Presence and rtc state helpers for db sync."
  (:require [logseq.common.util :as common-util]
            [logseq.db-sync.checksum :as sync-checksum]
            [frontend.worker.state :as worker-state]))

(defn current-client
  [db-sync-client repo]
  (let [client @db-sync-client]
    (when (= repo (:repo client))
      client)))

(defn client-ops-conn
  [get-client-ops-conn repo]
  (get-client-ops-conn repo))

(defn sync-counts
  [{:keys [get-datascript-conn
           get-pending-local-tx-count
           get-unpushed-asset-ops-count
           get-missing-asset-upload-files
           get-local-tx
           get-local-checksum
           get-local-server-checksum
           get-graph-uuid
           latest-remote-tx
           latest-remote-checksum
           latest-remote-checksum-version]}
   repo]
  (when (get-datascript-conn repo)
    (let [pending-local (if get-pending-local-tx-count
                          (get-pending-local-tx-count repo)
                          0)
          pending-asset (get-unpushed-asset-ops-count repo)
          missing-asset-upload-files (if get-missing-asset-upload-files
                                       (get-missing-asset-upload-files repo)
                                       [])
          local-tx (get-local-tx repo)
          remote-tx (get latest-remote-tx repo)
          remote-checksum-version (get latest-remote-checksum-version repo)
          local-checksum
          (if (and (= sync-checksum/server-checksum-version
                      remote-checksum-version)
                   get-local-server-checksum)
            (get-local-server-checksum repo)
            (when get-local-checksum
              (get-local-checksum repo)))
          remote-checksum (get latest-remote-checksum repo)
          pending-server (when (and (number? local-tx) (number? remote-tx))
                           (max 0 (- remote-tx local-tx)))
          graph-uuid (get-graph-uuid repo)
          client (current-client worker-state/*db-sync-client repo)
          ws-url (:ws-url @worker-state/*db-sync-config)
          ws-state (or (some-> client :ws-state deref)
                       (if (seq ws-url) :stopped :inactive))
          sync-ready? (true? (some-> client :sync-ready? deref))
          last-error (some-> client :last-sync-error deref)]
      {:repo repo
       :graph-id graph-uuid
       :pending-local pending-local
       :pending-asset pending-asset
       :missing-asset-upload-files missing-asset-upload-files
       :pending-server pending-server
       :local-tx local-tx
       :remote-tx remote-tx
       :local-checksum local-checksum
       :remote-checksum remote-checksum
       :ws-state ws-state
       :sync-ready? sync-ready?
       :last-error last-error})))

(defn normalize-online-users
  [users]
  (->> users
       (keep (fn [{:keys [user-id email username name]}]
               (when (string? user-id)
                 (let [display-name (or username name user-id)]
                   (cond-> {:user/uuid user-id
                            :user/name display-name}
                     (string? email) (assoc :user/email email))))))
       (common-util/distinct-by :user/uuid)
       (vec)))

(defn rtc-state-payload
  [sync-counts-f client]
  (let [repo (:repo client)
        ws-state @(:ws-state client)
        sync-ready? (true? (some-> client :sync-ready? deref))
        ;; A client can be quarantined while an older worker generation is
        ;; still being upgraded. Missing optional presence state must never
        ;; prevent publishing the authoritative non-ready RTC state.
        online-users (or (some-> client :online-users deref) [])
        {:keys [pending-local pending-asset missing-asset-upload-files pending-server
                local-tx remote-tx local-checksum remote-checksum graph-id
                last-error]}
        (sync-counts-f repo)]
    {:rtc-state {:ws-state ws-state}
     ;; An open transport is not a synchronization proof. Only publish the
     ;; healthy lock after hello/pull/checksum convergence has marked this
     ;; exact client generation ready.
     :rtc-lock (and (= :open ws-state) sync-ready?)
     :online-users (or online-users [])
     ;; Unknown sidecar/cursor state must remain unknown. Presenting nil as 0
     ;; created the false "nothing pending" state after failed activation.
     :unpushed-block-update-count pending-local
     :pending-asset-ops-count pending-asset
     :missing-asset-upload-files (or missing-asset-upload-files [])
     :missing-asset-upload-files-count (count missing-asset-upload-files)
     :pending-server-ops-count pending-server
     :local-tx local-tx
     :remote-tx remote-tx
     :local-checksum local-checksum
     :remote-checksum remote-checksum
     :sync-ready? sync-ready?
     :last-sync-error last-error
     :graph-uuid graph-id}))

(defn set-ws-state!
  [broadcast-f client ws-state]
  (when-let [*ws-state (:ws-state client)]
    (reset! *ws-state ws-state)
    (broadcast-f client)))

(defn update-online-users!
  [broadcast-f client users]
  (when-let [*online-users (:online-users client)]
    (let [users' (normalize-online-users users)]
      (when (not= users' @*online-users)
        (reset! *online-users users')
        (broadcast-f client)))))

(defn update-user-presence!
  [broadcast-f client user-id* editing-block-uuid]
  (when (and user-id* editing-block-uuid)
    (when-let [*online-users (:online-users client)]
      (swap! *online-users
             (fn [users]
               (mapv (fn [user]
                       (if (= user-id* (:user/uuid user))
                         (assoc user :user/editing-block-uuid editing-block-uuid)
                         user))
                     users)))
      (broadcast-f client))))
