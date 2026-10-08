;; Knob A/Bs on RC35 code (frozen bw), all against the RC33 champion (s1ref A/Bs are saturated): Corp :hq-first (ice HQ
;; first while it holds agendas; m35 log-10), Corp :dig-ice (re-test), Runner :runner-income 3 (recurring-income
;; resources; modern-b Runner rated ~2.3 in m33-m36); 300 paired seeds 440000-440299, modern decks, win rates + proxies.
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
             :experiments (vec (for [[tag spec side] [["bw-rc35" rc34 [:corp :runner]] ["bw-hqf" (assoc-in rc34 [:corp-opts :w :hq-first] true) [:corp]]
                                                      ["bw-dig" (assoc-in rc34 [:corp-opts :w :dig-ice] true) [:corp]]
                                                      ["bw-inc3" (assoc-in rc34 [:runner-opts :w :runner-income] 3.0) [:runner]]]]
                                 {:agent [:champion spec] :tag tag :seeds (range 440000 440300) :matchups monolith.ai.sweep/modern-mix
                                  :sides side :games-log (str R "/eval-BW.jsonl") :null? false}))}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BW.jsonl") #{"bw-rc35" "bw-hqf" "bw-dig" "bw-inc3"}))
  (flush))
