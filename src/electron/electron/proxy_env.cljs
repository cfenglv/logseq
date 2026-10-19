(ns electron.proxy-env
  "Standard proxy env seeding for the Electron-owned db-worker child.")

(defonce ^:private *worker-proxy-env (atom nil))

(defn set-worker-proxy-env!
  "Seed the standard proxy env vars the Electron-owned db-worker child inherits
  from the main process. A resolved system proxy (or a fixed proxy configured
  in Settings) becomes https_proxy/http_proxy/all_proxy so the worker's
  existing proxy-from-env + HttpsProxyAgent connect path can reach the sync
  service even when the App was launched without shell proxy env (for example
  from Finder). Only absent vars or vars previously set by this function are
  touched; an explicitly configured environment always wins."
  [proxy]
  (let [proxy-url (when (and proxy
                             (seq (:protocol proxy))
                             (seq (:host proxy))
                             (some? (:port proxy)))
                    (str (:protocol proxy) "://" (:host proxy) ":" (:port proxy)))
        prev @*worker-proxy-env]
    (doseq [[lower-case upper-case] [["https_proxy" "HTTPS_PROXY"]
                                     ["http_proxy" "HTTP_PROXY"]
                                     ["all_proxy" "ALL_PROXY"]]]
      (let [current (aget js/process.env lower-case)
            upper-case-value (aget js/process.env upper-case)
            owned? (= prev current)]
        (cond
          (some? upper-case-value)
          (when owned?
            (js-delete js/process.env lower-case))

          (or (nil? current) owned?)
          (if proxy-url
            (aset js/process.env lower-case proxy-url)
            (js-delete js/process.env lower-case)))))
    (reset! *worker-proxy-env proxy-url)
    (when (and proxy-url
               (nil? (or (aget js/process.env "NO_PROXY")
                         (aget js/process.env "no_proxy"))))
      (aset js/process.env "NO_PROXY" "127.0.0.1,localhost,<local>")))
  nil)
