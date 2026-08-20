(ns electron.configs
  (:require ["electron" :refer [^js app] :as electron]
            ["fs-extra" :as ^js fs]
            ["path" :as ^js node-path]
            [cljs.reader :as reader]
            [electron.home :as home]
            [electron.logger :as logger]
            [logseq.common.graph-registry :as graph-registry]))

;; FIXME: move configs.edn to where it should be
;; Qualification launches may isolate every desktop home-owned path explicitly.
(defonce home-root
  (home/resolve-root (.-LOGSEQ_TEST_HOME_DIR js/process.env)
                     (.getPath app "home")))
(defonce dot-root (.join node-path home-root ".logseq"))
(defonce cfg-root
  (let [qualification-user-data (.-LOGSEQ_TEST_USER_DATA_DIR js/process.env)]
    (when (seq qualification-user-data)
      (.setPath app "userData" qualification-user-data))
    (.getPath app "userData")))
(defonce cfg-path (.join node-path cfg-root "configs.edn"))

(defn graph-registry-path
  []
  (.join node-path dot-root "graphs.edn"))

(defn- ensure-cfg
  []
  (try
    (.ensureFileSync fs cfg-path)
    (let [body (.toString (.readFileSync fs cfg-path))]
      (if (seq body) (reader/read-string body) {}))
    (catch :default e
      (logger/error :cfg-error e))))

(defn- write-cfg!
  [cfg]
  (try
    (.writeFileSync fs cfg-path (pr-str cfg)) cfg
    (catch :default e
      (logger/error :cfg-error e))))

(defn set-item!
  [k v]
  (when-let [cfg (ensure-cfg)]
    (some->> (assoc cfg k v)
             (write-cfg!))))

(defn get-item
  [k]
  (when-let [cfg (and k (ensure-cfg))]
    (get cfg k)))

(defn get-config
  []
  (ensure-cfg))

(defn semantic-search-enabled?
  []
  (true? (get-item :feature/enable-semantic-search?)))

(defn- read-edn-file
  [path]
  (try
    (.ensureFileSync fs path)
    (let [body (.toString (.readFileSync fs path))]
      (if (seq body) (reader/read-string body) []))
    (catch :default e
      (logger/error :graph-registry-read-error e)
      [])))

(defn read-graph-registry
  []
  (read-edn-file (graph-registry-path)))

(defn write-graph-registry!
  [registry]
  (try
    (.ensureDirSync fs dot-root)
    (.writeFileSync fs (graph-registry-path) (pr-str (vec registry)))
    (vec registry)
    (catch :default e
      (logger/error :graph-registry-write-error e)
      nil)))

(defn upsert-graph-registry-entry!
  [entry]
  (let [registry (read-graph-registry)
        registry' (graph-registry/upsert-entry registry entry)]
    (write-graph-registry! registry')))
