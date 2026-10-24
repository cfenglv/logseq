(ns frontend.handler.db-based.import-test
  (:require ["jszip" :as JSZip]
            [cljs.test :refer [async deftest is]]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.fs :as fs]
            [frontend.handler.db-based.import :as import-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.repo :as repo-handler]
            [frontend.persist-db :as persist-db]
            [frontend.state :as state]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

(deftest sqlite-zip-builds-search-after-assets-finish-test
  (async done
    (let [check-import
          (fn [with-assets?]
            (let [zip (JSZip.)
                  graph (str config/db-version-prefix "zip-imported")
                  calls (atom [])]
              (.file zip "db.sqlite" "synthetic db")
              (when with-assets?
                (.file zip "assets/synthetic.png" "synthetic asset"))
              (-> (p/let [buffer (.generateAsync zip #js {:type "arraybuffer"})]
                    (p/with-redefs [import-handler/import-from-sqlite-db!
                                   (fn [_ _ finished] (p/resolved (finished)))
                                   state/get-current-repo (constantly graph)
                                   config/get-repo-dir (constantly "/synthetic-import")
                                   state/set-state! (fn [key value] (swap! calls conj [key value]))
                                   fs/mkdir-if-not-exists (fn [_] (p/resolved nil))
                                   fs/write-asset-file!
                                   (fn [repo name _]
                                     (swap! calls conj [:asset repo name])
                                     (p/resolved nil))
                                   notification/show! (fn [& _])
                                   shui/dialog-close! (fn [& _])
                                   state/<invoke-db-worker
                                   (fn [& args]
                                     (swap! calls conj (vec args))
                                     (p/resolved :started))]
                      (import-handler/import-from-sqlite-zip!
                       #js {:arrayBuffer #(p/resolved buffer)} "zip-imported"
                       #(swap! calls conj [:finished]))))
                  (p/then
                   (fn []
                     (is (= (if with-assets?
                              [[:asset graph "synthetic.png"]
                               [:graph/importing nil]
                               [:graph/importing-state nil]
                               [:finished]
                               [:thread-api/search-build-blocks-indice-in-worker graph]]
                              [[:graph/importing nil]
                               [:graph/importing-state nil]
                               [:finished]])
                            (vec (remove #(or (= [:graph/importing :sqlite-zip] %)
                                              (and (= :graph/importing-state (first %))
                                                   (map? (second %))))
                                         @calls)))))))))]
      (-> (p/do! (check-import true) (check-import false))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest import-from-sqlite-db-persists-import-marker-through-worker-test
  (async done
    (let [calls (atom [])
          graph (str config/db-version-prefix "imported")]
      (-> (p/with-redefs [persist-db/<import-db
                          (fn [repo buffer]
                            (swap! calls conj [:import-db repo buffer])
                            (p/resolved nil))
                          state/add-repo!
                          (fn [repo]
                            (swap! calls conj [:add-repo repo])
                            (p/resolved nil))
                          repo-handler/restore-and-setup-repo!
                          (fn [repo opts]
                            (swap! calls conj [:restore repo opts])
                            (p/resolved nil))
                          state/set-current-repo!
                          (fn [repo]
                            (swap! calls conj [:current-repo repo])
                            nil)
                          persist-db/<export-db
                          (fn [repo opts]
                            (swap! calls conj [:export-db repo opts])
                            (p/resolved nil))
                          state/<invoke-db-worker
                          (fn [& args]
                            (swap! calls conj (vec args))
                            (p/resolved nil))
                          db/transact!
                          (fn [& _]
                            (throw (js/Error. "renderer DB transact should not be used")))]
            (import-handler/import-from-sqlite-db! "buffer" "imported" #(swap! calls conj [:finished])))
          (p/then
           (fn []
             (let [[import-call add-repo-call restore-call current-repo-call export-call worker-call finished-call] @calls
                   [api worker-repo tx-data tx-meta context] worker-call]
               (is (= [:import-db graph "buffer"] import-call))
               (is (= [:add-repo {:url graph}] add-repo-call))
               (is (= [:restore graph {:import-type :sqlite-db}] restore-call))
               (is (= [:current-repo graph] current-repo-call))
               (is (= [:export-db graph {}] export-call))
               (is (= :thread-api/transact api))
               (is (= graph worker-repo))
               (is (= :sqlite-db (-> tx-data first :kv/value)))
               (is (= {:import-db? true} tx-meta))
               (is (nil? context))
               (is (= [:finished] finished-call)))))
          (p/catch
           (fn [error]
             (is false (str error))))
          (p/finally done)))))
