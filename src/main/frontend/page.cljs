(ns frontend.page
  "Provides root component for both Logseq app and publishing build"
  (:require [frontend.components.container :as container]
            [frontend.components.error-screen :as error-screen]
            [frontend.components.plugins :as plugin]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.handler.route :as route-handler]
            [frontend.rfx :as rfx]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [io.factorhouse.hsx.core :as hsx]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [reitit.frontend.easy :as rfe]))

(defn- setup-fns!
  []
  (try
    (comp
     (ui/setup-active-keystroke!)
     (ui/setup-viewport-listeners!))
    (catch :default _e
      nil)))

(hsx/defc not-found
  []
  [:div {:class "flex flex-col items-center justify-center min-h-screen bg-background"}
   [:h1 {:class "text-6xl font-bold text-gray-12 mb-4"} "404"]
   [:h2 {:class "text-2xl font-semibold text-gray-10 mb-6"} (t :page/not-found-title)]
   [:p {:class "text-gray-500 mb-8"} (t :page/not-found-desc)]
   (shui/button {:on-click #(rfe/push-state :home)
                 :variant :outline}
                (shui/tabler-icon "home") (t :page/go-back-home))])

(defn route-view-key
  "React key for the painted route view. Title and path strings change while
   typing; the resolved page or zoomed-block uuid does not."
  [route-match paint]
  (or (some-> (get-in paint [:page :block/uuid]) str)
      (:path route-match)
      (get-in route-match [:data :name])))

(defn- painted-page-title-key
  [{:keys [status page]}]
  (when (and (= :ready status) page (not (:block/page page)))
    [(:block/uuid page) (:block/title page)]))

(hsx/defc route-view
  "A route switch unmounts the previous view at once. Keep it on screen until
   the next route can paint in a single commit instead of flashing an empty
   main area while its snapshots load."
  [route-match]
  (let [paint (routes/use-route-paint route-match)
        ready? (not= :loading (:status paint))
        [page-uuid page-title] (painted-page-title-key paint)
        [held set-held!] (hooks/use-state nil)
        current (when ready?
                  {:route-match route-match
                   :key (route-view-key route-match paint)})]
    (hooks/use-effect!
     (fn []
       (when page-title
         (route-handler/update-page-title! route-match))
       js/undefined)
     [page-uuid page-title])
    (when (and current (not= current held))
      (set-held! current))
    (when-let [painted (or current held)]
      ^{:key (:key painted)}
      [(:view (:data (:route-match painted))) (:route-match painted)])))

(hsx/defc current-page
  []
  (hooks/use-effect!
   (fn []
     (state/setup-electron-updater!)
     (state/load-app-user-cfgs)
     (ui/inject-document-devices-envs!)
     (ui/inject-dynamic-style-node!)
     (plugin-handler/host-mounted!)
     (setup-fns!))
   [])
  (if-let [route-match (rfx/use-sub [:route-match])]
    (when (:view (:data route-match))
      (error-screen/boundary
       [:<>
        (container/root-container
         route-match
         (error-screen/boundary (route-view route-match) true))
        (when config/lsp-enabled?
          (plugin/hook-daemon-renderers))]))
    (not-found)))
