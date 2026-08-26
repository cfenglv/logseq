(ns logseq.e2e.settings-basic-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.locator :as loc]
   [logseq.e2e.settings :as settings]
   [logseq.e2e.util :as util]
   [wally.main :as w]))

(use-fixtures :once fixtures/open-page)

(use-fixtures :each
  fixtures/new-logseq-page
  fixtures/validate-graph)

(defn- open-left-sidebar!
  []
  (when-not (w/visible? "#left-sidebar.is-open")
    (w/click "#left-menu")
    (w/wait-for "#left-sidebar.is-open")))

(defn- open-settings!
  []
  (w/click ".toolbar-dots-btn")
  (w/click "[role='menuitem'] div:text('Settings')")
  (w/wait-for "#settings"))

(deftest runtime-language-switch-refreshes-sidebar-and-settings-test
  (open-left-sidebar!)
  (open-settings!)
  (try
    (is (= "Settings" (util/get-text "#settings .cp__settings-modal-title")))
    (is (every? (set (w/all-text-contents "a.wrap-th strong"))
                ["Favorites" "Recent"]))

    (w/click "#settings [role='combobox']")
    (w/click (loc/filter "[role='option']" :has-text "简体中文"))

    (w/wait-for "#settings .cp__settings-modal-title:text('设置')")
    (w/wait-for "#settings [data-id='general'] strong:text('常规')")
    (w/wait-for "#settings label[for='toggle_theme']:has-text('切换到')")
    (w/wait-for "#settings label[for='toggle_radix_theme']:text('高亮色')")
    (w/wait-for "a.wrap-th strong:text('收藏页面')")
    (w/wait-for "a.wrap-th strong:text('最近使用')")

    (is (= "设置" (util/get-text "#settings .cp__settings-modal-title")))
    (is (= "常规" (util/get-text "#settings [data-id='general'] strong")))
    (is (= "高亮色" (util/get-text "#settings label[for='toggle_radix_theme']")))
    (is (every? (set (w/all-text-contents "a.wrap-th strong"))
                ["收藏页面" "最近使用"]))
    (finally
      (when (w/visible? "#settings")
        (k/esc))
      (settings/refresh-test-env!))))
