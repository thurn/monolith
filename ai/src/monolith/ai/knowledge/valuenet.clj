(ns monolith.ai.knowledge.valuenet
  "Learned Corp-perspective value of a full (or determinized) state, trained on S1 self-play
  outcomes (monolith.ai.valuegen + ai/py/monolith_ai/value_train.py)."
  (:require
   [monolith.ai.engine :as engine]
   [monolith.ai.nn :as nn]
   [monolith.ai.valuegen :as vg])
  (:import [monolith Mlp]))

(defonce nets (atom {}))

(defn net [dir] (or (@nets dir) (let [n (nn/load-net dir)] (swap! nets assoc dir n) n)))

(defn corp-value
  "Value in [-1, 1]: +1 means the Corp is expected to win."
  [the-net s corp-deck]
  (let [^Mlp mlp (:mlp the-net)
        [idx val] (nn/sparse-arrays (vg/position-features s (engine/decklist corp-deck)))]
    (.value mlp (.trunk mlp idx val) 0)))
