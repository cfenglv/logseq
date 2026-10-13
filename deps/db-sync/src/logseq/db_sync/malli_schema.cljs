(ns logseq.db-sync.malli-schema
  (:require [clojure.string :as string]
            [logseq.common.defkeywords :refer [defkeyword]]
            [logseq.common.user-navigation :as navigation]
            [malli.core :as ma]
            [malli.transform :as mt]))

(defkeyword :display-name "Optional user-selected display name, separate from the login identity.")
(defkeyword :avatar-png-base64 "Optional Base64-encoded 128 by 128 PNG user avatar.")
(defkeyword :has-avatar? "Whether the current user profile contains an avatar, without its image data.")
(defkeyword :profile-revision "Version of a user's saved display name and avatar.")
(defkeyword :expected-revision "Profile version the user read before editing.")
(defkeyword :user-profile/get "Read the authenticated user's profile.")
(defkeyword :user-profile/save "Save the authenticated user's profile at an expected revision.")
(defkeyword :graph-members/avatar "Read a current graph member's avatar and profile metadata.")

(def avatar-base64-max-length 174764)

(defkeyword :user-navigation/init "Provision and conditionally initialize the authenticated user's private navigation graph.")
(defkeyword :favorites-seed "Ordered UUID strings from legacy shared favorites, including an initialized empty vector.")
(defkeyword :recents-seed "Ordered legacy recent UUID strings for one physical device, without invented times.")
(defkeyword :transfer-id "Persistent UUID string binding one conditional local-to-RTC navigation transfer.")

(def navigation-uuid-schema [:re #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"])
(def navigation-seed-schema [:vector navigation-uuid-schema])
(def navigation-stamp-schema [:or [:tuple [:= :seed] navigation-uuid-schema]
                             [:tuple [:= :selection] navigation-uuid-schema]])
(def navigation-visits-schema [:vector {:max navigation/recents-limit}
                              [:tuple navigation-uuid-schema [:int {:min 0}]]])
(def navigation-value-validators
  (update-vals
   {:favorite [:or [:map {:closed true} [:selected? [:= false]]]
               [:map {:closed true} [:selected? [:= true]]
                [:membership-id navigation-uuid-schema] [:added-at [:int {:min 0}]]]]
    :order [:vector [:tuple navigation-uuid-schema navigation-stamp-schema]]
    :visits navigation-visits-schema
    :favorites-seed navigation-seed-schema
    :recents-seed navigation-seed-schema
    :transfer-once [:= true]}
   ma/validator))

(defn navigation-transfer-values-valid?
  [values scope device-id]
  (and (map? values)
       (every? (fn [[key value]]
                 (let [[family key-scope discriminator] (navigation/ident-parts key)
                       validator (get navigation-value-validators family)]
                   (and validator (= scope key-scope) (not= :transfer-once family)
                        (= key (navigation/ident family key-scope discriminator))
                        (case family
                          :favorite (ma/validate navigation-uuid-schema discriminator)
                          (:visits :recents-seed) (= device-id discriminator)
                          (:order :favorites-seed) (nil? discriminator))
                        (validator value)))) values)))

(def user-navigation-init-request-schema
  [:map {:closed true}
   [:scopes [:vector [:map {:closed true}
                     [:scope navigation-uuid-schema]
                     [:device-id navigation-uuid-schema]
                     [:favorites-seed navigation-seed-schema]
                     [:recents-seed navigation-seed-schema]
                     [:transfer-id {:optional true} navigation-uuid-schema]
                     [:values {:optional true} :string]]]]])

(def display-name-schema
  [:maybe [:and :string
           [:fn (fn [value]
                  (<= 1 (.-length (js/Array.from (string/trim value))) 64))]]])

(def profile-revision-schema [:int {:min 0}])

(def user-profile-save-request-schema
  [:map {:closed true}
   [:display-name display-name-schema]
   [:avatar-png-base64 [:maybe [:string {:max avatar-base64-max-length}]]]
   [:expected-revision profile-revision-schema]])

(def user-profile-metadata-schema
  [:map
   [:user-id :string]
   [:display-name display-name-schema]
   [:has-avatar? :boolean]
   [:profile-revision profile-revision-schema]])

(def user-profile-response-schema
  (into user-profile-metadata-schema
        [[:email {:optional true} [:maybe :string]]
         [:username {:optional true} [:maybe :string]]
         [:avatar-png-base64 [:maybe [:string {:max avatar-base64-max-length}]]]]))

(def tx-entry-schema
  [:map
   [:tx-id {:optional true} :uuid]
   [:logical-tx-id {:optional true} :uuid]
   [:upload-session-id {:optional true} :string]
   [:chunk-index {:optional true} :int]
   [:chunk-final? {:optional true} :boolean]
   [:tx :string]
   [:outliner-op {:optional true} [:maybe :keyword]]])

(def tx-log-entry-schema
  [:map
   [:t :int]
   [:tx :string]
   [:outliner-op {:optional true} [:maybe :keyword]]])

(def client-revision-entry
  [:client-revision {:optional true} :string])

(def profile-refresh-message-schema
  [:map {:closed true} [:type [:= "profile/refresh"]]])

(def ws-client-message-schema
  [:multi {:dispatch :type}
   ["hello"
    [:map
     [:type [:= "hello"]]
     [:client :string]]]
   ["presence"
    [:map
     [:type [:= "presence"]]
     [:editing-block-uuid {:optional true} [:maybe :string]]]]
   ["profile/refresh"
    profile-refresh-message-schema]
   ["pull"
    [:map
     [:type [:= "pull"]]
     [:since {:optional true} :int]]]
   ["tx/batch"
    [:map
     [:type [:= "tx/batch"]]
     client-revision-entry
     [:t-before :int]
     [:txs [:sequential tx-entry-schema]]]]
   ["ping"
    [:map
     [:type [:= "ping"]]]]])

(def tx-reject-reason-schema
  [:enum "stale"
   "empty tx data"
   "invalid tx"
   "invalid t-before"
   "db transact failed"
   "snapshot upload in progress"])

(def tx-reject-schema
  [:map
   [:type [:= "tx/reject"]]
   [:reason tx-reject-reason-schema]
   [:t {:optional true} :int]
   [:success-tx-ids {:optional true} [:sequential :uuid]]
   [:failed-tx-id {:optional true} :uuid]
   [:missing-block-uuids {:optional true} [:sequential :uuid]]
   [:error-detail {:optional true} :string]
   [:data {:optional true} :string]])

(def user-presence-schema
  [:map
   [:user-id :string]
   [:email {:optional true} [:maybe :string]]
   [:username {:optional true} [:maybe :string]]
   [:name {:optional true} [:maybe :string]]
   [:editing-block-uuid {:optional true} :string]
   [:display-name {:optional true} display-name-schema]
   [:has-avatar? {:optional true} :boolean]
   [:profile-revision {:optional true} profile-revision-schema]])

(def online-users-schema
  [:map
   [:type [:= "online-users"]]
   [:online-users [:sequential user-presence-schema]]])

(def pull-ok-schema
  [:map
   [:type [:= "pull/ok"]]
   [:t :int]
   [:checksum {:optional true} :string]
   [:txs [:sequential tx-log-entry-schema]]])

(def tx-batch-ok-schema
  [:map
   [:type [:= "tx/batch/ok"]]
   [:t :int]
   [:checksum {:optional true} :string]])

(def ws-server-message-schema
  [:multi {:dispatch :type}
   ["hello"
    [:map
     [:type [:= "hello"]]
     [:t :int]
     [:capabilities {:optional true} [:sequential :string]]
     [:checksum {:optional true} :string]]]
   ["online-users" online-users-schema]
   ["profile/updated"
    (into [:map {:closed true}] (concat (rest user-profile-metadata-schema) [[:type [:= "profile/updated"]]]))]
   ["presence"
    [:map
     [:type [:= "presence"]]
     [:user-id :string]
     [:editing-block-uuid [:maybe :string]]]]
   ["pull/ok" pull-ok-schema]
   ["tx/batch/ok" tx-batch-ok-schema]
   ["changed"
    [:map
     [:type [:= "changed"]]
     [:t :int]]]
   ["tx/reject" tx-reject-schema]
   ["pong"
    [:map
     [:type [:= "pong"]]]]
   ["error"
    [:map
     [:type [:= "error"]]
     [:message :string]]]])

(def http-error-response-schema
  [:map
   [:error :string]])

(def http-ok-response-schema
  [:map
   [:ok :boolean]])

(def graph-member-role-schema
  [:enum "manager" "member"])

(def graph-info-schema
  [:map
   [:graph-id :string]
   [:graph-name :string]
   [:schema-version {:optional true} [:maybe :string]]
   [:graph-e2ee? {:optional true} :boolean]
   [:graph-ready-for-use? {:optional true} :boolean]
   [:role {:optional true} [:maybe graph-member-role-schema]]
   [:invited-by {:optional true} [:maybe :string]]
   [:created-at :int]
   [:updated-at :int]])

(def graph-member-info-schema
  [:map
   [:user-id :string]
   [:graph-id :string]
   [:role graph-member-role-schema]
   [:invited-by {:optional true} [:maybe :string]]
   [:created-at :int]
   [:email {:optional true} [:maybe :string]]
   [:username {:optional true} [:maybe :string]]
   [:display-name {:optional true} display-name-schema]
   [:has-avatar? {:optional true} :boolean]
   [:profile-revision {:optional true} profile-revision-schema]])

(def graph-members-list-response-schema
  [:map
   [:members [:sequential graph-member-info-schema]]])

(def graphs-list-response-schema
  [:map
   [:graphs [:sequential graph-info-schema]]
   [:user-rsa-keys-exists? {:optional true} :boolean]])

(def graph-create-request-schema
  [:map
   [:graph-name :string]
   [:schema-version {:optional true} [:maybe :string]]
   [:graph-e2ee? {:optional true} :boolean]
   [:graph-ready-for-use? {:optional true} :boolean]])

(def graph-create-response-schema
  [:map
   [:graph-id :string]
   [:graph-e2ee? {:optional true} :boolean]
   [:graph-ready-for-use? {:optional true} :boolean]])

(def graph-access-response-schema http-ok-response-schema)

(def graph-delete-response-schema
  [:map
   [:graph-id :string]
   [:deleted :boolean]])

(def graph-member-create-request-schema
  [:or
   [:map
    [:user-id :string]
    [:role {:optional true} graph-member-role-schema]]
   [:map
    [:email :string]
    [:role {:optional true} graph-member-role-schema]]])

(def graph-member-update-request-schema
  [:map
   [:role graph-member-role-schema]])

(def tx-batch-request-schema
  [:map
   client-revision-entry
   [:t-before :int]
   [:txs [:sequential tx-entry-schema]]])

(def e2ee-user-key-request-schema
  [:map
   [:public-key :string]
   [:encrypted-private-key :string]
   [:reset-private-key {:optional true} :boolean]])

(def e2ee-user-key-response-schema
  [:map
   [:public-key {:optional true} [:maybe :string]]
   [:encrypted-private-key {:optional true} [:maybe :string]]])

(def e2ee-user-public-key-response-schema
  [:map
   [:public-key {:optional true} [:maybe :string]]])

(def e2ee-graph-aes-key-request-schema
  [:map
   [:encrypted-aes-key :string]])

(def e2ee-graph-aes-key-response-schema
  [:map
   [:encrypted-aes-key {:optional true} [:maybe :string]]
   ;; base64 of the raw RSA-OAEP wrapped AES key, for agents that unwrap
   ;; client-side with their RSA private key (no transit parsing needed)
   [:encrypted-aes-key-base64 {:optional true} [:maybe :string]]])

(def e2ee-grant-access-entry-schema
  [:map
   [:email :string]
   [:encrypted-aes-key :string]])

(def e2ee-grant-access-request-schema
  [:map
   [:target-user-email+encrypted-aes-key-coll [:sequential e2ee-grant-access-entry-schema]]])

(def e2ee-grant-access-response-schema
  [:map
   [:ok :boolean]
   [:missing-users {:optional true} [:sequential :string]]])

(def snapshot-download-response-schema
  [:map
   [:ok :boolean]
   [:key :string]
   [:url :string]
   [:content-encoding {:optional true} [:maybe :string]]])

(def snapshot-upload-response-schema
  [:map
   [:ok :boolean]
   [:count :int]])

(def asset-get-response-schema
  [:or
   :any
   http-error-response-schema])

(def checksum-diagnostics-response-schema
  [:map
   [:checksum :string]
   [:t :int]
   [:legacy-anchor :string]
   [:server-recomputed-checksum :string]
   [:server-checkpoint-identity :string]
   [:metadata-proof :string]])

(def http-request-schemas
  {:user-profile/save user-profile-save-request-schema
   :graphs/create graph-create-request-schema
   :graph-members/create graph-member-create-request-schema
   :graph-members/update graph-member-update-request-schema
   :sync/tx-batch tx-batch-request-schema
   :e2ee/user-keys e2ee-user-key-request-schema
   :e2ee/graph-aes-key e2ee-graph-aes-key-request-schema
   :e2ee/grant-access e2ee-grant-access-request-schema})

(def http-response-schemas
  {:user-profile/get user-profile-response-schema
   :user-profile/save user-profile-response-schema
   :graph-members/avatar user-profile-response-schema
   :graphs/list graphs-list-response-schema
   :graphs/create graph-create-response-schema
   :graphs/access graph-access-response-schema
   :graphs/delete graph-delete-response-schema
   :graph-members/list graph-members-list-response-schema
   :graph-members/create http-ok-response-schema
   :graph-members/update http-ok-response-schema
   :graph-members/delete http-ok-response-schema
   :worker/health http-ok-response-schema
   :sync/health http-ok-response-schema
   :sync/pull pull-ok-schema
   :sync/checksum-diagnostics checksum-diagnostics-response-schema
   :sync/tx-batch [:or tx-batch-ok-schema tx-reject-schema http-error-response-schema]
   :sync/snapshot-download snapshot-download-response-schema
   :sync/snapshot-upload snapshot-upload-response-schema
   :sync/admin-reset http-ok-response-schema
   :e2ee/user-keys e2ee-user-key-response-schema
   :e2ee/user-public-key e2ee-user-public-key-response-schema
   :e2ee/graph-aes-key e2ee-graph-aes-key-response-schema
   :e2ee/grant-access e2ee-grant-access-response-schema
   :assets/get asset-get-response-schema
   :assets/put http-ok-response-schema
   :assets/delete http-ok-response-schema
   :error http-error-response-schema})

(def ^:private json-transformer
  (mt/transformer
   {:name :db-sync/json}
   mt/json-transformer))

(defn- ->coercer
  ([schema] (->coercer schema false))
  ([schema private-profile?]
   (ma/coercer schema json-transformer nil
               #(ma/-fail! ::db-sync-malli-coerce
                           (if private-profile? {} (select-keys % [:value]))))))

(def ws-client-message-coercer (->coercer ws-client-message-schema))
(def profile-refresh-message-coercer (->coercer profile-refresh-message-schema true))
(def ws-server-message-coercer (->coercer ws-server-message-schema))

(def http-request-coercers
  (into {}
        (map (fn [[k schema]] [k (->coercer schema (= k :user-profile/save))]))
        http-request-schemas))

(def http-response-coercers
  (into {}
        (map (fn [[k schema]]
               [k (->coercer schema (contains? #{:user-profile/get :user-profile/save :graph-members/avatar} k))]))
        http-response-schemas))
