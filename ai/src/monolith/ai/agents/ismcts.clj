(ns monolith.ai.agents.ismcts
  "S2: root-parallel determinized MCTS (netrunner-rs found 4 determinization trees to be the knee).
  Each tree fixes one determinization of the acting side's information set; trees are searched
  round-robin within the decision's time budget and root statistics are merged by action key.
  Tree nodes are decision points with >1 legal action (both sides; opponent nodes maximize the
  opponent's value). Leaves: an S1-policy rollout truncated at the end of the next turn, scored
  relative to the root as tanh((leaf - root)/k) with the shared evaluator.
  `make-clairvoyant` is the reference cheater: same search on the true state."
  (:require
   [monolith.ai.agents.heuristic :as s1]
   [monolith.ai.harness :as h]
   [monolith.ai.knowledge.evaluator :as ev]
   [monolith.ai.moves :as moves]
   [monolith.ai.sim :as sim]))

(defn- akey [a] [(:command a) (get-in a [:args :card :cid]) (get-in a [:args :server]) (:label a)])

(defn- s1-action [sm d acts weights decks rng]
  (let [env {:side (:side d) :decision d :actions acts :obs (sim/obs sm (:side d))
             :weights weights :mem (atom {}) :decks decks :rng rng}]
    (or (second (s1/decide env)) (first acts))))

(defn- settle!
  "Applies forced single-action decisions. Returns the next decision with >1 action, or nil at game end."
  [sm]
  (loop [i 0]
    (let [d (sim/decision sm)]
      (when (and d (< i 200))
        (let [acts (sim/legal sm d)]
          (cond (empty? acts) nil
                (= 1 (count acts)) (if (sim/apply! sm (first acts)) (recur (inc i)) nil)
                :else [d acts]))))))

(defn- rollout!
  "S1 policy until the end of the turn after the current one (or a step cap)."
  [sm weights decks rng max-steps]
  (let [start-turn (:turn (sim/snapshot sm))
        start-active (:active-player (sim/snapshot sm))]
    (loop [i 0]
      (let [s (sim/snapshot sm)]
        (when (and (< i max-steps) (not (moves/game-over? s))
                   (not (and (> (:turn s) start-turn) (= (:active-player s) start-active) (:end-turn s))))
          (when-let [d (sim/decision sm)]
            (let [acts (sim/legal sm d)]
              (when (seq acts)
                (when (sim/apply! sm (if (= 1 (count acts)) (first acts) (s1-action sm d acts weights decks rng)))
                  (recur (inc i)))))))))))

(defn- new-node [snap d acts] {:snap snap :d d :acts acts :n 0 :stats {} :children {}})

(defn- ucb-pick [node c ^java.util.Random rng]
  (let [{:keys [acts stats n prior]} node
        untried (remove #(contains? stats (akey %)) acts)]
    (if (seq untried)
      ;; progressive bias: S1's choice is tried first
      (or (first (filter #(= prior (akey %)) untried))
          (nth (vec untried) (.nextInt rng (count untried))))
      (apply max-key (fn [a] (let [{w :w k :n} (stats (akey a))]
                               (+ (/ w k) (* c (Math/sqrt (/ (Math/log (max 1 n)) k))))))
             acts))))

(defn- iterate!
  "One MCTS iteration on tree (an atom holding the root node). root-side values are in [-1,1]."
  [tree sm root-side root-eval {:keys [weights decks rng c k rollout-steps]}]
  (loop [path [] node-path []]
    (let [node (get-in @tree node-path)
          a (ucb-pick node c rng)
          kk (akey a)
          child-path (conj node-path :children kk)]
      (sim/restore! sm (:snap node))
      (let [ok (sim/apply! sm a)
            existing (get-in @tree child-path)]
        (if (and ok existing (:snap existing))
          (recur (conj path [node-path kk (:side (:d node))]) child-path)
          (let [nxt (when ok (settle! sm))
                _ (when (and ok nxt)
                    (swap! tree assoc-in child-path (new-node (sim/snapshot sm) (first nxt) (second nxt))))
                _ (when ok (rollout! sm weights decks rng rollout-steps))
                leaf (if ok (ev/for-side (sim/snapshot sm) root-side weights) (- root-eval 50.0))
                v (Math/tanh (/ (- leaf root-eval) k))
                path (conj path [node-path kk (:side (:d node))])]
            (doseq [[np key side] path]
              (swap! tree (fn [t f] (if (empty? np) (f t) (update-in t np f)))
                     (fn [nd] (-> nd (update :n inc)
                                  (update-in [:stats key] (fn [st] (-> (or st {:w 0.0 :n 0})
                                                                       (update :n inc)
                                                                       (update :w + (if (= side root-side) v (- v))))))))))))))))

(defn search
  "Runs MCTS on `trees` determinizations of handle `sim-handle` until deadline. Returns {akey visits}."
  [sim-handle root-side actions {:keys [trees deadline rng clairvoyant] :as opts}]
  (let [sessions (vec (for [_ (range trees)]
                        (let [sm (if clairvoyant
                                   (sim/begin-true! sim-handle (.nextLong ^java.util.Random rng))
                                   (sim/begin! sim-handle (.nextLong ^java.util.Random rng)))
                              snap (sim/snapshot sm)
                              d (sim/decision sm)
                              acts (sim/legal sm d)
                              root-eval (ev/for-side snap root-side (:weights opts))
                              t (atom (assoc (new-node snap d acts) :prior (:prior opts)))]
                          (sim/end! sm)
                          {:sm sm :tree t :root-eval root-eval :snap snap})))]
    (loop [i 0]
      (when (< (System/currentTimeMillis) deadline)
        (let [{:keys [sm tree root-eval snap]} (sessions (mod i trees))
              live (sim/resume! sm snap)]
          (try (iterate! tree live root-side root-eval opts)
               (finally (sim/end! live)))
          (recur (inc i)))))
    (apply merge-with (fn [a b] {:n (+ (:n a) (:n b)) :w (+ (:w a) (:w b))})
           (for [{:keys [tree]} sessions] (:stats @tree)))))

(defrecord Ismcts [side weights trees c k rollout-steps clairvoyant margin]
  h/Agent
  (choose [_ ctx]
    (let [{:keys [actions decision obs decks ^java.util.Random rng budget-ms]} ctx]
      (let [env (assoc ctx :obs @obs :weights weights :mem (atom {}))
            s1a (second (s1/decide env))
            s1i (or (first (keep-indexed (fn [i x] (when (identical? x s1a) i)) actions)) 0)]
        (if (or (= 1 (count actions)) (not= :turn (:kind decision)))
          s1i
          (let [stats (search @(:sim ctx) side actions
                              {:trees trees :deadline (+ (System/currentTimeMillis) budget-ms) :rng rng
                               :weights weights :decks decks :c c :k k :rollout-steps rollout-steps
                               :clairvoyant clairvoyant :prior (akey (nth actions s1i))})
                ;; best mean value among actions with enough visits; S1's choice wins ties
                s1-mean (when-let [st (get stats (akey (nth actions s1i)))] (/ (:w st) (:n st)))
                cands (for [[kk st] stats :when (>= (:n st) 4)] [kk (/ (:w st) (:n st))])
                [best bm] (when (seq cands) (apply max-key second cands))
                idx (when (and best (or (nil? s1-mean) (> bm (+ s1-mean margin))))
                      (first (keep-indexed (fn [i a] (when (= (akey a) best) i)) actions)))]
            (or idx s1i)))))))

(defn make
  ([] (make {}))
  ([{:keys [side weights trees c k rollout-steps margin] :or {trees 4 c 0.7 k 10.0 rollout-steps 80 margin 0.15}}]
   (->Ismcts side (merge s1/default-weights weights) trees c k rollout-steps false margin)))

(defn make-clairvoyant
  "Reference cheater: the same search on the true hidden state (one tree)."
  ([] (make-clairvoyant {}))
  ([{:keys [side weights c k rollout-steps margin] :or {c 0.7 k 10.0 rollout-steps 80 margin 0.15}}]
   (->Ismcts side (merge s1/default-weights weights) 1 c k rollout-steps true margin)))
