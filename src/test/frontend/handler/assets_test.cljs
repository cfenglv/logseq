(ns frontend.handler.assets-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.handler.assets :as assets]
            [frontend.util :as util]
            [promesa.core :as p]))

(defn- uint8->vec
  [^js payload]
  (js->clj (js/Array.from payload)))

(deftest coerce-array-buffer-to-uint8-test
  (let [source (js/Uint8Array. #js [1 2 3])
        output (#'assets/->uint8 (.-buffer source))]
    (is (instance? js/Uint8Array output))
    (is (= [1 2 3] (uint8->vec output)))))

(deftest coerce-array-buffer-view-to-uint8-test
  (let [source (js/Uint8Array. #js [9 8 7 6])
        view (js/DataView. (.-buffer source) 1 2)
        output (#'assets/->uint8 view)]
    (is (instance? js/Uint8Array output))
    (is (= [8 7] (uint8->vec output)))))

(deftest coerce-buffer-like-object-to-uint8-test
  (let [buffer-like #js {:type "Buffer"
                         :data #js [10 11 12]}
        output (#'assets/->uint8 buffer-like)]
    (is (instance? js/Uint8Array output))
    (is (= [10 11 12] (uint8->vec output)))))

(deftest coerce-buffer-like-map-to-uint8-test
  (let [buffer-like {"type" "Buffer"
                     "data" [13 14 15]}
        output (#'assets/->uint8 buffer-like)]
    (is (instance? js/Uint8Array output))
    (is (= [13 14 15] (uint8->vec output)))))

(deftest coerce-buffer-like-object-with-seq-data-to-uint8-test
  (let [buffer-like #js {:type "Buffer"
                         :data [16 17 18]}
        output (#'assets/->uint8 buffer-like)]
    (is (instance? js/Uint8Array output))
    (is (= [16 17 18] (uint8->vec output)))))

(deftest coerce-indexed-byte-object-to-uint8-test
  (let [buffer-like #js {"0" 19
                         "1" 20
                         "2" 21}
        output (#'assets/->uint8 buffer-like)]
    (is (instance? js/Uint8Array output))
    (is (= [19 20 21] (uint8->vec output)))))

(deftest coerce-indexed-byte-map-to-uint8-test
  (let [buffer-like {"0" 22
                     "1" 23
                     "2" 24}
        output (#'assets/->uint8 buffer-like)]
    (is (instance? js/Uint8Array output))
    (is (= [22 23 24] (uint8->vec output)))))

(deftest get-all-assets-does-not-readdir-missing-assets-dir
  (async done
    (let [readdir-calls (atom 0)
          original-assets-root config/get-current-repo-assets-root
          original-stat fs/stat
          original-readdir fs/readdir]
      (set! config/get-current-repo-assets-root (constantly "/tmp/graph/assets"))
      (set! fs/stat (fn [path]
                      (is (= "/tmp/graph/assets" path))
                      (p/rejected (js/Error. "ENOENT"))))
      (set! fs/readdir (fn [& _args]
                         (swap! readdir-calls inc)
                         (p/rejected (js/Error. "readdir should not be called"))))
      (-> (p/let [result (assets/<get-all-assets)]
            (is (= [] result))
            (is (zero? @readdir-calls)))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally (fn []
                       (set! config/get-current-repo-assets-root original-assets-root)
                       (set! fs/stat original-stat)
                       (set! fs/readdir original-readdir)
                       (done)))))))

(deftest resolve-asset-real-path-url-electron-test
  (with-redefs [util/electron? (constantly true)
                config/get-repo-dir (constantly "/Users/charlie/graph")]
    (is (= "assets:///Users/charlie/graph/assets/test.png"
           (#'assets/resolve-asset-real-path-url "some-repo" "assets/test.png")))))

(deftest normalize-asset-resource-url-electron-test
  (with-redefs [util/electron? (constantly true)]
    (is (= "assets:///Users/charlie/graph/assets/test.png"
           (assets/normalize-asset-resource-url "/Users/charlie/graph/assets/test.png")))))

(deftest normalize-asset-resource-url-electron-windows-test
  (with-redefs [util/electron? (constantly true)
                util/win32? true]
    (is (= "assets:///C/logseq__colon/Users/charlie/graph/assets/test.png"
           (assets/normalize-asset-resource-url "C:/Users/charlie/graph/assets/test.png")))))

(deftest make-asset-url-electron-test
  (async done
    (with-redefs [util/electron? (constantly true)
                  config/get-repo-dir (constantly "/Users/charlie/graph")]
      (-> (assets/<make-asset-url "assets/test.png")
          (p/then (fn [url]
                    (is (= "assets:///Users/charlie/graph/assets/test.png" url))
                    (done)))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))
                     (done)))))))

(deftest make-asset-url-electron-windows-test
  (async done
    (with-redefs [util/electron? (constantly true)
                  util/win32? true
                  config/get-repo-dir (constantly "C:/Users/charlie/graph")]
      (-> (assets/<make-asset-url "assets/test.png")
          (p/then (fn [url]
                    (is (= "assets:///C/logseq__colon/Users/charlie/graph/assets/test.png" url))
                    (done)))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))
                     (done)))))))

(deftest asset-protocol-url->media-url-keeps-electron-assets-protocol-test
  (with-redefs [util/electron? (constantly true)]
    (let [url "assets:///C/logseq__colon/Users/charlie/graph/assets/test.mp3"]
      (is (= url
             (assets/asset-protocol-url->media-url url))))))

(deftest remote-asset-download-latch-recovers-after-failure-test
  (async done
    (let [requested? (atom false)
          calls (atom 0)
          asset {:block/uuid (random-uuid)
                 :logseq.property.asset/type "png"
                 :logseq.property.asset/remote-metadata
                 {:checksum "sha-256-value" :type "png"}}]
      (-> (p/with-redefs
            [assets/maybe-request-remote-asset-download!
             (fn [_repo _asset file-ready?]
               (when-not file-ready?
                 (if (= 1 (swap! calls inc))
                   (p/rejected (js/Error. "temporary network failure"))
                   (p/resolved true))))]
            (p/let [first-result
                    (assets/request-remote-asset-download-once!
                     "repo" asset false requested?)]
              (is (false? first-result))
              (is (false? @requested?)
                  "a rejected request must release the mounted-component latch")
              (p/let [second-result
                      (assets/request-remote-asset-download-once!
                       "repo" asset false requested?)]
                (is (true? second-result))
                (is (true? @requested?))
                (is (= 2 @calls))
                (assets/request-remote-asset-download-once!
                 "repo" asset true requested?)
                (is (false? @requested?)
                    "file arrival releases the latch without another request")
                (is (= 2 @calls)))))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest remote-asset-download-retry-scheduler-enters-low-frequency-recovery-test
  (let [timer* (atom nil)
        attempt* (atom 0)
        calls* (atom 0)
        callbacks* (atom [])
        delays* (atom [])
        fake-set-timeout
        (fn [callback delay]
          (swap! callbacks* conj callback)
          (swap! delays* conj delay)
          (str "timer-" (count @callbacks*)))]
    (dotimes [_ (count assets/remote-asset-download-retry-delays-ms)]
      (is (true?
           (assets/schedule-remote-asset-download-retry!
            timer* attempt* #(swap! calls* inc) fake-set-timeout)))
      (is (false?
           (assets/schedule-remote-asset-download-retry!
            timer* attempt* #(swap! calls* inc) fake-set-timeout))
          "an existing timer deduplicates retry scheduling")
      ((last @callbacks*)))
    (is (= (count assets/remote-asset-download-retry-delays-ms)
           @calls*))
    (is (true?
         (assets/schedule-remote-asset-download-retry!
          timer* attempt* #(swap! calls* inc) fake-set-timeout)))
    (is (= assets/remote-asset-download-steady-retry-ms
           (last @delays*))
        "recovery continues at low frequency after the fast budget")
    ((last @callbacks*))
    (is (= (inc (count assets/remote-asset-download-retry-delays-ms))
           @calls*))))
