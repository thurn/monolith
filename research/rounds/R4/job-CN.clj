;; Frozen cn (re-frozen with :follow-check): RC40 spec on code with the break-cost fix (pump-and-break uses below ice strength
;; break nothing). + :follow-check on both sides (a stored mid-turn line is replanned when S1 scores/kills; f8 log-10).
;; Corp + :scorable-joint (agendas in reach counted jointly and at most at their scored value; f4 log-10).
;; Runner vs the RC28 champion on the fresh decks, seeds 550000-550299 (cn-rc40 pairs with cm-rc40: the fix's effect):
;; + :tagged-install. Corp vs the RC28 champion Runner on the fresh decks (not saturated, unlike :s1ref), seeds
;; 560000-560299: + :overwrite-trap.
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
  (let [rc39 (-> (assoc rc38 :corp-opts (:corp-opts rc36)) (assoc-in [:corp-opts :w :prefer-etr] true)
                 (update-in [:runner-opts :w] assoc :grip-trash true :avoid-tags true))
        fresh [:worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b :classique-2025-a :classique-2025-c :classique-2026-a :classique-2026-c]
        ex (fn [tag spec side m] {:agent [:champion spec] :tag tag :seeds (range 550000 550300) :matchups m
                                  :sides [side] :games-log (str R "/eval-CN.jsonl") :null? false})]
    (println (monolith.ai.evalset/run-many {:threads 16 :opponent [:champion rc28]
                                            :experiments [(assoc (ex "cn-rc40" rc39 :corp fresh) :seeds (range 560000 560300))
                                                          (assoc (ex "cn-ot" (assoc-in rc39 [:corp-opts :w :overwrite-trap] true) :corp fresh) :seeds (range 560000 560300))
                                                          (assoc (ex "cn-fc" (assoc-in rc39 [:corp-opts :w :follow-check] true) :corp fresh) :seeds (range 560000 560300))
                                                          (assoc (ex "cn-sj" (assoc-in rc39 [:corp-opts :w :scorable-joint] true) :corp fresh) :seeds (range 560000 560300))]}))
    (println (monolith.ai.evalset/run-many {:threads 16 :opponent [:champion rc28]
                                            :experiments [(ex "cn-rc40" rc39 :runner fresh)
                                                          (ex "cn-ti" (assoc-in rc39 [:runner-opts :w :tagged-install] true) :runner fresh)
                                                          (ex "cn-fc" (assoc-in rc39 [:runner-opts :w :follow-check] true) :runner fresh)]})))
  (println (monolith.ai.blunders/summarize (str R "/eval-CN.jsonl") #{"cn-rc40" "cn-ti" "cn-ot" "cn-fc" "cn-sj"}))
  (flush))
