(require 'monolith.ai.gamelog)
(let [rc2 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}]
  (println (count (monolith.ai.gamelog/review-set
                   {:candidate [:champion rc2]
                    :matchups [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]
                    :seeds (range 200500 200520) :dir "/home/dthurn/monolith/research/rounds/R4/review-dev6" :threads 6}))))
