;; Frozen ay: RC24 = RC23 + Runner :w-damage 6.0, on code with ice self-break (N-Pot/F2P) and Paywall encounter loss.
;; Review sets: review-m13, review-m14 (both sides, eight modern matchups) and review-mxr6 (Runner only, archetypes).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc24 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0
                                 :w-damage 6.0 :w-tag 2.0)}
      all8 (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))
      arch (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))]
  (doseq [[d seeds m side] [["review-m13" (range 201540 201560) all8 nil] ["review-m14" (range 201560 201580) all8 nil]
                            ["review-mxr6" (range 201580 201600) arch :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc24] :matchups m :seeds seeds :dir (str R "/" d) :threads 16}
                                                        side (assoc :side-only side)))))
    (flush)))
