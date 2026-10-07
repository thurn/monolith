;; Corp A/B of all fixes since ba (through f2d0d82: runcalc, mid-turn budget, :aid/manual no-op prunes, Sprint, Bloop):
;; RC25 Corp on frozen be, modern decks, Corp side, seeds 350000-350299 (paired with job-BEold).
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.blunders)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      corp (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
      runner (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 6.0 :w-tag 2.0)]
  (println (monolith.ai.evalset/run-many {:threads 7 :experiments [{:agent [:champion {:corp-opts corp :runner-opts runner}] :tag "be-new" :seeds (range 350000 350300)
                                                                    :matchups monolith.ai.sweep/modern-mix :sides [:corp] :games-log (str R "/eval-BEnew.jsonl") :null? false}]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BEnew.jsonl") #{"be-new"}))
  (flush))
