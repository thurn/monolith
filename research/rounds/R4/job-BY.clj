;; Runner follow-up on RC35 code (frozen bx = ba7bc0d; Runner code identical to bw), against the RC33 champion: replicate BW's
;; :runner-poverty 1.5 win (+71/-45, p=0.02) on fresh seeds, stack :runner-income 3 (BW +26/-14, p=0.08), and a dose step
;; :runner-poverty 3.0; plus Corp :rich-credit 10 (RC35 Corp clicks for credits ~4x/game at 8-14 credits, the most common Corp
;; review complaint; the planner overrides S1's ice/draw/install with the credit click); 300 paired seeds 460000-460299, win rates + proxies.
(require 'monolith.ai.sweep 'monolith.ai.evalset 'monolith.ai.blunders)
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
      rc34 (-> rc28 (assoc-in [:corp-opts :w :corp-poverty] 0.5) (assoc-in [:runner-opts :w :blind-facecheck] true))]
  (println (monolith.ai.evalset/run-many
            {:threads 16 :opponent [:champion rc28]
             :experiments (vec (for [[tag spec side] [["by-rc35" rc34 [:corp :runner]] ["by-rich10" (assoc-in rc34 [:corp-opts :w :rich-credit] 10) [:corp]]
                                                      ["by-pov15" (assoc-in rc34 [:runner-opts :w :runner-poverty] 1.5) [:runner]]
                                                      ["by-pi" (update-in rc34 [:runner-opts :w] assoc :runner-poverty 1.5 :runner-income 3.0) [:runner]]
                                                      ["by-pov3" (assoc-in rc34 [:runner-opts :w :runner-poverty] 3.0) [:runner]]]]
                                 {:agent [:champion spec] :tag tag :seeds (range 460000 460300) :matchups monolith.ai.sweep/modern-mix
                                  :sides side :games-log (str R "/eval-BY.jsonl") :null? false}))}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BY.jsonl") #{"by-rc35" "by-rich10" "by-pov15" "by-pi" "by-pov3"}))
  (flush))
