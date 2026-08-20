(ns frontend.handler.db-based.rtc-background-tasks-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.handler.db-based.rtc-background-tasks :as rtc-background-tasks]
            [promesa.core :as p]))

(deftest guarded-rtc-background-action-survives-sync-and-async-failures-test
  (async done
         (let [sync-error (ex-info "sync failure" {:code :sync-failure})
               async-error (ex-info "async failure" {:code :async-failure})]
           (->
            (p/let [sync-result
                    (#'rtc-background-tasks/<guard-rtc-action
                     {:operation :test-sync}
                     #(throw sync-error))
                    async-result
                    (#'rtc-background-tasks/<guard-rtc-action
                     {:operation :test-async}
                     #(p/rejected async-error))
                    nil-rejection-result
                    (#'rtc-background-tasks/<guard-rtc-action
                     {:operation :test-nil-rejection}
                     #(p/rejected nil))
                    success-result
                    (#'rtc-background-tasks/<guard-rtc-action
                     {:operation :test-success}
                     #(p/resolved :ok))]
              (is (nil? sync-result)
                  "a synchronous action failure must not terminate the supervisor")
              (is (nil? async-result)
                  "a rejected action must not terminate the supervisor")
              (is (nil? nil-rejection-result)
                  "an unstructured rejection must not break the failure guard")
              (is (= :ok success-result)
                  "successful actions must preserve their result"))
            (p/catch
             (fn [error]
               (is false (str "unexpected guard failure: " error))))
            (p/finally done)))))
