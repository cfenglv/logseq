(ns frontend.worker.sync.missing-system-kv-runner
  (:require
   [cljs.test :as test]
   [frontend.worker.sync.missing-system-kv-test]))

(derive ::node ::test/default)

(defmethod test/report [::node :end-run-tests]
  [summary]
  (js/process.exit (if (test/successful? summary) 0 1)))

(defn main
  []
  (test/run-tests (test/empty-env ::node)
                  'frontend.worker.sync.missing-system-kv-test))
