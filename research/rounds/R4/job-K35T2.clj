;; T2 re-measurement after the HQ-memory leak fix (LOG 2026-10-08): RC35 (= RC34 spec) on frozen bv (07489d6, leak fixed)
;; vs :s1ref on the pre-registered held-out T2 seeds 900000-900299, own null. K32's Runner g_r (+1.68) was measured with the leak.
(require 'monolith.ai.evalset 'monolith.ai.sweep)
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
               (update :runner-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true) (assoc-in [:eval :kill-threat] 2.0))))
      rc35 (-> rc32 (assoc-in [:corp-opts :w :corp-poverty] 0.5) (assoc-in [:runner-opts :w :blind-facecheck] true))]
  (println :t2 (monolith.ai.evalset/run {:agent [:champion rc35] :opponent :s1ref :seeds (range 900000 900300)
                                         :matchups monolith.ai.sweep/holdout-mix :tag "rc35" :null? true :threads 4
                                         :games-log (str R "/holdout-rc35.jsonl")}))
  (flush))
