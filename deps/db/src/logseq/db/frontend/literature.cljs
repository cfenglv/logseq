(ns logseq.db.frontend.literature
  "Pure data helpers for the opt-in shared literature model."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [goog.crypt :as gcrypt]
            [goog.crypt.Sha256]
            [logseq.clj-fractional-indexing :as fractional-indexing]
            [logseq.common.defkeywords :refer [defkeyword]]
            [logseq.common.uuid :as common-uuid]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.common.order :as db-order]
            [logseq.db.frontend.literature-source :as literature-source]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.build :as db-property-build]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.page :as outliner-page]))

(defkeyword :key-uuid "The UUID of the exact shared source-key value node.")
(defkeyword :unlink "Explicitly detach a shared source value from its binding.")
(defkeyword :unlink-owner "Explicitly remove the selected Paper reference to a source value.")
(defkeyword :reassign "Move one shared source value to another existing Paper.")
(defkeyword :target-paper-unavailable "The selected destination is not a live Paper.")
(defkeyword :literature/correct-source-record "A user transaction correcting one shared source relation.")
(defkeyword :inverse? "Replay the guarded inverse of a source correction.")
(defkeyword :source-correction-blocked "A stale source history action was consumed without changing the graph.")
(defkeyword :conflicting-owners "Duplicate resolution would associate one PDF with different Papers.")
(defkeyword :pdf-filenames "Incoming PDF basenames grouped by their original Asset UUID.")
(defkeyword :literature/pdf-filenames "A native transaction adding missing PDF basenames.")
(defkeyword :literature/rename-pdf-titles "An explicit native transaction naming PDF Assets after their Paper.")
(defkeyword :annotation-count "The selected PDF's annotation count.")
(defkeyword :reference-count "The selected PDF's user reference count.")

(def identifier-properties
  {:doi :logseq.property/literature-doi
   :arxiv :logseq.property/literature-arxiv})

(defn- non-blank-string
  [value]
  (when (some? value)
    (when-not (string? value)
      (throw (ex-info "Literature identifier must be a string"
                      {:value-type (type value)})))
    (not-empty (string/trim value))))

(defn normalize-doi
  [value]
  (when-let [value (non-blank-string value)]
    (let [normalized (-> value
                         string/lower-case
                         (string/replace #"^doi:\s*" "")
                         (string/replace #"^https?://(?:dx\.)?doi\.org/" ""))]
      (when-not (re-matches #"10\.\d{4,9}/\S+" normalized)
        (throw (ex-info "Invalid DOI" {:identifier-type :doi})))
      normalized)))

(defn normalize-arxiv
  [value]
  (when-let [value (non-blank-string value)]
    (let [normalized (-> value
                         string/lower-case
                         (string/replace #"^arxiv:\s*" "")
                         (string/replace #"^https?://(?:www\.)?arxiv\.org/(?:abs|pdf)/" "")
                         (string/replace #"[?#].*$" "")
                         (string/replace #"\.pdf$" "")
                         (string/replace #"v\d+$" ""))]
      (when-not (or (re-matches #"\d{4}\.\d{4,5}" normalized)
                    (re-matches #"[a-z][a-z0-9.-]*/\d{7}" normalized))
        (throw (ex-info "Invalid arXiv identifier" {:identifier-type :arxiv})))
      normalized)))

(defn normalize-identifiers
  [identifiers]
  (doseq [identifier-type (keys identifiers)]
    (when-not (contains? identifier-properties identifier-type)
      (throw (ex-info "Unsupported literature identifier"
                      {:identifier-type identifier-type}))))
  (->> identifiers
       (keep (fn [[identifier-type value]]
               (when-let [normalized ((case identifier-type
                                        :doi normalize-doi
                                        :arxiv normalize-arxiv)
                                      value)]
                 [identifier-type normalized])))
       (into {})))



(defn available?
  "Returns true when the standard literature definitions exist."
  [db]
  (every? #(some? (d/entity db %))
          db-property/literature-property-idents))

(defn source-binding-title
  "Returns the neutral graph page title for one Zotero source binding."
  [binding-uuid]
  (when (uuid? binding-uuid)
    (str "Zotero literature · " (subs (str binding-uuid) 0 8))))

(defn build-source-binding-plan
  "Builds an idempotent ordinary-page transaction for one Zotero source.
   The page is shared graph data; credentials and local paths stay outside it."
  [db binding-uuid]
  (let [title (source-binding-title binding-uuid)
        existing (when (uuid? binding-uuid)
                   (d/entity db [:block/uuid binding-uuid]))]
    (cond
      (not (available? db))
      {:status :blocked :reason :disabled}

      (nil? title)
      {:status :blocked :reason :invalid-binding-uuid}

      (and existing
           (or (not (ldb/page? existing))
               (not= title (:block/title existing))))
      {:status :blocked :reason :binding-uuid-conflict}

      (and existing (nil? (:logseq.property/deleted-at existing)))
      {:status :existing
       :binding-uuid binding-uuid
       :title title}

      :else
      (let [{:keys [page-uuid tx-data tx-meta]}
            (outliner-page/create
             db title {:uuid binding-uuid
                       :persist-op? true
                       :split-namespace? false})]
        (cond
          (not= binding-uuid page-uuid)
          {:status :blocked :reason :binding-title-conflict}

          (empty? tx-data)
          {:status :blocked :reason :binding-page-unavailable}

          :else
          {:status :ready
           :action (if existing :restore :create)
           :binding-uuid binding-uuid
           :title title
           :tx-data tx-data
           :tx-meta tx-meta})))))

(defn- public-destination?
  [page binding-uuid]
  (and (ldb/internal-page? page)
       (not (ldb/class? page))
       (not (ldb/property? page))
       (not (ldb/journal? page))
       (nil? (:block/parent page))
       (not (ldb/hidden? page))
       (not= binding-uuid (:block/uuid page))))

(defn build-source-connection-plan
  "Resolves the ordinary public root page only during explicit connection.
   A saved UUID never falls back to a title or restores a recycled destination.
   Both native page operations are planned before either is written."
  [db binding-uuid {:keys [page-uuid title]}]
  (let [binding-plan (build-source-binding-plan db binding-uuid)]
    (if (= :blocked (:status binding-plan))
      binding-plan
      (let [db' (if (seq (:tx-data binding-plan))
                  (d/db-with db (:tx-data binding-plan))
                  db)
            destination-plan (when-not page-uuid
                               (outliner-page/create
                                db' title {:persist-op? true
                                           :split-namespace? false}))
            destination-uuid (or page-uuid (:page-uuid destination-plan))
            existing (when destination-uuid
                       (d/entity db' [:block/uuid destination-uuid]))
            planned-db (if (and (nil? existing) (seq (:tx-data destination-plan)))
                         (d/db-with db' (:tx-data destination-plan))
                         db')
            destination (when destination-uuid
                          (d/entity planned-db [:block/uuid destination-uuid]))]
        (if-not (public-destination? destination binding-uuid)
          {:status :blocked :reason :destination-unavailable}
          (let [plans (cond-> [binding-plan]
                        (nil? existing) (conj destination-plan))
                tx-data (vec (mapcat :tx-data plans))]
            {:status (if (seq tx-data) :ready :existing)
             :action (or (:action binding-plan) :create)
             :binding-uuid binding-uuid
             :destination-page-uuid destination-uuid
             :tx-data tx-data
             :tx-meta {:persist-op? true
                       :outliner-op :create-page
                       :outliner-ops (vec (mapcat #(get-in % [:tx-meta :outliner-ops])
                                                  plans))}}))))))

(def ^:private paper-by-identifier-query
  '[:find ?paper-uuid ?title
    :in $ ?property ?value
    :where
    [?value-node :block/title ?value]
    [?paper ?property ?value-node]
    [?paper :logseq.property/literature-item? true]
    [?paper :block/uuid ?paper-uuid]
    [?paper :block/title ?title]
    (not [?paper :logseq.property/deleted-at])])

(def ^:private paper-by-source-key-query
  '[:find ?paper-uuid ?title
    :in $ ?source-key
    :where
    [?key-node :block/title ?source-key]
    [?paper :logseq.property/literature-source-key ?key-node]
    [?key-node :logseq.property/literature-source-target ?binding]
    [?paper :logseq.property/literature-item? true]
    [?paper :block/uuid ?paper-uuid]
    [?paper :block/title ?title]
    (not [?binding :logseq.property/deleted-at])
    (not [?key-node :logseq.property/deleted-at])
    (not [?paper :logseq.property/deleted-at])])

(def ^:private retained-source-key-query
  '[:find ?key-node .
    :in $ ?source-key
    :where
    [?key-node :block/title ?source-key]
    [?key-node :logseq.property/created-from-property ?property]
    [?property :db/ident :logseq.property/literature-source-key]])

(defn- paper-result
  [rows]
  (let [papers (->> rows
                    (reduce (fn [result [paper-uuid title matched-by]]
                              (-> result
                                  (update-in [paper-uuid :matched-by] (fnil conj #{}) matched-by)
                                  (assoc-in [paper-uuid :block/uuid] paper-uuid)
                                  (assoc-in [paper-uuid :block/title] title)))
                            {})
                    vals
                    (sort-by (comp str :block/uuid))
                    vec)]
    {:status (case (count papers)
               0 :missing
               1 :found
               :conflict)
     :papers papers}))

(defn find-paper-by-identifiers
  "Finds literature papers by accepted, canonical DOI/arXiv values. Multiple
  identifiers that resolve to different papers return :conflict."
  [db identifiers]
  (let [identifiers (normalize-identifiers identifiers)
        rows (mapcat (fn [[identifier-type value]]
                       (map (fn [[paper-uuid title]]
                              [paper-uuid title [identifier-type value]])
                            (d/q paper-by-identifier-query
                                 db
                                 (get identifier-properties identifier-type)
                                 value)))
                     identifiers)]
    (paper-result rows)))

(defn find-paper-by-source-key
  "Finds the valid literature target of an exact source key."
  [db source-key]
  (let [source-key (non-blank-string source-key)
        rows (when source-key
               (map (fn [[paper-uuid title]]
                      [paper-uuid title [:source-key source-key]])
                    (d/q paper-by-source-key-query db source-key)))]
    (paper-result rows)))

(def ^:private paper-by-exact-bibliography-query
  '[:find ?paper-uuid ?paper-title
    :in $ ?title ?authors
    :where
    [?paper :block/title ?title]
    [?paper :logseq.property/literature-item? true]
    [?paper :logseq.property/literature-authors ?authors-node]
    [?authors-node :block/title ?authors]
    [?paper :block/uuid ?paper-uuid]
    [?paper :block/title ?paper-title]
    (not [?paper :logseq.property/deleted-at])])

(defn find-paper-by-exact-bibliography
  "Finds exact title-and-authors duplicate candidates. This is only a stop for
   an identifier-free automatic create; it does not establish paper identity."
  [db title authors]
  (let [title (non-blank-string title)
        authors (non-blank-string authors)
        rows (when (and title authors)
               (map (fn [[paper-uuid paper-title]]
                      [paper-uuid paper-title [:bibliography [title authors]]])
                    (d/q paper-by-exact-bibliography-query
                         db title authors)))]
    (paper-result rows)))

(def ^:private pdf-by-checksum-query
  '[:find ?asset-uuid ?title (pull ?asset [:logseq.property.asset/size])
    :in $ ?checksum
    :where
    [?asset :logseq.property.asset/checksum ?checksum]
    [?asset :logseq.property.asset/type "pdf"]
    [?asset :block/tags :logseq.class/Asset]
    [?asset :block/uuid ?asset-uuid]
    [?asset :block/title ?title]
    (not [?asset :logseq.property/deleted-at])])

(def ^:private paper-pdfs-query
  '[:find ?asset-uuid ?title ?size ?checksum
    :in $ ?paper-uuid
    :where
    [?paper :block/uuid ?paper-uuid]
    [?paper :logseq.property/literature-item? true]
    [?paper :logseq.property/literature-assets ?asset]
    [?asset :logseq.property.asset/type "pdf"]
    [?asset :block/tags :logseq.class/Asset]
    [?asset :logseq.property.asset/checksum ?checksum]
    [?asset :logseq.property.asset/size ?size]
    [?asset :block/uuid ?asset-uuid]
    [?asset :block/title ?title]
    (not [?paper :logseq.property/deleted-at])
    (not [?asset :logseq.property/deleted-at])])

(defn- asset-map
  [[asset-uuid title size checksum]]
  {:block/uuid asset-uuid
   :block/title title
   :logseq.property.asset/size size
   :logseq.property.asset/checksum checksum})

(defn find-pdf-by-checksum
  "Finds native PDF Assets by exact checksum without choosing among duplicate
  Asset identities."
  [db checksum]
  (let [checksum (non-blank-string checksum)
        assets (if checksum
                 (->> (d/q pdf-by-checksum-query db checksum)
                      (map (fn [[asset-uuid title size]]
                             (asset-map [asset-uuid title
                                         (:logseq.property.asset/size size) checksum])))
                      (sort-by (comp str :block/uuid))
                      vec)
                 [])]
    {:status (case (count assets)
               0 :missing
               1 :found
               :conflict)
     :assets assets}))

(declare find-paper-by-asset)

(defn find-reusable-pdf-by-checksum
  "Finds a reusable PDF only after the optional literature model is enabled."
  [db checksum]
  (if (available? db)
    (let [{:keys [status assets] :as result} (find-pdf-by-checksum db checksum)]
      (cond-> result
        (= :found status)
        (assoc :literature-status
               (:status (find-paper-by-asset db (:block/uuid (first assets)))))))
    {:status :disabled
     :assets []}))

(defn paper-pdfs
  "Returns all live native PDF Assets explicitly related to a literature paper."
  [db paper-uuid]
  (->> (d/q paper-pdfs-query db paper-uuid)
       (map asset-map)
       (sort-by (comp str :block/uuid))
       vec))

(def ^:private paper-by-asset-query
  '[:find ?paper-uuid ?title
    :in $ ?asset-uuid
    :where
    [?asset :block/uuid ?asset-uuid]
    [?paper :logseq.property/literature-assets ?asset]
    [?paper :logseq.property/literature-item? true]
    [?paper :block/uuid ?paper-uuid]
    [?paper :block/title ?title]
    (not [?paper :logseq.property/deleted-at])])

(defn find-paper-by-asset
  "Finds live literature items that explicitly own one PDF Asset."
  [db asset-uuid]
  (paper-result
   (map (fn [[paper-uuid title]]
          [paper-uuid title [:asset asset-uuid]])
        (d/q paper-by-asset-query db asset-uuid))))

(def ^:private supported-automatic-types
  #{"journal-article" "preprint" "paper-conference"
     "book" "chapter" "report" "thesis"})

(defn- normalize-bibliographic-text
  [value]
  (some-> value
          str
          (.normalize "NFKC")
          string/lower-case
          (string/replace #"\s+" " ")
          ;; PDF text layers commonly insert layout spaces between CJK glyphs.
          ;; Remove only spaces touching CJK characters; preserve Latin spaces.
          (string/replace #"([\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af])\s+" "$1")
          (string/replace #"\s+([\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af])" "$1")
          string/trim
          not-empty))

(defn- property-value-title
  [entity property]
  (some-> (get entity property) :block/title non-blank-string))

(defn- paper-bibliographic-record
  [paper]
  {:title (:block/title paper)
   :authors (property-value-title paper :logseq.property/literature-authors)
   :type (some-> (property-value-title paper :logseq.property/literature-type)
                 string/lower-case)})

(defn- split-authors
  [value]
  (->> (string/split (or value "") #"[;\n]+")
       (keep normalize-bibliographic-text)
       vec))

(defn- ascii-alphanumeric?
  [value]
  (boolean (and (seq value) (re-matches #"[a-z0-9]" value))))

(defn- contains-author?
  [text author]
  (loop [from-index 0]
    (let [index (.indexOf text author from-index)]
      (if (neg? index)
        false
        (let [end-index (+ index (count author))
              starts-with-ascii? (ascii-alphanumeric? (subs author 0 1))
              ends-with-ascii? (ascii-alphanumeric? (subs author (dec (count author))))
              before (when (pos? index) (subs text (dec index) index))
              after (when (< end-index (count text))
                      (subs text end-index (inc end-index)))
              start-boundary? (or (not starts-with-ascii?)
                                  (zero? index)
                                  (not (ascii-alphanumeric? before)))
              end-boundary? (or (not ends-with-ascii?)
                                (= end-index (count text))
                                (not (ascii-alphanumeric? after)))]
          (if (and start-boundary? end-boundary?)
            true
            (recur (inc index))))))))

(defn- author-match?
  [paper-authors author-texts]
  (let [paper-authors (split-authors paper-authors)
        evidence (keep normalize-bibliographic-text author-texts)]
    (boolean
     (some (fn [author]
             (let [given-family
                   (when-let [[_ family given]
                              (re-matches #"([^,]+),\s*([^,]+)" author)]
                     (normalize-bibliographic-text
                      (str given " " family)))]
               (some (fn [text]
                       (or (contains-author? text author)
                           (and given-family
                                (contains-author? text given-family))))
                     evidence)))
           paper-authors))))

(defn same-bibliography?
  "Compares two identified records without fuzzy title scores."
  [left right]
  (and (some? (normalize-bibliographic-text (:title left)))
       (= (normalize-bibliographic-text (:title left))
          (normalize-bibliographic-text (:title right)))
       (or (author-match? (:authors left) [(:authors right)])
           (author-match? (:authors right) [(:authors left)]))))

(defn needs-verified-metadata?
  "These link-only stops may be resolved by independently verified metadata;
   an identity conflict or invalid extraction may not."
  [result]
  (and (= :blocked (:status result))
       (contains? #{:identifier-not-found :partial-identifier-match
                    :title-mismatch :author-mismatch :missing-type
                    :unsupported-type :missing-authors}
                  (:reason result))))

(defn- live-pdf-asset
  [db asset-uuid]
  (let [asset (d/entity db [:block/uuid asset-uuid])
        tag-idents (into #{} (keep :db/ident) (:block/tags asset))]
    (when (and asset
               (nil? (:logseq.property/deleted-at asset))
               (= "pdf" (:logseq.property.asset/type asset))
               (contains? tag-idents :logseq.class/Asset))
      asset)))

(defn- arxiv-record-id-match?
  [evidence-id record-id]
  (when (and (string? evidence-id) (string? record-id))
    (let [evidence-id (string/lower-case (string/trim evidence-id))
          record-id (string/lower-case (string/trim record-id))]
      (and (re-matches #"(?:\d{4}\.\d{4,5}|[a-z][a-z0-9.-]*/\d{7})(?:v[1-9]\d*)?" record-id)
           (= (normalize-arxiv evidence-id)
              (normalize-arxiv record-id))
           (or (not (re-find #"v[1-9]\d*$" evidence-id))
               (= evidence-id record-id))))))

(defn- live-literature-paper
  [db paper-uuid]
  (let [paper (when (uuid? paper-uuid)
                (d/entity db [:block/uuid paper-uuid]))]
    (when (and paper
               (true? (:logseq.property/literature-item? paper))
               (nil? (:logseq.property/deleted-at paper)))
      paper)))

(def ^:private paper-sources-pull
  [:block/uuid
   :logseq.property/literature-item?
   :logseq.property/deleted-at
   {:logseq.property/literature-source-key
    [:block/uuid :block/title :logseq.property/deleted-at
     {:logseq.property/created-from-property [:db/ident]}
     {:logseq.property/literature-source-target
      [:block/uuid :logseq.property/deleted-at]}]}])

(defn paper-sources
  "Reads only the selected Paper's source values, including explicitly detached
   values. Local client activation does not determine shared source ownership."
  [db paper-uuid]
  (let [paper (when (uuid? paper-uuid)
                (d/pull db paper-sources-pull [:block/uuid paper-uuid]))]
    (if-not (and (true? (:logseq.property/literature-item? paper))
                 (nil? (:logseq.property/deleted-at paper)))
      {:status :blocked :reason :paper-unavailable}
      {:status :ok
       :sources
       (->> (:logseq.property/literature-source-key paper)
            (filter #(and (nil? (:logseq.property/deleted-at %))
                          (= :logseq.property/literature-source-key
                             (get-in % [:logseq.property/created-from-property :db/ident]))))
            (map (fn [key-node]
                   (let [binding (:logseq.property/literature-source-target key-node)]
                     {:key-uuid (:block/uuid key-node)
                      :source-key (:block/title key-node)
                      :binding-uuid (:block/uuid binding)
                      :active? (boolean (and (:block/uuid binding)
                                             (nil? (:logseq.property/deleted-at binding))))})))
            (sort-by (juxt :source-key (comp str :key-uuid)))
            vec)})))

(defn build-correct-source-record-plan
  "Revalidates an exact active source value and builds one ordinary user
   transaction. Owner unlink retains the key and binding; binding unlink also
   retains the Paper reference."
  [db paper-uuid {:keys [key-uuid source-key binding-uuid active?] :as source} action target-paper-uuid]
  (when-not (contains? #{:unlink :unlink-owner :reassign} action)
    (throw (ex-info "Unsupported source correction action" {:action action})))
  (let [paper (live-literature-paper db paper-uuid)
        key-node (when (uuid? key-uuid) (d/entity db [:block/uuid key-uuid]))
        binding (:logseq.property/literature-source-target key-node)
        owner (when (not= :unlink-owner action)
                (literature-source/find-source-record-owner db binding-uuid source-key))]
    (cond
      (nil? paper)
      {:status :blocked :reason :paper-unavailable}

      (or (nil? key-node)
          (some? (:logseq.property/deleted-at key-node))
          (not (true? active?))
          (nil? binding)
          (some? (:logseq.property/deleted-at binding)))
      {:status :blocked :reason :source-link-unavailable}

      (or (not= source-key (:block/title key-node))
          (not= :logseq.property/literature-source-key
                (:db/ident (:logseq.property/created-from-property key-node)))
          (not= binding-uuid (:block/uuid binding))
          (not (some #(= (:db/id key-node) (:db/id %))
                     (:logseq.property/literature-source-key paper))))
      {:status :blocked :reason :source-link-mismatch}

      (and (not= :unlink-owner action) (= :conflict (:status owner)))
      {:status :blocked :reason :source-link-conflict}

      (and (not= :unlink-owner action) (not= paper-uuid (:paper-uuid owner)))
      {:status :blocked :reason :source-link-unavailable}

      (and (= :reassign action)
           (nil? (live-literature-paper db target-paper-uuid)))
      {:status :blocked :reason :target-paper-unavailable}

      (and (= :reassign action) (= paper-uuid target-paper-uuid))
      {:status :blocked :reason :source-link-mismatch}

      :else
      (let [context {:paper-uuid paper-uuid :source source :action action
                     :target-paper-uuid target-paper-uuid :inverse? false}
            tx-data (case action
                      :unlink
                      [[:db/retract [:block/uuid key-uuid]
                        :logseq.property/literature-source-target [:block/uuid binding-uuid]]]
                      :unlink-owner
                      [[:db/retract [:block/uuid paper-uuid]
                        :logseq.property/literature-source-key [:block/uuid key-uuid]]]
                      :reassign
                      [[:db/retract [:block/uuid paper-uuid]
                        :logseq.property/literature-source-key [:block/uuid key-uuid]]
                       [:db/add [:block/uuid target-paper-uuid]
                        :logseq.property/literature-source-key [:block/uuid key-uuid]]])
            inverse-data (case action
                           :unlink
                           [[:db/add [:block/uuid key-uuid]
                             :logseq.property/literature-source-target [:block/uuid binding-uuid]]]
                           :unlink-owner
                           [[:db/add [:block/uuid paper-uuid]
                             :logseq.property/literature-source-key [:block/uuid key-uuid]]]
                           :reassign
                           [[:db/retract [:block/uuid target-paper-uuid]
                             :logseq.property/literature-source-key [:block/uuid key-uuid]]
                            [:db/add [:block/uuid paper-uuid]
                             :logseq.property/literature-source-key [:block/uuid key-uuid]]])]
        {:status :ready
         :action action
         :paper-uuid paper-uuid
         :key-uuid key-uuid
         :ops [[:transact [tx-data {:literature/correct-source-record context}]]]
         :inverse-ops [[:transact [inverse-data
                                  {:literature/correct-source-record (assoc context :inverse? true)}]]]}))))

(defn build-correct-source-record-replay-plan
  "Replans only this source correction against the current graph, including its
   exact inverse. Owner unlink restores only its selected edge; binding unlink
   restores an inactive key only to its unchanged owner."
  [db {:keys [paper-uuid source action target-paper-uuid inverse?]}]
  (cond
    (and inverse? (= :unlink-owner action))
    (let [{:keys [key-uuid source-key binding-uuid active?]} source
          paper (live-literature-paper db paper-uuid)
          key-node (when (uuid? key-uuid) (d/entity db [:block/uuid key-uuid]))
          binding (:logseq.property/literature-source-target key-node)]
      (if (and paper key-node binding
               (true? active?)
               (nil? (:logseq.property/deleted-at key-node))
               (nil? (:logseq.property/deleted-at binding))
               (= source-key (:block/title key-node))
               (= :logseq.property/literature-source-key
                  (:db/ident (:logseq.property/created-from-property key-node)))
               (= binding-uuid (:block/uuid binding))
               (not-any? #(and (= source-key (:block/title %))
                               (= binding-uuid (:block/uuid (:logseq.property/literature-source-target %))))
                         (:logseq.property/literature-source-key paper)))
        {:status :ready
         :tx-data [[:db/add [:block/uuid paper-uuid]
                    :logseq.property/literature-source-key [:block/uuid key-uuid]]]}
        {:status :blocked :reason :source-link-unavailable}))

    (and inverse? (= :unlink action))
    (let [{:keys [key-uuid source-key binding-uuid]} source
          paper (live-literature-paper db paper-uuid)
          key-node (d/entity db [:block/uuid key-uuid])
          binding (d/entity db [:block/uuid binding-uuid])
          owners (when key-node
                   (->> (d/datoms db :avet :logseq.property/literature-source-key (:db/id key-node))
                        (keep #(live-literature-paper db (:block/uuid (d/entity db (:e %)))))
                        (map :block/uuid)
                        distinct
                        vec))]
      (if (and paper key-node binding
               (nil? (:logseq.property/deleted-at key-node))
               (nil? (:logseq.property/deleted-at binding))
               (= source-key (:block/title key-node))
               (= :logseq.property/literature-source-key
                  (:db/ident (:logseq.property/created-from-property key-node)))
               (nil? (:logseq.property/literature-source-target key-node))
               (= [paper-uuid] owners)
               (= :missing (:status (literature-source/find-source-record-owner
                                     db binding-uuid source-key))))
        {:status :ready
         :tx-data [[:db/add [:block/uuid key-uuid]
                    :logseq.property/literature-source-target [:block/uuid binding-uuid]]]}
        {:status :blocked :reason :source-link-unavailable}))

    :else
    (let [plan (build-correct-source-record-plan
                db (if inverse? target-paper-uuid paper-uuid) source action
                (if inverse? paper-uuid target-paper-uuid))]
      (if (= :ready (:status plan))
        {:status :ready :tx-data (get-in plan [:ops 0 1 0])}
        (dissoc plan :ops :inverse-ops)))))

(defn build-keep-pdf-plan
  "Persists an explicit ordinary-PDF choice on this Asset only. An association
   already committed in the current graph requires an explicit unlink first."
  [db asset-uuid]
  (let [asset (live-pdf-asset db asset-uuid)
        by-asset (find-paper-by-asset db asset-uuid)]
    (cond
      (not (available? db)) {:status :blocked :reason :disabled}
      (nil? asset) {:status :blocked :reason :asset-unavailable}
      (not= :missing (:status by-asset))
      {:status :blocked :reason :asset-already-linked}
      (true? (:logseq.property.asset/literature-auto-excluded? asset))
      {:status :kept :asset-uuid asset-uuid}
      :else
      {:status :ready :action :keep :asset-uuid asset-uuid
       :tx-data [[:db/add [:block/uuid asset-uuid]
                  :logseq.property.asset/literature-auto-excluded? true]]})))

(defn- clear-automatic-exclusion-tx
  [asset]
  (when (true? (:logseq.property.asset/literature-auto-excluded? asset))
    [[:db/retract [:block/uuid (:block/uuid asset)]
      :logseq.property.asset/literature-auto-excluded? true]]))

(declare pdf-title-tx)

(defn build-link-pdf-plan
  "Builds one current-db plan for explicitly linking a live PDF Asset to a
   literature item the user selected. Search results never authorize this
   write: the target and the asset ownership are checked again here."
  [db asset-uuid target-paper-uuid & [{:keys [preserve-title?]}]]
  (let [asset (live-pdf-asset db asset-uuid)
        target (live-literature-paper db target-paper-uuid)
        by-asset (find-paper-by-asset db asset-uuid)]
    (cond
      (not (available? db))
      {:status :blocked :reason :disabled}

      (nil? asset)
      {:status :blocked :reason :asset-unavailable}

      (nil? target)
      {:status :blocked :reason :target-paper-unavailable}

      (= :conflict (:status by-asset))
      {:status :blocked
       :reason :asset-linked-to-multiple-papers
       :papers (:papers by-asset)}

      (= :found (:status by-asset))
      (let [paper (first (:papers by-asset))]
        (if (= target-paper-uuid (:block/uuid paper))
          (if-let [tx (clear-automatic-exclusion-tx asset)]
            {:status :ready :action :link :paper-uuid target-paper-uuid
             :asset-uuid asset-uuid :tx-data tx}
            {:status :already-linked :paper paper :paper-uuid target-paper-uuid})
          {:status :blocked
           :reason :asset-linked-to-another-paper
           :paper paper}))

      :else
      {:status :ready
       :action :link
       :paper-uuid target-paper-uuid
       :asset-uuid asset-uuid
       :tx-data (into [[:db/add [:block/uuid target-paper-uuid]
                        :logseq.property/literature-assets
                        [:block/uuid asset-uuid]]]
                      (concat (clear-automatic-exclusion-tx asset)
                              (when-not preserve-title?
                                (pdf-title-tx db asset (:block/title target)))))})))

(defn build-auto-associate-pdf-plan
  "Associates a source attachment without overriding Keep or its own name.
   Restores source names previously replaced with the Paper title."
  [db asset-uuid target-paper-uuid & [attachment-title]]
  (if (and (available? db)
           (true? (:logseq.property.asset/literature-auto-excluded?
                   (live-pdf-asset db asset-uuid))))
    {:status :blocked :reason :automatic-excluded}
    (let [plan (build-link-pdf-plan db asset-uuid target-paper-uuid {:preserve-title? true})
          asset (live-pdf-asset db asset-uuid)
          paper (live-literature-paper db target-paper-uuid)
          title-tx (when (and (contains? #{:ready :already-linked} (:status plan))
                              (= (:block/title asset) (:block/title paper)))
                     (pdf-title-tx db asset attachment-title))]
      (if (seq title-tx)
        (-> plan
            (assoc :status :ready :action :link
                   :paper-uuid target-paper-uuid :asset-uuid asset-uuid)
            (update :tx-data #(into (vec %) title-tx)))
        plan))))

(defn build-auto-link-pdf-plan
  "Builds an automatic link plan only when complete extraction evidence and
   every supplied identifier confirm one existing literature item. This path
   never creates or edits a bibliography item."
  [db asset-uuid {:keys [identifiers title author-texts extraction-status
                         usable? title-conflict? identifier-ambiguous?]}]
  (let [evidence-title title
        identifiers (normalize-identifiers (or identifiers {}))
        expected-matches (set (map vec identifiers))]
    (cond
      (not (available? db))
      {:status :blocked :reason :disabled}

      (true? (:logseq.property.asset/literature-auto-excluded?
              (live-pdf-asset db asset-uuid)))
      {:status :blocked :reason :automatic-excluded}

      (not= :complete extraction-status)
      {:status :blocked :reason :incomplete-extraction}

      (not (true? usable?))
      {:status :blocked :reason :unusable-extraction}

      title-conflict?
      {:status :blocked :reason :title-conflict}

      identifier-ambiguous?
      {:status :blocked :reason :ambiguous-identifiers}

      (empty? identifiers)
      {:status :blocked :reason :missing-identifiers}

      (nil? (normalize-bibliographic-text evidence-title))
      {:status :blocked :reason :missing-title}

      (empty? (keep normalize-bibliographic-text author-texts))
      {:status :blocked :reason :missing-author-evidence}

      :else
      (let [by-identifiers (find-paper-by-identifiers db identifiers)]
        (cond
          (= :missing (:status by-identifiers))
          {:status :blocked :reason :identifier-not-found}

          (= :conflict (:status by-identifiers))
          {:status :blocked
           :reason :identifier-conflict
           :papers (:papers by-identifiers)}

          :else
          (let [{:keys [matched-by] :as found-paper}
                (first (:papers by-identifiers))]
            (if (not= expected-matches matched-by)
              {:status :blocked
               :reason :partial-identifier-match
               :paper found-paper}
              (let [paper (d/entity db [:block/uuid (:block/uuid found-paper)])
                    {paper-title :title
                     :keys [authors type]} (paper-bibliographic-record paper)]
                (cond
                  (not= (normalize-bibliographic-text paper-title)
                        (normalize-bibliographic-text evidence-title))
                  {:status :blocked :reason :title-mismatch}

                  (nil? type)
                  {:status :blocked :reason :missing-type}

                  (not (contains? supported-automatic-types type))
                  {:status :blocked :reason :unsupported-type}

                  (nil? authors)
                  {:status :blocked :reason :missing-authors}

                  (not (author-match? authors author-texts))
                  {:status :blocked :reason :author-mismatch}

                  :else
                  (build-link-pdf-plan db asset-uuid (:block/uuid found-paper)))))))))))

(defn- next-sibling-order
  [asset]
  (let [current-order (:block/order asset)]
    (->> (:block/_parent (:block/parent asset))
         (keep :block/order)
         (filter #(pos? (compare % current-order)))
         sort
         first)))

(defn- identifier-property-values
  [identifiers]
  (->> (normalize-identifiers identifiers)
       (map (fn [[identifier-type value]]
              [(get identifier-properties identifier-type) value]))
       (into {})))

(def ^:private source-record-metadata-fields
  [{:field :title :property :block/title}
   {:field :authors :property :logseq.property/literature-authors}
   {:field :date :property :logseq.property/literature-date}
   {:field :publication :property :logseq.property/literature-publication}
   {:field :type :property :logseq.property/literature-type}
   {:field :doi :property :logseq.property/literature-doi :identity? true}
   {:field :arxiv :property :logseq.property/literature-arxiv :identity? true}
   {:field :url :property :logseq.property/literature-url}
   {:field :citekey :property :logseq.property/literature-citekey}])

(def ^:private source-record-metadata-pull
  (into [:db/id
         :block/uuid
         :block/title
         :logseq.property/literature-item?
         :logseq.property/deleted-at]
        (keep (fn [{:keys [property]}]
                (when-not (= :block/title property)
                  {property
                   [:db/id
                    :block/uuid
                    :block/title]})))
        source-record-metadata-fields))

(defn search-papers-for-candidate
  "Resolves an indexed Paper, accepted public value or PDF name to its live Papers."
  [db candidate-uuid]
  (let [candidate (d/entity db [:block/uuid candidate-uuid])
        alias-source (ldb/get-alias-source-page db (:db/id candidate))
        property (:db/ident (:logseq.property/created-from-property candidate))
        paper (:block/parent candidate)]
    (when-not (ldb/hidden? candidate)
      (->> (cond
             (true? (:logseq.property/literature-item? candidate))
             [candidate]

             (live-pdf-asset db candidate-uuid)
             (filter #(true? (:logseq.property/literature-item? %))
                     (:logseq.property/_literature-assets candidate))

             alias-source
             (search-papers-for-candidate db (:block/uuid alias-source))

             (:block/link candidate)
             (search-papers-for-candidate db (:block/uuid (:block/link candidate)))

             (and (= :logseq.property/literature-aliases property)
                  (some #(= (:db/id candidate) (:db/id %)) (get paper property))
                  (or (true? (:logseq.property/literature-item? paper))
                      (live-pdf-asset db (:block/uuid paper))))
             (search-papers-for-candidate db (:block/uuid paper))

             (and (true? (:logseq.property/literature-item? paper))
                  (some (fn [{metadata-property :property}]
                          (and (not= :block/title metadata-property)
                               (= property metadata-property)))
                        source-record-metadata-fields)
                  (= (:db/id candidate) (:db/id (get paper property))))
             [paper])
           (remove ldb/hidden?)))))

(defn build-add-pdf-filenames-tx
  "Adds missing basenames using the standard native property definitions."
  [db pdf-filenames]
  (when (and (available? db) (seq pdf-filenames))
    (let [property (d/entity db :logseq.property/literature-aliases)]
      (into
       []
       (mapcat
        (fn [[asset-uuid names]]
          (when-let [asset (live-pdf-asset db asset-uuid)]
            (let [existing (set (map :block/title (:logseq.property/literature-aliases asset)))
                  basenames (->> names (map #(last (string/split % #"[\\/]"))) set)]
              (mapcat
               (fn [filename]
                 (let [value (db-property-build/build-property-value-block
                              asset property filename)]
                   [value
                    [:db/add (:db/id asset) :logseq.property/literature-aliases
                     [:block/uuid (:block/uuid value)]]]))
               (remove #(or (string/blank? %) (contains? existing %)) basenames)))))
        pdf-filenames)))))

(defn- pdf-title-tx
  [db asset title]
  (when (and (not (string/blank? title)) (not= title (:block/title asset)))
    (into (vec (build-add-pdf-filenames-tx
                db {(:block/uuid asset) #{(:block/title asset)}}))
          (:tx-data (outliner-core/save-block
                     db {:block/uuid (:block/uuid asset) :block/title title} {})))))

(defn build-rename-paper-pdfs-plan
  "Names a Paper's sole PDF through native saves; multiple attachments keep their names."
  [db paper-uuid]
  (let [paper (live-literature-paper db paper-uuid)
        assets (when paper (paper-pdfs db paper-uuid))]
    (cond
      (not (available? db)) {:status :blocked :reason :disabled}
      (or (nil? paper) (string/blank? (:block/title paper)))
      {:status :blocked :reason :target-paper-unavailable}
      (some #(= :conflict (:status (find-paper-by-asset db (:block/uuid %)))) assets)
      {:status :blocked :reason :asset-linked-to-multiple-papers}
      :else
      (let [changed (when (= 1 (count assets))
                      (filterv #(not= (:block/title paper) (:block/title %)) assets))]
        {:status :ready :count (count changed)
         :tx-data (into [] (mapcat #(pdf-title-tx db % (:block/title paper))) changed)}))))

(defn- normalize-metadata-value
  [field value]
  (case field
    :doi (when (some? value)
           (try
             (normalize-doi value)
             (catch :default _error ::invalid-identifier)))
    :arxiv (when (some? value)
             (try
               (normalize-arxiv value)
               (catch :default _error ::invalid-identifier)))
    :type (some-> value non-blank-string string/lower-case)
    (non-blank-string value)))

(defn- property-value-state
  [paper property]
  (let [value-node (get paper property)]
    {:value (:block/title value-node)
     :value-node-uuid (:block/uuid value-node)}))

(defn build-source-record-metadata-plan
  "Compares one current source record with an existing paper without mutating db.
   Missing and different graph values require review. Source identity conflicts
   block changes; the explicit DOI correction caller checks ownership first."
  [db paper-uuid source-record & [correct-identifiers?]]
  (let [paper (when (uuid? paper-uuid)
                (d/pull db source-record-metadata-pull
                        [:block/uuid paper-uuid]))
        paper (when (and paper
                         (true? (:logseq.property/literature-item? paper))
                         (nil? (:logseq.property/deleted-at paper)))
                paper)]
    (cond
      (nil? paper)
      {:status :blocked :reason :paper-unavailable}

      :else
      (let [changes
            (->> source-record-metadata-fields
                 (keep
                  (fn [{:keys [field property identity?]}]
                    (let [source-value (normalize-metadata-value
                                        field (get source-record field))]
                      (when (and source-value
                                 (not= ::invalid-identifier source-value))
                        (let [{:keys [value value-node-uuid]}
                              (if (= :block/title property)
                                {:value (:block/title paper)}
                                (property-value-state paper property))
                              current-value (normalize-metadata-value field value)]
                          (cond
                            (= source-value current-value)
                            nil

                            (and identity? current-value (not correct-identifiers?))
                            {:field field
                             :property property
                             :kind :identity-conflict
                             :current value
                             :source source-value}

                            :else
                            (cond->
                             {:field field
                              :property property
                              :kind (if current-value
                                      :different-value
                                      :missing-without-permission)
                              :current value
                              :source source-value}
                              value-node-uuid
                              (assoc :value-node-uuid value-node-uuid))))))))
                 vec)
            identity-conflicts (filterv #(= :identity-conflict (:kind %)) changes)
            reviews (filterv #(contains? #{:different-value
                                           :missing-without-permission}
                                         (:kind %))
                             changes)]
        (cond
          (seq identity-conflicts)
          {:status :blocked
           :reason :identifier-metadata-conflict
           :paper-uuid paper-uuid
           :changes identity-conflicts}

          (seq reviews)
          {:status :manual
           :reason :metadata-review
           :paper-uuid paper-uuid
           :changes reviews}

          :else
          {:status :already-imported
           :paper {:block/uuid paper-uuid
                   :block/title (:block/title paper)}})))))

(defn build-linked-source-record-metadata-plan
  "Revalidates that the source record still belongs to the unique live link
   between one binding and paper, then builds the read-only metadata plan."
  [db binding-uuid paper-uuid source-record]
  (let [link (literature-source/find-source-record-link db binding-uuid paper-uuid)
        source-key (non-blank-string (:source-key source-record))]
    (case (:status link)
      :missing
      {:status :blocked :reason :source-link-unavailable}

      :conflict
      {:status :blocked
       :reason :source-link-conflict
       :source-keys (:source-keys link)}

      :found
      (if (= source-key (:source-key link))
        (build-source-record-metadata-plan
         db paper-uuid source-record)
        {:status :blocked :reason :source-link-mismatch})

      {:status :blocked :reason :source-link-unavailable})))

(defn- metadata-change-signature
  [change]
  (select-keys change [:field :kind :current :source]))

(defn- metadata-value-node-uuid
  [paper-uuid property]
  (common-uuid/gen-uuid
   :builtin-block-uuid
   (str "logseq-literature-metadata-v1/" paper-uuid "/" property)))

(defn- build-metadata-change-tx-data
  [db paper-uuid {:keys [property source value-node-uuid]}]
  (if (= :block/title property)
    (let [paper (d/entity db [:block/uuid paper-uuid])]
      (into (:tx-data
             (outliner-core/save-block
              db {:block/uuid paper-uuid :block/title source} {}))
            (mapcat #(when (and (= "pdf" (:logseq.property.asset/type %))
                               (= (:block/title paper) (:block/title %)))
                       (pdf-title-tx db % source)))
            (:logseq.property/literature-assets paper)))
    (if value-node-uuid
      (:tx-data
       (outliner-core/save-block
        db {:block/uuid value-node-uuid
            :block/title source}
        {}))
      (let [paper (d/entity db [:block/uuid paper-uuid])
            property-entity (d/entity db property)
            node-uuid (metadata-value-node-uuid paper-uuid property)
            value-node (db-property-build/build-property-value-block
                        paper property-entity source :block-uuid node-uuid)
            paper-tx (:tx-data
                      (outliner-core/save-block
                       db (assoc {:block/uuid paper-uuid}
                                 property [:block/uuid node-uuid])
                       {}))]
        (into [value-node] paper-tx)))))

(defn- build-accepted-metadata-plan
  [db paper-uuid metadata-plan accepted-changes]
    (cond
      (not= :manual (:status metadata-plan))
      (if (= :already-imported (:status metadata-plan))
        {:status :blocked :reason :metadata-plan-stale}
        metadata-plan)

      (not (vector? accepted-changes))
      {:status :blocked :reason :invalid-metadata-selection}

      (empty? accepted-changes)
      {:status :blocked :reason :no-metadata-fields-selected}

      (not= (count accepted-changes)
            (count (distinct (map :field accepted-changes))))
      {:status :blocked :reason :duplicate-metadata-fields}

      :else
      (let [requested (set (map metadata-change-signature accepted-changes))
            current-changes (:changes metadata-plan)
            current-signatures (set (map metadata-change-signature current-changes))]
        (if-not (every? current-signatures requested)
          {:status :blocked :reason :metadata-plan-stale}
          (let [selected (filterv #(contains? requested
                                              (metadata-change-signature %))
                                  current-changes)
                tx-data (into []
                              (mapcat #(build-metadata-change-tx-data
                                        db paper-uuid %))
                              selected)]
            {:status :ready
             :action :apply-source-record-metadata
             :paper-uuid paper-uuid
             :changes selected
             :ops [[:transact [tx-data {}]]]})))))

(defn build-apply-source-record-metadata-plan
  "Applies only the current source changes the user accepted."
  [db binding-uuid paper-uuid source-record accepted-changes]
  (build-accepted-metadata-plan
   db paper-uuid
   (build-linked-source-record-metadata-plan db binding-uuid paper-uuid source-record)
   accepted-changes))

(defn- sha256-hex
  [value]
  (let [digest (goog.crypt.Sha256.)]
    (.update digest value)
    (gcrypt/byteArrayToHex (.digest digest))))

(defn source-record-paper-uuid
  "Returns a deterministic UUIDv8 only for a normalized scholarly identifier.
   Source keys are intentionally excluded because separate personal libraries
   can assign different keys to the same paper."
  [{:keys [doi arxiv]}]
  (let [{:keys [doi arxiv]} (normalize-identifiers {:doi doi :arxiv arxiv})
        identity (cond
                   doi (str "doi:" doi)
                   arxiv (str "arxiv:" arxiv))]
    (when identity
      (let [hex (sha256-hex (str "logseq-literature-paper-v1/" identity))
            uuid-hex (str (subs hex 0 12) "8" (subs hex 13 16)
                          "8" (subs hex 17 32))]
        (uuid (str (subs uuid-hex 0 8) "-"
                   (subs uuid-hex 8 12) "-"
                   (subs uuid-hex 12 16) "-"
                   (subs uuid-hex 16 20) "-"
                   (subs uuid-hex 20 32)))))))

(defn source-registration-paper-uuid
  "Uses an explicitly chosen manual UUID only for an identifier-free source.
   Automatic records retain their scholarly identifier identity."
  [{:keys [paper-uuid create? doi arxiv] :as source-record}]
  (if (contains? source-record :paper-uuid)
    (do
      (when-not (and (uuid? paper-uuid)
                     (boolean? create?)
                     (empty? (normalize-identifiers {:doi doi :arxiv arxiv})))
        (throw (ex-info "Invalid manual source choice" {})))
      paper-uuid)
    (source-record-paper-uuid source-record)))

(defn- source-record-seed-title
  "Returns the same honest placeholder for every client that sees an identifier.
   Provider metadata is deliberately excluded because concurrent offline source
   records can disagree and RTC persists resolved datoms rather than CAS intent."
  [{:keys [doi arxiv]}]
  (let [{:keys [doi arxiv]} (normalize-identifiers {:doi doi :arxiv arxiv})]
    (cond
      doi (str "DOI: " doi)
      arxiv (str "arXiv: " arxiv))))

(defn- live-source-binding
  [db binding-uuid]
  (let [binding (when (uuid? binding-uuid)
                  (d/entity db [:block/uuid binding-uuid]))]
    (when (and binding
               (not (ldb/recycled? binding)))
      binding)))

(defn- build-source-link-tx
  [paper source-key binding]
  (let [source-values
        (db-property-build/build-property-values-tx-m
         binding
         {:logseq.property/literature-source-key
          #{{:value source-key
             :attributes
             {:logseq.property/literature-source-target
              [:block/uuid (:block/uuid binding)]}}}}
         :pure? true
         :pvalue-map? true)
        value-blocks (get source-values
                          :logseq.property/literature-source-key)
        paper-update
        (merge {:db/id (:db/id paper)}
               (db-property-build/build-properties-with-ref-values
                source-values))]
    (into (vec value-blocks) [paper-update])))

(defn build-register-source-record-plan
  "Builds one current-db plan for a normalized, authorized top-level source
   record. Existing source/identifier matches are linked; identifier-free exact
   title-and-authors candidates stop for manual review. New papers use a
   deterministic identifier UUID, or an explicit manual UUID for an identifier-free
   record. The first source seeds known metadata in its create transaction;
   selecting an existing paper only adds a source relation."
  [db binding-uuid destination-page-uuid {:keys [classification source-key title authors doi arxiv]
                    :as source-record}]
  (let [binding (live-source-binding db binding-uuid)
        destination (when (uuid? destination-page-uuid)
                      (d/entity db [:block/uuid destination-page-uuid]))
        source-key (non-blank-string source-key)
        title (non-blank-string title)
        authors (non-blank-string authors)
        chosen-uuid (:paper-uuid source-record)
        registration-uuid (source-registration-paper-uuid source-record)]
    (cond
      (not (available? db))
      {:status :blocked :reason :disabled}

      (nil? binding)
      {:status :blocked :reason :source-binding-unavailable}

      (not (public-destination? destination binding-uuid))
      {:status :blocked :reason :destination-unavailable}

      (not= :bibliographic-item classification)
      {:status :blocked :reason :not-top-level-bibliography}

      (nil? source-key)
      {:status :blocked :reason :missing-source-key}

      :else
      (let [by-source (find-paper-by-source-key db source-key)]
        (cond
          (= :conflict (:status by-source))
          {:status :blocked
           :reason :source-key-conflict
           :papers (:papers by-source)}

          (= :found (:status by-source))
          {:status :already-imported
           :paper-uuid (:block/uuid (first (:papers by-source)))
           :paper (first (:papers by-source))}

          ;; A retained native value records unlink, recycle, or paper deletion.
          ;; Only an explicit restore should make this source active again.
          (d/q retained-source-key-query db source-key)
          {:status :blocked :reason :source-link-unavailable}

          (and chosen-uuid (false? (:create? source-record)))
          (if-let [paper (live-literature-paper db chosen-uuid)]
            {:status :ready
             :action :link
             :paper-uuid chosen-uuid
             :source-key source-key
             :tx-data (build-source-link-tx paper source-key binding)}
            {:status :blocked :reason :paper-uuid-conflict})

          :else
          (let [identifiers (normalize-identifiers {:doi doi :arxiv arxiv})
                by-identifiers (find-paper-by-identifiers db identifiers)]
            (cond
              (= :conflict (:status by-identifiers))
              {:status :blocked
               :reason :identifier-conflict
               :papers (:papers by-identifiers)}

              (= :found (:status by-identifiers))
              (let [paper (first (:papers by-identifiers))]
                {:status :ready
                 :action :link
                 :paper-uuid (:block/uuid paper)
                 :source-key source-key
                 :tx-data (build-source-link-tx
                           (d/entity db [:block/uuid (:block/uuid paper)])
                           source-key binding)})

              :else
              (let [paper-uuid registration-uuid
                    seed-title (source-record-seed-title source-record)
                    duplicate-candidates
                    (when (and (nil? chosen-uuid) title (empty? identifiers))
                      (find-paper-by-exact-bibliography db title authors))]
                (cond
                  (and (empty? identifiers) (nil? title))
                  {:status :blocked :reason :missing-title}

                  (and (nil? chosen-uuid) duplicate-candidates
                       (not= :missing (:status duplicate-candidates)))
                  {:status :manual
                   :reason :exact-bibliography-candidate
                   :papers (:papers duplicate-candidates)}

                  (and (empty? identifiers) (nil? chosen-uuid))
                  {:status :manual
                   :reason :missing-stable-identifier}

                  (some? (d/entity db [:block/uuid paper-uuid]))
                  {:status :blocked
                   :reason :paper-uuid-conflict
                   :paper-uuid paper-uuid}

                  :else
                  (let [page destination
                        page-id (:db/id page)]
                    (if-not page-id
                      {:status :blocked :reason :invalid-source-position}
                      (let [paper-context
                            (common-util/block-with-timestamps
                             {:db/id -1
                              :block/uuid paper-uuid
                              :block/title (or title seed-title)
                              :block/page page})
                            property-values
                            (reduce
                             (fn [values {:keys [field property]}]
                               (if (contains? #{:title :doi :arxiv} field)
                                 values
                                 (if-let [value (normalize-metadata-value
                                                field (get source-record field))]
                                   (assoc values property value)
                                   values)))
                             (identifier-property-values identifiers)
                             source-record-metadata-fields)
                            property-value-tx
                            (db-property-build/build-property-values-tx-m
                             paper-context property-values :pure? true)
                            paper
                            (merge paper-context
                                   {:block/parent page-id
                                    :block/page page-id
                                    :block/order
                                    (fractional-indexing/generate-key-between
                                     (db-order/get-max-order db)
                                     nil)
                                    :logseq.property/literature-item? true}
                                   (db-property-build/build-properties-with-ref-values
                                    property-value-tx))]
                        {:status :ready
                         :action :create
                         :paper-uuid paper-uuid
                         :source-key source-key
                         :tx-data
                         (into (vec (vals property-value-tx))
                               (concat [paper]
                                       (build-source-link-tx
                                        (assoc paper :block/page page)
                                        source-key binding)))}))))))))))))

(defn build-register-pdf-plan
  "Builds one current-db plan for an explicit user action that either links a
   PDF to the uniquely identified literature item or creates a sibling
   literature item. The caller must transact the returned tx-data without
   yielding to another worker request."
  [db asset-uuid title identifiers paper-uuid & [verified-metadata]]
  (let [asset (live-pdf-asset db asset-uuid)
        title (some-> title str string/trim not-empty)
        identifiers (normalize-identifiers identifiers)
        by-asset (find-paper-by-asset db asset-uuid)]
    (cond
      (not (available? db))
      {:status :blocked :reason :disabled}

      (nil? asset)
      {:status :blocked :reason :asset-unavailable}

      (= :conflict (:status by-asset))
      {:status :blocked
       :reason :asset-linked-to-multiple-papers
       :papers (:papers by-asset)}

      (= :found (:status by-asset))
      (build-link-pdf-plan db asset-uuid (:block/uuid (first (:papers by-asset))))

      (nil? title)
      {:status :blocked :reason :missing-title}

      :else
      (let [by-identifiers (find-paper-by-identifiers db identifiers)]
        (cond
          (= :conflict (:status by-identifiers))
          {:status :blocked
           :reason :identifier-conflict
           :papers (:papers by-identifiers)}

          (= :found (:status by-identifiers))
          (let [paper (first (:papers by-identifiers))]
            (build-link-pdf-plan db asset-uuid (:block/uuid paper)))

          (and (uuid? paper-uuid)
               (some? (d/entity db [:block/uuid paper-uuid])))
          {:status :blocked :reason :paper-uuid-conflict}

          :else
          (let [parent (:block/parent asset)
                page (:block/page asset)
                current-order (:block/order asset)]
            (if-not (and (uuid? paper-uuid) parent page current-order)
              {:status :blocked :reason :invalid-asset-position}
              (let [paper-context (common-util/block-with-timestamps
                                   {:db/id -1
                                    :block/uuid paper-uuid
                                    :block/title title
                                    :block/page page})
                    paper-base (assoc paper-context
                                      :block/parent (:db/id parent)
                                      :block/page (:db/id page)
                                      :block/order
                                      (fractional-indexing/generate-key-between
                                       current-order
                                       (next-sibling-order asset))
                                      :logseq.property/literature-item? true
                                      :logseq.property/literature-assets
                                      #{(:db/id asset)})
                    property-values
                    (reduce
                     (fn [values {:keys [field property]}]
                       (if (contains? #{:authors :date :publication :type :url}
                                      field)
                         (if-let [value (normalize-metadata-value
                                        field (get verified-metadata field))]
                           (assoc values property value)
                           values)
                         values))
                     (identifier-property-values identifiers)
                     source-record-metadata-fields)
                    property-value-tx (db-property-build/build-property-values-tx-m
                                       paper-context property-values :pure? true)
                    paper (merge paper-base
                                 (db-property-build/build-properties-with-ref-values
                                  property-value-tx))]
                {:status :ready
                 :action :create
                 :paper-uuid paper-uuid
                 :asset-uuid asset-uuid
                 :tx-data (into (conj (vec (vals property-value-tx)) paper)
                                (concat (clear-automatic-exclusion-tx asset)
                                        (pdf-title-tx db asset title)))}))))))))


(defn build-doi-metadata-plan
  "Plans an explicit DOI correction. Another Paper is never overwritten or
   merged; a selected PDF may instead be reassociated using its native UUID."
  [db paper-uuid record & [asset-uuid]]
  (let [paper (live-literature-paper db paper-uuid)
        doi (:doi (normalize-identifiers {:doi (:doi record)}))
        match (find-paper-by-identifiers db {:doi doi})
        target (first (:papers match))]
    (cond
      (nil? paper) {:status :blocked :reason :paper-unavailable}
      (nil? doi) {:status :blocked :reason :invalid-identifier}
      (= :conflict (:status match)) {:status :blocked :reason :identifier-conflict}
      (and target (not= paper-uuid (:block/uuid target)))
      (if asset-uuid
        (let [owner (find-paper-by-asset db asset-uuid)]
          (if-not (and (= :found (:status owner))
                       (= paper-uuid (:block/uuid (first (:papers owner)))))
            {:status :blocked :reason :asset-linked-to-another-paper}
            (let [detach [:db/retract [:block/uuid paper-uuid]
                          :logseq.property/literature-assets [:block/uuid asset-uuid]]
                  link (build-link-pdf-plan (d/db-with db [detach]) asset-uuid (:block/uuid target))]
              (if (= :ready (:status link))
                {:status :ready :action :reassign :paper-uuid (:block/uuid target)
                 :asset-uuid asset-uuid
                 :changes [{:field :assets :kind :different-value
                            :current paper-uuid :source (:block/uuid target)}]
                 :ops [[:transact [(into [detach] (:tx-data link)) {}]]]}
                (dissoc link :tx-data)))))
        {:status :existing :paper (select-keys target [:block/uuid :block/title])
         :assets (->> (:logseq.property/literature-assets paper)
                      (keep #(when-let [pdf (live-pdf-asset db (:block/uuid %))]
                               (select-keys pdf [:block/uuid :block/title])))
                      (sort-by :block/title) vec)})
      :else
      (build-source-record-metadata-plan db paper-uuid record true))))

(defn build-apply-doi-metadata-plan
  [db paper-uuid record accepted-changes & [asset-uuid]]
  (let [plan (build-doi-metadata-plan db paper-uuid record asset-uuid)]
    (if (= :reassign (:action plan))
      (if (= (mapv metadata-change-signature (:changes plan))
             (mapv metadata-change-signature accepted-changes))
        plan
        {:status :blocked :reason :metadata-plan-stale})
      (build-accepted-metadata-plan db paper-uuid plan accepted-changes))))

(defn build-add-doi-paper-plan
  "Adds a DOI-only bibliography item using ordinary native block/property txs.
   An existing exact DOI always wins without changing its metadata."
  [db destination-page-uuid record]
  (let [identifiers (normalize-identifiers (select-keys record [:doi]))
        title (non-blank-string (:title record))
        by-identifiers (find-paper-by-identifiers db identifiers)
        paper-uuid (source-record-paper-uuid identifiers)
        destination (when (uuid? destination-page-uuid)
                      (d/entity db [:block/uuid destination-page-uuid]))]
    (cond
      (not (available? db)) {:status :blocked :reason :disabled}
      (or (nil? (:doi identifiers)) (nil? title))
      {:status :blocked :reason :invalid-identifier}
      (= :conflict (:status by-identifiers))
      {:status :blocked :reason :identifier-conflict}
      (= :found (:status by-identifiers))
      {:status :existing :paper-uuid (:block/uuid (first (:papers by-identifiers)))}
      (not (and (ldb/internal-page? destination)
                (not (ldb/class? destination))
                (not (ldb/property? destination))
                (not (ldb/built-in? destination))
                (not (ldb/hidden? destination))))
      {:status :blocked :reason :destination-unavailable}
      (d/entity db [:block/uuid paper-uuid])
      {:status :blocked :reason :paper-uuid-conflict}
      :else
      (let [insert-tx (:tx-data
                       (outliner-core/insert-blocks
                        db [{:block/uuid paper-uuid :block/title title
                             :logseq.property/literature-item? true}]
                        destination
                        {:outliner-op :insert-blocks :keep-uuid? true :bottom? true :sibling? false}))
            inserted-db (d/db-with db insert-tx)
            metadata-plan (build-source-record-metadata-plan
                           inserted-db paper-uuid (merge record identifiers) true)
            metadata-tx (build-accepted-metadata-plan
                         inserted-db paper-uuid metadata-plan
                         (mapv metadata-change-signature (:changes metadata-plan)))
            _ (assert (= :ready (:status metadata-tx)))
            tx-data (into (vec insert-tx) (get-in metadata-tx [:ops 0 1 0]))]
        {:status :ready :paper-uuid paper-uuid
         :ops [[:transact [tx-data {}]]]}))))

(defn build-auto-register-pdf-plan
  "Checks a fixed-source record against PDF evidence before creating or
   linking. Existing bibliography fields are never changed by this path."
  [db asset-uuid {:keys [identifiers title author-texts] :as evidence}
   {:keys [provider authors type] :as metadata}]
  (let [existing (build-auto-link-pdf-plan db asset-uuid evidence)]
    (if (or (empty? metadata) (not (needs-verified-metadata? existing)))
      existing
      (let [asset (live-pdf-asset db asset-uuid)
            by-asset (find-paper-by-asset db asset-uuid)
            identifiers (normalize-identifiers identifiers)
            metadata-identifiers (normalize-identifiers
                                  (select-keys metadata [:doi :arxiv]))
            expected-provider (if (:arxiv metadata-identifiers) :arxiv :crossref)]
        (cond
          (nil? asset)
          {:status :blocked :reason :asset-unavailable}

          (= :conflict (:status by-asset))
          {:status :blocked :reason :asset-linked-to-multiple-papers}

          (and (= :found (:status by-asset))
               (let [identified (find-paper-by-identifiers db metadata-identifiers)]
                 (not (and (= :found (:status identified))
                           (= (:block/uuid (first (:papers by-asset)))
                              (:block/uuid (first (:papers identified))))))))
          {:status :blocked :reason :asset-linked-to-another-paper}

          (not= provider expected-provider)
          {:status :blocked :reason :unsupported-metadata-provider}

          (not (every? (fn [[kind value]] (= value (get metadata-identifiers kind)))
                       identifiers))
          {:status :blocked :reason :metadata-identifier-mismatch}

          (and (= :arxiv provider)
               (not (arxiv-record-id-match?
                     (or (get-in evidence [:identifiers :arxiv]) (:arxiv metadata))
                     (:arxiv-record-id metadata))))
          {:status :blocked :reason :metadata-version-mismatch}

          (not= (normalize-bibliographic-text title)
                (normalize-bibliographic-text (:title metadata)))
          {:status :blocked :reason :title-mismatch}

          (or (not (contains? supported-automatic-types type))
              (and (= :arxiv provider) (not= "preprint" type)))
          {:status :blocked :reason :unsupported-type}

          (nil? (non-blank-string authors))
          {:status :blocked :reason :missing-authors}

          (not (author-match? authors author-texts))
          {:status :blocked :reason :author-mismatch}

          :else
          (build-register-pdf-plan
           db asset-uuid (:title metadata) metadata-identifiers
           (source-record-paper-uuid metadata-identifiers) metadata))))))

(defn- inbound-dependencies
  [db asset-id]
  (->> (d/q '[:find ?source ?attribute
              :in $ ?target
              :where
              [?source ?attribute ?target]]
            db
            asset-id)
       (map (fn [[source-id attribute]]
              {:source-id source-id
               :source-uuid (:block/uuid (d/entity db source-id))
               :attribute attribute}))
       (sort-by (juxt (comp str :attribute)
                      (comp str :source-uuid)
                      :source-id))
       vec))

(defn- asset-attributes
  [db asset-id]
  (->> (d/datoms db :eavt asset-id)
       (map :a)
       (filter #(= "logseq.property.asset" (namespace %)))
       distinct
       (sort-by str)))

(defn- blocked-demotion
  [reason & [extra]]
  (merge {:status :blocked
          :reason reason}
         extra))

(defn duplicate-pdf-usage
  "Counts the selected PDF's annotations and user references on demand."
  [db asset-uuid]
  (let [asset (live-pdf-asset db asset-uuid)]
    {:annotation-count (count (filter #(= :annotation (:logseq.property/ls-type %))
                                      (:logseq.property/_asset asset)))
     :reference-count (count (remove #(true? (:logseq.property/literature-item? %))
                                     (:block/_refs asset)))}))

(defn- duplicate-children-move-tx
  [db keep-asset duplicate-asset]
  (let [children (->> (d/datoms db :avet :block/parent (:db/id duplicate-asset))
                      (map #(d/entity db (:e %)))
                      ldb/sort-by-order
                      vec)]
    (when (seq children)
      (let [conn (ldb/temp-conn-from-db db)
            txs (volatile! [])]
        (d/listen! conn ::duplicate-children
                   (fn [{:keys [tx-data]}] (vswap! txs into tx-data)))
        (try
          ;; Native moves retain UUIDs, descendant pages and property ownership.
          (doseq [child (reverse children)]
            (outliner-core/move-blocks!
             conn [(d/entity @conn (:db/id child))]
             (d/entity @conn (:db/id keep-asset))
             (cond-> {:sibling? false}
               (:logseq.property/created-from-property child)
               (assoc :created-from-property
                      (:db/id (:logseq.property/created-from-property child))))))
          @txs
          (finally
            (d/unlisten! conn ::duplicate-children)
            (reset! conn nil)))))))

(defn- page-comparison-current?
  [db keep-asset duplicate-asset {:keys [rule assets paper-uuid]}]
  (let [paper (live-literature-paper db paper-uuid)
        ids (set (map :block/uuid (:logseq.property/literature-assets paper)))
        identity (fn [asset]
                   {:checksum (:logseq.property.asset/checksum asset)
                    :size (:logseq.property.asset/size asset)})]
    (and (= "pdf-pages-v1" rule)
         paper
         (every? #(contains? ids (:block/uuid %)) [keep-asset duplicate-asset])
         (= assets (into {} (map (juxt :block/uuid identity)
                                 [keep-asset duplicate-asset]))))))

(defn build-duplicate-pdf-demotion-plan
  "Merges exact or explicitly compared PDF copies using native moves and links.
   Annotation UUIDs and old reference targets survive. The caller builds and
   transacts this plan against the same current worker db turn."
  [db keep-uuid duplicate-uuid & [{:keys [preview? comparison]}]]
  (cond
    (not (available? db))
    (blocked-demotion :disabled)

    (= keep-uuid duplicate-uuid)
    (blocked-demotion :same-asset)

    :else
    (let [keep-asset (live-pdf-asset db keep-uuid)
          duplicate-asset (live-pdf-asset db duplicate-uuid)]
      (cond
        (nil? keep-asset)
        (blocked-demotion :keep-asset-unavailable)

        (nil? duplicate-asset)
        (blocked-demotion :duplicate-asset-unavailable)

        (and comparison (not (page-comparison-current? db keep-asset duplicate-asset comparison)))
        (blocked-demotion :comparison-changed)

        (and (nil? comparison)
             (or (string/blank? (:logseq.property.asset/checksum keep-asset))
            (nil? (:logseq.property.asset/size keep-asset))
            (not= (:logseq.property.asset/checksum keep-asset)
                  (:logseq.property.asset/checksum duplicate-asset))
            (not= (:logseq.property.asset/size keep-asset)
                  (:logseq.property.asset/size duplicate-asset))))
        (blocked-demotion :not-exact-duplicate)

        (:block/link duplicate-asset)
        (blocked-demotion :duplicate-has-link)

        :else
        (let [duplicate-id (:db/id duplicate-asset)
              dependencies (inbound-dependencies db duplicate-id)
              paper-edge? (fn [{:keys [attribute source-uuid]}]
                            (and (= :logseq.property/literature-assets attribute)
                                 (live-literature-paper db source-uuid)))
              paper-edges (filter paper-edge? dependencies)
              duplicate-ref [:block/uuid duplicate-uuid]
              keep-ref [:block/uuid keep-uuid]
              ownership-tx (vec (mapcat
                                 (fn [{:keys [source-uuid]}]
                                   [[:db/retract [:block/uuid source-uuid]
                                     :logseq.property/literature-assets duplicate-ref]
                                    [:db/add [:block/uuid source-uuid]
                                     :logseq.property/literature-assets keep-ref]])
                                 paper-edges))
              ownership-db (d/db-with db ownership-tx)
              derived-only-owners
              (set (keep (fn [{:keys [source-id source-uuid]}]
                           (when-not (some #{duplicate-id}
                                           (outliner-core/rebuild-block-refs
                                            ownership-db (d/entity ownership-db source-id)))
                             source-uuid))
                         paper-edges))
              derived-edge? (fn [{:keys [attribute source-uuid]}]
                              (and (= :block/refs attribute)
                                   (contains? derived-only-owners source-uuid)))
              keep-owners (filter paper-edge? (inbound-dependencies db (:db/id keep-asset)))
              owners (set (map :source-uuid (concat paper-edges keep-owners)))]
          (cond
            (or (seq (:block/_alias keep-asset))
                (seq (:block/_alias duplicate-asset)))
            (blocked-demotion :duplicate-in-use)

            (> (count owners) 1)
            (blocked-demotion :conflicting-owners)

            preview?
            {:status :ready :keep-uuid keep-uuid :duplicate-uuid duplicate-uuid}

            :else
            (let [tx-data (into ownership-tx
                                (concat
                                 (mapcat (fn [{:keys [source-uuid]}]
                                           [[:db/retract [:block/uuid source-uuid] :block/refs duplicate-ref]
                                            [:db/add [:block/uuid source-uuid] :block/refs keep-ref]])
                                         (filter derived-edge? dependencies))
                                 (mapv (fn [attribute]
                                         [:db.fn/retractAttribute duplicate-ref attribute])
                                       (asset-attributes db duplicate-id))
                                 (duplicate-children-move-tx db keep-asset duplicate-asset)
                                 (for [annotation (:logseq.property/_asset duplicate-asset)]
                                   [:db/add [:block/uuid (:block/uuid annotation)]
                                    :logseq.property/asset keep-ref])
                                 (for [link (:block/_link duplicate-asset)]
                                   [:db/add [:block/uuid (:block/uuid link)] :block/link keep-ref])
                                 (mapcat (fn [alias]
                                           [[:db/retract duplicate-ref :block/alias (:db/id alias)]
                                            [:db/add keep-ref :block/alias (:db/id alias)]])
                                         (:block/alias duplicate-asset))
                                 [[:db/retract duplicate-ref :block/tags :logseq.class/Asset]
                                  [:db/add duplicate-ref :block/link keep-ref]]))]
              {:status :ready
               :keep-uuid keep-uuid
               :duplicate-uuid duplicate-uuid
               :tx-data tx-data})))))))
