;; Win rates on the eight modern matchups, frozen as (7eb1ace): RC20 (null) vs RC18's spec on the same code, both sides,
;; 300 paired seeds (320000-320299). Also gives T3 proxies on modern decks.
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      runner (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 3.0)
      rc18 {:corp-opts (-> rc4 (update :w dissoc :dig-ice) (assoc-in [:eval :rd-exposure] 1.0)) :runner-opts runner}
      rc20 {:corp-opts (-> rc4 (update :w dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts runner}
      ex (fn [tag spec & [null?]] {:agent [:champion spec] :tag tag :seeds (range 320000 320300) :matchups monolith.ai.sweep/modern-mix
                                   :games-log (str R "/eval-AT.jsonl") :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many {:threads 12 :experiments [(ex "at-rc20" rc20 true) (ex "at-rc18" rc18)]}))
  (flush))
