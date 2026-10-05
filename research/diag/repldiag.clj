(require '[monolith.ai.harness :as h] '[monolith.ai.observe :as observe] '[monolith.ai.agents.heuristic :as s1]
         '[monolith.ai.moves :as moves] '[monolith.ai.knowledge.servers :as srv] '[clojure.edn :as edn])
(let [[keyf file] (clojure.string/split (System/getenv "DIAG") #" ")
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      wts (merge @(requiring-resolve 'monolith.ai.knowledge.vfeat/default-weights)
                 {:credit-knee2 12 :rich-credit 20 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true})
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      prev (atom nil)]
  (binding [h/*hq-memory* true
            h/*on-step* (fn [gm d a]
                          (let [p @prev]
                            (when p
                              (let [pr (moves/current-prompt p :runner)]
                                (when (and (= :runner (:side a)) (re-find #"(?i)replacement|run on|successful run" (str (:msg pr))))
                                  (let [o (observe/observe p :runner)
                                        k (first (get-in p [:run :server]))
                                        env {:obs o :weights wts :decks decks :side :runner}]
                                    (println :T (:turn p) :msg (:msg pr) :chose (:label a) :k k
                                             :pts (get-in p [:runner :agenda-point])
                                             :content (when k (mapv (juxt :title :advance-counter) (get-in p [:corp :servers k :content])))
                                             :bv (when k (try (srv/content-value o k (s1/run-opts env)) (catch Throwable t (str t)))))))))
                            (reset! prev @(:state gm))))]
    (println (select-keys (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)}) [:winner :turn]))))
