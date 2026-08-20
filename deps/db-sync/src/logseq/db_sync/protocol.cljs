(ns logseq.db-sync.protocol
  (:require [clojure.walk :as walk]
            [goog.crypt :as gcrypt]
            [goog.crypt.Sha256]
            [logseq.db-sync.common :as common]))

(defn- sha256-hex
  [value]
  (let [digest (goog.crypt.Sha256.)]
    (.update digest value)
    (gcrypt/byteArrayToHex (.digest digest))))

(defn tx-payload-digest
  "Server-computed binding for an ordinary tx envelope. The digest is based on
  the decoded payload and never trusts a client-declared checksum."
  [outliner-op tx-data]
  (sha256-hex
   (common/write-transit
    [:client-tx-payload-v1 outliner-op false tx-data])))

(defn tx-upload-completed-digest
  [outliner-op ordered-wire-digests]
  (sha256-hex
   (common/write-transit
    [:client-tx-upload-completed-v1 outliner-op (vec ordered-wire-digests)])))

(defn tx-upload-session-id
  [logical-tx-id outliner-op tx-data]
  (sha256-hex
   (common/write-transit
    [:client-tx-upload-session-v1 logical-tx-id outliner-op tx-data])))

(declare transit->tx)

(defn tx-wire-payload-digest
  [{:keys [tx-id logical-tx-id upload-session-id chunk-index chunk-final?
           outliner-op tx]}]
  (sha256-hex
   (common/write-transit
    [:client-tx-wire-v1 tx-id logical-tx-id upload-session-id chunk-index
     (boolean chunk-final?) outliner-op (transit->tx tx)])))

(defn tx-chunk-id
  [logical-tx-id upload-session-id chunk-index chunk-final?]
  (let [hex (sha256-hex (str "logseq-tx-chunk-v2/"
                             logical-tx-id "/" upload-session-id "/"
                             chunk-index "/"
                             (if chunk-final? "final" "more")))
        uuid-hex (str (subs hex 0 12) "5" (subs hex 13 16)
                      "8" (subs hex 17 32))]
    (uuid (str (subs uuid-hex 0 8) "-"
               (subs uuid-hex 8 12) "-"
               (subs uuid-hex 12 16) "-"
               (subs uuid-hex 16 20) "-"
               (subs uuid-hex 20 32)))))

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
