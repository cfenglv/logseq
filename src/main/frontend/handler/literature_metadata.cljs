(ns ^:no-doc frontend.handler.literature-metadata
  "Fixed-origin identifier lookups shared by new and existing PDF organization."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [logseq.db.frontend.literature :as db-literature]
            [promesa.core :as p]))

(def ^:private max-response-characters 65536)
(def ^:private max-title-characters 512)
(def ^:private max-authors-characters 8192)
(def ^:private max-authors 500)
(def ^:private atom-namespace "http://www.w3.org/2005/Atom")

(defn- non-blank
  [value]
  (when (string? value)
    (some-> value string/trim not-empty)))

(defn- normalized-doi
  [value]
  (let [candidate (some-> value non-blank string/lower-case)]
    (when (and candidate
               (re-matches #"10\.\d{4,9}/\S+" candidate))
      candidate)))

(defn- bounded
  [value max-length]
  (let [value (non-blank value)]
    (when (and value (<= (count value) max-length))
      value)))

(defn- crossref-author
  [{:keys [given family name]}]
  (or (bounded name 256)
      (let [given (bounded given 128)
            family (bounded family 128)]
        (when family
          (if given (str family ", " given) family)))))

(defn- crossref-date
  [message]
  (let [parts (first (get-in message [:published :date-parts]))]
    (when (and (vector? parts)
               (<= 1 (count parts) 3)
               (every? #(and (int? %) (pos? %)) parts))
      (string/join "-" (map-indexed (fn [index n]
                                     (if (zero? index)
                                       (str n)
                                       (str (when (< n 10) "0") n)))
                                   parts)))))

(defn parse-crossref-record
  "Accepts one exact, field-selected scholarly DOI record with named authors."
  [requested-doi body]
  (try
    (if (or (not (string? body))
            (> (count body) max-response-characters))
      {:status :blocked :reason :metadata-response-too-large}
      (let [response (-> body js/JSON.parse
                         (bean/->clj :keywordize-keys true))
            messages (get-in response [:message :items])
            message (when (and (= 1 (get-in response [:message :total-results]))
                               (= 1 (count messages)))
                      (first messages))
            type (get {"journal-article" "journal-article"
                       "proceedings-article" "paper-conference"
                       "book" "book" "monograph" "book"
                       "book-chapter" "chapter"
                       "report" "report" "dissertation" "thesis"}
                      (:type message))
            doi (normalized-doi (:DOI message))
            title (bounded (first (:title message)) max-title-characters)
            raw-authors (:author message)
            authors (when (and (vector? raw-authors)
                               (<= 1 (count raw-authors) max-authors))
                      (let [names (mapv crossref-author raw-authors)]
                        (when (every? some? names)
                          (bounded (string/join "; " names)
                                   max-authors-characters))))]
        (cond
          (not= "ok" (:status response))
          {:status :blocked :reason :invalid-metadata-response}

          (not= (normalized-doi requested-doi) doi)
          {:status :blocked :reason :metadata-identifier-mismatch}

          (nil? type)
          {:status :blocked :reason :unsupported-type}

          (nil? title)
          {:status :blocked :reason :missing-title}

          (nil? authors)
          {:status :blocked :reason :missing-authors}

          :else
          {:status :ready
           :record {:provider :crossref
                    :doi doi
                    :title title
                    :authors authors
                    :type type
                    :url (bounded (:URL message) 4096)
                    :date (crossref-date message)
                    :publication (bounded
                                  (first (:container-title message)) 512)}})))
    (catch :default _error
      {:status :blocked :reason :invalid-metadata-response})))

(defn- normalized-arxiv-request
  [value]
  (let [value (some-> value non-blank string/lower-case)]
    (when (and value
               (re-matches #"(?:\d{4}\.\d{4,5}|[a-z][a-z0-9.-]*/\d{7})(?:v[1-9]\d*)?" value))
      value)))

(defn- atom-children
  [node name]
  (when node
    (->> (array-seq (.-childNodes node))
         (filter #(and (= 1 (.-nodeType %))
                       (= atom-namespace (.-namespaceURI %))
                       (= name (.-localName %)))))))

(defn- atom-text
  [node name]
  (let [children (atom-children node name)]
    (when (= 1 (count children))
      (non-blank (.-textContent (first children))))))

(defn parse-arxiv-record
  "Accepts one matching Atom entry; a requested PDF version must match exactly.
   The third argument lets isolated Node tests supply a DOM parser without
   adding an XML package to the client bundle."
  ([requested-arxiv body]
   (parse-arxiv-record requested-arxiv body js/DOMParser))
  ([requested-arxiv body parser]
   (try
     (if (or (not (string? body))
             (> (count body) max-response-characters)
             (re-find #"(?i)<!\s*(?:doctype|entity)\b" body))
       {:status :blocked :reason :invalid-metadata-response}
       (let [requested (normalized-arxiv-request requested-arxiv)
             document (.parseFromString (new parser) body "application/xml")
             feed (.-documentElement document)
             entries (when (and (= "feed" (.-localName feed))
                                (= atom-namespace (.-namespaceURI feed)))
                       (atom-children feed "entry"))
             entry (when (= 1 (count entries)) (first entries))
             returned (some->> (atom-text entry "id")
                               (re-matches
                                #"https?://(?:www\.)?arxiv\.org/abs/((?:\d{4}\.\d{4,5}|[a-z][a-z0-9.-]*/\d{7})(?:v[1-9]\d*)?)")
                               second)
             requested-base (some-> requested
                                    (string/replace #"v[1-9]\d*$" ""))
             returned-base (some-> returned
                                   (string/replace #"v[1-9]\d*$" ""))
             title (some-> (atom-text entry "title")
                           (string/replace #"\s+" " ")
                           (bounded max-title-characters))
             raw-authors (when entry (vec (atom-children entry "author")))
             authors (when (<= 1 (count raw-authors) max-authors)
                       (let [names (mapv #(bounded (atom-text % "name") 256)
                                         raw-authors)]
                         (when (every? some? names)
                           (bounded (string/join "; " names)
                                    max-authors-characters))))
             doi-node (when entry
                        (some #(when (and (= "doi" (.-localName %))
                                          (= "http://arxiv.org/schemas/atom" (.-namespaceURI %))) %)
                              (array-seq (.-childNodes entry))))
             doi (some-> doi-node .-textContent normalized-doi)
             published (atom-text entry "published")
             date (when (and published
                             (re-find #"^\d{4}-\d{2}-\d{2}(?:T|$)" published))
                    (subs published 0 10))]
         (cond
           (nil? requested)
           {:status :blocked :reason :unsupported-metadata-identifier}

           (nil? entry)
           {:status :blocked :reason :invalid-metadata-response}

           (or (nil? returned)
               (not= requested-base returned-base)
               (and (re-find #"v[1-9]\d*$" requested)
                    (not= requested returned)))
           {:status :blocked :reason :metadata-identifier-mismatch}

           (nil? title)
           {:status :blocked :reason :missing-title}

           (nil? authors)
           {:status :blocked :reason :missing-authors}

           :else
           {:status :ready
            :record (cond-> {:provider :arxiv
                     :arxiv requested-base
                     :arxiv-record-id returned
                     :title title
                     :authors authors
                     :type "preprint"
                     :url (str "https://arxiv.org/abs/" returned)
                     :date date}
                      doi (assoc :doi doi))})))
     (catch :default _error
       {:status :blocked :reason :invalid-metadata-response}))))

(defn- <http-record!
  [url accept identifier parser signal]
  (let [request-id (str (random-uuid))
        abort-request! #(ipc/ipc :httpRequestAbort request-id)]
    (when signal
      (.addEventListener signal "abort" abort-request! #js {:once true}))
    (-> (p/let [response (ipc/ipc :httpRequest
                                 request-id
                                 {:url url
                                  :method "GET"
                                  :headers {"Accept" accept}
                                  :returnType "text"
                                  :includeResponse true
                                  :abortable true
                                  :maxResponseBytes 65536
                                  :timeout 5000})]
          (cond
            (and signal (.-aborted signal))
            {:status :blocked :reason :metadata-request-cancelled}

            (not (:ok response))
            {:status :blocked :reason :metadata-unavailable}

            :else
            (parser identifier (:body response))))
        (p/catch
         (fn [_error]
           {:status :blocked :reason :metadata-unavailable}))
        (p/finally
         (fn []
           (when signal
             (.removeEventListener signal "abort" abort-request!)))))))

(defonce ^:private arxiv-last-request-at (atom 0))
(defonce ^:private arxiv-lookup-tail (atom (p/resolved nil)))

(defn- <arxiv-record!
  [arxiv signal]
  ;; arXiv asks callers to space consecutive API requests. The one-promise
  ;; chain is active only for explicit, opt-in PDF lookups; it does not poll.
  (let [result
        (-> (p/catch @arxiv-lookup-tail (constantly nil))
            (p/then
             (fn [_]
               (p/let [_ (p/delay
                          (max 0 (- 3000 (- (.now js/Date)
                                             @arxiv-last-request-at))))]
                 (if (and signal (.-aborted signal))
                   {:status :blocked :reason :metadata-request-cancelled}
                   (do
                     (reset! arxiv-last-request-at (.now js/Date))
                     (<http-record!
                      (str "https://export.arxiv.org/api/query?id_list="
                           (js/encodeURIComponent arxiv)
                           "&max_results=1")
                      "application/atom+xml" arxiv parse-arxiv-record
                      signal)))))))]
    (reset! arxiv-lookup-tail (p/catch result (constantly nil)))
    result))

(defn- <crossref-record!
  [doi signal]
  (<http-record!
   (str "https://api.crossref.org/works?filter=doi:"
        (js/encodeURIComponent doi)
        "&rows=1&select=DOI,title,author,type,published,container-title,URL")
   "application/json" doi parse-crossref-record signal))

(defn <lookup-record!
  "Resolves exact identifiers. A second lookup is needed only when an arXiv
   record does not itself confirm the PDF's supplied DOI."
  [identifiers signal]
  (let [doi (normalized-doi (:doi identifiers))
        arxiv (normalized-arxiv-request (:arxiv identifiers))]
    (cond
      (and signal (.-aborted signal))
      (p/resolved {:status :blocked :reason :metadata-request-cancelled})

      arxiv
      (p/let [result (<arxiv-record! arxiv signal)]
        (if (or (not= :ready (:status result)) (nil? doi))
          result
          (let [record (:record result)]
            (cond
              (= doi (:doi record)) result
              (:doi record) {:status :blocked :reason :metadata-identifier-mismatch}
              :else
              (p/let [doi-result (<crossref-record! doi signal)]
                (if (and (= :ready (:status doi-result))
                         (db-literature/same-bibliography? record (:record doi-result)))
                  (assoc-in result [:record :doi] doi)
                  (if (= :ready (:status doi-result))
                    {:status :blocked :reason :metadata-identifier-mismatch}
                    doi-result)))))))

      doi
      (<crossref-record! doi signal)

      :else
      (p/resolved {:status :blocked :reason :unsupported-metadata-identifier}))))
