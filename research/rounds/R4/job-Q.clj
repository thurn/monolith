;; RC3 candidate = RC2 + prune-runs + w-program + remote-ice-prior 1 + hq-memory + no-naked-agendas on code with the tax-ETR,
;; Indexing and Stimhack-grip fixes. Dev review 9 first, then dev-mix A/B: base (RC2 + prune + wprog, null),
;; RC3, RC3 without hq-memory, RC3 without no-naked-agendas.
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.gamelog)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
            :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                :rich-credit 20 :prune-runs true :w-program 6.0}
            :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      rc3 (update base :w assoc :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true)
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log (str R "/eval-Q.jsonl") :null? (boolean null?)})]
  (println (count (monolith.ai.gamelog/review-set
                   {:candidate [:champion rc3]
                    :matchups [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]
                    :seeds (range 200560 200580) :dir (str R "/review-dev9") :threads 8})))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [(dev "q-base" [:champion base] true)
              (dev "q-rc3" [:champion rc3])
              (dev "q-rc3-nohq" [:champion (update rc3 :w dissoc :hq-memory)])
              (dev "q-rc3-naked" [:champion (update rc3 :w dissoc :no-naked-agendas)])]}))
  (flush))
