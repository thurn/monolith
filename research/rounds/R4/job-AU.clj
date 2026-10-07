;; Frozen au (486efa6): RC21 = RC20 with :rd-exposure 0.5, on code where S1 shares run-ap-eval, exposed hosted credits unhalved,
;; Corp draw/rez/trash fixes. Review sets on modern-e..h: review-mx4, review-mx5 (both sides) and review-mxr3 (Runner only).
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [R "/home/dthurn/monolith/research/rounds/R4"
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true
                          :tag-threat true})
      rc21 {:corp-opts (-> rc4 (update :w #(-> % (dissoc :dig-ice :prune-runs :w-program :remote-ice-prior :hq-memory :dig-breakers) identity)) (assoc-in [:eval :rd-exposure] 0.5))
            :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5 :run-ap-eval 1.0 :run-click-credit 1.0 :w-damage 3.0)}
      ms (vec (mapcat #(repeat 2 %) [:modern-e :modern-f :modern-g :modern-h]))]
  (doseq [[d seeds side] [["review-mx4" (range 201360 201380) nil] ["review-mx5" (range 201380 201400) nil] ["review-mxr3" (range 201400 201420) :runner]]]
    (.mkdirs (java.io.File. (str R "/" d)))
    (println d (count (monolith.ai.gamelog/review-set (cond-> {:candidate [:champion rc21] :matchups ms :seeds seeds :dir (str R "/" d) :threads 12}
                                                        side (assoc :side-only side)))))
    (flush)))
