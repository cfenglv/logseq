(ns frontend.worker.platform-browser-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.worker.platform :as platform]
            [frontend.worker.platform.browser :as platform-browser]
            [goog.object :as gobj]
            [promesa.core :as p]))

(defn- fake-pfs
  [files]
  #js {:readFile (fn [path]
                   (if (contains? @files path)
                     (p/resolved (get @files path))
                     (p/rejected (js/Error. (str "ENOENT: " path)))))
       :writeFile (fn [path payload]
                    (swap! files assoc path payload)
                    (p/resolved nil))
       :stat (fn [path]
               (if-let [payload (get @files path)]
                 (p/resolved #js {:size (.-byteLength payload)
                                   :type "file"})
                 (p/rejected (js/Error. (str "ENOENT: " path)))))
       :mkdir (fn [path]
                (swap! files assoc path (js/Uint8Array. #js []))
                (p/resolved nil))})

(deftest browser-platform-asset-read-uses-renderer-memory-path-test
  (async done
         (let [repo "logseq_db_Lambda RTC"
               file-name "69fb07e1-852f-4896-9d30-761843368fdb.png"
               payload (js/Uint8Array. #js [1 2 3])
               files (atom {"/Lambda%20RTC/assets/69fb07e1-852f-4896-9d30-761843368fdb.png" payload})
               original-pfs (gobj/get js/globalThis "pfs")
               original-location (gobj/get js/globalThis "location")]
           (gobj/set js/globalThis "pfs" (fake-pfs files))
           (gobj/set js/globalThis "location" #js {:href "http://localhost/" :search ""})
           (-> (p/let [platform (platform-browser/browser-platform)
                       read-payload ((get-in platform [:storage :asset-read-bytes!])
                                     repo
                                     file-name)]
                 (is (= payload read-payload)))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally
                 (fn []
                   (if (some? original-pfs)
                     (gobj/set js/globalThis "pfs" original-pfs)
                     (gobj/remove js/globalThis "pfs"))
                   (if (some? original-location)
                     (gobj/set js/globalThis "location" original-location)
                     (gobj/remove js/globalThis "location"))
                   (done)))))))

(deftest browser-prepared-artifact-inspection-uses-file-list-and-webcrypto-test
  (async done
         (let [original-location (gobj/get js/globalThis "location")
               exports (atom [])
               payload (js/Uint8Array. #js [1 2 3])
               paths {:canonical-path "/db.sqlite"
                      :wal-path "/db.sqlite-wal"
                      :shm-path "/db.sqlite-shm"}
               capture-error (fn [f]
                               (try
                                 (-> (f)
                                     (p/then (constantly nil))
                                     (p/catch identity))
                                 (catch :default error
                                   (p/resolved error))))
               pool (fn [file-names]
                      #js {:getFileNames (fn [] (clj->js file-names))
                           :exportFile (fn [path]
                                         (swap! exports conj path)
                                         payload)})]
           (gobj/set js/globalThis "location"
                     #js {:href "http://localhost/" :search ""})
           (-> (p/let [platform (platform-browser/browser-platform)
                       inspect (get-in platform [:storage :inspect-db-artifact-set])
                       result (inspect (pool ["/db.sqlite"]) paths)
                       wal-error (capture-error
                                  #(inspect (pool ["/db.sqlite" "/db.sqlite-wal"])
                                            paths))
                       missing-error (capture-error #(inspect (pool []) paths))]
                 (is (= {:canonical-sha256
                         "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
                         :canonical-byte-size 3
                         :bounded-memory? false}
                        result))
                 (is (= ["/db.sqlite"] @exports))
                 (is (= :selfhost6/uncheckpointed-prepared-canonical
                        (:type (ex-data wal-error))))
                 (is (= :selfhost6/missing-prepared-canonical
                        (:type (ex-data missing-error)))))
               (p/catch (fn [error]
                          (is false (str "unexpected error: " error))))
               (p/finally
                 (fn []
                   (if (some? original-location)
                     (gobj/set js/globalThis "location" original-location)
                     (gobj/remove js/globalThis "location"))
                   (done)))))))

(deftest browser-repair-artifact-swap-is-explicitly-unsupported-test
  (let [original-location (gobj/get js/globalThis "location")]
    (gobj/set js/globalThis "location"
              #js {:href "http://localhost/" :search ""})
    (try
      (let [platform (platform-browser/browser-platform)]
        (is (false? (platform/db-artifact-swap-supported? platform)))
        (is (= :selfhost6/artifact-swap-unsupported
               (:type
                (ex-data
                 (try
                   (platform/prepare-db-artifact-swap!
                    platform nil nil {})
                   nil
                   (catch :default error error)))))))
      (finally
        (if (some? original-location)
          (gobj/set js/globalThis "location" original-location)
          (gobj/remove js/globalThis "location"))))))
