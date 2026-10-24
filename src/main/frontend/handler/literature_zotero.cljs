(ns ^:no-doc frontend.handler.literature-zotero
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [logseq.common.defkeywords :refer [defkeyword]]
            [promesa.core :as p]))

(defkeyword :next-offset "The next page in a user-requested Zotero metadata preview.")
(defkeyword :zotero-library-key "The Zotero library identity stored only in this device binding.")
(defkeyword :literature/show-zotero-companion "Reveal the fixed companion packaged with this desktop client.")

(def ^:private local-api-root "http://127.0.0.1:23119/api")
(def ^:private notifier-url
  "http://127.0.0.1:23119/logseq-literature/changes")
(def ^:private item-batch-size 50)
(def ^:private item-key-pattern #"^[A-Z0-9]{8}$")
(def ^:private numeric-library-id-pattern #"^[1-9][0-9]*$")
(def ^:private uuid-pattern
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(def ^:private item-types
  {"journalArticle" "journal-article"
   "preprint" "preprint"
   "conferencePaper" "paper-conference"
   "book" "book"
   "bookSection" "chapter"
   "report" "report"
   "thesis" "thesis"
   "webpage" "webpage"})

(def ^:private excluded-item-types
  #{"attachment" "note" "annotation"})

(defn- non-blank-string
  [value]
  (let [value (some-> value str string/trim (string/replace #"\s+" " "))]
    (when-not (string/blank? value) value)))

(defn- item-key
  [value]
  (let [value (some-> value non-blank-string string/upper-case)]
    (when (and value (re-matches item-key-pattern value)) value)))

(defn- numeric-library-id
  [value]
  (let [value (some-> value str)]
    (when (and value (re-matches numeric-library-id-pattern value)) value)))

(defn sanitize-source
  "Returns the authorized Zotero source and scope before connection, or nil.
   Credentials, profile paths and Local API origins are intentionally absent."
  [{:keys [graph-binding-uuid source-identity scope] :as _binding}]
  (let [{source-kind :kind source-id :id local-uuid :binding-uuid}
        source-identity
        source-identity'
        (case source-kind
          :user (when-let [id (numeric-library-id source-id)]
                  {:kind :user :id id})
          :group (when-let [id (numeric-library-id source-id)]
                   {:kind :group :id id})
          :local (let [value (some-> local-uuid str string/lower-case)]
                   (when (and value (re-matches uuid-pattern value))
                     {:kind :local :binding-uuid value}))
          nil)
        scope'
        (case (:kind scope)
          :library {:kind :library}
          :collection (when-let [key (item-key (:key scope))]
                        {:kind :collection :key key})
          nil)]
    (when (and (uuid? graph-binding-uuid) source-identity' scope')
      {:graph-binding-uuid graph-binding-uuid
       :source-identity source-identity'
       :scope scope'})))

(defn- library-key
  [value]
  (when (and (string? value) (re-matches #"[A-Za-z0-9]{8}" value))
    value))

(defn sanitize-binding
  "Returns a connected local binding with a public destination and local library identity."
  [{:keys [destination-page-uuid zotero-library-key] :as binding}]
  (when (and (uuid? destination-page-uuid) (library-key zotero-library-key))
    (when-let [source (sanitize-source binding)]
      (assoc source
             :destination-page-uuid destination-page-uuid
             :zotero-library-key zotero-library-key))))

(defn- source-prefix
  [{:keys [kind id binding-uuid]}]
  (case kind
    :user (str "zotero:user:" id)
    :group (str "zotero:group:" id)
    :local (str "zotero:local:" binding-uuid)))

(defn source-key-item-key
  "Returns the Zotero item key only when a shared source key exactly belongs
   to the supplied local binding."
  [binding source-key]
  (let [binding (sanitize-binding binding)
        source-key (non-blank-string source-key)
        prefix (when binding
                 (str (source-prefix (:source-identity binding)) ":item:"))
        suffix (when (and prefix source-key (string/starts-with? source-key prefix))
                 (subs source-key (count prefix)))
        key (item-key suffix)]
    (when (and key (= source-key (str prefix key)))
      key)))

(defn- api-library-path
  [{:keys [kind id]}]
  (case kind
    :group (str "groups/" id)
    ;; Zotero 9 exposes the current personal library through users/0. The
    ;; stable shared identity remains the explicit binding above, never 0.
    (:user :local) "users/0"))

(defn- scope-items-path
  [{:keys [source-identity scope]}]
  (str (api-library-path source-identity)
       (when (= :collection (:kind scope))
         (str "/collections/" (:key scope)))
       "/items/top"))

(defn- normalize-doi
  [value]
  (let [candidate (some-> value
                          non-blank-string
                          string/lower-case
                          (string/replace #"^doi:\s*" "")
                          (string/replace #"^https?://(?:dx\.)?doi\.org/" ""))]
    (when (and candidate (re-matches #"10\.\d{4,9}/\S+" candidate))
      candidate)))

(defn- normalize-arxiv
  [value]
  (let [candidate (some-> value
                          non-blank-string
                          string/lower-case
                          (string/replace #"^arxiv:\s*" "")
                          (string/replace #"^https?://(?:www\.)?arxiv\.org/(?:abs|pdf)/" "")
                          (string/replace #"[?#].*$" "")
                          (string/replace #"\.pdf$" "")
                          (string/replace #"v\d+$" ""))]
    (when (and candidate
               (or (re-matches #"\d{4}\.\d{4,5}" candidate)
                   (re-matches #"[a-z][a-z0-9.-]*/\d{7}" candidate)))
      candidate)))

(defn- arxiv-from-data
  [data]
  (let [extra (non-blank-string (:extra data))
        extra-value (when extra
                      (some-> (re-find #"(?i)(?:^|\n)\s*arxiv\s*:\s*([^\s]+)" extra)
                              second))
        candidates (cond-> [(:url data)]
                     (= "arxiv" (some-> (:archive data)
                                         non-blank-string
                                         string/lower-case))
                     (conj (:archiveLocation data))
                     extra-value (conj extra-value))]
    (some normalize-arxiv candidates)))

(defn- creator-name
  [creator]
  (or (non-blank-string (:name creator))
      (let [first-name (non-blank-string (:firstName creator))
            last-name (non-blank-string (:lastName creator))]
        (cond
          (and first-name last-name) (str last-name ", " first-name)
          last-name last-name
          :else first-name))))

(defn- authors
  [creators]
  (let [values (->> creators
                    (filter #(contains? #{"author" "bookAuthor"}
                                        (:creatorType %)))
                    (keep creator-name))]
    (when (seq values) (string/join "; " values))))

(defn- publication
  [data]
  (some non-blank-string
        [(:publicationTitle data)
         (:proceedingsTitle data)
         (:bookTitle data)
         (:publisher data)
         (:institution data)
         (:websiteTitle data)]))

(defn- library-identity-matches?
  [{:keys [kind id]} library]
  (let [library-kind (some-> (:type library) keyword)
        library-id (some-> (:id library) str)]
    (case kind
      :group (and (= :group library-kind) (= id library-id))
      :user (and (= :user library-kind)
                 (or (= "0" library-id) (= id library-id)))
      :local (and (= :user library-kind) (= "0" library-id))
      false)))

(defn normalize-item
  "Maps one Zotero Local API item to the source-record accepted by the DB
   worker. It never carries attachment paths, tokens or profile identity."
  [binding envelope]
  (let [binding (sanitize-binding binding)
        data (or (:data envelope) envelope)
        normalized-key (item-key (or (:key data) (:key envelope)))
        raw-type (non-blank-string (:itemType data))
        child? (some? (non-blank-string (:parentItem data)))
        excluded? (or child? (contains? excluded-item-types raw-type))
        title (non-blank-string (:title data))
        doi (normalize-doi (:DOI data))
        arxiv (arxiv-from-data data)
        warning (when (and (non-blank-string (:DOI data)) (nil? doi))
                  :invalid-doi)
        library (:library envelope)
        mismatch? (and binding
                       (not (library-identity-matches?
                             (:source-identity binding) library)))
        reason (cond
                 (nil? binding) :invalid-binding
                 mismatch? :library-identity-mismatch
                 (nil? normalized-key) :invalid-item-key
                 excluded? :not-top-level-bibliography
                 (nil? raw-type) :missing-item-type
                 (and (nil? title) (nil? doi) (nil? arxiv)) :missing-title)
        canonical-type (when raw-type
                         (or (get item-types raw-type)
                             (str "zotero:" raw-type)))]
    (if reason
      {:status :ignored
       :item-key normalized-key
       :reason reason}
      {:status :ready
       :item-key normalized-key
       :warnings (cond-> [] warning (conj warning))
       :source-record
       {:classification :bibliographic-item
        :source-key (str (source-prefix (:source-identity binding))
                         ":item:" normalized-key)
        :title title
        :authors (authors (:creators data))
        :date (non-blank-string (:date data))
        :publication (publication data)
        :type canonical-type
        :doi doi
        :arxiv arxiv
        :url (non-blank-string (:url data))
        :citekey (non-blank-string (:citationKey data))}})))

(defn- parse-key-list
  [body]
  (let [raw-keys (->> (string/split-lines (or body ""))
                      (keep non-blank-string)
                      distinct
                      vec)
        keys (mapv item-key raw-keys)]
    (when (some nil? keys)
      (throw (ex-info "Zotero returned an invalid item key list"
                      {:type :literature-zotero/invalid-key-list})))
    keys))

(defn- parse-items
  [body]
  (try
    (let [items (-> body js/JSON.parse (bean/->clj :keywordize-keys true))]
      (when-not (vector? items)
        (throw (ex-info "Zotero item response is not an array"
                        {:type :literature-zotero/invalid-item-response})))
      items)
    (catch :default error
      (if (= :literature-zotero/invalid-item-response
             (:type (ex-data error)))
        (throw error)
        (throw (ex-info "Zotero returned invalid JSON"
                        {:type :literature-zotero/invalid-json}
                        error))))))

(defn- active?
  [still-authorized?]
  (and still-authorized? (true? (still-authorized?))))

(defn <read-library-key!
  "Reads only the current library identity from the existing Zotero companion."
  []
  (p/let [response (ipc/ipc :httpRequest
                           (str (random-uuid))
                           {:url (str notifier-url "?identity=1")
                            :method "GET"
                            :proxy {:type "direct"}
                            :returnType "text"
                            :includeResponse true
                            :timeout 5000})
          key (when (:ok response)
                (library-key (.-libraryKey (js/JSON.parse (:body response)))))]
    (when-not key
      (throw (ex-info "Zotero library identity is unavailable"
                      {:type :literature-zotero/library-identity-unavailable})))
    key))

(defn <check-setup!
  "Checks local API access and companion identity without reading library items."
  []
  (-> (ipc/ipc :httpRequest (str (random-uuid))
               {:url (str local-api-root "/") :method "GET"
                :proxy {:type "direct"} :returnType "text"
                :includeResponse true :timeout 5000})
      (p/then (fn [response]
                (cond
                  (= 403 (:status response)) :local-access
                  (not (:ok response)) :unavailable
                  :else (-> (<read-library-key!)
                            (p/then (constantly :ready))
                            (p/catch (fn [error]
                                       (if (= :literature-zotero/library-identity-unavailable
                                              (:type (ex-data error)))
                                         :companion
                                         (throw error))))))))
      (p/catch (constantly :unavailable))))

(defn <show-companion!
  []
  (ipc/ipc :literature/show-zotero-companion))

(defn <local-api-request!
  "Checks the bound library before every read-only Zotero data request."
  [binding still-authorized? path query]
  (when-not (library-key (:zotero-library-key binding))
    (throw (ex-info "Zotero binding has no library identity"
                    {:type :literature-zotero/invalid-binding})))
  (let [url (str local-api-root "/" path
                 (when (seq query) (str "?" query)))]
    (when-not (active? still-authorized?)
      (throw (ex-info "Zotero binding is no longer active"
                      {:type :literature-zotero/binding-inactive})))
    (p/let [key (<read-library-key!)
            _ (when-not (active? still-authorized?)
                (throw (ex-info "Zotero binding is no longer active"
                                {:type :literature-zotero/binding-inactive})))
            _ (when-not (= key (:zotero-library-key binding))
                (throw (ex-info "Zotero library changed"
                                {:type :literature-zotero/library-identity-mismatch})))
            response (ipc/ipc :httpRequest
                              (str (random-uuid))
                              {:url url
                               :method "GET"
                            :proxy {:type "direct"}
                               :headers {"Zotero-API-Version" "3"}
                               :returnType "text"
                               :includeResponse true
                               :timeout 5000})]
      (when-not (map? response)
        (throw (ex-info "Zotero Local API is available only in the desktop client"
                        {:type :literature-zotero/desktop-required})))
      (when-not (:ok response)
        (throw (ex-info "Zotero Local API request failed"
                        {:type :literature-zotero/request-failed
                         :status (:status response)})))
      (:body response))))

(defn <read-collections-page!
  "Reads one user-requested personal-library page without retaining the tree."
  [still-authorized? start expected-library-key]
  (when-not (active? still-authorized?)
    (throw (ex-info "Zotero collection selection is no longer active"
                    {:type :literature-zotero/binding-inactive})))
  (p/let [key (or expected-library-key (<read-library-key!))
          body (<local-api-request! {:zotero-library-key key}
                                    still-authorized?
                                    "users/0/collections"
                                    (str "start=" start "&limit=51"))]
    (when-not (active? still-authorized?)
      (throw (ex-info "Zotero collection selection is no longer active"
                      {:type :literature-zotero/binding-inactive})))
    (let [rows (parse-items body)
          collections
          (mapv (fn [row]
                  (let [data (:data row)
                        collection-key (item-key (:key data))
                        name (non-blank-string (:name data))]
                    (when-not (and collection-key name
                                   (library-identity-matches?
                                    {:kind :local} (:library row)))
                      (throw (ex-info "Invalid Zotero collection response"
                                      {:type :literature-zotero/invalid-collection-response})))
                    {:key collection-key :name name}))
                (take 50 rows))]
      (cond-> {:collections collections :zotero-library-key key}
        (> (count rows) 50) (assoc :next-offset (+ start 50))))))

(defn- parse-notifier-response
  [body]
  (try
    (let [{:keys [session cursor rescan keys] :as response}
          (-> body js/JSON.parse (bean/->clj :keywordize-keys true))
          normalized-keys (when (vector? keys) (mapv item-key keys))]
      (when-not (and (map? response)
                     (library-key (:libraryKey response))
                     (non-blank-string session)
                     (int? cursor)
                     (not (neg? cursor))
                     (boolean? rescan)
                     (vector? normalized-keys)
                     (not-any? nil? normalized-keys))
        (throw (ex-info "Invalid Zotero notifier response"
                        {:type :literature-zotero/invalid-notifier-response})))
      {:session session
       :cursor cursor
       :zotero-library-key (:libraryKey response)
       :rescan rescan
       :keys (vec (distinct normalized-keys))})
    (catch :default error
      (if (= :literature-zotero/invalid-notifier-response
             (:type (ex-data error)))
        (throw error)
        (throw (ex-info "Zotero notifier returned invalid JSON"
                        {:type :literature-zotero/invalid-notifier-json}
                        error))))))

(defn <notifier-request!
  "Waits for one bounded change hint from the optional Zotero companion.
   An optional AbortSignal cancels the Electron main-process fetch by id."
  ([notification-state]
   (<notifier-request! notification-state nil))
  ([{:keys [session cursor] :or {cursor 0}} signal]
   (let [query (str "session=" (js/encodeURIComponent (or session ""))
                    "&ack=" cursor)
         request-id (str (random-uuid))
         request (ipc/ipc :httpRequest
                          request-id
                          {:url (str notifier-url "?" query)
                           :method "GET"
                            :proxy {:type "direct"}
                           :returnType "text"
                           :includeResponse true
                           :abortable true
                           :timeout 30000})
         abort-request! #(ipc/ipc :httpRequestAbort request-id)]
     (when signal
       (.addEventListener signal "abort" abort-request! #js {:once true})
       (when (.-aborted signal)
         (abort-request!)))
     (-> (p/let [response request]
           (when-not (map? response)
             (throw (ex-info "Zotero notifier is available only in the desktop client"
                             {:type :literature-zotero/desktop-required})))
           (when-not (:ok response)
             (throw (ex-info "Zotero notifier request failed"
                             {:type :literature-zotero/notifier-request-failed
                              :status (:status response)})))
           (parse-notifier-response (:body response)))
         (p/finally
          (fn []
            (when signal
              (.removeEventListener signal "abort" abort-request!))))))))

(defn <read-source-record!
  "Reads and normalizes exactly one source record for an explicit, live
   binding-to-paper source key. This function never writes graph data."
  [binding source-key still-authorized?]
  (let [binding (sanitize-binding binding)
        key (source-key-item-key binding source-key)]
    (cond
      (nil? binding)
      (p/resolved {:status :blocked :reason :invalid-binding})

      (nil? key)
      (p/resolved {:status :blocked :reason :invalid-source-key})

      (not (active? still-authorized?))
      (p/resolved {:status :cancelled :reason :binding-inactive})

      :else
      (p/let [query (str "itemKey=" (js/encodeURIComponent key) "&limit=1")
              body (<local-api-request! binding still-authorized? (scope-items-path binding) query)
              envelopes (parse-items body)]
        (cond
          (not (active? still-authorized?))
          {:status :cancelled :reason :binding-inactive}

          (empty? envelopes)
          {:status :missing :reason :source-item-unavailable}

          (not= 1 (count envelopes))
          (throw (ex-info "Zotero returned more than one item for one source key"
                          {:type :literature-zotero/invalid-item-response}))

          :else
          (let [envelope (first envelopes)
                returned-key (item-key (or (get-in envelope [:data :key])
                                           (:key envelope)))]
            (when-not (= key returned-key)
              (throw (ex-info "Zotero returned an item outside the requested source key"
                              {:type :literature-zotero/unexpected-item})))
            (normalize-item binding envelope)))))))

(defn- file-url-filename
  [value]
  (try
    (when-not (string? value)
      (throw (js/Error. "Missing attachment file URL")))
    (let [url (js/URL. value)
          filename (js/decodeURIComponent
                    (last (string/split (.-pathname url) #"/")))]
      (when-not (and (= "file:" (.-protocol url))
                     (string/blank? (.-search url))
                     (string/blank? (.-hash url))
                     (not (string/blank? filename))
                     (not (re-find #"[/\\]" filename)))
        (throw (js/Error. "Invalid attachment file URL")))
      filename)
    (catch :default error
      (throw (ex-info "Zotero returned an invalid attachment file URL"
                      {:type :literature-zotero/invalid-item-response}
                      error)))))

(defn- standalone-pdf?
  [data]
  (and (= "attachment" (:itemType data))
       (nil? (non-blank-string (:parentItem data)))
       (= "application/pdf" (:contentType data))
       (contains? #{"imported_file" "imported_url" "linked_file"}
                  (:linkMode data))))

(defn- <read-parent-pdf-attachments!
  "Reads a scoped parent's PDF-child metadata and local file URLs in batches
   of at most 50 keys. Returned paths are transient; no file bytes or graph
   data are read or written, and a file URL does not prove file availability."
  [binding source-key still-authorized?]
  (let [binding (sanitize-binding binding)
        parent-key (source-key-item-key binding source-key)
        library-path (when binding (api-library-path (:source-identity binding)))
        children-path (str library-path "/items/" parent-key "/children")]
    (p/let [parent (<read-source-record! binding source-key still-authorized?)]
      (cond
        (not (or (= :ready (:status parent))
                 (and (= :ignored (:status parent))
                      (= :missing-title (:reason parent))))) parent
        (not (active? still-authorized?)) {:status :cancelled :reason :binding-inactive}
        :else
        (p/let [body (<local-api-request! binding still-authorized? children-path "format=keys")]
          (if-not (active? still-authorized?)
            {:status :cancelled :reason :binding-inactive}
            (p/loop [batches (seq (partition-all item-batch-size (parse-key-list body)))
                     attachments []]
              (if-not (active? still-authorized?)
                {:status :cancelled :reason :binding-inactive}
                (if-let [batch (first batches)]
                  (p/let [query (str "itemKey="
                                    (js/encodeURIComponent (string/join "," batch))
                                    "&limit=" item-batch-size)
                          batch-body (<local-api-request! binding still-authorized? children-path query)]
                    (if-not (active? still-authorized?)
                      {:status :cancelled :reason :binding-inactive}
                      (let [envelopes (parse-items batch-body)
                            returned-keys (mapv #(item-key (or (get-in % [:data :key])
                                                               (:key %)))
                                                envelopes)
                            requested-keys (set batch)]
                        (when-not (and (every? requested-keys returned-keys)
                                       (= (count returned-keys) (count (set returned-keys))))
                          (throw (ex-info "Zotero returned duplicate or unrequested children"
                                          {:type :literature-zotero/unexpected-item})))
                        (doseq [envelope envelopes]
                          (when-not (and (= parent-key (get-in envelope [:data :parentItem]))
                                         (library-identity-matches? (:source-identity binding)
                                                                    (:library envelope)))
                            (throw (ex-info "Zotero returned a child outside the requested parent"
                                            {:type :literature-zotero/unexpected-item}))))
                        (p/let [batch-result
                                (p/loop [remaining (seq (filter #(let [data (:data %)]
                                                                 (and (= "attachment" (:itemType data))
                                                                      (= "application/pdf" (:contentType data))
                                                                      (contains? #{"imported_file" "imported_url" "linked_file"}
                                                                                 (:linkMode data))))
                                                              envelopes))
                                         files []]
                                  (if-not (active? still-authorized?)
                                    {:status :cancelled :reason :binding-inactive}
                                    (if-let [envelope (first remaining)]
                                      (let [data (:data envelope)
                                            key (item-key (or (:key data) (:key envelope)))]
                                        (p/let [file-url (<local-api-request!
                                                           binding still-authorized?
                                                         (str library-path "/items/" key "/file/view/url") nil)]
                                          (if-not (active? still-authorized?)
                                            {:status :cancelled :reason :binding-inactive}
                                            (p/recur (next remaining)
                                                     (conj files
                                                           {:item-key key
                                                            :parent-item-key parent-key
                                                            :filename (file-url-filename file-url)
                                                            :title (non-blank-string (:title data))
                                                            :content-type (:contentType data)
                                                            :link-mode (:linkMode data)
                                                            :file-url file-url})))))
                                      {:status :ready :attachments files})))]
                          (if (= :cancelled (:status batch-result))
                            batch-result
                            (p/recur (next batches)
                                     (into attachments (:attachments batch-result))))))))
                  {:status :ready :parent-item-key parent-key :attachments attachments})))))))))

(defn- <read-standalone-pdf-attachment!
  [binding source-key still-authorized?]
  (let [binding (sanitize-binding binding)
        key (source-key-item-key binding source-key)]
    (cond
      (nil? binding)
      (p/resolved {:status :blocked :reason :invalid-binding})

      (nil? key)
      (p/resolved {:status :blocked :reason :invalid-source-key})

      (not (active? still-authorized?))
      (p/resolved {:status :cancelled :reason :binding-inactive})

      :else
      (p/let [query (str "itemKey=" (js/encodeURIComponent key) "&limit=1")
              body (<local-api-request! binding still-authorized? (scope-items-path binding) query)
              envelopes (parse-items body)]
        (cond
          (not (active? still-authorized?))
          {:status :cancelled :reason :binding-inactive}

          (empty? envelopes)
          {:status :missing :reason :source-item-unavailable}

          (not= 1 (count envelopes))
          (throw (ex-info "Zotero returned more than one item for one source key"
                          {:type :literature-zotero/invalid-item-response}))

          :else
          (let [envelope (first envelopes)
                data (or (:data envelope) envelope)
                returned-key (item-key (or (:key data) (:key envelope)))
                normalized (normalize-item binding envelope)]
            (when-not (= key returned-key)
              (throw (ex-info "Zotero returned an item outside the requested source key"
                              {:type :literature-zotero/unexpected-item})))
            (if (and (= :ignored (:status normalized))
                     (= :not-top-level-bibliography (:reason normalized))
                     (standalone-pdf? data))
              (p/let [file-url (<local-api-request!
                               binding still-authorized?
                               (str (api-library-path (:source-identity binding))
                                    "/items/" key "/file/view/url") nil)]
                (if-not (active? still-authorized?)
                  {:status :cancelled :reason :binding-inactive}
                  {:status :ready
                   :attachments [{:item-key key
                                  :filename (file-url-filename file-url)
                                  :content-type (:contentType data)
                                  :link-mode (:linkMode data)
                                  :file-url file-url}]}))
              (if (= :ready (:status normalized))
                {:status :ignored :item-key key :reason :not-top-level-bibliography}
                normalized))))))))

(defn <read-pdf-attachments!
  "Reads scoped PDF metadata and transient local file URLs without reading
   file bytes or writing graph data. Three arguments read a parent's children;
   the fourth argument explicitly selects a parent or standalone PDF."
  ([binding source-key still-authorized?]
   (<read-parent-pdf-attachments! binding source-key still-authorized?))
  ([binding source-key still-authorized? context]
   (case (:kind context)
     :parent (<read-parent-pdf-attachments! binding source-key still-authorized?)
     :standalone (<read-standalone-pdf-attachment! binding source-key still-authorized?))))

(defn- import-item
  [binding envelope]
  (let [normalized (normalize-item binding envelope)
        data (or (:data envelope) envelope)
        kind (cond
               (= :ready (:status normalized)) :parent
               (= :missing-title (:reason normalized)) :parent
               (and (= :not-top-level-bibliography (:reason normalized))
                    (standalone-pdf? data)) :standalone)]
    (cond-> normalized
      kind (assoc :attachment-context
                  {:kind kind
                   :item-key (:item-key normalized)
                   :source-key (str (source-prefix (:source-identity binding))
                                    ":item:" (:item-key normalized))}))))

(defn- counts
  [results]
  (frequencies (map :status results)))

(defn <import-item-keys!
  "Reads at most 50 explicit keys per request and dispatches source records or
   local PDFs sequentially with transient attachment context. Authorization is
   rechecked before every request and graph write."
  [binding keys still-authorized? register!]
  (let [binding (sanitize-binding binding)
        normalized-keys (mapv item-key keys)
        keys (vec (distinct normalized-keys))]
    (cond
      (nil? binding)
      (p/resolved {:status :blocked :reason :invalid-binding})

      (some nil? normalized-keys)
      (p/resolved {:status :blocked :reason :invalid-item-keys})

      (not (active? still-authorized?))
      (p/resolved {:status :cancelled :reason :binding-inactive})

      :else
      (p/loop [batches (seq (partition-all item-batch-size keys))
               fetched 0
               ignored []
               results []]
        (if-let [batch (first batches)]
          (if-not (active? still-authorized?)
            {:status :cancelled
             :reason :binding-inactive
             :requested-count (count keys)
             :fetched-count fetched
             :ignored ignored
             :results results
             :result-counts (counts results)}
            (p/let [query (str "itemKey="
                               (js/encodeURIComponent (string/join "," batch))
                               "&limit=" item-batch-size)
                    body (<local-api-request! binding still-authorized? (scope-items-path binding) query)
                    envelopes (parse-items body)
                    returned-keys (set (map #(item-key (or (get-in % [:data :key])
                                                           (:key %)))
                                            envelopes))
                    requested-keys (set batch)
                    _ (when-not (every? requested-keys returned-keys)
                        (throw (ex-info "Zotero returned an item outside the requested batch"
                                        {:type :literature-zotero/unexpected-item})))
                    normalized (mapv #(import-item binding %) envelopes)
                    batch-ignored (filterv #(nil? (:attachment-context %)) normalized)
                    importable (filterv :attachment-context normalized)
                    batch-results
                    (p/loop [remaining importable
                             imported []]
                      (if-let [{source-item-key :item-key
                                :keys [source-record warnings attachment-context]}
                               (first remaining)]
                        (if-not (active? still-authorized?)
                          {:cancelled? true :results imported}
                          (p/let [result (register!
                                         (:graph-binding-uuid binding)
                                         source-record
                                         attachment-context)]
                            ;; Keep the current Notifier cursor. Its existing
                            ;; retry/cancellation loop owns the next attempt.
                            (when (= :deferred (:status result))
                              (throw (ex-info "Zotero import deferred during graph repair"
                                              {:type :literature-zotero/repair-active})))
                            (p/recur (next remaining)
                                     (conj imported
                                           {:item-key source-item-key
                                            :status (:status result)
                                            :reason (:reason result)
                                            :paper-uuid (:paper-uuid result)
                                            :warnings warnings}))))
                        {:cancelled? false :results imported}))
                    next-results (into results (:results batch-results))
                    next-ignored (into ignored batch-ignored)
                    next-fetched (+ fetched (count envelopes))]
              (if (:cancelled? batch-results)
                {:status :cancelled
                 :reason :binding-inactive
                 :requested-count (count keys)
                 :fetched-count next-fetched
                 :ignored next-ignored
                 :results next-results
                 :result-counts (counts next-results)}
                (p/recur (next batches)
                         next-fetched
                         next-ignored
                         next-results))))
          {:status :complete
           :requested-count (count keys)
           :fetched-count fetched
           :ignored ignored
           :results results
           :result-counts (counts results)})))))

(defn <import-scope!
  "Reads one authorized library/collection key snapshot, then imports it through
   the same bounded explicit-key path used by future notifications."
  [binding still-authorized? register!]
  (let [binding (sanitize-binding binding)]
    (cond
      (nil? binding)
      (p/resolved {:status :blocked :reason :invalid-binding})

      (not (active? still-authorized?))
      (p/resolved {:status :cancelled :reason :binding-inactive})

      :else
      (p/let [body (<local-api-request! binding still-authorized? (scope-items-path binding) "format=keys")]
        (if-not (active? still-authorized?)
          {:status :cancelled :reason :binding-inactive}
          (<import-item-keys! binding
                              (parse-key-list body)
                              still-authorized?
                              register!))))))

(defn <read-scope-page!
  "Reads at most one existing batch of normalized metadata on user request.
   It never registers entries or reads attachment metadata or file bytes."
  [binding offset still-authorized?]
  (let [binding (sanitize-binding binding)
        records (atom [])]
    (cond
      (nil? binding) (p/resolved {:status :blocked :reason :invalid-binding})
      (not (active? still-authorized?))
      (p/resolved {:status :cancelled :reason :binding-inactive})
      :else
      (p/let [body (<local-api-request! binding still-authorized? (scope-items-path binding) "format=keys")
              keys (parse-key-list body)
              batch (vec (take item-batch-size (drop offset keys)))
              result (<import-item-keys!
                      binding batch still-authorized?
                      (fn [_binding-uuid record _attachment-context]
                        (when record (swap! records conj record))
                        (p/resolved {:status :ready})))]
        (if (= :complete (:status result))
          {:status :complete
           :items @records
           :next-offset (when (< (+ offset item-batch-size) (count keys))
                          (+ offset item-batch-size))}
          result)))))

(defn <process-notifier-cycle!
  "Processes one long-poll response. The returned cursor advances only after
   its scope/key import completes, so a lost or cancelled response is replayed."
  ([binding notification-state still-authorized? register!]
   (<process-notifier-cycle! binding notification-state still-authorized? register! nil))
  ([binding notification-state still-authorized? register! signal]
   (cond
     (nil? (sanitize-binding binding))
     (p/resolved {:status :blocked :reason :invalid-binding})

     (not (active? still-authorized?))
     (p/resolved {:status :cancelled :reason :binding-inactive})

     :else
     (p/let [{:keys [session cursor rescan keys] :as notification}
             (if signal
               (<notifier-request! notification-state signal)
               (<notifier-request! notification-state))]
       (cond
         (not (active? still-authorized?))
         {:status :cancelled :reason :binding-inactive}

         (not= (:zotero-library-key binding) (:zotero-library-key notification))
         {:status :blocked :reason :library-identity-mismatch}

         :else
         (p/let [result (if rescan
                          (<import-scope! binding still-authorized? register!)
                          (<import-item-keys! binding keys
                                              still-authorized? register!))]
           (if (and (= :complete (:status result))
                    (active? still-authorized?))
             {:status (if (and (not rescan) (empty? keys)) :idle :complete)
              :notification-state {:session session :cursor cursor}
              :notification notification
              :import result}
             {:status (if (= :complete (:status result))
                        :cancelled
                        (:status result))
              :reason (if (= :complete (:status result))
                        :binding-inactive
                        (:reason result))
              :notification notification
              :import result})))))))
