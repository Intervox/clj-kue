(ns clj-kue.job
  (:refer-clojure :exclude [get set])
  (:require [clj-kue.redis  :as r     ]
            [clojure.walk   :as walk  ])
  (:use (clj-time [core   :only [now]]
                  [coerce :only [to-long from-long]])
        clj-kue.util))

(def priorities
  { :low      "10"
    :normal   "0"
    :medium   "-5"
    :high     "-10"
    :critical "-15" })

(defprotocol IKueJob
  (get [this] [this k]
    "Get a value of a field")
  (set [this k v]
    "Set a value of a field")
  (touch [this]
    "Update updated_at field")
  (removeState [this]
    "Clear current state")
  (state [this state]
    "Set current state")
  (complete [this])
  (failed [this])
  (inactive [this])
  (active [this])
  (progress [this] [this complete total]
    "Get or update progress value")
  (log [this s]
    "Log a string")
  (priority [this] [this level]
    "Set or get the priority level")
  (error [this] [this err]
    "Set or get the job's failure err")
  (attempt [this]
    "Increment attemps, returns true if any attemps left"))

(defn- set* [storage k v]
  (swap! storage assoc
    (keyword k) (if (keyword? v) (name v) v)))

(deftype KueJob [storage]

  IKueJob

  (get [this]
    @storage)

  (get [this k]
    (clojure.core/get @storage (keyword k)))

  (set [this k v]
    (set* storage k v)
    (let [id  (.get this :id)]
      (r/with-conn
        (r/command :hset (str "q:job:" id) k v)))
    this)

  (touch [this]
    (.set this :updated_at (to-long (now))))

  (removeState
    [this]
    (let [{:keys [id state type]} (.get this)]
      (r/with-conn
        (r/command :zrem "q:jobs" id)
        (r/command :zrem (str "q:jobs:" state) id)
        (r/command :zrem (str "q:jobs:" type ":" state) id)))
    this)

  (state [this state]
    (.removeState this)
    (.set this :state state)
    (let [{:keys [id priority state type]} (.get this)]
      (r/with-conn
        (r/command :zadd "q:jobs" priority id)
        (r/command :zadd (str "q:jobs:" state) priority id)
        (r/command :zadd (str "q:jobs:" type ":" state) priority id)
        (if (= state "inactive")
            (r/command :lpush (str "q:" type ":jobs") 1)))
      (.touch this)))

  (complete [this]
    (.set this :progress 100)
    (.state this :complete))

  (failed [this]
    (.state this :failed))

  (inactive [this]
    (.state this :inactive))

  (active [this]
    (.state this :active))

  (progress [this]
    (.get this :progress))

  (progress [this complete total]
    (->>  (/ complete total)
          (* 100)
          float
          (min 100)
          (.set this :progress))
    (.touch this))

  (log [this s]
    (let [id  (.get this :id)]
      (r/with-conn
        (r/command :rpush (str "q:job:" id ":log") s)))
    (.touch this))

  (priority [this]
    (.get this :priority))

  (priority [this level]
    (set* storage :priority (or (priorities level) level))
    this)

  (error [this]
    (.get this :error))

  (error [this err]
    (let [msg (if-not (string? err)
                      (safely (or (.getMessage err)
                                  (.toString err)))
                      err)
          sum (if-not (string? err)
                      (safely (get-stack-trace err)))]
      (.set this :error msg)
      (.log this sum))
    (.set this :failed_at (to-long (now))))

  (attempt [this]
    (let [id    (.get this :id)
          -key  (str "q:job:" id)]
      (.touch this)
      (->>  (r/with-conn
              (r/command :hsetnx  -key :max_attempts 1)
              (r/command :hget    -key :max_attempts  )
              (r/command :hincrby -key :attempts     1))
            (drop 1)
            (map (comp read-string str))
            (apply -)
            pos?))))

(defn getJob [id]
  (let [-key  (str "q:job:" id)
        data  (->>  (r/with-conn
                      (r/command :hgetall -key))
                    (partition 2)
                    (map vec)
                    (into {})
                    walk/keywordize-keys)]
    (if (empty? data)
        (throw (Exception. (str "job " id " doesnt exist")))
        (-> (r/parse data :data)
            (assoc :id id)
            atom
            KueJob.))))
