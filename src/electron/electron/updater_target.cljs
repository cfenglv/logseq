(ns electron.updater-target
  (:require ["node:crypto" :refer [createHash]]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:url" :refer [pathToFileURL]]
            ["yauzl" :as yauzl]
            [promesa.core :as p]))

(def ^:private max-manifest-bytes (* 64 1024))
(def ^:private current-formats
  #js {:activation "selfhost-activation-v1"
       :clientOps "official-client-ops-sqlite-v2+selfhost-upload-v1"})
(defonce ^:private *signature-module (atom nil))
(defonce ^:private *manifest-module (atom nil))

(def ^:private <native-import
  ;; The packaged validators are ESM resources. Keeping import() inside a
  ;; Function prevents Closure from rewriting the runtime module URL.
  (js/Function. "moduleUrl" "return import(moduleUrl);"))

(defn- packaged-module!
  [cache filename]
  (or @cache
      (let [module-url (-> (node-path/join (.-resourcesPath js/process)
                                           "updater"
                                           filename)
                           pathToFileURL
                           (.-href))
            module (<native-import module-url)]
        (reset! cache module)
        module)))

(defn- target-manifest-entry?
  [entry-name]
  (or (.endsWith entry-name "/Contents/Resources/updater/TARGET_BUILD_MANIFEST.json")
      (.endsWith entry-name "/resources/updater/TARGET_BUILD_MANIFEST.json")
      (= entry-name "resources/updater/TARGET_BUILD_MANIFEST.json")))

(defn read-target-manifest-from-zip!
  "Reads one bounded target manifest without extracting or buffering the
  archive. The downloaded archive digest remains owned by electron-updater."
  [archive-path]
  (js/Promise.
   (fn [resolve reject]
     (.open yauzl archive-path #js {:lazyEntries true :autoClose true}
            (fn [open-error ^js zipfile]
              (if open-error
                (reject open-error)
                (let [settled? (atom false)
                      found* (atom nil)
                      fail! (fn [error]
                              (when (compare-and-set! settled? false true)
                                (.close zipfile)
                                (reject error)))
                      next! #(.readEntry zipfile)]
                  (.on zipfile "error" fail!)
                  (.on zipfile "end"
                       (fn []
                         (when (compare-and-set! settled? false true)
                           (if-let [result @found*]
                             (resolve result)
                             (reject (ex-info "target build manifest is missing from update ZIP"
                                              {:code :target-manifest-missing}))))))
                  (.on zipfile "entry"
                       (fn [^js entry]
                         (let [entry-name (.-fileName entry)]
                           (if-not (target-manifest-entry? entry-name)
                             (next!)
                             (if @found*
                               (fail! (ex-info "update ZIP contains more than one target build manifest"
                                               {:code :duplicate-target-manifest}))
                               (if (> (.-uncompressedSize entry) max-manifest-bytes)
                                 (fail! (ex-info "target build manifest exceeds its byte limit"
                                                 {:code :target-manifest-too-large}))
                                 (.openReadStream
                                  zipfile entry
                                  (fn [stream-error ^js stream]
                                    (if stream-error
                                      (fail! stream-error)
                                      (let [chunks (atom [])
                                            bytes-read (atom 0)]
                                        (.on stream "data"
                                             (fn [^js chunk]
                                               (let [total (+ @bytes-read (.-length chunk))]
                                                 (if (> total max-manifest-bytes)
                                                   (do
                                                     (.destroy stream)
                                                     (fail! (ex-info "target build manifest exceeds its byte limit"
                                                                     {:code :target-manifest-too-large})))
                                                   (do
                                                     (reset! bytes-read total)
                                                     (swap! chunks conj chunk))))))
                                        (.on stream "error" fail!)
                                        (.on stream "end"
                                             (fn []
                                               (when-not @settled?
                                                 (try
                                                   (let [payload-bytes (js/Buffer.concat (to-array @chunks))
                                                         manifest (js/JSON.parse (.toString payload-bytes "utf8"))
                                                         digest (-> (createHash "sha256")
                                                                    (.update payload-bytes)
                                                                    (.digest "hex"))]
                                                     (reset! found* #js {:manifest manifest
                                                                        :sha256 digest
                                                                        :bytesRead @bytes-read})
                                                     (next!))
                                                   (catch :default error
                                                     (fail! error)))))))))))))))
                  (next!)))))))))

(defn ^:no-doc preflight-downloaded-target-with-modules!
  [{:keys [downloaded-file signed-metadata archive-digest-verified
           verified-archive-sha512]}
   ^js signature-module
   ^js manifest-module]
  (when-not (and (string? downloaded-file)
                 (.endsWith (.toLowerCase downloaded-file) ".zip"))
    (throw (ex-info "this updater target needs a qualified archive reader"
                    {:code :unsupported-updater-target-container})))
  (p/let [verified-metadata (.verifySignedUpdateMetadata signature-module
                                                        #js {:signedMetadata signed-metadata})
          signed-sha512-base64 (.toString (js/Buffer.from (aget verified-metadata "archive-sha512") "hex")
                                          "base64")
          _ (when-not (= verified-archive-sha512 signed-sha512-base64)
              (throw (ex-info "electron-updater archive digest differs from signed metadata"
                              {:code :target-archive-digest-mismatch})))
          ^js stat (fs/statSync downloaded-file)
          _ (when-not (= (.-size stat) (aget verified-metadata "archive-size"))
              (throw (ex-info "downloaded target size does not match signed metadata"
                              {:code :target-archive-size-mismatch})))
          ^js extracted (read-target-manifest-from-zip! downloaded-file)
          manifest (.-manifest extracted)
          _ (when-not (= (.-sha256 extracted)
                         (aget verified-metadata "target-build-manifest-sha256"))
              (throw (ex-info "target build manifest digest does not match signed metadata"
                              {:code :target-manifest-digest-mismatch})))
          expected #js {:targetSourceFullSha (aget verified-metadata "target-source-full-sha")
                        :targetVersion (aget verified-metadata "target-version")
                        :releaseLineId (aget verified-metadata "release-line-id")
                        :platform (aget verified-metadata "platform")
                        :arch (aget verified-metadata "arch")
                        :bundleIdentity (aget verified-metadata "bundle-identity")
                        :signingKeyIdentity (aget verified-metadata "key-id")}
          _ (.validateTargetBuildManifest manifest-module
                                          #js {:manifest manifest
                                               :archiveDigestVerified archive-digest-verified
                                               :expected expected
                                               :currentFormats current-formats})]
    (doseq [field ["readable-activation-formats"
                   "readable-client-ops-formats"
                   "activation-write-format"
                   "client-ops-write-format"]]
      (when-not (= (js/JSON.stringify (aget manifest field))
                   (js/JSON.stringify (aget verified-metadata field)))
        (throw (ex-info "target manifest differs from signed compatibility metadata"
                        {:code :target-manifest-signature-mismatch
                         :field field}))))
    true))

(defn preflight-downloaded-target!
  [target]
  (p/let [^js signature-module (packaged-module! *signature-module "project-update-signature.mjs")
          ^js manifest-module (packaged-module! *manifest-module "target-build-manifest.mjs")]
    (preflight-downloaded-target-with-modules! target signature-module manifest-module)))
