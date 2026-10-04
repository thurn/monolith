(ns monolith.ai.agents.champion
  "The current best agent (R2/R3 hybrid): S3's turn planner with side-specific evaluator weights
  tuned by ES against S1, anchored on S1 (deviate only when the plan beats S1's best line by
  :s1-margin), and its top lines re-ranked by S1 rollouts through the opponent's next turn.
  Prompts, runs and encounters are played by S1 (with ES-tuned S1 weights)."
  (:require
   [monolith.ai.agents.heuristic :as s1]
   [monolith.ai.agents.planner :as planner]))

(def eval-weights
  "Evaluator multipliers per side (research/rounds/R2/es-s3-*.jsonl)."
  {:corp (:eval s1/default-weights)
   :runner (:eval s1/default-weights)})

(defn make
  ([] (make {}))
  ([{:keys [side s1-margin rerank] :or {s1-margin 2.0 rerank 3} :as opts}]
   (planner/make (merge {:beam 6 :max-apps 2500}
                        (dissoc opts :side)
                        {:side side :s1-margin s1-margin :rerank rerank
                         :weights {:eval (eval-weights (or side :corp))}}))))
