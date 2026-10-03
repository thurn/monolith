# O2 report: search-enabling speed

- Profiled 3 S3-vs-S3 games (`profiles/planner-collapsed.txt`). Engine internals dominate as in O1 (`gather-effects`, `get-all-cards`, `get-card`, zone scans); the top costs in our own code were card-text regex parsing (`knowledge/cards` `parse-sub`, ~3%) and observation stubbing (~2%).
- Change: memoized the pure per-title card-data functions (`parse-sub`, `printed-ice-model`, `breaker-types`, `econ-gain`, `load-credits`, `icebreaker?`, `conditional-etr?`, `trap-damage`).
- **Forkable atom: not done (relaxation).** All search (S2 trees, S3 beam, S3 rerank rollouts) runs on the game's own thread with make/unmake on the game atom plus a sim-local RNG/id counter (`monolith.ai.sim`). Parallelism comes from running many games at once, so cross-thread forks are never needed. The make/unmake path is free: a snapshot is a deref, a restore is a `reset!`.
- Determinization cost is negligible next to beam expansion (one `determinize!` per plan).
- S5 actors were not profiled: S5 was killed at R1.
