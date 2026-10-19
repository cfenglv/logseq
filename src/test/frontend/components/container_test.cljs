(ns frontend.components.container-test
  (:require ["react" :as react]
            ["react-dom/server" :as react-dom-server]
            [cljs.test :refer [async deftest is]]
            [frontend.components.container :as container]
            [frontend.components.content :as cp-content]
            [frontend.db.async :as db-async]
            [frontend.state :as state]
            [goog.object :as gobj]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

(defn- render-static
  [element]
  (let [previous-react (gobj/get js/globalThis "React")]
    (gobj/set js/globalThis "React" react)
    (try
      (.renderToStaticMarkup react-dom-server element)
      (finally
        (if (some? previous-react)
          (gobj/set js/globalThis "React" previous-react)
          (gobj/remove js/globalThis "React"))))))

(deftest node-embed-context-menu-uses-the-placeholder-identity-test
  (async done
    (let [target-uuid #uuid "11111111-1111-1111-1111-111111111111"
          placeholder-uuid #uuid "22222222-2222-2222-2222-222222222222"
          embed-wrapper #js {:getAttribute
                             (fn [attribute]
                               (when (= attribute "originalblockid")
                                 (str placeholder-uuid)))}
          inner-row #js {:getAttribute (fn [_attribute] nil)}
          bullet #js {:getAttribute
                      (fn [attribute]
                        (case attribute
                          "blockid" (str target-uuid)
                          nil))}
          target #js {:closest
                      (fn [selector]
                        (case selector
                          ".bullet-container[blockid]" bullet
                          ".ls-block" inner-row
                          ".ls-block[originalblockid]" embed-wrapper
                          nil))}
          effect (atom nil)
          contextmenu-handler (atom nil)
          looked-up (atom [])
          cleanup (atom nil)
          previous-window (gobj/get js/globalThis "window")
          fake-window #js {:addEventListener
                           (fn [event-name handler]
                             (when (= "contextmenu" event-name)
                               (reset! contextmenu-handler handler)))
                           :removeEventListener (fn [& _])}
          event #js {:target target
                     :preventDefault (fn [])
                     :stopPropagation (fn [])}]
      (gobj/set js/globalThis "window" fake-window)
      (-> (p/with-redefs [hooks/use-effect!
                          (fn [setup _deps]
                            (reset! effect setup))
                          state/get-state (constantly nil)
                          state/get-current-repo (constantly "test")
                          state/selection? (constantly false)
                          state/clear-selection! (fn [])
                          state/conj-selection-block! (fn [& _])
                          db-async/<get-block
                          (fn [_repo block-uuid _opts]
                            (swap! looked-up conj block-uuid)
                            (p/resolved {:block/uuid block-uuid}))
                          cp-content/block-context-menu-content
                          (fn [& _] [:div])
                          shui/popup-show! (fn [& _] nil)]
            (render-static (container/app-context-menu-observer))
            (reset! cleanup (@effect))
            (@contextmenu-handler event)
            (p/let [_ (p/delay 0)]
              (is (= [placeholder-uuid] @looked-up)
                  "Block context-menu actions on an embed must receive the placeholder UUID.")))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally (fn []
                       (when-let [cleanup! @cleanup]
                         (cleanup!))
                       (if (some? previous-window)
                         (gobj/set js/globalThis "window" previous-window)
                         (gobj/remove js/globalThis "window"))
                       (done)))))))
