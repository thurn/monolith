;; Code-regression check for the held-out Corp drop: RC1's Corp spec on RC1's own code (frozen j = b809ef2) on the same
;; corp-proxy seeds as job AB (300000-300299, Corp side). Paired with AB's ab-rc1c (same spec, current code) and
;; ab-rc8c: separates always-on code changes since RC1 from option changes. :s1ref is frozen, so the opponent is equal.
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc1 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}]
  (println (monolith.ai.evalset/run-many
            {:threads 18
             :experiments [{:agent [:champion rc1] :tag "ae-rc1-old" :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                            :sides [:corp] :games-log (str R "/eval-AE.jsonl")}]}))
  (flush))
