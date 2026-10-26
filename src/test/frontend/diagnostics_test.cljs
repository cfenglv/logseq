(ns frontend.diagnostics-test
  (:require [cljs.test :refer [deftest is testing]]
            [electron.ipc :as ipc]
            [frontend.components.diagnostics :as diagnostics-ui]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [promesa.core :as p]
            [frontend.diagnostics :as diagnostics]))

(deftest diagnostic-report-selects-fields-and-redacts-known-values
  (with-redefs [config/get-custom-sync-server-url (constantly "https://sync.example.invalid")]
    (let [report (diagnostics/report
                  {:message "Failed at /private/example/file on https://sync.example.invalid; Bearer example-token password=example-password"
                   :stack "synthetic stack" :body "private note" :data {:secret "example-secret"}}
                  {:status :unavailable :sync {:pending-local nil :pending-server 2 :token "example-token"}
                   :repair {:state :waiting :operation-id "internal-operation"}}
                  {:platform "synthetic" :arch "synthetic" :private-values ["/private/example"]})]
      (is (= "Failed at <private>/file on <private>; Bearer <redacted> password=<redacted>"
             (get-in report [:error :message])))
      (is (not (contains? (:error report) :body)))
      (is (not (contains? (:error report) :data)))
      (is (not (contains? (:sync report) :token)))
      (is (nil? (get-in report [:sync :pending-local])))
      (is (= 2 (get-in report [:sync :pending-server])))
      (is (not (contains? (:repair report) :operation-id))))))

(deftest repair-status-needs-a-completion-receipt
  (is (= :idle (diagnostics/repair-state {:operation nil})))
  (is (= :repairing (diagnostics/repair-state {:operation {:disposition :repairing}})))
  (is (= :local-only (diagnostics/repair-state {:operation {:disposition :local-only}})))
  (is (= :waiting (diagnostics/repair-state {:operation {:next-retry-at-ms 100}})))
  (is (= :switching (diagnostics/repair-state {:prepared-swap? true})))
  (is (= :verifying (diagnostics/repair-state {:operation {:target-projection-epoch 2}
                                             :committed {:projection-epoch 2}})))
  (is (= :complete (diagnostics/repair-state {:completed? true}))))

(deftest repair-events-stay-with-the-correct-graph-and-operation
  (let [snapshot {:repair {:state :repairing :operation-id "current" :at 10}}]
    (is (= (:repair snapshot) (diagnostics-ui/latest-repair snapshot {:repo "other" :at 11 :completed? true} "graph")))
    (is (= (:repair snapshot) (diagnostics-ui/latest-repair snapshot {:repo "graph" :at 11 :operation-id "old" :completed? true} "graph")))
    (is (= :complete (:state (diagnostics-ui/latest-repair snapshot {:repo "graph" :at 11 :operation-id "current" :completed? true} "graph"))))
    (is (= "next" (:operation-id (diagnostics-ui/latest-repair snapshot {:repo "graph" :at 12 :operation {:operation-id "next" :disposition :repairing}} "graph")))))
  (is (= :idle (:state (diagnostics-ui/latest-repair
                       {:requested-at 20 :repair {:state :idle :at 20}}
                       {:repo "graph" :at 10 :operation-id "old" :completed? true}
                       "graph"))))
  (testing "Unknown or offline status does not offer a new repair"
    (let [error {:code :db-sync/checksum-mismatch} repair {:state :idle}]
      (is (not (diagnostics-ui/repair-available? {:status :unavailable :sync {:ws-state :open}} repair error)))
      (is (not (diagnostics-ui/repair-available? {:status :ready :sync {:ws-state :closed}} repair error)))
      (is (diagnostics-ui/repair-available? {:status :ready :sync {:ws-state :open}} repair error)))))

(deftest timeout-feedback-has-readable-details
  (is (= (t :bug-report.diagnostics/timed-out)
         (diagnostics-ui/error-text (p/TimeoutException. "Operation timed out.")))))

(deftest closure-error-level-is-forwarded-without-normal-log-traffic
  (let [calls (atom [])
        apis (.-apis js/window)]
    (when-not apis (set! (.-apis js/window) #js {}))
    (try
      (with-redefs [ipc/ipc (fn [& args] (swap! calls conj args) (p/resolved true))]
        (diagnostics/record-renderer-error! {:level :severe :exception (js/Error. "Synthetic logged error")})
        (diagnostics/record-renderer-error! {:level :info :message "Ordinary activity"})
        (is (= 1 (count @calls)))
        (is (= :diagnostics/record-error (ffirst @calls)))
        (is (= "Synthetic logged error" (:message (second (first @calls))))))
      (finally (when-not apis (set! (.-apis js/window) apis))))))
