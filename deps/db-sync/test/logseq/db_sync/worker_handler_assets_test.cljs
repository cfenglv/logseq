(ns logseq.db-sync.worker-handler-assets-test
  (:require [cljs.test :refer [async deftest is]]
            [logseq.db-sync.worker.handler.assets :as assets]
            [promesa.core :as p]))

(defn- bytes->stream
  [^js payload]
  (js/ReadableStream.
   #js {:start (fn [controller]
                 (.enqueue controller payload)
                 (.close controller))}))

(deftest assets-get-includes-content-length-header-test
  (async done
         (let [payload (js/Uint8Array. #js [1 2 3 4])
               request (js/Request. "http://localhost/assets/graph-1/snapshot-1.snapshot"
                                    #js {:method "GET"})
               env #js {:LOGSEQ_SYNC_ASSETS
                        #js {:get (fn [_key]
                                    (js/Promise.resolve
                                     #js {:body payload
                                          :size 4
                                          :httpMetadata #js {:contentType "application/octet-stream"}}))}}]
           (-> (p/let [resp (assets/handle request env)]
                 (is (= 200 (.-status resp)))
                 (is (= "4" (.get (.-headers resp) "content-length")))
                 (is (= "4" (.get (.-headers resp) "x-asset-size"))))
               (p/then (fn []
                         (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest assets-get-includes-content-length-with-stream-body-without-fixed-length-stream-test
  (async done
         (let [payload (js/Uint8Array. #js [1 2 3 4])
               request (js/Request. "http://localhost/assets/graph-1/snapshot-1.snapshot"
                                    #js {:method "GET"})
               env #js {:LOGSEQ_SYNC_ASSETS
                        #js {:get (fn [_key]
                                    (js/Promise.resolve
                                     #js {:body (bytes->stream payload)
                                          :size 4
                                          :httpMetadata #js {:contentType "application/octet-stream"}}))}}
               original-fixed-length-stream (.-FixedLengthStream js/globalThis)
               restore! #(aset js/globalThis "FixedLengthStream" original-fixed-length-stream)]
           (aset js/globalThis "FixedLengthStream" js/undefined)
           (-> (p/let [resp (assets/handle request env)
                       buf (.arrayBuffer resp)]
                 (is (= 200 (.-status resp)))
                 (is (= "4" (.get (.-headers resp) "content-length")))
                 (is (= "4" (.get (.-headers resp) "x-asset-size")))
                 (is (= 4 (.-byteLength buf))))
               (p/then (fn []
                         (restore!)
                         (done)))
               (p/catch (fn [error]
                          (restore!)
                          (is false (str error))
                          (done)))))))

(deftest assets-get-serves-image-inline-with-extension-content-type-test
  (async done
         (let [payload (js/Uint8Array. #js [1 2 3 4])
               request (js/Request. "http://localhost/assets/graph-1/image-id.png"
                                    #js {:method "GET"})
               env #js {:LOGSEQ_SYNC_ASSETS
                        #js {:get (fn [_key]
                                    (p/resolved
                                     #js {:body payload
                                          :size 4
                                          ;; Existing Code Mode uploads stored the transfer MIME type.
                                          :httpMetadata #js {:contentType "application/json"}}))}}]
           (-> (p/let [resp (assets/handle request env)]
                 (is (= 200 (.-status resp)))
                 (is (= "image/png" (.get (.-headers resp) "content-type")))
                 (is (= "inline; filename=\"image-id.png\""
                        (.get (.-headers resp) "content-disposition"))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest assets-put-streams-when-new-client-declares-exact-size-test
  (async done
         (let [payload (js/Uint8Array. #js [1 2 3 4])
               request (js/Request.
                        "http://localhost/assets/graph-1/image-id.png"
                        #js {:method "PUT"
                             :headers #js {"content-type" "image/png"
                                           "x-amz-meta-checksum" "checksum-1"
                                           "x-logseq-asset-size" "4"}
                             :body payload})
               array-buffer-calls (atom 0)
               puts (atom [])
               env #js {:LOGSEQ_SYNC_ASSETS
                        #js {:put (fn [key body opts]
                                   (swap! puts conj {:key key
                                                     :body body
                                                     :opts opts})
                                   (p/let [buf (.arrayBuffer (js/Response. body))]
                                     (is (= 4 (.-byteLength buf)))
                                     true))}}]
           ;; The new path must not fall back to Request.arrayBuffer.
           (aset request "arrayBuffer"
                 (fn []
                   (swap! array-buffer-calls inc)
                   (p/rejected (js/Error. "unexpected buffering"))))
           (-> (p/let [resp (assets/handle request env)]
                 (is (= 200 (.-status resp)))
                 (is (= 0 @array-buffer-calls))
                 (is (= 1 (count @puts)))
                 (is (= "graph-1/image-id.png" (:key (first @puts))))
                 (is (fn? (some-> (:body (first @puts)) .-getReader)))
                 (is (= "checksum-1"
                        (some-> (:opts (first @puts))
                                (aget "customMetadata")
                                (aget "checksum")))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest assets-put-keeps-legacy-buffered-request-contract-test
  (async done
         (let [payload (js/Uint8Array. #js [1 2 3 4])
               request (js/Request.
                        "http://localhost/assets/graph-1/legacy-id.png"
                        #js {:method "PUT"
                             :headers #js {"content-type" "image/png"
                                           "x-amz-meta-checksum" "legacy-checksum"}
                             :body payload})
               original-array-buffer (.bind (.-arrayBuffer request) request)
               array-buffer-calls (atom 0)
               puts (atom [])
               env #js {:LOGSEQ_SYNC_ASSETS
                        #js {:put (fn [key body _opts]
                                   (swap! puts conj {:key key :body body})
                                   (p/resolved true))}}]
           (aset request "arrayBuffer"
                 (fn []
                   (swap! array-buffer-calls inc)
                   (original-array-buffer)))
           (-> (p/let [resp (assets/handle request env)]
                 (is (= 200 (.-status resp)))
                 (is (= 1 @array-buffer-calls))
                 (is (= 1 (count @puts)))
                 (is (= "graph-1/legacy-id.png" (:key (first @puts))))
                 (is (= 4 (.-byteLength (:body (first @puts))))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))
