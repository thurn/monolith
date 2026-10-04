(ns monolith.ai.valuegen
  "Value-function data: S1 self-play positions (one per click decision, full state, Corp-perspective
  features) labelled with the final outcome. Binary, big-endian, per position:
  int game, byte active-side (0 corp), float label (+1 Corp won, -1 Runner won), short nnz, nnz x (short idx, float val)."
  (:require
   [clojure.java.io :as io]
   [monolith.ai.engine :as engine]
   [monolith.ai.features :as f]
   [monolith.ai.harness :as h]
   [monolith.ai.tourney :as tourney])
  (:import [java.io DataOutputStream BufferedOutputStream FileOutputStream]))

(defn position-features [s decklist]
  (f/state-features s :corp {:kind :turn :side (:active-player s)} decklist))

(defn worker [{:keys [dir id games corp-deck runner-deck agents sample-p]}]
  (let [rng (java.util.Random. (+ 7 id))
        decklist (engine/decklist corp-deck)
        out (DataOutputStream. (BufferedOutputStream. (FileOutputStream. (io/file dir (format "v%02d.bin" id))) 1048576))]
    (try
      (dotimes [gi games]
        (let [seed (+ (* id 10000000) gi)
              buf (transient [])
              r (binding [h/*on-step* (fn [g d _]
                                        (when (and (= :turn (:kind d)) (< (.nextDouble rng) sample-p))
                                          (let [s @(:state g)]
                                            (conj! buf [(if (= :corp (:active-player s)) 0 1) (position-features s decklist)]))))]
                  (tourney/play-one {:corp (rand-nth agents) :runner (rand-nth agents) :seed seed
                                     :corp-deck corp-deck :runner-deck runner-deck :budget-ms 250}))]
          (when (#{:corp :runner} (:winner r))
            (let [label (if (= :corp (:winner r)) 1.0 -1.0)]
              (doseq [[active ^java.util.HashMap m] (persistent! buf)]
                (.writeInt out (int seed))
                (.writeByte out (int active))
                (.writeFloat out (float label))
                (.writeShort out (.size m))
                (doseq [[k v] m] (.writeShort out (int k)) (.writeFloat out (float v))))))))
      (finally (.close out)))))

(defn -main [& args]
  (let [{:keys [dir threads games corp-deck runner-deck agents sample-p]
         :or {threads 16 games 3000 corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner
              agents [:heuristic] sample-p 0.5}} (read-string (first args))
        pool (java.util.concurrent.Executors/newFixedThreadPool threads)]
    (.mkdirs (io/file dir))
    (doseq [i (range threads)]
      (.submit pool ^Runnable (fn [] (try (worker {:dir dir :id i :games games :corp-deck corp-deck :runner-deck runner-deck
                                                   :agents agents :sample-p sample-p})
                                          (catch Throwable t (.printStackTrace t))))))
    (.shutdown pool)
    (.awaitTermination pool 48 java.util.concurrent.TimeUnit/HOURS)
    (shutdown-agents)
    (System/exit 0)))
