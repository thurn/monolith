(ns monolith.sidecar
  "Headless Jinteki.net rules engine. Reads one JSON request per line on stdin
  and writes one JSON response per line on stdout. Everything else the engine
  prints is redirected to stderr so it cannot corrupt the protocol."
  (:gen-class)
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [game.cards.agendas]
   [game.cards.assets]
   [game.cards.basic]
   [game.cards.events]
   [game.cards.hardware]
   [game.cards.ice]
   [game.cards.identities]
   [game.cards.operations]
   [game.cards.programs]
   [game.cards.resources]
   [game.cards.upgrades]
   [game.core :as core]
   [game.core.diffs :as diffs]
   [game.main :as main]
   [game.utils :refer [server-card]]
   [jinteki.cards :refer [all-cards]]
   [jinteki.i18n :refer [insert-lang!]]
   [jinteki.preconstructed :as precon]
   [taoensso.timbre :as timbre]))

(defonce game (atom nil))

(defn load-cards! []
  (insert-lang! "en" (slurp (io/resource "en.ftl")))
  (->> (io/resource "raw_data.edn")
       slurp
       edn/read-string
       :cards
       (map (juxt :title identity))
       (into {})
       (reset! all-cards)))

(defn- resolve-deck [{:keys [identity cards]}]
  {:identity (server-card (:title identity))
   :cards (mapv (fn [{:keys [qty card]}] {:qty qty :card (server-card card)}) cards)})

(defn new-game! [{:keys [corp runner]}]
  (let [corp-deck (or corp precon/gateway-beginner-corp)
        runner-deck (or runner precon/gateway-beginner-runner)]
    (reset! game
            (core/init-game
             {:gameid 1
              :format :casual
              :players [{:side "Corp" :user {:username "Corp"} :deck (resolve-deck corp-deck)}
                        {:side "Runner" :user {:username "Runner"} :deck (resolve-deck runner-deck)}]}))))

(defn action! [{:keys [side command args]}]
  (let [state @game
        old @state]
    (try
      (main/handle-action state (keyword side) command args)
      (catch Throwable t
        (reset! state old)
        (throw t)))))

(defn views []
  (let [{:keys [corp-state runner-state]} (diffs/public-states @game false false false)]
    {:corp corp-state :runner runner-state}))

(defn handle [{:keys [op] :as req}]
  (case op
    "ping" {}
    "new-game" (do (new-game! req) (views))
    "action" (do (action! req) (views))
    "state" (views)
    "quit" {}
    (throw (ex-info (str "unknown op " op) {}))))

(defn -main [& _]
  (let [proto-out (io/writer System/out)
        reply! (fn [m]
                 (.write proto-out (json/generate-string m))
                 (.write proto-out "\n")
                 (.flush proto-out))
        boot-start (System/nanoTime)]
    (binding [*out* *err*]
      (timbre/set-min-level! :warn)
      (load-cards!)
      (reply! {:op "ready" :boot-ms (quot (- (System/nanoTime) boot-start) 1000000)})
      ;; first/rest rather than [line & more]: destructuring calls `next`, which would block
      ;; reading the following line before this one is handled.
      (loop [lines (line-seq (io/reader System/in))]
        (when-let [line (first lines)]
          (let [req (when (seq line) (json/parse-string line true))
                start (System/nanoTime)
                resp (try
                       (assoc (handle req) :ok true)
                       (catch Throwable t
                         (.printStackTrace t)
                         {:ok false :error (str t)}))]
            (when req
              (reply! (assoc resp
                             :id (:id req)
                             :ms (/ (quot (- (System/nanoTime) start) 1000) 1000.0))))
            (when-not (= "quit" (:op req))
              (recur (rest lines))))))
      ;; A clean exit matters: the JVM writes its auto-created CDS archive at exit.
      (shutdown-agents)
      (System/exit 0))))
