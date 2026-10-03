(ns monolith.ai.sim
  "Simulator handle for search agents. `begin!` saves the real state, then rewrites the game atom
  in place into a determinization consistent with one side's observation; `end!` restores it.
  In between, search uses make/unmake (`snapshot`/`restore!`) on the same atom, with a
  sim-local RNG and id counter so the real game's randomness and ids are untouched.

  Determinizer rules (plan section 5.3): own hidden cards are a shuffle of what is left;
  opponent hidden cards come from their decklist minus every card this side has seen, with
  typed slots (ice slots only get ice, remote roots only agendas/assets/upgrades, advanced cards
  lean to agendas); sampled cards are built the engine's way (make-card from server-card)."
  (:require
   [clojure.walk :as walk]
   [game.core.initializing :refer [make-card]]
   [game.utils :refer [server-card]]
   [monolith.ai.engine :as engine]
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.servers :as srv]
   [monolith.ai.moves :as moves]
   [monolith.ai.observe :as observe]))

(defn shuffle-with [coll ^java.util.Random rng]
  (let [al (java.util.ArrayList. ^java.util.Collection (or (seq coll) []))]
    (java.util.Collections/shuffle al rng)
    (vec al)))

(defn- visible-titles
  "Titles of the opponent's cards this side can identify (observation-based)."
  [obs opp]
  (let [opp-name (if (= opp :corp) "Corp" "Runner")
        rig (get-in obs [:runner :rig])
        all (concat (srv/all-corp-installed obs)
                    (:program rig) (:hardware rig) (:resource rig) (:facedown rig)
                    (for [s [:corp :runner] z [:discard :scored :current :rfg :play-area :hand :set-aside]
                          c (get-in obs [s z])] c))]
    (->> all (filter #(and (= opp-name (:side %)) (:title %) (not (:hidden %)))) (map :title))))

(defn- pool [decklist seen]
  (let [seen (frequencies seen)]
    (into [] (for [[t q] (sort decklist) :let [left (- q (get seen t 0))] _ (range (max 0 left))] t))))

(defn- take-weighted!
  "Removes and returns a title from the mutable pool (ArrayList) by weight fn, or nil."
  [^java.util.ArrayList pl wf ^java.util.Random rng]
  (let [ws (mapv wf pl)
        tot (reduce + 0.0 ws)]
    (when (pos? tot)
      (let [r (* tot (.nextDouble rng))]
        (loop [i 0 acc 0.0]
          (let [acc (+ acc (ws i))]
            (if (or (>= acc r) (= i (dec (count ws))))
              (.remove pl (int i))
              (recur (inc i) acc))))))))

(defn- rebuild [old title]
  (let [fresh (make-card (server-card title) (:cid old))]
    (merge fresh (select-keys old [:zone :host :hosted :advance-counter :extra-advance-counter :counter
                                   :installed :new :index :side :previous-zone :facedown :rezzed]))))

(defn- hidden-installed-slots
  "[path card kind] for opponent-installed corp cards the Runner cannot identify."
  [s]
  (for [[k srv] (get-in s [:corp :servers])
        [zk kind] [[:ices :ice] [:content (if (srv/remote? k) :remote :root)]]
        [i c] (map-indexed vector (get srv zk))
        :when (not (or (:rezzed c) (:seen c)))]
    [[:corp :servers k zk i] c kind]))

(defn determinize!
  "Rewrites the game atom into a determinization for `side`. Returns the set of rewritten cids."
  [state side decks ^java.util.Random rng]
  (let [s @state
        opp (if (= side :corp) :runner :corp)
        obs (observe/observe s side)
        decklist (engine/decklist (decks opp))
        pl (java.util.ArrayList. ^java.util.Collection (pool decklist (visible-titles obs opp)))
        _ (java.util.Collections/shuffle pl rng)
        changed (transient #{})
        put (fn [s path old title] (conj! changed (:cid old)) (assoc-in s path (rebuild old title)))
        typ #(cards/ctype %)
        s (if (= opp :corp)
            ;; Runner's view: typed installed slots first
            (let [slots (hidden-installed-slots s)
                  s (reduce (fn [s [path c kind]]
                              (let [adv (or (:advance-counter c) 0)
                                    wf (case kind
                                         :ice #(if (= "ICE" (typ %)) 1.0 0.0)
                                         :root #(if (= "Upgrade" (typ %)) 1.0 0.0)
                                         :remote #(case (typ %)
                                                    "Agenda" (if (pos? adv) 3.0 1.0)
                                                    "Asset" (if (and (pos? adv) (not (srv/trap-damage % 0))) 0.02 (if (pos? adv) 0.8 1.0))
                                                    "Upgrade" (if (pos? adv) 0.02 0.5)
                                                    0.0))
                                    t (or (take-weighted! pl wf rng)
                                          ;; pool exhausted for this type: any decklist title of the type
                                          (first (filter #(pos? (wf %)) (keys decklist))))]
                                (if t (put s path c t) s)))
                            s slots)
                  ;; facedown archives
                  s (reduce (fn [s [i c]]
                              (if (or (:seen c) (empty? pl)) s
                                  (put s [:corp :discard i] c (.remove pl (int 0)))))
                            s (map-indexed vector (get-in s [:corp :discard])))
                  ;; HQ then R&D get the rest in random order
                  s (reduce (fn [s [i c]]
                              (if (empty? pl) s (put s [:corp :hand i] c (.remove pl (int 0)))))
                            s (map-indexed vector (get-in s [:corp :hand])))
                  s (reduce (fn [s [i c]]
                              (if (empty? pl) s (put s [:corp :deck i] c (.remove pl (int 0)))))
                            s (map-indexed vector (get-in s [:corp :deck])))]
              ;; own stack order is unknown
              (update-in s [:runner :deck] #(vec (shuffle-with % rng))))
            ;; Corp's view
            (let [s (reduce (fn [s [i c]]
                              (if (empty? pl) s (put s [:runner :rig :facedown i] c (.remove pl (int 0)))))
                            s (map-indexed vector (get-in s [:runner :rig :facedown])))
                  s (reduce (fn [s [i c]]
                              (if (empty? pl) s (put s [:runner :hand i] c (.remove pl (int 0)))))
                            s (map-indexed vector (get-in s [:runner :hand])))
                  s (reduce (fn [s [i c]]
                              (if (empty? pl) s (put s [:runner :deck i] c (.remove pl (int 0)))))
                            s (map-indexed vector (get-in s [:runner :deck])))]
              (update-in s [:corp :deck] #(vec (shuffle-with % rng)))))
        changed (persistent! changed)
        fresh (into {} (for [z [[:corp :hand] [:corp :deck] [:corp :discard] [:runner :hand] [:runner :deck]]
                             c (get-in s z)
                             :when (changed (:cid c))]
                         [(:cid c) c]))
        scrub (fn [x] (walk/postwalk #(if (and (map? %) (:cid %) (changed (:cid %)) (:title %))
                                         (or (fresh (:cid %)) (dissoc % :title :printed-title :code))
                                         %)
                                     x))]
    (reset! state (-> s
                      (dissoc :history :click-states :turn-state :paid-ability-state)
                      (update :turn-events scrub)))
    changed))

;;; Handle

(defn make
  "A handle bound to game g for `side`. Nothing in it exposes the real state to the agent."
  [g side decks]
  {:g g :side side :decks decks :saved (atom nil)})

(defn begin!
  "Saves the real state and determinizes for the handle's side. seed drives sampling and sim RNG."
  [{:keys [g side decks saved] :as sim} seed]
  (when @saved (throw (ex-info "sim already begun" {})))
  (reset! saved @(:state g))
  (let [rng (java.util.Random. seed)]
    (determinize! (:state g) side decks rng)
    (assoc sim :sg (assoc g :rng rng :ids (atom 1000000000)))))

(defn begin-true!
  "Clairvoyant session on the true state (reference agent only): saves it, no determinization."
  [{:keys [g saved] :as sim} seed]
  (when @saved (throw (ex-info "sim already begun" {})))
  (reset! saved @(:state g))
  (assoc sim :sg (assoc g :rng (java.util.Random. seed) :ids (atom 1000000000))))

(defn resume!
  "Re-enters a session: saves the real state and installs a previously determinized snapshot."
  [{:keys [g saved] :as sim} snap]
  (when @saved (throw (ex-info "sim already begun" {})))
  (reset! saved @(:state g))
  (reset! (:state g) snap)
  sim)

(defn end! [{:keys [g saved]}]
  (when-let [s @saved] (reset! (:state g) s) (reset! saved nil)))

(defn snapshot [{:keys [g]}] @(:state g))
(defn restore! [{:keys [g]} snap] (reset! (:state g) snap))
(defn decision [{:keys [g]}] (moves/decision (:state g)))
(defn legal [{:keys [g]} d] (moves/legal (:state g) d nil))
(defn obs [{:keys [g]} side] (observe/observe @(:state g) side))

(defn apply!
  "Applies an action in the sim. Returns true, or false if the engine threw or ignored it."
  [{:keys [sg]} action]
  (let [state (:state sg)
        before @state]
    (try (engine/command! sg (:side action) (:command action) (:args action))
         (not (identical? before @state))
         (catch Throwable _ false))))
