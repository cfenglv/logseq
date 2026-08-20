(ns frontend.handler.export-property-test
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs.test :refer [async deftest is testing]]
            [datascript.core :as d]
            [frontend.extensions.latex :as latex]
            [logseq.common.export.file :as common-file]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.test.helper :as db-test]
            [logseq.graph-parser.exporter :as gp-exporter]
            [promesa.core :as p]))

(deftest block-properties-content-uses-property-title-and-time-for-datetime
  (let [datetime-ms (tc/to-long (t/date-time 2026 5 14 9 30))
        expected-datetime (date-time-util/format
                           (t/to-default-time-zone (tc/from-long datetime-ms))
                           "MMM do, yyyy HH:mm")
        properties (array-map
                    :logseq.property/deadline datetime-ms
                    :user.property/P1-MoCeM8Tf "hello")]
    (with-redefs [db-property/properties (constantly properties)
                  db-property/sort-properties (fn [prop-entities] prop-entities)
                  d/entity (fn [_db lookup]
                             (case lookup
                               :logseq.property/deadline {:db/ident :logseq.property/deadline
                                                          :block/title "deadline"
                                                          :logseq.property/type :datetime}
                               :user.property/P1-MoCeM8Tf {:db/ident :user.property/P1-MoCeM8Tf
                                                           :block/title "P1"
                                                           :logseq.property/type :default}
                               nil))]
      (is (= (str "  deadline:: " expected-datetime "\n"
                  "  P1:: hello")
             (@#'common-file/block-properties-content nil {} "  " {}))))))

(deftest display-math-serialization-has-exactly-one-delimiter-layer-test
  (doseq [[stored-title canonical-title]
          [["x" "x"]
           ["" ""]
           ["$$x$$" "x"]
           ["$$$$" ""]
           ["\\$5 + x" "\\$5 + x"]]]
    (testing (str "stored title " (pr-str stored-title))
      (is (= (str "$$\n" canonical-title "\n$$")
             (@#'common-file/format-markdown-block-content
              {:logseq.property.node/display-type :math}
              stored-title
              1
              false)))
      (is (= [:div.latex (str "$$" canonical-title "$$")]
             (latex/html-export stored-title true true))))))

(deftest public-math-import-persists-through-reopen-and-exports-one-layer-test
  (async done
    (let [conn (db-test/create-conn)
          config-file {:path "logseq/config.edn" :content "{}"}
          page-file {:path "pages/math-root-runner.md"
                     :content (str "- $$x + y$$\n"
                                   "- $$$$\n"
                                   "- prefix $$x$$\n"
                                   "- $$x$$ suffix\n"
                                   "- $$$$x$$$$\n")}
          warnings (atom [])
          read-file (fn [file] (p/resolved (:content file)))]
      (->
       (gp-exporter/export-file-graph
        conn conn config-file [config-file page-file]
        {:<read-file read-file
         :<read-and-copy-asset
         (fn [& _]
           (p/rejected (js/Error. "No asset read is valid in this fixture")))
         :default-config "{}"
         :notify-user #(swap! warnings conj %)
         :log-fn (constantly nil)
         :user-options {:convert-all-tags? false}})
       (p/then
        (fn [_]
          (let [math-blocks (->> (d/datoms @conn :avet
                                           :logseq.property.node/display-type :math)
                                 (map #(d/entity @conn (:e %))))
                nonempty (first (filter #(= "x + y" (:block/title %)) math-blocks))
                empty-math (first (filter #(= "" (:block/title %)) math-blocks))
                reopened @(d/conn-from-datoms (d/datoms @conn :eavt) (:schema @conn))
                reopened-nonempty (d/entity reopened [:block/uuid (:block/uuid nonempty)])
                reopened-empty (d/entity reopened [:block/uuid (:block/uuid empty-math)])
                exported (common-file/block->content
                          reopened
                          (:block/uuid reopened-empty)
                          {:init-level 1 :include-properties? false}
                          {})]
            (is (empty? @warnings))
            (is (= ["x + y" :math]
                   [(:block/title reopened-nonempty)
                    (:logseq.property.node/display-type reopened-nonempty)]))
            (is (= ["" :math]
                   [(:block/title reopened-empty)
                    (:logseq.property.node/display-type reopened-empty)]))
            (doseq [ordinary ["prefix $$x$$" "$$x$$ suffix" "$$$$x$$$$"]]
              (let [persisted (db-test/find-block-by-content reopened ordinary)]
                (is (= ordinary (:block/title persisted)))
                (is (nil? (:logseq.property.node/display-type persisted)))))
            (is (= 2 (count (re-seq #"\$\$" exported)))
                "The public exporter must add exactly one delimiter layer")
            (is (not (re-find #"\$\$\$\$" exported))))))
       (p/catch
        (fn [error]
          (is false (str "public Math import/export failed: " error))))
       (p/finally done)))))
