;; RC1 on the current code (runner fixes since 18:20) vs RC1 + dig-breakers / grip-breakers, dev mix both sides.
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [rc {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
          :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
          :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log "/home/dthurn/monolith/research/rounds/R4/eval-M.jsonl" :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many
            {:threads 14
             :experiments
             [(dev "m-rc1" [:champion rc] true)
              (dev "m-db" [:champion (assoc-in rc [:w :dig-breakers] true)])
              (dev "m-dbgb" [:champion (-> rc (assoc-in [:w :dig-breakers] true) (assoc-in [:w :grip-breakers] true))])]}))
  (flush))
