# Netrunner AI research plan

A plan for a Claude Code–driven research project to build a Netrunner AI that plays at a near-human level. We build five candidate strategies on the Jinteki.net Clojure rules engine, play them against each other in tournaments, and drop the weak ones. Between research rounds we run optimization rounds that make the engine fast enough for search.

Related:

- [README.md](../README.md): the prototype, the sidecar protocol, and measured engine latency.
- [scripts/setup](../scripts/setup): pins `mtgred/netrunner` at `25c256a3` into `vendor/netrunner`.
- [sidecar.clj](../sidecar/src/monolith/sidecar.clj): the minimal headless engine host this work builds on.

## 1. Goal and definition of done

**Goal:** one or more AI players, for both Corp and Runner, that a competent casual human finds a real opponent.

"Near-human" is defined as a ladder. Each rung is measurable:

| Tier | Meaning | How it is measured |
|---|---|---|
| T0 Legal | Finishes games without stalls or crashes | Stall rate < 0.5% over 1,000 games |
| T1 Sane | Crushes random play | ≥ 99% win rate vs `random` on both sides |
| T2 Competent | Clearly beats a hand-written expert bot | ≥ 70% vs `heuristic` v1 on both sides |
| T3 Club | Plays like a reasonable human | Blind log review (section 6.5), plus the user winning ≤ 60% of 20+ games through the Godot client |

The project is done when one strategy (or hybrid) reaches T3 on the chosen deck pool, or when we have strong evidence about why none can, within the compute and time budget.

## 2. Constraints and non-goals

- **The rules engine is Jinteki.net's.** We don't write a new one.
- Allowed:
  - Patching engine hotspots.
  - Changing engine internals for determinism or forking.
  - Writing limited components in other languages: NN inference, a fast run-outcome estimator, the MCTS tree, featurizers.
- Not allowed: a parallel "fast simulator" that reimplements card rules. If a component needs to know what a card does, it asks the engine.
- This is still a disposable prototype (see [AGENTS.md](../AGENTS.md)). We write no unit tests. Correctness of engine optimizations is guarded by the upstream test suite and by seeded replay equivalence (section 8.3).
- Hardware: one M5 Max (18 cores, 64 GB). No cluster in v1.
- Agents may know the opponent's **decklist**, but not the order or the hidden identities. This is a deliberate simplification: in tournaments with a known meta, humans largely know what they're facing.

## 3. Engine facts that shape this plan

I found these by reading `vendor/netrunner` at the pinned commit. Each one drives a design decision below.

**State lives in one atom and is mostly an immutable map.** Snapshots are cheap through structural sharing (`@state`). `/undo-turn` already works by saving `@state` and `reset!`-ing it later.

**The state holds closures that capture the atom itself.** Two examples:

- `register-effect-completed` stores continuation fns in `[:effect-completed eid]`.
- Prompts store `:effect` fns.

These closures refer to the specific `state` atom. So `(atom @state)` is **not** an independent fork: resuming a pending continuation in the copy mutates the original. Consequences:

- Within one atom, "save, explore, `reset!`" is safe. This is make/unmake search.
- A position can't move to a different atom by copying the map. Workers have to rebuild it by **replaying the action log** from the same seed, or we need the forkable-atom patch (section 8.2, O2).

**Randomness is not seedable.**

- `game.core.shuffling/rng` is a `defonce` `SecureRandom` seeded from entropy.
- Other paths use `clojure.core/shuffle`, `rand-nth`, and `rand-int`. Examples: random hand trashes in `damage.clj` and `costs.clj`, `mark.clj`, and archives access order.
- IDs are random too:
  - `make-cid` uses `random-uuid`.
  - Prompt choices use `uuid/v4`.
  - Effects and events use `uuid/v1`.
- Fix: seed every source from a per-game `SplittableRandom` bound in a dynamic var, and replace the ID functions with per-game counters. We need this for replay, paired tournaments, and debugging.

**There is no legal-move generator.** The engine accepts commands from `process-actions/commands` and throws or no-ops on bad ones. These pieces exist to build one from:

- `game.core.diffs/playable?` and `ability-playable?`, which drive the web UI's highlighting.
- The `generate-install-list` and `generate-runnable-zones` commands.
- Prompt state: `:choices` lists, `{:card …}`/`:req` selectors, `{:number …}`, `:credit`, `:card-title`, `:psi`, `:trace`, and `:run` prompt types.

**It's slow for search as hosted today.** The README measures 1–7 ms per action through the sidecar. That figure includes JSON, building `public-states` diffs for both sides, and the C1-only JIT that `sidecar-bench` uses. In-process with C2 it should be much faster, but we haven't measured that yet.

**There are strong correctness assets:**

- About 71k lines of upstream card tests in `test/clj/game/cards/` (for example, 295 `deftest`s for agendas alone).
- A `do-game` test framework that can build exact board positions. We'll use it for the tactical puzzle suite.

**Real decks are bundled.** `jinteki.preconstructed` includes the Gateway beginner and intermediate decks, System Gateway, and every Worlds-winning Corp and Runner deck from 2012 to 2023.

**There is global mutable state.**

- Caches and registries: `card-defs-cache`, `turmoil/*` atoms, `say/store`, `effect-registry`, `msg-registry`.
- Most are written once at load. Before running games on many threads in one JVM, check each one for thread safety.

## 4. Architecture

Everything runs in-process on the JVM. The JSON sidecar stays the Godot client's interface; the AI work doesn't go through it.

```
ai/                                  Leiningen project; source paths include the vendored engine (like sidecar/)
  src/monolith/ai/
    harness.clj                      seeded game loop, decision points, watchdog, action log
    moves.clj                        legal action generation (shared by every agent)
    observe.clj                      per-side redacted observation
    determinize.clj                  sample a full state consistent with an observation
    fork.clj                         snapshot/restore and cross-worker replay
    engine_patches.clj               alter-var-root overrides: RNG, IDs, logging, hotspots
    agents/random.clj  heuristic.clj  ismcts.clj  planner.clj  neural.clj  llm.clj
    tourney.clj                      round-robin scheduler, results JSONL
  py/                                uv project: NN training, analysis notebooks
patches/engine/*.patch               source-level engine patches, applied by scripts/setup
research/
  LOG.md                             running lab notebook (decisions, surprises, dead ends)
  rounds/R1/ … report.md, results.jsonl, profiles/
scripts/ai-tourney  ai-profile  engine-equiv  ai-play (one game, verbose log)
```

### 4.1 The agent contract

```clojure
(defprotocol Agent
  (choose [agent ctx]
    "ctx = {:side :corp, :obs <redacted view>, :actions [<action> ...],
            :budget-ms 250, :rng <SplittableRandom>, :sim <simulator handle>}
     Returns an index into :actions."))

;; An action is plain data that the harness turns into an engine command.
{:command "run"    :args {:server "R&D"}}
{:command "choice" :args {:choice {:uuid "..."}} :label "Pay 2 credits"}
{:command "play"   :args {:card {:cid "..."}} :label "Hedge Fund"}
```

- `:sim` is the **only** route to the engine an agent has. It gives search agents `determinize`, `apply-action`, `snapshot`, and `restore`, all on a state sampled from the agent's own observation. Agents never see the real `state` atom. This is the anti-cheat boundary, and it gets a review every round (section 6.4).
- Macro-actions keep branching sane:
  - "Auto-break this ice with the cheapest breaker combo" is one action, via the engine's `dynamic-ability` auto-pump/break.
  - Priority passes in run windows default to `continue` unless an agent opts in to finer control.
- Number prompts (credits, trace bids) are discretized to about 6 buckets. `card-title` prompts are limited to cards in the opponent's decklist.

### 4.2 The harness loop

```clojure
(loop [n 0]
  (cond (game-over? state) (result state)
        (> n max-actions)  (stall! state :action-cap)   ; counted separately from losses
        :else (let [side    (decision-side state)        ; prompt owner, else active player w/ clicks
                    actions (moves/legal state side)]
                (if (empty? actions) (stall! state :no-moves)
                  (do (apply! state (nth actions (choose (agent side) (ctx state side actions))))
                      (recur (inc n)))))))
```

Every game writes its seed, agents, decks, and action-index list, so `scripts/ai-play --replay <game-id>` reproduces it exactly. Stalls are bucketed by cause (engine exception, no legal moves, action cap) and reviewed. Many will be move-generator bugs, not engine bugs.

## 5. The five strategies

There are also two **reference agents** that don't count toward the five:

- `random`: uniform over legal actions. The floor, and the throughput benchmark.
- `clairvoyant`: S2 given the true hidden state. It cheats on purpose. The gap between it and S2 measures how much hidden information costs us.

### S1. Heuristic expert (`heuristic`)

A rule-based player written by Claude from Netrunner strategy knowledge.

- **Corp:** ice centrals before remotes, keep a scoring remote, never over-advance without the credits to protect it, and score out when the Runner is poor.
- **Runner:** stay above an economy floor, find breakers, run R&D/HQ when the run calculator says it can get in, and check remotes when they're advanced.
- Includes a **run calculator**: can I get through this server with these breakers and credits, given known and unknown ice? It's a limited component used by every strategy.
- **Why it might win:** fast, explainable, and cheap to improve by reading losses.
- **Why it might lose:** brittle against specific cards, with combinatorial blind spots.
- It doubles as the rollout policy for S2 and the default prompt resolver for S3 and S5. Its quality compounds.

### S2. Information-set MCTS (`ismcts`)

Single-observer ISMCTS (Cowling, Powley & Whitehouse 2012).

- Each iteration determinizes hidden information, then descends a tree keyed by the observer's information set.
- Leaves are evaluated with an S1-policy rollout truncated at N turns, then a static eval.
- Progressive widening handles large action sets. Decisions are made on macro-actions.
- **Why it might win:** the standard strong approach for imperfect-information card games, and it needs no domain knowledge beyond the eval.
- **Why it might lose:** per-iteration engine cost. With no forks, each iteration is restore plus maybe 50–500 engine actions. This strategy depends most on the optimization rounds.

### S3. Turn planner with a tuned eval (`planner`)

Netrunner turns are short click sequences (Corp 3, Runner 4).

- Beam-search our own turn's action sequences on one or a few determinizations.
- Score end-of-turn states with a weighted eval: agenda points, credits, cards, ice strength on each server, breaker coverage, advancement threats, tags, and so on.
- Weights are tuned by CMA-ES self-play (Texel-style tuning).
- The opponent is modeled implicitly by the eval, plus an optional 1-ply response from S1.
- **Why it might win:** it matches how humans think ("what's my best turn?"), and its cost is bounded per turn.
- **Why it might lose:** a weak opponent model, and the eval is only as good as its features.

### S4. Learned policy/value network (`neural`)

- **v1:** a value net trained on positions from S1–S3 tournament games, labeled by outcome. A policy head is trained to imitate the round's winner.
- **v2:** self-play improvement. Pick PPO with action masking, or AlphaZero-lite over S2's search, based on round-2 data.
- Actions are variable, so the net scores `(state features, action features)` pairs.
- Train in Python (PyTorch MPS or MLX). Run inference in the JVM via ONNX Runtime Java with batched calls. That's a limited non-Clojure component.
- **Why it might win:** it can learn eval features we would never hand-write, and it plugs in as S2's leaf eval and S3's eval.
- **Why it might lose:** data hunger. Self-play volume depends on engine speed, and featurizing card text is hard. v1 keeps to card-ID embeddings over a fixed deck pool.

### S5. LLM planner (`llm`)

Claude picks a **turn plan** from a text rendering of the observation and the legal macro-actions. S1 resolves the low-level prompts within that plan.

- Uses `claude-sonnet-5-5` for play. `claude-opus-5-5` is reserved for log review and is never used as a player.
- Load the `claude-api` skill before implementing.
- **Why it might win:** real strategic knowledge of Netrunner, and the most "human-shaped" play.
- **Why it might lose:** latency, cost, and arithmetic slips (for example, miscounting credits for a run).
- It plays a **reduced schedule** (about 50–100 games per round) under a hard spend cap agreed with the user before round 1.
- It also has offline uses: writing S1 rules from loss analysis, and labeling positions for S4.

### Shared compute budget

Agents are compared at **equal wall-clock per decision**. The default is 250 ms for tournament screening and 2 s for confirmation matches. S5 is exempt and judged separately on quality per dollar.

## 6. Evaluation methodology

### 6.1 Deck pool, staged

1. **Stage A:** `gateway-beginner-corp` vs `gateway-beginner-runner`. Small card pool, already used by the prototype.
2. **Stage B:** the System Gateway intermediate decks.
3. **Stage C:** two or three Worlds-winning pairings, for example 2023 Sokka Corp vs Runner. These are the T3 target.

A strategy moves to the next stage once it passes its tier gate on the current stage. Every card a stage adds needs move-gen coverage first, checked with `random` stall rates.

### 6.2 Tournament format

- Round-robin. Every pairing plays both sides, because Corp and Runner are separate agents and the matchup is asymmetric.
- **Paired seeds:** game *k* of A-vs-B and game *k* of B-vs-A use the same shuffle seed, which cuts variance.
- Ratings come from a Bradley–Terry fit per side. Win rates are reported with Wilson 95% CIs.
- **Sample sizes:**
  - About 400 games per pairing-side detects a 60/40 edge.
  - About 1,500 games detects 55/45.
  - Promotion decisions ("is v2 better than v1?") use SPRT, as in Stockfish's fishtest. That stops early when the answer is clear.
- Throughput: suppose about 200 decisions per game at 250 ms on 16 workers. That's about 50 s of thinking per game, or roughly 1,100 games/hour, if the engine isn't the bottleneck. Round 0 measures the real numbers.

### 6.3 Metrics logged per game

Winner and win type, length in turns and actions, agenda points per side, think time per decision, engine time per decision, stall cause, and the agent version hashes.

### 6.4 Anti-cheat review

Each round, a reviewer subagent reads every agent's code for paths to hidden information. The check: anything outside `ctx`, or anything that reads the real `state`.

The `clairvoyant` gap is a sanity check. If an "honest" agent matches `clairvoyant` too closely, treat it as a leak until proven otherwise.

### 6.5 Human-likeness review (for T3)

- **Blind log review:** Claude (`claude-opus-5-5`) gets 20 game logs, half from the candidate and half from S1. It scores each against a fixed rubric:
  - Wasted clicks.
  - Facechecking with no reason.
  - Unprotected agendas left in a server.
  - Missed lethal or missed scores.
  - Economy collapse.
- **Human games:** the user plays 20+ games through the Godot client. This needs an `ai` op added to the sidecar. Results and qualitative notes go in `research/LOG.md`.
- **Tactical puzzle suite:** about 30 positions built with the upstream `do-game` helpers, each with a known best action ("win this turn," "don't run into this," "score now"). The score is the fraction solved within the budget. It gives fast, low-noise signal between tournaments.

## 7. Round schedule

Each round ends with a written report in `research/rounds/<id>/report.md` and a **stop-and-report checkpoint** with the user before the next one starts.

**R0: infrastructure (no strategies yet)**

- Build `ai/`: harness, seeded RNG and counter IDs, move gen, observation, determinizer, `random`, and the tournament runner.
- Exit criteria:
  - 1,000 seeded `random` vs `random` games on Stage A.
  - Stall rate < 0.5%.
  - The same seed reproduces the same game bit-for-bit.
  - Baseline throughput (games/s, µs per action) and a first CPU profile recorded.

**O1: cheap speed (before any search exists)**

- Profile `random` games, then apply the low-risk wins in section 8.1.
- Exit criteria: measured speedup, and the equivalence check passes.

**R1: build all five, v1**

- Implement S1–S5 in parallel, one worktree per strategy. Everything depends only on the shared `ai/` contract.
- Run the full round-robin on Stage A at 250 ms. S5 plays a reduced schedule.
- Output: first ratings, failure analysis per strategy, the clairvoyant gap, and puzzle scores.

**O2: search-enabling speed**

- Profile **inside S2 and S3**, not `random`. Search workloads hit different paths (restore, determinize, eval).
- Do the forkable atom, cheaper determinization, and the hotspots S2 exposes. See section 8.2.

**R2: improve and cull**

- Each strategy gets one improvement cycle aimed at its R1 failure analysis.
- Tournament on Stage A, then Stage B.
- **Cull the bottom two**, unless one of them is clearly valuable as a component (for example, S1 as a rollout policy). Culled strategies are kept as opponents and components.

**O3: deep optimization**

- Target whatever dominates the survivors' profiles. Typical candidates:
  - Effect-recomputation caching.
  - Native or JVM-level NN inference batching.
  - Moving the MCTS tree to a primitive-array Java implementation.

**R3: hybrids**

- Combine the survivors. Likely combinations:
  - S4 net as S2's prior and leaf eval.
  - S3's eval inside S2.
  - S5 as a high-level planner over S2 tactics.
- Run on Stage C at 2 s/decision.

**Final: T3 evaluation**

- Human games, blind log review, puzzle suite, and a final report with the ladder results for every surviving agent.

## 8. Optimization playbook

All speed work is measured, not guessed. Profile with `clj-async-profiler` (flamegraphs into `research/rounds/<id>/profiles/`) and the already-bundled `tufte`. Each change is recorded with its before/after µs per action, and engine patches land one at a time so each speedup can be attributed.

### 8.1 O1 candidates (low risk)

- Host the engine in-process. No JSON, no `public-states` diff on every action; observations are built only when an agent asks.
- Make logging a no-op in AI games: `system-msg`, `system-say`, toasts, and sfx, via `alter-var-root`. First verify that no game logic reads `:log`.
- Run full C2 JIT with a warmup phase, and try `ParallelGC` with a large young generation.
- Run games on parallel threads in one JVM once the global atoms in section 3 are audited. Otherwise use one JVM per worker.
- Switch off any schema or instrumentation checks in hot paths.

### 8.2 O2 candidates (enable search)

- **Forkable state.** A `deftype` implementing `IAtom2`/`IDeref` that delegates to a per-thread backing reference. Engine closures capture the wrapper, so a fork is O(1) and can cross threads. This is the most valuable and riskiest patch. Prototype it early in O2, and fall back to make/unmake plus replay.
- Make determinization cheap: swap hidden card identities in place (title and static fields from `server-card`) instead of rebuilding.
  - **Risk:** hidden cards that register events while unrezzed or in hand.
  - Audit the deck pool's cards for this.
- Index `get-card` and zone lookups if profiles show linear scans.

### 8.3 Correctness safety net for engine changes

- `scripts/engine-equiv`:
  - Record 500 seeded `random` games on the pristine engine as an action list plus a state hash after each action. The hash strips logs and IDs.
  - Replay them on the patched engine.
  - Any hash divergence fails.
- Run the upstream card test namespaces that cover touched code (`lein test game.cards.ice-test` and so on) before each optimization lands.
- Engine source changes live as ordered files in `patches/engine/`, applied by `scripts/setup`. Function-level overrides live in `engine_patches.clj`. The vendored checkout stays reproducible.

## 9. Running this with Claude Code

- **One session per round.** The session opens by reading this doc, `research/LOG.md`, and the previous round's report.
- **Parallel strategy work:** in R1 and R2, the orchestrating session spawns one subagent per strategy, each in its own git worktree, against the frozen `ai/` contract. Contract changes go through the orchestrator only.
- **Lab notebook discipline:**
  - Every surprising result, dead end, or decision gets a dated entry in `research/LOG.md`.
  - Results are append-only JSONL. Reports cite run IDs.
- **Commit cadence:** commit immediately and often, with Conventional Commits (`feat(ai): …`, `perf(engine): …`, `docs(research): …`), per AGENTS.md. Every `perf` commit message includes its measured speedup.
- **Long runs** (tournaments, CMA-ES, self-play) run as background jobs that write progress files. The session checks on them instead of blocking.
- **Guardrails:**
  - S5 spend is capped.
  - Background jobs get wall-clock limits.
  - There is no network access beyond the Claude API for S5.

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Engine too slow for MCTS even after O2 | S2 and S4-v2 underperform | S3 is per-turn bounded; S4-v1 is offline; root-parallelize across 16 workers |
| Forkable-atom patch breaks subtle engine behavior | Silent rules bugs | `engine-equiv` hashes, upstream tests, and keeping make/unmake as a fallback |
| Move gen misses legal actions | Agents are strictly weaker, invisibly | Compare against `playable?` highlights, and log "engine accepted an action we didn't generate" during human games |
| Hidden-info leak via `:sim` | Inflated results | Anti-cheat review and clairvoyant-gap check every round |
| Engine soft-locks on rare card interactions | Stalls, noisy results | Watchdog, stall bucketing, and excluding stalled games from ratings, reported separately |
| S5 costs balloon | Budget | Turn-level calls, reduced schedule, hard cap |
| Overfitting to Stage A decks | Fails on Stage C | Stage gates; keep Stage C held out until R3 |

## 11. Open questions for the user

1. **S5 budget:** what spend cap per round for Claude API play?
2. **Stage C decks:** are the Worlds 2023 decks the right target, or is there a meta you'd rather play?
3. **Human games:** are you willing to play about 20 games per finalist? That needs the `ai` sidecar op and a small client change.
4. **Known decklists:** OK to let agents know the opponent's decklist (section 2)?
5. **Engine patches:** fine to keep them as a patch series against the pinned commit, or do you want a fork of `mtgred/netrunner`?
