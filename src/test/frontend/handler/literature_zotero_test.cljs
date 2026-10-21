(ns frontend.handler.literature-zotero-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.handler.literature-zotero :as zotero]
            [frontend.test.helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(def graph-binding-uuid
  #uuid "79000000-0000-4000-8000-000000000001")

(def local-source-uuid
  "79000000-0000-4000-8000-000000000002")

(def destination-page-uuid #uuid "79000000-0000-4000-8000-000000000099")

(def local-library-binding
  {:zotero-library-key "LOCAL001"
   :destination-page-uuid destination-page-uuid
   :graph-binding-uuid graph-binding-uuid
   :source-identity {:kind :local :binding-uuid local-source-uuid}
   :scope {:kind :library}})

(def user-collection-binding
  {:zotero-library-key "LOCAL001"
   :destination-page-uuid destination-page-uuid
   :graph-binding-uuid graph-binding-uuid
   :source-identity {:kind :user :id "123456"}
   :scope {:kind :collection :key "COLL0001"}})

(defn- envelope
  ([key title]
   (envelope key title {}))
  ([key title data]
   {:key key
    :library {:type "user" :id 0 :name "Synthetic library"}
    :data (merge {:key key
                  :itemType "journalArticle"
                  :title title
                  :creators []}
                 data)}))

(defn- json
  [value]
  (js/JSON.stringify
   (clj->js (if (and (map? value) (contains? value :zotero-library-key))
              (-> value (dissoc :zotero-library-key)
                  (assoc :libraryKey (:zotero-library-key value)))
              value))))

(deftest binding-sanitization-keeps-only-authorized-identity-and-scope
  (is (nil? (zotero/sanitize-binding (dissoc local-library-binding :destination-page-uuid))))
  (is (nil? (zotero/sanitize-binding (assoc local-library-binding :destination-page-uuid "invalid"))))
  (is (= local-library-binding
         (zotero/sanitize-binding
          (assoc local-library-binding
                 :token "must-not-survive"
                 :profile-path "/private/profile"))))
  (is (= user-collection-binding
         (zotero/sanitize-binding user-collection-binding)))
  (is (nil? (zotero/sanitize-binding
             (assoc-in local-library-binding
                       [:source-identity :binding-uuid]
                       "not-a-uuid"))))
  (is (nil? (zotero/sanitize-binding
             (assoc-in user-collection-binding [:scope :key] "bad")))))

(deftest item-normalization-preserves-bibliography-and-drops-private-fields
  (let [result
        (zotero/normalize-item
         local-library-binding
         (envelope
          "AAAA1111"
          "  A complete paper  "
          {:creators [{:creatorType "author"
                       :firstName "Ada"
                       :lastName "Lovelace"}
                      {:creatorType "author"
                       :name "Synthetic Consortium"}
                      {:creatorType "editor"
                       :name "Ignored Editor"}]
           :date "2026-09-21"
           :publicationTitle "Synthetic Journal"
           :DOI "https://doi.org/10.5555/ROUND79.1"
           :url "https://example.invalid/paper"
           :citationKey "Lovelace2026"
           :path "/private/zotero/storage/paper.pdf"}))]
    (is (= :ready (:status result)))
    (is (= {:classification :bibliographic-item
            :source-key (str "zotero:local:" local-source-uuid
                             ":item:AAAA1111")
            :title "A complete paper"
            :authors "Lovelace, Ada; Synthetic Consortium"
            :date "2026-09-21"
            :publication "Synthetic Journal"
            :type "journal-article"
            :doi "10.5555/round79.1"
            :arxiv nil
            :url "https://example.invalid/paper"
            :citekey "Lovelace2026"}
           (:source-record result)))
    (is (not (re-find #"private|storage"
                      (pr-str result)))))
  (testing "webpage source preserves its known website title"
    (let [record (:source-record
                  (zotero/normalize-item
                   local-library-binding
                   (envelope "WEBP0001" "Synthetic webpage"
                             {:itemType "webpage" :websiteTitle "Synthetic Site"})))]
      (is (= "webpage" (:type record)))
      (is (= "Synthetic Site" (:publication record))))))

(deftest item-normalization-enforces-source-and-top-level-boundaries
  (testing "invalid DOI is a warning and is never used as identity"
    (let [result (zotero/normalize-item
                  local-library-binding
                  (envelope "BBBB2222" "Bad DOI" {:DOI "not-a-doi"}))]
      (is (= :ready (:status result)))
      (is (= [:invalid-doi] (:warnings result)))
      (is (nil? (get-in result [:source-record :doi])))))
  (testing "arXiv versions normalize to the work identity"
    (is (= "2401.12345"
           (get-in (zotero/normalize-item
                    local-library-binding
                    (envelope "CCCC3333" "Preprint"
                              {:itemType "preprint"
                               :archive "arXiv"
                               :archiveLocation "2401.12345v3"}))
                   [:source-record :arxiv]))))
  (testing "a stable identifier can seed a paper when provider title is missing"
    (let [result (zotero/normalize-item
                  local-library-binding
                  (envelope "DDDD3333" "   "
                            {:DOI "https://doi.org/10.5555/MISSING.TITLE"}))]
      (is (= :ready (:status result)))
      (is (= "10.5555/missing.title"
             (get-in result [:source-record :doi])))
      (is (nil? (get-in result [:source-record :title])))))
  (testing "children, missing titles and a different library are ignored"
    (is (= :not-top-level-bibliography
           (:reason (zotero/normalize-item
                     local-library-binding
                     (envelope "DDDD4444" "PDF"
                               {:itemType "attachment"
                                :parentItem "AAAA1111"
                                :path "/private/file.pdf"})))))
    (is (= :missing-title
           (:reason (zotero/normalize-item
                     local-library-binding
                     (envelope "EEEE5555" "   ")))))
    (is (= :library-identity-mismatch
           (:reason (zotero/normalize-item
                     local-library-binding
                     (assoc (envelope "FFFF6666" "Wrong library")
                            :library {:type "group" :id 99})))))))

(deftest source-key-item-key-is-exactly-bound
  (let [source-key (str "zotero:local:" local-source-uuid
                        ":item:AAAA1111")]
    (is (= "AAAA1111"
           (zotero/source-key-item-key local-library-binding source-key)))
    (is (nil? (zotero/source-key-item-key
               user-collection-binding source-key)))
    (is (nil? (zotero/source-key-item-key
               local-library-binding (str source-key "EXTRA"))))
    (is (nil? (zotero/source-key-item-key
               local-library-binding
               (str "zotero:local:" local-source-uuid
                    ":item:aaaa1111"))))))

(deftest ten-thousand-normalizations-stay-a-small-linear-cost
  (let [item (envelope "AAAA1111" "Performance paper"
                       {:creators [{:creatorType "author"
                                    :firstName "Ada"
                                    :lastName "Lovelace"}]
                        :DOI "10.5555/round79.performance"})
        started (.now js/performance)
        accepted (->> (range 10000)
                      (map (fn [_]
                             (zotero/normalize-item
                              local-library-binding item)))
                      (filter #(= :ready (:status %)))
                      count)
        elapsed (- (.now js/performance) started)]
    (js/console.log
     (str "literature-zotero normalize 10000 items: " elapsed "ms"))
    (is (= 10000 accepted))
    ;; This is intentionally loose enough for ordinary CI variance while still
    ;; rejecting a per-item network, storage or database lookup.
    (is (< elapsed 500))))

(deftest-async local-api-request-is-fixed-origin-read-only-and-bounded
  (let [called (atom nil)]
    (p/with-redefs [ipc/ipc
                    (fn [& args]
                      (reset! called args)
                      (p/resolved {:status 200
                                   :ok true
                                   :body (if (string/ends-with? (:url (last args)) "?identity=1")
                                           (json {:libraryKey "LOCAL001"})
                                           "AAAA1111\n")}))]
      (p/let [body (zotero/<local-api-request!
                    local-library-binding (constantly true)
                    "users/0/items/top" "format=keys")
              [method request-id options] @called]
        (is (= "AAAA1111\n" body))
        (is (= :httpRequest method))
        (is (re-matches #"[0-9a-f-]{36}" request-id))
        (is (= {:url "http://127.0.0.1:23119/api/users/0/items/top?format=keys"
                :method "GET"
                :proxy {:type "direct"}
                :headers {"Zotero-API-Version" "3"}
                :returnType "text"
                :includeResponse true
                :timeout 5000}
               options))))))

(deftest-async collection-selection-reads-one-bounded-page-and-keeps-the-library-identity
  (let [calls (atom [])
        rows (mapv (fn [n]
                     {:library {:type "user" :id 0}
                      :data {:key (str "COLL" (+ 1000 n))
                             :name (str "Collection " n)}})
                   (range 51))]
    (p/with-redefs [ipc/ipc
                    (fn [_ _ options]
                      (swap! calls conj options)
                      (p/resolved
                       {:ok true :status 200
                        :body (json
                               (if (string/includes? (:url options) "identity=1")
                                 {:libraryKey "LOCAL001"}
                                 (if (string/includes? (:url options) "start=50")
                                   [(last rows)] rows)))}))]
      (p/let [first-page (zotero/<read-collections-page! (constantly true) 0 nil)
              second-page (zotero/<read-collections-page!
                           (constantly true) (:next-offset first-page)
                           (:zotero-library-key first-page))]
        (is (= 50 (count (:collections first-page))))
        (is (= {:key "COLL1000" :name "Collection 0"}
               (first (:collections first-page))))
        (is (= 50 (:next-offset first-page)))
        (is (= "LOCAL001" (:zotero-library-key first-page)))
        (is (= [{:key "COLL1050" :name "Collection 50"}]
               (:collections second-page)))
        (is (nil? (:next-offset second-page)))
        (is (= "LOCAL001" (:zotero-library-key second-page)))
        (is (= 5 (count @calls)))
        (is (every? #(= "GET" (:method %)) @calls))
        (is (= ["http://127.0.0.1:23119/api/users/0/collections?start=0&limit=51"
                "http://127.0.0.1:23119/api/users/0/collections?start=50&limit=51"]
               (->> @calls (map :url) (filter #(string/includes? % "/collections?")) vec)))))))

(deftest-async collection-selection-rejects-changed-libraries-and-cancelled-reads
  (p/doseq [mode [:changed :cancelled :invalid]]
    (let [calls (atom [])
          active? (atom true)]
      (p/with-redefs [ipc/ipc
                      (fn [_ _ options]
                        (swap! calls conj (:url options))
                        (let [identity? (string/includes? (:url options) "identity=1")]
                          (when (and (not identity?) (= :cancelled mode))
                            (reset! active? false))
                          (p/resolved
                           {:ok true :status 200
                            :body (json
                                   (if identity?
                                     {:libraryKey (if (= :changed mode) "OTHER001" "LOCAL001")}
                                     [{:library {:type "group" :id 12}
                                       :data {:key "COLL1000" :name "Wrong library"}}]))})))]
        (p/let [result (-> (zotero/<read-collections-page! #(deref active?) 50 "LOCAL001")
                          (p/catch #(ex-data %)))]
          (is (= (case mode
                   :changed :literature-zotero/library-identity-mismatch
                   :cancelled :literature-zotero/binding-inactive
                   :invalid :literature-zotero/invalid-collection-response)
                 (:type result)))
          (is (= (if (= :changed mode) 1 2) (count @calls))))))))

(deftest-async explicit-source-record-read-is-single-item-and-read-only
  (let [source-key (str "zotero:local:" local-source-uuid
                        ":item:AAAA1111")
        calls (atom [])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! calls conj [path query])
                      (p/resolved
                       (json [(envelope "AAAA1111" "Updated paper"
                                        {:DOI "10.5555/updated"})])))]
      (p/let [result (zotero/<read-source-record!
                             local-library-binding source-key
                             (constantly true))]
        (is (= :ready (:status result)))
        (is (= "Updated paper" (get-in result [:source-record :title])))
        (is (= [["users/0/items/top" "itemKey=AAAA1111&limit=1"]]
               @calls))))))

(deftest-async explicit-source-record-read-fails-closed
  (let [source-key (str "zotero:local:" local-source-uuid
                        ":item:AAAA1111")
        calls (atom 0)]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [& _]
                      (swap! calls inc)
                      (p/resolved "[]"))]
      (p/let [inactive (zotero/<read-source-record!
                              local-library-binding source-key
                              (constantly false))
              mismatched (zotero/<read-source-record!
                          user-collection-binding source-key
                          (constantly true))
              missing (zotero/<read-source-record!
                       local-library-binding source-key
                       (constantly true))]
        (is (= {:status :cancelled :reason :binding-inactive} inactive))
        (is (= {:status :blocked :reason :invalid-source-key} mismatched))
        (is (= {:status :missing :reason :source-item-unavailable} missing))
        (is (= 1 @calls))))))

(deftest-async explicit-source-record-read-rejects-unexpected-or-multiple-items
  (let [source-key (str "zotero:local:" local-source-uuid
                        ":item:AAAA1111")
        bodies (atom [(json [(envelope "BBBB2222" "Wrong item")])
                      (json [(envelope "AAAA1111" "First")
                             (envelope "AAAA1111" "Duplicate")])])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [& _]
                      (let [body (first @bodies)]
                        (swap! bodies subvec 1)
                        (p/resolved body)))]
      (p/let [unexpected
              (-> (zotero/<read-source-record!
                   local-library-binding source-key (constantly true))
                  (p/then (constantly :unexpected-success))
                  (p/catch #(-> % ex-data :type)))
              multiple
              (-> (zotero/<read-source-record!
                   local-library-binding source-key (constantly true))
                  (p/then (constantly :unexpected-success))
                  (p/catch #(-> % ex-data :type)))]
        (is (= :literature-zotero/unexpected-item unexpected))
        (is (= :literature-zotero/invalid-item-response multiple))))))

(defn- <pdf-attachments!
  [binding source-key still-authorized?]
  (p/then (p/resolved nil)
          (fn [_]
            (zotero/<read-pdf-attachments! binding source-key still-authorized?))))

(deftest-async pdf-child-read-keeps-multiple-local-files-and-authorized-parent-scope
  (let [source-key "zotero:user:123456:item:AAAA1111"
        children [(envelope "BBBB2222" "Stored PDF"
                            {:itemType "attachment" :parentItem "AAAA1111"
                             :contentType "application/pdf" :linkMode "imported_file"
                             :filename "stored  paper.pdf"})
                  (envelope "CCCC3333" "Linked PDF"
                            {:itemType "attachment" :parentItem "AAAA1111"
                             :contentType "application/pdf" :linkMode "linked_file"
                             :path "/synthetic/linked/linked#? paper.pdf"})
                  (envelope "DDDD4444" "Snapshot"
                            {:itemType "attachment" :parentItem "AAAA1111"
                             :contentType "text/html" :linkMode "imported_url"})
                  (envelope "EEEE5555" "Note" {:itemType "note" :parentItem "AAAA1111"})
                  (envelope "FFFF6666" "PDF web link"
                            {:itemType "attachment" :parentItem "AAAA1111"
                             :contentType "application/pdf" :linkMode "linked_url"})
                  (envelope "GGGG7777" "Downloaded PDF"
                            {:itemType "attachment" :parentItem "AAAA1111"
                             :contentType "application/pdf" :linkMode "imported_url"
                             :filename "downloaded.pdf"})]
        calls (atom [])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! calls conj [path query])
                      (p/resolved
                       (cond
                         (= path "users/0/collections/COLL0001/items/top")
                         (json [(envelope "AAAA1111" "Parent")])

                         (= query "format=keys")
                         "BBBB2222\nCCCC3333\nDDDD4444\nEEEE5555\nFFFF6666\nGGGG7777\n"

                         (= path "users/0/items/AAAA1111/children") (json children)
                         (= path "users/0/items/BBBB2222/file/view/url")
                         "file:///synthetic/storage/stored%20%20paper.pdf"
                         (= path "users/0/items/CCCC3333/file/view/url")
                         "file:///synthetic/linked/linked%23%3F%20paper.pdf"
                         (= path "users/0/items/GGGG7777/file/view/url")
                         "file:///synthetic/storage/downloaded.pdf"
                         :else (throw (ex-info "Unexpected child read" {:path path :query query})))))
                   ipc/ipc (fn [& _]
                             (throw (ex-info "PDF child metadata must not read native file bytes" {})))]
      (p/let [result (<pdf-attachments!
                     user-collection-binding source-key (constantly true))]
        (is (= :ready (:status result)))
        (is (= "AAAA1111" (:parent-item-key result)))
        (is (= [{:item-key "BBBB2222" :parent-item-key "AAAA1111"
                 :title "Stored PDF"
                 :filename "stored  paper.pdf" :content-type "application/pdf"
                 :link-mode "imported_file"
                 :file-url "file:///synthetic/storage/stored%20%20paper.pdf"}
                {:item-key "CCCC3333" :parent-item-key "AAAA1111"
                 :title "Linked PDF"
                 :filename "linked#? paper.pdf" :content-type "application/pdf"
                 :link-mode "linked_file" :file-url "file:///synthetic/linked/linked%23%3F%20paper.pdf"}
                {:item-key "GGGG7777" :parent-item-key "AAAA1111"
                 :title "Downloaded PDF"
                 :filename "downloaded.pdf" :content-type "application/pdf"
                 :link-mode "imported_url" :file-url "file:///synthetic/storage/downloaded.pdf"}]
               (:attachments result)))
        (is (= [["users/0/collections/COLL0001/items/top" "itemKey=AAAA1111&limit=1"]
                ["users/0/items/AAAA1111/children" "format=keys"]
                ["users/0/items/AAAA1111/children"
                 "itemKey=BBBB2222%2CCCCC3333%2CDDDD4444%2CEEEE5555%2CFFFF6666%2CGGGG7777&limit=50"]
                ["users/0/items/BBBB2222/file/view/url" nil]
                ["users/0/items/CCCC3333/file/view/url" nil]
                ["users/0/items/GGGG7777/file/view/url" nil]]
               @calls))))))

(deftest-async pdf-child-read-batches-explicit-keys-at-fifty
  (let [source-key (str "zotero:local:" local-source-uuid ":item:AAAA1111")
        keys (mapv #(str "K" (.padStart (str %) 7 "0")) (range 1 52))
        batches (atom [])
        file-requests (atom 0)]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (p/resolved
                       (cond
                         (= path "users/0/items/top")
                         (json [(envelope "AAAA1111" "Parent")])

                         (= query "format=keys") (string/join "\n" keys)

                         (= path "users/0/items/AAAA1111/children")
                         (let [batch (-> (re-find #"itemKey=([^&]+)" query)
                                         second js/decodeURIComponent
                                         (string/split #","))]
                           (swap! batches conj batch)
                           (json (mapv #(envelope % "PDF"
                                                 {:itemType "attachment" :parentItem "AAAA1111"
                                                  :contentType "application/pdf" :linkMode "imported_file"
                                                  :filename (str % ".pdf")})
                                       batch)))

                         :else
                         (do (swap! file-requests inc)
                             (str "file:///synthetic/" (nth (string/split path #"/") 3) ".pdf")))))]
      (p/let [result (<pdf-attachments!
                     local-library-binding source-key (constantly true))]
        (is (= :ready (:status result)))
        (is (= keys (mapv :item-key (:attachments result))))
        (is (= [50 1] (mapv count @batches)))
        (is (= 51 @file-requests))))))

(deftest-async pdf-child-read-cancels-after-each-in-flight-request
  (let [source-key (str "zotero:local:" local-source-uuid ":item:AAAA1111")]
    (p/loop [stops (seq [1 2 3 4])]
      (when-let [stop-at (first stops)]
        (p/let [_ (let [authorized? (atom true)
                       calls (atom 0)]
                   (p/with-redefs [zotero/<local-api-request!
                                   (fn [_binding _still-authorized? path query]
                                     (when (= stop-at (swap! calls inc))
                                       (reset! authorized? false))
                                     (p/resolved
                                      (cond
                                        (= path "users/0/items/top")
                                        (json [(envelope "AAAA1111" "Parent")])
                                        (= query "format=keys") "BBBB2222\nCCCC3333\n"
                                        (= path "users/0/items/AAAA1111/children")
                                        (json (mapv #(envelope % "PDF"
                                                              {:itemType "attachment" :parentItem "AAAA1111"
                                                               :contentType "application/pdf" :linkMode "imported_file"})
                                                    ["BBBB2222" "CCCC3333"]))
                                        :else "file:///synthetic/paper.pdf")))]
                     (p/let [result (<pdf-attachments!
                                    local-library-binding source-key #(deref authorized?))]
                       (is (= {:status :cancelled :reason :binding-inactive} result))
                       (is (= stop-at @calls)))))]
          (p/recur (next stops)))))))

(deftest-async pdf-child-read-rejects-unrequested-keys-and-wrong-parent
  (let [source-key (str "zotero:local:" local-source-uuid ":item:AAAA1111")]
    (p/loop [cases (seq [["CCCC3333" "AAAA1111"] ["BBBB2222" "DDDD4444"]])]
      (when-let [[child-key parent-key] (first cases)]
        (p/let [_ (let [calls (atom [])]
                   (p/with-redefs [zotero/<local-api-request!
                                   (fn [_binding _still-authorized? path query]
                                     (swap! calls conj [path query])
                                     (p/resolved
                                      (cond
                                        (= path "users/0/items/top")
                                        (json [(envelope "AAAA1111" "Parent")])
                                        (= query "format=keys") "BBBB2222\n"
                                        :else (json [(envelope child-key "PDF"
                                                              {:itemType "attachment" :parentItem parent-key
                                                               :contentType "application/pdf" :linkMode "imported_file"})]))))]
                     (p/let [result (-> (<pdf-attachments!
                                        local-library-binding source-key (constantly true))
                                       (p/then (constantly :unexpected-success))
                                       (p/catch #(-> % ex-data :type)))]
                       (is (= :literature-zotero/unexpected-item result))
                       (is (= 3 (count @calls))))))]
          (p/recur (next cases)))))))

(deftest-async pdf-child-read-fails-closed-before-listing-attachments
  (let [source-key (str "zotero:local:" local-source-uuid ":item:AAAA1111")
        calls (atom [])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! calls conj [path query])
                      (p/resolved "[]"))]
      (p/let [inactive (<pdf-attachments!
                       local-library-binding source-key (constantly false))
              mismatched (<pdf-attachments!
                          user-collection-binding source-key (constantly true))
              invalid (<pdf-attachments!
                       (dissoc local-library-binding :destination-page-uuid)
                       source-key (constantly true))
              missing (<pdf-attachments!
                       local-library-binding source-key (constantly true))]
        (is (= {:status :cancelled :reason :binding-inactive} inactive))
        (is (= {:status :blocked :reason :invalid-source-key} mismatched))
        (is (= {:status :blocked :reason :invalid-binding} invalid))
        (is (= {:status :missing :reason :source-item-unavailable} missing))
        (is (= [["users/0/items/top" "itemKey=AAAA1111&limit=1"]] @calls))))))

(deftest-async pdf-child-read-allows-metadata-empty-parent-and-keeps-source-boundaries
  (let [source-key "zotero:user:123456:item:AAAA1111"
        metadata-empty-parent (envelope "AAAA1111" "  "
                                        {:DOI "" :url "" :archive ""
                                         :archiveLocation "" :extra ""})
        parent-body (atom (json [metadata-empty-parent]))
        calls (atom [])
        native-calls (atom 0)
        import-calls (atom 0)
        rejected-parents
        [[(json [(assoc metadata-empty-parent :library {:type "group" :id 99})])
          {:status :ignored :reason :library-identity-mismatch}]
         [(json [(envelope "AAAA1111" "" {:itemType "attachment"})])
          {:status :ignored :reason :not-top-level-bibliography}]
         [(json [(envelope "AAAA1111" "" {:itemType "note"})])
          {:status :ignored :reason :not-top-level-bibliography}]
         [(json [(envelope "AAAA1111" "" {:itemType "annotation"})])
          {:status :ignored :reason :not-top-level-bibliography}]
         [(json [(envelope "AAAA1111" "" {:itemType nil})])
          {:status :ignored :reason :missing-item-type}]
         ["[]" {:status :missing :reason :source-item-unavailable}]]]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! calls conj [path query])
                      (p/resolved
                       (cond
                         (= path "users/0/collections/COLL0001/items/top") @parent-body
                         (= query "format=keys") "BBBB2222\n"
                         (= path "users/0/items/AAAA1111/children")
                         (json [(envelope "BBBB2222" "PDF"
                                          {:itemType "attachment" :parentItem "AAAA1111"
                                           :contentType "application/pdf" :linkMode "linked_file"
                                           :path "/synthetic/metadata-empty-parent.pdf"})])
                         (= path "users/0/items/BBBB2222/file/view/url")
                         "file:///synthetic/metadata-empty-parent.pdf"
                         :else (throw (ex-info "Unexpected child read" {:path path :query query})))))
                   ipc/ipc (fn [& _]
                             (swap! native-calls inc)
                             (throw (ex-info "PDF metadata must not read native file bytes" {})))
                   zotero/<import-item-keys! (fn [& _]
                                              (swap! import-calls inc)
                                              (throw (ex-info "PDF metadata must not register source records" {})))]
      (p/let [result (<pdf-attachments!
                     user-collection-binding source-key (constantly true))]
        (is (= {:status :ready :parent-item-key "AAAA1111"
                :attachments [{:item-key "BBBB2222" :parent-item-key "AAAA1111"
                               :filename "metadata-empty-parent.pdf" :title "PDF" :content-type "application/pdf"
                               :link-mode "linked_file" :file-url "file:///synthetic/metadata-empty-parent.pdf"}]}
               result)
            "An authorized bibliography parent needs no title, DOI or arXiv to read its PDF children")
        (is (= [["users/0/collections/COLL0001/items/top" "itemKey=AAAA1111&limit=1"]
                ["users/0/items/AAAA1111/children" "format=keys"]
                ["users/0/items/AAAA1111/children" "itemKey=BBBB2222&limit=50"]
                ["users/0/items/BBBB2222/file/view/url" nil]]
               @calls))
        (p/let [_ (p/loop [remaining (seq rejected-parents)]
                    (when-let [[body expected] (first remaining)]
                      (reset! parent-body body)
                      (reset! calls [])
                      (p/let [rejected (<pdf-attachments!
                                       user-collection-binding source-key (constantly true))]
                        (is (= expected (select-keys rejected [:status :reason])))
                        (is (= [["users/0/collections/COLL0001/items/top" "itemKey=AAAA1111&limit=1"]]
                               @calls)
                            "Rejected parents must not list children")
                        (p/recur (next remaining)))))]
          (is (zero? @native-calls))
          (is (zero? @import-calls)))))))

(deftest-async standalone-pdf-reader-uses-scoped-key-and-transient-file-url
  (let [source-key "zotero:user:123456:item:BBBB2222"
        context {:kind :standalone :item-key "BBBB2222" :source-key source-key}
        file-url "file:///synthetic/%E6%96%87%E7%8C%AE%20paper%23draft%3Fpart%25.pdf"
        native-calls (atom 0)
        import-calls (atom 0)]
    (p/let [_ (p/loop [modes (seq ["imported_file" "imported_url" "linked_file"])]
                (when-let [mode (first modes)]
                  (p/let [_ (let [requests (atom [])
                                 item (envelope "BBBB2222" ""
                                                {:itemType "attachment" :contentType "application/pdf"
                                                 :linkMode mode})]
                             (p/with-redefs [zotero/<local-api-request!
                                             (fn [_binding _still-authorized? path query]
                                               (swap! requests conj [path query])
                                               (p/resolved
                                                (cond
                                                  (= path "users/0/collections/COLL0001/items/top") (json [item])
                                                  (= path "users/0/items/BBBB2222/file/view/url") file-url
                                                  :else (throw (ex-info "Unexpected standalone read" {:path path :query query})))))
                                            ipc/ipc (fn [& _]
                                                      (swap! native-calls inc)
                                                      (throw (ex-info "Reader must not read native bytes" {})))
                                            zotero/<import-item-keys! (fn [& _]
                                                                       (swap! import-calls inc)
                                                                       (throw (ex-info "Reader must not register a source" {})))]
                               (p/let [result (p/then (p/resolved nil)
                                                     (fn [_]
                                                       (apply zotero/<read-pdf-attachments!
                                                              [user-collection-binding source-key
                                                               (constantly true) context])))]
                                 (is (= :ready (:status result)))
                                 (is (= [{:item-key "BBBB2222" :filename "文献 paper#draft?part%.pdf"
                                          :content-type "application/pdf" :link-mode mode :file-url file-url}]
                                        (mapv #(dissoc % :parent-item-key) (:attachments result))))
                                 (is (nil? (:parent-item-key (first (:attachments result)))))
                                 (is (= [["users/0/collections/COLL0001/items/top" "itemKey=BBBB2222&limit=1"]
                                         ["users/0/items/BBBB2222/file/view/url" nil]]
                                        @requests))
                                 (is (= :not-top-level-bibliography
                                        (:reason (zotero/normalize-item user-collection-binding item)))))))]
                    (p/recur (next modes)))))]
      (is (zero? @native-calls))
      (is (zero? @import-calls)))))

(deftest-async standalone-pdf-reader-keeps-scope-type-and-in-flight-authorization-boundaries
  (let [source-key "zotero:user:123456:item:BBBB2222"
        context {:kind :standalone :item-key "BBBB2222" :source-key source-key}
        item (envelope "BBBB2222" ""
                       {:itemType "attachment" :contentType "application/pdf" :linkMode "imported_file"})
        parent-body (atom (json [item]))
        requests (atom [])
        authorized? (atom true)
        stop-at (atom nil)
        read! (fn [binding key]
                (-> (p/then (p/resolved nil)
                            (fn [_]
                              (apply zotero/<read-pdf-attachments!
                                     [binding key #(deref authorized?) context])))
                    (p/catch (fn [error] {:rejected (:type (ex-data error))}))))
        cases [[(json [(assoc item :library {:type "group" :id 99})])
                {:status :ignored :reason :library-identity-mismatch}]
               [(json [(assoc-in item [:data :parentItem] "AAAA1111")])
                {:status :ignored :reason :not-top-level-bibliography}]
               [(json [(assoc-in item [:data :contentType] "image/png")])
                {:status :ignored :reason :not-top-level-bibliography}]
               [(json [(assoc-in item [:data :itemType] "note")])
                {:status :ignored :reason :not-top-level-bibliography}]
               [(json [(assoc-in item [:data :itemType] "annotation")])
                {:status :ignored :reason :not-top-level-bibliography}]
               [(json [(assoc-in item [:data :linkMode] "linked_url")])
                {:status :ignored :reason :not-top-level-bibliography}]
               ["[]" {:status :missing :reason :source-item-unavailable}]
               [(json [(assoc item :key "CCCC3333" :data (assoc (:data item) :key "CCCC3333"))])
                {:rejected :literature-zotero/unexpected-item}]
               [(json [item item]) {:rejected :literature-zotero/invalid-item-response}]]]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! requests conj [path query])
                      (when (= @stop-at (count @requests))
                        (reset! authorized? false))
                      (p/resolved
                       (cond
                         (= path "users/0/collections/COLL0001/items/top") @parent-body
                         (= path "users/0/items/BBBB2222/file/view/url") "file:///synthetic/standalone.pdf"
                         :else (throw (ex-info "Unexpected standalone read" {:path path :query query})))))]
      (p/let [_ (p/loop [remaining (seq cases)]
                  (when-let [[body expected] (first remaining)]
                    (reset! parent-body body)
                    (reset! requests [])
                    (p/let [result (read! user-collection-binding source-key)]
                      (is (= expected (select-keys result [:status :reason :rejected])))
                      (is (= [["users/0/collections/COLL0001/items/top" "itemKey=BBBB2222&limit=1"]]
                             @requests))
                      (p/recur (next remaining)))))]
        (reset! requests [])
        (p/let [invalid-binding (read! (dissoc user-collection-binding :destination-page-uuid) source-key)
                mismatched-key (read! user-collection-binding
                                      (str "zotero:local:" local-source-uuid ":item:BBBB2222"))]
          (is (= {:status :blocked :reason :invalid-binding} invalid-binding))
          (is (= {:status :blocked :reason :invalid-source-key} mismatched-key))
          (is (empty? @requests))
          (reset! authorized? false)
          (p/let [inactive (read! user-collection-binding source-key)]
            (is (= {:status :cancelled :reason :binding-inactive} inactive))
            (is (empty? @requests))
            (reset! parent-body (json [item]))
            (p/loop [stops (seq [1 2])]
              (when-let [stop (first stops)]
                (reset! requests [])
                (reset! authorized? true)
                (reset! stop-at stop)
                (p/let [cancelled (read! user-collection-binding source-key)]
                  (is (= {:status :cancelled :reason :binding-inactive} cancelled))
                  (is (= stop (count @requests)))
                  (p/recur (next stops)))))))))))

(deftest-async notifier-request-is-fixed-origin-read-only-and-long-polled
  (let [called (atom nil)]
    (p/with-redefs [ipc/ipc
                    (fn [& args]
                      (reset! called args)
                      (p/resolved
                       {:status 200
                        :ok true
                        :body (json {:session "ROUND80SESSION00000000001"
                                     :cursor 7
                                     :rescan false :zotero-library-key "LOCAL001"
                                     :keys ["AAAA1111"]})}))]
      (p/let [result (zotero/<notifier-request!
                       {:session "ROUND80SESSION00000000001"
                        :cursor 6})
              [method request-id options] @called]
        (is (= {:session "ROUND80SESSION00000000001"
                :cursor 7
                :rescan false :zotero-library-key "LOCAL001"
                :keys ["AAAA1111"]}
               result))
        (is (= :httpRequest method))
        (is (re-matches #"[0-9a-f-]{36}" request-id))
        (is (= {:url (str "http://127.0.0.1:23119/"
                          "logseq-literature/changes?"
                          "session=ROUND80SESSION00000000001&ack=6")
                :method "GET"
                :proxy {:type "direct"}
                :returnType "text"
                :includeResponse true
                :abortable true
                :timeout 30000}
               options))))))

(deftest-async notifier-request-aborts-the-main-process-fetch
  (let [started (p/deferred)
        reject-request (atom nil)
        request-id (atom nil)
        abort-id (atom nil)
        controller (js/AbortController.)]
    (p/with-redefs [ipc/ipc
                    (fn [method id & [options]]
                      (case method
                        :httpRequest
                        (do
                          (reset! request-id id)
                          (is (true? (:abortable options)))
                          (p/resolve! started true)
                          (p/create (fn [_resolve reject]
                                      (reset! reject-request reject))))

                        :httpRequestAbort
                        (do
                          (reset! abort-id id)
                          (when-let [reject @reject-request]
                            (reject (ex-info "synthetic abort"
                                             {:type :synthetic-abort})))
                          (p/resolved nil))))]
      (let [request (zotero/<notifier-request!
                     {:session nil :cursor 0}
                     (.-signal controller))]
        (p/let [_ started
                _ (.abort controller)
                outcome (-> request
                            (p/then (fn [_] :unexpected-success))
                            (p/catch #(-> % ex-data :type)))]
          (is (= :synthetic-abort outcome))
          (is (= @request-id @abort-id)))))))

(deftest-async scope-import-uses-a-key-snapshot-and-existing-worker-boundary
  (let [requests (atom [])
        registrations (atom [])
        bodies (atom [(p/resolved "AAAA1111\nBBBB2222\nCCCC3333\n")
                      (p/resolved
                       (json [(envelope "AAAA1111" "First"
                                        {:DOI "10.5555/round79.first"})
                              (envelope "BBBB2222" "No identifier")
                              (envelope "CCCC3333" "Child"
                                        {:itemType "attachment"
                                         :parentItem "AAAA1111"
                                         :path "/private/child.pdf"})]))])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! requests conj [path query])
                      (let [response (first @bodies)]
                        (swap! bodies subvec 1)
                        response))]
      (p/let [result
              (zotero/<import-scope!
               local-library-binding
               (constantly true)
               (fn [binding-uuid record]
                 (swap! registrations conj [binding-uuid record])
                 (p/resolved
                  (if (:doi record)
                    {:status :created
                     :paper-uuid #uuid "79000000-0000-8000-8000-000000000010"}
                    {:status :manual :reason :missing-stable-identifier}))))]
        (is (= :complete (:status result)))
        (is (= 3 (:requested-count result)))
        (is (= 3 (:fetched-count result)))
        (is (= {:created 1 :manual 1} (:result-counts result)))
        (is (= [:not-top-level-bibliography]
               (mapv :reason (:ignored result))))
        (is (= [["users/0/items/top" "format=keys"]
                ["users/0/items/top"
                 "itemKey=AAAA1111%2CBBBB2222%2CCCCC3333&limit=50"]]
               @requests))
        (is (= [graph-binding-uuid graph-binding-uuid]
               (mapv first @registrations)))
        (is (not (re-find #"private/child" (pr-str result))))))))

(deftest-async manual-preview-reads-one-metadata-batch-per-page
  (let [keys (mapv #(str "PAGE" (subs (str (+ 10000 %)) 1)) (range 55))
        calls (atom [])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! calls conj [path query])
                      (p/resolved
                       (if (= query "format=keys")
                         (string/join "\n" keys)
                         (json (mapv #(envelope % "Synthetic book" {:itemType "book"})
                                     (if (string/includes? query (first keys))
                                       (take 50 keys) (drop 50 keys)))))))]
      (p/let [first-page (zotero/<read-scope-page! local-library-binding 0 (constantly true))
              second-page (zotero/<read-scope-page! local-library-binding 50 (constantly true))]
        (is (= [:complete 50 50]
               [(:status first-page) (count (:items first-page)) (:next-offset first-page)]))
        (is (= [:complete 5 nil]
               [(:status second-page) (count (:items second-page)) (:next-offset second-page)]))
        (is (every? #(= "book" (:type %)) (:items first-page)))
        (is (= ["format=keys"
                (str "itemKey=" (js/encodeURIComponent (string/join "," (take 50 keys))) "&limit=50")
                "format=keys"
                (str "itemKey=" (js/encodeURIComponent (string/join "," (drop 50 keys))) "&limit=50")]
               (mapv second @calls)))
        (is (every? #(= "users/0/items/top" (first %)) @calls))))))

(deftest-async batch-dispatches-parent-and-standalone-pdf-without-changing-source-records
  (let [ready (envelope "AAAA1111" "Ready parent"
                        {:DOI "10.5555/round438.parent"
                         :path "/synthetic/ready-parent.pdf"
                         :filename "ready-parent.pdf"})
        metadata-empty (envelope "BBBB2222" " " {:DOI "" :url "" :extra ""})
        stored (envelope "CCCC3333" "Stored PDF"
                         {:itemType "attachment" :contentType "application/pdf"
                          :linkMode "imported_file" :filename "stored.pdf"})
        linked (envelope "DDDD4444" "Linked PDF"
                         {:itemType "attachment" :contentType "application/pdf"
                          :linkMode "linked_file" :path "/synthetic/linked.pdf"})
        downloaded (envelope "EEEE5555" "Downloaded PDF"
                             {:itemType "attachment" :contentType "application/pdf"
                              :linkMode "imported_url" :filename "downloaded.pdf"})
        ignored [(envelope "FFFF6666" "Image"
                           {:itemType "attachment" :contentType "image/png"
                            :linkMode "imported_file"})
                 (envelope "GGGG7777" "Note" {:itemType "note"})
                 (envelope "HHHH8888" "Annotation" {:itemType "annotation"})
                 (envelope "IIII9999" "Web PDF"
                           {:itemType "attachment" :contentType "application/pdf"
                            :linkMode "linked_url"})
                 (envelope "JJJJ0000" "Child PDF"
                           {:itemType "attachment" :parentItem "AAAA1111"
                            :contentType "application/pdf" :linkMode "imported_file"})
                 (assoc stored :key "KKKK1111"
                        :library {:type "group" :id 99}
                        :data (assoc (:data stored) :key "KKKK1111"))
                 (envelope "LLLL2222" "" {:itemType nil})]
        envelopes (into [ready metadata-empty stored linked downloaded] ignored)
        keys (mapv :key envelopes)
        requests (atom [])
        registrations (atom [])
        native-calls (atom 0)
        expected-record {:classification :bibliographic-item
                         :source-key "zotero:user:123456:item:AAAA1111"
                         :title "Ready parent" :authors nil :date nil
                         :publication nil :type "journal-article"
                         :doi "10.5555/round438.parent" :arxiv nil :url nil :citekey nil}]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! requests conj [path query])
                      (p/resolved (json envelopes)))
                   ipc/ipc (fn [& _]
                             (swap! native-calls inc)
                             (throw (ex-info "Batch dispatch must not read attachment bytes" {})))]
      (p/let [result (zotero/<import-item-keys!
                     user-collection-binding keys (constantly true)
                     (fn [binding-uuid record & [attachment-context]]
                       (swap! registrations conj [binding-uuid record attachment-context])
                       (p/resolved {:status (if record :created :manual)})))]
        (is (= :complete (:status result)))
        (is (= 12 (:requested-count result) (:fetched-count result)))
        (is (= {:created 1 :manual 4} (:result-counts result)))
        (is (= [[graph-binding-uuid expected-record
                 {:kind :parent :item-key "AAAA1111"
                  :source-key "zotero:user:123456:item:AAAA1111"}]
                [graph-binding-uuid nil
                 {:kind :parent :item-key "BBBB2222"
                  :source-key "zotero:user:123456:item:BBBB2222"}]
                [graph-binding-uuid nil
                 {:kind :standalone :item-key "CCCC3333"
                  :source-key "zotero:user:123456:item:CCCC3333"}]
                [graph-binding-uuid nil
                 {:kind :standalone :item-key "DDDD4444"
                  :source-key "zotero:user:123456:item:DDDD4444"}]
                [graph-binding-uuid nil
                 {:kind :standalone :item-key "EEEE5555"
                  :source-key "zotero:user:123456:item:EEEE5555"}]]
               @registrations))
        (is (= [:not-top-level-bibliography :not-top-level-bibliography
                :not-top-level-bibliography :not-top-level-bibliography
                :not-top-level-bibliography :library-identity-mismatch :missing-item-type]
               (mapv :reason (:ignored result))))
        (is (= [["users/0/collections/COLL0001/items/top"
                 (str "itemKey=" (js/encodeURIComponent (string/join "," keys)) "&limit=50")]]
               @requests))
        (is (= expected-record (:source-record (zotero/normalize-item user-collection-binding ready))))
        (is (= :missing-title (:reason (zotero/normalize-item user-collection-binding metadata-empty))))
        (is (= :not-top-level-bibliography (:reason (zotero/normalize-item user-collection-binding stored))))
        (is (zero? @native-calls))))))

(deftest-async batch-attachment-dispatch-does-not-fallback-from-invalid-binding-key-or-scope
  (let [requests (atom [])
        registrations (atom 0)
        response (atom "[]")
        register! (fn [& _]
                    (swap! registrations inc)
                    (p/resolved {:status :manual}))]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? path query]
                      (swap! requests conj [path query])
                      (p/resolved @response))]
      (p/let [invalid-binding (zotero/<import-item-keys!
                              (dissoc user-collection-binding :destination-page-uuid)
                              ["AAAA1111"] (constantly true) register!)
              invalid-key (zotero/<import-item-keys!
                          user-collection-binding ["bad"] (constantly true) register!)]
        (is (= {:status :blocked :reason :invalid-binding} invalid-binding))
        (is (= {:status :blocked :reason :invalid-item-keys} invalid-key))
        (is (empty? @requests))
        (is (zero? @registrations))
        (p/let [outside-scope (zotero/<import-item-keys!
                              user-collection-binding ["AAAA1111"]
                              (constantly true) register!)]
          (is (= :complete (:status outside-scope)))
          (is (= 0 (:fetched-count outside-scope)))
          (is (= [["users/0/collections/COLL0001/items/top" "itemKey=AAAA1111&limit=50"]]
                 @requests))
          (is (zero? @registrations))
          (reset! response (json [(envelope "BBBB2222" "Unrequested PDF"
                                           {:itemType "attachment" :contentType "application/pdf"
                                            :linkMode "imported_file"})]))
          (p/let [unrequested (-> (zotero/<import-item-keys!
                                   user-collection-binding ["AAAA1111"]
                                   (constantly true) register!)
                                  (p/then (fn [result] {:returned result}))
                                  (p/catch (fn [error] {:error (ex-data error)})))]
            (is (= :literature-zotero/unexpected-item (get-in unrequested [:error :type])))
            (is (nil? (:returned unrequested)))
            (is (zero? @registrations))))))))

(deftest-async standalone-pdf-dispatch-failure-keeps-the-notifier-positive-cursor
  (let [cursor {:session "SYNTHETICPDFSESSION" :cursor 6}
        notification {:session "SYNTHETICPDFSESSION" :cursor 7
                      :rescan false :zotero-library-key "LOCAL001" :keys ["BBBB2222"]}
        context {:kind :standalone :item-key "BBBB2222"
                 :source-key (str "zotero:local:" local-source-uuid ":item:BBBB2222")}
        reads (atom [])
        attempts (atom [])
        fail-save? (atom true)
        saved? (atom false)
        register! (fn [_ record & [attachment-context]]
                    (swap! attempts conj [record attachment-context])
                    (if @fail-save?
                      (p/rejected (ex-info "Synthetic attachment save failed"
                                           {:type :synthetic-attachment-failure}))
                      (do (reset! saved? true)
                          (p/resolved {:status :manual}))))]
    (p/with-redefs [zotero/<notifier-request!
                    (fn [state]
                      (swap! reads conj state)
                      (p/resolved notification))
                   zotero/<local-api-request!
                    (fn [& _]
                      (p/resolved
                       (json [(envelope "BBBB2222" "Standalone PDF"
                                        {:itemType "attachment" :contentType "application/pdf"
                                         :linkMode "linked_file" :path "/synthetic/standalone.pdf"})])))]
      (p/let [first-attempt (-> (zotero/<process-notifier-cycle!
                                local-library-binding cursor (constantly true) register!)
                               (p/then (fn [result] {:returned result}))
                               (p/catch (fn [error] {:error (ex-data error)})))]
        (is (= :synthetic-attachment-failure (get-in first-attempt [:error :type])))
        (is (nil? (:returned first-attempt)))
        (is (= [[nil context]] @attempts))
        (is (false? @saved?))
        (reset! fail-save? false)
        (p/let [resumed (zotero/<process-notifier-cycle!
                         local-library-binding cursor (constantly true) register!)]
          (is (= [cursor cursor] @reads))
          (is (= :complete (:status resumed)))
          (is (= {:session "SYNTHETICPDFSESSION" :cursor 7} (:notification-state resumed)))
          (is (= {:manual 1} (get-in resumed [:import :result-counts])))
          (is (= [[nil context] [nil context]] @attempts))
          (is (true? @saved?)))))))

(deftest-async explicit-keys-are-deduplicated-and-batched-at-fifty
  (let [keys (mapv #(str "K" (.padStart (str %) 7 "0")) (range 1 52))
        requests (atom [])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [_binding _still-authorized? _path query]
                      (swap! requests conj query)
                      (let [encoded (second (re-find #"itemKey=([^&]+)" query))
                            requested (-> encoded
                                          js/decodeURIComponent
                                          (.split ",")
                                          js->clj)]
                        (p/resolved
                         (json (mapv #(envelope % (str "Paper " %)
                                               {:DOI (str "10.5555/" %)})
                                     requested)))))]
      (p/let [result
              (zotero/<import-item-keys!
               local-library-binding
               (conj keys (first keys))
               (constantly true)
               (fn [_ _] (p/resolved {:status :created})))]
        (is (= :complete (:status result)))
        (is (= 51 (:requested-count result)))
        (is (= 2 (count @requests)))
        (is (= 50 (count (re-seq #"K\d{7}" (first @requests)))))
        (is (= 1 (count (re-seq #"K\d{7}" (second @requests)))))))))

(deftest-async changed-authorization-stops-before-the-next-graph-write
  (let [authorized? (atom true)
        registrations (atom [])]
    (p/with-redefs [zotero/<local-api-request!
                    (fn [& _]
                      (p/resolved
                       (json [(envelope "AAAA1111" "First"
                                        {:DOI "10.5555/round79.1"})
                              (envelope "BBBB2222" "Second"
                                        {:DOI "10.5555/round79.2"})])))]
      (p/let [result
              (zotero/<import-item-keys!
               local-library-binding
               ["AAAA1111" "BBBB2222"]
               #(true? @authorized?)
               (fn [_ record]
                 (swap! registrations conj (:source-key record))
                 (reset! authorized? false)
                 (p/resolved {:status :created})))]
        (is (= :cancelled (:status result)))
        (is (= :binding-inactive (:reason result)))
        (is (= 1 (count @registrations)))
        (is (= {:created 1} (:result-counts result)))))))

(deftest-async deferred-item-stops-import-and-retries-the-unacknowledged-notification
  (let [keys ["AAAA1111" "BBBB2222" "CCCC3333"]
        notification {:session "SYNTHETICREPAIRSESSION"
                      :cursor 9 :rescan false :zotero-library-key "LOCAL001" :keys keys}
        cursor {:session nil :cursor 0}
        reads (atom [])
        attempts (atom [])
        imported (atom #{})
        repairing? (atom true)
        register! (fn [_ record]
                    (let [title (:title record)]
                      (swap! attempts conj title)
                      (p/resolved
                       (cond
                         (and @repairing? (= "BBBB2222" title))
                         {:status :deferred :reason :repair-active}

                         (contains? @imported title)
                         {:status :already-imported}

                         :else
                         (do (swap! imported conj title)
                             {:status :created})))))]
    (p/with-redefs [zotero/<notifier-request!
                    (fn [state]
                      (swap! reads conj state)
                      (p/resolved notification))
                    zotero/<local-api-request!
                    (fn [& _]
                      (p/resolved (json (mapv #(envelope % %) keys))))]
      (p/let [first-attempt (-> (zotero/<process-notifier-cycle!
                                local-library-binding cursor
                                (constantly true) register!)
                               (p/then (fn [result] {:returned result}))
                               (p/catch (fn [error] {:error (ex-data error)})))]
        (is (= :literature-zotero/repair-active
               (get-in first-attempt [:error :type])))
        (is (nil? (:returned first-attempt)))
        (is (= ["AAAA1111" "BBBB2222"] @attempts))
        (is (= #{"AAAA1111"} @imported))
        (reset! repairing? false)
        (p/let [resumed (zotero/<process-notifier-cycle!
                         local-library-binding cursor
                         (constantly true) register!)]
          (is (= [cursor cursor] @reads))
          (is (= :complete (:status resumed)))
          (is (= {:session "SYNTHETICREPAIRSESSION" :cursor 9}
                 (:notification-state resumed)))
          (is (= {:already-imported 1 :created 2}
                 (get-in resumed [:import :result-counts])))
          (is (= (set keys) @imported)))))))

(deftest-async notifier-cycle-advances-only-after-its-import-completes
  (let [scope-imports (atom 0)]
    (p/with-redefs [zotero/<notifier-request!
                    (fn [state]
                      (is (= {:session nil :cursor 0} state))
                      (p/resolved
                       {:session "ROUND80SESSION00000000001"
                        :cursor 9
                        :rescan true :zotero-library-key "LOCAL001"
                        :keys []}))
                    zotero/<import-scope!
                    (fn [binding still-authorized? _register!]
                      (is (= local-library-binding binding))
                      (is (still-authorized?))
                      (swap! scope-imports inc)
                      (p/resolved {:status :complete
                                   :requested-count 0}))]
      (p/let [result
              (zotero/<process-notifier-cycle!
               local-library-binding
               {:session nil :cursor 0}
               (constantly true)
               (fn [& _] (p/resolved {:status :created})))]
        (is (= :complete (:status result)))
        (is (= {:session "ROUND80SESSION00000000001" :cursor 9}
               (:notification-state result)))
        (is (= 1 @scope-imports))))))

(deftest-async cancelled-notifier-import-does-not-advance-its-cursor
  (p/with-redefs [zotero/<notifier-request!
                  (fn [_]
                    (p/resolved
                     {:session "ROUND80SESSION00000000001"
                      :cursor 10
                      :rescan false :zotero-library-key "LOCAL001"
                      :keys ["AAAA1111"]}))
                  zotero/<import-item-keys!
                  (fn [& _]
                    (p/resolved {:status :cancelled
                                 :reason :binding-inactive}))]
    (p/let [result
            (zotero/<process-notifier-cycle!
             local-library-binding
             {:session "ROUND80SESSION00000000001" :cursor 9}
             (constantly true)
             (fn [& _] (p/resolved {:status :created})))]
      (is (= :cancelled (:status result)))
      (is (= :binding-inactive (:reason result)))
      (is (nil? (:notification-state result))))))

(deftest-async authorization-lost-during-the-last-write-does-not-advance-cursor
  (let [authorized? (atom true)]
    (p/with-redefs [zotero/<notifier-request!
                    (fn [_]
                      (p/resolved
                       {:session "ROUND80SESSION00000000001"
                        :cursor 11
                        :rescan false :zotero-library-key "LOCAL001"
                        :keys ["AAAA1111"]}))
                    zotero/<import-item-keys!
                    (fn [& _]
                      (reset! authorized? false)
                      (p/resolved {:status :complete
                                   :requested-count 1}))]
      (p/let [result
              (zotero/<process-notifier-cycle!
               local-library-binding
               {:session "ROUND80SESSION00000000001" :cursor 10}
               #(true? @authorized?)
               (fn [& _] (p/resolved {:status :created})))]
        (is (= :cancelled (:status result)))
        (is (= :binding-inactive (:reason result)))
        (is (nil? (:notification-state result)))))))

(deftest-async malformed-or-unexpected-local-api-data-fails-closed
  (testing "malformed JSON rejects without a graph write"
    (let [writes (atom 0)]
      (p/with-redefs [zotero/<local-api-request! (fn [& _]
                                                   (p/resolved "not-json"))]
        (-> (zotero/<import-item-keys!
             local-library-binding ["AAAA1111"] (constantly true)
             (fn [& _] (swap! writes inc)))
            (p/then (fn [_] (is false "expected invalid JSON rejection")))
            (p/catch (fn [error]
                       (is (= :literature-zotero/invalid-json
                              (:type (ex-data error))))
                       (is (zero? @writes)))))))))

(deftest-async inactive-binding-performs-no-request-or-write
  (let [requests (atom 0)
        writes (atom 0)]
    (p/with-redefs [zotero/<local-api-request! (fn [& _] (swap! requests inc))]
      (p/let [result (zotero/<import-scope!
                             local-library-binding
                             (constantly false)
                             (fn [& _] (swap! writes inc)))]
        (is (= {:status :cancelled :reason :binding-inactive} result))
        (is (zero? @requests))
        (is (zero? @writes))))))

(deftest missing-library-identity-invalidates-a-connected-binding
  (is (nil? (zotero/sanitize-binding
             (dissoc local-library-binding :zotero-library-key)))))

(deftest-async different-local-library-is-rejected-before-any-data-read
  (let [requests (atom [])
        key (str "zotero:local:" local-source-uuid ":item:AAAA1111")
        register-calls (atom 0)]
    (p/with-redefs [ipc/ipc
                    (fn [_method _id options]
                      (swap! requests conj (:url options))
                      (is (string/ends-with? (:url options) "?identity=1"))
                      (p/resolved {:ok true :body (json {:libraryKey "OTHER002"})}))]
      (p/let [results (p/all
                      [(-> (zotero/<read-source-record!
                            local-library-binding key (constantly true))
                           (p/catch #(ex-data %)))
                       (-> (zotero/<read-pdf-attachments!
                            local-library-binding key (constantly true))
                           (p/catch #(ex-data %)))
                       (-> (zotero/<read-scope-page!
                            local-library-binding 0 (constantly true))
                           (p/catch #(ex-data %)))
                       (-> (zotero/<import-scope!
                            local-library-binding (constantly true)
                            (fn [& _] (swap! register-calls inc)))
                           (p/catch #(ex-data %)))])]
        (is (= 4 (count @requests)))
        (is (every? #(= :literature-zotero/library-identity-mismatch (:type %)) results))
        (is (zero? @register-calls))))))

(deftest-async source-hint-for-another-library-stops-before-source-or-asset-reads
  (let [requests (atom 0)]
    (p/with-redefs [zotero/<notifier-request!
                    (fn [& _] (p/resolved {:session "NEWSESSION" :cursor 1
                                           :zotero-library-key "OTHER002"
                                           :rescan true :keys []}))
                   zotero/<local-api-request!
                    (fn [& _] (swap! requests inc))]
      (p/let [result (zotero/<process-notifier-cycle!
                     local-library-binding {:session "OLDSESSION" :cursor 7}
                     (constantly true) (fn [& _] (throw (js/Error. "Unexpected import"))))]
        (is (= {:status :blocked :reason :library-identity-mismatch} result))
        (is (zero? @requests))))))

(deftest-async lost-binding-during-identity-check-never-starts-data-read
  (let [authorized? (atom true)
        requests (atom [])]
    (p/with-redefs [ipc/ipc
                    (fn [_method _id options]
                      (swap! requests conj (:url options))
                      (reset! authorized? false)
                      (p/resolved {:ok true :body (json {:libraryKey "LOCAL001"})}))]
      (p/let [result (-> (zotero/<local-api-request!
                         local-library-binding #(deref authorized?)
                         "users/0/items/top" "format=keys")
                        (p/catch #(ex-data %)))]
        (is (= :literature-zotero/binding-inactive (:type result)))
        (is (= ["http://127.0.0.1:23119/logseq-literature/changes?identity=1"]
               @requests))))))
