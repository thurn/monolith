(ns monolith.ai.puzzles
  "Tactical puzzle suite (plan section 7.5): hand-built Stage A positions with a known right
  answer, checked by outcome at the end of the acting side's turn. Positions are built on the
  real engine by pulling named cards out of the decks and installing them ignoring costs."
  (:require
   [clojure.string :as str]
   [game.core :as core]
   [game.core.eid :refer [make-eid]]
   [monolith.ai.engine :as engine]
   [monolith.ai.harness :as h]
   [monolith.ai.moves :as moves]
   [monolith.ai.tourney :as tourney]))

(defn- pull!
  "Moves a card titled t from side's deck (or hand) to the set-aside zone and returns it."
  [state side t]
  (let [c (or (some #(when (= t (:title %)) %) (get-in @state [side :deck]))
              (some #(when (= t (:title %)) %) (get-in @state [side :hand])))]
    (when-not c (throw (ex-info (str "card not available: " t) {:side side})))
    (core/move state side c :set-aside)
    (last (get-in @state [side :set-aside]))))

(defn- settle-prompts!
  "Answers any prompts the setup created with the first option."
  [g]
  (dotimes [_ 20]
    (let [d (moves/decision (:state g))]
      (when (= :prompt (:kind d))
        (let [a (first (moves/legal (:state g) d nil))]
          (when a (engine/command! g (:side d) (:command a) (:args a))))))))

(defn build
  "spec: {:active :corp|:runner
          :corp {:hand [t] :credits n :install [{:t title :server \"Server 1\" :adv n :rez bool}] :discard [t] :scored [t]}
          :runner {:hand [t] :credits n :install [t] :scored [t]}}"
  [{:keys [active corp runner seed corp-deck runner-deck]
    :or {seed 1 corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner}}]
  (let [g (engine/new-game {:seed seed :corp corp-deck :runner runner-deck})
        state (:state g)]
    (engine/with-game g
      (core/keep-hand state :corp nil)
      (core/keep-hand state :runner nil)
      (core/start-turn state :corp nil)
      ;; empty both hands back into the decks
      (doseq [side [:corp :runner] c (get-in @state [side :hand])]
        (core/move state side c :deck))
      (doseq [t (:install corp)]
        (let [{:keys [t server adv rez]} t
              c (pull! state :corp t)]
          (core/corp-install state :corp (make-eid state) c server {:ignore-all-cost true})
          (let [c (some #(when (= (:cid c) (:cid %)) %) (for [[_ s] (get-in @state [:corp :servers]) x (concat (:ices s) (:content s))] x))]
            (when (and adv (pos? adv)) (core/add-prop state :corp (make-eid state) c :advance-counter adv {:placed true}))
            (when rez (core/rez state :corp (make-eid state) (core/get-card state c) {:ignore-cost :all-costs})))))
      (doseq [t (:install runner)]
        (core/runner-install state :runner (make-eid state) (pull! state :runner t) {:ignore-all-cost true}))
      (doseq [t (:discard corp)]
        (let [c (pull! state :corp t)] (core/move state :corp (assoc c :seen true) :discard)))
      (doseq [t (:scored corp)]
        (let [c (core/move state :corp (pull! state :corp t) :scored)]
          (core/card-init state :corp c {:resolve-effect false :init-data true})))
      (doseq [t (:scored runner)]
        (core/move state :runner (pull! state :corp t) :scored {:force true}))
      (doseq [[side spec] [[:corp corp] [:runner runner]] t (:hand spec)]
        (core/move state side (pull! state side t) :hand))
      (core/fake-checkpoint state))
    (settle-prompts! g)
    (when (= active :runner)
      (swap! state assoc-in [:corp :click] 0)
      (engine/command! g :corp "end-turn" nil)
      (settle-prompts! g)
      (engine/command! g :runner "start-turn" nil)
      (settle-prompts! g))
    (when-let [n (:tags runner)]
      (engine/with-game g (core/gain-tags state :corp (make-eid state) n))
      (settle-prompts! g))
    (doseq [[side spec] [[:corp corp] [:runner runner]] :when (:credits spec)]
      (swap! state assoc-in [side :credit] (:credits spec)))
    (when-let [c (:clicks (if (= active :corp) corp runner))]
      (swap! state assoc-in [active :click] c))
    g))

(defn ap [s side] (get-in s [side :agenda-point] 0))

(def suite
  "Each puzzle: :name, :spec (see build), :goal (fn [start-state end-state] bool)."
  [{:name "corp-score-now"
    :spec {:active :corp :corp {:credits 5 :hand ["Hedge Fund"]
                                :install [{:t "Palisade" :server "New remote" :rez true}
                                          {:t "Superconducting Hub" :server "Server 1" :adv 3}]}}
    :goal (fn [a b] (> (ap b :corp) (ap a :corp)))}
   {:name "corp-advance-to-score"
    :spec {:active :corp :corp {:credits 4 :hand ["Palisade"]
                                :install [{:t "Palisade" :server "New remote" :rez true}
                                          {:t "Superconducting Hub" :server "Server 1" :adv 0}]}}
    :goal (fn [a b] (> (ap b :corp) (ap a :corp)))}
   {:name "corp-seamless-to-score"
    :spec {:active :corp :corp {:credits 4 :hand ["Seamless Launch"]
                                :install [{:t "Palisade" :server "New remote" :rez true}
                                          {:t "Offworld Office" :server "Server 1" :adv 1}]}}
    :goal (fn [a b] (> (ap b :corp) (ap a :corp)))}
   {:name "corp-win-by-scoring"
    :spec {:active :corp :corp {:credits 6 :hand ["Hedge Fund"] :scored ["Send a Message" "Offworld Office"]
                                :install [{:t "Whitespace" :server "New remote" :rez true}
                                          {:t "Offworld Office" :server "Server 1" :adv 2}]}}
    :goal (fn [a b] (= :corp (:winner b)))}
   {:name "corp-rez-to-stop-steal"
    :agent-side :corp
    :spec {:active :runner :corp {:credits 5 :install [{:t "Palisade" :server "New remote"}
                                                       {:t "Send a Message" :server "Server 1" :adv 3}]}
           :runner {:credits 5 :hand ["Sure Gamble" "VRcation" "Creative Commission"]}}
    :goal (fn [a b] (some #(= "Send a Message" (:title %)) (get-in b [:corp :servers :remote1 :content])))}
   {:name "corp-no-naked-agenda"
    :spec {:active :corp :corp {:credits 3 :hand ["Offworld Office" "Hedge Fund" "Hedge Fund"]}
           :runner {:credits 8}}
    :goal (fn [a b] (not-any? (fn [[_ s]] (and (some #(= "Agenda" (:type %)) (:content s)) (empty? (:ices s))))
                              (get-in b [:corp :servers])))}
   {:name "runner-steal-naked-agenda"
    :spec {:active :runner :corp {:install [{:t "Offworld Office" :server "New remote" :adv 2}] :discard ["Urtica Cipher" "Urtica Cipher"]}
           :runner {:credits 5 :hand ["Sure Gamble" "Cleaver" "Unity"]}}
    :goal (fn [a b] (> (ap b :runner) (ap a :runner)))}
   {:name "runner-break-etr-to-steal"
    :spec {:active :runner :corp {:credits 0 :install [{:t "Palisade" :server "New remote" :rez true}
                                                       {:t "Send a Message" :server "Server 1" :adv 3}]
                                  :discard ["Urtica Cipher" "Urtica Cipher"]}
           :runner {:credits 8 :install ["Cleaver"] :hand ["Sure Gamble" "Jailbreak"]}}
    :goal (fn [a b] (> (ap b :runner) (ap a :runner)))}
   {:name "runner-install-breaker-then-steal"
    :spec {:active :runner :corp {:credits 0 :install [{:t "Whitespace" :server "New remote" :rez true}
                                                       {:t "Offworld Office" :server "Server 1" :adv 3}]
                                  :discard ["Urtica Cipher" "Urtica Cipher"]}
           :runner {:credits 9 :hand ["Unity" "Sure Gamble" "Telework Contract"]}}
    :goal (fn [a b] (> (ap b :runner) (ap a :runner)))}
   {:name "runner-win-by-stealing"
    :spec {:active :runner :corp {:credits 0 :scored [] :install [{:t "Palisade" :server "New remote" :rez true}
                                                                  {:t "Send a Message" :server "Server 1" :adv 2}]}
           :runner {:credits 10 :install ["Cleaver"] :scored ["Offworld Office" "Offworld Office"] :hand ["Sure Gamble" "VRcation" "Creative Commission"]}}
    :goal (fn [a b] (= :runner (:winner b)))}
   {:name "runner-avoid-urtica-with-small-hand"
    :spec {:active :runner :corp {:install [{:t "Urtica Cipher" :server "New remote" :adv 2}]}
           :runner {:credits 5 :hand ["Sure Gamble" "Cleaver"]}}
    :goal (fn [a b] (nil? (:winner b)))}
   {:name "runner-no-facecheck-with-one-card"
    :spec {:active :runner :corp {:credits 10 :install [{:t "Karunā" :server "R&D"} {:t "Karunā" :server "HQ"}]}
           :runner {:credits 6 :hand ["Sure Gamble"]}}
    :goal (fn [a b] (nil? (:winner b)))}
   {:name "runner-trash-regolith"
    :spec {:active :runner :corp {:install [{:t "Regolith Mining License" :server "New remote" :rez true}]}
           :runner {:credits 6 :hand ["Sure Gamble" "Cleaver"]}}
    :goal (fn [a b] (some #(= "Regolith Mining License" (:title %)) (get-in b [:corp :discard])))}
   {:name "runner-run-central-when-corp-broke"
    :spec {:active :runner :corp {:credits 0 :hand ["Offworld Office" "Send a Message" "Superconducting Hub" "Offworld Office"]
                                  :install [{:t "Karunā" :server "HQ"}]}
           :runner {:credits 3 :hand ["Sure Gamble" "Cleaver" "Unity" "VRcation"]}}
    :goal (fn [a b] (> (ap b :runner) (ap a :runner)))}])

(defn solve
  "Plays the puzzle's acting side with agent-spec (opponent: S1) until that side's turn ends.
  Returns {:name :solved bool}."
  [agent-spec {:keys [name spec goal agent-side]} seed]
  (let [g (build (assoc spec :seed seed))
        side (:active spec)
        me (or agent-side side)
        start @(:state g)
        turn (:turn start)
        agents {me (tourney/make-agent agent-spec me)
                (if (= me :corp) :runner :corp) (tourney/make-agent :heuristic (if (= me :corp) :runner :corp))}
        r (h/play-game {:seed seed :game g :agents agents :budget-ms 250
                        :corp-deck (:corp-deck g) :runner-deck (:runner-deck g)
                        :stop-fn (fn [s] (or (not= side (:active-player s)) (:end-turn s) (not= turn (:turn s))))})
        end @(:state g)]
    {:name name :solved (boolean (goal start end)) :stall (:stall r)}))

(defn run-suite
  "Fraction of puzzles solved (each tried on `seeds` deck orders; solved if solved on a majority)."
  [agent-spec & {:keys [seeds] :or {seeds [1 2 3]}}]
  (let [rs (for [p suite]
             (let [xs (for [s seeds] (solve agent-spec p s))]
               {:name (:name p) :solved (> (count (filter :solved xs)) (/ (count seeds) 2))
                :stalls (count (filter :stall xs))}))]
    {:agent agent-spec :score (/ (count (filter :solved rs)) (double (count rs))) :results (vec rs)}))

;;; Reference solutions: every puzzle carries :solution, a script of label regexes for the acting
;;; side that reaches the goal, and optionally :bad, a script that must not. `check` validates
;;; puzzles with these scripts only, so held-out puzzles are proven well-posed without running
;;; any candidate agent on them.

(defrecord Scripted [side script fallback]
  h/Agent
  (choose [_ {:keys [actions decision] :as ctx}]
    (let [re (first @script)
          hit (when re (first (keep-indexed (fn [i a] (when (re-find re (str (:label a))) i)) actions)))]
      (cond
        hit (do (swap! script rest) hit)
        (= :turn (:kind decision))
        (if re
          (throw (ex-info (str "script step not legal: " re) {:labels (mapv :label actions)}))
          (or (first (keep-indexed (fn [i a] (when (= :credit (:type a)) i)) actions)) 0))
        ;; mid-script, keep a run going (S1 might jack out) until the script's next step applies
        (and re (#{:run :encounter} (:kind decision)) (some #(= :continue (:type %)) actions))
        (first (keep-indexed (fn [i a] (when (= :continue (:type a)) i)) actions))
        :else (h/choose fallback ctx)))))

(defn run-script
  "Plays the puzzle's acting side by `script` (S1 for steps the script does not cover and for the
  opponent). Returns {:solved bool :stall ...}."
  [{:keys [spec goal agent-side]} script seed]
  (let [g (build (assoc spec :seed seed))
        side (:active spec)
        me (or agent-side side)
        start @(:state g)
        turn (:turn start)
        opp (if (= me :corp) :runner :corp)
        agents {me (->Scripted me (atom (map re-pattern script)) (tourney/make-agent :heuristic me))
                opp (tourney/make-agent :heuristic opp)}
        r (try (h/play-game {:seed seed :game g :agents agents :budget-ms 250
                             :corp-deck (:corp-deck g) :runner-deck (:runner-deck g)
                             :stop-fn (fn [s] (or (not= side (:active-player s)) (:end-turn s) (not= turn (:turn s))))})
               (catch Exception e {:stall {:cause :script :error (ex-message e) :data (ex-data e)}}))]
    {:solved (boolean (goal start @(:state g))) :stall (:stall r)}))

(defn check
  "Validates puzzles by their scripts on each seed: the solution must solve, :bad must not."
  [puzzles & {:keys [seeds] :or {seeds [1 2 3]}}]
  (vec (for [p puzzles]
         (let [sol (for [s seeds] (run-script p (:solution p) s))
               bad (when (:bad p) (for [s seeds] (run-script p (:bad p) s)))]
           {:name (:name p)
            :ok (and (every? :solved sol) (not-any? :solved bad))
            :solution (mapv #(if (:stall %) (:stall %) (:solved %)) sol)
            :bad (mapv #(if (:stall %) (:stall %) (:solved %)) bad)}))))

(defn- ap-up [side] (fn [a b] (> (ap b side) (ap a side))))
(defn- winner? [side] (fn [_ b] (= side (:winner b))))
(defn- alive [_ b] (not= :corp (:winner b)))
(defn- m [matchup] {:corp-deck (keyword (str (name matchup) "-corp")) :runner-deck (keyword (str (name matchup) "-runner"))})

(def holdout-suite
  "Held-out puzzles (pre-registered T3 rule, research/LOG.md 2026-10-03): built only from held-out
  decks (Worlds 2023-2025), validated only by their scripts, never used for debugging. Run agents
  on them only for the final T3 evaluation."
  [{:name "h-eotl-lethal"
    :spec (merge (m :worlds-2023-b) {:active :corp :corp {:credits 4 :hand ["End of the Line" "Hedge Fund"]}
                                     :runner {:tags 1 :hand ["Sure Gamble" "Diesel" "Moshing"]}})
    :goal (winner? :corp) :solution ["^play End of the Line"] :bad []}
   {:name "h-mindscaping-damage-lethal"
    :spec (merge (m :worlds-2024-a) {:active :corp :corp {:credits 4 :hand ["Mindscaping" "Sprint"]}
                                     :runner {:tags 3 :hand ["Sure Gamble" "Diesel"]}})
    :goal (winner? :corp) :solution ["^play Mindscaping" "(?i)net damage"] :bad []}
   {:name "h-credit-then-advance-to-score"
    :spec (merge (m :worlds-2025-b) {:active :corp :corp {:credits 1 :hand ["Bigger Picture"]
                                                          :install [{:t "Ping" :server "New remote" :rez true}
                                                                    {:t "Stoke the Embers" :server "Server 1" :adv 2}]}})
    :goal (ap-up :corp) :solution ["^click for credit" "^advance Stoke" "^advance Stoke"] :bad []}
   {:name "h-score-to-win"
    :spec (merge (m :worlds-2024-b) {:active :corp :corp {:credits 2 :scored ["Fujii Asset Retrieval" "Fujii Asset Retrieval"]
                                                          :install [{:t "Tatu-Bola" :server "New remote" :rez true}
                                                                    {:t "House of Knives" :server "Server 1" :adv 1}]}})
    :goal (winner? :corp) :solution ["^advance House" "^advance House"] :bad []}
   {:name "h-no-naked-agenda"
    :spec (merge (m :worlds-2023-a) {:active :corp :corp {:credits 3 :hand ["Send a Message" "Hedge Fund" "Government Subsidy"]}
                                     :runner {:credits 8}})
    :goal (fn [_ b] (not-any? (fn [[_ s]] (and (some #(= "Agenda" (:type %)) (:content s)) (empty? (:ices s))))
                              (get-in b [:corp :servers])))
    :solution [] :bad ["^install Send a Message in New remote"]}
   {:name "h-rez-to-stop-steal"
    :agent-side :corp
    :spec (merge (m :worlds-2025-b) {:active :runner :corp {:credits 5 :install [{:t "Ping" :server "New remote"}
                                                                                  {:t "Artificial Cryptocrash" :server "Server 1" :adv 3}]}
                                     :runner {:credits 5 :hand ["Sure Gamble" "Strike Fund" "Wildcat Strike"]}})
    :goal (fn [_ b] (some #(= "Artificial Cryptocrash" (:title %)) (get-in b [:corp :servers :remote1 :content])))
    :solution ["^rez Ping"]}
   {:name "h-runner-steal-to-win"
    :spec (merge (m :worlds-2023-a) {:active :runner :corp {:credits 0 :install [{:t "Hortum" :server "New remote" :rez true}
                                                                                  {:t "Send a Message" :server "Server 1" :adv 3}]}
                                     :runner {:credits 6 :install ["Unity"] :scored ["Above the Law" "Send a Message"]
                                              :hand ["Sure Gamble" "Dirty Laundry"]}})
    :goal (winner? :runner) :solution ["^run Server 1" "^break Hortum" "^Steal"] :bad []}
   {:name "h-credit-up-for-bellona"
    :spec (merge (m :worlds-2023-b) {:active :runner :corp {:credits 0 :install [{:t "Ping" :server "New remote" :rez true}
                                                                                  {:t "Bellona" :server "Server 1" :adv 3}]}
                                     :runner {:credits 3 :install ["Cleaver"] :hand ["Steelskin Scarring" "Moshing"]}})
    :goal (ap-up :runner)
    :solution ["^click for credit" "^click for credit" "^click for credit" "^run Server 1" "^break Ping" "(?i)steal"]
    :bad ["^run Server 1" "^break Ping"]}
   {:name "h-trash-regolith"
    :spec (merge (m :worlds-2024-a) {:active :runner :corp {:install [{:t "Regolith Mining License" :server "New remote" :rez true}]}
                                     :runner {:credits 6 :hand ["Sure Gamble" "Gauss"]}})
    :goal (fn [_ b] (some #(= "Regolith Mining License" (:title %)) (get-in b [:corp :discard])))
    :solution ["^run Server 1" "(?i)pay.*trash"] :bad []}
   {:name "h-install-breaker-then-steal"
    :spec (merge (m :worlds-2024-a) {:active :runner :corp {:credits 0 :install [{:t "Ping" :server "New remote" :rez true}
                                                                                  {:t "Project Beale" :server "Server 1" :adv 2}]}
                                     :runner {:credits 3 :hand ["Gauss" "Diesel" "Deep Dive"]}})
    :goal (ap-up :runner) :solution ["^play Gauss" "^run Server 1" "^break Ping" "^Steal"] :bad ["^run Server 1"]}
   {:name "h-hq-when-corp-broke"
    :spec (merge (m :worlds-2025-b) {:active :runner :corp {:credits 0 :hand ["Stoke the Embers" "Artificial Cryptocrash" "Freedom of Information" "Offworld Office"]
                                                            :install [{:t "Ping" :server "HQ"}]}
                                     :runner {:credits 3 :hand ["Sure Gamble" "Strike Fund" "Running Hot"]}})
    :goal (ap-up :runner) :solution ["^run HQ" "(?i)steal"] :bad []}
   {:name "h-no-facecheck-one-card"
    :spec (merge (m :worlds-2025-a) {:active :runner :corp {:credits 5 :install [{:t "Fujii Asset Retrieval" :server "New remote"}]}
                                     :runner {:credits 5 :hand ["Strike Fund"]}})
    :goal alive :solution ["^draw" "^draw" "^draw"] :bad ["^run Server 1" "(?i)steal"]}
   {:name "h-must-steal-before-corp-wins"
    :spec (merge (m :worlds-2024-b) {:active :runner :corp {:credits 3 :scored ["Fujii Asset Retrieval" "Hybrid Release" "Regenesis"]
                                                            :install [{:t "Tatu-Bola" :server "New remote" :rez true}
                                                                      {:t "Fujii Asset Retrieval" :server "Server 1" :adv 4}]}
                                     :runner {:credits 4 :install ["Paricia"] :hand ["Gauss" "Sure Gamble" "Diesel" "Spec Work"]}})
    :goal (ap-up :runner) :solution ["^play Gauss" "^run Server 1" "^break Tatu" "^Steal"] :bad []}])
