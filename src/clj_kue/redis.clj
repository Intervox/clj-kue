(ns clj-kue.redis
  (:require [taoensso.carmine :as car ]
            [cheshire.core    :as json])
  (:use clj-kue.util))

(def ^:dynamic *redis-kue-connection* nil)

(def json-opts
  { :escape-non-ascii true  })

(defmacro with-conn [& body]
  `(car/wcar *redis-kue-connection* ~@body))



(defn- serialize-arg [x]
  (if (or (string? x)
          (keyword? x))
      x
      (json/generate-string x json-opts)))

(defn parse
  ([x]
    (try
      (json/parse-string x true)
      (catch Exception e x)))
  ([x & ks]
    (update-in x ks parse)))

(defn command [& args]
  (car/redis-call (map serialize-arg args)))

(defn set-connection-pool! [pool]
  (alter-var-root #'*redis-kue-connection*
                  assoc :pool pool))

(defn set-connection-spec! [spec]
  (alter-var-root #'*redis-kue-connection*
                  assoc :spec spec))

(defn set-connection! [{:keys [pool spec]}]
  (set-connection-pool! pool)
  (set-connection-spec! spec))

(defn connect!
  ([]
    (connect! nil nil))
  ([spec]
    (connect! spec nil))
  ([spec pool]
    (alter-var-root #'*redis-kue-connection*
                    (constantly { :pool pool
                                  :spec spec}))
    (with-conn (command :ping))))
