(ns electron.updater-test
  (:require ["events" :refer [EventEmitter]]
            [cljs.test :refer [async deftest is testing]]
            [electron.updater :as updater]
            [promesa.core :as p]))

(defn- updater-error
  [message code]
  (doto (js/Error. message)
    (aset "code" code)))

(defn- run-install-scenario!
  [{:keys [failure]}]
  (let [events (atom [])
        dirty? (atom true)
        running? (atom true)
        child-spawned? (atom false)
        unhandled (atom [])
        unhandled-handler (fn [error]
                            (swap! unhandled conj error))
        verify! (fn []
                  (swap! events conj [:verify @dirty? @running?])
                  (case failure
                    :invalid-signature
                    (p/rejected
                     (updater-error "invalid project signature"
                                    "INVALID_SIGNATURE"))

                    :preflight
                    (p/rejected
                     (updater-error "project update preflight failed"
                                    "PREFLIGHT_FAILED"))

                    (p/resolved true)))
        spawn-install!
        (fn []
          (swap! events conj [:spawn-call @dirty? @running?])
          (case failure
            :helper-missing
            (throw (updater-error "helper missing" "ENOENT"))

            :helper-permission
            (throw (updater-error "helper is not executable" "EACCES"))

            (:child-spawn-error :success)
            (let [child (EventEmitter.)]
              (js/setTimeout
               (fn []
                 (if (= failure :child-spawn-error)
                   (.emit child "error"
                          (updater-error "child spawn failed" "EIO"))
                   (do
                     (reset! child-spawned? true)
                     (swap! events conj
                            [:child-spawn @dirty? @running?])
                     (.emit child "spawn"))))
               0)
              child)))
        set-quit-dirty! (fn [value]
                          (swap! events conj
                                 [:dirty value @child-spawned? @running?])
                          (reset! dirty? value))
        quit-app! (fn []
                    (swap! events conj [:quit @dirty? @child-spawned?])
                    (reset! running? false))
        emit-error! (fn [error]
                      (swap! events conj
                             [:ui-error
                              (.-code error)
                              @dirty?
                              @running?]))]
    (.on js/process "unhandledRejection" unhandled-handler)
    (-> (updater/run-project-signed-install!
         {:verify! verify!
          :spawn-install! spawn-install!
          :set-quit-dirty! set-quit-dirty!
          :quit-app! quit-app!
          :emit-error! emit-error!})
        (p/then
         (fn [_]
           (p/let [_ (p/delay 0)]
             {:events @events
              :dirty? @dirty?
              :running? @running?
              :unhandled @unhandled})))
        (p/finally
         (fn []
           (.off js/process "unhandledRejection" unhandled-handler))))))

(deftest install-failures-keep-dirty-protection-and-report-to-updater-ui
  (async done
    (letfn [(run-cases [[[failure expected-code] & remaining]]
              (if-not failure
                (p/resolved nil)
                (-> (p/let [{:keys [events dirty? running? unhandled]}
                            (run-install-scenario! {:failure failure})]
                      (testing (name failure)
                        (is dirty?)
                        (is running?)
                        (is (empty? unhandled))
                        (is (not-any? #(and (= :dirty (first %))
                                            (false? (second %)))
                                      events))
                        (is (not-any? #(= :quit (first %)) events))
                        (if (contains? #{:invalid-signature :preflight}
                                       failure)
                          (is (not-any? #(= :spawn-call (first %)) events))
                          (is (= 1
                                 (count
                                  (filter #(= :spawn-call (first %))
                                          events)))))
                        (is (= 1 (count (filter #(= :ui-error (first %))
                                                events))))
                        (is (= expected-code
                               (second
                                (first
                                 (filter #(= :ui-error (first %))
                                         events)))))
                        (is (every? true?
                                    (mapcat
                                     (fn [event]
                                       (case (first event)
                                         :verify [(second event)
                                                  (nth event 2)]
                                         :spawn-call [(second event)
                                                      (nth event 2)]
                                         :ui-error [(nth event 2)
                                                    (nth event 3)]
                                         []))
                                     events)))))
                    (p/then (fn [] (run-cases remaining))))))]
      (-> (run-cases
           [[:invalid-signature "INVALID_SIGNATURE"]
            [:preflight "PREFLIGHT_FAILED"]
            [:helper-missing "ENOENT"]
            [:helper-permission "EACCES"]
            [:child-spawn-error "EIO"]])
          (p/catch
           (fn [error]
             (is false (str "install failure escaped its UI path: " error))))
          (p/finally done)))))

(deftest successful-install-disables-dirty-protection-only-after-child-spawn
  (async done
    (-> (p/let [{:keys [events dirty? running? unhandled]}
                (run-install-scenario! {:failure :success})]
          (is (false? dirty?))
          (is (false? running?))
          (is (empty? unhandled))
          (is (= [[:verify true true]
                  [:spawn-call true true]
                  [:child-spawn true true]
                  [:dirty false true true]
                  [:quit false true]]
                 events)))
        (p/catch
         (fn [error]
           (is false (str "successful install failed: " error))))
        (p/finally done))))
