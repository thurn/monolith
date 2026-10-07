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
                  (:trash-program sub) (update :trashed (fnil + 0) (:trash-program sub))
                  (:no-steal sub) (assoc :no-access true)
                  (:etr sub) (assoc :ended true)
                  ;; tax ETR: pay when possible, else the run ends
                  (:etr-unless-pay sub) (as-> st' (if (>= (:credits st') (:etr-unless-pay sub))
                                                    (update st' :credits - (:etr-unless-pay sub))
                                                    (assoc st' :ended true)))
                  (and (:etr-if-tagged sub) (pos? (+ (or (:tags st) 0) (or (:tagged st) 0)))) (assoc :ended true)
                  (and (:etr-if-adv sub) (>= (or (:advancements ice) 0) (:etr-if-adv sub))) (assoc :ended true)
                  (and (:etr-if-credits<= sub) (<= (:credits st) (:etr-if-credits<= sub))) (assoc :ended true))))
          ;; encounter-end damage when not fully broken (Anansi)
          (if-let [d (:unbroken-damage ice)] (update st :damage + d) st)
          (remove :broken (:subs ice))))

(defn- terminal [{:keys [credits damage ended tags no-access trashed]} {:keys [credits0 hand value w-damage w-tag w-program breakers replacement]} success?]
  (let [spent (- credits0 credits)
        ;; programs trashed by subroutines (Cobra, Archer): each costs its rebuild (credits + clicks)
        lost (* w-program (min (or trashed 0) (count breakers)))]
    (if (> damage (dec (max 1 hand)))
      (if (> damage hand) flatline-utility (- 0.0 (* 3 w-damage damage) lost))
      (- (if (and success? (or (not no-access) replacement)) value 0.0) spent (* w-damage damage) (* w-tag (or tags 0)) lost))))

(declare walk)

(defn- encounter
  "Runner's best utility facing a known rezzed ice model at index i."
  [ctx i st ice]
  (let [opts (concat
              (for [b (:breakers ctx)
                    :let [c (cards/break-cost b ice)]
                    :when (and c (<= c (:credits st)))]
                {:how [:break (:title b) c] :st (cond-> (update st :credits - c) (:no-access b) (assoc :no-access true))})
              [{:how [:fire] :st (fire-subs st ice)}])]
    (apply max-key :u
           (for [{:keys [how st]} opts]
             (if (:ended st)
               {:u (terminal st ctx false) :p 0.0 :how how}
               (let [r (walk ctx (inc i) st)] {:u (:u r) :p (:p r) :how how}))))))

(defn- approach
  "Utility at the i-th ice (outermost = 0). st carries :corp-credits."
  [ctx i st]
  (let [ice (nth (:ices ctx) i)
        rez-bonus (:rez-bonus ctx 0)
        jack-out (when (pos? i) {:u (terminal st ctx false) :p 0.0 :how [:jack-out]})
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
                               :let [m (cards/printed-ice-model t (:remote? ctx))
                                     r (encounter ctx i (update st :corp-credits - (+ (:rez-cost m) rez-bonus)) m)]]
                           [(/ n (double total)) (:u r) (:p r)])
                worst (apply min-key second outcomes)
                ev (reduce + (map (fn [[p u]] (* p u)) outcomes))
                pv (reduce + (map (fn [[p _ q]] (* p q)) outcomes))
                [u pr] (if (= :worst (:mode ctx)) [(second worst) (nth worst 2)] [ev pv])]
            (best {:u u :p pr :how [:unknown]})))))))

(defn walk [ctx i st]
  (if (>= i (count (:ices ctx)))
    ;; reached the server: pay any approach toll, then success
    (let [toll (:toll ctx 0)]
      (if (and (pos? toll) (< (:credits st) toll))
        {:u (terminal st ctx false) :p 0.0 :how [:toll-fail]}
        {:u (terminal (update st :credits - toll) ctx true) :p 1.0 :how [:success]}))
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
  :mode :expected|:worst, :rez-bonus, :toll, :w-damage, :w-tag, :w-program.
  Returns {:u utility :how first decision :success? ...}."
  [{:keys [ices remote? credits] :as opts}]
  (let [entries (mapv #(ice-entry % remote?) (reverse ices))
        ctx (merge {:w-damage 2.0 :w-tag 1.0 :w-program 0.0 :mode :expected :value 0.0} opts
                   {:ices entries :credits0 credits})
        st {:credits credits :damage 0 :corp-credits (:corp-credits opts 0) :tagged (:tagged opts 0)}]
    (walk ctx 0 st)))

(defn run-utility
  "Utility of running vs not running (0). Positive = worth it."
  [opts]
  (:u (evaluate opts)))
