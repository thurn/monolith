(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log "/home/dthurn/monolith/research/rounds/R4/eval-I.jsonl" :null? (boolean null?)})
      prx (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                                    :sides [:corp] :games-log "/home/dthurn/monolith/research/rounds/R4/proxy-I.jsonl" :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [(dev "i0" :champion true)
              (prx "p0" :champion true)
              (dev "i-ra" [:champion {:rerank-anchor true}])
              (dev "i-sc" [:champion {:eval {:scorable-agendas 2.5}}])
              (dev "i-kt" [:champion {:eval {:kill-threat 1.0}}])
              (dev "i-ae" [:champion {:eval {:asset-econ 1.0}}])
              (dev "i-hf" [:champion {:eval {:hq-flood 1.0}}])
              (dev "i-k12" [:champion {:w {:credit-knee2 12}}])
              (dev "i-rr6" [:champion {:rerank 6}])
              (dev "i-rt2" [:champion {:rerank-turns 2}])
              (dev "i-eri" [:champion {:w {:empty-remote-ice true}}])
              (dev "i-cic1" [:champion {:w {:corp-ice-per-central 1}}])
              (dev "i-cse3" [:champion {:w {:corp-safety-extra 3.0}}])
              (dev "i-react" [:champion {:w {:react-centrals true}}])
              (dev "i-vm15" [:champion {:vmodel "/home/dthurn/monolith/research/rounds/R4/vm3.json" :vweight 15.0 :vblend 1.0}])
              (prx "p-ra" [:champion {:rerank-anchor true}])
              (prx "p-sc" [:champion {:eval {:scorable-agendas 2.5}}])
              (prx "p-ae" [:champion {:eval {:asset-econ 1.0}}])
              (prx "p-hf" [:champion {:eval {:hq-flood 1.0}}])
              (prx "p-cse3" [:champion {:w {:corp-safety-extra 3.0}}])
              (prx "p-vm" [:champion {:vmodel "/home/dthurn/monolith/research/rounds/R4/vm3.json" :vweight 15.0 :vblend 1.0}])]}))
  (flush))
