;; Frozen bu: RC34 = RC33 + Runner :blind-facecheck (job BR: blind facechecks -78%, win null) + Corp :corp-poverty 0.5
;; (job BR: Corp broke-ends -15%, win null). Gate review sets: review-m33, review-m34 (both sides, eight modern matchups),
;; review-mrunner5 (Runner only, eight modern matchups); then RC34 vs RC33 head-to-head on 300 seeds (both sides).
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
      all8 (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))
      arch (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))
      vmix (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-var))]
  (doseq [[d seeds m side] [["review-m33" (range 202140 202160) all8 nil] ["review-m34" (range 202160 202180) all8 nil]
                            ["review-mrunner5" (range 202180 202200) all8 :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc34] :matchups m :seeds seeds :dir (str R "/" d) :threads 14}
                                                        side (assoc :side-only side)))))
    (flush))
  (println (monolith.ai.evalset/run-many {:threads 14 :opponent [:champion rc28]
                                          :experiments [{:agent [:champion rc34] :tag "bu-rc34" :seeds (range 430000 430300) :matchups monolith.ai.sweep/modern-mix
                                                         :sides [:corp :runner] :games-log (str R "/eval-BU.jsonl") :null? true}]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BU.jsonl") #{"bu-rc34" "null"}))
  (flush))
