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
  (reduce + 0 (keep #(when (= :credit (:cost/type %)) (:cost/amount %)) costs)))

(defn breaker-model
  "Break/pump model of an installed icebreaker. icebreakers = number of installed icebreakers
  (for Unity-style X pumps)."
  [card icebreakers]
  (let [abs (:abilities (card-def card))
        brk (first (filter :breaks abs))
        pmp (first (filter :pump abs))]
    (when brk
      {:title (:title card)
       :cid (:cid card)
       :breaks (let [b (:breaks brk)] (if (contains? b "All") :all b))
       :n (max 1 (or (:break brk) 1))
       :break-cost (credit-cost (:break-cost brk))
       :pump (when pmp (let [p (or (:pump pmp) 0)] (if (and (zero? p) (:pump-bonus pmp)) (max 1 icebreakers) p)))
       :pump-cost (when pmp (credit-cost (:cost pmp)))
       :strength (or (:current-strength card) (:strength card) (:strength (printed (:title card))) 0)
       :temporary (boolean (:additional-ability brk))})))

(defn can-break-type? [breaker ice]
  (or (= :all (:breaks breaker))
      (some (:subtypes ice) (:breaks breaker))))

(defn break-cost
  "Credits for breaker to fully break ice (all unbroken subs), or nil if impossible."
  [breaker ice]
  (when (can-break-type? breaker ice)
    (let [gap (max 0 (- (:strength ice) (:strength breaker)))
          pumps (if (pos? gap) (when (and (:pump breaker) (pos? (:pump breaker))) (long (Math/ceil (/ gap (double (:pump breaker)))))) 0)
          nsubs (count (remove :broken (:subs ice)))]
      (when pumps
        (+ (* pumps (or (:pump-cost breaker) 0))
           (* (long (Math/ceil (/ nsubs (double (:n breaker))))) (:break-cost breaker)))))))

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
  (let [b (:breaks (first (filter :breaks (:abilities (card-def {:title title})))))]
    (cond (nil? b) #{} (contains? b "All") #{"Barrier" "Code Gate" "Sentry"} :else b)))

(defn etr-ice? [title] (some :etr (ice-subs title)))
(defn damage-ice? [title] (some :net (ice-subs title)))
