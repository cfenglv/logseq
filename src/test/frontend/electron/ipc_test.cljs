(ns frontend.electron.ipc-test
  (:require [cljs.test :refer [async deftest is]]
            [electron.ipc :as ipc]
            [frontend.util :as util]
            [logseq.db.sqlite.util :as sqlite-util]
            [promesa.core :as p]))

(deftest ipc-uses-transit-json-for-main-channel
  (async done
    (let [payloads (atom [])
          original-electron? util/electron?
          original-apis (.-apis js/window)]
      (set! util/electron? (constantly true))
      (set! (.-apis js/window)
            #js {:doAction (fn [payload]
                             (swap! payloads conj payload)
                             (js/Promise.resolve (sqlite-util/write-transit-str {:ok true})))})
      (-> (p/let [result (ipc/ipc :db-worker-runtime "logseq_db_graph_a")]
            (is (= 1 (count @payloads)))
            (is (string? (first @payloads)))
            (is (= [:db-worker-runtime "logseq_db_graph_a"]
                   (vec (sqlite-util/read-transit-str (first @payloads)))))
            (is (= {:ok true} result)))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally (fn []
                       (set! util/electron? original-electron?)
                       (set! (.-apis js/window) original-apis)
                       (done)))))))

(deftest ipc-keeps-non-string-response-unchanged
  (async done
    (let [original-electron? util/electron?
          original-apis (.-apis js/window)]
      (set! util/electron? (constantly true))
      (set! (.-apis js/window)
            #js {:doAction (fn [_payload]
                             (js/Promise.resolve #js {:legacy true}))})
      (-> (p/let [result (ipc/ipc :system/info)]
            (is (= true (aget result "legacy"))))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally (fn []
                       (set! util/electron? original-electron?)
                       (set! (.-apis js/window) original-apis)
                       (done)))))))

(deftest invoke-sends-an-empty-array-without-arguments
  (async done
    (let [received (atom nil)
          original-electron? util/electron?
          original-apis (.-apis js/window)]
      (set! util/electron? (constantly true))
      (set! (.-apis js/window)
            #js {:invoke (fn [channel args]
                           (reset! received {:channel channel :args args})
                           (js/Promise.resolve "downloaded"))})
      (-> (p/let [result (ipc/invoke "get-downloaded-update")]
            (is (= "downloaded" result))
            (is (= "get-downloaded-update" (:channel @received)))
            (is (array? (:args @received)))
            (is (= [] (js->clj (:args @received)))))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally (fn []
                       (set! util/electron? original-electron?)
                       (set! (.-apis js/window) original-apis)
                       (done)))))))

(deftest invoke-preserves-argument-order-and-conversion
  (async done
    (let [received (atom nil)
          original-electron? util/electron?
          original-apis (.-apis js/window)]
      (set! util/electron? (constantly true))
      (set! (.-apis js/window)
            #js {:invoke (fn [channel args]
                           (reset! received {:channel channel :args args})
                           (js/Promise.resolve true))})
      (-> (p/let [result (ipc/invoke "synthetic-argument-check" "first" {:enabled true} #js {:count 2})]
            (is (true? result))
            (is (= "synthetic-argument-check" (:channel @received)))
            (is (array? (:args @received)))
            (is (= ["first" {"enabled" true} {"count" 2}]
                   (js->clj (:args @received)))))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally (fn []
                       (set! util/electron? original-electron?)
                       (set! (.-apis js/window) original-apis)
                       (done)))))))
