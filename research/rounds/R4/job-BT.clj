;; :dig-full on RC33 code (frozen bt = 67a69e9): RC33 Runner vs RC33 Runner + :dig-full 3 (S1 digs for a missing breaker
;; up to 3 cards past a full grip); modern decks, 300 paired seeds (420000-420299), Runner side, win rates + T3 proxies.
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.blunders)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc26 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0
                                 :w-damage 6.0 :w-tag 3.5 :tag-exposure true)}
      rc28 (-> rc26
               (update :corp-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true :rich-credit 15 :unaffordable-ice 0.35)))
               (update :runner-opts #(-> % (assoc :rerank-samples 4) (update :w assoc :redet true) (assoc-in [:eval :kill-threat] 2.0))))
      ex (fn [tag spec sides] {:agent [:champion spec] :tag tag :seeds (range 420000 420300) :matchups monolith.ai.sweep/modern-mix
                               :sides sides :games-log (str R "/eval-BT.jsonl") :null? false})]
  (println (monolith.ai.evalset/run-many {:threads 12 :experiments [(ex "bt-rc33" rc28 [:runner]) (ex "bt-df3" (assoc-in rc28 [:runner-opts :w :dig-full] 3) [:runner])]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BT.jsonl") #{"bt-rc33" "bt-df3"}))
  (flush))
