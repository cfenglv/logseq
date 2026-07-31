(ns logseq.db-sync.protocol
  (:require [clojure.walk :as walk]
            [goog.crypt :as gcrypt]
            [goog.crypt.Sha256]
            [logseq.db-sync.common :as common]))

(defn- sha256-hex
  [value]
  (let [digest (goog.crypt.Sha256.)
        _ (.update digest value)]
    (gcrypt/byteArrayToHex (.digest digest))))

(defn tx-payload-digest
  [outliner-op chunk-final? tx-data]
  (sha256-hex
   (common/write-transit
    [:client-tx-payload-v1
     outliner-op
     (boolean chunk-final?)
     tx-data])))

(defn tx-upload-session-id
  "Stable content-addressed upload generation. A rebase that changes the
  normalized logical transaction necessarily starts a different session."
  [logical-tx-id outliner-op tx-data]
  (sha256-hex
   (common/write-transit
    [:client-tx-upload-session-v1 logical-tx-id outliner-op tx-data])))

(declare transit->tx)

(defn tx-wire-payload-digest
  "Server-verifiable binding for the actual chunk envelope. Never substitute a
  client-declared digest for this value."
  [{:keys [tx-id logical-tx-id upload-session-id chunk-index chunk-final?
           outliner-op tx]}]
  (sha256-hex
   (common/write-transit
    [:client-tx-wire-v1 tx-id logical-tx-id upload-session-id chunk-index
     (boolean chunk-final?) outliner-op (transit->tx tx)])))

(defn tx-chunk-id
  "Derive a stable UUID for a nonfinal chunk without consuming the logical tx
  id that the client uses to mark the complete operation finished."
  [logical-tx-id upload-session-id chunk-index chunk-final?]
  (if chunk-final?
    logical-tx-id
    (let [hex (sha256-hex (str "logseq-tx-chunk-v1/"
                               logical-tx-id "/" upload-session-id "/"
                               chunk-index))
          ;; UUIDv5-compatible version/variant bits over our SHA-256 prefix.
          uuid-hex (str (subs hex 0 12) "5" (subs hex 13 16)
                        "8" (subs hex 17 32))]
      (uuid (str (subs uuid-hex 0 8) "-"
                 (subs uuid-hex 8 12) "-"
                 (subs uuid-hex 12 16) "-"
                 (subs uuid-hex 16 20) "-"
                 (subs uuid-hex 20 32))))))

(defn- stringify-uuid
  [value]
  (if (uuid? value)
    (str value)
    value))

(defn parse-message [raw]
  (try
    (let [data (js->clj (js/JSON.parse raw) :keywordize-keys true)]
      (when (map? data)
        data))
    (catch :default _
      nil)))

(defn encode-message [m]
  (js/JSON.stringify
   (clj->js (walk/postwalk stringify-uuid m))))

(defn transit->tx [value]
  (common/read-transit value))

(defn tx->transit [tx-data]
  (common/write-transit tx-data))
