(ns electron.lifecycle
  (:require [promesa.core :as p]))

(defn enqueue!
  "Serialize lifecycle operations and continue after an earlier operation fails."
  [lifecycle-op operation]
  (let [next-op (-> @lifecycle-op
                    (p/catch (fn [_] nil))
                    (p/then (fn [_] (operation))))]
    (vreset! lifecycle-op next-op)
    next-op))

(defn quit-handler
  "Let normal quit finish owned work before allowing Electron's final quit.
  A verified updater handoff has already stopped that work."
  [{:keys [quitting? update-prepared? stop! quit! on-error!]}]
  (let [ready? (volatile! false)]
    (fn [^js event]
      (if (or @ready? (update-prepared?))
        (reset! quitting? true)
        (do
          (.preventDefault event)
          (when-not @quitting?
            (reset! quitting? true)
            (-> (p/resolved nil)
                (p/then (fn [_] (stop!)))
                (p/then (fn [_]
                          (vreset! ready? true)
                          (quit!)))
                (p/catch (fn [error]
                           (reset! quitting? false)
                           (on-error! error))))))))))
