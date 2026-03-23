(ns registry-api.main
  (:gen-class)
  (:require
   [registry-api.config :as config]
   [registry-api.routes :as routes]
   [registry-api.server :as server]))

(defn -main [& _args]
  (let [cfg     (config/load-config)
        app     (routes/app cfg)
        port    (get-in cfg [:app :port])
        join?   (get-in cfg [:http :join?] false)
        _server (server/start! app {:port port :join? join?})]
    (println "registry-api started on port" port)
    (println (str "health  -> http://localhost:" port "/health"))
    (println (str "ready   -> http://localhost:" port "/ready"))
    (println (str "ping    -> http://localhost:" port "/api/ping"))
    (println (str "openapi -> http://localhost:" port "/openapi.json"))))
