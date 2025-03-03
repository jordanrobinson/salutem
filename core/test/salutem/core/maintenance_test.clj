(ns salutem.core.maintenance-test
  (:require
   [clojure.test :refer :all]
   [clojure.core.async :as async]

   [tick.alpha.api :as t]

   [cartus.test :as cartus-test]
   [cartus.null :as cartus-null]

   [salutem.core.time :as time]
   [salutem.core.checks :as checks]
   [salutem.core.results :as results]
   [salutem.core.maintenance :as maintenance]
   [salutem.core.registry :as registry]))

(defn <!!-or-timeout
  ([chan]
   (<!!-or-timeout chan (t/new-duration 100 :millis)))
  ([chan timeout]
   (async/alt!!
     chan ([v] v)
     (async/timeout (t/millis timeout))
     (throw (ex-info "Timed out waiting on channel."
              {:channel chan
               :timeout (t/millis timeout)})))))

(deftest maintainer-logs-event-on-start
  (let [logger (cartus-test/logger)
        dependencies {:logger logger}
        context {:some "context"}
        interval (time/duration 50 :millis)

        registry (registry/empty-registry)
        registry-store (atom registry)

        trigger-channel (async/chan 10)
        shutdown-channel
        (maintenance/maintainer dependencies
          registry-store context interval trigger-channel)]
    (is (logged? logger
          {:context {:interval #time/duration "PT0.05S"}
           :level   :info
           :type    :salutem.core.maintenance/maintainer.starting}))

    (async/close! shutdown-channel)))

(deftest maintainer-puts-trigger-on-trigger-channel-every-interval
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some "context"}
        interval (time/duration 50 :millis)

        check (checks/background-check :thing
                (fn [_ result-cb] (result-cb (results/healthy))))

        registry (-> (registry/empty-registry)
                   (registry/with-check check))

        registry-store (atom registry)

        trigger-channel (async/chan 10)
        shutdown-channel
        (maintenance/maintainer dependencies
          registry-store context interval trigger-channel)]

    (async/<!! (async/timeout 150))

    (let [triggers
          (<!!-or-timeout (async/into [] (async/take 3 trigger-channel)))]
      (is (= [{:trigger-id 1
               :registry   registry
               :context    context}
              {:trigger-id 2
               :registry   registry
               :context    context}
              {:trigger-id 3
               :registry   registry
               :context    context}]
            triggers)))

    (async/close! shutdown-channel)))

(deftest maintainer-logs-event-every-interval-when-logger-in-context
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}
        interval (time/duration 50 :millis)

        registry (registry/empty-registry)
        registry-store (atom registry)

        trigger-channel (async/chan 10)
        shutdown-channel
        (t/with-clock (t/clock "2020-07-23T21:41:12.283Z")
          (maintenance/maintainer dependencies
            registry-store context interval trigger-channel))]

    (async/<!! (async/timeout 120))

    (is (logged? test-logger
          {:context {:trigger-id 1}
           :level   :info
           :type    :salutem.core.maintenance/maintainer.triggering}
          {:context {:trigger-id 2}
           :level   :info
           :type    :salutem.core.maintenance/maintainer.triggering}))

    (async/close! shutdown-channel)))

(deftest maintainer-closes-trigger-channel-when-shutdown-channel-closed
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some "context"}
        interval (time/duration 200 :millis)

        check (checks/background-check :thing
                (fn [_ result-cb] (result-cb (results/healthy))))

        registry (-> (registry/empty-registry)
                   (registry/with-check check))

        registry-store (atom registry)

        trigger-channel (async/chan 10)
        shutdown-channel
        (maintenance/maintainer dependencies
          registry-store context interval trigger-channel)]

    (async/close! shutdown-channel)

    (is (nil? (<!!-or-timeout trigger-channel)))))

(deftest maintainer-logs-event-on-shutdown-when-logger-in-context
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}
        interval (time/duration 200 :millis)

        registry (registry/empty-registry)
        registry-store (atom registry)

        trigger-channel (async/chan 10)
        shutdown-channel
        (t/with-clock (t/clock "2020-07-23T21:41:12.283Z")
          (maintenance/maintainer dependencies
            registry-store context interval trigger-channel))]

    (async/close! shutdown-channel)

    (async/<!! (async/timeout 50))

    (is (logged? test-logger
          {:context {:triggers-sent 0}
           :level   :info
           :type    :salutem.core.maintenance/maintainer.stopped}))))

(deftest refresher-logs-event-on-start
  (let [logger (cartus-test/logger)
        dependencies {:logger logger}

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (maintenance/refresher dependencies
      trigger-channel evaluation-channel)

    (is (logged? logger
          {:context {}
           :level   :info
           :type    :salutem.core.maintenance/refresher.starting}))

    (async/close! trigger-channel)))

(deftest refresher-logs-event-on-receipt-of-trigger
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}
        trigger-id 1

        registry
        (registry/empty-registry)

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (t/with-clock (t/clock "2020-07-23T21:41:12.283Z")
      (maintenance/refresher dependencies
        trigger-channel evaluation-channel))

    (async/put! trigger-channel
      {:trigger-id trigger-id
       :registry   registry
       :context    context})

    (async/<!! (async/timeout 50))

    (is (logged? test-logger
          {:context {:trigger-id 1}
           :level   :info
           :type    :salutem.core.maintenance/refresher.triggered}))

    (async/close! trigger-channel)))

(deftest refresher-puts-single-outdated-check-to-evaluation-channel
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some "context"}
        trigger-id 1

        check
        (checks/background-check :thing
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 30 :seconds)})
        outdated-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 35 :seconds))})

        registry
        (-> (registry/empty-registry)
          (registry/with-check check)
          (registry/with-cached-result check outdated-result))

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (maintenance/refresher dependencies
      trigger-channel evaluation-channel)

    (async/put! trigger-channel
      {:trigger-id trigger-id
       :registry   registry
       :context    context})

    (let [evaluation (<!!-or-timeout evaluation-channel)]
      (is (= (:trigger-id evaluation) trigger-id))
      (is (= (:check evaluation) check))
      (is (= (:context evaluation) context)))

    (async/close! trigger-channel)))

(deftest refresher-puts-many-outdated-checks-to-evaluation-channel
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some " context "}
        trigger-id 1

        check-1
        (checks/background-check :thing-1
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 30 :seconds)})
        check-2
        (checks/background-check :thing-2
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 1 :minutes)})
        check-3
        (checks/background-check :thing-2
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 45 :seconds)})

        check-1-outdated-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 35 :seconds))})
        check-2-current-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 40 :seconds))})
        check-3-outdated-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 55 :seconds))})

        registry
        (-> (registry/empty-registry)
          (registry/with-check check-1)
          (registry/with-check check-2)
          (registry/with-check check-3)
          (registry/with-cached-result check-1 check-1-outdated-result)
          (registry/with-cached-result check-2 check-2-current-result)
          (registry/with-cached-result check-3 check-3-outdated-result))

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (maintenance/refresher dependencies
      trigger-channel evaluation-channel)

    (async/put! trigger-channel
      {:trigger-id trigger-id
       :registry   registry
       :context    context})
    (async/close! trigger-channel)

    (let [evaluations (<!!-or-timeout (async/into #{} evaluation-channel))]
      (is (= #{{:trigger-id trigger-id :check check-1 :context context}
               {:trigger-id trigger-id :check check-3 :context context}}
            evaluations)))))

(deftest refresher-logs-event-on-triggering-evaluation-for-each-check
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}
        trigger-id 1

        check-1
        (checks/background-check :thing-1
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 30 :seconds)})
        check-2
        (checks/background-check :thing-2
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 1 :minutes)})
        check-3
        (checks/background-check :thing-3
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 45 :seconds)})

        check-1-outdated-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 35 :seconds))})
        check-2-current-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 40 :seconds))})
        check-3-outdated-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 55 :seconds))})

        registry
        (-> (registry/empty-registry)
          (registry/with-check check-1)
          (registry/with-check check-2)
          (registry/with-check check-3)
          (registry/with-cached-result check-1 check-1-outdated-result)
          (registry/with-cached-result check-2 check-2-current-result)
          (registry/with-cached-result check-3 check-3-outdated-result))

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (maintenance/refresher dependencies
      trigger-channel evaluation-channel)

    (async/put! trigger-channel
      {:trigger-id trigger-id
       :registry   registry
       :context    context})
    (async/close! trigger-channel)

    (<!!-or-timeout (async/into #{} evaluation-channel))

    (is (logged? test-logger #{:in-any-order}
          {:context {:trigger-id 1
                     :check-name (:name check-1)}
           :level   :info
           :type    :salutem.core.maintenance/refresher.evaluating}
          {:context {:trigger-id 1
                     :check-name (:name check-3)}
           :level   :info
           :type    :salutem.core.maintenance/refresher.evaluating}))))

(deftest refresher-puts-to-returned-evaluation-channel-when-none-provided
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some "context"}

        check
        (checks/background-check :thing
          (fn [_ result-cb] (result-cb (results/healthy)))
          {:ttl (time/duration 30 :seconds)})
        outdated-result
        (results/healthy
          {:evaluated-at (t/- (t/now) (t/new-duration 35 :seconds))})

        registry
        (-> (registry/empty-registry)
          (registry/with-check check)
          (registry/with-cached-result check outdated-result))

        trigger-channel (async/chan)
        evaluation-channel (maintenance/refresher dependencies trigger-channel)]
    (async/put! trigger-channel {:registry registry :context context})

    (let [evaluation (<!!-or-timeout evaluation-channel)]
      (is (= (:check evaluation) check))
      (is (= (:context evaluation) context)))

    (async/close! trigger-channel)))

(deftest refresher-closes-evaluation-channel-when-trigger-channel-closed
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (maintenance/refresher dependencies
      trigger-channel evaluation-channel)

    (async/close! trigger-channel)

    (is (nil? (<!!-or-timeout evaluation-channel)))))

(deftest refresher-logs-event-on-shutdown
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        trigger-channel (async/chan)
        evaluation-channel (async/chan)]
    (maintenance/refresher dependencies
      trigger-channel evaluation-channel)

    (async/close! trigger-channel)

    (async/<!! (async/timeout 50))

    (is (logged? test-logger
          {:context {}
           :level   :info
           :type    :salutem.core.maintenance/refresher.stopped}))))

(deftest evaluator-logs-event-on-start
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (is (logged? test-logger
          {:context {}
           :level   :info
           :type    :salutem.core.maintenance/evaluator.starting}))

    (async/close! evaluation-channel)))

(deftest evaluator-logs-event-on-evaluating-check
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}

        check (checks/background-check :thing
                (fn [_ result-cb]
                  (result-cb (results/healthy))))

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check
       :context    context})

    (is (logged? test-logger
          {:context {:trigger-id trigger-id
                     :check-name :thing}
           :level   :info
           :type    :salutem.core.maintenance/evaluator.evaluating}))

    (async/close! evaluation-channel)))

(deftest evaluator-logs-event-on-start-of-attempt
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}

        check (checks/background-check :thing
                (fn [context result-cb]
                  (result-cb
                    (results/unhealthy
                      (merge context
                        {:latency (t/new-duration 1 :seconds)})))))

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check
       :context    context})

    (<!!-or-timeout result-channel)

    (is (logged? test-logger
          {:context {:trigger-id trigger-id
                     :check-name :thing}
           :level   :info
           :type    :salutem.core.checks/attempt.starting}))

    (async/close! evaluation-channel)))

(deftest evaluator-evaluates-single-check
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some "context"}

        check (checks/background-check :thing
                (fn [context result-cb]
                  (result-cb
                    (results/unhealthy
                      (merge context
                        {:latency (t/new-duration 1 :seconds)})))))

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check
       :context    context})

    (let [{:keys [result] :as result-message} (<!!-or-timeout result-channel)]
      (is (= (:trigger-id result-message) trigger-id))
      (is (results/unhealthy? result))
      (is (= (:latency result) (t/new-duration 1 :seconds)))
      (is (= (:some result) "context")))

    (async/close! evaluation-channel)))

(deftest evaluator-evaluates-multiple-checks
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some "context"}

        check-1 (checks/background-check :thing-1
                  (fn [context result-cb]
                    (result-cb
                      (results/unhealthy
                        (merge context
                          {:latency (t/new-duration 1 :seconds)})))))
        check-2 (checks/background-check :thing-2
                  (fn [context result-cb]
                    (result-cb
                      (results/healthy
                        (merge context
                          {:latency (t/new-duration 120 :millis)})))))
        check-3 (checks/background-check :thing-3
                  (fn [context result-cb]
                    (result-cb
                      (results/unhealthy
                        (merge context
                          {:latency (t/new-duration 2 :seconds)})))))

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check-1
       :context    context})
    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check-2
       :context    context})
    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check-3
       :context    context})

    (letfn [(find-result [results name]
              (->> results
                (filter #(= (get-in % [:check :name]) name))
                first
                :result))]
      (let [results (async/<!! (async/into [] (async/take 3 result-channel)))
            check-1-result (find-result results :thing-1)
            check-2-result (find-result results :thing-2)
            check-3-result (find-result results :thing-3)]
        (is (results/unhealthy? check-1-result))
        (is (= (:latency check-1-result) (t/new-duration 1 :seconds)))
        (is (= (:some check-1-result) "context"))

        (is (results/healthy? check-2-result))
        (is (= (:latency check-2-result) (t/new-duration 120 :millis)))
        (is (= (:some check-2-result) "context"))

        (is (results/unhealthy? check-3-result))
        (is (= (:latency check-3-result) (t/new-duration 2 :seconds)))
        (is (= (:some check-3-result) "context"))))

    (async/close! evaluation-channel)))

(deftest evaluator-logs-event-on-attempt-completion
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}
        context {:some "context"}

        result (results/unhealthy
                 (merge context
                   {:latency (t/new-duration 1 :seconds)}))
        check (checks/background-check :thing
                (fn [_ result-cb]
                  (result-cb result)))

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check
       :context    context})

    (<!!-or-timeout result-channel)

    (is (logged? test-logger
          {:context {:trigger-id trigger-id
                     :check-name :thing
                     :result     result}
           :level   :info
           :type    :salutem.core.checks/attempt.completed}))

    (async/close! evaluation-channel)))

(deftest evaluator-times-out-evaluation-when-check-takes-longer-than-timeout
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}

        check (checks/background-check :thing
                (fn [_ result-cb]
                  (future
                    (Thread/sleep 100)
                    (result-cb
                      (results/healthy))))
                {:timeout (time/duration 50 :millis)})

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check})

    (let [{:keys [result] :as result-message}
          (<!!-or-timeout result-channel
            (t/new-duration 200 :millis))]
      (is (= (:trigger-id result-message) trigger-id))
      (is (results/unhealthy? result)))

    (async/close! evaluation-channel)))

(deftest evaluator-logs-event-on-attempt-timeout
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        check (checks/background-check :thing
                (fn [_ result-cb]
                  (future
                    (Thread/sleep 100)
                    (result-cb
                      (results/healthy))))
                {:timeout (time/duration 50 :millis)})

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check})

    (<!!-or-timeout result-channel
      (t/new-duration 200 :millis))

    (is (logged? test-logger
          {:context {:trigger-id trigger-id
                     :check-name :thing}
           :level   :info
           :type    ::checks/attempt.timed-out}))

    (async/close! evaluation-channel)))

(deftest evaluator-evaluates-to-returned-result-channel-when-none-provided
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}
        context {:some " context "}

        check (checks/background-check :thing
                (fn [context result-cb]
                  (result-cb
                    (results/unhealthy
                      (merge context
                        {:latency (t/new-duration 1 :seconds)})))))

        trigger-id 1

        evaluation-channel (async/chan)
        result-channel (maintenance/evaluator dependencies
                         evaluation-channel)]

    (async/put! evaluation-channel
      {:trigger-id trigger-id
       :check      check
       :context    context})

    (let [{:keys [result] :as result-message} (<!!-or-timeout result-channel)]
      (is (= (:trigger-id result-message) trigger-id))
      (is (results/unhealthy? result))
      (is (= (:latency result) (t/new-duration 1 :seconds)))
      (is (= (:some result) " context ")))

    (async/close! evaluation-channel)))

(deftest evaluator-closes-result-channel-when-evaluation-channel-closed
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/close! evaluation-channel)

    (is (nil? (<!!-or-timeout result-channel)))))

(deftest evaluator-logs-event-on-shutdown
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        evaluation-channel (async/chan)
        result-channel (async/chan)]
    (maintenance/evaluator dependencies
      evaluation-channel result-channel)

    (async/close! evaluation-channel)

    (async/<!! (async/timeout 50))

    (is (logged? test-logger
          {:level :info
           :type  :salutem.core.maintenance/evaluator.stopped}))))

(deftest updater-logs-event-on-start
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        registry (registry/empty-registry)
        registry-store (atom registry)

        result-channel (async/chan)]
    (maintenance/updater dependencies
      registry-store result-channel)

    (is (logged? test-logger
          {:level :info
           :type  :salutem.core.maintenance/updater.starting}))

    (async/close! result-channel)))

(deftest updater-adds-single-result-to-registry-in-registry-store-atom
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}

        check (checks/background-check :thing
                (fn [_ result-cb] (result-cb (results/healthy))))
        result (results/healthy
                 {:latency (t/new-duration 267 :millis)})

        registry (-> (registry/empty-registry)
                   (registry/with-check check))
        registry-store (atom registry)

        trigger-id 1

        updated? (atom false)

        result-channel (async/chan)]
    (maintenance/updater dependencies
      registry-store result-channel)

    (add-watch registry-store :watcher
      (fn [_ _ _ _]
        (reset! updated? true)))

    (async/put! result-channel
      {:trigger-id trigger-id
       :check      check
       :result     result})

    (loop [attempts 1]
      (if @updated?
        (is (= @registry-store
              (registry/with-cached-result registry check result)))
        (do
          (async/<!! (async/timeout 25))
          (and (< attempts 5) (recur (inc attempts))))))

    (remove-watch registry-store :watcher)
    (async/close! result-channel)))

(deftest updater-adds-many-results-to-registry-in-registry-store-atom
  (let [logger (cartus-null/logger)
        dependencies {:logger logger}

        check-1 (checks/background-check :thing-1
                  (fn [_ result-cb] (result-cb (results/healthy))))
        check-2 (checks/background-check :thing-2
                  (fn [_ result-cb] (result-cb (results/healthy))))
        check-3 (checks/background-check :thing-3
                  (fn [_ result-cb] (result-cb (results/healthy))))

        result-1 (results/healthy
                   {:latency (t/new-duration 267 :millis)})
        result-2 (results/unhealthy
                   {:version " 1.2.3 "})
        result-3 (results/unhealthy
                   {:items 1432})

        registry (-> (registry/empty-registry)
                   (registry/with-check check-1)
                   (registry/with-check check-2)
                   (registry/with-check check-3))
        registry-store (atom registry)

        trigger-id 1

        updated-count (atom 0)

        result-channel (async/chan 10)]
    (maintenance/updater dependencies
      registry-store result-channel)

    (add-watch registry-store :watcher
      (fn [_ _ _ _]
        (swap! updated-count inc)))

    (async/put! result-channel
      {:trigger-id trigger-id
       :check      check-1
       :result     result-1})
    (async/put! result-channel
      {:trigger-id trigger-id
       :check      check-2
       :result     result-2})
    (async/put! result-channel
      {:trigger-id trigger-id
       :check      check-3
       :result     result-3})

    (loop [attempts 1]
      (if (= @updated-count 3)
        (is (= @registry-store
              (-> registry
                (registry/with-cached-result check-1 result-1)
                (registry/with-cached-result check-2 result-2)
                (registry/with-cached-result check-3 result-3))))
        (do
          (async/<!! (async/timeout 25))
          (and (< attempts 5) (recur (inc attempts))))))

    (remove-watch registry-store :watcher)
    (async/close! result-channel)))

(deftest updater-logs-event-on-adding-result-to-registry
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        check (checks/background-check :thing
                (fn [_ result-cb] (result-cb (results/healthy))))
        result (results/healthy
                 {:latency (t/new-duration 267 :millis)})

        registry (-> (registry/empty-registry)
                   (registry/with-check check))
        registry-store (atom registry)

        trigger-id 2

        result-channel (async/chan)]
    (maintenance/updater dependencies
      registry-store result-channel)

    (async/put! result-channel
      {:trigger-id trigger-id
       :check      check
       :result     result})

    (async/<!! (async/timeout 25))

    (is (logged? test-logger
          {:context {:trigger-id trigger-id
                     :check-name :thing
                     :result     result}
           :level   :info
           :type    :salutem.core.maintenance/updater.updating}))

    (async/close! result-channel)))

(deftest updater-logs-event-on-shutdown
  (let [test-logger (cartus-test/logger)
        dependencies {:logger test-logger}

        registry (registry/empty-registry)
        registry-store (atom registry)

        result-channel (async/chan)]
    (maintenance/updater dependencies
      registry-store result-channel)

    (async/close! result-channel)

    (async/<!! (async/timeout 50))

    (is (logged? test-logger
          {:context {}
           :level   :info
           :type    :salutem.core.maintenance/updater.stopped}))))

(deftest maintain-starts-pipeline-to-refresh-registry
  (let [check-count (atom 0)

        check (checks/background-check :thing
                (fn [context result-cb]
                  (swap! check-count inc)
                  (result-cb
                    (results/healthy
                      (merge (select-keys context [:some])
                        {:invocation-count @check-count}))))
                {:ttl (time/duration 25 :millis)})

        registry (-> (registry/empty-registry)
                   (registry/with-check check))

        registry-store (atom registry)

        context {:some " context "}
        interval (time/duration 50 :millis)

        maintenance-pipeline
        (maintenance/maintain registry-store
          {:context  context
           :interval interval})]

    (async/<!! (async/timeout 175))

    (letfn [(time-free [result]
              (dissoc result :evaluated-at))]
      (is (=
            (time-free (registry/find-cached-result @registry-store :thing))
            (time-free (results/healthy
                         (merge context
                           {:invocation-count 3}))))))

    (maintenance/shutdown maintenance-pipeline)))

(deftest maintain-starts-pipeline-to-call-callback-functions
  (let [callback-data (atom {})

        callback-fn (fn [x]
                      (reset! callback-data x))

        check (checks/background-check
                :thing
                (fn [_ result-cb]
                  (result-cb (results/healthy {:arbitrary-data "foo"}))))

        registry (-> (registry/empty-registry)
                   (registry/with-check check))

        interval (t/new-duration 50 :millis)

        maintenance-pipeline
        (maintenance/maintain (atom registry)
          {:interval interval
           :callback-fns [callback-fn]})]

    (async/<!! (async/timeout 75))

    (is (= (:name @callback-data) :thing))
    (is (= (:type @callback-data) :background))
    (is (= (:status @callback-data) :healthy))
    (is (= (:arbitrary-data @callback-data) "foo"))

    (maintenance/shutdown maintenance-pipeline)))

(deftest maintain-closes-all-channels
  (let [evaluation-channel (async/chan 10)
        trigger-channel (async/chan (async/sliding-buffer 1))
        result-channel (async/chan 10)
        notifier-result-channel (async/chan 10)
        updater-result-channel (async/chan 10)

        registry (registry/empty-registry)

        interval (t/new-duration 50 :millis)

        maintenance-pipeline
        (maintenance/maintain (atom registry)
          {:interval interval
           :evaluation-channel evaluation-channel
           :trigger-channel trigger-channel
           :notifier-result-channel notifier-result-channel
           :updater-result-channel updater-result-channel
           :result-channel result-channel})]

    (maintenance/shutdown maintenance-pipeline)

    (async/<!! (async/timeout 75))

    (is (= (<!!-or-timeout evaluation-channel) nil))
    (is (= (<!!-or-timeout trigger-channel) nil))
    (is (= (<!!-or-timeout result-channel) nil))
    (is (= (<!!-or-timeout updater-result-channel) nil))
    (is (= (<!!-or-timeout notifier-result-channel) nil))))
