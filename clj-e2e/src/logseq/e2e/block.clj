(ns logseq.e2e.block
  (:require [clojure.string :as string]
            [clojure.test :refer [is]]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.keyboard :as k]
            [logseq.e2e.locator :as loc]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(defn open-last-block
  "Open the last existing block or pressing add button to create a new block"
  [& {:keys [in-retry?]}]
  (util/double-esc)
  (assert/assert-in-normal-mode?)

  (let [blocks-count (util/page-blocks-count)
        last-block (-> (if (zero? blocks-count)
                         (w/query ".ls-page-blocks .block-add-button")
                         (w/query ".ls-page-blocks .page-blocks-inner .ls-block .block-content"))
                       (last))]
    (w/click last-block)
    (if in-retry?
      (assert/assert-editor-mode)
      (try
        (assert/assert-editor-mode)
        (catch Error _e
          (open-last-block {:in-retry? true}))))))

(defn save-block
  [text]
  (assert/assert-have-count util/editor-q 1)
  (w/click util/editor-q)
  (w/fill util/editor-q text)
  (assert/assert-is-visible (loc/filter util/editor-q :has-text text)))

(defn open-last-block-strict!
  "Open the last block without retrying or swallowing editor failures."
  []
  (util/double-esc)
  (assert/assert-in-normal-mode?)
  (let [blocks-count (util/page-blocks-count)
        last-block (-> (if (zero? blocks-count)
                         (w/query ".ls-page-blocks .block-add-button")
                         (w/query ".ls-page-blocks .page-blocks-inner .ls-block .block-content"))
                       last)]
    (w/click last-block)
    (assert/assert-editor-mode)))

(defn new-block-strict!
  "Create and verify a block once, propagating every failure to the caller."
  [title]
  (when-not (util/get-editor)
    (open-last-block-strict!))
  (let [last-id (.getAttribute (w/-query ".editor-wrapper textarea") "id")]
    (when-not last-id
      (throw (ex-info "strict new block has no source editor" {:title title})))
    (k/press "Control+e")
    (k/enter)
    (assert/assert-is-visible
     (loc/filter ".editor-wrapper"
                 :has "textarea"
                 :has-not (str "#" last-id)))
    (assert/assert-editor-mode)
    (save-block title)))

(defn new-block
  [title & [in-retry?]]
  (let [editor (util/get-editor)]
    (when-not editor (open-last-block))
    (let [last-id (.getAttribute (w/-query ".editor-wrapper textarea") "id")]
      (is (some? last-id))
      (k/press "Control+e")
      (k/enter)
      (try
        (assert/assert-is-visible
         (loc/filter ".editor-wrapper"
                     :has "textarea"
                     :has-not (str "#" last-id)))
        (assert/assert-editor-mode)
        (save-block title)
        (catch Throwable e
          ;; A remote render can replace the transient editor after the input
          ;; event has already committed the uniquely titled block. Treat the
          ;; committed UI state as success instead of creating a duplicate.
          (if (and (not (string/blank? title))
                   (some #(= title %)
                         (util/get-page-blocks-contents)))
            true
            (if in-retry?
              (throw (ex-info
                      "new-block exception"
                      {:current-id (.getAttribute (w/-query ".editor-wrapper textarea") "id")
                       :last-id last-id}
                      e))
              (do (prn :retry-new-block title)
                  (new-block title true)))))))))

;; TODO: support tree
(defn new-blocks
  [titles]
  (let [editor? (util/get-editor)]
    (when-not editor? (open-last-block))
    (let [value (util/get-edit-content)]
      (if (string/blank? value)         ; empty block
        (save-block (first titles))
        (new-block (first titles))))
    (doseq [title (rest titles)]
      (new-block title))))

(defn delete-blocks
  "Delete the current block if in editing mode, otherwise, delete all the selected blocks."
  []
  (let [editor (util/get-editor)]
    (when editor (util/exit-edit))
    (k/backspace)))

(defn assert-blocks-visible
  "blocks - coll of :block/title"
  [blocks]
  (doseq [block blocks]
    (assert/assert-is-visible (format ".ls-page-blocks .ls-block :text('%s')" block))))

(defn jump-to-block
  [block-text]
  (w/click (w/find-one-by-text ".ls-block .block-content" block-text)))

(defn wait-editor-text
  [text]
  (assert/assert-have-count util/editor-q 1)
  (w/wait-for (format ".editor-wrapper textarea:text('%s')" text)))

(def copy #(k/press "ControlOrMeta+c" {:delay 100}))
(def paste #(k/press "ControlOrMeta+v" {:delay 100}))
(def undo #(k/press "ControlOrMeta+z" {:delay 100}))
(def redo #(k/press "ControlOrMeta+y" {:delay 100}))

(def ^:private editor-layout-timeout-ms 10000)
(def ^:private editor-layout-poll-ms 50)
(def ^:private block-selection-timeout-ms 10000)
(def ^:private block-selection-poll-ms 50)
(def ^:private selected-blocks-q
  ".ls-page-blocks .page-blocks-inner .ls-block.selected")

(defn- current-editor-x
  []
  (when-let [editor (util/get-editor)]
    (when-let [box (.boundingBox editor)]
      (.-x box))))

(defn- wait-for-editor-x
  [moved? context]
  (let [deadline (+ (System/nanoTime)
                    (* editor-layout-timeout-ms 1000000))]
    (loop [last-x nil]
      (let [x (current-editor-x)]
        (cond
          (and (some? x) (moved? x))
          x

          (< (System/nanoTime) deadline)
          (do
            (util/wait-timeout editor-layout-poll-ms)
            (recur (or x last-x)))

          :else
          (throw
           (ex-info
            "editor layout did not reach the expected position"
            (merge {:timeout-ms editor-layout-timeout-ms
                    :last-x (or x last-x)}
                   context))))))))

(defn- indent-outdent
  [indent?]
  (let [x1 (wait-for-editor-x (constantly true)
                              {:operation (if indent? :indent :outdent)
                               :stage :before-key})
        _ (if indent? (k/tab) (k/shift+tab))
        moved? (if indent?
                 #(< x1 %)
                 #(> x1 %))
        x2 (wait-for-editor-x moved?
                              {:operation (if indent? :indent :outdent)
                               :stage :after-key
                               :before-x x1})]
    (is (moved? x2))))

(defn indent
  []
  (indent-outdent true))

(defn outdent
  []
  (indent-outdent false))

(defn toggle-property
  [property-title property-value]
  (k/press (if util/mac? "ControlOrMeta+p" "Control+Alt+p"))
  (w/fill ".ls-property-dialog .ls-property-input input" property-title)
  (w/wait-for (format "#ac-0.menu-link:has-text('%s')" property-title))
  (k/enter)
  (util/wait-timeout 100)
  (w/click (w/-query ".ls-property-dialog .ls-property-input input"))
  (util/wait-timeout 100)
  (util/input property-value)
  (w/wait-for (format "#ac-0.menu-link:has-text('%s')" property-value))
  (k/enter))

(defn select-blocks-to-count
  [target-count]
  ;; A remote render can briefly replace the editor/selection DOM. Sending all
  ;; key events back-to-back lets a slow runner swallow an intermediate event,
  ;; so wait for each visible selection change before sending the next one.
  ;; The first key can select either one block or the editor block plus its
  ;; predecessor, depending on the current mode; stop at the requested count
  ;; instead of assuming every key changes the count by exactly one.
  (letfn [(selected-count []
            (util/count-elements selected-blocks-q))
          (wait-for-count-change [previous-count]
            (let [deadline (+ (System/nanoTime)
                              (* block-selection-timeout-ms 1000000))]
              (loop []
                (let [current-count (selected-count)]
                  (cond
                    (not= previous-count current-count)
                    current-count

                    (< (System/nanoTime) deadline)
                    (do
                      (util/wait-timeout block-selection-poll-ms)
                      (recur))

                    :else
                    (throw
                     (ex-info
                      "block selection count did not change"
                      {:timeout-ms block-selection-timeout-ms
                       :selected-count current-count
                       :target-count target-count})))))))]
    (loop [current-count (selected-count)]
      (cond
        (= target-count current-count)
        true

        (> current-count target-count)
        (throw
         (ex-info
          "block selection exceeded requested count"
          {:selected-count current-count
           :target-count target-count}))

        :else
        (do
          (k/press "Shift+ArrowUp" {:delay 20})
          (recur (wait-for-count-change current-count)))))))

(defn select-blocks
  [n]
  (util/repeat-keyboard n "Shift+ArrowUp"))
