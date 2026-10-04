(ns monolith.ai.vdata
  "Value-model training data (R4): plays games over a matchup mix and records dense features
  (knowledge/vfeat) at every turn boundary and, with probability mid-p, at click decisions,
  labelled with the final winner. One JSONL line per position:
  {\"m\" matchup \"seed\" s \"g\" pairing \"t\" turn \"a\" active-side \"y\" 1|0 \"x\" [...]}."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [monolith.ai.harness :as h]
   [monolith.ai.knowledge.vfeat :as vf]
   [monolith.ai.tourney :as tourney])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn- play-and-record [{:keys [corp runner seed matchup mid-p]}]
  (let [rng (java.util.Random. (+ seed 77))
        buf (transient [])
        last-turn (volatile! nil)
        r (binding [h/*on-step*
                    (fn [g d _]
                      (let [s @(:state g)
                            tk [(:turn s) (:active-player s)]
                            boundary (not= tk @last-turn)]
                        (vreset! last-turn tk)
                        (when (or boundary (and (= :turn (:kind d)) (< (.nextDouble rng) mid-p)))
                          (conj! buf [(:turn s) (name (:active-player s)) (vf/features s)]))))]
            (tourney/play-one {:corp corp :runner runner :seed seed :budget-ms 250
                               :corp-deck (keyword (str (name matchup) "-corp"))
                               :runner-deck (keyword (str (name matchup) "-runner"))}))]
    {:result (dissoc r :log) :positions (persistent! buf)}))

(defn run
  "opts: :pairings [[corp-spec runner-spec] ...] (seed s uses pairing s mod n), :matchups,
  :seeds, :threads, :out path, :mid-p (default 0.15)."
  [{:keys [pairings matchups seeds threads out mid-p] :or {threads 8 mid-p 0.15}}]
  (io/make-parents out)
  (let [w (io/writer out :append true)
        lock (Object.)
        pool (Executors/newFixedThreadPool threads)
        done (atom 0)
        futs (doall
              (for [s seeds
                    :let [[c r] (nth pairings (mod s (count pairings)))
                          m (nth matchups (mod (quot s (count pairings)) (count matchups)))]]
                (.submit pool ^Callable
                         (fn []
                           (let [{:keys [result positions]} (play-and-record {:corp c :runner r :seed s :matchup m :mid-p mid-p})
                                 y (case (:winner result) :corp 1 :runner 0 nil)]
                             (locking lock
                               (when (and y (not (:stall result)))
                                 (doseq [[t a x] positions]
                                   (.write w (json/generate-string {:m (name m) :seed s :g (str (tourney/spec-name c) "/" (tourney/spec-name r))
                                                                    :t t :a a :y y :x (mapv #(/ (Math/round (* 1000.0 (double %))) 1000.0) x)}))
                                   (.write w "\n"))
                                 (.flush w))
                               (swap! done inc))
                             nil)))))]
    (try (doseq [f futs] (.get ^java.util.concurrent.Future f))
         (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES) (.close w)))
    @done))
