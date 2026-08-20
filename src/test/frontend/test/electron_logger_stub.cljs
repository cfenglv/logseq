(ns frontend.test.electron-logger-stub)

(defonce *calls (atom []))

(defn reset-calls!
  []
  (reset! *calls []))

(defn calls
  []
  @*calls)

(defn- record!
  [level args]
  (swap! *calls conj [level args]))

(defn debug
  [& args]
  (record! :debug args))

(defn info
  [& args]
  (record! :info args))

(defn warn
  [& args]
  (record! :warn args))

(defn error
  [& args]
  (record! :error args))
