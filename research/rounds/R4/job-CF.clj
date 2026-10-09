;; Frozen ce = 058071c: RC38 = RC37 + Corp :potential-breakers, :etr-protect + Runner :dig-room (job CD: +39/-34, +33/-28,
;; +52/-34). Gate review sets review-m41..m44 (both sides) and review-mrunner9..10 (Runner only), seeds 202380-202499; then
;; RC38 vs RC37 on both sides against the RC33 champion, 300 paired seeds 520000-520299.
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
  (doseq [[d seeds m side] [["review-m41" (range 202380 202400) all8 nil] ["review-m42" (range 202400 202420) all8 nil]
                            ["review-m43" (range 202420 202440) all8 nil] ["review-m44" (range 202440 202460) all8 nil]
                            ["review-mrunner9" (range 202460 202480) all8 :runner] ["review-mrunner10" (range 202480 202500) all8 :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc38] :matchups m :seeds seeds :dir (str R "/" d) :threads 16}
                                                        side (assoc :side-only side)))))
    (flush))
  (println (monolith.ai.evalset/run-many {:threads 16 :opponent [:champion rc28]
                                          :experiments [{:agent [:champion rc38] :tag "cf-rc38" :seeds (range 520000 520300) :matchups monolith.ai.sweep/modern-mix
                                                         :sides [:corp :runner] :games-log (str R "/eval-CF.jsonl") :null? false}
                                                        {:agent [:champion rc37] :tag "cf-rc37" :seeds (range 520000 520300) :matchups monolith.ai.sweep/modern-mix
                                                         :sides [:corp :runner] :games-log (str R "/eval-CF.jsonl") :null? false}]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-CF.jsonl") #{"cf-rc38" "cf-rc37"}))
  (flush))
