;; Frozen ad (db55cb5): all Runner knowledge fixes of 2026-10-06 + tag-threat fix. Replaces jobs AA/AC (AA stopped:
;; its RC8 carried a tag-threat bug that left resources exposed, see LOG).
;; (1) Runner-only diagnostic review on modern dev Runner decks (review-runner3) for RC9 = RC7 + core-damage + tag-threat
;;     + rez-tax 0.5 (Runner); (2) dev-mix A/B both sides, 300 paired seeds: RC7 (null) vs + core-damage, + rez-tax 0.5, RC9.
(require 'monolith.ai.gamelog 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc7 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true)}
      both (fn [spec f] (-> spec (update :corp-opts f) (update :runner-opts f)))
      core #(assoc-in % [:eval :core-damage] 1.0)
      rt (fn [spec] (update spec :runner-opts update :w assoc :rez-tax 0.5))
      rc9 (rt (both rc7 (comp core #(assoc-in % [:w :tag-threat] true))))
      ms (vec (mapcat #(repeat 2 %) [:worlds-2019-a :worlds-2019-b :worlds-2020-a :worlds-2020-b :worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b]))
      d "review-runner3"
      dev (fn [tag spec & [null?]] {:agent [:champion spec] :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log (str R "/eval-AD.jsonl") :null? (boolean null?)})]
  (.mkdirs (java.io.File. (str R "/" d)))
  (println d (count (monolith.ai.gamelog/review-set {:candidate [:champion rc9] :matchups ms :seeds (range 200820 200840)
                                                     :dir (str R "/" d) :threads 16 :side-only :runner})))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 18 :experiments [(dev "ad-rc7" rc7 true) (dev "ad-core" (both rc7 core)) (dev "ad-rt05" (rt rc7)) (dev "ad-rc9" rc9)]}))
  (flush))
