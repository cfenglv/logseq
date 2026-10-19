(ns electron.updater-install-test
  (:require [cljs.test :refer [async deftest is]]
            [electron.updater-install :as updater-install]
            [promesa.core :as p]))

(defn- scenario
  [failure]
  (let [events (atom [])
        token {:attempt-id "attempt-1"}]
    {:events events
     :result
     (updater-install/run-install!
      {:preflight! (fn []
                     (swap! events conj :preflight)
                     (if (= failure :preflight)
                       (p/rejected (ex-info "bad target" {:code :bad-target}))
                       (p/resolved true)))
       :begin-quiesce! (fn []
                         (swap! events conj :begin)
                         (p/resolved token))
       :stop-active! (fn [actual-token]
                       (swap! events conj [:stop actual-token])
                       (p/resolved true))
       :handoff! (fn []
                   (swap! events conj :handoff)
                   (if (= failure :handoff)
                     (p/rejected (ex-info "handoff failed" {:code :handoff-failed}))
                     (p/resolved true)))
       :commit-quiesce! (fn [actual-token]
                          (swap! events conj [:commit actual-token]))
       :resume-quiesce! (fn [actual-token]
                          (swap! events conj [:resume actual-token])
                          (p/resolved true))
       :set-dirty! (fn [dirty?]
                     (swap! events conj [:dirty dirty?]))
       :restart! (fn []
                   (swap! events conj :restart))
       :emit-error! (fn [error]
                      (swap! events conj [:error (:code (ex-data error))]))})}))

(deftest successful-install-handoff-follows-the-single-authority-order
  (async done
    (let [{:keys [events result]} (scenario nil)]
      (-> result
          (p/then
           (fn [installed?]
             (is (true? installed?))
             (is (= [:preflight
                     :begin
                     [:stop {:attempt-id "attempt-1"}]
                     :handoff
                     [:dirty false]
                     [:commit {:attempt-id "attempt-1"}]]
                    @events))))
          (p/catch (fn [error]
                     (is false (str "unexpected error: " error))))
          (p/finally done)))))

(deftest preflight-failure-does-not-close-the-runtime-gate
  (async done
    (let [{:keys [events result]} (scenario :preflight)]
      (-> result
          (p/then
           (fn [installed?]
             (is (false? installed?))
             (is (= [:preflight [:error :bad-target]] @events))))
          (p/catch (fn [error]
                     (is false (str "unexpected error: " error))))
          (p/finally done)))))

(deftest handoff-failure-restores-the-captured-runtime-before-reporting
  (async done
    (let [{:keys [events result]} (scenario :handoff)]
      (-> result
          (p/then
           (fn [installed?]
             (is (false? installed?))
             (is (= [:preflight
                     :begin
                     [:stop {:attempt-id "attempt-1"}]
                     :handoff
                     [:resume {:attempt-id "attempt-1"}]
                     [:dirty true]
                     [:error :handoff-failed]]
                    @events))))
          (p/catch (fn [error]
                     (is false (str "unexpected error: " error))))
          (p/finally done)))))
