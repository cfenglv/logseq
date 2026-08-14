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
