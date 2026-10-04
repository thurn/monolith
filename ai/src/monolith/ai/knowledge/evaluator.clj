(ns monolith.ai.knowledge.evaluator
  "Linear position evaluator over named features, in credit units from the Corp's perspective
  (the Runner uses the negation). Reads card data through the knowledge modules, never card names.
  Used by S3 (planner) and S2 (ISMCTS leaves). Feature weights live in weights.edn under :eval."
  (:require
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.runcalc :as runcalc]
   [monolith.ai.knowledge.servers :as srv]))

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
                  (* n (if drip (min 0.6 (* 0.18 drip)) 0.6)))))

(defn- ice-value [obs breakers server-weight ices]
  (reduce + 0.0
          (map-indexed
           (fn [i c]
             ;; rezzed ice at its current strength (advanced Tree Line, Pharos, ...)
             (let [m (if (and (:rezzed c) (:current-strength c)) (cards/ice-model c) (cards/printed-ice-model (:title c) false))
                   base (min 3.0 (+ 1.0 (* 0.3 (:strength m)) (if (some :etr (:subs m)) 0.8 0.0)))
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
        safe (if (<= u 0.0) 0.9 (/ 0.9 (+ 1.0 (/ u 4.0))))]
    ;; worth its points if it survives, minus the clicks+credits still needed, minus the steal risk
    (- (* safe ap ap-value) (* 2.0 remaining) (* (- 1.0 safe) ap ap-value))))

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
         :cost (or (:cost p) 0)}))))

(def ^:private meat-op* (memoize meat-op))

(defn- kill-threat
  "Probability that the Corp holds a meat-damage operation that flatlines the Runner next turn
  (tags and grip as now, Corp credits + 3), over the Corp's unseen cards (hand + R&D):
  1 - (1 - c/N)^(h+1)."
  [s]
  (let [tags (+ (get-in s [:runner :tag :base] 0) (get-in s [:runner :tag :additional] 0))]
    (if (zero? tags)
      0.0
      (let [grip (count (get-in s [:runner :hand]))
            cr (+ 3 (get-in s [:corp :credit] 0))
            pool (keep :title (concat (get-in s [:corp :hand]) (get-in s [:corp :deck])))
            kills (count (filter (fn [t] (when-let [{:keys [dmg per-tag min-tags cost]} (meat-op* t)]
                                           (and (>= tags min-tags) (<= cost cr)
                                                (> (if per-tag (* dmg tags) dmg) grip))))
                                 pool))
            n (count pool)
            h (inc (count (get-in s [:corp :hand])))]
        (if (or (zero? kills) (zero? n)) 0.0
            (- 1.0 (Math/pow (- 1.0 (/ kills (double n))) h)))))))

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

(defn- asset-econ
  "Corp economy assets: rezzed per-turn gainers at their potential (loaded credits on rezzed cards
  are already in :hosted-credits); unrezzed ones at 0.8 x (potential - rez cost)."
  [corp-installed]
  (reduce + 0.0 (for [c corp-installed
                      :when (= "Asset" (:type c))
                      :let [pot (econ-potential* (:title c))]
                      :when pot]
                  (if (:rezzed c)
                    (if (re-find #"(?i)when your turn begins, gain" (str (:text (cards/printed (:title c))))) pot 0.0)
                    (* 0.8 (max 0.0 (- pot (cards/play-cost (:title c)))))))))

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
     :hq-agenda-cost (- (* 2.0 (reduce + 0 (for [c corp-hand :when (= "Agenda" (:type c))]
                                              (or (:advancementcost (cards/printed (:title c))) 5)))))
     ;; the Corp loses when it must draw from an empty R&D: drawing it down is costly
     :corp-deck-out (let [d (count (get-in s [:corp :deck]))] (- (* 0.5 (Math/pow (max 0 (- 10 d)) 2))))
     ;; agendas piling up in HQ (beyond 2 points) are a liability the static terms underrate:
     ;; they are not progressing and leak to HQ runs and hand-size discards
     :hq-flood (- (* 0.5 ap-value (max 0 (- (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) corp-hand))) 2))))
     :asset-econ (if (pos? (get-in w [:eval :asset-econ] 0.0)) (asset-econ corp-installed) 0.0)
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
     :runner-damage-exposure (if (<= (count (get-in s [:runner :hand])) 2) 2.0 0.0)
     :clicks (- (* 1.0 (get-in s [:corp :click] 0)) (* 1.0 (get-in s [:runner :click] 0)))
     ;; a tag costs the Runner a click and 2 credits to clear and exposes resources and meat damage
     :tags (* 2.5 (+ (get-in s [:runner :tag :base] 0) (get-in s [:runner :tag :additional] 0)))
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
