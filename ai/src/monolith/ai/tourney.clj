(ns monolith.ai.tourney
  "Match and round-robin runner. Games run on a fixed thread pool; results append to JSONL.
  Pairing k of A-vs-B and B-vs-A share seed k (paired seeds)."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [monolith.ai.harness :as h])
  (:import [java.util.concurrent Executors TimeUnit]))

(def registry
  '{:random monolith.ai.agents.random/make
    :heuristic monolith.ai.agents.heuristic/make
    :s1ref monolith.ai.ref.heuristic/make
    :planner monolith.ai.agents.planner/make
    :champion monolith.ai.agents.champion/make
    :ismcts monolith.ai.agents.ismcts/make
    :neural monolith.ai.agents.neural/make
    :neural-policy monolith.ai.agents.neural/make-policy
    :rnad monolith.ai.agents.rnad/make
    :clairvoyant monolith.ai.agents.ismcts/make-clairvoyant})

(defn- fingerprint
  "Position summary for loop detection (credits deliberately excluded, so free credit loops count
  as no progress)."
  [o]
  (let [cards (for [[_ srv] (get-in o [:corp :servers]) c (concat (:ices srv) (:content srv))] c)
        rig (get-in o [:runner :rig])
        rcards (concat (:program rig) (:hardware rig) (:resource rig))]
    (hash [(:turn o) (:active-player o)
           (for [sd [:corp :runner]] [(get-in o [sd :click]) (count (get-in o [sd :hand])) (count (get-in o [sd :deck]))
                                      (count (get-in o [sd :discard])) (count (get-in o [sd :scored]))
                                      (some-> (get-in o [sd :prompt]) first :msg str)])
           (count cards) (count (filter :rezzed cards)) (reduce + 0 (keep :advance-counter cards))
           (count rcards) (reduce + 0 (for [c (concat cards rcards) [_ n] (:counter c)] (or n 0)))
           (get-in o [:runner :tag :base]) (some-> (:run o) (select-keys [:server :position :phase]))])))

(defrecord LoopGuard [inner counts]
  h/Agent
  (choose [_ {:keys [actions] :as ctx}]
    ;; the same action chosen 5 times in the same position is a livelock (engine quirks: free
    ;; repeatable abilities, re-offered access targets); withhold it while alternatives exist
    (let [o @(:obs ctx)
          fp (fingerprint o)
          turn [(:turn o) (:active-player o)]
          seen (get @counts turn {})
          banned (set (for [[[f l] n] seen :when (and (= f fp) (>= n 5))] l))
          keep (vec (keep-indexed (fn [i a] (when-not (banned (:label a)) i)) actions))
          idx (if (and (seq banned) (seq keep) (< (count keep) (count actions)))
                (keep (h/choose inner (assoc ctx :actions (mapv actions keep))))
                (h/choose inner ctx))
          l (:label (nth actions idx))]
      (reset! counts {turn (update seen [fp l] (fnil inc 0))})
      idx)))

(defn make-agent
  "spec is a keyword or [keyword opts]. Every agent runs behind a LoopGuard."
  [spec side]
  (let [[k opts] (if (vector? spec) spec [spec {}])
        a ((requiring-resolve (registry k)) (assoc opts :side side))]
    (->LoopGuard a (atom {}))))

(defn spec-name [spec]
  (if (vector? spec)
    (str (name (first spec)) (when (seq (second spec)) (str (into (sorted-map) (second spec)))))
    (name spec)))

(defn shas []
  (let [sha #(str/trim (:out (sh "git" "-C" % "rev-parse" "--short" "HEAD")))]
    {:repo (sha ".") :fork (sha "vendor/netrunner")}))

(defn wilson
  "Wilson 95% interval for k successes in n."
  [k n]
  (if (zero? n) [0.0 1.0]
      (let [z 1.96 p (/ (double k) n) d (+ 1 (/ (* z z) n))
            c (/ (+ p (/ (* z z) (* 2 n))) d)
            w (/ (* z (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ (* z z) (* 4 n n))))) d)]
        [(- c w) (+ c w)])))

(defn play-one [{:keys [corp runner seed corp-deck runner-deck budget-ms max-actions meta]}]
  (let [;; a Runner spec with [:w :hq-memory] remembers accessed HQ cards (harness-level memory)
        hq-mem (boolean (and (vector? runner) (get-in (second runner) [:w :hq-memory])))
        r (try
            (binding [h/*hq-memory* hq-mem]
             (h/play-game {:seed seed :corp-deck corp-deck :runner-deck runner-deck :budget-ms budget-ms
                          :max-actions (or max-actions 6000)
                          :agents {:corp (make-agent corp :corp) :runner (make-agent runner :runner)}}))
            (catch Throwable t
              {:seed seed :stall {:cause :harness-exception :error (str t)
                                  :trace (mapv str (take 12 (.getStackTrace t)))}}))]
    (merge {:corp (spec-name corp) :runner (spec-name runner) :corp-deck corp-deck :runner-deck runner-deck
            :budget-ms budget-ms}
           meta
           r)))

(defn run-games
  "Runs every game spec on `threads` workers; calls on-result (serialized) per finished game."
  [games {:keys [threads on-result] :or {threads 16}}]
  (let [pool (Executors/newFixedThreadPool threads)
        lock (Object.)
        futures (doall (for [gm games]
                         (.submit pool ^Callable
                                  (fn [] (let [r (play-one gm)]
                                           (locking lock (when on-result (on-result r)))
                                           r)))))]
    (try (mapv #(.get ^java.util.concurrent.Future %) futures)
         (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES)))))

(defn summarize [results]
  (let [by (group-by (juxt :corp :runner) results)]
    (for [[[c r] rs] (sort-by key by)
          :let [ok (remove :stall rs)
                n (count ok)
                cw (count (filter #(= :corp (:winner %)) ok))
                [lo hi] (wilson cw n)]]
      {:corp c :runner r :games (count rs) :stalls (count (filter :stall rs))
       :corp-win (if (pos? n) (/ (double cw) n) 0.0) :ci [lo hi]
       :avg-turns (if (pos? n) (/ (double (reduce + (map :turn ok))) n) 0.0)
       :reasons (frequencies (map #(str (name (or (:winner %) :none)) "/" (:reason %)) ok))})))

(defn print-summary [results]
  (doseq [{:keys [corp runner games stalls corp-win ci avg-turns reasons]} (summarize results)]
    (println (format "%-28s vs %-28s n=%4d stalls=%3d corp-win=%.3f [%.3f,%.3f] turns=%.1f %s"
                     corp runner games stalls corp-win (first ci) (second ci) avg-turns reasons))))

(defn write-jsonl-fn [path meta]
  (io/make-parents path)
  (let [w (io/writer path :append true)]
    (fn [r]
      (.write w (json/generate-string (merge meta (dissoc r :log) {:log (:log r)})))
      (.write w "\n")
      (.flush w))))

(defn match-games
  "Both-sides pairing of agents a and b over seeds."
  [a b seeds opts]
  (concat (for [s seeds] (merge opts {:corp a :runner b :seed s}))
          (when (not= a b) (for [s seeds] (merge opts {:corp b :runner a :seed s})))))

(defn round-robin [agents seeds opts]
  (let [agents (vec agents)]
    (concat
     (for [a agents s seeds] (merge opts {:corp a :runner a :seed s}))   ; null rates
     (for [i (range (count agents)) j (range (inc i) (count agents))
           g (match-games (agents i) (agents j) seeds opts)]
       g))))

(defn -main
  "Args: one EDN map {:mode :match|:rr :agents [...] :seeds n :seed0 0 :threads 16 :out path
   :corp-deck kw :runner-deck kw :budget-ms 250}"
  [& args]
  (let [{:keys [mode agents seeds seed0 threads out budget-ms corp-deck runner-deck]
         :or {mode :rr seeds 100 seed0 0 threads 16 budget-ms 250
              corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner}}
        (read-string (first args))
        seeds (range seed0 (+ seed0 seeds))
        opts {:budget-ms budget-ms :corp-deck corp-deck :runner-deck runner-deck}
        games (case mode
                :rr (round-robin agents seeds opts)
                :match (match-games (first agents) (second agents) seeds opts)
                :one-way (for [s seeds] (merge opts {:corp (first agents) :runner (second agents) :seed s})))
        meta (assoc (shas) :started (str (java.time.Instant/now)))
        write (when out (write-jsonl-fn out meta))
        n (count games)
        done (atom 0)
        t0 (System/nanoTime)
        results (run-games games {:threads threads
                                  :on-result (fn [r]
                                               (when write (write r))
                                               (let [d (swap! done inc)]
                                                 (when (zero? (mod d (max 1 (quot n 20))))
                                                   (binding [*out* *err*]
                                                     (println (format "%d/%d games, %.1fs" d n (/ (- (System/nanoTime) t0) 1e9)))))))})]
    (print-summary results)
    (println (format "total %.1fs" (/ (- (System/nanoTime) t0) 1e9)))
    (shutdown-agents)
    (System/exit 0)))
