# Handoff: Netrunner AI research

## Status 2026-10-08 06:50 (R4, autonomous phase)

- **Best agent:** RC32/RC33 (same spec, `rounds/R4/job-BP.clj`). Candidate RC34 = RC33 + Runner `:blind-facecheck` + Corp `:corp-poverty` 0.5 (`job-BU.clj`, frozen `bu` = `9b9138a`).
- **Ladder (held-out, pre-registered, K32 on RC32):** T2 met (g_c +1.10, g_r +1.68). T3 puzzles met. **T3 review not met: 3.00** (Corp 3.6, Runner 2.4).
- **Overnight A/Bs** (BQ, BR, BS, BT; 300 paired seeds each) were all win-rate nulls:
  - `:respect-kill`, `:runner-poverty`, `:hand-econ`: rejected.
  - `:dig-full`: rejected (forced discards up).
  - `:blind-facecheck`: facechecks −78%, into RC34.
  - `:corp-poverty`: Corp broke turn-ends −15%, into RC34.
- **Analysis of 420 reviewed dev games:**
  - The rating mostly tracks the result (r = +0.78). Candidate wins average about 3.45–3.5 and losses about 2.0–2.3.
  - So T3 needs both fewer losses and cleaner wins.
  - The automated proxies predict ratings only weakly.
  - Against `:s1ref` the win rate is saturated (~88%). Against the RC33 champion the Runner wins 63%, so strength A/Bs now use RC33 as the opponent.
- **Running:** BU, which produces the RC34 gate review sets (m33, m34, mrunner5), then RC34 vs RC33 on 300 seeds.
- **Next 3:**
  - (1) Score m33, m34 and mrunner5 with one fresh Opus reviewer per set. If the gate passes (pooled ≥ 3.5, Runner-only ≥ 3.2), run K34 held-out confirmation plus replication.
  - (2) Strength work aimed at Runner losses, measured against the RC33 Corp: uncontested scoring remotes, flatlines with a small grip.
  - (3) The tag treadmill: repeat runs through tag ice, then paying to clear.
- **Lesson:** always chain a completion waiter on long jobs. BT/BS finished at about 23:00 and the machine idled until 06:40.

## Status 2026-10-07 16:45 (R4, autonomous phase)

- **Best agent:** RC32 (`rounds/R4/job-BO.clj`, frozen `bn` = `da85838`): RC31 spec + Corp `:unaffordable-ice` 0.35. Current code adds Piranhas/audit parses, central access risk, Tread Lightly targeting, variant dev decks (RC33 reviews: 3.05 pooled, not better).
- **Ladder (held-out, pre-registered, K32 on RC32):** **T2 met** (g_c +1.10 [+0.81, +1.47], g_r +1.68; bar +0.85). T3 puzzles met (dev 0.95, held-out 1.00). **T3 review not met: 3.00** (Corp 3.6, Runner 2.4; bar 3.5). The RC32 gate pass was modern 3.65 + Runner-only 3.40; held-out Runner fell to 2.4 while variant Runner decks (mvar1) held 3.6, so the gap is not Runner card packages.
- **Measurement limits:** two-set dev review pools for RC29–RC33 sit at 3.34 ± 0.33, about review noise; single fixes no longer show up in reviews. Proxies now include `:broke-ends` and `:blind-facechecks`; economy is the top review theme on both sides.
- **Running:** BQ (Runner `:respect-kill` 0.5, `:runner-poverty` 0.5 vs RC33), BR (Corp `:corp-poverty` 0.5, Runner `:blind-facecheck` vs RC33); 300 paired seeds each, slow (re-determinized samples double think time).
- **Next 3:** (1) fold the BQ/BR winners into RC34 and review (two modern sets + Runner-only + mvar); (2) if the gate passes, held-out confirmation K34 (copy `job-K32.clj`); (3) consider a bigger lever for the Runner: a two-turn horizon or economy planning (the one-turn horizon cannot plan "credit up, Gamble next turn").

## Status 2026-10-07 12:40 (R4, autonomous phase)

- **Best agent:** RC29 (`rounds/R4/job-BI.clj`, frozen `bi` = `e98ec32`): RC26 spec + 4 re-determinized rerank samples (`:redet`) both sides, Corp `:rich-credit` 10, Runner `:kill-threat` 2.0, `:w-tag` 3.5, `:tag-exposure`; on code with today's fixes (unaffordable-ice chance node, full mid-turn planner budget, `:aid` and "Manually" no-op prunes, Sprint/Bloop/Oppo/Pinhole rules, Descent memory, known HQ kills).
- **Ladder:** T3 puzzles met (dev 21/21, held-out 13/13 on RC25 spec). Modern gate: RC29 two-sided sets pooled **3.70** (m23 3.7, m24 3.7) ✓, Runner-only set 3.00 (bar 3.2) ✗, so no held-out shot yet. Earlier held-out T3 reviews: RC1 2.80 … RC18 3.00/3.30. T2 last measured on RC18 (Corp g_c +0.40, bar +0.85).
- **Running:** BK (RC30 review sets m25/m26/mxr12 = RC29 spec + Archives value fixes + no resource installs while tagged); BJ (RC28 knobs: Corp `:rich-credit` 10 vs 15, Runner `:kill-threat` 2 vs 1, 300 seeds).
- **Next 3:** (1) score m25/m26/mxr12; if pooled ≥ 3.5 and Runner-only ≥ 3.2, copy `job-K29.clj` for RC30 and run held-out confirmation + replication; (2) Runner facechecks without matching breakers (most frequent remaining Runner note); (3) A/B the tagged-resource prune (RC30 smoke Runner dipped to 0.625 on 8 games).
- **Lessons today:** never `require … :reload` a namespace list that includes the harness (protocol reset → every game stalls); replays of games are not reproducible across code changes that alter the legal-action list; decision noise came from single determinizations, not rollout counts.

## Status 2026-10-07 07:15 (R4, autonomous phase; header previously mislabeled 10:30)

- **Best agent:** RC23 candidate (`rounds/R4/job-AW.clj`): RC12 spec + Corp `:rd-exposure` 1.0, `:score-margin` 30, no Runner-model knobs in the Corp weights; Runner `:w-damage` 3.0, `:w-tag` 2.0; on code with ~40 modern-card fixes.
- **Ladder:** T2 met only by RC1 (RC18 held-out Corp g_c +0.40). T3 puzzles met. T3 review not met: held-out RC1 2.80, RC4 2.90, RC7 3.10, RC12 2.60, RC18 3.00 (replication set 3.30; RC18 level 3.15 over 20 games).
- **Proxies:** the eight modern dev matchups (`sweep/modern-mix`), especially the four with held-out archetype identities (modern-e…h), track the held-out level (RC19 2.60 there ≈ held-out). Gate for the next held-out shot (pre-registered): two modern sets pooled ≥ 3.5 and a Runner-only modern set ≥ 3.2; a T3 pass must replicate on seeds 910020–910039.
- **T2/T3 tension:** human-like Corp choices cost win rate against `:s1ref`; dropping the Runner-model knobs from the Corp weights recovered some (AT: +30/−15 on modern decks).
- **Running:** AW (RC23 review sets). Monitor re-arm needed after 30 min.
- **Next 3:** (1) score AW; fix top errors via constructed positions (`research/diag/position.clj`) and replays (`remotediag.clj`, `runnerplan.clj`); (2) when the gate is met: held-out confirmation + replication (copy `job-K18.clj`); (3) Bellona-style steal costs vs post-break credits (the one failing dev puzzle).
- **Commit rule:** AI code only after a smoke run with 0 stalls (a paren error once broke every game).

## Status 2026-10-07 04:45 (R4, autonomous phase)

- **Best agent:** RC18 candidate (RC12 spec + Corp `:rd-exposure`, Runner `:w-damage` 3.0; `rounds/R4/job-AQ.clj`), on code with ~20 modern-card knowledge fixes since RC12.
- **Ladder:** T2 met only by RC1 (RC12 held-out g_c +0.70 < 0.85, Runner +1.68). T3 puzzles met. T3 review not met: held-out RC1 2.80, RC4 2.90, RC7 3.10, RC12 2.60. A T3 pass must now also replicate on fresh held-out seeds 910020–910039 (pre-registered 2026-10-07).
- **Key finding:** old-deck dev reviews overstate held-out quality by ~0.65. New modern Standard dev matchups (`sweep/modern-mix`, decks in `ai/resources/monolith/ai/decks/modern.edn`) match the held-out level; on them the candidate went 2.70 (RC14) → 3.40 / 3.00 / 3.10 (RC15–RC17); Runner-only modern sets 2.40 → 3.10 → 3.60. Gate for the next held-out shot: two modern sets pooled ≥ 3.5 and a Runner-only modern set ≥ 3.2.
- **T2 Corp:** corp-hard (`sweep/corp-hard`) reproduces the held-out Corp drop; the T3-motivated Corp options cost win rate there (no-naked-agendas +33/−17 when removed). Job AO tests combinations.
- **Running:** AO (corp-hard Corp combinations), AQ (RC18 modern review sets). A Monitor reports job starts/ends.
- **Next 3:** (1) score AQ's sets; fix the top errors in constructed positions (`puzzles/build` + forced actions; see `/tmp`-style scripts described in LOG) and repeat until the modern gate is met; (2) choose RC Corp options from AO (T2) without the review-visible ones; (3) held-out shot + replication when the gate is met.

## Status 2026-10-07 01:30 (R4, autonomous phase)

- **Best agent:** RC12 = RC7 + core-damage + tag-threat (both sides) + Runner rez-tax 0.5, run-ap-eval 1.0, run-click-credit 1.0 (`rounds/R4/job-K12.clj`, frozen `ah`). Dev reviews 21 + 22 pooled **3.50** (Corp 3.5, Runner 3.5); modern-deck Runner diagnostic 3.30.
- **Ladder:** T2 met only by RC1 (RC7 held-out g_c +0.55 < 0.85; Runner fine). T3 puzzles met (RC7 13/13). T3 review: RC1 2.80, RC4 2.90, RC7 3.10 (Corp 3.8, Runner 2.4); **RC12's held-out confirmation (K12) is running**.
- **Running:** K12 (puzzles → held-out review set → T2), AE (RC1's Corp on RC1's code, corp-proxy: code-regression test for the held-out Corp drop).
- **Next 3:** (1) score K12's held-out review with one fresh reviewer; if T3 passes, report and continue on T2's Corp; (2) RC13 = RC12 + always-on fixes since `ah` (Stimhack prune, no-op prune, fast run calculator) + Corp central-ice-first: modern Runner set + 2 dev sets + dev-mix A/B; (3) held-out Corp T2: AE result, then bisect code changes since RC1 on a harder Corp set than corp-proxy.
- **Method that worked this session:** Runner-only review sets on modern dev decks (Worlds 2019–2022) plus replays (`research/diag/remotediag.clj`, `icepool.clj`, `runnerplan.clj`), and uniform knowledge audits of every card in all deck lists. Each set costs ~10 min of compute and one reviewer.

## Status 2026-10-06 19:45 (R4, autonomous phase)

- **Best agent:** RC7 = `{:corp-opts RC4 − dig-ice, :runner-opts RC4 + remote-denial 0.5 + run-urgency 0.3 + late-tag-removal}` (`rounds/R4/job-K7.clj`, frozen `y`). Dev reviews 13 + 14 pooled **3.50** (Corp 3.6, Runner 3.4) = gate met. Dev mix vs `:s1ref`: Corp ~0.94, Runner ~0.81.
- **Ladder:** T2 met on held-out decks by RC1 only (RC4 Corp short, +0.71). T3 puzzles met (RC7 held-out 13/13). T3 blind review: RC1 2.80, RC4 2.90; **RC7's held-out review and T2 running now (job K7)**.
- **Running:** K7 (held-out review set seeds 910000–910019 → one fresh Opus reviewer; then T2 on holdout-mix 900000–900299), then job AA (frozen `aa`: dev reviews 15–16 for RC8 = RC7 + core-damage + tag-threat; A/B of both options).
- **Next 3:** (1) score RC7's held-out review (fresh reviewer, rubric only) and T2 → if both pass, done; (2) else RC8 via job AA's reviews/A/B; (3) Runner fixes from review notes (contesting the scoring remote when rich; Corp leaving centrals bare early).
- **Gotcha:** never `git stash -u` while jobs run: it moves their (untracked) log files away and the JVM keeps writing to the deleted inode (recover with `tail -f --pid=<pid> /proc/<pid>/fd/1`). A parallel Mac session also pushes to master (R5 matchup book), so `git pull --rebase` before pushing.

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
