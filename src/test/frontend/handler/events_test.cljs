(ns frontend.handler.events-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.components.rtc.download-progress :as download-progress]
            [frontend.config :as config]
            [frontend.handler.db-based.sync :as rtc-handler]
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
