;; At each Runner play of a bypass run event in a review game, prints S1's bypass gain per server.
(require '[monolith.ai.harness :as h] '[monolith.ai.observe :as observe] '[monolith.ai.agents.heuristic :as s1]
         '[monolith.ai.knowledge.cards :as cards] '[clojure.edn :as edn])
(let [[keyf file] (clojure.string/split (System/getenv "DIAG") #" ")
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      wts (merge @(requiring-resolve 'monolith.ai.knowledge.vfeat/default-weights)
                 {:credit-knee2 12 :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true})
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      prev (atom nil)]
  (binding [h/*hq-memory* true
            h/*on-step* (fn [gm d a]
                          (let [p @prev]
                            (when (and p (= :runner (:side a)) (re-find #"(?i)bypass" (str (:text (cards/printed (get-in a [:args :card :title]))))))
                              (let [o (observe/observe p :runner)
                                    env {:obs o :weights wts :decks decks :side :runner :mem (atom {})}]
                                (println :T (:turn p) :label (:label a)
                                         :gain (into {} (for [k (keys (get-in o [:corp :servers]))] [k (s1/bypass-gain env k)]))
                                         :opts (mapv (fn [[u a k]] [(:label a) k u]) (s1/event-run-options env)))))
                            (reset! prev @(:state gm))))]
    (println (select-keys (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)}) [:winner :turn]))))
