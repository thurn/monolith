(ns monolith.ai.harness
  "Seeded game loop: decision points, watchdog, stall bucketing and an action-index log
  that reproduces a game exactly."
  (:require
   [monolith.ai.engine :as engine]
   [monolith.ai.moves :as moves]
   [monolith.ai.observe :as observe]
   [monolith.ai.sim :as sim]))

(defprotocol Agent
  (choose [agent ctx]
    "ctx = {:side :corp :actions [...] :decision {...} :obs (delay <redacted view>) :rng Random
            :budget-ms n :decks {...} :sim (delay <monolith.ai.sim handle>)}. Returns an index into :actions."))

(defn opp-titles [g side]
  (keys (engine/decklist (if (= side :corp) (:runner-deck g) (:corp-deck g)))))

(def ^:dynamic *on-step* nil)

(defn- result [g n stall t0 extra]
  (let [s @(:state g)]
    (merge
     {:seed (:seed g)
      :winner (:winner s)
      :reason (:reason s)
      :turn (:turn s)
      :n-actions n
      :points {:corp (get-in s [:corp :agenda-point]) :runner (get-in s [:runner :agenda-point])}
      :stall stall
      :ms (quot (- (System/nanoTime) t0) 1000000)}
     extra)))

(defn play-game
  "Plays one game. agents: {:corp agent :runner agent}. Returns a result map with :log, the
  list of chosen indices, enough to replay with `replay-game`."
  [{:keys [seed corp-deck runner-deck agents max-actions max-turn-actions replay budget-ms game stop-fn]
    :or {corp-deck :gateway-beginner-corp runner-deck :gateway-beginner-runner
         max-actions 6000 max-turn-actions 400 budget-ms 250}}]
  (let [t0 (System/nanoTime)
        g (or game (engine/new-game {:seed seed :corp corp-deck :runner runner-deck}))
        state (:state g)
        agent-rng {:corp (java.util.Random. (+ seed 1000003)) :runner (java.util.Random. (+ seed 2000003))}
        titles {:corp (opp-titles g :corp) :runner (opp-titles g :runner)}
        log (transient [])
        noops (volatile! 0)
        errors (volatile! [])
        think-ns {:corp (volatile! 0) :runner (volatile! 0)}
        decisions {:corp (volatile! 0) :runner (volatile! 0)}]
    (loop [n 0 replay (seq replay) turn-key nil turn-n 0]
      (let [d (moves/decision state)
            s @state
            tk [(:turn s) (:active-player s)]
            turn-n (if (= tk turn-key) (inc turn-n) 0)]
        (cond
          (and stop-fn (stop-fn s)) (result g n nil t0 {:log (persistent! log) :stopped true})
          (moves/game-over? s) (result g n nil t0 {:log (persistent! log) :noops @noops :errors (count @errors) :error-sample (first @errors)
                                                   :think-ms {:corp (quot @(think-ns :corp) 1000000) :runner (quot @(think-ns :runner) 1000000)}
                                                   :decisions {:corp @(decisions :corp) :runner @(decisions :runner)}})
          (nil? d) (result g n {:cause :no-decision} t0 {:log (persistent! log) :noops @noops})
          (> n max-actions) (result g n {:cause :action-cap} t0 {:log (persistent! log) :noops @noops})
          (> turn-n max-turn-actions) (result g n {:cause :livelock :decision d} t0 {:log (persistent! log) :noops @noops})
          :else
          (let [side (:side d)
                stall
                (loop [actions (moves/legal state d (titles side)) replay replay]
                  (if (empty? actions)
                    [{:cause :no-moves :decision d :prompt (some-> (moves/current-prompt s side) (select-keys [:msg :prompt-type :choices]))} replay]
                    (let [t1 (System/nanoTime)
                          idx (if replay
                                (first replay)
                                (choose (agents side) {:side side :actions actions :decision d
                                                       :obs (delay (observe/observe @state side))
                                                       :sim (delay (sim/make g side {:corp corp-deck :runner runner-deck}))
                                                       :decks {:corp corp-deck :runner runner-deck}
                                                       :rng (agent-rng side) :budget-ms budget-ms}))
                          _ (vswap! (think-ns side) + (- (System/nanoTime) t1))
                          _ (vswap! (decisions side) inc)
                          replay (next replay)
                          action (nth actions idx)
                          before @state
                          err (try (engine/command! g side (:command action) (:args action)) nil
                                   (catch Throwable t t))]
                      (conj! log idx)
                      (when *on-step* (*on-step* g d action))
                      (cond
                        ;; command! restored the state: drop the throwing action and re-ask (stall
                        ;; only if every action throws); the last error is kept for diagnosis
                        err (do (vswap! errors conj {:action (dissoc action :args) :error (str err)})
                                (if (> (count actions) 1)
                                  (recur (into (subvec actions 0 idx) (subvec actions (inc idx))) replay)
                                  [{:cause :exception :action (dissoc action :args) :error (str err)
                                    :trace (mapv str (take 12 (.getStackTrace ^Throwable err)))} replay]))
                        (moves/noop? before @state)
                        (do (vswap! noops inc)
                            (recur (into (subvec actions 0 idx) (subvec actions (inc idx))) replay))
                        :else [nil replay]))))]
            (let [[stall replay] stall]
              (if stall
                (result g n stall t0 {:log (persistent! log) :noops @noops})
                (recur (inc n) replay tk turn-n)))))))))

(defn replay-game
  "Replays a game from its seed, decks and :log; with *on-step* bound this can trace it."
  [{:keys [seed corp-deck runner-deck log] :as game}]
  (play-game (assoc game :replay log :agents {})))
