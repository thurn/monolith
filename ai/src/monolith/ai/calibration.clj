(ns monolith.ai.calibration
  "Run-calculator calibration: at the start of every real run, the calculator's predicted chance
  of reaching the server (from the Runner's observation) is logged next to whether the run was
  actually successful."
  (:require
   [monolith.ai.engine :as engine]
   [monolith.ai.harness :as h]
   [monolith.ai.knowledge.servers :as srv]
   [monolith.ai.observe :as observe]
   [monolith.ai.tourney :as tourney]))

(defn collect-game
  "Plays one game and returns [{:p predicted :success bool :server k :n-ice n}] for each run."
  [{:keys [corp runner seed corp-deck runner-deck] :or {corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner}}]
  (let [runs (transient [])
        cur (volatile! nil)
        decklist (engine/decklist corp-deck)]
    (binding [h/*on-step*
              (fn [g _ _]
                (let [s @(:state g)
                      run (:run s)]
                  (cond
                    (and run (nil? @cur))
                    (let [o (observe/observe s :runner)
                          k (first (:server run))
                          ev (srv/runner-run-eval o k {:corp-decklist decklist :value 1.0})]
                      (vreset! cur {:p (:p ev) :server k :n-ice (count (srv/ices o k)) :success false
                                    :id (:run-id run)}))
                    (and run @cur (:successful run)) (vswap! cur assoc :success true)
                    (and (nil? run) @cur) (do (conj! runs (dissoc @cur :id)) (vreset! cur nil)))))]
      (tourney/play-one {:corp corp :runner runner :seed seed :corp-deck corp-deck :runner-deck runner-deck :budget-ms 250}))
    (persistent! runs)))

(defn report
  "Buckets predictions; returns rows {:bucket [lo hi] :n :predicted :actual}."
  [rows]
  (for [[lo hi] [[0 0.01] [0.01 0.2] [0.2 0.4] [0.4 0.6] [0.6 0.8] [0.8 0.99] [0.99 1.01]]
        :let [xs (filter #(and (>= (:p %) lo) (< (:p %) hi)) rows)]
        :when (seq xs)]
    {:bucket [lo hi] :n (count xs)
     :predicted (/ (reduce + (map :p xs)) (count xs))
     :actual (/ (count (filter :success xs)) (double (count xs)))}))
