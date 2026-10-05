;; RC3 vs RC3 with rich-credit 15 (dev mix, both sides).
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      rc3 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
               :rich-credit 20 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      dev (fn [tag spec] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                          :games-log (str R "/eval-R.jsonl")})]
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments [(dev "r-rc3" [:champion rc3])
                           (dev "r-rich15" [:champion (assoc-in rc3 [:w :rich-credit] 15)])]}))
  (flush))
