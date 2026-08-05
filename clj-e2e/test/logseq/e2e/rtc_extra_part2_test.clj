(ns logseq.e2e.rtc-extra-part2-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [jsonista.core :as json]
            [logseq.e2e.api :refer [ls-api-call!]]
            [logseq.e2e.assets :as e2e-assets]
            [logseq.e2e.block :as b]
            [logseq.e2e.const :refer [*page1 *page2 *graph-name*]]
            [logseq.e2e.custom-report :as custom-report]
            [logseq.e2e.fixtures :as fixtures]
            [logseq.e2e.graph :as graph]
            [logseq.e2e.keyboard :as k]
            [logseq.e2e.page :as page]
            [logseq.e2e.rtc :as rtc]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(use-fixtures :once
  fixtures/open-2-pages
  (partial fixtures/prepare-rtc-graph-fixture "rtc-extra-part2-test-graph"))

(use-fixtures :each
  fixtures/new-logseq-page-in-rtc)

(def ^:private stress-default-rounds 1)
(def ^:private stress-default-ops-per-client 50)
(def ^:private stress-default-seed-blocks 20)
(def ^:private stress-default-seed 20260330)
(def ^:private stress-max-seed-depth 4)
(def ^:private stress-quiescence-poll-ms 250)
(def ^:private stress-quiescence-stable-ms 3000)
(def ^:private stress-quiescence-timeout-ms 30000)
(def ^:private severe-sync-log-patterns
  ["db-sync/checksum-mismatch"
   "db-sync/tx-rejected"
   "db-sync/apply-remote-txs-failed"])
(def ^:private random-edit-actions
  [:new :save :indent-outdent :delete-existing :undo :redo])

(defn- env-int
  [k default]
  (let [raw (System/getenv k)]
    (if-not (string/blank? raw)
      (try
        (Integer/parseInt raw)
        (catch Throwable _
          default))
      default)))

(defn- recent-console-logs
  []
  (->> (some-> custom-report/*pw-page->console-logs* deref vals)
       (mapcat identity)
       vec))

(defn- assert-no-severe-sync-errors!
  []
  (let [logs (recent-console-logs)
        matched (->> logs
                     (filter (fn [line]
                               (some #(string/includes? line %) severe-sync-log-patterns)))
                     vec)]
    (is (empty? matched)
        (str "found severe sync errors in console logs: "
             (pr-str (take-last 20 matched))))))

(defn- page-sync-state
  [pw-page]
  (w/with-page pw-page
    {:rtc-tx (rtc/get-rtc-tx)
     :blocks (util/get-page-blocks-contents)}))

(defn- two-page-sync-state
  []
  {:page1 (page-sync-state @*page1)
   :page2 (page-sync-state @*page2)})

(defn- wait-for-two-pages-quiescent!
  []
  (let [state (rtc/wait-for-stable-state!
               two-page-sync-state
               rtc/two-client-snapshot-quiescent?
               {:poll-ms stress-quiescence-poll-ms
                :stable-ms stress-quiescence-stable-ms
                :timeout-ms stress-quiescence-timeout-ms})]
    (prn :two-page-rtc-quiescent
         {:rtc-tx (mapv (comp :rtc-tx state) [:page1 :page2])
          :stable-ms stress-quiescence-stable-ms})
    state))

(defn- assert-two-pages-synced!
  [{s1 :page1 s2 :page2}]
  (let [tx1 (:rtc-tx s1)
        tx2 (:rtc-tx s2)]
    (is (= (:blocks s1) (:blocks s2))
        (str "page blocks diverged: "
             (pr-str {:page1-count (count (:blocks s1))
                      :page2-count (count (:blocks s2))
                      :page1-tail (take-last 8 (:blocks s1))
                      :page2-tail (take-last 8 (:blocks s2))})))
    (is (= (:local-tx tx1) (:remote-tx tx1))
        (str "page1 rtc-tx not converged: " (pr-str tx1)))
    (is (= (:local-tx tx2) (:remote-tx tx2))
        (str "page2 rtc-tx not converged: " (pr-str tx2)))
    (is (= tx1 tx2)
        (str "client rtc-tx watermarks differ: "
             (pr-str {:page1 tx1 :page2 tx2})))))

(defn- current-editor-layout
  []
  ;; A concurrent remote render can detach the editor between get-editor and
  ;; boundingBox. Playwright reports that as a nil box; it is an expected
  ;; "operation unavailable" result for these optional stress actions.
  (when-let [editor (util/get-editor)]
    (when-let [box (.boundingBox editor)]
      (when-let [editor-id (.getAttribute editor "id")]
        {:editor-id editor-id
         :x (.-x box)}))))

(defn- try-indent!
  []
  (if-let [{editor-id :editor-id x1 :x} (current-editor-layout)]
    (do
      (k/tab)
      (if-let [{editor-id' :editor-id x2 :x} (current-editor-layout)]
        (and (= editor-id editor-id')
             (> x2 x1))
        false))
    false))

(defn- try-outdent!
  []
  (if-let [{editor-id :editor-id x1 :x} (current-editor-layout)]
    (do
      (k/shift+tab)
      (if-let [{editor-id' :editor-id x2 :x} (current-editor-layout)]
        (and (= editor-id editor-id')
             (> x1 x2))
        false))
    false))

(defn- align-depth!
  [depth target]
  (loop [d depth]
    (cond
      (< d target) (if (try-indent!)
                     (recur (inc d))
                     d)
      (> d target) (if (try-outdent!)
                     (recur (dec d))
                     d)
      :else d)))

(defn- page-has-block-title?
  [title]
  (boolean (some #(= title %) (util/get-page-blocks-contents))))

(defn- new-block-safe!
  [title]
  (loop [attempt 4]
    (let [created?
          (try
            (b/new-block title)
            true
            (catch Throwable _
              false))]
      (if created?
        true
        ;; `b/new-block` verifies transient editor DOM after committing the
        ;; block. A concurrent remote render can replace that DOM even though
        ;; the uniquely named block was committed; do not create a duplicate
        ;; in that case.
        (if (page-has-block-title? title)
          true
          (if (zero? attempt)
            (throw (ex-info "new-block-safe failed" {:title title}))
            (do
              (util/exit-edit)
              (util/wait-timeout 80)
              (try
                (b/open-last-block)
                (catch Throwable _
                  nil))
              (util/wait-timeout 80)
              (recur (dec attempt)))))))))

(defn- save-block-safe!
  [original-title updated-title]
  (loop [attempt 4]
    (let [saved?
          (try
            (b/save-block updated-title)
            true
            (catch Throwable _
              (page-has-block-title? updated-title)))]
      (cond
        saved?
        true

        (zero? attempt)
        (throw (ex-info "save-block-safe failed"
                        {:original-title original-title
                         :updated-title updated-title}))

        :else
        (do
          (util/exit-edit)
          (if (page-has-block-title? original-title)
            (b/jump-to-block original-title)
            (new-block-safe! original-title))
          (recur (dec attempt)))))))

(defn- sync-by-barrier!
  ([tag]
   (sync-by-barrier! tag nil))
  ([tag checkpoints]
   ;; Exiting edit mode can itself enqueue a transaction. Do it on both pages
   ;; before creating either half of the causal barrier.
   (doseq [pw-page [@*page1 @*page2]]
     (w/with-page pw-page
       (util/exit-edit)))
   (let [target-tx (some->> checkpoints
                            vals
                            (filter integer?)
                            seq
                            (apply max))]
     ;; Ensure both pages have observed all prior edit/undo-redo txs first.
     (when target-tx
       (w/with-page @*page1
         (rtc/wait-tx-update-to target-tx))
       (w/with-page @*page2
         (rtc/wait-tx-update-to target-tx)))
     (let [{first-marker-tx :remote-tx}
           (w/with-page @*page1
             (rtc/with-wait-tx-updated
               (new-block-safe! (str "sync-trigger-" tag))
               (util/exit-edit)))]
       (w/with-page @*page1
         (rtc/wait-tx-update-to first-marker-tx))
       (w/with-page @*page2
         (rtc/wait-tx-update-to first-marker-tx))
       ;; The second client's acknowledgement is ordered after it observes the
       ;; first marker and after any of its own pre-barrier queued transactions.
       (let [{ack-marker-tx :remote-tx}
             (w/with-page @*page2
               (rtc/with-wait-tx-updated
                 (new-block-safe! (str "sync-ack-" tag))
                 (util/exit-edit)))]
         (w/with-page @*page1
           (rtc/wait-tx-update-to ack-marker-tx))
         (w/with-page @*page2
           (rtc/wait-tx-update-to ack-marker-tx))
         ack-marker-tx)))))

(defn- seed-long-nested-page!
  [seed]
  (let [seed-blocks (max 20 (env-int "DB_SYNC_E2E_STRESS_SEED_BLOCKS" stress-default-seed-blocks))
        rng (java.util.Random. (long (+ seed 97)))
        titles
        (w/with-page @*page1
          (util/exit-edit)
          (loop [i 0
                 depth 0
                 titles #{}]
            (if (< i seed-blocks)
              (let [title (format "seed-r%s-%03d" seed i)
                    target-depth (.nextInt rng (inc stress-max-seed-depth))]
                (new-block-safe! title)
                (recur (inc i)
                       (align-depth! depth target-depth)
                       (conj titles title)))
              (do
                (util/exit-edit)
                titles))))]
    (sync-by-barrier! (str "seed-" seed))
    titles))

(defn- next-action
  [rng]
  (nth random-edit-actions
       (.nextInt rng (count random-edit-actions))))

(defn- delete-existing-random-block!
  [rng known-titles]
  (loop [attempt 8]
    (if (zero? attempt)
      0
      (let [titles (vec @known-titles)]
        (if (empty? titles)
          0
          (let [title (nth titles (.nextInt rng (count titles)))
                deleted?
                (try
                  (b/jump-to-block title)
                  (b/delete-blocks)
                  true
                  (catch Throwable _
                    false))]
            (if deleted?
              (do
                (swap! known-titles disj title)
                1)
              (recur (dec attempt)))))))))

(defn- random-edit-op!
  [rng known-titles client-prefix round op-idx]
  (let [base (format "%s-r%s-op%s" client-prefix round op-idx)]
    (case (next-action rng)
      :new
      (let [title (str base "-new")]
        (new-block-safe! title)
        (swap! known-titles conj title)
        1)

      :save
      (let [save-title (str base "-save-updated")]
        (new-block-safe! (str base "-save"))
        (save-block-safe! (str base "-save") save-title)
        (swap! known-titles conj save-title)
        2)

      :indent-outdent
      (let [title (str base "-nest")]
        (new-block-safe! title)
        (swap! known-titles conj title)
        (+ 1
           (if (try-indent!) 1 0)
           (if (try-outdent!) 1 0)))

      :delete-existing
      (delete-existing-random-block! rng known-titles)

      :undo
      (do
        (b/undo)
        0)

      :redo
      (do
        (b/redo)
        0))))

(defn- local-random-edit-batch!
  [rng known-titles client-prefix round]
  (let [ops (max 1 (env-int "DB_SYNC_E2E_STRESS_OPS_PER_CLIENT" stress-default-ops-per-client))]
    (loop [i 0
           undo-steps 0]
      (if (< i ops)
        (recur (inc i)
               (+ undo-steps
                  (random-edit-op! rng known-titles client-prefix round i)))
        (do
          (util/exit-edit)
          undo-steps)))))

(defn- local-undo-redo-batch!
  [undo-steps]
  (let [steps (max 1 undo-steps)]
    ;; Undo and redo exactly what this client edited in the current round.
    (b/open-last-block)
    (dotimes [_ steps]
      (b/undo))
    (dotimes [_ steps]
      (b/redo))
    (util/exit-edit)))

(def ^:private stress-client-op-timeout-ms 120000)

(defn- await-future!
  [f label]
  (let [result (deref f stress-client-op-timeout-ms ::timeout)]
    (when (= result ::timeout)
      (throw (ex-info "parallel client op timed out"
                      {:label label
                       :timeout-ms stress-client-op-timeout-ms})))
    result))

(defn- run-two-clients-in-parallel!
  [p1-fn p2-fn]
  (let [start-signal (promise)
        p1-future (future @start-signal (p1-fn))
        p2-future (future @start-signal (p2-fn))]
    (deliver start-signal true)
    [(await-future! p1-future :p1-op)
     (await-future! p2-future :p2-op)]))

(deftest online-two-clients-undo-redo-stress-test
  (testing "two online RTC clients survive random edits + undo/redo loops on a long nested page"
    (let [rounds (max 1 (env-int "DB_SYNC_E2E_STRESS_ROUNDS" stress-default-rounds))
          seed (long (env-int "DB_SYNC_E2E_STRESS_SEED" stress-default-seed))
          p1-rng (java.util.Random. (long (+ seed 101)))
          p2-rng (java.util.Random. (long (+ seed 202)))
          known-titles (atom (seed-long-nested-page! seed))]
      (dotimes [round rounds]
        (let [p1-undo-steps (atom 0)
              p2-undo-steps (atom 0)
              ;; Phase 1: edit batches in parallel with synchronized start.
              [_ _]
              (run-two-clients-in-parallel!
               #(w/with-page @*page1
                  (reset! p1-undo-steps
                          (local-random-edit-batch! p1-rng known-titles "p1" round)))
               #(w/with-page @*page2
                  (reset! p2-undo-steps
                          (local-random-edit-batch! p2-rng known-titles "p2" round))))
              p1-edit-remote-tx (w/with-page @*page1
                                  (-> (rtc/get-rtc-tx) :local-tx))
              p2-edit-remote-tx (w/with-page @*page2
                                  (-> (rtc/get-rtc-tx) :local-tx))
              ;; Phase 2: undo+redo batches in parallel with synchronized start.
              [_ _]
              (run-two-clients-in-parallel!
               #(w/with-page @*page1
                  (local-undo-redo-batch! @p1-undo-steps))
               #(w/with-page @*page2
                  (local-undo-redo-batch! @p2-undo-steps)))
              p1-undo-remote-tx (w/with-page @*page1
                                  (-> (rtc/get-rtc-tx) :local-tx))
              p2-undo-remote-tx (w/with-page @*page2
                                  (-> (rtc/get-rtc-tx) :local-tx))]

          (sync-by-barrier!
           round
           {:p1-edit p1-edit-remote-tx
            :p2-edit p2-edit-remote-tx
            :p1-undo p1-undo-remote-tx
            :p2-undo p2-undo-remote-tx})
          (assert-two-pages-synced! (wait-for-two-pages-quiescent!))
          (assert-no-severe-sync-errors!))))))

;;; https://github.com/logseq/db-test/issues/651
(deftest issue-651-block-title-double-transit-encoded-test
  (testing "
1. create pages named \"bbb\", \"aaa\", and turn these pages into tag
2. set \"bbb\" parent to \"aaa\"
3. create a new page \"ccc\", and create a simple query with filter tags = aaa/bbb
wait for 5-10 seconds, will found that \"aaa/bbb\" became \"aaa/<encrypted-string>\"
"
    (w/with-page @*page1
      (page/new-page "aaa")
      (page/convert-to-tag "aaa")
      (page/new-page "bbb")
      (page/convert-to-tag "bbb" :extends ["aaa"])
      (page/new-page "ccc")
      (b/new-block "")
      (util/input-command "query")
      (w/click (util/-query-last "button:text('filter')"))
      (util/input "tags")
      (w/click "a.menu-link:has-text('tags')")
      (w/click "a.menu-link:has-text('bbb')")
      (util/wait-timeout 5000)          ;as described in issue-url
      )
    (let [{:keys [remote-tx]}
          (w/with-page @*page1
            (rtc/with-wait-tx-updated
              (b/new-block "done")))]
      (w/with-page @*page2
        (rtc/wait-tx-update-to remote-tx)))

    ;; The command palette no longer exposes the computed "aaa/bbb" title as
    ;; a stable test id. Inspect the synced tag relation through the public API;
    ;; #651 replaced the parent title with a transit/encryption payload.
    (doseq [p [@*page1 @*page2]]
      (w/with-page p
        (let [child (first (ls-api-call! :editor.getTagsByName "bbb"))
              parent-ids (get child ":logseq.property.class/extends")
              parent (ls-api-call! :editor.getTag (first parent-ids))]
          (is (= "bbb" (get child "title")))
          (is (= 1 (count parent-ids)))
          (is (= "aaa" (get parent "title"))))))

    (rtc/validate-graphs-in-2-pw-pages)))

(deftest paste-multiple-blocks-test
  (testing "
1. create 3 blocks
  - block1
  - block2
  - block3
2. copy these 3 blocks
3. when cursor at block3, press <enter> to create a new block
4. paste them at current position 5 times
5. validate blocks are same on both clients"
    (w/with-page @*page1
      (b/new-blocks ["block1" "block2" "block3"])
      (util/exit-edit)
      (b/select-blocks 2)
      (b/copy)
      (b/jump-to-block "block3")
      (util/repeat-keyboard 1 "Enter"))

    (dotimes [_ 5]
      (let [{:keys [remote-tx]}
            (w/with-page @*page1
              (rtc/with-wait-tx-updated
                (b/paste)))]
        (w/with-page @*page2
          (rtc/wait-tx-update-to remote-tx))))

    (let [{:keys [remote-tx]}
          (w/with-page @*page1
            (rtc/with-wait-tx-updated
              (b/new-block "sync-trigger")))]
      (w/with-page @*page2
        (rtc/wait-tx-update-to remote-tx)))

    (let [expected (vec (concat ["block1" "block2" "block3"]
                                (take (* 3 5) (cycle ["block1" "block2" "block3"]))
                                ["sync-trigger"]))]
      (w/with-page @*page1
        (util/exit-edit)
        (is (= expected
               (util/get-page-blocks-contents))))

      (w/with-page @*page2
        (util/exit-edit)
        (is (= expected
               (util/get-page-blocks-contents)))))

    (rtc/validate-graphs-in-2-pw-pages)))

(deftest asset-blocks-validate-after-init-downloaded-test
  (testing "
- add some assets in client1
- remove local graph in client2
- re-download the remote graph in client2
- compare asset-blocks data in both clients"
    (let [asset-path (.toAbsolutePath
                      (java.nio.file.Paths/get
                       "../assets/icon.png"
                       (into-array String [])))
          page-title (w/with-page @*page1 (page/get-page-name))
          expected-asset-sha256 (e2e-assets/file-sha256 asset-path)
          asset-filename* (atom nil)
          asset-block-uuid* (atom nil)]
      (w/with-page @*page1
        (let [p (w/get-page)
              before (e2e-assets/list-assets *graph-name*)
              _ (when-not (util/get-editor)
                  (b/open-last-block))
              chooser (.waitForFileChooser
                       p
                       (reify Runnable
                         (run [_]
                           (util/input-command "Upload an asset"))))]
          (.setFiles chooser (into-array java.nio.file.Path [asset-path]))
          (let [filename (e2e-assets/wait-for-new-asset!
                          *graph-name* before 60000)
                asset-block-uuid (some-> filename
                                         (string/replace #"\.[^.]+$" ""))]
            (reset! asset-filename* filename)
            (reset! asset-block-uuid* asset-block-uuid)
            (is (string? filename)
                (pr-str {:graph *graph-name*
                         :assets-before before
                         :assets (e2e-assets/list-assets *graph-name*)}))
            (is (true? (e2e-assets/wait-for-asset!
                        *graph-name* filename 60000))))
          ;; A database transaction cursor does not imply that attachment
          ;; upload has completed. Wait for both queues to drain.
          (w/wait-for "button.cloud.on.idle" {:timeout 120000})))

      (let [{:keys [remote-tx]}
            (w/with-page @*page1
              (rtc/with-wait-tx-updated
                (b/new-block "sync done")))]
        (w/with-page @*page2
          (rtc/wait-tx-update-to remote-tx)))

      (w/with-page @*page2
        (let [asset-filename @asset-filename*
              asset-block-uuid @asset-block-uuid*
              asset-block-locator (format ".ls-block[blockid='%s']"
                                          asset-block-uuid)]
          (is (string? asset-filename))
          (is (string? asset-block-uuid))
          (page/goto-page-via-api page-title)
          (w/wait-for asset-block-locator {:timeout 60000})

          (graph/remove-local-graph *graph-name*)
          (is (true? (e2e-assets/clear-assets-dir! *graph-name*)))
          (is (not (some #(= asset-filename %)
                         (e2e-assets/list-assets *graph-name*))))

          (graph/wait-for-remote-graph *graph-name*)
          (graph/switch-graph *graph-name* true false)
          (page/goto-page-via-api page-title)

          ;; Browser E2E exercises the lazy download path. Electron and CLI
          ;; additionally prefetch these files during graph download.
          (is (true? (e2e-assets/wait-for-asset!
                      *graph-name* asset-filename 60000))
              (pr-str {:graph *graph-name*
                       :asset-filename asset-filename
                       :assets (e2e-assets/list-assets *graph-name*)}))
          (w/wait-for ".asset-container img" {:timeout 60000})
          (is (= expected-asset-sha256
                 (e2e-assets/asset-sha256 *graph-name* asset-filename))
              "the restored bytes must match the uploaded file")
          (is (pos? (w/eval-js
                     "document.querySelector('.asset-container img')?.naturalWidth || 0"))
              "the restored image must decode successfully")
          (w/wait-for asset-block-locator {:timeout 60000})))

      (rtc/validate-graphs-in-2-pw-pages))))

(deftest issue-683-paste-large-block-test
  (testing "Copying and pasting a large block of text into sync-ed graph causes sync to fail"
    (let [large-text (slurp (io/resource "large_text.txt"))]
      (w/with-page @*page1
        (w/eval-js (str "navigator.clipboard.writeText(" (json/write-value-as-string large-text) ")")))
      (let [{:keys [remote-tx]}
            (w/with-page @*page1
              (rtc/with-wait-tx-updated
                (b/new-block "")
                (b/paste)))]
        (w/with-page @*page2
          (rtc/wait-tx-update-to remote-tx)))

      (rtc/validate-graphs-in-2-pw-pages))))
