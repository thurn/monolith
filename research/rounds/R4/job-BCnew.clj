;; Runner A/B of the run-calculator fixes (8bd355e: unaffordable unrezzed ice passed, untagged meat kills, steal costs
;; after breaking): RC25 Runner on frozen bc, modern decks, Runner side, seeds 340000-340299 (paired with job-BCold).
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.blunders)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      corp (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
      runner (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 6.0 :w-tag 2.0)]
  (println (monolith.ai.evalset/run-many {:threads 7 :experiments [{:agent [:champion {:corp-opts corp :runner-opts runner}] :tag "bc-new" :seeds (range 340000 340300)
                                                                    :matchups monolith.ai.sweep/modern-mix :sides [:runner] :games-log (str R "/eval-BCnew.jsonl") :null? false}]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BCnew.jsonl") #{"bc-new"}))
  (flush))
