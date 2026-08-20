(ns logseq.e2e.rtc-marker-durability-contract-test
  (:require [clojure.test :refer [deftest is]]
            [logseq.e2e.api :as api]
            [logseq.e2e.rtc-extra-part2-test]))

(def ^:private stress-ns 'logseq.e2e.rtc-extra-part2-test)

(defn- stress-var
  [symbol]
  (or (ns-resolve stress-ns symbol)
      (throw (ex-info "missing stress helper" {:symbol symbol}))))

(def ^:private marker-title "sync-trigger-non-durable")
(def ^:private marker-uuid "12345678-1234-4234-8234-123456789abc")
(def ^:private other-uuid "87654321-4321-4321-8321-cba987654321")

(defn- invoke-marker-write
  [read-result]
  (let [calls (atom [])
        outcome
        (with-redefs [api/ls-api-call!
                      (fn [operation & args]
                        (swap! calls conj [operation (vec args)])
                        (case operation
                          :editor.appendBlockInPage {"uuid" marker-uuid}
                          :editor.getBlock (if (instance? Throwable read-result)
                                             (throw read-result)
                                             read-result)))]
          (try
            {:result ((deref (stress-var 'append-barrier-marker!)) marker-title)}
            (catch Throwable error
              {:thrown error})))]
    (assoc outcome :calls @calls)))

(deftest marker-read-back-fails-closed
  (doseq [read-result [nil
                       {}
                       {"uuid" nil "title" marker-title}
                       {"uuid" "not-a-uuid" "title" marker-title}
                       {"uuid" other-uuid "title" marker-title}
                       {"uuid" marker-uuid "title" "wrong-title"}]]
    (let [{:keys [calls thrown]} (invoke-marker-write read-result)]
      (is (some? thrown)
          (str "barrier accepted non-durable marker " (pr-str read-result)))
      (is (= [[:editor.appendBlockInPage [marker-title]]
              [:editor.getBlock [marker-uuid]]]
             calls)))))

(deftest exact-marker-read-back-is-accepted
  (let [{:keys [calls result thrown]}
        (invoke-marker-write {"uuid" marker-uuid "title" marker-title})]
    (is (nil? thrown))
    (is (= marker-uuid (get result "uuid")))
    (is (= [[:editor.appendBlockInPage [marker-title]]
            [:editor.getBlock [marker-uuid]]]
           calls))))

(deftest marker-read-api-errors-preserve-identity
  (let [failure (ex-info "read failed" {:phase :read-back})
        {:keys [thrown]} (invoke-marker-write failure)]
    (is (identical? failure thrown))))
