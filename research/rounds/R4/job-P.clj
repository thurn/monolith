;; RC2 (base + rich-credit) vs + prune-runs, + w-program 6, + both; dev mix both sides; then dev review 8 with both.
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.gamelog)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc2 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true :rich-credit 20}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      both (-> rc2 (assoc-in [:w :prune-runs] true) (assoc-in [:w :w-program] 6.0))
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log (str R "/eval-P.jsonl") :null? (boolean null?)})]
  (println (count (monolith.ai.gamelog/review-set
                   {:candidate [:champion both]
                    :matchups [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]
                    :seeds (range 200540 200560) :dir (str R "/review-dev8") :threads 8})))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [(dev "p-rc2" [:champion rc2] true)
              (dev "p-prune" [:champion (assoc-in rc2 [:w :prune-runs] true)])
              (dev "p-wprog" [:champion (assoc-in rc2 [:w :w-program] 6.0)])
              (dev "p-both" [:champion both])]}))
  (flush))
