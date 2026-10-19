(ns logseq.e2e.flashcards-basic-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [logseq.e2e.api :refer [ls-api-call!]]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.fixtures :as fixtures]
            [logseq.e2e.keyboard :as k]
            [logseq.e2e.locator :as loc]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(use-fixtures :each
  fixtures/open-page
  fixtures/validate-graph)

(defn- open-flashcards
  []
  (util/double-esc)
  (when-not (w/visible? ".flashcards-nav")
    (w/click "#left-menu")
    (w/wait-for ".flashcards-nav"))
  (w/eval-js "selector => document.querySelector(selector)?.click()" ".flashcards-nav a")
  (assert/assert-is-visible "#cards-modal"))

(defn- select-cards-option
  [label]
  (w/click "#cards-modal [role='combobox']")
  (w/click (loc/filter "[role='option']" :has-text label)))

(defn- click-flashcards-plus
  []
  (w/click "#ls-cards-add"))

(defn- setup-flashcards-data!
  [{:keys [page-name tag-a tag-b card-a card-b query-a query-b scope-a scope-b]
    :or {scope-a "Cards A" scope-b "Cards B"}}]
  (ls-api-call! :editor.appendBlockInPage page-name (str card-a " #Card #" tag-a))
  (ls-api-call! :editor.appendBlockInPage page-name (str card-b " #Card #" tag-b))
  (let [cards (ls-api-call! :editor.getTag "logseq.class/Cards")
        cards-id (get cards "id")
        cards-a (ls-api-call! :editor.appendBlockInPage page-name scope-a
                              {:properties {:block/tags #{cards-id}}})
        cards-b (ls-api-call! :editor.appendBlockInPage page-name scope-b
                              {:properties {:block/tags #{cards-id}}})
        query-a-id (get cards-a ":logseq.property/query")
        query-b-id (get cards-b ":logseq.property/query")]
    (ls-api-call! :editor.updateBlock query-a-id query-a)
    (ls-api-call! :editor.updateBlock query-b-id query-b)
    {:cards-a-id (get cards-a "id") :cards-b-id (get cards-b "id")}))

(deftest flashcards-plus-and-switching-test
  (testing "create #Cards blocks from flashcards dialog and switch card sets"
    (let [tag-a "fc-tag-a"
          tag-b "fc-tag-b"
          card-a "Card A"
          card-b "Card B"
          query-a (str "[[" tag-a "]]")
          query-b (str "[[" tag-b "]]")]
      (util/goto-journals)
      (let [page (ls-api-call! :editor.getCurrentPage)
            page-name (get page "name")]
        (setup-flashcards-data!
         {:page-name page-name
          :tag-a tag-a
          :tag-b tag-b
          :card-a card-a
          :card-b card-b
          :query-a query-a
          :query-b query-b}))

      (open-flashcards)
      (click-flashcards-plus)
      (w/wait-for ".ls-block .tag:has-text('Cards')")

      (open-flashcards)
      (select-cards-option "Cards A")
      (assert/assert-is-visible (format "#cards-modal .ls-card :text('%s')" card-a))
      (assert/assert-have-count (format "#cards-modal .ls-card :text('%s')" card-b) 0)
      (assert/assert-is-visible (loc/filter "#cards-modal .text-sm.opacity-50" :has-text "1/1"))

      (select-cards-option "Cards B")
      (assert/assert-is-visible (format "#cards-modal .ls-card :text('%s')" card-b))
      (assert/assert-have-count (format "#cards-modal .ls-card :text('%s')" card-a) 0)
      (assert/assert-is-visible (loc/filter "#cards-modal .text-sm.opacity-50" :has-text "1/1"))

      (select-cards-option "All cards")
      (assert/assert-is-visible (loc/filter "#cards-modal .text-sm.opacity-50" :has-text "1/2"))))

  (testing "an untitled #Cards block is listed by its query with page names, not uuids"
    (let [tag-a "fc-tag-a"
          card-a "Card A"
          query-a (str "[[" tag-a "]]")]
      (k/esc)
      (assert/assert-is-hidden "#cards-modal")
      (util/goto-journals)
      (let [page (ls-api-call! :editor.getCurrentPage)
            page-name (get page "name")
            cards (ls-api-call! :editor.getTag "logseq.class/Cards")
            cards-id (get cards "id")
            untitled-cards (ls-api-call! :editor.appendBlockInPage page-name ""
                                         {:properties {:block/tags #{cards-id}}})
            query-id (get untitled-cards ":logseq.property/query")]
        (ls-api-call! :editor.updateBlock query-id query-a))

      (open-flashcards)
      (w/click "#cards-modal [role='combobox']")
      (assert/assert-is-visible (loc/filter "[role='option']" :has-text query-a))
      (assert/assert-have-count
       (loc/filter "[role='option']"
                   :has-text #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
       0)

      (w/click (loc/filter "[role='option']" :has-text query-a))
      (assert/assert-is-visible (format "#cards-modal .ls-card :text('%s')" card-a))
      (assert/assert-is-visible (loc/filter "#cards-modal .text-sm.opacity-50" :has-text "1/1")))))

(deftest flashcards-switching-ignores-late-scope-result-test
  (util/goto-journals)
  (let [page-name (get (ls-api-call! :editor.getCurrentPage) "name")
        {:keys [cards-a-id cards-b-id]}
        (setup-flashcards-data!
         {:page-name page-name :tag-a "fc-late-a" :tag-b "fc-late-b"
          :card-a "Delayed card" :card-b "Latest card"
          :query-a "[[fc-late-a]]" :query-b "[[fc-late-b]]"
          :scope-a "Delayed cards" :scope-b "Latest cards"})]
    (is (and (number? cards-a-id) (number? cards-b-id) (not= cards-a-id cards-b-id)))
    (open-flashcards)
    (select-cards-option "Latest cards")
    (assert/assert-is-visible "#cards-modal .ls-card :text('Latest card')")
    (w/eval-js
     "id => {
       const f=frontend.extensions.fsrs;
       const test={id:String(id),due:f._LT_get_due_card_block_ids,all:f._LT_get_card_block_ids,pending:null,settled:false};
       window.fsrsResponseOrderTest=test;
       f._LT_get_due_card_block_ids=function(repo,scope){
         const result=test.due(repo,scope);
         return String(scope)!==test.id ? result : result.then(value=>new Promise(resolve=>test.pending=()=>resolve(value)));
       };
       f._LT_get_card_block_ids=function(repo,scope){
         const result=test.all(repo,scope);
         if(String(scope)===test.id)result.then(()=>test.settled=true);
         return result;
       };
     }" cards-a-id)
    (try
      (select-cards-option "Delayed cards")
      (.waitForFunction (w/get-page) "() => typeof window.fsrsResponseOrderTest.pending==='function'")
      (select-cards-option "Latest cards")
      (assert/assert-is-visible "#cards-modal .ls-card :text('Latest card')")
      (w/eval-js
       "async () => {
         const test=window.fsrsResponseOrderTest;test.pending();
         await new Promise((resolve,reject)=>{
           const deadline=setTimeout(()=>{clearInterval(timer);reject(new Error('Delayed scope did not settle'));},5000);
           const timer=setInterval(()=>{if(test.settled){clearInterval(timer);clearTimeout(deadline);resolve();}},10);
         });
         await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);
       }")
      (is (= "Latest cards" (w/eval-js "() => document.querySelector('#cards-modal [role=combobox]').textContent")))
      (assert/assert-is-visible "#cards-modal .ls-card :text('Latest card')")
      (assert/assert-have-count "#cards-modal .ls-card :text('Delayed card')" 0)
      (finally
        (w/eval-js "() => {
          const test=window.fsrsResponseOrderTest,f=frontend.extensions.fsrs;
          f._LT_get_due_card_block_ids=test.due;f._LT_get_card_block_ids=test.all;
          delete window.fsrsResponseOrderTest;
        }")))))
