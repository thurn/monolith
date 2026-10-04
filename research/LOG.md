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
