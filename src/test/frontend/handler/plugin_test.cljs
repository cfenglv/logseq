(ns frontend.handler.plugin-test
  (:require [cljs.test :refer [async deftest is testing]]
            [frontend.common.idb :as idb]
            [frontend.fs :as fs]
            [frontend.handler.plugin :as plugin]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

(defn- load-settings
  []
  ((plugin/make-fn-to-load-dotdir-json "settings" #js {}) :sample-plugin))

(deftest electron-plugin-settings-empty-files-use-default-test
  (async done
         (let [read-values (atom ["" " \n\t "])]
           (reset! plugin/*ls-dotdir-root "/tmp/logseq-test")
           (-> (p/with-redefs [util/electron? (constantly true)
                               state/get-current-repo (constantly "repo")
                               fs/create-if-not-exists (fn [& _] (p/resolved nil))
                               fs/read-file (fn [& _]
                                              (p/resolved
                                               (let [value (first @read-values)]
                                                 (swap! read-values subvec 1)
                                                 value)))]
                 (p/let [[empty-path empty-value] (load-settings)
                         [whitespace-path whitespace-value] (load-settings)]
                   (testing "empty and whitespace-only files both return the supplied default"
                     (is (.endsWith empty-path "/settings/sample-plugin.json"))
                     (is (.endsWith whitespace-path "/settings/sample-plugin.json"))
                     (is (= {} (js->clj empty-value)))
                     (is (= {} (js->clj whitespace-value))))))
               (p/then (fn [_] (done)))
               (p/catch (fn [error]
                          (is false (str "empty settings should not throw: " error))
                          (done)))))))

(deftest electron-plugin-settings-valid-json-is-preserved-test
  (async done
         (reset! plugin/*ls-dotdir-root "/tmp/logseq-test")
         (-> (p/with-redefs [util/electron? (constantly true)
                             state/get-current-repo (constantly "repo")
                             fs/create-if-not-exists (fn [& _] (p/resolved nil))
                             fs/read-file (fn [& _]
                                            (p/resolved
                                             "{\"enabled\":true,\"count\":3}"))]
               (p/let [[path value] (load-settings)]
                 (is (.endsWith path "/settings/sample-plugin.json"))
                 (is (= {"enabled" true "count" 3}
                        (js->clj value)))))
             (p/then (fn [_] (done)))
             (p/catch (fn [error]
                        (is false (str error))
                        (done))))))

(deftest browser-plugin-settings-continues-to-use-idb-and-default-test
  (async done
         (let [reads (atom [])
               stored #js {:theme "dark"}]
           (reset! plugin/*ls-dotdir-root "LSPUserDotRoot/")
           (-> (p/with-redefs [util/electron? (constantly false)
                               idb/get-item (fn [path]
                                              (swap! reads conj path)
                                              (p/resolved
                                               (if (= 1 (count @reads))
                                                 stored
                                                 nil)))]
                 (p/let [[stored-path stored-value] (load-settings)
                         [default-path default-value] (load-settings)]
                   (is (= stored-path default-path))
                   (is (.endsWith stored-path "/settings/sample-plugin.json"))
                   (is (= {"theme" "dark"} (js->clj stored-value)))
                   (is (= {} (js->clj default-value)))
                   (is (= [stored-path stored-path] @reads))))
               (p/then (fn [_] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))
