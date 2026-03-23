(ns registry-api.server
  (:require [ring.adapter.jetty :as jetty]))

(defn start! [handler {:keys [port join?] :or {port 8080 join? false}}]
  (jetty/run-jetty handler {:port port
                            :join? join?}))

(defn stop! [server]
  (.stop server))
