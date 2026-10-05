;; RC2 base on code with the Siphon/Archives fixes vs + rd-agendas, + rich-credit 20, + both (dev mix, both sides).
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [rc {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
          :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers DIG-BREAKERS}
          :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      rda (assoc-in rc [:eval :rd-agendas] 1.0)
      rich (assoc-in rc [:w :rich-credit] 20)
      both (assoc-in rda [:w :rich-credit] 20)
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log "/home/dthurn/monolith/research/rounds/R4/eval-N.jsonl" :null? (boolean null?)})
      prx (fn [tag spec] {:agent spec :tag tag :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                          :sides [:corp] :games-log "/home/dthurn/monolith/research/rounds/R4/proxy-N.jsonl"})]
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [(dev "n-rc2" [:champion rc] true)
              (dev "n-rda" [:champion rda])
              (dev "n-rich" [:champion rich])
              (dev "n-both" [:champion both])
              (prx "pn-rc2" [:champion rc])
              (prx "pn-both" [:champion both])]}))
  (flush))
