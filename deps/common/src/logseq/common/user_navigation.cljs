(ns logseq.common.user-navigation
  "Pure values and identity for the account-owned navigation graph."
  (:require [clojure.string :as string]
            [goog.crypt :as gcrypt]
            [goog.crypt.Sha256]
            [logseq.common.defkeywords :refer [defkeywords]]))

(defkeywords
  :selected? {:doc "An explicit personal favorite selection, including false."}
  :membership-id {:doc "Stable UUID string for one continuous favorite membership."}
  :added-at {:doc "Actual time at which a favorite membership was selected."}
  :user-navigation/context {:doc "Private runtime identity, independent of the working graph."}
  :user-navigation/changed {:doc "Private post-commit scopes; does not change a notes revision."}
  :user-navigation/snapshot {:doc "Current working scope's derived personal navigation values."}
  :user-navigation/favorites {:doc "Working-graph resource for personal favorite UUIDs."}
  :user-navigation/recents {:doc "Working-graph resource for personal recent-page UUIDs."}
  :user-navigation/favorite-status {:doc "Working-graph resource for personal membership."})

(def recents-limit 15)
(def families #{:favorite :order :visits :favorites-seed :recents-seed :transfer-once})

(defn graph-id
  "A reserved v5-shaped UUID, derived only from the verified account identity."
  [user-id]
  (let [digest (goog.crypt.Sha256.)
        _ (.update digest (gcrypt/stringToUtf8ByteArray (str "user-navigation-graph-v1/" user-id)))
        hex (gcrypt/byteArrayToHex (.digest digest))
        hex (str (subs hex 0 12) "5" (subs hex 13 16) "8" (subs hex 17 32))]
    (str (subs hex 0 8) "-" (subs hex 8 12) "-" (subs hex 12 16) "-"
         (subs hex 16 20) "-" (subs hex 20 32))))

(defn reserved-graph?
  [id owner-id]
  (and (string? owner-id) (= id (graph-id owner-id))))

(defn reserved-id-shape? [id]
  (and (string? id) (some? (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}" id))))

(defn scope-prefix [scope]
  (str "user-navigation." scope "."))

(defn ident
  [family scope discriminator]
  (when-not (contains? families family)
    (throw (ex-info "unknown navigation family" {:family family})))
  (keyword "logseq.kv" (str (scope-prefix scope) (name family)
                             (when discriminator (str "." discriminator)))))

(defn ident-parts
  [key]
  (when (and (keyword? key) (= "logseq.kv" (namespace key))
             (string/starts-with? (name key) "user-navigation."))
    (let [[_ scope family discriminator] (string/split (name key) #"\.")
          family (keyword family)]
      (when (contains? families family)
        [family scope discriminator]))))

(defn favorite-value
  "Generate the new membership once at the mutator boundary, never on replay."
  [previous selected? membership-id now]
  (if selected?
    (if (:selected? previous)
      previous
      {:selected? true :membership-id membership-id :added-at now})
    {:selected? false}))

(defn recent-visits
  "Merge actual visits; each device persists only its own bounded value."
  [visits]
  (->> visits
       (reduce (fn [result [id at]] (update result id #(max (or % 0) at))) {})
       (sort-by (fn [[id at]] [(- at) id]))
       (take recents-limit)
       vec))

(defn visit-value [previous target now]
  (recent-visits (conj (vec previous) [target now])))

(defn active-members
  [entries scope]
  (let [seed (get entries (ident :favorites-seed scope nil))
        members (into {} (map-indexed (fn [rank id] [id {:stamp [:seed id] :added-at rank}]) seed))]
    (reduce-kv
     (fn [members key value]
       (let [[family key-scope target] (ident-parts key)]
         (if (and (= family :favorite) (= scope key-scope))
           (if (:selected? value)
             (assoc members target {:stamp [:selection (:membership-id value)] :added-at (:added-at value)})
             (dissoc members target))
           members)))
     members entries)))

(defn favorites
  [entries scope]
  (let [members (active-members entries scope)
        ordered (->> (get entries (ident :order scope nil))
                     (keep (fn [[target stamp]] (when (= stamp (:stamp (get members target))) target)))
                     distinct vec)
        remaining (apply dissoc members ordered)]
    (into ordered (->> remaining (sort-by (fn [[target value]] [(:added-at value) target])) (map first)))))

(defn order-value
  [entries scope targets]
  (let [members (active-members entries scope)]
    (mapv (fn [target] [target (:stamp (get members target))]) (filter members (distinct targets)))))

(defn recents
  [entries scope]
  (let [scope-rows (keep (fn [[key value]]
                           (let [[family key-scope device] (ident-parts key)]
                             (when (= scope key-scope) [family device value]))) entries)
        visits (recent-visits (mapcat #(nth % 2) (filter #(= :visits (first %)) scope-rows)))
        seed (->> scope-rows (filter #(= :recents-seed (first %)))
                  (sort-by second) (mapcat #(nth % 2)))]
    (->> (concat (map first visits) seed) distinct (take recents-limit) vec)))

(defn snapshot [entries scope token]
  {:scope scope :token token :favorites (favorites entries scope) :recents (recents entries scope)})

(defn transfer-values
  "Move a local domain to its recorded RTC scope and the private runtime's device."
  [entries target-scope device-id]
  (into {} (map (fn [[key value]]
                 (let [[family _ discriminator] (ident-parts key)]
                   [(ident family target-scope (if (#{:visits :recents-seed} family) device-id discriminator)) value])))
        entries))

(defn resource-key [snapshot resource-key]
  (let [{:keys [scope token favorites recents]} snapshot]
    (cond
      (and (:loading? snapshot) (#{:favorites :recent-pages :favorite-status} (first resource-key))) nil
      scope
      (case (first resource-key)
        :favorites [:user-navigation/favorites scope token favorites]
        :recent-pages [:user-navigation/recents scope token recents]
        :favorite-status [:user-navigation/favorite-status scope token (str (second resource-key)) favorites]
        resource-key)
      :else resource-key)))

(defn initialization-values
  "Only fill missing fields; a completed transfer cannot be applied twice."
  [entries scope {:keys [device-id favorites-seed recents-seed transfer-id values]}]
  (let [marker (when transfer-id (ident :transfer-once scope transfer-id))]
    (if (and marker (contains? entries marker))
      {}
      (let [seed-values {(ident :favorites-seed scope nil) favorites-seed
                         (ident :recents-seed scope device-id) recents-seed}
            missing (reduce-kv (fn [result key value]
                                 (if (contains? entries key) result (assoc result key value)))
                               {} (merge values seed-values))
            visits-key (ident :visits scope device-id)
            missing (if-let [visits (get values visits-key)]
                      (assoc missing visits-key (recent-visits (concat (get entries visits-key) visits)))
                      missing)]
        (cond-> missing marker (assoc marker true))))))
