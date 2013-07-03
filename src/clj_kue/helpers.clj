(ns clj-kue.helpers
  (:require [clojure.string     :as string  ]
            [clj-progress.core  :as progress])
  (:use clj-kue.core))

(defn- job-buffer-write [job buffer flush?]
  (dosync
    (let [s         (ensure buffer)
          coll      (string/split-lines s)
          blank?    (empty? s)
          complete? (= (last s) \newline)
          [logs b]  (if (or complete? flush?)
                        [coll ""]
                        [(butlast coll) (last coll)])]
      (when-not blank?
        (ref-set buffer b)
        (doseq [l logs]
          (.log job l))))))

(defn- job-buffer-log [job buffer s]
  (dosync
    (alter buffer str s))
  (job-buffer-write job buffer false))

(defn job-writer [job]
  (let [buffer  (ref "")
        flush*  (partial job-buffer-write job buffer true)
        log     (partial job-buffer-log job buffer)]
    (proxy  [java.io.Writer] []
            (close [])
            (flush []
              (flush*))
            (write
              ([chrs offs len]
                (log  (if (string? chrs)
                          (subs chrs offs (+ offs len))
                          (String. ^chars chrs offs len))))
              ([thing]
                (-> thing str log))))))


(defn progress-handler [job]
  { :init   (fn [{:keys [ttl header]}]
              (.log job header)
              (.progress job 0 ttl))
    :tick   (fn [{:keys [ttl done]}]
              (.progress job done ttl))})

(defmacro with-kue-job [job & body]
  `(let [job# ~job]
    (progress/with-progress-handler
      (progress-handler job#)
      (binding [*out* (job-writer job#)] ~@body))))

(defmacro kue-handler [params & body]
  `(fn [~'job]
    (let [~params (.get ~'job :data)]
      (with-kue-job ~'job
        ~@body))))

(defmacro defhandler [name params & body]
  `(def ~name (kue-handler ~params ~@body)))

(defmacro kue-worker [type params & body]
  `(process ~type (kue-handler ~params ~@body)))
