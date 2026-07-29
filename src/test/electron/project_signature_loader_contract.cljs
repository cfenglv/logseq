(ns electron.project-signature-loader-contract
  (:require [electron.updater :as updater]
            [goog.object :as gobj]))

(def ^:private result-prefix "PROJECT_SIGNATURE_LOADER_CONTRACT ")

(defn- finish!
  [payload]
  (println (str result-prefix (js/JSON.stringify (clj->js payload))))
  (js/process.exit 0))

(defn- fail!
  [message]
  (.error js/console (str result-prefix message))
  (js/process.exit 1))

(defn- load-project-signature-module!
  []
  (#'updater/<project-signature-module!))

(defn- run-success!
  []
  (let [first-load (load-project-signature-module!)
        second-load (load-project-signature-module!)]
    (when-not (identical? first-load second-load)
      (fail! "production loader did not cache its module Promise"))
    (-> first-load
        (.then
         (fn [signature-module]
           (let [algorithm
                 (gobj/get signature-module "projectUpdateAlgorithm")
                 parse-version
                 (gobj/get signature-module "parseSelfhostProjectVersion")]
             (if (and (= "ed25519-sha512-manifest-v1" algorithm)
                      (fn? parse-version)
                      (some? (parse-version "2.0.1-selfhost.5")))
               (finish! {:scenario "success"
                         :algorithm algorithm
                         :same-promise true})
               (fail! "production loader did not return the real signature module")))))
        (.catch
         (fn [error]
           (fail! (str "real ESM load rejected: " error)))))))

(defn- run-failure!
  []
  (let [first-load (load-project-signature-module!)
        second-load (load-project-signature-module!)]
    (when-not (identical? first-load second-load)
      (fail! "failed production load was not cached as one Promise"))
    (.then
     first-load
     (fn [_]
       (fail! "missing production signature module was silently bypassed"))
     (fn [error]
       (let [code (gobj/get error "code")
             message (str (or (gobj/get error "message") error))]
         (if (or (= "ERR_MODULE_NOT_FOUND" code)
                 (.includes message "Cannot find module")
                 (.includes message "module not found"))
           (finish! {:scenario "failure"
                     :code code
                     :same-promise true
                     :rejected true})
           (fail! (str "unexpected production loader rejection: " error))))))))

(defn main
  [& _]
  (if (some #(= "--experimental-vm-modules" %)
            (array-seq (.-execArgv js/process)))
    (fail! "contract must run without --experimental-vm-modules")
    (case (gobj/get (.-env js/process)
                    "LOGSEQ_PROJECT_SIGNATURE_CONTRACT_SCENARIO")
      "success" (run-success!)
      "failure" (run-failure!)
      (fail! "unknown contract scenario"))))
