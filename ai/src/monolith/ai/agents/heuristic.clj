(ns monolith.ai.agents.heuristic
  "S1: rule-based expert in the Chiriboga tradition, rebuilt on shared knowledge modules.
  A named priority cascade per side; every rule is (fn [env] action-or-nil). The last rule
  that fired is kept in the agent's :trace atom for failure analysis."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [monolith.ai.engine :as engine]
   [monolith.ai.harness :as h]
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.runcalc :as runcalc]
   [monolith.ai.knowledge.servers :as srv]
   [monolith.ai.moves :as moves]))

(def default-weights (edn/read-string (slurp (io/resource "monolith/ai/knowledge/weights.edn"))))

;;; Helpers

(defn acts [env type] (filter #(= type (:type %)) (:actions env)))
(defn act [env type] (first (acts env type)))
(defn act-where [env pred] (first (filter pred (:actions env))))
(defn card-title [a] (get-in a [:args :card :title]))
(defn ptype [title] (cards/ctype title))
(defn me [env] (get (:obs env) (:side env)))
(defn credits [env] (get-in (:obs env) [(:side env) :credit]))
(defn clicks [env] (get-in (:obs env) [(:side env) :click]))
(defn w [env k] (get-in env [:weights k]))
(defn label-is [a re] (re-find re (str (:label a))))

(defn adv-need [c]
  (- (or (:current-advancement-requirement c) (:advancementcost c) (:advancementcost (cards/printed (:title c))) 99)
     (or (:advance-counter c) 0)))

(defn find-card [obs cid]
  (some #(when (= cid (:cid %)) %) (srv/all-corp-installed obs)))

(defn prompt [env] (moves/current-prompt (:obs env) (:side env)))

(defn choice [env re]
  (act-where env #(and (#{:choice :done} (:type %)) (label-is % re))))

(defn econ-ability-value
  "Credits a click ability yields now (0 if it is not an economy ability)."
  [env a]
  (let [obs (:obs env)
        c (some #(when (= (get-in a [:args :card :cid]) (:cid %)) %) (concat (srv/runner-installed obs) (srv/all-corp-installed obs)))
        hosted (get-in c [:counter :credit] 0)
        lbl (str (:label a))]
    (cond
      (re-find #"(?i)take (\d+) \[credits\]" lbl) (min hosted (parse-long (second (re-find #"(?i)take (\d+)" lbl))))
      (re-find #"(?i)take all hosted credits" lbl) (+ 1 hosted)
      (re-find #"(?i)place 3 \[credits\]" lbl) 1.5
      :else 0)))

(defn best-econ-ability [env]
  (let [cands (filter #(> (econ-ability-value env %) 1) (acts env :click-ability))]
    (when (seq cands) (apply max-key #(econ-ability-value env %) cands))))

;;; Corp

(defn corp-decklist [env] (engine/decklist (get-in env [:decks :corp])))
(defn runner-decklist [env] (engine/decklist (get-in env [:decks :runner])))

(defn ice-install-cost [obs server]
  (if (= server "New remote") 0 (count (srv/ices obs (srv/server-key server)))))

(defn ice-score
  "How good a piece of ice is to install (higher is better)."
  [title]
  (let [m (cards/printed-ice-model title false)]
    (+ (if (cards/etr-ice? title) 3 0) (if (cards/damage-ice? title) 1.5 0) (* 0.3 (:strength m)))))

(defn agenda-in-server? [obs k]
  (some #(or (:hidden %) (#{"Agenda" "Asset"} (:type %))) (srv/content obs k)))

(defn remote-safe?
  "Runner can't profitably get into remote k next turn (from the Corp's knowledge)."
  [env k value]
  (let [obs (:obs env)
        ev (if (w env :potential-breakers)
             (srv/corp-server-safety* obs k value (w env :corp-safety-extra)
                                      (srv/runner-pool-from-obs obs (runner-decklist env))
                                      (count (get-in obs [:runner :hand])))
             (srv/corp-server-safety obs k value (w env :corp-safety-extra)))]
    (and (seq (srv/ices obs k)) (<= (:u ev) 0.0))))

(defn scoring-remotes
  "Remotes with ice and no agenda/asset in them, safest first."
  [env]
  (let [obs (:obs env)]
    (->> (srv/remotes obs)
         (map key)
         (filter #(and (seq (srv/ices obs %)) (empty? (srv/content obs %))))
         (sort-by #(- (count (srv/ices obs %)))))))

(defn c-score [env] (act env :score))

(defn c-advance-to-score
  "Advance an installed agenda that can be scored this turn."
  [env]
  (let [obs (:obs env)
        cands (for [a (acts env :advance)
                    :let [c (find-card obs (get-in a [:args :card :cid]))]
                    :when (and c (= "Agenda" (:type c)))
                    :let [need (adv-need c)]
                    :when (and (<= need (clicks env)) (<= need (credits env)))]
                [need a])]
    (second (first (sort-by first cands)))))

(defn place-advancements
  "Advancement counters an operation places on an installed card, from its text (nil if none)."
  [title]
  (some-> (re-find #"(?i)place (\d+) advancement (?:counters|tokens) on (?:1|an|up to 1) installed card" (str (:text (cards/printed title))))
          second parse-long))

(defn c-seamless
  "Play an operation that places advancement counters onto an agenda (not installed this turn)
  when that makes scoring it possible this turn."
  [env]
  (when-let [a (act-where env #(and (= :play (:type %)) (place-advancements (card-title %))))]
    (let [obs (:obs env)
          n (place-advancements (card-title a))
          cost (cards/play-cost (card-title a))
          cands (for [[k _] (srv/remotes obs) c (srv/content obs k)
                      :when (and (= "Agenda" (:type c)) (not (:new c)) (= true (:installed c)))
                      :let [need (- (adv-need c) n)]
                      :when (and (<= need (dec (clicks env))) (<= (+ cost (max 0 need)) (credits env)))]
                  c)]
      (when (seq cands) a))))

(defn c-protect-centrals
  "Ice an unprotected HQ/R&D."
  [env]
  (let [obs (:obs env)
        installs (filter #(= "ICE" (ptype (card-title %))) (acts env :install))]
    (first
     (for [server ["HQ" "R&D"]
           :when (empty? (srv/ices obs (srv/server-key server)))
           a (sort-by #(- (ice-score (card-title %))) installs)
           :when (= server (get-in a [:args :server]))]
       a))))

(defn c-react-centrals
  "With :react-centrals, ice HQ or R&D (best ice first) when the Runner got into it last turn and it
  has fewer than 2 ice: the human reaction to repeated central pressure."
  [env]
  (when (w env :react-centrals)
    (let [obs (:obs env)
          hit (set (get-in obs [:runner :register-last-turn :successful-run]))
          installs (filter #(= "ICE" (ptype (card-title %))) (acts env :install))]
      (first
       (for [[server k] [["R&D" :rd] ["HQ" :hq]]
             :when (and (hit k) (< (count (srv/ices obs k)) 2))
             a (sort-by #(- (ice-score (card-title %))) installs)
             :when (and (= server (get-in a [:args :server]))
                        (<= (ice-install-cost obs server) (- (credits env) 2)))]
         a)))))

(defn c-dig-ice
  "With :dig-ice, a Corp with an unprotected HQ or R&D and no ice in hand digs for ice: a draw
  operation if it has one (e.g. Violet Level Clearance), else a basic draw."
  [env]
  (when (w env :dig-ice)
    (let [obs (:obs env)
          hand (get-in obs [:corp :hand])]
      (when (and (some #(empty? (srv/ices obs %)) [:hq :rd])
                 (not-any? #(= "ICE" (:type %)) hand)
                 (< (count hand) (or (get-in obs [:corp :hand-size :total]) 5))
                 (> (count (get-in obs [:corp :deck])) 5))
        (or (act-where env #(and (= :play (:type %)) (re-find #"(?i)draw \d+ cards" (str (:text (cards/printed (card-title %)))))
                                 (<= (cards/play-cost (card-title %)) (credits env))))
            (act env :draw))))))

(defn c-install-agenda
  "Install an agenda into a safe, empty, iced remote when it can be scored by next turn."
  [env]
  (let [obs (:obs env)
        agendas (filter #(= "Agenda" (ptype (card-title %))) (acts env :install))]
    (first
     (for [a (sort-by #(- (srv/ap (card-title %))) agendas)
           k (scoring-remotes env)
           :when (= (srv/server-name k) (get-in a [:args :server]))
           :let [req (or (:advancementcost (cards/printed (card-title a))) 9)
                 after (dec (clicks env))
                 now-adv (min after (credits env))
                 next-need (- req now-adv)]
           :when (and (<= next-need 3) (remote-safe? env k (* 5 (srv/ap (card-title a)))))]
       a))))

(defn c-advance-for-next-turn
  "Advance an agenda installed in a remote so it is scorable next turn (never-advance otherwise)."
  [env]
  (let [obs (:obs env)]
    (first
     (for [a (acts env :advance)
           :let [c (find-card obs (get-in a [:args :card :cid]))]
           :when (and c (= "Agenda" (:type c)))
           :let [need (adv-need c)]
           :when (and (> need 3) (pos? (credits env)))]
       a))))

(defn c-build-scoring-remote
  "Create an iced remote when holding an agenda and none exists."
  [env]
  (let [obs (:obs env)
        hand (get-in obs [:corp :hand])]
    (when (and (some #(= "Agenda" (:type %)) hand) (empty? (scoring-remotes env)))
      (first (for [a (sort-by #(- (ice-score (card-title %))) (acts env :install))
                   :when (and (= "ICE" (ptype (card-title a))) (= "New remote" (get-in a [:args :server])))]
               a)))))

(defn c-ice-scoring-remote
  [env]
  (let [obs (:obs env)
        hand (get-in obs [:corp :hand])
        k (first (scoring-remotes env))]
    (when (and k (some #(= "Agenda" (:type %)) hand)
               (or (< (count (srv/ices obs k)) (w env :corp-ice-scoring-remote))
                   (and (< (count (srv/ices obs k)) 4) (not (remote-safe? env k 10.0)))))
      (first (for [a (sort-by #(- (ice-score (card-title %))) (acts env :install))
                   :when (and (= "ICE" (ptype (card-title a))) (= (srv/server-name k) (get-in a [:args :server]))
                              (<= (ice-install-cost obs (srv/server-name k)) (- (credits env) 3)))]
               a)))))

(defn c-play-econ
  [env]
  (let [ops (filter #(pos? (cards/econ-gain (card-title %))) (acts env :play))]
    (when (< (credits env) (w env :corp-econ-cap))
      (first (sort-by #(- (cards/econ-gain (card-title %))) ops)))))

(defn c-econ-asset-ability [env]
  (when (< (credits env) 15) (best-econ-ability env)))

(defn econ-asset?
  "Assets that pay out credits: loaded on rez (Adonis) or gained each turn (PAD Campaign)."
  [title]
  (or (cards/load-credits title)
      (re-find #"(?i)when your turn begins, gain \d+\[credit\]" (str (:text (cards/printed title))))))

(defn c-install-econ-asset
  [env]
  (let [obs (:obs env)
        n-remotes (count (srv/remotes obs))]
    (when (< n-remotes (w env :max-remotes))
      (act-where env #(and (= :install (:type %)) (= "New remote" (get-in % [:args :server]))
                           (= "Asset" (ptype (card-title %))) (econ-asset? (card-title %)))))))

(defn c-rez-econ
  [env]
  (act-where env #(and (= :rez (:type %)) (econ-asset? (card-title %))
                       (>= (credits env) (+ (cards/play-cost (card-title %)) 0)))))

(defn c-install-ambush
  "Install an ambush (advanceable trap) into a new remote as a decoy."
  [env]
  (let [obs (:obs env)]
    (when (< (count (srv/remotes obs)) (w env :max-remotes))
      (act-where env #(and (= :install (:type %)) (= "New remote" (get-in % [:args :server]))
                           (srv/trap-damage (card-title %) 0))))))

(defn c-bluff-advance
  [env]
  (let [obs (:obs env)]
    (when (and (>= (credits env) 4) (< (.nextDouble ^java.util.Random (:rng env)) (w env :bluff-advance-p)))
      (first (for [a (acts env :advance)
                   :let [c (find-card obs (get-in a [:args :card :cid]))]
                   :when (and c (srv/trap-damage (:title c) 0) (< (or (:advance-counter c) 0) 3))]
               a)))))

(defn c-more-ice
  [env]
  (let [obs (:obs env)
        targets (concat ["HQ" "R&D"] (map srv/server-name (filter #(seq (srv/content obs %)) (map key (srv/remotes obs)))))
        weakest (sort-by #(count (srv/ices obs (srv/server-key %))) targets)]
    (first (for [server weakest
                 :when (< (count (srv/ices obs (srv/server-key server))) (if (#{"HQ" "R&D"} server) (w env :corp-ice-per-central) 3))
                 a (sort-by #(- (ice-score (card-title %))) (acts env :install))
                 :when (and (= "ICE" (ptype (card-title a))) (= server (get-in a [:args :server]))
                            (<= (ice-install-cost obs server) (- (credits env) (w env :corp-ice-reserve))))]
             a))))

(defn c-draw [env]
  ;; never draw past the maximum hand size (the extra card is discarded at end of turn)
  (let [n (count (get-in (:obs env) [:corp :hand]))
        mx (or (get-in (:obs env) [:corp :hand-size :total]) 5)]
    (when (and (< n mx) (> (count (get-in (:obs env) [:corp :deck])) 5) (or (< n 4) (>= (credits env) 10))) (act env :draw))))

(defn c-unclog
  "With :unclog, a Corp at its maximum hand size installs an asset (economy first) into a new
  remote rather than click for credit and discard at end of turn."
  [env]
  (when (w env :unclog)
    (let [obs (:obs env)
          n (count (get-in obs [:corp :hand]))
          mx (or (get-in obs [:corp :hand-size :total]) 5)]
      (when (>= n mx)
        (let [assets (filter #(and (= :install (:type %)) (= "New remote" (get-in % [:args :server]))
                                   (= "Asset" (ptype (card-title %))))
                             (:actions env))]
          (when (seq assets)
            (or (first (filter #(econ-asset? (card-title %)) assets)) (first assets))))))))

(defn c-credit [env] (act env :credit))

(defn rich-draw
  "With :rich-credit set, a side at or above that many credits draws instead of clicking for a
  credit while below its hand size (dev review 6: rich idle credit clicks)."
  [env side]
  (let [obs (:obs env)
        t (get-in env [:weights :rich-credit])]
    (when (and t (>= (credits env) t)
               (< (count (get-in obs [side :hand])) (or (get-in obs [side :hand-size :total]) 5))
               (> (count (get-in obs [side :deck])) 5))
      (act env :draw))))

(defn- card-text [title] (str (:text (cards/printed title))))
(defn- runner-tagged? [obs] (pos? (or (get-in obs [:runner :tag :base]) 0)))

(defn meat-damage [title]
  (some-> (re-find #"(?i)do (\d+) meat damage" (card-text title)) second parse-long))

(defn- op-damage
  "Meat damage of operation t now (per-tag texts like High-Profile Target scale with tags)."
  [obs t]
  (when-let [d (meat-damage t)]
    (if (re-find #"(?i)meat damage for each tag" (card-text t))
      (* d (+ (get-in obs [:runner :tag :base] 0) (get-in obs [:runner :tag :additional] 0)))
      d)))

(defn c-kill
  "Meat-damage operations when they flatline the Runner (whole hand plus one)."
  [env]
  (let [obs (:obs env)
        hand (count (get-in obs [:runner :hand]))
        ops (filter #(op-damage obs (card-title %)) (acts env :play))]
    (when (and (seq ops) (>= (reduce + (map #(op-damage obs (card-title %)) ops)) (inc hand)))
      (apply max-key #(op-damage obs (card-title %)) ops))))

(defn c-trash-resource
  "When the Runner is tagged, trash their best resource."
  [env]
  (when (runner-tagged? (:obs env)) (act env :trash-resource)))

(defn c-tag-op
  "Operations that give the Runner tags (only legal when their conditions hold)."
  [env]
  (when (>= (credits env) 3)
    (act-where env #(and (= :play (:type %)) (re-find #"(?i)give the runner \d+ tag|gains? \d+ tag" (card-text (card-title %)))))))

(defn dud-op?
  "Operations whose effect is empty in state s (full state or observation), from card text:
  tag-scaled effects with no tags, Archives effects with an empty Archives, advancement placers
  with no installed agenda, credit loss the Runner cannot pay, punishment for steals that did
  not happen (dev review 6: Psychographics at 0 tags, Preemptive Action, Audacity...)."
  [s title]
  (let [txt (card-text title)
        tags (+ (or (get-in s [:runner :tag :base]) 0) (or (get-in s [:runner :tag :additional]) 0))
        agenda-installed (some #(= "Agenda" (:type %)) (for [[k v] (get-in s [:corp :servers])
                                                             :when (str/starts-with? (name k) "remote")
                                                             c (:content v)] c))]
    (boolean
     (or (and (re-find #"(?i)number of tags the Runner has|for each tag" txt) (zero? tags))
         (and (re-find #"(?i)cards? from Archives" txt) (empty? (get-in s [:corp :discard])))
         (and (re-find #"(?i)advancement (?:counters|tokens) on" txt) (not agenda-installed))
         (and (re-find #"(?i)the Runner stole during their last turn" txt) (zero? (or (get-in s [:runner :agenda-point]) 0)))
         (when-let [[_ n] (re-find #"(?i)If the Runner has at least (\d+)\[credit\]" txt)]
           (< (or (get-in s [:runner :credit]) 0) (parse-long n)))))))

(defn c-other-op
  "Any other affordable operation that is not pure economy (cheap generic use of the deck)."
  [env]
  (when (>= (credits env) 6)
    (act-where env #(and (= :play (:type %)) (not (pos? (cards/econ-gain (card-title %))))
                         (not (place-advancements (card-title %)))
                         (not (dud-op? (:obs env) (card-title %)))
                         (<= (cards/play-cost (card-title %)) (- (credits env) 3))))))

(def corp-turn-rules
  [[:kill c-kill]
   [:score c-score]
   [:advance-to-score c-advance-to-score]
   [:seamless c-seamless]
   [:protect-centrals c-protect-centrals]
   [:react-centrals c-react-centrals]
   [:dig-ice c-dig-ice]
   [:install-agenda c-install-agenda]
   [:advance-for-next-turn c-advance-for-next-turn]
   [:build-scoring-remote c-build-scoring-remote]
   [:econ-asset-ability c-econ-asset-ability]
   [:play-econ c-play-econ]
   [:ice-scoring-remote c-ice-scoring-remote]
   [:install-econ-asset c-install-econ-asset]
   [:rez-econ c-rez-econ]
   [:more-ice c-more-ice]
   [:install-ambush c-install-ambush]
   [:bluff-advance c-bluff-advance]
   [:trash-resource c-trash-resource]
   [:tag-op c-tag-op]
   [:other-op c-other-op]
   [:draw c-draw]
   [:unclog c-unclog]
   [:rich-draw #(rich-draw % :corp)]
   [:credit c-credit]])

(defn corp-run-decision [env]
  (let [obs (:obs env)
        run (:run obs)
        target (first (:server run))]
    (or
     ;; rez the approached ice when affordable; on an empty, valueless remote keep a reserve
     (act-where env #(and (= :rez (:type %)) (= "ICE" (ptype (card-title %)))
                          (or (#{:hq :rd} target)
                              (seq (srv/content obs target))
                              (>= (- (credits env) (cards/play-cost (card-title %))) (w env :corp-rez-reserve)))))
     ;; rez upgrades in the attacked server just before the Runner approaches it
     (when (and (= :movement (:phase run)) (zero? (:position run 0)))
       (act-where env #(and (= :rez (:type %)) (= "Upgrade" (ptype (card-title %)))
                            (= target (second (get-in % [:args :card :zone]))))))
     (act env :fire)
     (act env :continue))))

(defn corp-end-turn [env]
  (or (c-score env) (c-rez-econ env) (act env :end-turn)))

;;; Prompts (shared and per side)

(defn hand-card-value
  "How much a player wants to keep a card in hand (higher = keep)."
  [env c]
  (let [t (:title c) typ (or (:type c) (ptype t))]
    (case typ
      "Agenda" 10
      "ICE" (+ 4 (ice-score t))
      "Operation" (if (pos? (cards/econ-gain t)) (if (<= (cards/play-cost t) (credits env)) 6 3) 2)
      "Program" (if (cards/icebreaker? t)
                  (if (some #(= t (:title %)) (get-in (:obs env) [:runner :rig :program])) 1 8)
                  3)
      "Event" (cond (pos? (cards/econ-gain t)) 5 (re-find #"(?i)\brun\b" (str (:text (cards/printed t)))) 4 :else 3)
      3)))

(defn select-best [env score]
  (let [sels (acts env :select)
        best (when (seq sels) (apply max-key #(score (get-in % [:args :card])) sels))]
    (if (and best (pos? (score (get-in best [:args :card])))) best (act env :done))))

(defn prompt-common [env]
  (let [p (prompt env)
        msg (str (:msg p))
        obs (:obs env)]
    (cond
      (= :mulligan (:prompt-type p))
      (let [hand (get-in obs [(:side env) :hand])
            good (if (= :corp (:side env))
                   (some #(= "ICE" (:type %)) hand)
                   (some #(or (cards/icebreaker? (:title %)) (pos? (cards/econ-gain (:title %)))) hand))]
        (choice env (if good #"^Keep" #"^Mulligan")))

      (re-find #"(?i)^Discard down to" msg)
      (let [sels (acts env :select)]
        (when (seq sels) (apply min-key #(hand-card-value env (get-in % [:args :card])) sels)))

      (re-find #"(?i)will now be trashed|^OK$" msg) (choice env #"OK")
      (re-find #"(?i)choose a trigger to resolve" msg) (first (remove #(= :done (:type %)) (:actions env))))))

(defn lethal-damage-choice
  "A mode choice that flatlines the Runner (\"Do X net damage\", X from tags up to 3), if any."
  [env]
  (let [obs (:obs env)
        grip (count (get-in obs [:runner :hand]))
        tags (+ (get-in obs [:runner :tag :base] 0) (get-in obs [:runner :tag :additional] 0))]
    (first (for [a (acts env :choice)
                 :let [l (str (:label a))
                       [_ n] (re-find #"(?i)do (\d+|X) (?:net|meat|core|brain) damage" l)
                       cap (or (some-> (re-find #"(?i)up to (\d+)" l) second parse-long) 99)
                       per-tag (re-find #"(?i)per tag|for each tag|number of tags" l)
                       base (cond (nil? n) nil (re-find #"\d" n) (parse-long n) :else 1)
                       dmg (when base (if per-tag (* base (min tags cap)) base))]
                 :when (and dmg (> dmg grip))]
             a))))

(defn corp-prompt [env]
  (let [p (prompt env)
        msg (str (:msg p))
        src (str (:title (:card p)))
        obs (:obs env)]
    (or
     (prompt-common env)
     (lethal-damage-choice env)
     (cond
       (re-find #"(?i)advancement counters on" msg)
       (select-best env (fn [c] (let [c (find-card obs (:cid c))]
                                  (if (and c (= "Agenda" (:type c))) (- 10 (adv-need c)) 0))))
       (re-find #"(?i)ice to install" msg)
       (select-best env (fn [c] (if (= "ICE" (:type c)) (ice-score (:title c)) 0)))
       (re-find #"(?i)rez 1 installed piece of ice, ignoring all costs" (str (:text (cards/printed src))))
       (select-best env (fn [c] (if (= "ICE" (:type c)) (cards/play-cost (:title c)) 0)))
       (re-find #"(?i)ability\?|draw \d+ cards\?" msg) (choice env #"^Yes")
       :else nil)
     (first (remove #(re-find #"(?i)cancel" (str (:label %))) (:actions env)))
     (first (:actions env)))))

;;; Runner

(defn run-opts [env]
  (let [corp-ap (get-in (:obs env) [:corp :agenda-point] 0)
        ;; urgency: steals matter more as the Corp nears 7 points
        urgency (+ 1.0 (* (or (w env :run-urgency) 0.0) (max 0 (- corp-ap 3))))]
    {:corp-decklist (corp-decklist env)
     :ap-value (* urgency (or (w env :run-ap-value) (w env :ap-value)))
     :w-damage (w env :w-damage)
     :hand (count (get-in (:obs env) [:runner :hand]))
     :remote-ice-prior (w env :remote-ice-prior)
     :w-program (w env :w-program)}))

(declare server-run-eval)

(defn server-run-utility
  "Expected utility of running server k now, with optional event modifiers."
  [env k opts]
  (- (:u (server-run-eval env k opts)) (w env :click-value)))

(defn server-run-eval
  "Run-calculator result (:u, :p success probability, :how) for running server k now."
  [env k {:keys [credits-bonus rez-bonus extra-access mode hand-delta replacement-value] :or {credits-bonus 0 rez-bonus 0 extra-access 0 hand-delta 0}}]
  (let [obs (:obs env)
        opts (update (run-opts env) :hand + hand-delta)
        opts (assoc opts :extra-access (+ extra-access (srv/extra-accesses obs k)))
        ;; a server already run unsuccessfully this turn: the same wall stops a re-run
        failed (some #{k} (get-in obs [:runner :register :unsuccessful-run]))
        value (cond failed 0.0 replacement-value replacement-value :else (srv/content-value obs k opts))
        ev (srv/runner-run-eval obs k (assoc opts :value value :credits-bonus credits-bonus :rez-bonus rez-bonus :mode mode
                                             :replacement (some? replacement-value)))]
    ev))

(defn runnable [env]
  (for [a (acts env :run)] [(srv/server-key (get-in a [:args :server])) a]))

(defn event-run-options
  "Run events in hand, as [utility action server-key]."
  [env]
  (let [obs (:obs env)
        servers (map first (runnable env))]
    (for [a (acts env :play)
          :let [t (card-title a) txt (str (:text (cards/printed t)))]
          :when (re-find #"(?i)\brun\b" txt)
          :let [hq-rd-only (re-find #"(?i)run HQ or R&D" txt)
                cost (cards/play-cost t)
                bonus-cr (or (some-> (re-find #"(?i)place (\d+)\[credit\] on this event" txt) second parse-long) 0)
                rez-bonus (or (some-> (re-find #"(?i)rez cost of each piece of ice is increased by (\d+)" txt) second parse-long) 0)
                extra (if (re-find #"(?i)access 1 additional card" txt) 1 0)
                draw (if (re-find #"(?i)draw 1 card" txt) 0.5 0)
                ;; core damage after the run (Stimhack) permanently costs a card and hand size
                core (* 4.0 (or (some-> (re-find #"(?i)suffer (\d+) core damage" txt) second parse-long) 0))
                ;; Account Siphon: "instead of breaching, ... lose up to N, you gain M for each credit lost and take T tags"
                siphon (when-let [[_ n m] (re-find #"(?i)instead of breaching .*lose up to (\d+)\[credit\], then you gain (\d+)\[credit\] for each credit lost" txt)]
                         (let [lost (min (parse-long n) (get-in obs [:corp :credit] 0))
                               tags (or (some-> (re-find #"(?i)take (\d+) tags" txt) second parse-long) 0)]
                           (- (+ (* (parse-long m) lost) lost) (* 2.5 tags))))]
          k servers
          :when (or (not hq-rd-only) (#{:hq :rd} k))]
      [(+ draw (- core) (server-run-utility env k (cond-> {:credits-bonus (- bonus-cr cost) :rez-bonus rez-bonus :extra-access extra :hand-delta -1}
                                            siphon (assoc :replacement-value siphon)))) a k])))

(defn ability-run-options
  "Click abilities that make a run on a central (e.g. Red Team), as [utility action server-key]."
  [env]
  (let [obs (:obs env)]
    (for [a (acts env :click-ability)
          :when (label-is a #"(?i)run on a central")
          :let [c (some #(when (= (get-in a [:args :card :cid]) (:cid %)) %) (srv/runner-installed obs))
                payout (min 3 (get-in c [:counter :credit] 0))
                ran (set (get-in obs [:runner :register :made-run]))]
          k [:hq :rd :archives]
          :when (not (ran k))]
      [(+ payout (server-run-utility env k {})) a k])))

(defn r-run [env]
  (let [obs (:obs env)
        plain (for [[k a] (runnable env)] [(server-run-utility env k {}) a k])
        all (concat plain (event-run-options env) (ability-run-options env))
        [u a k] (when (seq all) (apply max-key first all))]
    (when (and a (> u (w env :min-run-utility)))
      (swap! (:mem env) assoc :run-target k)
      a)))

(defn have-breaker-for [obs subtype]
  (some #(contains? (cards/breaker-types (:title %)) subtype) (get-in obs [:runner :rig :program])))

(defn r-install-breaker [env]
  (let [obs (:obs env)
        needed (set (for [[_ srv] (srv/servers obs) c (:ices srv)
                          :when (not (:hidden c))
                          st (:subtypes (cards/printed (:title c)))
                          :when (#{"Barrier" "Code Gate" "Sentry"} st)]
                      st))
        all-types #{"Barrier" "Code Gate" "Sentry"}
        missing (remove #(have-breaker-for obs %) all-types)
        score (fn [a] (let [types (cards/breaker-types (card-title a))
                            fixes (filter (set missing) types)]
                        (+ (* 3 (count (filter needed fixes))) (count fixes)
                           (if (= #{"Barrier" "Code Gate" "Sentry"} types) -1.5 0))))]
    (when (seq missing)
      (let [cands (filter #(and (cards/icebreaker? (card-title %)) (pos? (score %))) (acts env :install))]
        (when (seq cands)
          (let [best (apply max-key score cands)]
            (when (<= (+ (cards/play-cost (card-title best)) 0) (credits env)) best)))))))

(defn r-dig-breakers
  "With :dig-breakers: when rezzed Corp ice has types the rig cannot break and the grip holds no
  breaker for them, dig (a draw event if affordable, else a basic draw) while the stack has cards."
  [env]
  (when (w env :dig-breakers)
    (let [obs (:obs env)
          rezzed-types (set (for [[_ srv] (srv/servers obs) c (:ices srv)
                                  :when (:rezzed c)
                                  st (:subtypes (cards/printed (:title c)))
                                  :when (#{"Barrier" "Code Gate" "Sentry"} st)]
                              st))
          missing (remove #(have-breaker-for obs %) rezzed-types)
          grip-types (set (for [c (get-in obs [:runner :hand]) :when (cards/icebreaker? (:title c))
                                t (cards/breaker-types (:title c))]
                            t))
          need (remove grip-types missing)
          hand (count (get-in obs [:runner :hand]))
          mx (or (get-in obs [:runner :hand-size :total]) 5)]
      (when (and (seq need) (seq (get-in obs [:runner :deck])))
        (or ;; a tutor (Self-modifying Code) fetches the breaker directly
            (when (>= (credits env) 5)
              (act-where env #(and (#{:ability :click-ability} (:type %))
                                   (re-find #"(?i)search your stack for (?:1|a) program" (str (:label %))))))
            (when (< hand mx)
              (or (act-where env #(and (= :play (:type %))
                                 (re-find #"(?i)draw \d+ cards" (str (:text (cards/printed (card-title %)))))
                                 (<= (cards/play-cost (card-title %)) (credits env))))
                  (act env :draw))))))))

(defn missing-breaker-types
  "Ice types among rezzed Corp ice that the Runner's installed breakers cannot break."
  [obs]
  (let [rezzed-types (set (for [[_ srv] (srv/servers obs) c (:ices srv)
                                :when (:rezzed c)
                                st (:subtypes (cards/printed (:title c)))
                                :when (#{"Barrier" "Code Gate" "Sentry"} st)]
                            st))]
    (set (remove #(have-breaker-for obs %) rezzed-types))))

(defn r-econ [env]
  (let [obs (:obs env)
        cr (credits env)
        cl (clicks env)]
    (or
     (best-econ-ability env)
     (when (< cr (w env :runner-econ-cap)) (act-where env #(and (= :play (:type %)) (pos? (cards/econ-gain (card-title %)))
                                          (not (re-find #"(?i)if you have any \[click\] remaining" (str (:text (cards/printed (card-title %))))))
                                          (>= cr (cards/play-cost (card-title %))))))
     (when (or (and (< cr 10) (= 1 cl)) (< cr 4))
       (act-where env #(and (= :play (:type %)) (pos? (cards/econ-gain (card-title %))))))
     (when (< cr 12) (act-where env #(and (= :install (:type %)) (cards/load-credits (card-title %))
                                          (> (cards/load-credits (card-title %)) (cards/play-cost (card-title %)))))))))

(defn utility-permanent?
  "Non-breaker installables whose text gives a steady edge: extra accesses, credits on
  successful runs, or extra draws."
  [title]
  (let [t (str (:text (cards/printed title)))]
    (and (not (cards/icebreaker? title))
         (re-find #"(?i)access (?:1|an) additional card|whenever you make a successful run, (?:gain|place) \d+\[credit\]|instead draw 2 cards" t))))

(defn r-install-other [env]
  (act-where env #(and (= :install (:type %))
                       (utility-permanent? (card-title %))
                       (>= (credits env) (+ 2 (cards/play-cost (card-title %)))))))

(defn r-draw [env]
  (let [obs (:obs env)
        hand (count (get-in obs [:runner :hand]))]
    (when (or (< hand (w env :runner-draw-below)) (and (< hand 5) (>= (credits env) 6)))
      (or (when (= 1 (clicks env)) (act-where env #(and (= :play (:type %)) (re-find #"(?i)draw \d+ cards" (str (:text (cards/printed (card-title %))))))))
          (act env :draw)))))

(defn r-safety-draw
  "Keep at least 3 cards while the Corp may still have damage."
  [env]
  (let [obs (:obs env)]
    (when (and (< (count (get-in obs [:runner :hand])) 3) (> (clicks env) 1))
      (act env :draw))))

(defn r-remove-tag
  "Clear tags while the Corp could punish them: its decklist does meat damage (always clear), or
  the Runner has resources to lose (clear if it keeps at least 3 credits)."
  [env]
  (let [obs (:obs env)
        kill-deck (some #(or (meat-damage %)
                             (re-find #"(?i)damage (?:for each|per) tag|tagged[^.]*damage" (str (:text (cards/printed %)))))
                        (keys (corp-decklist env)))
        resources (seq (get-in obs [:runner :rig :resource]))]
    (when (and (runner-tagged? obs) (act env :remove-tag)
               (or kill-deck
                   (and resources (>= (credits env) 5))))
      (act env :remove-tag))))

(defn r-install-generic
  "Install other affordable hardware/programs/resources while keeping a small reserve."
  [env]
  (when (w env :runner-install-generic)
  (act-where env #(and (= :install (:type %)) (not (cards/icebreaker? (card-title %)))
                       (<= (cards/play-cost (card-title %)) (- (credits env) 3))))))

(def runner-turn-rules
  [[:remove-tag r-remove-tag]
   [:safety-draw r-safety-draw]
   [:econ-critical (fn [env] (when (< (credits env) (w env :runner-econ-floor)) (r-econ env)))]
   [:install-breaker r-install-breaker]
   [:dig-breakers r-dig-breakers]
   [:run r-run]
   [:econ r-econ]
   [:install-other r-install-other]
   [:install-generic r-install-generic]
   [:draw r-draw]
   [:rich-draw #(rich-draw % :runner)]
   [:credit (fn [env] (act env :credit))]])

(defn current-position-ices
  "Ice from the current position inward (state order: innermost first)."
  [obs]
  (let [run (:run obs)
        k (first (:server run))
        ices (srv/ices obs k)
        pos (:position run 0)]
    [k (subvec (vec ices) 0 (min pos (count ices)))]))

(defn- breach-opts
  "Run options for valuing the breach of k mid-run, including installed extra accesses
  (R&D Interface): the same value the run was started on."
  [env k]
  (assoc (run-opts env) :extra-access (srv/extra-accesses (:obs env) k)))

(defn runner-encounter [env]
  (let [obs (:obs env)
        run (:run obs)
        [k ices] (current-position-ices obs)
        value (srv/content-value obs k (breach-opts env k))
        ev (srv/runner-run-eval (assoc-in obs [:corp :servers k :ices] ices) k (assoc (breach-opts env k) :value value))
        how (:how ev)
        breaks (acts env :break)]
    (cond
      (= :break (first how))
      (or (act-where env #(and (= :break (:type %)) (= (second how) (card-title %))))
          (first breaks)
          (act env :continue))
      :else (act env :continue))))

(defn runner-run-decision [env]
  (let [obs (:obs env)
        run (:run obs)]
    (or
     (when (and (= :movement (:phase run)) (act env :jack-out))
       (let [[k ices] (current-position-ices obs)
             value (srv/content-value obs k (breach-opts env k))
             ev (srv/runner-run-eval (assoc-in obs [:corp :servers k :ices] ices) k (assoc (breach-opts env k) :value value))]
         (when (neg? (:u ev)) (act env :jack-out))))
     (act env :continue))))

(defn trash-worth? [env c-title cost]
  (let [obs (:obs env)
        c (some #(when (= c-title (:title %)) %) (srv/all-corp-installed obs))]
    (srv/worth-trashing? obs (or c {:title c-title}) cost)))

(defn runner-prompt [env]
  (let [p (prompt env)
        msg (str (:msg p))
        src (str (:title (:card p)))
        obs (:obs env)]
    (or
     (prompt-common env)
     (cond
       (choice env #"^Steal$") (choice env #"^Steal$")
       ;; additional steal costs (pay credits, trash a program, ...): stealing is almost always right,
       ;; unless the cost is net damage that would flatline (Obokata Protocol)
       (choice env #"(?i)^pay to steal")
       (let [d (srv/steal-damage src)]
         (if (and d (>= d (count (get-in obs [:runner :hand]))))
           (or (choice env #"(?i)^No action") (choice env #"(?i)^pay to steal"))
           (choice env #"(?i)^pay to steal")))
       (re-find #"(?i)^You accessed" msg)
       (let [tr (choice env #"(?i)to trash")
             cost (some-> (re-find #"Pay (\d+)" (str (:label tr))) second parse-long)]
         (if (and tr cost (trash-worth? env src cost)) tr (or (choice env #"(?i)^No action") (first (:actions env)))))

       (re-find #"(?i)^Choose a server" msg)
       (let [target (:run-target @(:mem env))]
         (or (when target (choice env (re-pattern (str "^" (java.util.regex.Pattern/quote (srv/server-name target)) "$"))))
             (let [cs (remove #(re-find #"(?i)cancel" (:label %)) (acts env :choice))]
               (when (seq cs) (apply max-key #(server-run-utility env (srv/server-key (:label %)) {}) cs)))))

       ;; heap breakers (Paperclip, Black Orchestra): only if install + breaking this ice is affordable
       (re-find #"(?i)^Install (.+) from the heap\?" msg)
       (let [t (second (re-find #"(?i)^Install (.+) from the heap\?" msg))
             [_ ices] (current-position-ices obs)
             ice (last ices)
             m (cards/breaker-model {:title t} (inc (count (srv/icebreakers obs))))
             bc (when (and m ice (not (:hidden ice))) (cards/break-cost m (cards/ice-model ice)))]
         (choice env (if (and bc (<= (+ (cards/play-cost t) bc) (credits env))) #"(?i)^Yes" #"(?i)^No")))

       ;; tutors (Self-modifying Code): fetch a breaker for a type the rig cannot break
       (re-find #"(?i)choose a program|search your stack|program to install" msg)
       (let [missing (missing-breaker-types obs)
             score (fn [t] (let [types (cards/breaker-types t)]
                             (cond (nil? t) 0
                                   (some missing types) (+ 10 (count (filter missing types)))
                                   (cards/icebreaker? t) 2
                                   (= "Program" (ptype t)) 1
                                   :else 0)))]
         (cond
           (seq (acts env :select)) (select-best env #(score (:title %)))
           (seq (acts env :choice)) (let [cs (acts env :choice)
                                          best (apply max-key #(score (str (:label %))) cs)]
                                      (when (pos? (score (str (:label best)))) best))
           :else nil))

       ;; breach replacements: breach unless the replacement is worth more (an event played for it,
       ;; or a resource paying more credits than the breach is worth)
       (re-find #"(?i)^Choose a breach replacement ability" msg)
       (let [k (first (get-in obs [:run :server]))
             bv (if k (srv/content-value obs k (breach-opts env k)) 0.0)
             breach (choice env #"(?i)^Breach ")
             repl-value (fn [a] (let [t (str/replace (str (:label a)) #"\s*\[[^\]]*\]$" "")
                                      c (some #(when (= t (:title %)) %) (srv/runner-installed obs))]
                                  (cond (= "Event" (ptype t)) 100.0
                                        c (double (get-in c [:counter :credit] 0))
                                        :else 1.0)))
             best (when (seq (acts env :choice))
                    (apply max-key repl-value (remove #(= breach %) (acts env :choice))))]
         (if (and breach (or (nil? best) (>= bv (repl-value best)))) breach (or best breach)))

       ;; take everything a replacement offers (Bank Job), never 0
       (re-find #"(?i)^How many hosted credits do you want to take" msg)
       (let [ns (acts env :number)] (when (seq ns) (apply max-key #(or (parse-long (str (:label %))) 0) ns)))

       ;; X install costs of breakers that interface only with ice of exactly equal strength (Atman):
       ;; match the strength of rezzed Corp ice it can afford
       (and (re-find #"(?i)^How many credits do you want to spend" msg)
            (re-find #"(?i)exactly equal strength" (str (:text (cards/printed src)))))
       (let [ns (acts env :number)
             strengths (frequencies (for [c (srv/all-corp-installed obs) :when (and (:rezzed c) (= "ICE" (:type c)))]
                                      (or (:current-strength c) (:strength (cards/printed (:title c))) 0)))
             cr (credits env)
             target (or (some->> strengths (filter #(<= (key %) cr)) seq (apply max-key val) key)
                        (min cr 4))
             val-of #(or (parse-long (str (:label %))) 0)]
         (when (seq ns) (apply min-key #(Math/abs (- (val-of %) target)) ns)))

       (re-find #"(?i)Jack out\?" msg)
       (choice env (if (<= (count (get-in obs [:runner :hand])) 2) #"^Yes" #"^No"))

       (and (choice env #"(?i)^Pay") (choice env #"(?i)^End the run"))
       (let [toll (or (some-> (re-find #"(\d+)" (str (:label (choice env #"(?i)^Pay")))) second parse-long) 0)]
         (or (when (>= (credits env) (+ toll 2)) (choice env #"(?i)^Pay"))
             (when (>= (clicks env) 2) (choice env #"(?i)^Spend"))
             (choice env #"(?i)^End the run")))

       (re-find #"(?i)Insufficient MU" msg)
       (select-best env (fn [c] (cond (re-find #"(?i)trash this program" (str (:text (cards/printed (:title c))))) 10
                                      (cards/icebreaker? (:title c)) 1
                                      :else 2)))

       (re-find #"(?i)credit providing card" msg)
       (or (first (acts env :select)) (act env :done))

       ;; "Everything else" first: the engine can keep offering an already-accessed upgrade (livelock)
       (re-find #"(?i)choose a card to access|click a card to access" msg)
       (or (choice env #"(?i)^Everything else") (first (remove #(= :done (:type %)) (:actions env))))
       :else nil)
     (first (remove #(re-find #"(?i)cancel" (str (:label %))) (:actions env)))
     (first (:actions env)))))

;;; Agent

(defn decide [env]
  (let [{:keys [side decision]} env
        kind (:kind decision)
        rules (cond
                (= kind :prompt) [[:prompt (if (= side :corp) corp-prompt runner-prompt)]]
                (= kind :turn) (if (= side :corp) corp-turn-rules runner-turn-rules)
                (= kind :run) [[:run (if (= side :corp) corp-run-decision runner-run-decision)]]
                (= kind :encounter) [[:encounter (if (= side :corp) corp-run-decision runner-encounter)]]
                (= kind :end-turn) [[:end-turn (if (= side :corp) corp-end-turn #(act % :end-turn))]]
                :else [])]
    (or (some (fn [[nm rule]] (when-let [a (rule env)] [nm a])) rules)
        [:fallback (first (:actions env))])))

(defrecord Heuristic [side weights mem trace]
  h/Agent
  (choose [_ ctx]
    (let [env (assoc ctx :obs @(:obs ctx) :weights weights :mem mem)
          [nm a] (decide env)
          idx (or (first (keep-indexed (fn [i x] (when (identical? x a) i)) (:actions ctx))) 0)]
      (reset! trace nm)
      idx)))

(defn make
  ([] (make {}))
  ([{:keys [side weights]}]
   (->Heuristic side (merge default-weights weights) (atom {}) (atom nil))))
