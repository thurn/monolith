(ns monolith.ai.gamelog
  "Human-readable public game logs (what a spectator sees), for the T3 blind log review."
  (:require
   [clojure.string :as str]
   [jinteki.i18n :as i18n]
   [monolith.ai.harness :as h]
   [monolith.ai.tourney :as tourney]))

(defn entry-text [e]
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

(defn- sim-shuffle [v seed]
  (let [al (java.util.ArrayList. ^java.util.Collection v)]
    (java.util.Collections/shuffle al (java.util.Random. (long seed)))
    (vec al)))

(defn play-and-render
  "Plays one game and returns {:result r :log text}."
  [{:keys [corp runner seed corp-deck runner-deck] :or {corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner}}]
  (let [last-g (volatile! nil)
        r (binding [h/*on-step* (fn [g _ _] (vreset! last-g g))]
            (tourney/play-one {:corp corp :runner runner :seed seed :corp-deck corp-deck :runner-deck runner-deck :budget-ms 250}))]
    {:result (dissoc r :log) :actions (:log r) :log (render @(:state @last-g))}))

(defn review-set
  "Blind-review material (pre-registered T3 rule, research/LOG.md 2026-10-03). Plays n games of
  candidate vs ref over matchups; game i reviews one side: candidate's in even i, ref's in odd i,
  with the reviewed side alternating Corp/Runner so each agent is reviewed equally often on each
  side. Writes <dir>/log-XX.txt (shuffled by a fixed seed, no agent names) and
  beside it as <dir>-key.edn (the sealed mapping; never in the reviewer's directory). Returns the key."
  [{:keys [candidate ref matchups seeds dir shuffle-seed threads] :or {ref :s1ref shuffle-seed 20261004 threads 3}}]
  (let [games (for [[i s] (map-indexed vector seeds)
                    :let [m (nth matchups (mod i (count matchups)))
                          reviewed (if (even? i) :candidate :ref)
                          side (if (even? (quot i 2)) :corp :runner)
                          spec {:candidate candidate :ref ref}
                          me (spec reviewed)
                          other (spec (if (= reviewed :candidate) :ref :candidate))]]
                {:seed s :matchup m :reviewed reviewed :side side
                 :corp (if (= side :corp) me other) :runner (if (= side :corp) other me)})
        order (vec (sim-shuffle (vec games) shuffle-seed))
        pool (java.util.concurrent.Executors/newFixedThreadPool threads)
        played (try (mapv deref (doall (for [g order]
                                         (.submit pool ^Callable
                                                  (fn [] (play-and-render {:corp (:corp g) :runner (:runner g) :seed (:seed g)
                                                                           :corp-deck (keyword (str (name (:matchup g)) "-corp"))
                                                                           :runner-deck (keyword (str (name (:matchup g)) "-runner"))}))))))
                    (finally (.shutdown pool)))
        key (vec (for [[j g] (map-indexed vector order)
                       :let [{:keys [result log actions]} (played j)
                             f (format "log-%02d.txt" (inc j))]]
                   (do (spit (str dir "/" f)
                             (str "Review the " (if (= :corp (:side g)) "CORP" "RUNNER") "'s play in this game.\n"
                                  "Winner: " (some-> (:winner result) name) " (" (:reason result) "), turn " (:turn result) "\n\n"
                                  log "\n"))
                       ;; :log (action indices) makes the game replayable with h/replay-game for diagnosis
                       (merge (dissoc g :corp :runner) {:file f :winner (:winner result) :turn (:turn result) :log actions}))))]
    (spit (str dir "-key.edn") (pr-str key))
    key))
