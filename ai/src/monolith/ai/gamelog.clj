(ns monolith.ai.gamelog
  "Human-readable public game logs (what a spectator sees), for the T3 blind log review."
  (:require
   [clojure.string :as str]
   [jinteki.i18n :as i18n]
   [monolith.ai.harness :as h]
   [monolith.ai.tourney :as tourney]))

(defn- entry-text [e]
  (let [t (or (:text (:public e)) (:text e))]
    (cond
      (string? t) t
      (map? t) (if (:raw-text t)
                 (:raw-text t)
                 (try (i18n/build-msg t) (catch Exception _ (pr-str (:msg/type t)))))
      :else nil)))

(defn render [s]
  (->> (:log s)
       (keep entry-text)
       (map #(-> % (str/replace #"\[their\]" "their") (str/replace #"\[Credits?\]" "credits")
                 (str/replace #"\[Click\]" "click")))
       (str/join "\n")))

(defn play-and-render
  "Plays one game and returns {:result r :log text}."
  [{:keys [corp runner seed corp-deck runner-deck] :or {corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner}}]
  (let [last-g (volatile! nil)
        r (binding [h/*on-step* (fn [g _ _] (vreset! last-g g))]
            (tourney/play-one {:corp corp :runner runner :seed seed :corp-deck corp-deck :runner-deck runner-deck :budget-ms 250}))]
    {:result (dissoc r :log) :log (render @(:state @last-g))}))
