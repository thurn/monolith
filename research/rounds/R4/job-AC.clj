;; Frozen ac (1a249ee). Runner :rez-tax (Corp rez spending counts for the Runner). (1) Runner-only diagnostic review
;; on modern dev Runner decks (review-runner3) for RC9 = RC8 + rez-tax 0.5; (2) dev-mix A/B, Runner side, 300 paired
;; seeds: RC8 vs rez-tax 0.5 / 1.0.
(require 'monolith.ai.gamelog 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc8 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true)}
      rt (fn [x] (update rc8 :runner-opts update :w assoc :rez-tax x))
      ms (vec (mapcat #(repeat 2 %) [:worlds-2019-a :worlds-2019-b :worlds-2020-a :worlds-2020-b :worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b]))
      d "review-runner3"
      dev (fn [tag spec] {:agent [:champion spec] :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                          :sides [:runner] :games-log (str R "/eval-AC.jsonl")})]
  (.mkdirs (java.io.File. (str R "/" d)))
  (println d (count (monolith.ai.gamelog/review-set {:candidate [:champion (rt 0.5)] :matchups ms :seeds (range 200820 200840)
                                                     :dir (str R "/" d) :threads 16 :side-only :runner})))
  (flush)
  (println (monolith.ai.evalset/run-many
            {:threads 18 :experiments [(dev "ac-rc8" rc8) (dev "ac-rt05" (rt 0.5)) (dev "ac-rt10" (rt 1.0))]}))
  (flush))
