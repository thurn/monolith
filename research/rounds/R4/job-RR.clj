;; Diagnostic (not blind, not pre-registered): 10 RC1-Runner games vs :s1ref on runner-proxy decks.
(require 'monolith.ai.gamelog 'monolith.ai.sweep)
(let [rc {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
          :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
          :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      ms monolith.ai.sweep/runner-proxy
      pool (java.util.concurrent.Executors/newFixedThreadPool 3)
      futs (doall (for [i (range 10)]
                    (.submit pool ^Callable (fn [] (let [m (nth ms (mod i (count ms)))
                                                         {:keys [result log]} (monolith.ai.gamelog/play-and-render
                                                                                {:corp :s1ref :runner [:champion rc] :seed (+ 510000 i)
                                                                                 :corp-deck (keyword (str (name m) "-corp"))
                                                                                 :runner-deck (keyword (str (name m) "-runner"))})]
                                                     (spit (format "/home/dthurn/monolith/research/rounds/R4/review-runner1/log-%02d.txt" (inc i))
                                                           (str "Review the RUNNER's play in this game.\nWinner: " (some-> (:winner result) name)
                                                                " (" (:reason result) "), turn " (:turn result) "\n\n" log "\n"))
                                                     [i m (:winner result)])))))]
  (doseq [f futs] (println @f))
  (.shutdown pool))
