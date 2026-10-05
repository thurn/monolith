;; RC3 pre-registered held-out confirmation (protocol: LOG.md 2026-10-04 09:15): puzzle suites for RC3 and
;; :s1ref, the 20-game blind-review set (seeds 910000-910019), then the T2 evalset (seeds 900000-900299, own null).
(require 'monolith.ai.confirm 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc3 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
               :rich-credit 20 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}]
  (monolith.ai.confirm/run {:candidate [:champion rc3] :tag "rc3" :dir R :parts #{:puzzles :review}})
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc3] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc3" :null? true :threads 19
                                         :games-log (str R "/holdout-rc3.jsonl")}))
  (flush))
