(ns monolith.ai.moves
  "Legal action generation shared by every agent.

  An action is plain data: {:side :corp :command \"play\" :args {...} :label \"...\" :type :install}.
  `decision` finds who must act; `legal` lists their actions. Rules (after netrunner-rs):
  identical duplicate actions are removed, multi-select prompts are progressive (no deselect),
  and number prompts are discretized."
  (:require
   [clojure.string :as str]
   [game.core.board :refer [all-active all-active-installed all-installed get-all-cards installable-servers]]
   [game.core.card :refer [agenda? asset? corp? event? get-card hardware? ice? in-hand? installed?
                           operation? program? resource? rezzed? upgrade? get-counters]]
   [game.core.diffs :refer [ability-playable? playable?]]
   [game.core.flags :refer [can-advance? can-score? can-rez?]]
   [game.core.installing :refer [corp-can-pay-and-install?]]
   [game.core.payment :refer [can-pay? ->c]]
   [game.core.rezzing :refer [get-rez-cost]]
   [game.core.runs :refer [get-current-encounter get-runnable-zones]]
   [game.core.ice :refer [get-current-ice]]
   [game.core.servers :refer [zone->name]]
   [game.core.eid :refer [make-eid]]
   [game.core.card :as card]))

(defn other [side] (if (= side :corp) :runner :corp))

(defn card-ref [c] (select-keys c [:cid :zone :side :type :title :host]))

(defn current-prompt [s side] (first (get-in s [side :prompt])))

(defn blocking-prompt? [p]
  (and p (not (#{:waiting :run} (:prompt-type p)))))

(defn game-over? [s] (boolean (:winner s)))

(defn decision
  "Who must act next and in what context: {:side s :kind k}. kind is one of
  :prompt :encounter :run :phase-12 :start-turn :turn :end-turn, or nil if nobody can act."
  [state]
  (let [s @state
        cp (current-prompt s :corp)
        rp (current-prompt s :runner)
        run (:run s)
        active (:active-player s)]
    (cond
      (game-over? s) nil
      (blocking-prompt? cp) (if (and (blocking-prompt? rp) (= active :runner) (not= :mulligan (:prompt-type rp)))
                              {:side :runner :kind :prompt}
                              {:side :corp :kind :prompt})
      (blocking-prompt? rp) {:side :runner :kind :prompt}
      (seq (:encounters s))
      (let [enc (get-current-encounter state)]
        {:side (if (= :runner (:no-action enc)) :corp :runner) :kind :encounter})
      run {:side (if (= :runner (:no-action run)) :corp :runner) :kind :run}
      (get-in s [:corp-phase-12 :active]) {:side :corp :kind :phase-12}
      (get-in s [:runner-phase-12 :active]) {:side :runner :kind :phase-12}
      (or (:end-turn s) (not (get-in s [active :turn-started])))
      {:side (if (:end-turn s) (other active) active) :kind :start-turn}
      (pos? (get-in s [active :click] 0)) {:side active :kind :turn}
      :else {:side active :kind :end-turn})))

(defn- act [side command args label type]
  {:side side :command command :args args :label label :type type})

;;; Prompt actions

(defn- buckets [mx]
  (->> [0 1 2 3 (quot mx 2) mx] (filter #(<= 0 % mx)) distinct sort vec))

(defn- choice-label [{:keys [value]}]
  (cond (string? value) value
        (map? value) (str (:title value) " " (:zone value))
        :else (str value)))

(defn- select-actions [state side p]
  (let [s @state
        sel (first (get-in s [side :selected]))
        ability (:ability sel)
        src (when (:card ability) (get-card state (:card ability)))
        chosen (set (map :cid (:cards sel)))
        own (if (= side :corp) "Corp" "Runner")
        ok? (fn [target]
              (and (not= (:cid target) (:not-self sel))
                   ;; never pick the opponent's hidden hand or deck cards
                   (not (and (not= own (:side target)) (#{:hand :deck} (first (:zone target)))))
                   (not (chosen (:cid target)))
                   (cond (:card sel) ((:card sel) target)
                         (:req sel) ((:req sel) state side (:eid ability) src [target])
                         :else true)))
        by-cid (into {} (map (juxt :cid identity)) (get-all-cards state))
        cards (->> (:selectable p)
                   (keep by-cid)
                   (filter ok?))
        ;; identical cards (same title, same zone, both unselected) are interchangeable
        cards (vals (into (sorted-map) (map (fn [c] [[(str (:title c)) (str (:zone c)) (if (installed? c) (:cid c) "")] c]) cards)))
        done (->> (:choices p) (filter #(= "Done" (:value %))) first)]
    (concat
     (for [c cards]
       (act side "select" {:card (card-ref c) :eid (:eid p)} (str "select " (:title c)) :select))
     (when done
       [(act side "choice" {:choice {:uuid (:uuid done)} :eid (:eid p)} "Done" :done)]))))

(defn- number-actions [state side p]
  (let [s @state
        choices (:choices p)
        mx (cond (= choices :credit) (get-in s [side :credit])
                 (= :trace (:prompt-type p)) (if (number? choices) choices (get-in s [side :credit]))
                 (:number choices) (let [n (:number choices)] (if (fn? n) (get-in s [side :credit]) n))
                 (:counter choices) (get-counters (get-card state (:card p)) (:counter choices))
                 :else 0)
        mx (max 0 (or mx 0))]
    (for [n (buckets mx)]
      (act side "choice" {:choice n :eid (:eid p)} (str n) :number))))

(defn- title-actions [side p titles]
  (for [t (sort titles)]
    (act side "choice" {:choice t :eid (:eid p)} t :card-title)))

(defn prompt-actions [state side opp-titles]
  (let [p (current-prompt @state side)
        choices (:choices p)]
    (cond
      (= :select (:prompt-type p)) (select-actions state side p)
      (or (= choices :credit) (= :trace (:prompt-type p)) (:number choices) (:counter choices))
      (number-actions state side p)
      (:card-title choices) (title-actions side p opp-titles)
      (sequential? choices)
      (->> choices
           (map (fn [c] [(choice-label c) c]))
           (reduce (fn [[seen out] [lbl c]] (if (seen lbl) [seen out] [(conj seen lbl) (conj out c)])) [#{} []])
           second
           (map (fn [c] (act side "choice" {:choice {:uuid (:uuid c)} :eid (:eid p)} (choice-label c) :choice))))
      :else [])))

;;; Paid abilities

(defn- ability-actions
  "Non-dynamic, non-break/pump abilities of side's active cards that are playable now."
  [state side {:keys [clicks?]}]
  (let [s @state]
    (for [c (all-active state side)
          :let [c (get-card state c)]
          :when (and c (seq (:abilities c)) (not (:disabled c)))
          [i ab] (map-indexed vector (:abilities c))
          :when (and (not (:dynamic ab)) (not (:break ab)) (not (:pump ab))
                     (if clicks? true (not (:action ab)))
                     (:playable (ability-playable? ab i state side c)))]
      (act side "ability" {:card (card-ref c) :ability i}
           (str (:title c) ": " (or (:label ab) (:msg ab) i)) (if (:action ab) :click-ability :ability)))))

(defn- rez-actions [state side cards]
  (when (= side :corp)
    (for [c cards
          :let [c (get-card state c)]
          :when (and c (installed? c) (not (rezzed? c)) (can-rez? state side c)
                     (can-pay? state side (make-eid state) c nil (get-rez-cost state side c nil)))]
      (act side "rez" {:card (card-ref c)} (str "rez " (:title c)) :rez))))

(defn- non-ice-unrezzed [state]
  (remove ice? (filter #(and (not (rezzed? %)) (or (asset? %) (upgrade? %))) (all-installed state :corp))))

;;; Turn actions

(defn- corp-install-actions [state c]
  (for [server (installable-servers state c)
        :when (string? server)
        :when (corp-can-pay-and-install? state :corp (make-eid state) c server
                                         {:base-cost [(->c :click 1)] :action :corp-click-install :no-toast true})]
    (act :corp "play" {:card (card-ref c) :server server} (str "install " (:title c) " in " server) :install)))

(defn- dedupe-hand [cards]
  (vals (into (sorted-map) (map (fn [c] [(:title c) c]) cards))))

(defn- corp-turn-actions [state]
  (let [s @state
        hand (dedupe-hand (get-in s [:corp :hand]))
        clicks (get-in s [:corp :click])]
    (concat
     [(act :corp "credit" {} "click for credit" :credit)]
     (when (seq (get-in s [:corp :deck])) [(act :corp "draw" {} "draw" :draw)])
     (for [c hand :when (and (operation? c) (:playable (playable? c state :corp)))]
       (act :corp "play" {:card (card-ref c)} (str "play " (:title c)) :play))
     (mapcat #(corp-install-actions state %) (filter #(or (agenda? %) (asset? %) (ice? %) (upgrade? %)) hand))
     (for [c (all-installed state :corp)
           :when (and (or (agenda? c) (:advanceable c) (and (ice? c) false))
                      (card/can-be-advanced? state c) (can-advance? state :corp c)
                      (pos? (get-in s [:corp :credit])))]
       (act :corp "advance" {:card (card-ref c)} (str "advance " (:title c)) :advance))
     (for [c (all-installed state :corp)
           :when (and (agenda? c) (can-score? state :corp c))]
       (act :corp "score" {:card (card-ref c)} (str "score " (:title c)) :score))
     (when (and (pos? (get-in s [:runner :tag :base] 0)) (>= (get-in s [:corp :credit]) 2)
                (seq (filter resource? (all-active-installed state :runner))))
       [(act :corp "trash-resource" {} "trash resource" :trash-resource)])
     (when (and (>= clicks 3)
                (some #(pos? (get-counters % :virus)) (concat (all-installed state :runner) (all-installed state :corp))))
       [(act :corp "purge" {} "purge" :purge)])
     (rez-actions state :corp (non-ice-unrezzed state))
     (ability-actions state :corp {:clicks? true}))))

(defn- runner-turn-actions [state]
  (let [s @state
        hand (dedupe-hand (get-in s [:runner :hand]))]
    (concat
     [(act :runner "credit" {} "click for credit" :credit)]
     (when (seq (get-in s [:runner :deck])) [(act :runner "draw" {} "draw" :draw)])
     (for [z (get-runnable-zones state :runner)
           :let [server (zone->name z)]]
       (act :runner "run" {:server server} (str "run " server) :run))
     (for [c hand :when (and (or (event? c) (hardware? c) (program? c) (resource? c))
                             (:playable (playable? c state :runner)))]
       (act :runner "play" {:card (card-ref c)} (str "play " (:title c)) (if (event? c) :play :install)))
     (when (and (pos? (get-in s [:runner :tag :base] 0)) (>= (get-in s [:runner :credit]) 2))
       [(act :runner "remove-tag" {} "remove tag" :remove-tag)])
     (ability-actions state :runner {:clicks? true}))))

;;; Run actions

(defn- encounter-actions [state side]
  (let [s @state
        ice (get-current-ice state)]
    (if (= side :runner)
      (concat
       (for [c (all-active-installed state :runner)
             :let [c (get-card state c)]
             [i ab] (map-indexed vector (:abilities c))
             :when (= :auto-pump-and-break (:dynamic ab))]
         (act :runner "dynamic-ability" {:dynamic "auto-pump-and-break" :card (card-ref c)}
              (str "break " (:title ice) " with " (:title c)) :break))
       (ability-actions state :runner {:clicks? false})
       [(act :runner "continue" {} "let subroutines fire" :continue)])
      (let [unfired (and ice (rezzed? ice) (some #(and (not (:broken %)) (not (:fired %))) (:subroutines ice)))]
        (if unfired
          [(act :corp "unbroken-subroutines" {:card (card-ref ice)} (str "fire " (:title ice)) :fire)]
          [(act :corp "continue" {} "continue" :continue)])))))

(defn- run-actions [state side]
  (let [s @state
        run (:run s)
        phase (:phase run)
        ice (get-current-ice state)]
    (if (= side :runner)
      (concat
       [(act :runner "continue" {} "continue" :continue)]
       (when (and (= phase :movement) (not (:cannot-jack-out run)))
         [(act :runner "jack-out" {} "jack out" :jack-out)])
       (ability-actions state :runner {:clicks? false}))
      (concat
       [(act :corp "continue" {} "continue" :continue)]
       (when (and (= phase :approach-ice) ice (not (rezzed? ice)))
         (rez-actions state :corp [ice]))
       (rez-actions state :corp (non-ice-unrezzed state))
       (ability-actions state :corp {:clicks? false})))))

(defn legal
  "Legal actions for the deciding side. opp-titles: card titles allowed in card-title prompts."
  ([state] (legal state (decision state) nil))
  ([state {:keys [side kind]} opp-titles]
   (vec
    (case kind
      :prompt (prompt-actions state side opp-titles)
      :encounter (encounter-actions state side)
      :run (run-actions state side)
      :phase-12 (concat [(act side "end-phase-12" {} "end phase 1.2" :pass)]
                        (ability-actions state side {:clicks? false}))
      :start-turn [(act side "start-turn" {} "start turn" :start-turn)]
      :turn ((if (= side :corp) corp-turn-actions runner-turn-actions) state)
      :end-turn (concat [(act side "end-turn" {} "end turn" :end-turn)]
                        (when (= side :corp) (rez-actions state :corp (non-ice-unrezzed state)))
                        (ability-actions state side {:clicks? false}))
      nil []))))
