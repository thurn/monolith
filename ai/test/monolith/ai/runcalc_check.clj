(ns monolith.ai.runcalc-check
  "Invariant checks for the run calculator (a sign error here went unnoticed for a day and made
  Runners take grip-emptying runs). Run: cd ai && ../scripts/lein run -m monolith.ai.runcalc-check"
  (:require [monolith.ai.knowledge.runcalc :as rc]))

(defn- ice [subs] {:rezzed true :title "Test" :subs subs :strength 9})

(defn -main [& _]
  (let [u (fn [subs hand & {:as o}]
            (:u (#'rc/walk (merge {:w-damage 2.0 :w-tag 1.0 :w-program 6.0 :mode :expected :value 10.0
                                   :hand hand :credits0 5 :breakers [] :ices [{:known true :rezzed true :model (ice subs)}]} o)
                           0 {:credits 5 :damage 0 :corp-credits 0})))
        checks {"damage equal to grip is a loss" (neg? (u [{:net 2}] 2))
                "damage above grip is a flatline" (<= (u [{:net 3}] 2) rc/flatline-utility)
                "small damage keeps some value" (< (u [{:net 1}] 5) 10.0)
                "trashed program costs" (< (u [{:trash-program 1}] 5 :breakers [{:title "B"}]) (u [] 5))
                "tax ETR is paid" (pos? (u [{:etr-unless-pay 1}] 5))
                "hard ETR blocks" (<= (u [{:etr true}] 5) 0.0)}]
    (doseq [[k ok] checks] (println (if ok "ok  " "FAIL") k))
    (System/exit (if (every? val checks) 0 1))))
