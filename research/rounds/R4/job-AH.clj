;; Frozen ah (9be27ef): RC12 = RC11 + Runner :run-ap-eval 1.0 + :run-click-credit 1.0 (planner run lines on the evaluator scale,
;; clicks left after a run credited). Review sets (8 threads, alongside job AB): review-runner6 (modern dev Runner decks,
;; diagnostic), dev reviews 21 + 22 (gate: pooled >= 3.5; plus modern Runner >= 3.2).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc12 {:corp-opts (update rc4 :w dissoc :dig-ice)
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0)}
      modern (vec (mapcat #(repeat 2 %) [:worlds-2019-a :worlds-2019-b :worlds-2020-a :worlds-2020-b :worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b]))
      ms [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]]
  (doseq [[d seeds m side] [["review-runner6" (range 200880 200900) modern :runner]
                            ["review-dev21" (range 200920 200940) ms nil] ["review-dev22" (range 200940 200960) ms nil]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc12] :matchups m :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
