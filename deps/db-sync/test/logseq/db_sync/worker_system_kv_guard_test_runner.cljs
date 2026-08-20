(ns logseq.db-sync.worker-system-kv-guard-test-runner
  (:require
   [cljs.test :as test]
   [logseq.db-sync.worker-batch-integrity-guard-test]
   [logseq.db-sync.worker-semantic-integrity-guard-test]
   [logseq.db-sync.worker-tx-log-integrity-guard-test]
   [logseq.db-sync.worker-system-kv-guard-test]))

(derive ::node ::test/default)

(defmethod test/report [::node :end-run-tests]
  [summary]
  (js/process.exit (if (test/successful? summary) 0 1)))

(defn main
  []
  (test/run-tests (test/empty-env ::node)
                  'logseq.db-sync.worker-batch-integrity-guard-test
                  'logseq.db-sync.worker-semantic-integrity-guard-test
                  'logseq.db-sync.worker-tx-log-integrity-guard-test
                  'logseq.db-sync.worker-system-kv-guard-test))
