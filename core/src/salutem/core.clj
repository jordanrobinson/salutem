(ns salutem.core
  (:require
   [salutem.core.time :as time]
   [salutem.core.checks :as checks]
   [salutem.core.results :as results]
   [salutem.core.registry :as registry]
   [salutem.core.maintenance :as maintenance]))

; time
(def duration time/duration)

; results
(def result results/result)
(def healthy results/healthy)
(def unhealthy results/unhealthy)
(def healthy? results/healthy?)
(def unhealthy? results/unhealthy?)
(def outdated? results/outdated?)

; checks
(def background-check checks/background-check)
(def realtime-check checks/realtime-check)
(def background? checks/background?)
(def realtime? checks/realtime?)
(def evaluate checks/evaluate)

; registry
(def empty-registry registry/empty-registry)

(def with-check registry/with-check)
(def with-cached-result registry/with-cached-result)

(def find-check registry/find-check)
(def find-cached-result registry/find-cached-result)

(def check-names registry/check-names)

(def all-checks registry/all-checks)
(def outdated-checks registry/outdated-checks)

(def resolve-check registry/resolve-check)
(def resolve-checks registry/resolve-checks)

; maintenance
(def maintain maintenance/maintain)
(def shutdown maintenance/shutdown)
