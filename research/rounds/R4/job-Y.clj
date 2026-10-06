;; Two fresh dev review sets (13, 14; gate: pooled candidate mean >= 3.5 over 20 games) for RC7 = RC6 +
;; late-tag-removal on the code with the review-11/12 fixes, then RC7 vs RC6 on the dev mix and corp-proxy.
(require 'monolith.ai.gamelog 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc1 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true})
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc5 {:corp-opts rc1 :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      rc6 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      rc7 (update rc6 :runner-opts update :w assoc :late-tag-removal true)
      cand rc7
      ms [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]]
  (doseq [[d seeds] [["review-dev13" (range 200640 200660)] ["review-dev14" (range 200660 200680)]]]
    (println d (count (monolith.ai.gamelog/review-set {:candidate [:champion cand] :matchups ms :seeds seeds
                                                       :dir (str R "/" d) :threads 18})))
    (flush))
  (println (monolith.ai.evalset/run-many
            {:threads 19
             :experiments [{:agent [:champion rc6] :tag "y-rc6" :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                            :games-log (str R "/eval-Y.jsonl") :null? true}
                           {:agent [:champion rc7] :tag "y-rc7" :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                            :games-log (str R "/eval-Y.jsonl")}
                           {:agent [:champion rc6] :tag "py-rc6" :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                            :sides [:corp] :games-log (str R "/proxy-Y.jsonl")}
                           {:agent [:champion rc7] :tag "py-rc7" :seeds (range 300000 300300) :matchups monolith.ai.sweep/corp-proxy
                            :sides [:corp] :games-log (str R "/proxy-Y.jsonl")}]}))
  (flush))
