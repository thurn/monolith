(ns monolith.ai.knowledge.vfeat
  "Dense, card-agnostic position features for the learned value model (R4). Corp perspective,
  computed from a full or determinized state through the knowledge modules (card text and
  engine card-defs), never card names, so the model can transfer to decks it was not fit on."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [monolith.ai.knowledge.cards :as cards]
   [monolith.ai.knowledge.evaluator :as ev]
   [monolith.ai.knowledge.servers :as srv]))

(def default-weights (edn/read-string (slurp (io/resource "monolith/ai/knowledge/weights.edn"))))

(defn- cnt [x] (double (count x)))
(defn- clip [x lo hi] (max lo (min hi (double x))))
(defn- agenda? [c] (= "Agenda" (:type c)))
(defn- ap-sum [cs] (double (reduce + 0 (map #(srv/ap (:title %)) (filter agenda? cs)))))

(defn- remaining [c]
  (let [req (or (:current-advancement-requirement c) (:advancementcost (cards/printed (:title c))) 5)]
    (max 0 (- req (or (:advance-counter c) 0)))))

(defn- safety-u
  "Runner's best run utility on server k worth value (Corp's knowledge, current rig, +2 credits)."
  [s k value]
  (clip (:u (srv/corp-server-safety s k value 2)) -30 30))

(def names
  [:ap-corp :ap-runner :ap-diff :corp-near-win :runner-near-win
   :corp-credits :corp-credits-log :runner-credits :runner-credits-log
   :corp-clicks :runner-clicks :corp-active :turn
   :corp-hand :runner-grip :runner-grip-small :corp-deck :runner-deck :corp-deck-low
   :hq-agendas :hq-ap :hq-ap-frac :rd-density :rd-ap :archives-ap-hidden :archives-ap
   :inst-agendas :inst-ap :inst-remaining :inst-in-reach-ap :inst-u-max :inst-u-sum :inst-safe-ap
   :remotes :max-remote-ice :max-agenda-remote-ice :empty-remote-max-ice :ice-total :ice-rezzed :ice-unrezzed
   :hq-ice :rd-ice :archives-ice :hq-u :rd-u
   :breakers :cov-barrier :cov-codegate :cov-sentry :cov-all :programs :hardware :resources
   :tags :bad-pub :assets-rezzed :remote-unrezzed :corp-hosted :runner-hosted
   :corp-hand-ice :corp-hand-ops :corp-hand-assets :grip-breakers :grip-events
   :linear-eval])

(defn features
  "Vector of doubles in the order of `names`."
  [s]
  (let [w default-weights
        apv (:ap-value w)
        corp (:corp s) runner (:runner s)
        ap-c (double (or (:agenda-point corp) 0))
        ap-r (double (or (:agenda-point runner) 0))
        cc (double (or (:credit corp) 0)) rc (double (or (:credit runner) 0))
        hand (:hand corp) deck (:deck corp) grip (:hand runner)
        remotes (srv/remotes s)
        inst (for [[k _] remotes c (srv/content s k) :when (agenda? c)] [k c])
        inst-u (for [[k c] inst] (safety-u s k (* apv (srv/ap (:title c)))))
        corp-active (= :corp (:active-player s))
        in-reach (for [[_ c] inst
                       :let [r (remaining c)]
                       :when (and corp-active (<= r (or (:click corp) 0)) (<= r cc))]
                   c)
        ices-of (fn [k] (srv/ices s k))
        all-ice (for [[_ v] (srv/servers s) c (:ices v)] c)
        rig (:rig runner)
        bs (srv/icebreakers s)
        covers (fn [t] (if (some #(cards/can-break-type? % {:subtypes #{t}}) bs) 1.0 0.0))
        hq-v (if (seq hand) (* apv (/ (ap-sum hand) (cnt hand))) 0.0)
        rd-v (if (seq deck) (* apv (/ (ap-sum deck) (cnt deck))) 0.0)
        discard (:discard corp)
        corp-installed (srv/all-corp-installed s)
        typ #(or (:type %) (cards/ctype (:title %)))
        tags (+ (get-in runner [:tag :base] 0) (get-in runner [:tag :additional] 0))]
    [ap-c ap-r (- ap-c ap-r) (if (>= ap-c 5) 1.0 0.0) (if (>= ap-r 5) 1.0 0.0)
     (/ (min cc 40.0) 10.0) (Math/log1p cc) (/ (min rc 40.0) 10.0) (Math/log1p rc)
     (double (or (:click corp) 0)) (double (or (:click runner) 0)) (if corp-active 1.0 0.0) (/ (double (or (:turn s) 0)) 10.0)
     (cnt hand) (cnt grip) (if (<= (count grip) 2) 1.0 0.0) (/ (cnt deck) 10.0) (/ (cnt (:deck runner)) 10.0) (if (<= (count deck) 5) 1.0 0.0)
     (cnt (filter agenda? hand)) (ap-sum hand) (if (seq hand) (/ (ap-sum hand) (cnt hand)) 0.0)
     (if (seq deck) (/ (ap-sum deck) (cnt deck)) 0.0) (ap-sum deck)
     (ap-sum (remove :seen discard)) (ap-sum discard)
     (cnt inst) (ap-sum (map second inst)) (double (reduce + 0 (map (comp remaining second) inst)))
     (ap-sum in-reach) (if (seq inst-u) (reduce max inst-u) -30.0) (reduce + 0.0 inst-u)
     (reduce + 0.0 (for [[[_ c] u] (map vector inst inst-u) :when (<= u 0.0)] (double (srv/ap (:title c)))))
     (cnt remotes)
     (reduce max 0.0 (for [[k _] remotes] (cnt (ices-of k))))
     (reduce max 0.0 (for [[k _] inst] (cnt (ices-of k))))
     (reduce max 0.0 (for [[k _] remotes :when (empty? (srv/content s k))] (cnt (ices-of k))))
     (cnt all-ice) (cnt (filter :rezzed all-ice)) (cnt (remove :rezzed all-ice))
     (cnt (ices-of :hq)) (cnt (ices-of :rd)) (cnt (ices-of :archives))
     (if (pos? hq-v) (safety-u s :hq hq-v) 0.0) (if (pos? rd-v) (safety-u s :rd rd-v) 0.0)
     (cnt bs) (covers "Barrier") (covers "Code Gate") (covers "Sentry") (if (some #(= :all (:breaks %)) bs) 1.0 0.0)
     (cnt (:program rig)) (cnt (:hardware rig)) (cnt (:resource rig))
     (double tags) (double (+ (get-in corp [:bad-publicity :base] 0) (get-in corp [:bad-publicity :additional] 0)))
     (cnt (filter #(and (:rezzed %) (= "Asset" (typ %))) corp-installed))
     (cnt (for [[k _] remotes c (srv/content s k) :when (and (not (:rezzed c)) (not (agenda? c)))] c))
     (/ (double (reduce + 0 (for [c corp-installed] (get-in c [:counter :credit] 0)))) 10.0)
     (/ (double (reduce + 0 (for [c (concat (:program rig) (:hardware rig) (:resource rig))] (get-in c [:counter :credit] 0)))) 10.0)
     (cnt (filter #(= "ICE" (typ %)) hand)) (cnt (filter #(= "Operation" (typ %)) hand)) (cnt (filter #(= "Asset" (typ %)) hand))
     (cnt (filter #(cards/icebreaker? (:title %)) grip)) (cnt (filter #(= "Event" (typ %)) grip))
     (/ (ev/evaluate s w) 20.0)]))
