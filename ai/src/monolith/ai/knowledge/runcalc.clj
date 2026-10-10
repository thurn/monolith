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
            (if (or (:ended st)
                    ;; a trace the Corp cannot win even spending every credit (base + credits <= link)
                    (and (:link st) (:trace sub) (<= (+ (:trace sub) (max 0 (or (:corp-credits st) 0))) (:link st))))
              st
                (cond-> st
                  (:corp-draw sub) (update :corp-hand (fnil + 0) (:corp-draw sub))
                  ;; "trash 1 installed program unless the Runner pays N": pay when able, else lose a program
                  (:etr-unless-pay-trash sub) (as-> st' (if (>= (:credits st') (:etr-unless-pay-trash sub))
                                                          (update st' :credits - (:etr-unless-pay-trash sub))
                                                          (update st' :trashed (fnil inc 0))))
                  (:net sub) (update :damage + (:net sub))
                  (:meat sub) (update :damage + (:meat sub))
                  (:core sub) (update :damage + 1)
                  (:lose-credits sub) (update :credits #(max 0 (- % (:lose-credits sub))))
                  (:tag sub) (update :tags (fnil inc 0))
                  (:trash-program sub) (update :trashed (fnil + 0) (:trash-program sub))
                  (:no-steal sub) (assoc :no-access true)
                  ;; lost later runs this turn: priced like a lost click and a half
                  (:lock-runs sub) (update :credits - 1.5)
                  (:etr sub) (assoc :ended true)
                  ;; tax ETR: pay when possible, else the run ends
                  (:etr-unless-pay sub) (as-> st' (if (>= (:credits st') (:etr-unless-pay sub))
                                                    (update st' :credits - (:etr-unless-pay sub))
                                                    (assoc st' :ended true)))
                  (and (:etr-if-tagged sub) (pos? (+ (or (:tags st) 0) (or (:tagged st) 0)))) (assoc :ended true)
                  (:net-per-tag sub) (as-> st' (let [x (+ (or (:tags st') 0) (or (:tagged st') 0))]
                                                 (-> st' (update :damage + x) (update :tags (fnil + 0) x))))
                  ;; a lost click is worth about 1.5 credits (the Runner's next click)
                  (:lose-click sub) (update :credits #(max 0 (- % 1.5)))
                  (and (:etr-if-hq-bigger sub) (> (or (:corp-hand st) 0) (- (or (:grip st) 0) (or (:damage st) 0)))) (assoc :ended true)
                  (and (:etr-if-adv sub) (>= (or (:advancements ice) 0) (:etr-if-adv sub))) (assoc :ended true)
                  (and (:etr-if-credits<= sub) (<= (:credits st) (:etr-if-credits<= sub))) (assoc :ended true))))
          ;; encounter-end damage when not fully broken (Anansi)
          (if-let [d (:unbroken-damage ice)] (update st :damage + d) st)
          (remove :broken (:subs ice))))

(defn- terminal [{:keys [credits damage ended tags no-access trashed corp-credits]} {:keys [credits0 hand value w-damage w-tag w-program breakers replacement rez-tax corp-credits0 steal-costs]} success?]
  (let [;; :rez-tax: credits the Corp spends rezzing during the run are worth a fraction to the Runner
        ;; (facechecks force rezzes: dev runner review 2, idle early turns at 9-14 credits)
        spent (- credits0 credits (* (or rez-tax 0.0) (- (or corp-credits0 0) (or corp-credits 0))))
        ;; programs trashed by subroutines (Cobra, Archer): each costs its rebuild (credits + clicks)
        lost (* w-program (min (or trashed 0) (count breakers)))]
    (if (> damage (dec (max 1 hand)))
      (if (> damage hand) flatline-utility (- 0.0 (* 3 w-damage damage) lost))
      (- (if (and success? (or (not no-access) replacement))
           ;; value may be a function of the damage taken on the way in (:breach-damage: a smaller grip
           ;; makes damage-on-access cards deadlier)
           (- (if (fn? value) (value damage) value) (reduce + 0.0 (for [[c v] steal-costs :when (< credits c)] v)))
           0.0)
         spent (* w-damage damage) (* w-tag (or tags 0)) lost))))

(declare walk)

(defn- encounter
  "Runner's best utility facing a known rezzed ice model at index i."
  [ctx i st ice]
  (let [;; Paywall: credits lost on encounter, before anything is broken
        st (if-let [n (:encounter-lose ice)] (update st :credits #(max 0 (- % n))) st)
        ice (if (pos? (:strength-reduce ctx 0)) (update ice :strength #(max 0 (- (or % 0) (:strength-reduce ctx)))) ice)
        ice (if (:tag-etr ice)
              (update ice :subs #(into (vec %) (repeat (+ (or (:tagged st) 0) (or (:tags st) 0)) {:etr true})))
              ice)
        opts (concat
              ;; the two cheapest break options only: with heap, hardware and hosted break sources the
              ;; full product over breakers x pooled unrezzed ice blew up (a 58-minute review game)
              (take 2 (sort-by #(nth (:how %) 2)
              (for [b (:breakers ctx)
                    :when (or (nil? (:only-cid b)) (= (:only-cid b) (:cid ice)))
                    :let [ic (if (and (:heap b) (not (contains? (:heaped st) (:title b)))) (:install-cost b 0) 0)
                          ;; break limit (Afshar on HQ...): break the ETR subroutines first, the rest fire
                          {:keys [n exempt]} (:max-break ice)
                          limited (and n (not (some exempt (cards/breaker-types (:title b)))))
                          live (remove :broken (:subs ice))
                          etr? #(or (:etr %) (:etr-if-tagged %) (:etr-unless-pay %) (:etr-if-adv %) (:etr-if-credits<= %))
                          [to-break to-fire] (cond
                                               ;; Banner: only the run-ending subroutines are stopped
                                               (:etr-only b) [(filter etr? live) (remove etr? live)]
                                               limited (split-at n (sort-by #(if (etr? %) 0 1) live))
                                               :else [live nil])
                          c (if (:etr-only b)
                              (when (and (cards/can-break-type? b ice) (<= (or (:strength ice) 0) (or (:strength b) 0)))
                                (+ ic (if (seq to-break) (:break-cost b) 0)))
                              (some-> (cards/break-cost b (assoc ice :subs (vec to-break))) (+ ic)))]
                    :when (and c (<= c (:credits st)))]
                {:how [:break (:title b) c]
                 :st (cond-> (update st :credits - c)
                       (:no-access b) (assoc :no-access true)
                       (:heap b) (update :heaped (fnil conj #{}) (:title b))
                       (seq to-fire) (fire-subs (assoc ice :subs (vec to-fire) :unbroken-damage nil)))})))
              ;; N-Pot/F2P: pay the ice's own price per subroutine
              (when-let [{:keys [cost untagged-only]} (:self-break ice)]
                (let [n (count (remove :broken (:subs ice)))
                      c (* cost n)]
                  (when (and (pos? n) (<= c (:credits st))
                             (not (and untagged-only (pos? (+ (or (:tagged st) 0) (or (:tags st) 0))))))
                    [{:how [:self-break c] :st (update st :credits - c)}])))
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
          (best (encounter ctx i (cond-> st
                                   (not (:rezzed ice)) (update :corp-credits - (+ (:rez-cost m) rez-bonus))
                                   ;; rez-time damage (Anemone)
                                   (and (not (:rezzed ice)) (:rez-damage m)) (update :damage + (:rez-damage m)))
                           m))
          (best (walk ctx (inc i) st))))
      :else
      ;; chance node over affordable candidates; if none affordable, the ice stays unrezzed
      (let [pool (:pool ctx)
            affordable? (fn [[t _]] (>= (:corp-credits st) (+ (:rez-cost (cards/printed-ice-model t (:remote? ctx))) rez-bonus)))
            affordable (->> pool (filter affordable?)
                            ;; the 8 most likely titles: bounds the chance-node branching
                            (sort-by (comp - val)) (take 8))
            total (reduce + 0 (vals affordable))
            ;; ice the Corp cannot afford stays unrezzed and is passed (modern review 15: a 2-advanced
            ;; remote behind one unrezzed ice vs a 3-credit Corp was never run; half its pool cost more)
            unaffordable (reduce + 0 (vals (remove affordable? pool)))
            all (+ total unaffordable)]
        (if (zero? total)
          (best (walk ctx (inc i) st))
          (let [outcomes (concat
                          (for [[t n] affordable
                                :let [m (cards/printed-ice-model t (:remote? ctx))
                                      r (encounter ctx i (cond-> (update st :corp-credits - (+ (:rez-cost m) rez-bonus))
                                                           (:rez-damage m) (update :damage + (:rez-damage m)))
                                                   m)]]
                            [(/ n (double all)) (:u r) (:p r)])
                          (when (pos? unaffordable)
                            (let [r (walk ctx (inc i) st)]
                              [[(/ unaffordable (double all)) (:u r) (:p r)]])))
                worst (apply min-key second outcomes)
                ev (reduce + (map (fn [[p u]] (* p u)) outcomes))
                pv (reduce + (map (fn [[p _ q]] (* p q)) outcomes))
                [u pr] (if (= :worst (:mode ctx)) [(second worst) (nth worst 2)] [ev pv])]
            (best {:u u :p pr :how [:unknown]})))))))

(def ^:dynamic *memo* nil)

(declare walk*)

(defn walk
  "Memoized on [i st] within one evaluate call: many orders of breaks and pooled unrezzed ice reach
  the same run state, and the full tree is exponential in the number of unrezzed ice."
  [ctx i st]
  (if-let [m *memo*]
    (let [k [i st]]
      (or (get @m k) (let [r (walk* ctx i st)] (swap! m assoc k r) r)))
    (walk* ctx i st)))

(defn- walk* [ctx i st]
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
     :model (assoc (if (:rezzed c) (cards/ice-model c) (cards/printed-ice-model (:title c) remote?)) :cid (:cid c))}))

(def ^:private barred?
  (memoize (fn [title remote?]
             (boolean (re-find (if remote? #"(?i)cannot interface with ice protecting a remote server"
                                   #"(?i)cannot interface with ice protecting a central server")
                               (str (:text (cards/printed title))))))))

(defn evaluate
  "opts: :ices (observed ice vector, innermost first, as in state), :remote?, :breakers (models),
  :credits, :hand, :corp-credits, :pool {title count}, :value (credits-equivalent of success),
  :mode :expected|:worst, :rez-bonus, :toll, :w-damage, :w-tag, :w-program.
  Returns {:u utility :how first decision :success? ...}."
  [{:keys [ices remote? credits] :as opts}]
  (let [;; "cannot interface with ice protecting a remote (central) server" (Passport): no use there
        ;; (CN seeds 550140, 550020: Enigma on a remote run into with Passport as the only decoder, 6-9 times)
        opts (update opts :breakers (fn [bs] (vec (remove #(barred? (:title %) (boolean remote?)) bs))))
        entries (mapv #(ice-entry % remote?) (reverse ices))
        ctx (merge {:w-damage 2.0 :w-tag 1.0 :w-program 0.0 :mode :expected :value 0.0} opts
                   {:ices entries :credits0 credits :corp-credits0 (:corp-credits opts 0)})
        st {:credits credits :damage 0 :corp-credits (:corp-credits opts 0) :tagged (:tagged opts 0) :link (:link opts)
            :corp-hand (:corp-hand opts 0) :grip (:hand opts 0)}]
    (binding [*memo* (atom {})]
      (walk ctx 0 st))))

(defn run-utility
  "Utility of running vs not running (0). Positive = worth it."
  [opts]
  (:u (evaluate opts)))
