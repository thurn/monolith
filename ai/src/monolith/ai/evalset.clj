(ns monolith.ai.evalset
  "Fixed evaluation sets: an agent plays both sides against a fixed opponent on a seed range
  spread over a matchup mix, and every game is appended to a JSONL log under a tag. Agents
  whose games are deterministic (S1, app-capped planners) need playing only once per set;
  research/cmp.py pairs any two tags by (seed, side) and fits side-ratings against the null
  (the opponent's self-play, tag \"null\")."
  (:require
   [monolith.ai.ab :as ab]
   [monolith.ai.tourney :as tourney]))

(defn run
  "opts: :agent spec, :opponent spec, :seeds, :matchups, :tag, :games-log, :threads, :null? (also
  play the opponent's self-play under tag \"null\")."
  [{:keys [agent opponent seeds matchups tag games-log threads null?] :or {opponent :s1ref threads 14}}]
  (let [decks-for (fn [s] (ab/matchup-decks (nth (vec matchups) (mod s (count matchups)))))
        games (concat
               (for [side [:corp :runner] s seeds :let [[cd rd] (decks-for s)]]
                 {:tag tag :side side :seed s :corp-deck cd :runner-deck rd :budget-ms 250
                  :corp (if (= side :corp) agent opponent) :runner (if (= side :runner) agent opponent)})
               (when null?
                 (for [s seeds :let [[cd rd] (decks-for s)]]
                   {:tag "null" :side :corp :seed s :corp-deck cd :runner-deck rd :budget-ms 250
                    :corp opponent :runner opponent})))
        write (tourney/write-jsonl-fn games-log (tourney/shas))
        results (tourney/run-games games {:threads threads})]
    (doseq [[g r] (map vector games results)]
      (write (merge (dissoc r :log :error-sample)
                    {:tag (:tag g) :side (name (:side g))
                     :agent (tourney/spec-name (if (= :corp (:side g)) (:corp g) (:runner g)))
                     :opponent (tourney/spec-name opponent)})))
    (let [won (fn [g r] (= (:side g) (:winner r)))
          rate (fn [side] (let [xs (filter #(and (= tag (:tag (first %))) (= side (:side (first %)))) (map vector games results))]
                            (/ (count (filter (fn [[g r]] (won g r)) xs)) (double (max 1 (count xs))))))]
      {:tag tag :corp (rate :corp) :runner (rate :runner) :stalls (count (filter :stall results))})))
