(ns clj-kue.job
  (:refer-clojure :exclude [set])
  (:require [clj-kue.redis  :as r     ]
            [clojure.walk   :as walk  ])
  (:use (clj-time [core   :only [now]]
                  [coerce :only [to-long from-long]])
        clj-kue.util)
  (:gen-class
    :name     clj-kue.job
    :state    data
    :init     init
    :prefix   "job-"
    :main     false
    :methods  [ [get [] clojure.lang.PersistentArrayMap]
                [get [String] String]
                [get [clojure.lang.Keyword] String]
                [set [String String] void]
                [set [clojure.lang.Keyword String] void]
                [set [String clojure.lang.Keyword] void]
                [set [clojure.lang.Keyword clojure.lang.Keyword] void]
                [set [String Long] void]
                [set [clojure.lang.Keyword Long] void]
                [set [String Float] void]
                [set [clojure.lang.Keyword Float] void]
                [touch [] void]
                [removeState [] void]
                [state [String] void]
                [state [clojure.lang.Keyword] void]
                [complete [] void]
                [failed [] void]
                [inactive [] void]
                [active [] void]
                [progress [] String]
                [progress [Long Long] void]
                [log [String] void]
                [priority [] String]
                [priority [Long] void]
                [priority [String] void]
                [priority [clojure.lang.Keyword] void]
                [error [] String]
                [error [String] void]
                [error [Exception] void]
                [attempt [] Boolean]]
    :constructors { [] []
                    [String] []}))

(def priorities
  { :low      "10"
    :normal   "0"
    :medium   "-5"
    :high     "-10"
    :critical "-15" })

(defn set [this k v]
  (swap! (.data this) assoc (keyword k) (name v)))

(defn getJob
  [id]
  (let [-key  (str "q:job:" id)
        data  (->>  (r/with-conn
                      (r/command :hgetall -key))
                    (partition 2)
                    (map vec)
                    (into {})
                    walk/keywordize-keys)]
    (if (empty? data)
        (throw (Exception. (str "job " id " doesnt exist"))))
    (assoc (r/parse data :data) :id id)))

; Constructor

(defn job-init
  ([]
    [[] (atom {})])
  ([id]
    [[] (atom (getJob id))]))

; Methods

(defn job-get
  "Get a value of a field"
  ([this]
    @(.data this))
  ([this k]
    (get @(.data this) (keyword k))))

(defn job-set
  "Set a value of a field"
  [this k v]
  (set this k v)
  (let [{:keys [id]} (.get this)]
    (r/with-conn
      (r/command :hset (str "q:job:" id) k v))))

(defn job-touch
  "Update updated_at field"
  [this]
  (.set this :updated_at (to-long (now))))

(defn job-removeState
  "Clear current state"
  [this]
  (let [{:keys [id state type]} (.get this)]
    (r/with-conn
      (r/command :zrem "q:jobs" id)
      (r/command :zrem (str "q:jobs:" state) id)
      (r/command :zrem (str "q:jobs:" type ":" state) id))))

(defn job-state
  "Set current state"
  [this state]
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

(defn job-complete [this]
  (.set this :progress 100)
  (.state this :complete))

(defn job-failed [this]
  (.state this :failed))

(defn job-inactive [this]
  (.state this :inactive))

(defn job-active [this]
  (.state this :active))

(defn job-progress
  "Get or update progress value"
  ([this]
    (.get this :progress))
  ([this complete total]
    (->>  (/ complete total)
          (* 100)
          float
          (min 100)
          (.set this :progress))
    (.touch this)))

(defn job-log
  "Log a string"
  [this s]
  (let [{:keys [id]} (.get this)]
    (r/with-conn
      (r/command :rpush (str "q:job:" id ":log") s)))
  (.touch this))

(defn job-priority
  "Set or get the priority level"
  ([this]
    (.get this :priority))
  ([this level]
    (set this :priority (or (priorities level) level))))

(defn job-error
  "Set or get the job's failure err"
  ([this]
    (.get this :error))
  ([this err]
    (let [msg (safely (.getMessage err))
          sum (safely (get-stack-trace err))]
      (.set this :error (or msg err))
      (.log this (or sum "")))
    (.set this :failed_at (to-long (now)))))

(defn job-attempt
  "Increment attemps, returns true if any attemps left"
  [this]
  (let [{:keys [id]}    (.get this)
        -key            (str "q:job:" id)]
    (.touch this)
    (->>  (r/with-conn
            (r/command :hsetnx  -key :max_attempts 1)
            (r/command :hget    -key :max_attempts  )
            (r/command :hincrby -key :attempts     1))
          (drop 1)
          (map (comp read-string str))
          (apply -)
          pos?)))
