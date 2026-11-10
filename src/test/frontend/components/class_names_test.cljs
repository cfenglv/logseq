(ns frontend.components.class-names-test
  (:require [cljs.test :refer [deftest is]]
            [logseq.shui.components :as components]))

(deftest caller-whitespace-overrides-component-default-test
  (is (= "whitespace-normal" (components/cn "whitespace-nowrap" "whitespace-normal")))
  (is (= "whitespace-nowrap" (components/cn "whitespace-normal" "whitespace-nowrap")))
  (is (= "whitespace-nowrap hover:whitespace-pre-wrap"
         (components/cn "whitespace-nowrap hover:whitespace-nowrap" "hover:whitespace-pre-wrap")))
  (is (= "!whitespace-normal"
         (components/cn "!whitespace-normal" "whitespace-nowrap"))))
