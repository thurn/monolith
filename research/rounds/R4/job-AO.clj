;; Corp combinations on corp-hard (job AL: every RC12 Corp option leans negative there; no-naked-agendas significantly,
;; +33/-17). Frozen an, Corp side, 300 paired seeds (310000-310299). RC12's Corp (null) vs: - runner knobs (the Corp's model
;; of the Runner: prune-runs, w-program, remote-ice-prior, hq-memory, dig-breakers; invisible to reviewers), that minus
;; rich-credit, minus empty-remote-ice, minus all three, and minus all three and no-naked-agendas.
(require 'monolith.ai.evalset)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      hard [:classique-2025-a :worlds-2017-a :worlds-2020-a :worlds-2018-b :classique-2022-a :worlds-2017-b :worlds-2019-a :worlds-2021-a]
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc12c (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :empty-remote-ice true :dig-breakers true
                            :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                            :tag-threat true})
      without (fn [c & ks] (update c :w #(apply dissoc % ks)))
      nork (without rc12c :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers)
      cp (fn [tag c & [null?]] {:agent [:champion {:corp-opts c :runner-opts c}] :tag tag :seeds (range 310000 310300)
                                :matchups hard :sides [:corp] :null? (boolean null?) :games-log (str R "/eval-AO.jsonl")})]
  (println (monolith.ai.evalset/run-many
            {:threads 18
             :experiments [(cp "ao-rc12c" rc12c true) (cp "ao-nork" nork) (cp "ao-nork-rich" (without nork :rich-credit))
                           (cp "ao-nork-empty" (without nork :empty-remote-ice)) (cp "ao-nork-both" (without nork :rich-credit :empty-remote-ice))
                           (cp "ao-nork-all" (without nork :rich-credit :empty-remote-ice :no-naked-agendas))]}))
  (flush))
