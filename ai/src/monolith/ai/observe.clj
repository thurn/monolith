(ns monolith.ai.observe
  "Per-side redacted observation. Hidden cards become stubs that keep only public fields
  (cid, zone, side, counters, host); everything else an agent sees comes from here."
  (:require
   [game.core.card :refer [ice? rezzed? faceup?]]))

(def stub-keys [:cid :zone :side :advance-counter :extra-advance-counter :counter :installed :rezzed
                :host :hosted :new :index :facedown])

(defn stub [c] (-> (select-keys c stub-keys) (assoc :hidden true)))

(defn- corp-card-visible-to-runner? [c]
  (or (rezzed? c) (:seen c) (:monolith-known c) (and (= :discard (first (:zone c))) (not (:facedown c)) (:seen c))
      (#{:scored :current :play-area :rfg} (first (:zone c)))))

(defn- redact-corp-installed [c]
  (if (corp-card-visible-to-runner? c)
    (update c :hosted #(mapv redact-corp-installed %))
    (cond-> (stub c) (ice? c) (assoc :type "ICE"))))

(defn- redact-servers [servers]
  (into {} (for [[k srv] servers]
             [k (-> srv
                    (update :ices #(mapv redact-corp-installed %))
                    (update :content #(mapv redact-corp-installed %)))])))

(defn- redact-runner-installed [c]
  (if (:facedown c) (stub c) c))

(def drop-keys [:effects :events :queued-events :effect-completed :log :history :click-states :turn-state
                :paid-ability-state :suppress :stack :sfx :sfx-current-id :disabled-card-reg])

(defn observe
  "Observation of `state` (a deref'd game map) for side."
  [s side]
  (let [s (apply dissoc s drop-keys)]
    (if (= side :runner)
      (-> s
          (update-in [:corp :hand] #(mapv stub %))
          (update-in [:corp :deck] #(mapv stub %))
          (update-in [:runner :deck] #(mapv stub %))
          (update-in [:corp :discard] #(mapv (fn [c] (if (or (:seen c) (faceup? c) (:monolith-known c)) c (stub c))) %))
          (update-in [:corp :servers] redact-servers)
          (update :corp dissoc :prompt :prompt-state :selected))
      (-> s
          (update-in [:runner :hand] #(mapv stub %))
          (update-in [:runner :deck] #(mapv stub %))
          (update-in [:corp :deck] #(mapv stub %))
          (update-in [:runner :rig] (fn [rig] (into rig (for [[k v] rig] [k (mapv redact-runner-installed v)]))))
          (update :runner dissoc :prompt :prompt-state :selected)))))
