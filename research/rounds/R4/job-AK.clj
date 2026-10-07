;; Frozen ak (HEAD after modern-mix): RC14 = RC13 spec (RC12 + Corp central-ice-first) on code with the asset-eval fix.
;; Review sets on the new modern Standard dev matchups: review-modern1 (both sides, 5 Corp + 5 Runner candidate games over
;; all four matchups) and review-mrunner1 (Runner only, 10 candidate games).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc14 {:corp-opts (update rc4 :w #(-> % (dissoc :dig-ice) (assoc :central-ice-first true)))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0)}
      ms (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))]
  (doseq [[d seeds side] [["review-modern1" (range 201020 201040) nil] ["review-mrunner1" (range 201040 201060) :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc14] :matchups ms :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
