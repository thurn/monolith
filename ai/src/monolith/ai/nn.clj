(ns monolith.ai.nn
  "Loads networks written by ai/py (manifest.json + weights.bin, float32 little-endian) and
  evaluates policies over the legal actions of a decision."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [monolith.ai.engine :as engine]
   [monolith.ai.features :as f])
  (:import [monolith Mlp]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files Paths]))

(defn- read-floats [^ByteBuffer bb n]
  (let [a (float-array n)]
    (.get (.asFloatBuffer bb) a)
    (.position bb (+ (.position bb) (* 4 n)))
    a))

(defn load-net
  "dir contains manifest.json {\"dims\":{S D H HA HZ}, \"tensors\":[[name, n], ...], \"version\": n}."
  [dir]
  (let [man (json/parse-string (slurp (io/file dir "manifest.json")) true)
        bb (doto (ByteBuffer/wrap (Files/readAllBytes (Paths/get (str dir "/weights.bin") (make-array String 0))))
             (.order ByteOrder/LITTLE_ENDIAN))
        ts (into {} (for [[nm n] (:tensors man)] [(keyword nm) (read-floats bb n)]))
        {:keys [S D H HA HZ]} (:dims man)
        two (fn [k] (into-array (Class/forName "[F") [(ts (keyword (str k "0"))) (ts (keyword (str k "1")))]))]
    (when (or (not= S f/state-dim) (not= D f/action-dim))
      (throw (ex-info "net dims do not match featurizer" {:net (:dims man) :S f/state-dim :D f/action-dim})))
    {:version (:version man)
     :mlp (Mlp. S D H HA HZ (:w1 ts) (:b1 ts) (:w2 ts) (:b2 ts) (:wa ts) (:ba ts)
                (two "wv") (two "bv") (two "wzh") (two "wza") (two "bz") (two "wz") (two "bzz"))}))

(defn sparse-arrays [^java.util.HashMap m]
  (let [n (.size m) idx (int-array n) val (float-array n)]
    (loop [es (seq m) i 0]
      (when es
        (let [[k v] (first es)] (aset idx i (int k)) (aset val i (float v)))
        (recur (next es) (inc i))))
    [idx val]))

(defn softmax ^doubles [^floats logits]
  (let [n (alength logits)
        mx (reduce max Double/NEGATIVE_INFINITY (seq logits))
        e (double-array n)]
    (dotimes [i n] (aset e i (Math/exp (- (aget logits i) mx))))
    (let [s (reduce + (seq e))]
      (dotimes [i n] (aset e i (/ (aget e i) s))))
    e))

(defn evaluate
  "Returns {:probs double[] :value v :state sparse-map :actions [sparse-maps]} for a decision."
  [net obs side decision actions decks]
  (let [^Mlp mlp (:mlp net)
        sm (f/state-features obs side decision (engine/decklist (:corp decks)))
        [si sv] (sparse-arrays sm)
        h (.trunk mlp si sv)
        ams (mapv f/action-features actions)
        pairs (mapv sparse-arrays ams)
        logits (.logits mlp h (if (= side :corp) 0 1)
                        (into-array (Class/forName "[I") (map first pairs))
                        (into-array (Class/forName "[F") (map second pairs)))]
    {:probs (softmax logits) :value (.value mlp h (if (= side :corp) 0 1)) :state sm :actions ams}))

(defn sample ^long [^doubles p ^java.util.Random rng]
  (let [r (.nextDouble rng)]
    (loop [i 0 acc 0.0]
      (let [acc (+ acc (aget p i))]
        (if (or (>= acc r) (= i (dec (alength p)))) i (recur (inc i) acc))))))

(defn cleanup
  "DeepNash-style test-time cleanup: drop actions under threshold and renormalize."
  ^doubles [^doubles p threshold]
  (let [q (double-array (map #(if (< % threshold) 0.0 %) p))
        s (reduce + (seq q))]
    (if (pos? s)
      (do (dotimes [i (alength q)] (aset q i (/ (aget q i) s))) q)
      p)))

(defn argmax ^long [^doubles p]
  (loop [i 1 best 0]
    (if (>= i (alength p)) best (recur (inc i) (if (> (aget p i) (aget p best)) i best)))))
