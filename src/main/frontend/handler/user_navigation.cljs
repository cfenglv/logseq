(ns frontend.handler.user-navigation
  "One private original RTC handle, with a derived snapshot for the working graph."
  (:require [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.diagnostics :as diagnostics]
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
            [lambdaisland.glogi :as log]
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
  (and (:key session) (identical? session @*session) (= (:key session) (context-key))))

(defn- <check-current! [session]
  (when-not (current? session)
    (p/rejected (ex-info "Navigation context changed" {:code :navigation-context-closed}))))

(defn- <check-handle! [handle]
  (when-not (identical? (:opening handle) (:opening @*handle))
    (p/rejected (ex-info "Navigation context changed" {:code :navigation-context-closed}))))

(defn- install-snapshot! [session snapshot]
  (when (current? session)
    (state/set-state! :user-navigation/snapshot (assoc snapshot :repo (nth (:key session) 2)))))

(defn- report-save-error! [error]
  (let [record (diagnostics/error-record error)
        private-values (filter string? (:key @*session))
        cause (ex-info (diagnostics/redact-text (:message record) private-values)
                       (select-keys (ex-data error) [:code :status]))]
    (when-let [stack (:stack record)]
      (set! (.-stack cause) (diagnostics/redact-text stack private-values)))
    (log/error :event :user-navigation/save-error :exception cause))
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
      ;; Keep each graph's rejection branch separate from the shared opening.
      (-> (js/Promise.resolve (:ready @*handle))
          (p/catch (fn [_]
                     ;; The previous graph's failed opening has finished its cleanup.
                     ;; Only the current graph may now initialize its own context.
                     (p/let [_ (<check-current! session)]
                       (<open-handle! session)))))
      (let [previous @*handle
            handle {:identity identity :opening (atom nil)}]
        (reset! *handle handle)
        (let [ready
              (-> (p/let [_ (<release! previous)
                          _ (<check-current! session)
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
                          ;; An acquired runtime belongs to the account, not the notes graph.
                          _ (<check-handle! handle)
                          _ (when browser-client
                              (worker-handler/handle-message! (:worker browser-client) (:invoke opened)
                                                              #(handle-event! opened %1 %2 %3)))
                          _ (when browser-client ((:invoke opened) :thread-api/init))
                          _ ((:invoke opened) :thread-api/create-or-open-db repo
                             {:user-navigation/context {:user-id user-id :http-base service :ws-url (config/db-sync-ws-url)}})
                          configured ((:invoke opened) :thread-api/configure-user-navigation repo
                                      {:user-id user-id :http-base service :ws-url (config/db-sync-ws-url)})
                          _ (<check-handle! handle)
                          opened (assoc opened :device-id (:device-id configured) :ready (:ready @*handle))
                          _ (reset! *handle opened)
                          _ (<sync-auth! opened)
                          _ (<check-handle! handle)]
                    opened)
                  (p/catch (fn [error]
                             (p/let [_ (p/finally (p/do! (<release! @(:opening handle)))
                                                 (fn []
                                                   (when (identical? (:opening handle) (:opening @*handle))
                                                     (reset! *handle nil))))]
                               (p/rejected error)))))]
          (swap! *handle #(if (identical? handle %) (assoc % :ready ready) %))
          ready)))))

(defn- <legacy-recents [repo]
  (p/let [renames (when (util/electron?) (graph-handler/<get-graph-directory-renames))]
    (let [recent-map (state/get-state :ui/recent-pages)
          old-repos (keep #(when (= repo (:to %)) (:from %)) renames)]
      (vec (distinct (mapcat #(get recent-map %) (cons repo old-repos)))))))

(defn <ensure-context! []
  (let [key (context-key)]
    (if (and (= key (:key @*session))
             (or (nil? key) (some-> @*session :ready deref)))
      (or (some-> @*session :ready deref) (p/resolved nil))
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
                        _ (<check-current! session)
                        legacy (<legacy-recents repo)
                        _ (<check-current! session)
                        source (notes-worker :thread-api/user-navigation-source repo service user-id legacy)
                        _ (<check-current! session)
                        handle (when (and user-id (:remote-scope source)) (<open-handle! session))
                        _ (<check-current! session)
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
                            ;; Keep the key so ordinary state notifications do not retry.
                            ;; A subsequent real entry can initialize again.
                            (when (identical? session @*session)
                              (reset! (:ready session) nil))
                            (when (and (current? session) (not= :navigation-context-closed (:code (ex-data error))))
                              (report-save-error! error))))
            ready))))))

(defn- <operate! [operation args]
  (let [ready (<ensure-context!)
        session @*session]
   (-> (p/let [context ready
          _ (<check-current! session)
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
                  (p/rejected error))))))

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
            _ (<check-current! session)
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
