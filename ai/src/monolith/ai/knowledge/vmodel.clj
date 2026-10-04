(ns monolith.ai.knowledge.vmodel
  "Learned value model (R4): JSON written by ai/py/monolith_ai/vtrain.py, evaluated in plain
  Clojure on knowledge/vfeat features. Output is the logit of P(Corp wins)."
  (:require
   [cheshire.core :as json]
   [monolith.ai.knowledge.vfeat :as vf]))

(defonce ^:private cache (atom {}))

(defn load-model [path]
  (or (@cache path)
      (let [m (json/parse-string (slurp path) true)
            model {:mean (double-array (:mean m)) :std (double-array (:std m))
                   :layers (mapv (fn [{:keys [W b]}] {:W (mapv double-array W) :b (double-array b)}) (:layers m))}]
        (swap! cache assoc path model)
        model)))

(defn logit
  "Corp-win logit of state s."
  [{:keys [^doubles mean ^doubles std layers]} s]
  (let [x (double-array (vf/features s))
        n (alength x)]
    (dotimes [i n] (aset x i (/ (- (aget x i) (aget mean i)) (aget std i))))
    (let [nl (count layers)]
      (loop [li 0 ^doubles v x]
        (let [{:keys [W ^doubles b]} (layers li)
              out (double-array (count W))]
          (dotimes [j (count W)]
            (let [^doubles row (W j)]
              (aset out j (loop [i 0 acc (aget b j)]
                            (if (< i (alength row)) (recur (inc i) (+ acc (* (aget row i) (aget v i)))) acc)))))
          (if (= li (dec nl))
            (aget out 0)
            (do (dotimes [j (alength out)] (aset out j (max 0.0 (aget out j))))
                (recur (inc li) out))))))))
