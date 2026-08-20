(ns logseq.db-sync.worker-handler-assets-test
  (:require [cljs.test :refer [async deftest is]]
            [logseq.db-sync.index :as index]
            [logseq.db-sync.worker.handler.assets :as assets]
            [promesa.core :as p]))

(defn- bytes->stream
  [^js payload]
  (js/ReadableStream.
   #js {:start (fn [controller]
                 (.enqueue controller payload)
                 (.close controller))}))

(defn- put-request-with-size
  [size]
  #js {:url "http://localhost/assets/graph-1/asset-1.bin"
       :method "PUT"
       :headers #js {:get (constantly nil)}
       :arrayBuffer (fn [] (p/resolved #js {:byteLength size}))})

(deftest asset-put-get-round-trip-reuses-existing-r2-owner-test
  (async done
    (let [stored (atom nil)
          payload (js/Uint8Array. #js [11 22 33 44])
          bucket #js {:put (fn [key body ^js opts]
                             (reset! stored
                                     {:key key
                                      :body body
                                      :http-metadata (.-httpMetadata opts)})
                             (p/resolved nil))
                      :get (fn [key]
                             (let [{stored-key :key body :body
                                    http-metadata :http-metadata} @stored]
                               (p/resolved
                                (when (= key stored-key)
                                  #js {:body body
                                       :size (.-byteLength body)
                                       :httpMetadata http-metadata}))))}
          env #js {:DB #js {}
                   :LOGSEQ_SYNC_ASSETS bucket}
          put-request (js/Request.
                       "http://localhost/assets/graph-1/round-trip.bin"
                       #js {:method "PUT"
                            :headers #js {"content-type" "application/octet-stream"}
                            :body payload})
          get-request (js/Request.
                       "http://localhost/assets/graph-1/round-trip.bin"
                       #js {:method "GET"})]
      (-> (p/with-redefs [index/<graph-e2ee? (fn [_ _]
                                               (p/resolved true))]
            (p/let [put-response (assets/handle put-request env)
                    get-response (assets/handle get-request env)
                    received (.arrayBuffer get-response)]
              (is (= 200 (.-status put-response)))
              (is (= 200 (.-status get-response)))
              (is (= "graph-1/round-trip.bin" (:key @stored)))
              (is (= (vec payload)
                     (vec (js/Uint8Array. received))))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done)))))))

(deftest encrypted-assets-allow-upload-at-larger-limit-test
  (async done
         (let [request (put-request-with-size (* 200 1024 1024))
               put-calls (atom 0)
               env #js {:DB #js {}
                        :LOGSEQ_SYNC_ASSETS
                        #js {:put (fn [& _]
                                    (swap! put-calls inc)
                                    (p/resolved nil))}}]
           (-> (p/with-redefs [index/<graph-e2ee? (fn [_ _] (p/resolved true))]
                 (p/let [resp (assets/handle request env)]
                   (is (= 200 (.-status resp)))
                   (is (= 1 @put-calls))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest unencrypted-assets-keep-existing-upload-limit-test
  (async done
         (let [request (put-request-with-size (* 150 1024 1024))
               put-calls (atom 0)
               env #js {:DB #js {}
                        :LOGSEQ_SYNC_ASSETS
                        #js {:put (fn [& _]
                                    (swap! put-calls inc)
                                    (p/resolved nil))}}]
           (-> (p/with-redefs [index/<graph-e2ee? (fn [_ _] (p/resolved false))]
                 (p/let [resp (assets/handle request env)]
                   (is (= 413 (.-status resp)))
                   (is (zero? @put-calls))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest encrypted-assets-still-reject-uploads-above-larger-limit-test
  (async done
         (let [request (put-request-with-size (inc (* 200 1024 1024)))
               put-calls (atom 0)
               env #js {:DB #js {}
                        :LOGSEQ_SYNC_ASSETS
                        #js {:put (fn [& _]
                                    (swap! put-calls inc)
                                    (p/resolved nil))}}]
           (-> (p/with-redefs [index/<graph-e2ee? (fn [_ _] (p/resolved true))]
                 (p/let [resp (assets/handle request env)]
                   (is (= 413 (.-status resp)))
                   (is (zero? @put-calls))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

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
