(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log "/home/dthurn/monolith/research/rounds/R4/eval-H.jsonl" :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many
            {:threads 6
             :experiments
             [(dev "h0" :champion true)
              (dev "h-vm15" [:champion {:vmodel "/home/dthurn/monolith/research/rounds/R4/vm3.json" :vweight 15.0 :vblend 1.0}])
              (dev "h-cic1" [:champion {:w {:corp-ice-per-central 1}}])
              (dev "h-cse3" [:champion {:w {:corp-safety-extra 3.0}}])]}))
  (flush))
