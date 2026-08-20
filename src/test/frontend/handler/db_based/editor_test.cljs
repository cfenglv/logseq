(ns frontend.handler.db-based.editor-test
  (:require [clojure.test :refer [are deftest is testing]]
            [frontend.db :as db]
            [frontend.handler.db-based.editor :as db-editor-handler]))

(deftest wrap-parse-block-markdown-heading-test
  (testing "normal blocks save markdown heading syntax as heading property"
    (is (= {:block/title "Heading"
            :logseq.property/heading 1}
           (select-keys (db-editor-handler/wrap-parse-block
                         {:block/title "# Heading"})
                        [:block/title :logseq.property/heading]))))

  (testing "raw display-type blocks preserve leading hash text"
    (doseq [display-type [:code :math]]
      (is (= {:block/title "# shell comment"
              :logseq.property.node/display-type display-type}
             (select-keys (db-editor-handler/wrap-parse-block
                           {:block/title "# shell comment"
                            :logseq.property.node/display-type display-type})
                          [:block/title
                           :logseq.property/heading
                           :logseq.property.node/display-type]))
          (str "Preserves content for " display-type)))))

(deftest wrap-parse-block-standalone-fenced-code-test
  (are [title expected] (= expected
                           (select-keys
                            (db-editor-handler/wrap-parse-block {:block/title title})
                            [:block/title
                             :logseq.property.node/display-type
                             :logseq.property.code/lang]))
    "```\nSELECT value\nFROM records\n```"
    {:block/title "SELECT value\nFROM records"
     :logseq.property.node/display-type :code}

    "```sql\nSELECT value\nFROM records\n```"
    {:block/title "SELECT value\nFROM records"
     :logseq.property.node/display-type :code
     :logseq.property.code/lang "sql"}

    " \n```text\nindented\n```\n "
    {:block/title "indented"
     :logseq.property.node/display-type :code
     :logseq.property.code/lang "text"}))

(deftest wrap-parse-block-standalone-display-math-test
  (is (= {:block/title "E = mc^2"
          :logseq.property.node/display-type :math}
         (select-keys
          (db-editor-handler/wrap-parse-block {:block/title " \n$$\n  E = mc^2  \n$$\n "})
          [:block/title :logseq.property.node/display-type]))))

(deftest wrap-parse-block-normalizes-only-whole-damaged-math-title-test
  (let [block-uuid #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        existing-math {:db/id 12
                       :block/uuid block-uuid
                       :block/title "x"
                       :logseq.property.node/display-type :math}]
    (with-redefs [db/entity (constantly existing-math)]
      (testing "one proven outer display layer is removed, including empty Math"
        (is (= "x"
               (:block/title
                (db-editor-handler/wrap-parse-block
                 {:block/uuid block-uuid :block/title "$$x$$"}))))
        (is (= ""
               (:block/title
                (db-editor-handler/wrap-parse-block
                 {:block/uuid block-uuid :block/title "$$$$"})))))
      (testing "text, multiple formulas, inline and escaped dollars are never guessed"
        (doseq [title ["prefix $$x$$"
                       "$$x$$ suffix"
                       "$$x$$\n$$y$$"
                       "$x$"
                       "\\$5 + x"]]
          (is (= title
                 (:block/title
                  (db-editor-handler/wrap-parse-block
                   {:block/uuid block-uuid :block/title title})))
              title))))))

(deftest generic-save-does-not-change-an-ordinary-block-to-math-test
  (let [block-uuid #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        ordinary {:db/id 12
                  :block/uuid block-uuid
                  :block/title "ordinary"}]
    (with-redefs [db/entity (constantly ordinary)]
      (doseq [title ["$$x$$" "$$$$"]]
        (let [result (db-editor-handler/wrap-parse-block
                      {:block/uuid block-uuid :block/title title}
                      {:allow-math-display-type-conversion? false})]
          (is (= title (:block/title result)))
          (is (nil? (:logseq.property.node/display-type result)))))
      (testing "the Math guard does not disable the existing Code conversion"
        (let [result (db-editor-handler/wrap-parse-block
                      {:block/uuid block-uuid
                       :block/title "```clojure\n(+ 1 2)\n```"}
                      {:allow-math-display-type-conversion? false})]
          (is (= :code (:logseq.property.node/display-type result)))
          (is (= "(+ 1 2)" (:block/title result))))))))

(deftest wrap-parse-block-markdown-hashtag-link-test
  (testing "markdown link targets that resolve to existing hashtag pages are saved as refs"
    (let [tag-uuid #uuid "5c6cd067-c602-4955-96b8-74b62e08113c"
          tag-page {:db/id 12
                    :block/title "Tag1"
                    :block/name "tag1"
                    :block/uuid tag-uuid
                    :block/tags [{:db/ident :logseq.class/Tag}]}]
      (with-redefs [db/get-page (fn [page]
                                  (when (= "Tag1" page)
                                    tag-page))]
        (let [result (db-editor-handler/wrap-parse-block
                      {:block/title "alias [Tag Number One](#Tag1)"})]
          (is (= (str "alias [Tag Number One](#[[" tag-uuid "]])")
                 (:block/title result)))
          (is (= [tag-uuid] (map :block/uuid (:block/refs result)))))))))

(deftest wrap-parse-block-preserves-multiple-block-refs-test
  (testing "multiple block refs are preserved when markdown hashtag refs are merged"
    (let [block-uuid #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          ref-uuid-a #uuid "11111111-1111-1111-1111-111111111111"
          ref-uuid-b #uuid "22222222-2222-2222-2222-222222222222"]
      (with-redefs [db/entity (fn [lookup]
                                (when (and (vector? lookup)
                                           (= :block/uuid (first lookup)))
                                  {:block/uuid (second lookup)}))]
        (let [result (db-editor-handler/wrap-parse-block
                      {:block/uuid block-uuid
                       :block/title (str "((" ref-uuid-a ")) ((" ref-uuid-b "))")})]
          (is (= #{[:block/uuid ref-uuid-a]
                   [:block/uuid ref-uuid-b]}
                 (set (:block/refs result))))
          (is (= 2 (count (:block/refs result)))))))))
