(ns registry-api.routes
  (:require
   [reitit.coercion.malli]
   [reitit.openapi :as openapi]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [muuntaja.core :as m]
   [reitit.ring.middleware.muuntaja :as rmu]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.keyword-params :as keyword-params]
   [ring.middleware.params :as params]
   [ring.util.http-response :as response]))

(def HealthResponse
  [:map
   [:status [:= "ok"]]
   [:service :string]
   [:env :string]])

(def ReadyResponse
  [:map
   [:ready? :boolean]
   [:checks [:map
             [:config-loaded? :boolean]
             [:database-config-present? :boolean]]]])

(def PingResponse
  [:map
   [:message [:= "pong"]]])

(defn health-handler [config]
  (fn [_request]
    (response/ok
     {:status "ok"
      :service (get-in config [:app :name])
      :env (str (get-in config [:app :env]))})))

(defn ready-handler [config]
  (fn [_request]
    (let [db-host (get-in config [:database :host])
          db-name (get-in config [:database :name])
          database-config-present? (every? seq [db-host db-name])]
      (if database-config-present?
        (response/ok
         {:ready? true
          :checks {:config-loaded? true
                   :database-config-present? true}})
        (response/service-unavailable
         {:ready? false
          :checks {:config-loaded? true
                   :database-config-present? false}})))))

(defn ping-handler [_request]
  (response/ok {:message "pong"}))

(defn router [config]
  (ring/router
   [["/openapi.json"
     {:get {:no-doc true
            :openapi {:id "openapi"}
            :handler (openapi/create-openapi-handler)}}]

    ["/health"
     {:get {:summary "Liveness endpoint"
            :responses {200 {:body HealthResponse}}
            :handler (health-handler config)}}]

    ["/ready"
     {:get {:summary "Readiness endpoint"
            :responses {200 {:body ReadyResponse}
                        503 {:body ReadyResponse}}
            :handler (ready-handler config)}}]

    ["/api/ping"
     {:get {:summary "Simple test endpoint"
            :responses {200 {:body PingResponse}}
            :handler ping-handler}}]]
   {:data {:coercion reitit.coercion.malli/coercion
           :muuntaja m/instance
           :middleware [openapi/openapi-feature
                        parameters/parameters-middleware
                        params/wrap-params
                        keyword-params/wrap-keyword-params
                        rmu/format-middleware
                        rrc/coerce-response-middleware
                        rrc/coerce-request-middleware]}}))

(defn app [config]
  (ring/ring-handler
   (router config)
   (ring/create-default-handler)))
