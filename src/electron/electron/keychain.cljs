(ns electron.keychain
  "Helper functions for storing E2EE secrets inside the OS keychain."
  (:require ["keytar" :as keytar]
            [electron.logger :as logger]
            [promesa.core :as p]))

(def ^:private keychain-service "Logseq E2EE")

(defn supported?
  []
  (boolean keytar))

(defn <set-password!
  "Persist `encrypted-text` for the `refresh-token` entry."
  [key encrypted-text]
  (if-let [account (and (supported?) key)]
    (-> (p/let [_ (.setPassword keytar keychain-service account encrypted-text)]
          true)
        (p/catch (fn [e]
                   (logger/error ::set-password {:error e})
                   (throw e))))
    (p/resolved false)))

(defn <get-password
  "Fetch encrypted text stored for `refresh-token`."
  [key]
  (if-let [account (and (supported?) key)]
    (-> (p/let [password (.getPassword keytar keychain-service account)]
          password)
        (p/catch (fn [e]
                   (logger/error ::get-password {:error e})
                   (throw e))))
    (p/resolved nil)))

(defn <delete-password!
  [key]
  (if-let [account (and (supported?) key)]
    (-> (p/let [_ (.deletePassword keytar keychain-service account)]
          true)
        (p/catch (fn [e]
                   (logger/error ::delete-password {:error e})
                   (throw e))))
    (p/resolved false)))
