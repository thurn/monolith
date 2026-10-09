;; RC36 knob A/B on frozen ca = 56edb66 (by + opt-ins :no-overdraw, :jackout-damage): RC36 vs RC36 + :no-overdraw (no basic draw at full hand;
;; BY replay: 100 of 142 Runner discard-down turns drew at a full hand), both sides, against the RC33 champion, 300 paired seeds
;; 480000-480299, win rates + proxies; plus Runner :jackout-damage (mrunner7 log-15: continued through Karuna at 3 cards, flatlined).
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
      rc34 (-> rc28 (assoc-in [:corp-opts :w :corp-poverty] 0.5) (assoc-in [:runner-opts :w :blind-facecheck] true))
      rc36 (-> rc34 (update-in [:corp-opts :w] assoc :hq-first true :ice-strong true) (update-in [:runner-opts :w] assoc :runner-poverty 1.5 :runner-income 3.0))]
  (println (monolith.ai.evalset/run-many {:threads 16 :opponent [:champion rc28]
                                          :experiments [{:agent [:champion rc36] :tag "ca-rc36" :seeds (range 480000 480300) :matchups monolith.ai.sweep/modern-mix
                                                         :sides [:corp :runner] :games-log (str R "/eval-CA.jsonl") :null? false}
                                                        {:agent [:champion (-> rc36 (assoc-in [:corp-opts :w :no-overdraw] true) (assoc-in [:runner-opts :w :no-overdraw] true))] :tag "ca-nod" :seeds (range 480000 480300) :matchups monolith.ai.sweep/modern-mix
                                                         :sides [:corp :runner] :games-log (str R "/eval-CA.jsonl") :null? false}
                                                        {:agent [:champion (assoc-in rc36 [:runner-opts :w :jackout-damage] true)] :tag "ca-jo" :seeds (range 480000 480300) :matchups monolith.ai.sweep/modern-mix
                                                         :sides [:runner] :games-log (str R "/eval-CA.jsonl") :null? false}]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-CA.jsonl") #{"ca-rc36" "ca-nod" "ca-jo"}))
  (flush))
