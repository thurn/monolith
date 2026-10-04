# Handoff: Netrunner AI research (paused 2026-10-03, mid-R2/R3)

Plan: [docs/ai-research-plan.md](../docs/ai-research-plan.md). Lab notebook: [LOG.md](LOG.md). Round reports: [R0](rounds/R0/report.md), [O1](rounds/O1/report.md), [R1](rounds/R1/report.md), [O2](rounds/O2/report.md), [R2 draft](rounds/R2/report.md).

All background jobs are stopped. Nothing is running. `scripts/ci` passes.

## Where things stand

| Round | Status |
|---|---|
| R0 infrastructure | done: fork, seeded RNG/ids, harness, move gen, `random`, tourney; 0 stalls in 1,000 games, bit-reproducible |
| O1 cheap speed | done: 490 → 265 µs/action (target 200 missed by 33%) |
| R1 five strategies | done: ranking S3 > S1 > S2 > S4 >> S5; S5 killed at its kill signal |
| O2 | done (light): card-parse memoization; the forkable atom was skipped because in-thread make/unmake suffices |
| R2 improve and cull | mostly done: S1 Corp ES-tuned (+7.4 pts), S3 fixes; S2/S5 culled; Stage B run |
| R3 hybrids, Stage C | started: champion config + determinization voting coded, not yet measured |
| Final T3 evaluation | not started (tools exist: puzzle suite, game-log renderer) |

**Ladder:** T0 and T1 met. T2 (pre-registered as ≥ +0.85 Bradley–Terry side-rating over S1, i.e. ≥80% Corp / ≥58% Runner vs S1 on Stage A) is **not met**. Best agent is about +0.2 to +0.4 over S1. T3 is not evaluated yet.

## Best agent today

`:champion` (`ai/src/monolith/ai/agents/champion.clj`) = S3 turn planner + S1 anchoring (margin 2) + S1-rollout rerank of the top 3 lines, with S1 handling prompts, runs and encounters.
- Stage A, vs S1 (paired A/B, 200 seeds): Corp 0.69, Runner 0.365 (S1 self-play null: Corp ≈ 0.65 after ES tuning).
- Stage B: clearly the best Runner (S1's Corp wins only 0.59 against it vs its 0.74 null).
- Solves 14/14 tactical puzzles.

## What was in flight when paused

1. A/B `planner` vs `[:planner {:dets 3}]` (3-determinization voting), then `planner` vs `:champion`, on seeds 80000–80299, after the HQ-exposure evaluator fix. Re-run with:
   ```
   scripts/ai-job research/rounds/R2/job-r3ab.log '(require (quote monolith.ai.ab) (quote monolith.ai.agents.champion)) (println (monolith.ai.ab/run {:a :planner :b [:planner {:dets 3}] :seeds (range 80000 80300) :threads 16 :log "<abs path>/research/rounds/R2/ab.jsonl"}))'
   ```
   Expect ~2 h at 16 threads. Fill the `DETS_CHAMP` placeholder in the R2 report with the result.
2. The champion has not been re-measured since the HQ-exposure evaluator change (S3 Corp vs S1: 0.663 with it, 0.503 without, on seeds 80000+).

## Suggested next steps (in order)

0. **Check for deck overfitting before any more tuning.** Nearly all development, every A/B, both ES runs, the puzzles and S4's data used only Stage A (the beginner Gateway decks, ~32 cards). Stage B got one 100-seed round robin. The planner and champion have never played Stage C. See "Deck coverage" below.
   - Held-out check now: champion vs S1 on Stage B, C1 and C2 (≥200 paired seeds each).
   - Broaden the pool: the engine bundles every Worlds-winning matchup 2012–2025 plus the Classique decks (`jinteki.preconstructed`). Add them to `engine/decks` and run S1 and champion sweeps (stall rates + win rates) to find where the card-text vocabulary breaks.
   - Remove card-name rules from S1 (`heuristic.clj`: Seamless Launch, Docklands Pass, Pennyshaver, Verbal Plasticity, Mayfly, Manegarm Skunkworks, Send a Message) in favour of text-derived ones.
   - Extend the effect vocabulary (`knowledge/cards.clj`, `knowledge/servers.clj`) as gaps appear: tags, traces, bad publicity, recurring credits, click-loss ice, etc. Unknown effects are currently treated as harmless.
   - From then on, run A/Bs on a mix of matchups so improvements must transfer, and keep Stage C held out for confirmation.
1. Finish the in-flight A/Bs; adopt voting if it helps at acceptable cost.
2. Confirmation match, champion vs S1, 400+ seeds on Stage A and B, to report the final T2 status honestly.
3. Stage C (R3 target): S1 has generic tag/kill rules, but C2 (NEH tag-and-flatline) still stalemates in S1 self-play. It needs card-specific hooks (Oppo Research, End of the Line, asset spam).
4. T3: run the puzzle suite for the finalist and do the blind log review: render 20 logs with `monolith.ai.gamelog/play-and-render` (10 champion, 10 S1), shuffle labels, score each against the rubric in plan §7.5 with one reviewer subagent. Add puzzles from Stage B/C decks, not just Stage A.
5. Remaining improvement ideas: the S3 Runner is still the weak side (econ and draw choices). More search does not help; the evaluator does. Per-feature A/Bs need ≥300 paired seeds (ES with 100 seeds overfit).

## Deck coverage (as of the pause)

| Deck pool | What ran there |
|---|---|
| Stage A (`gateway-beginner-*`) | All S1/S3 trace-driven debugging, all A/Bs, S1 and S3 ES tuning, the 14 puzzles, S4 training data |
| Stage B (`gateway-intermediate-*`) | One 100-seed round robin (champion best Runner, Corp ≈ S1), random stall checks |
| Stage C (`worlds-2023-a/b-*`) | Random stall checks (0 stalls), 60-game S1 self-play: C1 Corp 0.88, C2 Corp 0.22 with 47 deck-out stalemates |

Stage-A-specific parts:
- the ES-tuned S1 Corp weights (no extra central ice, 3 ice on the scoring remote, bluff 0.2);
- evaluator weights fitted from Stage A S1 self-play;
- the effect-parsing vocabulary;
- the card-name rules listed above.

The planner itself (beam search, determinization, S1 anchoring, rerank) is deck-agnostic.

## Lessons worth keeping

- **The puzzle suite found the most damaging bug:** `score` wasn't offered at 0 clicks, which gave the Runner a free turn in every game.
- **The logistic fit found an evaluator sign bug** (Runner rig counted for the Corp). Fitted weights themselves are confounded; use them for signs and scale only.
- The planner farms any overvalued feature (Smartware "place 3 credits", hoarding agendas in HQ). Check new features with traces before A/Bs.
- Run long jobs with `scripts/ai-job` (detached JVM), not through the socket REPL (`scripts/ai-repl` + `scripts/ai-eval`). Client timeouts and hot reloads have broken runs (stale protocol records).
- R-NaD from scratch learned nothing in 145k games. The imitation policy alone is weak too (S4 policy: 25% Corp vs S1), but it is far ahead of R-NaD.

## How to resume

```
scripts/setup                 # keeps the vendor/netrunner fork (never re-clone it)
scripts/ai-repl &             # dev REPL on port 5555; scripts/ai-eval '<clj>' to evaluate
scripts/ai-tourney '{:mode :rr :agents [:heuristic :champion] :seeds 200 :threads 16 :out "research/rounds/R3/results.jsonl"}'
scripts/ai-report research/rounds/R3/results.jsonl
```
Engine fork: `vendor/netrunner`, branch `monolith` (head `cf45e8b`), baseline branch `upstream`. Equivalence check: `scripts/engine-equiv`.

## Moving to another machine (e.g. Windows)

Run it under **WSL2 (Ubuntu)**: every script in `scripts/` is bash, and clj-async-profiler does not support native Windows.

1. **The engine fork is not in this repo.** `vendor/` is gitignored, so its commits (seeded RNG, perf work: `7463dd9`, `75d01ec`, `c7bf430`, `cf45e8b` on branch `monolith`) live only in `vendor/netrunner/.git`. A fresh `scripts/setup` would create an empty `monolith` branch at the pin and silently lose them. Pick one way to carry them:
   - **Private remote (recommended):** create an empty *private* GitHub repo (not GitHub's Fork button, since forks of public repos are public), then `git -C vendor/netrunner push <remote> monolith upstream`. On the new machine, clone it into `vendor/netrunner` before running `scripts/setup`.
   - **Bundle in this repo:** `git -C vendor/netrunner bundle create ../../research/netrunner-monolith.bundle upstream..monolith` (a few KB). Commit it, run `scripts/setup` on the new machine, then `git -C vendor/netrunner fetch ../../research/netrunner-monolith.bundle monolith:monolith && git -C vendor/netrunner checkout monolith`. Refresh the bundle after every engine commit.
2. Install JDK 21, Leiningen, `uv` and Python 3.11+. `scripts/lein` defaults `JAVA_HOME` to the Homebrew path; export `JAVA_HOME` to the new JDK.
3. `scripts/setup` downloads `raw_data.edn` and builds the i18n file. Gitignored research artifacts are not carried over: `research/equiv/baseline-500.edn` (re-record with `scripts/engine-equiv record` on the `upstream`+seeding commits, or copy it), S4 data and nets, and the R-NaD checkpoint.
4. Thread counts are pinned at 16 in tourneys and A/Bs; set `:threads` to the new machine's core count. S3/champion results stay comparable (their search is capped by engine applications). Time-budgeted agents (S2) are not comparable across machines. Results record repo and fork SHAs.
5. The Python learners use Apple's MPS when available and fall back to CPU (only S4/S5 training).
6. `scripts/ci` expects Godot at the macOS path (override with the `GODOT` env var). It is only needed for the Godot client smoke test, not for the AI work.
7. Some commands in this file and in `LOG.md` use absolute `/Users/dthurn/...` paths; substitute the new repo root.
