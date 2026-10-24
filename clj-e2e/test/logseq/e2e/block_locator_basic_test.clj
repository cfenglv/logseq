(ns logseq.e2e.block-locator-basic-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [logseq.e2e.block :as block]
            [wally.main :as w]))

(use-fixtures :each
  (fn [f]
    (w/with-page-open (w/make-page {:headless true :persistent false :slow-mo 0
                                  :default-timeout 1000})
      (f))))

(deftest last-page-block-content-excludes-property-editors
  (.setContent (w/get-page)
               "<div class='ls-page-blocks'><div class='page-blocks-inner'>
                  <div class='ls-block'><div class='block-content'>last block</div>
                    <div class='property-block-container'><div class='ls-block'>
                      <div class='block-content'>property value</div>
                    </div></div>
                  </div>
                </div></div>")
  (is (= "last block" (.textContent (#'block/last-page-block-content))))
  (.evaluate (w/get-page) "document.querySelector('.ls-block > .block-content').remove()")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No page block content"
                       (#'block/last-page-block-content))))

(deftest last-page-block-content-re-resolves-after-removal
  (.setContent (w/get-page)
               "<div class='ls-page-blocks'><div class='page-blocks-inner'>
                  <div class='ls-block'><div class='block-content'>first block</div></div>
                  <div class='ls-block' id='last-block'><div class='block-content'>last block</div></div>
                </div></div>")
  (let [content (#'block/last-page-block-content)]
    (is (= "last block" (.textContent content)))
    (.evaluate (w/get-page) "document.querySelector('#last-block').remove()")
    (.click content)
    (is (= "first block" (.textContent content)))))
