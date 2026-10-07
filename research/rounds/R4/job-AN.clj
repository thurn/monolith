;; Frozen an (ae5863f): RC16 = RC15 spec on code with Banner, Leech, Spec Work prune. Modern review sets: review-modern3 and
;; review-modern4 (both sides; proposed gate: pooled >= 3.5 over the two) and review-mrunner3 (Runner only).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc16 {:corp-opts (update rc4 :w dissoc :dig-ice)
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0)}
      ms (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))]
  (doseq [[d seeds side] [["review-modern3" (range 201100 201120) nil] ["review-modern4" (range 201120 201140) nil] ["review-mrunner3" (range 201140 201160) :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc16] :matchups ms :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
