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

## 2026-10-03 — R1 complete (handoff)

- R1 report: `research/rounds/R1/report.md`. Ranking: S3 > S1 > S2 > S4 >> S5. T0/T1 met; T2 not met (S3 +0.11/+0.31 side-rating over S1, need +0.85).
- Surprise: S4's prior pruning hurts S3; the pure imitation policy is far weaker than S3, but it is the best search-free agent (S5 is the worst).
- REPL hot-reload of `harness` broke the `NetAgent` protocol binding mid-tournament (400 "stalls" that were harness exceptions); removed and rerun. Run tournaments in fresh JVMs.
- Next: O2 (profile inside S3), then R2 (S3 tuning first).

## 2026-10-03 — R2: S3 A/Bs (200 paired seeds × both seats vs S1)

- S3 games are now deterministic replays (app cap binds, so baseline A rows are identical across A/Bs): baseline Corp 0.635, Runner 0.345.
- rerank 3 (S1 rollouts through the opponent's turn): Corp 0.670, Runner 0.350, p=0.54. Not adopted yet.
- beam 10 / 5,000 apps: 0.615 / 0.360, p=1.0. **More search does not help; the evaluator is the bottleneck.**
- ice weight 0.5: Corp 0.535 (−10 points), p=0.13. Kept at 1.0.
- agendas-in-hq −2.0: 0.655 / 0.345, p=0.60. Run urgency 0.3: no net effect.
- S1 null after the Diviner fix: 0.583 Corp (400 games). Corp rez reserve 4 vs 0: identical games (never binds).
- Implication: S3's Runner (0.345) is now below S1's Runner (0.417). Testing an S1-anchored planner (deviate from S1 only when the plan beats S1's best line by a margin).

## 2026-10-03 — R2: champion config, Stage B, livelock, ES tuning

- S1-anchored planner: margin 2 → Corp 0.665 / Runner 0.390 (p=0.27); margin 5 → neutral. Combined champion `[:planner {:s1-margin 2.0 :rerank 3}]` on Stage A: Corp 0.690 (+5.5), Runner 0.365 (+2), p=0.24 vs plain S3.
- Stage B round robin (100 seeds; S5 culled): `stageB-summary.md`. The champion is clearly the best Runner (S1's Corp wins only 0.59 against it vs S1's null 0.74); its Corp is about S1's level (0.71).
- Stage B livelock (7 games, S4 Runner): Conduit's "Toggle auto-resolve" UI ability was offered as a free action and the follow-up prompt toggled it back. Excluded `^Toggle auto` abilities from move gen.
- ES tuning of S1 weights launched (Runner then Corp; 10 candidates × 200 shared seeds per generation).
- ES results (`R2/es-*.jsonl`): Runner weights stayed flat within seed noise; Corp weights converged to safety-extra 1.4, no extra central ice, 3 ice on the scoring remote, bluff 0.2, econ cap 15, ice reserve 2. Validation on 500 fresh paired seeds: Corp 0.598 → 0.672 (p=0.007) **adopted**; Runner variant +2 points (p=0.45) not adopted.
- S3 evaluator ES (Corp side, 10 gens × 8 candidates × 100 seeds): converged to agenda-points 3.5, installed-agendas 1.5, clicks 1.4, credits 0.8, damage exposure 2.1. **Failed validation** on 300 fresh paired seeds: Corp 0.657 → 0.590. With 100 seeds per generation the ES selected noise. Runner-side ES cancelled; S3 keeps the hand-set weights. (S1 ES worked because S1 games are 20× cheaper and validation used 500 seeds.)
- S1 generic rules for Stage C (meat-damage kill, trash resource when tagged, tag operations, other operations, remove tags): neutral on Stage A. Generic Runner installs are gated off: they drop S1's Stage A Runner from 0.35 to 0.17 (p≈3e-12).
- Stage C S1 self-play (60 games): C1 Corp 0.88; C2 Corp 0.22 with 47 Corp deck-outs. NEH's tag-and-flatline plan needs card-specific play S1 lacks.

## 2026-10-03 — Paused for handoff

- Stopped mid-A/B (planner vs 3-determinization voting; planner vs champion) to free the machine. All jobs killed; `scripts/ci` passes. State and next steps: `research/HANDOFF.md`.

## 2026-10-03 — New machine (Windows 11, WSL2 Ubuntu 24.04, i7-12700F)

- The O1 equivalence baseline no longer replays on `master`: R1's move-gen fixes (score at 0 clicks, auto-resolve toggles) changed the legal action lists, so recorded indices go out of range. Against the pre-R1 harness (`509b387`) all 500 games are per-action identical to the Mac recording, so the engine reproduces across machines.
- Final-state hashes were JVM-dependent (84, then 95 of 500 differing on two runs of the same code; never within one JVM). Cause: `resolve-trash-prevention` took its `set/difference` branch for every multi-card trash (a lazy `keep` result is always truthy), and a set of cards iterates in identity-hash order because cards hold fns. Net damage and similar multi-trashes put cards in the heap in a per-JVM order. This was O1's "heap ordering" note. Fork fix `5ce8aa5`: filter `targets` in order (upstream suite: 3,747 tests, 0 failures). Re-recorded baseline on `master` + fixed fork: 0/500 diverged in two fresh-JVM checks.
- Throughput here (Stage A random self-play, warm): 633 µs/action single thread (Mac: 265); 23.5 / 35.8 / 41.2 / 42.9 / 42.7 games/s at 4 / 8 / 12 / 16 / 20 threads. Use 12–16 threads.

## 2026-10-03 — Autonomous phase begins: deck split, frozen reference, T3 pre-registration

Goal for this phase (user, 2026-10-03): clear T2 and T3 on decks the agent was not tuned on, working unattended.

- **Deck split.** `engine/decks` now includes every bundled Worlds (2012–2025) and Classique matchup as `:<matchup>-corp/-runner` (92 decks; all cards resolve). The NN vocabulary stays pinned to the original nine decks (`engine/base-decks`). Development pool: Stage A, Stage B, Worlds 2012–2022, Classique (`sweep/dev-matchups`, 38 matchups). **Held out for confirmation only:** Stage C (Worlds 2023 a/b) and Worlds 2024/2025 a/b (`sweep/holdout`). On held-out decks I run aggregate matches only: no trace reading, no debugging, no tuning. Exception already made: one baseline champion-vs-S1 run on C1/C2 (below), as the handoff asked.
- **Frozen T2 reference.** `:s1ref` (`monolith.ai.ref.*`) is a byte-for-byte copy of S1 and its knowledge modules (cards, servers, runcalc, weights) at `012b74f`. The champion uses S1 internally, so improving S1 must not move the bar. `:s1ref` is the ES-tuned S1, stronger than R1's "heuristic v1", so this is conservative.
- **T2 as measured from now on** (unchanged bar, generalized to many decks): per side, Bradley–Terry side-rating gain ≥ +0.85 logit over `:s1ref`, where each matchup's null is `:s1ref` self-play on the same seeds. Reported on Stage A (original), the dev pool, and the held-out pool; the held-out pool is the one that counts.
- **T3 pre-registration** (the plan gives no numeric bar; fixed now, before any T3 measurement):
  1. *Puzzles:* ≥30 puzzles, of which ≥10 are held-out puzzles built from held-out-deck positions and never used for debugging. Pass: ≥90% of dev puzzles and ≥80% of held-out puzzles solved, and strictly more than S1 on held-out puzzles.
  2. *Blind log review:* 20 full-game logs on held-out matchups (10 candidate, 10 `:s1ref`; each game's candidate side balanced 5 Corp / 5 Runner; opponent is the other agent), labels removed and order shuffled by a fixed seed. One fresh reviewer agent (claude-opus-5-5) scores each log for the side under review against the §7.5 rubric (counts of wasted clicks, reasonless facechecks, unprotected agendas, missed lethal/scores, economy collapse) plus a 1–5 rating of "plays like a competent casual human". Pass: candidate mean rating ≥ 3.5, candidate mean serious-blunder count (missed lethal/score, agenda left unprotected and stolen) ≤ 1.0 per game, and candidate rated above `:s1ref` on mean.
  3. T2 must also hold on the held-out pool.

## 2026-10-03 — Wider deck pool: move-gen coverage

- Random-vs-random sweep, 20 seeds × 46 matchups (`R3/sweep-random.jsonl`): **90/920 stalls (9.8%)** before fixes. Causes:
  - 58 StackOverflowErrors: upstream engine bug in `pick-credit-providing-cards` (the retry call shifted its arguments, dropping the bad-publicity budget, so `pay-rest` recursed forever when the pool couldn't cover a payment). Fork fix `2ae5bf346`.
  - 23 dead-end selects: `:all` select prompts with no valid target (Corporate Troubleshooter with no rezzed ice, Marcus Batty, Trope with 0 targets) offer only "Hide", which re-shows the prompt forever. Move gen now offers `monolith-cancel-select`, which resolves the select with no targets.
  - 4 harness exceptions: an engine card req throws while the engine's lazy `:selectable` seq is realized; move gen now realizes it under try.
  - 6 engine schema exceptions on single actions: the harness now drops an action that throws (`command!` already restores the state) and only stalls if every action throws; each game records `:errors`.
  - Bad-publicity payment (`bad-pub-choice`) is now offered in select prompts that allow it.
- After fixes (`sweep-random2.jsonl`): 5/880 stalls, 4 of them the lazy-selectable bug fixed after that run started, 1 livelock (worlds-2016-b, random only).
- Traces on a dev deck (Worlds 2019 a, champion vs S1) show **S1's Runner is helpless off Stage A**: with 4 credits and Sure Gamble/Liberated Account in hand it ran HQ or the same remote 4 times a turn for 20+ turns, never drawing or clicking for credits (run rule outranks economy; HQ access value 2.7 vs 1.0 click cost). S1's Corp clicked for credits for many turns holding agendas. The champion's Runner played the economy well but let Hagen's subroutines fire four runs in a row. Next: measure before fixing.
