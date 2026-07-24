(ns logseq.e2e.assets
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [wally.main :as w]))

(defn assets-dir
  [graph-name]
  (str "/" graph-name "/assets"))

(defn list-assets
  "List file names in the DB-graph assets directory.

  clj-e2e runs the web build, so DB-graph files live in lightning-fs
  (`window.pfs`) rather than the desktop graph directory."
  [graph-name]
  (let [dir (assets-dir graph-name)]
    (vec
     (or
      (w/eval-js
       (str "(() => window.pfs.readdir(" (pr-str dir) ").catch(() => []))()"))
      []))))

(defn wait-for-asset!
  [graph-name filename timeout-ms]
  (let [deadline-ms (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (some #(= filename %) (list-assets graph-name))
        true

        (> (System/currentTimeMillis) deadline-ms)
        false

        :else
        (do
          (Thread/sleep 500)
          (recur))))))

(defn wait-for-new-asset!
  [graph-name before-filenames timeout-ms]
  (let [before (set before-filenames)
        deadline-ms (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [after (set (list-assets graph-name))
            new-files (set/difference after before)
            candidate (first (sort (filter #(string/includes? % ".") new-files)))]
        (cond
          (string? candidate)
          candidate

          (> (System/currentTimeMillis) deadline-ms)
          nil

          :else
          (do
            (Thread/sleep 250)
            (recur)))))))

(defn clear-assets-dir!
  "Recursively remove the graph asset directory in lightning-fs."
  [graph-name]
  (let [dir (assets-dir graph-name)]
    (boolean
     (w/eval-js
      (str
       "(() => {"
       "  const dir = " (pr-str dir) ";"
       "  const rimraf = window.workerThread && window.workerThread.rimraf;"
       "  if (!rimraf) return Promise.resolve(false);"
       "  return rimraf(dir).then(() => true).catch(() => false);"
       "})()")))))

(defn file-sha256
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest digest (java.nio.file.Files/readAllBytes path))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn asset-sha256
  "Return the SHA-256 of one LightningFS asset, or nil when it is absent."
  [graph-name filename]
  (let [path (str (assets-dir graph-name) "/" filename)]
    (w/eval-js
     (str
      "(async () => {"
      "  try {"
      "    const bytes = await window.pfs.readFile(" (pr-str path) ");"
      "    const start = bytes.byteOffset || 0;"
      "    const end = start + bytes.byteLength;"
      "    const digest = await crypto.subtle.digest('SHA-256', bytes.buffer.slice(start, end));"
      "    return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');"
      "  } catch (_) { return null; }"
      "})()"))))
