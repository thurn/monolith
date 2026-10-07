;; Frozen al (858b28e): RC15 = RC12 spec (no central-ice-first) on code with the modern-deck fixes (Corp install-sub prompts,
;; exposed-asset rez, break limits + manual breaking, Anemone, Hammer, click-cost steals, Spec Work, Regolith asset value).
;; Modern review sets: review-modern2 (both sides) and review-mrunner2 (Runner only).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc15 {:corp-opts (update rc4 :w dissoc :dig-ice)
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0)}
      ms (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))]
  (doseq [[d seeds side] [["review-modern2" (range 201060 201080) nil] ["review-mrunner2" (range 201080 201100) :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc15] :matchups ms :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
