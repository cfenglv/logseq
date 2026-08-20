(ns electron.updater-target
  (:require ["node:crypto" :refer [createHash]]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            ["node:url" :refer [pathToFileURL]]
            ["yauzl" :as yauzl]
            [promesa.core :as p]))

(def ^:private max-manifest-bytes (* 64 1024))
(def ^:private preflight-timeout-ms 2000)
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

(defn- parse-manifest-bytes
  [payload-bytes]
  (when (> (.-length ^js payload-bytes) max-manifest-bytes)
    (throw (ex-info "target build manifest exceeds its byte limit"
                    {:code :target-manifest-too-large})))
  #js {:manifest (js/JSON.parse (.toString ^js payload-bytes "utf8"))
       :sha256 (-> (createHash "sha256")
                   (.update payload-bytes)
                   (.digest "hex"))
       :bytesRead (.-length ^js payload-bytes)})

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
                                                   (let [payload-bytes (js/Buffer.concat (to-array @chunks))]
                                                     (reset! found* (parse-manifest-bytes payload-bytes))
                                                     (next!))
                                                   (catch :default error
                                                     (fail! error)))))))))))))))
                  (next!)))))))))

(defn read-target-manifest-from-nsis!
  [archive-path helper-path]
  (js/Promise.
   (fn [resolve reject]
     (.execFile child-process
                helper-path
                #js ["e" "-bd" "-bb0" "-so" "-r" archive-path "TARGET_BUILD_MANIFEST.json"]
                #js {:encoding nil
                     :maxBuffer (inc max-manifest-bytes)
                     :timeout preflight-timeout-ms}
                (fn [error stdout stderr]
                  (if error
                    (reject (ex-info "cannot read target manifest from NSIS archive"
                                     {:code :target-manifest-container-read-failed
                                      :detail (some-> (or stderr (.-message ^js error)) str)}))
                    (try
                      (resolve (parse-manifest-bytes stdout))
                      (catch :default parse-error
                        (reject parse-error)))))))))

(defn- find-extracted-manifest
  [root]
  (loop [pending [root]
         visited 0
         found []]
    (when (> visited 256)
      (throw (ex-info "AppImage manifest extraction produced too many entries"
                      {:code :target-manifest-extraction-unbounded})))
    (if-let [path (peek pending)]
      (let [pending (pop pending)
            ^js stat (fs/lstatSync path)]
        (cond
          (.isSymbolicLink stat)
          (recur pending (inc visited) found)

          (.isDirectory stat)
          (recur (into pending (map #(node-path/join path %) (array-seq (fs/readdirSync path))))
                 (inc visited)
                 found)

          (= "TARGET_BUILD_MANIFEST.json" (node-path/basename path))
          (recur pending (inc visited) (conj found path))

          :else
          (recur pending (inc visited) found)))
      (when (= 1 (count found))
        (first found)))))

(defn read-target-manifest-from-appimage!
  [archive-path]
  (let [directory (fs/mkdtempSync (node-path/join (os/tmpdir) "selfhost6-appimage-manifest-"))]
    (js/Promise.
     (fn [resolve reject]
       (.execFile child-process
                  archive-path
                  #js ["--appimage-extract" "*/resources/updater/TARGET_BUILD_MANIFEST.json"]
                  #js {:cwd directory
                       :encoding "utf8"
                       :maxBuffer (* 1024 1024)
                       :timeout preflight-timeout-ms}
                  (fn [error _stdout stderr]
                    (try
                      (if error
                        (reject (ex-info "cannot read target manifest from AppImage"
                                         {:code :target-manifest-container-read-failed
                                          :detail (or (not-empty stderr) (.-message ^js error))}))
                        (if-let [manifest-path (find-extracted-manifest directory)]
                          (let [^js stat (fs/statSync manifest-path)]
                            (if (> (.-size stat) max-manifest-bytes)
                              (reject (ex-info "target build manifest exceeds its byte limit"
                                               {:code :target-manifest-too-large}))
                              (resolve (parse-manifest-bytes (fs/readFileSync manifest-path)))))
                          (reject (ex-info "target build manifest is missing from AppImage"
                                           {:code :target-manifest-missing}))))
                      (catch :default read-error
                        (reject read-error))
                      (finally
                        (fs/rmSync directory #js {:recursive true :force true})))))))))

(defn- read-target-manifest!
  [downloaded-file]
  (let [lower (.toLowerCase downloaded-file)]
    (cond
      (.endsWith lower ".zip")
      (read-target-manifest-from-zip! downloaded-file)

      (and (= "win32" (.-platform js/process)) (.endsWith lower ".exe"))
      (read-target-manifest-from-nsis!
       downloaded-file
       (node-path/join (.-resourcesPath js/process) "updater" "7za.exe"))

      (and (= "linux" (.-platform js/process)) (.endsWith lower ".appimage"))
      (read-target-manifest-from-appimage! downloaded-file)

      :else
      (p/rejected (ex-info "this updater target needs a qualified archive reader"
                           {:code :unsupported-updater-target-container})))))

(defn ^:no-doc preflight-downloaded-target-with-modules!
  [{:keys [downloaded-file signed-metadata archive-digest-verified
           verified-archive-sha512]}
   ^js signature-module
   ^js manifest-module]
  (when-not (string? downloaded-file)
    (throw (ex-info "downloaded target path is missing"
                    {:code :missing-downloaded-update-target})))
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
          ^js extracted (read-target-manifest! downloaded-file)
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
