(ns registry-api.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defn load-config []
  (aero/read-config (io/resource "config.edn")))
