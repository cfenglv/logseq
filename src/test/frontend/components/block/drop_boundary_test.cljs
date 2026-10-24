(ns frontend.components.block.drop-boundary-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.components.block]
            [frontend.db.async :as db-async]
            [frontend.handler.dnd :as dnd]
            [frontend.state :as state]
            [promesa.core :as p]))

(deftest selected-block-drop-passes-canonical-blocks-to-dnd-test
  (async done
    (let [block-drop (some-> (resolve 'frontend.components.block/block-drop) deref)
          first-uuid (random-uuid)
          second-uuid (random-uuid)
          selected [{:id first-uuid
                     :block {:block/uuid first-uuid :block/title "first"}}
                    {:id second-uuid
                     :block {:block/uuid second-uuid :block/title "second"}}]
          moved (atom nil)
          move-to (atom :nested)
          event #js {:stopPropagation (fn [])}]
      (is (fn? block-drop))
      (-> (p/with-redefs [state/get-selection-block-ids (constantly [first-uuid second-uuid])
                          state/get-current-repo (constantly "test-repo")
                          db-async/<get-blocks (fn [& _] (p/resolved selected))
                          dnd/move-blocks (fn [_event blocks _target _original _move-to]
                                            (reset! moved blocks))]
            (p/let [_ (block-drop event
                                  (random-uuid)
                                  {:block/uuid (random-uuid)}
                                  nil
                                  move-to)
                    _ (p/delay 0)]
              (is (= (mapv :block selected) @moved))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest selected-embedded-child-drop-resolves-placeholder-before-worker-read-test
  (async done
    (let [block-drop (some-> (resolve 'frontend.components.block/block-drop) deref)
          target-uuid (random-uuid)
          placeholder-uuid (random-uuid)
          placeholder {:block/uuid placeholder-uuid :block/title "embedded instance"}
          ancestor #js {:getAttribute (fn [attr]
                                       (when (= attr "originalblockid") (str placeholder-uuid)))}
          child #js {:getAttribute (fn [attr]
                                    (when (= attr "blockid") (str target-uuid)))
                     :closest (fn [selector]
                                (when (= selector ".ls-block[originalblockid]") ancestor))}
          requested (atom nil)
          moved (atom nil)
          move-complete (p/deferred)]
      (-> (p/with-redefs [state/get-selection-blocks (constantly [child ancestor])
                          state/get-current-repo (constantly "test-repo")
                          db-async/<get-blocks (fn [_repo ids _opts]
                                                (reset! requested (vec ids))
                                                (p/resolved [{:block placeholder}]))
                          dnd/move-blocks (fn [_event blocks _target _original _move-to]
                                           (reset! moved blocks)
                                           (p/resolve! move-complete nil))]
            (p/let [_ (block-drop #js {:stopPropagation (fn [])}
                                  (random-uuid) {:block/uuid (random-uuid)} nil (atom :sibling))
                    _ (p/timeout move-complete 1000)]
              (is (= [placeholder-uuid] @requested))
              (is (= [placeholder] @moved))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))
