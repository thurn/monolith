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

1. Finish the in-flight A/Bs; adopt voting if it helps at acceptable cost.
2. Confirmation match, champion vs S1, 400+ seeds on Stage A and B, to report the final T2 status honestly.
3. Stage C (R3 target): S1 has generic tag/kill rules, but C2 (NEH tag-and-flatline) still stalemates in S1 self-play. Card-specific hooks are needed (Oppo Research, End of the Line, asset spam).
4. T3: run the puzzle suite for the finalist and do the blind log review: render 20 logs with `monolith.ai.gamelog/play-and-render` (10 champion, 10 S1), shuffle labels, score each against the rubric in plan §7.5 with one reviewer subagent.
5. Remaining improvement ideas: the S3 Runner is still the weak side (econ and draw choices). More search does not help; the evaluator does. Per-feature A/Bs need ≥300 paired seeds (ES with 100 seeds overfit).

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
