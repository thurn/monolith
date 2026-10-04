# R2 report: improve and cull

All A/Bs are paired (same seeds, both seats) against S1 and use McNemar's test on discordant pairs (`ab.jsonl`). S3 games are deterministic replays (the 2,500-application cap binds).

## S3 (rank 1 in R1)

| Change | Corp vs S1 | Runner vs S1 | p | Decision |
|---|---|---|---|---|
| baseline | 0.635 | 0.345 | | |
| rerank 3 (S1 rollouts through the opponent's turn) | 0.670 | 0.350 | 0.54 | in champion |
| beam 10, 5,000 apps | 0.615 | 0.360 | 1.0 | rejected: search budget is not the bottleneck |
| ice weight 0.5 | 0.535 | 0.365 | 0.13 | rejected |
| S1 anchor, margin 2 | 0.665 | 0.390 | 0.27 | in champion |
| anchor 2 + rerank 3 | 0.690 | 0.365 | 0.24 | = champion |
| ES-tuned evaluator (Corp) | 0.590 (vs 0.657) | | | **rejected: failed validation** (100-seed generations selected noise) |
| HQ-agenda liability scaled by HQ exposure | 0.663 (vs 0.503 with the term off) | | | adopted |

Pending: the A/Bs of 3-determinization voting and of the champion after the HQ-exposure fix were stopped at the handoff (see `../../HANDOFF.md`).

## S1 (rank 2)

- Diviner-style conditional ETR modelled from text: run-calculator calibration on iced servers fixed (2 ice: predicted 0.91 vs actual 0.45 → 0.53 vs 0.48).
- ES on Corp weights, validated on 500 fresh paired seeds: Corp 0.598 → 0.672 (p=0.007), adopted. ES on Runner weights: +2 points (p=0.45), not adopted.
- Runner run agenda-point value: 5 ≈ 7 > 10 > 14 > 20 (monotone; aggressive running hurts).
- Generic Stage C rules (meat-damage kill, trash resource when tagged, tag operations, remove tags): neutral on Stage A. Generic Runner installs: Stage A Runner 0.35 → 0.17, gated off.

## S4 (rank 4) and S2 (rank 3)

- S4: no new training data cycle (the teacher keeps changing). The R1 finding stands: prior pruning hurts the planner.
- S2: unchanged; iteration-starved at 250 ms.

## Stage B (`stageB-summary.md`, 100 seeds per pairing-side)

| Agent | Corp rating | Runner rating |
|---|---|---|
| S3 champion config | +0.13 | +0.06 |
| S1 | +0.35 | −0.85 |
| S2 | +0.18 | −0.84 |
| S4 | −0.66 | −0.72 |

The champion config is by far the best Runner on Stage B. One move-gen livelock was found and fixed (Conduit's auto-resolve toggle).

## Cull

Bottom two are **S5** (R1 kill signal) and **S2** (iteration-starved, below S1). Both remain components: R-NaD for fine-tuning, MCTS-style multiple determinizations as voting inside the planner (R3). S4 is kept as an ordering prior, but not used for pruning.
