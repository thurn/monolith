(require '[monolith.ai.harness :as h] '[monolith.ai.tourney :as t] '[monolith.ai.moves :as moves] '[monolith.ai.agents.heuristic :as s1])
(let [rc2 {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
           :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true}
           :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}
      wts (merge @(requiring-resolve 'monolith.ai.knowledge.vfeat/default-weights) (:w rc2))
      [seed deck] (map read-string (clojure.string/split (System/getenv "DIAG") #" "))
      inner (t/make-agent [:champion rc2] :corp)
      wrap (reify h/Agent
             (choose [_ ctx]
               (let [idx (h/choose inner ctx)
                     d (:decision ctx)
                     o @(:obs ctx)]
                 (when (= :turn (:kind d))
                   (let [[r a] (s1/decide (assoc ctx :obs o :weights wts :mem (atom {})))
                         chosen (:label (nth (:actions ctx) idx))]
                     (println :T (:turn o) :cr (get-in o [:corp :credit]) :hand (count (get-in o [:corp :hand]))
                              :clk (get-in o [:corp :click]) :chose chosen (when (and (re-find #"credit" (str chosen)) (>= (get-in o [:corp :credit]) 15)) (str " HAND " (mapv :title (get-in o [:corp :hand])) " ICE " (into {} (for [[k v] (get-in o [:corp :servers])] [k (count (:ices v))])))) (if (= chosen (:label a)) "" (str " | S1 " r ": " (:label a))))))
                 idx)))]
  (println (select-keys (h/play-game {:seed seed :corp-deck (keyword (str (name deck) "-corp")) :runner-deck (keyword (str (name deck) "-runner")) :budget-ms 250 :max-actions 6000
                         :agents {:corp wrap :runner (t/make-agent :s1ref :runner)}}) [:winner :turn])))
