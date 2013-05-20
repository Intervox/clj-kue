(ns clj-kue.core
  (:require [clj-kue.worker :as worker])
  (:use [clj-kue.worker :only [Worker]]))

(defn process
  "Process jobs with the given type, invoking (f job)"
  ([type f]
    (.start (Worker (name type) f)))
  ([type n f]
    (repeatedly n
      #(.start (Worker (name type) f)))))
