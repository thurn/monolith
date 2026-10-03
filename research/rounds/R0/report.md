# R0 report: infrastructure

Fork `7463dd9`, `75d01ec` (seeding); repo `285d9d5`, `78bb027`.

## Exit criteria

| Criterion | Result |
|---|---|
| 1,000 seeded `random` vs `random` Stage A games | done, 19.5 s on 16 threads |
| Stall rate < 0.5% (livelocks included) | 0 / 1000 stalls, 0 no-op actions |
| Same seed reproduces the same game | yes: identical action lists, logs and canonical states (only wall-clock stats differ) |
| Baseline throughput + profile | 490 µs/action single thread warm; 51 games/s on 16 threads; profile in `profiles/` |

## Notes

- Random vs random: Corp wins 61.7% [58.6, 64.7], mostly by flatline (random Runners facecheck Karunā/Tithe with small hands). Average 9.2 turns, 234 actions per game.
- Parallel efficiency is ~37% of linear; the M5 Max has a mix of performance and efficiency cores, so per-thread speed is not uniform. Thread count is pinned at 16 in all runs.
- Profile: 79% of time inside engine commands, ~8% move generation. `fake-checkpoint` (recomputing ice/breaker strength, labels, subtypes, effects after every command) is the largest single cost.
