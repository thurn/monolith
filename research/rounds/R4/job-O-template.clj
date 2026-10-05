;; After job N: T3 proxies on N's games, Runner remote-ice-prior A/B on top of RC2, then dev review 7 with RC2.
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.blunders 'monolith.ai.gamelog)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc2 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true RC2-EXTRA-W}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 RC2-EXTRA-EVAL}}
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :sides [:runner] :games-log (str R "/eval-O.jsonl") :null? (boolean null?)})]
  (doseq [r (monolith.ai.blunders/summarize (str R "/eval-N.jsonl") #{"n-rc2" "n-rda" "n-rich" "n-both"})] (println :proxy r))
  (flush)
  (println (count (monolith.ai.gamelog/review-set
                   {:candidate [:champion rc2]
                    :matchups [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]
                    :seeds (range 200520 200540) :dir (str R "/review-dev7") :threads 8})))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 18
             :experiments
             [(dev "o-rc2" [:champion rc2])
              (dev "o-rip05" [:champion (assoc-in rc2 [:w :remote-ice-prior] 0.5)])
              (dev "o-rip1" [:champion (assoc-in rc2 [:w :remote-ice-prior] 1.0)])]}))
  (flush))
