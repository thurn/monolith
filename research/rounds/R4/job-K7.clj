;; (Puzzles done in job-K7a.log; the first run stopped at the review step on a missing directory.)
;; RC7 pre-registered held-out confirmation (protocol: LOG.md 2026-10-04 09:15; gate: two dev review sets pooled >= 3.5,
;; met by reviews 13-14 = 3.50). Frozen y (a3b8599), the code of reviews 13-14. Puzzle suites for RC7 and :s1ref,
;; the 20-game blind-review set (seeds 910000-910019), then the T2 evalset (seeds 900000-900299, own null).
(require 'monolith.ai.confirm 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc6 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      rc7 (update rc6 :runner-opts update :w assoc :late-tag-removal true)]
  (monolith.ai.confirm/run {:candidate [:champion rc7] :tag "rc7" :dir R :parts #{:review}})
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc7] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc7" :null? true :threads 18
                                         :games-log (str R "/holdout-rc7.jsonl")}))
  (flush))
