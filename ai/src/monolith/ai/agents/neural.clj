(ns monolith.ai.agents.neural
  "S4: learned policy/value network (search-based use). v1 = S3's planner with an imitation-trained
  policy prior pruning each beam node to its top-k actions; the value head is only blended into
  the leaf score when :value-weight is set (it must win its own A/B first).
  `make-policy` is the search-free reference: the imitation policy alone."
  (:require
   [monolith.ai.agents.heuristic :as s1]
   [monolith.ai.agents.planner :as planner]
   [monolith.ai.nn :as nn]
   [monolith.ai.selfplay :as selfplay]
   [monolith.ai.sim :as sim]))

(def default-net "../research/rounds/R1/s4-net")

(defonce nets (atom {}))
(defn net [dir] (or (@nets dir) (let [n (nn/load-net dir)] (swap! nets assoc dir n) n)))

(defn prior-filter
  "Keeps the top-k actions by prior (plus any with probability >= floor)."
  [the-net k floor decks sm d acts]
  (if (<= (count acts) k)
    acts
    (let [o (sim/obs sm (:side d))
          ^doubles p (:probs (nn/evaluate the-net o (:side d) d acts decks))
          ranked (sort-by (fn [i] (- (aget p i))) (range (count acts)))
          keep (set (concat (take k ranked) (filter #(>= (aget p %) floor) ranked)))]
      (keep-indexed (fn [i a] (when (keep i) a)) acts))))

(defn value-term [the-net side w decks sm s]
  (let [d {:kind :turn :side side}
        v (:value (nn/evaluate the-net (sim/obs sm side) side d [] decks))]
    (* w v)))

(defn make
  ([] (make {}))
  ([{:keys [side net-dir topk floor value-weight] :or {topk 4 floor 0.15} :as opts}]
   (let [n (net (or net-dir default-net))
         p (planner/make opts)]
     (assoc p
            :filter-acts (fn [decks sm d acts] (prior-filter n topk floor decks sm d acts))
            :value-fn (when value-weight (fn [decks sm s] (value-term n side value-weight decks sm s)))))))

(defn make-policy
  ([] (make-policy {}))
  ([{:keys [net-dir]}]
   (selfplay/->NetAgent (atom (net (or net-dir default-net))) :argmax 0.0 nil 0.0)))
