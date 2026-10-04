(ns monolith.ai.agents.planner
  "S3: the netrunner-rs turn planner on the Jinteki engine. At a click decision of its own turn it
  determinizes once, beam-searches action lines to the end of the turn (beam k plus the best line
  under each distinct first action), and plays the best line's first action, following the plan
  while the real legal actions still contain the next planned action. Prompts inside lines are
  resolved by S1 for both sides (a cheap opponent model). A run ends a line and is scored with
  the run calculator. Everything outside its own click decisions is delegated to S1."
  (:require
   [clojure.string :as str]
   [monolith.ai.agents.heuristic :as s1]
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.servers :as srv]
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
  [sm side weights decks rng budget sim-runs branch-prompts]
  (loop [n 0]
    (let [s (sim/snapshot sm)
          d (sim/decision sm)]
      (cond
        (or (nil? d) (moves/game-over? s)) {:status :game-over :n n}
        (> n budget) {:status :cutoff :n n}
        (and (= side :runner) (:run s) (not sim-runs)) {:status :run :n n}
        (and (= (:side d) side) (= :turn (:kind d))) {:status :open :n n}
        ;; own prompts with a few options are branch points of the search
        (and branch-prompts (= (:side d) side) (= :prompt (:kind d)) (= side (:active-player s))
             (<= 2 (count (sim/legal sm d)) 6))
        {:status :open :n n}
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

(defn sensible?
  "Prunes actions no line should contain: installing over the Corp's own agenda/asset (which trashes
  it) and rezzing an ambush (which only reveals it)."
  [s a]
  (let [title (get-in a [:args :card :title])
        typ (some-> title cards/ctype)]
    (not (or (and (= "play" (:command a)) (#{"Agenda" "Asset"} typ)
                  (let [server (get-in a [:args :server])]
                    (and server (str/starts-with? server "Server")
                         (some #(#{"Agenda" "Asset"} (:type %))
                               (get-in s [:corp :servers (srv/server-key server) :content])))))
             (and (= "rez" (:command a)) title (srv/trap-damage title 0))))))

(defn- signature [s side]
  (hash [(get-in s [side :click]) (get-in s [:corp :credit]) (get-in s [:runner :credit])
         (sort (map :cid (get-in s [side :hand])))
         (for [[k v] (get-in s [:corp :servers])] [k (map (juxt :cid :rezzed :advance-counter) (concat (:ices v) (:content v)))])
         (map :cid (concat (get-in s [:runner :rig :program]) (get-in s [:runner :rig :hardware]) (get-in s [:runner :rig :resource])))
         (get-in s [:corp :agenda-point]) (get-in s [:runner :agenda-point]) (boolean (:run s))]))

(defn- rollout-score
  "Plays the line's end state forward with S1 for both sides, then evaluates. turns 1: until
  `side`'s next turn begins (one opponent turn of foresight); turns 2: also through `side`'s
  next turn, until the opponent's following turn begins. Capped at max-steps actions."
  [sm side weights decks rng max-steps turns leaf-fn]
  (let [s0 (sim/snapshot sm)
        evalf (or leaf-fn (fn [s sd] (ev/for-side s sd weights)))
        stop-at (if (= 2 turns) 3 2)]
    (loop [i 0 prev (:active-player s0) changes 0]
      (let [s (sim/snapshot sm)
            d (sim/decision sm)
            active (:active-player s)
            changes (if (not= active prev) (inc changes) changes)]
        (if (or (nil? d) (moves/game-over? s) (>= i max-steps)
                (and (>= changes stop-at) (= :turn (:kind d))))
          (evalf s side)
          (let [acts (sim/legal sm d)]
            (if (or (empty? acts) (not (sim/apply! sm (if (= 1 (count acts)) (first acts) (s1-choose sm d acts weights decks rng)))))
              (evalf s side)
              (recur (inc i) active changes))))))))

(def ^:dynamic *debug* nil)

(defn plan
  "Beam search from the current (determinized) sim state. Returns {:line [actions] :score x :apps n}."
  [sm side {:keys [weights decks rng beam max-apps deadline filter-acts value-fn rerank sim-runs rerank-turns branch-prompts leaf-fn rerank-samples]}]
  (let [root (sim/snapshot sm)
        score (fn [status] (let [s (sim/snapshot sm)
                                 base (+ (if leaf-fn (leaf-fn s side) (ev/for-side s side weights)) (if value-fn (value-fn sm s) 0.0))]
                             (if (= status :run)
                               (+ base (run-utility sm {:weights weights :decks decks :rng rng}))
                               base)))
        apps (volatile! 0)]
    (loop [frontier [{:line [] :snap root :score (score :open)}]
           finals []
           depth 0]
      (if (or (empty? frontier) (> depth 6) (> @apps max-apps) (> (System/currentTimeMillis) deadline))
        (let [all (concat finals (filter #(seq (:line %)) frontier))
              top (take (or rerank 0) (sort-by (comp - :score) all))
              all (if (seq top)
                    (concat (for [c top]
                              ;; rerank scores dominate their beam scores; :rerank-samples averages
                              ;; several rollouts per line (each from the line's end state)
                              (let [n (max 1 (or rerank-samples 1))
                                    xs (for [_ (range n)]
                                         (do (sim/restore! sm (:snap c))
                                             (rollout-score sm side weights decks rng (* 400 (or rerank-turns 1)) (or rerank-turns 1) leaf-fn)))]
                                (assoc c :score (+ 10000.0 (/ (reduce + 0.0 xs) n)))))
                            all)
                    all)]
          (assoc (if (seq all) (apply max-key :score all) {:line [] :score 0.0})
                 :apps @apps
                 :by-first (reduce (fn [m c] (let [k (action-key (first (:line c)))]
                                               (assoc m k (max (get m k Double/NEGATIVE_INFINITY) (:score c)))))
                                   {} all)
                 :first-acts (into {} (for [c all] [(action-key (first (:line c))) (first (:line c))]))))
        (let [children
              (vec
               (for [{:keys [line snap]} frontier
                     :let [_ (sim/restore! sm snap)
                           d (sim/decision sm)
                           acts (when (and d (= side (:side d)) (or (= :turn (:kind d)) (and branch-prompts (= :prompt (:kind d)))))
                                  (sim/legal sm d))
                           acts (filter #(sensible? snap %) acts)
                           acts (if (and filter-acts (seq acts)) (filter-acts sm d acts) acts)]
                     a acts
                     :while (and (<= @apps max-apps) (<= (System/currentTimeMillis) deadline))
                     :let [_ (sim/restore! sm snap)
                           ok (sim/apply! sm a)
                           {:keys [status n]} (if ok (advance! sm side weights decks rng (if sim-runs 200 60) sim-runs branch-prompts) {:status :bad :n 0})
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
          (when *debug*
            (println "depth" depth "children" (count children) "keep" (count keep) "done" (count done))
            (doseq [c (take 12 (sort-by (comp - :score) (concat keep done)))]
              (println "   " (format "%.2f" (:score c)) (:status c) (mapv :label (:line c)))))
          (recur (vec keep) (into finals done) (inc depth)))))))

(defn vote
  "Combines per-determinization plan results: the first action with the best mean best-line
  score across determinizations (an action missing from one determinization counts that
  determinization's worst line). Returns a result whose :line is just that action."
  [results]
  (let [ks (distinct (mapcat (comp keys :by-first) results))
        mean (fn [k] (/ (reduce + (for [r results] (get (:by-first r) k (reduce min 0.0 (vals (:by-first r))))))
                        (count results)))
        best-k (when (seq ks) (apply max-key mean ks))
        first-a (some #(get (:first-acts %) best-k) results)]
    {:line (if first-a [first-a] [])
     :score (if best-k (mean best-k) 0.0)
     :by-first (into {} (for [k ks] [k (mean k)]))
     :apps (reduce + (map :apps results))
     :best-k best-k}))

(defrecord Planner [side weights beam max-apps budget-factor plan-state filter-acts value-fn rerank s1-margin dets sim-runs rerank-turns branch-prompts leaf-fn rerank-samples]
  h/Agent
  (choose [_ ctx]
    (let [{:keys [actions decision obs decks ^java.util.Random rng budget-ms]} ctx
          o @obs]
      (if (or (not= :turn (:kind decision)) (= 1 (count actions)))
        ;; delegate prompts, runs, encounters, turn starts/ends to S1 (unless the plan chose this prompt)
        (let [{:keys [line made-turn]} @plan-state
              nxt (first line)
              planned (when (and branch-prompts nxt (= :prompt (:kind decision)) (= made-turn (:turn o)))
                        (first (keep-indexed (fn [i x] (when (= (action-key x) (action-key nxt)) i)) actions)))]
          (if planned
            (do (swap! plan-state update :line rest) planned)
            (let [env (assoc ctx :obs o :weights weights :mem (atom {}))
                  [_ a] (s1/decide env)]
              (or (first (keep-indexed (fn [i x] (when (identical? x a) i)) actions)) 0))))
        (let [turn-key [(:turn o) (get-in o [side :click])]
              {:keys [line made-turn]} @plan-state
              nxt (first line)
              follow (when (and nxt (= made-turn (:turn o)))
                       (first (keep-indexed (fn [i x] (when (= (action-key x) (action-key nxt)) i)) actions)))]
          (if follow
            (do (swap! plan-state update :line rest) follow)
            (let [fresh-turn (not= made-turn (:turn o))
                  deadline (+ (System/currentTimeMillis) (long (* budget-ms (if fresh-turn budget-factor 4) (max 1 (or dets 1)))))
                  plan-once (fn []
                              (let [sm (sim/begin! @(:sim ctx) (.nextLong rng))]
                                (try (plan sm side {:weights weights :decks decks :rng rng :beam beam
                                                    :max-apps max-apps :deadline deadline :rerank rerank :sim-runs sim-runs :rerank-turns rerank-turns
                                                    :branch-prompts branch-prompts :leaf-fn leaf-fn :rerank-samples rerank-samples
                                                    :filter-acts (when filter-acts (partial filter-acts decks))
                                                    :value-fn (when value-fn (partial value-fn decks))})
                                     (finally (sim/end! sm)))))
                  result (if (and dets (> dets 1))
                           (vote (vec (repeatedly dets plan-once)))
                           (plan-once))
                  best (first (:line result))
                  ;; S1 anchoring: keep S1's choice unless the plan beats S1's best line by a margin
                  s1a (when s1-margin (second (s1/decide (assoc ctx :obs o :weights weights :mem (atom {})))))
                  s1-score (when s1a (get (:by-first result) (action-key s1a)))
                  best (if (and s1a s1-score best (< (- (:score result) s1-score) s1-margin)) s1a best)
                  result (if (and s1a (identical? best s1a)) (assoc result :line [s1a]) result)
                  idx (when best (first (keep-indexed (fn [i x] (when (= (action-key x) (action-key best)) i)) actions)))]
              (reset! plan-state {:line (rest (:line result)) :made-turn (:turn o) :score (:score result) :apps (:apps result)})
              (or idx
                  ;; plan's first action not legal in reality (should be rare): fall back to S1
                  (let [env (assoc ctx :obs o :weights weights :mem (atom {}))
                        [_ a] (s1/decide env)]
                    (or (first (keep-indexed (fn [i x] (when (identical? x a) i)) actions)) 0))))))))))

(defn make
  ([] (make {}))
  ([{:keys [side weights beam max-apps budget-factor rerank s1-margin dets value-net value-weight sim-runs rerank-turns branch-prompts
             vmodel vweight vblend rerank-samples]
      :or {beam 6 max-apps 2500 budget-factor 16 rerank 0 value-weight 20.0 vweight 15.0 vblend 0.0}}]
   (let [vf (when value-net
              (let [n ((requiring-resolve 'monolith.ai.knowledge.valuenet/net) value-net)
                    cv (requiring-resolve 'monolith.ai.knowledge.valuenet/corp-value)]
                (fn [decks _sm s] (* value-weight (if (= side :runner) -1.0 1.0) (cv n s (:corp decks))))))]
     ;; :vmodel (path): leaf score = vweight x learned Corp-win logit (side-signed), plus vblend x
     ;; the linear evaluator; terminal states keep +-win-value
     (let [w (merge s1/default-weights weights)
           leaf (when vmodel
                  (let [m ((requiring-resolve 'monolith.ai.knowledge.vmodel/load-model) vmodel)
                        lg (requiring-resolve 'monolith.ai.knowledge.vmodel/logit)]
                    (fn [s sd]
                      (if (:winner s)
                        (ev/for-side s sd w)
                        (+ (* vweight (lg m s) (if (= sd :corp) 1.0 -1.0))
                           (if (pos? vblend) (* vblend (ev/for-side s sd w)) 0.0))))))]
       (->Planner side w beam max-apps budget-factor (atom {}) nil vf rerank s1-margin dets sim-runs rerank-turns branch-prompts leaf rerank-samples)))))
