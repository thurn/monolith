;; At the first Runner action of each Runner turn in a review game, prints S1's run evaluation of every
;; remote with content (DIAG="<key.edn> log-NN.txt"; optional TURNS="10 11 12").
(require '[monolith.ai.harness :as h] '[monolith.ai.observe :as observe] '[monolith.ai.agents.heuristic :as s1]
         '[monolith.ai.knowledge.servers :as srv] '[clojure.edn :as edn])
(let [genv (fn [k] (or (System/getenv k) (some-> (ns-resolve 'user (symbol k)) deref)))  ; REPL: (def DIAG "...")
      [keyf file] (clojure.string/split (genv "DIAG") #" ")
      turns (some->> (genv "TURNS") (re-seq #"\d+") (map parse-long) set)
      g (some #(when (= file (:file %)) %) (edn/read-string (slurp keyf)))
      wts (merge @(requiring-resolve 'monolith.ai.knowledge.vfeat/default-weights)
                 {:credit-knee2 12 :rich-credit 15 :prune-runs true :w-program 6.0 :remote-ice-prior 1.0 :hq-memory true
                  :remote-denial 0.5 :run-urgency 0.3})
      decks {:corp (keyword (str (name (:matchup g)) "-corp")) :runner (keyword (str (name (:matchup g)) "-runner"))}
      prev (atom nil) last-turn (atom nil)]
  (binding [h/*hq-memory* true
            h/*on-step* (fn [gm d a]
                          (let [p @prev]
                            (when (and p (= :runner (:side a)) (= :runner (:active-player p)) (not= (:turn p) @last-turn)
                                       (or (nil? turns) (turns (:turn p))))
                              (reset! last-turn (:turn p))
                              (let [o (observe/observe p :runner)
                                    env {:obs o :weights wts :decks decks :side :runner :mem (atom {})}]
                                (println :T (:turn p) :cr (get-in p [:runner :credit]) :pts [(get-in p [:runner :agenda-point]) (get-in p [:corp :agenda-point])]
                                         :corp-cr (get-in p [:corp :credit]) :first (:label a)
                                         :rig (mapv :title (get-in p [:runner :rig :program])))
                                (doseq [[k srv] (srv/remotes o) :when (seq (:content srv))]
                                  (let [ev (try (s1/server-run-eval env k {}) (catch Throwable t {:err (str t)}))]
                                    (println "   " k :ice (mapv (juxt :title :rezzed) (get-in p [:corp :servers k :ices]))
                                             :content (mapv (juxt :title :advance-counter) (get-in p [:corp :servers k :content]))
                                             :u (some-> (:u ev) double Math/round) :p (:p ev) :how (:how ev) :err (:err ev)
                                             :value (try (srv/content-value o k (s1/run-opts env)) (catch Throwable t (str t))))))))
                            (reset! prev @(:state gm))))]
    (println (select-keys (h/replay-game {:seed (:seed g) :corp-deck (:corp decks) :runner-deck (:runner decks) :log (:log g)}) [:winner :turn]))))
