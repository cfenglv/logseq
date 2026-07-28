(ns frontend.components.e2ee-test
  (:require ["react" :as react]
            ["react-dom/server" :as react-dom-server]
            [cljs.test :refer [async deftest is testing]]
            [frontend.common.crypt :as crypt]
            [frontend.components.e2ee :as e2ee]
            [frontend.state :as state]
            [goog.object :as gobj]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]))

(defn- attach-input-ref!
  [props input]
  (when-let [input-ref (:ref props)]
    (if (fn? input-ref)
      (input-ref input)
      (set! (.-current input-ref) input))))

(defn- render-password-component!
  [component states]
  (let [state-index (atom -1)
        inputs (atom [])
        buttons (atom [])
        previous-react (gobj/get js/globalThis "React")]
    (gobj/set js/globalThis "React" react)
    (try
      (with-redefs [hooks/use-state
                    (fn [initial]
                      (let [index (swap! state-index inc)
                            state-atom (or (nth states index nil)
                                           (atom initial))]
                        [@state-atom #(reset! state-atom %)]))
                    shui/toggle-password
                    (fn [props]
                      (swap! inputs conj props)
                      (.createElement react "input" nil))
                    shui/button
                    (fn [props & _children]
                      (swap! buttons conj props)
                      (.createElement react "button" nil))
                    shui/dialog-close! (fn [])]
        (.renderToStaticMarkup react-dom-server (component))
        {:input (first @inputs)
         :button (first @buttons)})
      (finally
        (if (some? previous-react)
          (gobj/set js/globalThis "React" previous-react)
          (js-delete js/globalThis "React"))))))

(deftest request-password-button-and-enter-read-autofilled-dom-value-test
  (async done
         (let [button-promise (p/deferred)
               enter-promise (p/deferred)
               button-render (render-password-component!
                              #(e2ee/e2ee-request-password button-promise)
                              [(atom "")])
               enter-render (render-password-component!
                             #(e2ee/e2ee-request-password enter-promise)
                             [(atom "")])
               button-input #js {:value "autofilled-button-password"}
               enter-input #js {:value "autofilled-enter-password"}]
           (attach-input-ref! (:input button-render) button-input)
           (attach-input-ref! (:input enter-render) enter-input)
           (testing "a password-manager-filled field must leave the submit button usable"
             (is (not (true? (:disabled (:button button-render))))))
           ((:on-click (:button button-render))
            #js {:currentTarget #js {:tagName "BUTTON"}})
           ((:on-key-press (:input enter-render))
            #js {:key "Enter"
                 :target enter-input
                 :currentTarget enter-input})
           (-> (p/all [button-promise enter-promise])
               (p/then
                (fn [[button-value enter-value]]
                  (is (= "autofilled-button-password" button-value))
                  (is (= "autofilled-enter-password" enter-value))
                  (done)))
               (p/catch
                (fn [error]
                  (is false (str error))
                  (done)))))))

(deftest decrypt-password-uses-same-current-dom-value-for-decrypt-and-save-test
  (async done
         (let [private-key-promise (p/deferred)
               decrypt-calls (atom [])
               save-calls (atom [])
               rendered
               (render-password-component!
                #(e2ee/e2ee-password-to-decrypt-private-key
                  :encrypted-private-key
                  private-key-promise)
                [(atom "") (atom false)])
               input #js {:value "autofilled-decrypt-password"}]
           (attach-input-ref! (:input rendered) input)
           (is (not (true? (:disabled (:button rendered)))))
           (p/with-redefs [crypt/<decrypt-private-key
                           (fn [password encrypted-private-key]
                             (swap! decrypt-calls conj
                                    [password encrypted-private-key])
                             (p/resolved :private-key))
                           state/<invoke-db-worker
                           (fn [op password]
                             (swap! save-calls conj [op password])
                             (p/resolved nil))]
             ((:on-click (:button rendered))
              #js {:currentTarget #js {:tagName "BUTTON"}}))
           (-> private-key-promise
               (p/then
                (fn [private-key]
                  (is (= :private-key private-key))
                  (is (= [["autofilled-decrypt-password"
                           :encrypted-private-key]]
                         @decrypt-calls))
                  (is (= [[:thread-api/save-e2ee-password
                           "autofilled-decrypt-password"]]
                         @save-calls))
                  (done)))
               (p/catch
                (fn [error]
                  (is false (str error))
                  (done)))))))

(deftest controlled-password-input-path-remains-supported-test
  (async done
         (let [password-state (atom "")
               password-promise (p/deferred)
               first-render
               (render-password-component!
                #(e2ee/e2ee-request-password password-promise)
                [password-state])
               change-target #js {:value "typed-password"}]
           ((:on-change (:input first-render))
            #js {:target change-target})
           (let [second-render
                 (render-password-component!
                  #(e2ee/e2ee-request-password password-promise)
                  [password-state])]
             (is (false? (boolean (:disabled (:button second-render)))))
             ((:on-click (:button second-render))
              #js {:currentTarget #js {:tagName "BUTTON"}}))
           (-> password-promise
               (p/then
                (fn [password]
                  (is (= "typed-password" password))
                  (done)))
               (p/catch
                (fn [error]
                  (is false (str error))
                  (done)))))))
