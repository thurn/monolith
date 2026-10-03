(ns monolith.ai.ab
  "Paired A/B comparisons (plan 7.2): variants A and B each play both sides against a fixed
  opponent on the same seeds; per seed-side the two outcomes form a pair, and McNemar's test on
  the discordant pairs decides. Results are appended to a JSONL log."
  (:require
   [cheshire.core :as json]
   [monolith.ai.tourney :as tourney]))

(defn- mcnemar-p
  "Two-sided exact binomial p-value for b vs c discordant pairs."
  [b c]
  (let [n (+ b c)
        k (min b c)
        lg (fn [x] (reduce + 0.0 (map #(Math/log %) (range 1 (inc x)))))
        pmf (fn [i] (Math/exp (- (+ (lg n) (* n (Math/log 0.5))) (lg i) (lg (- n i)))))]
    (if (zero? n) 1.0 (min 1.0 (* 2 (reduce + (map pmf (range 0 (inc k)))))))))

(defn run
  "opts: :a :b agent specs, :opponent spec, :seeds range, :threads, :decks [corp runner], :log path."
  [{:keys [a b opponent seeds threads decks log] :or {opponent :heuristic threads 8
                                                       decks [:gateway-beginner-corp :gateway-beginner-runner]}}]
  (let [[cd rd] decks
        base {:corp-deck cd :runner-deck rd :budget-ms 250}
        games (for [v [:a :b] side [:corp :runner] s seeds]
                (merge base {:variant v :side side :seed s
                             :corp (if (= side :corp) ({:a a :b b} v) opponent)
                             :runner (if (= side :runner) ({:a a :b b} v) opponent)}))
        results (tourney/run-games games {:threads threads})
        won (fn [g r] (= (:side g) (:winner r)))
        by (into {} (map (fn [g r] [[(:variant g) (:side g) (:seed g)] (won g r)]) games results))
        pairs (for [side [:corp :runner] s seeds] [(by [:a side s]) (by [:b side s])])
        b-only (count (filter (fn [[x y]] (and (not x) y)) pairs))
        a-only (count (filter (fn [[x y]] (and x (not y))) pairs))
        rate (fn [v side] (/ (count (filter #(by [v side %]) seeds)) (double (count seeds))))
        out {:a (tourney/spec-name a) :b (tourney/spec-name b) :opponent (tourney/spec-name opponent)
             :n (count seeds) :a-corp (rate :a :corp) :a-runner (rate :a :runner)
             :b-corp (rate :b :corp) :b-runner (rate :b :runner)
             :a-only a-only :b-only b-only :p (mcnemar-p a-only b-only)
             :stalls (count (filter :stall results))}]
    (when log (spit log (str (json/generate-string out) "\n") :append true))
    out))
