;; Frozen bd: RC26 = RC25 spec on code with unaffordable unrezzed ice passed, untagged meat kills, steal costs after breaking,
;; full mid-turn planner budget, :aid no-op prune, Sprint shuffle rule, Bloop discount; Runner :w-tag 3.5 (was 2.0) and
;; :tag-exposure (tagged Runner's resources priced at 0.7 x (cost + 1), top three). Review sets: review-m17, review-m18
;; (both sides, eight modern matchups) and review-mxr8 (Runner only, archetypes).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc26 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) (assoc :score-margin 30.0))) (assoc-in [:eval :rd-exposure] 1.0))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0
                                 :w-damage 6.0 :w-tag 3.5 :tag-exposure true)}
      all8 (vec (mapcat #(repeat 2 %) monolith.ai.sweep/modern-mix))
      arch (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))]
  (doseq [[d seeds m side] [["review-m17" (range 201660 201680) all8 nil] ["review-m18" (range 201680 201700) all8 nil]
                            ["review-mxr8" (range 201700 201720) arch :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc26] :matchups m :seeds seeds :dir (str R "/" d) :threads 10}
                                                        side (assoc :side-only side)))))
    (flush)))
