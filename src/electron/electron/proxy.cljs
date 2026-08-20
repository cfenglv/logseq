(ns electron.proxy
  (:require [clojure.string :as string]))

(defn- url-hostname
  [^js url]
  (let [host (.-hostname url)]
    (if (and (string/starts-with? host "[")
             (string/ends-with? host "]"))
      (subs host 1 (dec (count host)))
      host)))

(defn parse-pac-rule
  "Parse one Proxy Auto-Config result clause."
  [line]
  (try
    (let [[type address] (string/split (string/trim line) #"\s+" 2)]
      (cond
        (= type "DIRECT")
        {:protocol "direct"}

        (and (contains? #{"PROXY" "HTTP" "HTTPS" "SOCKS" "SOCKS4" "SOCKS5"} type)
             (not (string/blank? address)))
        (let [url (js/URL. (str "http://" address))]
          {:protocol (cond
                       (= type "HTTPS") "https"
                       (= type "SOCKS4") "socks4"
                       (contains? #{"SOCKS" "SOCKS5"} type) "socks5"
                       :else "http")
           :host (url-hostname url)
           :port (.-port url)})

        :else
        nil))
    (catch :default _
      nil)))

(defn select-pac-route
  "Select the first route from Electron's ordered PAC result.

  PAC fallback chains such as `PROXY host:port; DIRECT` are ordered by
  preference. db-worker cannot retry the whole chain, but it can use the same
  first route Electron would attempt."
  [result]
  (let [clauses (->> (string/split result #";")
                     (map string/trim)
                     (remove string/blank?)
                     vec)
        routes (mapv parse-pac-rule clauses)]
    (if (or (empty? routes) (some nil? routes))
      (throw (ex-info "system proxy returned no supported route"
                      {:code :unsupported-system-proxy
                       :result result}))
      (first routes))))
