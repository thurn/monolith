(ns monolith.ai.sweep
  "Deck-pool sweeps: plays a fixed pairing on every matchup and prints stall rates, Corp win
  share and win reasons per matchup. Used to find where move gen or the card-text vocabulary
  breaks on decks the agents were not developed on."
  (:require
   [jinteki.preconstructed :as precon]
   [monolith.ai.tourney :as tourney]))

(def holdout
  "Matchups reserved for confirmation runs (Stage C and the newest Worlds)."
  #{:worlds-2023-a :worlds-2023-b :worlds-2024-a :worlds-2024-b :worlds-2025-a :worlds-2025-b})

(def dev-matchups (sort (remove holdout precon/all-matchups)))

(def dev-mix
  "A/B matchup mix: Stage A, Stage B and every dev Worlds/Classique matchup (seed s plays
  matchup s mod 40, so improvements must transfer across decks)."
  (vec (concat [:gateway-beginner :gateway-intermediate] dev-matchups)))

(def corp-proxy
  "Dev matchups whose Corps resemble the held-out ones (NEH/asset-spam), chosen from held-out deck
  lists only: a dev signal for Corp transfer (R4)."
  [:worlds-2018-a :worlds-2018-b :worlds-2020-b :worlds-2021-b :classique-2022-c :classique-2025-b :classique-2026-d :worlds-2016-a])

(def runner-proxy
  "Dev matchups whose Corps field big or advanceable ice (Weyland/Argus/Blue Sun/HB), a dev signal
  for the Runner against held-out Corps of that kind (R4)."
  [:worlds-2012-b :classique-2022-d :classique-2023-d :classique-2025-d :classique-2026-b :worlds-2013-a :worlds-2012-a :worlds-2022-b])

(def corp-hard
  "Dev matchups where the champion's Corp wins least vs :s1ref (64-88% on jobs X/Y/AD; corp-proxy sits
  at ~93%, too near the ceiling to show Corp differences). Chosen from dev results only (R4, 2026-10-07)."
  [:classique-2025-a :worlds-2017-a :worlds-2020-a :worlds-2018-b :classique-2022-a :worlds-2017-b :worlds-2019-a :worlds-2021-a])

(def modern-mix
  "Modern Standard dev matchups (R4): Standard card pool 2021-2025 like the held-out decks, different lists."
  [:modern-a :modern-b :modern-c :modern-d])

(def holdout-mix [:worlds-2023-a :worlds-2023-b :worlds-2024-a :worlds-2024-b :worlds-2025-a :worlds-2025-b])

(defn deck-keys [m] [(keyword (str (name m) "-corp")) (keyword (str (name m) "-runner"))])

(defn run
  "opts: :matchups seq of matchup keys, :corp :runner agent specs, :seeds, :threads, :out jsonl."
  [{:keys [matchups corp runner seeds threads out] :or {threads 12}}]
  (let [games (for [m matchups :let [[cd rd] (deck-keys m)] s seeds]
                {:matchup m :corp corp :runner runner :seed s :corp-deck cd :runner-deck rd :budget-ms 250})
        write (when out (tourney/write-jsonl-fn out (tourney/shas)))
        results (tourney/run-games games {:threads threads :on-result (fn [r] (when write (write (dissoc r :log))))})
        rows (for [[m rs] (group-by :matchup (map (fn [g r] (assoc r :matchup (:matchup g))) games results))
                   :let [ok (remove :stall rs) n (count ok)]]
               {:matchup m :n (count rs) :stalls (count (filter :stall rs))
                :stall-causes (frequencies (map (comp :cause :stall) (filter :stall rs)))
                :corp-win (if (pos? n) (/ (count (filter #(= :corp (:winner %)) ok)) (double n)) 0.0)
                :turns (if (pos? n) (/ (reduce + (map :turn ok)) (double n)) 0.0)
                :reasons (frequencies (map #(str (name (or (:winner %) :none)) "/" (:reason %)) ok))})]
    (doseq [{:keys [matchup n stalls stall-causes corp-win turns reasons]} (sort-by :matchup rows)]
      (println (format "%-20s n=%3d stalls=%2d corp=%.2f turns=%4.1f %s %s"
                       (name matchup) n stalls corp-win turns reasons (if (seq stall-causes) stall-causes ""))))
    (flush)
    rows))
