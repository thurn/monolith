;; Release-candidate evaluation (fill RC-SPEC and RC-TAG). One pool: dev mix (both sides, own null),
;; Corp proxies (Corp side, own null), and the pre-registered held-out T2 run (seeds 900000-900299,
;; own null). Puzzles and the held-out review set run separately (monolith.ai.confirm).
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      spec RC-SPEC
      tag RC-TAG]
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments
             [{:agent spec :tag tag :seeds (range 900000 900300) :matchups monolith.ai.sweep/holdout-mix
               :games-log (str R "/holdout-" tag ".jsonl") :null? true}
              {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
               :games-log (str R "/rc-dev-" tag ".jsonl") :null? true}
              {:agent spec :tag tag :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy :sides [:corp]
               :games-log (str R "/rc-proxy-" tag ".jsonl") :null? true}]}))
  (flush))
