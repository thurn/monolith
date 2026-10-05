# Handoff: Netrunner AI research

## Status 2026-10-05 13:20 (R4, autonomous phase)

- **Best agent (behaviour):** RC3 = `[:champion {:rerank-anchor true :rerank 6 :s1-strong-margin 8 :w {:credit-knee2 12 :corp-safety-extra 3 :react-centrals true :dig-ice true :empty-remote-ice true :dig-breakers true :rich-credit 20 :prune-runs true :w-program 6 :remote-ice-prior 1 :hq-memory true :no-naked-agendas true} :eval {:kill-threat 1 :hq-flood 1 :scorable-agendas 2.5 :asset-econ 1}}]` (`job-K3.clj`). Win rate vs `:s1ref` on the dev mix ≈ RC1's (all new parts neutral at 300 seeds); dev blind reviews: 2.90 / 2.90 / 3.20 / 3.20 (reviews 6–9).
- **Ladder:** T2 met on held-out decks (RC1, 02:05). T3 puzzles met (RC1). T3 blind review not met (RC1 held-out 2.80; bar 3.5). Gate before spending the next held-out review: a dev review ≥ 3.3.
- **Running:** job R (RC3 vs rich-credit 15), then job S (`job-S-template.clj`: dev review 10 with RC4 = RC3 + review-9 fixes, and a bigger-search A/B).
- **Next 3:** (1) dev review 10 → if ≥ 3.3, RC4 held-out confirmation (copy `job-K3.clj`); (2) keep fixing review findings via exact replays (`review-*-key.edn` carry action logs; `research/diag/rundiag.clj`: `DIAG="<key.edn> log-NN.txt" scripts/ai-job <log> (load-file ...)`); (3) if reviews plateau near 3.2, test bigger search / two-turn rollouts for review quality.

## Status 2026-10-05 02:20 (R4, autonomous phase)

- **Best agent: RC1** = `[:champion {:rerank-anchor true :rerank 6 :s1-strong-margin 8 :w {:credit-knee2 12 :corp-safety-extra 3 :react-centrals true} :eval {:kill-threat 1 :hq-flood 1 :scorable-agendas 2.5 :asset-econ 1}}]` on code `b809ef2`.
- **Ladder (pre-registered protocol, LOG.md 2026-10-04 09:15):** T0/T1 met. **T2 met on held-out decks** (RC1: g_c +1.07 [+0.74, +1.46], g_r +1.52 [+1.15, +1.87]; bar +0.85 point estimate). Dev mix: g_c +1.87, g_r +1.91. **T3 puzzles met** (dev 21/21, held-out 11/13 vs `:s1ref` 9/13). **T3 blind review not met**: rating 2.80 (bar 3.5; Corp 3.4, Runner 2.2), blunders 0.5 (≤1.0 ok), above `:s1ref` 1.4 (ok).
- **Running:** job L (Runner-side tests on big-ice dev Corps), job M (dev mix: RC1 on current code vs + dig-breakers / + grip-breakers). Both ~04:45.
- **Next 3:** (1) RC2 = RC1 + dig-ice + empty-remote-ice + Runner fixes since 18:20 (+ dig-breakers if L/M agree); (2) RC2's pre-registered held-out runs (`job-K2-template.clj`: T2, puzzles, review set + one fresh Opus reviewer); (3) if the Runner rating is still short, more dev Runner diagnostics (review-runner style) on big-ice decks.

## R4 tooling (how experiments run now)

- **Experiments:** `monolith.ai.evalset/run-many` runs many `{:agent :tag :seeds :matchups :sides :games-log :null?}` experiments in one thread pool (seed-major, no idle tail); write the job as a `.clj` file (see `rounds/R4/job-I.clj`, `job-RC-template.clj`) and launch it with `scripts/ai-job <log> '(load-file "<abs path>")'`.
- **Frozen code per experiment:** `git worktree add /home/dthurn/monolith-frozen/<name> HEAD`, then symlink `vendor/` and `sidecar/resources/{raw_data.edn,en.ftl}` into it, and launch with that worktree's `scripts/ai-job`. Later code changes cannot confound the experiment's shared baseline. Before launching, compile in a fresh JVM (`cd ai && ../scripts/lein trampoline run -m clojure.main -e "(do (require 'monolith.ai.agents.champion) (System/exit 0))"`).
- **Analysis:** `research/summary.py <games.jsonl> <baseline-tag>` (all tags vs baseline: rates, discordant pairs, McNemar p per side, side-ratings); `research/cmp.py` (one comparison, bootstrap CIs); `research/permatchup.py`; T3 proxies with `(monolith.ai.blunders/summarize path #{tags})` in the REPL. Draw conclusions only from complete 300-seed runs.
- **Matchup sets** (`monolith.ai.sweep`): `dev-mix` (40 dev matchups), `corp-proxy` (8 dev matchups with NEH/asset Corps like the held-out ones), `holdout-mix` (6; confirmation only).
- **T3:** puzzles in `monolith.ai.puzzles` (`suite` 14 Stage A, `dev-suite` 7, `holdout-suite` 13; `check` validates by scripts); blind review: `gamelog/review-set` + `research/review-rubric.md` + one fresh Opus reviewer subagent; held-out confirmation: `monolith.ai.confirm/run`.
- **Value model:** `vfeat` (features) → `vdata` (positions, also from replayed game logs) → `ai/py/monolith_ai/vtrain.py` → `vmodel` (planner `:vmodel` option). So far neutral as a correction term.
- `scripts/ai-queue <file>` keeps 2 jobs busy from a queue file (lines `ROOT=<worktree> <log>|<expr>`); restart it after editing the script (a running copy keeps the old code).

## Earlier handoff (paused 2026-10-03, mid-R2/R3)

Plan: [docs/ai-research-plan.md](../docs/ai-research-plan.md). Lab notebook: [LOG.md](LOG.md). Round reports: [R0](rounds/R0/report.md), [O1](rounds/O1/report.md), [R1](rounds/R1/report.md), [O2](rounds/O2/report.md), [R2 draft](rounds/R2/report.md).

All background jobs were stopped and `scripts/ci` passed on the Mac at the pause. **Resuming on the Windows/WSL2 machine (2026-10-04):** setup is done; skip to "Memory and background jobs" below, then "Overnight run lost to OOM", then continue from the latest entries in `LOG.md`.

Code: `git@github.com:thurn/monolith.git` (`master`). Engine fork: `git@github.com:thurn/netrunner.git` (`master`, used locally as branch `monolith`).

## Setting up on a new machine (do this first)

You are resuming on a fresh machine; the user has given you this file and `monolith-local-files.zip`. Follow these steps in order. Each ends with a check; don't move on until it passes.

**0. Environment.** Work inside **WSL2 Ubuntu** (every script is bash). If you are in native Windows (PowerShell/cmd), ask the user to run `wsl --install -d Ubuntu` in an admin PowerShell, reboot, and open Claude Code inside the Ubuntu shell. Keep the checkout in the Linux filesystem (e.g. `~/monolith`), not under `/mnt/c` (much slower). *Check:* `uname -a` mentions Linux.

**1. Prerequisites.**
```bash
sudo apt-get update && sudo apt-get install -y git openjdk-21-jdk python3 curl zip unzip
mkdir -p ~/bin && curl -fsSL https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -o ~/bin/lein && chmod +x ~/bin/lein
curl -LsSf https://astral.sh/uv/install.sh | sh
echo 'export PATH="$HOME/bin:$HOME/.local/bin:$PATH"' >> ~/.bashrc
echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64' >> ~/.bashrc
source ~/.bashrc
lein version        # first run self-installs into ~/.lein/self-installs, which scripts/lein links to
```
*Check:* `java -version` says 21, `lein version` and `uv --version` work. If the JDK directory differs (e.g. arm64), set `JAVA_HOME` to the actual path under `/usr/lib/jvm`.

**2. GitHub access.** Both repos are cloned over SSH. *Check:* `ssh -T git@github.com` greets the user. If not, generate a key (`ssh-keygen -t ed25519`) and ask the user to add `~/.ssh/id_ed25519.pub` to their GitHub account.

**3. Clone this repo and the engine fork.** The engine fork (seeded RNG, perf work) lives in a separate repo and must sit at `vendor/netrunner` on a local branch named **`monolith`**. If that branch is missing, `scripts/setup` silently creates an empty one at the upstream pin and the engine changes are lost.
```bash
git clone git@github.com:thurn/monolith.git ~/monolith
cd ~/monolith
git clone -b master git@github.com:thurn/netrunner.git vendor/netrunner
git -C vendor/netrunner branch -m master monolith
git -C vendor/netrunner branch upstream 25c256a3131e146198b444cd2ff9a1f02ea7cc1a
```
*Check:* `git -C vendor/netrunner log --oneline -6 monolith` shows `5ce8aa5 fix: keep target order when resolving trash prevention` at the top and `25c256a Merge pull request #8748` below the five fork commits.

**4. Unpack the local files.** From the repo root, `unzip <path>/monolith-local-files.zip`. If the user didn't say where the zip is, look in `/mnt/c/Users/*/Downloads/`. It holds:
- `research/equiv/baseline-500.edn`: the engine-equivalence baseline from O1. It predates R1's move-gen changes (score at 0 clicks, auto-resolve toggles), so it no longer replays on `master`; step 6 re-records it;
- `research/rounds/R1/s4-data/`: S4 imitation data;
- `claude-memory/`: two Claude memory files (the user prefers sequential work, no parallel subagent fan-out; same rule as plan §10). Move them into this project's Claude memory directory, `~/.claude/projects/<repo path with every / replaced by ->/memory/`, then delete `claude-memory/` from the repo root.

*Check:* `git status` is clean (the unpacked paths are gitignored).

**5. Engine setup and Python deps.**
```bash
scripts/setup                  # downloads card data, builds the i18n file, keeps branch monolith
(cd ai/py && uv sync)          # PyTorch; only needed for S4/S5 training
```
*Check:* `git -C vendor/netrunner branch --show-current` prints `monolith`.

**6. Verify the engine and agents.** Both commands start a fresh JVM (first run downloads dependencies).
```bash
scripts/engine-equiv record 500   # replaces the zip's stale baseline (see step 4)
scripts/engine-equiv
scripts/ai-tourney '{:mode :match :agents [:heuristic :random] :seeds 50 :threads 4}'
```
*Expect:*
- `engine-equiv` prints `{:games 500, :diverged 0, …}`. The check runs in a fresh JVM, so this confirms seeded games reproduce across JVMs; step 3's commit check confirms the fork itself. (The O1 notes' "heap ordering" final-state diffs were JVM-dependent discard order, fixed in the fork; see `LOG.md`.)
- In the tourney, `heuristic` wins ≥97% in both seats, with 0 stalls.

**7. Machine adjustments.**
- Commands in this file use 16 threads; replace with `nproc` (or one less).
- WSL2 gets half the host RAM by default. Raise it in `%UserProfile%\.wslconfig` (`[wsl2]` / `memory=28GB` on a 32 GB host), then `wsl --shutdown`. *Check:* `free -g` in WSL.
- `scripts/ci` needs Godot (it smoke-tests the Godot client). On this machine run only its non-Godot parts: `scripts/build-dist` must succeed. The AI work never needs Godot.
- Background jobs: `scripts/ai-job <log> '<clj expr>'` runs an expression in a detached JVM from `ai/`, so give it absolute paths (use `$PWD` from the repo root). Tournaments: `scripts/ai-tourney '<edn>'` (paths relative to the repo root). Interactive dev: `scripts/ai-repl &`, then `scripts/ai-eval '<clj>'`. Read "Memory and background jobs" before launching anything.
- Results aren't comparable with the Mac's for time-budgeted agents (S2). S3/champion are capped by engine applications, so their results are deterministic and comparable.

Once step 6 passes, continue with "What was in flight" and "Suggested next steps" below, and log progress in `LOG.md` as before.

## Memory and background jobs (read before launching anything)

The WSL VM has 28 GB RAM + 8 GB swap. If it runs out, the kernel OOM killer fires, and anything sharing a systemd scope with the victim (the tmux pane, Claude Code, every job started from it) is SIGKILLed. That is what ended the 2026-10-03 overnight run.

- **Heap:** every ai/ JVM's heap cap is `AI_HEAP` (default `8g`, set in `ai/project.clj`). ParallelGC grows the heap to its cap whatever the live data, so assume each JVM's RSS reaches `AI_HEAP` + 1 GB. Measured: champion vs S1, 14 threads, peaks at ~9 GB RSS with 8g, and a full GC leaves the old generation 1–5% full, so 8g is plenty for champion and S1 A/Bs.
- **Budget:** the sum of (`AI_HEAP` + 1 GB) over all live JVMs must stay ≤ 26 GB. Typical layouts: one job at `AI_HEAP=16g` + REPL at 6g; or two jobs at 8g + REPL at 6g. Before launching, check `pgrep -c java` and `free -g`, and kill an idle REPL (`systemctl --user stop 'ai-repl-*'`) rather than overcommitting.
- **Isolation:** `scripts/ai-job` and `scripts/ai-repl` start each JVM in its own systemd user scope (`ai-job-*`, `ai-repl-*`), so an OOM kill takes out one JVM, not tmux or Claude. List them with `systemctl --user list-units 'ai-*'`. Don't start JVMs another way (`lein run …`, `nohup … &`) for anything long; `scripts/ai-tourney` runs in the foreground in your own scope, so use it only for short runs, or wrap it in `ai-job`.
- **Check for a dead job:** a log that stops growing without the final result line means the job died. `journalctl --since today | grep -i oom` shows OOM kills.
- **Make results resumable:** prefer runners that append one line per game (`:games-log` in `ab/run`, `evalset`) over ones that print only a summary at the end, so a killed job loses minutes rather than hours.

## Overnight run lost to OOM (2026-10-03 22:12)

Three JVMs at the old `-Xmx24g` were live: the held-out A/B job (~21 GB counting swap), the S1-generalization A/B job (~7 GB) and the dev REPL (~6 GB). The kernel killed the held-out JVM, and systemd then killed the whole tmux scope, including Claude and the other JVMs. Nothing survived to morning; the PC sleeping later was unrelated. All code was committed (`dc4ed3e`).
- **Survived:** held-out Stage B, `heuristic` vs `champion`, 300 seeds (90000–90299), in `rounds/R3/ab-heldout.jsonl`: S1 Corp 0.757 / Runner 0.243; champion Corp 0.807 / Runner 0.623; p≈3e-16.
- **Lost; rerun these** from the repo root (`ai-job` returns at once, so both run together; at the 8g default they fit alongside one REPL):
  ```bash
  scripts/ai-job research/rounds/R3/job-heldout-c.log "(require 'monolith.ai.ab 'monolith.ai.engine) (doseq [st [:C1 :C2]] (let [{:keys [corp runner]} (monolith.ai.engine/stages st)] (println st (monolith.ai.ab/run {:a :heuristic :b :champion :seeds (range 90000 90300) :threads 14 :decks [corp runner] :log \"$PWD/research/rounds/R3/ab-heldout.jsonl\"})) (flush)))"
  scripts/ai-job research/rounds/R3/job-ab-s1gen.log "(require 'monolith.ai.ab 'monolith.ai.sweep) (println (monolith.ai.ab/run {:a :s1ref :b :heuristic :opponent :s1ref :seeds (range 100000 100500) :threads 5 :matchups monolith.ai.sweep/dev-mix :tag \"s1gen\" :log \"$PWD/research/rounds/R3/ab.jsonl\" :games-log \"$PWD/research/rounds/R3/ab-games.jsonl\"}))"
  ```

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
   ```bash
   scripts/ai-job research/rounds/R2/job-r3ab.log "(require 'monolith.ai.ab 'monolith.ai.agents.champion) (println (monolith.ai.ab/run {:a :planner :b [:planner {:dets 3}] :seeds (range 80000 80300) :threads 16 :log \"$PWD/research/rounds/R2/ab.jsonl\"})) (println (monolith.ai.ab/run {:a :planner :b :champion :seeds (range 80000 80300) :threads 16 :log \"$PWD/research/rounds/R2/ab.jsonl\"}))"
   ```
   Run from the repo root; results append to `research/rounds/R2/ab.jsonl`. Expect ~2–3 h at 16 threads. Replace the "Pending" line in `rounds/R2/report.md` with the results.
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
- Budget memory before launching a JVM (see "Memory and background jobs"). Three 24 GB-heap JVMs on a 28 GB VM OOM-killed the whole session overnight on 2026-10-03, losing every running job.
- Run long jobs with `scripts/ai-job` (detached JVM), not through the socket REPL (`scripts/ai-repl` + `scripts/ai-eval`). Client timeouts and hot reloads have broken runs (stale protocol records).
- R-NaD from scratch learned nothing in 145k games. The imitation policy alone is weak too (S4 policy: 25% Corp vs S1), but it is far ahead of R-NaD.
