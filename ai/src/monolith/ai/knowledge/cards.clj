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
      (and (re-find #"end the run" l) (not (re-find #"end the run if" l))) (assoc :etr true)
      (re-find #"(\d+) net damage" l) (assoc :net (n #"(\d+) net damage"))
      (re-find #"(\d+) meat damage" l) (assoc :meat (n #"(\d+) meat damage"))
      (re-find #"(\d+) core damage|(\d+) brain damage" l) (assoc :core 1)
      (re-find #"lose (\d+) \[credits\]" l) (assoc :lose-credits (n #"lose (\d+) \[credits\]"))
      (re-find #"give the runner (\d+) tag|1 tag" l) (assoc :tag 1)
      (re-find #"trash (1|a) program|trash an installed program" l) (assoc :trash-program 1)
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
        (let [subs (or (seq (:subroutines card)) (:subroutines (card-def card)))]
          (mapv (fn [s] (let [m (parse-sub (:label s))]
                          (cond-> (assoc m :broken (:broken s))
                            (and (:net m) (conditional-etr? (:title card))) (assoc :etr true))))
                subs)))))

(defn ice-model
  "Run-calculator view of a known ice."
  [card]
  (let [p (printed (:title card))]
    {:title (:title card)
     :strength (or (:current-strength card) (:strength card) (:strength p) 0)
     :subtypes (set (or (:subtypes card) (:subtypes p)))
     :subs (ice-subs card)
     :rez-cost (or (:cost p) 0)
     :rezzed (boolean (:rezzed card))}))

(defn printed-ice-model
  "Model of an ice title as it would be once rezzed in a given server (remote? adds Palisade-like bonuses)."
  [title remote?]
  (let [p (printed title)
        bonus (if (and remote? (re-find #"(?i)protecting a remote server, it gets \+(\d+) strength" (str (:text p))))
                (parse-long (second (re-find #"(?i)protecting a remote server, it gets \+(\d+) strength" (str (:text p)))))
                0)]
    {:title title
     :strength (+ (or (:strength p) 0) bonus)
     :subtypes (set (:subtypes p))
     :subs (ice-subs title)
     :rez-cost (or (:cost p) 0)}))

;;; Breakers

(defn- credit-cost [costs]
  (reduce + 0 (keep #(when (= :credit (:cost/type %)) (:cost/amount %)) (flatten (seq costs)))))

(defn- counter-type
  "The counter a cost spends per activation: :virus, :power, :self (trash this program) or :grip
  (trash a card from the grip, Faust), else nil."
  [costs]
  (some #(case (:cost/type %) (:virus :any-virus-counter) :virus :power :power :trash-can :self :trash-from-hand :grip nil)
        (flatten (seq costs))))

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
  (let [abs (:abilities (card-def card))
        brk (first (filter :breaks abs))
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
         :n (max 1 (or (:break brk) 1))
         :break-cost (credit-cost (:break-cost brk))
         :pump (when pmp (let [p (or (:pump pmp) 0)] (if (and (zero? p) (:pump-bonus pmp)) (max 1 icebreakers) p)))
         :pump-cost (when pmp (credit-cost (:cost pmp)))
         ;; counter-paid activations (Yusuf, Revolver, Propeller pumps): limited by counters
         :break-counter bk :pump-counter pk
         :counters (into {} (for [k (distinct (remove nil? [bk pk]))] [k (counters-available card k)]))
         :strength strength
         :temporary (boolean (:additional-ability brk))})
      ;; X-credit breakers (Matryoshka): 1 credit per subroutine
      xbrk
      (when-let [types (subtype-breaks (:title card))]
        {:title (:title card) :cid (:cid card) :breaks types :n 1 :break-cost 1
         :pump (when pmp (or (:pump pmp) 1)) :pump-cost (when pmp (credit-cost (:cost pmp)))
         :strength strength :temporary false}))))

(defn can-break-type? [breaker ice]
  (or (= :all (:breaks breaker))
      (some (:subtypes ice) (:breaks breaker))))

(defn break-cost
  "Credits for breaker to fully break ice (all unbroken subs), or nil if impossible."
  [breaker ice]
  (when (can-break-type? breaker ice)
    (let [gap (max 0 (- (:strength ice) (:strength breaker)))
          nsubs (count (remove :broken (:subs ice)))]
      (if-let [{:keys [cost pump break]} (:combined breaker)]
        (if (= :x cost)
          (max gap nsubs 1)
          (let [k (max 1 (if (pos? gap) (long (Math/ceil (/ gap (double pump)))) 0)
                       (long (Math/ceil (/ nsubs (double break)))))]
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
        g (some-> (re-find #"(?i)^gain (\d+)\[credit\]" (str (:text p))) second parse-long)]
    (if g (- g (or (:cost p) 0)) 0)))

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
