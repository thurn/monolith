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
                          opts {:corp-decklist decklist :ap-value 7.0 :w-damage 2.0 :hand (count (get-in o [:runner :hand]))}
                          ev (srv/runner-run-eval o k (assoc opts :value (srv/content-value o k opts)))]
                      (vreset! cur {:p (:p ev) :server k :n-ice (count (srv/ices o k)) :success false :how (:how ev)
                                    :ice (mapv (juxt :title :rezzed) (get-in s [:corp :servers k :ices]))
                                    :credits (get-in s [:runner :credit]) :corp-credits (get-in s [:corp :credit])
                                    :breakers (mapv :title (get-in s [:runner :rig :program]))
                                    :id (:run-id run)
                                    :succ0 (count (get-in s [:runner :register :successful-run]))}))
                    (and (nil? run) @cur)
                    (let [succ (> (count (get-in s [:runner :register :successful-run])) (:succ0 @cur))]
                      (conj! runs (dissoc (assoc @cur :success succ) :id :succ0))
                      (vreset! cur nil)))))]
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
