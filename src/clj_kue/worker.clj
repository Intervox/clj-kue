(ns clj-kue.worker
  (:refer-clojure :exclude [get set])
  (:require [clj-kue.redis  :as r])
  (:use (clj-kue  [job :only [getJob]]
                  util)))

(defn- get-next-job
  "Attempt to fetch the next job"
  [type maxidle]
  (let [lkey  (str "q:" type ":jobs")
        zkey  (str "q:jobs:" type ":inactive")
        tout  (or maxidle 0)]
    (-> (r/with-conn
          (r/command :blpop lkey tout)
          (r/command :multi)
          (r/command :zrange zkey 0 0)
          (r/command :zremrangebyrank zkey 0 0)
          (r/command :exec))
        last
        ffirst)))

(defn- process [^clj_kue.job.KueJob job f]
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

(defn- future-cancel* [f timeout]
  (if (number? timeout)
    (cond
      (zero?  timeout)  (future-cancel f)
      (pos?   timeout)  (future
                          (Thread/sleep timeout)
                          (future-cancel f)))))

(defprotocol IKueWorker
  (get [this k]
    "Get the value of a property")
  (set [this k v]
    "Set new value of a property")
  (getset [this k v]
    "Set new value of a property and return the old one")
  (step [this]
    "Try to process single job")
  (start [this]
    "Start processing jobs")
  (stop [this] [this timeout]
    "Stop processing jobs"))

(deftype KueWorker [state]

  IKueWorker

  (get [this k]
    (clojure.core/get @state (keyword k)))

  (set [this k v]
    (dosync
      (alter state assoc (keyword k) v))
    this)

  (getset [this k v]
    (dosync
      (let [k*  (keyword k)
            old (clojure.core/get (ensure state) k*)]
        (when-not (= old v)
          (alter state assoc k* v))
        old)))

  (step [this]
    (with-log-err
      (when-let [id (get-next-job (.get this :type)
                                  (.get this :maxidle))]
        (let [job ^clj_kue.job.KueJob (getJob id)
              f   (.get this :handler)]
          (.active job)
          (if-let [t  (process job f)]
            (do (.complete job)
                (.set job :duration t)
                (r/with-conn
                  (r/command :incrby :q:stats:work-time t)))
            (if (.attempt job)
                (.inactive job)
                (.failed job))))))
    this)

  (start [this]
    (when-not (.getset this :active true)
      (.set this :future
        (future
          (while (.get this :active)
            (Thread/sleep 0)
            (.step this)))))
    this)

  (stop [this]
    (.stop this -1))

  (stop [this timeout]
    (when-let [f  (.getset this :future nil)]
      (let [f*  (future-cancel* f timeout)]
        (if (.getset this :active false)
            (safely @f))
        (if (future? f*)
            (future-cancel f*))))
    this))

(defn Worker ^clj_kue.worker.KueWorker
  [type f & {:keys [maxidle]}]
  (new KueWorker (ref { :handler  f
                        :type     (name type)
                        :maxidle  maxidle})))
