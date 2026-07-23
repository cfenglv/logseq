(ns logseq.db-sync.worker.routes.sync
  (:require [reitit.core :as r]))

(def ^:private route-data
  [["/health" {:methods {"GET" :sync/health}}]
   ["/pull" {:methods {"GET" :sync/pull}}]
   ["/checksum/diagnostics" {:methods {"GET" :sync/checksum-diagnostics}}]
   ["/snapshot/download" {:methods {"GET" :sync/snapshot-download}}]
   ["/snapshot/stream" {:methods {"GET" :sync/snapshot-stream}}]
   ["/snapshot/download-v2" {:methods {"GET" :sync/snapshot-download-v2
                                       "DELETE" :sync/snapshot-download-v2-cancel}}]
   ["/snapshot/stream-v2" {:methods {"GET" :sync/snapshot-stream-v2}}]
   ["/admin/reset" {:methods {"DELETE" :sync/admin-reset}}]
   ["/tx/batch" {:methods {"POST" :sync/tx-batch}}]
   ["/snapshot/upload" {:methods {"POST" :sync/snapshot-upload}}]
   ["/snapshot/upload-v2" {:methods {"POST" :sync/snapshot-upload-v2}}]])

(def ^:private router
  (r/router route-data))

(defn match-route [method path]
  (when-let [match (r/match-by-path router path)]
    (when-let [handler (get-in match [:data :methods method])]
      (assoc match :handler handler))))
