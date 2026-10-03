# Monolith feasibility prototype

A Godot 4.7 3D client that plays Netrunner against the **unmodified** Jinteki.net Clojure rules engine. The client launches the engine as a bundled JVM subprocess.

**Verdict: feasible.** The engine runs headless with no MongoDB, web server, or source patches. Godot drives it over stdio, and a full hot-seat turn cycle works: mulligan, installs, runs, and real 3D mouse picking.

## Layout

- `scripts/setup` clones `mtgred/netrunner` at a pinned commit into `vendor/`. It also downloads card data (`raw_data.edn`) from `NoahTheDuke/netrunner-data`.
- `sidecar/` is a small Leiningen project. It compiles the vendored `src/clj` and `src/cljc` trees with AOT and exposes a newline-delimited JSON protocol on stdin/stdout.
- `scripts/build-dist` produces `dist/`, which holds a jlink-trimmed JVM (`runtime/`) and `sidecar.jar`.
- `client/` is the Godot project. `sidecar.gd` spawns the process (`OS.execute_with_pipe` plus a reader thread). `main.gd` renders each side's view and turns clicks into engine actions.
- `scripts/run` launches the client. `scripts/run --autoplay` plays a scripted demo.
- `scripts/sidecar-bench` reports per-action latency.

## Protocol

```json
{"id":1,"op":"new-game"}
{"id":2,"op":"action","side":"corp","command":"play","args":{"card":{"cid":"…","zone":["hand"],"side":"Corp","type":"ICE"}}}
{"id":3,"op":"quit"}
```

- `command`/`args` map one-to-one onto the engine's `game.core.process-actions/commands` table. That is the same surface the web client's websocket uses, so JSON args work as-is.
- Each response carries `{"corp": <corp view>, "runner": <runner view>}` from `game.core.diffs/public-states`.
- The engine already does hidden-information stripping. The Runner view shows unrezzed Corp cards with no title.
- On exception, the sidecar restores the previous state, mirroring `web.game`.

## Measurements (M5 Max, JDK 21)

| | |
|---|---|
| Cold start to "ready", no CDS | ~1.1 s |
| Cold start with auto-created AppCDS archive | ~0.65–0.7 s (first launch ~1.7 s while it writes the archive) |
| Card data load | ~160 ms |
| Engine time per action | 1–7 ms (new-game ~40 ms cold) |
| Godot↔sidecar round trip | ~1.2 ms for a state fetch |
| Payload per response (both views) | 10–17 KB, growing with the log |
| RSS | ~200 MB at `-Xmx128m`, ~400 MB with default heap |
| Bundle size | 60 MB runtime + 42 MB jar (+60 MB CDS archive written to `user://` at runtime) |

## Findings and gotchas

- The `game.*` namespaces have no dependency on `web.*` or monger. The only undeclared transitive dependency was `malli`, which came in via reitit.
- `jinteki.i18n/load-dictionary!` uses `io/file` on a resource directory, so it fails inside a jar. The sidecar concatenates the English `.ftl` files at setup time and calls `insert-lang!` itself.
- Some log entries are structured `msg/type` maps rather than text. The web client renders those through fluent i18n on the client side. This prototype prints the raw type.
- JVM warnings go to **stdout** by default and would corrupt the protocol. The client passes `-Xlog:disable`, and it ignores lines that don't start with `{`.
- AppCDS archives are tied to the jar's absolute path, so a pre-built archive in `dist/` doesn't relocate. `-XX:+AutoCreateSharedArchive` with a path under `user://` self-heals instead. It needs a clean JVM exit, which is why there is a `quit` op.
- `~/.lein/profiles.clj` plugins (e.g. `ultra`) break on JDK 21. `scripts/lein` isolates `LEIN_HOME`.

## Not explored yet

- Godot export templates and a signed macOS `.app` with the runtime inside `Contents/`.
- Sending `public-diffs` instead of full views.
- GraalVM native-image, which might cut boot and RSS further. Card defs rely on runtime `eval`/dynamic loading in a few places, so it is risky.
- Networked play: the same JSON protocol could sit behind a socket.
- Mobile: no JVM subprocess on iOS or Android.
