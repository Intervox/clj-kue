(ns clj-kue.carmine
  (:require [clj-kue.redis :as redis]))

(defn set-connection-pool! [pool]
  (alter-var-root #'redis/*redis-kue-connection*
                  assoc :pool pool))

(defn set-connection-spec! [pool]
  (alter-var-root #'redis/*redis-kue-connection*
                  assoc :pool spec))

(defn set-connection! [pool spec]
  (set-connection-pool! pool)
  (set-connection-pool! spec))
