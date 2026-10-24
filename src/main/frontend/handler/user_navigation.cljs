(ns frontend.handler.user-navigation
  "One private original RTC handle, with a derived snapshot for the working graph."
  (:require [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.graph :as graph-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.user :as user-handler]
            [frontend.handler.worker :as worker-handler]
            [frontend.persist-db :as persist-db]
            [frontend.persist-db.browser :as browser]
            [frontend.persist-db.remote :as remote]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.common.user-navigation :as navigation]
            [promesa.core :as p]))

(defonce ^:private *handle (atom nil))
(defonce ^:private *session (atom nil))
(defonce ^:private *close-listener (atom nil))

(defn enabled? []
  (and (not config/publishing?)
       (or config/db-sync-local? (config/get-custom-sync-server-url))))

(defn- context-key []
  (when (and (enabled?) (state/get-current-repo) @state/db-worker-ready?
             (not (state/get-state :db/restoring?)))
    [(config/db-sync-http-base) (user-handler/user-uuid) (state/get-current-repo)
     (when (util/electron?) (get-in @persist-db/remote-db [:client :root-dir]))
     @state/*db-worker (state/get-state :user-navigation/source-identity)]))

(defn- current? [session]
  (and (identical? session @*session) (= (:key session) (context-key))))

(defn- install-snapshot! [session snapshot]
  (when (current? session)
    (state/set-state! :user-navigation/snapshot (assoc snapshot :repo (nth (:key session) 2)))))

(defn- report-save-error! [error]
  (notification/show! (t :storage/navigation-save-error) :error)
  (state/pub-event! [:capture-error {:error error :payload {:type :user-navigation}}]))

(defn- refresh-snapshot! [handle]
  (let [session @*session
        scope (get-in @(:context session) [:source :remote-scope])]
    (when (and scope (identical? handle (:handle @(:context session))) (current? session))
      (-> ((:invoke handle) :thread-api/user-navigation-snapshot (:repo handle) scope)
          (p/then #(install-snapshot! session %))
          (p/catch report-save-error!)))))

(defn- handle-event! [handle type wrapped-worker payload]
  (when (identical? (:opening handle) (:opening @*handle))
    (case type
      :user-navigation/changed (when (contains? (:scopes payload)
                                                (get-in @(:context @*session) [:source :remote-scope]))
                                  (refresh-snapshot! @*handle))
      (:capture-error :notification :log :db-worker/ui-request)
      (worker-handler/handle type wrapped-worker payload)
      nil)))

(defn- <release! [handle]
  (when handle
    (if-let [client (:client handle)]
      (p/do! (remote/stop! client) (ipc/ipc :release-user-navigation-runtime (:repo handle)))
      (do (when-let [worker (:worker handle)] (.terminate ^js worker)) (p/resolved nil)))))

(defn- <sync-auth! [handle]
  (when (= (second (:identity handle)) (user-handler/user-uuid))
    ((:invoke handle) :thread-api/sync-app-state
     (assoc (user-handler/sync-app-state-payload) :git/current-repo (:repo handle)))))

(defn- <open-handle! [session]
  (let [[service user-id _ root] (:key session)
        identity [service user-id root]]
    (if (= identity (:identity @*handle))
      (:ready @*handle)
      (let [previous @*handle
            handle {:identity identity :opening (atom nil)}]
        (reset! *handle handle)
        (let [ready
              (p/let [_ (<release! previous)
                      runtime (when (util/electron?)
                                (ipc/ipc :user-navigation-db-runtime {:service service :user-id user-id}))
                      browser-client (when-not runtime (browser/create-worker-client! true))
                      repo (or (:repo runtime) (str "logseq_db_user-navigation-" (navigation/graph-id (str service "\n" user-id))))
                      client (when runtime
                               (remote/start! (assoc runtime
                                                     :event-handler #(handle-event! @(:opening handle) %1 %2 %3)
                                                     :still-active? #(identical? (:opening handle) (:opening @*handle)))))
                      opened (assoc handle :repo repo :client client :worker (:worker browser-client)
                                    :invoke (or (:wrapped-worker client) (:wrapped-worker browser-client)))
                      _ (reset! (:opening handle) opened)
                      _ (when browser-client
                          (worker-handler/handle-message! (:worker browser-client) (:invoke opened)
                                                          #(handle-event! opened %1 %2 %3)))
                      _ (when browser-client ((:invoke opened) :thread-api/init))
                      _ ((:invoke opened) :thread-api/create-or-open-db repo
                         {:user-navigation/context {:user-id user-id :http-base service :ws-url (config/db-sync-ws-url)}})
                      configured ((:invoke opened) :thread-api/configure-user-navigation repo
                                  {:user-id user-id :http-base service :ws-url (config/db-sync-ws-url)})]
                (if (identical? (:opening handle) (:opening @*handle))
                  (p/let [opened (assoc opened :device-id (:device-id configured) :ready (:ready @*handle))
                          _ (reset! *handle opened)
                          _ (<sync-auth! opened)]
                    opened)
                  (p/do! (<release! opened) (throw (ex-info "Navigation context changed" {:code :navigation-context-closed})))))]
          (swap! *handle #(if (identical? handle %) (assoc % :ready ready) %))
          ready)))))

(defn- <legacy-recents [repo]
  (p/let [renames (when (util/electron?) (graph-handler/<get-graph-directory-renames))]
    (let [recent-map (state/get-state :ui/recent-pages)
          old-repos (keep #(when (= repo (:to %)) (:from %)) renames)]
      (vec (distinct (mapcat #(get recent-map %) (cons repo old-repos)))))))

(defn <ensure-context! []
  (let [key (context-key)]
    (if (= key (:key @*session))
      (some-> @*session :ready deref)
      (let [session {:key key :context (atom nil) :ready (atom nil)}]
        (reset! *session session)
        (state/set-state! :user-navigation/snapshot (when key {:repo (nth key 2) :loading? true}))
        (if-not key
          (do
            (when-not (state/get-state :db/restoring?)
              (let [handle @*handle] (reset! *handle nil) (<release! handle)))
            (p/resolved nil))
          (let [ready
                (p/let [[service user-id repo root notes-worker] key
                        _ (when (and @*handle (not= [service user-id root] (:identity @*handle)))
                            (let [previous @*handle] (reset! *handle nil) (<release! previous)))
                        legacy (<legacy-recents repo)
                        source (notes-worker :thread-api/user-navigation-source repo service user-id legacy)
                        _ (when-not (current? session)
                            (throw (ex-info "Navigation context changed" {:code :navigation-context-closed})))
                        handle (when (and user-id (:remote-scope source)) (<open-handle! session))
                        _ (when-not (current? session)
                            (throw (ex-info "Navigation context changed" {:code :navigation-context-closed})))
                        context {:source source :handle handle :legacy legacy}
                        _ (reset! (:context session) context)
                        snapshot (if handle
                                   (let [{:keys [device-id transfer-id values]} (:domain source)
                                         local-scope (:local-scope source)]
                                     ((:invoke handle) :thread-api/user-navigation-add-scope (:repo handle)
                                      {:scope (:remote-scope source) :transfer-id transfer-id
                                       :favorites-seed (get values (navigation/ident :favorites-seed local-scope nil))
                                       :recents-seed (get values (navigation/ident :recents-seed local-scope device-id))
                                       :values (navigation/transfer-values values (:remote-scope source) (:device-id handle))}))
                                   (:snapshot source))]
                  (install-snapshot! session snapshot)
                  (when handle
                    (-> ((:invoke handle) :thread-api/db-sync-start (:repo handle))
                        (p/catch (fn [error]
                                   (when (and (current? session) (#{401 403 404} (:status (ex-data error))))
                                     (notification/show! (t :sync.navigation/online-error) :warning))))))
                  context)]
            (reset! (:ready session) ready)
            (p/catch ready (fn [error]
                            (when (and (current? session) (not= :navigation-context-closed (:code (ex-data error))))
                              (report-save-error! error))))
            ready))))))

(defn- <operate! [operation args]
  (let [ready (<ensure-context!)
        session @*session]
   (-> (p/let [context ready
          _ (when-not (current? session)
              (throw (ex-info "Navigation context changed" {:code :navigation-context-closed})))
          [service user-id repo _ notes-worker] (:key session)
          {:keys [handle source legacy]} context
          snapshot (if handle
                     (let [method (case operation
                                    :favorite :thread-api/user-navigation-set-favorite
                                    :visit :thread-api/user-navigation-visit
                                    :reorder :thread-api/user-navigation-reorder
                                    :remove-recents :thread-api/user-navigation-remove-recents)]
                       (apply (:invoke handle) method (:repo handle) (:remote-scope source) args))
                     (notes-worker :thread-api/user-navigation-local repo service user-id legacy operation args))]
    (install-snapshot! session snapshot)
    snapshot)
       (p/catch (fn [error]
                  (when (and (current? session) (not= :navigation-context-closed (:code (ex-data error))))
                    (report-save-error! error))
                  (throw error))))))

(defn <set-favorite! [target selected?] (<operate! :favorite [(str target) selected?]))
(defn <visit! [target] (<operate! :visit [(str target)]))
(defn <reorder! [targets] (<operate! :reorder [(mapv str targets)]))
(defn <remove-recents! [targets] (<operate! :remove-recents [(mapv str targets)]))

(defn <favorited? [target]
  (p/let [_ (<ensure-context!)]
    (boolean (some #{(str target)} (:favorites (state/get-state :user-navigation/snapshot))))))

(defn <nodes [recent?]
  (let [ready (<ensure-context!)
        session @*session]
    (p/let [_ ready
            _ (when-not (current? session)
                (throw (ex-info "Navigation context changed" {:code :navigation-context-closed})))
            snapshot (state/get-state :user-navigation/snapshot)
            result ((nth (:key session) 4) :thread-api/user-navigation-nodes (nth (:key session) 2)
                    (get snapshot (if recent? :recents :favorites)) recent?)]
      (when (current? session) result))))

(defn start! []
  (rfx/listen! ::context
               (fn [before after]
                 (if (not= (:key @*session) (context-key))
                   (<ensure-context!)
                   (when (not= (:auth/id-token before) (:auth/id-token after))
                     (when-let [handle @*handle]
                       (p/let [_ (<sync-auth! handle)]
                         (when (identical? (:opening handle) (:opening @*handle))
                           ((:invoke handle) :thread-api/db-sync-start (:repo handle)))))))))
  (add-watch state/*db-worker ::context (fn [_ _ _ _] (<ensure-context!)))
  (when-let [listener @*close-listener] (.removeEventListener js/window "beforeunload" listener))
  (let [listener (fn [_] (<release! @*handle))]
    (.addEventListener js/window "beforeunload" listener)
    (reset! *close-listener listener))
  (<ensure-context!))
