(ns electron.keychain
  "Helper functions for storing E2EE secrets inside the OS keychain."
  (:require ["electron" :refer [app]]
            [clojure.string :as string]
            [electron.logger :as logger]
            [goog.object :as gobj]
            [promesa.core :as p]))

(defn- qualification-home?
  []
  (boolean (seq (gobj/get (.-env js/process) "LOGSEQ_TEST_HOME_DIR"))))

(def ^:private keytar
  (when-not (qualification-home?)
    (js/require "keytar")))

(defonce ^:private service-name
  (delay
    (let [app-name (try (.getName app)
                        (catch :default _ nil))]
      (if (string/blank? app-name)
        "Logseq"
        app-name))))

(defn- keychain-service
  []
  (str (force service-name) " E2EE"))

(defn supported?
  []
  (and (boolean keytar)
       (not (qualification-home?))))

(defn <set-password!
  "Persist `encrypted-text` for the `refresh-token` entry."
  [key encrypted-text]
  (if-let [account (and (supported?) key)]
    (-> (p/let [_ (.setPassword keytar (keychain-service) account encrypted-text)]
          true)
        (p/catch (fn [e]
                   (logger/error ::set-password {:error e})
                   (throw e))))
    (p/resolved false)))

(defn <get-password
  "Fetch encrypted text stored for `refresh-token`."
  [key]
  (if-let [account (and (supported?) key)]
    (-> (p/let [password (.getPassword keytar (keychain-service) account)]
          password)
        (p/catch (fn [e]
                   (logger/error ::get-password {:error e})
                   (throw e))))
    (p/resolved nil)))

(defn <delete-password!
  [key]
  (if-let [account (and (supported?) key)]
    (-> (p/let [_ (.deletePassword keytar (keychain-service) account)]
          true)
        (p/catch (fn [e]
                   (logger/error ::delete-password {:error e})
                   (throw e))))
    (p/resolved false)))
