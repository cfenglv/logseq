(ns logseq.e2e.settings-basic-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [logseq.e2e.api :refer [ls-api-call!]]
   [logseq.e2e.assert :as assert]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.locator :as loc]
   [logseq.e2e.page :as page]
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
(def ^:private preferred-format "yyyy-MM-dd")

(defn- open-settings-editor!
  []
  (w/click ".toolbar-dots-btn")
  (w/click (loc/filter "[role='menuitem']" :has-text "Settings"))
  (w/wait-for "#settings")
  (w/click ".settings-menu-item[data-id='editor']")
  (w/wait-for ".panel-wrap.is-editor select.form-select"))

(defn- date-format-select
  []
  (w/-query ".panel-wrap.is-editor select.form-select"))

(defn- preferred-date-format
  []
  (get (ls-api-call! :app.getUserConfigs) "preferredDateFormat"))

(deftest preferred-date-format-survives-reload-test
  (testing "Settings > Editor preferred date format is restored on startup"
    (open-settings-editor!)
    (is (not= preferred-format (preferred-date-format))
        "The test graph must start on a different date format than the one we persist.")
    (.selectOption (date-format-select) preferred-format)
    (assert/assert-is-visible
     (w/get-by-text "Please refresh the app for this change to take effect"))
    (assert/assert-is-hidden "#settings")
    (is (= preferred-format (preferred-date-format)))
    (util/refresh-until-graph-loaded)
    (is (= preferred-format (preferred-date-format))
        "Startup restore must load the persisted format into UI state.")
    (open-settings-editor!)
    (is (= preferred-format (.inputValue (date-format-select)))
        "The Settings dropdown must keep the persisted format after reload.")
    (ls-api-call! :editor.createJournalPage "2026-01-15")
    (page/goto-page "2026-01-15")
    (is (= "2026-01-15" (page/get-page-name))
        "A journal created after reload must use the persisted title format.")))
