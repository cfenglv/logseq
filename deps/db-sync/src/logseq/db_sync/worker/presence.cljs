(ns logseq.db-sync.worker.presence
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.worker.ws :as ws]
            [promesa.core :as p]))

(defn claims->user
  [claims]
  (when claims
    (let [user-id (aget claims "sub")
          email (aget claims "email")
          username (or (aget claims "preferred_username")
                       (aget claims "cognito:username")
                       (aget claims "username"))
          name (aget claims "name")]
      (when (string? user-id)
        (cond-> {:user-id user-id}
          (string? email) (assoc :email email)
          (string? username) (assoc :username username)
          (string? name) (assoc :name name))))))

(defn attachment->user
  [attachment]
  (:presence/user (bean/->clj attachment)))

(defn <connection-user [db claims]
  (when-let [user (claims->user claims)]
    (p/let [profile (index/<user-profile-metadata db (:user-id user))]
      (when-not profile (throw (ex-info "profile unavailable" {})))
      (merge user profile))))

(defn- serialize-attachment!
  [^js ws user]
  (.serializeAttachment ws (bean/->js {:presence/user user})))

(defn presence*
  [^js self]
  (or (.-presence self)
      (set! (.-presence self) (atom {}))))

(defn online-users
  [^js self]
  (vec (distinct (vals @(presence* self)))))

(defn broadcast-online-users!
  [^js self]
  (ws/broadcast! self nil {:type "online-users" :online-users (online-users self)}))

(defn add-presence!
  [^js self ^js ws user]
  (swap! (presence* self) assoc ws user)
  (serialize-attachment! ws user))

(defn update-presence!
  [^js self ^js ws {:keys [editing-block-uuid] :as updates}]
  (swap! (presence* self)
         (fn [presence]
           (if-let [user (get presence ws)]
             (let [user' (if (contains? updates :editing-block-uuid)
                           (if (and (string? editing-block-uuid)
                                    (not (string/blank? editing-block-uuid)))
                             (assoc user :editing-block-uuid editing-block-uuid)
                             (dissoc user :editing-block-uuid))
                           user)]
               (serialize-attachment! ws user')
               (assoc presence ws user'))
             presence))))

(defn get-user
  [^js self ^js ws]
  (get @(presence* self) ws))

(defn <refresh-profile!
  [^js self socket]
  (when-let [user (get-user self socket)]
    (p/let [profile (index/<user-profile-metadata (.. self -env -DB) (:user-id user))]
      (when-not profile (throw (ex-info "profile unavailable" {})))
      ;; External reads may finish out of order. Preserve each socket's editor state.
      (when (= (:user-id user) (:user-id (get-user self socket)))
        (let [fields (select-keys profile [:display-name :has-avatar? :profile-revision])]
          (swap! (presence* self)
                 (fn [users]
                   (into {}
                         (map (fn [[ws current]]
                                (if (and (= (:user-id profile) (:user-id current))
                                         (>= (:profile-revision profile) (get current :profile-revision -1)))
                                  (let [updated (merge current fields)]
                                    (serialize-attachment! ws updated)
                                    [ws updated])
                                  [ws current])))
                         users)))
          (ws/broadcast! self nil (assoc profile :type "profile/updated")))))))

(defn remove-presence!
  [^js self ^js ws]
  (swap! (presence* self) dissoc ws))
