(ns monolith.ai.equiv
  "Seeded replay equivalence for engine changes: record per-action canonical state hashes
  of random games, then replay the same action lists on the current engine and compare."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [monolith.ai.agents.random :as random]
   [monolith.ai.harness :as h]
   [monolith.ai.moves :as moves]))

(def strip-keys #{:eid :cost-label :effect-completed :log :history :click-states :turn-state
                  :paid-ability-state :start-date :end-time :time :sfx :sfx-current-id :toast})

(defn canon [s]
  (walk/postwalk (fn [x]
                   (cond (fn? x) :fn
                         (map? x) (let [m (reduce dissoc (if (record? x) (into {} x) x) strip-keys)
                                        ;; label refresh (skipped when headless) turns nil into []
                                        m (cond-> m
                                            (= [] (:corp-abilities m)) (assoc :corp-abilities nil)
                                            (= [] (:runner-abilities m)) (assoc :runner-abilities nil))]
                                    ;; keyed by eid numbers, which skipped make-eid calls shift
                                    (if (contains? m :set-aside-tracking)
                                      (update m :set-aside-tracking #(sort-by str (vals %)))
                                      m))
                         :else x))
                 s))

(defn state-hash [s] (hash (canon s)))

(defn step-hash
  "Cheap per-action fingerprint: who decides, the legal action labels, and the log tail."
  [state]
  (let [s @state d (moves/decision state)]
    (hash [d (map :label (moves/legal state d nil)) (count (:log s)) (map :text (take-last 3 (:log s)))
           (get-in s [:corp :credit]) (get-in s [:runner :credit]) (count (get-in s [:corp :hand]))
           (count (get-in s [:runner :hand]))])))

(defn record-game [seed]
  (let [hashes (transient [])
        last-g (volatile! nil)
        r (binding [h/*on-step* (fn [g _ _] (vreset! last-g g) (conj! hashes (step-hash (:state g))))]
            (h/play-game {:seed seed :agents {:corp (random/make) :runner (random/make)}}))]
    {:seed seed :log (:log r) :hashes (persistent! hashes) :final (state-hash @(:state @last-g))}))

(defn record! [path n]
  (with-open [w (io/writer path)]
    (doseq [s (range n)]
      (.write w (pr-str (record-game s)))
      (.write w "\n"))))

(defn check-game [{:keys [seed log hashes final]}]
  (let [i (volatile! 0)
        bad (volatile! nil)
        last-g (volatile! nil)]
    (binding [h/*on-step* (fn [g _ a]
                            (vreset! last-g g)
                            (when (and (nil? @bad) (not= (nth hashes @i nil) (step-hash (:state g))))
                              (vreset! bad {:seed seed :step @i :action (:label a)}))
                            (vswap! i inc))]
      (h/replay-game {:seed seed :log log}))
    (or @bad (when (not= final (state-hash @(:state @last-g))) {:seed seed :step :final}))))

(defn check! [path]
  (let [games (keep #(try (edn/read-string %) (catch Exception _ nil)) (line-seq (io/reader path)))
        bad (doall (keep check-game games))]
    {:games (count games) :diverged (count bad) :first (take 5 bad)}))
