
;; RC12 pre-registered held-out confirmation (protocol: LOG.md 2026-10-04 09:15; gate: dev reviews 21+22 pooled 3.50, modern Runner 3.30;
;; frozen ah (9be27ef), the code of reviews 21-22). Puzzle suites for RC12 and :s1ref,
(require 'monolith.ai.confirm 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc12 {:corp-opts (update rc4 :w dissoc :dig-ice)
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0)}]
  (.mkdirs (java.io.File. (str R "/review-holdout-rc12")))
  (monolith.ai.confirm/run {:candidate [:champion rc12] :tag "rc12" :dir R :parts #{:puzzles :review}})
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc12] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc12" :null? true :threads 18
                                         :games-log (str R "/holdout-rc12.jsonl")}))
  (flush))
