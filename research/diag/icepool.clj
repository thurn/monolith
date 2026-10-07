;; At one Runner turn of a review game (DIAG, TURN, SERVER e.g. "remote1"), prints the Runner's ice pool for that server's
;; outermost unrezzed ice and the run outcome per candidate ice (u, p, how).
(require '[monolith.ai.harness :as h] '[monolith.ai.observe :as observe] '[monolith.ai.agents.heuristic :as s1]
         '[monolith.ai.knowledge.servers :as srv] '[monolith.ai.knowledge.runcalc :as runcalc] '[monolith.ai.knowledge.cards :as cards] '[clojure.edn :as edn])
(let [genv (fn [k] (or (System/getenv k) (some-> (ns-resolve 'user (symbol k)) deref)))
      [keyf file] (clojure.string/split (genv "DIAG") #" ")
      turn (parse-long (genv "TURN")) k (keyword (genv "SERVER"))
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      wts (merge @(requiring-resolve 'monolith.ai.knowledge.vfeat/default-weights)
                 {:credit-knee2 12 :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true
                  :remote-denial 0.5 :run-urgency 0.3 :rez-tax 0.5})
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      prev (atom nil) done (atom false)]
  (binding [h/*hq-memory* true
            h/*on-step* (fn [gm d a]
                          (let [p @prev]
                            (when (and p (not @done) (= :runner (:side a)) (= :runner (:active-player p)) (= turn (:turn p)))
                              (reset! done true)
                              (let [o (observe/observe p :runner)
                                    env {:obs o :weights wts :decks decks :side :runner :mem (atom {})}
                                    ropts (s1/run-opts env)
                                    pool (srv/ice-pool o (:corp-decklist ropts))
                                    value (srv/content-value o k ropts)]
                                (println :value value :credits (get-in o [:runner :credit]) :corp-cr (get-in o [:corp :credit])
                                         :breakers (mapv :title (srv/icebreakers o)) :eval (select-keys (s1/server-run-eval env k {}) [:u :p :how]))
                                (doseq [[t n] (sort-by (comp - val) pool)
                                        :let [r (runcalc/evaluate {:ices [{:title t :rezzed true :subroutines (:subroutines (game.core.card-defs/card-def {:title t}))}]
                                                                   :remote? true :breakers (srv/icebreakers o) :credits (get-in o [:runner :credit])
                                                                   :hand (count (get-in o [:runner :hand])) :corp-credits 0 :value value
                                                                   :w-damage 2.0 :w-program 6.0})]]
                                  (println (format "  %-22s n=%s rez=%s u=%.1f p=%.2f %s" t n (:rez-cost (cards/printed-ice-model t true)) (double (:u r)) (double (:p r)) (:how r))))))
                            (reset! prev @(:state gm))))]
    (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)})
    nil))
