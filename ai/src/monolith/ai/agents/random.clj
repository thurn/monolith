(ns monolith.ai.agents.random
  "Uniform over legal actions: the floor and the throughput benchmark."
  (:require [monolith.ai.harness :as h]))

(defrecord RandomAgent []
  h/Agent
  (choose [_ {:keys [actions ^java.util.Random rng]}]
    (.nextInt rng (count actions))))

(defn make ([] (->RandomAgent)) ([_opts] (->RandomAgent)))
