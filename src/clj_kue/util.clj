(ns clj-kue.util
  (:require [clojure.string :as string]))

(defmacro safely [& exprs]
  "Try to do exprs"
  `(try
      ~@exprs
      (catch Exception e#)))

(defn get-stack-trace [e]
  (string/join "\n" (map #(.toString %) (.getStackTrace e))))

(defmacro with-log-err [& exprs]
  "Try to do exprs"
  `(try ~@exprs
        (catch Exception e#
          (do (println (.getMessage e#))
              (println (get-stack-trace e#))))))
