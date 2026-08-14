(ns electron.updater-target-test
  (:require ["jszip" :as JSZip]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [electron.updater-target :as updater-target]
            [promesa.core :as p]))

(defn- with-zip!
  [entries f]
  (let [dir (fs/mkdtempSync (node-path/join (os/tmpdir) "selfhost6-target-"))
        archive (node-path/join dir "target.zip")
        zip (new JSZip)]
    (doseq [[path content] entries]
      (.file zip path content))
    (-> (.generateAsync zip #js {:type "nodebuffer"})
        (p/then (fn [zip-bytes]
                  (fs/writeFileSync archive zip-bytes)
                  (f archive)))
        (p/finally (fn []
                     (fs/rmSync dir #js {:recursive true :force true}))))))

(deftest reads-one-bounded-manifest-from-the-target-zip
  (async done
    (-> (with-zip!
         [["Logseq.app/Contents/Resources/updater/TARGET_BUILD_MANIFEST.json"
           (js/JSON.stringify #js {:schema-version 1})]
          ["Logseq.app/Contents/Resources/other.txt" "ignored"]]
         (fn [archive]
           (p/let [^js result (updater-target/read-target-manifest-from-zip! archive)]
             (is (= 1 (aget (.-manifest result) "schema-version")))
             (is (= 64 (count (.-sha256 result))))
             (is (pos? (.-bytesRead result))))))
        (p/catch (fn [error]
                   (is false (str "unexpected error: " error))))
        (p/finally done))))

(deftest missing-target-manifest-fails-closed
  (async done
    (-> (with-zip!
         [["Logseq.app/Contents/Resources/other.json" "{}"]]
         (fn [archive]
           (-> (updater-target/read-target-manifest-from-zip! archive)
               (p/then (fn [_] :unexpected-success))
               (p/catch (fn [error]
                          (is (= :target-manifest-missing (:code (ex-data error)))))))))
        (p/catch (fn [error]
                   (is false (str "unexpected error: " error))))
        (p/finally done))))

(deftest preflight-binds-electron-updater-digest-to-the-signed-manifest
  (async done
    (let [manifest #js {:schema-version 1
                        :readable-activation-formats #js ["selfhost-activation-v1"]
                        :readable-client-ops-formats #js ["official-client-ops-sqlite-v2+selfhost-upload-v1"]
                        :activation-write-format "selfhost-activation-v1"
                        :client-ops-write-format "official-client-ops-sqlite-v2+selfhost-upload-v1"}
          archive-sha512-hex (.repeat "a" 128)
          archive-sha512-base64 (.toString (js/Buffer.from archive-sha512-hex "hex") "base64")]
      (-> (with-zip!
           [["Logseq.app/Contents/Resources/updater/TARGET_BUILD_MANIFEST.json"
             (js/JSON.stringify manifest)]]
           (fn [archive]
             (p/let [^js extracted (updater-target/read-target-manifest-from-zip! archive)
                     signed #js {:archive-sha512 archive-sha512-hex
                                 :archive-size (.-size (fs/statSync archive))
                                 :target-build-manifest-sha256 (.-sha256 extracted)
                                 :target-source-full-sha (.repeat "b" 40)
                                 :target-version "2.0.1-selfhost.7"
                                 :release-line-id "selfhost-official-architecture-v1"
                                 :platform "darwin"
                                 :arch "arm64"
                                 :bundle-identity "com.logseq.logseq"
                                 :key-id "ed25519:test"
                                 :readable-activation-formats (aget manifest "readable-activation-formats")
                                 :readable-client-ops-formats (aget manifest "readable-client-ops-formats")
                                 :activation-write-format (aget manifest "activation-write-format")
                                 :client-ops-write-format (aget manifest "client-ops-write-format")}
                     result (updater-target/preflight-downloaded-target-with-modules!
                             {:downloaded-file archive
                              :signed-metadata signed
                              :verified-archive-sha512 archive-sha512-base64
                              :archive-digest-verified true}
                             #js {:verifySignedUpdateMetadata (fn [_] signed)}
                             #js {:validateTargetBuildManifest (fn [_] manifest)})]
               (is (true? result))
               (is (= archive-sha512-base64
                      (.toString (js/Buffer.from (aget signed "archive-sha512") "hex") "base64"))))))
          (p/catch (fn [error]
                     (is false (str "unexpected error: " error))))
          (p/finally done)))))
