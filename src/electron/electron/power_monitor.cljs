(ns electron.power-monitor
  (:require [electron.logger :as logger]))

(defn notify-renderers-on-resume!
  [windows send-to-renderer]
  (doseq [^js window windows]
    (when-not (.isDestroyed window)
      (send-to-renderer window :power-resume {:reason :system-resume}))))

(defn setup!
  [^js power-monitor get-all-windows send-to-renderer]
  (let [resume-handler (fn []
                         (logger/info :power-resume)
                         (notify-renderers-on-resume!
                          (get-all-windows)
                          send-to-renderer))]
    (.on power-monitor "resume" resume-handler)
    #(.removeListener power-monitor "resume" resume-handler)))
