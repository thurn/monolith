;; Frozen ag (d52cb0d): RC11 = RC10 spec (RC7 + core-damage + tag-threat + rez-tax 0.5) on code with all fixes through d52cb0d
;; (adds D4v1d, Botulus/Boomerang/Endurance, digging draws at max hand). Review sets: review-runner5 (modern dev Runner decks),
;; dev reviews 19 + 20 (gate: pooled >= 3.5; plus modern Runner >= 3.2).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc11 {:corp-opts (update rc4 :w dissoc :dig-ice)
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5)}
      modern (vec (mapcat #(repeat 2 %) [:worlds-2019-a :worlds-2019-b :worlds-2020-a :worlds-2020-b :worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b]))
      ms [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]]
  (doseq [[d seeds m side] [["review-runner5" (range 200860 200880) modern :runner]
                            ["review-dev19" (range 200760 200780) ms nil] ["review-dev20" (range 200780 200800) ms nil]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc11] :matchups m :seeds seeds :dir (str R "/" d) :threads 8}
                                                        side (assoc :side-only side)))))
    (flush)))
