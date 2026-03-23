(ns user
  "REPL helpers for interactive development.

   Start here:
     (go)       — load config, start server on port 8080
     (halt)     — stop the running server
     (reset)    — stop, reload code, restart
     (status)   — show current server state

   Then try:
     (GET \"/health\")
     (GET \"/ready\")
     (GET \"/api/ping\")
     (GET \"/openapi.json\")

   Or exercise the Ring handler directly (no HTTP):
     (handler (mock-request :get \"/api/ping\"))
  "
  (:require
   [registry-api.config :as config]
   [registry-api.routes :as routes]
   [registry-api.server :as server]
   [clojure.pprint :refer [pprint]]
   [clojure.data.json :as json]))

;; ---- State ----------------------------------------------------------------

(defonce ^:private !server (atom nil))
(defonce ^:private !config (atom nil))
(defonce ^:private !app (atom nil))

;; ---- Lifecycle ------------------------------------------------------------

(defn go
  "Load config and start the HTTP server."
  []
  (if @!server
    (println "Server already running. Use (reset) to restart.")
    (let [cfg  (config/load-config)
          app  (routes/app cfg)
          port (get-in cfg [:app :port])
          srv  (server/start! app {:port port :join? false})]
      (reset! !config cfg)
      (reset! !app app)
      (reset! !server srv)
      (println (str "Server started on http://localhost:" port))
      :started)))

(defn halt
  "Stop the running server."
  []
  (if-let [srv @!server]
    (do
      (server/stop! srv)
      (reset! !server nil)
      (reset! !app nil)
      (reset! !config nil)
      (println "Server stopped.")
      :stopped)
    (do
      (println "No server running.")
      :not-running)))

(defn reset
  "Stop, reload namespaces, and restart."
  []
  (halt)
  (require 'registry-api.config :reload)
  (require 'registry-api.routes :reload)
  (require 'registry-api.server :reload)
  (go))

(defn status
  "Print current server state and config."
  []
  (if @!server
    (let [port (get-in @!config [:app :port])]
      (println (str "Server RUNNING on http://localhost:" port))
      (println "Config:")
      (pprint @!config))
    (println "Server NOT running. Call (go) to start.")))

;; ---- HTTP helpers ---------------------------------------------------------

(defn GET
  "Make a real HTTP GET request to the running server. Returns parsed body."
  [path]
  (let [port (get-in @!config [:app :port] 8080)
        url  (str "http://localhost:" port path)
        conn (doto (-> url java.net.URI. .toURL .openConnection)
               (.setRequestMethod "GET")
               (.setRequestProperty "Accept" "application/json")
               (.connect))
        status (.getResponseCode conn)
        stream (if (< status 400)
                 (.getInputStream conn)
                 (.getErrorStream conn))
        body   (slurp stream)]
    (.disconnect conn)
    {:status status
     :body   (try (json/read-str body :key-fn keyword)
                  (catch Exception _ body))}))

;; ---- Ring-level testing (no HTTP) -----------------------------------------

(defn handler
  "Get the current Ring handler for direct invocation."
  []
  @!app)

(defn mock-request
  "Build a minimal Ring request map."
  [method path]
  {:request-method method
   :uri path
   :headers {"accept" "application/json"}})

(comment
  ;; ---- Quick start --------------------------------------------------------
  ;; Evaluate these forms one by one in your REPL:

  ;; 1. Start the server
  (go)

  ;; 2. Check status
  (status)

  ;; 3. Hit the endpoints
  (GET "/health")
  (GET "/ready")
  (GET "/api/ping")
  (GET "/openapi.json")

  ;; 4. Test the Ring handler directly (no HTTP)
  (let [app (handler)
        req (mock-request :get "/api/ping")]
    (app req))

  ;; 5. Inspect config
  (pprint @!config)

  ;; 6. Restart after code changes
  (reset)

  ;; 7. Stop
  (halt))
