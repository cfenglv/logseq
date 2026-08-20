(ns electron.home
  (:require [clojure.string :as string]))

(defn resolve-root
  [test-home-root default-home-root]
  (if (and (string? test-home-root) (not (string/blank? test-home-root)))
    test-home-root
    default-home-root))
