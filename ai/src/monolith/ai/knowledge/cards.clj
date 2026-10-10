(ns monolith.ai.knowledge.cards
  "Card models derived from card data (raw_data.edn text and engine card-defs), not card names.
  Per-card multimethod overrides cover what parsing gets wrong."
  (:require
   [clojure.string :as str]
   [game.core.card-defs :refer [card-def]]
   [jinteki.cards :refer [all-cards]]))

(defn printed [title] (get @all-cards title))

(defn subtypes [card-or-title]
  (set (:subtypes (printed (if (string? card-or-title) card-or-title (:title card-or-title))))))

(defn ctype [title] (:type (printed title)))

;;; Subroutines

(defn parse-sub
  "Effect of a subroutine from its label. Keys: :etr, :net, :lose-credits, :etr-if-credits<=,
  :corp-gain, :corp-benefit, :unknown."
  [label]
  (let [l (str/lower-case (str label))
        n (fn [re] (some-> (re-find re l) second parse-long))]
    (cond-> {}
      (re-find #"end the run if the runner has (\d+)" l) (assoc :etr-if-credits<= (n #"has (\d+)"))
      (re-find #"end the run if the runner is tagged" l) (assoc :etr-if-tagged true)
      ;; Vicsek: X net damage and X tags, X = the Runner's tags
      (re-find #"do x damage and give the runner x tags" l) (assoc :net-per-tag 1 :tag-per-tag 1)
      ;; Vertigo, Enigma, Viper: a lost click (priced in the run calculator)
      (re-find #"loses? \[click\]" l) (assoc :lose-click 1)
      ;; card-pool audit (unparsed subroutines, all Standard ice): generic Runner-side effects
      (re-find #"^trash (1|a|an|\d+) resources?\.?$" l) (assoc :trash-program 1)
      (re-find #"trash (1|a|an) installed program unless the runner pays (\d+)" l)
      (assoc :etr-unless-pay-trash (n #"unless the runner pays (\d+)"))
      ;; no credits for the rest of the run: nothing further can be broken, as good as ETR for the calculator
      (re-find #"the runner cannot spend any credits for the remainder of this (run|turn)" l) (assoc :etr true)
      ;; Winchester (trace), Swordsman: hardware or an AI program lost
      (re-find #"trash a piece of hardware|trash an ai program" l) (assoc :trash-program 1)
      ;; Piranhas' "You may draw 1 card" is the Corp drawing: HQ grows before its ETR check (modern review 29:
      ;; R&D run into rezzed Piranhas five times, predicted HQ 4 vs grip 4, actual 5 vs 4)
      (re-find #"^\[subroutine\] you may draw (\d+) cards?|^you may draw (\d+) cards?|^gain \d+\[credit\]\. you may draw (\d+) card" l)
      (assoc :corp-draw (some->> (re-find #"draw (\d+) card" l) second parse-long))
      ;; Piranhas: ETR while the Corp's HQ outnumbers the Runner's grip
      (re-find #"end the run if there are more cards in hq than in the grip" l) (assoc :etr-if-hq-bigger true)
      (re-find #"if there are (\d+) or more hosted advancement counters, end the run" l)
      (assoc :etr-if-adv (n #"if there are (\d+) or more hosted advancement counters, end the run"))
      ;; "End the run unless the Runner pays N [Credits]" (Pop-up Window): a tax, not a wall
      (re-find #"end the run unless the runner pays (\d+) \[credits?\]" l)
      (assoc :etr-unless-pay (n #"end the run unless the runner pays (\d+) \[credits?\]"))
      ;; Mausolus-style labels: the parenthetical is the advanced version ("(and end the run)" only
      ;; with 3+ hosted advancement counters; review 15: agendas behind a lone unadvanced Mausolus)
      (re-find #"\(and end the run\)" l) (assoc :etr-if-adv 3)
      (and (re-find #"end the run" l) (not (re-find #"end the run if" l)) (not (re-find #"\(and end the run\)" l))
           (not (re-find #"end the run unless the runner pays (\d+) \[credits?\]" l))
           (not (re-find #"if there are \d+ or more hosted advancement counters, end the run" l)))
      (assoc :etr true)
      (re-find #"(\d+) net damage" l) (assoc :net (n #"(\d+) net damage"))
      (re-find #"(\d+) meat damage" l) (assoc :meat (n #"(\d+) meat damage"))
      (re-find #"(\d+) core damage|(\d+) brain damage" l) (assoc :core 1)
      (re-find #"loses? (\d+) ?\[credits?\]" l) (assoc :lose-credits (n #"loses? (\d+) ?\[credits?\]"))
      (re-find #"give the runner (\d+) tag|1 tag" l) (assoc :tag 1)
      (re-find #"trash (1|a|an) (installed )?program" l) (assoc :trash-program 1)
      ;; Hammer: a resource or hardware lost, priced like a program
      (re-find #"trash (1|a|an) installed (resource|piece of hardware)|choose a (resource|program|piece of hardware)[^.]*to trash" l) (assoc :trash-program 1)
      ;; Archangel, Hydra-style bounces: the Corp picks the best installed card; the Runner pays to
      ;; reinstall it (dev review 11: rig reinstalled each turn and bounced again by Archangel)
      (re-find #"add (1|an) installed runner card to the grip" l) (assoc :trash-program 1)
      ;; Ansel 1.0: any installed card (the Corp picks a breaker or console)
      (re-find #"trash (1|an?) installed (runner )?card" l) (assoc :trash-program 1)
      ;; Ansel 1.0: unbroken, the run can still succeed but nothing can be stolen or trashed
      (re-find #"cannot steal or trash" l) (assoc :no-steal true)
      ;; Excalibur: the rest of the turn's runs are lost (review-runner3: R&D into Excalibur first, every turn)
      (re-find #"cannot make another run this turn" l) (assoc :lock-runs true)
      ;; trace strength: the run calculator voids the effects when the Corp cannot win the trace
      (re-find #"trace (\d+)" l) (assoc :trace (n #"trace (\d+)"))
      (re-find #"^gain (\d+) \[credits\]" l) (assoc :corp-gain (n #"gain (\d+)"))
      (re-find #"install" l) (assoc :corp-benefit 1))))

(defmulti ice-subs-override :title)
(defmethod ice-subs-override :default [_] nil)

(defn- conditional-etr?
  "Card text ends the run as a side effect of a damage subroutine (e.g. Diviner's odd-cost check),
  which the subroutine label alone does not show. Treated as ETR (pessimistic)."
  [title]
  (re-find #"(?i)if you trash a card this way.*end the run" (str (:text (printed title)))))

(defn ice-subs
  "Subroutine effects for a known ice card (state card or title)."
  [card]
  (let [card (if (string? card) {:title card} card)]
    (or (ice-subs-override card)
        (let [subs (or (seq (:subroutines card)) (:subroutines (card-def card)))
              ;; subroutines without a label (DNA Tracker, Tree Line): read the printed text's i-th one
              printed-subs (vec (keep #(second (re-find #"\[subroutine\]\s*(.+)" %))
                                      (str/split-lines (str (:text (printed (:title card)))))))]
          (mapv (fn [i s] (let [m (parse-sub (or (:label s) (get printed-subs i)))]
                          (cond-> (assoc m :broken (:broken s))
                            (and (:net m) (conditional-etr? (:title card))) (assoc :etr true))))
                (range) subs)))))

(defn unbroken-damage
  "Net damage an ice does when its encounter ends without it being fully broken (Anansi), from text;
  Saisentan's chosen-type bonus counts as 1 more."
  [title]
  (let [txt (str (:text (printed title)))]
    (or (some-> (re-find #"(?i)if the runner did not fully break it, do (\d+) net damage" txt) second parse-long)
        (when (re-find #"(?i)whenever you trash a card of the chosen type with net damage" txt) 1))))

(defn rez-damage
  "Net damage the Corp can do when it rezzes this ice during a run (Anemone: trash a card from HQ to do 2)."
  [title]
  (some-> (re-find #"(?i)when you rez this ice during a run[^.]*do (\d+) net damage" (str (:text (printed title))))
          second parse-long))

(defn max-break
  "Break limit from 'the Runner cannot break more than N of its printed subroutines' (Afshar on HQ,
  Akhet at 3+ advancements, Unsmiling Tsarevna after its rez choice, Hammer except with killers):
  {:n N :exempt #{breaker types}} when it applies to this card (a known installed card or a title)."
  [card]
  (let [card (if (string? card) {:title card} card)
        txt (str (:text (printed (:title card))))]
    (when-let [[_ n] (re-find #"(?i)cannot break more than (\d+) of its printed subroutines" txt)]
      (let [applies (cond (re-find #"(?i)while this ice is protecting HQ" txt) (= :hq (second (:zone card)))
                          (re-find #"(?i)while there are (\d+) or more hosted advancement" txt)
                          (>= (or (:advance-counter card) 0) (parse-long (second (re-find #"(?i)while there are (\d+) or more hosted advancement" txt))))
                          :else true)]
        (when applies
          {:n (parse-long n) :exempt (if (re-find #"(?i)except using killers" txt) #{"Sentry"} #{})})))))

(defn ice-text-extras
  "Encounter effects from ice text the subroutine parse misses: :encounter-lose (Paywall: the Runner loses N on
  encounter) and :self-break {:cost X :untagged-only bool} (N-Pot, F2P: the Runner may pay X to break 1 subroutine)."
  [title]
  (let [txt (str/replace (str (:text (printed title))) #"<[^>]*>" "")]
    (cond-> {}
      (re-find #"(?i)when the runner encounters this ice, they lose (\d+)\[credit\]" txt)
      (assoc :encounter-lose (parse-long (second (re-find #"(?i)when the runner encounters this ice, they lose (\d+)\[credit\]" txt))))
      (re-find #"(?i)(\d+)\[credit\]: break 1 subroutine on this ice\. only the runner can use this ability" txt)
      (assoc :self-break {:cost (parse-long (second (re-find #"(?i)(\d+)\[credit\]: break 1 subroutine on this ice" txt)))
                          :untagged-only (boolean (re-find #"(?i)only if they are not tagged" txt))}))))

(def ice-text-extras* (memoize ice-text-extras))

(defn ice-model
  "Run-calculator view of a known ice."
  [card]
  (let [p (printed (:title card))]
    (merge (ice-text-extras* (:title card))
    {:title (:title card)
     :max-break (max-break card)
     :tag-etr (boolean (re-find #"(?i)subroutines for the remainder of this run[^.]*\. X is equal to the number of tags" (str (:text p))))
     :strength (or (:current-strength card) (:strength card) (:strength p) 0)
     :subtypes (set (or (:subtypes card) (:subtypes p)))
     :subs (ice-subs card)
     :unbroken-damage (unbroken-damage (:title card))
     :advancements (or (:advance-counter card) 0)
     :rez-cost (or (:cost p) 0)
     :rezzed (boolean (:rezzed card))})))

(defn printed-ice-model
  "Model of an ice title as it would be once rezzed in a given server (remote? adds Palisade-like bonuses)."
  [title remote?]
  (let [p (printed title)
        bonus (if (and remote? (re-find #"(?i)protecting a remote server, it gets \+(\d+) strength" (str (:text p))))
                (parse-long (second (re-find #"(?i)protecting a remote server, it gets \+(\d+) strength" (str (:text p)))))
                0)]
    (merge (ice-text-extras* title)
    {:title title
     :strength (+ (or (:strength p) 0) bonus)
     :subtypes (set (:subtypes p))
     :subs (ice-subs title)
     :unbroken-damage (unbroken-damage title)
     :rez-damage (rez-damage title)
     :max-break (max-break title)
     ;; Starlit Knight: one more ETR subroutine per Runner tag (threat 4; assumed active)
     :tag-etr (boolean (re-find #"(?i)subroutines for the remainder of this run[^.]*\. X is equal to the number of tags" (str (:text p))))
     :rez-cost (or (:cost p) 0)})))

;;; Breakers

(defn- credit-cost [costs]
  (reduce + 0 (keep #(when (= :credit (:cost/type %)) (:cost/amount %)) (flatten (seq costs)))))

(defn- counter-type
  "The counter a cost spends per activation: :virus, :power, :self (trash this program) or :grip
  (trash a card from the grip, Faust), else nil."
  [costs]
  (some #(case (:cost/type %) (:virus :any-virus-counter) :virus :power :power :trash-can :self :trash-from-hand :grip nil)
        (flatten (seq costs))))

(defn- counter-amount
  "Counters one activation spends (Endurance: 2 hosted power counters), default 1."
  [costs]
  (or (some #(when (#{:virus :any-virus-counter :power} (:cost/type %)) (:cost/amount %)) (flatten (seq costs))) 1))

(defn- counters-available
  "Activations the card can still pay with counters of kind k (installed: counters on it;
  not installed: what its text places on install)."
  [card k]
  (let [placed (fn [kind] (some-> (re-find (re-pattern (str "(?i)place (\\d+) " kind " counters on (?:it|this program)"))
                                           (str (:text (printed (:title card)))))
                                  second parse-long))]
    (case k
      :self 1
      :grip (or (:monolith-grip card) 0)
      :virus (or (get-in card [:counter :virus]) (when-not (:cid card) (placed "virus")) 0)
      :power (or (get-in card [:counter :power]) (when-not (:cid card) (placed "power")) 0)
      nil)))

(defn- subtype-breaks
  "Ice types a breaker interfaces with, from its subtypes (for defs without a :breaks key)."
  [title]
  (let [st (subtypes title)]
    (cond (st "AI") :all
          :else (not-empty (set (keep {"Fracter" "Barrier" "Decoder" "Code Gate" "Killer" "Sentry"} st))))))

(defn- x-credit-cost? [costs] (some #(= :x-credits (:cost/type %)) costs))

(defn breaker-model
  "Break/pump model of an installed icebreaker. icebreakers = number of installed icebreakers
  (for Unity-style X pumps). Heap breakers (Paperclip, Black Orchestra) pump and break with one
  payment: :combined {:cost c-or-:x :pump p-or-:x :break b-or-:x}."
  [card icebreakers]
  (if-let [[_ cost ty] (re-find #"(?i)(\d+)\[credit\]: subroutines on the (barrier|code gate|sentry) you are encountering cannot end the run"
                                (str/replace (str (:text (printed (:title card)))) #"<[^>]*>" ""))]
    ;; Banner: one payment stops a barrier's subroutines from ending the run; the others still fire
    {:title (:title card) :cid (:cid card) :breaks #{(if (= "code gate" (str/lower-case ty)) "Code Gate" (str/capitalize ty))} :n 99 :break-cost (parse-long cost)
     :etr-only true :pump nil :pump-cost nil :temporary false
     :strength (or (:current-strength card) (:strength card) (:strength (printed (:title card))) 0)}
  (let [abs (:abilities (card-def card))
        ;; skip conditional break abilities when an unconditional one exists (Euler's 0[credit] break
        ;; works only the turn it is installed; its real cost is 2[credit] for up to 2 subroutines)
        brks (filter :breaks abs)
        lines (filter #(re-find #"(?i)\bbreak\b" %) (str/split-lines (str (:text (printed (:title card))))))
        brk (or (when (and (> (count brks) 1) (= (count brks) (count lines)))
                  (first (keep (fn [[b l]] (when-not (re-find #"(?i)use this ability only if" l) b)) (map vector brks lines))))
                (first brks))
        heap (first (filter :heap-breaker-break abs))
        xbrk (when-not (or brk heap) (first (filter #(x-credit-cost? (:break-cost %)) abs)))
        pmp (first (filter :pump abs))
        strength (or (:current-strength card) (:strength card) (:strength (printed (:title card))) 0)]
    (cond
      heap
      (when-let [types (subtype-breaks (:title card))]
        {:title (:title card) :cid (:cid card) :breaks types :n 1 :break-cost 0 :pump nil :pump-cost nil
         :strength strength :temporary false
         :combined {:cost (if (x-credit-cost? (:cost heap)) :x (credit-cost (:cost heap)))
                    :pump (:heap-breaker-pump heap) :break (:heap-breaker-break heap)}})
      brk
      (let [bk (counter-type (:break-cost brk))
            pk (when pmp (counter-type (:cost pmp)))]
        {:title (:title card)
         :cid (:cid card)
         :breaks (let [b (:breaks brk)] (if (contains? b "All") :all b))
         ;; :break 0 = "break any number of subroutines" (Odore, Switchblade)
         :n (let [b (:break brk)] (if (and b (zero? b)) 99 (max 1 (or b 1))))
         :break-cost (credit-cost (:break-cost brk))
         :pump (when pmp (let [p (or (:pump pmp) 0)] (if (and (zero? p) (:pump-bonus pmp)) (max 1 icebreakers) p)))
         :pump-cost (when pmp (credit-cost (:cost pmp)))
         ;; counter-paid activations (Yusuf, Revolver, Propeller pumps): limited by counters
         :break-counter bk :pump-counter pk
         ;; Eater: breaking with it forfeits all accesses this run
         :no-access (boolean (re-find #"(?i)you cannot access cards for the remainder of this run" (str (:text (printed (:title card))))))
         ;; activations, not counters: Endurance spends 2 power counters a use (CN seed 550051: broke with 1
         ;; counter in the model, 5 failed activations a run, then the subroutines fired)
         :counters (into {} (for [k (distinct (remove nil? [bk pk]))
                                  :let [amt (max 1 (if (= k bk) (counter-amount (:break-cost brk)) (counter-amount (:cost pmp))))]]
                              [k (quot (counters-available card k) amt)]))
         :strength strength
         ;; D4v1d: breaks only ice of strength N or more, whatever its own strength
         :min-ice-strength (some-> (re-find #"(?i)a piece of ice that has a strength of (\d+) or greater" (str (:text (printed (:title card))))) second parse-long)
         :temporary (boolean (:additional-ability brk))})
      ;; X-credit breakers (Matryoshka): 1 credit per subroutine
      xbrk
      (when-let [types (subtype-breaks (:title card))]
        {:title (:title card) :cid (:cid card) :breaks types :n 1 :break-cost 1
         :pump (when pmp (or (:pump pmp) 1)) :pump-cost (when pmp (credit-cost (:cost pmp)))
         :strength strength :temporary false})))))

(defn can-break-type? [breaker ice]
  (and (or (nil? (:min-ice-strength breaker)) (>= (or (:strength ice) 0) (:min-ice-strength breaker)))
       (or (= :all (:breaks breaker))
           (some (:subtypes ice) (:breaks breaker)))))

(defn break-cost
  "Credits for breaker to fully break ice (all unbroken subs), or nil if impossible."
  [breaker ice]
  (when (can-break-type? breaker ice)
    (let [gap (if (or (:min-ice-strength breaker) (:ignore-strength breaker)) 0 (max 0 (- (:strength ice) (:strength breaker))))
          nsubs (count (remove :broken (:subs ice)))]
      (if-let [{:keys [cost pump break]} (:combined breaker)]
        (if (= :x cost)
          (max gap nsubs 1)
          ;; uses below the ice's strength break nothing: the use that reaches it is the first to break
          ;; (frunner3 log-12: Black Orchestra on Mausolus priced 6, costs 9; pumped once for 3 and stopped)
          (let [b (long (Math/ceil (/ nsubs (double break))))
                k (if (pos? gap)
                    (+ (long (Math/ceil (/ gap (double pump)))) (max 0 (dec b)))
                    (max 1 b))]
            (* k cost)))
        (let [pumps (if (pos? gap) (when (and (:pump breaker) (pos? (:pump breaker))) (long (Math/ceil (/ gap (double (:pump breaker)))))) 0)
              breaks (long (Math/ceil (/ nsubs (double (:n breaker)))))
              {:keys [break-counter pump-counter counters]} breaker
              need (merge-with + (if (and break-counter (pos? breaks)) {break-counter breaks} {})
                               (if (and pump-counter pumps (pos? pumps)) {pump-counter pumps} {}))]
          (when (and pumps (every? (fn [[k n]] (<= n (get counters k 0))) need))
            (+ (* pumps (or (:pump-cost breaker) 0))
               (* breaks (:break-cost breaker)))))))))

;;; Generic card facts

(defn trash-cost [title] (:trash (printed title)))
(defn play-cost [title] (or (:cost (printed title)) 0))

(defn econ-gain
  "Net credits from playing an economy operation/event, parsed from text (0 if none)."
  [title]
  (let [p (printed title)
        t (str (:text p))
        ;; also behind a first-line play restriction (m41 log-19: Petty Cash "Play only if ..." read as
        ;; non-economy, held for 10 turns at 0-3 credits)
        g (some-> (or (re-find #"(?i)^gain (\d+)\[credit\]" t)
                      (when (re-find #"(?i)^play only [^\n]*\n" t) (re-find #"(?im)^gain (\d+)\[credit\]" t)))
                  second parse-long)]
    ;; "gain 1[credit] for each installed connection" is variable, not a fixed gain (f4 log-07: Calling in
    ;; Favors played twice for nothing with no connections)
    (if (and g (not (re-find #"(?i)^gain \d+\[credit\] for each" t))) (- g (or (:cost p) 0)) 0)))

(defn load-credits [title]
  (some-> (re-find #"(?i)load (\d+)\[credit\]" (str (:text (printed title)))) second parse-long))

(defn icebreaker? [title] (contains? (subtypes title) "Icebreaker"))

(defn breaker-types [title]
  (let [b (:breaks (first (filter :breaks (:abilities (card-def {:title title})))))
        b (or b (when (icebreaker? title) (let [t (subtype-breaks title)] (if (= :all t) #{"All"} t))))]
    (cond (nil? b) #{} (contains? b "All") #{"Barrier" "Code Gate" "Sentry"} :else b)))

(defn etr-ice? [title] (some :etr (ice-subs title)))
(defn damage-ice? [title] (some :net (ice-subs title)))

;; Card-text parsing is pure per title; memoize the hot entry points (O2 profile: ~5% of S3 time).
(alter-var-root #'parse-sub memoize)
(alter-var-root #'printed-ice-model memoize)
(alter-var-root #'breaker-types memoize)
(alter-var-root #'econ-gain memoize)
(alter-var-root #'load-credits memoize)
(alter-var-root #'icebreaker? memoize)
(alter-var-root #'conditional-etr? memoize)
