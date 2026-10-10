;; Job CR (frozen cr): RC42 = RC41 + Corp :pressure-first + Runner :trace-link, :free-run-first (CO neutrals) + Corp :rich-dig + Runner :breach-damage (CQ),
;; on code with all always-on fixes since cp (prompt, Passport, Anoetic Void, Endurance counters, agenda access/steal damage)
;; and the engine's Loki fix. (1) Gate on fresh-2: review-h1..h4 (two-sided, seeds 202880-202959) pooled >= 3.5 and
;; review-hrunner1 (Runner-only, 202960-202979) >= 3.2. (2) Paired RC41 vs RC42 on fresh8 vs :s1ref, both sides, seeds
;; 580000-580299. Both run on the fixed code, so this measures RC42's knobs together; CQ's cq-rc41 vs co-rc41 measures the fixes.
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
  (let [rc41 (-> rc39 (update-in [:corp-opts :w] assoc :follow-check true :overwrite-trap true)
                 (update-in [:runner-opts :w] merge {:tagged-install true}))
        rc42 (-> rc41 (update-in [:corp-opts :w] assoc :pressure-first true)
                 (update-in [:runner-opts :w] assoc :trace-link true :free-run-first true)
                 (update-in [:corp-opts :w] merge {:rich-dig true}) (update-in [:runner-opts :w] merge {:breach-damage true}))
        fresh [:worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b :classique-2025-a :classique-2025-c :classique-2026-a :classique-2026-c]
        fresh2 (vec (mapcat #(repeat 2 %) [:classique-2025-b :classique-2025-d :classique-2026-b :classique-2026-d :classique-2023-a :classique-2023-b :classique-2023-c :classique-2022-b]))]
    (println :rc42 rc42)
    (doseq [[d seeds side] [["review-h1" (range 202880 202900) nil] ["review-h2" (range 202900 202920) nil]
                            ["review-h3" (range 202920 202940) nil] ["review-h4" (range 202940 202960) nil]
                            ["review-hrunner1" (range 202960 202980) :runner]]]
      (.mkdirs (java.io.File. (str R "/" d)))
      (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc42] :matchups fresh2 :seeds seeds :dir (str R "/" d) :threads 16}
                                                          side (assoc :side-only side)))))
      (flush))
    (let [ex (fn [tag spec] {:agent [:champion spec] :tag tag :seeds (range 580000 580300) :matchups fresh
                             :sides [:corp :runner] :games-log (str R "/eval-CR.jsonl") :null? false})]
      (println (monolith.ai.evalset/run-many {:threads 16 :opponent :s1ref :experiments [(ex "cr-rc41" rc41) (ex "cr-rc42" rc42)]}))
      (println (monolith.ai.blunders/summarize (str R "/eval-CR.jsonl") #{"cr-rc41" "cr-rc42"}))))
  (flush))
