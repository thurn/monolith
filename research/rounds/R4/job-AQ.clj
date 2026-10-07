;; Frozen aq (3f09fa5): RC18 = RC17 without :etr-guard (stalled HB Corps), Runner :w-damage 3.0, on code with exposed-asset and
;; Saisentan fixes. Modern review sets: review-modern7, review-modern8 (both sides; gate pooled >= 3.5), review-mrunner4 (Runner).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc18 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice) identity)) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 3.0)}
      ms (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))]
  (doseq [[d seeds side] [["review-modern7" (range 201200 201220) nil] ["review-modern8" (range 201220 201240) nil] ["review-mrunner4" (range 201240 201260) :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc18] :matchups ms :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
