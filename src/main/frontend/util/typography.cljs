(ns frontend.util.typography
  "Local typography overrides and the known legacy conversion."
  (:require [clojure.string :as string]))

(def presets {:serif "Lyon-Text, Georgia, ui-serif, serif"
              :mono "iawriter-mono, Nitti, Menlo, Courier, monospace"})

(defn valid-value? [field value]
  (or (nil? value)
      (case field
        :family (or (contains? #{:default :serif :mono} value)
                    (and (string? value) (not (string/blank? value))))
        :size (and (number? value) (<= 8 value 48))
        false)))

(defn normalize [value]
  (cond
    (nil? value) {}
    (and (map? value) (every? #{:type :global} (keys value)))
    (let [family (get {nil :default "default" :default "serif" :serif "mono" :mono} (:type value))]
      (when-not (and family (or (nil? (:global value)) (boolean? (:global value))))
        (throw (ex-info "Invalid legacy typography" {})))
      (cond-> {:editor {:family family}} (:global value) (assoc :interface {:family family})))
    (and (map? value) (every? #{:editor :interface :code} (keys value))
         (every? (fn [[region group]]
                   (and (map? group) (every? (if (= region :code) #{:family} #{:family :size}) (keys group))
                        (every? (fn [[field setting]] (valid-value? field setting)) group))) value)) value
    :else (throw (ex-info "Invalid typography settings" {}))))

(defn set-value [settings region field value]
  (when-not (and (#{:editor :interface :code} region)
                ((if (= region :code) #{:family} #{:family :size}) field)
                (valid-value? field value))
    (throw (ex-info "Invalid typography value" {})))
  (let [group (if (nil? value) (dissoc (get settings region {}) field)
                  (assoc (get settings region {}) field value))]
    (if (seq group) (assoc settings region group) (dissoc settings region))))

(defn family-css
  ([family] (family-css family "var(--ls-font-family), sans-serif"))
  ([family fallback]
   (cond
     (or (nil? family) (= :default family)) nil
     (keyword? family) (get presets family)
     :else (str "\"" (-> family (string/replace "\\" "\\\\") (string/replace "\"" "\\\"")
                             (string/replace #"[\n\r\f]" " ")) "\", " fallback))))

(defn css-properties [settings]
  {"--ls-note-font-family" (family-css (get-in settings [:editor :family]))
   "--ls-note-font-size" (some-> (get-in settings [:editor :size]) (str "px"))
   "--ls-interface-font-family" (family-css (get-in settings [:interface :family]))
   "--ls-interface-font-size" (some-> (get-in settings [:interface :size]) (str "px"))
   "--ls-code-font-family" (family-css (get-in settings [:code :family]) "monospace")})

(defn apply! [document settings]
  (let [root (.-documentElement document)
        style (.-style root)]
    (.removeAttribute root "data-font")
    (.removeAttribute root "data-font-global")
    (doseq [[property value] (css-properties settings)]
      (if value (.setProperty style property value) (.removeProperty style property)))))
