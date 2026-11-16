(ns frontend.handler.user-navigation-test
  (:require [cljs.test :refer [async deftest is]]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.diagnostics :as diagnostics]
            [frontend.handler.db-based.recent :as recent]
            [frontend.handler.graph :as graph]
            [frontend.handler.notification :as notification]
            [frontend.handler.user :as user]
            [frontend.handler.user-navigation :as navigation]
            [frontend.persist-db :as persist-db]
            [frontend.persist-db.remote :as remote]
            [frontend.rfx :as rfx]
            [frontend.state :as state]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

(defn- with-navigation
  [{:keys [runtime-gate fail-open? fail-write?]} f]
  (let [data (atom {:git/current-repo "graph-a" :ui/recent-pages {}})
        calls (atom [])
        notices (atom [])
        errors (atom [])
        logs (atom [])
        listeners (atom {})
        opening-failed? (atom false)
        worker (atom nil)
        ready? (atom true)
        window (.-window js/globalThis)
        log-handler diagnostics/record-renderer-error!
        snapshot (fn [] {:scope (str "scope-" (:git/current-repo @data))
                         :token ["transfer"] :favorites [] :recents []})]
    (letfn [(put! [k v]
              (let [before @data]
                (swap! data assoc k v)
                (doseq [listener (vals @listeners)] (listener before @data))))
            (notes [repo]
              (fn [method requested-repo & _]
                (is (= repo requested-repo))
                (case method
                  :thread-api/user-navigation-source
                  (p/resolved {:remote-scope (str "scope-" repo) :local-scope (str "local-" repo)
                               :domain {:device-id "device" :values {}}})
                  :thread-api/user-navigation-nodes (p/resolved [])
                  :thread-api/pull (:pull @data))))
            (invoke [method & args]
              (swap! calls conj method)
              (case method
                :thread-api/create-or-open-db
                (when (and fail-open? (compare-and-set! opening-failed? false true))
                  (p/rejected (ex-info "Synthetic open failure: Bearer synthetic-secret" {:code :test/open-failed})))
                :thread-api/configure-user-navigation {:device-id "device"}
                (:thread-api/user-navigation-add-scope :thread-api/user-navigation-snapshot) (snapshot)
                (:thread-api/user-navigation-visit :thread-api/user-navigation-set-favorite)
                (if (some-> fail-write? deref)
                  (p/rejected (ex-info "Synthetic save failure" {:code :test/save-failed}))
                  (do (swap! data update :writes (fnil conj []) (vec args)) (snapshot)))
                nil))]
      (reset! worker (notes "graph-a"))
      (set! (.-window js/globalThis) #js {:apis #js {} :addEventListener (fn [& _]) :removeEventListener (fn [& _])})
      (log/add-handler log-handler)
      (-> (p/with-redefs [navigation/enabled? (constantly true)
                          config/db-sync-http-base (constantly "https://service.example")
                          config/db-sync-ws-url (constantly "wss://service.example")
                          config/get-custom-sync-server-url (constantly "https://service.example")
                          util/electron? (constantly true)
                          user/user-uuid (constantly "owner")
                          user/sync-app-state-payload (constantly {})
                          graph/<get-graph-directory-renames #(p/resolved [])
                          persist-db/remote-db (atom {:client {:root-dir "/synthetic"}})
                          state/*db-worker worker
                          state/db-worker-ready? ready?
                          state/get-current-repo #(:git/current-repo @data)
                          state/get-state (fn ([] @data) ([k] (get @data k)))
                          state/set-state! put!
                          state/<invoke-db-worker (fn [& args] (apply @worker args))
                          state/pub-event! #(swap! errors conj (get-in % [1 :error]))
                          notification/show! (fn [& args]
                                               (swap! notices conj args)
                                               (put! :notification/shown true))
                          rfx/listen! #(swap! listeners assoc %1 %2)
                          remote/start! (fn [_] (swap! calls conj :start) {:wrapped-worker invoke})
                          remote/stop! (fn [_] (swap! calls conj :stop) (p/resolved true))
                          ipc/ipc (fn [method & args]
                                    (swap! calls conj method)
                                    (case method
                                      :user-navigation-db-runtime
                                      (if (and runtime-gate (= 1 (get (frequencies @calls) method)))
                                        runtime-gate
                                        (p/resolved {:repo "private-navigation"}))
                                      :release-user-navigation-runtime (p/resolved true)
                                      :diagnostics/record-error (do (swap! logs conj (first args)) (p/resolved true))))]
            (p/hcat (fn [value error]
                      (remove-watch worker :frontend.handler.user-navigation/context)
                      (swap! data assoc :git/current-repo nil)
                      (p/let [_ (navigation/<ensure-context!)
                              _ (p/delay 0)]
                        (if error (throw error) value)))
                    (f {:initial (navigation/start!) :data data :calls calls :notices notices
                        :errors errors :logs logs :worker worker :ready? ready? :notes notes :put! put!})))
          (p/finally (fn []
                       (log/remove-handler log-handler)
                       (set! (.-window js/globalThis) window)))))))

(defn- finish [result done]
  (-> result (p/catch #(is false (or (.-stack %) (str %)))) (p/finally done)))

(deftest graph-handoff-reuses-valid-account-opening-test
  (async done
    (let [gate (p/deferred)]
      (finish
       (with-navigation {:runtime-gate gate}
         (fn [{:keys [initial data calls notices worker notes]}]
           (p/let [_ (p/delay 0)
                   _ (is (= 1 (get (frequencies @calls) :user-navigation-db-runtime)))
                   _ (swap! data assoc :git/current-repo "graph-b")
                   _ (reset! worker (notes "graph-b"))]
             (let [current (navigation/<ensure-context!)]
               (p/resolve! gate {:repo "private-navigation"})
               (p/let [old (p/catch initial (fn [error] {:error error}))
                       _ current]
                 (is (= :navigation-context-closed (:code (ex-data (:error old)))))
                 (is (= "graph-b" (get-in @data [:user-navigation/snapshot :repo])))
                 (is (not (get-in @data [:user-navigation/snapshot :loading?])))
                 (is (nil? (get (frequencies @calls) :release-user-navigation-runtime)))
                 (is (= 1 (get (frequencies @calls) :user-navigation-db-runtime)))
                 (is (empty? @notices))
                 (p/let [_ (navigation/<nodes true)
                         _ (navigation/<set-favorite! "page-b" true)]
                   (is (= "scope-graph-b" (get-in @data [:writes 0 1])))))))))
       done))))

(deftest new-graph-takes-over-after-waiting-for-old-opening-failure-test
  (async done
    (let [gate (p/deferred)]
      (finish
       (with-navigation {:runtime-gate gate :fail-open? true}
         (fn [{:keys [initial data calls notices worker notes]}]
           (p/let [_ (p/delay 0)
                   _ (swap! data assoc :git/current-repo "graph-b")
                   _ (reset! worker (notes "graph-b"))]
             (let [current (navigation/<ensure-context!)]
               (p/let [_ (p/delay 0)
                       _ (is (= 1 (get (frequencies @calls) :user-navigation-db-runtime)))
                       _ (p/resolve! gate {:repo "private-navigation"})
                       old (p/catch initial (fn [error] {:error error}))
                       _ current]
                 (is (= :test/open-failed (:code (ex-data (:error old)))))
                 (is (= 1 (get (frequencies @calls) :release-user-navigation-runtime)))
                 (is (= 2 (get (frequencies @calls) :user-navigation-db-runtime)))
                 (is (= "graph-b" (get-in @data [:user-navigation/snapshot :repo])))
                 (is (empty? @notices))
                 (p/let [_ (navigation/<set-favorite! "page-b" true)]
                   (is (= 2 (get (frequencies @calls) :thread-api/create-or-open-db)))))))))
       done))))

(deftest failed-initialization-is-diagnosable-and-recoverable-without-loop-test
  (async done
    (finish
     (with-navigation {:fail-open? true}
       (fn [{:keys [initial calls notices logs put!]}]
         (p/let [result (p/catch initial (fn [error] {:error error}))
                 _ (is (= :test/open-failed (:code (ex-data (:error result)))))
                 _ (put! :unrelated/state true)
                 _ (p/delay 10)]
           (is (= 1 (get (frequencies @calls) :thread-api/create-or-open-db)))
           (is (= 1 (count @notices)))
           (is (= :test/open-failed (:code (first @logs))))
           (is (not (string/includes? (:message (first @logs)) "synthetic-secret")))
           (p/let [_ (navigation/<set-favorite! "page-a" true)]
             (is (= 2 (get (frequencies @calls) :thread-api/create-or-open-db)))
             (is (= 1 (count @notices)))))))
     done)))

(deftest current-save-failure-is-not-hidden-or-replayed-test
  (async done
    (let [failed? (atom true)]
      (finish
       (with-navigation {:fail-write? failed?}
         (fn [{:keys [initial calls notices errors]}]
           (p/let [_ initial
                   result (p/catch (navigation/<set-favorite! "page-a" true) (fn [error] {:error error}))
                   _ (p/delay 10)]
             (is (= :test/save-failed (:code (ex-data (:error result)))))
             (is (= 1 (count @notices)))
             (is (= [(:error result)] @errors))
             (is (= 1 (get (frequencies @calls) :thread-api/user-navigation-set-favorite)))
             (reset! failed? false)
             (p/let [_ (navigation/<set-favorite! "page-a" true)]
               (is (= 2 (get (frequencies @calls) :thread-api/user-navigation-set-favorite)))
               (is (= 1 (get (frequencies @calls) :thread-api/create-or-open-db)))))))
       done))))

(deftest delayed-recent-stays-with-its-originating-graph-and-worker-test
  (async done
    (finish
     (with-navigation {}
       (fn [{:keys [initial data worker ready? notes put!]}]
         (p/let [_ initial
                 _ (p/doseq [transition [:switching :switched :replaced-worker]]
                     (let [gate (p/deferred)
                           repo (:git/current-repo @data)]
                       (swap! data assoc :pull gate)
                       (let [visit (recent/add-page-to-recent! 42 false)]
                         (p/let [_ (p/delay 0)]
                           (case transition
                             :switching (do (put! :db/restoring? true) (reset! ready? false))
                             :switched (do (swap! data assoc :git/current-repo "graph-b") (reset! worker (notes "graph-b")))
                             :replaced-worker (reset! worker (notes repo)))
                           (p/resolve! gate {:block/uuid "old-page"})
                           (p/let [_ visit]
                             (is (empty? (:writes @data)))
                             (reset! ready? true)
                             (put! :db/restoring? false)
                             (navigation/<ensure-context!))))))]
           (swap! data assoc :pull (p/resolved {:block/uuid "current-page"}))
           (p/let [_ (recent/add-page-to-recent! 43 false)]
             (is (= "current-page" (get-in @data [:writes 0 2])))))))
     done)))
