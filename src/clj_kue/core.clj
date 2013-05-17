(ns clj-kue.core
  (:require [clj-kue.worker :as worker]))

(defn process
  "Process jobs with the given type, invoking (f job)"
  ([type f]
    (future (worker/start type f)))
  ([type n f]
    (dotimes [_ n]
      (future (worker/start type f))))
  ([type n f sleep]
    (dotimes [_ n]
      (future (worker/start type f sleep)))))
