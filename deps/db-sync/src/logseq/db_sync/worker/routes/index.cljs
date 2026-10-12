(ns logseq.db-sync.worker.routes.index
  (:require [reitit.core :as r]))

(def ^:private route-data
  [["/admin"
    ["/graphs/:graph-id" {:methods {"DELETE" :admin-graphs/delete}}]]

   ["/user/profile" {:methods {"GET" :user-profile/get
                               "PUT" :user-profile/save}}]

   ["/graphs"
    ["" {:methods {"GET" :graphs/list
                   "POST" :graphs/create}}]
    ["/:graph-id"
     ["/access" {:methods {"GET" :graphs/access}}]
     ["/members" {:methods {"GET" :graph-members/list
                            "POST" :graph-members/create}}]
     ["/members/:member-id" {:methods {"PUT" :graph-members/update
                                       "DELETE" :graph-members/delete}}]
     ["/members/:member-id/avatar" {:methods {"GET" :graph-members/avatar}}]
     ["" {:methods {"DELETE" :graphs/delete}}]]]

   ["/e2ee"
    ["/user-keys" {:methods {"GET" :e2ee/user-keys-get
                             "POST" :e2ee/user-keys-post}}]
    ["/user-public-key" {:methods {"GET" :e2ee/user-public-key-get}}]
    ["/graphs/:graph-id"
     ["/aes-key" {:methods {"GET" :e2ee/graph-aes-key-get
                            "POST" :e2ee/graph-aes-key-post}}]
     ["/grant-access" {:methods {"POST" :e2ee/grant-access}}]]]])

(def ^:private router
  (r/router route-data))

(defn match-route [method path]
  (when-let [match (r/match-by-path router path)]
    (when-let [handler (get-in match [:data :methods method])]
      (assoc match :handler handler))))
