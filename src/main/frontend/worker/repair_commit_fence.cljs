(ns frontend.worker.repair-commit-fence
  "One short-lived in-memory gate around a canonical repair rename.

  This is not a scheduler or durable repair state. It drains calls already in
  the elected Worker and holds later calls on one promise until db-core reopens
  the canonical graph."
  (:require [promesa.core :as p]))

(defonce ^:private *active-calls (atom {}))
(defonce ^:private *fences (atom {}))

(defn- release-active-call!
  [repo call-token]
  (let [remaining (get (swap! *active-calls
                              (fn [calls]
                                (let [next-tokens (disj (get calls repo #{}) call-token)]
                                  (if (seq next-tokens)
                                    (assoc calls repo next-tokens)
                                    (dissoc calls repo)))))
                       repo)]
    (when (empty? remaining)
      (when-let [drained (get-in @*fences [repo :drained])]
        (p/resolve! drained true)))))

(defn- <acquire-active-call!
  [repo]
  (if-let [release-promise (get-in @*fences [repo :release-promise])]
    (p/then release-promise #(<acquire-active-call! repo))
    (let [call-token (js-obj)]
      (swap! *active-calls update repo (fnil conj #{}) call-token)
      (p/resolved call-token))))

(defn <with-repo-call!
  [repo f]
  (if-not (string? repo)
    (f)
    (p/let [call-token (<acquire-active-call! repo)]
      (-> (try
            (p/let [result (f)] result)
            (catch :default error
              (p/rejected error)))
          (p/finally #(release-active-call! repo call-token))))))

(defn <enter!
  [repo operation-id]
  (when (get @*fences repo)
    (throw (ex-info "Repair commit fence is already active"
                    {:type :selfhost6/repair-commit-fence-active
                     :repo repo})))
  (let [owner-token (js-obj)
        drained (p/deferred)
        release (p/deferred)
        state {:operation-id operation-id
               :owner-token owner-token
               :drained drained
               :release-promise release}]
    (swap! *fences assoc repo state)
    (when (empty? (get @*active-calls repo))
      (p/resolve! drained true))
    (p/then drained (constantly owner-token))))

(defn owner?
  [repo owner-token]
  (identical? owner-token (get-in @*fences [repo :owner-token])))

(defn release!
  [repo owner-token]
  (let [state (get @*fences repo)]
    (when-not (and state (identical? owner-token (:owner-token state)))
      (throw (ex-info "Repair commit fence owner changed"
                      {:type :selfhost6/repair-commit-fence-owner-mismatch
                       :repo repo})))
    (swap! *fences dissoc repo)
    (p/resolve! (:release-promise state) true)
    nil))

(defn reset-for-test!
  []
  (doseq [[_repo state] @*fences]
    (p/resolve! (:release-promise state) true))
  (reset! *fences {})
  (reset! *active-calls {}))
