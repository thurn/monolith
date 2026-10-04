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

(defn icebreakers [obs]
  (let [bs (filter #(cards/icebreaker? (:title %)) (:program (get-in obs [:runner :rig])))
        n (count bs)]
    (keep #(cards/breaker-model % n) bs)))

(defn visible-corp-titles
  "Corp card titles whose identity this observation shows (any zone)."
  [obs]
  (->> (concat (all-corp-installed obs)
               (mapcat :hosted (all-corp-installed obs))
               (get-in obs [:corp :discard]) (get-in obs [:corp :scored]) (get-in obs [:runner :scored])
               (get-in obs [:corp :current]) (get-in obs [:corp :rfg]) (get-in obs [:corp :play-area])
               (remove :hidden (get-in obs [:corp :hand])))
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
  (some-> (re-find #"(?i)additional cost to steal this agenda, the Runner must pay (\d+)\[credit\]" (str (:text (cards/printed t))))
          second parse-long))

(defn agenda-access-value
  "Runner value of accessing agenda t: its points, less any steal cost, or nothing if the Runner
  cannot pay that cost (credits = the Runner's credits before the run)."
  [t ap-value credits]
  (let [c (steal-cost t)]
    (cond (nil? c) (* ap-value (ap t))
          (and credits (< credits c)) 0.0
          :else (max 0.0 (- (* ap-value (ap t)) c)))))

(defn hidden-content-value
  "Runner value (credits) of accessing one unknown remote card with adv counters in a server
  protected by n-ice pieces of ice (agendas are rarely left unprotected)."
  [pool adv n-ice {:keys [ap-value hand w-damage runner-credits]}]
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
                                  w (if (and (= typ "Agenda") (zero? n-ice)) (* w 0.1) w)]]
                        [t (* q w)])
              tot (reduce + 0.0 (map second weights))]
          (reduce + 0.0
                  (for [[t w] weights
                        :let [p (/ w tot)
                              dmg (trap-damage t adv)]]
                    (* p (cond (= "Agenda" (cards/ctype t)) (agenda-access-value t ap-value runner-credits)
                               dmg (if (>= dmg hand) runcalc/flatline-utility (- (* w-damage dmg)))
                               :else 0.5))))))))

(defn content-value
  "Runner value of breaching server k (credits-equivalent), excluding run costs."
  [obs k {:keys [corp-decklist ap-value hand w-damage extra-access] :or {extra-access 0} :as opts}]
  (let [pool (unseen-pool obs corp-decklist)
        dens (agenda-density pool)]
    (case k
      ;; after a successful R&D run this turn that stole nothing, the top card is known and
      ;; still there: another single-access run only sees it again
      :rd (let [reg (get-in obs [:runner :register])
                top-known (and (some #{:rd} (:successful-run reg)) (not (:stole-agenda reg)) (zero? extra-access))]
            (if top-known 0.0 (* ap-value dens (+ 1 extra-access) (if (seq (get-in obs [:corp :deck])) 1.0 0.0))))
      :hq (let [n (count (get-in obs [:corp :hand]))]
            (if (zero? n) 0.0 (* ap-value dens (min n (+ 1 extra-access)))))
      :archives (+ (* ap-value (reduce + 0 (map (comp ap :title) (remove :hidden (get-in obs [:corp :discard])))))
                   (* 0.3 ap-value dens (count (filter :hidden (get-in obs [:corp :discard])))))
      ;; remote
      (let [rpool (remote-card-pool obs corp-decklist)
            cr (get-in obs [:runner :credit])
            opts (assoc opts :runner-credits cr)]
        (reduce + 0.0
                (for [c (content obs k)]
                  (cond
                    (:hidden c) (hidden-content-value rpool (+ (or (:advance-counter c) 0)) (count (ices obs k)) opts)
                    (= "Agenda" (:type c)) (agenda-access-value (:title c) ap-value cr)
                    :else (let [tc (cards/trash-cost (:title c))]
                            ;; trashing a rezzed economy card denies the Corp its remaining credits,
                            ;; only worth anything if the Runner can afford the trash cost
                            (if (and tc (>= (get-in obs [:runner :credit]) (+ tc 1)))
                              (max 0.0 (- (* 0.5 (+ (get-in c [:counter :credit] 0) 3)) tc))
                              0.0)))))))))

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
    :corp-credits (get-in obs [:corp :credit])
    :pool (ice-pool obs corp-decklist)
    :value value
    :rez-bonus rez-bonus
    :toll (approach-toll obs k)
    :mode (or mode :expected)
    :w-damage (:w-damage opts 2.0)}))

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
                        :let [m (cards/breaker-model {:title t} (inc (count bs)))
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
