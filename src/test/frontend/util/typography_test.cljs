(ns frontend.util.typography-test
  (:require [cljs.test :refer [deftest is]]
            [frontend.util.typography :as typography]))

(deftest known-legacy-fonts-and-independent-reset-test
  (is (= {} (typography/normalize nil)))
  (is (= {:editor {:family :default}} (typography/normalize {})))
  (is (= {:editor {:family :mono}} (typography/normalize {:type "mono"})))
  (is (= {:editor {:family :serif} :interface {:family :serif}}
         (typography/normalize {:type "serif" :global true})))
  (let [first-click (typography/set-value {} :editor :family "Installed Font")
        both (-> first-click (typography/set-value :editor :size 20)
                 (typography/set-value :interface :family :mono)
                 (typography/set-value :interface :size 14))
        reset-family (typography/set-value both :editor :family nil)]
    (is (= "Installed Font" (get-in first-click [:editor :family])))
    (is (= {:editor {:size 20} :interface {:family :mono :size 14}} reset-family))
    (is (= reset-family (typography/normalize reset-family)))
    (is (= {:interface {:family :mono :size 14}}
           (typography/set-value reset-family :editor :size nil)))
    (is (= "20px" (get (typography/css-properties both) "--ls-note-font-size")))
    (is (= "14px" (get (typography/css-properties both) "--ls-interface-font-size")))
    (is (nil? (get (typography/css-properties reset-family) "--ls-note-font-family"))))
  (is (= "\"Family\\\"; color: red; /*\", var(--ls-font-family), sans-serif"
         (typography/family-css "Family\"; color: red; /*")))
  (let [values (atom {})
        removed (atom [])
        document #js {:documentElement #js {:style #js {:setProperty #(swap! values assoc %1 %2)
                                                        :removeProperty #(swap! values dissoc %)}
                                            :removeAttribute #(swap! removed conj %)}}]
    (typography/apply! document {:editor {:family :serif :size 20} :interface {:family :mono :size 14}})
    (typography/apply! document {:interface {:size 14}})
    (is (= {"--ls-interface-font-size" "14px"} @values))
    (is (some #{"data-font-global"} @removed))))

(deftest optional-code-family-keeps-existing-settings-and-monospace-fallback
  (let [base {:editor {:family :serif :size 18} :interface {:family :mono}}
        configured (typography/set-value base :code :family "Menlo")]
    (is (= configured (typography/normalize configured)))
    (is (= base (typography/set-value configured :code :family nil)))
    (is (= "\"Menlo\", monospace" (get (typography/css-properties configured) "--ls-code-font-family")))
    (is (nil? (get (typography/css-properties base) "--ls-code-font-family")))
    (is (= "\"A\\\"B\\\\C D\", monospace" (typography/family-css "A\"B\\C\nD" "monospace")))
    (is (thrown? js/Error (typography/normalize {:code {:size 18}})))
    (is (thrown? js/Error (typography/set-value base :code :size 18)))
    (let [values (atom {})
          document #js {:documentElement #js {:style #js {:setProperty #(swap! values assoc %1 %2)
                                                          :removeProperty #(swap! values dissoc %)}
                                              :removeAttribute (fn [_])}}]
      (typography/apply! document configured)
      (is (= "\"Menlo\", monospace" (get @values "--ls-code-font-family")))
      (typography/apply! document base)
      (is (not (contains? @values "--ls-code-font-family"))))))
