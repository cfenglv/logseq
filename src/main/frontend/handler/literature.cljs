(ns ^:no-doc frontend.handler.literature
  (:require ["/frontend/extensions/pdf/literature" :as js-literature]
            [cljs-bean.core :as bean]
            [clojure.set :as set]
            [clojure.string :as string]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db.async :as db-async]
            [frontend.db.subs :as db-subs]
            [electron.ipc :as ipc]
            [frontend.fs :as fs]
            [frontend.handler.assets :as assets-handler]
            [frontend.handler.common.page :as page-common-handler]
            [frontend.handler.literature-metadata :as metadata]
            [frontend.handler.literature-zotero :as zotero]
            [frontend.modules.outliner.op :as outliner-op]
            [frontend.modules.outliner.ui :as ui-outliner-tx]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [logseq.common.path :as path]
            [logseq.db.frontend.literature :as db-literature]
            [logseq.db :as ldb]
            [logseq.db.frontend.asset :as db-asset]
            [promesa.core :as p]))

(defonce ^:private *active-task (atom nil))
(defonce ^:private *zotero-sync-task (atom nil))
(defonce ^:private *zotero-lifecycle-installed? (atom false))

(def ^:private settings-storage-key :ls-literature-settings)

(def ^:private default-settings
  {:enabled? false
   :auto-identification? false
   :online-metadata? false
   :zotero-binding nil
   :last-zotero-binding nil})

(declare cancel-active-identification!
         schedule-new-pdf-identification!
         reconcile-zotero-lifecycle!
         current-graph?
         stop-zotero-sync!
         ensure-manual-review!
         <store-and-maybe-auto-link!)

(defn- sanitize-settings
  [value]
  (let [binding (when (map? value)
                  (zotero/sanitize-binding (:zotero-binding value)))
        last-binding (when (map? value)
                       (zotero/sanitize-binding
                        (:last-zotero-binding value)))]
    (merge default-settings
           (when (map? value)
             {:enabled? (true? (:enabled? value))
              :auto-identification? (true? (:auto-identification? value))
              :online-metadata? (true? (:online-metadata? value))
              :zotero-binding binding
              ;; Existing settings only have :zotero-binding. Remember it as
              ;; the reconnect identity without requiring a migration.
              :last-zotero-binding (or last-binding binding)}))))

(defn load-settings
  "Returns local settings for one graph. Shared literature data is not stored
   here and receiving it never opts this client into background work."
  [repo]
  (try
    (let [all-settings (storage/get settings-storage-key)]
      (sanitize-settings (when (map? all-settings)
                           (get all-settings repo))))
    (catch :default _error
      default-settings)))

(defn- save-settings!
  [repo settings]
  (let [all-settings (try
                       (let [value (storage/get settings-storage-key)]
                         (if (map? value) value {}))
                       (catch :default _error
                         {}))
        settings (sanitize-settings settings)]
    (storage/set settings-storage-key (assoc all-settings repo settings))
    settings))

(defn auto-identification-enabled?
  [repo]
  (let [{:keys [enabled? auto-identification?]} (load-settings repo)]
    (and enabled? auto-identification?)))

(defn online-metadata-enabled?
  [repo]
  (let [{:keys [enabled? auto-identification? online-metadata?]}
        (load-settings repo)]
    (and enabled? auto-identification? online-metadata?)))

(defn <activate!
  "Enables local literature tasks after normal graph migration."
  [repo]
  (let [settings (save-settings! repo (assoc (load-settings repo) :enabled? true))]
    (when (:zotero-binding settings)
      (reconcile-zotero-lifecycle! repo))
    (p/resolved {:status :enabled})))

(defn disable!
  "Stops this client's literature work without removing shared graph data."
  [repo]
  (let [settings (save-settings! repo (assoc (load-settings repo) :enabled? false))]
    (cancel-active-identification!)
    (reconcile-zotero-lifecycle! repo)
    settings))

(defn set-auto-identification-enabled!
  [repo enabled?]
  (let [settings (load-settings repo)]
    (when (and enabled? (not (:enabled? settings)))
      (throw (ex-info "Literature must be activated before automatic identification"
                      {:type :literature/not-activated
                       :repo repo})))
    (when-not enabled?
      (cancel-active-identification!))
    (save-settings! repo (assoc settings :auto-identification? (true? enabled?)))))

(defn set-online-metadata-enabled!
  [repo enabled?]
  (let [settings (load-settings repo)]
    (when (and enabled? (not (:enabled? settings)))
      (throw (ex-info "Literature must be activated before online metadata lookup"
                      {:type :literature/not-activated
                       :repo repo})))
    (save-settings! repo (assoc settings :online-metadata? (true? enabled?)))))

(defn set-zotero-binding!
  "Stores only the local bridge identity and authorized scope for this graph."
  [repo binding]
  (let [settings (load-settings repo)
        requested-binding binding
        binding (when requested-binding
                  (zotero/sanitize-binding requested-binding))]
    (when (and requested-binding (not (:enabled? settings)))
      (throw (ex-info "Literature must be activated before binding Zotero"
                      {:type :literature/not-activated
                       :repo repo})))
    (when (and (some? requested-binding) (nil? binding))
      (throw (ex-info "Invalid Zotero binding"
                      {:type :literature/invalid-zotero-binding
                       :repo repo})))
    (let [next-settings (cond-> (assoc settings :zotero-binding binding)
                          binding (assoc :last-zotero-binding binding))
          saved (save-settings! repo next-settings)]
      (reconcile-zotero-lifecycle! repo)
      saved)))

(defn local-zotero-binding
  "Builds the local-only personal-library binding used by the settings UI."
  [binding-uuid collection-key]
  (let [collection-key (some-> collection-key str string/trim string/upper-case)]
    (zotero/sanitize-source
     {:graph-binding-uuid binding-uuid
      :source-identity {:kind :local
                        :binding-uuid (some-> binding-uuid str string/lower-case)}
       :scope (if (nil? collection-key)
               {:kind :library}
               {:kind :collection :key collection-key})})))

(defn <connect-local-zotero!
  "Reads the current library identity and resolves graph pages before saving the local binding."
  ([repo binding-uuid collection-key]
   (<connect-local-zotero! repo binding-uuid collection-key nil))
  ([repo binding-uuid collection-key expected-library-key]
  (let [source (local-zotero-binding binding-uuid collection-key)
        settings (load-settings repo)
        previous (:last-zotero-binding settings)
        cancelled {:status :cancelled :reason :binding-inactive}]
    (cond
      (not (:enabled? settings))
      (p/resolved {:status :blocked :reason :not-activated})

      (nil? source)
      (p/resolved {:status :blocked :reason :invalid-binding})

      (not (current-graph? repo))
      (p/resolved cancelled)

      :else
      (p/let [key (zotero/<read-library-key!)]
        (cond
          (not (current-graph? repo)) cancelled
          (and expected-library-key (not= key expected-library-key))
          {:status :blocked :reason :library-identity-mismatch}
          :else
          (p/let [changed-library? (and previous (not= key (:zotero-library-key previous)))
                  connected-uuid (if changed-library? (random-uuid) binding-uuid)
                  binding (assoc (local-zotero-binding connected-uuid collection-key)
                                 :zotero-library-key key)
                  destination (if previous
                                {:page-uuid (:destination-page-uuid previous)}
                                {:title (t :literature/default-page-title)})
                  result (db-async/<ensure-literature-source-binding
                           repo connected-uuid destination)]
            (cond
              (not (current-graph? repo)) cancelled
              (contains? #{:created :restored :existing} (:status result))
              (let [binding (assoc binding :destination-page-uuid
                                   (:destination-page-uuid result))]
                (set-zotero-binding! repo binding)
                {:status :connected :binding binding :binding-result result})
              :else result))))))))

(defn zotero-binding
  [repo]
  (:zotero-binding (load-settings repo)))

(defn- zotero-import-active?
  [repo binding]
  (and (= repo (state/get-current-repo))
       (not (true? (state/get-state :db/restoring?)))
       (let [settings (load-settings repo)]
         (and (:enabled? settings)
              (= binding (:zotero-binding settings))))))

(defn- <read-linked-zotero-record!
  [repo binding paper-uuid]
  (p/let [link (db-async/<find-literature-source-record-link
                repo (:graph-binding-uuid binding) paper-uuid)]
    (if-not (= :found (:status link))
      link
      (zotero/<read-source-record!
       binding
       (:source-key link)
       #(zotero-import-active? repo binding)))))

(defn <check-zotero-metadata!
  "Reads one explicitly selected paper's current Zotero record and returns a
   transient metadata plan. It does not store the record or write the graph."
  [repo paper-uuid]
  (if-let [binding (zotero-binding repo)]
    (if-not (zotero-import-active? repo binding)
      (p/resolved {:status :cancelled :reason :binding-inactive})
      (p/let [record (<read-linked-zotero-record! repo binding paper-uuid)]
        (if-not (= :ready (:status record))
          record
          (db-async/<plan-linked-literature-source-record-metadata
           repo
           (:graph-binding-uuid binding)
           paper-uuid
           (:source-record record)))))
    (p/resolved {:status :blocked :reason :missing-zotero-binding})))

(defn <apply-zotero-metadata!
  "Re-reads one paper's Zotero item, then asks the DB worker to revalidate and
   apply only the exact metadata changes the user accepted."
  [repo paper-uuid accepted-changes]
  (if-let [binding (zotero-binding repo)]
    (if-not (zotero-import-active? repo binding)
      (p/resolved {:status :cancelled :reason :binding-inactive})
      (p/let [record (<read-linked-zotero-record! repo binding paper-uuid)]
        (cond
          (not= :ready (:status record))
          record

          (not (zotero-import-active? repo binding))
          {:status :cancelled :reason :binding-inactive}

          :else
          (db-async/<apply-literature-source-record-metadata
           repo
           (:graph-binding-uuid binding)
           paper-uuid
           (:source-record record)
           accepted-changes))))
    (p/resolved {:status :blocked :reason :missing-zotero-binding})))

(defn- require-zotero-import-active!
  [repo binding]
  (when-not (zotero-import-active? repo binding)
    (throw (ex-info "Zotero binding is no longer active"
                    {:type :literature-zotero/binding-inactive}))))

(defn- <read-zotero-pdf-file!
  [repo binding {:keys [file-url filename]}]
  (require-zotero-import-active! repo binding)
  (let [local-path (path/file-url-or-path->path file-url)]
    (p/let [stat (ipc/ipc :stat local-path)]
      (when-not (and (number? (:size stat))
                     (js/Number.isFinite (:size stat))
                     (pos? (:size stat))
                     (not (assets-handler/exceed-limit-size? #js {:size (:size stat)})))
        (throw (ex-info "Invalid Zotero attachment size"
                        {:type :literature-zotero/invalid-attachment-size})))
      (require-zotero-import-active! repo binding)
      (p/let [payload (ipc/ipc :readFileRaw local-path :js-obj)
              ^js data (assets-handler/->uint8 payload)]
        (require-zotero-import-active! repo binding)
        (when-not (= (:size stat) (.-byteLength data))
          (throw (ex-info "Zotero attachment changed while reading"
                          {:type :literature-zotero/attachment-changed})))
        (js/File. #js [data]
                  (if (string/ends-with? (string/lower-case filename) ".pdf")
                    filename (str filename ".pdf"))
                  #js {:type "application/pdf"})))))

(defn- <insert-zotero-asset-block!
  [repo binding target-uuid block]
  (require-zotero-import-active! repo binding)
  (p/let [_ (state/<invoke-db-worker
             :thread-api/apply-outliner-ops repo
             [[:insert-blocks [[block] target-uuid
                              {:keep-uuid? true :bottom? true :sibling? false}]]]
             {:persist-op? true :gen-undo-ops? false})
          saved (db-async/<get-block repo (:block/uuid block) {:children? false})]
    (when-not (and (= (:block/uuid block) (:block/uuid saved))
                   (= target-uuid (get-in saved [:block/parent :block/uuid])))
      (throw (ex-info "Zotero attachment was not inserted at its destination"
                      {:type :literature-zotero/attachment-insert-failed})))
    saved))

(defn- <recycle-unregistered-zotero-file!
  [repo asset-uuid repo-dir asset-dir file-path]
  (p/let [saved (db-async/<get-block repo asset-uuid {:children? false})]
    (when (nil? saved)
      (fs/unlink! repo (path/path-join repo-dir asset-dir file-path) nil))))

(defn- require-zotero-pdf-identity!
  [asset-uuid asset checksum byte-size]
  (when-not (and (= asset-uuid (:block/uuid asset))
                 (= "pdf" (:logseq.property.asset/type asset))
                 (some #(= :logseq.class/Asset (:db/ident %)) (:block/tags asset))
                 (= checksum (:logseq.property.asset/checksum asset))
                 (= byte-size (:logseq.property.asset/size asset))
                 (nil? (:logseq.property/deleted-at asset)))
    (throw (ex-info "Zotero PDF Asset identity changed"
                    {:type :literature-zotero/attachment-identity-changed}))))

(defn- <link-saved-zotero-pdf!
  [paper-uuid repo binding asset-uuid source-context candidate status file repo-dir asset-dir
   file-path]
  (when paper-uuid
    (-> (p/then
         (p/resolved nil)
         (fn [_]
           (require-zotero-import-active! repo binding)
           (db-async/<link-pdf-to-literature
            repo asset-uuid paper-uuid
            {:automatic? true
             :source-context (assoc source-context
                                    :reference-uuid (ldb/new-block-id)
                                    :asset-block (-> (:asset-block candidate)
                                                     (assoc :block/uuid asset-uuid)
                                                     (dissoc :logseq.property.asset/external-url)))})))
        (p/then
         (fn [result]
           (when-not (or (contains? #{:linked :already-linked} (:status result))
                         (and (= :blocked (:status result))
                              (contains? #{:automatic-excluded :source-link-unavailable}
                                         (:reason result))))
             (throw (ex-info "Zotero PDF was not associated with its literature item"
                             {:type :literature-zotero/attachment-link-failed
                              :status (:status result) :reason (:reason result)})))
           (p/let [_checked
                   (when (and (= :missing status)
                              (contains? #{:linked :already-linked} (:status result)))
                     (p/let [saved (db-async/<get-block repo asset-uuid {:children? false})]
                       (require-zotero-pdf-identity!
                        asset-uuid saved (:checksum candidate) (.-size file))))]
             result)))
        (p/catch
         (fn [error]
           (-> (if (= :missing status)
                 (<recycle-unregistered-zotero-file!
                  repo asset-uuid repo-dir asset-dir file-path)
                 (p/resolved nil))
               (p/catch (fn [cleanup-error]
                          (log/warn :literature-zotero/asset-cleanup-failed cleanup-error)))
               (p/then (fn [_] (throw error)))))))))

(defn- <save-zotero-pdf!
  [repo binding paper-uuid source-context attachment]
  (p/let [^js file (<read-zotero-pdf-file! repo binding attachment)
          candidate (assets-handler/prepare-asset-block
                     repo (if-let [title (:title attachment)] {:src file :title title} file) {})
          _ (require-zotero-import-active! repo binding)
          {:keys [status assets literature-status]} (db-async/<find-reusable-pdf-by-checksum repo (:checksum candidate))]
    (when-not (contains? #{:missing :found} status)
      (throw (ex-info "Zotero PDF cannot be reused safely"
                      {:type :literature-zotero/attachment-reuse-failed
                       :status status :assets assets})))
    (require-zotero-import-active! repo binding)
    (p/let [[repo-dir asset-dir] (assets-handler/ensure-assets-dir! repo)
            target-uuid (or paper-uuid (:destination-page-uuid binding))
            asset-uuid (if (= :found status)
                         (:block/uuid (first assets))
                         (get-in candidate [:asset-block :block/uuid]))
            file-path (str asset-uuid ".pdf")
            asset (if (= :found status)
                    (db-async/<get-block repo asset-uuid {:children? false})
                    (p/let [_ (require-zotero-import-active! repo binding)
                            _written (assets-handler/db-based-write-asset! repo file-path file)]
                      (when-not paper-uuid
                        (-> (p/then (p/resolved nil)
                                    (fn [_]
                                      (<insert-zotero-asset-block! repo binding target-uuid (:asset-block candidate))))
                            (p/catch
                             (fn [error]
                               (-> (<recycle-unregistered-zotero-file!
                                    repo asset-uuid repo-dir asset-dir file-path)
                                   (p/catch (fn [cleanup-error]
                                              (log/warn :literature-zotero/asset-cleanup-failed cleanup-error)))
                                   (p/then (fn [_] (throw error))))))))))]
      (when asset
        (require-zotero-pdf-identity! asset-uuid asset (:checksum candidate) (.-size file)))
      (p/let [_restored
              (when (= :found status)
                (let [local-path (path/path-join repo-dir asset-dir file-path)]
                  (p/let [stat (ipc/ipc :stat local-path)]
                    (require-zotero-import-active! repo binding)
                    (if (nil? stat)
                      (assets-handler/db-based-write-asset! repo file-path file)
                      (do
                        (when-not (= (.-size file) (:size stat))
                          (throw (ex-info "Existing PDF size does not match its Asset"
                                          {:type :literature-zotero/attachment-identity-changed})))
                        (p/let [matches? (ipc/ipc :fileContentMatches local-path
                                                 (.-size file) (:checksum candidate))]
                          (when-not matches?
                            (throw (ex-info "Existing PDF bytes do not match their Asset"
                                            {:type :literature-zotero/attachment-identity-changed})))))))))
               linked (<link-saved-zotero-pdf! paper-uuid repo binding asset-uuid source-context candidate status file repo-dir asset-dir file-path)
               excluded? (= [:blocked :automatic-excluded]
                            [(:status linked) (:reason linked)])
               stale? (= [:blocked :source-link-unavailable]
                         [(:status linked) (:reason linked)])
               _recycled (when (and (= :missing status) (or excluded? stale?))
                           (<recycle-unregistered-zotero-file! repo asset-uuid repo-dir asset-dir file-path))]
        (p/let [position (when (and (nil? paper-uuid)
                                   (not= target-uuid (get-in asset [:block/parent :block/uuid])))
                          (db-async/<q repo {:transact-db? false}
                                       '[:find ?u . :in $ ?asset-uuid ?target-uuid
                                         :where
                                         [?a :block/uuid ?asset-uuid]
                                         [?p :block/link ?a]
                                         [?t :block/uuid ?target-uuid]
                                         [?p :block/parent ?t]
                                         [?p :block/uuid ?u]
                                         (not [?p :logseq.property/deleted-at])]
                                       asset-uuid target-uuid))
                _position (when (and (nil? paper-uuid)
                                     (not= target-uuid (get-in asset [:block/parent :block/uuid]))
                                     (nil? position))
                            (<insert-zotero-asset-block!
                             repo binding target-uuid
                             {:block/uuid (get-in candidate [:asset-block :block/uuid])
                              :block/title "" :block/link [:block/uuid asset-uuid]}))]
          (require-zotero-import-active! repo binding)
          (when (and (nil? paper-uuid)
                     (or (= :missing status) (= :missing literature-status)))
            (ensure-manual-review! repo asset)
            (when (and (zotero-import-active? repo binding)
                       (auto-identification-enabled? repo))
              (schedule-new-pdf-identification!
               repo repo-dir asset-dir
               [{:asset-uuid asset-uuid :file-path file-path
                 :checksum (:checksum candidate) :byte-size (.-size file)}]
               :on-result #(<store-and-maybe-auto-link! repo repo-dir asset-dir %))))
          (if stale? linked {:asset-uuid asset-uuid :file-path file-path}))))))

(defn- zotero-import-callback
  [repo binding]
  (fn [binding-uuid source-record & [context]]
    (-> (p/let [result (when source-record
                        (db-async/<register-literature-source-record
                         repo binding-uuid (:destination-page-uuid binding) source-record))]
          (if (and source-record
                   (not (contains? #{:created :linked :already-imported :manual}
                                   (:status result))))
            result
            (do
              (when (and (contains? #{:created :linked :already-imported} (:status result))
                         (not (uuid? (:paper-uuid result))))
                (throw (ex-info "Zotero registration did not return a literature identity"
                                {:type :literature-zotero/invalid-registration-result})))
              (p/let [attachments (if (= :standalone (:kind context))
                                   (zotero/<read-pdf-attachments! binding (or (:source-key context) (:source-key source-record))
                                                                 #(zotero-import-active? repo binding) context)
                                   (zotero/<read-pdf-attachments! binding (or (:source-key context) (:source-key source-record))
                                                                 #(zotero-import-active? repo binding)))]
                (if-not (= :ready (:status attachments))
                  (if (= :cancelled (:status attachments))
                    attachments
                    (throw (ex-info "Zotero PDF attachments could not be read"
                                    {:type :literature-zotero/attachment-read-failed
                                     :status (:status attachments) :reason (:reason attachments)})))
                  (p/loop [remaining (seq (:attachments attachments))]
                    (if-let [attachment (first remaining)]
                      (p/let [saved (<save-zotero-pdf!
                                    repo binding (:paper-uuid result)
                                    {:binding-uuid binding-uuid
                                     :source-key (or (:source-key context) (:source-key source-record))}
                                    attachment)]
                        (if (= [:blocked :source-link-unavailable]
                               [(:status saved) (:reason saved)])
                          saved
                          (p/recur (next remaining))))
                      (or result {:status :manual :reason :missing-metadata}))))))))
        (p/catch (fn [error]
                   (if (= :literature-zotero/binding-inactive (:type (ex-data error)))
                     {:status :cancelled :reason :binding-inactive}
                     (throw error)))))))

(defn- record-import-state!
  [repo status result]
  (when (current-graph? repo)
    (state/update-state! [:literature/import-state repo]
                         #(assoc % :status status :counts (:result-counts result)))))

(defn <import-zotero-now!
  "Runs one explicit key-snapshot import through native Asset save/link.
   This function does not start a Zotero notification observer."
  [repo]
  (if-let [binding (zotero-binding repo)]
    (do
      (when (zotero-import-active? repo binding) (record-import-state! repo :working nil))
      (-> (zotero/<import-scope!
           binding #(zotero-import-active? repo binding) (zotero-import-callback repo binding))
          (p/then (fn [result]
                    (when (zotero-import-active? repo binding)
                      (record-import-state! repo (:status result) result))
                    result))
          (p/catch (fn [error]
                     (when (zotero-import-active? repo binding) (record-import-state! repo :failed nil))
                     (throw error)))))
    (p/resolved {:status :blocked :reason :missing-zotero-binding})))

(defn <refresh-zotero-items!
  "Re-reads explicit Zotero keys through the same save/link path as
   notifications, which are never treated as metadata facts."
  [repo item-keys]
  (if-let [binding (zotero-binding repo)]
    (zotero/<import-item-keys!
     binding
     item-keys
     #(zotero-import-active? repo binding)
     (zotero-import-callback repo binding))
    (p/resolved {:status :blocked :reason :missing-zotero-binding})))

(defn <preview-manual-zotero-page!
  "Lists one page of identifier-free, unregistered source records on request."
  [repo offset]
  (if-let [binding (zotero-binding repo)]
    (p/let [result (zotero/<read-scope-page!
                   binding offset #(zotero-import-active? repo binding))]
      (if (= :complete (:status result))
        (p/let [records (filterv #(and (nil? (:doi %)) (nil? (:arxiv %)))
                                (:items result))
                claimed (when (seq records)
                          (db-async/<q
                           repo {:transact-db? false}
                           '[:find ?key
                             :in $ [?key ...]
                             :where
                             [?value :block/title ?key]
                             [?value :logseq.property/created-from-property ?property]
                             [?property :db/ident :logseq.property/literature-source-key]]
                           (mapv :source-key records)))
                claimed-keys (set (map first claimed))]
          (if (zotero-import-active? repo binding)
            (assoc result
                   :binding binding
                   :items (filterv #(not (contains? claimed-keys (:source-key %))) records))
            {:status :cancelled :reason :binding-inactive}))
        result))
    (p/resolved {:status :blocked :reason :missing-zotero-binding})))

(defn <adopt-manual-zotero-record!
  "Explicitly creates or selects a shared entry through the existing source op.
   Re-reads the shown source before acceptance; later imports keep shared fields."
  [repo binding source-record paper-uuid create?]
  (if-not (zotero-import-active? repo binding)
    (p/resolved {:status :cancelled :reason :binding-inactive})
    (p/let [fresh (zotero/<read-source-record!
                  binding (:source-key source-record)
                  #(zotero-import-active? repo binding))]
      (if-not (and (= :ready (:status fresh))
                   (= source-record (:source-record fresh))
                   (nil? (:doi source-record)) (nil? (:arxiv source-record))
                   (zotero-import-active? repo binding))
        {:status :blocked :reason :source-changed}
        (p/let [registered (db-async/<register-literature-source-record
                           repo (:graph-binding-uuid binding)
                           (:destination-page-uuid binding)
                           (assoc source-record :paper-uuid paper-uuid :create? create?))]
          (if (contains? #{:created :linked :already-imported} (:status registered))
            (p/let [attachments (<refresh-zotero-items!
                                repo [(zotero/source-key-item-key binding
                                                                (:source-key source-record))])]
              (if (= :complete (:status attachments)) registered attachments))
            registered))))))

(defn <poll-zotero-notifications-once!
  "Runs one optional companion long-poll cycle. Callers own repetition and
   cancellation; binding this graph never starts a hidden loop."
  ([repo notification-state]
   (<poll-zotero-notifications-once! repo notification-state nil))
  ([repo notification-state signal]
   (if-let [binding (zotero-binding repo)]
     (p/let [result (zotero/<process-notifier-cycle!
                    binding
                    notification-state
                    #(zotero-import-active? repo binding)
                    (zotero-import-callback repo binding)
                    signal)]
       (when (and (= :complete (:status result)) (zotero-import-active? repo binding))
         (record-import-state! repo :complete (:import result)))
       (when (= :library-identity-mismatch (:reason result))
         (set-zotero-binding! repo nil))
       result)
     (p/resolved {:status :blocked :reason :missing-zotero-binding}))))

(defn- retry-delay-ms
  [attempt]
  (min 30000 (* 1000 (bit-shift-left 1 (min attempt 5)))))

(defn- <abortable-delay
  [delay-ms signal]
  (js/Promise.
   (fn [resolve _reject]
     (if (.-aborted signal)
       (resolve :cancelled)
       (let [timeout-id* (atom nil)
             abort-listener* (atom nil)
             finish! (fn [result]
                       (when-let [timeout-id @timeout-id*]
                         (js/clearTimeout timeout-id))
                       (when-let [abort-listener @abort-listener*]
                         (.removeEventListener signal "abort" abort-listener))
                       (resolve result))
             abort-listener #(finish! :cancelled)]
         (reset! abort-listener* abort-listener)
         (reset! timeout-id* (js/setTimeout #(finish! :elapsed) delay-ms))
         (.addEventListener signal "abort" abort-listener #js {:once true}))))))

(defn- same-zotero-task?
  [token repo binding]
  (let [task @*zotero-sync-task]
    (and (= token (:token task))
         (= repo (:repo task))
         (= binding (:binding task))
         (not (.-aborted ^js (:signal task)))
         (zotero-import-active? repo binding))))

(defn stop-zotero-sync!
  "Stops the one renderer-owned Zotero request/backoff task, if present."
  ([] (stop-zotero-sync! :stopped))
  ([reason]
   (if-let [{:keys [^js controller finish!]} @*zotero-sync-task]
     (do
       (reset! *zotero-sync-task nil)
       (finish! {:status :cancelled :reason reason})
       (.abort controller)
       {:status :stopped :reason reason})
     {:status :idle :reason reason})))

(defn- start-zotero-sync!
  [repo binding]
  (stop-zotero-sync! :replaced)
  (let [controller (js/AbortController.)
        signal (.-signal controller)
        token (random-uuid)
        initial-state {:session nil :cursor 0}
        completion (p/deferred)
        finished? (atom false)
        finish! (fn [result]
                  (when (compare-and-set! finished? false true)
                    (swap! *zotero-sync-task
                           (fn [active]
                             (when-not (= token (:token active)) active)))
                    (p/resolve! completion result)))
        task {:token token
              :repo repo
              :binding binding
              :controller controller
              :signal signal
              :finish! finish!
              :run completion}]
    (reset! *zotero-sync-task task)
    (state/update-state! [:literature/import-state repo] #(assoc % :notifier-error? false))
    (letfn [(retry! [notification-state attempt error]
              (if-not (same-zotero-task? token repo binding)
                (finish! {:status :cancelled :reason :lifecycle-inactive})
                (let [delay-ms (retry-delay-ms attempt)]
                  (state/update-state! [:literature/import-state repo] #(assoc % :notifier-error? true))
                  (when (or (zero? attempt) (= 5 attempt))
                    (log/warn :literature-zotero-poll-failed
                              {:type (:type (ex-data error))
                               :status (:status (ex-data error))
                               :retry-ms delay-ms}))
                  (-> (<abortable-delay delay-ms signal)
                      (p/then
                       (fn [delay-result]
                         (if (and (= :elapsed delay-result)
                                  (same-zotero-task? token repo binding))
                           (do
                             (step! notification-state (inc attempt))
                             nil)
                           (finish! {:status :cancelled
                                     :reason :lifecycle-inactive}))))
                      (p/catch
                       (fn [delay-error]
                         (finish! {:status :failed
                                   :reason :backoff-failed
                                   :error delay-error})))))))
            (step! [notification-state attempt]
              (if-not (same-zotero-task? token repo binding)
                (finish! {:status :cancelled :reason :lifecycle-inactive})
                (-> (p/resolved nil)
                    (p/then
                     (fn [_]
                       (<poll-zotero-notifications-once!
                        repo notification-state signal)))
                    (p/then
                     (fn [result]
                       (if (and (same-zotero-task? token repo binding)
                                (contains? #{:complete :idle} (:status result)))
                         (do
                           (state/update-state! [:literature/import-state repo] #(assoc % :notifier-error? false))
                           (step! (:notification-state result) 0)
                           nil)
                         (finish! result))))
                    (p/catch
                     (fn [error]
                       (retry! notification-state attempt error)
                       nil)))))]
      (step! initial-state 0)
      {:status :started :repo repo :run completion})))

(defn init-zotero-lifecycle!
  "Installs event-driven graph/restore cancellation hooks once."
  []
  (when (compare-and-set! *zotero-lifecycle-installed? false true)
    (rfx/listen!
     ::zotero-lifecycle
     (fn [previous current]
       (let [previous-repo (:git/current-repo previous)
             current-repo (:git/current-repo current)
             previous-restoring? (:db/restoring? previous)
             current-restoring? (:db/restoring? current)]
         (cond
           (not= previous-repo current-repo)
           (stop-zotero-sync! :graph-changed)

           current-restoring?
           (stop-zotero-sync! :db-restoring)

           (and previous-restoring? (not current-restoring?) current-repo)
           (reconcile-zotero-lifecycle! current-repo))))))
  :installed)

(defn shutdown-zotero-lifecycle!
  "Removes lifecycle hooks and stops the active task. Used by tests/reloads."
  []
  (rfx/unlisten! ::zotero-lifecycle)
  (reset! *zotero-lifecycle-installed? false)
  (stop-zotero-sync! :shutdown))

(defn reconcile-zotero-lifecycle!
  "Starts exactly one Zotero task only for the current, enabled, bound desktop
   graph. Possessing this local binding designates this client as importer."
  [repo]
  (if-not (util/electron?)
    (do (stop-zotero-sync! :desktop-required)
        {:status :blocked :reason :desktop-required})
    (let [binding (zotero-binding repo)
          active @*zotero-sync-task]
      (cond
        (or (nil? binding) (not (zotero-import-active? repo binding)))
        (do
          (rfx/unlisten! ::zotero-lifecycle)
          (reset! *zotero-lifecycle-installed? false)
          (stop-zotero-sync! :binding-inactive)
          {:status :blocked :reason :binding-inactive})

        (and (= repo (:repo active))
             (= binding (:binding active))
             (not (.-aborted ^js (:signal active))))
        {:status :running :repo repo :run (:run active)}

        :else
        (do
          (init-zotero-lifecycle!)
          (start-zotero-sync! repo binding))))))

(def ^:private max-auto-identification-bytes
  (* 32 1024 1024))

(def ^:private doi-pattern
  #"\b10\.\d{4,9}/[-._;()/:a-z0-9]+")

(def ^:private arxiv-pattern
  #"\barxiv:\s*((?:\d{4}\.\d{4,5}|[a-z][a-z0-9.-]*/\d{7}))(v\d+)?\b")

(defn- normalize-text
  [value]
  (some-> value
          str
          (.normalize "NFKC")
          string/lower-case
          (string/replace #"\s+" " ")
          string/trim))

(defn- trim-doi-tail
  [value]
  (string/replace value #"[.,;:]+$" ""))

(defn- compact-text-length
  [value]
  (count (string/replace (or value "") #"\s+" "")))

(defn- join-visual-line-items
  [items]
  (-> (reduce (fn [{:keys [text previous]} item]
                (let [current (or (:text item) "")
                      adjacent-text? (and previous
                                          (not (re-find #"\s$" text))
                                          (not (re-find #"^\s" current)))
                      geometry? (and adjacent-text?
                                     (number? (:x previous))
                                     (number? (:width previous))
                                     (number? (:x item))
                                     (number? (:font-size previous))
                                     (number? (:font-size item)))
                      gap (when geometry?
                            (- (:x item)
                               (+ (:x previous) (:width previous))))
                      reference-font (when geometry?
                                       (min (:font-size previous)
                                            (:font-size item)))
                      insert-space? (and adjacent-text?
                                         (or (not geometry?)
                                             (> gap (* reference-font 0.2))))]
                  {:text (str text (when insert-space? " ") current)
                   :previous item}))
              {:text "" :previous nil}
              items)
      :text
      (string/replace #"\s+" " ")
      string/trim))

(defn- items->lines
  [items]
  (->> items
       (sort-by (juxt (comp - :y) #(or (:x %) 0)))
       (reduce (fn [lines item]
                 (if (and (seq lines)
                          (<= (js/Math.abs (- (:y (peek lines)) (:y item))) 2))
                   (update-in lines [(dec (count lines)) :items] conj item)
                   (conj lines {:y (:y item) :items [item]})))
               [])
       (mapv (fn [{:keys [items] :as line}]
               (let [items (sort-by #(or (:x %) 0) items)
                     font-sizes (keep :font-size items)]
                 (assoc line
                        :items items
                        :text (join-visual-line-items items)
                        :maximum-font (when (seq font-sizes)
                                        (apply max font-sizes))
                        :minimum-font (when (seq font-sizes)
                                        (apply min font-sizes))))))))

(defn- identifier-matches
  [items]
  (let [reference-y (some (fn [{:keys [text y]}]
                            (when (re-matches #"(?:\d+(?:\.\d+)*\.?\s+)?(?:references|bibliography)\s*:?"
                                              (normalize-text text)) y))
                          (items->lines (filter #(number? (:y %)) items)))
        cited-dois (->> (string/join " " (map :text items))
                        normalize-text
                        (re-seq #"(10\.\d{4,9}/[-._;()/:a-z0-9]+)\s*\[\d+(?:\s*[,–-]\s*\d+)*\]")
                        (map (comp trim-doi-tail second))
                        set)]
  (loop [remaining items
         result []]
    (if-let [item (first remaining)]
      (let [text (or (:text item) "")
            normalized (normalize-text text)
            reference-context? (boolean (and reference-y (number? (:y item)) (< (:y item) reference-y)))
            dois (map (fn [value]
                        {:kind :doi
                         :value (trim-doi-tail value)
                         :trailing-dot? (string/ends-with? value ".")
                         :y (:y item)
                         :reference-context? (or reference-context? (contains? cited-dois (trim-doi-tail value)))})
                      (re-seq doi-pattern normalized))
            arxivs (map (fn [[_ identifier version]]
                          {:kind :arxiv
                           :value (str identifier version)
                           :y (:y item)
                           :reference-context? reference-context?})
                        (re-seq arxiv-pattern normalized))]
        (recur (next remaining)
               (into result (concat dois arxivs))))
      (let [candidates (->> result
                            (reduce (fn [acc candidate]
                                      (assoc acc [(:kind candidate) (:value candidate)] candidate))
                                    {})
                            vals
                            vec)]
        (->> candidates
             (remove (fn [{:keys [kind value trailing-dot?] :as candidate}]
                       (and (= :doi kind)
                            trailing-dot?
                            (some (fn [other]
                                    (and (not= candidate other)
                                         (= :doi (:kind other))
                                         (string/starts-with?
                                          (:value other)
                                          (str value "."))))
                                  candidates))))
             (mapv #(dissoc % :trailing-dot?))))))))

(def ^:private title-layout-punctuation (js/RegExp. "[^\\p{L}\\p{N}]" "gu"))

(defn- title-key
  [value]
  (some-> value normalize-text (string/replace title-layout-punctuation "")))

(defn- embedded-title-lines
  "An embedded title is usable only when the visible upper-page text contains it."
  [title lines]
  (let [title (title-key title)
        positions (reductions (fn [offset line] (+ offset (count (title-key (:text line))))) 0 lines)
        text (apply str (map (comp title-key :text) lines))
        start (when (and title (<= 12 (count title))) (string/index-of text title))]
    (when (some? start)
      (let [end (+ start (count title))]
        (->> (map vector lines positions (rest positions))
             (keep (fn [[line from to]] (when (and (< from end) (> to start)) line)))
             vec)))))

(defn- title-and-authors
  [{:keys [items page-height] :as extraction}]
  (let [top-threshold (* (or page-height 800) 0.5)
        fallback-title (->> items
                            (filter #(and (seq (string/trim (or (:text %) "")))
                                          (number? (:font-size %))
                                          (or (not (number? (:y %)))
                                              (>= (:y %) top-threshold))))
                            (sort-by (juxt (comp - :font-size)
                                           (comp - #(or % 0) :y)))
                            first)
        eligible-items (->> items
                            (filter #(and (number? (:y %))
                                          (number? (:font-size %))
                                          (>= (:y %) top-threshold)
                                          (pos? (compact-text-length (:text %))))))
        page-lines (items->lines eligible-items)
        embedded-lines (embedded-title-lines (get-in extraction [:info :title]) page-lines)
        seed-line (->> page-lines
                       (filter #(>= (compact-text-length (:text %)) 12))
                       (sort-by (juxt (comp - :maximum-font) (comp - :y)))
                       first)
        seed-font (:maximum-font seed-line)
        font-tolerance (when seed-line (max 0.75 (* seed-font 0.04)))
        minimum-title-font (when seed-line
                             (- (:minimum-font seed-line) font-tolerance))
        lines (when seed-line
                (->> eligible-items
                     (filter #(and (>= (:font-size %) minimum-title-font)
                                   (<= (:font-size %)
                                       (+ seed-font font-tolerance))))
                     items->lines
                     (filterv #(>= (compact-text-length (:text %)) 3))))
        seed-index (when seed-line
                     (first (keep-indexed
                             (fn [index line]
                               (when (<= (js/Math.abs
                                          (- (:y line) (:y seed-line)))
                                         2)
                                 index))
                             lines)))
        max-line-gap (when seed-line (* seed-font 1.65))
        first-index (when (some? seed-index)
                      (loop [index seed-index]
                        (if (and (pos? index)
                                 (<= (- (:y (nth lines (dec index)))
                                        (:y (nth lines index)))
                                     max-line-gap))
                          (recur (dec index))
                          index)))
        last-index (when (some? seed-index)
                     (loop [index seed-index]
                       (if (and (< (inc index) (count lines))
                                (<= (- (:y (nth lines index))
                                       (:y (nth lines (inc index))))
                                    max-line-gap))
                         (recur (inc index))
                         index)))
        title-lines (or embedded-lines
                        (when (some? first-index) (subvec lines first-index (inc last-index))))
        title (or (when embedded-lines (some-> (get-in extraction [:info :title]) string/trim not-empty))
                  (some->> title-lines
                           (map :text)
                           (string/join " ")
                           (#(string/replace % #"\s+" " "))
                           (#(string/replace % #"-\s+" "-"))
                           string/trim
                           not-empty)
                  (some-> (:text fallback-title) string/trim not-empty))
        title-bottom (if (seq title-lines)
                       (apply min (map :y title-lines))
                       (:y fallback-title))
        title-font-size (or seed-font (:font-size fallback-title))
        author-text (when (and title (number? title-bottom))
                      (->> items
                           (filter #(and (number? (:y %))
                                         (< (:y %) title-bottom)
                                         (>= (:y %) (- title-bottom 90))
                                         (< (or (:font-size %) 0) title-font-size)
                                         (let [text (normalize-text (:text %))]
                                           (and (not (re-find doi-pattern text))
                                                (not (re-find arxiv-pattern text))))))
                           (sort-by (juxt (comp - :y) #(or (:x %) 0)))
                           (map :text)
                           (remove string/blank?)
                           (string/join " ")
                           (#(string/replace % #"\s+" " "))))]
    {:title title
     :author-text (some-> author-text string/trim not-empty)}))

(defn extraction->candidates
  "Turns a bounded first-page extraction into review-only candidates.
   Incomplete or unreadable extraction is never usable for automatic decisions."
  [{:keys [status reason info] :as extraction}]
  (if (not= :complete status)
    {:status status
     :reason reason
     :usable? false
     :identifiers []}
    (let [{:keys [title author-text]} (title-and-authors extraction)
          info-title (:title info)
          info-author (:author info)]
      {:status status
       :reason reason
       :usable? true
       :identifiers (identifier-matches (:items extraction))
       :title title
       :author-text author-text
       :metadata {:title info-title
                  :author info-author}
       ;; Embedded producer titles often retain an old word-processor filename.
       ;; The visual title is authoritative when present; info remains a fallback.
       :conflicts {:title? false}})))

(defn- js-extraction->clj
  [value]
  (let [result (bean/->clj value)]
    (-> result
        (set/rename-keys {:pageHeight :page-height
                          :pageWidth :page-width
                          :totalPages :total-pages
                          :pagesRequested :pages-requested
                          :sourceItems :source-items
                          :elapsedMs :elapsed-ms})
        (update :status keyword)
        (update :reason keyword)
        (update :items
                (fn [items]
                  (mapv #(set/rename-keys % {:fontSize :font-size
                                             :hasEOL :has-eol?})
                        items))))))

(defn- pdf-assets-url
  []
  (str (if (and (not (util/electron?))
                (some-> js/globalThis .-location .-host
                        (string/ends-with? "logseq.com")))
         "./static/"
         "./")
       "js/pdfjs/"))

(defn ^:no-doc <extract-first-page
  [pdf-bytes signal]
  (let [assets-url (pdf-assets-url)]
    (-> (js-literature/extractFirstPageLiterature
         pdf-bytes
         #js {:signal signal
              :deadlineMs 1500
              :maxChars 20000
              :maxItems 600
              :workerSrc (str assets-url "pdf.worker.mjs")
              :cMapUrl (str assets-url "cmaps/")
              :cMapPacked true})
        (p/then js-extraction->clj))))

(defn cancel-active-identification!
  []
  (when-let [^js controller (:controller @*active-task)]
    (.abort controller)))

(defn- current-graph?
  [repo]
  (and (= repo (state/get-current-repo))
       (not (state/get-state :db/restoring?))))

(defn <paper-sources!
  "Reads one Paper's shared source identities without enabling local imports."
  [repo paper-uuid]
  (if (current-graph? repo)
    (db-async/<paper-literature-sources repo paper-uuid)
    (p/resolved {:status :blocked :reason :inactive-graph})))

(defn <correct-paper-source!
  "Corrects one shared source relation through the native worker user transaction."
  [repo paper-uuid source action target-paper-uuid]
  (if (current-graph? repo)
    (db-async/<correct-literature-source-record
     repo paper-uuid source action target-paper-uuid)
    (p/resolved {:status :blocked :reason :inactive-graph})))

(defn <create-manual-paper!
  "Creates an ordinary literature block through the native user transaction.
   A PDF, source binding, identifier, and metadata are optional."
  [repo destination-page-uuid title]
  (p/let [[destination destination-route]
          (when (current-graph? repo)
            (p/all [(db-async/<get-block repo destination-page-uuid {:children? false})
                    (state/<invoke-db-worker :thread-api/get-page-route-info repo destination-page-uuid)]))]
    (cond
      (not (current-graph? repo))
      {:status :blocked :reason :inactive-graph}

      (not (and (ldb/internal-page? destination)
                (not (ldb/class? destination))
                (not (ldb/property? destination))
                (not (ldb/built-in? destination))
                (false? (:hidden? destination-route))))
      {:status :blocked :reason :destination-unavailable}

      :else
      (let [paper-uuid (random-uuid)
            block {:block/uuid paper-uuid
                   :block/title (string/trim title)
                   :logseq.property/literature-item? true}]
        (p/let [_ (ui-outliner-tx/transact!
                   {:outliner-op :insert-blocks}
                   (outliner-op/insert-blocks!
                    [block] destination-page-uuid
                    {:keep-uuid? true :bottom? true :sibling? false}))
                saved (db-async/<get-block repo paper-uuid {:children? false})]
          (when-not (and (= paper-uuid (:block/uuid saved))
                         (true? (:logseq.property/literature-item? saved))
                         (= destination-page-uuid (get-in saved [:block/parent :block/uuid])))
            (throw (ex-info "Manual literature block was not inserted at its destination"
                            {:repo repo :paper-uuid paper-uuid})))
          {:status :created :paper-uuid paper-uuid})))))

(defn <add-doi-paper!
  [repo destination-page-uuid record]
  (if (current-graph? repo)
    (db-async/<add-literature-doi-paper repo destination-page-uuid record)
    (p/resolved {:status :blocked :reason :inactive-graph})))

(defn <default-destination!
  "Resolves the explicit Add action's normal page without activating imports."
  [repo]
  (when (current-graph? repo)
    (p/let [configured (some-> (load-settings repo) :zotero-binding :destination-page-uuid)
            info (when configured
                   (state/<invoke-db-worker :thread-api/get-page-route-info repo configured))]
      (when (current-graph? repo)
        (if (and configured (false? (:hidden? info)))
          (db-async/<get-block repo configured {:children? false})
          (page-common-handler/<create! (t :literature/default-page-title)
                               {:redirect? false :edit? false :expected-repo repo}))))))

(def library-hidden-rules
  '[[ (literature-hidden ?e) [?e :logseq.property/hide? true]]
    [ (literature-hidden ?e) [?e :logseq.property/deleted-at _]]
    [ (literature-hidden ?e) [?e :block/parent ?parent]
      (literature-hidden ?parent)]])

(def library-window-query
  '[:find (max 51 ?paper)
    :in $ ?before %
    :where
    [?paper :logseq.property/literature-item? true]
    [?paper :block/uuid _]
    [?paper :block/title _]
    [(< ?paper ?before)]
    (not-join [?paper] (literature-hidden ?paper))])

(defn library-window
  "Normalizes the bounded aggregate's unordered result before paging."
  [rows]
  (vec (sort > (first rows))))

(def library-rows-query
  '[:find ?paper ?uuid ?short-title
    (pull ?paper [{:logseq.property/literature-authors [:block/title]}
                  {:logseq.property/literature-date [:block/title]}
                  {:logseq.property/literature-doi [:block/title]}
                  {:logseq.property/literature-arxiv [:block/title]}])
    :in $ [?paper ...] %
    :where
    [?paper :logseq.property/literature-item? true]
    [?paper :block/uuid ?uuid]
    [?paper :block/title ?title]
    [(count ?title) ?length]
    [(min 240 ?length) ?display-length]
    [(subs ?title 0 ?display-length) ?short-title]
    (not-join [?paper] (literature-hidden ?paper))])

(defn library-query-spec
  [query inputs]
  {:kind :datalog :query query :inputs inputs
   :rules library-hidden-rules :remove-block-children? false})

(defn review-identifiers
  "Returns only unambiguous, non-reference identifiers from a review result."
  [review]
  (->> (get-in review [:candidates :identifiers])
       (remove :reference-context?)
       (filter #(contains? #{:doi :arxiv} (:kind %)))
       (group-by :kind)
       (keep (fn [[kind matches]]
               (when (= 1 (count matches))
                 [kind (:value (first matches))])))
       (into {})))

(defn review-title
  [review]
  (or (some-> (get-in review [:candidates :title]) string/trim not-empty)
      (some-> (get-in review [:candidates :metadata :title]) string/trim not-empty)))

(defn automatic-review-evidence
  "Builds the strict evidence payload for the worker, or explains why the
   review must remain manual. Reference identifiers never participate."
  [review]
  (let [candidates (:candidates review)
        identifiers (->> (:identifiers candidates)
                         (remove :reference-context?)
                         (filter #(contains? #{:doi :arxiv} (:kind %))))
        grouped (group-by :kind identifiers)
        ambiguous? (some #(> (count %) 1) (vals grouped))
        identifiers (when-not ambiguous?
                      (->> grouped
                           (keep (fn [[kind matches]]
                                   (when (= 1 (count matches))
                                     [kind (:value (first matches))])))
                           (into {})))
        title (review-title review)
        author-texts (->> [(:author-text candidates)
                           (get-in candidates [:metadata :author])]
                          (keep #(some-> % str string/trim not-empty))
                          distinct
                          vec)]
    (cond
      (not= :complete (get-in review [:extraction :status]))
      {:status :blocked :reason :incomplete-extraction}

      (not (true? (:usable? candidates)))
      {:status :blocked :reason :unusable-extraction}

      (true? (get-in candidates [:conflicts :title?]))
      {:status :blocked :reason :title-conflict}

      ambiguous?
      {:status :blocked :reason :ambiguous-identifiers}

      (empty? identifiers)
      {:status :blocked :reason :missing-identifiers}

      (nil? title)
      {:status :blocked :reason :missing-title}

      (empty? author-texts)
      {:status :blocked :reason :missing-author-evidence}

      :else
      {:status :ready
       :evidence {:identifiers identifiers
                  :title title
                  :author-texts author-texts
                  :extraction-status :complete
                  :usable? true
                  :title-conflict? false
                  :identifier-ambiguous? false}})))

(defn review-candidate
  [repo asset-uuid]
  (state/get-state [:literature/review-candidates repo (str asset-uuid)]))

(def ^:private retained-extraction-keys
  [:status :reason :version :total-pages :pages-requested
   :chars :source-items :elapsed-ms])

(defn- compact-review-result
  [result]
  (update result :extraction
          #(if (map? %)
             (select-keys % retained-extraction-keys)
             %)))

(defn store-review-result!
  "Stores one small, graph-local review record. PDF bytes and incoming File
   objects are never retained here."
  [repo repo-dir asset-dir-rpath result]
  (when (and (current-graph? repo) (:asset-uuid result))
    (state/set-state!
     [:literature/review-candidates repo (str (:asset-uuid result))]
     (assoc (compact-review-result result)
            :repo-dir repo-dir
            :asset-dir-rpath asset-dir-rpath))))

(defn clear-review-candidate!
  [repo asset-uuid]
  (state/update-state!
   [:literature/review-candidates repo]
   #(dissoc (or % {}) (str asset-uuid))))

(defn <keep-pdf!
  "Persists the ordinary-PDF choice before clearing its local review."
  [repo asset-uuid]
  (if-not (current-graph? repo)
    (p/resolved {:status :blocked :reason :inactive-graph})
    (p/let [result (db-async/<keep-pdf-as-ordinary repo asset-uuid)]
      (when (= :kept (:status result))
        (clear-review-candidate! repo asset-uuid))
      result)))

(defn- record-automatic-result!
  [repo asset-uuid result]
  (state/update-state!
   [:literature/review-candidates repo (str asset-uuid)]
   (fn [review]
     (when review
       (assoc review :automatic-result
              (select-keys result
                           [:status :reason :action :paper-uuid :asset-uuid]))))))

(defn- add-filename-identifier
  [result]
  (if (seq (remove :reference-context? (get-in result [:candidates :identifiers])))
    result
    (if-let [[_ identifier version]
             (some->> (:asset-title result)
                      string/lower-case
                      (re-find #"^(\d{4}\.\d{4,5})(v[1-9]\d*)?(?:_|\.pdf$|$)"))]
      (update-in result [:candidates :identifiers] conj
                 {:kind :arxiv :value (str identifier version) :reference-context? false})
      result)))

(defn <apply-review-result!
  "Runs the same verified transaction for a saved PDF. Explicit organization
   authorizes this task only; ordinary automatic imports still require settings."
  [repo repo-dir asset-dir-rpath result {:keys [explicit? allow-online? context signal]}]
  (let [result (add-filename-identifier result)
        asset-uuid (:asset-uuid result)
        current? #(and (current-graph? repo)
                       (or (nil? context) (= context (db-subs/projection-context)))
                       (not (and signal (.-aborted signal))))]
    (store-review-result! repo repo-dir asset-dir-rpath result)
    (cond
      (not (current?)) (p/resolved {:status :blocked :reason :inactive-graph})
      (and (not explicit?) (not (auto-identification-enabled? repo)))
      (p/resolved {:status :blocked :reason :automatic-identification-disabled})
      :else
      (let [{:keys [status reason evidence]} (automatic-review-evidence result)
            online? #(and (current?)
                          (if explicit? allow-online? (online-metadata-enabled? repo)))]
        (-> (if (not= :ready status)
              (p/resolved {:status :blocked
                           :reason (if (= :incomplete-extraction reason)
                                     (or (get-in result [:extraction :reason]) reason)
                                     reason)})
              (p/let [linked (db-async/<auto-link-pdf-to-literature repo asset-uuid evidence)]
                (if (and (db-literature/needs-verified-metadata? linked)
                         (util/electron?) (online?))
                  (p/let [lookup (metadata/<lookup-record! (:identifiers evidence) signal)]
                    (if (= :ready (:status lookup))
                      (if (online?)
                        (db-async/<register-verified-pdf-as-literature
                         repo asset-uuid evidence (:record lookup))
                        {:status :blocked :reason :inactive-graph})
                      lookup))
                  linked)))
            (p/then (fn [applied]
                      (if (contains? #{:created :linked :already-linked} (:status applied))
                        (clear-review-candidate! repo asset-uuid)
                        (record-automatic-result! repo asset-uuid applied))
                      applied)))))))

(defn <store-and-maybe-auto-link!
  [repo repo-dir asset-dir-rpath result]
  (-> (<apply-review-result! repo repo-dir asset-dir-rpath result
                            {:signal (some-> @*active-task :controller .-signal)})
      (p/catch
       (fn [error]
         (let [asset-uuid (:asset-uuid result)
               failure {:status :failed :reason :auto-link-error}]
           (log/error :literature-auto-link-failed
                      {:asset-uuid asset-uuid :error error})
           (record-automatic-result! repo asset-uuid failure)
           failure)))))

(defn- reviewable-pdf?
  [asset]
  (and (uuid? (:block/uuid asset))
       (= "pdf" (:logseq.property.asset/type asset))
       (nil? (:logseq.property/deleted-at asset))
       (some #(= :logseq.class/Asset (:db/ident %)) (:block/tags asset))))

(defn ensure-manual-review!
  "Uses supplied current native PDF metadata; changed fingerprints discard old
   extraction without reading the file or starting identification."
  [repo asset-block]
  (let [asset-uuid (:block/uuid asset-block)
        cached (review-candidate repo asset-uuid)]
    (when (and (current-graph? repo) (reviewable-pdf? asset-block))
      (if (and cached
               (= [(:checksum cached) (:byte-size cached)]
                  [(:logseq.property.asset/checksum asset-block)
                   (:logseq.property.asset/size asset-block)]))
        cached
        (let [review {:asset-uuid asset-uuid
                      :asset-title (:block/title asset-block)
                      :file-path (str asset-uuid ".pdf")
                      :checksum (:logseq.property.asset/checksum asset-block)
                      :byte-size (:logseq.property.asset/size asset-block)
                      :repo-dir (config/get-repo-dir repo)
                      :asset-dir-rpath "assets"
                      :extraction {:status :not-run :reason :manual}
                      :candidates {:status :not-run
                                   :reason :manual
                                   :usable? false
                                   :identifiers []}}]
          (state/set-state!
           [:literature/review-candidates repo (str asset-uuid)] review)
          review)))))

(def ^:private current-review-query
  '[:find (pull ?asset [:block/uuid :logseq.property.asset/type
                       :logseq.property.asset/checksum :logseq.property.asset/size
                       :logseq.property.asset/literature-auto-excluded?
                       {:block/tags [:db/ident]}])
    ?short-title ?paper-uuid ?short-paper-title
    :in $ ?asset-uuid
    :where
    [?asset :block/uuid ?asset-uuid]
    [?asset :block/tags :logseq.class/Asset]
    [?asset :logseq.property.asset/type "pdf"]
    [?asset :block/title ?title]
    (not [?asset :logseq.property/deleted-at])
    [(count ?title) ?length]
    [(min 240 ?length) ?display-length]
    [(subs ?title 0 ?display-length) ?short-title]
    (or-join [?asset ?paper-uuid ?short-paper-title]
      (and [?paper :logseq.property/literature-assets ?asset]
           [?paper :logseq.property/literature-item? true]
           [?paper :block/uuid ?paper-uuid]
           [?paper :block/title ?paper-title]
           (not [?paper :logseq.property/deleted-at])
           [(count ?paper-title) ?paper-length]
           [(min 240 ?paper-length) ?paper-display-length]
           [(subs ?paper-title 0 ?paper-display-length) ?short-paper-title])
      (and (not-join [?asset]
             [?paper :logseq.property/literature-assets ?asset]
             [?paper :logseq.property/literature-item? true]
             [?paper :block/uuid _]
             [?paper :block/title _]
             (not [?paper :logseq.property/deleted-at]))
           [(ground false) ?paper-uuid]
           [(ground "") ?short-paper-title]))])

(defn <prepare-review!
  "Reads the selected native Asset and every current owner from one DB snapshot.
   This never reads PDF bytes, starts identification, or writes graph data."
  [repo asset-uuid]
  (let [context (db-subs/projection-context)
        current? #(and (current-graph? repo)
                       (= repo (:graph-id context))
                       (= context (db-subs/projection-context)))
        unavailable {:status :unavailable}]
    (if-not (and (uuid? asset-uuid) (current?))
      (p/resolved unavailable)
      ;; A selected edit form needs the full title, not the bounded list label.
      (p/let [rows (db-async/<q repo {} (assoc current-review-query 2 '?title) asset-uuid)]
        (if-not (and (current?) (seq rows))
          unavailable
          (let [[asset-block title] (first rows)
                asset (assoc asset-block :block/title title)
                papers (->> rows
                            (keep (fn [[_ _ paper-uuid paper-title]]
                                    (when (uuid? paper-uuid)
                                      {:block/uuid paper-uuid :block/title paper-title})))
                            distinct
                            (sort-by (comp str :block/uuid))
                            vec)]
            (case (count papers)
              0 {:status :ready :asset asset
                 :review (ensure-manual-review! repo asset)}
              1 (do
                  (clear-review-candidate! repo asset-uuid)
                  {:status :linked :paper (first papers)})
              {:status :conflict :asset asset :papers papers})))))))

(defn review-candidate-page
  "Returns a page of existing graph-local memory records, without querying DB
   metadata. An explicit continuation later rechecks the selected current Asset."
  [repo after]
  (if-not (current-graph? repo)
    {:status :unavailable :entries [] :next-cursor nil}
    (let [window (->> (state/get-state [:literature/review-candidates repo])
                      (filter val)
                      (sort-by key)
                      (drop-while #(and after (<= (compare (key %) after) 0)))
                      (take 51)
                      vec)
          entries (mapv (fn [[_ review]]
                          (let [title (or (review-title review)
                                          (:asset-title review)
                                          (:file-path review)
                                          (str (:asset-uuid review)))]
                            {:asset-uuid (:asset-uuid review)
                             :title (subs title 0 (min 240 (count title)))
                             :review-status (or (:review-status review)
                                                (get-in review [:extraction :status]))
                             :reason (or (get-in review [:automatic-result :reason])
                                         (get-in review [:extraction :reason]))}))
                        (take 50 window))]
      {:status :ok :entries entries
       :next-cursor (when (> (count window) 50)
                      (key (nth window 49)))})))

(defn <register-pdf!
  "Registers an existing PDF from explicit user input. A review candidate is
   optional and is cleared only after the worker accepts the operation."
  [repo asset-uuid title identifiers & [metadata]]
  (if-not (current-graph? repo)
    (p/resolved {:status :blocked :reason :inactive-graph})
    (p/let [result (apply db-async/<register-pdf-as-literature
                       (cond-> [repo asset-uuid title identifiers] (seq metadata) (conj metadata)))]
      (when (contains? #{:created :linked :already-linked} (:status result))
        (clear-review-candidate! repo asset-uuid))
      result)))


(defn <lookup-doi!
  [repo doi signal]
  (let [identifiers (try (db-literature/normalize-identifiers {:doi doi})
                         (catch :default _ nil))]
    (cond
      (not (current-graph? repo)) (p/resolved {:status :blocked :reason :inactive-graph})
      (nil? (:doi identifiers)) (p/resolved {:status :blocked :reason :invalid-identifier})
      :else (metadata/<lookup-record! identifiers signal))))

(defn <plan-doi-metadata!
  [repo paper-uuid record asset-uuid]
  (if (current-graph? repo)
    (db-async/<plan-literature-doi-metadata repo paper-uuid record asset-uuid)
    (p/resolved {:status :blocked :reason :inactive-graph})))

(defn <apply-doi-metadata!
  [repo paper-uuid record accepted-changes asset-uuid]
  (if (current-graph? repo)
    (db-async/<apply-literature-doi-metadata repo paper-uuid record accepted-changes asset-uuid)
    (p/resolved {:status :blocked :reason :inactive-graph})))

(def ^:private unregistered-pdf-eids-query
  '[:find (min 51 ?asset) .
    :in $ ?after
    :where
    [?asset :logseq.property.asset/type "pdf"]
    [?asset :block/tags :logseq.class/Asset]
    [?asset :block/uuid _]
    [(> ?asset ?after)]
    (not [?asset :logseq.property/deleted-at])
    (not-join [?asset]
      [?paper :logseq.property/literature-assets ?asset]
      [?paper :logseq.property/literature-item? true]
      [?paper :block/uuid _]
      [?paper :block/title _]
      (not [?paper :logseq.property/deleted-at]))])

(def ^:private unregistered-pdf-rows-query
  '[:find ?asset ?uuid ?short-title ?keep
    (pull ?asset [:logseq.property.asset/checksum :logseq.property.asset/size])
    :in $ [?asset ...]
    :where
    [?asset :logseq.property.asset/type "pdf"]
    [?asset :block/tags :logseq.class/Asset]
    [?asset :block/uuid ?uuid]
    [?asset :block/title ?title]
    (not [?asset :logseq.property/deleted-at])
    (not-join [?asset]
      [?paper :logseq.property/literature-assets ?asset]
      [?paper :logseq.property/literature-item? true]
      [?paper :block/uuid _]
      [?paper :block/title _]
      (not [?paper :logseq.property/deleted-at]))
    [(count ?title) ?length]
    [(min 240 ?length) ?display-length]
    [(subs ?title 0 ?display-length) ?short-title]
    [(get-else $ ?asset :logseq.property.asset/literature-auto-excluded? false) ?keep]])

(defn <unregistered-pdfs!
  "Reads one metadata page on demand. The cursor belongs only to the open view;
   no PDF content, identification, registration or persistent cache is involved."
  [repo cursor & [{:keys [include-kept?]}]]
  (let [unavailable {:status :unavailable :assets [] :next-cursor nil}
        context (db-subs/projection-context)
        same-projection? #(and (current-graph? repo)
                               (= repo (:graph-id context))
                               (= context (db-subs/projection-context)))
        ;; Entity IDs belong to one projection. A repaired graph starts over
        ;; on the next explicit page request, without a background reload.
        after (if (= context (:projection-context cursor)) (:after cursor) 0)]
    (if-not (same-projection?)
      (p/resolved unavailable)
      (p/let [eids (db-async/<q repo {}
                                       (cond-> unregistered-pdf-eids-query
                                         (not include-kept?)
                                         (conj '(not [?asset :logseq.property.asset/literature-auto-excluded? true])))
                                       after)
              window (vec (take 50 eids))
              rows (when (and (seq window) (same-projection?))
                     (db-async/<q repo {} unregistered-pdf-rows-query window))]
        (if-not (same-projection?)
          unavailable
          {:status :ok
           :projection-context context
           :assets (mapv (fn [[eid asset-uuid title keep? file-metadata]]
                           (merge {:db/id eid :block/uuid asset-uuid :block/title title
                                   :logseq.property.asset/literature-auto-excluded? keep?}
                                  file-metadata))
                         (sort-by first rows))
           ;; Advance across the selected window even if a row was linked or
           ;; deleted between queries; never skip the 51st lookahead item.
           :next-cursor (when (> (count eids) 50)
                          {:after (peek window) :projection-context context})})))))

(def ^:private history-subject-queries
  '{:asset-owner
    [:find (min 51 ?subject) . :in $ ?after :where
     [?subject :logseq.property.asset/type "pdf"]
     [(> ?subject ?after)]
     [?subject :block/tags :logseq.class/Asset]
     [?subject :block/uuid _]
     [?subject :block/title _]
     (not [?subject :logseq.property/deleted-at])]
    :checksum
    [:find (min 51 ?subject) . :in $ ?after :where
     [?subject :logseq.property.asset/type "pdf"]
     [(> ?subject ?after)]
     [?subject :block/tags :logseq.class/Asset]
     [?subject :block/uuid _]
     [?subject :block/title _]
     (not [?subject :logseq.property/deleted-at])
     [?subject :logseq.property.asset/checksum ?checksum]
     [(string? ?checksum)]
     [(clojure.string/blank? ?checksum) ?blank]
     [(false? ?blank)]]
    :source-owner
    [:find (min 51 ?subject) . :in $ ?after :where
     [?subject :logseq.property/created-from-property :logseq.property/literature-source-key]
     [(> ?subject ?after)]
     [?subject :logseq.property/literature-source-target ?binding]
     [?subject :block/uuid _]
     [?subject :block/title _]
     [?binding :block/uuid _]
     (not [?subject :logseq.property/deleted-at])
     (not [?binding :logseq.property/deleted-at])]})

(def ^:private history-window-queries
  '{:asset-owner
    [:find ?subject ?uuid ?short-title (count-distinct ?paper)
     :in $ [?subject ...] :where
     [?paper :logseq.property/literature-assets ?subject]
     [?paper :logseq.property/literature-item? true]
     [?paper :block/uuid _]
     [?paper :block/title _]
     (not [?paper :logseq.property/deleted-at])
     [?subject :logseq.property.asset/type "pdf"]
     [?subject :block/tags :logseq.class/Asset]
     [?subject :block/uuid ?uuid]
     [?subject :block/title ?title]
     (not [?subject :logseq.property/deleted-at])
     [(count ?title) ?length]
     [(min 240 ?length) ?display-length]
     [(subs ?title 0 ?display-length) ?short-title]]
    :checksum
    [:find ?subject ?uuid ?short-title ?checksum (count-distinct ?asset)
     :in $ [?subject ...] :where
     [?subject :logseq.property.asset/checksum ?checksum]
     [(string? ?checksum)]
     [(clojure.string/blank? ?checksum) ?blank]
     [(false? ?blank)]
     [?asset :logseq.property.asset/checksum ?checksum]
     [?asset :logseq.property.asset/type "pdf"]
     [?asset :block/tags :logseq.class/Asset]
     [?asset :block/uuid _]
     [?asset :block/title _]
     (not [?asset :logseq.property/deleted-at])
     [?subject :logseq.property.asset/type "pdf"]
     [?subject :block/tags :logseq.class/Asset]
     [?subject :block/uuid ?uuid]
     [?subject :block/title ?title]
     (not [?subject :logseq.property/deleted-at])
     [(count ?title) ?length]
     [(min 240 ?length) ?display-length]
     [(subs ?title 0 ?display-length) ?short-title]]
    :source-owner
    [:find ?subject ?uuid ?short-title ?binding-uuid ?source-key (count-distinct ?paper)
     :in $ [?subject ...] :where
     [(get-some $ ?subject :logseq.property/literature-source-target) [_ ?binding]]
     [(get-some $ ?subject :block/title) [_ ?source-key]]
     [(get-some $ ?subject :block/uuid) [_ ?uuid]]
     [?subject :logseq.property/created-from-property :logseq.property/literature-source-key]
     [(get-some $ ?binding :block/uuid) [_ ?binding-uuid]]
     [(missing? $ ?subject :logseq.property/deleted-at)]
     [(missing? $ ?binding :logseq.property/deleted-at)]
     [?key :block/title ?source-key]
     [(get-some $ ?key :logseq.property/literature-source-target) [_ ?key-binding]]
     [(= ?key-binding ?binding)]
     [?key :logseq.property/created-from-property :logseq.property/literature-source-key]
     [(get-some $ ?key :block/uuid) _]
     [(missing? $ ?key :logseq.property/deleted-at)]
     [?paper :logseq.property/literature-source-key ?key]
     [(get-some $ ?paper :logseq.property/literature-item?) [_ ?literature-marker]]
     [(true? ?literature-marker)]
     [(get-some $ ?paper :block/uuid) _]
     [(get-some $ ?paper :block/title) _]
     [(missing? $ ?paper :logseq.property/deleted-at)]
     [(count ?source-key) ?length]
     [(min 240 ?length) ?display-length]
     [(subs ?source-key 0 ?display-length) ?short-title]]})

(defn- history-entry
  [kind row]
  (when (> (peek row) 1)
    (case kind
      :asset-owner
      (let [[_ asset-uuid title owner-count] row]
        {:identity asset-uuid :title title :count owner-count :asset-uuid asset-uuid})
      :checksum
      (let [[_ asset-uuid title checksum asset-count] row]
        {:identity checksum :title title :count asset-count
         :asset-uuid asset-uuid :checksum checksum})
      :source-owner
      (let [[_ key-uuid title binding-uuid source-key paper-count] row]
        {:identity [binding-uuid source-key] :title title :count paper-count
         :key-uuid key-uuid :binding-uuid binding-uuid :source-key source-key}))))

(defn <history-conflicts!
  "Reads one explicitly selected metadata check. The raw window, including
   nonconflicts, advances the view-local cursor; PDF bytes are never read."
  [repo kind cursor]
  (let [context (db-subs/projection-context)
        current? #(and (current-graph? repo)
                       (= repo (:graph-id context))
                       (= context (db-subs/projection-context)))
        unavailable {:status :unavailable :entries [] :next-cursor nil}
        q1 (get history-subject-queries kind)
        q2 (get history-window-queries kind)
        after (if (and (= repo (:repo cursor))
                       (= kind (:kind cursor))
                       (= context (:projection-context cursor)))
                (:after cursor) 0)]
    (if-not (and q1 q2 (current?))
      (p/resolved unavailable)
      (let [first-query (db-async/<q repo {} q1 after)]
        (p/let [raw first-query
                window (vec (take 50 raw))
                rows (when (and (seq window) (current?))
                       (db-async/<q repo {} q2 window))]
          (if-not (current?)
            unavailable
            {:status :ok :kind kind :projection-context context
             :entries (->> rows
                           (sort-by first)
                           (keep #(history-entry kind %))
                           (reduce (fn [entries entry]
                                     (if (some #(= (:identity %) (:identity entry)) entries)
                                       entries
                                       (conj entries entry))) []))
             ;; Q2 can be empty after a fact change. The raw lookahead still
             ;; belongs to the next page, even when this page has no conflicts.
             :next-cursor (when (> (count raw) 50)
                            {:repo repo :kind kind :projection-context context
                             :after (peek window)})}))))))

(def ^:private history-checksum-group-query
  '[:find (pull ?asset [:block/uuid :logseq.property.asset/type
                       :logseq.property.asset/checksum :logseq.property.asset/size
                       :logseq.property.asset/literature-auto-excluded?
                       {:block/alias [:block/title]}
                       {:block/tags [:db/ident]}])
    ?short-title ?paper-uuid ?short-paper-title
    :in $ ?asset-uuid ?checksum
    :where
    [?anchor :block/uuid ?asset-uuid]
    [?anchor :logseq.property.asset/type "pdf"]
    [?anchor :block/tags :logseq.class/Asset]
    [?anchor :block/title _]
    [?anchor :logseq.property.asset/checksum ?checksum]
    (not [?anchor :logseq.property/deleted-at])
    [?asset :logseq.property.asset/checksum ?checksum]
    [?asset :block/tags :logseq.class/Asset]
    [?asset :logseq.property.asset/type "pdf"]
    [?asset :block/uuid _]
    [?asset :block/title ?title]
    (not [?asset :logseq.property/deleted-at])
    [(count ?title) ?length]
    [(min 240 ?length) ?display-length]
    [(subs ?title 0 ?display-length) ?short-title]
    (or-join [?asset ?paper-uuid ?short-paper-title]
      (and [?paper :logseq.property/literature-assets ?asset]
           [?paper :logseq.property/literature-item? true]
           [?paper :block/uuid ?paper-uuid]
           [?paper :block/title ?paper-title]
           (not [?paper :logseq.property/deleted-at])
           [(count ?paper-title) ?paper-length]
           [(min 240 ?paper-length) ?paper-display-length]
           [(subs ?paper-title 0 ?paper-display-length) ?short-paper-title])
      (and (not-join [?asset]
             [?paper :logseq.property/literature-assets ?asset]
             [?paper :logseq.property/literature-item? true]
             [?paper :block/uuid _]
             [?paper :block/title _]
             (not [?paper :logseq.property/deleted-at]))
           [(ground false) ?paper-uuid]
           [(ground "") ?short-paper-title]))])

(def ^:private history-source-group-query
  '[:find ?member-uuid ?paper-uuid ?short-paper-title
    :in $ ?anchor-uuid ?binding-uuid ?source-key :where
    [?anchor :block/uuid ?anchor-uuid]
    [?anchor :block/title ?source-key]
    [?anchor :logseq.property/created-from-property :logseq.property/literature-source-key]
    [?anchor :logseq.property/literature-source-target ?binding]
    [?binding :block/uuid ?binding-uuid]
    (not [?anchor :logseq.property/deleted-at])
    (not [?binding :logseq.property/deleted-at])
    [?key :block/title ?source-key]
    [?key :logseq.property/literature-source-target ?binding]
    [?key :logseq.property/created-from-property :logseq.property/literature-source-key]
    [?key :block/uuid ?member-uuid]
    (not [?key :logseq.property/deleted-at])
    (or-join [?key ?paper-uuid ?short-paper-title]
      (and [?paper :logseq.property/literature-source-key ?key]
           [?paper :logseq.property/literature-item? true]
           [?paper :block/uuid ?paper-uuid]
           [?paper :block/title ?paper-title]
           (not [?paper :logseq.property/deleted-at])
           [(count ?paper-title) ?length]
           [(min 240 ?length) ?display-length]
           [(subs ?paper-title 0 ?display-length) ?short-paper-title])
      (and (not-join [?key]
             [?paper :logseq.property/literature-source-key ?key]
             [?paper :logseq.property/literature-item? true]
             [?paper :block/uuid _]
             [?paper :block/title _]
             (not [?paper :logseq.property/deleted-at]))
           [(ground false) ?paper-uuid]
           [(ground "") ?short-paper-title]))])

(defn- history-asset-members
  [rows]
  (->> rows
       (group-by (comp :block/uuid first))
       (sort-by (comp str key))
       (mapv (fn [[_ member-rows]]
               (let [[asset title] (first member-rows)]
                 (assoc asset :block/title title
                        :papers (->> member-rows
                                     (keep (fn [[_ _ paper-uuid paper-title]]
                                             (when (uuid? paper-uuid)
                                               {:block/uuid paper-uuid
                                                :block/title paper-title})))
                                     distinct
                                     (sort-by (comp str :block/uuid))
                                     vec)))))))

(defn- history-source-members
  [rows {:keys [binding-uuid source-key]}]
  (->> rows
       (group-by first)
       (sort-by (comp str key))
       (mapv (fn [[key-uuid member-rows]]
               {:key-uuid key-uuid :binding-uuid binding-uuid :source-key source-key
                :papers (->> member-rows
                             (keep (fn [[_ paper-uuid paper-title]]
                                     (when (uuid? paper-uuid)
                                       {:block/uuid paper-uuid :block/title paper-title})))
                             distinct
                             (sort-by (comp str :block/uuid))
                             vec)}))))

(defn <history-conflict-group!
  "Reads the selected identity's complete current group from one DB snapshot.
   Physical source keys retain their separate Paper edges; no correction runs."
  [repo kind {:keys [asset-uuid checksum key-uuid binding-uuid source-key] :as entry}]
  (let [context (db-subs/projection-context)
        current? #(and (current-graph? repo)
                       (= repo (:graph-id context))
                       (= context (db-subs/projection-context)))
        unavailable {:status :unavailable :members []}
        [query inputs] (case kind
                         :asset-owner (when (uuid? asset-uuid)
                                        [current-review-query [asset-uuid]])
                         :checksum (when (and (uuid? asset-uuid) (string? checksum)
                                              (not (string/blank? checksum)))
                                     [history-checksum-group-query [asset-uuid checksum]])
                         :source-owner (when (and (uuid? key-uuid) (uuid? binding-uuid)
                                                  (string? source-key))
                                         [history-source-group-query
                                          [key-uuid binding-uuid source-key]])
                         nil)]
    (if-not (and query (current?))
      (p/resolved unavailable)
      (let [group-query (apply db-async/<q repo {} query inputs)]
        (p/let [rows group-query
                members (if (= kind :source-owner)
                          (history-source-members rows entry)
                          (history-asset-members rows))
                plans (when (and (= :checksum kind) (seq members) (current?))
                        (db-async/<duplicate-pdf-plans repo (mapv :block/uuid members)))]
          (if-not (and (current?) (seq rows))
            unavailable
            {:status :ok :kind kind :projection-context context
             :members (if (= :checksum kind)
                        (mapv #(merge % (select-keys (get plans (:block/uuid %))
                                                   [:status :reason :annotation-count :reference-count])) members)
                        members)}))))))

(defn <search-existing-papers!
  "Searches a bounded title-candidate window only after explicit user input."
  [repo query]
  (let [query (some-> query str string/trim)]
    (cond
      (not (current-graph? repo))
      (p/resolved {:status :unavailable
                   :reason :inactive-graph
                   :papers []
                   :truncated? false})

      (< (count query) 2)
      (p/resolved {:status :idle :papers [] :truncated? false})

      :else
      (db-async/<search-literature-papers repo query))))

(defn <link-pdf-to-paper!
  "Links a PDF to one explicitly selected literature item. The worker checks
   the target and current asset ownership again before transacting."
  [repo asset-uuid target-paper-uuid]
  (if-not (current-graph? repo)
    (p/resolved {:status :blocked :reason :inactive-graph})
    (p/let [result (db-async/<link-pdf-to-literature
                     repo asset-uuid target-paper-uuid)]
      (when (contains? #{:linked :already-linked} (:status result))
        (clear-review-candidate! repo asset-uuid))
      result)))

(defn <rename-paper-pdfs!
  [repo paper-uuid]
  (when (current-graph? repo)
    (state/<invoke-db-worker :thread-api/literature-rename-pdf-titles repo paper-uuid)))

(defn <paper-pdfs-for-comparison!
  [repo paper-uuid]
  (p/let [rows (db-async/<q
                repo {}
                '[:find (pull ?asset [:block/uuid :block/title
                                     :logseq.property.asset/checksum :logseq.property.asset/size
                                     {:block/alias [:block/title]}])
                  :in $ ?paper-uuid
                  :where
                  [?paper :block/uuid ?paper-uuid]
                  [?paper :logseq.property/literature-assets ?asset]
                  [?asset :block/tags :logseq.class/Asset]
                  [?asset :logseq.property.asset/type "pdf"]
                  (not [?asset :logseq.property/deleted-at])]
                paper-uuid)
          assets (mapv first rows)
          usage (when (seq assets)
                  (db-async/<duplicate-pdf-plans repo (mapv :block/uuid assets)))]
    (->> assets
         (map #(merge % (select-keys (get usage (:block/uuid %))
                                    [:annotation-count :reference-count])))
         (sort-by (juxt (comp - :annotation-count) (comp str :block/uuid)))
         vec)))

(defn- <comparison-pdf-bytes!
  [repo asset-uuid signal]
  (p/let [response (js/fetch
                    (assets-handler/normalize-asset-resource-url
                     (path/path-join (config/get-repo-dir repo) "assets" (str asset-uuid ".pdf")))
                    #js {:signal signal})]
    (when-not (.-ok response)
      (throw (ex-info "Comparison PDF is not available locally"
                      {:type :literature/file-not-local})))
    (.arrayBuffer response)))

(defn <compare-pdf-copies!
  [repo paper-uuid assets signal on-progress]
  (let [assets-url (pdf-assets-url)]
    (p/let [a (<comparison-pdf-bytes! repo (:block/uuid (first assets)) signal)
            b (<comparison-pdf-bytes! repo (:block/uuid (second assets)) signal)
            raw (js-literature/compareLiteraturePdfs
                 a b #js {:signal signal :onProgress on-progress
                           :workerSrc (str assets-url "pdf.worker.mjs")
                           :cMapUrl (str assets-url "cmaps/")
                           :standardFontDataUrl (str assets-url "standard_fonts/")})
            result (bean/->clj raw)
            files (zipmap (map :block/uuid assets) (:files result))]
      (if (= "equal" (:status result))
        (if (every? (fn [asset]
                      (= (get files (:block/uuid asset))
                         {:checksum (:logseq.property.asset/checksum asset)
                          :size (:logseq.property.asset/size asset)})) assets)
          {:status :equal :comparison {:rule "pdf-pages-v1" :paper-uuid paper-uuid :assets files}}
          {:status :changed})
        (assoc result :status (keyword (:status result)))))))

(defn <merge-compared-pdfs!
  [repo keep-uuid duplicate-uuid comparison signal]
  ;; Read only this selected pair again before committing. Native replay checks
  ;; the same immutable Asset identities and current bibliography ownership.
  (let [read-identity (fn [asset-uuid]
                        (p/let [data (<comparison-pdf-bytes! repo asset-uuid signal)
                                checksum (db-asset/<get-file-array-buffer-checksum data)]
                          {:checksum checksum :size (.-byteLength data)}))]
    (p/let [a (read-identity keep-uuid)
            b (read-identity duplicate-uuid)]
      (if (and (current-graph? repo) (not (.-aborted signal))
               (= {keep-uuid a duplicate-uuid b} (:assets comparison)))
        (db-async/<resolve-duplicate-pdf repo keep-uuid duplicate-uuid comparison)
        {:status :blocked :reason :comparison-changed}))))

(defn <register-review-candidate!
  [repo asset-uuid title]
  (if-not (current-graph? repo)
    (p/resolved {:status :blocked :reason :inactive-graph})
    (if-let [review (review-candidate repo asset-uuid)]
      (<register-pdf! repo asset-uuid title (review-identifiers review))
      (p/resolved {:status :blocked :reason :missing-review-candidate}))))

(defn <retry-review-candidate!
  ([repo asset-uuid] (<retry-review-candidate! repo asset-uuid {}))
  ([repo asset-uuid {:keys [register? allow-online?]}]
   (if-not (current-graph? repo)
     (p/resolved {:status :blocked :reason :inactive-graph})
     (if-let [{:keys [repo-dir asset-dir-rpath] :as review}
              (review-candidate repo asset-uuid)]
       (let [entry (select-keys review [:asset-uuid :asset-title :file-path :checksum :byte-size])
             context (db-subs/projection-context)
             applied (atom nil)]
         (state/set-state! [:literature/review-candidates repo (str asset-uuid)]
                           (assoc review :review-status :retrying))
         (p/let [result (schedule-new-pdf-identification!
                          repo repo-dir asset-dir-rpath [entry]
                          :on-result
                          (fn [result]
                            (if register?
                              (p/let [value (<apply-review-result!
                                            repo repo-dir asset-dir-rpath result
                                            {:explicit? true :allow-online? allow-online? :context context
                                             :signal (some-> @*active-task :controller .-signal)})]
                                (reset! applied value))
                              (store-review-result! repo repo-dir asset-dir-rpath result))))]
           (when (= :busy (:status result))
             (state/set-state! [:literature/review-candidates repo (str asset-uuid)]
                               (assoc review :review-status :busy)))
           (cond-> result register? (assoc :automatic-result @applied))))
       (p/resolved {:status :blocked :reason :missing-review-candidate})))))

(defn- <notify-result
  [on-result result]
  (if-not on-result
    (p/resolved nil)
    (try
      (-> (p/resolved (on-result result))
          (p/catch
           (fn [error]
             (log/error :literature-identification-callback-failed
                        {:asset-uuid (:asset-uuid result) :error error})
             nil)))
      (catch :default error
        (log/error :literature-identification-callback-failed
                   {:asset-uuid (:asset-uuid result) :error error})
        (p/resolved nil)))))

(defn ^:no-doc <identify-saved-pdf!
  "Owns one file's byte buffer. No recursive batch continuation retains it."
  [repo-dir asset-dir-rpath {:keys [file-path byte-size checksum] :as entry} signal]
  (let [result! (fn [extraction]
                  (compact-review-result
                   (assoc entry :extraction extraction
                          :candidates (extraction->candidates extraction))))]
    (-> (p/let [local-stat (when-not (number? byte-size)
                            (fs/stat repo-dir (path/path-join asset-dir-rpath file-path)))
                local-size (or byte-size (:size local-stat))]
          (if (and (number? local-size) (> local-size max-auto-identification-bytes))
            (result! {:status :skipped :reason :file-size-limit})
            (p/let [pdf-bytes (if (util/electron?)
                               (p/let [response (js/fetch
                                                 (assets-handler/normalize-asset-resource-url
                                                  (path/path-join repo-dir asset-dir-rpath file-path))
                                                 #js {:signal signal})]
                                 (when-not (.-ok response)
                                   (throw (ex-info "PDF is not available locally"
                                                   {:type :literature/file-not-local})))
                                 (.arrayBuffer response))
                               (fs/read-file-raw repo-dir
                                                 (path/path-join asset-dir-rpath file-path)))
                    file-checksum (when checksum
                                    (db-asset/<get-file-array-buffer-checksum pdf-bytes))
                    extraction (if (and checksum (not= checksum file-checksum))
                                 {:status :failed :reason :content-mismatch}
                                 (<extract-first-page pdf-bytes signal))]
              (result! extraction))))
        (p/catch
         (fn [error]
           (result! {:status :failed
                     :reason (cond
                               (.-aborted signal) :cancelled
                               (= :literature/file-not-local (:type (ex-data error))) :file-not-local
                               :else :read-error)}))))))

(defn- <identify-sequentially
  [repo-dir asset-dir-rpath entries signal on-result]
  (letfn [(step [remaining results]
            (cond
              (.-aborted signal) (p/resolved {:status :cancelled :results results})
              (empty? remaining) (p/resolved {:status :complete :results results})
              :else
              (p/let [result (<identify-saved-pdf! repo-dir asset-dir-rpath (first remaining) signal)
                      _ (<notify-result on-result result)]
                (step (next remaining) (conj results result)))))]
    (step entries [])))

(defn schedule-new-pdf-identification!
  "A running history task accepts newly saved PDFs before its next history item."
  [repo repo-dir asset-dir-rpath entries & {:keys [on-result]}]
  (let [active @*active-task]
    (cond
      (empty? entries) (p/resolved {:status :empty})
      (and (= :history (:kind active)) (= repo (:repo active))
           (not (.-aborted ^js (.-signal ^js (:controller active)))))
      (do (swap! (:incoming active) conj
                 {:repo-dir repo-dir :asset-dir-rpath asset-dir-rpath
                  :entries entries :on-result on-result})
          (p/resolved {:status :queued}))
      active (p/resolved {:status :busy})
      :else
      (let [controller (js/AbortController.)
            token (random-uuid)
            task {:token token :repo repo :controller controller}
            monitor-id (js/setInterval #(when-not (current-graph? repo) (.abort controller)) 50)]
        (reset! *active-task task)
        (-> (<identify-sequentially repo-dir asset-dir-rpath entries (.-signal controller) on-result)
            (p/finally
             (fn []
               (js/clearInterval monitor-id)
               (swap! *active-task #(when-not (= token (:token %)) %)))))))))

(defn pause-organization!
  [repo]
  (when (and (= :history (:kind @*active-task)) (= repo (:repo @*active-task)))
    (let [task @*active-task]
      (cancel-active-identification!)
      (:finished task))))

(defn <organize-existing-pdfs!
  "Explicit, device-local organization. Reopening starts from remaining Assets;
   EID cursors never survive the current renderer projection."
  [repo allow-online?]
  (cond
    (not (current-graph? repo)) (p/resolved {:status :unavailable})
    @*active-task (p/resolved {:status :busy})
    :else
    (let [context (db-subs/projection-context)
          previous (state/get-state [:literature/organization repo])
          resume? (and (= :paused (:status previous)) (= context (:context previous)))
          *progress (atom (merge {:checked 0 :created 0 :linked 0 :pending 0 :unavailable 0}
                                 (when resume? previous)
                                 {:status :running :reason nil :context context :allow-online? allow-online?}))
          controller (js/AbortController.)
          signal (.-signal controller)
          incoming (atom [])
          token (random-uuid)
          finished (p/deferred)
          repo-dir (config/get-repo-dir repo)
          task {:kind :history :repo repo :context context :token token
                :controller controller :incoming incoming :finished finished}
          current? #(and (current-graph? repo) (= context (db-subs/projection-context))
                         (not (.-aborted signal)))
          publish! (fn [updates]
                     (swap! *progress merge updates)
                     (state/set-state! [:literature/organization repo] @*progress))
          monitor-id (js/setInterval #(when-not (current?) (.abort controller)) 50)]
      (reset! *active-task task)
      (publish! {})
      (letfn [(pause! [reason]
                (publish! {:status :paused :reason reason})
                @*progress)
              (<process! [asset]
                (p/let [prepared (<prepare-review! repo (:block/uuid asset))]
                  (cond
                    (not (current?)) {:status :cancelled}
                    (= :linked (:status prepared)) {:status :already-linked}
                    (not= :ready (:status prepared)) prepared
                    :else
                    (let [entry {:asset-uuid (:block/uuid asset)
                                 :asset-title (:block/title asset)
                                 :file-path (str (:block/uuid asset) ".pdf")
                                 :byte-size (get-in prepared [:asset :logseq.property.asset/size])
                                 :checksum (get-in prepared [:asset :logseq.property.asset/checksum])}]
                      (p/let [extracted (<identify-saved-pdf! repo-dir "assets" entry signal)]
                        (<apply-review-result! repo repo-dir "assets" extracted
                                               {:explicit? true :allow-online? allow-online?
                                                :context context :signal signal}))))))
              (<step! [assets after]
                (cond
                  (not (current?)) (p/resolved (pause! :cancelled))
                  (seq @incoming)
                  (let [request (first @incoming)]
                    (swap! incoming #(vec (rest %)))
                    (p/let [_ (<identify-sequentially (:repo-dir request) (:asset-dir-rpath request)
                                                     (:entries request) signal (:on-result request))]
                      (<step! assets after)))
                  (seq assets)
                  (let [asset (first assets)]
                    (publish! {:current-title (:block/title asset)})
                    (p/let [result (<process! asset)]
                      (if (or (not (current?))
                              (= :deferred (:status result))
                              (contains? #{:metadata-unavailable :metadata-request-cancelled}
                                         (:reason result)))
                        (pause! (or (:reason result) :cancelled))
                        (let [kind (case (:status result)
                                     :created :created
                                     (:linked :already-linked) :linked
                                     (if (= :file-not-local (:reason result)) :unavailable :pending))]
                          (swap! *progress update :checked inc)
                          (swap! *progress update kind inc)
                          (publish! {:cursor {:after (:db/id asset) :projection-context context}})
                          ;; Retain status, not a batch of extracted page text.
                          (when-let [review (review-candidate repo (:block/uuid asset))]
                            (state/set-state! [:literature/review-candidates repo (str (:block/uuid asset))]
                                              (dissoc review :candidates)))
                          (p/let [_ (p/delay 0)] (<step! (next assets) after))))))
                  after (<page! after)
                  :else (do (publish! {:status :complete :current-title nil})
                            (p/resolved @*progress))))
              (<page! [cursor]
                (p/let [page (<unregistered-pdfs! repo cursor)]
                  (if (and (current?) (= :ok (:status page)))
                    (<step! (:assets page) (:next-cursor page))
                    (p/resolved (pause! :inactive-graph)))))]
        (-> (<page! (when resume? (:cursor previous)))
            (p/catch (fn [error]
                       (publish! {:status :failed :reason :organization-error})
                       (throw error)))
            (p/finally
             (fn []
               (js/clearInterval monitor-id)
               (swap! *active-task #(when-not (= token (:token %)) %))
               (p/resolve! finished nil))))))))
