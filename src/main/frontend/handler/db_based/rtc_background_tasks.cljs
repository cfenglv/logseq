(ns frontend.handler.db-based.rtc-background-tasks
  "Background tasks related to RTC"
  (:require [cljs-time.core :as t]
            [frontend.common.missionary :as c.m]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.handler.db-based.rtc-flows :as rtc-flows]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [missionary.core :as m]
            [promesa.core :as p]))

(defn- run-background-task-when-not-publishing
  [key' task]
  (when-not config/publishing?
    (c.m/run-background-task key' task)))

(defn- log-rtc-action-failure!
  [{:keys [operation repo start-reason]} error]
  (let [error-data (ex-data error)]
    (log/error :db-sync/background-action-failed
               (cond-> {:operation operation
                        :error-code (or (:code error-data)
                                        (:type error-data)
                                        :unexpected)
                        :error-name (or (some-> error .-name) "Error")}
                 (some? repo) (assoc :repo repo)
                 (some? start-reason) (assoc :start-reason start-reason)))))

(defn- <guard-rtc-action
  "Resolve an individual RTC background action even when it fails.

  A transient auth, worker, or network failure must not terminate the
  long-lived Missionary supervisor that receives future resume events."
  [context action-f]
  (try
    (-> (action-f)
        (p/catch (fn [error]
                   (log-rtc-action-failure! context error)
                   nil)))
    (catch :default error
      (log-rtc-action-failure! context error)
      (p/resolved nil))))

(run-background-task-when-not-publishing
 ;; try to restart rtc-loop when possible,
 ;; triggered by `rtc-flows/rtc-try-restart-flow`
 ::restart-rtc-to-reconnect
 (m/reduce
  (constantly nil)
  (m/ap
    (let [{:keys [graph-uuid t]} (m/?> rtc-flows/rtc-try-restart-flow)]
      (when (and graph-uuid t
                 (= graph-uuid (ldb/get-graph-rtc-uuid (db/get-db)))
                 (> 5000 (- (common-util/time-ms) t)))
        (log/info :trying-to-restart-rtc graph-uuid :t (t/now))
        (let [repo (state/get-current-repo)]
          (c.m/<?
           (<guard-rtc-action
            {:operation :timeout-restart :repo repo}
            #(rtc-handler/<rtc-start! repo :stop-before-start? false)))))))))

(run-background-task-when-not-publishing
 ;; stop rtc when [user-logout]
 ::stop-rtc-if-needed
 (m/reduce
  (constantly nil)
  (m/ap
    (m/?> rtc-flows/logout-flow)
    (log/info :try-to-stop-rtc-if-needed :logout)
    (c.m/<?
     (<guard-rtc-action
      {:operation :logout-stop}
      rtc-handler/<rtc-stop!)))))

(run-background-task-when-not-publishing
 ;; auto-start rtc when [user-login graph-switch]
 ::auto-start-rtc-if-possible
 (m/reduce
  (constantly nil)
  (m/ap
    (let [[start-reason repo] (m/?> rtc-flows/trigger-start-rtc-flow)]
      (log/info :try-to-start-rtc [start-reason repo])
      (let [repo (or repo (state/get-current-repo))]
        (c.m/<?
         (<guard-rtc-action
          {:operation :auto-start
           :repo repo
           :start-reason start-reason}
          #(rtc-handler/<rtc-start-from-trigger! start-reason repo))))))))
