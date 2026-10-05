;; RC1 held-out T3 material (pre-registered protocol): puzzle suites for RC1 and :s1ref, and the
;; 20-game blind-review set on the held-out matchups (seeds 910000-910019).
(require 'monolith.ai.confirm)
(let [rc {:rerank-anchor true :rerank 6 :s1-strong-margin 8.0
          :w {:credit-knee2 12 :corp-safety-extra 3.0 :react-centrals true}
          :eval {:kill-threat 1.0 :hq-flood 1.0 :scorable-agendas 2.5 :asset-econ 1.0}}]
  (monolith.ai.confirm/run {:candidate [:champion rc] :tag "rc1" :dir "/home/dthurn/monolith/research/rounds/R4" :parts #{:puzzles :review}}))
