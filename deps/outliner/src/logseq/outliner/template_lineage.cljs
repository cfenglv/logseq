(ns logseq.outliner.template-lineage
  "Reuse proven, equal template nodes within one template application."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [datascript.impl.entity :as de]
            [logseq.common.defkeywords :refer [defkeywords]]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.db :as ldb]
            [logseq.db.common.entity-plus :as entity-plus]
            [logseq.db.common.order :as db-order]
            [logseq.db.frontend.content :as db-content]))

(defkeywords
  :block.temp/template-source-uuid {:doc "Original node identity, before template copy remints UUIDs."}
  :block.temp/template-root-uuid {:doc "Source template boundary for a single application."}
  :template/aliases {:doc "Application-local mapping of coalesced source nodes."})

(defn prepare-blocks
  [db template]
  (when-let [blocks (seq (rest (ldb/get-block-and-children
                               db (:block/uuid template) {:include-property-block? true})))]
    (mapv (fn [index block]
            (cond-> (assoc (into {} block)
                           :db/id (:db/id block)
                           :block.temp/template-source-uuid (:block/uuid block)
                           :block.temp/template-root-uuid (:block/uuid template))
              (zero? index)
              (assoc :logseq.property/used-template (:db/id template))))
          (range) blocks)))

(defn- ref-uuid
  [db value]
  (cond
    (uuid? value) value
    (and (vector? value) (= :block/uuid (first value))) (second value)
    (or (map? value) (de/entity? value)) (:block/uuid value)
    (integer? value) (:block/uuid (d/entity db value))))

(defn materialize-source
  "Capture a live prepared source; omit a deleted optional source during replay."
  [db block batch-uuids]
  (let [source-id (:block.temp/template-source-uuid block)
        result (dissoc block :block.temp/template-source-uuid :block.temp/template-root-uuid)]
    (if source-id
      (assoc result :logseq.property/template-source-block [:block/uuid source-id])
      (if-let [source (:logseq.property/template-source-block result)]
        (let [source-id (ref-uuid db source)]
          (if (or (contains? batch-uuids source-id)
                  (some->> source-id (vector :block/uuid) (d/entity db)))
            result
            (dissoc result :logseq.property/template-source-block)))
        result))))

(defn- origin
  [db node-id]
  (loop [node-id node-id seen #{}]
    (when-not (contains? seen node-id)
      (when-let [node (d/entity db [:block/uuid node-id])]
        (when-not (ldb/recycled? node)
          (if-let [source (:logseq.property/template-source-block node)]
            (when-not (= :logseq.property/empty-placeholder (:db/ident source))
              (recur (:block/uuid source) (conj seen node-id)))
            node-id))))))

(defn owning-template
  [block]
  (loop [parent (:block/parent block)]
    (when parent
      (if (some #(= :logseq.class/Template (:db/ident %)) (:block/tags parent))
        parent
        (recur (:block/parent parent))))))

(defn valid-source?
  [block source]
  (and (owning-template block) (owning-template source)
       (not (ldb/recycled? block)) (not (ldb/recycled? source))
       (loop [node source seen #{(:block/uuid block)}]
         (when-not (contains? seen (:block/uuid node))
           (if-let [parent (:logseq.property/template-source-block node)]
             (recur parent (conj seen (:block/uuid node)))
             true)))))

(def ^:private identity-attrs
  #{:db/id :block/uuid :block/tx-id :block/page :block/parent :block/order :block/level
    :block/created-at :block/updated-at :block/refs
    :logseq.property/created-by-ref :logseq.property/updated-by-ref
    :logseq.property/used-template :logseq.property/template-source-block})

(defn- ref-attr?
  [db attr]
  (= :db.type/ref (get-in (d/schema db) [attr :db/valueType])))

(defn- rewrite-title
  [title aliases]
  (if (string? title)
    (reduce (fn [result node-id]
              (if-let [replacement (get aliases node-id)]
                (string/replace result (page-ref/->page-ref node-id)
                                (page-ref/->page-ref replacement))
                result))
            title (db-content/get-matched-ids title))
    title))

(declare semantic-node)

(defn- semantic-ref
  [db value identities seen]
  (let [node-id (ref-uuid db value)
        node (when node-id (d/entity db [:block/uuid node-id]))]
    (if (and (:logseq.property/created-from-property node)
             (not (contains? seen node-id)))
      [:property-value
       (semantic-node db node identities (conj seen node-id))
       (mapv #(semantic-ref db % identities (conj seen node-id))
             (ldb/sort-by-order (entity-plus/lookup-kv-then-entity node :block/_raw-parent)))]
      (or (get identities node-id) node-id (:db/ident node) value))))

(defn- semantic-node
  [db block identities seen]
  (into {}
        (keep (fn [[attr value]]
                (when-not (or (contains? identity-attrs attr)
                              (= "block.temp" (namespace attr)))
                  [attr
                   (cond
                     (contains? #{:block/title :block/raw-title} attr)
                     (rewrite-title value identities)

                     (ref-attr? db attr)
                     (if (set? value)
                       (set (map #(semantic-ref db % identities seen) value))
                       (semantic-ref db value identities seen))

                     :else value)])))
        (into {} block)))

(defn- rewrite-block
  [db block aliases]
  (reduce-kv
   (fn [result attr value]
     (assoc result attr
            (cond
              (contains? #{:block/title :block/raw-title} attr)
              (rewrite-title value aliases)

              (and (not= attr :logseq.property/template-source-block)
                   (ref-attr? db attr))
              (let [rewrite (fn [v]
                              (if-let [replacement (get aliases (ref-uuid db v))]
                                (d/entity db [:block/uuid replacement])
                                v))]
                (if (set? value) (set (map rewrite value)) (rewrite value)))

              :else value)))
   {} block))

(defn- ordered-union
  "Stable identity-based order; contradictory source orders are not guessed."
  [sequences ranks]
  (let [nodes (set (mapcat identity sequences))
        edges (set (mapcat #(partition 2 1 %) sequences))
        outgoing (group-by first edges)
        degrees (reduce (fn [m [_ b]] (update m b inc)) (zipmap nodes (repeat 0)) edges)
        priority (fn [a b] (compare [(get ranks a) (str a)] [(get ranks b) (str b)]))]
    (loop [ready (into (sorted-set-by priority) (filter #(zero? (get degrees %)) nodes))
           degrees degrees
           result []]
      (if-let [node (first ready)]
        (let [[degrees ready]
              (reduce (fn [[degrees ready] [_ child]]
                        (let [degrees (update degrees child dec)]
                          [degrees (cond-> ready (zero? (get degrees child)) (conj child))]))
                      [degrees (disj ready node)] (get outgoing node))]
          (recur ready degrees (conj result node)))
        (when (= (count result) (count nodes)) result)))))

(defn coalesce
  "Keep official template selection. Only reuse equal, unambiguous source nodes."
  [db blocks]
  (let [blocks (vec blocks)
        unchanged {:blocks blocks :aliases {}}]
    (if (or (< (count (set (map :block.temp/template-root-uuid blocks))) 2)
            (not-any? :logseq.property/template-source-block blocks))
      unchanged
      (let [by-id (into {} (map (juxt :block/uuid identity)) blocks)
            identities (into {} (keep (fn [node-id]
                                       (when-let [source (origin db node-id)]
                                         [node-id source]))) (keys by-id))
            groups (group-by #(get identities (:block/uuid %) (:block/uuid %)) blocks)
            parent-id (fn [block] (let [node-id (ref-uuid db (:block/parent block))]
                                   (when (contains? by-id node-id) node-id)))
            parent-key (fn [block] (some-> (parent-id block) identities))
            eligible-groups
            (into {}
                  (filter (fn [[source members]]
                            (and (> (count members) 1)
                                 (= (count members) (count (set (map :block.temp/template-root-uuid members))))
                                 (every? #(= (parent-key (first members)) (parent-key %)) members)
                                 (let [base (d/entity db [:block/uuid source])
                                       expected (semantic-node db base identities #{source})]
                                   (every? #(= expected (semantic-node db % identities #{(:block/uuid %)}))
                                           members)))))
                  groups)
            eligible? (fn eligible? [source seen]
                        (when (and (contains? eligible-groups source) (not (contains? seen source)))
                          (let [members (get groups source)
                                parents (set (keep parent-id members))]
                            (or (<= (count parents) 1)
                                (eligible? (parent-key (first members)) (conj seen source))))))
            aliases (into {}
                          (mapcat (fn [[source members]]
                                    (when (eligible? source #{})
                                      (map (fn [block] [(:block/uuid block) (:block/uuid (first members))])
                                           (rest members)))))
                          groups)]
        (if (or (empty? aliases) (not= (count by-id) (count blocks)))
          unchanged
          (let [resolve-id #(get aliases % %)
                kept (mapv #(rewrite-block db % aliases)
                           (remove #(contains? aliases (:block/uuid %)) blocks))
                kept-by-id (into {} (map (juxt :block/uuid identity)) kept)
                output-parent #(some-> (parent-id %) resolve-id)
                sequences (->> blocks
                               (group-by #(vector (output-parent %) (:block.temp/template-root-uuid %)))
                               (group-by (comp first key)))
                ranks (reduce (fn [m [index block]]
                                (let [node-id (resolve-id (:block/uuid block))]
                                  (if (contains? m node-id) m (assoc m node-id index))))
                              {} (map-indexed vector blocks))
                orders (into {}
                             (map (fn [[parent entries]]
                                    [parent (ordered-union
                                             (map (fn [[_ members]] (mapv (comp resolve-id :block/uuid) members))
                                                  entries)
                                             ranks)]))
                             sequences)]
            (if (some nil? (vals orders))
              unchanged
              (letfn [(emit [parent depth]
                        (mapcat (fn [node-id order]
                                  (let [block (assoc (get kept-by-id node-id)
                                                     :block/order order :block/level depth)]
                                    (cons block (emit node-id (inc depth)))))
                                (get orders parent)
                                (db-order/gen-n-keys (count (get orders parent)) nil nil)))]
                {:blocks (vec (emit nil 1)) :aliases aliases}))))))))
