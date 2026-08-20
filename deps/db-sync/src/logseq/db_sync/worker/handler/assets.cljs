(ns logseq.db-sync.worker.handler.assets
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [logseq.db-sync.common :as common :refer [cors-headers]]
            [logseq.db-sync.worker.http :as http]
            [promesa.core :as p]))

(def max-asset-size (* 100 1024 1024))

(def ^:private asset-type->content-type
  {"png" "image/png"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "gif" "image/gif"
   "webp" "image/webp"
   "bmp" "image/bmp"
   "svg" "image/svg+xml"
   "ico" "image/x-icon"
   "pdf" "application/pdf"})

(defn- response-content-type [stored-content-type asset-type]
  (or (get asset-type->content-type (string/lower-case asset-type))
      stored-content-type
      "application/octet-stream"))

(defn- parse-size
  [size]
  (cond
    (number? size) size
    (string? size) (let [n (js/parseInt size 10)]
                     (when-not (js/isNaN n)
                       n))
    :else nil))

(defn- abort-upload-pipes!
  [^js abort-controller error]
  (when abort-controller
    (try
      (.abort abort-controller error)
      (catch :default _ nil))))

(defn- record-first-failure!
  [first-failure error]
  (compare-and-set! first-failure nil error)
  error)

(defn- capture-pipe-failure
  "Attach a rejection handler in the same turn that an upload participant
  starts. Every participant resolves this guarded Promise after retaining its
  original failure, so the aggregate cannot fail fast while another stream is
  still reading the request body. The first failure also aborts the shared
  pipe graph."
  [pipe-promise abort-controller first-failure]
  (let [failure (atom nil)]
    {:promise (p/catch (js/Promise.resolve pipe-promise)
                       (fn [error]
                         (reset! failure error)
                         (record-first-failure! first-failure error)
                         (abort-upload-pipes! abort-controller error)
                         nil))
     :failure failure}))

(defn- fixed-length-body
  ([body size]
   (fixed-length-body body size nil (atom nil)))
  ([body size abort-controller first-failure]
   (when (and (number? size)
              (exists? js/FixedLengthStream)
              (some? body)
              (fn? (.-pipeTo body)))
     (let [^js fixed (js/FixedLengthStream. size)
           pipe-promise
           (if abort-controller
             (.pipeTo body (.-writable fixed)
                      #js {:signal (.-signal abort-controller)})
             (.pipeTo body (.-writable fixed)))]
       (merge {:body (.-readable fixed)}
              (capture-pipe-failure
               pipe-promise abort-controller first-failure))))))

(defn- decoded-base64-chunk
  [value]
  (when-not (re-matches #"(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?" value)
    (throw (js/Error. "invalid base64 asset body")))
  (let [decoded (js/atob value)
        result (js/Uint8Array. (.-length decoded))]
    (dotimes [index (.-length decoded)]
      (aset result index (.charCodeAt decoded index)))
    result))

(defn- base64-decoded-body
  [body abort-controller first-failure]
  (let [remainder (atom "")
        decoder (js/TextDecoder.)
        transform (js/TransformStream.
                   #js {:transform
                        (fn [chunk controller]
                          (let [text (string/replace
                                      (str @remainder (.decode decoder chunk #js {:stream true}))
                                      #"\s" "")
                                complete-length (* 4 (max 0 (dec (quot (count text) 4))))
                                complete (subs text 0 complete-length)]
                            (reset! remainder (subs text complete-length))
                            (when (seq complete)
                              (.enqueue controller (decoded-base64-chunk complete)))))
                        :flush
                        (fn [controller]
                          (let [tail (string/replace (str @remainder (.decode decoder)) #"\s" "")]
                            (when (seq tail)
                              (.enqueue controller (decoded-base64-chunk tail)))))})
        pipe-promise
        (if abort-controller
          (.pipeTo body (.-writable transform)
                   #js {:signal (.-signal abort-controller)})
          (.pipeTo body (.-writable transform)))]
    (merge {:body (.-readable transform)}
           (capture-pipe-failure
            pipe-promise abort-controller first-failure))))

(def ^:private best-effort-cleanup-timeout-ms 1000)

(defn- <bounded-best-effort!
  [start!]
  (js/Promise.
   (fn [resolve _reject]
     (let [settled? (atom false)
           timer* (atom nil)
           finish! (fn [& _]
                     (when (compare-and-set! settled? false true)
                       (when-let [timer @timer*]
                         (js/clearTimeout timer))
                       (resolve nil)))]
       (reset! timer* (js/setTimeout finish! best-effort-cleanup-timeout-ms))
       (try
         (.then (js/Promise.resolve (start!)) finish! finish!)
         (catch :default _
           (finish!)))))))

(defn- <best-effort-delete!
  [^js bucket key]
  (if (fn? (.-delete bucket))
    (<bounded-best-effort! #(.delete bucket key))
    (p/resolved nil)))

(defn- <best-effort-cancel!
  [^js body error]
  (if (fn? (some-> body .-cancel))
    (<bounded-best-effort! #(.cancel body error))
    (p/resolved nil)))

(defn- response-fixed-length-body
  [body size]
  (let [{stream-body :body pipe-promise :promise} (fixed-length-body body size)]
    (when pipe-promise
      ;; The response consumes the paired readable stream after this handler returns.
      (p/catch pipe-promise (fn [_] nil)))
    stream-body))

(defn <put-stream!
  "Streams `body` with a declared byte length to the R2 `bucket` without buffering it in Worker memory.

  Options:

  | key             | description |
  |-----------------|-------------|
  | `:size`         | Exact payload size in bytes |
  | `:content-type` | HTTP content type stored in R2 metadata |
  | `:checksum`     | Client-computed SHA-256 checksum |
  | `:asset-type`   | File extension stored in custom metadata |
  | `:encoding`     | Optional `base64` streaming transfer encoding |
  | `:cleanup-on-failure?` | Delete a failed new-key upload; never enable for overwrites |"
  [^js bucket key body
   {:keys [size content-type checksum asset-type encoding cleanup-on-failure?]}]
  (cond
    (or (not (number? size)) (neg? size))
    (p/resolved (http/error-response "invalid asset size" 400))

    (> size max-asset-size)
    (p/resolved (http/error-response "asset too large" 413))

    (and (nil? body) (pos? size))
    (p/resolved (http/error-response "missing asset body" 400))

    :else
    (let [abort-controller
          (when (exists? js/AbortController)
            (js/AbortController.))
          first-failure (atom nil)
          {decoded-body :body
           decode-promise :promise
           decode-failure :failure}
          (when (and (= "base64" encoding) (some? body))
            (base64-decoded-body body abort-controller first-failure))
          source-body (or decoded-body body (js/Uint8Array. 0))
          {stream-body :body
           fixed-length-promise :promise
           fixed-length-failure :failure}
          (fixed-length-body source-body size abort-controller first-failure)
          upload-body (or stream-body source-body)
          put-result
          (try
            (.put bucket key upload-body
                  #js {:httpMetadata #js {:contentType (or content-type "application/octet-stream")}
                       :customMetadata #js {:checksum checksum :type asset-type}
                       ;; Ignored by R2, consumed by the Node adapter
                       ;; to reject truncated or oversized streams.
                       :logseqExpectedSize size})
            (catch :default error
              (p/rejected error)))
          put-failure (atom nil)
          put-promise
          (p/catch
           (js/Promise.resolve put-result)
           (fn [error]
             (reset! put-failure error)
             (record-first-failure! first-failure error)
             (abort-upload-pipes! abort-controller error)
             (<best-effort-cancel! upload-body error)))]
      (-> (p/all (cond-> [put-promise]
                   decode-promise (conj decode-promise)
                   fixed-length-promise (conj fixed-length-promise)))
          (p/then (fn [_]
                    (if-let [error (or @first-failure
                                       (some-> decode-failure deref)
                                       (some-> fixed-length-failure deref)
                                       (some-> put-failure deref))]
                      (p/let [_ (when cleanup-on-failure?
                                  (<best-effort-delete! bucket key))]
                        (if (= "invalid base64 asset body" (.-message error))
                          (http/error-response (.-message error) 400)
                          (throw error)))
                      (http/json-response :assets/put {:ok true} 200))))))))

(defn- <body-with-known-length
  [body size]
  (cond
    (nil? body)
    (p/resolved nil)

    (and (number? size)
         (exists? js/FixedLengthStream)
         (fn? (some-> body .-pipeTo)))
    (p/resolved (response-fixed-length-body body size))

    ;; Some runtimes drop content-length for streamed bodies without a fixed-length wrapper.
    ;; Buffer as a fallback so clients still receive the header.
    (and (number? size)
         (fn? (some-> body .-getReader)))
    (p/let [resp (js/Response. body)
            buf (.arrayBuffer resp)]
      buf)

    :else
    (p/resolved body)))

(defn- handle-get-asset
  [^js bucket key asset-type]
  (.then (.get bucket key)
         (fn [^js obj]
           (if (nil? obj)
             (http/error-response "not found" 404)
             (let [metadata (.-httpMetadata obj)
                   content-type (response-content-type (.-contentType metadata) asset-type)
                   content-encoding (.-contentEncoding metadata)
                   cache-control (.-cacheControl metadata)
                   size (parse-size (or (.-size obj)
                                        (some-> (.-body obj) .-byteLength)))
                   content-length (cond
                                    (number? size) (str size)
                                    (string? size) size
                                    :else nil)]
               (p/let [body (<body-with-known-length (.-body obj) size)
                       headers (cond-> {"content-type" content-type
                                        "content-disposition" (str "inline; filename=\""
                                                                   (last (string/split key #"/")) "\"")
                                        "x-asset-type" asset-type}
                                 (and (string? content-length)
                                      (pos? (.-length content-length)))
                                 (assoc "content-length" content-length)
                                 (and (string? content-length)
                                      (pos? (.-length content-length)))
                                 (assoc "x-asset-size" content-length)
                                 (and (string? content-encoding)
                                      (not= content-encoding "null")
                                      (pos? (.-length content-encoding)))
                                 (assoc "content-encoding" content-encoding)
                                 (and (string? cache-control)
                                      (pos? (.-length cache-control)))
                                 (assoc "cache-control" cache-control)
                                 true
                                 (bean/->js))]
                 (js/Response. body
                               #js {:status 200
                                    :headers (js/Object.assign
                                              headers
                                              (cors-headers))})))))))

(defn parse-asset-path [path]
  (let [prefix "/assets/"]
    (when (string/starts-with? path prefix)
      (let [rest-path (subs path (count prefix))
            slash-idx (string/index-of rest-path "/")
            graph-id (when (and slash-idx (pos? slash-idx)) (subs rest-path 0 slash-idx))
            file (when (and slash-idx (pos? slash-idx)) (subs rest-path (inc slash-idx)))
            dot-idx (when file (string/last-index-of file "."))
            asset-uuid (when (and dot-idx (pos? dot-idx)) (subs file 0 dot-idx))
            asset-type (when (and dot-idx (pos? dot-idx)) (subs file (inc dot-idx)))]
        (when (and (seq graph-id) (seq asset-uuid) (seq asset-type))
          {:graph-id graph-id
           :asset-uuid asset-uuid
           :asset-type asset-type
           :key (str graph-id "/" asset-uuid "." asset-type)})))))

(defn handle [request ^js env]
  (let [url (js/URL. (.-url request))
        path (.-pathname url)
        method (.-method request)]
    (cond
      (= method "OPTIONS")
      (js/Response. nil #js {:status 204 :headers (cors-headers)})

      :else
      (if-let [{:keys [key asset-type]} (parse-asset-path path)]
        (let [^js bucket (.-LOGSEQ_SYNC_ASSETS env)]
          (if-not bucket
            (http/error-response "missing assets bucket" 500)
            (case method
              "GET"
              (handle-get-asset bucket key asset-type)

              "PUT"
              (let [headers (.-headers request)
                    declared-size-header (.get headers "x-logseq-asset-size")
                    content-type (or (.get headers "content-type")
                                     "application/octet-stream")
                    checksum (.get headers "x-amz-meta-checksum")]
                (if (some? declared-size-header)
                  (<put-stream! bucket
                                key
                                (.-body request)
                                {:size (parse-size declared-size-header)
                                 :content-type content-type
                                 :checksum checksum
                                 :asset-type asset-type})
                  ;; Legacy clients do not send x-logseq-asset-size. Keep their
                  ;; request contract unchanged and validate the actual buffered
                  ;; length before writing.
                  (.then (.arrayBuffer request)
                         (fn [buf]
                           (if (> (.-byteLength buf) max-asset-size)
                             (http/error-response "asset too large" 413)
                             (.then (.put bucket
                                          key
                                          buf
                                          #js {:httpMetadata #js {:contentType content-type}
                                               :customMetadata #js {:checksum checksum
                                                                    :type asset-type}})
                                    (fn [_]
                                      (http/json-response :assets/put {:ok true} 200))))))))

              "DELETE"
              (.then (.delete bucket key)
                     (fn [_]
                       (http/json-response :assets/delete {:ok true} 200)))

              (http/error-response "method not allowed" 405))))
        (http/error-response "invalid asset path" 400)))))
