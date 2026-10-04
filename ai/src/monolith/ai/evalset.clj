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
  [{:keys [agent opponent seeds matchups tag games-log threads null? sides] :or {opponent :s1ref threads 14 sides [:corp :runner]}}]
  (let [decks-for (fn [s] (ab/matchup-decks (nth (vec matchups) (mod s (count matchups)))))
        meta (fn [tag side spec] {:tag tag :side (name side) :agent (tourney/spec-name spec)
                                  :opponent (tourney/spec-name opponent)})
        ;; seed-major order so partial logs hold complete seeds
        games (for [s seeds
                    :let [[cd rd] (decks-for s)]
                    [t side] (concat (for [sd sides] [tag sd]) (when null? [["null" :corp]]))]
                {:tag t :side side :seed s :corp-deck cd :runner-deck rd :budget-ms 250
                 :corp (if (and (= t tag) (= side :corp)) agent opponent)
                 :runner (if (and (= t tag) (= side :runner)) agent opponent)
                 :meta (meta t side (if (= t tag) agent opponent))})
        write (tourney/write-jsonl-fn games-log (tourney/shas))
        results (tourney/run-games games {:threads threads :on-result (fn [r] (write (dissoc r :error-sample)))})]
    (let [won (fn [g r] (= (:side g) (:winner r)))
          rate (fn [side] (let [xs (filter #(and (= tag (:tag (first %))) (= side (:side (first %)))) (map vector games results))]
                            (/ (count (filter (fn [[g r]] (won g r)) xs)) (double (max 1 (count xs))))))]
      {:tag tag :corp (rate :corp) :runner (rate :runner) :stalls (count (filter :stall results))})))
