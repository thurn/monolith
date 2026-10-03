# R1 report: all five strategies, v1

Stage A (`gateway-beginner-corp` vs `gateway-beginner-runner`), 250 ms/decision budget (S3 gets the turn's budget, 16× at its first click decision; app cap 2,500), 100 paired seeds per pairing-side, 10–14 worker threads. Results: `results.jsonl` (fork at `monolith` head after O1). Generated with `scripts/ai-report research/rounds/R1/results.jsonl`.

## Ratings

Round robin of S1–S3 and S5 (S4 added afterwards on the same seeds):

| Agent | Corp rating | Runner rating |
|---|---|---|
| S3 `planner` | +1.15 | +0.89 |
| S1 `heuristic` | +1.04 | +0.58 |
| S2 `ismcts` | +0.99 | +0.37 |
| S5 `rnad` | −3.18 | −4.29 |

With S4 added (same seeds; ratings refit over all games):

| Agent | Corp rating | Runner rating |
|---|---|---|
| S3 `planner` | +0.98 | +0.83 |
| S1 `heuristic` | +1.05 | +0.46 |
| S2 `ismcts` | +0.87 | +0.23 |
| S4 `neural` | +0.28 | +0.41 |
| S5 `rnad` | −3.18 | −4.27 |

S4 vs S1: 0.48 as Corp, and S1's Corp wins 0.74 against S4's Runner. Pure imitation policy (`neural-policy`, search-free): 0.25 as Corp, 0.08 as Runner vs S1.

Null rates (self-play Corp win share): S1 0.63, S2 0.64, S3 0.50, S5 0.81 (S5 self-play ends by flatline).

Key pairings (Corp win share, 100 games each):

| Corp \ Runner | S1 | S2 | S3 | S5 |
|---|---|---|---|---|
| S1 | 0.63 | 0.62 | 0.57 | 0.99 |
| S2 | 0.56 | 0.64 | 0.57 | 1.00 |
| S3 | 0.68 | 0.76 | 0.50 | 0.95 |
| S5 | 0.00 | 0.00 | 0.00 | 0.81 |

## Ladder status

- **T0 Legal:** 0 stalls in every R1 game (all agents) and in 200 random games on each of Stages B, C1 and C2.
- **T1 Sane:** S1 vs `random` 99.7% as Corp, 98.7% as Runner (300 games per side). S3 and S2 inherit S1's floor.
- **T2 Competent** (pre-registered as a Bradley–Terry side-rating gain of ≥ 0.85 over S1 on both sides): not reached. S3 is +0.11 (Corp) and +0.31 (Runner) over S1.

## Does S3 beat S2 here too? (netrunner-rs prior)

Yes. S3 vs S2: 0.76 as Corp, and S2's Corp manages only 0.57 against S3's Runner. With S1 rollouts at ~30 ms each, S2 gets ~20 iterations per decision; deviating from S1 on that noise made it worse (R1 smoke test), so v1 only deviates on a clear margin, which makes it close to S1. Same conclusion as netrunner-rs: MCTS is weaker than the turn planner at equal wall-clock.

## Failure analysis per strategy

- **S1:** Corp economy collapses in long games; Runner spends ~1/3 of clicks clicking for credits; 23% of self-play games end by Corp deck-out (stalemates). A/B on the Runner's agenda-point run value: 5 ≈ 7 > 10 > 14 > 20 (aggressive running hurts it).
- **S2:** iteration-starved (see above). Its value is as a component (multi-determinization voting in R3).
- **S3:** strongest. History of evaluator bugs found by traces (agenda overwrite, ambush rezzing, rig sign, hosted-credit farming). Runner side still loses 57% of games vs S1's Corp; it under-contests the Corp's scoring remote when the Corp is near 7 points.
- **S4:** policy prior trained on 1,180 S3 games (75k decisions): 63% top-1 agreement on held-out decisions; value head uninformative (MSE ≈ 1.0) and therefore gated off. Pruning S3's beam to the prior's top-4 (plus any ≥ 0.15) costs strength: S4 is below S3 on both sides and below S1 as Corp. The prior is not yet good enough to prune with; R2 should use it for ordering only, or train on far more data.
- **S5:** killed at the plan's signal after 145k self-play games (~6 h): 5 wins in ~1200 evaluation games vs S1, flat curve. Self-play degenerates into runner flatlines (S5 null 0.81 by flatline).

## Clairvoyant gap

`clairvoyant` (S2's search on the true state) vs S1: 0.66 as Corp, 0.60 Runner wins; honest S2 vs S1: 0.56 and 0.38. Hidden information is worth ~10 points to the Corp and ~22 to the Runner. No honest agent is close to the clairvoyant on the Runner side, so there is no leak signal.

## Puzzle scores (14 positions, solved on 2 of 3 deck orders)

| Agent | Solved |
|---|---|
| random | 6/14 |
| S1 heuristic | 11/14 |
| S2 ismcts | 8/14 |
| S3 planner | **14/14** |
| S4 neural | **14/14** |
| S4 policy only | 9/14 |
| S5 rnad | 2/14 |

## Run-calculator calibration

300 S1 self-play games, 4,042 runs, prediction from the Runner's observation with the value S1 itself used:

| Predicted | n | Actual |
|---|---|---|
| ~0 | 310 | 0.01 |
| 0.4–0.6 | 131 | 0.54 |
| 0.6–0.8 | 146 | 0.60 |
| 0.8–0.99 | 279 | 0.73 |
| ~1 | 2,988 | 0.97 |

Before the fix the calculator was badly overconfident on iced servers (2 ice: predicted 0.91, actual 0.45). The cause was Diviner, whose subroutine label says only "Do 1 net damage" while the card text ends the run on an odd-cost trash. Damage subroutines on cards with that text pattern are now treated as ETR. Note the round robin above ran on the pre-fix build.

## S5 learning curve vs S1

Evaluation every 10 actor games (clean policy, 5% threshold): 0–2 wins per 148 games per side at every checkpoint from version 0 to 363; mean agenda points per game flat (Corp 0.2–0.4, Runner 0.6–0.8). See `rnad/eval.jsonl`, `rnad/metrics.jsonl`.

## Decisions for R2

- Improve in rank order: S3 (evaluator A/B tuning, Runner pressure), S1 (Corp economy, Runner econ), S4 (more data from the improved S3), S2.
- Cull: S5 (as a standalone player) and S2 are the bottom two; both stay as components (S2 search for R3 voting, R-NaD as fine-tuning).
