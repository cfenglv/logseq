(ns frontend.extensions.pdf.assets-test
  (:require [cljs.test :refer [are deftest is testing]]
            [frontend.db :as db]
            [frontend.db.model :as db-model]
            [frontend.extensions.pdf.assets :as pdf-assets]
            [frontend.extensions.pdf.utils :as pdf-utils]
            [frontend.handler.editor :as editor-handler]
            [frontend.util :as util]))

(deftest fix-local-asset-pagename
  (testing "matched filenames"
    (are [x y] (= y (pdf-utils/fix-local-asset-pagename x))
      "2015_Book_Intertwingled_1659920114630_0" "2015 Book Intertwingled"
      "hls__2015_Book_Intertwingled_1659920114630_0" "2015 Book Intertwingled"
      "hls/2015_Book_Intertwingled_1659920114630_0" "hls/2015 Book Intertwingled"
      "hls__sicp__-1234567" "sicp"))
  (testing "non matched filenames"
    (are [x y] (= y (pdf-utils/fix-local-asset-pagename x))
      "foo" "foo"
      "foo_bar" "foo_bar"
      "foo__bar" "foo__bar"
      "foo_bar.pdf" "foo_bar.pdf")))

(deftest inflate-asset-normalizes-local-assets-url-on-windows
  (with-redefs [util/electron? (constantly true)
                util/win32? true]
    (is (= "assets:///C/logseq__colon/Users/charlie/sicp.pdf"
           (:url (pdf-assets/inflate-asset
                  "C:/Users/charlie/sicp.pdf"
                  {:href "assets:///C:/Users/charlie/sicp.pdf"}))))))

(deftest creating-pdf-annotation-preserves-explicit-title-test
  (let [inserted (atom nil)
        annotation {:id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                    :page 4
                    :content {:text "Quoted sentence"}
                    :properties {:color "yellow"}}]
    (with-redefs [db-model/query-block-by-uuid (constantly nil)
                  db/entity (constantly
                             {:property/closed-values
                              [{:db/id 200 :block/title "yellow"}]})
                  editor-handler/api-insert-new-block!
                  (fn [title opts]
                    (reset! inserted [title opts])
                    {:block/title title})]
      (pdf-assets/db-based-ensure-ref-block!
       {:block {:db/id 100
                :block/uuid #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}}
       annotation
       nil)
      (is (= "Quoted sentence" (first @inserted)))
      (is (= annotation
             (get-in @inserted [1 :properties :logseq.property.pdf/hl-value]))))))
