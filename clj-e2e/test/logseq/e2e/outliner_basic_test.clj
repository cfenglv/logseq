(ns logseq.e2e.outliner-basic-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [logseq.e2e.api :refer [ls-api-call!]]
   [logseq.e2e.assert :as assert]
   [logseq.e2e.block :as b]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.page :as p]
   [logseq.e2e.util :as util]
   [wally.main :as w]))

(use-fixtures :once fixtures/open-page)
(use-fixtures :each
  fixtures/new-logseq-page
  fixtures/validate-graph)

(defn- block-text-position
  [text]
  (let [locator (w/find-one-by-text "span" text)]
    (assert/assert-is-visible locator)
    (first (util/bounding-xy locator))))

(defn create-test-page-and-insert-blocks []
  ;; a page block and a child block
  (is (= 2 (util/blocks-count)))
  (b/new-blocks ["first block" "second block"])
  (util/exit-edit)
  (is (= 3 (util/blocks-count))))

(defn indent-and-outdent []
  (b/new-blocks ["b1" "b2"])
  (testing "simple indent and outdent"
    (b/indent)
    (b/outdent))

  (testing "indent a block with its children"
    (b/new-block "b3")
    (b/indent)
    (k/arrow-up)
    (b/indent)
    (util/exit-edit)
    (let [[x1 x2 x3] (map block-text-position ["b1" "b2" "b3"])]
      (is (< x1 x2 x3))))

  (testing "unindent a block with its children"
    (b/open-last-block)
    (b/new-blocks ["b4" "b5"])
    (b/indent)
    (k/arrow-up)
    (b/outdent)
    (util/exit-edit)
    (let [[x2 x3 x4 x5] (map block-text-position ["b2" "b3" "b4" "b5"])]
      (is (and (= x2 x4) (= x3 x5) (< x2 x3))))))

(defn indent-outdent-embed-page []
  (p/new-page "Page embed")
  (b/new-blocks ["b1" "b2"])
  (p/new-page "Page testing")
  (b/new-blocks ["b3" ""])
  (util/input-command "Node embed")
  (util/press-seq "Page embed" {:delay 60})
  (w/wait-for "#ac-0.menu-link:has-text('Page embed')")
  (k/press "Enter" {:delay 60})
  (util/exit-edit)
  (b/new-blocks ["b4"])
  (b/outdent)
  (b/indent)
  (util/exit-edit)
  (let [[x2 x3 x4] (map block-text-position ["b2" "b3" "b4"])]
    (is (= x2 x4))
    (is (< x3 x2))))

(def ^:private block-order-timeout-ms 10000)
(def ^:private block-order-poll-ms 50)

(defn- wait-page-blocks-contents
  [expected]
  (let [deadline (+ (System/nanoTime)
                    (* block-order-timeout-ms 1000000))]
    (loop [actual (util/get-page-blocks-contents)]
      (cond
        (= expected actual)
        actual

        (< (System/nanoTime) deadline)
        (do
          (util/wait-timeout block-order-poll-ms)
          (recur (util/get-page-blocks-contents)))

        :else
        (throw
         (ex-info
          "page blocks did not reach the expected order"
          {:timeout-ms block-order-timeout-ms
           :expected expected
           :actual actual}))))))

(defn- select-b3-and-b4
  []
  ;; RTC can replace the editor DOM after the final block is created or after
  ;; an acknowledged move. Re-enter the intended block and wait for the exact
  ;; two-block selection before issuing a move shortcut.
  (w/click (util/get-by-text "b4" true))
  (b/wait-editor-text "b4")
  (b/select-blocks-to-count 2)
  (assert/assert-selected-block-text "b3")
  (assert/assert-selected-block-text "b4"))

(defn- move-selected-blocks
  [shortcut expected-orders]
  ;; Observe each move before issuing the next shortcut. This prevents a remote
  ;; render from swallowing a back-to-back keyboard event without replaying a
  ;; data-changing operation.
  (doseq [expected expected-orders]
    (k/press shortcut {:delay 20})
    (wait-page-blocks-contents expected)))

(defn move-up-down
  ([]
   (move-up-down (fn [] nil)))
  ([settle!]
   (b/new-blocks ["b1" "b2" "b3" "b4"])
   ;; Commit the final editor value before an already-visible RTC idle state can
   ;; satisfy settle! and allow a remote render to replace the editor.
   (util/exit-edit)
   (wait-page-blocks-contents ["b1" "b2" "b3" "b4"])
   (settle!)
   (is (= ["b1" "b2" "b3" "b4"]
          (wait-page-blocks-contents ["b1" "b2" "b3" "b4"])))
   (select-b3-and-b4)
   (move-selected-blocks
    (str (if util/mac? "Meta" "Alt") "+Shift+ArrowUp")
    [["b1" "b3" "b4" "b2"]
     ["b3" "b4" "b1" "b2"]])
   (settle!)
   (is (= ["b3" "b4" "b1" "b2"]
          (wait-page-blocks-contents ["b3" "b4" "b1" "b2"])))
   (select-b3-and-b4)
   (move-selected-blocks
    (str (if util/mac? "Meta" "Alt") "+Shift+ArrowDown")
    [["b1" "b3" "b4" "b2"]
     ["b1" "b2" "b3" "b4"]])
   (settle!)
   (is (= ["b1" "b2" "b3" "b4"]
          (wait-page-blocks-contents ["b1" "b2" "b3" "b4"])))))

(defn- zoom-in-shortcut []
  (k/press (if util/mac? "Meta+Shift+." "Alt+ArrowRight")))

(defn- current-location-hash []
  (w/eval-js "window.location.hash"))

(defn- current-editing-block-id []
  (w/eval-js
   "(() => {
      const editor = document.querySelector('.editor-wrapper textarea');
      return editor?.closest('[blockid]')?.getAttribute('blockid') ?? null;
    })();"))

(deftest focused-root-block-cannot-indent-or-move-test
  (testing "Focused root block ignores indent/outdent/move-up/move-down commands"
    (b/new-blocks ["focused-root" "focused-child"])
    (k/arrow-up)
    (let [root-id (current-editing-block-id)]
      (is (string? root-id))
      (zoom-in-shortcut)
      (util/wait-timeout 400)
      ;; Retry once in case the first key event gets swallowed by the editor.
      (when-not (string/includes? (or (current-location-hash) "") root-id)
        (zoom-in-shortcut)
        (util/wait-timeout 400))
      (is (string/includes? (or (current-location-hash) "") root-id))
      (util/wait-editor-visible)
      (is (= "focused-root" (util/get-edit-content)))
      (let [before-hash (current-location-hash)
            before-block-contents (util/get-page-blocks-contents)]
        (k/tab)
        (util/wait-timeout 100)
        (k/shift+tab)
        (util/wait-timeout 100)
        (k/meta+shift+arrow-up)
        (util/wait-timeout 100)
        (k/meta+shift+arrow-down)
        (util/wait-timeout 100)
        (is (= "focused-root" (util/get-edit-content)))
        (is (= before-hash (current-location-hash)))
        (is (= before-block-contents (util/get-page-blocks-contents)))))))

(defn delete
  ([]
   (delete (fn [] nil)))
  ([settle!]
   (testing "Delete blocks case 1"
     (b/new-blocks ["b1" "b2" "b3" "b4"])
     (settle!)
     ;; Establish observable selection state before each destructive key. In an
     ;; RTC graph, a remote render can otherwise land between Escape and
     ;; Backspace and leave an unselected empty block behind.
     (w/click (util/get-by-text "b4" true))
     (b/wait-editor-text "b4")
     (b/delete-blocks)                       ; delete b4
     (settle!)
     (w/click (util/get-by-text "b3" true))
     (b/wait-editor-text "b3")
     (b/select-blocks-to-count 2)            ; select b3 and b2
     (b/delete-blocks)
     (settle!)
     (util/wait-editor-visible)
     (assert/assert-have-count
      ".ls-page-blocks .page-blocks-inner .ls-block"
      1)
     (is (= "b1" (util/get-edit-content)))
     (is (= 1 (util/page-blocks-count))))))

(defn delete-end []
  (testing "Delete at end"
    (b/new-blocks ["b1" "b2" "b3"])
    (k/arrow-up)
    (k/delete)
    (is (= "b2b3" (util/get-edit-content)))
    (is (= 2 (util/page-blocks-count)))))

(defn delete-test-with-children []
  (testing "Delete block with its children"
    (b/new-blocks ["b1" "b2" "b3" "b4"])
    (b/indent)
    (k/arrow-up)
    (b/indent)
    (k/arrow-up)
    (b/delete-blocks)
    (util/wait-editor-visible)
    (is (= "b1" (util/get-edit-content)))
    (is (= 1 (util/page-blocks-count)))))

(deftest create-test-page-and-insert-blocks-test
  (create-test-page-and-insert-blocks))

(deftest indent-and-outdent-test
  (indent-and-outdent))

(deftest indent-outdent-embed-page-test
  (indent-outdent-embed-page))

(deftest move-up-down-test
  (move-up-down))

(deftest delete-test
  (delete))

(deftest delete-end-test
  (delete-end))

(deftest delete-test-with-children-test
  (delete-test-with-children))

(deftest delete-concat-test-2-blocks
  (testing "Delete concat with empty block"
    (b/new-blocks ["" "b2"])
    (b/indent)
    (k/arrow-up)
    (k/delete)
    (util/wait-editor-visible)
    (is (= "b2" (util/get-edit-content)))
    (util/exit-edit)
    (is (= ["b2"] (util/get-page-blocks-contents)))))

(deftest delete-concat-test-3-blocks
  (testing "Delete concat with empty block"
    (b/new-blocks ["" "b2" "b3"])
    (b/indent)
    (k/arrow-up)
    (k/arrow-up)
    (k/delete)
    (util/wait-editor-visible)
    (is (= "b2" (util/get-edit-content)))
    (util/exit-edit)
    (is (= ["b2" "b3"] (util/get-page-blocks-contents)))))

(deftest delete-concat-test-with-children
  (testing "Delete concat with children blocks"
    (b/new-blocks ["" "b2" "b3"])
    (b/indent)
    (k/arrow-up)
    (b/indent)
    (k/arrow-up)
    (k/delete)
    (util/wait-editor-visible)
    (is (= "" (util/get-edit-content)))
    (is (= 3 (util/page-blocks-count)))))

(deftest delete-concat-test-with-tag
  (testing "Delete concat with tag"
    (b/new-blocks ["" "b2"])
    (b/indent)
    (util/set-tag "tag1")
    (k/arrow-up)
    (k/delete)
    (util/wait-editor-visible)
    (is (= "b2" (util/get-edit-content)))
    (util/exit-edit)
    (assert/assert-is-visible
     ".ls-block a.tag:has-text('tag1')")
    (is (= ["b2"] (util/get-page-blocks-contents)))))

(deftest backspace-empty-first-child-keeps-empty-parent-subtree-test
  (testing "Backspace in the first empty child of an empty parent deletes only the child"
    (p/new-page "backspace empty first child")
    (let [page-uuid (get (ls-api-call! :editor.getBlock "backspace empty first child") "uuid")
          [parent first-child child2 child3]
          (ls-api-call! :editor.insertBatchBlock
                        page-uuid
                        [{:content ""
                          :children [{:content ""}
                                     {:content "child2"}
                                     {:content "child3"}]}])
          block-visible? #(pos? (util/count-elements (str "#ls-block-" %)))]
      (w/click (str "#ls-block-" (get first-child "uuid") " .block-content"))
      (util/wait-editor-visible)
      (is (= "" (util/get-edit-content)))
      (k/backspace)
      (util/wait-timeout 100)
      (is (not (block-visible? (get first-child "uuid"))))
      (is (block-visible? (get parent "uuid")))
      (is (block-visible? (get child2 "uuid")))
      (is (block-visible? (get child3 "uuid"))))))
