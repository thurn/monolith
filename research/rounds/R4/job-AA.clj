;; Frozen ab (f94ea33): always-on fixes since y (bypass events valued by the ice they skip, bounce subs cost like
;; program trash, stealth-only breakers need stealth credits, Euler cost, Ansel subs, Obokata at grip, Archives traps,
;; Ika self-host pruned, Aesop targets, phase 1.2 fallback) + two options: :core-damage (eval) and :tag-threat (w).
;; RC8 = RC7 + both. Dev reviews 15 + 16 for RC8 (gate: pooled >= 3.5), then dev-mix A/B, 300 paired seeds:
;; RC7 (null) vs + core-damage, + tag-threat, RC8.
(require 'monolith.ai.gamelog 'monolith.ai.evalset 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc6 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      rc7 (update rc6 :runner-opts update :w assoc :late-tag-removal true)
      both (fn [spec f] (-> spec (update :corp-opts f) (update :runner-opts f)))
      core #(assoc-in % [:eval :core-damage] 1.0)
      tagt #(assoc-in % [:w :tag-threat] true)
      rc8 (both rc7 (comp core tagt))
      ms [:worlds-2012-b :classique-2022-d :classique-2026-b :worlds-2013-a :worlds-2018-b :worlds-2020-b :classique-2026-d :worlds-2021-b :worlds-2016-a :worlds-2022-b]
      dev (fn [tag spec & [null?]] {:agent [:champion spec] :tag tag :seeds (range 100000 100300) :matchups monolith.ai.sweep/dev-mix
                                    :games-log (str R "/eval-AA.jsonl") :null? (boolean null?)})]
  (doseq [[d seeds] [["review-dev15" (range 200680 200700)] ["review-dev16" (range 200700 200720)]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set {:candidate [:champion rc8] :matchups ms :seeds seeds
                                                       :dir (str R "/" d) :threads 16})))
    (flush))
  (println (monolith.ai.evalset/run-many
            {:threads 18
             :experiments [(dev "aa-rc7" rc7 true) (dev "aa-core" (both rc7 core)) (dev "aa-tag" (both rc7 tagt)) (dev "aa-rc8" rc8)]}))
  (flush))
