;; Frozen ck = 9105250 (:credit-for-econ is opt-in and off, so this is RC39 code). RC40 = RC39 spec (job CK: :credit-for-econ null,
;; not adopted). Gate under the new fresh-deck rule (LOG, RC39 gate): 4 fresh two-sided sets (review-f5..f8) pooled >= 3.5 and a
;; fresh Runner-only set (review-frunner3) >= 3.2, all on new seeds 202680-202779. K40 only if both hold.
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
      rc37r (update-in rc36 [:runner-opts :w] assoc :no-overdraw true :jackout-damage true :dig-room true :grip-trash true :avoid-tags true)
      rc39 (assoc-in rc37r [:corp-opts :w :prefer-etr] true)
      rc37 (-> rc36 (assoc-in [:corp-opts :w :no-overdraw] true) (update-in [:runner-opts :w] assoc :no-overdraw true :jackout-damage true))
      rc38 (-> rc37 (update-in [:corp-opts :w] assoc :potential-breakers true :etr-protect true) (assoc-in [:runner-opts :w :dig-room] true))
      all8 (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))
      fresh8 (vec (mapcat #(repeat 2 %) [:worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b :classique-2025-a :classique-2025-c :classique-2026-a :classique-2026-c]))
      arch (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))
      vmix (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-var))]
  (doseq [[d seeds m side] [["review-f5" (range 202680 202700) fresh8 nil] ["review-f6" (range 202700 202720) fresh8 nil]
                            ["review-f7" (range 202720 202740) fresh8 nil] ["review-f8" (range 202740 202760) fresh8 nil]
                            ["review-frunner3" (range 202760 202780) fresh8 :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc39] :matchups m :seeds seeds :dir (str R "/" d) :threads 16}
                                                        side (assoc :side-only side)))))
    (flush))
  (flush))
