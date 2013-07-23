(ns clj-kue.util
  (:require [clojure.string :as string]))

(defmacro safely [& exprs]
  "Try to do exprs"
  `(try
      ~@exprs
      (catch Exception e#)))

(defn get-stack-trace [^Exception e]
  (->>  (.getStackTrace e)
        (map #(.toString ^java.lang.StackTraceElement %))
        (cons (.getMessage e))
        (string/join "\n")))

(defmacro with-log-err [& exprs]
  "Try to do exprs"
  `(try ~@exprs
        (catch Exception e#
          (println (get-stack-trace e#)))))
