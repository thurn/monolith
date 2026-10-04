(ns monolith.ai.engine
  "In-process host for the Jinteki.net engine fork: card loading, seeded game creation,
  and applying engine commands under the game's own RNG and id counter."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [game.cards.agendas]
   [game.cards.assets]
   [game.cards.basic]
   [game.cards.events]
   [game.cards.hardware]
   [game.cards.ice]
   [game.cards.identities]
   [game.cards.operations]
   [game.cards.programs]
   [game.cards.resources]
   [game.cards.upgrades]
   [game.core :as core]
   [game.core.say]
   [game.core.eid :as eid]
   [game.core.prompt-state :as prompt-state]
   [game.main :as main]
   [game.rng :as rng]
   [game.utils :refer [server-card]]
   [jinteki.cards :refer [all-cards]]
   [jinteki.i18n :refer [insert-lang!]]
   [jinteki.preconstructed :as precon]
   [taoensso.timbre :as timbre]))

(defonce loaded
  (delay
    (timbre/set-min-level! :fatal)
    (insert-lang! "en" (slurp (io/resource "en.ftl")))
    (->> (io/resource "raw_data.edn") slurp edn/read-string :cards
         (map (juxt :title identity)) (into {})
         (reset! all-cards))
    true))

(defn load! [] @loaded)

(def base-decks
  {:gateway-beginner-corp precon/gateway-beginner-corp
   :gateway-beginner-runner precon/gateway-beginner-runner
   :gateway-intermediate-corp precon/gateway-intermediate-corp
   :gateway-intermediate-runner precon/gateway-intermediate-runner
   ;; Stage C: the two Worlds 2023 final games, each a complete Corp-vs-Runner pairing
   :worlds-2023-a-corp (:corp precon/worlds-2023-sokka-corps)
   :worlds-2023-a-runner (:runner precon/worlds-2023-sokka-corps)
   :worlds-2023-b-corp (:corp precon/worlds-2023-sokka-runs)
   :worlds-2023-b-runner (:runner precon/worlds-2023-sokka-runs)})

(def decks
  "Every bundled deck: base-decks (the NN vocabulary is pinned to these) plus each Worlds and
  Classique matchup as :<matchup>-corp / :<matchup>-runner."
  (into base-decks
        (for [m precon/all-matchups side [:corp :runner]]
          [(keyword (str (name m) "-" (name side))) (side (precon/matchup-by-key m))])))

(def stages
  {:A {:corp :gateway-beginner-corp :runner :gateway-beginner-runner}
   :B {:corp :gateway-intermediate-corp :runner :gateway-intermediate-runner}
   :C1 {:corp :worlds-2023-a-corp :runner :worlds-2023-a-runner}
   :C2 {:corp :worlds-2023-b-corp :runner :worlds-2023-b-runner}})

(defn resolve-deck [{:keys [identity cards]}]
  {:identity (server-card (:title identity))
   :cards (mapv (fn [{:keys [qty card]}] {:qty qty :card (server-card card)}) cards)})

(defn decklist
  "Title -> qty for a deck key, the public knowledge both players have."
  [deck-key]
  (into {} (map (juxt :card :qty)) (:cards (decks deck-key))))

(defmacro with-game
  "Runs body with the game's RNG and id counter bound."
  [g & body]
  `(let [g# ~g]
     (binding [rng/*rng* (:rng g#) rng/*ids* (:ids g#) rng/*headless* true]
       ~@body)))

(defn new-game
  "Creates a seeded game. Returns {:state atom :rng Random :ids atom :seed long ...}."
  [{:keys [seed corp runner] :or {corp :gateway-beginner-corp runner :gateway-beginner-runner}}]
  (load!)
  (let [g {:seed seed
           :rng (java.util.Random. (long seed))
           :ids (atom 0)
           :corp-deck corp
           :runner-deck runner}]
    (assoc g :state
           (with-game g
             (core/init-game
              {:gameid seed
               :format :casual
               :room "casual"
               :players [{:side "Corp" :user {:username "Corp"} :deck (resolve-deck (decks corp))}
                         {:side "Runner" :user {:username "Runner"} :deck (resolve-deck (decks runner))}]})))))

(defn- cancel-select!
  "Resolves the current select prompt with no targets, like \"Done\" on a non-:all select."
  [state side]
  (let [sel (first (get-in @state [side :selected]))
        p (first (get-in @state [side :prompt]))]
    (swap! state update-in [side :selected] #(vec (rest %)))
    (when p (prompt-state/remove-from-prompt-queue state side p))
    (eid/effect-completed state side (:eid (:ability sel)))))

(defn command!
  "Applies one engine command for side. On exception the state is restored and the throwable rethrown."
  [g side command args]
  (with-game g
    (let [state (:state g)
          old @state]
      (try
        (if (= command "monolith-cancel-select")
          (cancel-select! state side)
          (main/handle-action state side command args))
        (catch Throwable t
          (reset! state old)
          (throw t))))))
