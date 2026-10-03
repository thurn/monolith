(ns monolith.ai.knowledge.runcalc
  "Run calculator: expectimax over the ice protecting a server, outermost first.
  At known ice the Runner picks the best of breaking with each breaker, letting subroutines
  fire, or (after the first ice) jacking out. Unknown ice is a chance node over the Corp's
  unseen ice pool, limited to what the Corp can afford to rez. Advises only; the engine is
  the authority."
  (:require
   [monolith.ai.knowledge.cards :as cards]))

(def flatline-utility
  "Utility of flatlining: losing the game outright, so it dominates almost any gain."
  -250.0)

(defn- fire-subs
  "Applies unbroken subroutines; returns updated {:credits :damage :ended} ."
  [{:keys [credits damage] :as st} ice]
  (reduce (fn [st sub]
            (if (:ended st) st
                (cond-> st
                  (:net sub) (update :damage + (:net sub))
                  (:meat sub) (update :damage + (:meat sub))
                  (:core sub) (update :damage + 1)
                  (:lose-credits sub) (update :credits #(max 0 (- % (:lose-credits sub))))
                  (:tag sub) (update :tags (fnil inc 0))
                  (:etr sub) (assoc :ended true)
                  (and (:etr-if-credits<= sub) (<= (:credits st) (:etr-if-credits<= sub))) (assoc :ended true))))
          st (remove :broken (:subs ice))))

(defn- terminal [{:keys [credits damage ended tags]} {:keys [credits0 hand value w-damage w-tag]} success?]
  (let [spent (- credits0 credits)]
    (if (> damage (dec (max 1 hand)))
      (if (> damage hand) flatline-utility (- (* 3 w-damage damage)))
      (- (if success? value 0.0) spent (* w-damage damage) (* w-tag (or tags 0))))))

(declare walk)

(defn- encounter
  "Runner's best utility facing a known rezzed ice model at index i."
  [ctx i st ice]
  (let [opts (concat
              (for [b (:breakers ctx)
                    :let [c (cards/break-cost b ice)]
                    :when (and c (<= c (:credits st)))]
                {:how [:break (:title b) c] :st (update st :credits - c)})
              [{:how [:fire] :st (fire-subs st ice)}])]
    (apply max-key :u
           (for [{:keys [how st]} opts]
             {:u (if (:ended st) (terminal st ctx false) (:u (walk ctx (inc i) st)))
              :how how}))))

(defn- approach
  "Utility at the i-th ice (outermost = 0). st carries :corp-credits."
  [ctx i st]
  (let [ice (nth (:ices ctx) i)
        rez-bonus (:rez-bonus ctx 0)
        jack-out (when (pos? i) {:u (terminal st ctx false) :how [:jack-out]})
        best (fn [r] (if (and jack-out (> (:u jack-out) (:u r))) jack-out r))]
    (cond
      (:known ice)
      (let [m (:model ice)]
        (if (or (:rezzed ice) (>= (:corp-credits st) (+ (:rez-cost m) (if (:rezzed ice) 0 rez-bonus))))
          (best (encounter ctx i (cond-> st (not (:rezzed ice)) (update :corp-credits - (+ (:rez-cost m) rez-bonus))) m))
          (best (walk ctx (inc i) st))))
      :else
      ;; chance node over affordable candidates; if none affordable, the ice stays unrezzed
      (let [pool (:pool ctx)
            affordable (filter (fn [[t _]] (>= (:corp-credits st) (+ (:rez-cost (cards/printed-ice-model t (:remote? ctx))) rez-bonus))) pool)
            total (reduce + 0 (vals affordable))]
        (if (zero? total)
          (best (walk ctx (inc i) st))
          (let [outcomes (for [[t n] affordable
                               :let [m (cards/printed-ice-model t (:remote? ctx))]]
                           [(/ n (double total))
                            (:u (encounter ctx i (update st :corp-credits - (+ (:rez-cost m) rez-bonus)) m))])
                worst (apply min (map second outcomes))
                ev (reduce + (map (fn [[p u]] (* p u)) outcomes))
                u (if (= :worst (:mode ctx)) worst ev)]
            (best {:u u :how [:unknown]})))))))

(defn walk [ctx i st]
  (if (>= i (count (:ices ctx)))
    ;; reached the server: pay any approach toll, then success
    (let [toll (:toll ctx 0)]
      (if (and (pos? toll) (< (:credits st) toll))
        {:u (terminal st ctx false) :how [:toll-fail]}
        {:u (terminal (update st :credits - toll) ctx true) :how [:success]}))
    (approach ctx i st)))

(defn ice-entry
  "Calculator view of an observed ice card (outermost-first order is the caller's job)."
  [c remote?]
  (if (:hidden c)
    {:known false}
    {:known true :rezzed (boolean (:rezzed c))
     :model (if (:rezzed c) (cards/ice-model c) (cards/printed-ice-model (:title c) remote?))}))

(defn evaluate
  "opts: :ices (observed ice vector, innermost first, as in state), :remote?, :breakers (models),
  :credits, :hand, :corp-credits, :pool {title count}, :value (credits-equivalent of success),
  :mode :expected|:worst, :rez-bonus, :toll, :w-damage, :w-tag.
  Returns {:u utility :how first decision :success? ...}."
  [{:keys [ices remote? credits] :as opts}]
  (let [entries (mapv #(ice-entry % remote?) (reverse ices))
        ctx (merge {:w-damage 2.0 :w-tag 1.0 :mode :expected :value 0.0} opts
                   {:ices entries :credits0 credits})
        st {:credits credits :damage 0 :corp-credits (:corp-credits opts 0)}]
    (walk ctx 0 st)))

(defn run-utility
  "Utility of running vs not running (0). Positive = worth it."
  [opts]
  (:u (evaluate opts)))
