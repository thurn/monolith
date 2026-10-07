;; Planner trace for the Runner (RC11 spec): replays review game DIAG="<key.edn> log-NN.txt" up to the first Runner
;; decision of TURN, then lets the champion choose with planner/*debug* on (top lines per depth) and prints S1's choice.
(require '[monolith.ai.harness :as h] '[monolith.ai.tourney :as t] '[monolith.ai.agents.planner :as planner] '[clojure.edn :as edn])
(let [genv (fn [k] (or (System/getenv k) (some-> (ns-resolve 'user (symbol k)) deref)))
      [keyf file] (clojure.string/split (genv "DIAG") #" ")
      turn (parse-long (genv "TURN"))
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      ev {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0 :core-damage 1.0}
      base {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0 :eval ev}
      rc4 (assoc base :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true
                          :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true :no-naked-agendas true :tag-threat true})
      spec (or (some-> (genv "SPEC") read-string)
               {:corp-opts (update rc4 :w dissoc :dig-ice)
                :runner-opts (update rc4 :w assoc :remote-denial 0.5 :run-urgency 0.3 :late-tag-removal true :rez-tax 0.5)})
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      n (atom 0) found (atom nil)]
  (binding [h/*hq-memory* true
            h/*on-step* (fn [gm d a] (let [s @(:state gm)]
                                       (when (and (nil? @found) (= :runner (:side d)) (= :turn (:kind d)) (= turn (:turn s)) (= :runner (:active-player s)))
                                         (reset! found @n))
                                       (swap! n inc)))]
    (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)}))
  (let [k @found
        inner (t/make-agent [:champion spec] :runner)
        calls (atom 0)
        wrap (reify h/Agent
               (choose [_ ctx]
                 (swap! calls inc)
                 (let [o @(:obs ctx)]
                   (println :T (:turn o) :cr (get-in o [:runner :credit]) :clicks (get-in o [:runner :click]) :grip (mapv :title (get-in o [:runner :hand])))
                   (let [idx (binding [planner/*debug* true] (h/choose inner ctx))]
                     (println :chose (:label (nth (:actions ctx) idx)))
                     idx))))]
    (println :replaying k)
    (binding [h/*hq-memory* true]
      (h/play-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :replay (take k (:log g))
                    :agents {:corp (t/make-agent :s1ref :corp) :runner wrap}
                    :stop-fn (fn [_] (>= @calls 1))}))
    nil))
