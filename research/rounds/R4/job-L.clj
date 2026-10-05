(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [rc {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
          :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
          :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      rp (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 500000 500300) :matchups monolith.ai.sweep/runner-proxy
                                   :sides [:runner] :games-log "/home/dthurn/monolith/research/rounds/R4/rproxy-L.jsonl" :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many
            {:threads 6
             :experiments
             [(rp "l-rc1" [:champion rc] true)
              (rp "l-db" [:champion (assoc-in rc [:w :dig-breakers] true)])
              (rp "l-gb" [:champion (assoc-in rc [:w :grip-breakers] true)])
              (rp "l-dbgb" [:champion (-> rc (assoc-in [:w :dig-breakers] true) (assoc-in [:w :grip-breakers] true))])
              (rp "l-rt2" [:champion (assoc rc :runner-opts {:rerank-turns 2})])
              (rp "l-vmR" [:champion (assoc rc :runner-opts {:vmodel "/home/dthurn/monolith/research/rounds/R4/vm3.json" :vweight 15.0 :vblend 1.0})])]}))
  (flush))
