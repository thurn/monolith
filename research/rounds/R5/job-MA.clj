;; Matchup-awareness go/no-go (Mac): do the best champion options differ by matchup?
;; Opponent :champion; the null (champion self-play) is the baseline for both sides.
;; 3 dev matchups x 160 seeds; 7 candidates, incl. opposite directions of the Corp safety knob.
(require 'monolith.ai.evalset 'monolith.ai.agents.champion)
(let [log "/Users/dthurn/monolith_prototype/research/rounds/R5/eval-MA.jsonl"
      ms [:worlds-2018-a :worlds-2021-b :worlds-2016-a]
      ex (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 500000 500480) :matchups ms
                                   :games-log log :null? (boolean null?)})
      rc1 {:rerank-anchor true :rerank 6 :s1-strong-margin 8
           :w {:credit-knee2 12 :corp-safety-extra 3 :react-centrals true}
           :eval {:kill-threat 1 :hq-flood 1 :scorable-agendas 2.5 :asset-econ 1}}]
  (println (monolith.ai.evalset/run-many
            {:threads 17 :opponent :champion
             :experiments
             [(ex "ma-rc1" [:champion rc1] true)
              (ex "ma-cse3" [:champion {:w {:corp-safety-extra 3.0}}])
              (ex "ma-cse0" [:champion {:w {:corp-safety-extra 0.0}}])
              (ex "ma-kt" [:champion {:eval {:kill-threat 1.0}}])
              (ex "ma-ae" [:champion {:eval {:asset-econ 1.0}}])
              (ex "ma-hf" [:champion {:eval {:hq-flood 1.0}}])
              (ex "ma-ra" [:champion {:rerank-anchor true}])]}))
  (flush))
