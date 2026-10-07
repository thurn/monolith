;; RC1's Corp on RC1's own code (frozen j = b809ef2), corp-hard, same seeds as job AJ: code-regression test for the Corp.
(require 'monolith.ai.evalset)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      hard [:classique-2025-a :worlds-2017-a :worlds-2020-a :worlds-2018-b :classique-2022-a :worlds-2017-b :worlds-2019-a :worlds-2021-a]
      rc1 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}]
  (println (monolith.ai.evalset/run-many
            {:threads 18 :experiments [{:agent [:champion rc1] :tag "aj-rc1-old" :seeds (range 310000 310300) :matchups hard
                                        :sides [:corp] :games-log (str R "/eval-AJ.jsonl")}]}))
  (flush))
