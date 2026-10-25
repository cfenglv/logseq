(ns frontend.handler.graph-test
  (:require [cljs.test :refer [async deftest is testing]]
            [electron.ipc :as ipc]
            [frontend.common.idb :as idb]
            [frontend.handler.graph]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.util.url :as url-util]
            [logseq.common.graph-registry :as graph-registry]
            [promesa.core :as p]))

(deftest graph-registry-key-is-indexeddb-compatible-test
  (let [registry-key (some-> (resolve 'frontend.handler.graph/graph-registry-key) deref)]
    (is (= "ls-graph-registry" registry-key))))

(deftest get-graph-registry-normalizes-indexeddb-js-values-test
  (async done
    (let [get-registry-f (some-> (resolve 'frontend.handler.graph/<get-graph-registry) deref)]
      ;; done after p/with-redefs has put the originals back, not inside it:
      ;; otherwise the next test starts with the redefs still in place
      (-> (p/with-redefs [idb/get-item (fn [_]
                                         (p/resolved
                                          #js [#js {"repo" "logseq_db_work"
                                                    "graph-name" "work"
                                                    "graph-id" "remote-uuid"}]))]
            (-> (get-registry-f)
                (.then (fn [registry]
                         (is (= [{:repo "logseq_db_work"
                                  :graph-name "work"
                                  :graph-id "remote-uuid"}]
                                registry))
                         (is (= "logseq_db_work"
                                (:repo (graph-registry/resolve-target
                                        registry
                                        {:graph-id "remote-uuid"}))))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally (fn [] (done)))))))

(deftest get-graph-registry-uses-the-desktop-writer-owner-test
  (async done
    (let [registry [{:repo "logseq_db_Foo"
                     :graph-name "Foo"
                     :graph-id "canonical-uuid"}
                    {:repo "logseq_db_Foo(2)"
                     :graph-name "Foo(2)"
                     :graph-id "spaced-uuid"
                     :local-graph-id "spaced-local-uuid"}]]
      (-> (p/with-redefs [util/electron? (constantly true)
                         idb/get-item (fn [_]
                                        (throw (js/Error. "Desktop registry must not use browser storage")))
                         ipc/ipc (fn [command]
                                   (is (= "getGraphRegistry" command))
                                   (p/resolved registry))]
            (p/let [result (frontend.handler.graph/<get-graph-registry)]
              (is (= registry result))
              (is (= (second registry)
                     (graph-registry/resolve-target result {:graph-id "spaced-uuid"})))
              (is (= (first registry)
                     (graph-registry/resolve-target result {:graph-id "canonical-uuid"})))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest get-graph-registry-keeps-browser-storage-owner-test
  (async done
    (-> (p/with-redefs [util/electron? (constantly false)
                       ipc/ipc (fn [& _]
                                 (throw (js/Error. "Browser registry must not use desktop IPC")))
                       idb/get-item (fn [key]
                                      (is (= "ls-graph-registry" key))
                                      (p/resolved #js [#js {"repo" "logseq_db_browser"
                                                           "graph-id" "browser-uuid"}]))]
          (p/let [result (frontend.handler.graph/<get-graph-registry)]
            (is (= [{:repo "logseq_db_browser" :graph-id "browser-uuid"}] result))))
        (p/catch (fn [error] (is false (str error))))
        (p/finally done))))

(deftest remember-current-graph-id-in-tab-test
  (async done
    (let [remember-f (some-> (resolve 'frontend.handler.graph/remember-current-graph-id-in-tab!) deref)
          original-invoke-db-worker state/<invoke-db-worker
          stored-graph (atom nil)]
      (is (fn? remember-f) "Current graph id should be remembered for same-tab reloads")
      (when remember-f
        (set! state/<invoke-db-worker
              (fn [api repo]
                (is (= :thread-api/get-graph-uuid api))
                (is (= "logseq_db_work" repo))
                (p/resolved #uuid "11111111-1111-1111-1111-111111111111")))
        (-> (p/with-redefs [state/get-current-repo (constantly "logseq_db_work")
                            frontend.handler.graph/set-tab-graph! (fn [repo graph-id]
                                                                    (reset! stored-graph {:repo repo
                                                                                          :graph-id graph-id}))]
              (-> (remember-f)
                  (p/then
                   (fn []
                     (is (= {:repo "logseq_db_work"
                             :graph-id "11111111-1111-1111-1111-111111111111"}
                            @stored-graph))))))
            (p/catch
             (fn [error]
               (is false (str error))))
            (p/finally
             (fn []
               (set! state/<invoke-db-worker original-invoke-db-worker)
               (done))))))))

(deftest current-graph-id-uses-tab-memory-test
  (let [current-graph-id-f (some-> (resolve 'frontend.handler.graph/current-graph-id) deref)]
    (is (fn? current-graph-id-f) "Current graph id helper should exist")
    (when current-graph-id-f
      (with-redefs [state/get-current-repo (constantly "logseq_db_work")
                    frontend.handler.graph/get-tab-graph
                    (fn []
                      {:repo "logseq_db_work"
                       :graph-id "11111111-1111-1111-1111-111111111111"})]
        (is (= "11111111-1111-1111-1111-111111111111"
               (current-graph-id-f)))))))

(deftest prepare-startup-repo-restores-spaced-tab-by-identity-test
  (let [registry [{:repo "logseq_db_Foo" :graph-id "canonical-uuid"}
                  {:repo "logseq_db_Foo(2)" :graph-id "spaced-uuid"}]]
    (doseq [old-name [" Foo " "Foo " " Foo"]]
      (let [current (atom (str "logseq_db_" old-name))
            tab (atom {:repo @current :graph-id "spaced-uuid"})]
        (with-redefs [util/electron? (constantly true)
                      state/set-current-repo! #(reset! current %)
                      frontend.handler.graph/get-tab-graph #(deref tab)
                      frontend.handler.graph/set-tab-graph!
                      (fn [repo graph-id] (reset! tab {:repo repo :graph-id graph-id}))]
          (frontend.handler.graph/prepare-startup-repo! registry {} [])
          (is (= "logseq_db_Foo(2)" @current))
          (is (= {:repo "logseq_db_Foo(2)" :graph-id "spaced-uuid"} @tab))
          (frontend.handler.graph/prepare-startup-repo! registry {} [])
          (is (= "logseq_db_Foo(2)" @current)))))))

(deftest prepare-startup-repo-keeps-url-priority-test
  (let [registry [{:repo "logseq_db_Foo" :graph-id "canonical-uuid"}
                  {:repo "logseq_db_Foo(2)" :graph-id "spaced-uuid"}]
        current (atom "logseq_db_ Foo ")
        tab (atom {:repo @current :graph-id "spaced-uuid"})]
    (with-redefs [util/electron? (constantly true)
                  state/set-current-repo! #(reset! current %)
                  frontend.handler.graph/get-tab-graph #(deref tab)
                  frontend.handler.graph/set-tab-graph!
                  (fn [repo graph-id] (reset! tab {:repo repo :graph-id graph-id}))]
      (frontend.handler.graph/prepare-startup-repo! registry {:graph-id "canonical-uuid"} [])
      (is (= "logseq_db_Foo" @current))
      (is (= {:repo "logseq_db_Foo" :graph-id "canonical-uuid"} @tab)))))

(deftest prepare-startup-repo-keeps-normal-tab-and-browser-behavior-test
  (doseq [[desktop? tab url-target]
          [[true {:repo "logseq_db_Foo" :graph-id "stale-uuid"} {}]
           [true {:repo "logseq_db_Foo Bar" :graph-id "stale-uuid"} {}]
           [true {:repo "logseq_db_ Foo " :graph-id "stale-uuid"} {:graph-id "missing-uuid"}]
           [true {:repo "logseq_db_ Foo " :graph-id "missing-uuid"} {}]
           [false {:repo "logseq_db_ Foo " :graph-id "stale-uuid"} {:graph-id "stale-uuid"}]]]
    (let [writes (atom [])]
      (with-redefs [util/electron? (constantly desktop?)
                    state/set-current-repo! #(swap! writes conj [:current %])
                    frontend.handler.graph/get-tab-graph (constantly tab)
                    frontend.handler.graph/set-tab-graph!
                    (fn [repo graph-id] (swap! writes conj [:tab repo graph-id]))]
        (frontend.handler.graph/prepare-startup-repo!
         [{:repo "logseq_db_Other" :graph-id "stale-uuid"}]
         url-target [])
        (is (empty? @writes))))))

(deftest prepare-startup-repo-uses-exact-directory-origin-before-duplicate-uuid-test
  (doseq [target ["logseq_db_Foo(2)" "logseq_db_Foo(3)"]
          old-name [" Foo " "Foo " " Foo"]]
    (let [source (str "logseq_db_" old-name)
          registry [{:repo "logseq_db_Foo" :graph-id "copied-uuid"}
                    {:repo target :graph-id "copied-uuid"}]
          renames [{:from source :to target}]
          current (atom source)
          tab (atom {:repo source :graph-id "copied-uuid"})]
      (with-redefs [util/electron? (constantly true)
                    state/get-current-repo #(deref current)
                    state/set-current-repo! #(reset! current %)
                    frontend.handler.graph/get-tab-graph #(deref tab)
                    frontend.handler.graph/set-tab-graph!
                    (fn [repo graph-id] (reset! tab {:repo repo :graph-id graph-id}))]
        (is (= {} (frontend.handler.graph/prepare-startup-repo! registry {} renames)))
        (is (= target @current))
        (is (= {:repo target :graph-id "copied-uuid"} @tab))
        (frontend.handler.graph/prepare-startup-repo! registry {} renames)
        (is (= target @current))
        (is (= target (frontend.handler.graph/resolve-startup-repo
                       registry [{:url "logseq_db_Foo"} {:url target}] {} @tab @current)))))))

(deftest prepare-startup-repo-projects-raw-url-and-current-before-open-test
  (let [renames [{:from "logseq_db_Foo " :to "logseq_db_Foo(2)"}]
        registry [{:repo "logseq_db_Foo" :graph-id "canonical-uuid"}]]
    (doseq [identifier ["Foo " "logseq_db_Foo "]]
      (let [current (atom "logseq_db_Foo")
            tab (atom nil)
            target {:graph-identifier identifier :route {:to :page :page-id "page-id"}}]
        (with-redefs [util/electron? (constantly true)
                      state/get-current-repo #(deref current)
                      state/set-current-repo! #(reset! current %)
                      frontend.handler.graph/get-tab-graph #(deref tab)
                      frontend.handler.graph/set-tab-graph!
                      (fn [repo graph-id] (reset! tab {:repo repo :graph-id graph-id}))]
          (is (= (assoc target :graph-identifier "logseq_db_Foo(2)")
                 (frontend.handler.graph/prepare-startup-repo! registry target renames)))
          (is (= "logseq_db_Foo(2)" @current)))))
    (let [current (atom "logseq_db_Foo ")]
      (with-redefs [util/electron? (constantly true)
                    state/get-current-repo #(deref current)
                    state/set-current-repo! #(reset! current %)
                    frontend.handler.graph/get-tab-graph (constantly nil)]
        (frontend.handler.graph/prepare-startup-repo! registry {} renames)
        (is (= "logseq_db_Foo(2)" @current))))))

(deftest prepare-startup-repo-keeps-explicit-url-uuid-over-renamed-tab-test
  (let [registry [{:repo "logseq_db_Foo" :graph-id "canonical-uuid"}]
        renames [{:from "logseq_db_Foo " :to "logseq_db_Foo(2)"}]
        current (atom "logseq_db_Foo ")
        tab (atom {:repo @current :graph-id "old-uuid"})]
    (with-redefs [util/electron? (constantly true)
                  state/get-current-repo #(deref current)
                  state/set-current-repo! #(reset! current %)
                  frontend.handler.graph/get-tab-graph #(deref tab)
                  frontend.handler.graph/set-tab-graph!
                  (fn [repo graph-id] (reset! tab {:repo repo :graph-id graph-id}))]
      (frontend.handler.graph/prepare-startup-repo! registry {:graph-id "canonical-uuid"} renames)
      (is (= "logseq_db_Foo" @current))
      (is (= {:repo "logseq_db_Foo" :graph-id "canonical-uuid"} @tab)))))

(deftest prepare-startup-repo-keeps-real-tab-identity-consistent-test
  (let [canonical {:repo "logseq_db_Foo" :graph-id "copied-uuid"}
        renamed {:repo "logseq_db_Foo(2)" :graph-id "copied-uuid"}
        renames [{:from "logseq_db_Foo " :to "logseq_db_Foo(2)"}]]
    (doseq [[registry initial-current stored-tab url-target expected-id]
            [[[canonical] "logseq_db_Other"
              {"ls-tab-repo" "logseq_db_Other" "ls-tab-graph-id" "other-uuid"}
              {:graph-identifier "Foo "} nil]
             [[canonical renamed] "logseq_db_Foo "
              {"ls-tab-graph-id" "copied-uuid"} {} "copied-uuid"]
             [[canonical] "logseq_db_Foo "
              {"ls-tab-graph-id" "copied-uuid"} {} nil]]]
      (let [current (atom initial-current)
            stored (atom stored-tab)
            original-storage (js/Object.getOwnPropertyDescriptor js/globalThis "sessionStorage")
            storage #js {:getItem (fn [key] (get @stored key))
                         :setItem (fn [key value] (swap! stored assoc key (str value)))
                         :removeItem (fn [key] (swap! stored dissoc key))}]
        (try
          (js/Object.defineProperty js/globalThis "sessionStorage"
                                    #js {:configurable true :value storage})
          (with-redefs [util/electron? (constantly true)
                        state/get-current-repo #(deref current)
                        state/set-current-repo! #(reset! current %)]
            (let [target (frontend.handler.graph/prepare-startup-repo! registry url-target renames)]
              (is (= "logseq_db_Foo(2)" @current))
              (is (= {:repo "logseq_db_Foo(2)" :graph-id expected-id}
                     (frontend.handler.graph/get-tab-graph)))
              (is (= expected-id (frontend.handler.graph/current-graph-id)))
              (is (= "logseq_db_Foo(2)"
                     (frontend.handler.graph/resolve-startup-repo
                      registry [{:url "logseq_db_Foo"} {:url "logseq_db_Foo(2)"}]
                      target (frontend.handler.graph/get-tab-graph) @current)))))
          (finally
            (if original-storage
              (js/Object.defineProperty js/globalThis "sessionStorage" original-storage)
              (js/Reflect.deleteProperty js/globalThis "sessionStorage"))))))))

(deftest upsert-current-graph-registry-repairs-missing-local-graph-uuid-test
  (async done
    (let [upsert-current-f (some-> (resolve 'frontend.handler.graph/<upsert-current-graph-registry!) deref)
          local-graph-uuid #uuid "11111111-1111-1111-1111-111111111111"
          registry-entry (atom nil)]
      (is (fn? upsert-current-f) "Current graph registry upsert should exist")
      (-> (p/with-redefs [state/get-current-repo (constantly "logseq_db_broken")
                          state/<invoke-db-worker
                          (fn [api repo]
                            (is (= "logseq_db_broken" repo))
                            (case api
                              :thread-api/ensure-local-graph-uuid (p/resolved local-graph-uuid)
                              :thread-api/get-graph-uuid (p/resolved local-graph-uuid)
                              (throw (ex-info "Unexpected worker API" {:api api}))))
                          frontend.handler.graph/<upsert-graph-registry-entry!
                          (fn [entry]
                            (reset! registry-entry entry)
                            (p/resolved nil))]
            (-> (upsert-current-f)
                (.then (fn [_]
                         (is (= {:repo "logseq_db_broken"
                                 :graph-name "broken"
                                 :local-graph-id (str local-graph-uuid)
                                 :graph-id (str local-graph-uuid)}
                                @registry-entry))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally (fn [] (done)))))))

(deftest resolve-startup-repo-prefers-tab-repo-before-global-current-test
  (let [resolve-f (some-> (resolve 'frontend.handler.graph/resolve-startup-repo) deref)]
    (is (fn? resolve-f) "Startup repo resolver should exist")
    (when resolve-f
      (is (= "logseq_db_tab"
             (resolve-f []
                        [{:url "logseq_db_tab"}
                         {:url "logseq_db_current"}]
                        {}
                        {:repo "logseq_db_tab"
                         :graph-id "tab-uuid"}
                        "logseq_db_current"))))))

(deftest resolve-startup-repo-prefers-url-graph-id-test
  (let [resolve-f (some-> (resolve 'frontend.handler.graph/resolve-startup-repo) deref)]
    (is (fn? resolve-f) "Startup repo resolver should exist")
    (when resolve-f
      (is (= "logseq_db_url"
             (resolve-f [{:repo "logseq_db_url"
                          :graph-name "url"
                          :graph-id "url-uuid"}
                         {:repo "logseq_db_tab"
                          :graph-name "tab"
                          :graph-id "tab-uuid"}]
                        [{:url "logseq_db_current"}]
                        {:graph-id "url-uuid"}
                        {:repo "logseq_db_tab"
                         :graph-id "tab-uuid"}
                        "logseq_db_current"))))))

(deftest resolve-startup-repo-prefers-compatibility-url-graph-test
  (let [resolve-f (some-> (resolve 'frontend.handler.graph/resolve-startup-repo) deref)]
    (is (fn? resolve-f) "Startup repo resolver should exist")
    (when resolve-f
      (is (= "logseq_db_work"
             (resolve-f [{:repo "logseq_db_work"
                          :graph-name "work"
                          :graph-id "work-uuid"}
                         {:repo "logseq_db_tab"
                          :graph-name "tab"
                          :graph-id "tab-uuid"}]
                        [{:url "logseq_db_current"}]
                        (url-util/parse-web-url-target
                         "https://logseq.com/#/page/Home?graph=work")
                        {:repo "logseq_db_tab"
                         :graph-id "tab-uuid"}
                        "logseq_db_current"))))))

(deftest resolve-startup-repo-uses-tab-graph-id-before-global-current-test
  (let [resolve-f (some-> (resolve 'frontend.handler.graph/resolve-startup-repo) deref)]
    (is (fn? resolve-f) "Startup repo resolver should exist")
    (when resolve-f
      (testing "refreshing a bare root URL keeps the tab's graph context"
        (is (= "logseq_db_tab"
               (resolve-f [{:repo "logseq_db_tab"
                            :graph-name "tab"
                            :graph-id "tab-uuid"}]
                          [{:url "logseq_db_current"}]
                          {}
                          {:repo "logseq_db_tab"
                           :graph-id "tab-uuid"}
                          "logseq_db_current"))))
      (testing "global current graph remains the last fallback"
        (is (= "logseq_db_current"
               (resolve-f []
                          [{:url "logseq_db_first"}]
                          {}
                          nil
                          "logseq_db_current")))))))

(deftest normalize-registry-entry-prefers-remote-graph-id-test
  (let [normalize-f (some-> (resolve 'frontend.handler.graph/normalize-registry-entry) deref)]
    (is (fn? normalize-f) "Graph registry entry normalizer should exist")
    (when normalize-f
      (testing "remote graphs store graph-id without duplicating rtc-graph-id"
        (is (= {:repo "logseq_db_work"
                :graph-name "work"
                :local-graph-id "local-uuid"
                :graph-id "remote-uuid"}
               (select-keys
                (normalize-f {:repo "logseq_db_work"
                              :graph-name "work"
                              :local-graph-id "local-uuid"
                              :graph-id "remote-uuid"})
                [:repo :graph-name :local-graph-id :rtc-graph-id :graph-id]))))
      (testing "local-only graphs use local graph uuid as canonical graph-id"
        (is (= "local-uuid"
               (:graph-id (normalize-f {:repo "logseq_db_local"
                                        :graph-name "local"
                                        :local-graph-id "local-uuid"})))))
      (testing "missing graph identity fails fast"
        (is (thrown? js/Error
                     (normalize-f {:repo "logseq_db_broken"
                                   :graph-name "broken"})))))))

(deftest resolve-registry-target-prefers-graph-id-test
  (let [resolve-f (some-> (resolve 'frontend.handler.graph/resolve-registry-target) deref)]
    (is (fn? resolve-f) "Graph registry target resolver should exist")
    (when resolve-f
      (let [registry [{:repo "logseq_db_work"
                       :graph-name "work"
                       :graph-id "remote-uuid"}
                      {:repo "logseq_db_other"
                       :graph-name "work"
                       :graph-id "other-uuid"}]]
        (is (= "logseq_db_work"
               (:repo (resolve-f registry {:graph-id "remote-uuid"}))))
        (is (= "logseq_db_other"
               (:repo (resolve-f registry {:graph-identifier "logseq_db_other"}))))
        (is (= "logseq_db_work"
               (:repo (resolve-f registry {:graph-identifier "remote-uuid"})))
            "Protocol URL graph identifiers can be canonical graph ids")
        (is (nil? (resolve-f registry {:graph-id "missing-uuid"})))))))

(deftest upsert-registry-entry-replaces-local-id-after-remote-id-exists-test
  (let [registry [{:repo "logseq_db_work"
                   :graph-name "work"
                   :local-graph-id "local-uuid"
                   :graph-id "local-uuid"}]
        registry' (graph-registry/upsert-entry
                   registry
                   {:repo "logseq_db_work"
                    :graph-name "work"
                    :local-graph-id "local-uuid"
                    :graph-id "remote-uuid"})]
    (is (= 1 (count registry')))
    (is (= "remote-uuid" (:graph-id (first registry'))))
    (is (not (contains? (first registry') :rtc-graph-id)))
    (is (nil? (graph-registry/resolve-target registry' {:graph-id "local-uuid"})))
    (is (= "logseq_db_work"
           (:repo (graph-registry/resolve-target registry' {:graph-id "remote-uuid"}))))))
