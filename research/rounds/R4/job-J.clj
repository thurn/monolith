(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [rc {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
          :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
          :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log "/home/dthurn/monolith/research/rounds/R4/eval-J.jsonl" :null? (boolean null?)})
      prx (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                                    :sides [:corp] :games-log "/home/dthurn/monolith/research/rounds/R4/proxy-J.jsonl" :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [;; pre-registered held-out T2 run for RC1 (LOG.md 2026-10-04 09:15)
              {:agent [:champion rc] :tag "rc1" :seeds (range 900000 900300) :matchups monolith.ai.sweep/holdout-mix
               :games-log "/home/dthurn/monolith/research/rounds/R4/holdout-rc1.jsonl" :null? true}
              (dev "j0" :champion true)
              (dev "j-rc1" [:champion rc])
              (prx "pj0" :champion true)
              (prx "pj-rc1" [:champion rc])
              (dev "j-rc1-nosm" [:champion (dissoc rc :s1-strong-margin)])
              (dev "j-rc1-vmR" [:champion (assoc rc :runner-opts {:vmodel "/home/dthurn/monolith/research/rounds/R4/vm3.json" :vweight 15.0 :vblend 1.0})])
              (dev "j-rc1-dig" [:champion (assoc-in rc [:w :dig-ice] true)])
              (prx "pj-rc1-nosm" [:champion (dissoc rc :s1-strong-margin)])
              (prx "pj-rc1-dig" [:champion (assoc-in rc [:w :dig-ice] true)])]}))
  (flush))
