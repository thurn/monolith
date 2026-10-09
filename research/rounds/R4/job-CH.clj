;; Frozen ce = 058071c: Corp ablation against :s1ref (the T2 and review opponent) on corp-hard dev decks. K38 T2: RC38 Corp
;; 0.720 vs RC36 0.767 on the same held-out seeds (+25/-39, p=0.10); RC38's Corp knobs were neutral vs the RC33 champion only.
;; Arms (Corp side, 300 paired seeds 530000-530299): RC38, RC38 - :potential-breakers, RC38 - :etr-protect, RC36.
(require 'monolith.ai.gamelog 'monolith.ai.sweep 'monolith.ai.evalset 'monolith.ai.blunders)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc27 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0
                                 :w-damage 6.0 :w-tag 3.5 :tag-exposure true)}
      rc28 (-> rc27
               (update :corp-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true :rich-credit 15 :unaffordable-ice 0.35)))
               (update :runner-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true) (assoc-in [:eval :kill-threat] 2.0))))
      rc34 (-> rc28 (assoc-in [:corp-opts :w :corp-poverty] 0.5) (assoc-in [:runner-opts :w :blind-facecheck] true))
      rc36 (-> rc34 (update-in [:corp-opts :w] assoc :hq-first true :ice-strong true) (update-in [:runner-opts :w] assoc :runner-poverty 1.5 :runner-income 3.0))
      rc37 (-> rc36 (assoc-in [:corp-opts :w :no-overdraw] true) (update-in [:runner-opts :w] assoc :no-overdraw true :jackout-damage true))
      rc38 (-> rc37 (update-in [:corp-opts :w] assoc :potential-breakers true :etr-protect true) (assoc-in [:runner-opts :w :dig-room] true))
      all8 (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))
      arch (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))
      vmix (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-var))]
  (println (monolith.ai.evalset/run-many {:threads 16 :opponent :s1ref
                                          :experiments (vec (for [[tag spec] [["ch-rc38" rc38]
                                                                              ["ch-nopb" (update-in rc38 [:corp-opts :w] dissoc :potential-breakers)]
                                                                              ["ch-noetr" (update-in rc38 [:corp-opts :w] dissoc :etr-protect)]
                                                                              ["ch-rc36" rc36]]]
                                                              {:agent [:champion spec] :tag tag :seeds (range 530000 530300) :matchups monolith.ai.sweep/corp-hard
                                                               :sides [:corp] :games-log (str R "/eval-CH.jsonl") :null? false}))}))
  (println (monolith.ai.blunders/summarize (str R "/eval-CH.jsonl") #{"ch-rc38" "ch-nopb" "ch-noetr" "ch-rc36"}))
  (flush))
