(ns clj-kue.worker
  (:require [clj-kue.redis  :as r])
  (:use clj-kue.util))

(defn get-job
  "Attempt to fetch the next job"
  [type]
  (let [lkey  (str "q:" type ":jobs")
        zkey  (str "q:jobs:" type ":inactive")]
    (-> (r/with-conn
          (r/command :blpop lkey 0)
          (r/command :multi)
          (r/command :zrange zkey 0 0)
          (r/command :zremrangebyrank zkey 0 0)
          (r/command :exec))
        last
        ffirst)))

(defn process [job f]
  (try  (let [start (. System (nanoTime))]
          (f job)
          (-> (. System (nanoTime))
              (- start)
              (/ 1000000)
              int))
        (catch Exception e
          (do (.failed job)
              (.error job e)
              false))))

(defn start
  "Start processing jobs with the given function f"
  ([type f]
    (start type f 1000))
  ([type f sleep]
    (with-log-err
      (when-let [id (get-job type)]
        (let [job (new clj-kue.job id)]
          (.active job)
          (if-let [t  (process job f)]
            (do (.complete job)
                (.set job :duration t)
                (r/with-conn
                  (r/command :incrby :q:stats:work-time t)))
            (if (.attempt job)
                (.inactive job)
                (.failed job))))))
    (Thread/sleep sleep)
    (recur type f sleep)))
