;; RC2 pre-registered held-out confirmation (fill DIG-BREAKERS): T2 evalset (seeds 900000-900299,
;; own null), puzzle suites for RC2 and :s1ref, and the 20-game blind-review set (seeds 910000-910019).
(require 'monolith.ai.confirm 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc2 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true
               :dig-breakers DIG-BREAKERS}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}]
  (monolith.ai.confirm/run {:candidate [:champion rc2] :tag "rc2" :dir R :parts #{:puzzles :review}})
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc2] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc2" :null? true :threads 19
                                         :games-log (str R "/holdout-rc2.jsonl")}))
  (flush))
