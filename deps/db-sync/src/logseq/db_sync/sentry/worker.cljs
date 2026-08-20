(ns logseq.db-sync.sentry.worker
  (:require ["@sentry/cloudflare" :as sentry]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.sentry :as sentry-config]))

(defn wrap-handler [handler]
  (sentry/withSentry (fn [^js env]
                       (clj->js (or (sentry-config/options-from-env env) {})))
                     handler))

(defn capture-exception! [error]
  (let [code (:error-code (common/error-log-data error))
        safe-error (js/Error. (str "db-sync/"
                                   (if (keyword? code)
                                     (name code)
                                     "exception")))]
    (set! (.-name safe-error) "DbSyncError")
    (sentry/captureException safe-error)))
