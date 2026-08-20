(ns electron.updater-test
  (:require ["node:child_process" :refer [spawnSync]]
            ["events" :refer [EventEmitter]]
            ["electron-updater/out/AppUpdater" :as app-updater-module]
            [clojure.string :as string]
            [cljs.test :refer [async deftest is testing]]
            [electron.updater :as updater]
            [frontend.test.electron-logger-stub :as logger-stub]
            [goog.object :as gobj]
            [promesa.core :as p]))

(defn- updater-error
  [message code]
  (doto (js/Error. message)
    (aset "code" code)))

(defn- target-asset
  [platform arch version]
  (case platform
    "darwin" (str "Logseq-darwin-" arch "-" version ".zip")
    "win32" (str "Logseq-win-" arch "-" version "-nsis.exe")
    "linux" (str "Logseq-linux-"
                 (if (= arch "x64") "x86_64" arch)
                 "-"
                 version
                 ".AppImage")))

(defn- update-info
  [version tag asset & [minimum-system-version]]
  (let [info #js {:version version
                  :files #js [#js {:url asset}]}]
    (when-not (= tag ::absent)
      (aset info "tag" (when-not (= tag ::null) tag)))
    (when minimum-system-version
      (aset info "minimumSystemVersion" minimum-system-version))
    info))

(defn- reset-production-policy-caches!
  []
  (when-let [namespace-object
             (.getObjectByName js/goog "electron.updater")]
    (doseq [key (array-seq (js/Object.keys namespace-object))
            :when (re-find #"(?:policy|signature|support)"
                           (string/lower-case key))
            :let [candidate (aget namespace-object key)]
            :when (some? candidate)
            :when (some?
                   (aget candidate
                         "cljs$core$IReset$_reset_BANG_$arity$2"))]
      (reset! candidate nil))))

(defn- configure-production-policy!
  []
  (let [auto-updater
        (aget js/globalThis "__LOGSEQ_TEST_AUTO_UPDATER__")]
    (reset-production-policy-caches!)
    (.resetContractState auto-updater)
    (let [dispose! (updater/init-updater {:win nil})
          raw-policy (.-isUpdateSupported auto-updater)]
      {:auto-updater auto-updater
       :dispose! dispose!
       :policy raw-policy})))

(defn- run-nightly-policy-subprocess
  [nightly-version]
  (let [env (js/Object.assign #js {} (.-env js/process))
        _ (gobj/set env "LOGSEQ_TEST_COMPILED_VERSION" nightly-version)
        result
        (spawnSync
         (.-execPath js/process)
         #js ["--require"
              "./scripts/fixtures/electron-test-preload.cjs"
              "static/tests.js"
              "-v"
              (str (namespace ::nightly-policy)
                   "/nightly-production-update-policy-controls-real-app-updater-downloads")]
         #js {:cwd (.cwd js/process)
              :encoding "utf8"
              :env env})]
    {:status (.-status result)
     :output (str (.-stdout result) (.-stderr result))}))

(defn- run-app-updater-flow!
  [current info production-policy]
  (let [app-updater
        (new (.-AppUpdater app-updater-module)
             nil
             #js {:version current})
        downloads (atom 0)]
    (set! (.-logger app-updater)
          #js {:debug (fn [])
               :error (fn [])
               :info (fn [])
               :warn (fn [])})
    (set! (.-allowDowngrade app-updater) false)
    (set! (.-autoDownload app-updater) true)
    (set! (.-isUpdateSupported app-updater) production-policy)
    (set! (.-isUserWithinRollout app-updater) (fn [_] true))
    (set! (.-getUpdateInfoAndProvider app-updater)
          (fn []
            (p/resolved #js {:info info :provider #js {}})))
    (set! (.-downloadUpdate app-updater)
          (fn [& _]
            (swap! downloads inc)
            (p/resolved #js ["controlled-download-fixture"])))
    (p/let [result (.doCheckForUpdates app-updater)
            _ (or (.-downloadPromise result) (p/resolved nil))]
      {:available? (true? (.-isUpdateAvailable result))
       :downloads @downloads})))

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
              (set! (.-unref child)
                    (fn []
                      (swap! events conj
                             [:unref @dirty? @running?])
                      child))
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

(deftest production-update-policy-controls-real-app-updater-downloads
  (async done
    (let [platform (.-platform js/process)
          arch (.-arch js/process)
          other-arch (if (= arch "x64") "arm64" "x64")
          other-platform (if (= platform "darwin") "win32" "darwin")
          stable-current "2.0.1-selfhost.5"
          stable-next "2.0.1-selfhost.6"
          nightly-current "2.0.1-selfhost.6.nightly.20260728"
          nightly-next "2.0.1-selfhost.6.nightly.20260729"
          disposers (atom [])
          stable-config (configure-production-policy!)
          _ (swap! disposers conj (:dispose! stable-config))
          stable-policy (:policy stable-config)
          stable-asset (target-asset platform arch stable-next)
          stable-good (update-info stable-next stable-next stable-asset)
          stable-wrong-tag
          (update-info stable-next "2.0.1-selfhost.999" stable-asset)
          stable-wrong-arch
          (update-info stable-next
                       stable-next
                       (target-asset platform other-arch stable-next))
          stable-wrong-platform
          (update-info stable-next
                       stable-next
                       (target-asset other-platform arch stable-next))
          stable-wrong-version
          (update-info stable-next
                       stable-next
                       (target-asset platform arch "2.0.1-selfhost.7"))
          stable-cross-track
          (update-info nightly-next
                       "nightly"
                       (target-asset platform arch nightly-next))
          stable-minimum-system
          (update-info stable-next
                       stable-next
                       stable-asset
                       "999.0.0")]
      (logger-stub/reset-calls!)
      (is (= stable-current updater/electron-version)
          "stable test process must load production policy with the stable version")
      (-> (p/let [raw-good (stable-policy stable-good)
                  good (run-app-updater-flow!
                        stable-current stable-good stable-policy)
                  wrong-tag (run-app-updater-flow!
                             stable-current stable-wrong-tag stable-policy)
                  wrong-arch (run-app-updater-flow!
                              stable-current stable-wrong-arch stable-policy)
                  wrong-platform
                  (run-app-updater-flow!
                   stable-current stable-wrong-platform stable-policy)
                  wrong-version
                  (run-app-updater-flow!
                   stable-current stable-wrong-version stable-policy)
                  cross-track
                  (run-app-updater-flow!
                   stable-current stable-cross-track stable-policy)
                  unsupported-system
                  (run-app-updater-flow!
                   stable-current stable-minimum-system stable-policy)]
            (is (true? raw-good)
                (str "production-installed stable support policy accepts the valid fixture directly; logs="
                     (pr-str (logger-stub/calls))))
            (is (= {:available? true :downloads 1} good)
                "production stable policy accepts a valid later stable")
            (doseq [[label result]
                    [["wrong tag" wrong-tag]
                     ["wrong architecture" wrong-arch]
                     ["wrong platform" wrong-platform]
                     ["asset/version mismatch" wrong-version]
                     ["stable to nightly" cross-track]
                     ["minimum system version" unsupported-system]]]
              (is (= {:available? false :downloads 0} result)
                  (str "production policy rejects " label)))
            (is (some #(identical? stable-minimum-system %)
                      (array-seq
                       (.-defaultSupportCalls
                        (:auto-updater stable-config))))
                "production wrapper preserves the original minimumSystemVersion callback")
            (let [{:keys [status output]}
                  (run-nightly-policy-subprocess nightly-current)]
              (is (= 0 status)
                  (str "nightly production policy subprocess failed:\n"
                       output))))
          (p/catch
           (fn [error]
             (is false
                 (str "production updater policy contract failed: " error))))
          (p/finally
           (fn []
             (doseq [dispose! @disposers]
               (dispose!))
             (done)))))))

(deftest nightly-production-update-policy-controls-real-app-updater-downloads
  (async done
    (let [nightly-current "2.0.1-selfhost.6.nightly.20260728"
          configured-version
          (gobj/get (.-env js/process) "LOGSEQ_TEST_COMPILED_VERSION")]
      (if-not (= nightly-current configured-version)
        (do
          ;; The stable parent test launches this test in a fresh Node process
          ;; whose preload seeds frontend.version before the CLJS bundle loads.
          (is true)
          (done))
        (let [platform (.-platform js/process)
              arch (.-arch js/process)
              stable-next "2.0.1-selfhost.6"
              nightly-next "2.0.1-selfhost.6.nightly.20260729"
              nightly-config (configure-production-policy!)
              nightly-policy (:policy nightly-config)
              nightly-asset (target-asset platform arch nightly-next)
              stable-asset (target-asset platform arch stable-next)
              feed-calls
              (array-seq (.-feedURLCalls (:auto-updater nightly-config)))
              feed-text
              (string/lower-case
               (str (or (some-> feed-calls last (aget "url"))
                        (some-> feed-calls last)
                        "")))]
          (logger-stub/reset-calls!)
          (is (= nightly-current updater/electron-version)
              "nightly subprocess must load production policy with the nightly version")
          (is (= 1 (count feed-calls))
              "nightly production path configures exactly one Generic feed")
          (is (string/includes? feed-text "nightly")
              "nightly production feed is an isolated rolling URL")
          (is (not (string/includes? feed-text "/releases/latest"))
              "nightly production path never points at stable latest")
          (is (true? (.-allowPrerelease (:auto-updater nightly-config)))
              "nightly production path enables prerelease metadata")
          (-> (p/let [raw-rolling
                      (nightly-policy
                       (update-info nightly-next "nightly" nightly-asset))
                      rolling-tag
                      (run-app-updater-flow!
                       nightly-current
                       (update-info nightly-next "nightly" nightly-asset)
                       nightly-policy)
                      absent-tag
                      (run-app-updater-flow!
                       nightly-current
                       (update-info nightly-next ::absent nightly-asset)
                       nightly-policy)
                      null-tag
                      (run-app-updater-flow!
                       nightly-current
                       (update-info nightly-next ::null nightly-asset)
                       nightly-policy)
                      dated-tag
                      (run-app-updater-flow!
                       nightly-current
                       (update-info nightly-next nightly-next nightly-asset)
                       nightly-policy)
                      manual-exit
                      (run-app-updater-flow!
                       nightly-current
                       (update-info stable-next stable-next stable-asset)
                       nightly-policy)]
                (is (true? raw-rolling)
                    (str "production-installed nightly support policy accepts the valid fixture directly; logs="
                         (pr-str (logger-stub/calls))))
                (doseq [[label result]
                        [["rolling tag" rolling-tag]
                         ["absent tag" absent-tag]
                         ["null tag" null-tag]]]
                  (is (= {:available? true :downloads 1} result)
                      (str "production nightly policy accepts " label)))
                (is (= {:available? false :downloads 0} dated-tag)
                    "rolling Generic metadata rejects a dated release tag")
                (is (= {:available? false :downloads 0} manual-exit)
                    "nightly requires manual exit to stable"))
              (p/catch
               (fn [error]
                 (is false
                     (str "nightly production policy contract failed: "
                          error))))
              (p/finally
               (fn []
                 ((:dispose! nightly-config))
                 (done)))))))))

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
                  [:unref true true]
                  [:dirty false true true]
                  [:quit false true]]
                 events)))
        (p/catch
         (fn [error]
           (is false (str "successful install failed: " error))))
        (p/finally done))))

(deftest project-signed-install-awaits-db-worker-teardown-before-spawn-and-quit
  (async done
    (let [events* (atom [])
          dirty?* (atom true)
          running?* (atom true)
          teardown-resolve* (atom nil)
          teardown-task (js/Promise.
                         (fn [resolve _reject]
                           (reset! teardown-resolve* resolve)))
          child (EventEmitter.)
          _ (set! (.-unref child)
                  (fn []
                    (swap! events* conj :unref)
                    child))
          task
          (updater/run-project-signed-install!
           {:verify! (fn []
                       (swap! events* conj :verify)
                       (p/resolved true))
            :teardown-db-workers! (fn []
                                    (swap! events* conj :teardown)
                                    teardown-task)
            :teardown-timeout-ms 100
            :spawn-install! (fn []
                              (swap! events* conj :spawn)
                              child)
            :set-quit-dirty! (fn [value]
                               (swap! events* conj [:dirty value])
                               (reset! dirty?* value))
            :quit-app! (fn []
                         (swap! events* conj :quit)
                         (reset! running?* false))
            :emit-error! (fn [error]
                           (swap! events* conj [:ui-error error]))})]
      (->
       (p/let [_ (p/delay 0)]
         (is (= [:verify :teardown] @events*)
             "the install helper must not spawn while db-worker teardown is pending")
         (is @dirty?*)
         (is @running?*)
         (when-let [resolve! @teardown-resolve*]
           (resolve! true))
         (p/let [_ (p/delay 0)]
           (is (= [:verify :teardown :spawn] @events*))
           (.emit child "spawn")
           task))
       (p/then
        (fn [result]
          (is (true? result))
          (is (= [:verify :teardown :spawn :unref [:dirty false] :quit]
                 @events*))
          (is (false? @dirty?*))
          (is (false? @running?*))))
       (p/catch
        (fn [error]
          (is false (str "bounded teardown ordering failed: " error))))
       (p/finally done)))))

(deftest project-signed-install-teardown-rejection-keeps-app-running-and-dirty
  (async done
    (let [events* (atom [])
          dirty?* (atom true)
          running?* (atom true)
          errors* (atom [])
          child (EventEmitter.)
          _ (set! (.-unref child) (fn [] child))]
      (->
       (updater/run-project-signed-install!
        {:verify! (fn []
                    (swap! events* conj :verify)
                    (p/resolved true))
         :teardown-db-workers! (fn []
                                 (swap! events* conj :teardown)
                                 (p/rejected (updater-error "db-worker teardown rejected"
                                                            "DB_WORKER_TEARDOWN_FAILED")))
         :teardown-timeout-ms 100
         :spawn-install! (fn []
                           (swap! events* conj :spawn)
                           (js/setTimeout #(.emit child "spawn") 0)
                           child)
         :set-quit-dirty! (fn [value]
                            (swap! events* conj [:dirty value])
                            (reset! dirty?* value))
         :quit-app! (fn []
                      (swap! events* conj :quit)
                      (reset! running?* false))
         :emit-error! (fn [error]
                        (swap! errors* conj error)
                        (swap! events* conj :ui-error))})
       (p/then
        (fn [result]
          (is (false? result))
          (is (= [:verify :teardown [:dirty true] :ui-error] @events*))
          (is @dirty?*)
          (is @running?*)
          (is (= 1 (count @errors*)))
          (when-let [error (first @errors*)]
            (is (= "DB_WORKER_TEARDOWN_FAILED" (.-code error))))))
       (p/catch
        (fn [error]
          (is false (str "teardown rejection escaped terminal UI handling: " error))))
       (p/finally done)))))

(deftest project-signed-install-teardown-timeout-keeps-app-running-and-dirty
  (async done
    (let [events* (atom [])
          dirty?* (atom true)
          running?* (atom true)
          errors* (atom [])
          child (EventEmitter.)
          _ (set! (.-unref child) (fn [] child))]
      (->
       (updater/run-project-signed-install!
        {:verify! (fn []
                    (swap! events* conj :verify)
                    (p/resolved true))
         :teardown-db-workers! (fn []
                                 (swap! events* conj :teardown)
                                 (js/Promise. (fn [_resolve _reject])))
         :teardown-timeout-ms 10
         :spawn-install! (fn []
                           (swap! events* conj :spawn)
                           (js/setTimeout #(.emit child "spawn") 0)
                           child)
         :set-quit-dirty! (fn [value]
                            (swap! events* conj [:dirty value])
                            (reset! dirty?* value))
         :quit-app! (fn []
                      (swap! events* conj :quit)
                      (reset! running?* false))
         :emit-error! (fn [error]
                        (swap! errors* conj error)
                        (swap! events* conj :ui-error))})
       (p/then
        (fn [result]
          (is (false? result))
          (is (= [:verify :teardown [:dirty true] :ui-error] @events*))
          (is @dirty?*)
          (is @running?*)
          (is (= 1 (count @errors*)))
          (when-let [error (first @errors*)]
            (let [diagnostic (string/lower-case
                              (pr-str [(.-message error)
                                       (ex-data error)]))]
              (is (string/includes? diagnostic "timeout"))
              (is (string/includes? diagnostic "db-worker"))))))
       (p/catch
        (fn [error]
          (is false (str "teardown timeout escaped terminal UI handling: " error))))
       (p/finally done)))))
