(ns electron.updater-install
  (:require [promesa.core :as p]))

(defn- require-dependency!
  [deps key]
  (let [dependency (get deps key)]
    (when-not (fn? dependency)
      (throw (ex-info "missing updater install dependency" {:code :missing-updater-install-dependency
                                                             :dependency key})))
    dependency))

(defn run-install!
  "Runs the one forward-update handoff. Preflight completes before the active
  runtime gate closes. A failure after capture resumes only the owners in the
  memory token; if that cannot be proved, the caller restarts the current App."
  [deps]
  (let [preflight! (require-dependency! deps :preflight!)
        begin-quiesce! (require-dependency! deps :begin-quiesce!)
        stop-active! (require-dependency! deps :stop-active!)
        handoff! (require-dependency! deps :handoff!)
        commit-quiesce! (require-dependency! deps :commit-quiesce!)
        resume-quiesce! (require-dependency! deps :resume-quiesce!)
        set-dirty! (require-dependency! deps :set-dirty!)
        restart! (require-dependency! deps :restart!)
        emit-error! (require-dependency! deps :emit-error!)
        token* (atom nil)]
    (-> (p/resolved nil)
        (p/then (fn [_]
                  (preflight!)))
        (p/then (fn [verified?]
                  (when-not (true? verified?)
                    (throw (ex-info "target update preflight did not verify"
                                    {:code :updater-preflight-failed})))
                  (begin-quiesce!)))
        (p/then (fn [token]
                  (reset! token* token)
                  (stop-active! token)))
        (p/then (fn [_]
                  (handoff!)))
        (p/then (fn [confirmed?]
                  (when-not (true? confirmed?)
                    (throw (ex-info "platform updater handoff was not confirmed"
                                    {:code :updater-handoff-unconfirmed})))
                  (set-dirty! false)
                  (commit-quiesce! @token*)
                  true))
        (p/catch
         (fn [error]
           (if-let [token @token*]
             (-> (p/resolved nil)
                 (p/then (fn [_]
                           (resume-quiesce! token)))
                 (p/then (fn [_]
                           (set-dirty! true)))
                 (p/catch (fn [_resume-error]
                            (restart!)))
                 (p/then (fn [_]
                           (emit-error! error)
                           false)))
             (do
               (emit-error! error)
               (p/resolved false))))))))
