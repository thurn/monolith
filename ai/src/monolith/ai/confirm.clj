(ns monolith.ai.confirm
  "Held-out confirmation for a release candidate, per the protocol fixed in research/LOG.md
  (2026-10-04 09:15): T2 evalset on the six held-out matchups (seeds 900000-900299, with null),
  the puzzle suites for the candidate and :s1ref, and the blind-review game set (seeds
  910000-910019). Prints puzzle scores; games go to the given files."
  (:require
   [monolith.ai.evalset :as evalset]
   [monolith.ai.gamelog :as gamelog]
   [monolith.ai.puzzles :as puzzles]
   [monolith.ai.sweep :as sweep]))

(defn puzzles-report [spec]
  (let [dev (puzzles/run-suite spec :puzzles (concat puzzles/suite puzzles/dev-suite))
        held (puzzles/run-suite spec :puzzles puzzles/holdout-suite)]
    {:agent spec :dev (:score dev) :holdout (:score held)
     :dev-failed (mapv :name (remove :solved (:results dev)))
     :holdout-failed (mapv :name (remove :solved (:results held)))}))

(defn run
  "opts: :candidate spec, :tag, :dir (output directory), :threads, :parts (subset of
  #{:t2 :puzzles :review})."
  [{:keys [candidate tag dir threads parts] :or {threads 16 parts #{:t2 :puzzles :review}}}]
  (when (:puzzles parts)
    (doseq [spec [candidate :s1ref]]
      (println :puzzles (puzzles-report spec)) (flush)))
  (when (:review parts)
    (println :review (count (gamelog/review-set {:candidate candidate :matchups sweep/holdout-mix
                                                 :seeds (range 910000 910020) :dir (str dir "/review-holdout-" tag)
                                                 :threads 4})))
    (flush))
  (when (:t2 parts)
    (println :t2 (evalset/run {:agent candidate :opponent :s1ref :seeds (range 900000 900300)
                               :matchups sweep/holdout-mix :tag tag :null? true :threads threads
                               :games-log (str dir "/holdout-" tag ".jsonl")}))
    (flush)))
