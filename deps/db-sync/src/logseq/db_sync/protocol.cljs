(ns logseq.db-sync.protocol
  (:require [clojure.walk :as walk]
            [goog.crypt :as gcrypt]
            [goog.crypt.Sha256]
            [logseq.db-sync.common :as common]))

(defn tx-payload-digest
  [outliner-op chunk-final? tx-data]
  (let [digest (goog.crypt.Sha256.)
        canonical (common/write-transit
                   [:client-tx-payload-v1
                    outliner-op
                    (boolean chunk-final?)
                    tx-data])]
    (.update digest canonical)
    (gcrypt/byteArrayToHex (.digest digest))))

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
