;; RC18 pre-registered held-out confirmation (protocol: LOG.md 2026-10-04 09:15; gate 2026-10-07: modern sets 7+8 pooled 3.60,
;; Runner-only modern set >= 3.2). Frozen aq (3f09fa5), the code of reviews modern7/8. Puzzles (RC18 and :s1ref), the
;; pre-registered review set (seeds 910000-910019), the pre-registered replication set (seeds 910020-910039, same matchups),
;; then T2 (seeds 900000-900299, own null).
(require 'monolith.ai.confirm 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.gamelog)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc18 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice) identity)) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 3.0)}]
  (.mkdirs (java.io.File. (str R "/review-holdout-rc18")))
  (monolith.ai.confirm/run {:candidate [:champion rc18] :tag "rc18" :dir R :parts #{:puzzles :review}})
  (.mkdirs (java.io.File. (str R "/review-holdout-rc18b")))
  (println :review-b (count (monolith.ai.gamelog/review-set {:candidate [:champion rc18] :matchups monolith.ai.sweep/holdout-mix
                                                             :seeds (range 910020 910040) :dir (str R "/review-holdout-rc18b") :threads 8})))
  (flush)
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc18] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc18" :null? true :threads 18
                                         :games-log (str R "/holdout-rc18.jsonl")}))
  (flush))
