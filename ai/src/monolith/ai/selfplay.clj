(ns monolith.ai.selfplay
  "Actor pool for S5 (and S4 data): self-play games with the current net, trajectories out as
  binary shards, weights in by polling <dir>/net/manifest.json. Actor 0 interleaves evaluation
  games against S1 and appends them to <dir>/eval.jsonl.

  Shard format (big-endian, read by ai/py/traj.py), per game:
    int magic 0x4E52474D, int nsteps, int winner (0 corp, 1 runner, 2 none), int seed
    per step: byte side, short corp-ap, short runner-ap, short A, short chosen,
              sparse state, A sparse actions, A float behaviour probs
    sparse = short nnz, nnz x (short idx, float val)"
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [monolith.ai.agents.heuristic :as heuristic]
   [monolith.ai.harness :as h]
   [monolith.ai.nn :as nn])
  (:import [java.io DataOutputStream BufferedOutputStream FileOutputStream File]))

(defn- write-sparse [^DataOutputStream out ^java.util.HashMap m]
  (.writeShort out (.size m))
  (doseq [[k v] m] (.writeShort out (int k)) (.writeFloat out (float v))))

(defrecord NetAgent [net-ref mode epsilon record threshold]
  h/Agent
  (choose [_ {:keys [side actions decision obs decks ^java.util.Random rng]}]
    (if (= 1 (count actions))
      0
      (let [o @obs
            {:keys [probs state] :as ev} (nn/evaluate @net-ref o side decision actions decks)
            ^doubles probs probs
            n (alength probs)
            mu (if (pos? epsilon)
                 (double-array (map #(+ (* (- 1 epsilon) %) (/ epsilon n)) probs))
                 probs)
            idx (case mode
                  :sample (nn/sample mu rng)
                  :clean (nn/sample (nn/cleanup probs threshold) rng)
                  :argmax (nn/argmax probs))]
        (when record
          (swap! record conj {:side side :corp-ap (get-in o [:corp :agenda-point] 0) :runner-ap (get-in o [:runner :agenda-point] 0)
                              :chosen idx :mu mu :state state :actions (:actions ev)}))
        idx))))

(defn write-game! [^DataOutputStream out steps winner seed]
  (.writeInt out (unchecked-int 0x4E52474D))
  (.writeInt out (count steps))
  (.writeInt out (case winner :corp 0 :runner 1 2))
  (.writeInt out (int seed))
  (doseq [{:keys [side corp-ap runner-ap chosen ^doubles mu state actions]} steps]
    (.writeByte out (if (= side :corp) 0 1))
    (.writeShort out (int corp-ap))
    (.writeShort out (int runner-ap))
    (.writeShort out (count actions))
    (.writeShort out (int chosen))
    (write-sparse out state)
    (doseq [a actions] (write-sparse out a))
    (doseq [p mu] (.writeFloat out (float p)))))

(defn- manifest-version [dir]
  (try (:version (json/parse-string (slurp (io/file dir "net" "manifest.json")) true)) (catch Exception _ nil)))

(defn- maybe-reload! [dir net-ref]
  (let [v (manifest-version dir)]
    (when (and v (not= v (:version @net-ref)))
      (try (reset! net-ref (nn/load-net (str dir "/net"))) (catch Exception _ nil)))))

(defn- append-jsonl [path m]
  (locking append-jsonl
    (spit path (str (json/generate-string m) "\n") :append true)))

(defn actor-loop
  [{:keys [dir id games-per-shard deadline epsilon corp-deck runner-deck eval-every]
    :or {games-per-shard 50 epsilon 0.03 eval-every 10}}]
  (let [net-ref (atom (nn/load-net (str dir "/net")))
        shard-dir (io/file dir "shards")
        rng (java.util.Random. (+ 7919 id))]
    (.mkdirs shard-dir)
    (loop [shard 0]
      (when (< (System/currentTimeMillis) deadline)
        (let [tmp (io/file shard-dir (format "a%02d-%06d.tmp" id shard))
              out (DataOutputStream. (BufferedOutputStream. (FileOutputStream. tmp) 65536))]
          (try
            (dotimes [g games-per-shard]
              (when (< (System/currentTimeMillis) deadline)
                (maybe-reload! dir net-ref)
                (let [seed (.nextInt rng Integer/MAX_VALUE)
                      rec (atom [])
                      agent (->NetAgent net-ref :sample epsilon rec 0.0)
                      r (h/play-game {:seed seed :corp-deck corp-deck :runner-deck runner-deck
                                      :agents {:corp agent :runner agent}})]
                  (when-not (:stall r) (write-game! out @rec (:winner r) seed))
                  ;; evaluation vs S1, alternating sides
                  (when (and (zero? id) (zero? (mod g eval-every)))
                    (doseq [side [:corp :runner]]
                      (let [me (->NetAgent net-ref :clean 0.0 nil 0.05)
                            s1 (heuristic/make {})
                            er (h/play-game {:seed (.nextInt rng Integer/MAX_VALUE) :corp-deck corp-deck :runner-deck runner-deck
                                             :agents (if (= side :corp) {:corp me :runner s1} {:corp s1 :runner me})})]
                        (append-jsonl (str dir "/eval.jsonl")
                                      {:t (System/currentTimeMillis) :version (:version @net-ref) :side side
                                       :won (= side (:winner er)) :stall (boolean (:stall er)) :turn (:turn er)
                                       :points (:points er)})))))))
            (finally (.close out)))
          (.renameTo tmp (io/file shard-dir (format "a%02d-%06d.bin" id shard)))
          (recur (inc shard)))))))

(defn -main
  "Arg: EDN {:dir path :threads n :hours h :corp-deck kw :runner-deck kw}"
  [& args]
  (let [{:keys [dir threads hours corp-deck runner-deck epsilon]
         :or {threads 12 hours 24 corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner epsilon 0.03}}
        (read-string (first args))
        deadline (+ (System/currentTimeMillis) (long (* hours 3600 1000)))
        pool (java.util.concurrent.Executors/newFixedThreadPool threads)]
    (doseq [i (range threads)]
      (.submit pool ^Runnable (fn [] (try (actor-loop {:dir dir :id i :deadline deadline :epsilon epsilon
                                                       :corp-deck corp-deck :runner-deck runner-deck})
                                          (catch Throwable t (binding [*out* *err*] (println "actor" i "died" t) (.printStackTrace t)))))))
    (.shutdown pool)
    (.awaitTermination pool (+ 3600 (long (* hours 3600))) java.util.concurrent.TimeUnit/SECONDS)
    (shutdown-agents)
    (System/exit 0)))
