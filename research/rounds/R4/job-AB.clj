;; Corp-side ablation on corp-proxy (NEH/asset dev Corps resembling held-out), frozen ad (db55cb5), 300 paired seeds, Corp side only:
;; RC8's Corp (null) vs RC1's Corp options and RC8 minus each Corp option. Question: which current Corp options cost
;; held-out-like Corp strength (RC7 held-out g_c +0.55 vs bar +0.85; RC1 +1.07 on older code).
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc1c (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true})
      rc8c (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :empty-remote-ice true :dig-breakers true
                           :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                           :tag-threat true})
      without (fn [k] (update rc8c :w dissoc k))
      cp (fn [tag c & [null?]] {:agent [:champion {:corp-opts c :runner-opts c}] :tag tag :seeds (range 300000 300300)
                                :matchups monolith.ai.sweep/corp-proxy :sides [:corp] :null? (boolean null?)
                                :games-log (str R "/eval-AB.jsonl")})]
  (println (monolith.ai.evalset/run-many
            {:threads 18
             :experiments [(cp "ab-rc8c" rc8c true) (cp "ab-rc1c" rc1c) (cp "ab-norich" (without :rich-credit))
                           (cp "ab-nonaked" (without :no-naked-agendas)) (cp "ab-noempty" (without :empty-remote-ice))
                           (cp "ab-norip" (without :remote-ice-prior)) (cp "ab-notag" (without :tag-threat))]}))
  (flush))
