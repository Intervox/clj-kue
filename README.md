clj-kue
=======

Clojure client for [Kue](http://learnboost.github.io/kue/).

### Main functionality

 * Fulli-functional Kue jobs.
 * Parallel workers for jobs processing.

## Installing

You can install `clj-kue` using [clojars repository](https://clojars.org/intervox/clj-kue).

With Leiningen:

```Clojure
[intervox/clj-kue "0.1.0"]
```

With Maven:

```xml
<dependency>
  <groupId>intervox</groupId>
  <artifactId>clj-kue</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Usage

### Workers

To create a worker, you use `Worker` function from `clj-kue.worker` namespace:

```Clojure
(use '[clj-kue.worker :only [Worker]])

(def worker (Worker "test" your-handler))
(.start worker)
```

Each worker have `step`, `start` and `stop` methods.

`step` methods reads single job from `Kue` and processes it with the specified handler.

`start` methods starts invoking of the `step` method in an infinite loop in the separate thread.

`stop` method closes processing thread. If no `timeout` is specified, it terminates current `step` immediately. If `timeout` is specified, it waits `timeout` miliseconds for `step` to complete. Since `step` includes fetching the current job from the `Kue`, it won't be terminated immediately when the queue is empty.

### Creating job handler

Job handler is an ordinary function taking the current job as its argument.

Since workers ignore the output of the function, you shoud use `job` methods to pass processing results back to `Kue`. For the full list see [IKueJob protocol](https://github.com/Intervox/clj-kue/blob/master/src/clj_kue/job.clj#L16).

### Creating parallel cluster of workers

Instead of using `Worker` function from `clj-kue.worker` namespace you can spawn workers with `process` function from `clj-kue.core` namespace. The differences are that `process` function allows you to spawn multiple workers, and that it starts each worker immidiately:

```Clojure
(use 'clj-kue.core)

; Starts 3 workers
(process "test" 3 your-handler)
```

### Example

To handle the following job

```java-script
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

## License

Copyright © 2013 Leonid Beschastny

Distributed under the Eclipse Public License, the same as Clojure.
