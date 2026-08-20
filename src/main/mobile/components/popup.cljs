(ns mobile.components.popup
  "Mobile popup"
  (:require [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [lambdaisland.glogi :as log]
            [logseq.shui.popup.core :as shui-popup]
            [logseq.shui.ui :as shui]
            [mobile.state :as mobile-state]
            [promesa.core :as p]
            [io.factorhouse.hsx.core :as hsx]))

(defonce *last-popup? (atom nil))
(defonce *last-popup-data (atom nil))
(defonce *native-sheet-dismissing? (atom false))
(defonce *pending-popup-data (atom nil))

(declare activate-native-popup!)

(defn- <observe-call!
  [f on-error]
  (try
    (let [result (f)]
      (if (p/promise? result)
        (p/catch result on-error)
        (p/resolved result)))
    (catch :default error
      (p/resolved (on-error error)))))

(defn- popup-min-height
  [default-height]
  (cond
    (false? default-height) nil
    (number? default-height) default-height
    :else 400))

(defn- present-native-sheet!
  [data]
  (when-let [^js plugin mobile-util/native-bottom-sheet]
    (let [{:keys [opts]} data
          id (:id opts)
          sheet-presented? (some? @*last-popup-data)]
      (reset! *last-popup-data data)
      (when-not sheet-presented?
        (let [present-opts
              (clj->js
               (let [height (popup-min-height (:default-height opts))
                     height' (if (contains? #{:ls-icon-picker} id)
                               760
                               height)]
                 (cond-> {:allowFullHeight (not= (:type opts) :action-sheet)}
                   (int? height') (assoc :defaultHeight height'))))]
          (<observe-call!
           #(.present plugin present-opts)
           (fn [error]
             (let [current-data @*last-popup-data
                   retry-data (or @*pending-popup-data
                                  (when (and
                                         (not @*native-sheet-dismissing?)
                                         (not= data current-data))
                                    current-data))]
               (log/error :native-bottom-sheet-present-failed error)
               (reset! *last-popup? false)
               (reset! *last-popup-data nil)
               (reset! *native-sheet-dismissing? false)
               (reset! *pending-popup-data nil)
               (mobile-state/set-popup! nil)
               (when retry-data
                 (activate-native-popup! retry-data))))))))))

(defn- activate-native-popup!
  [data]
  (reset! *last-popup? true)
  (mobile-state/set-popup! data)
  (present-native-sheet! data))

(defn- request-native-popup!
  [data]
  (if @*native-sheet-dismissing?
    (reset! *pending-popup-data data)
    (activate-native-popup! data)))

(defn- dismiss-native-sheet!
  []
  (when-let [^js plugin mobile-util/native-bottom-sheet]
    (reset! *native-sheet-dismissing? true)
    (<observe-call!
     #(.dismiss plugin #js {})
     (fn [error]
       (log/error :native-bottom-sheet-dismiss-failed error)
       (when (and @*native-sheet-dismissing?
                  (some? @*last-popup-data))
         (reset! *native-sheet-dismissing? false)
         (when-let [data @*pending-popup-data]
           (reset! *pending-popup-data nil)
           (activate-native-popup! data)))))))

(defn- notify-native-sheet-content-ready!
  []
  (when-let [^js plugin mobile-util/native-bottom-sheet]
    (.requestAnimationFrame
     js/window
     (fn []
       (.requestAnimationFrame
        js/window
        (fn []
          (<observe-call!
           #(.contentReady plugin #js {})
           #(log/error :native-bottom-sheet-content-ready-failed %))))))))

(defn- handle-native-sheet-state!
  [^js data]
  (let [dismissing? (.-dismissing data)]
    (cond
      (true? dismissing?)
      (let [popup-open? (some? @mobile-state/*popup-data)]
        (reset! *native-sheet-dismissing? true)
        (when popup-open?
          (<observe-call!
           #(state/pub-event! [:mobile/clear-edit])
           #(log/error :mobile-clear-edit-failed %))
          (mobile-state/set-popup! nil))
        (reset! *last-popup? false)
        (reset! *last-popup-data nil)
        (notify-native-sheet-content-ready!)
        (when popup-open?
          (when-let [plugin ^js mobile-util/native-editor-toolbar]
            (<observe-call!
             #(.dismiss plugin)
             #(log/error :native-editor-toolbar-dismiss-failed %)))))

      (false? dismissing?)
      (do
        (reset! *native-sheet-dismissing? false)
        (when-let [data @*pending-popup-data]
          (reset! *pending-popup-data nil)
          (activate-native-popup! data)))

      :else
      nil)))

(defonce native-sheet-listener
  (when-let [^js plugin (when (mobile-util/native-platform?)
                          mobile-util/native-bottom-sheet)]
    (.addListener plugin "state" handle-native-sheet-state!)))

(defn- wrap-calc-commands-popup-side
  [pos opts]
  (let [[side _mh] (let [[_x y _ height] pos
                        vh (.-clientHeight js/document.body)
                        [th bh] [(- y 85) (- vh (+ y height) 310)]
                        direction (if (> bh 280) "bottom"
                                      (if (> (- th bh) 100)
                                        "top" "bottom"))]
                    (if (= "top" direction)
                      ["top" th]
                      ["bottom" bh]))]
    (-> (assoc opts :auto-side? true)
        (assoc-in [:content-props :side] side))))

(defn popup-show!
  [event content-fn {:keys [id dropdown-menu?] :as opts}]
  (cond
    (and (keyword? id) (= "editor.commands" (namespace id)))
    (let [opts (wrap-calc-commands-popup-side event opts)
          side (some-> opts :content-props :side)
          max-h (some-> opts :max-popup-height (js/parseInt) (- 48))
          _ (when max-h (js/document.documentElement.style.setProperty
                         (str "--" side "-popup-content-max-height") (str max-h "px")))
          pid (shui-popup/show! event content-fn opts)]
      (reset! *last-popup? false)
      pid)

    dropdown-menu?
    (let [pid (shui-popup/show! event content-fn opts)]
      (reset! *last-popup? false)
      pid)

    :else
    (when content-fn
      (when-let [_plugin ^js mobile-util/native-bottom-sheet]
        (let [data {:open? true
                    :content-fn content-fn
                    :opts opts}]
          (request-native-popup! data))))))

(defn popup-hide!
  [& args]
  (let [id (first args)
        current-id (get-in @*last-popup-data [:opts :id])
        current-popup? (or (nil? id) (= id current-id))
        pending-id (get-in @*pending-popup-data [:opts :id])
        pending-popup? (and (some? @*pending-popup-data)
                            (or (nil? id) (= id pending-id)))]
    (when pending-popup?
      (reset! *pending-popup-data nil))
    (cond
      (= :download-rtc-graph id)
      (do
        (when (and @*last-popup?
                   current-popup?
                   (not @*native-sheet-dismissing?))
          (dismiss-native-sheet!))
        (mobile-state/set-tab! "home"))

      (and @*last-popup? (not (= id :editor.commands/commands)))
      (when (and current-popup?
                 (not @*native-sheet-dismissing?))
        (dismiss-native-sheet!))

      :else
      (apply shui-popup/hide! args))))

(set! shui/popup-show! popup-show!)
(set! shui/popup-hide! popup-hide!)

(hsx/defc popup
  [opts content-fn]
  (let [title (or (:title opts) (when (string? content-fn) content-fn))
        content (if (fn? content-fn)
                  (content-fn)
                  (if-let [buttons (:buttons opts)]
                    [:div.-mx-2
                     (for [{:keys [role text]} buttons]
                       (ui/menu-link
                        {:on-click #(some-> (:on-action opts) (apply [{:role role}]))
                         :data-role role}
                        [:span.text-lg.flex.items-center text]))]
                    (when-not (string? content-fn) content-fn)))]
    [:div {:class "flex flex-col items-center p-2 w-full h-full"}
     [:div.app-popup
      (when title [:h2.py-2.opacity-40 title])
      content]]))
