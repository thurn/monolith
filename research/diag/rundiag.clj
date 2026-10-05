(require '[monolith.ai.harness :as h] '[monolith.ai.observe :as observe] '[monolith.ai.agents.heuristic :as s1]
         '[monolith.ai.knowledge.servers :as srv] '[clojure.edn :as edn])
(let [[keyf file] (clojure.string/split (System/getenv "DIAG") #" ")
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      wts (merge @(requiring-resolve 'monolith.ai.knowledge.vfeat/default-weights)
                 {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true :rich-credit 20})
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      prev (atom nil)]
  (binding [h/*hq-memory* true h/*on-step* (fn [gm d a]
                          (let [p @prev]
                            (when (and p (= :runner (:side a)) (#{:run :play} (:type a)) (re-find #"(?i)run" (str (:label a))))
                              (let [o (observe/observe p :runner)
                                    k (srv/server-key (or (get-in a [:args :server]) "HQ"))
                                    env {:obs o :weights wts :decks decks :side :runner}
                                    ev (try (s1/server-run-eval env k {}) (catch Throwable t {:err (str t)}))]
                                (println :T (:turn p) :cr (get-in p [:runner :credit]) :label (:label a) :k k
                                         :ice (mapv (juxt :title :rezzed) (get-in p [:corp :servers k :ices]))
                                         :breakers (mapv :title (get-in p [:runner :rig :program]))
                                         :u (some-> (:u ev) (* 10) Math/round (/ 10.0)) :p (:p ev) :how (:how ev) :err (:err ev)
                                         :value (try (srv/content-value o k (s1/run-opts env)) (catch Throwable _ nil)))))
                            (reset! prev @(:state gm))))]
    (println (select-keys (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)}) [:winner :turn]))))
