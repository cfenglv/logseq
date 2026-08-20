(ns frontend.components.rtc.indicator-test
  (:require [cljs.test :refer [deftest is]]
            [frontend.components.rtc.indicator :as indicator]))

(deftest repairing-is-visible-as-busy-without-claiming-open-test
  (is (= :syncing
         (indicator/rtc-display-state
          {:rtc-lock false
           :rtc-state {:ws-state :repairing}})))
  (is (= :close
         (indicator/rtc-display-state
          {:rtc-lock false
           :rtc-state {:ws-state :repair-required}})))
  (is (= :close
         (indicator/rtc-display-state
          {:rtc-lock true
           :rtc-state {:ws-state :repair-required}}))
      "a stale open lock must not hide the repair-required state")
  (is (= :syncing
         (indicator/rtc-display-state
          {:rtc-lock true
           :rtc-state {:ws-state :repairing}}))
      "a stale open lock must not claim that snapshot repair is healthy")
  (is (= :open
         (indicator/rtc-display-state
          {:rtc-lock true
           :rtc-state {:ws-state :open}}))))

(deftest asset-transfer-counts-counts-active-uploads-and-downloads
  (is (= {:upload 2
          :download 1}
         (indicator/asset-transfer-counts
          {"upload-1" {:direction :upload :loaded 0 :total 10}
           "upload-2" {:direction :upload :loaded 5 :total 10}
           "upload-done" {:direction :upload :loaded 10 :total 10}
           "download-1" {:direction :download :loaded 1 :total 10}
           "missing-total" {:direction :download :loaded 1}
           "other" {:direction :other :loaded 0 :total 10}}))))

(deftest asset-status-rows-shows-pending-upload-and-active-transfer-info
  (is (= [{:count 2 :label-key :sync/missing-asset-files}
          {:count 1 :label-key :sync/pending-asset-uploads}
          {:count 1 :label-key :sync/assets-uploading}
          {:count 2 :label-key :sync/assets-downloading}]
         (indicator/asset-status-rows
          {:pending-asset-ops 3
           :missing-asset-upload-files [{:file "assets/missing-1.pdf"}
                                        {:file "assets/missing-2.pdf"}]
           :asset-transfer-counts {:upload 1
                                   :download 2}}))))

(deftest asset-status-rows-hides-zero-counts
  (is (= []
         (indicator/asset-status-rows
          {:pending-asset-ops 0
           :missing-asset-upload-files []
           :asset-transfer-counts {:upload 0
                                   :download 0}}))))
