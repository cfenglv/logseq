(ns frontend.handler.literature-metadata-test
  (:require ["@xmldom/xmldom" :as xmldom]
            [cljs.test :refer [deftest is]]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.handler.literature-metadata :as metadata]
            [frontend.test.helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(defn- crossref-body
  [message]
  (js/JSON.stringify
   (clj->js {:status "ok"
             :message {:total-results 1 :items [(merge {:DOI "10.5555/verified"
                              :type "journal-article"
                              :title ["A Verified Paper"]
                              :author [{:given "Alice" :family "Example"}]
                              :published {:date-parts [[2026 9 29]]}
                              :container-title ["Synthetic Journal"]}
                             message)]}})))

(defn- arxiv-body
  [{:keys [id title authors extra-entry]
    :or {id "2401.12345v2"
         title "A Verified Preprint"
         authors ["Alice Example" "Bob Researcher"]}}]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
       "<entry><id>http://arxiv.org/abs/" id "</id>"
       (when title (str "<title>" title "</title>"))
       (apply str (map #(str "<author><name>" % "</name></author>")
                       authors))
       "<published>2024-01-01T00:00:00Z</published></entry>"
       extra-entry "</feed>"))

(deftest crossref-accepts-only-an-exact-complete-journal-record
  (let [result (metadata/parse-crossref-record
                "10.5555/verified" (crossref-body {}))]
    (is (= :ready (:status result)))
    (is (= {:provider :crossref
            :doi "10.5555/verified"
            :title "A Verified Paper"
            :authors "Example, Alice"
            :type "journal-article"
            :url nil
            :date "2026-09-29"
            :publication "Synthetic Journal"}
           (:record result))))
  (doseq [[reason message]
          [[:metadata-identifier-mismatch {:DOI "10.5555/other"}]
           [:unsupported-type {:type "dataset"}]
           [:missing-title {:title []}]
           [:missing-authors {:author []}]]]
    (is (= reason
           (:reason (metadata/parse-crossref-record
                     "10.5555/verified" (crossref-body message))))))
  (is (= :invalid-metadata-response
         (:reason (metadata/parse-crossref-record
                   "10.5555/verified" "not-json"))))
  (is (= :metadata-response-too-large
         (:reason (metadata/parse-crossref-record
                   "10.5555/verified" (apply str (repeat 65537 "x")))))))

(deftest arxiv-accepts-one-exact-versioned-preprint
  (let [parse #(metadata/parse-arxiv-record %1 %2 xmldom/DOMParser)
        result (parse "2401.12345v2" (arxiv-body {}))]
    (is (= :ready (:status result)))
    (is (= {:provider :arxiv
            :arxiv "2401.12345"
            :arxiv-record-id "2401.12345v2"
            :title "A Verified Preprint"
            :authors "Alice Example; Bob Researcher"
            :type "preprint"
            :url "https://arxiv.org/abs/2401.12345v2"
            :date "2024-01-01"}
           (:record result)))
    (is (= :ready
           (:status (parse "2401.12345" (arxiv-body {})))))
    (doseq [[reason request overrides]
            [[:metadata-identifier-mismatch "2401.12345v1" {}]
             [:metadata-identifier-mismatch "2401.12345v2"
              {:id "2401.12345v3"}]
             [:metadata-identifier-mismatch "2401.12345v2"
              {:id "2401.99999v2"}]
             [:missing-title "2401.12345v2" {:title nil}]
             [:missing-authors "2401.12345v2" {:authors []}]
             [:invalid-metadata-response "2401.12345v2"
              {:extra-entry "<entry><id>http://arxiv.org/abs/2401.12345v2</id></entry>"}]]]
      (is (= reason (:reason (parse request (arxiv-body overrides))))))
    (is (= :invalid-metadata-response
           (:reason (parse "2401.12345v2"
                           "<!DOCTYPE feed [<!ENTITY x 'bad'>]><feed/>"))))
    (is (= :invalid-metadata-response
           (:reason (parse "2401.12345v2" "<html/>"))))))

(deftest-async crossref-request-sends-only-doi-to-one-fixed-endpoint
  (let [calls (atom [])]
    (p/with-redefs [ipc/ipc (fn [& args]
                              (swap! calls conj args)
                              (p/resolved {:ok true
                                           :body (crossref-body {})}))]
      (p/let [result (metadata/<lookup-record!
                      {:doi "10.5555/verified"} nil)
              unsupported (metadata/<lookup-record!
                           {:arxiv "not-an-id"} nil)]
        (is (= :ready (:status result)) (pr-str result))
        (is (= :unsupported-metadata-identifier (:reason unsupported)))
        (is (= 1 (count @calls)))
        (let [[method _request-id opts] (first @calls)]
          (is (= :httpRequest method))
          (is (= "https://api.crossref.org/works?filter=doi:10.5555%2Fverified&rows=1&select=DOI,title,author,type,published,container-title,URL"
                 (:url opts)))
          (is (= "GET" (:method opts)))
          (is (= 5000 (:timeout opts)))
          (is (= 65536 (:maxResponseBytes opts)))
          (is (not (re-find #"Verified%20Paper|synthetic/path|file:"
                            (str (:url opts))))))))))

(deftest-async arxiv-request-sends-only-the-versioned-id
  (let [calls (atom [])
        original-parser (aget js/globalThis "DOMParser")]
    (aset js/globalThis "DOMParser" xmldom/DOMParser)
    (-> (p/with-redefs [ipc/ipc (fn [& args]
                                  (swap! calls conj args)
                                  (p/resolved {:ok true
                                               :body (arxiv-body {})}))]
          (p/let [result (metadata/<lookup-record!
                          {:arxiv "2401.12345v2"} nil)]
            (is (= :ready (:status result)) (pr-str result))
            (is (= 1 (count @calls)))
            (let [[method _request-id opts] (first @calls)]
              (is (= :httpRequest method))
              (is (= "https://export.arxiv.org/api/query?id_list=2401.12345v2&max_results=1"
                     (:url opts)))
              (is (= "application/atom+xml" (get-in opts [:headers "Accept"])))
              (is (= 5000 (:timeout opts)))
              (is (= 65536 (:maxResponseBytes opts)))
              (is (not (re-find #"pdf|asset|title|author"
                                (str (:url opts))))))))
        (p/finally #(aset js/globalThis "DOMParser" original-parser)))))

(deftest-async consecutive-arxiv-lookups-are-spaced-without-a-background-task
  (let [sent-at (atom [])
        original-parser (aget js/globalThis "DOMParser")]
    (aset js/globalThis "DOMParser" xmldom/DOMParser)
    (-> (p/with-redefs [ipc/ipc (fn [& _args]
                                  (swap! sent-at conj (.now js/Date))
                                  (p/resolved {:ok true
                                               :body (arxiv-body {})}))]
          (p/let [results (p/all [(metadata/<lookup-record!
                                   {:arxiv "2401.12345v2"} nil)
                                  (metadata/<lookup-record!
                                   {:arxiv "2401.12345v2"} nil)])]
            (is (= [:ready :ready] (mapv :status results)))
            (is (= 2 (count @sent-at)))
            (is (>= (- (second @sent-at) (first @sent-at)) 3000))))
        (p/finally #(aset js/globalThis "DOMParser" original-parser)))))

(deftest old-arxiv-and-field-selected-conference-records
  (let [old (metadata/parse-arxiv-record
             "quant-ph/9906129v1" (arxiv-body {:id "quant-ph/9906129v1"}) xmldom/DOMParser)]
    (is (= :ready (:status old)))
    (is (= "quant-ph/9906129" (get-in old [:record :arxiv])))
    (is (= :metadata-identifier-mismatch
           (:reason (metadata/parse-arxiv-record
                     "quant-ph/9906129v2" (arxiv-body {:id "quant-ph/9906129v1"}) xmldom/DOMParser)))))
  (is (= "paper-conference"
         (get-in (metadata/parse-crossref-record
                  "10.5555/verified" (crossref-body {:type "proceedings-article"})) [:record :type])))
  (is (= :metadata-identifier-mismatch
         (:reason (metadata/parse-crossref-record
                   "10.5555/verified" (crossref-body {:DOI "10.5555/another"}))))))

(deftest-async arxiv-declared-doi-confirms-both-identifiers-with-one-request
  (let [calls (atom [])
        original-parser (aget js/globalThis "DOMParser")
        body (string/replace
              (arxiv-body {}) "</entry>"
              "<arxiv:doi xmlns:arxiv='http://arxiv.org/schemas/atom'>10.5555/verified</arxiv:doi></entry>")]
    (aset js/globalThis "DOMParser" xmldom/DOMParser)
    (-> (p/with-redefs [ipc/ipc (fn [& args]
                                (swap! calls conj args)
                                (p/resolved {:ok true :body body}))]
          (p/let [result (metadata/<lookup-record! {:arxiv "2401.12345v2" :doi "10.5555/verified"} nil)]
            (is (= :ready (:status result)))
            (is (= "10.5555/verified" (get-in result [:record :doi])))
            (is (= 1 (count @calls)))))
        (p/finally #(aset js/globalThis "DOMParser" original-parser)))))


(deftest named-collaboration-authors-fit-the-bounded-response
  (let [authors (mapv (fn [i] {:given (str "Given" i) :family (str "Family" i)}) (range 300))
        result (metadata/parse-crossref-record "10.5555/verified" (crossref-body {:author authors}))]
    (is (= :ready (:status result)))
    (is (= 300 (count (string/split (get-in result [:record :authors]) #"; "))))
    (is (<= (count (get-in result [:record :authors])) 8192))))
