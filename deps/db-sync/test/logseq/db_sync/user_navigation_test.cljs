(ns logseq.db-sync.user-navigation-test
  (:require [cljs.test :refer [deftest is]]
            [logseq.common.user-navigation :as navigation]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.malli-schema :as schema]))

(def scope "11111111-1111-4111-8111-111111111111")
(def x "22222222-2222-4222-8222-222222222222")
(def y "33333333-3333-4333-8333-333333333333")
(def device "44444444-4444-4444-8444-444444444444")
(def other-device "55555555-5555-4555-8555-555555555555")
(def member "66666666-6666-4666-8666-666666666666")
(def new-member "77777777-7777-4777-8777-777777777777")

(deftest favorite-membership-and-stale-order-test
  (let [key-x (navigation/ident :favorite scope x)
        selected (navigation/favorite-value nil true member 100)
        entries {(navigation/ident :favorites-seed scope nil) [x y]
                 key-x selected}
        order (navigation/order-value entries scope [x y])
        cancelled (assoc entries key-x {:selected? false})
        reselected (assoc cancelled key-x (navigation/favorite-value nil true new-member 200))]
    (is (= selected (navigation/favorite-value selected true new-member 200)))
    (is (= [x y] (navigation/favorites (assoc entries (navigation/ident :order scope nil) order) scope)))
    (is (= [y] (navigation/favorites (assoc cancelled (navigation/ident :order scope nil) order) scope)))
    (is (= [y x] (navigation/favorites (assoc reselected (navigation/ident :order scope nil) order) scope)))
    (is (= {:selected? false} (common/read-transit (common/write-transit {:selected? false}))))
    (is ((get schema/navigation-value-validators :favorite) {:selected? false}))))

(deftest device-visits-and-empty-initialization-test
  (let [entries {(navigation/ident :visits scope device) [[x 100] [y 90]]
                 (navigation/ident :visits scope other-device) [[y 200]]
                 (navigation/ident :recents-seed scope device) [x]}]
    (is (= [y x] (navigation/recents entries scope)))
    (is (= [[x 300] [y 90]] (navigation/visit-value [[x 100] [y 90]] x 300)))
    (is (= navigation/recents-limit
           (count (navigation/recent-visits (mapv #(vector (str %) %) (range 30))))))
    (let [candidate {:device-id device :favorites-seed [] :recents-seed []}
          initialized (navigation/initialization-values {} scope candidate)]
      (is (= [] (get initialized (navigation/ident :favorites-seed scope nil))))
      (is (= {} (navigation/initialization-values initialized scope
                                                  (assoc candidate :favorites-seed [x] :recents-seed [y])))))))

(deftest conditional-transfer-preserves-new-intent-test
  (let [key-x (navigation/ident :favorite scope x)
        visits-key (navigation/ident :visits scope device)
        entries {key-x {:selected? false} visits-key [[y 200]]}
        candidate {:device-id device :transfer-id member :favorites-seed [x] :recents-seed [x]
                   :values {key-x {:selected? true :membership-id new-member :added-at 10}
                            visits-key [[x 100] [y 150]]}}
        transferred (navigation/initialization-values entries scope candidate)
        result (merge entries transferred)]
    (is (not (contains? transferred key-x)))
    (is (= [[y 200] [x 100]] (get transferred visits-key)))
    (is (= [] (navigation/favorites result scope)))
    (is (= {} (navigation/initialization-values result scope candidate)))
    (is (= result (common/read-transit (common/write-transit result))))
    (is (navigation/reserved-id-shape? (navigation/graph-id "synthetic-user")))
    (is (not= (navigation/graph-id "synthetic-user") (navigation/graph-id "other-user")))))
