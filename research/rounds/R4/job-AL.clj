;; Corp-option ablation on corp-hard (job AJ: RC1's Corp options beat RC12's +31/-20 there, the same direction as the
;; held-out T2 drop), frozen ak, Corp side, 300 paired seeds (310000-310299, same as AJ): RC12's Corp (null) minus each
;; option, and minus the Runner-model knobs carried in the Corp's weights.
(require 'monolith.ai.evalset)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      hard [:classique-2025-a :worlds-2017-a :worlds-2020-a :worlds-2018-b :classique-2022-a :worlds-2017-b :worlds-2019-a :worlds-2021-a]
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc12c (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :empty-remote-ice true :dig-breakers true
                            :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                            :tag-threat true})
      without (fn [& ks] (update rc12c :w #(apply dissoc % ks)))
      cp (fn [tag c & [null?]] {:agent [:champion {:corp-opts c :runner-opts c}] :tag tag :seeds (range 310000 310300)
                                :matchups hard :sides [:corp] :null? (boolean null?) :games-log (str R "/eval-AL.jsonl")})]
  (println (monolith.ai.evalset/run-many
            {:threads 18
             :experiments [(cp "al-rc12c" rc12c true) (cp "al-noempty" (without :empty-remote-ice)) (cp "al-norich" (without :rich-credit))
                           (cp "al-nonaked" (without :no-naked-agendas)) (cp "al-notag" (without :tag-threat))
                           (cp "al-nocore" (update rc12c :eval dissoc :core-damage))
                           (cp "al-norunk" (without :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers))]}))
  (flush))
