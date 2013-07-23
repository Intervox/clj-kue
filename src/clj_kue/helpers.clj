(ns clj-kue.helpers
  (:require [clojure.string     :as string  ]
            [clj-progress.core  :as progress])
  (:use clj-kue.core))

(defn- job-buffer-write [^clj_kue.job.IKueJob job buffer flush?]
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

(defn- job-buffer-log [^clj_kue.job.IKueJob job buffer s]
  (dosync
    (alter buffer str s))
  (job-buffer-write job buffer false))

(defn job-writer [^clj_kue.job.IKueJob job]
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
                          (String. ^chars chrs ^Integer offs ^Integer len))))
              ([thing]
                (-> thing str log))))))


(defn progress-handler [^clj_kue.job.IKueJob job]
  { :init   (fn [{:keys [ttl header]}]
              (.log job header)
              (.progress job 0 ttl))
    :tick   (fn [{:keys [ttl done]}]
              (.progress job done ttl))})

(defmacro with-kue-job [^clj_kue.job.IKueJob job & body]
  `(let [job# ~job]
    (progress/with-progress-handler
      (progress-handler job#)
      (binding [*out* (job-writer job#)] ~@body))))

(defmacro kue-handler [params & body]
  `(fn [~(vary-meta 'job assoc :tag 'clj_kue.job.IKueJob)]
    (let [~params (.get ~'job :data)]
      (with-kue-job ~'job
        ~@body))))

(defmacro defhandler [name params & body]
  `(def ~name (kue-handler ~params ~@body)))

(defmacro kue-worker [type params & body]
  `(process ~type (kue-handler ~params ~@body)))
