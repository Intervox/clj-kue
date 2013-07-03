(ns clj-kue.redis
  (:require [taoensso.carmine :as car ]
            [cheshire.core    :as json])
  (:use clj-kue.util))

(def ^:private car (find-ns 'taoensso.carmine))

(def ^:dynamic *redis-kue-connection* nil)

(def json-opts
  { :escape-non-ascii true  })

(defmacro with-conn [& body]
  `(car/with-conn (:pool *redis-kue-connection*)
                  (:spec *redis-kue-connection*)
                  ~@body))

(defn- serialize-arg [x]
  (cond
    (string?  x)  x
    (keyword? x)  (name x)
    :else         (json/generate-string x json-opts)))

(defn parse
  ([x]
    (try
      (json/parse-string x true)
      (catch Exception e x)))
  ([x & ks]
    (update-in x ks parse)))

(defn command [command & args]
  (apply  (ns-resolve car (symbol (name command)))
          (map serialize-arg args)))

(defn connect!
  ([]
    (connect! nil nil))
  ([spec-opts]
    (connect! spec-opts nil))
  ([spec-opts pool-opts]
    (let [pool  (->>  pool-opts
                      (apply concat)
                      (apply car/make-conn-pool))
          spec  (->>  spec-opts
                      (apply concat)
                      (apply car/make-conn-spec))]
      (alter-var-root #'*redis-kue-connection*
                      (constantly { :pool pool
                                    :spec spec}))
      (with-conn (command :ping)))))
