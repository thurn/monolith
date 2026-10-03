(ns monolith.ai.datagen
  "Imitation data for S4: plays games with a teacher agent (S3 by default) and records the
  teacher's multi-action decisions in the self-play shard format (behaviour probs one-hot on the
  chosen action), so ai/py can train a policy prior on them."
  (:require
   [clojure.java.io :as io]
   [monolith.ai.engine :as engine]
   [monolith.ai.features :as f]
   [monolith.ai.harness :as h]
   [monolith.ai.selfplay :as selfplay]
   [monolith.ai.tourney :as tourney])
  (:import [java.io DataOutputStream BufferedOutputStream FileOutputStream]))

(defrecord Recording [inner rec]
  h/Agent
  (choose [_ {:keys [side actions decision obs decks] :as ctx}]
    (let [idx (h/choose inner ctx)]
      (when (> (count actions) 1)
        (let [o @obs]
          (swap! rec conj {:side side :corp-ap (get-in o [:corp :agenda-point] 0) :runner-ap (get-in o [:runner :agenda-point] 0)
                           :chosen idx
                           :mu (let [m (double-array (count actions))] (aset m idx 1.0) m)
                           :state (f/state-features o side decision (engine/decklist (:corp decks)))
                           :actions (mapv f/action-features actions)})))
      idx)))

(defn worker [{:keys [dir id games teacher opponents deadline]}]
  (let [rng (java.util.Random. (+ 104729 id))]
    (.mkdirs (io/file dir))
    (loop [shard 0 left games]
      (when (and (pos? left) (< (System/currentTimeMillis) deadline))
        (let [n (min 10 left)
              tmp (io/file dir (format "d%02d-%05d.tmp" id shard))
              out (DataOutputStream. (BufferedOutputStream. (FileOutputStream. tmp) 65536))]
          (try
            (dotimes [_ n]
              (let [seed (.nextInt rng Integer/MAX_VALUE)
                    opp (nth opponents (.nextInt rng (count opponents)))
                    teacher-side (if (.nextBoolean rng) :corp :runner)
                    rec (atom [])
                    agents {teacher-side (->Recording (tourney/make-agent teacher teacher-side) rec)
                            (if (= teacher-side :corp) :runner :corp) (tourney/make-agent opp (if (= teacher-side :corp) :runner :corp))}
                    r (h/play-game {:seed seed :agents agents})]
                (when-not (:stall r) (selfplay/write-game! out @rec (:winner r) seed))))
            (finally (.close out)))
          (.renameTo tmp (io/file dir (format "d%02d-%05d.bin" id shard)))
          (recur (inc shard) (- left n)))))))

(defn -main
  "Arg: EDN {:dir path :threads n :games-per-thread n :hours h :teacher spec :opponents [specs]}"
  [& args]
  (let [{:keys [dir threads games-per-thread hours teacher opponents]
         :or {threads 6 games-per-thread 200 hours 6 teacher :planner opponents [:planner :heuristic]}}
        (read-string (first args))
        deadline (+ (System/currentTimeMillis) (long (* hours 3600000)))
        pool (java.util.concurrent.Executors/newFixedThreadPool threads)]
    (doseq [i (range threads)]
      (.submit pool ^Runnable (fn [] (try (worker {:dir dir :id i :games games-per-thread :teacher teacher
                                                   :opponents opponents :deadline deadline})
                                          (catch Throwable t (.printStackTrace t))))))
    (.shutdown pool)
    (.awaitTermination pool (long (+ 600 (* hours 3600))) java.util.concurrent.TimeUnit/SECONDS)
    (shutdown-agents)
    (System/exit 0)))
