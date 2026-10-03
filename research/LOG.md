# Research log

Append-only lab notebook for the Netrunner AI project ([plan](../docs/ai-research-plan.md)).

## 2026-10-03 — R0 infrastructure

- Fork: `vendor/netrunner` now has `upstream` (pin `25c256a3`) and `monolith` branches; `scripts/setup` checks out `monolith` and never re-clones.
- Seeding: new `game.rng` namespace in the fork with dynamic `*rng*` (java.util.Random) and `*ids*` (counter atom). Every engine `shuffle`/`rand-int`/`rand-nth`, `make-cid`, prompt `uuid/v4`, effect/event `uuid/v1`, toast ids and card timestamps route through it. Unbound vars keep upstream behaviour, so the sidecar is unchanged.
  - Surprise: the default HQ access order is a fn stored in player state (`:hq-access-fn shuffle`), so grepping for `(shuffle ` missed it; also `->> ... shuffle` threading forms. Found by diffing two replays.
  - Replays are now bit-identical except `[:stats :time]` and fn identities inside prompts.
- Harness: in-process engine (`monolith.ai.engine`), `moves/decision` + `moves/legal`, `harness/play-game` with no-op detection (an action that leaves the state `identical?` is removed and the agent re-asked), per-turn livelock cap (400 actions), global cap 6000.
- Move-gen bug found by random play: the discard-to-hand-size select uses `in-hand?`, which also matches the opponent's HQ; selecting a Corp card as the Runner throws. Fix: never offer opponent hand/deck cards in select prompts.
- Corp fires unbroken subroutines as a forced single action when any unbroken, unfired subroutine exists (no "decline to fire" option).
- Runner encounter options: per-breaker `auto-pump-and-break` dynamic abilities (which also pass priority), non-break paid abilities, or let subroutines fire. Raw break/pump abilities are hidden.
- Result: 1000 seeded random-vs-random Stage A games, 0 stalls, 0 no-ops. Corp wins 61.7% (mostly runner flatlines from random facechecks).
- Throughput: ~490 µs/action single-threaded warm (move gen included), 51 games/s on 16 threads; parallel efficiency only ~37%, to investigate in O1.

## 2026-10-03 — R1 step 1–2: knowledge + S1 heuristic

- `knowledge/cards.clj` reads breaker models straight from engine card-defs (`:breaks`, `:break`, `:break-cost`, `:pump`, `:cost`) and ice subroutine effects from subroutine labels (ETR, net damage, credit loss, conditional ETR). No card-name lists.
- `knowledge/runcalc.clj` is an expectimax over the ice (outermost first): at known ice the Runner takes the best of break-with-each-breaker / let fire / jack out; unknown ice is a chance node over the Corp's unseen ice pool restricted to what the Corp can afford to rez. Approach tolls (Manegarm) are parsed from text.
- S1 iterations driven by `monolith.ai.play/trace-game` logs (self-play null rate in parentheses, Corp win share):
  1. Corp never installed agendas: "safe" was `u < 0` but a Runner that bounces off has `u = 0` (8% Corp).
  2. Runner re-ran remotes with assets it could not afford to trash; Red Team ability label mismatch (72%).
  3. Bayesian prior for advanced remote cards; Runner then flatlined into bluff-advanced Urtica Cipher in 53% of games (66%).
  4. Flatline utility −250 and a safety-draw rule (62%, still 21% flatlines).
  5. "Advanced card in an unprotected remote" read as a trap tell (49% Corp; 32% of games end by Corp deck-out = stalemates).
- T1 check, 300 games per side vs `random`: S1 Corp 99.7%, S1 Runner 98.7% (4 flatlines vs random ice). T1 essentially met; Runner 0.3 points short.
- Known S1 weaknesses (for R2): Corp economy collapses in long games (rezzes everything), stalemates, single-ice scoring remotes, no Corp rez reserve.

## 2026-10-03 — R1 steps 3–6: NN infra, S5 launch, S3, S2, S4 pipeline

- NN infra: sparse featurizer over a 122-title vocabulary (every bundled deck incl. Stage C): 1118 state features, 191 action features. Inference is a hand-written Java MLP (`ai/java/monolith/Mlp.java`) instead of ONNX Runtime (relaxation: no native dependency, small nets are fast enough).
- R-NaD port (`ai/py/monolith_ai/rnad.py`) follows OpenSpiel's structure (V-trace with η-regularized rewards, NeuRD with logit thresholding, EMA target). Check on asymmetric matching pennies (Nash P(H)=0.4) instead of Leduc (OpenSpiel not installed): with Adam β1=0.9 the policy cycled wildly; with β1=0 (as in the reference) and lr 1e-4 it settles into 0.31–0.45 around 0.40.
- S5 training launched in the background (10 actor threads, MPS learner, 24 h).
- **Major move-gen bug found by the puzzle suite:** `score` was only offered while the Corp had clicks, so an agenda advanced with the last click could only be scored next turn, giving the Runner a free turn to steal it. Every game before this fix was biased toward the Runner. S5 actors and S4 data generation were restarted on the fixed build.
- S1 null rate after the fix: Corp 62.5% (40 games) vs 49% before.
- S3 planner v1: solves 14/14 tactical puzzles after fixes (line must include free end-of-turn scoring; agenda EV must subtract remaining advancement cost), but loses to S1 in full games. Traces showed the Corp overwriting its own agendas by installing assets into occupied remotes, rezzing ambushes, then (after a fix) drawing itself out. Pruned overwrite-installs and trap rezzes from planner lines; evaluator weights to be fitted from data (next entry).
- S2 v1: ~19 MCTS iterations per decision at 250 ms (S1 rollouts are ~30 ms each); deviating from S1 on noisy rollout means made it much worse (Corp 30%, Runner 13% vs S1). Now only deviates with ≥4 visits and a 0.15 tanh-margin.

## 2026-10-03 — R1: evaluator fit, S3 fixes, first A/B

- Logistic fit of S1 self-play outcomes on evaluator features (75k turn-start positions, `ai/py/monolith_ai/fit_eval.py`): accuracy 0.653, logloss 0.624 (base rate 0.570). Exposed a **sign bug**: the `:rig` feature (Runner breakers/installs) was counted as good for the Corp, so S3's Runner avoided building a rig. Fitted scale: 1 agenda point ≈ 23 credits; agendas in HQ are a liability (−7.7 credits per point); ice is fitted negative (−8.8), which is confounded by S1 over-icing when it is stuck, so ice stays positive by judgment.
- S3's Runner then farmed Smartware Distributor's "place 3 credits" every click (hosted credits counted as liquid). Hosted credits are now discounted by their release rate parsed from card text (a 1-per-turn drip is worth 0.18 per credit).
- After these fixes S3 beats S1 in both seats (40 games each): Corp 77.5% vs S1 null 62.5%; as Runner it holds S1's Corp to 50%.
- T2 interpretation (pre-registered here): "≥70% vs S1 on both sides measured against null rates" means a Bradley-Terry side-rating gain of logit(0.7) = 0.85 over S1. With S1's Corp null at 62.5%, that is ≥80% Corp wins vs S1's Runner and ≥58% Runner wins vs S1's Corp.
- A/B (300 paired seeds, both seats vs S1): S1 Runner with run agenda-point value 10 instead of 7 wins 30.3% vs 37.3% (McNemar p=0.04). More aggressive running hurts S1.
- S5 actors reniced to +15 so data generation and A/B runs get the CPU; S5 keeps the idle cycles.

## 2026-10-03 — S5 stopped early (kill signal)

- R-NaD from scratch: 145k self-play games, 9k learner steps, ~6 h wall clock (part of it paused or reniced). Evaluation vs S1 (clean policy, threshold 0.05): 5 wins in ~1200 games, no trend; mean agenda points per game flat (Corp 0.2–0.4, Runner 0.6–0.8).
- This is the plan's kill signal (does not beat S1, flat curve). Stopped at ~6 h instead of 24 h to give the CPU to S3/S4 work (relaxation, recorded). R-NaD moves to R3 as fine-tuning from an imitation policy.
- A/B on S1 Runner run agenda-point value: 5 ≈ 7 (37.0% vs 37.3%); 10/14/20 monotonically worse (30/26/22%). Urgency multiplier 0.3: +2.0 points, p=0.43, not adopted.
