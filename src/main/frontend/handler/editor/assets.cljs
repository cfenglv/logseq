(ns ^:no-doc frontend.handler.editor.assets
  (:require [clojure.string :as string]
            [frontend.commands :as commands]
            [frontend.context.i18n :refer [t]]
            [frontend.db.async :as db-async]
            [frontend.db.subs :as db-subs]
            [frontend.fs :as fs]
            [frontend.fs.protocol :as fs-protocol]
            [frontend.handler.assets :as assets-handler]
            [frontend.handler.editor :as editor]
            [frontend.handler.literature :as literature-handler]
            [frontend.handler.notification :as notification]
            [frontend.modules.outliner.op :as outliner-op]
            [frontend.modules.outliner.ui :as ui-outliner-tx]
            [frontend.state :as state]
            [frontend.util.ref :as ref]
            [lambdaisland.glogi :as log]
            [logseq.common.path :as path]
            [logseq.db :as ldb]
            [logseq.db.frontend.asset :as db-asset]
            [logseq.shui.ui :as shui]
            [medley.core :as medley]
            [promesa.core :as p]
            [reitit.frontend.easy :as rfe]))

(defn- notify-literature-result!
  [repo {:keys [status paper-uuid]}]
  (when (= repo (state/get-current-repo))
    (let [success? (contains? #{:created :linked :already-linked} status)
          destination (if (and success? paper-uuid)
                        (rfe/href :page {:name paper-uuid})
                        (rfe/href :literature nil {:tab "pending"}))]
      (notification/show!
       [:span
        (t (case status
             :created :literature.library/recognized-created
             (:linked :already-linked) :literature.library/recognized-linked
             :literature.library/recognized-pending))
        " "
        [:a.underline {:href destination
                       :on-click (fn [^js event]
                                   (when-not (= repo (state/get-current-repo))
                                     (.preventDefault event)))}
         (if (and success? paper-uuid)
           (t :literature.library/open-entry-action)
           (t :literature.library/pending-tab))]]
       (if success? :success :info)))))

(defn delete-asset-of-block!
  [{:keys [repo asset-block full-text block-id local? delete-local?] :as _opts}]
  (p/let [block (db-async/<get-block repo block-id {:children? false})
          _ (or block (throw (ex-info (str block-id " not exists")
                                      {:block-id block-id})))
          text (:block/title block)
          content (if asset-block
                    (string/replace text (ref/->page-ref (:block/uuid asset-block)) "")
                    (string/replace text full-text ""))]
    (editor/save-block! repo block content)
    (when (and local? delete-local?)
      (when asset-block
        (editor/delete-block-aux! asset-block)))))

(defn- validate-asset-candidate!
  [{:keys [file file-name ext] :as candidate}]
  (when (string/blank? ext)
    (throw (ex-info "File doesn't have a valid ext."
                    {:file-name file-name})))
  (when (some-> file (assets-handler/exceed-limit-size?))
    (notification/show! [:div (t :asset/size-too-large)]
                        :warning
                        false)
    (throw (ex-info "Asset size shouldn't be larger than 100M" {:file-name file-name})))
  candidate)

(defn- pdf-reuse-checksums
  [candidates include-new-pdfs?]
  (let [pdf-checksums (->> candidates
                           (keep (fn [{:keys [ext checksum]}]
                                   (when (and (= "pdf" ext) (seq checksum)) checksum))))
        counts (frequencies pdf-checksums)]
    (->> candidates
         (keep (fn [{:keys [ext checksum existing-asset]}]
                 (when (and (= "pdf" ext)
                            (seq checksum)
                            (or include-new-pdfs?
                                existing-asset
                                (> (get counts checksum 0) 1)))
                   checksum)))
         distinct
         vec)))

(defn- <pdf-reuse-results
  [repo candidates reuse-existing-pdf? include-new-pdfs?]
  (if-not reuse-existing-pdf?
    (p/resolved {})
    (let [checksums (pdf-reuse-checksums candidates include-new-pdfs?)]
      (p/let [results (p/all (mapv #(db-async/<find-reusable-pdf-by-checksum repo %)
                                    checksums))]
        (zipmap checksums results)))))

(defn- linked-asset-block
  [candidate asset-uuid]
  {:block/uuid (get-in candidate [:asset-block :block/uuid])
   :block/title ""
   :block/link [:block/uuid asset-uuid]})

(defn- remember-pdf-filename
  [plan {:keys [file file-name]} asset-uuid]
  (if (and file (not (string/blank? file-name)))
    (update-in plan [:pdf-filenames asset-uuid] (fnil conj #{}) file-name)
    plan))

(defn- build-asset-insert-plan
  [candidates reuse-results & [initial-plan]]
  (when-let [[checksum result]
             (some (fn [[checksum result]]
                     (when (= :conflict (:status result)) [checksum result]))
                   reuse-results)]
    (throw (ex-info "Multiple PDF Assets share the same checksum"
                    {:type :literature/duplicate-pdf-conflict
                     :checksum checksum
                     :assets (:assets result)
                     :asset-uuids (mapv :block/uuid (:assets result))})))
  (reduce
   (fn [{:keys [new-pdf-assets repaired-assets] :as plan}
        {:keys [file ext checksum existing-asset asset-block] :as candidate}]
     (let [{:keys [status assets literature-status]} (when (= "pdf" ext)
                                     (get reuse-results checksum))]
       (cond
         (= :found status)
         (let [candidate (validate-asset-candidate! candidate)
               asset-uuid (:block/uuid (first assets))
               repair? (and file (not (contains? repaired-assets asset-uuid)))
               mime-type (some-> file .-type string/lower-case)]
           (cond-> (-> plan
                       (update :blocks conj (linked-asset-block candidate asset-uuid))
                       (remember-pdf-filename candidate asset-uuid))
             repair?
             (-> (update :repair-writes conj {:file file
                                              :file-path (str asset-uuid ".pdf")
                                              :checksum checksum})
                 (update :repaired-assets conj asset-uuid))
             (and repair?
                  (= :missing literature-status)
                  (or (string/blank? mime-type) (= "application/pdf" mime-type)))
             (update :new-pdf-identifications conj
                     {:asset-uuid asset-uuid
                      :file-path (str asset-uuid ".pdf")
                      :checksum checksum
                      :byte-size (.-size file)})))

         (= :missing status)
         (let [candidate (validate-asset-candidate! candidate)]
           (if-let [asset-uuid (get new-pdf-assets checksum)]
             (-> plan
                 (update :blocks conj (linked-asset-block candidate asset-uuid))
                 (remember-pdf-filename candidate asset-uuid))
             (let [plan (-> plan
                            (update :blocks conj asset-block)
                            (remember-pdf-filename candidate (:block/uuid asset-block))
                            (assoc-in [:new-pdf-assets checksum] (:block/uuid asset-block)))]
               (if file
                 (let [file-path (str (:block/uuid asset-block) "." ext)
                       mime-type (some-> file .-type string/lower-case)]
                   (cond-> (update plan :new-writes conj {:file file
                                                          :file-path file-path})
                     ;; Keep the native attachment path, but do not identify a
                     ;; PDF when the incoming File explicitly claims another type.
                     (or (string/blank? mime-type)
                         (= "application/pdf" mime-type))
                     (update :new-pdf-identifications conj
                             {:asset-uuid (:block/uuid asset-block)
                              :file-path file-path
                              :checksum checksum
                              :byte-size (.-size file)})))
                 plan))))

         existing-asset
         (update plan :existing-assets conj existing-asset)

         :else
         (let [candidate (validate-asset-candidate! candidate)]
           (cond-> (update plan :blocks conj asset-block)
             (:file candidate)
             (update :new-writes conj {:file (:file candidate)
                                       :file-path (str (:block/uuid asset-block) "." ext)}))))))
   (or initial-plan {:blocks []
    :new-writes []
    :repair-writes []
    :new-pdf-identifications []
    :existing-assets []
    :new-pdf-assets {}
    :pdf-filenames {}
    :repaired-assets #{}})
   candidates))

(defonce ^:private *pdf-save-tail (atom nil))

(defn- <serialize-pdf-save!
  [save!]
  (let [previous (if-let [active @*pdf-save-tail]
                   ;; A failed operation still rejects its caller; the next
                   ;; independent paste can proceed through the normal path.
                   (p/catch active (fn [_] nil))
                   (p/resolved nil))
        result (p/then previous (fn [_] (save!)))]
    (reset! *pdf-save-tail result)
    (p/finally result #(compare-and-set! *pdf-save-tail result nil))))

(defn- native-pdf-file
  [input]
  (let [file (if (map? input) (:src input) input)]
    (when (and (not (string? file))
               (= "pdf" (some-> file .-name db-asset/asset-path->type)))
      file)))

(defn- <apply-pdf-choice!
  [repo checksum result choices]
  (if-let [chosen-uuid (get choices checksum)]
    (if-let [asset (some #(when (= chosen-uuid (:block/uuid %)) %) (:assets result))]
      (p/let [owners (db-async/<q
                     repo {}
                     '[:find ?paper
                       :in $ ?asset-uuid
                       :where
                       [?asset :block/uuid ?asset-uuid]
                       [?paper :logseq.property/literature-assets ?asset]
                       [?paper :logseq.property/literature-item? true]
                       (not [?paper :logseq.property/deleted-at])]
                     chosen-uuid)]
        (assoc result :status :found :assets [asset]
                      :literature-status (case (count owners) 0 :missing 1 :found :conflict)))
      (p/rejected (ex-info "Selected PDF is no longer available"
                           {:type :literature/pdf-choice-changed})))
    (p/resolved result)))

(defn- <prepare-and-write-pdfs!
  [repo files candidate-opts current! choices]
  (let [created-paths (atom [])]
    (->
     (p/let [_ (current!)
             _ (doseq [input files
                       :let [file (native-pdf-file input)]
                       :when file]
                 (validate-asset-candidate! {:file file :file-name (.-name file) :ext "pdf"}))
             [repo-dir asset-dir] (assets-handler/ensure-assets-dir! repo)
             ordinary (p/all
                       (keep (fn [[idx file]]
                               (when-not (native-pdf-file file)
                                 (p/let [candidate (assets-handler/prepare-asset-block
                                                   repo file (candidate-opts idx))]
                                   [idx candidate])))
                             (medley/indexed files)))
             ordinary (into {} ordinary)]
       (letfn [(prepare-one [[idx input] plan reuse-results]
                   (p/let [_ (current!)
                           ^js pdf-file (native-pdf-file input)
                           content (when pdf-file (.arrayBuffer pdf-file))
                           content-checksum (when content (db-asset/<get-file-array-buffer-checksum content))
                           candidate (if pdf-file
                                       (assets-handler/prepare-asset-block
                                        repo input (assoc (candidate-opts idx) :checksum content-checksum))
                                       (get ordinary idx))
                           checksum (:checksum candidate)
                           pdf? (= "pdf" (:ext candidate))
                           reuse-results (if (and pdf? (not (contains? reuse-results checksum)))
                                           (p/let [result (db-async/<find-reusable-pdf-by-checksum repo checksum)
                                                   result (<apply-pdf-choice! repo checksum result choices)]
                                             (assoc reuse-results checksum result))
                                           reuse-results)
                           next-plan (build-asset-insert-plan [candidate] reuse-results plan)
                           _ (when pdf-file
                               (p/do!
                                (current!)
                                (p/all
                                 (map (fn [{:keys [file file-path]}]
                                        (let [full-path (path/path-join repo-dir asset-dir file-path)]
                                          (p/let [exists? (fs/file-exists? repo-dir (path/path-join asset-dir file-path))
                                                  _ (assets-handler/db-based-write-asset! repo file-path file content)]
                                            (when-not exists? (swap! created-paths conj full-path)))))
                                      (drop (count (:new-writes plan)) (:new-writes next-plan))))
                                (p/all
                                 (map #(assets-handler/<restore-reused-asset-file!
                                        repo repo-dir asset-dir % content)
                                      (drop (count (:repair-writes plan)) (:repair-writes next-plan))))))]
                     ;; The plan retains identities and native blocks, never
                     ;; the acquired PDF contents while the next file waits.
                     [(if pdf-file
                        (assoc next-plan :new-writes (:new-writes plan)
                                         :repair-writes (:repair-writes plan))
                        next-plan)
                      reuse-results]))
               (step [remaining plan reuse-results]
                 (if (seq remaining)
                   (-> (prepare-one (first remaining) plan reuse-results)
                       (p/then (fn [[next-plan next-results]]
                                 (step (next remaining) next-plan next-results))))
                   (p/resolved plan)))]
         (step (seq (medley/indexed files)) (build-asset-insert-plan [] {}) {})))
     (p/catch
      (fn [error]
        ;; Planning has not inserted any Asset yet. Recycle only files that
        ;; this preparation created; never remove an existing or repaired PDF.
        (p/let [_ (p/all (map #(fs/unlink! repo % {}) @created-paths))]
          (p/rejected error)))))))

(declare <db-based-save-assets!)

(defn db-based-save-assets!
  "Save incoming(pasted) assets to assets directory.

   Returns: inserted native Asset or linked-block entities."
  [repo files & {:as opts}]
  (<db-based-save-assets! repo files opts))

(defn- <resolve-duplicate-pdfs!
  [repo keep-uuid duplicate-uuids current?]
  (letfn [(step [remaining results]
            (cond
              (not (current?)) (p/resolved (conj results {:status :blocked}))
              (seq remaining)
              (-> (db-async/<resolve-duplicate-pdf
                   repo keep-uuid (first remaining))
                  (p/then (fn [result]
                            (if (= :resolved (:status result))
                              (step (next remaining) (conj results result))
                              (conj results result)))))
              :else (p/resolved results)))]
    (step duplicate-uuids [])))

(defn- duplicate-pdf-choice
  [asset on-click action-label]
  (let [asset-uuid (:block/uuid asset)
        title (not-empty (string/trim (:block/title asset)))
        papers (keep :block/title (:papers asset))]
    [:div {:key (str asset-uuid)}
     (shui/button
      {:variant :outline :class "h-auto w-full justify-between gap-3 px-3 py-2"
       :on-click on-click}
      [:span.min-w-0.text-left
       [:span.block.truncate (or title (t :asset.duplicate-pdf/untitled-copy))]
       (when (seq papers) [:span.block.truncate.text-xs.opacity-60 (string/join " · " papers)])]
      [:span.shrink-0 action-label])
     [:details.text-xs.opacity-60.mt-1
      [:summary.cursor-pointer (t :literature.library/details-action)]
      [:code.break-all (str asset-uuid)]]]))

(defn resolve-duplicate-pdf-choice!
  [repo assets keep-uuid on-resolved current? graph-current?]
  (let [duplicate-uuids (->> assets
                             (map :block/uuid)
                             (remove #(= keep-uuid %)))]
    (-> (<resolve-duplicate-pdfs! repo keep-uuid duplicate-uuids current?)
        (p/then
         (fn [results]
           (when (current?)
             (if (every? #(= :resolved (:status %)) results)
               (do
                 (shui/dialog-close! :literature-duplicate-pdf)
                 (notification/show!
                  (t :asset.duplicate-pdf/resolved)
                  :success)
                 (-> (p/resolved nil)
                     (p/then (fn [_]
                               (when (graph-current?) (on-resolved))))
                     (p/catch
                      (fn [error]
                        (log/error :resolve-duplicate-pdf-failed error)
                        (when (graph-current?)
                          (notification/show!
                           (t :asset.duplicate-pdf/resolve-error) :error))))))
               (do
                 (shui/dialog-close! :literature-duplicate-pdf)
                 (notification/show!
                  (if (some #(= :duplicate-in-use (:reason %)) results)
                    (t :asset.duplicate-pdf/in-use-warning)
                    (t :asset.duplicate-pdf/changed-warning))
                  :warning
                  false))))))
        (p/catch
         (fn [error]
           (log/error :resolve-duplicate-pdf-failed error)
           (when (current?)
             (notification/show!
              (t :asset.duplicate-pdf/resolve-error)
              :error)))))))

(defn show-duplicate-pdf-conflict!
  ([repo assets on-resolved]
   (let [assets (vec assets)
         context (db-subs/projection-context)]
     (shui/dialog-open!
      (fn [{dialog-close :close}]
        (let [graph-current? #(and (= repo (state/get-current-repo))
                                    (not (state/get-state :db/restoring?))
                                    (= context (db-subs/projection-context)))
              current? (fn []
                         (let [dialog (second (shui/dialog-get :literature-duplicate-pdf))]
                           (and (graph-current?)
                                (:open? dialog)
                                (identical? dialog-close (:close dialog)))))]
          [:div.flex.flex-col.gap-3
           [:p.text-sm.opacity-80 (t :asset.duplicate-pdf/conflict-description)]
           [:div.flex.flex-col.gap-2
            (for [asset assets]
              (duplicate-pdf-choice
               asset
               #(resolve-duplicate-pdf-choice!
                 repo assets (:block/uuid asset) on-resolved current? graph-current?)
               (t :asset.duplicate-pdf/keep-action)))]]))
      {:id :literature-duplicate-pdf
       :content-props {:finalFocus true}
       :title (t :asset.duplicate-pdf/conflict-title)
       :align :center})))
  ([repo files opts {:keys [assets checksum]}]
   (let [context (db-subs/projection-context)
         current? #(and (= repo (state/get-current-repo))
                        (= context (db-subs/projection-context))
                        (not (state/get-state :db/restoring?)))]
     (p/let [group (literature-handler/<history-conflict-group!
                    repo :checksum {:asset-uuid (:block/uuid (first assets)) :checksum checksum})]
       (when (current?)
         (if-not (= :ok (:status group))
           (notification/show! (t :asset.duplicate-pdf/changed-warning) :warning)
           (shui/dialog-open!
            (fn [_]
              [:div.flex.flex-col.gap-3
               [:p.text-sm.opacity-70 (t :asset.duplicate-pdf/reference-description)]
               (for [asset (:members group)]
                 (duplicate-pdf-choice
                  asset
                  (fn []
                    (when (current?)
                      (shui/dialog-close! :literature-pdf-reference-choice)
                      (-> (<db-based-save-assets!
                           repo files (assoc-in opts [:pdf-choices checksum] (:block/uuid asset)))
                          (p/catch (fn [error]
                                     (log/error :literature-pdf-choice-failed error)
                                     (when (current?)
                                       (notification/show! (t :asset.duplicate-pdf/changed-warning) :warning)))))))
                  (t :asset.duplicate-pdf/use-reference-action)))])
            {:id :literature-pdf-reference-choice :content-props {:finalFocus true}
             :title (t :asset.duplicate-pdf/reference-title)})))))))

(defn- asset-conflict-resume-opts
  [opts target-block edit-block has-unsaved-edit?]
  (cond-> opts
    (and (nil? target-block) edit-block)
    (assoc :last-edit-block edit-block)

    (and (nil? target-block) edit-block has-unsaved-edit?)
    (assoc :resume-unsaved-edit? true)))

(defn- <preserve-edited-pdf-target!
  [repo repo-dir asset-dir target-uuid plan]
  (p/let [target (db-async/<get-block repo target-uuid {:children? false})
          editing-target? (= target-uuid (:block/uuid (state/get-edit-block)))
          still-empty? (and target
                            (string/blank? (:block/title target))
                            (nil? (:block/link target))
                            (nil? (:logseq.property.asset/type target))
                            (or (not editing-target?)
                                (string/blank? (state/get-edit-content))))]
    (if (or still-empty? (not= target-uuid (:block/uuid (first (:blocks plan)))))
      plan
      (let [asset-uuid (ldb/new-block-id)
            ext (or (:logseq.property.asset/type (first (:blocks plan))) "pdf")
            original-path (str target-uuid "." ext)
            replacement-path (str asset-uuid "." ext)
            replace-id #(if (= % target-uuid) asset-uuid %)]
        (p/let [_ (when (= "pdf" (:logseq.property.asset/type (first (:blocks plan))))
                    (fs-protocol/rename!
                     (fs/get-fs repo-dir {:repo repo :rpath (path/path-join asset-dir replacement-path)})
                     repo
                     (path/path-join repo-dir asset-dir original-path)
                     (path/path-join repo-dir asset-dir replacement-path)))]
          (-> plan
              (update :blocks #(mapv (fn [block]
                                       (cond-> (update block :block/uuid replace-id)
                                         (= [:block/uuid target-uuid] (:block/link block))
                                         (assoc :block/link [:block/uuid asset-uuid]))) %))
              (update :new-pdf-identifications
                      #(mapv (fn [entry]
                               (if (= target-uuid (:asset-uuid entry))
                                 (assoc entry :asset-uuid asset-uuid :file-path replacement-path)
                                 entry)) %))
              (update :new-writes #(mapv (fn [entry]
                                          (if (= original-path (:file-path entry))
                                            (assoc entry :file-path replacement-path)
                                            entry)) %))
              (update :pdf-filenames #(into {} (map (fn [[id names]] [(replace-id id) names])) %))))))))

(defn- <db-based-save-assets!
  [repo files {:keys [pdf-area? last-edit-block resume-unsaved-edit?
                      reuse-existing-pdf? save-to-page target-block
                      auto-identify-literature? on-literature-identification
                      literature-action? on-assets-saved pdf-choices]
               :as opts}]
  (let [edit-context [(state/get-current-repo)
                      (state/get-route-match)
                      (state/get-state :editor/editing?)]
        state-edit-block (when-not target-block
                           (state/get-edit-block))
        edit-content (when state-edit-block
                       (state/get-edit-content))
        edit-block (when-not target-block
                     (if state-edit-block
                       (assoc state-edit-block :block/title edit-content)
                       last-edit-block))
        has-unsaved-edit? (or (and state-edit-block
                                    (not= (:block/title state-edit-block) edit-content))
                               (and resume-unsaved-edit? last-edit-block))
        unsaved-edit-block (or state-edit-block last-edit-block)
        unsaved-edit-content (if state-edit-block
                               edit-content
                               (:block/title last-edit-block))
        empty-target? (cond
                        target-block false
                        state-edit-block (string/blank? edit-content)
                        last-edit-block (string/blank? (:block/title last-edit-block))
                        :else false)
        literature-enabled? (:enabled? (literature-handler/load-settings repo))
        reuse-pdf? (and reuse-existing-pdf? (or literature-enabled? literature-action?))
        identify-pdf? (and auto-identify-literature? literature-enabled?)
        bounded-pdf? (and reuse-pdf? (some native-pdf-file files))
        context (when bounded-pdf? (db-subs/projection-context))
        current! (fn []
                   (when-not (and (= repo (state/get-current-repo))
                                  (= context (db-subs/projection-context))
                                  (not (state/get-state :db/restoring?)))
                     (throw (ex-info "PDF save context changed" {:type :canceled}))))
        candidate-opts (fn [idx]
                         {:block/uuid (when (and (zero? idx) empty-target?)
                                        (:block/uuid edit-block))})
        ;; Save captured text before the queued file work can outlive it.
        initial-edit-save (when (and bounded-pdf? has-unsaved-edit?)
                            (editor/save-block-aux! unsaved-edit-block unsaved-edit-content nil))
        save! (fn []
                (->
                 (p/let [_ (when bounded-pdf? (current!))
                         _ initial-edit-save
                         live-target (when (and bounded-pdf? empty-target?)
                                       (db-async/<get-block repo (:block/uuid edit-block) {:children? false}))
                         prepare-opts (if (and bounded-pdf? empty-target?
                                               (not (and live-target
                                                         (string/blank? (:block/title live-target))
                                                         (nil? (:block/link live-target))
                                                         (nil? (:logseq.property.asset/type live-target)))))
                                        (constantly {})
                                        candidate-opts)
                         plan
             (if bounded-pdf?
               (<prepare-and-write-pdfs! repo files prepare-opts current! pdf-choices)
               (p/let [candidates (p/all
                                  (for [[idx file] (medley/indexed files)]
                                    (assets-handler/prepare-asset-block repo file (candidate-opts idx))))
                       reuse-results (<pdf-reuse-results repo candidates reuse-pdf?
                                                        identify-pdf?)]
                 (build-asset-insert-plan candidates reuse-results)))
             [repo-dir asset-dir-rpath] (assets-handler/ensure-assets-dir! repo)
             asset-page (db-async/<invoke-db-worker :thread-api/pull repo
                                                   [:block/uuid] :logseq.class/Asset)
             _saved (when (and has-unsaved-edit? (not bounded-pdf?))
                      (editor/save-block-aux! unsaved-edit-block unsaved-edit-content nil))
             ;; An empty paste target can become a note while PDF IO runs.
             ;; Keep that note and give the prepared Asset its own native UUID.
             _latest-edit (when (and bounded-pdf?
                                     (= (:block/uuid edit-block) (:block/uuid (state/get-edit-block))))
                            (editor/save-block-aux! (state/get-edit-block) (state/get-edit-content) nil))
             {:keys [blocks new-writes repair-writes existing-assets
                     new-pdf-identifications pdf-filenames]}
             (if (and bounded-pdf? empty-target?)
               (<preserve-edited-pdf-target! repo repo-dir asset-dir-rpath (:block/uuid edit-block) plan)
               plan)
             _notified (doseq [existing-asset existing-assets]
                         (notification/show! (t :asset/already-exists
                                                (:block/title existing-asset)
                                                (:block/uuid existing-asset))
                                             :warning
                                             false))
             _written (p/all
                       (concat
                        (map (fn [{:keys [file file-path]}]
                               (assets-handler/db-based-write-asset! repo file-path file))
                             new-writes)
                        (map #(assets-handler/<restore-reused-asset-file! repo repo-dir asset-dir-rpath %)
                             repair-writes)))
             insert-to-current-block-page? (boolean (and (not target-block) (:block/uuid edit-block) (not pdf-area?)))
             target (cond
                      target-block
                      target-block

                      insert-to-current-block-page?
                      edit-block

                      save-to-page
                      save-to-page

                      :else
                      asset-page)]
       (when-not target
         (throw (ex-info "invalid target" {:files files
                                           :asset-page asset-page
                                           :edit-block edit-block})))
       (when bounded-pdf? (current!))
       (when (seq blocks)
         (p/do!
          (ui-outliner-tx/transact!
           (cond-> {:outliner-op :insert-blocks}
             (and empty-target? insert-to-current-block-page?)
             (assoc :editor/edit-block-fn
                    (fn [rows]
                      (when (and (= edit-context [(state/get-current-repo)
                                                 (state/get-route-match)
                                                 (state/get-state :editor/editing?)])
                                 (= edit-content (state/get-edit-content)))
                        (when-let [asset (some #(when (= (:block/uuid edit-block) (:block/uuid %)) %) rows)]
                          (editor/edit-block! asset :max {:skip-load? true
                                                         :save-current-block? false}))))))
           (outliner-op/insert-blocks! blocks target {:keep-uuid? true
                                                      :bottom? true
                                                      :sibling? (boolean (and edit-block (= edit-block target)))
                                                      :replace-empty-target? insert-to-current-block-page?}))
          (when (seq pdf-filenames)
            (db-async/<add-literature-pdf-filenames repo pdf-filenames))
          (p/let [results (db-async/<get-blocks repo (map :block/uuid blocks) {:children? false})
                  blocks (editor/unwrap-block-results results)]
            (when (= edit-context [(state/get-current-repo)
                                  (state/get-route-match)
                                  (state/get-state :editor/editing?)])
              (when-let [block (when (= edit-content (state/get-edit-content))
                                (some (fn [block] (when (= (:block/uuid block) (:block/uuid edit-block)) block)) blocks))]
                (editor/edit-block! block :max {:skip-load? true})))
            (when (and identify-pdf?
                       (seq new-pdf-identifications))
              ;; This background task reads the saved path and never retains the
              ;; incoming File while it waits. The default callback may ask the
              ;; DB worker to link only a fully confirmed existing bibliography item.
              (let [remaining (atom (count new-pdf-identifications))
                    counts (atom {})
                    store-result!
                    (or on-literature-identification
                        (fn [extracted]
                          (p/let [result (literature-handler/<store-and-maybe-auto-link!
                                         repo repo-dir asset-dir-rpath extracted)]
                            (if (= 1 (count new-pdf-identifications))
                              (notify-literature-result! repo result)
                              (let [kind (case (:status result)
                                           :created :created
                                           (:linked :already-linked) :linked
                                           :pending)]
                                (swap! counts update kind (fnil inc 0))
                                (when (and (zero? (swap! remaining dec)) (= repo (state/get-current-repo)))
                                  (notification/show!
                                   (t :literature.library/batch-results
                                      (get @counts :created 0) (get @counts :linked 0) (get @counts :pending 0))
                                   :info))))
                            result)))]
                ;; The small pending record makes manual registration available
                ;; even if the bounded extractor is busy or cannot read the PDF.
                (when-not on-literature-identification
                  (doseq [entry new-pdf-identifications]
                    (literature-handler/store-review-result!
                     repo repo-dir asset-dir-rpath
                     (assoc entry
                            :extraction {:status :pending :reason :pending}
                            :candidates {:status :pending
                                         :reason :pending
                                         :usable? false
                                         :identifiers []}))))
                (literature-handler/schedule-new-pdf-identification!
                 repo
                 repo-dir
                 asset-dir-rpath
                 new-pdf-identifications
                 :on-result store-result!)))
            (when (and (not on-assets-saved) (some native-pdf-file files)
                       (not (and identify-pdf? (seq new-pdf-identifications)))
                       (= repo (state/get-current-repo)))
              (notification/show!
               (if (seq repair-writes)
                 (t :literature.library/reused-pdf)
                 (t :literature.library/saved-pdf))
               :success))
            (p/do! (when on-assets-saved (on-assets-saved blocks)) blocks)))))
     (p/catch
      (fn [error]
        (let [data (ex-data error)]
          (if (= :literature/duplicate-pdf-conflict (:type data))
            (show-duplicate-pdf-conflict!
             repo
             files
             (asset-conflict-resume-opts
              opts target-block edit-block has-unsaved-edit?)
             data)
            (p/rejected error)))))))]
    (if bounded-pdf?
      (<serialize-pdf-save! save!)
      (save!))))

(defn db-upload-assets!
  "Paste asset for db graph and insert link to current editing block"
  [repo id ^js files format uploading? drop-or-paste?]
  (editor/insert-command!
   id
   ""
   format
   {:last-pattern (if drop-or-paste? "" commands/command-trigger)
    :restore?     true
    :command      :insert-asset})
  (-> (db-based-save-assets! repo (js->clj files)
                             :reuse-existing-pdf? true
                             :auto-identify-literature?
                             (literature-handler/auto-identification-enabled? repo))
      (p/catch (fn [e]
                 (js/console.error e)))
      (p/finally
        (fn []
          (reset! uploading? false)
          (reset! editor/*asset-uploading? false)
          (reset! editor/*asset-uploading-process 0)))))

(defn upload-asset!
  "Paste asset and insert link to current editing block"
  [id ^js files format uploading? drop-or-paste?]
  (let [repo (state/get-current-repo)]
    (db-upload-assets! repo id ^js files format uploading? drop-or-paste?)))
