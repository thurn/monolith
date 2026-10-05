;; Runner side on the dev mix: RC4 vs + remote-denial 0.5 / 1.0, + run-urgency 0.3 (contesting scoring remotes).
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc4 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
               :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      dev (fn [tag spec] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                          :sides [:runner] :games-log (str R "/eval-T.jsonl")})]
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments [(dev "t-rc4" [:champion rc4])
                           (dev "t-den05" [:champion (assoc-in rc4 [:w :remote-denial] 0.5)])
                           (dev "t-den1" [:champion (assoc-in rc4 [:w :remote-denial] 1.0)])
                           (dev "t-urg03" [:champion (assoc-in rc4 [:w :run-urgency] 0.3)])]}))
  (flush))
