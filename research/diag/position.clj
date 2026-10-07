;; Constructed-position test: builds a position with puzzles/build, optionally forces one action by label, then lets an
;; agent play until the Runner's run (or the active side's turn) ends, printing each decision. In the REPL:
;;   (in-ns 'user) (def POS "{:active :runner :corp-deck :modern-b-corp :runner-deck :modern-b-runner
;;                            :corp {:hand [\"Above the Law\"] :install [{:t \"Afshar\" :server \"HQ\" :rez true}]}
;;                            :runner {:credits 8 :install [\"Buzzsaw\"]}}")
;;   (def FORCE "run HQ") (def SPEC "<agent spec edn, e.g. [:champion {...}]>")
;;   (load-file "research/diag/position.clj")
(require 'monolith.ai.puzzles 'monolith.ai.agents.champion 'monolith.ai.moves)
(monolith.ai.engine/load!)
(let [genv (fn [k] (some-> (ns-resolve 'user (symbol k)) deref))
      pos (read-string (genv "POS"))
      spec (read-string (or (genv "SPEC") ":heuristic"))
      g (monolith.ai.puzzles/build (assoc pos :seed 1))
      st (:state g)
      side (:active pos)]
  (when-let [f (genv "FORCE")]
    (let [d (monolith.ai.moves/decision st)
          a (some #(when (= f (:label %)) %) (monolith.ai.moves/legal st d #{}))]
      (println :forced (:label a))
      (monolith.ai.engine/command! g side (:command a) (:args a))))
  (binding [monolith.ai.harness/*hq-memory* true
            monolith.ai.harness/*on-step* (fn [gm d a] (println (:side a) (:kind d) "|" (:label a)))]
    (monolith.ai.harness/play-game {:seed 1 :game g :corp-deck (:corp-deck pos) :runner-deck (:runner-deck pos)
                                    :agents {side (monolith.ai.tourney/make-agent spec side)
                                             (if (= side :corp) :runner :corp) (monolith.ai.tourney/make-agent :heuristic (if (= side :corp) :runner :corp))}
                                    :budget-ms 250
                                    :stop-fn (fn [s] (or (not= side (:active-player s)) (:end-turn s)
                                                         (and (genv "FORCE") (re-find #"^run" (genv "FORCE")) (not (:run s)))))}))
  (println :end {:runner-credits (get-in @st [:runner :credit]) :runner-points (get-in @st [:runner :agenda-point])
                 :corp-credits (get-in @st [:corp :credit]) :corp-points (get-in @st [:corp :agenda-point])}))
