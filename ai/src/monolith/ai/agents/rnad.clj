(ns monolith.ai.agents.rnad
  "S5: the R-NaD policy, played search-free. Test-time cleanup as in DeepNash: actions under
  :threshold probability are dropped and the rest renormalized, then sampled."
  (:require
   [monolith.ai.nn :as nn]
   [monolith.ai.selfplay :as selfplay]))

(def default-net "../research/rounds/R1/rnad/net")

(defn make
  ([] (make {}))
  ([{:keys [net-dir threshold mode] :or {threshold 0.05 mode :clean}}]
   ;; loaded per game so a running tournament picks up the latest exported weights
   (selfplay/->NetAgent (atom (nn/load-net (or net-dir default-net))) mode 0.0 nil threshold)))
