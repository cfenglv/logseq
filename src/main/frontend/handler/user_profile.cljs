(ns frontend.handler.user-profile
  "Service-scoped user profile reads, saves and display selection."
  (:require [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.user :as user-handler]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [frontend.util.email :as email-util]
            [logseq.db-sync.malli-schema :as schema]
            [malli.core :as ma]
            [promesa.core :as p]))

(defonce ^:private *avatar-requests (atom {}))
(defonce ^:private *focus-listener (atom nil))

(defn current-context []
  (when (and (not config/publishing?)
             (or config/db-sync-local? (config/get-custom-sync-server-url)))
    (when-let [user-id (user-handler/user-uuid)]
      [(rtc-handler/http-base) user-id])))

(defn current-graph-id []
  (some (fn [graph]
          (when (= (:url graph) (state/get-current-repo))
            (some-> (:GraphUUID graph) str)))
        (state/get-rtc-graphs)))

(defn sync-context! []
  (let [context (current-context)
        repo (state/get-current-repo)
        current (state/get-state :user-profile/state)]
    (cond
      (not= context (:context current))
      (do
        (reset! *avatar-requests {})
        (state/set-state! :user-profile/state {:context context :repo repo :profiles {} :avatars {}}))

      (not= repo (:repo current))
      (let [own-id (second context)]
        (state/set-state! :user-profile/state
                          (assoc current :repo repo
                                 :profiles (select-keys (:profiles current) [own-id])
                                 :avatars (into {} (filter #(= own-id (ffirst %))) (:avatars current))))))
    context))

(defn- request-current? [context repo]
  (and (= context (current-context)) (= repo (state/get-current-repo))))

(defn ingest-metadata!
  [context repo profiles]
  (when (and context (request-current? context repo))
    (sync-context!)
    (let [current (state/get-state :user-profile/state)
          profiles' (reduce (fn [result {:keys [user-id profile-revision] :as profile}]
                              (if (and (some? profile-revision)
                                       (>= profile-revision (get-in result [user-id :profile-revision] -1)))
                                (assoc result user-id (merge (get result user-id) (dissoc profile :avatar-png-base64)))
                                result))
                            (:profiles current) profiles)
          avatars' (into {}
                         (filter (fn [[[user-id revision] _]]
                                   (let [profile (get profiles' user-id)]
                                     (and (:has-avatar? profile) (= revision (:profile-revision profile))))))
                         (:avatars current))]
      (state/set-state! :user-profile/state (assoc current :profiles profiles' :avatars avatars')))))

(defn- ingest-profile! [context repo profile]
  (ingest-metadata! context repo [profile])
  (when (and (request-current? context repo)
             (= (:profile-revision profile)
                (get-in (state/get-state :user-profile/state) [:profiles (:user-id profile) :profile-revision])))
    (when-let [image (:avatar-png-base64 profile)]
      (state/set-state! [:user-profile/state :avatars [(:user-id profile) (:profile-revision profile)]]
                        (str "data:image/png;base64," image)))))

(defn- <request [context repo target-id path method body schema-key]
  (let [own? (= target-id (second context))
        current? #(and (= context (current-context))
                       (or own? (= repo (state/get-current-repo))))]
   (p/let [_ (user-handler/<ensure-id&access-token!)
          _ (when-not (current?)
              (throw (ex-info "profile context changed" {})))
          response (rtc-handler/fetch-json
                    (str (first context) path)
                    (cond-> {:method method}
                      body (assoc :headers {"content-type" "application/json"}
                                  :body (js/JSON.stringify (clj->js body))))
                    {})
          profile ((get schema/http-response-coercers schema-key) response)]
    (when-not (and (current?) (= target-id (:user-id profile)))
      (throw (ex-info "profile context changed" {})))
    (ingest-profile! context (if own? (state/get-current-repo) repo) profile)
    profile)))

(defn <get-own! []
  (let [context (sync-context!)
        repo (state/get-current-repo)]
    (when context
      (<request context repo (second context) "/user/profile" "GET" nil :user-profile/get))))

(defn <save! [body]
  (p/do
    (when-not (ma/validate schema/user-profile-save-request-schema body)
      (throw (ex-info "profile-invalid-input" {:status 400 :body {:error "profile-invalid-input"}})))
    (let [context (sync-context!)
          repo (state/get-current-repo)]
      (when-not context (throw (ex-info "profile unavailable" {:status 401})))
      (p/let [saved (<request context repo (second context) "/user/profile" "PUT" body :user-profile/save)
              _ (when (and (= context (current-context))
                           (= :open (get-in (state/get-state :rtc/state) [:rtc-state :ws-state])))
                  (-> (state/<invoke-db-worker :thread-api/db-sync-refresh-profile context (state/get-current-repo))
                      (p/then (fn [sent?]
                                (when-not sent? (notification/show! (t :account/profile-online-update-error) :warning))))
                      (p/catch (fn [_]
                                 (notification/show! (t :account/profile-online-update-error) :warning)))))]
        saved))))

(defn <refresh-members! []
  (let [context (sync-context!)
        repo (state/get-current-repo)]
    (when (current-graph-id)
      (p/let [members (rtc-handler/<rtc-get-users-info true)]
        (when (and context (request-current? context repo))
          (let [ids (conj (set (map :user/uuid members)) (second context))
                current (state/get-state :user-profile/state)]
            (state/set-state! :user-profile/state
                              (assoc current :profiles (select-keys (:profiles current) ids)
                                     :avatars (into {} (filter #(contains? ids (ffirst %))) (:avatars current)))))
          (ingest-metadata! context repo
                            (map #(assoc (select-keys % [:display-name :has-avatar? :profile-revision])
                                         :user-id (:user/uuid %) :username (:user/name %) :email (:user/email %))
                                 members)))
        members))))

(defn <load-avatar! [user-id]
  (let [context (sync-context!)
        repo (state/get-current-repo)
        profile (get-in (state/get-state :user-profile/state) [:profiles user-id])
        revision (:profile-revision profile)
        avatar-key [user-id revision]
        request-key [context repo user-id revision]
        own? (= user-id (second context))
        graph-id (current-graph-id)]
    (when (and context (:has-avatar? profile)
               (or own? graph-id)
               (not (get-in (state/get-state :user-profile/state) [:avatars avatar-key])))
      (or (get @*avatar-requests request-key)
          (let [request (<request context repo user-id
                                  (if own? "/user/profile"
                                      (str "/graphs/" graph-id "/members/" user-id "/avatar"))
                                  "GET" nil (if own? :user-profile/get :graph-members/avatar))]
            (swap! *avatar-requests assoc request-key request)
            (-> request
                (p/finally #(when (identical? request (get @*avatar-requests request-key))
                              (swap! *avatar-requests dissoc request-key)))))))))

(defn resolve-display
  "Resolve already-read data only. User display preferences never change author identity."
  [user profile avatar-src config current-user-id]
  (let [user-id (some-> (:user/uuid user) str)
        custom-name (:display-name profile)
        raw-name (or custom-name (:username profile) (:user/name user)
                     (:email profile) (:user/email user) user-id)
        name (if (and (nil? custom-name) (string? raw-name) (re-matches #"[^\s@]+@[^\s@]+" raw-name))
               (email-util/display-email raw-name config) raw-name)]
    {:name name :user-id user-id :user-profile/self? (= user-id current-user-id)
     :avatar-src (if profile avatar-src (:avatar-src user))
     :has-avatar? (:has-avatar? profile) :profile-revision (:profile-revision profile)}))

(defn save-error-message [error]
  (case (:status (ex-data error))
    400 (t :account/profile-invalid-input-error)
    413 (t :account/profile-request-too-large-error)
    409 (t :account/profile-conflict-error)
    401 (t :account/profile-access-error)
    403 (t :account/profile-access-error)
    (t :account/profile-save-unconfirmed-error)))

(defn- refresh-current! []
  (when (current-context)
    (-> (p/all [(<get-own!) (<refresh-members!)])
        (p/catch (fn [_] (notification/show! (t :account/profile-load-error) :error))))))

(defn refresh-on-focus! []
  (sync-context!)
  (refresh-current!))

(defn on-rtc-state! [payload]
  (let [context (:user-profile/context payload)
        repo (:user-profile/repo payload)]
    (when (and context (request-current? context repo))
      (when (:user-profile/error payload)
        (notification/show! (t :account/profile-online-update-error) :warning))
      (ingest-metadata! context repo
                        (map #(assoc (select-keys % [:display-name :has-avatar? :profile-revision])
                                     :user-id (:user/uuid %)) (:online-users payload)))
      (when (and (= :open (get-in payload [:rtc-state :ws-state]))
                 (not= :open (get-in (state/get-state :rtc/state) [:rtc-state :ws-state])))
        (refresh-current!)))))

(defn start! []
  (rfx/listen! ::context
               (fn [before after]
                 (when (not= (select-keys before [:auth/id-token :git/current-repo])
                             (select-keys after [:auth/id-token :git/current-repo]))
                   (let [previous-context (:context (state/get-state :user-profile/state))]
                     (sync-context!)
                     (when (or (not= previous-context (current-context))
                               (not= (:git/current-repo before) (:git/current-repo after)))
                       (refresh-current!))))))
  (when-let [listener @*focus-listener]
    (.removeEventListener js/window "focus" listener))
  (let [listener (fn [_] (refresh-on-focus!))]
    (.addEventListener js/window "focus" listener)
    (reset! *focus-listener listener))
  (sync-context!))
