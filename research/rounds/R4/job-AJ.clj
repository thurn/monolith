;; Corp strength on corp-hard (dev matchups where our Corp wins least), Corp side, 300 paired seeds (310000-310299), frozen ai:
;; RC12's Corp (null) vs RC1's Corp options and RC13's Corp (central-ice-first). Paired with job AJ-old (RC1 on RC1's code, frozen j).
(require 'monolith.ai.evalset)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      hard [:classique-2025-a :worlds-2017-a :worlds-2020-a :worlds-2018-b :classique-2022-a :worlds-2017-b :worlds-2019-a :worlds-2021-a]
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc1c (assoc base :eval (dissoc ev :core-damage) :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true})
      rc12c (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :empty-remote-ice true :dig-breakers true
                            :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                            :tag-threat true})
      rc13c (update rc12c :w assoc :central-ice-first true)
      cp (fn [tag c & [null?]] {:agent [:champion {:corp-opts c :runner-opts c}] :tag tag :seeds (range 310000 310300)
                                :matchups hard :sides [:corp] :null? (boolean null?) :games-log (str R "/eval-AJ.jsonl")})]
  (println (monolith.ai.evalset/run-many {:threads 18 :experiments [(cp "aj-rc12c" rc12c true) (cp "aj-rc1c" rc1c) (cp "aj-rc13c" rc13c)]}))
  (flush))
