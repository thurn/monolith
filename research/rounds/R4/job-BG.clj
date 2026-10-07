;; Determinization noise: RC26 spec (dets 1) vs :dets 3 vs 4 re-determinized rerank samples (:redet), both sides, frozen bg (96a367e), modern decks, both sides,
;; 300 paired seeds (360000-360299), with T3 proxies. Motivation: one hidden-info sample per decision makes run choices a
;; lottery (archetype review 8 log-11: HQ run into a known Saisentan at 0 credits chosen 2/10 times over agent RNG seeds,
;; 1/10 with dets 3, 0/10 with 4 re-determinized rerank samples; plain rerank-samples 4 did not help because every sample
;; restarts from the same determinization).
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
      d3 (-> rc26 (assoc-in [:corp-opts :dets] 3) (assoc-in [:runner-opts :dets] 3))
      rd4 (-> rc26 (assoc-in [:corp-opts :rerank-samples] 4) (assoc-in [:corp-opts :w :redet] true)
              (assoc-in [:runner-opts :rerank-samples] 4) (assoc-in [:runner-opts :w :redet] true))
      ex (fn [tag spec] {:agent [:champion spec] :tag tag :seeds (range 360000 360300) :matchups monolith.ai.sweep/modern-mix
                         :sides [:corp :runner] :games-log (str R "/eval-BG.jsonl") :null? false})]
  (println (monolith.ai.evalset/run-many {:threads 14 :experiments [(ex "bg-d1" rc26) (ex "bg-d3" d3) (ex "bg-rd4" rd4)]}))
  (println (monolith.ai.blunders/summarize (str R "/eval-BG.jsonl") #{"bg-d1" "bg-d3" "bg-rd4"}))
  (flush))
