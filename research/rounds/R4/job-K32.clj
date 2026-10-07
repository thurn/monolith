;; RC32 pre-registered held-out confirmation (protocol: LOG.md 2026-10-04 09:15; gate 2026-10-07: two modern sets pooled >= 3.5,
;; Runner-only modern set >= 3.2). Frozen bn (da85838), the code of review-m29/m30/mxr14 (m29+m30 pooled 3.65). Puzzles (RC32 and
;; :s1ref), the pre-registered review set (seeds 910000-910019), the pre-registered replication set (seeds 910020-910039, same
;; matchups), then T2 (seeds 900000-900299, own null).
(require 'monolith.ai.confirm 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.gamelog)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc32 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0
                                 :w-damage 6.0 :w-tag 3.5 :tag-exposure true)}
      rc32 (-> rc32
               (update :corp-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true :rich-credit 15 :unaffordable-ice 0.35)))
               (update :runner-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true) (assoc-in [:eval :kill-threat] 2.0))))]
  (.mkdirs (java.io.File. (str R "/review-holdout-rc32")))
  (monolith.ai.confirm/run {:candidate [:champion rc32] :tag "rc32" :dir R :parts #{:puzzles :review}})
  (.mkdirs (java.io.File. (str R "/review-holdout-rc32b")))
  (println :review-b (count (monolith.ai.gamelog/review-set {:candidate [:champion rc32] :matchups monolith.ai.sweep/holdout-mix
                                                             :seeds (range 910020 910040) :dir (str R "/review-holdout-rc32b") :threads 8})))
  (flush)
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc32] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc32" :null? true :threads 12
                                         :games-log (str R "/holdout-rc32.jsonl")}))
  (flush))
