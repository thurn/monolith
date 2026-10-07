;; Frozen ap (1c773c0): RC17 = RC16 + Corp :rd-exposure 1.0 (eval) + :etr-guard. Modern review sets: review-modern5 and
;; review-modern6 (both sides; gate: pooled >= 3.5).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc17 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice) (assoc :etr-guard true))) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0)}
      ms (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))]
  (doseq [[d seeds side] [["review-modern5" (range 201160 201180) nil] ["review-modern6" (range 201180 201200) nil]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc17] :matchups ms :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
