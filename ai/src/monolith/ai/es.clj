(ns monolith.ai.es
  "Weight tuning by a simple separable evolution strategy (plan section 6, S3 tuning: CMA-ES).
  Each generation samples lambda candidates around the mean (per-dimension Gaussian), scores each
  by win rate on that generation's shared seeds (common random numbers), recombines the best mu,
  and shrinks the step sizes. Parameters are clamped to bounds; integer params are rounded."
  (:require
   [cheshire.core :as json]
   [monolith.ai.tourney :as tourney]))

(defn- sample [^java.util.Random rng mean sigma bounds ints]
  (into {} (for [[k m] mean
                 :let [[lo hi] (bounds k)
                       v (-> (+ m (* (sigma k) (.nextGaussian rng))) (max lo) (min hi))]]
             [k (if (ints k) (Math/round (double v)) v)])))

(defn score
  "Win rate of `agent-key` with weights w playing `side` against `opponent` on seeds."
  [agent-key w side opponent seeds threads decks]
  (let [[cd rd] decks
        me [agent-key {:weights w}]
        games (for [s seeds] {:corp (if (= side :corp) me opponent) :runner (if (= side :runner) me opponent)
                              :seed s :corp-deck cd :runner-deck rd :budget-ms 250})
        rs (tourney/run-games games {:threads threads})]
    (/ (count (filter #(= side (:winner %)) rs)) (double (count rs)))))

(defn run
  [{:keys [agent side opponent params generations lambda mu seeds-per-gen threads log decks seed]
    :or {agent :heuristic opponent :heuristic generations 12 lambda 10 mu 3 seeds-per-gen 200 threads 16
         decks [:gateway-beginner-corp :gateway-beginner-runner] seed 1}}]
  (let [rng (java.util.Random. seed)
        bounds (into {} (for [[k [lo hi]] params] [k [lo hi]]))
        ints (set (for [[k [_ _ _ _ int?]] params :when int?] k))]
    (loop [g 0
           mean (into {} (for [[k [_ _ init]] params] [k init]))
           sigma (into {} (for [[k [_ _ _ sg]] params] [k sg]))
           best nil]
      (if (>= g generations)
        best
        (let [seeds (range (* 100000 (inc g)) (+ (* 100000 (inc g)) seeds-per-gen))
              cands (cons (into {} (for [[k v] mean] [k (if (ints k) (Math/round (double v)) v)]))
                          (repeatedly (dec lambda) #(sample rng mean sigma bounds ints)))
              scored (vec (for [c cands] [(score agent c side opponent seeds threads decks) c]))
              top (take mu (sort-by (comp - first) scored))
              new-mean (into {} (for [k (keys mean)] [k (/ (reduce + (map #(double (get (second %) k)) top)) mu)]))
              best (if (or (nil? best) (> (ffirst top) (first best))) (first top) best)]
          (when log
            (spit log (str (json/generate-string {:gen g :mean-score (first (first scored)) :top (ffirst top)
                                                  :top-weights (second (first top)) :new-mean new-mean})
                           "\n") :append true))
          (recur (inc g) new-mean (into {} (for [[k s] sigma] [k (* 0.85 s)])) best))))))
