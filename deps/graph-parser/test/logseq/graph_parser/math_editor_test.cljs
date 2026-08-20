(ns logseq.graph-parser.math-editor-test
  (:require [cljs.test :refer [are deftest is testing]]
            [logseq.graph-parser.exporter :as gp-exporter]
            [logseq.graph-parser.mldoc :as gp-mldoc]))

(defn- imported-math-block
  [title]
  (#'gp-exporter/handle-math
   {:block/title title
    :block.temp/ast-blocks (mapv first (gp-mldoc/->db-edn title :markdown))}))

(deftest handle-math-uses-the-whole-displayed-math-ast-test
  (testing "non-empty and exact empty display Math import to the same canonical shape"
    (are [title bare-title]
        (= {:block/title bare-title
            :logseq.property.node/display-type :math
            :block/tags [:logseq.class/Math-block]}
           (dissoc (imported-math-block title) :block.temp/ast-blocks))
      "$$x$$" "x"
      "$$$$" ""))
  (testing "mixed, multiple and nested ASTs remain ordinary text"
    (doseq [title ["prefix $$x$$"
                   "$$x$$ suffix"
                   "$$x$$\n$$y$$"
                   "$$$$x$$$$"]]
      (is (= title (:block/title (imported-math-block title))) title)
      (is (nil? (:logseq.property.node/display-type
                 (imported-math-block title)))))))
