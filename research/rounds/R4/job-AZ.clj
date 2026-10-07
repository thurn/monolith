;; Runner variants on the eight modern matchups, frozen az (ccc4753: + broke-facecheck prune, pay-or-ETR rule), Runner side,
;; 300 paired seeds (330000-330299): RC24 Runner (null; w-damage 6) vs run-ap-eval 0.6 and vs w-damage 8. Win rates + T3 proxies
;; (job AX: run-ap-eval 1.0 halves ignored remotes but doubles broke facechecks and flatlines).
(require 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      corp (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
      runner (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 6.0 :w-tag 2.0)
      ex (fn [tag r & [null?]] {:agent [:champion {:corp-opts corp :runner-opts r}] :tag tag :seeds (range 330000 330300)
                                :matchups monolith.ai.sweep/modern-mix :sides [:runner] :games-log (str R "/eval-AZ.jsonl") :null? (boolean null?)})]
  (println (monolith.ai.evalset/run-many {:threads 16 :experiments [(ex "az-rc24" runner true) (ex "az-rae06" (update runner :w assoc :run-ap-eval 0.6))
                                                                    (ex "az-wd8" (update runner :w assoc :w-damage 8.0))]}))
  (flush))
