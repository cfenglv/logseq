(ns frontend.handler.events-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.components.rtc.download-progress :as download-progress]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.conn :as db-conn]
            [frontend.db.transact :as db-transact]
            [frontend.handler.db-based.sync :as rtc-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.events :as events]
            [frontend.handler.events.rtc-error :as rtc-error]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.db.frontend.schema :as db-schema]
            [promesa.core :as p]))

(defn- finish-async!
  [promise done]
  (-> promise
      (p/then (fn [_] (done)))
      (p/catch
       (fn [error]
         (is false (str error))
         (done)))))

(def math-block
  {:db/id 1
   :block/uuid #uuid "11111111-1111-1111-1111-111111111111"
   :block/title "ordinary"})

(deftest math-type-transition-is-one-outliner-transaction-test
  (async done
         (let [apply-calls (atom [])
               edit-calls (atom [])
               ordinary-editor-info {:block-uuid (:block/uuid math-block)
                                     :container-id 17
                                     :start-pos 10
                                     :end-pos 10}]
           (finish-async!
            (-> (p/with-redefs
                  [db/entity (fn [id]
                               (when-not (= id :logseq.kv/latest-code-lang)
                                 (if (seq @apply-calls)
                                   (assoc math-block
                                          :block/title "x"
                                          :logseq.property.node/display-type :math)
                                   math-block)))
                   db-conn/get-db (constantly ::conn)
                   db-transact/apply-outliner-ops
                   (fn [conn ops opts]
                     (swap! apply-calls conj [conn ops opts])
                     (p/resolved {:blocks [math-block]}))
                   editor-handler/wrap-parse-block
                   (fn [block _opts] block)
                   editor-handler/edit-block!
                   (fn [& args] (swap! edit-calls conj args))]
                  (events/handle
                   [:editor/upsert-type-block
                    {:block (assoc math-block :block/title "x")
                     :type :math
                     :update-current-block? true
                     :preserve-editor-state? true
                     :math-transition-editor-info ordinary-editor-info
                     :math-transition-rollback
                     {:ordinary-editing-block math-block
                      :transition-token (random-uuid)}}]))
                (p/then
                 (fn []
                   (is (= 1 (count @apply-calls)))
                   (let [[conn ops opts] (first @apply-calls)]
                     (is (= ::conn conn))
                     (is (= :upsert-type-block (:outliner-op opts)))
                     (is (= ordinary-editor-info (:undo-redo/editor-info opts))
                         "The one undo record must use the pre-remap ordinary cursor/focus")
                     (is (= [:save-block]
                            (mapv first ops)))
                     (is (= ["x" :math]
                            [(get-in ops [0 1 0 :block/title])
                             (get-in ops [0 1 0 :logseq.property.node/display-type])]))
                     (is (= [:logseq.property.node/display-type]
                            (get-in ops [0 1 1 :retract-attributes]))))
                   (is (empty? @edit-calls)
                       "The atomic Math transition preserves the live focus/cursor"))))
            done))))

(deftest failed-math-type-transition-rolls-back-the-optimistic-editor-test
  (async done
         (let [failure (ex-info "synthetic Math tx failure" {})
               rollback {:input-id "edit-block-test"
                         :ordinary-editing-block math-block
                         :transition-token (random-uuid)}
               rollback-calls (atom [])]
           (-> (p/with-redefs
                 [db/entity (fn [id]
                              (when-not (= id :logseq.kv/latest-code-lang)
                                math-block))
                  db-conn/get-db (constantly ::conn)
                  db-transact/apply-outliner-ops
                  (fn [& _] (p/rejected failure))
                  editor-handler/wrap-parse-block
                  (fn [block _opts] block)
                  editor-handler/rollback-math-transition!
                  (fn [payload] (swap! rollback-calls conj payload))]
                 (events/handle
                  [:editor/upsert-type-block
                   {:block (assoc math-block :block/title "x")
                    :type :math
                    :update-current-block? true
                    :preserve-editor-state? true
                    :math-transition-rollback rollback}]))
               (p/then (fn [_]
                         (is false "The rejected outliner transaction must propagate")
                         (done)))
               (p/catch (fn [caught]
                          (is (identical? failure caught))
                          (is (= [rollback] @rollback-calls))
                          (done)))))))

(deftest native-tablet-rtc-download-shows-and-hides-progress-on-success-test
  (async done
         (let [graph-name "tablet-success"
               graph-uuid "success-uuid"
               calls (atom [])]
           (finish-async!
            (-> (p/with-redefs
                  [util/mobile? (constantly false)
                   mobile-util/native-platform? (constantly true)
                   download-progress/show! (fn [name]
                                             (swap! calls conj [:show name]))
                   download-progress/hide! (fn []
                                             (swap! calls conj [:hide]))
                   rtc-handler/<rtc-download-graph!
                   (fn [name uuid e2ee?]
                     (swap! calls conj [:download name uuid e2ee?])
                     (p/resolved nil))
                   rtc-handler/<get-remote-graphs
                   (fn []
                     (swap! calls conj [:refresh])
                     (p/resolved nil))
                   state/pub-event!
                   (fn [event]
                     (swap! calls conj [:switch event])
                     nil)]
                  (events/handle
                   [:rtc/download-remote-graph
                    graph-name
                    graph-uuid
                    db-schema/version
                    false]))
                (p/then
                 (fn []
                   (is (= [[:show graph-name]
                           [:download graph-name graph-uuid false]
                           [:refresh]
                           [:switch
                            [:graph/switch
                             (str config/db-version-prefix graph-name)
                             {:rtc-download? true}]]
                           [:hide]]
                          @calls)
                       "native tablet download must keep progress visible through graph switch and hide it afterward"))))
            done))))

(deftest native-tablet-rtc-download-hides-progress-on-failure-without-switching-test
  (async done
         (let [graph-name "tablet-failure"
               graph-uuid "failure-uuid"
               failure (ex-info "download failed" {:graph-uuid graph-uuid})
               calls (atom [])]
           (finish-async!
            (-> (p/with-redefs
                  [util/mobile? (constantly false)
                   mobile-util/native-platform? (constantly true)
                   download-progress/show! (fn [name]
                                             (swap! calls conj [:show name]))
                   download-progress/hide! (fn []
                                             (swap! calls conj [:hide]))
                   rtc-handler/<rtc-download-graph!
                   (fn [name uuid e2ee?]
                     (swap! calls conj [:download name uuid e2ee?])
                     (p/rejected failure))
                   rtc-handler/<get-remote-graphs
                   (fn []
                     (swap! calls conj [:unexpected-refresh])
                     (p/resolved nil))
                   state/pub-event!
                   (fn [event]
                     (swap! calls conj [:unexpected-switch event])
                     nil)
                   rtc-error/download-decrypt-failed? (constantly false)]
                  (events/handle
                   [:rtc/download-remote-graph
                    graph-name
                    graph-uuid
                    db-schema/version
                    false]))
                (p/then
                 (fn []
                   (is (= [[:show graph-name]
                           [:download graph-name graph-uuid false]
                           [:hide]]
                          @calls)
                       "failure must hide native tablet progress without refreshing or switching graphs"))))
            done))))
