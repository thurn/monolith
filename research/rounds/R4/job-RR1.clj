;; Diagnostic (not a gate): Runner-only review set on modern dev Runner decks (Worlds 2019-2022), RC8 (= RC7 +
;; core-damage + tag-threat) on frozen bb. Matchups doubled so the candidate's 10 games cover all 8.
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true})
      rc6 {:corp-opts (update rc4 :w dissoc :dig-ice)
           :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3)}
      rc7 (update rc6 :runner-opts update :w assoc :late-tag-removal true)
      both (fn [spec f] (-> spec (update :corp-opts f) (update :runner-opts f)))
      rc8 (both rc7 #(-> % (assoc-in [:eval :core-damage] 1.0) (assoc-in [:w :tag-threat] true)))
      ms (vec (mapcat #(repeat 2 %) [:worlds-2019-a :worlds-2019-b :worlds-2020-a :worlds-2020-b :worlds-2021-a :worlds-2021-b :worlds-2022-a :worlds-2022-b]))
      d "review-runner2"]
  (.mkdirs (java.io.File. (str R "/" d)))
  (println d (count (monolith.ai.gamelog/review-set {:candidate [:champion rc8] :matchups ms :seeds (range 200800 200820)
                                                     :dir (str R "/" d) :threads 8 :side-only :runner})))
  (flush))
