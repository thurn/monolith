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

## 2026-10-04 — Overnight run lost to OOM

- At 22:12 the VM ran out of memory with three `-Xmx24g` JVMs live (held-out A/B ~21 GB incl. swap, S1-gen A/B ~7 GB, REPL ~6 GB). The kernel killed the held-out JVM; systemd then killed the whole tmux scope (Claude and the other JVMs), since `nohup` jobs shared its cgroup.
- Survived: held-out Stage B, `heuristic` vs `champion`, 300 seeds: S1 Corp 0.757 / Runner 0.243, champion Corp 0.807 / Runner 0.623 (p≈3e-16). C1/C2 legs and the S1-gen A/B were lost.
- Fix: heap cap is now `AI_HEAP` (default 8g); `ai-job`/`ai-repl` run each JVM in its own systemd user scope. 8g suffices: champion vs S1 at 14 threads peaks ~9 GB RSS, and a full GC leaves the old generation 1–5% full. Budget rules in `HANDOFF.md`.

## 2026-10-04 — R4 begins: autonomous push for T2/T3 on held-out decks

- Machine: 20 cores, 27 GB VM. Layout: two 8g jobs + a 6g REPL (25 GB budget).
- `ab/run` and `evalset/run` now stream one JSONL line per game (seed-major order, replay `:log` kept), so killed jobs lose only in-flight games and partial logs hold complete seeds.
- Launched (results in `rounds/R4/`):
  1. Dev-mix baseline, 300 seeds (100000–100299) over the 40 dev matchups: `:heuristic` (current S1) vs `:s1ref`, then `:champion` vs `:s1ref`, opponent `:s1ref`. This is the first broad measurement of where the champion stands against the frozen T2 reference.
  2. Held-out baseline (the one sanctioned held-out run before tuning), 300 seeds (90000–90299) over the 6 held-out matchups: `:champion` vs `:s1ref`.
- Context from the existing S1 self-play sweep (`R3/sweep-s1.jsonl`, 20 seeds × 40 dev matchups): S1's Corp null averages ≈0.80 and is ≥0.9 on 17 matchups; it is Runner-favoured on six (classique-2023-b, worlds-2017-b, 2019-a/b, 2020-a). So the Corp side needs ≈0.90 average to clear +0.85 logit; the Runner side ≈0.37.
- Plan for R4: (a) read the baselines per matchup and side; (b) trace the champion's losses on dev decks only and fix generic weaknesses (Corp side first, it gained least on Stage A/B); (c) grow the puzzle suite to ≥30 with ≥10 held-out puzzles (pre-registered T3 rule); (d) build the blind-review pipeline.

### 2026-10-04 morning — first dev-deck traces, generic fixes

- Current S1 vs `:s1ref` on the dev mix (300 seeds): Corp 0.747 vs 0.740, Runner 0.273 vs 0.260, p=0.07. The frozen bar and S1 have not drifted apart.
- Trace, champion Corp vs `:s1ref`, worlds-2018-a seed 7 (lost 1–7): turn 1 Project Beale went into a 1-ice remote because "safe" ignored breakers still in the Runner's hand (Aumakua came down and stole it that turn); R&D stayed unprotected while the Runner ran it 4×/turn (the evaluator has an HQ-exposure term but nothing for R&D); later the Corp banked 121 credits without scoring.
- **Breaker model gap:** `breaker-model` returned nil for Paperclip and Matryoshka and doubled Black Orchestra/MKUltra costs (pump and break are one payment for heap breakers). Paperclip/Black Orchestra/MKUltra are the rig of ~15 dev Runner decks, so S1 and the champion thought those Runners could not break barriers. Fixed from card-def keys (`:heap-breaker-pump/break`, x-credit costs) with ice types from breaker subtypes. `:s1ref` keeps its frozen copy of the bug.
- **S1 never paid steal costs** ("Pay to steal" fell through to "No action"): fixed. Steal costs (Bellona) now enter server content value.
- New evaluator/S1 options (off by default; A/B pending): `:potential-breakers` (server safety mixes in the best breaker the Runner may hold, p = 1-(1-c/N)^h over unseen cards), `:central-threat` (run-calculator value of HQ/R&D for the Runner as the Corp knows them, replacing `:hq-exposure`, which penalised agendas in HQ but not the same agendas in an equally open R&D), `:scorable-agendas` (an agenda the Corp can finish advancing this turn is valued like a scored one, so beam search keeps advance chains), planner `:branch-prompts` (own prompts with 2–6 options become search branches instead of S1 choices).
- Diagnosis of `corp-advance-to-score` regression: (1) beam myopia: advancing is value-neutral until the score, so in 2/3 determinizations Palisade-install lines crowded advance×3 out of the beam; (2) the S1-rollout rerank flipped the choice in all 3 seeds (one noisy rollout where S1's Runner raids an open HQ outweighs a scored point). The rerank was never significant on its own (R2: p=0.54).
- **Puzzles:** suite is now 20 dev (14 Stage A + 6 from Worlds 2012–2022 decks) + 13 held-out (Worlds 2023–2025). Every puzzle has a scripted reference solution and a bad line; `puzzles/check` validates them by script only, so held-out puzzles are proven well-posed without running any agent on them. Dev scores: `:s1ref` 0.65, champion 0.80 → 0.85 after the breaker/steal fixes, champion+options with rerank off 1.00.

### 2026-10-04 07:00 — baselines in

- **Dev mix, R3 champion vs `:s1ref`** (300 seeds × 40 matchups, `eval-dev.jsonl` tag `champion`): null Corp 0.740; champion Corp 0.855, Runner 0.573; **g_c +1.03 [+0.70, +1.52], g_r +1.46 [+1.18, +1.84]**. 11 stalls (1.8%, all livelocks), which breaks T0's 0.5%.
- **Held-out, R3 champion vs `:s1ref`** (300 seeds × 6 held-out matchups; the one sanctioned pre-tuning run): null Corp 0.617; champion Corp 0.647, Runner 0.667, p=2e-10; 18 stalls. Rough side-ratings: **Corp ≈ +0.13, Runner ≈ +1.17**. The per-game log was lost (job loaded pre-streaming code), so only the summary exists. **The Corp side does not transfer to held-out decks.** I will not trace held-out games; Corp work continues on dev decks, and the dev Corp figure may be inflated by an exploit (next item).
- Livelock causes on dev (replays): (1) Daily Quest / Commercial Bankers Group expose their start-of-turn trigger as a manual ability the engine lets you repeat any time: free +3 credits per use, farmed by the champion (an upstream engine bug; move gen now hides "(start of turn)" abilities); (2) Paperclip's raw "+X strength, break X" ability was offered at turn level with X=0 (move gen now hides heap-breaker abilities outside encounters); (3) Clone Chip's install-from-heap with no target only changed the engine's undo snapshot (`:paid-ability-state`, now ignored by the no-op check); (4) the engine re-offers an already-accessed Archives upgrade and S1 re-picked it (S1 now takes "Everything else"). Generic net: every agent now runs behind a `LoopGuard` that withholds an action chosen 5 times in the same position fingerprint (credits excluded). Checked: 60/60 `:s1ref` null games are bit-identical with all of this; the three stalled seeds now finish.
- Restarted the variant ladder on the fixed move gen (`job-eval-A`: `champ-b`, `v2nr`, `v4`).

### 2026-10-04 07:30 — dev blind-review trial: T3 is the real gap; Corp passivity on asset decks

- **Dev trial of the T3 blind review** (`rounds/R4/review-dev1`, key `review-dev1-key.edn`, scores `review-dev1-scores.json`): 20 games of v5 (= champion + potential-breakers, central-threat, scorable-agendas, credit knee, hand curve, kill-threat, rerank off) vs `:s1ref` on 10 dev matchups; one blind Opus reviewer with `research/review-rubric.md`. Candidate mean rating **2.00** (Corp 2.0, Runner 2.0), serious blunders 1.30/game; `:s1ref` 2.10 (Corp 2.6, Runner 1.6), 1.40. Bar: ≥3.5, ≤1.0, above `:s1ref`. **Far from T3.**
- Reviewer's main complaints. Corp: agendas held for many turns then discarded into an open Archives and stolen; clicking for credits while rich; drawing into forced discards; decking itself; agendas in naked remotes when an iced one existed. Runner: banking credits and never contesting advanced remotes; re-running a known Urtica Cipher 7×; re-installing heap breakers that break nothing; Eater runs that access nothing.
- Trace of log-10's game (worlds-2021-b, candidate Corp): both early ice went on HQ/R&D, so no scoring remote existed; from turn 4 to 20 the Corp held 4–5 agendas in a 1-ice HQ and never installed one. When a 1-ice remote existed, safety with a possible Paperclip in the grip looked worse than holding. The evaluator has no notion of time: holding agendas costs only a small static liability.
- **Corp diagnostic on the four NEH/asset dev decks** (worlds-2018-a/b, 2020-b, 2021-b; 4 seeds each, vs `:s1ref`): candidate Corp 6/16, `:s1ref` Corp 13/16 (S1 scores in 14–23 turns; the candidate decked itself 4 times in 27–45-turn games). The planner's Corp is too passive on exactly the archetype that dominates the held-out Corps (2023-b, 2024-a, 2025-b are asset-spam/NEH-style; 2024-b, 2025-a Jinteki asset/trap).
- Implication: hand-patching the linear evaluator is unlikely to reach T3 (rating 3.5). Next: (1) read the variant ladder by deck; (2) consider leaning on S1's Corp on the Corp side short-term; (3) build a learned, card-agnostic value function from self-play outcomes on the dev pool (the planner already has a value-fn hook) to replace the hand-set weights.

### 2026-10-04 07:45 — ladder: the fixes help, my bundled evaluator options hurt

- Paired on the same dev seeds (vs `:s1ref`, partial): **`champ-b`** (R3 champion config + generic fixes: heap/X breaker models, steal costs, S1 pays to steal, R&D known top, start-of-turn exploit and Paperclip/Clone Chip livelocks removed) vs R3 `champion`, 217 seeds: Corp 0.870 → 0.860 (p=0.83), **Runner 0.545 → 0.675 (+40/−14, p=0.0005)**. The Corp held its level without the infinite-credit exploit.
- **`v5`** (champ-b + potential-breakers, central-threat, scorable-agendas, credit knee, hand curve, kill-threat, rerank off) vs `champ-b`, 115 seeds: **Corp 0.887 → 0.748 (+5/−21, p=0.002)**, Runner 0.673 → 0.582 (p=0.15). The bundle is clearly worse; the dev review trial (rating 2.0) used this weaker agent. (Partial `v5` rows were lost in a log cleanup; this summary is all that remains.)
- Lesson: one factor per A/B. Single-factor tests on the current code now running in `job-eval-B2`: `cb` (champion defaults, same code), `cb-nr` (rerank 0), `cb-ct` (central-threat replaces hq-exposure), `cb-pb` (potential-breakers). `job-eval-A` continues with `v2nr` and `v4` (older code). Each job gets its own games file from now on.
- Value model: features + generator + trainer + planner hook built. On ~13k S1 self-play positions, with per-matchup intercepts (deck strength is invisible to card-agnostic features; planning only needs within-game differences), held-out-matchup logloss: intercepts only 0.510, linear 0.443, MLP 0.432 (overfits quickly; needs far more data, positions within a game are highly correlated).

### 2026-10-04 08:15 — T3 proxies, more generic fixes, value model v1

- **T3 proxy metrics** (`monolith.ai.blunders`, replay-based, ~2 min per 600 games): per game and side, rich credit clicks (≥15 credits), forced discards, agendas into Archives on the Corp's turn, repeated failed runs, idle turns, flatlines, decking. `champ-b` vs `:s1ref` (same games): Corp rich clicks 3.4 vs 9.4, idle turns 1.0 vs 4.5, agendas→Archives 0.2 vs 0.5; Runner rich clicks 2.8 vs 22.9, idle 1.3 vs 6.1, repeated failed runs 1.7 vs 1.8; but forced discards ~6/game for both champion sides.
- Fixes from these proxies and traces (all generic):
  - Draw-into-discard: the planner scores lines at the end-turn decision, *before* the hand-size discard, so cards beyond the limit were counted. Evaluator hand terms now cap at each side's max hand size; S1's Corp no longer draws past its max.
  - Repeated failed runs: (a) counter-paid breakers (Yusuf's virus counters, Revolver/Cerberus power counters, self-trash) were modeled as free and unlimited; activations are now limited by counters on the card (or placed on install, from text). (b) Faust (grip-paid) had no engine auto-break at all; only Faust and Deus X of the 48 breakers in the pool lack `auto-icebreaker`; engine fork `66ec545` wraps Faust, and grip-paid activations are limited by grip size. (c) Generic guard: a server already run unsuccessfully this turn (engine register) has no access value on a re-run.
  - The engine never marks an accessed installed card as seen, so agents forgot known traps (the 7× Urtica re-run). The harness now tags installed cards the Runner accesses (`:monolith-known`); the observation and determinizer treat them as seen.
- Value model v1 (`rounds/R4/vm1.json`, MLP 64): 117k S1 self-play positions + 28k positions replayed from `champ-b` games; held-out-matchup logloss 0.427 (linear 0.433). Alone as leaf it loses tactics (dev puzzles 0.75: S1 always scores when it can, so "score now" has no contrast in the data); as a correction on top of the hand evaluator (`:vblend 1`, `:vweight 5`) puzzles stay at 0.95. Queued: `cb2` (current code + new null on the new engine), `cbv5`, `cbv15`.

### 2026-10-04 09:15 — confirmation protocol (fixed now, before further held-out runs)

Held-out matchups: `sweep/holdout-mix` (Worlds 2023 a/b = Stage C, 2024 a/b, 2025 a/b). Opponent and reference `:s1ref` throughout.
- **T2 confirmation:** `evalset` of the candidate vs `:s1ref` with null, seeds 900000–900299 (never used before), all six held-out matchups. Report g_c, g_r (cmp.py, 200 bootstraps) against the pre-registered +0.85 point bar, with CIs, plus Stage A and the dev mix for context. At most one held-out T2 run per release candidate; every run is logged, whatever the result.
- **T3 puzzles:** `puzzles/run-suite` on `holdout-suite` (13) and dev puzzles (20), seeds [1 2 3], majority per puzzle, for the candidate and `:s1ref`. Pass rule as pre-registered (≥90% dev, ≥80% held-out, strictly more than `:s1ref` on held-out).
- **T3 blind review:** `gamelog/review-set` on the six held-out matchups, seeds 910000–910019 (20 games: candidate reviewed in 10, 5 Corp / 5 Runner; `:s1ref` in 10), one fresh Opus reviewer with `research/review-rubric.md`, key kept outside the reviewer's directory. Pass rule as pre-registered.
- Release candidate = the dev-mix winner after paired single-factor A/Bs (≥300 seeds). Dev review trials (dev matchups, seeds 200000+) are allowed for development; held-out reviews only for confirmation.

### 2026-10-04 09:40 — more review-driven fixes; frozen worktrees for experiments

- Eater ("You cannot access cards for the remainder of this run") was used to break into R&D every turn, accessing nothing (15 such runs in one trace). Breaker models now carry `:no-access`; the run calculator gives such a success no access value. Account Siphon-style replacement payoffs ("instead of breaching… lose up to N, gain M per credit lost, take T tags") are parsed from text and exempt. Same game re-rendered: 15 → 0 no-access runs.
- Heap breakers (Paperclip, Black Orchestra, MKUltra) were reinstalled from the heap mid-run by S1's default "Yes", then could not pay to break. S1 now installs from the heap only when install + breaking the encountered ice is affordable.
- What the Corp discards at hand size (80 `cb` games): 163 operations, 146 assets, 66 upgrades, 6 ice, 1 agenda: unplayed economy assets (PAD Campaign, Daily Business Show) and upgrades. The evaluator values an installed asset at ~0.25 credits, below a click for credit. New option `:asset-econ` values economy assets from text (per-turn gain ×4; loaded credits ×0.6; unrezzed at 0.8 × (potential − rez cost)).
- New proxy: peak number of iced-but-empty remotes. `cb` Corp 2.65 vs `:s1ref` 1.3 (review: "iced six empty remotes for no purpose"). Option `:empty-remote-ice` counts ice on spare empty remotes at 0.15 instead of 0.7. Option `:hq-flood`: extra liability for agenda points in HQ beyond 2.
- Planner timing: evaluation is ~80 µs per position, negligible; the engine is ~6 ms per application on the loaded machine. The only speed lever is fewer applications (`cd-1k` test).
- Experiments that span several jobs now run from a frozen `git worktree` (`/home/dthurn/monolith-frozen/<name>`, `vendor/` and card data symlinked), launched by `scripts/ai-queue` lines prefixed `ROOT=...`, so later code changes cannot confound a shared baseline. Job D (single factors: kill-threat, asset-econ, hq-flood, 1k apps, rerank 6, branch-prompts, scorable-agendas, hand curve) is queued from frozen commit `d` behind the running jobs.

### 2026-10-04 10:00 — dev review 2: rating 3.0 (from 2.0); rerank confirmed

- **Rerank (S1 rollouts through the opponent's turn) helps:** `cb-nr` vs `cb` (rerank 0 vs 3, same code, 300 seeds): +26/−49, p=0.01, both sides worse. It stays, despite breaking the `corp-advance-to-score` puzzle.
- **Dev review 2** (`review-dev2`, current champion defaults incl. all fixes to 09:15, fresh seeds 200100–200119, same 10 dev matchups, fresh Opus reviewer): candidate **3.00** (Corp 3.2, Runner 2.8), serious blunders **0.50**/game, wasted clicks 14.7; `:s1ref` 1.80, 1.50, 21.8. Two of three T3 review conditions met on dev; rating short by 0.5. (Unpaired with review 1: different seeds and code.)
- Candidate failures left (review notes): Corp drew itself out while hoarding agendas in a 28-card HQ (rated 1); Runner at 6 points never contested the remote where the Corp scored 5 agendas over 14 turns (rated 1; trace: no breakers for that remote's ice, 20–30 credits, clicking for credits); Eater re-installed ~15× after Archangel bounced it; idle credit clicks while rich in otherwise good games (several 4s).
- Fixes: steals that reach 7 points are valued as wins (50) in run values, for remote and central accesses; evaluator penalizes the Corp drawing R&D below 10 cards (quadratic) and counts at most 7 hand cards whatever the max hand size; new option `:grip-breakers` (breakers in grip for uncovered ice types count 1.5 vs 4 installed).

### 2026-10-04 10:00 — cb2 in; the anchor never protected S1's choice under rerank

- **`cb2`** (champion defaults, code to 08:40: hand cap, draw fix, counter/grip breakers, failed-run guard, Faust fork fix; own null on the new engine; 300 seeds): Corp 0.860, Runner 0.650, **0 stalls / 600**; g_c +1.18 [+0.80, +1.75], g_r +1.72 [+1.44, +2.11]. Proxies vs `cb`: forced discards Corp 5.4 → 3.4, Runner 6.7 → 3.8; repeated failed runs 1.58 → 0.16; but rich credit clicks rose (Corp 3.1 → 4.3, Runner 2.9 → 3.5): the wasted draws became credit clicks.
- Why rich credit clicks: at a replayed `cb2` position (25 credits, Architect in hand), S1 chose "install Architect in Server 1" but the champion clicked for credit. The rerank adds 10,000 to its top-3 lines' rollout scores; S1's action was not among them, so the S1 anchor compared a beam score with a rerank score and always lost. The anchor has been inert whenever S1's choice falls outside the top 3 (most of the time since R2). New option `:rerank-anchor` reranks the anchor's best line as well; at that position the champion then installs Architect. Queued as `ce-ra` in job E (frozen `e` = `486e6ab`).

### 2026-10-04 10:30 — central-threat rejected; a dev proxy for the Corp transfer gap

- `cb-ct` (central-threat replaces hq-exposure) vs `cb`, 75 seeds so far: Corp +1/−14 (p=0.001). Rejected; with rerank-off it explains the v5/v2nr Corp collapse.
- **Corp proxy:** on eight dev matchups whose Corps resemble the held-out ones (NEH/asset-spam: worlds-2016-a, 2018-a/b, 2020-b, 2021-b, classique-2022-c, 2025-b, 2026-d; picked from held-out deck lists only), every champion version's Corp wins exactly as often as `:s1ref`'s Corp (0.81 vs null 0.81, ~59 games each), while the Runner gains hugely (`cb2` 0.85 vs null Runner 0.19). This mirrors the held-out result (Corp ≈ +0.13) and gives a dev signal to work on.
- Job P (front of queue, frozen `p`): Corp side only, 300 paired seeds over `sweep/corp-proxy`: baseline, scorable-agendas, perceived-safety, asset-econ, hq-flood, rerank-anchor, no S1 anchor, current S1, `vm2` hybrid (weight 15).
- **Correction (10:55):** `cb-ct` final at 300 seeds is neutral: Corp +26/−30 (p=0.69), Runner +27/−23, overall +53/−53. The 75-seed partial (+1/−14) was noise. Rule from now on: no conclusions from partial runs. The v5/v2nr losses are then mostly rerank-off (`cb-nr`: +26/−49 overall, p=0.01); `cb-pb` (potential-breakers) still running.
- On the eight Corp-proxy decks, 6 of `cb2`'s 11 Corp losses were self-deck-outs (`:s1ref` Corp: none). `cb2` predates the deck-out penalty; job P's baseline includes it.

### 2026-10-04 11:50 — consolidated single-factor job G; more fixes

- `cbv5` (champion + vm1 learned-value correction, weight 5) vs `cb2`, ~298 seeds: Corp +22/−13 (p=0.18), Runner +31/−35; neutral overall. `cbv15` running.
- Current S1 vs `:s1ref` (dev mix, 300 seeds): g_c +0.28 [+0.04, +0.60], g_r +0.52 [+0.29, +0.81]: the knowledge fixes improved S1 itself (and so the champion's rollouts and anchor).
- Deck-out fix checked: 3 of 4 replayed `cb2` Corp self-deck-out seeds are now Corp wins.
- Fixes: Aumakua's "manually place 1 virus counter" (and other free UI-correction abilities) were repeatable for free (the one `cbv5` livelock; also an infinite-strength exploit): hidden by move gen. Liberated Account-style reserves (click: take 2) were valued so that a basic credit click beat taking 2; hosted click-to-take credits now count 0.4 each.
- Throughput: evalset runs idled ~10–20 min per tag waiting for their slowest games. `evalset/run-many` puts many experiments in one pool, seed-major. All pending single-factor tests are now one job G (frozen `g` = `71adaa6`, 16 threads, `rounds/R4/job-G.clj`): dev-mix baseline `g0` + null and 16 variants (rerank-anchor, scorable, kill-threat, asset-econ, hq-flood, empty-remote-ice, grip-breakers, credit knee 12, tag-exposure, potential-breakers, 1k apps, rerank 6, rerank samples 2, rerank turns 2, branch-prompts, rerank-anchor + margin 1), and the Corp-proxy baseline `p0` + null with 8 variants (rerank-anchor, scorable, perceived-safety, asset-econ, hq-flood, no anchor, current S1, vm2 hybrid). ~10 h.
- `cb-pb` (potential-breakers) vs `cb`, 300 seeds: **Corp +18/−35 (p=0.03)**, Runner +26/−27. Rejected (the Corp gets too timid). With rerank-off this accounts for the v5/v2nr Corp drop. (G re-tests it on current code as `g-pb`.)

### 2026-10-04 12:10 — dev review 3; two run-valuation consistency bugs

- **Dev review 3** (current defaults at 11:50: + deck-out, win-steal, hosted-credit, Eater, heap-breaker fixes; seeds 200200–200219): candidate **2.70** (Corp 2.4, Runner 3.0), blunders 1.10, wasted clicks **8.1** (review 2: 3.00 / 0.50 / 14.7); `:s1ref` 1.50 / 2.20 / 16.9. With 10 candidate games per review the rating noise is about ±0.3; wasted clicks clearly fell.
- Candidate failures: Corp leaves HQ or R&D unprotected under repeated runs (3 games); agendas behind a single ice the Runner already breaks (3); iced empty remotes; idle credit clicks. Runner: re-ran a known Urtica Cipher server 4× (again) and a naked Marilyn Campaign 5× without trashing; passive against the scoring remote (2).
- Root causes of the re-runs: (1) server content value ignored a *known* armed ambush's damage; (2) content value credited trashing any affordable asset while S1's access prompt (`trash-worth?`) declined it, so the Runner ran, declined, ran again. Now one shared `worth-trashing?` decides both, and known armed ambushes are valued by their damage (lethal = flatline utility).
- Job C stopped on `cbv5`'s last straggler (599/600 done; `cbv15` dropped; `p-vm` in G and `h-vm15` cover the value model). Job H (frozen `h`, includes the trash/trap fix, 6 threads alongside G's 16): baseline `h0` + null, `h-vm15` (vm3 hybrid, weight 15), `h-cic1` (S1 `corp-ice-per-central` 1), `h-cse3` (S1 `corp-safety-extra` 3.0). The last two undo ES-tuned S1 Corp weights that exploit S1's weak Runner (no central ice, aggressive installs), which reviewers flag as mistakes.
- 12:20: G and H were on course for ~12 h. Replaced by one job I (frozen `i` = `ddaa056`, 19 threads, `rounds/R4/job-I.clj`, ~7.5 h): dev-mix baseline `i0` + null and 13 single factors (rerank-anchor, scorable, kill-threat, asset-econ, hq-flood, credit knee 12, rerank 6, rerank-turns 2, empty-remote-ice, S1 ice-per-central 1, S1 safety-extra 3, S1 react-centrals, vm3 hybrid w15); Corp-proxy baseline `p0` + null and 6 (rerank-anchor, scorable, asset-econ, hq-flood, safety-extra 3, vm3). Dropped for time: tag-exposure, potential-breakers (already rejected), 1k apps, rerank samples, branch-prompts, anchor margin 1, grip-breakers, perceived-safety, no-anchor, current-S1.
- New dev puzzle `d-clear-tag-vs-kill-deck` (tagged Runner, 3-card grip, Scorched Earth deck): champion solves 1/3 seeds, `:s1ref` 2/3, champion + rerank-anchor 3/3 (S1's tag removal gets a rollout, which shows the kill); kill-threat alone 1/3.

### 2026-10-04 12:45 — S1 and evaluator fixes while job I runs

- S1 changes (economy assets incl. per-turn gainers, no voluntary draws at ≤5 cards in R&D, shared trash decision, known-trap damage) vs earlier S1, 300 dev seeds: Corp +3/−1, Runner identical. Harmless.
- S1 Corp now (a) picks a lethal damage mode in choice prompts ("Do 1 net damage per tag (up to 3)" style, parsed with per-tag scaling and caps; this also resolves the planner's own prompts), (b) counts per-tag meat damage in its kill rule (High-Profile Target; S1 now solves `d-hpt-per-tag-lethal` 3/3). New option `:react-centrals` (ice HQ/R&D after the Runner got in last turn; in job I).
- **Winning scores:** a proxy-deck trace (worlds-2018-b, seed 300011) had the Corp sitting on 6 points from turn 21 with five agendas in HQ, two iced remotes and up to 55 credits: the evaluator valued any agenda at ~7 credits per point even when one score ends the game. `agenda-ev` now values a score that reaches 7 as a win (50) and a steal that reaches 7 as a loss (50), mirroring the Runner's winning-steal fix. Same seed: Corp now wins 7–3 on turn 23.
- Dev puzzles: champion defaults 18/21; champion + rerank-anchor + scorable-agendas 21/21; `:s1ref` 14/21.
- **Reviewer noise:** a second fresh Opus reviewer scored the same 20 review-3 logs. Per-log ratings identical in 15/20, ±1 otherwise; means candidate 2.70 vs 2.90, `:s1ref` 1.50 vs 1.80; serious blunders noisier (candidate 1.10 vs 0.70). So one reviewer's mean rating is good to ~±0.2; game sampling (10 candidate games) is the larger noise. The serious-blunder bar (≤1.0) sits inside the reviewer noise for our current agent.
- New S1 option `:unclog` (at max hand size, install an asset into a new remote instead of clicking for credit and discarding): from a proxy trace where three Commercial Bankers Groups clogged HQ all game while the Corp clicked for credits at 30–50.

### 2026-10-04 14:25 — dev review 4; three run-model bugs behind "running into known ice"

- **Dev review 4** (champion + rerank-anchor, code to 13:00, matchups weighted to the Corp-proxy decks, seeds 200300–200319): candidate **2.90** (Corp **3.4**, Runner 2.4), blunders **0.40**, wasted 11.9; `:s1ref` 1.90. The Corp clears the rating bar on held-out-like decks; the Runner is the weak side here.
- Runner complaints: ran into a known IP Block "for 30+ turns"; bumped into a rezzed Eli 1.0 turn after turn with Corroder and credits; ran into a known Anansi; hoarded credits. Causes found:
  1. IP Block's "End the run if the Runner is tagged" parsed as nothing; now `:etr-if-tagged` (fires if a tag sub fired earlier in the encounter or the Runner is already tagged).
  2. Anansi's "if the Runner did not fully break it, do 3 net damage" ignored; ice models now carry `:unbroken-damage`.
  3. Eli: the run was started on the breach value *with* R&D Interface's extra access (6.6) but the encounter decision valued the breach without it (3.3), so paying 4 to break looked bad and the Runner let it fire, every turn. Mid-run encounter and jack-out decisions now use the same extra accesses. Same seed: bounces off Eli 18 → 2 (initial facechecks), Runner wins on turn ~12.
  Also: a planner run line no longer gets its click refunded when the run calculator gives it no chance of success.

### 2026-10-04 17:00 — dev review 5; stale REPL caveat

- **Dev review 5** (champion + rerank-anchor + s1-strong-margin 8 + scorable-agendas, code to 16:40, mixed dev/proxy matchups, seeds 200400–200419): candidate **3.00** (Corp 3.0, Runner 3.0), blunders 0.50, wasted clicks **6.0** (lowest yet); `:s1ref` 1.10. Rating series 2.0 → 3.0 → 2.7 → 2.9 → 3.0; the bar is 3.5.
- Caveat: review games 2–5 were generated in the long-lived dev REPL, which never reloaded `harness`, so they ran without the Runner access memory (the "known Urtica re-run" complaints in reviews 3–5 come from that; a fresh-JVM check confirms the card is tagged known). From now on review games are generated in fresh JVMs via `ai-job`.
- Remaining candidate complaints (review 5): Corp leaves R&D unprotected under repeated runs while rich (rated 1), sits on credits without advancing (3s); Runner builds economy passively, clears tags at the cost of its economy, re-runs (stale-REPL) known traps.

### 2026-10-04 20:45 — job I results; release candidate RC1; job J

Job I (frozen `i`, 299/300 seeds, all vs baseline `i0` on the same seeds; dev mix both sides, Corp proxies Corp side). Discordant pairs (+variant wins / −baseline wins), McNemar p:

| factor | Corp | Runner | proxies (Corp) |
|---|---|---|---|
| kill-threat | +6/−5 | **+13/−1 (p=0.002)** | |
| S1 corp-safety-extra 3 | +4/−3 | **+18/−6 (p=0.02)** | +5/−6 |
| rerank 6 | +28/−17 (0.14) | +41/−31 | |
| rerank-anchor | +25/−16 (0.21) | +43/−37 | +25/−17 |
| rerank-turns 2 | +20/−22 | +43/−26 (0.05) | |
| S1 react-centrals | +6/−4 | +29/−17 (0.10) | |
| credit knee 12 | +18/−15 | +32/−20 (0.13) | |
| scorable-agendas | +19/−13 | +3/−1 | +17/−11 |
| asset-econ | +14/−7 (0.19) | +6/−5 | +17/−18 |
| hq-flood | +9/−6 | +12/−6 | **+22/−8 (p=0.016)** |
| empty-remote-ice | +11/−9 | +5/−1 | |
| S1 ice-per-central 1 | +4/−2 | +3/−3 | |
| vm3 value model (w15) | +31/−43 | +54/−36 (0.07) | **+24/−51 (p=0.002)** |

No factor is significantly harmful except the value model for the Corp. Several Runner gains come from options that only change S1's *Corp* behaviour (safety-extra, react-centrals): they act through the S1 opponent model in the Runner's rollouts. Proxy Corp baseline `p0` 0.849 vs null (g_c +0.15) — up from 0.81 before the deck-out fix, still far from the bar.

**RC1** = champion + `{:rerank-anchor true :rerank 6 :s1-strong-margin 8 :w {:credit-knee2 12 :corp-safety-extra 3 :react-centrals true} :eval {:kill-threat 1 :hq-flood 1 :scorable-agendas 2.5 :asset-econ 1}}` on code `b809ef2` (all fixes to 18:20). Dev puzzles 21/21. Rejected: value model (Corp harm), rerank-turns 2 (Corp neutral, double rollout cost), empty-remote-ice and ice-per-central 1 (neutral).

**Job J** (frozen `j`, 19 threads, `rounds/R4/job-J.clj`): RC1's pre-registered held-out T2 run (seeds 900000–900299 + null); dev mix `j0` (base) vs `j-rc1`, ablations `j-rc1-nosm` (no strong margin), `j-rc1-vmR` (Runner-only value model), `j-rc1-dig` (dig-ice); Corp proxies `pj0`, `pj-rc1`, `pj-rc1-nosm`, `pj-rc1-dig`.
- **T3 puzzles, RC1 (pre-registered run, 20:52):** dev 21/21 (1.00), held-out 11/13 (0.85; missed `h-no-naked-agenda`, `h-credit-up-for-bellona`); `:s1ref` dev 14/21 (0.67), held-out 9/13 (0.69). **Puzzle criterion met** (≥0.90 dev, ≥0.80 held-out, strictly above `:s1ref`). The held-out misses are not investigated (protocol).
- **T3 held-out blind review, RC1 (pre-registered, seeds 910000–910019, one fresh Opus reviewer):** candidate **2.80** (Corp 3.4, Runner 2.2), serious blunders **0.50**, wasted clicks 13.8; `:s1ref` 1.40 / 1.90 / 15.0. Blunders ≤1.0 ✓, above `:s1ref` ✓, **rating ≥3.5 ✗**. T3 is not met with RC1. Reviewer notes on the candidate: Corp competent (3–4: idle credit clicks while rich, ice on empty remotes, an agenda behind ice the Runner already passed); Runner 2.2 (stalls for many turns clicking for credits with an empty grip and running empty Archives instead of contesting the scoring remote; early facechecks into big Weyland ice). Per protocol these held-out games are not traced; work continues on dev analogs.

### 2026-10-04 21:30 — Runner passivity work (dev analogs only)

- The Runner is the T3 gap (held-out review: Runner 2.2). A dev instance (seed 100024, worlds-2015-a): the RC1 Runner clicked for credits to 57 with an empty grip because its stack was exhausted and Ichi had trashed its barrier/sentry breakers — legitimately stuck. The broader pattern: it cannot break the scoring remote's ice and does not dig for missing breakers.
- New S1 option `:dig-breakers` (when rezzed Corp ice has types the rig cannot break and no matching breaker is in the grip, draw or play a draw event; a strong rule under `:s1-strong-margin`). New matchup set `sweep/runner-proxy`: 8 dev Corps with big or advanceable ice (Weyland/Argus/Blue Sun/HB).
- Job L (frozen `l`, 6 threads alongside J): Runner side only, 300 seeds on `runner-proxy`: RC1 (+null) vs RC1 + dig-breakers, + grip-breakers, + both, + Runner-only rerank-turns 2, + Runner-only value model.

### 2026-10-04 23:20 — Runner diagnostic review and fixes

- Diagnostic (not blind, not pre-registered): 10 RC1-Runner games vs `:s1ref` on `runner-proxy` decks, scored by one Opus reviewer with a request for recurring decision errors (`review-runner1`): mean 3.00, blunders 1.0. Recurring errors: not contesting a remote while the Corp is broke (one case: Mausolus with no advancements misread as ETR); credit clicks while rich; never using Self-modifying Code to fetch the missing fracter; Atman installed at X=0 (twice); Bank Job's breach replacement taken for 0 credits instead of accessing an advanced card; spending credits before a run that needed them; HQ tunnel vision.
- Fixes (generic): Mausolus-style "If there are N or more hosted advancement counters, end the run" is now a conditional ETR on the ice's advancements (was an unconditional ETR); S1 picks "Breach <server>" over a breach replacement unless the replacement is an event played for it or pays more than the breach is worth; takes the maximum hosted credits (was 0); sizes X for "exactly equal strength" breakers (Atman) to the most common affordable rezzed ice strength; `:dig-breakers` now prefers a stack tutor (Self-modifying Code) and the search prompt picks a breaker for a missing ice type. Dev puzzles unchanged (21/21 with the RC options; S1 18/21).

### 2026-10-05 02:05 — **T2 met on held-out decks (RC1)**; job J results

- **Pre-registered held-out T2 run, RC1** (`holdout-rc1.jsonl`, seeds 900000–900298, six held-out matchups, own null): null Corp 0.589; RC1 Corp 0.768, Runner 0.688; **g_c +1.07 [+0.74, +1.46], g_r +1.52 [+1.15, +1.87]**. Both point estimates clear the pre-registered +0.85 bar, so **T2 is met on held-out decks**; the Corp interval's lower end (+0.74) is below the bar. Per matchup (Corp / Runner win vs null Corp-win): 2023-a 0.98/0.22 (null 0.88), 2023-b 0.68/0.98 (0.14), 2024-a 0.90/0.64 (0.80), 2024-b 0.86/0.63 (0.82), 2025-a 0.65/0.78 (0.52), 2025-b 0.53/0.88 (0.37). For comparison the R3 champion's held-out Corp was ≈ +0.13.
- **Job J** (dev mix, 298 paired seeds vs new base `j0`): RC1 Corp **+33/−11 (p=0.001)**, Runner +43/−31 (p=0.20); g_c +1.87, g_r +1.91. Corp proxies RC1 **+27/−12 (p=0.024)**. Ablations vs RC1: dig-ice Corp +10/−9, Runner +17/−16, proxies +11/−6; no strong margin Corp +10/−12, Runner +21/−14 (p=0.31); Runner-only value model no Runner gain (dropped).
- T3 status for RC1: puzzles met (dev 1.00, held-out 0.85 > `:s1ref` 0.69); blind review not met (rating 2.80; Corp 3.4, Runner 2.2; blunders 0.5; above `:s1ref` 1.4). The Runner side is the remaining gap.
- Next: RC2 = RC1 + dig-ice + Runner fixes since 18:20 (Mausolus conditional ETR, breach replacements, hosted-credit choice, Atman X, tutor) + dig-breakers if jobs L/M support it; then RC2's own pre-registered held-out runs (T2, puzzles, review).
- **Job L** (Runner side only, `runner-proxy` big-ice dev Corps, ~290 paired seeds, code of 21:20): RC1 Runner 0.536 (g_r +2.20 over the null). Versus RC1: dig-breakers +15/−18, grip-breakers +39/−41, both +39/−40, Runner-only rerank-turns 2 +49/−43, Runner-only value model +46/−55 — all neutral. Dig-breakers is kept as a behavioural (T3) candidate pending job M's dev-mix check.

### 2026-10-05 03:50 — Dev review 6 (RC2 configuration) and fixes

- Diagnostic blind review on dev decks (`review-dev6`, seeds 200500–200519, RC2 config incl. dig-breakers on frozen `m`, one fresh Opus reviewer): candidate **2.90** (Corp 2.8, Runner 3.0), blunders 0.30; `:s1ref` 1.70. Compared with RC1's held-out review (Corp 3.4, Runner 2.2) the Runner improved and the Corp looked worse, but n=10 per side on different decks, so the split is noise-sized.
- Recurring candidate errors: Corp clicks for credits with a full hand while rich and discards; plays operations whose condition is not met (Psychographics at 0 tags, Preemptive Action, Economic Warfare, Audacity with nothing to advance); leaves R&D bare against heavy R&D pressure. Runner: Stimhack into Archives, redundant Archives runs, ignored fresh agendas, and breached HQ instead of taking Account Siphon's money.
- Root causes found: (1) **Account Siphon was never used**: card choices are labelled `"Title [:zone]"`, so the breach-replacement rule's event lookup failed and it always breached (affects every Siphon in every deck since 23:20). (2) Facedown Archives cards the Runner had already accessed stayed unknown to it, so Archives kept looking valuable (redundant runs, Stimhack into Archives). Fixed both; run events with core damage now cost 4 per point; dig-ice draws only below hand size. Commit `12068d8`.
- Next: trace the Corp's do-nothing operations (S1 or planner?), then job M's verdict on dig-breakers, then freeze RC2.

### 2026-10-05 04:05 — Corp passivity root cause; job N

- Traced the RC2 Corp on a review-6 seed (200504, worlds-2018-b) with S1's choice beside each planner choice. Two causes of idle credit clicks: (1) the HQ agenda terms (in-HQ 0.25·ap·7, exposure, flood) charge an agenda once drawn but agendas in R&D cost nothing, so every draw has negative expected value (≈ −0.5 at 1 HQ ice) while a credit click above the knee is +0.1; (2) every hand card is worth 0.5–1 regardless of use, so playing Hedge Fund at 30 credits (+0.4 credits value, −0.5 hand) scores below a credit click. The Corp sat at 30–76 credits holding Hedge Fund, two assets and an agenda.
- New (default off): `:eval :rd-agendas` prices R&D agendas like HQ agendas (+0.1·ap margin); `:w :rich-credit N` drops click-for-credit in the planner's search at ≥N credits when anything else is legal, and S1 draws instead when below hand size. Same seed with both: no rich credit clicks, plays Hedge Fund, draws for ice, installs and scores (still won, T18). `:rd-agendas` alone did not stop the credit clicks.
- Always on (`0b92bc0`): `dud-op?` prunes operations with no effect from card text (tag-scaled with 0 tags, Archives effects with empty Archives, advancement placers with no installed agenda, unpayable credit loss, steal punishment with no steals) in S1's `:other-op` and the planner.
- Job M interim (284 seeds): dig-breakers +7/−3 Corp, +22/−16 Runner (n.s.), dig+grip +6/−5, +25/−35. Keeping dig-breakers in RC2.
- **Job N** (frozen `n` = `58318e8`, 19 threads): RC2 base (RC1 + dig-ice + empty-remote-ice + dig-breakers, null) vs + rd-agendas, + rich-credit 20, + both, on the dev mix; Corp proxies for base and both. Pre-stated rule: RC2 takes "both" (behavioural T3 fixes) unless either side or the proxies show a loss at p<0.1; if only one hurts, take the other.
- **Job M final** (300 paired seeds, dev mix, frozen `m`): RC1 on current-at-the-time code Corp 0.930 / Runner 0.707 (g +2.09/+2.04 over its null); + dig-breakers +7/−3 (p=0.34) / +22/−17 (p=0.52); + dig + grip-breakers +6/−5 / +26/−38 (p=0.17). Dig-breakers neutral-positive → in RC2; grip-breakers stays off.

### 2026-10-05 05:55 — Job N interim: rd-agendas hurts the Corp

- At 196 paired seeds: `:rd-agendas` Corp **+3/−16 (p=0.004)**, with rich-credit +3/−17 (p=0.003); Runner unchanged. `:rich-credit 20` alone Corp +4/−2, Runner +6/−4 (neutral-positive). Likely mechanism for the harm: an R&D steal now also removes the R&D agenda cost, so the Corp's evaluator counts R&D steals ~40% cheaper and defends R&D less. Not pursuing it further now.
- Per the pre-stated rule RC2 = RC2 base + `:rich-credit 20` (no rd-agendas). Job O (T3 proxies on N's games, dev review 7 with this RC2, Runner remote-ice-prior A/B) starts when N finishes.
- **Job N final** (300 paired seeds, dev mix, frozen `n`): RC2 base Corp 0.927 / Runner 0.713 (g +2.11/+2.13). rd-agendas **+7/−25 (p=0.002)** Corp, +30/−30 Runner; both +6/−27 (p<0.001), +36/−33; rich-credit **+8/−3 (p=0.23)**, +9/−7. Corp proxies (Corp side): base 278/300, both 280/300. Rule applied: RC2 = base + rich-credit 20. rd-agendas stays off.
- Job O launched (frozen `o` = `d21f911`).

### 2026-10-05 07:40 — Job O stage 1: T3 proxies on job N's games

- Per game, tested agent (n=300 per side), base → rich-credit: Corp rich credit clicks 2.70 → 1.06, idle turns 0.89 → 0.61, forced discards 3.04 → 2.63; Runner rich credit clicks 2.63 → 0.89, idle turns 1.44 → 1.32, repeat failed runs 0.08 → 0.33 (small new cost). rd-agendas raised forced discards (3.04 → 3.92) as well as losing games. Remaining rich clicks come from S1 rollouts/anchor when the hand is full.
- Dev review 7 (seeds 200520–200539, RC2 = base + rich-credit, frozen `o`) generated; one fresh Opus reviewer scoring now. Gate for spending RC2's held-out shot: dev candidate rating ≥ 3.3.

### 2026-10-05 07:30 — Dev review 7: Runner still the gap; replay diagnosis

- Dev review 7 (RC2 = base + rich-credit, seeds 200520–200539, one fresh Opus reviewer): candidate **2.90** (Corp **3.4**, Runner **2.4**), blunders 0.80, wasted 11.1; `:s1ref` 1.80. Corp up from 2.8 (review 6), Runner down from 3.0 — within the noise of 5 games per side, but the gate (≥3.3) is not met, so RC2's held-out shot is not spent yet.
- Candidate Runner errors: running into known rezzed ice it cannot break (Cobra 6×, losing five programs; Archer; Afshar), running an empty Archives four times a turn with 20+ credits, passive while a broke Corp scores. Corp: credit clicks with a full hand; R&D left bare early.
- Exact replays (review keys now carry action logs) with S1's run calculator at every Runner run:
  - log-12: from turn 25 the Runner had 20–24 credits and ran Archives 4×/turn at content value 0 and utility 0. **Side effect of rich-credit**: with credit clicks pruned, the planner's least-bad option was a zero-value run.
  - log-07: R&D run into rezzed Archer with S1's p = 0 — the planner overrode S1.
  - log-15: Cobra has no ETR; `fire-subs` ignored "Trash a program", so letting it fire cost only 2 net damage.
- New options (off by default, `adb5538`): `:prune-runs` (planner drops plain runs with content value ≈ 0, certain failure at known ice, or on a server already failed this turn) and `:w-program` (Runner run calculator charges per program trashed by subroutines). Job P: A/B of each and both on top of RC2 (dev mix, 300 seeds), plus dev review 8 with both.
- **Job O final** (Runner side, 299 paired seeds, dev mix): remote-ice-prior 0.5 +12/−9 (p=0.66), 1.0 **+17/−11 (p=0.35)**; Runner 0.722 → 0.742. Neutral-positive; candidate for RC2 as a behavioural fix ("ignored fresh remote agendas"), to be included in the next combined check.
- Job P launched (frozen `p2` = `bff134c`): dev review 8 games (prune-runs + w-program), then the 4-arm A/B.

### 2026-10-05 08:00 — Dev review 8 (RC2 + prune-runs + w-program): 3.20

- Dev review 8 (seeds 200540–200559, frozen `p2`, one fresh Opus reviewer): candidate **3.20** (Corp 3.4, Runner 3.0), serious blunders **0.10**, wasted clicks **3.8**; `:s1ref` 1.70 / 1.80 / 18.1. Best dev review so far (reviews 6/7: 2.90/2.90; wasted 6.1/11.1), still under the 3.3 gate.
- Candidate errors left: Runner re-runs HQ into cards it already saw, plays Indexing and then takes its rearrange instead of accessing, takes Turnpike tags and pays to clear them, a Stimhack facecheck into a flatline (4-card grip vs Blue Sun); Corp advances agendas behind a single tax ice (Pop-up Window) against a rich Runner, some credit clicks while rich.
- Fixes: (1) **HQ access memory** (`a5cbfd9`): accessed HQ cards are tagged and shown to the Runner while they stay in HQ, HQ is valued per card, and the sim keeps known HQ/Archives cards rather than resampling them (the sim used to resample accessed facedown Archives cards too). Enabled per Runner spec with `[:w :hq-memory]` (binding in `tourney/play-one`) so it can be A/B'd; Runners without it (including `:s1ref`) see exactly what they saw before. (2) Run events with core damage count it against the grip in the run calculator. (3) Breach replacements: only events that gain credits (Siphon) beat a breach; others (Indexing) are worth 0.
- Caveat noted: `:s1ref` is frozen in its rules (`ref/heuristic.clj`) but shares the live knowledge modules (`servers`, `runcalc`, `cards`), so knowledge fixes shift the reference slightly. Every T2 run re-measures the null, so ratings stay relative to the reference as it is at that commit; held-out RC1 and RC2 numbers are therefore against slightly different `:s1ref`s.

### 2026-10-05 09:40 — Job P: prune-runs / w-program neutral on win rate

- Job P (300 paired seeds, dev mix, frozen `p2`): RC2 Corp 0.913 / Runner 0.697. prune-runs +3/−2, +21/−27 (p=0.47); w-program +5/−6, +21/−23; both +7/−5, +31/−33 (p=0.90). Win-rate neutral; kept for their behavioural effect (dev review 8: 3.20, wasted clicks 3.8).
- Since review 8 (all in job Q's code, `d29e008`+): HQ access memory (option), tax-ETR modelling ("End the run unless the Runner pays N" was a hard ETR, so the Corp trusted Pop-up Window as a wall), money-only event replacements (Indexing), Stimhack core damage against the grip, and `:no-naked-agendas` (planner option: no agenda installs into iceless servers unless scorable this turn — the planner's S1 Runner model almost never checks naked remotes, so rollouts rewarded naked installs; a human punishes them).
- **Job Q** (frozen `q`): dev review 9 with RC3 = RC2 + prune-runs + w-program + remote-ice-prior 1 + hq-memory + no-naked-agendas; then dev mix A/B: base (RC2 + prune + w-program, null), RC3, RC3 − hq-memory, RC3 − no-naked-agendas. If dev review 9 ≥ 3.3 → RC3 held-out confirmation (`job-K3.clj`).

### 2026-10-05 10:30 — Dev review 9 (RC3): 3.20; usage limit pause

- Dev review 9 (RC3, seeds 200560–200579, frozen `q`): candidate **3.20** (Corp 3.0, Runner **3.4**), blunders 0.20, wasted 4.5; `:s1ref` 2.20. Runner up (3.0 → 3.4), Corp down (3.4 → 3.0); still under the 3.3 gate. Candidate errors: repeated R&D runs in one turn to the same top card, Patron replacement taken over an agenda access, a skipped winning steal; Corp clicks for credits at 13–17 credits (below the rich-credit threshold of 20), agendas behind one breakable ice, Psychographics/Dedication Ceremony with no effect (dud-op? missed them: check the "faceup card" and 0-tag texts).
- Job Q (A/B of RC3 vs base, − hq-memory, − no-naked-agendas) is running in the background; results land in `eval-Q.jsonl`.
- Next: (1) job Q results; (2) fix the R&D same-top-card repeat (prune-runs should treat R&D as top-known after a non-steal access), dud-op misses, rich-credit 15; (3) dev review 10, then RC3/RC4 held-out confirmation (`job-K3.clj`) once a dev review is ≥ 3.3.

### 2026-10-05 12:55 — Job Q results; job R

- **Job Q** (300 paired seeds, dev mix, frozen `q`): base (RC2 + prune + w-program) Corp 0.913 / Runner 0.713. RC3 Corp +14/−17 (p=0.72), Runner **+26/−18 (p=0.29)**, Runner 0.740; vs RC3: without hq-memory Corp +4/−5, Runner +16/−20; without no-naked-agendas Corp +14/−15, Runner +5/−5. All neutral on win rate; RC3's behavioural parts stay (dev review 9: 3.20, Runner 3.4).
- The machine sat idle 11:30–12:50 (usage-limit pause). Job R (frozen `r`): RC3 vs RC3 with rich-credit 15 (review 9: Corp credit clicks at 13–17 credits).

### 2026-10-05 13:45 — Job R; review-9 fixes; job S

- **Job R** (300 paired seeds, dev mix, frozen `r`): RC3 Corp 0.906 / Runner 0.740; rich-credit 15 instead of 20 Corp +11/−8, Runner +10/−7 (p≈0.64). Neutral-positive → RC4 uses 15.
- Review-9 fixes via exact replays (`f085d5c`, `391bbb1`): (1) repeated R&D runs to the same top card happened after an HQ steal because the register's `:stole-agenda` is turn-wide; with access memory (`:hq-memory`) the accessed R&D card is now remembered (observe shows it, sim keeps it, pools exclude it) and R&D is valued by its known top card — on the replay the repeat runs drop to value 0, so prune-runs removes them; (2) Patron: its replacement is mandatory, and the Runner targeted R&D/Server 1 and then ran them; it now targets Archives when Archives is worth ≤ 1, else no server; (3) Archangel's paid forced encounter only against a non-empty rig; (4) never forfeit Posted Bounty for a tag; (5) Blue Sun never bounces ice guarding agendas or centrals.
- **Job S** (frozen `s`): dev review 10 with RC4 (RC3 + rich-credit 15 on the fixed code), then RC4 vs RC4 with a bigger search (max-apps 6000, beam 8).

### 2026-10-05 13:50 — Dev review 10 (RC4): **3.60** — gate met; RC4 held-out confirmation launched

- Dev review 10 (RC4 = RC3 + rich-credit 15 on frozen `s` (`9007db5`), seeds 200580–200599, one fresh Opus reviewer): candidate **3.60** (Corp 3.6, Runner 3.6), serious blunders 0.30, wasted 3.5; `:s1ref` 1.60 / 2.20 / 18.5. First dev review above 3.5 (series: 2.90, 2.90, 3.20, 3.20, 3.60). Remaining candidate errors: R&D left bare early (Corp), Siphon into a known Enigma, a Chiyashi facecheck flatline at 6 points (Runner).
- Gate (≥ 3.3) met → **RC4's pre-registered held-out confirmation** (`job-K4.clj`, run with frozen `s`'s code, identical to review 10): puzzles (RC4 and `:s1ref`, dev + held-out suites), blind-review set seeds 910000–910019 (one fresh Opus reviewer, key outside the directory), then T2 on `holdout-mix` seeds 900000–900299 with its own null. Bars unchanged (T3: puzzles ≥0.90 dev / ≥0.80 held-out / above `:s1ref`; review mean ≥ 3.5, serious blunders ≤ 1.0, above `:s1ref`; T2: g ≥ +0.85 per side).
- Job S stopped after ~50 seeds to free the CPU (bigger-search A/B to be rerun later; partial rows stay in `eval-S.jsonl`, not used).
- **T3 puzzles, RC4 (pre-registered run, 14:00):** dev 21/21 (1.00), held-out 12/13 (0.92; missed `h-credit-then-advance-to-score`); `:s1ref` dev 0.67, held-out 0.69. **Puzzle criterion met.** Held-out misses not investigated (protocol). Held-out review set generated; one fresh Opus reviewer scoring; T2 run in progress.
- **T3 held-out blind review, RC4 (pre-registered, seeds 910000–910019, one fresh Opus reviewer):** candidate **2.90** (Corp 3.0, Runner 2.8), serious blunders **1.00**, wasted 7.5; `:s1ref` 1.50 / 1.80 / 27.4. Blunders ≤ 1.0 ✓ (at the limit), above `:s1ref` ✓, **rating ≥ 3.5 ✗**. T3 not met with RC4 (RC1 was 2.80). Candidate ratings: 4,4,4,4,3,2,2,2,2,2.
- Honest reading: dev review 10 (3.60) was the first of five dev reviews over the gate and I spent the held-out shot right after it — a winner's-curse setup; with 10 candidate games per set the SD of the mean is ≈ 0.3, and the dev series (2.9, 2.9, 3.2, 3.2, 3.6) suggests a true dev level near 3.3, which held-out decks (NBN tag/kill, Jinteki traps) then pull lower. The reviewer's notes on held-out games are not used for tuning (protocol); the general patterns they name (passivity vs scoring remotes, tags vs NBN) were already on the dev list.
- Protocol change (process, not a bar): the gate for spending a held-out review becomes **two fresh dev review sets (20 candidate games) with a pooled mean ≥ 3.5**, so the gate itself is not a single noisy draw. The T3 bar stays as pre-registered.
- **Pre-registered held-out T2 run, RC4** (`holdout-rc4.jsonl`, seeds 900000–900299, own null 0.590): RC4 Corp 0.707, Runner 0.670; **g_c +0.71 [+0.38, +1.08], g_r +1.41 [+0.98, +1.77]**. The Corp point estimate is **below the +0.85 bar**: RC4 does not meet T2 (RC1 did: +1.05/+1.54 on the same seeds). Paired on the same held-out seeds RC4's Corp is worse than RC1's, +29/−47 (p=0.05); Runner +45/−51. Aggregate per matchup (no tracing, per protocol): the Corp drop is concentrated in worlds-2023-b (0.68 → 0.42, n=50).
- Status: T2 met (RC1); T3 puzzles met (RC1, RC4); T3 review not met (RC1 2.80, RC4 2.90). No single agent has passed both T2 and T3 review yet.
- Interpretation: none of the RC2–RC4 options hurt the Corp on the dev mix in isolation (N, P, Q, R), so either small effects added up, or the always-on changes since RC1 (dud-op, ETR-tax, prompt fixes, knowledge changes that also make the shared-knowledge `:s1ref` Runner opponent stronger) cost the Corp on held-out-like decks. Next: job U measures RC1 vs RC4 configs on the *current* code, Corp side, on the dev mix and the NEH/asset `corp-proxy` set (300 seeds each), alongside job T (Runner remote contesting).

### 2026-10-05 16:20 — Jobs T and U

- **Job T** (Runner side, dev mix, ~300 paired seeds, frozen `t`): RC4 Runner 0.730; remote-denial 0.5 **+22/−15 (p=0.32)**, 1.0 +21/−19, run-urgency 0.3 +10/−5 (p=0.30). All neutral-positive; candidates for RC5: remote-denial 0.5 + run-urgency 0.3, pending the ignored-advanced-remote proxy (added to `blunders.clj`, not yet computed on these games).
- **Job U** (Corp side, current code): RC4 vs RC1 configs on the dev mix +17/−22 (p=0.52), on corp-proxy +8/−14 (p=0.29). Both lean negative and in the same direction as the held-out drop (+29/−47); the RC2–RC4 Corp options may cost a little on NEH/asset-style Corps. Next: Corp-side ablation of RC4's Corp options (rich-credit, empty-remote-ice, dig-ice, no-naked-agendas) on corp-proxy, and consider a split RC5 = RC1's Corp options + RC4's Runner options (via `:corp-opts`/`:runner-opts`).
- Paused here (usage limit). Next steps in order: (1) proxies on eval-T; (2) Corp ablation on corp-proxy; (3) RC5 = Corp from RC1 + Runner from RC4 (+ remote-denial 0.5, run-urgency 0.3); two dev review sets (gate: pooled ≥ 3.5) before its held-out confirmation.

### 2026-10-05 — Job V launched (frozen `v`)

- (1) ignored-advanced-remote and other proxies on job T's Runner arms; (2) Corp-side ablation of RC4's Corp options on corp-proxy (RC1, RC4, RC4 minus dig-ice / empty-remote-ice / rich-credit / no-naked-agendas; 300 seeds each); (3) RC5 = `{:corp-opts RC1 :runner-opts RC4 + remote-denial 0.5 + run-urgency 0.3}` vs RC4 on the dev mix (both sides, with null). `tourney/play-one` now also reads `:hq-memory` from `:runner-opts`.

### 2026-10-05 — **Bug: run-calculator sign error since `adb5538` (07:18)**; job V killed, job W

- Proxies on job T: remote-denial / run-urgency barely change ignored-advanced-remote (0.58 → 0.53–0.55 per game); rich credit clicks are down to ~0.2/game. But Runner flatlines were 9%. Tracing the rate per job: J/M/N/O ≈ 4–6%, then P/Q/R/T ≈ 8–10% *for the same configuration* (n-rich 0.043 vs p-rc2 0.077; paired 13 new flatlines vs 3 gone, p≈0.02). The only code between frozen `o` and `p2` was `adb5538`, whose edit of `runcalc/terminal` turned the "damage equals grip" penalty `−3·w·d` into `+3·w·d − lost`: every Runner — candidate and `:s1ref` alike, since runcalc is shared knowledge — saw grip-emptying runs as profitable.
- Affected: jobs P, Q, R, S, T, U, dev reviews 8–10, and **RC4's held-out confirmation** (puzzles 12/13, review 2.90, T2 g_c +0.71/g_r +1.41). Those numbers stand as reported for RC4 as it was; the A/B conclusions from P–U (prune-runs, w-program, hq-memory, no-naked-agendas, rich-credit 15, remote-denial, RC1-vs-RC4 Corp) were measured with buggy Runners on both sides and need re-checking where they matter. Dev review 10's 3.60 is also suspect as a dev estimate.
- Fix `(- 0.0 (* 3 w-damage damage) lost)` plus `ai/test/monolith/ai/runcalc_check.clj`, a six-invariant check (`lein run -m monolith.ai.runcalc-check`), run before launches from now on. Relaxation logged: AGENTS.md says no tests; the goal allows checks that save time, and this one would have saved a day. Undo: delete the file.
- Job V (started on the buggy code) killed; **job W** = the same experiments on the fixed code (frozen `w`): RC4 (null) vs RC5 split on the dev mix; Corp ablation of RC4 on corp-proxy with RC1.

### 2026-10-05 20:50 — Job W (fixed code); RC6; job X

- **Job W** (300 paired seeds, frozen `w`): RC4 Corp 0.933 / Runner 0.740 (g +2.18/+2.24; Runner flatlines back to ~6%). RC5 (`{:corp-opts RC1 :runner-opts RC4 + remote-denial 0.5 + run-urgency 0.3}`) vs RC4: Corp **+11/−22 (p=0.08)** — RC1's Corp is worse than RC4's on the dev mix — and Runner **+24/−12 (p=0.065)** — the remote-contesting options help.
- Corp ablation on corp-proxy (vs RC4 0.920): RC1 +15/−11, − dig-ice **+8/−2 (p=0.11)**, − empty-remote-ice +5/−2, − rich-credit +11/−7, − no-naked-agendas +16/−9 (p=0.23). Every RC4 Corp option leans slightly negative on NEH/asset Corps (the held-out-like set) while RC4's Corp beats RC1's on the dev mix.
- **RC6** = `{:corp-opts RC4 − dig-ice, :runner-opts RC4 + remote-denial 0.5 + run-urgency 0.3}`: drops the Corp option with the clearest proxy cost, keeps no-naked-agendas for its T3 value, takes the better Runner. Job X (frozen `x`): two fresh dev review sets (11, 12; gate pooled ≥ 3.5), then RC6 vs RC4 on the dev mix and corp-proxy.
- **Dev reviews 11 + 12 (RC6, seeds 200600–200639, two fresh Opus reviewers):** candidate 3.30 and 3.20 → **pooled 3.25** (Corp 3.6 / 3.6, Runner 3.0 / 2.8), blunders 0.20 / 0.40; `:s1ref` 1.20 / 1.50. Gate (pooled ≥ 3.5) not met. The Corp is steady at ~3.6; the Runner is the limiter.
- Candidate Runner errors: Account Siphon into a known rezzed Enigma (twice, again), Inside Job past an Enigma it breaks for free, second copies of unique cards, Turntable swaps that handed the Corp points, Stimhack shrinking its own hand then drawing into discards, tag-removal loops against Turnpike/IP Block, a steal at 6 points with a 1-card grip into Punitive Counterstrike, not contesting a two-advanced remote when the Corp was one agenda from winning.
- Fixes (`f861bac`): duplicate unique installs pruned (S1 and planner); Turntable swaps only for a higher-point Corp agenda; under `:prune-runs`, run events whose every target fails for certain at known ice are pruned (bypass events excepted).
- Also since reviews 11–12: under `:remote-denial` an agenda in a remote that would win the Corp the game is valued as a winning steal (it prevents a loss); new option `:late-tag-removal` (S1 runs first and clears tags with its last clicks instead of clearing, then running back into tagging ice).
- Queued: **job Y** (frozen `y` = `a3b8599`, chained to start when job X ends): dev reviews 13 + 14 for RC7 = RC6 + late-tag-removal on the fixed code, then RC7 vs RC6 on the dev mix. If the pooled RC7 review is ≥ 3.5 → RC7's held-out confirmation (copy `job-K4.clj` with RC7's spec).

### 2026-10-05 05:30 — Matchup-awareness go/no-go (Mac, R5)

Question: does a per-matchup option book beat one global config? `rounds/R5/job-MA.clj` on code `8b8ca03`: 7 candidates vs **`:champion`** (null = champion self-play = baseline for both sides), 3 dev matchups (worlds-2016-a, worlds-2018-a, worlds-2021-b) × 160 seeds (500000–500479), 7,200 games, 1 stall. Analysis: `research/matchup_book.py` (log `rounds/R5/eval-MA.jsonl`, not committed). Mac throughput, champion vs champion: ~1,300 games/h at 17 threads.

| vs champion | Corp | Runner |
|---|---|---|
| RC1 | **+15.0 (+139/−67, p<0.001)** | +0.2 (+89/−88) |
| rerank-anchor | **+8.7 (p=0.003)** | **−6.5 (p=0.019)** |
| asset-econ 1 | +5.0 (p=0.07) | −1.3 |
| hq-flood 1 | −1.3 | **−3.5 (+7/−24, p=0.003)** |
| cse 0 / cse 3 / kill-threat | ≈0 | ≈0 |

- **Book vs global (choose on half the seeds, score on the other):** Corp −1.9 pts [95% −8.4, +5.0], Runner −3.3 [−5.2, +7.5]. The book does not beat one global config here. **No-go** for an option-selection book at this scale.
- Matchup dependence exists but is not exploitable at 80 seeds per half: RC1's effect is heterogeneous on both sides (χ² p=0.013 Corp, 0.016 Runner; Runner −10.0 on 2016-a, +11.3* on 2021-b). These are the only 2 of 14 interaction tests below 0.05.
- The candidates were mostly Corp knobs; as Runner they act only through the evaluator/opponent model, so the Runner side was a weak test.
- Side finding for the main track: RC1 is a large Corp gain head-to-head vs champion; on the Runner side rerank-anchor and hq-flood hurt, so a Runner `:runner-opts` without them is worth an A/B.
