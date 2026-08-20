(ns electron.db-worker
  (:require ["os" :as os]
            ["path" :as node-path]
            [electron.home :as home]
            [logseq.cli.server :as cli-server]
            [logseq.common.graph-dir :as graph-dir]
            [logseq.db-worker.daemon :as daemon]
            [promesa.core :as p]))

(defn- initial-state
  []
  {:repos {}
   :window->repo {}})

(defn- repo-key
  [repo]
  (graph-dir/repo-identity repo))

(defn- merge-repo-entry
  [existing entry]
  (if existing
    (-> existing
        (update :windows (fnil into #{}) (:windows entry))
        (update :runtime #(or % (:runtime entry))))
    entry))

(defn- normalize-state
  [state]
  (let [state (merge (initial-state) state)
        repos (reduce-kv (fn [m repo entry]
                           (if-let [key (repo-key repo)]
                             (update m key merge-repo-entry entry)
                             m))
                         {}
                         (:repos state))
        window->repo (reduce-kv (fn [m window-id repo]
                                  (if-let [key (repo-key repo)]
                                    (assoc m window-id key)
                                    m))
                                {}
                                (:window->repo state))]
    (assoc state
           :repos repos
           :window->repo window->repo)))

(defn- ensure-state
  [state]
  (normalize-state state))

(defn- dissoc-window
  [state window-id]
  (update state :window->repo dissoc window-id))

(defn- detach-window
  [state window-id]
  (let [state (ensure-state state)
        repo (get-in state [:window->repo window-id])]
    (if-not repo
      [state nil]
      (let [entry (get-in state [:repos repo])]
        (if-not entry
          [(dissoc-window state window-id) nil]
          (let [remaining (disj (:windows entry) window-id)
                state' (cond-> (dissoc-window state window-id)
                         (seq remaining)
                         (assoc-in [:repos repo :windows] remaining)

                         (empty? remaining)
                         (update :repos dissoc repo))]
            [state' (when (empty? remaining) (:runtime entry))]))))))

(defn- detach-window-from-repo
  [state repo window-id]
  (let [state (ensure-state state)
        entry (get-in state [:repos repo])]
    (if-not entry
      [state nil]
      (let [remaining (disj (:windows entry) window-id)
            state' (cond-> state
                     (= repo (get-in state [:window->repo window-id]))
                     (update :window->repo dissoc window-id)

                     (seq remaining)
                     (assoc-in [:repos repo :windows] remaining)

                     (empty? remaining)
                     (update :repos dissoc repo))]
        [state' (when (empty? remaining) (:runtime entry))]))))

(defn- detach-repo
  [state repo]
  (let [state (ensure-state state)
        entry (get-in state [:repos repo])]
    (if-not entry
      [state nil]
      (let [windows (or (:windows entry) #{})
            state' (-> state
                       (update :repos dissoc repo)
                       (update :window->repo
                               (fn [window->repo]
                                 (reduce (fn [m window-id]
                                           (if (= repo (get m window-id))
                                             (dissoc m window-id)
                                             m))
                                         window->repo
                                         windows))))]
        [state' (:runtime entry)]))))

(defn create-manager
  [{:keys [start-daemon! stop-daemon! runtime-ready?] :as deps}]
  {:deps deps
   :start-daemon! start-daemon!
   :stop-daemon! stop-daemon!
   :runtime-ready? (or runtime-ready? (fn [_runtime] (p/resolved true)))
   :claim-state (atom {:open? true :inflight 0 :waiters []})
   :install-attempt (atom nil)
   :state (atom (initial-state))})

(defn- begin-runtime-claim!
  [{:keys [claim-state]}]
  (let [accepted? (atom false)]
    (swap! claim-state
           (fn [state]
             (if (:open? state)
               (do
                 (reset! accepted? true)
                 (update state :inflight inc))
               state)))
    (when-not @accepted?
      (throw (ex-info "db-worker runtime claims are quiescing for update"
                      {:code :updater-quiescing})))
    true))

(defn- end-runtime-claim!
  [{:keys [claim-state]}]
  (let [ready-waiters (atom [])]
    (swap! claim-state
           (fn [state]
             (let [next-inflight (dec (:inflight state))]
               (when (neg? next-inflight)
                 (throw (ex-info "db-worker runtime claim count underflow"
                                 {:code :runtime-claim-underflow})))
               (if (zero? next-inflight)
                 (do
                   (reset! ready-waiters (:waiters state))
                   (assoc state :inflight 0 :waiters []))
                 (assoc state :inflight next-inflight)))))
    (doseq [resolve @ready-waiters]
      (resolve true))))

(defn- owned-runtime?
  [runtime]
  (not= false (:owned? runtime)))

(defn ensure-window-stopped!
  [{:keys [state stop-daemon!]} window-id]
  (let [runtime* (atom nil)]
    (swap! state
           (fn [current]
             (let [[next-state runtime] (detach-window current window-id)]
               (reset! runtime* runtime)
               next-state)))
    (if-let [runtime @runtime*]
      (if (owned-runtime? runtime)
        (p/let [_ (stop-daemon! runtime)]
          true)
        (p/resolved true))
      (p/resolved false))))

(defn- ensure-started-without-claim!
  [{:keys [state start-daemon! stop-daemon! runtime-ready?] :as manager} repo window-id]
  (let [key (repo-key repo)]
    (p/let [current-repo (get-in (ensure-state @state) [:window->repo window-id])
            _ (when (and current-repo (not= current-repo key))
                (ensure-window-stopped! manager window-id))]
      (if-let [entry (get-in (ensure-state @state) [:repos key])]
        (p/let [runtime (:runtime entry)
                ready? (runtime-ready? runtime)]
          (if ready?
            (do
              (swap! state (fn [current]
                             (-> (ensure-state current)
                                 (update-in [:repos key :windows] (fnil conj #{}) window-id)
                                 (assoc-in [:window->repo window-id] key))))
              runtime)
            (p/let [_ (when (owned-runtime? runtime)
                        (-> (stop-daemon! runtime)
                            (p/catch (fn [_] nil))))
                    runtime' (start-daemon! repo)]
              (swap! state
                     (fn [current]
                       (let [current' (ensure-state current)
                             windows (get-in current' [:repos key :windows] #{})]
                         (-> current'
                             (assoc-in [:repos key] {:runtime runtime'
                                                     :windows (conj windows window-id)})
                             (assoc-in [:window->repo window-id] key)))))
              runtime')))
        (p/let [runtime (start-daemon! repo)]
          (swap! state (fn [current]
                         (-> (ensure-state current)
                             (assoc-in [:repos key] {:runtime runtime
                                                     :windows #{window-id}})
                             (assoc-in [:window->repo window-id] key))))
          runtime)))))

(defn ensure-started!
  [manager repo window-id]
  (try
    (begin-runtime-claim! manager)
    (-> (ensure-started-without-claim! manager repo window-id)
        (p/finally (fn [] (end-runtime-claim! manager))))
    (catch :default error
      (p/rejected error))))

(defn- parse-runtime-lock
  [{:keys [base-url]}]
  (when (seq base-url)
    (try
      (let [^js parsed-url (js/URL. base-url)
            host (.-hostname parsed-url)
            port-str (.-port parsed-url)
            port (js/parseInt port-str 10)]
        (when (and (seq host) (number? port) (pos-int? port))
          {:host host
           :port port}))
      (catch :default _
        nil))))

(defn- runtime-ready-default?
  [runtime]
  (if-let [lock (parse-runtime-lock runtime)]
    (daemon/ready? lock)
    (p/resolved false)))

(defn ensure-stopped!
  [{:keys [state stop-daemon!]} repo window-id]
  (let [key (repo-key repo)]
    (if (= key (get-in (ensure-state @state) [:window->repo window-id]))
      (ensure-window-stopped! {:state state :stop-daemon! stop-daemon!} window-id)
      (let [runtime* (atom nil)]
        (swap! state
               (fn [current]
                 (let [[next-state runtime] (detach-window-from-repo current key window-id)]
                   (reset! runtime* runtime)
                   next-state)))
        (if-let [runtime @runtime*]
          (if (owned-runtime? runtime)
            (p/let [_ (stop-daemon! runtime)]
              true)
            (p/resolved true))
          (p/resolved false))))))

(defn stop-all!
  [{:keys [state stop-daemon!]}]
  (let [entries (:repos (ensure-state @state))]
    (-> (p/all (map (fn [[repo {:keys [runtime]}]]
                      (-> (if (owned-runtime? runtime)
                            (stop-daemon! runtime)
                            (p/resolved true))
                          (p/then (fn [ok?] [repo (true? ok?) nil]))
                          (p/catch (fn [error] [repo false error]))))
                    entries))
        (p/then
         (fn [results]
           (let [failed (->> results (remove second) (map first) set)
                 stopped (->> results (filter second) (map first) set)]
             (swap! state
                    (fn [current]
                      (-> (ensure-state current)
                          (update :repos #(apply dissoc % stopped))
                          (update :window->repo
                                  (fn [window->repo]
                                    (into {}
                                          (filter (fn [[_ repo]] (contains? failed repo)))
                                          window->repo))))))
             (if (seq failed)
               (p/rejected
                (ex-info "failed to stop all db-worker runtimes"
                         {:code :db-worker-stop-failed
                          :repos (vec failed)}))
               true)))))))

(defn update-quiescing?
  [{:keys [claim-state]}]
  (not (:open? @claim-state)))

(defn- close-runtime-claim-gate!
  [{:keys [claim-state]}]
  (js/Promise.
   (fn [resolve reject]
     (let [accepted? (atom false)
           ready? (atom false)]
       (swap! claim-state
              (fn [state]
                (if (:open? state)
                  (do
                    (reset! accepted? true)
                    (if (zero? (:inflight state))
                      (do
                        (reset! ready? true)
                        (assoc state :open? false))
                      (-> state
                          (assoc :open? false)
                          (update :waiters conj resolve))))
                  state)))
       (cond
         (not @accepted?)
         (reject (ex-info "db-worker runtime claim gate is already closed"
                          {:code :updater-quiescing}))

         @ready?
         (resolve true))))))

(defn- active-owner-token
  [state]
  (let [state (ensure-state state)]
    {:attempt-id (str (random-uuid))
     :active-repo-identities (-> state :repos keys sort vec)
     :window-to-repo-ownership (:window->repo state)}))

(defn begin-update-quiesce!
  [{:keys [state install-attempt] :as manager}]
  (when @install-attempt
    (throw (ex-info "an updater quiesce attempt is already active"
                    {:code :updater-quiescing})))
  (-> (close-runtime-claim-gate! manager)
      (p/then
       (fn [_]
         (let [token (active-owner-token @state)]
           (reset! install-attempt {:token token :phase :captured})
           token)))))

(defn- require-active-attempt!
  [{:keys [install-attempt]} token]
  (let [attempt @install-attempt]
    (when-not (= (:attempt-id token) (get-in attempt [:token :attempt-id]))
      (throw (ex-info "updater quiesce token is missing, stale, or consumed"
                      {:code :invalid-updater-quiesce-token})))
    attempt))

(defn stop-active-for-update!
  [{:keys [install-attempt] :as manager} token]
  (require-active-attempt! manager token)
  (swap! install-attempt assoc :phase :stopping)
  (-> (stop-all! manager)
      (p/then (fn [result]
                (swap! install-attempt assoc :phase :stopped)
                result))
      (p/catch (fn [error]
                 (swap! install-attempt assoc :phase :stopped)
                 (p/rejected error)))))

(defn resume-update-quiesce!
  [{:keys [claim-state install-attempt] :as manager} token]
  (let [{:keys [phase]} (require-active-attempt! manager token)
        restore! (fn []
                   (p/all
                    (map (fn [[window-id repo]]
                           (ensure-started-without-claim! manager repo window-id))
                         (:window-to-repo-ownership token))))]
    (-> (if (= phase :captured)
          (p/resolved true)
          (restore!))
        (p/then
         (fn [_]
           (reset! install-attempt nil)
           (swap! claim-state assoc :open? true :waiters [])
           true)))))

(defn commit-update-quiesce!
  [{:keys [install-attempt] :as manager} token]
  (require-active-attempt! manager token)
  (reset! install-attempt nil)
  true)

(defn ensure-repo-stopped!
  [{:keys [state stop-daemon!]} repo]
  (let [key (repo-key repo)
        runtime* (atom nil)]
    (swap! state
           (fn [current]
             (let [[next-state runtime] (detach-repo current key)]
               (reset! runtime* runtime)
               next-state)))
    (if-let [runtime @runtime*]
      (if (owned-runtime? runtime)
        (p/let [_ (stop-daemon! runtime)]
          true)
        (p/resolved true))
      (p/resolved false))))

(defonce ^:private *runtime-opts (atom {}))

(defn- managed-root-dir
  []
  (node-path/join
   (home/resolve-root (.-LOGSEQ_TEST_HOME_DIR js/process.env)
                      (.homedir os))
   "logseq"))

(defn- start-managed-daemon!
  [repo]
  (let [runtime-config (merge {:owner-source :electron
                               :root-dir (managed-root-dir)}
                              @*runtime-opts)]
    (p/let [_ (when (seq (:embedding-endpoint runtime-config))
                (-> (cli-server/stop-server! runtime-config repo)
                    (p/catch (fn [_] nil))))
            config (cli-server/ensure-server! runtime-config repo)]
      {:repo repo
       :base-url (:base-url config)
       :root-dir (:root-dir runtime-config)
       :auth-token nil
       :owned? (:owned? config)})))

(defn- stop-managed-daemon!
  [{:keys [repo root-dir]}]
  (p/let [result (cli-server/stop-server! {:owner-source :electron
                                           :root-dir (or root-dir
                                                         (managed-root-dir))}
                                          repo)]
    (:ok? result)))

(defonce manager
  (create-manager
   {:start-daemon! start-managed-daemon!
    :stop-daemon! stop-managed-daemon!
    :runtime-ready? runtime-ready-default?}))

(defn ensure-runtime!
  ([repo window-id]
   (ensure-started! manager repo window-id))
  ([repo window-id opts]
   (reset! *runtime-opts opts)
   (ensure-started! manager repo window-id)))

(defn release-window!
  [window-id]
  (ensure-window-stopped! manager window-id))

(defn release-runtime!
  ([repo window-id]
   (release-runtime! manager repo window-id))
  ([mgr repo window-id]
   (ensure-stopped! mgr repo window-id)))

(defn release-repo!
  [repo]
  (ensure-repo-stopped! manager repo))

(defn stop-all-managed!
  []
  (stop-all! manager))
