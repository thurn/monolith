(ns monolith.ai.knowledge.evaluator
  "Linear position evaluator over named features, in credit units from the Corp's perspective
  (the Runner uses the negation). Reads card data through the knowledge modules, never card names.
  Used by S3 (planner) and S2 (ISMCTS leaves). Feature weights live in weights.edn under :eval."
  (:require
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.runcalc :as runcalc]
   [monolith.ai.knowledge.servers :as srv]))

(def win-value 1000.0)

(defn- capped-credits [c]
  (let [c (double (or c 0))] (if (<= c 10) c (+ 10 (* 0.5 (- c 10))))))

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
             (let [m (cards/printed-ice-model (:title c) false)
                   base (min 3.0 (+ 1.0 (* 0.3 (:strength m)) (if (some :etr (:subs m)) 0.8 0.0)))
                   covered (some #(cards/can-break-type? % m) breakers)]
               (* server-weight base (if covered 0.6 1.0) (nth [1.0 0.7 0.5 0.4 0.3] (min i 4)))))
           ices)))

(defn agenda-ev
  "Expected Corp value of an installed agenda c in remote k: scoring minus stealing chance."
  [obs k c {:keys [ap-value]}]
  (let [ap (srv/ap (:title c))
        req (or (:current-advancement-requirement c) (:advancementcost (cards/printed (:title c))) 5)
        adv (or (:advance-counter c) 0)
        remaining (max 0 (- req adv))
        u (:u (srv/corp-server-safety obs k (* ap-value ap) 4))
        safe (if (<= u 0.0) 0.9 (/ 0.9 (+ 1.0 (/ u 4.0))))]
    ;; worth its points if it survives, minus the clicks+credits still needed, minus the steal risk
    (- (* safe ap ap-value) (* 2.0 remaining) (* (- 1.0 safe) ap ap-value))))

(defn features
  "Named feature values (Corp perspective) of a full or determinized state map."
  [s w]
  (let [ap-value (:ap-value w)
        breakers (srv/icebreakers s)
        rig (get-in s [:runner :rig])
        runner-installed (concat (:program rig) (:hardware rig) (:resource rig))
        corp-installed (srv/all-corp-installed s)
        corp-hand (get-in s [:corp :hand])
        covered (set (for [t ["Barrier" "Code Gate" "Sentry"]
                           :when (some #(contains? (cards/breaker-types (:title %)) t) (:program rig))]
                       t))]
    {:agenda-points (* ap-value (- (get-in s [:corp :agenda-point] 0) (get-in s [:runner :agenda-point] 0)))
     :credits (- (capped-credits (get-in s [:corp :credit])) (capped-credits (get-in s [:runner :credit])))
     :hosted-credits (- (hosted-credits (filter :rezzed corp-installed)) (hosted-credits runner-installed))
     :hands (- (* 0.5 (count corp-hand)) (* 0.8 (min 6 (count (get-in s [:runner :hand])))))
     ;; agendas are the Corp's finite route to 7 points: in HQ they are future points at some
     ;; steal risk; in Archives they are lost to the Corp and free for the Runner
     ;; liability scales with how exposed HQ is: unprotected HQ loses agendas fast
     :agendas-in-hq (let [hq-ap (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) corp-hand)))
                          eff (reduce + 0.0 (for [c (srv/ices s :hq)
                                                  :let [m (cards/printed-ice-model (:title c) false)]]
                                              (if (some #(cards/can-break-type? % m) breakers) 0.4 1.0)))
                          exposure (/ 0.7 (+ 1.0 (* 1.5 eff)))]
                      (- (* ap-value hq-ap (+ 0.25 exposure))))
     :agendas-in-archives (* -0.8 ap-value (reduce + 0 (map #(srv/ap (:title %)) (filter #(= "Agenda" (:type %)) (get-in s [:corp :discard])))))
     :installed-agendas (reduce + 0.0 (for [[k _] (srv/remotes s) c (srv/content s k)
                                            :when (= "Agenda" (:type c))]
                                        (agenda-ev s k c w)))
     :ice (reduce + 0.0 (for [[k srv] (srv/servers s)
                              :let [wt (cond (#{:hq :rd} k) 1.0
                                             (= :archives k) 0.3
                                             :else 0.7)]]
                          (ice-value s breakers wt (:ices srv))))
     :rig (+ (* 4.0 (count covered))
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
