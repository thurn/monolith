# Netrunner AI research plan

A plan for a Claude Code–driven research project to build a Netrunner AI that plays at a near-human level. We build five candidate strategies on the Jinteki.net Clojure rules engine, play them against each other in tournaments, and drop the weak ones. Between research rounds we run optimization rounds that make the engine fast enough for search.

Related:

- [README.md](../README.md): the prototype, the sidecar protocol, and measured engine latency.
- [scripts/setup](../scripts/setup): pins `mtgred/netrunner` at `25c256a3` into `vendor/netrunner`.
- [sidecar.clj](../sidecar/src/monolith/sidecar.clj): the minimal headless engine host this work builds on.
- Prior art (section 4): [Chiriboga](https://github.com/bobtheuberfish/chiriboga) and [netrunner-rs](https://github.com/lukejohannsen/netrunner-rs).

## 1. Goal and definition of done

**Goal:** one or more AI players, for both Corp and Runner, that a competent casual human finds a real opponent.

"Near-human" is defined as a ladder. Each rung is measurable:

| Tier | Meaning | How it is measured |
|---|---|---|
| T0 Legal | Finishes games without stalls or crashes | Stall rate < 0.5% over 1,000 games |
| T1 Sane | Crushes random play | ≥ 99% win rate vs `random` on both sides |
| T2 Competent | Clearly beats a hand-written expert bot | ≥ 70% vs `heuristic` v1 on both sides, measured against each side's null rate (section 7.2) |
| T3 Club | Plays like a reasonable human | Blind log review (section 7.5), plus the user winning ≤ 60% of 20+ games through the Godot client |

The project is done when one strategy (or hybrid) reaches T3 on the chosen deck pool, or when we have strong evidence about why none can, within the compute and time budget.

## 2. Constraints and non-goals

- **The rules engine is Jinteki.net's.** We don't write a new one.
- Allowed:
  - Patching engine hotspots.
  - Changing engine internals for determinism or forking.
  - Writing limited components in other languages: NN inference, a fast run-outcome estimator, the MCTS tree, featurizers.
- Not allowed: a parallel "fast simulator" that reimplements card rules. Estimators like the run calculator (section 6, S1) are allowed because they only advise. The engine stays the authority, and estimators are calibrated against it.
- This is still a disposable prototype (see [AGENTS.md](../AGENTS.md)). We write no unit tests. Correctness of engine optimizations is guarded by the upstream test suite and by seeded replay equivalence (section 9.3).
- Hardware: one M5 Max (18 cores, 64 GB). No cluster in v1.
- Agents may know the opponent's **decklist**, but not the order or the hidden identities.
  - This is a deliberate simplification: in tournaments with a known meta, humans largely know what they're facing.
  - netrunner-rs measured this as worth 0.06–0.11 win share to their Corp, so it inflates results. Stage C revisits it (section 7.1).
- **Licensing:** this repo is MIT. Chiriboga and netrunner-rs are both GPL-3.0. We borrow **designs, formulas, and lessons**, not code. No file from either project is copied or translated line by line.

## 3. Engine facts that shape this plan

I found these by reading `vendor/netrunner` at the pinned commit. Each one drives a design decision below.

**State lives in one atom and is mostly an immutable map.** Snapshots are cheap through structural sharing (`@state`). `/undo-turn` already works by saving `@state` and `reset!`-ing it later.

**The state holds closures that capture the atom itself.** Two examples:

- `register-effect-completed` stores continuation fns in `[:effect-completed eid]`.
- Prompts store `:effect` fns.

These closures refer to the specific `state` atom. So `(atom @state)` is **not** an independent fork: resuming a pending continuation in the copy mutates the original. Consequences:

- Within one atom, "save, explore, `reset!`" is safe. This is make/unmake search.
- A position can't move to a different atom by copying the map. Workers have to rebuild it by **replaying the action log** from the same seed, or we need the forkable-atom patch (section 9.2, O2).

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

**It's slow for search as hosted today.**

- The README measures 1–7 ms per action through the sidecar. That figure includes JSON, building `public-states` diffs for both sides, and the C1-only JIT that `sidecar-bench` uses.
- In-process with C2 it should be much faster, but we haven't measured that yet.
- For scale: netrunner-rs's planner spends up to 2,500 engine applications per turn plan. At 2 ms per action that would be 5 s per decision, and at 100 µs it would be 0.25 s. That is why O1 has a µs-per-action target (section 8).

**There are strong correctness assets:**

- About 71k lines of upstream card tests in `test/clj/game/cards/` (for example, 295 `deftest`s for agendas alone).
- A `do-game` test framework that can build exact board positions. We'll use it for the tactical puzzle suite.

**Real decks are bundled.** `jinteki.preconstructed` includes the Gateway beginner and intermediate decks, System Gateway, and every Worlds-winning Corp and Runner deck from 2012 to 2023.

**There is global mutable state.**

- Caches and registries: `card-defs-cache`, `turmoil/*` atoms, `say/store`, `effect-registry`, `msg-registry`.
- Most are written once at load. Before running games on many threads in one JVM, check each one for thread safety.

## 4. Prior art

Two open-source projects have built Netrunner AIs. Neither runs on the Jinteki engine, so their **numbers are priors, not facts** for us. Their designs and failures are the most useful input this plan has.

### 4.1 Chiriboga (JavaScript, rule-based)

[Chiriboga](https://github.com/bobtheuberfish/chiriboga) is a browser Netrunner with a hand-written AI opponent. It covers all of System Gateway and System Update 2021, which is close to our Stages A and B. (An earlier DQN attempt was abandoned.)

**Architecture:**

- Corp (`ai_corp.js`) and Runner (`ai_runner.js`) are each a long, ordered priority cascade. There is no lookahead over game states.
- Multi-step intents are carried between prompts in a `preferred` side channel.
- The real search lives in `runcalculator.js` plus two small breadth-first searches on the Corp side:
  - the maximum fast-advance possible this turn;
  - the maximum damage for a kill combo.

**Card knowledge:**

- About 40 kinds of per-card hooks are attached to card definitions:
  - `AIImplementIce` (24 ice) and `AIImplementBreaker` (21) for the run calculator;
  - `AIAdvancementLimit`, `AIWorthKeeping`, `AIWastefulToInstall`, `AIEconomyInstall`, and others.
- There are also many card-title lists in the central code.

**It mostly plays fair.** It reads hidden info only through "can this player see it" checks. HQ contents are inferred by a probabilistic tracker, not read.

**Ideas we adopt (S1, section 6):**

- **Corp protection score per server:**
  - ice count, weighted up for strong ice, central servers, and unrezzed ice;
  - discounted when the Runner has a matching breaker;
  - minus √(successful runs on that server);
  - for HQ, adjusted by agenda density.
- **Scoring rules:**
  - A remote counts as a scoring server only if it is at least as strong as HQ.
  - The scoring window is roughly `protection(remote) − protection(HQ) + 0.3·clicks + corp¢/(runner¢+1) − 1`.
  - Only start advancing an agenda if you can finish it.
- **Economy sufficiency:** have enough credits for the next 3 rezzes plus the cost of using known upgrades and ambushes.
- **Rez budgeting** by server value: don't spend credits needed for ice on a more valuable server.
- **Bluffing:** advance ambushes up to a bluff limit, and retire a bluff once the Runner knows the card.
- **Runner server potential:**

  | Server | Potential |
  |---|---|
  | R&D | 1, or 1.5 if HQ is empty |
  | HQ | the share of HQ not yet known |
  | Archives | 0.2 per face-down card |
  | Unknown remote | 1; 5 if advanced 1–3 times |

  Run when potential is high enough. Take a "prep" turn when a valuable server becomes reachable after one click of setup.
- **The run calculator:**
  - branch-and-bound DFS over break sequences, cheapest moves first;
  - prune useless pumps;
  - card-supplied abstract ice and breaker models over a small effect vocabulary (net damage, tags, end the run, credit loss);
  - hard limits on damage and tags, plus soft weighted costs;
  - separate modes for a full run and the cheapest bail-out;
  - unknown ice assumed pessimistic and scaled to the Corp's credits.
- Replan the cached run path when ice is rezzed or bypassed.

**Things we don't copy:**

- Hypotheticals done by mutating global state. We use state snapshots instead.
- Inline magic numbers. We use one tunable weights map.
- Card-title lists in central code. We use per-card hooks.
- Some known bad play: the Runner trashes every card it can afford, the Corp never asks the run calculator whether its servers are safe, and bluffing is fully deterministic.

### 4.2 netrunner-rs (Rust, search + RL)

[netrunner-rs](https://github.com/lukejohannsen/netrunner-rs) is a deterministic Rust engine with random, one-ply, turn-planner, ISMCTS and PUCT bots. It also has a fixed-action-space gym environment and an AlphaZero-style self-play loop.

Its roadmap docs record a lot of A/B data. The bot analysis was taken from its repo as of 2026-10-03. Headline results, quoted from their docs:

| Finding | Number |
|---|---|
| Turn planner vs `mcts@32` | planner wins 0.84 as Corp, 0.95 as Runner |
| AlphaZero-style volume runs promoted | **0 of 6** (e.g. 19,200 self-play games in about 10 h) |
| Best learned result | a policy-prior-only net scored 0.617 vs uniform search. Adding its well-calibrated value head dropped that to 0.359 |
| MCTS Runner, 1 → 2 → 4 determinization trees | 0.510 → 0.576 → 0.635, then flat from 8 to 32 trees |
| PUCT leaf eval changed to `tanh((leaf − root)/5)` | PUCT Runner went from 0.219 to 0.411 |
| Removing identical duplicate actions (3 copies of the same card in hand) | throughput 162 → 527 decisions/s, and fixed skewed search priors |
| Prompt livelocks before they were fixed | 24% of recorded decisions |
| Seat nulls (the same bot playing both sides) | Corp/Runner splits around 0.68/0.32, not 0.5 |

**Their lessons we adopt:**

- **Expect a hand-tuned turn planner to be the strongest early strategy.** S3 copies their planner design (section 6).
- **Evaluator terms that read card data beat terms keyed on card names.** Each term is A/B-tested on its own with paired seeds. Several intuitive terms measured as harmful and ship at weight 0.
- **Model the economy explicitly** in the evaluator. Their first planner lost Corp games by spending all its credits.
- **Determinizer details:**
  - Your own deck is exact.
  - Slots are typed: an ICE slot only draws ICE.
  - Install order is preserved.
  - Card pools are sorted, so sampling is deterministic.
  - The search root expands the **real** legal actions, not the sample's.
  - Log a guess-quality metric.
- **Move generation:**
  - Remove identical duplicate actions.
  - Don't let agents deselect inside multi-select prompts; that caused livelocks.
  - Cap the number of actions per decision.
  - Detect livelocks.
- **Evaluation hygiene:**
  - Measure each side's null rate before comparing anything (section 7.2).
  - Pin thread and tree counts; never derive them from the host's core count.
  - 384 games resolves about 7 points of win share.
- **The value-head failure.** A neural value head can be well calibrated and still hurt search. Gate value heads separately from policy priors (S4).
- **Difficulty knob.** Search budget made a poor difficulty dial for them. A random-action probability ε on top of the strongest bot worked. That's useful later for the Godot game; it is out of scope here.

## 5. Architecture

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
    knowledge/                       shared Netrunner knowledge used by S1, S2 and S3
      runcalc.clj                    run calculator (branch-and-bound over break sequences)
      cards.clj                      per-card hook multimethods, defaults derived from card data
      servers.clj                    protection score, server potential, HQ knowledge tracker
      weights.edn                    every tunable constant, in one place
    agents/random.clj  heuristic.clj  ismcts.clj  planner.clj  neural.clj  rnad.clj
    selfplay.clj                     actor pool for S4 and S5: trajectories out, weights in
    tourney.clj                      round-robin scheduler, results JSONL
  py/                                uv project: S4 training, S5 R-NaD learner, analysis notebooks
patches/engine/*.patch               source-level engine patches, applied by scripts/setup
research/
  LOG.md                             running lab notebook (decisions, surprises, dead ends)
  rounds/R1/ … report.md, results.jsonl, profiles/
scripts/ai-tourney  ai-profile  engine-equiv  ai-play (one game, verbose log)
```

### 5.1 The agent contract

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

- `:sim` is the **only** route to the engine an agent has. It gives search agents `determinize`, `apply-action`, `snapshot`, and `restore`, all on a state sampled from the agent's own observation. Agents never see the real `state` atom. This is the anti-cheat boundary, and it gets a review every round (section 7.4).
- Macro-actions keep branching sane:
  - "Auto-break this ice with the cheapest breaker combo" is one action, via the engine's `dynamic-ability` auto-pump/break.
  - Priority passes in run windows default to `continue` unless an agent opts in to finer control.
- Number prompts (credits, trace bids) are discretized to about 6 buckets. `card-title` prompts are limited to cards in the opponent's decklist.
- `moves.clj` follows netrunner-rs's rules:
  - It removes identical duplicate actions (two copies of the same card in hand give one "play" action).
  - Multi-select prompts are progressive: select, then confirm, with no deselect.
  - Actions per decision are capped.

### 5.2 The harness loop

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

- Every game writes its seed, agents, decks, and action-index list, so `scripts/ai-play --replay <game-id>` reproduces it exactly.
- Stalls are bucketed by cause (engine exception, no legal moves, action cap, **livelock**) and reviewed. A livelock is a repeated state hash with no click spent. Many stalls will be move-generator bugs, not engine bugs.

### 5.3 The determinizer

The determinizer samples a full game state that matches what one side can see.

- **Own hidden cards** (stack or R&D order): a uniform shuffle of what's left.
- **Opponent's hidden cards:**
  - Start from their decklist minus every card seen, with copy limits respected.
  - Assign cards to hidden slots with type constraints. An unrezzed ice slot only gets ICE; a remote root only gets assets, agendas, or upgrades. Advancement on a card shifts the odds toward agendas and traps.
- **Never resample** a card whose identity this side has seen. That includes accessed or exposed cards and the HQ cards tracked by `knowledge/servers.clj`.
- **Build sampled cards the engine's way** (`make-card` from `server-card`), so they get real subroutines and abilities. netrunner-rs shipped a bug where every sampled ice was a Barrier with no subroutines.
- **Guess quality:** log the fraction of sampled cards that are actually there, measured offline against the true state. It's a dashboard metric per round.

## 6. The five strategies

There are also two **reference agents** that don't count toward the five:

- `random`: uniform over legal actions. The floor, and the throughput benchmark.
- `clairvoyant`: S2 given the true hidden state. It cheats on purpose. The gap between it and S2 measures how much hidden information costs us.

S1, S2 and S3 share the `knowledge/` modules. The run calculator, protection score and server potential are written once and used as S1's rules, S3's evaluator features, and S2's rollout policy.

### S1. Heuristic expert (`heuristic`)

A rule-based player in the Chiriboga tradition (section 4.1), rebuilt on the Jinteki engine with Chiriboga's known mistakes fixed.

**Structure:**

- A priority cascade per side, like Chiriboga, because it's fast (S2's rollouts use it) and readable when debugging.
- Each rule is a named function, so failure analysis can say which rule fired.
- All constants live in `weights.edn`.

**Corp rules,** in roughly this order:

1. Score or finish an agenda that can be completed this turn. This uses a bounded search over (credits, clicks, hand) for fast advance.
2. Play a kill combo if the damage search says it's lethal.
3. Ice the weakest server, by protection score.
4. Install and advance in a scoring server when the scoring window is open.
5. Bluff-advance ambushes up to the bluff limit. **Unlike Chiriboga, this is randomized:** sometimes advance the agenda, sometimes the trap, with odds set in `weights.edn`.
6. Fall back to economy until economy is sufficient.

**Runner rules,** in roughly this order:

1. Remove tags when they are dangerous.
2. Run the highest-potential server the run calculator says is reachable.
3. Prep one turn when that unlocks a high-value server.
4. Complete the rig: one breaker of each type, prioritized by the known ice.
5. Economy below a credit threshold.
6. Draw.

**Corp also uses the run calculator.** It runs the calculator from the Runner's point of view, with public information only, to judge whether a remote is safe enough to score in. Chiriboga used crude proxies here instead.

**Runner trashes are valued, not automatic.** Trash only if the card's expected value to the Corp beats the credits spent. Chiriboga trashed anything affordable.

**The run calculator** (`knowledge/runcalc.clj`) follows Chiriboga's design:

- branch-and-bound DFS over break sequences, cheapest moves first;
- full-run and bail-out modes;
- pessimistic, credit-scaled guesses for unknown ice.

It differs in two ways:

- **Ice and breaker models are derived from card data**: strength, subtypes, subroutine text, and break/pump costs parsed from `raw_data.edn`. Per-card multimethod overrides cover what parsing gets wrong. This follows netrunner-rs's "read card data, not names" lesson and scales to Stage C.
- **It's calibrated against the engine.** Every real run in every tournament logs the predicted cost and outcome next to what actually happened. Prediction accuracy is reported each round.

**Card hooks:**

- `(defmulti advancement-limit :title)`, `worth-keeping`, `wasteful-to-install`, and so on, borrowing Chiriboga's vocabulary.
- Defaults come from card data, and per-card methods are added only where tournament losses point.
- Chiriboga's per-card notes for System Gateway are a reading list for writing them.

**Assessment:**

- **Why it might win:** fast, explainable, and cheap to improve by reading losses.
- **Why it might lose:** brittle against specific cards, with combinatorial blind spots.
- It doubles as the rollout policy for S2 and the default prompt resolver for S3. It is also the first fixed opponent that S5's learning curve is measured against. Its quality compounds.

### S2. Information-set MCTS (`ismcts`)

Single-observer ISMCTS (Cowling, Powley & Whitehouse 2012).

- **Root-parallel:** 4 trees, each with its own determinization, with root statistics merged by action. netrunner-rs found 4 to be the point where more trees stopped helping.
- Leaves: an S1-policy rollout truncated at N turns, then the S3 evaluator scored **relative to the root** as `tanh((leaf − root)/k)`. netrunner-rs got its single largest PUCT gain from that change.
- A cheaper rollout option to try in R1: random actions weighted by action type (score/steal 8, run 3, install 2.5, play/advance 2, rez 1.5, end turn 0.5), from netrunner-rs.
- Progressive widening handles large action sets. Decisions are made on macro-actions.
- **Why it might win:** the standard strong approach for imperfect-information card games.
- **Why it might lose:**
  - Per-iteration engine cost. With no forks, each iteration is restore plus maybe 50–500 engine actions. This strategy depends most on the optimization rounds.
  - Prior evidence is against it: netrunner-rs's MCTS lost 0.84/0.95 to their planner. They also found sampling helps the Runner (limited by uncertainty) more than the Corp (limited by depth).
  - If R1 confirms this, S2's value is as a component (an R3 hybrid with S3), not as a standalone player.

### S3. Turn planner with a tuned eval (`planner`)

The netrunner-rs planner design, which was the strongest bot in that project.

**Search:**

- At the first decision of our own turn, sample **one** determinization.
- Beam-search action lines to the end of the turn:
  - beam 6, plus the best line under each distinct first action;
  - a budget of about 2,500 engine applications.
  - The extra line per first action matters: it stops "install, advance, advance, score" from being cut at ply 1 just because "install" scores badly on its own.
- **The opponent is frozen:** every priority window is answered with a pass.
- **One node of minimax for opponent yes/no prompts.** Without it, netrunner-rs's planner never played cards whose effect depends on the opponent's answer.
- **A run ends the line.** The evaluator scores it with mid-run terms, using run-calculator outputs. The run itself is then played one decision at a time by a one-ply chooser.
- **Merge transpositions:** two click orders that reach the same state hash are the same line.
- **Follow the plan while reality matches its prediction.** If the real legal actions differ from what the plan expected, replan.

**Evaluator:**

- A linear sum over named features:
  - agenda points;
  - **an explicit economy model**: clicks valued as credits, income from installed cards over a horizon, and a rez reserve;
  - the protection score per server;
  - server potential;
  - breaker coverage of known ice;
  - advancement threats;
  - tags and damage exposure.
- Features read card data, not card names.

**Tuning:**

- Each feature is added in its own branch and A/B-tested with paired seeds (section 7.2). It ships only if it clears a pre-registered threshold.
- Expect some intuitive features to measure as harmful and ship at weight 0.
- Once the feature set is stable (R2), CMA-ES self-play can fine-tune the weights together.

**Assessment:**

- **Why it might win:** strong prior evidence (section 4.2), it matches how humans think ("what's my best turn?"), and its cost is bounded per turn.
- **Why it might lose:**
  - A frozen opponent means no defensive foresight.
  - One determinization per turn means strategy fusion under uncertainty.
  - The evaluator is only as good as its features.

### S4. Learned policy/value network (`neural`)

This is the **search-based** learning strategy. S5 is the search-free one. netrunner-rs's results set expectations here: six AlphaZero-style runs promoted nothing, but a policy prior alone did help.

- **v1, policy prior only:**
  - A policy net trained to imitate S3's chosen actions in tournament games.
  - Used as a move-ordering prior in S3's beam and S2's tree.
- **Value head:**
  - Trained on game outcomes, but used in search **only if it wins its own A/B** against the hand-written evaluator.
  - Good calibration (MSE) doesn't count as evidence that it helps.
- **v2, AlphaZero-lite:** visit-count targets from S2's or S3's search. Attempt it only if v1's prior gains are real.
- Actions are variable, so the net scores `(state features, action features)` pairs.
- **Training and inference:**
  - Train in Python (PyTorch MPS or MLX).
  - Run inference in the JVM via ONNX Runtime Java, batched across concurrent games. netrunner-rs measured 199 µs per unbatched call and a 2.6× gain from batching.
  - That's a limited non-Clojure component.
- **Why it might win:** it can learn patterns we would never hand-write, and it plugs into S2 and S3.
- **Why it might lose:** data hunger, plus the value-head problem netrunner-rs never solved. v1 keeps to card-ID embeddings over a fixed deck pool.

### S5. Regularized Nash Dynamics (`rnad`)

R-NaD is the model-free, game-theoretic self-play algorithm from DeepMind's DeepNash (Perolat et al., *Mastering the game of Stratego with model-free multiagent reinforcement learning*, Science 2022). It converges toward a Nash equilibrium in two-player zero-sum imperfect-information games without any search.

How it works, in brief:

- **Reward transformation:** each player's reward is penalized by `η · log(π(a|o) / π_reg(a|o))`, which pulls the learned policy `π` toward a regularization policy `π_reg`. This turns the game into one where learning dynamics converge instead of cycling.
- **Dynamics:** train `π` to the fixed point of that regularized game using NeuRD (Neural Replicator Dynamics) policy updates and a V-trace–style value estimator for off-policy correction.
- **Update:** set `π_reg ← π` and repeat. The sequence of fixed points approaches a Nash equilibrium of the original game.
- **Test-time cleanup:** as in DeepNash, drop actions below a probability threshold and discretize the rest, so the policy doesn't make rare low-probability blunders.

Why it fits Netrunner:

- Netrunner's skill is largely **bluffing and mixed strategy**:
  - Corp: which face-down card is the agenda, advance-and-bluff remotes, and when to rez.
  - Runner: which server to pressure and when to call a bluff.
  - Explicit mixed decisions: psi games and trace bids.
- Determinized search (S2, S3) is known to handle bluffing badly ("strategy fusion"). R-NaD learns mixed strategies directly and is hard to exploit by construction.
- **It needs no forking and no determinizer.** Actors only play games forward, so it sidesteps the closure-in-state problem from section 3. Its only engine requirement is raw parallel throughput.

Implementation plan:

- **Actors** run in the JVM: engine games in self-play, policy inference through the same ONNX Runtime path and featurizer as S4. Each trajectory step records the observation features, legal-action features, chosen action, behavior-policy probabilities, and reward.
- **Learner** runs in Python. Port DeepMind's reference implementation in OpenSpiel (`open_spiel/python/algorithms/rnad/`, JAX) to PyTorch/MLX, or run it on CPU JAX if that's fast enough.
- Trajectories go from actors to learner as batched files or over a local socket. Weights go back to the actors every N learner steps.
- **Netrunner is asymmetric**, so use either one network with a side input or two networks (Corp and Runner) trained together. Start with one shared trunk and two policy heads.
- S4 and S5 share the featurizer and network architecture on purpose. That way a comparison between them tests the algorithm (search-based vs Nash self-play), not the network.

Assessment:

- **Why it might win:** the strongest theoretical fit for hidden information and bluffing, almost no inference cost at play time, and a stochastic policy that feels human rather than robotic.
- **Why it might lose:**
  - Sample hunger. DeepNash trained at a scale far beyond one laptop.
  - netrunner-rs's learned agents never beat their own search, which is a warning sign.
  - We bet that Netrunner on a fixed small deck pool is much smaller than Stratego in the parts that matter, and Stage A's decks are the test of that bet.
- **Kill signal:** after a fixed training budget, it doesn't beat S1 more than 50% and the learning curve against S1 is flat. Then R-NaD is moved to R3 as a fine-tuning method over an S3- or S4-imitation policy instead of starting from scratch.
- Training gets a fixed **wall-clock budget per round**, the same as S4, for example 24 hours of background self-play in R1. That keeps the comparison fair.

### Shared compute budget

Agents are compared at **equal wall-clock per decision**. The default is 250 ms for tournament screening and 2 s for confirmation matches.

- S3 plans once per turn, so it gets the turn's budget at its first decision.
- S5 uses far less than its budget, since inference is microseconds.
- Training compute for S4 and S5 is budgeted separately per round, in wall-clock hours.

## 7. Evaluation methodology

### 7.1 Deck pool, staged

1. **Stage A:** `gateway-beginner-corp` vs `gateway-beginner-runner`. Small card pool, already used by the prototype, and fully covered by Chiriboga's card notes.
2. **Stage B:** the System Gateway intermediate decks.
3. **Stage C:** two or three Worlds-winning pairings, for example 2023 Sokka Corp vs Runner. These are the T3 target.
   - Stage C also runs a variant where the determinizer gets a format-pool prior instead of the exact decklist. That measures how much the known-decklist simplification is worth.

A strategy moves to the next stage once it passes its tier gate on the current stage. Every card a stage adds needs move-gen coverage first, checked with `random` stall rates.

### 7.2 Tournament format

- Round-robin. Every pairing plays both sides, because Corp and Runner are separate agents and the matchup is asymmetric.
- **Measure each side's null rate first.** Play each agent against itself on the same seeds to get its Corp/Runner win split, and judge results against that, not 0.5. netrunner-rs's seat splits were around 0.68/0.32 and fooled them more than once.
- **Paired seeds:** game *k* of A-vs-B and game *k* of B-vs-A use the same shuffle seed, which cuts variance. Paired A/B comparisons use McNemar's test.
- **Pin everything:** binaries (git SHA), decks, thread counts, tree counts, and budgets are recorded per run and never derived from the host.
- Ratings come from a Bradley–Terry fit per side. Win rates are reported with Wilson 95% CIs.
- **Sample sizes:**
  - About 400 games per pairing-side detects a 60/40 edge; netrunner-rs found 384 games resolves about 7 points.
  - About 1,500 games detects 55/45.
  - Promotion decisions ("is v2 better than v1?") use SPRT, as in Stockfish's fishtest. That stops early when the answer is clear.
- Throughput: suppose about 200 decisions per game at 250 ms on 16 workers. That's about 50 s of thinking per game, or roughly 1,100 games/hour, if the engine isn't the bottleneck. Round 0 measures the real numbers.

### 7.3 Metrics logged per game

- Winner and win type, length in turns and actions, and agenda points per side.
- Think time and engine time per decision.
- Stall cause.
- Run-calculator predictions vs actual outcomes.
- Determinizer guess quality.
- The agent version hashes.

### 7.4 Anti-cheat review

Each round, the session does one review pass over every agent's code, looking for paths to hidden information. The check: anything outside `ctx`, or anything that reads the real `state`.

The `clairvoyant` gap is a sanity check. If an "honest" agent matches `clairvoyant` too closely, treat it as a leak until proven otherwise.

### 7.5 Human-likeness review (for T3)

- **Blind log review:** Claude (`claude-opus-5-5`) gets 20 game logs, half from the candidate and half from S1. It scores each against a fixed rubric:
  - Wasted clicks.
  - Facechecking with no reason.
  - Unprotected agendas left in a server.
  - Missed lethal or missed scores.
  - Economy collapse.
- **Human games:** the user plays 20+ games through the Godot client. This needs an `ai` op added to the sidecar. Results and qualitative notes go in `research/LOG.md`.
- **Tactical puzzle suite:** about 30 positions built with the upstream `do-game` helpers, each with a known best action ("win this turn," "don't run into this," "score now"). The score is the fraction solved within the budget. It gives fast, low-noise signal between tournaments.

## 8. Round schedule

Each round ends with a written report in `research/rounds/<id>/report.md` and a **stop-and-report checkpoint** with the user before the next one starts.

**R0: infrastructure (no strategies yet)**

- Build `ai/`: harness, seeded RNG and counter IDs, move gen, observation, determinizer, `random`, and the tournament runner.
- Exit criteria:
  - 1,000 seeded `random` vs `random` games on Stage A.
  - Stall rate < 0.5%, livelocks included.
  - The same seed reproduces the same game bit-for-bit.
  - Baseline throughput (games/s, µs per action) and a first CPU profile recorded.

**O1: cheap speed (before any search exists)**

- Profile `random` games, then apply the low-risk wins in section 9.1.
- Exit criteria:
  - The equivalence check passes.
  - Measured speedup, with a target of **≤ 200 µs per engine action**. At that speed, S3's 2,500-application plan fits in about 0.5 s.
  - If the target is missed, report how far off it is and adjust S3's budget before R1.

**R1: build all five, v1**

Build everything **sequentially**, one piece at a time, in this order. Each step builds on the ones before it:

1. `knowledge/`: run calculator, protection score, server potential, and card hooks.
2. **S1**, which uses step 1. It also becomes the fixed opponent and the rollout policy for later steps.
3. **Shared NN infrastructure, then S5:**
   - the featurizer, the ONNX inference path, and the `selfplay.clj` actor pool;
   - then the R-NaD learner.
   - Start S5's training budget as a background process right away, so it trains on the CPU while steps 4–6 are written. Pause it during benchmark and profiling runs.
4. **S3**, which reuses step 1 as evaluator features.
5. **S2**, which uses S1 rollouts and S3's evaluator.
6. **S4**, which imitates S3 and reuses step 3's infrastructure.

Each step ends with a smoke match against `random` and S1 before the next step starts. Then run the full round-robin on Stage A at 250 ms.
- Output:
  - first ratings and failure analysis per strategy;
  - the clairvoyant gap;
  - puzzle scores;
  - run-calculator calibration;
  - S5's learning curve against S1.
- The report should check the netrunner-rs prior explicitly: does S3 beat S2 here too?

**O2: search-enabling speed**

- Profile **inside S2 and S3**, not `random`. Search workloads hit different paths (restore, determinize, eval).
- Profile the S5 actor pool too. It is pure forward play plus inference, so its bottlenecks are featurization, batching, and games/s per core.
- Do the forkable atom, cheaper determinization, and the hotspots S2 exposes. See section 9.2.

**R2: improve and cull**

- Each strategy gets one improvement cycle aimed at its R1 failure analysis, one strategy at a time. Go in R1 rank order, so the leaders improve first if the round runs short. S3 gets its per-feature A/B tuning pass here.
- Tournament on Stage A, then Stage B.
- **Cull the bottom two**, unless one of them is clearly valuable as a component (for example, S1 as a rollout policy). Culled strategies are kept as opponents and components.

**O3: deep optimization**

- Target whatever dominates the survivors' profiles. Typical candidates:
  - Effect-recomputation caching.
  - Native or JVM-level NN inference batching.
  - Moving the MCTS tree to a primitive-array Java implementation.

**R3: hybrids**

- Combine the survivors. Likely combinations:
  - S3's planner with S2-style multi-determinization voting at the root, to reduce strategy fusion.
  - S4's policy prior inside S3's beam.
  - S5's policy as the opponent model inside S3, instead of a frozen opponent.
  - R-NaD fine-tuning starting from an S3- or S4-imitation policy.
- Run on Stage C at 2 s/decision.

**Final: T3 evaluation**

- Human games, blind log review, puzzle suite, and a final report with the ladder results for every surviving agent.

## 9. Optimization playbook

All speed work is measured, not guessed. Profile with `clj-async-profiler` (flamegraphs into `research/rounds/<id>/profiles/`) and the already-bundled `tufte`. Each change is recorded with its before/after µs per action, and engine patches land one at a time so each speedup can be attributed.

### 9.1 O1 candidates (low risk)

- Host the engine in-process. No JSON, no `public-states` diff on every action; observations are built only when an agent asks.
- Make logging a no-op in AI games: `system-msg`, `system-say`, toasts, and sfx, via `alter-var-root`. First verify that no game logic reads `:log`.
- Run full C2 JIT with a warmup phase, and try `ParallelGC` with a large young generation.
- Run games on parallel threads in one JVM once the global atoms in section 3 are audited. Otherwise use one JVM per worker.
- Switch off any schema or instrumentation checks in hot paths.
- Remove identical duplicate actions in move gen. That was a 3× throughput gain for netrunner-rs, from search alone.

### 9.2 O2 candidates (enable search)

- **Forkable state.** A `deftype` implementing `IAtom2`/`IDeref` that delegates to a per-thread backing reference. Engine closures capture the wrapper, so a fork is O(1) and can cross threads. This is the most valuable and riskiest patch. Prototype it early in O2, and fall back to make/unmake plus replay.
- Make determinization cheap: swap hidden card identities in place (title and static fields from `server-card`) instead of rebuilding.
  - **Risk:** hidden cards that register events while unrezzed or in hand.
  - Audit the deck pool's cards for this.
- A fast, canonical state hash (ignoring logs and IDs), used for S3's transposition merging and for livelock detection.
- Index `get-card` and zone lookups if profiles show linear scans.

### 9.3 Correctness safety net for engine changes

- `scripts/engine-equiv`:
  - Record 500 seeded `random` games on the pristine engine as an action list plus a state hash after each action. The hash strips logs and IDs.
  - Replay them on the patched engine.
  - Any hash divergence fails.
- Run the upstream card test namespaces that cover touched code (`lein test game.cards.ice-test` and so on) before each optimization lands.
- Engine source changes live as ordered files in `patches/engine/`, applied by `scripts/setup`. Function-level overrides live in `engine_patches.clj`. The vendored checkout stays reproducible.

## 10. Running this with Claude Code

- **Sequential, not parallel.** Work happens one step at a time on the main branch, in the order section 8 gives. Fanning out to parallel subagents or workflows exhausts Claude usage limits, so don't do it.
  - Subagents are allowed only for an occasional single, bounded lookup.
  - Parallelism lives in the **compute**, not in Claude: tournaments, tuning and self-play run as multi-threaded background processes.
- **One session per step.** Within a round, each strategy or optimization step gets its own session, which keeps context small. A session opens by reading this doc, `research/LOG.md`, and the latest round report. It closes by writing a short handoff entry in the log.
- **Prior-art lookups:** when writing a rule or evaluator term, read the matching Chiriboga or netrunner-rs code directly with targeted greps (clones under `vendor/prior-art/`, gitignored). GPL code is never pasted or translated into this repo (section 2).
- **Lab notebook discipline:**
  - Every surprising result, dead end, or decision gets a dated entry in `research/LOG.md`.
  - Results are append-only JSONL. Reports cite run IDs.
- **Commit cadence:** commit immediately and often, with Conventional Commits (`feat(ai): …`, `perf(engine): …`, `docs(research): …`), per AGENTS.md. Every `perf` commit message includes its measured speedup.
- **Long runs** (tournaments, tuning, self-play) run as background jobs that write progress files. The session checks on them instead of blocking.
- **Guardrails:**
  - Background jobs, including S4 and S5 training, get wall-clock limits.
  - Training runs checkpoint regularly, so a stopped run can resume.
  - No AI game makes network calls. All play is local.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Engine too slow for MCTS even after O2 | S2 and S4-v2 underperform | S3 is per-turn bounded; S4-v1 is offline; root-parallelize across 16 workers |
| Engine too slow for S3's planning budget | The strongest prior strategy is crippled | O1 target of ≤ 200 µs per action; shrink the beam and budget; run lines in parallel across workers |
| Forkable-atom patch breaks subtle engine behavior | Silent rules bugs | `engine-equiv` hashes, upstream tests, and keeping make/unmake as a fallback |
| Move gen misses legal actions | Agents are strictly weaker, invisibly | Compare against `playable?` highlights, and log "engine accepted an action we didn't generate" during human games |
| Prompt livelocks | Stalls, wasted compute (24% of decisions at one point in netrunner-rs) | Progressive selection, state-hash livelock detection, stall bucketing |
| Hidden-info leak via `:sim` | Inflated results | Anti-cheat review and clairvoyant-gap check every round |
| Seat bias misread as agent strength | False conclusions | Per-side null rates measured before any comparison |
| Engine soft-locks on rare card interactions | Stalls, noisy results | Watchdog, stall bucketing, and excluding stalled games from ratings, reported separately |
| Run calculator's card-data parsing is wrong for some cards | S1 and S3 misjudge runs | Calibration log vs engine outcomes, with per-card overrides where it's off |
| R-NaD needs far more self-play than one machine can produce | S5 stays weak | Small Stage A pool, fixed training budget with a kill signal, fallback to R-NaD fine-tuning from an imitation policy in R3 |
| JAX → PyTorch/MLX port of R-NaD introduces subtle bugs | S5 learns nothing, and we blame the algorithm | Check the port on a small OpenSpiel game (for example Leduc poker) against the reference implementation's exploitability before using it on Netrunner |
| A learned value head hurts search despite good calibration | S4 is a net negative | Gate the value head separately from the policy prior |
| GPL code leaks into the MIT repo | Licensing problem | Ideas only; prior-art code is read for reference but never copied or translated |
| Overfitting to Stage A decks | Fails on Stage C | Stage gates; keep Stage C held out until R3 |

## 12. Open questions for the user

1. **Training budget:** how many wall-clock hours of background self-play per round can the machine run for S4 and S5? The plan assumes about 24 hours in R1.
2. **Stage C decks:** are the Worlds 2023 decks the right target, or is there a meta you'd rather play?
3. **Human games:** are you willing to play about 20 games per finalist? That needs the `ai` sidecar op and a small client change.
4. **Known decklists:** OK to let agents know the opponent's decklist (section 2)? netrunner-rs measured this as worth 0.06–0.11 to the Corp.
5. **Engine patches:** fine to keep them as a patch series against the pinned commit, or do you want a fork of `mtgred/netrunner`?
6. **Licensing:** OK with "ideas, not code" from the GPL projects? The alternative is relicensing this repo as GPL-3.0, which would let us port Chiriboga's card hooks directly.
