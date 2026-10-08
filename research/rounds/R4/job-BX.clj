;; Corp A/B on frozen bx = ba7bc0d, against the RC33 champion Runner: RC35 Corp vs + :flood-install 3 (m35 log-11) vs
;; + :flood-install 3 + :hq-first (HQ first in protect- and react-centrals while HQ holds an agenda; m35 log-10, m36 log-11);
;; 300 paired seeds 450000-450299, modern decks, win rates + proxies.
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
             :experiments (vec (for [[tag spec side] [["bx-rc35" rc34 [:corp]] ["bx-fl3" (assoc-in rc34 [:corp-opts :w :flood-install] 3) [:corp]]
                                                      ["bx-fl3hq" (update-in rc34 [:corp-opts :w] assoc :flood-install 3 :hq-first true) [:corp]]]]
                                 {:agent [:champion spec] :tag tag :seeds (range 450000 450300) :matchups monolith.ai.sweep/modern-mix
                                  :sides side :games-log (str R "/eval-BX.jsonl") :null? false}))}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BX.jsonl") #{"bx-rc35" "bx-fl3" "bx-fl3hq"}))
  (flush))
