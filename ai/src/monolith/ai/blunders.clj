(ns monolith.ai.blunders
  "Cheap automatic proxies for the T3 blind-review rubric, computed by replaying logged games
  (evalset rows with :log). Per game and side, counts:
    :rich-credit-clicks  click-for-credit with >= 15 credits
    :forced-discards     cards discarded to hand size
    :agendas-to-archives Corp agendas that entered Archives during the Corp's own turn
    :repeat-failed-runs  runs on a server already run unsuccessfully this turn (Runner)
    :idle-turns          own turns with only credit clicks / free abilities
  plus :flatlined (Runner lost by flatline) and :decked (Corp lost by decking).
  In an evalset row the tested agent plays (:side row); the other side is the opponent."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [monolith.ai.harness :as h]))

(def ks [:rich-credit-clicks :forced-discards :agendas-to-archives :repeat-failed-runs :idle-turns :reinstalls :tagged-ends])

(defn- agenda-count [s zone]
  (count (filter #(= "Agenda" (:type %)) (get-in s [:corp zone]))))

(defn- server-label [k]
  (case (name k) "hq" "HQ" "rd" "R&D" "archives" "Archives" (str "Server " (subs (name k) 6))))

(defn game-metrics
  "Replays row; returns {:corp {...} :runner {...} :winner :reason :turns :faithful}."
  [row]
  (let [zero (zipmap ks (repeat 0))
        m (atom {:corp zero :runner zero})
        turn-acts (atom #{})
        failed (atom #{})
        installed (atom {:corp #{} :runner #{}})
        prev (atom nil)
        bump! (fn [sd k n] (swap! m update-in [sd k] + n))
        res (binding [h/*on-step*
                      (fn [g d a]
                        (let [s @(:state g)
                              p @prev
                              sd (:side d)]
                          (when (and p (not= [(:turn p) (:active-player p)] [(:turn s) (:active-player s)]))
                            (when (empty? (filter #{:install :play :run :advance :score :draw :rez :click-ability
                                                    :trash-resource :purge :remove-tag} @turn-acts))
                              (bump! (:active-player p) :idle-turns 1))
                            ;; Runner ending its turn tagged with a grip of 4 or fewer (kill-deck exposure)
                            (when (and (= :runner (:active-player p)) (pos? (get-in s [:runner :tag :base] 0))
                                       (<= (count (get-in s [:runner :hand])) 4))
                              (bump! :runner :tagged-ends 1))
                            (reset! turn-acts #{})
                            (reset! failed #{}))
                          (when (= sd (:active-player s)) (swap! turn-acts conj (:type a)))
                          ;; installing a card title this side already installed earlier (Eater bounced
                          ;; by Archangel every turn, heap breakers re-bought): only Runner permanents
                          (when (and p (= :install (:type a)) (= sd :runner))
                            (let [t (get-in a [:args :card :title])
                                  rig (get-in p [:runner :rig])
                                  in-play (some #(= t (:title %)) (concat (:program rig) (:hardware rig) (:resource rig)))]
                              (when (and (contains? (get @installed sd) t) (not in-play)) (bump! sd :reinstalls 1))
                              (swap! installed update sd conj t)))
                          (when (and p (= :credit (:type a)) (>= (or (get-in p [sd :credit]) 0) 15)) (bump! sd :rich-credit-clicks 1))
                          (when (and (= :run (:type a)) (@failed (get-in a [:args :server]))) (bump! :runner :repeat-failed-runs 1))
                          (when (and p (= :prompt (:kind d)) (re-find #"(?i)^discard down" (str (some-> (get-in p [sd :prompt]) first :msg))))
                            (bump! sd :forced-discards 1))
                          (when (and p (:run p) (not (:run s)) (not (get-in p [:run :successful])))
                            (when-let [k (first (get-in p [:run :server]))] (swap! failed conj (server-label k))))
                          (when (and p (= :corp (:active-player s)) (> (agenda-count s :discard) (agenda-count p :discard)))
                            (bump! :corp :agendas-to-archives (- (agenda-count s :discard) (agenda-count p :discard))))
                          (let [empty-iced (count (for [[k srv] (get-in s [:corp :servers])
                                                        :when (and (.startsWith (name k) "remote") (seq (:ices srv)) (empty? (:content srv)))]
                                                    k))]
                            (swap! m update-in [:corp :max-empty-iced-remotes] (fnil max 0) empty-iced))
                          (reset! prev s)))]
              (h/replay-game {:seed (:seed row) :corp-deck (keyword (:corp-deck row))
                              :runner-deck (keyword (:runner-deck row)) :log (:log row)}))
        reason (str (:reason res))]
    (-> @m
        (assoc-in [:runner :flatlined] (if (and (= :corp (:winner res)) (= "Flatline" reason)) 1 0))
        (assoc-in [:corp :decked] (if (and (= :runner (:winner res)) (= "Decked" reason)) 1 0))
        (assoc :turns (:turn res) :faithful (= (some-> (:winner res) name) (:winner row))))))

(defn summarize
  "Per tag: mean metrics of the tested agent and of the opponent, per side, over a games log."
  [games-log tags]
  (let [rows (->> (line-seq (io/reader games-log))
                  (map #(json/parse-string % true))
                  (filter #(and (contains? tags (:tag %)) (:log %) (not (:stall %))))
                  vec)
        ms (pmap (fn [r] (assoc (try (game-metrics r) (catch Throwable _ {:faithful false})) :tag (:tag r) :side (keyword (:side r)))) rows)
        ms (filter :faithful ms)
        mean (fn [xs] (into {:n (count xs)}
                            (for [k (concat ks [:flatlined :decked :max-empty-iced-remotes])
                                  :let [vs (keep k xs)] :when (seq vs)]
                              [k (/ (Math/round (* 100.0 (/ (reduce + 0.0 vs) (count vs)))) 100.0)])))]
    (for [[t xs] (sort-by key (group-by :tag ms))
          side [:corp :runner]]
      {:tag t :side side
       :tested (mean (map side (filter #(= side (:side %)) xs)))
       :opponent (mean (map side (filter #(not= side (:side %)) xs)))
       :turns (/ (Math/round (* 10.0 (/ (reduce + 0.0 (map :turns xs)) (count xs)))) 10.0)})))
