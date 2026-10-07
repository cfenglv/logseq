(ns logseq.common.graph-registry-test
  (:require [cljs.test :refer [deftest is]]
            [logseq.common.graph-registry :as registry]))

(deftest registry-normalizes-graph-name-whitespace
  (let [entries (registry/upsert-entry [] {:graph-id "graph-id"
                                         :repo " logseq_db_space name "
                                         :graph-name " space name "})]
    (is (= "logseq_db_space name" (:repo (first entries))))
    (is (= "space name" (:graph-name (first entries))))
    (doseq [identifier [" space name " " logseq_db_space name " "logseq_db_ space name "]]
      (is (= "graph-id" (:graph-id (registry/resolve-target entries {:graph-identifier identifier})))))))

(deftest directory-renames-preserve-exact-origin-and-copied-identities
  (let [canonical {:repo "logseq_db_Foo" :graph-name "Foo" :graph-id "copied-uuid"}
        old {:repo "logseq_db_Foo " :graph-name "Foo " :graph-id "copied-uuid"
             :local-graph-id "local-uuid" :updated-at 1}
        renames [{:from "logseq_db_Foo " :to "logseq_db_Foo(2)"}]
        result (registry/apply-directory-renames [canonical old] renames)]
    (is (= [canonical (assoc old :repo "logseq_db_Foo(2)" :graph-name "Foo(2)")] result))
    (is (= result (registry/apply-directory-renames result renames)))
    (is (nil? (registry/directory-renamed-repo renames "logseq_db_Foo")))
    (is (= "logseq_db_Foo(2)" (registry/directory-renamed-repo renames "logseq_db_Foo ")))
    (doseq [identifier ["Foo " "logseq_db_Foo "]]
      (is (= "logseq_db_Foo(2)" (registry/directory-renamed-identifier renames identifier))))
    (doseq [identifier [nil "Foo" "logseq_db_Foo" "Foo(2)"]]
      (is (nil? (registry/directory-renamed-identifier renames identifier))))
    (is (= [canonical] (registry/apply-directory-renames [canonical] renames)))))
