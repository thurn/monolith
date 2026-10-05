;; RC4 = RC3 (+ rich-credit RICH) on code with the review-9 fixes. Dev review 10, then dev-mix A/B:
;; RC4 (null) vs RC4 with a bigger search (max-apps 6000, beam 8).
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.gamelog)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc4 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
               :rich-credit RICH :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log (str R "/eval-S.jsonl") :null? (boolean null?)})]
  (println (count (monolith.ai.gamelog/review-set
                   {:candidate [:champion rc4]
                    :matchups [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]
                    :seeds (range 200580 200600) :dir (str R "/review-dev10") :threads 8})))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [(dev "s-rc4" [:champion rc4] true)
              (dev "s-rc4-big" [:champion (assoc rc4 :max-apps 6000 :beam 8)])]}))
  (flush))
