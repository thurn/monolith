;; Replays a reviewed game (DIAG="<key.edn> log-NN.txt") and prints, for each Corp action, the Corp's
;; hand, credits and central ice counts.
(require '[monolith.ai.harness :as h] '[clojure.edn :as edn])
(let [[keyf file] (clojure.string/split (System/getenv "DIAG") #" ")
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      prev (atom nil)]
  (binding [h/*hq-memory* true
            h/*on-step* (fn [gm d a]
                          (let [p @prev]
                            (when (and p (= :corp (:side a)) (= :turn (:kind d)))
                              (println :T (:turn p) :clk (get-in p [:corp :click]) :cr (get-in p [:corp :credit]) :act (:label a)
                                       :rd-ice (count (get-in p [:corp :servers :rd :ices])) :hq-ice (count (get-in p [:corp :servers :hq :ices]))
                                       :hand (mapv :title (get-in p [:corp :hand]))))
                            (reset! prev @(:state gm))))]
    (println (select-keys (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)}) [:winner :turn]))))
