clj-kue
=======

Clojure client for [Kue](http://learnboost.github.io/kue/) build on top of [Carmine](https://github.com/ptaoussanis/carmine).

Compatible with `Kue 0.6.x.`, tested with `Kue 0.6.2.`.

### Main functionality

 * Fully-functional Kue jobs.
 * Parallel workers for jobs processing.

## Installation

You can install `clj-kue` using [clojars repository](https://clojars.org/intervox/clj-kue).

With Leiningen:

```Clojure
[intervox/clj-kue "0.2.1"]
```

With Maven:

```xml
<dependency>
  <groupId>intervox</groupId>
  <artifactId>clj-kue</artifactId>
  <version>0.2.1</version>
</dependency>
```

## Usage

### Redis Connection Settings

`clj-kue` is build on top of [Carmine](https://github.com/ptaoussanis/carmine).

To create connection pool for your workers use `connect!` function from `clj-kue.redis` namespace:

```Clojure
(require '[clj-kue.redis :as redis])

(redis/connect!)
```

You can also specify connection spec (e.g. host, port, db, timeout, uri) and connections pool options:

```Clojure
(def spec-opts {:db 1})
(def pool-opts {:max-total 20})

(redis/connect! spec-opts pool-opts)
```

For the full list of avaliable connections pool options see [GenericKeyedObjectPool documentation](http://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool/impl/GenericKeyedObjectPool.html).

To alter connection spec or connections pool options you can use one of the following functions:

```Clojure
(redis/set-connection-pool! conn-pool)
```

```Clojure
(redis/set-connection-spec! conn-spec)
```

```Clojure
(redis/set-connection! {:pool conn-pool :spec conn-spec})
```

If you're using [Carmine](https://github.com/ptaoussanis/carmine) prior to `2.0.0` in your project then you shoul consider using [the previous version of `clj-kue`](https://github.com/Intervox/clj-kue/tree/v0.1.x#installation).

### Workers

To create a worker, you use `Worker` function from `clj-kue.worker` namespace:

```Clojure
(use '[clj-kue.worker :only [Worker]])

(def worker (Worker "test" your-handler))
(.start worker)
```

You can specify any of worker options during its creation:

```Clojure
(def worker (Worker "test" your-handler :maxidle 1000))
```

Right now workers support the following options:

 * `maxidle` maximum timeout to wait for a new job in seconds (default `nil`). `0`, `nil` or any `false` value will result in an infinite timeout.

You can also use `get` and `set` methods to access and update workers options, including `type` and `handler` options:

```Clojure
(.set worker :maxidle 1000)
(.set worker :handler your-new-handler)
```

Workers use `ref`s to store their state allowing you to perform "hot" update of their options. For example, you can set a new jobs handler without stopping the worker. You can also set a new `type` of jobs to process, but it'll take effect only on the next processing step.

Each worker have `step`, `start` and `stop` methods.

`step` methods reads single job from `Kue` and processes it with the specified handler. If there is no jobs in the queue `step` waits for one with maximum timeout of `maxidle` seconds.

`start` methods starts invoking of the `step` method in an infinite loop in the separate thread.

`stop` method performs graceful shutdown of the `worker`. Calling `stop` will close the processing thread, but not before the next `step` of processing, because there is no way to close the thread while it's waiting for a new job. By default `stop` will block the execution untill the worker is stopped. But you can specify maximum `timeout` in miliseconds to wait. `nil` or negative value will result in infinite `timeout`. You can pass zero `timeout` if you don't want to wait at all. Consider that `stop` uses `interuptions` to shutdown threads after `timeout` and may interrupt processing of the job itself.

### Creating job handler

Job handler is an ordinary function taking the current job as its single argument.

Since workers ignore the output of the function, you shoud use `job` methods to pass processing results back to `Kue`. For the full list see [IKueJob protocol](https://github.com/Intervox/clj-kue/blob/master/src/clj_kue/job.clj#L16).

### Creating parallel cluster of workers

Instead of using `Worker` function from `clj-kue.worker` namespace you can spawn workers with `process` function from `clj-kue.core` namespace. The differences are that `process` function allows you to spawn multiple workers, and that it starts each worker immidiately:

```Clojure
(use 'clj-kue.core)

; Starts 3 workers
(process "test" 3 your-handler)
```

### Exception handling

Workers automatically handles all exeptions in jobs handlers. So, if any exception is thrown in job handler, worker logs this exception and treat the job as failed.

### Example

To handle the following job

```js
var kue = require('./index.js')
  , jobs = kue.createQueue();

jobs.create('test', {
    title: 'Sample job with progress and log'
  , step: 1000
  , ticks: 100
  , log: 'Hello world'
}).save(function(){
  process.exit();
});
```

we spawn a single worker, reporting its progress and logging its results.

```clojure
(process "test"
  (fn [job]
    (let [{:keys [step ticks log]} (.get job :data)]
      (dotimes [i ticks]
        (Thread/sleep step)
        (.progress job i ticks))
      (.log job log))))
```

### Better alternative to `clj.core/process`

Instead of using `clj.core/process` function to spawn workers you can use helpers from `clj-kue.helpers` namespace.

Available helpers macros:

 * `with-kue-job` wraps the execution of the job
 * `kue-handler` creates a handler function with specified bindings, wrapped with `with-kue-job`
 * `defhandler` also defines it
 * `kue-worker` spawns single worker woth specified `kue-handler`

Advantages of wrapping your handler with `with-kue-job`:

 * It catches any console output and sends it to `kue` using job's `log` metod.
 * If you're using [clj-progress](https://github.com/Intervox/clj-progress), it wraps it with special `progress-handler` and sends it to `kue` using job's `progress` metod.
 * Helpers are safe from reflections.

The previous example may be rewriten using `clj-kue.helpers` namespace:

```Clojure
(use '[clj-kue.helpers :only [kue-worker]])
(use 'clj-progress.core)

(kue-worker :test {:keys [step ticks log]}
  (init ticks)
  (dotimes [i ticks]
    (Thread/sleep step)
    (tick))
  (println log)
  (done))
```

Or, if you want to spawn multiple workers:

```Clojure
(use 'clj-kue.core)
(use '[clj-kue.helpers :only [defhandler]])
(use 'clj-progress.core)

(defhandler my-handler {:keys [step ticks log]}
  (init ticks)
  (dotimes [i ticks]
    (Thread/sleep step)
    (tick))
  (println log)
  (done))

(process :test 5 my-handler)
```

## License

Copyright © 2013-2014 Leonid Beschastny

Distributed under the Eclipse Public License, the same as Clojure.
