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
