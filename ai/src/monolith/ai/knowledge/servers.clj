(ns monolith.ai.knowledge.servers
  "Server views over an observation: names, ice, contents, unseen-card pools, Runner server
  value and Corp protection (the run calculator from the Runner's side)."
  (:require
   [clojure.string :as str]
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.runcalc :as runcalc]))

(defn server-name [k]
  (case k :hq "HQ" :rd "R&D" :archives "Archives"
        (str "Server " (subs (name k) 6))))

(defn server-key [nm]
  (case nm "HQ" :hq "R&D" :rd "Archives" :archives
        (keyword (str "remote" (last (str/split nm #" "))))))

(defn remote? [k] (str/starts-with? (name k) "remote"))

(defn servers [obs] (get-in obs [:corp :servers]))
(defn remotes [obs] (filter (comp remote? key) (servers obs)))
(defn ices [obs k] (get-in obs [:corp :servers k :ices]))
(defn content [obs k] (get-in obs [:corp :servers k :content]))

(defn all-corp-installed [obs]
  (for [[_ srv] (servers obs) c (concat (:ices srv) (:content srv))] c))

(defn runner-installed [obs]
  (let [rig (get-in obs [:runner :rig])]
    (concat (:program rig) (:hardware rig) (:resource rig))))

(defn- stealth-credits
  "Credits on the Runner's stealth cards (Cloak, Ghost Runner, Smoke's identity)."
  [obs]
  (let [rig (get-in obs [:runner :rig])]
    (reduce + 0 (for [c (concat (:program rig) (:hardware rig) (:resource rig) [(get-in obs [:runner :identity])])
                      :when (some #{"Stealth"} (:subtypes (cards/printed (:title c))))]
                  (+ (or (get-in c [:counter :recurring]) 0) (or (get-in c [:counter :credit]) 0))))))

(defn icebreakers [obs]
  (let [stealth (delay (stealth-credits obs))
        ;; breakers that spend only stealth credits (Switchblade, Dai V) break nothing without them
        ;; (dev review 13: a known Cobra fired three times once Cloak was gone)
        usable? #(or (not (re-find #"(?i)spend credits only from <strong>stealth" (str (:text (cards/printed (:title %))))))
                     (pos? @stealth))
        ;; D4v1d-style programs break ice without the Icebreaker subtype
        breaker? #(or (cards/icebreaker? %) (re-find #"(?i)break ice subroutine" (str (:text (cards/printed %)))))
        bs (filter #(and (breaker? (:title %)) (usable? %)) (:program (get-in obs [:runner :rig])))
        ;; heap breakers (Paperclip, Black Orchestra, MKUltra) in the heap can be installed mid-encounter:
        ;; usable at their install cost once per run (review-runner2: a Magnet remote looked impassable)
        installed (set (map :title bs))
        heap (->> (get-in obs [:runner :discard])
                  (filter #(and (:title %) (cards/icebreaker? (:title %)) (not (installed (:title %)))
                                (re-find #"(?i)install this program from your heap" (str (:text (cards/printed (:title %)))))))
                  (reduce (fn [m c] (assoc m (:title c) c)) {}) vals)
        n (count bs)
        grip (count (get-in obs [:runner :hand]))]
    (concat (keep #(cards/breaker-model (assoc % :monolith-grip grip) n) bs)
            ;; non-icebreaker break sources ignore strength: break hardware (Endurance; Boomerang only on
            ;; its chosen ice) and Trojans hosted on ice (Botulus: only its host)
            (keep (fn [c] (when-let [m (and (re-find #"(?i)\bbreak (up to|1|any)" (str (:text (cards/printed (:title c)))))
                                            ;; Poison Vial-style supplements break only after another breaker did
                                            ;; (review-m13: Piranhas/Ping run as if Poison Vial alone broke them)
                                            (not (re-find #"(?i)only if you have already broken a subroutine" (str (:text (cards/printed (:title c))))))
                                            (cards/breaker-model c n))]
                            (cond-> (assoc m :ignore-strength true)
                              (get-in c [:special :boomerang-target]) (assoc :only-cid (get-in c [:special :boomerang-target :cid])))))
                  (get-in obs [:runner :rig :hardware]))
            (for [[_ srv] (get-in obs [:corp :servers]) ice (:ices srv) h (:hosted ice)
                  :when (and (= "Runner" (:side h)) (re-find #"(?i)break 1 subroutine on host ice" (str (:text (cards/printed (:title h))))))
                  :let [m (cards/breaker-model h n)] :when m]
              (assoc m :ignore-strength true :only-cid (:cid ice)))
            (keep #(some-> (cards/breaker-model {:title (:title %) :monolith-grip grip} (inc n))
                           (assoc :heap true :install-cost (cards/play-cost (:title %))))
                  heap))))

(defn visible-corp-titles
  "Corp card titles whose identity this observation shows (any zone)."
  [obs]
  (->> (concat (all-corp-installed obs)
               (mapcat :hosted (all-corp-installed obs))
               (get-in obs [:corp :discard]) (get-in obs [:corp :scored]) (get-in obs [:runner :scored])
               (get-in obs [:corp :current]) (get-in obs [:corp :rfg]) (get-in obs [:corp :play-area])
               (remove :hidden (get-in obs [:corp :hand]))
               (remove :hidden (get-in obs [:corp :deck])))
       (filter #(and (:title %) (= "Corp" (:side %)) (not (:hidden %))))
       (map :title)))

(defn unseen-pool
  "decklist {title qty} minus the visible titles. pred filters titles (e.g. ICE only)."
  ([obs decklist] (unseen-pool obs decklist (constantly true)))
  ([obs decklist pred]
   (let [seen (frequencies (visible-corp-titles obs))]
     (into {} (for [[t q] decklist
                    :let [left (- q (get seen t 0))]
                    :when (and (pos? left) (pred t))]
                [t left])))))

(defn ap [title] (or (:agendapoints (cards/printed title)) 0))

(defn agenda-density
  "Expected agenda points per unseen corp card."
  [pool]
  (let [n (reduce + 0 (vals pool))]
    (if (zero? n) 0.0
        (/ (reduce + 0.0 (for [[t q] pool] (* q (ap t)))) n))))

(defn ice-pool [obs corp-decklist]
  (unseen-pool obs corp-decklist #(= "ICE" (cards/ctype %))))

(defn remote-card-pool [obs corp-decklist]
  (unseen-pool obs corp-decklist #(#{"Agenda" "Asset" "Upgrade"} (cards/ctype %))))

(defn trap-damage
  "Net damage an unrezzed ambush with adv advancements does on access, from card text."
  [title adv]
  (let [t (str (:text (cards/printed title)))]
    (when (re-find #"(?i)accesses this" t)
      (let [base (some-> (re-find #"(?i)do (\d+) net damage" t) second parse-long)
            per (when (re-find #"(?i)plus 1 net damage for each hosted advancement" t) 1)]
        (when base (+ base (* (or per 0) adv)))))))

(defn steal-cost
  "Credits the Runner must pay as an additional cost to steal agenda t (Bellona), from its text."
  [t]
  (some-> (re-find #"(?i)additional cost to steal [^,]+, the Runner must pay (\d+)\[credit\]" (str (:text (cards/printed t))))
          second parse-long))

(defn steal-damage
  "Net damage the Runner must suffer as an additional cost to steal agenda t (Obokata Protocol)."
  [t]
  (some-> (re-find #"(?i)additional cost to steal [^,]+, the Runner must suffer (\d+) net damage" (str (:text (cards/printed t))))
          second parse-long))

(def ^:dynamic *runner-points*
  "The Runner's agenda points for the access being valued (bound by content-value): a steal that
  reaches 7 is worth a win, not its points."
  0)

(def winning-steal-value 50.0)

(def ^:dynamic *runner-grip* "The Runner's grip size for the access being valued." 5)

(def ^:dynamic *click-to-steal-ok*
  "Whether the Runner will have a click left to pay a click steal cost (Méliès City Luxury Line)."
  true)
(def ^:dynamic *corp-win-denial*
  "Bound to the Corp's agenda points while valuing remote cards under :remote-denial: an agenda the
  Corp would win by scoring is worth a winning steal to the Runner (it prevents a loss)."
  nil)

(defn agenda-access-value
  "Runner value of accessing agenda t: its points (a win if they reach 7), less any steal cost, or
  nothing if the Runner cannot pay that cost (credits = the Runner's credits before the run)."
  [t ap-value credits]
  (let [c (steal-cost t)
        d (steal-damage t)
        v (if (or (>= (+ *runner-points* (ap t)) 7)
                  (and *corp-win-denial* (>= (+ *corp-win-denial* (ap t)) 7)))
            winning-steal-value
            (* ap-value (ap t)))
        g (or *runner-grip* 5)
        ;; "spend [click]" to steal: worthless on the last click (modern Runner review 1: Méliès declined 12 times)
        v (if (and (not *click-to-steal-ok*)
                   (re-find #"(?i)additional cost to steal[^.]*spend \[click\]" (str (:text (cards/printed t)))))
            0.0 v)
        v (if d (cond (> d g) 0.0
                      (= d g) (if (>= (+ *runner-points* (ap t)) 7) v 0.0)
                      :else (- v (* 2.0 d)))
              v)]
    (cond (nil? c) (max 0.0 v)
          (and credits (< credits c)) 0.0
          :else (max 0.0 (- v c)))))

(defn hidden-content-value
  "Runner value (credits) of accessing one unknown remote card with adv counters in a server
  protected by n-ice pieces of ice (agendas are rarely left unprotected)."
  [pool adv n-ice {:keys [ap-value hand w-damage runner-credits remote-ice-prior]}]
  (let [n (reduce + 0 (vals pool))]
    (if (zero? n) 0.0
        (let [weights (for [[t q] pool
                            :let [typ (cards/ctype t)
                                  advanceable (or (= typ "Agenda") (re-find #"(?i)you can advance this" (str (:text (cards/printed t)))))
                                  ;; advanced cards are mostly agendas; traps get advanced only as bluffs
                                  w (cond (and (pos? adv) (not advanceable)) 0.02
                                          (and (pos? adv) (= typ "Agenda")) 3.0
                                          (pos? adv) (if (>= adv 3) 0.4 0.8)
                                          :else 1.0)
                                  w (if (and (= typ "Agenda") (zero? n-ice)) (* w 0.1) w)
                                  ;; :remote-ice-prior k: Corps put agendas behind their ice, so an
                                  ;; unadvanced card behind n pieces is (1 + k*min(n,3)) times likelier one
                                  w (if (and remote-ice-prior (= typ "Agenda") (zero? adv) (pos? n-ice))
                                      (* w (+ 1.0 (* remote-ice-prior (min n-ice 3))))
                                      w)]]
                        [t (* q w)])
              tot (reduce + 0.0 (map second weights))]
          (reduce + 0.0
                  (for [[t w] weights
                        :let [p (/ w tot)
                              dmg (trap-damage t adv)]]
                    (* p (cond (= "Agenda" (cards/ctype t)) (agenda-access-value t ap-value runner-credits)
                               dmg (if (>= dmg hand) runcalc/flatline-utility (- (* w-damage dmg)))
                               :else 0.5))))))))

(defn- drip-credits
  "Credits a Corp asset gains each turn from its text (PAD Campaign 1, Commercial Bankers Group 3)."
  [t]
  (some-> (re-find #"(?i)when your turn begins, (?:you may )?gain (\d+)\[credit\]" (str (:text (cards/printed t)))) second parse-long))

(defn worth-trashing?
  "Whether the Runner should pay cost to trash installed Corp card c (title t) on access; shared by
  run valuation and the access prompt so they never disagree (which made Runners re-run servers)."
  [obs c cost]
  (let [cr (get-in obs [:runner :credit] 0)
        t (:title c)
        left (get-in c [:counter :credit] (or (cards/load-credits t) 0))]
    (cond
      (or (nil? cost) (> cost cr)) false
      (cards/load-credits t) (or (and (:rezzed c) (>= left (* 2 cost)) (>= (- cr cost) 1))
                                 (and (not (:rezzed c)) (>= (- cr cost) 3)))
      (re-find #"(?i)approaches this server" (str (:text (cards/printed t)))) (>= (- cr cost) 2)
      ;; per-turn economy: trash it while it can still pay out (review-runner3: PAD Campaign and
      ;; Commercial Bankers Group left alone by a rich Runner)
      (drip-credits t) (>= (- cr cost) 3)
      :else (>= (- cr cost) 8))))

(defn access-penalty
  "Credits-equivalent cost of accessing card t in Archives from its \"when the Runner accesses\"
  text: tags (News Team: 2 tags or -1 point) and net damage (Shock!); Snare! is inert there."
  [t]
  (let [txt (str (:text (cards/printed t)))]
    (if (or (not (re-find #"(?i)when the runner accesses this" txt)) (re-find #"(?i)except in archives" txt))
      0.0
      (+ (* 2.5 (or (some-> (re-find #"(?i)take (\d+) tags?" txt) second parse-long) 0))
         (* 2.0 (or (some-> (re-find #"(?i)do (\d+) net damage" txt) second parse-long) 0))))))

(declare content-value*)

(defn content-value
  "Runner value of breaching server k (credits-equivalent), excluding run costs."
  [obs k {:keys [corp-decklist ap-value hand w-damage extra-access] :or {extra-access 0} :as opts}]
  (binding [*runner-points* (or (get-in obs [:runner :agenda-point]) 0)
            *runner-grip* (count (get-in obs [:runner :hand]))
            ;; before the run its click is still counted; during it, one more click must remain
            *click-to-steal-ok* (>= (or (get-in obs [:runner :click]) 0) (if (:run obs) 1 2))]
    (content-value* obs k opts)))

(defn- content-value*
  [obs k {:keys [corp-decklist ap-value hand w-damage extra-access] :or {extra-access 0} :as opts}]
  (let [pool (unseen-pool obs corp-decklist)
        need (- 7 *runner-points*)
        n-pool (reduce + 0 (vals pool))
        win-frac (if (pos? n-pool) (/ (reduce + 0 (for [[t q] pool :when (>= (ap t) need)] q)) (double n-pool)) 0.0)
        ;; per-access value: agenda points, or a win when any steal of enough points ends the game
        dens (max (agenda-density pool) (/ (* win-frac winning-steal-value) (max ap-value 1e-9)))]
    (case k
      ;; after a successful R&D run this turn that stole nothing, the top card is known and
      ;; still there: another single-access run only sees it again
      ;; with access memory (harness :hq-memory) the accessed top card is visible: it counts at its
      ;; own value and only further accesses see unseen cards
      :rd (let [reg (get-in obs [:runner :register])
                deck (get-in obs [:corp :deck])
                top (first deck)
                top-known (and (some #{:rd} (:successful-run reg)) (not (:stole-agenda reg)) (zero? extra-access))]
            (cond
              (empty? deck) 0.0
              (and top (not (:hidden top)))
              (+ (if (= "Agenda" (cards/ctype (:title top))) (agenda-access-value (:title top) ap-value (get-in obs [:runner :credit])) 0.0)
                 (* ap-value dens (min extra-access (dec (count deck)))))
              top-known 0.0
              :else (* ap-value dens (+ 1 extra-access))))
      ;; HQ cards the Runner accessed earlier are visible (harness :hq-memory): they count at
      ;; their own value, the rest at the unseen density (accesses are random across HQ)
      :hq (let [hand (get-in obs [:corp :hand])
                n (count hand)
                known (remove :hidden hand)
                per-card (if (zero? n) 0.0
                             (/ (+ (* ap-value dens (- n (count known)))
                                   (reduce + 0.0 (for [c known :when (= "Agenda" (cards/ctype (:title c)))]
                                                   (agenda-access-value (:title c) ap-value (get-in obs [:runner :credit])))))
                                n))]
            (* per-card (min n (+ 1 extra-access))))
      ;; known access-punishers in Archives (News Team) cost their tags (dev runner review 2: Archives
      ;; run into known News Teams, 4 and 6 tags)
      :archives (+ (* ap-value (reduce + 0 (map (comp ap :title) (remove :hidden (get-in obs [:corp :discard])))))
                   (- (reduce + 0.0 (map (comp access-penalty :title) (remove :hidden (get-in obs [:corp :discard])))))
                   (* 0.3 ap-value dens (count (filter :hidden (get-in obs [:corp :discard])))))
      ;; remote
      ;; :remote-denial k: an agenda stolen from a remote is also a score denied to the Corp, so
      ;; remote agendas are worth (1 + k) times their points (centrals' agendas are not imminent)
      (let [rpool (remote-card-pool obs corp-decklist)
            cr (get-in obs [:runner :credit])
            apv (* ap-value (+ 1.0 (or (:remote-denial opts) 0.0)))
            opts (assoc opts :runner-credits cr :ap-value apv)]
        (binding [*corp-win-denial* (when (:remote-denial opts) (or (get-in obs [:corp :agenda-point]) 0))]
         (reduce + 0.0
                (for [c (content obs k)]
                  (cond
                    (:hidden c) (hidden-content-value rpool (+ (or (:advance-counter c) 0)) (count (ices obs k)) opts)
                    (= "Agenda" (:type c)) (agenda-access-value (:title c) apv cr)
                    :else (let [tc (cards/trash-cost (:title c))
                                dmg (when-not (:rezzed c) (trap-damage (:title c) (or (:advance-counter c) 0)))]
                            (cond
                              ;; a known, still-armed ambush (Urtica Cipher): accessing it again hurts
                              dmg (if (>= dmg (or hand 5)) runcalc/flatline-utility (- (* (or w-damage 2.0) dmg)))
                              ;; trashing an economy card denies the Corp its credits, but only if the
                              ;; access decision will actually trash it
                              (worth-trashing? obs c tc) (max 0.5 (- (if-let [d (drip-credits (:title c))]
                                                                      (* 6.0 d)
                                                                      ;; :asset-eval (with the planner's :run-ap-eval): hosted credits
                                                                      ;; at the evaluator's ~1 each, like the run's agenda points
                                                                      (if (:asset-eval opts)
                                                                        (get-in c [:counter :credit] 0)
                                                                        (* 0.5 (+ (get-in c [:counter :credit] 0) 3))))
                                                                    tc))
                              :else 0.0))))))))))

(defn extra-accesses
  "Additional cards the Runner's installed cards let it access when breaching central k
  (\"breach HQ, access 1 additional card\"), from card text."
  [obs k]
  (let [zone (case k :hq "HQ" :rd "R&D" nil)]
    (if-not zone
      0
      (reduce + 0 (for [c (runner-installed obs)
                        :when (re-find (re-pattern (str "(?i)breach " zone ", access (?:1|an) additional card")) (str (:text (cards/printed (:title c)))))]
                    1)))))

(defn approach-toll
  "Credits the Runner must pay when approaching server k (rezzed upgrades like Manegarm Skunkworks)."
  [obs k]
  (reduce + 0 (for [c (content obs k)
                    :when (and (:rezzed c) (re-find #"(?i)approaches this server, end the run unless they either spend \[click\]\[click\] or pay (\d+)" (str (:text (cards/printed (:title c))))))]
                (parse-long (second (re-find #"(?i)or pay (\d+)" (str (:text (cards/printed (:title c))))))))))

(defn runner-run-eval
  "Runcalc evaluation of running server k from the Runner's observation."
  [obs k {:keys [corp-decklist value credits-bonus rez-bonus mode] :or {credits-bonus 0 rez-bonus 0} :as opts}]
  (runcalc/evaluate
   {:ices (ices obs k)
    :remote? (remote? k)
    :breakers (icebreakers obs)
    :credits (+ (get-in obs [:runner :credit]) credits-bonus)
    :hand (count (get-in obs [:runner :hand]))
    :corp-hand (count (get-in obs [:corp :hand]))
    :corp-credits (get-in obs [:corp :credit])
    :pool (ice-pool obs corp-decklist)
    :value value
    ;; agendas with a credit steal cost (Bellona), known or possible under hidden cards: content
    ;; value is a step function of the Runner's credits at access, so each cost c the Runner can pay
    ;; now carries the value lost if breaking leaves fewer than c (puzzles *-credit-up-for-bellona)
    :steal-costs (when (and (remote? k) (not (:replacement opts)))
                   (let [cr (+ (get-in obs [:runner :credit]) credits-bonus)
                         cs (content obs k)
                         titles (concat (keep :title (remove :hidden cs))
                                        (when (some :hidden cs) (keys (remote-card-pool obs corp-decklist))))
                         costs (sort (distinct (keep #(when (= "Agenda" (cards/ctype %)) (steal-cost %)) titles)))
                         v (fn [c] (content-value (assoc-in obs [:runner :credit] c) k opts))]
                     (seq (for [c costs :when (<= c cr)
                                :let [loss (- (v c) (v (dec c)))]
                                :when (pos? loss)]
                            [c loss]))))
    :rez-bonus rez-bonus
    :toll (approach-toll obs k)
    :mode (or mode :expected)
    :replacement (:replacement opts)
    :tagged (+ (get-in obs [:runner :tag :base] 0) (get-in obs [:runner :tag :additional] 0))
    :w-damage (:w-damage opts 2.0)
    :w-tag (or (:w-tag opts) 1.0)
    :w-program (or (:w-program opts) 0.0)
    :rez-tax (or (:rez-tax opts) 0.0)
    ;; Leech-style counters: the encountered ice gets -1 strength per counter (approximate: not
    ;; spent across the run's ice)
    :strength-reduce (reduce + 0 (for [c (get-in obs [:runner :rig :program])
                                       :when (re-find #"(?i)virus counter: the ice you are encountering gets -1 strength"
                                                      (str/replace (str (:text (cards/printed (:title c)))) #"<[^>]*>" ""))]
                                   (get-in c [:counter :virus] 0)))}))

(defn corp-server-safety
  "From the Corp's own observation: the Runner's best utility for a run on k worth `value`, assuming
  the Runner knows the ice and has `extra` more credits than now. Negative = safe."
  [obs k value extra]
  (runcalc/evaluate
   {:ices (mapv #(assoc % :hidden false) (ices obs k))
    :remote? (remote? k)
    :breakers (icebreakers obs)
    :credits (+ (get-in obs [:runner :credit]) extra)
    :hand (count (get-in obs [:runner :hand]))
    :corp-credits (get-in obs [:corp :credit])
    :pool {}
    :value value
    :toll (approach-toll obs k)
    :tagged (+ (get-in obs [:runner :tag :base] 0) (get-in obs [:runner :tag :additional] 0))
    :mode :expected}))

(alter-var-root #'trap-damage memoize)

(defn runner-pool-from-state
  "Unseen Runner cards {title qty} in a full or determinized state (hand + stack)."
  [s]
  (frequencies (keep :title (concat (get-in s [:runner :hand]) (get-in s [:runner :deck])))))

(defn runner-pool-from-obs
  "Runner decklist minus every Runner card the observation shows (installed, heap, scored, removed)."
  [obs runner-decklist]
  (let [rig (get-in obs [:runner :rig])
        seen (frequencies (keep #(when-not (:hidden %) (:title %))
                                (concat (:program rig) (:hardware rig) (:resource rig)
                                        (get-in obs [:runner :discard]) (get-in obs [:runner :rfg])
                                        (get-in obs [:runner :play-area]))))]
    (into {} (for [[t q] runner-decklist :let [left (- q (get seen t 0))] :when (pos? left)] [t left]))))

(defn corp-server-safety*
  "corp-server-safety that also expects the Runner to install a breaker from hand: for each
  icebreaker title in the unseen pool that would raise the Runner's utility (after paying its
  install cost), the best such utility is mixed in with the probability that the hand holds at
  least one useful copy, 1 - (1 - c/N)^h. Opponent decklists are public, so this uses no hidden
  information beyond what a determinization already samples."
  [obs k value extra runner-pool hand-size]
  (let [base (corp-server-safety obs k value extra)]
    (if (or (empty? runner-pool) (zero? (or hand-size 0)) (empty? (ices obs k)))
      base
      (let [installed (set (map :title (get-in obs [:runner :rig :program])))
            bs (icebreakers obs)
            ice (mapv #(assoc % :hidden false) (ices obs k))
            opts {:ices ice :remote? (remote? k) :hand (count (get-in obs [:runner :hand]))
                  :corp-credits (get-in obs [:corp :credit]) :pool {} :value value
                  :toll (approach-toll obs k) :mode :expected}
            cands (for [[t q] runner-pool
                        :when (and (not (installed t)) (cards/icebreaker? t))
                        :let [m (cards/breaker-model {:title t :monolith-grip (max 0 (dec (count (get-in obs [:runner :hand]))))} (inc (count bs)))
                              cr (- (+ (get-in obs [:runner :credit]) extra) (cards/play-cost t))]
                        :when (and m (>= cr 0))
                        :let [u (:u (runcalc/evaluate (assoc opts :breakers (conj (vec bs) m) :credits cr)))]
                        :when (> u (:u base))]
                    [u q])]
        (if (empty? cands)
          base
          (let [n (reduce + (vals runner-pool))
                c (reduce + (map second cands))
                p (- 1.0 (Math/pow (- 1.0 (min 1.0 (/ c (double n)))) hand-size))
                u-best (reduce max (map first cands))]
            (assoc base :u (+ (* p u-best) (* (- 1.0 p) (:u base))) :p-breaker p)))))))
