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

(declare make*)

(defn make
  ([] (make {}))
  ;; :w overrides top-level weights (e.g. {:potential-breakers true}); :eval overrides evaluator weights
  ;; :corp-opts / :runner-opts are merged in for that side only (e.g. a Runner-only value model)
  ([{:keys [side corp-opts runner-opts] :as opts0}]
   (let [opts (merge (dissoc opts0 :corp-opts :runner-opts) (if (= side :runner) runner-opts corp-opts))]
     (make* opts))))

(defn- make*
  ([{:keys [side s1-margin rerank w eval] :or {s1-margin 2.0 rerank 3} :as opts}]
   ;; budget-factor 200: the deadline (budget-ms x 200) practically never binds, so the 2,500
   ;; application cap does and games replay deterministically on any machine
   (planner/make (merge {:beam 6 :max-apps 2500 :budget-factor 200}
                        (dissoc opts :side :w :eval)
                        {:side side :s1-margin s1-margin :rerank rerank
                         :weights (merge w {:eval (merge (eval-weights (or side :corp)) eval)})}))))
