(ns monolith.ai.features
  "Shared featurizer for S4 and S5: a sparse state vector from one side's observation and a
  sparse vector per legal action. Card identities are one-hot over a fixed title vocabulary
  (every card in the bundled deck pool), so v1 nets are tied to that pool."
  (:require
   [clojure.string :as str]
   [monolith.ai.engine :as engine]
   [monolith.ai.knowledge.servers :as srv]))

(def vocab
  (let [titles (sort (distinct (for [[_ d] engine/base-decks t (cons (get-in d [:identity :title]) (map :card (:cards d)))] t)))]
    (into {} (map-indexed (fn [i t] [t i]) titles))))

(def V (count vocab))

(def server-slots [:hq :rd :archives :remote1 :remote2 :remote3 :remote4 :remote5 :remote6])
(def server-index (into {} (map-indexed (fn [i k] [k i]) server-slots)))

(def kinds [:prompt :encounter :run :phase-12 :start-turn :turn :end-turn])
(def prompt-types [:select :mulligan :other :trace :psi :waiting :run nil])
(def phases [:initiation :approach-ice :encounter-ice :movement :success nil])
(def action-types [:credit :draw :play :install :advance :score :trash-resource :purge :rez :click-ability :ability
                   :run :remove-tag :break :continue :jack-out :fire :pass :start-turn :end-turn
                   :select :done :number :card-title :choice])

;;; State layout (offsets into one sparse vector)

(def n-scalars 40)
(def zone-blocks [:my-hand :rig :corp-ice-known :corp-content-known :corp-discard :runner-discard :scored :unseen])
(def per-server 8)
(def state-dim (+ n-scalars (count kinds) (count prompt-types) (count phases) (count server-slots)
                  (* V (count zone-blocks)) (* per-server (count server-slots))))

(def off-kind n-scalars)
(def off-ptype (+ off-kind (count kinds)))
(def off-phase (+ off-ptype (count prompt-types)))
(def off-run-server (+ off-phase (count phases)))
(def off-zones (+ off-run-server (count server-slots)))
(def off-servers (+ off-zones (* V (count zone-blocks))))

(defn- zone-off [block] (+ off-zones (* V (.indexOf ^java.util.List zone-blocks block))))

(defn- add! [^java.util.HashMap m idx v]
  (when (and idx (not (zero? v)))
    (.put m (int idx) (+ (double (or (.get m (int idx)) 0.0)) (double v)))))

(defn- add-title! [m block title v]
  (when-let [i (vocab title)] (add! m (+ (zone-off block) i) v)))

(defn state-features
  "Sparse state vector {idx value} (a java.util.HashMap) for side, given its observation and decision."
  [obs side decision corp-decklist]
  (let [m (java.util.HashMap.)
        opp (if (= side :corp) :runner :corp)
        me (get obs side) them (get obs opp)
        run (:run obs)
        p (first (get me :prompt))]
    ;; scalars
    (add! m 0 (if (= side :runner) 1.0 0.0))
    (add! m 1 (/ (or (:turn obs) 0) 30.0))
    (add! m 2 (if (= side (:active-player obs)) 1.0 0.0))
    (add! m 3 (/ (or (:credit me) 0) 20.0))
    (add! m 4 (/ (or (:credit them) 0) 20.0))
    (add! m 5 (/ (or (:click me) 0) 4.0))
    (add! m 6 (/ (or (:click them) 0) 4.0))
    (add! m 7 (/ (or (:agenda-point me) 0) 7.0))
    (add! m 8 (/ (or (:agenda-point them) 0) 7.0))
    (add! m 9 (/ (count (:hand me)) 10.0))
    (add! m 10 (/ (count (:hand them)) 10.0))
    (add! m 11 (/ (count (:deck me)) 40.0))
    (add! m 12 (/ (count (:deck them)) 40.0))
    (add! m 13 (or (get-in obs [:runner :tag :base]) 0))
    (add! m 14 (/ (or (get-in obs [:corp :bad-publicity :base]) 0) 3.0))
    (add! m 15 (/ (or (get-in obs [:runner :memory :available]) 0) 4.0))
    (add! m 16 (/ (or (get-in obs [:runner :hand-size :total]) 5) 10.0))
    (add! m 17 (/ (or (get-in obs [:corp :hand-size :total]) 5) 10.0))
    (add! m 18 (if run 1.0 0.0))
    (add! m 19 (/ (or (:position run) 0) 5.0))
    (add! m 20 (if (seq (:encounters obs)) 1.0 0.0))
    (add! m 21 (/ (count (srv/remotes obs)) 6.0))
    (add! m 22 (/ (count (get-in obs [:corp :discard])) 20.0))
    (add! m 23 (/ (count (get-in obs [:runner :discard])) 20.0))
    (add! m 24 (/ (or (get-in obs [:runner :link]) 0) 3.0))
    (add! m 25 (if (:no-action run) 1.0 0.0))
    ;; one-hots
    (add! m (+ off-kind (.indexOf ^java.util.List kinds (:kind decision))) 1.0)
    (let [i (.indexOf ^java.util.List prompt-types (:prompt-type p))] (when (>= i 0) (add! m (+ off-ptype i) 1.0)))
    (let [i (.indexOf ^java.util.List phases (:phase run))] (when (>= i 0) (add! m (+ off-phase i) 1.0)))
    (when-let [i (server-index (first (:server run)))] (add! m (+ off-run-server i) 1.0))
    ;; zones
    (doseq [c (:hand me) :when (:title c)] (add-title! m :my-hand (:title c) 1.0))
    (let [rig (get-in obs [:runner :rig])]
      (doseq [c (concat (:program rig) (:hardware rig) (:resource rig)) :when (:title c)]
        (add-title! m :rig (:title c) 1.0)))
    (doseq [[k s] (srv/servers obs)
            :let [si (server-index k)]]
      (doseq [c (:ices s) :when (:title c)] (add-title! m :corp-ice-known (:title c) (if (:rezzed c) 1.0 0.5)))
      (doseq [c (:content s) :when (:title c)] (add-title! m :corp-content-known (:title c) (if (:rezzed c) 1.0 0.5)))
      (when si
        (let [b (+ off-servers (* per-server si))]
          (add! m b (/ (count (:ices s)) 4.0))
          (add! m (+ b 1) (/ (count (filter :rezzed (:ices s))) 4.0))
          (add! m (+ b 2) (/ (count (:content s)) 3.0))
          (add! m (+ b 3) (count (filter :hidden (:content s))))
          (add! m (+ b 4) (/ (reduce + 0 (map #(or (:advance-counter %) 0) (:content s))) 5.0))
          (add! m (+ b 5) (count (filter #(= "Agenda" (:type %)) (:content s))))
          (add! m (+ b 6) (count (filter #(and (:rezzed %) (= "Asset" (:type %))) (:content s))))
          (add! m (+ b 7) (/ (reduce + 0 (map #(or (:current-strength %) 0) (filter :rezzed (:ices s)))) 10.0)))))
    (doseq [c (get-in obs [:corp :discard]) :when (:title c)] (add-title! m :corp-discard (:title c) 1.0))
    (doseq [c (get-in obs [:runner :discard]) :when (:title c)] (add-title! m :runner-discard (:title c) 1.0))
    (doseq [c (concat (get-in obs [:corp :scored]) (get-in obs [:runner :scored])) :when (:title c)]
      (add-title! m :scored (:title c) (if (= side :corp) 1.0 1.0)))
    (when corp-decklist
      (doseq [[t q] (srv/unseen-pool obs corp-decklist)] (add-title! m :unseen t (/ q 3.0))))
    m))

;;; Actions

(def a-off-type 0)
(def a-off-title (count action-types))
(def a-off-server (+ a-off-title V))
(def n-a-servers 10)
(def a-off-num (+ a-off-server n-a-servers))
(def n-hash 32)
(def a-off-hash (+ a-off-num 2))
(def action-dim (+ a-off-hash n-hash))

(defn- server-slot [nm]
  (cond (nil? nm) nil
        (= nm "New remote") 9
        :else (let [k (try (srv/server-key nm) (catch Exception _ nil))] (server-index k))))

(defn action-features
  "Sparse vector {idx value} for one action map."
  [a]
  (let [m (java.util.HashMap.)
        t (.indexOf ^java.util.List action-types (:type a))
        title (or (get-in a [:args :card :title])
                  (second (re-find #"^(?:select |play |install |rez |advance |score |fire |break )?(.+?)(?: in .*| with .*)?$" (str (:label a)))))
        label (str (:label a))]
    (when (>= t 0) (add! m (+ a-off-type t) 1.0))
    (when-let [i (vocab (get-in a [:args :card :title]))] (add! m (+ a-off-title i) 1.0))
    (when (= :break (:type a))
      (when-let [i (vocab (second (re-find #" with (.+)$" label)))] (add! m (+ a-off-title i) 1.0)))
    (when-let [s (server-slot (or (get-in a [:args :server]) (when (#{:choice} (:type a)) label)))]
      (add! m (+ a-off-server s) 1.0))
    (when (= :number (:type a))
      (add! m a-off-num (/ (double (get-in a [:args :choice] 0)) 10.0)))
    (when (#{:choice :card-title} (:type a))
      (when-let [i (vocab label)] (add! m (+ a-off-title i) 1.0))
      (add! m (+ a-off-hash (mod (hash (str/replace label #"\d+" "#")) n-hash)) 1.0)
      (when-let [n (some-> (re-find #"(\d+)" label) second parse-long)] (add! m (inc a-off-num) (/ n 10.0))))
    m))

(defn dense ^floats [^java.util.HashMap m dim]
  (let [a (float-array dim)]
    (doseq [[k v] m] (aset a (int k) (float v)))
    a))
