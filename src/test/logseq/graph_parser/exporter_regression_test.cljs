(ns logseq.graph-parser.exporter-regression-test
  (:require [cljs.test :refer [deftest is testing]]
            [logseq.graph-parser.exporter :as gp-exporter]))

(deftest build-pdf-annotation-always-has-string-title-test
  (let [annotation-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        parent-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        parent-asset {:block/uuid parent-id
                      :block/title "paper.pdf"}
        base-annotation {:id annotation-id
                         :page 7
                         :properties {:color "blue"}
                         :position {:page 7}}
        build (fn [annotation]
                (#'gp-exporter/build-annotation-block
                 annotation
                 {"blue" :logseq.property/color.blue}
                 parent-asset
                 {}
                 {}
                 {:log-fn (fn [& _])}))
        missing-text (build base-annotation)
        nil-text (build (assoc base-annotation :content {:text nil}))
        titled (build (assoc base-annotation :content {:text "Selected text"}))]
    (testing "missing and nil annotation text become an actual empty string"
      (is (= "" (:block/title missing-text)))
      (is (= "" (:block/title nil-text)))
      (is (string? (:block/title missing-text)))
      (is (string? (:block/title nil-text))))
    (testing "an explicit non-empty title is preserved"
      (is (= "Selected text" (:block/title titled))))
    (testing "normalization does not discard annotation metadata"
      (doseq [annotation [missing-text nil-text titled]]
        (is (= annotation-id (:block/uuid annotation)))
        (is (= 7 (:logseq.property.pdf/hl-page annotation)))
        (is (= :logseq.property/color.blue
               (:logseq.property.pdf/hl-color annotation)))
        (is (= [:block/uuid parent-id]
               (:logseq.property/asset annotation)))
        (is (= [:logseq.class/Pdf-annotation]
               (:block/tags annotation))))
      (is (= base-annotation
             (:logseq.property.pdf/hl-value missing-text)))
      (is (= (assoc base-annotation :content {:text nil})
             (:logseq.property.pdf/hl-value nil-text)))
      (is (= (assoc base-annotation :content {:text "Selected text"})
             (:logseq.property.pdf/hl-value titled))))))
