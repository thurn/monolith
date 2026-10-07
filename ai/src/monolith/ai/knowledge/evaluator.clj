(ns monolith.ai.knowledge.evaluator
  "Linear position evaluator over named features, in credit units from the Corp's perspective
  (the Runner uses the negation). Reads card data through the knowledge modules, never card names.
  Used by S3 (planner) and S2 (ISMCTS leaves). Feature weights live in weights.edn under :eval."
  (:require
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.runcalc :as runcalc]
   [monolith.ai.knowledge.servers :as srv]
   [clojure.string :as str]))

(def win-value 1000.0)

(defn- capped-credits
  "Credits with diminishing value: 1 each up to 10, 0.5 up to knee2 (default: no second knee),
  0.1 beyond."
  ([c] (capped-credits c nil))
  ([c knee2]
   (let [c (double (or c 0))
         k2 (double (or knee2 1e9))]
     (cond (<= c 10) c
           (<= c k2) (+ 10 (* 0.5 (- c 10)))
           :else (+ 10 (* 0.5 (- k2 10)) (* 0.1 (- c k2)))))))

(defn- corp-hand-value
  "Corp hand: with :corp-hand-curve, the first 3 cards are worth 1 each, the next 2 0.5, more 0;
  otherwise 0.5 per card."
  [n curve?]
  (if curve?
    (+ (min n 3) (* 0.5 (max 0 (- (min n 5) 3))))
    (* 0.5 n)))

(defn- hosted-credits
  "Credits loaded on cards, discounted by how fast they can be taken (parsed from card text):
  a slow drip (e.g. 1 per turn) is worth far less than credits taken 3 per click."
  [cards]
  (reduce + 0.0 (for [c cards
                      :let [n (get-in c [:counter :credit] 0)]
                      :when (pos? n)
                      :let [txt (str (:text (cards/printed (:title c))))
                            drip (some-> (re-find #"(?i)when your turn begins, take (\d+)\[credit\]" txt) second parse-long)]]
                  ;; click-to-take reserves (Liberated Account: 2 per click) at 0.4 each, so taking
                  ;; them (+2 liquid) beats a basic credit click (+1)
                  (* n (if drip (min 0.6 (* 0.18 drip)) 0.4)))))

(defn- harmonic-locked?
  "Unrezzed ice whose rez needs another rezzed harmonic ice derezzed (Bloop) when the Corp has none:
  it cannot be rezzed (modern review 15: an outer Bloop on R&D was passed for 25 runs)."
  [obs c]
  (and (not (:rezzed c))
       (re-find #"(?i)additional cost to rez this ice, derez another piece of harmonic ice" (str (:text (cards/printed (:title c)))))
       (not-any? #(and (:rezzed %) (not= (:cid %) (:cid c)) (re-find #"(?i)harmonic" (str (:subtype (cards/printed (:title %))))))
                 (mapcat :ices (vals (get-in obs [:corp :servers]))))))

(defn- ice-value [obs breakers server-weight ices]
  (reduce + 0.0
          (map-indexed
           (fn [i c]
             ;; rezzed ice at its current strength (advanced Tree Line, Pharos, ...)
             (let [m (if (and (:rezzed c) (:current-strength c)) (cards/ice-model c) (cards/printed-ice-model (:title c) false))
                   base (min 3.0 (+ 1.0 (* 0.3 (:strength m)) (if (some :etr (:subs m)) 0.8 0.0)))
                   base (if (harmonic-locked? obs c) (* 0.2 base) base)
                   covered (some #(cards/can-break-type? % m) breakers)]
               (* server-weight base (if covered 0.6 1.0) (nth [1.0 0.7 0.5 0.4 0.3] (min i 4)))))
           ices)))

(defn- runner-unseen-remote-pool
  "Agenda/asset/upgrade titles among the Corp's cards that the Runner has not seen (hand, R&D,
  facedown installed and facedown Archives), {title qty}: the Runner's prior for a hidden remote card."
  [s]
  (let [installed (srv/all-corp-installed s)
        hidden (concat (get-in s [:corp :hand]) (get-in s [:corp :deck])
                       (remove #(or (:rezzed %) (:seen %)) installed)
                       (remove :seen (get-in s [:corp :discard])))]
    (frequencies (filter #(#{"Agenda" "Asset" "Upgrade"} (cards/ctype %)) (keep :title hidden)))))

(defn- perceived-value
  "What running remote k is worth to a Runner who cannot see agenda c: the hidden-card value of
  its advancement level over the Runner's unseen pool, other cards in the server at face value."
  [s k c ap-value]
  ;; n-ice at least 1: do not assume the Runner discounts agendas in open remotes (S1's prior)
  (srv/hidden-content-value (runner-unseen-remote-pool s) (or (:advance-counter c) 0) (max 1 (count (srv/ices s k)))
                            {:ap-value ap-value :hand (count (get-in s [:runner :hand])) :w-damage 2.0
                             :runner-credits (get-in s [:runner :credit])}))

(defn agenda-ev
  "Expected Corp value of an installed agenda c in remote k: scoring minus stealing chance.
  With :perceived-safety, whether the Runner runs is judged on what the card is worth to it
  unseen (less a click's worth), so agendas hidden among assets count as safer."
  [obs k c {:keys [ap-value] :as w}]
  (let [ap (srv/ap (:title c))
        req (or (:current-advancement-requirement c) (:advancementcost (cards/printed (:title c))) 5)
        adv (or (:advance-counter c) 0)
        remaining (max 0 (- req adv))
        hidden? (and (:perceived-safety w) (not (:seen c)))
        value (if hidden? (perceived-value obs k c ap-value) (* ap-value ap))
        u (:u (if (:potential-breakers w)
                (srv/corp-server-safety* obs k value 4 (srv/runner-pool-from-state obs) (count (get-in obs [:runner :hand])))
                (srv/corp-server-safety obs k value 4)))
        u (if hidden? (- u 1.5) u)
        safe (if (<= u 0.0) 0.9 (/ 0.9 (+ 1.0 (/ u 4.0))))
        ;; a score that reaches 7 wins the game, a steal that reaches 7 loses it: worth far more
        ;; than the points (otherwise a Corp on 6 sits on agendas forever)
        win srv/winning-steal-value
        score-v (if (>= (+ (get-in obs [:corp :agenda-point] 0) ap) 7) win (* ap ap-value))
        steal-v (if (>= (+ (get-in obs [:runner :agenda-point] 0) ap) 7) win (* ap ap-value))]
    ;; worth its points if it survives, minus the clicks+credits still needed, minus the steal risk
    (- (* safe score-v) (* 2.0 remaining) (* (- 1.0 safe) steal-v))))

(defn- remaining-adv [c]
  (let [req (or (:current-advancement-requirement c) (:advancementcost (cards/printed (:title c))) 5)]
    (max 0 (- req (or (:advance-counter c) 0)))))

(defn- in-reach?
  "On the Corp's turn: agenda c can be advanced to its requirement with the clicks and credits left."
  [s c]
  (let [r (remaining-adv c)]
    (and (= :corp (:active-player s))
         (<= r (get-in s [:corp :click] 0))
         (<= r (get-in s [:corp :credit] 0)))))

(defn- meat-op
  "{:dmg n :per-tag bool :min-tags k :cost c} for an operation that does meat damage, from text."
  [title]
  (let [p (cards/printed title)
        t (str (:text p))]
    (when (= "Operation" (:type p))
      (when-let [[_ n per] (re-find #"(?i)do (\d+) meat damage( for each tag)?" t)]
        {:dmg (parse-long n) :per-tag (boolean per)
         :min-tags (cond (re-find #"(?i)at least 2 tags" t) 2
                         (re-find #"(?i)tagged|remove 1 tag" t) 1
                         :else 0)
         :cost (or (:cost p) 0)
         ;; Measured Response: "Play only if the threat level is 4 or greater, and only if the Runner
         ;; made a successful run during their last turn. Do 4 meat damage unless the Runner pays 8"
         :threat (some-> (re-find #"(?i)threat level is (\d+) or greater" t) second parse-long)
         :needs-run (boolean (re-find #"(?i)successful run during their last turn" t))
         :unless-pay (some-> (re-find #"(?i)meat damage unless the Runner pays (\d+)" t) second parse-long)}))))

(def ^:private meat-op* (memoize meat-op))

(defn- kill-threat
  "Probability that the Corp holds a meat-damage operation that flatlines the Runner next turn
  (tags and grip as now, Corp credits + 3), over the Corp's unseen cards (hand + R&D):
  1 - (1 - c/N)^(h+1)."
  [s]
  (let [tags (+ (get-in s [:runner :tag :base] 0) (get-in s [:runner :tag :additional] 0))
        threat (max (get-in s [:runner :agenda-point] 0) (get-in s [:corp :agenda-point] 0))
        ran (seq (get-in s [:runner (if (= :corp (:active-player s)) :register-last-turn :register) :successful-run]))
        rcr (get-in s [:runner :credit] 0)]
    (if (and (zero? tags) (< threat 3))
      0.0
      (let [grip (count (get-in s [:runner :hand]))
            cr (+ 3 (get-in s [:corp :credit] 0))
            pool (keep :title (concat (get-in s [:corp :hand]) (get-in s [:corp :deck])))
            kills (count (filter (fn [t] (when-let [{:keys [dmg per-tag min-tags cost needs-run unless-pay] :as op} (meat-op* t)]
                                           (and (>= tags min-tags) (<= cost cr)
                                                ;; untagged kills (Measured Response, modern review 15)
                                                (>= threat (or (:threat op) 0))
                                                (or (not needs-run) ran)
                                                (or (nil? unless-pay) (< rcr unless-pay))
                                                ;; >= : a hand wiped to 0 is one more damage from dead, and two
                                                ;; copies kill (review 15: tagged at 4 cards vs Scorched Earth)
                                                (>= (if per-tag (* dmg tags) dmg) grip))))
                                 pool))
            n (count pool)
            h (inc (count (get-in s [:corp :hand])))]
        (if (or (zero? kills) (zero? n)) 0.0
            (- 1.0 (Math/pow (- 1.0 (/ kills (double n))) h)))))))

(defn- tag-punisher? [title]
  (boolean (re-find #"(?i)runner is tagged|for each tag|trash (1|a|an installed) resource|meat damage|if the runner has (a|any|\d+) tags?|tagged runner"
                    (str (:text (cards/printed title))))))

(def ^:private tag-punisher?* (memoize tag-punisher?))

(defn- tag-threat?
  "Whether any Corp card the Runner could still face (hand, R&D, installed) uses tags."
  [s]
  (some #(some-> (:title %) tag-punisher?*)
        (concat (get-in s [:corp :hand]) (get-in s [:corp :deck]) (srv/all-corp-installed s))))

(defn- econ-potential
  "Credits an economy asset is worth over the next few turns, from its text: per-turn gains
  (PAD Campaign: 4 turns' worth) or credits loaded on rez (Adonis, Marilyn: 0.6 each)."
  [title]
  (let [t (str (:text (cards/printed title)))
        drip (some-> (re-find #"(?i)when your turn begins, gain (\d+)\[credit\]" t) second parse-long)
        load (some-> (re-find #"(?i)(?:load|put|place) (\d+)\[credit\]" t) second parse-long)]
    (cond drip (* 4.0 drip)
          load (* 0.6 load)
          :else nil)))

(def ^:private econ-potential* (memoize econ-potential))

(defn- exposed?
  "Corp card in an iceless remote whose trash cost the Runner can pay."
  [s c]
  (let [[_ k] (:zone c)]
    (boolean (and k (str/starts-with? (name k) "remote")
                  (empty? (get-in s [:corp :servers k :ices]))
                  (>= (get-in s [:runner :credit] 0) (or (cards/trash-cost (:title c)) 99))))))

(defn- asset-econ
  "Corp economy assets: rezzed per-turn gainers at their potential (loaded credits on rezzed cards
  are already in :hosted-credits); unrezzed ones at 0.8 x (potential - rez cost), 0.3 x when exposed
  (modern reviews: Nico Campaign installed naked and trashed turn after turn)."
  [s corp-installed]
  (reduce + 0.0 (for [c corp-installed
                      :when (= "Asset" (:type c))
                      :let [pot (econ-potential* (:title c))]
                      :when pot]
                  (if (:rezzed c)
                    (if (re-find #"(?i)when your turn begins, gain" (str (:text (cards/printed (:title c))))) pot 0.0)
                    (* (if (exposed? s c) 0.3 0.8) (max 0.0 (- pot (cards/play-cost (:title c)))))))))

(defn- central-threat
  "Runner's expected gain next turn from running HQ and R&D, as the Corp knows them (its own HQ
  agendas, its deck's agenda density), by the run calculator; only positive values count."
  [s w]
  (let [apv (:ap-value w)
        hand (get-in s [:corp :hand])
        deck (get-in s [:corp :deck])
        hq-v (if (seq hand) (* apv (/ (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) hand))) (double (count hand)))) 0.0)
        rd-v (if (seq deck) (* apv (/ (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) deck))) (double (count deck)))) 0.0)
        pool (when (:potential-breakers w) (srv/runner-pool-from-state s))
        hs (count (get-in s [:runner :hand]))
        u (fn [k v] (if (pos? v)
                      (max 0.0 (:u (if pool (srv/corp-server-safety* s k v 2 pool hs) (srv/corp-server-safety s k v 2))))
                      0.0))]
    (- (+ (u :hq hq-v) (u :rd rd-v)))))

(defn features
  "Named feature values (Corp perspective) of a full or determinized state map."
  [s w]
  (let [ap-value (:ap-value w)
        scorable? (pos? (get-in w [:eval :scorable-agendas] 0.0))
        breakers (srv/icebreakers s)
        rig (get-in s [:runner :rig])
        runner-installed (concat (:program rig) (:hardware rig) (:resource rig))
        corp-installed (srv/all-corp-installed s)
        corp-hand (get-in s [:corp :hand])
        covered (set (for [t ["Barrier" "Code Gate" "Sentry"]
                           :when (some #(contains? (cards/breaker-types (:title %)) t) (:program rig))]
                       t))]
    {:agenda-points (* ap-value (- (get-in s [:corp :agenda-point] 0) (get-in s [:runner :agenda-point] 0)))
     :credits (- (capped-credits (get-in s [:corp :credit]) (:credit-knee2 w)) (capped-credits (get-in s [:runner :credit]) (:credit-knee2 w)))
     :hosted-credits (- (hosted-credits (filter :rezzed corp-installed)) (hosted-credits runner-installed))
     ;; capped at the maximum hand sizes: lines are scored before the end-of-turn discard, so
     ;; cards beyond the limit would be counted and then thrown away (draw-into-discard)
     :hands (- (corp-hand-value (min (count corp-hand) (or (get-in s [:corp :hand-size :total]) 5) 7) (:corp-hand-curve w))
               (* 0.8 (min (count (get-in s [:runner :hand])) (or (get-in s [:runner :hand-size :total]) 5))))
     ;; agendas are the Corp's finite route to 7 points: in HQ they are future points at some
     ;; steal risk; in Archives they are lost to the Corp and free for the Runner
     ;; liability scales with how exposed HQ is: unprotected HQ loses agendas fast
     :agendas-in-hq (- (* ap-value 0.25 (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) corp-hand)))))
     :hq-exposure (let [hq-ap (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) corp-hand)))
                        eff (reduce + 0.0 (for [c (srv/ices s :hq)
                                                :let [m (cards/printed-ice-model (:title c) false)]]
                                            (if (some #(cards/can-break-type? % m) breakers) 0.4 1.0)))]
                    (- (* ap-value hq-ap (/ 0.7 (+ 1.0 (* 1.5 eff))))))
     ;; agendas in HQ still need their full advancement (installed ones pay only what remains,
     ;; in agenda-ev), so holding an agenda is not cheaper than installing it
     ;; agendas still in R&D are at least as far from scoring as agendas in HQ: without this,
     ;; the HQ terms above make every draw a loss in expectation and a rich Corp clicks for
     ;; credits instead of drawing (dev review 6)
     :rd-agendas (if (pos? (get-in w [:eval :rd-agendas] 0.0))
                   (let [rd-ap (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) (get-in s [:corp :deck]))))
                         eff (reduce + 0.0 (for [c (srv/ices s :rd)
                                                 :let [m (cards/printed-ice-model (:title c) false)]]
                                             (if (some #(cards/can-break-type? % m) breakers) 0.4 1.0)))]
                     (- (* ap-value rd-ap (+ 0.35 (/ 0.7 (+ 1.0 (* 1.5 eff)))))))
                   0.0)
     ;; :rd-exposure: R&D's agenda density times ~3 accesses a turn, damped by R&D ice like
     ;; :hq-exposure (modern reviews: R&D bare for 4-8 turns while HQ got two ice on turn 1)
     :rd-exposure (if (pos? (get-in w [:eval :rd-exposure] 0.0))
                    (let [deck (get-in s [:corp :deck])
                          rd-ap (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) deck)))
                          eff (reduce + 0.0 (for [c (srv/ices s :rd)
                                                  :let [m (cards/printed-ice-model (:title c) false)]]
                                              (if (some #(cards/can-break-type? % m) breakers) 0.4 1.0)))]
                      (let [dens (/ rd-ap (double (max 1 (count deck))))
                            ;; HQ's share: agendas the Corp will draw into HQ (~2 cards a turn), on top of the
                            ;; agendas already there (:hq-exposure) (modern review 8: HQ bare until turn 10)
                            hq-eff (reduce + 0.0 (for [c (srv/ices s :hq)
                                                       :let [m (cards/printed-ice-model (:title c) false)]]
                                                   (if (some #(cards/can-break-type? % m) breakers) 0.4 1.0)))]
                        (- (+ (* ap-value dens 3.0 (/ 0.7 (+ 1.0 (* 1.5 eff))))
                              (* ap-value dens 2.0 (/ 0.7 (+ 1.0 (* 1.5 hq-eff))))))))
                    0.0)
     :hq-agenda-cost (- (* 2.0 (reduce + 0 (for [c corp-hand :when (= "Agenda" (:type c))]
                                              (or (:advancementcost (cards/printed (:title c))) 5)))))
     ;; the Corp loses when it must draw from an empty R&D: drawing it down is costly
     :corp-deck-out (let [d (count (get-in s [:corp :deck]))] (- (* 0.5 (Math/pow (max 0 (- 10 d)) 2))))
     ;; agendas piling up in HQ (beyond 2 points) are a liability the static terms underrate:
     ;; they are not progressing and leak to HQ runs and hand-size discards
     :hq-flood (- (* 0.5 ap-value (max 0 (- (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) corp-hand))) 2))))
     :asset-econ (if (pos? (get-in w [:eval :asset-econ] 0.0)) (asset-econ s corp-installed) 0.0)
     ;; tagged Runner with a small grip against a deck that kills (credits-equivalent of ~0.1 win per 1.0 probability)
     :kill-threat (if (pos? (get-in w [:eval :kill-threat] 0.0)) (* 100.0 (kill-threat s)) 0.0)
     :central-threat (if (pos? (get-in w [:eval :central-threat] 0.0)) (central-threat s w) 0.0)
     :agendas-in-archives (* -0.8 ap-value (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) (get-in s [:corp :discard])))))
     :installed-agendas (reduce + 0.0 (for [[k _] (srv/remotes s) c (srv/content s k)
                                            :when (and (= "Agenda" (:type c)) (not (and scorable? (in-reach? s c))))]
                                        (agenda-ev s k c w)))
     ;; an agenda the Corp can finish advancing this turn with its clicks and credits is worth
     ;; nearly its points (weighted like :agenda-points), so beam search keeps advance chains
     :scorable-agendas (if scorable?
                         (reduce + 0.0 (for [[k _] (srv/remotes s) c (srv/content s k)
                                             :when (and (= "Agenda" (:type c)) (in-reach? s c))]
                                         (- (* ap-value (srv/ap (:title c))) (* 0.5 (remaining-adv c)))))
                         0.0)
     :ice (let [;; with :empty-remote-ice, only the best-iced empty remote (a scoring server in waiting)
                ;; counts fully; ice on further empty remotes is mostly wasted
                empties (when (:empty-remote-ice w)
                          (->> (srv/remotes s) (filter #(empty? (:content (val %)))) (sort-by #(- (count (:ices (val %))))) (map key)))
                spare (set (rest empties))]
            (reduce + 0.0 (for [[k srv] (srv/servers s)
                                :let [wt (cond (#{:hq :rd} k) 1.0
                                               (= :archives k) 0.3
                                               (spare k) 0.15
                                               :else 0.7)]]
                            (ice-value s breakers wt (:ices srv)))))
     :rig (+ (* 4.0 (count covered))
             ;; with :grip-breakers, breakers in the grip for still-uncovered types count partly
             (if (:grip-breakers w)
               (* 1.5 (count (remove covered (set (for [c (get-in s [:runner :hand])
                                                        :when (and (:title c) (cards/icebreaker? (:title c)))
                                                        t (cards/breaker-types (:title c))]
                                                    t)))))
               0.0)
             (* 1.5 (count (filter #(and (not (cards/icebreaker? (:title %))) (= "Program" (:type %))) runner-installed)))
             (* 1.5 (count (:hardware rig)))
             (* 1.0 (count (:resource rig))))
     :corp-assets (reduce + 0.0 (for [c corp-installed
                                      :when (= "Asset" (:type c))]
                                  (cond (srv/trap-damage (:title c) 0) (if (:rezzed c) -1.0 (+ 1.0 (* 0.3 (or (:advance-counter c) 0))))
                                        (and (not (:rezzed c)) (cards/load-credits (:title c))) 2.0
                                        :else 0.5)))
     ;; core damage lowers the Runner's hand size for the rest of the game; the :hands cap alone
     ;; makes it nearly free, so Stimhack went on runs that needed no credits (dev reviews 12-14)
     :core-damage (if (pos? (get-in w [:eval :core-damage] 0.0)) (* 4.0 (get-in s [:runner :brain-damage] 0)) 0.0)
     :runner-damage-exposure (if (<= (count (get-in s [:runner :hand])) 2) 2.0 0.0)
     :clicks (- (* 1.0 (get-in s [:corp :click] 0)) (* 1.0 (get-in s [:runner :click] 0)))
     ;; a tag costs the Runner a click and 2 credits to clear and exposes resources and meat damage;
     ;; with :tag-threat, little when no resource is installed (the basic trash action needs one)
     ;; and the Corp has no card that uses tags
     :tags (let [n (+ (get-in s [:runner :tag :base] 0) (get-in s [:runner :tag :additional] 0))]
             (+ (* (if (and (:tag-threat w) (empty? (:resource rig)) (not (tag-threat? s))) 0.5 2.5) n)
                ;; with :tag-exposure, a tagged Runner's resources are trash targets: up to three (the Corp's
                ;; clicks), each worth its install cost + 1, if the Corp can pay the 2-credit trash (modern
                ;; review 16: Dr. Nuka Vrolyck and Daily Casts installed while tagged and trashed)
                (if (and (:tag-exposure w) (pos? n) (>= (get-in s [:corp :credit] 0) 2))
                  (* 0.7 (reduce + 0.0 (take 3 (sort > (map #(inc (or (cards/play-cost (:title %)) 0)) (:resource rig))))))
                  0.0)))
     ;; each bad publicity gives the Runner a credit per run
     :bad-publicity (* -1.5 (+ (get-in s [:corp :bad-publicity :base] 0) (get-in s [:corp :bad-publicity :additional] 0)))}))

(defn evaluate
  "Corp-perspective utility of state map s; game-over states are +-win-value."
  [s w]
  (cond
    (= :corp (:winner s)) win-value
    (= :runner (:winner s)) (- win-value)
    :else (let [fw (:eval w)
                fs (features s w)]
            (reduce + 0.0 (for [[k v] fs] (* (get fw k 1.0) v))))))

(defn for-side [s side w]
  (let [u (evaluate s w)] (if (= side :corp) u (- u))))
