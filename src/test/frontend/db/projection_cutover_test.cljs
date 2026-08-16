(ns frontend.db.projection-cutover-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.db.projection-cutover :as projection-cutover]
            [frontend.db.subs :as db-subs]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.state :as state]
            [promesa.core :as p]))

(def ^:private repo "projection-cutover-test")

(deftest future-commit-resets-once-and-throwing-plugin-does-not-block-test
  (let [hook-calls (atom [])]
    (db-subs/reset-graph! repo 2)
    (with-redefs [state/get-current-repo (constantly repo)
                  plugin-handler/hook-plugin-app
                  (fn [& args]
                    (swap! hook-calls conj args)
                    (throw (js/Error. "plugin failed")))]
      (is (true? (projection-cutover/apply-committed!
                  {:repo repo :projection-epoch 4})))
      (is (= {:graph-id repo :projection-epoch 4}
             (db-subs/projection-context)))
      (is (nil? (projection-cutover/apply-committed!
                 {:repo repo :projection-epoch 3})))
      (is (= 1 (count @hook-calls))))))

(deftest focus-probe-is-single-flight-and-advances-only-current-graph-test
  (async done
         (let [epoch-result (p/deferred)
               calls (atom [])
               ready-before @state/db-worker-ready?]
           (db-subs/reset-graph! repo 1)
           (projection-cutover/reset-probe-for-test!)
           (-> (p/with-redefs
                 [state/get-current-repo (constantly repo)
                  state/<invoke-db-worker
                  (fn [& args]
                    (if (= :thread-api/get-projection-epoch (first args))
                      (do
                        (swap! calls conj args)
                        epoch-result)
                      (p/resolved nil)))
                  plugin-handler/hook-plugin-app (fn [& _] nil)]
                 (reset! state/db-worker-ready? true)
                 (let [first-probe (projection-cutover/<probe-current!)
                       second-probe (projection-cutover/<probe-current!)]
                   (p/resolve! epoch-result 2)
                   (p/all [first-probe second-probe])))
               (p/then
                (fn [_]
                  (is (= [[:thread-api/get-projection-epoch repo]] @calls))
                  (is (= {:graph-id repo :projection-epoch 2}
                         (db-subs/projection-context)))))
               (p/catch #(is false (str %)))
               (p/finally
                (fn []
                  (reset! state/db-worker-ready? ready-before)
                  (projection-cutover/reset-probe-for-test!)
                  (done)))))))
