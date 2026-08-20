(ns frontend.modules.shortcut.config-test
  (:require ["fs" :as fs]
            ["path" :as node-path]
            [cljs.test :refer [deftest is testing]]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.route :as route-handler]
            [frontend.modules.shortcut.config :as shortcut-config]
            [frontend.state :as state]
            [frontend.test.helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(deftest graph-db-save-shortcut-does-not-trigger-legacy-save-event
  (testing "mod+s in db graph sends new save-info event"
    (let [events* (atom [])]
      (with-redefs [state/pub-event! (fn [event]
                                       (swap! events* conj event))]
        ((get-in shortcut-config/all-built-in-keyboard-shortcuts
                 [:graph/db-save :fn]))
        (is (= [[:graph/db-save-shortcut]]
               @events*))))))

(deftest graph-db-save-shortcut-electron-uses-backup-flow
  (let [events-source-path (node-path/join (.cwd js/process)
                                           "src"
                                           "main"
                                           "frontend"
                                           "handler"
                                           "events.cljs")
        source (.toString (fs/readFileSync events-source-path) "utf8")]
    (is (not (.includes source "Manual save is no longer required.")))
    (is (.includes source "(persist-db/export-current-graph!"))
    (is (.includes source ":succ-notification? true"))))

(deftest-async math-recovery-failure-stops-public-shortcut-side-effects
  (let [transition (p/deferred)
        recovery-error (ex-info "Math transition recovery failed"
                                {:type :math-transition/recovery-failed})
        side-effects (atom [])
        reports (atom [])
        unhandled (atom [])
        previous-console-error (.-error js/console)
        unhandled-handler #(swap! unhandled conj %)
        shortcut-fn #(get-in shortcut-config/all-built-in-keyboard-shortcuts
                             [% :fn])]
    (.on js/process "unhandledRejection" unhandled-handler)
    (set! (.-error js/console)
          (fn [& args]
            (when (= "Math transition recovery failed" (first args))
              (swap! reports conj (vec args)))))
    (->
     (p/with-redefs [editor-handler/escape-editing (fn [& _] transition)
                     state/get-search-mode (constantly false)
                     state/pub-event! #(swap! side-effects conj %)
                     route-handler/go-to-search!
                     #(swap! side-effects conj [:search %])]
       (let [results [((shortcut-fn :editor/escape-editing) nil nil)
                      ((shortcut-fn :go/search))
                      ((shortcut-fn :graph/open))
                      ((shortcut-fn :graph/remove))
                      ((shortcut-fn :shell/run))]]
         (p/reject! transition recovery-error)
         (p/let [settled (p/all results)
                 _ (p/delay 25)]
           (is (= (repeat 5 {:status :recovery-failed}) settled))
           (is (empty? @unhandled)
               "The actual shortcut dispatcher may discard every result")
           (is (= [["Math transition recovery failed" "shortcut-escape"]]
                  @reports)
               "One content-free report covers the shared failed transition")
           (is (empty? @side-effects)
               "Search, graph and shell actions remain behind successful escape"))))
     (p/finally
      (fn []
        (.off js/process "unhandledRejection" unhandled-handler)
        (set! (.-error js/console) previous-console-error))))))
