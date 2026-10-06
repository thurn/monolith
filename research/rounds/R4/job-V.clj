;; (1) T3 proxies on job T's Runner games; (2) Corp-option ablation of RC4 on corp-proxy (Corp side);
;; (3) RC5 = RC1's Corp options + RC4's Runner options (+ remote-denial 0.5, run-urgency 0.3) vs RC4, dev mix.
(require 'monolith.ai.evalset 'monolith.ai.sweep 'monolith.ai.blunders)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc1 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true})
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc5 {:corp-opts rc1
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      cprx (fn [tag spec] {:agent spec :tag tag :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                           :sides [:corp] :games-log (str R "/proxy-V.jsonl")})
      dev (fn [tag spec & [null?]] {:agent spec :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log (str R "/eval-V.jsonl") :null? (boolean null?)})]
  (doseq [r (monolith.ai.blunders/summarize (str R "/eval-T.jsonl") #{"t-rc4" "t-den05" "t-den1" "t-urg03"})
          :when (= :runner (:side r))]
    (println :proxy (:tag r) (:tested r)))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments [(dev "v-rc4" [:champion rc4] true)
                           (dev "v-rc5" [:champion rc5])
                           (cprx "pv-rc1" [:champion rc1])
                           (cprx "pv-rc4" [:champion rc4])
                           (cprx "pv-nodig" [:champion (update rc4 :w dissoc :dig-ice)])
                           (cprx "pv-noeri" [:champion (update rc4 :w dissoc :empty-remote-ice)])
                           (cprx "pv-norich" [:champion (update rc4 :w dissoc :rich-credit)])
                           (cprx "pv-nonaked" [:champion (update rc4 :w dissoc :no-naked-agendas)])]}))
  (flush))
