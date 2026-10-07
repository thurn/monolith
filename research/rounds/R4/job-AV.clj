;; Frozen av (6e06fe0): RC22 = RC21 + Runner :w-tag 2.0, on code with AU Co./dig-draw fixes. Review sets: review-m9 and review-m10
;; (both sides, all eight modern matchups; gate pooled >= 3.5) and review-mxr4 (Runner only, archetype matchups modern-e..h).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc22 {:corp-opts (-> rc4 (update :w dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc-in [:eval :rd-exposure] 0.5))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0
                                 :w-damage 3.0 :w-tag 2.0)}
      all8 (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))
      arch (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))]
  (doseq [[d seeds m side] [["review-m9" (range 201420 201440) all8 nil] ["review-m10" (range 201440 201460) all8 nil]
                            ["review-mxr4" (range 201460 201480) arch :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc22] :matchups m :seeds seeds :dir (str R "/" d) :threads 16}
                                                        side (assoc :side-only side)))))
    (flush)))
