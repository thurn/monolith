(ns monolith.ai.agents.planner
  "S3: the netrunner-rs turn planner on the Jinteki engine. At a click decision of its own turn it
  determinizes once, beam-searches action lines to the end of the turn (beam k plus the best line
  under each distinct first action), and plays the best line's first action, following the plan
  while the real legal actions still contain the next planned action. Prompts inside lines are
  resolved by S1 for both sides (a cheap opponent model). A run ends a line and is scored with
  the run calculator. Everything outside its own click decisions is delegated to S1."
  (:require
   [monolith.ai.agents.heuristic :as s1]
   [monolith.ai.harness :as h]
   [monolith.ai.knowledge.evaluator :as ev]
   [monolith.ai.moves :as moves]
   [monolith.ai.observe :as observe]
   [monolith.ai.sim :as sim]))

(defn- action-key [a]
  [(:command a) (get-in a [:args :card :cid]) (get-in a [:args :server]) (:label a)])

(defn- s1-choose
  "S1's choice for whoever must act in the sim."
  [sm d actions weights decks rng]
  (let [env {:side (:side d) :decision d :actions actions :obs (sim/obs sm (:side d))
             :weights weights :mem (atom {}) :decks decks :rng rng}
        [_ a] (s1/decide env)]
    (or a (first actions))))

(defn- run-utility
  "S1's run-calculator value of the run that has just started in the sim (Runner perspective)."
  [sm env-base]
  (let [o (sim/obs sm :runner)
        k (first (get-in o [:run :server]))]
    (if k
      (let [env (assoc env-base :obs o :side :runner)]
        (+ (s1/server-run-utility env k {}) (:click-value (:weights env-base))))
      0.0)))

(defn- advance!
  "After applying a line action, resolves prompts (S1, both sides) until `side` has its next click
  decision, the turn ends, a run starts, or the game ends. Returns {:status kw :n applications}."
  [sm side weights decks rng budget]
  (loop [n 0]
    (let [s (sim/snapshot sm)
          d (sim/decision sm)]
      (cond
        (or (nil? d) (moves/game-over? s)) {:status :game-over :n n}
        (> n budget) {:status :cutoff :n n}
        (and (= side :runner) (:run s)) {:status :run :n n}
        (and (= (:side d) side) (= :turn (:kind d))) {:status :open :n n}
        ;; out of clicks: let S1 take free end-of-turn actions (score, rez economy) inside the line
        (and (= (:side d) side) (= :end-turn (:kind d)))
        (let [acts (sim/legal sm d)
              a (s1-choose sm d acts weights decks rng)]
          (if (and a (not= :end-turn (:type a)) (sim/apply! sm a))
            (recur (inc n))
            {:status :end :n n}))
        (#{:start-turn :end-turn :turn} (:kind d)) {:status :end :n n}
        :else
        (let [acts (sim/legal sm d)]
          (if (empty? acts)
            {:status :stuck :n n}
            (let [a (s1-choose sm d acts weights decks rng)]
              (if (sim/apply! sm a) (recur (inc n)) {:status :stuck :n (inc n)}))))))))

(defn- signature [s side]
  (hash [(get-in s [side :click]) (get-in s [:corp :credit]) (get-in s [:runner :credit])
         (sort (map :cid (get-in s [side :hand])))
         (for [[k v] (get-in s [:corp :servers])] [k (map (juxt :cid :rezzed :advance-counter) (concat (:ices v) (:content v)))])
         (map :cid (concat (get-in s [:runner :rig :program]) (get-in s [:runner :rig :hardware]) (get-in s [:runner :rig :resource])))
         (get-in s [:corp :agenda-point]) (get-in s [:runner :agenda-point]) (boolean (:run s))]))

(defn plan
  "Beam search from the current (determinized) sim state. Returns {:line [actions] :score x :apps n}."
  [sm side {:keys [weights decks rng beam max-apps deadline filter-acts value-fn]}]
  (let [root (sim/snapshot sm)
        score (fn [status] (let [s (sim/snapshot sm)
                                 base (+ (ev/for-side s side weights) (if value-fn (value-fn sm s) 0.0))]
                             (if (= status :run)
                               (+ base (run-utility sm {:weights weights :decks decks :rng rng}))
                               base)))
        apps (volatile! 0)]
    (loop [frontier [{:line [] :snap root :score (score :open)}]
           finals []
           depth 0]
      (if (or (empty? frontier) (> depth 6) (> @apps max-apps) (> (System/currentTimeMillis) deadline))
        (let [all (concat finals (filter #(seq (:line %)) frontier))]
          (assoc (if (seq all) (apply max-key :score all) {:line [] :score 0.0}) :apps @apps))
        (let [children
              (vec
               (for [{:keys [line snap]} frontier
                     :let [_ (sim/restore! sm snap)
                           d (sim/decision sm)
                           acts (when (and d (= side (:side d)) (= :turn (:kind d))) (sim/legal sm d))
                           acts (if (and filter-acts (seq acts)) (filter-acts sm d acts) acts)]
                     a acts
                     :while (and (<= @apps max-apps) (<= (System/currentTimeMillis) deadline))
                     :let [_ (sim/restore! sm snap)
                           ok (sim/apply! sm a)
                           {:keys [status n]} (if ok (advance! sm side weights decks rng 60) {:status :bad :n 0})
                           _ (vswap! apps + 1 n)]
                     :when (not= status :bad)
                     :let [s (sim/snapshot sm)]]
                 {:line (conj line a) :snap s :score (score status) :status status :sig (signature s side)}))
              ;; transposition merge: same resulting position, keep the best-scoring line
              children (vals (reduce (fn [m c] (if (and (m (:sig c)) (>= (:score (m (:sig c))) (:score c))) m (assoc m (:sig c) c)))
                                     {} children))
              open (filter #(= :open (:status %)) children)
              done (remove #(= :open (:status %)) children)
              by-score (sort-by (comp - :score) open)
              ;; beam plus the best line under each distinct first action
              keep (distinct (concat (take beam by-score)
                                     (vals (reduce (fn [m c] (let [k (action-key (first (:line c)))]
                                                               (if (and (m k) (>= (:score (m k)) (:score c))) m (assoc m k c))))
                                                   {} by-score))))]
          (recur (vec keep) (into finals done) (inc depth)))))))

(defrecord Planner [side weights beam max-apps budget-factor plan-state filter-acts value-fn]
  h/Agent
  (choose [_ ctx]
    (let [{:keys [actions decision obs decks ^java.util.Random rng budget-ms]} ctx
          o @obs]
      (if (or (not= :turn (:kind decision)) (= 1 (count actions)))
        ;; delegate prompts, runs, encounters, turn starts/ends to S1
        (let [env (assoc ctx :obs o :weights weights :mem (atom {}))
              [_ a] (s1/decide env)]
          (or (first (keep-indexed (fn [i x] (when (identical? x a) i)) actions)) 0))
        (let [turn-key [(:turn o) (get-in o [side :click])]
              {:keys [line made-turn]} @plan-state
              nxt (first line)
              follow (when (and nxt (= made-turn (:turn o)))
                       (first (keep-indexed (fn [i x] (when (= (action-key x) (action-key nxt)) i)) actions)))]
          (if follow
            (do (swap! plan-state update :line rest) follow)
            (let [sm (sim/begin! @(:sim ctx) (.nextLong rng))
                  fresh-turn (not= made-turn (:turn o))
                  deadline (+ (System/currentTimeMillis) (long (* budget-ms (if fresh-turn budget-factor 2))))
                  result (try (plan sm side {:weights weights :decks decks :rng rng :beam beam
                                             :max-apps max-apps :deadline deadline
                                             :filter-acts (when filter-acts (partial filter-acts decks))
                                             :value-fn (when value-fn (partial value-fn decks))})
                              (finally (sim/end! sm)))
                  best (first (:line result))
                  idx (when best (first (keep-indexed (fn [i x] (when (= (action-key x) (action-key best)) i)) actions)))]
              (reset! plan-state {:line (rest (:line result)) :made-turn (:turn o) :score (:score result) :apps (:apps result)})
              (or idx
                  ;; plan's first action not legal in reality (should be rare): fall back to S1
                  (let [env (assoc ctx :obs o :weights weights :mem (atom {}))
                        [_ a] (s1/decide env)]
                    (or (first (keep-indexed (fn [i x] (when (identical? x a) i)) actions)) 0))))))))))

(defn make
  ([] (make {}))
  ([{:keys [side weights beam max-apps budget-factor] :or {beam 6 max-apps 2500 budget-factor 8}}]
   (->Planner side (merge s1/default-weights weights) beam max-apps budget-factor (atom {}) nil nil)))
