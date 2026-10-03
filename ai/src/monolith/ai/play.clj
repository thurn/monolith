(ns monolith.ai.play
  "One verbose game: every decision with the rule that fired, plus the engine log tail."
  (:require
   [clojure.string :as str]
   [monolith.ai.harness :as h]
   [monolith.ai.tourney :as tourney]))

(defn- board [s]
  (let [srv (fn [[k v]] (str (name k) ":" (count (:ices v)) "i/" (str/join "," (map #(str (:title %) (when-let [a (:advance-counter %)] (str "+" a)) (when (:rezzed %) "*")) (:content v)))))]
    (str "C " (get-in s [:corp :credit]) "c " (count (get-in s [:corp :hand])) "h " (get-in s [:corp :agenda-point]) "ap ["
         (str/join " " (map srv (get-in s [:corp :servers]))) "] | R "
         (get-in s [:runner :credit]) "c " (count (get-in s [:runner :hand])) "h " (get-in s [:runner :agenda-point]) "ap ["
         (str/join "," (map :title (concat (get-in s [:runner :rig :program]) (get-in s [:runner :rig :hardware]) (get-in s [:runner :rig :resource])))) "]")))

(defn trace-game
  "Returns the game result; prints a line per decision. opts as tourney/play-one plus :quiet-kinds."
  [{:keys [corp runner seed corp-deck runner-deck quiet-kinds log-lines]
    :or {corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner
         quiet-kinds #{} log-lines true}}]
  (let [agents {:corp (tourney/make-agent corp :corp) :runner (tourney/make-agent runner :runner)}
        last-log (volatile! 0)
        last-turn (volatile! nil)]
    (binding [h/*on-step*
              (fn [g d a]
                (let [s @(:state g)
                      tr (some-> (agents (:side d)) :trace deref)]
                  (when (not= @last-turn [(:turn s) (:active-player s)])
                    (vreset! last-turn [(:turn s) (:active-player s)])
                    (println (str "\n== turn " (:turn s) " " (name (:active-player s)) " :: " (board s))))
                  (when-not (quiet-kinds (:kind d))
                    (println (format "  %-6s %-10s %-22s %s" (name (:side d)) (name (:kind d)) (str tr) (:label a))))
                  (when log-lines
                    (let [logs (:log s)]
                      (doseq [l (drop @last-log logs)]
                        (println "        |" (if (string? (:text l)) (:text l) (pr-str (:text l)))))
                      (vreset! last-log (count logs))))))]
      (let [r (h/play-game {:seed seed :corp-deck corp-deck :runner-deck runner-deck :agents agents})]
        (println "\nRESULT" (dissoc r :log))
        r))))
