;; Search budget on RC7 (frozen y, same code as job Y, so arms pair with eval-Y's y-rc7 rows on seeds 100000-100299):
;; bigger beam search (max-apps 6000, beam 8) and two-turn rerank rollouts with 2 samples.
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc6 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      rc7 (update rc6 :runner-opts update :w assoc :late-tag-removal true)
      both (fn [m] (-> rc7 (update :corp-opts merge m) (update :runner-opts merge m)))
      dev (fn [tag spec] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                          :games-log (str R "/eval-Z.jsonl")})]
  (println (monolith.ai.evalset/run-many
            {:threads 16
             :experiments [(dev "z-big" [:champion (both {:max-apps 6000 :beam 8})])
                           (dev "z-rr2" [:champion (both {:rerank-turns 2 :rerank-samples 2})])]}))
  (flush))
