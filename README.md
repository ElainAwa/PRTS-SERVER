# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge (multi-thread engineering-validation branch)

**[中文文档](./README_zh.md) · [English](./README.md)**

> [!WARNING]
> ⚠️ **This is an engineering-validation build — use with caution, do not use in production.**
> This branch carries experimental multi-thread parallelism, enabled by default.
> To fall back to stable single-thread behavior, disable the toggles in `prts-features.yml`
> (see "Branch-specific config" below).

> [!NOTE]
> **Maintenance status**: the parallel engine feature set is complete and this branch has
> reached a **stable maintenance and long-term support (LTS) phase** — from here on only
> bug fixes and maintenance updates ship; new features should go to the main-branch track.

> [!NOTE]
> This README is AI-authored and maintained, for a quick overview and feature provenance.
> Except for the multi-thread parallel tick engine, this branch is identical to the main `1.21.1`
> branch — the full feature table and provenance live in **the main branch README**, not repeated here.

## Project Origins

[Arclight](https://github.com/IzzelAliz/Arclight) (hybrid server) → [Luminara](https://github.com/CraftAmethyst/Luminara) → **this fork: PRTS** (maintained by [ElainAwa](https://github.com/ElainAwa))
→ **this branch: `1.21.1-Multithreading`** (PRTS + multi-thread parallel tick engine)

## Branch-specific: Multi-thread Parallel Tick Engine

This branch splits the single-threaded world tick across multiple worker threads to reduce lag
under heavy load. The parallel engine consists of three parts, each independently toggleable
via `prts-features.yml` (all on by default):

- **Async pathfinding** (`pathfinding-async`): mob pathfinding is computed on worker threads, off the main thread;
- **Dimension parallelism** (`dimension-parallel`): each dimension ticks on its own thread without blocking the others;
- **Region parallelism** (`region-parallel`): the overworld is split into multiple regions by chunk stripes, ticked in parallel. The region count is configurable via `region-count` (2/4/8, default 4), and `region-auto-scale` adjusts it automatically based on load.

**Stability fixes unique to this branch** (not in main):
- **Mob AI freeze fix**: in NeoForge 1.21.1 mob AI only runs on the main thread; region parallelism once left mobs standing still — now fixed
- **Bulk-kill crash fix**: `/kill`-ing large numbers of entities used to crash the server (ConcurrentModificationException) while parallel region ticking ran — now fixed
- **Entity management thread-safety**: entity add/remove/query locked to keep data consistent under parallel ticking
- **Cross-region redstone stats**: measured cross-region redstone traffic <1% — redstone machines spanning regions have negligible impact

**Generation-storm spike control & barrier robustness (v1.0.29)**:
- **Chunkgen intake budget** (`generation-tasks-per-tick`, default 50): caps how many pending generation tasks the main thread hands to the worldgen mailbox per tick — lowers average MSPT ~20% under load
- **Chunkgen submission window** (`chunkgen-inflight-limit`, default 128): at most N generation submissions per rolling 2s window, so worldgen completions arrive at a steady rate — a forceload/teleport spike max MSPT drops from ~1.5-2.3s to ~430ms, while normal exploration never hits the window
- **Watchdog barrier awareness** (`barrier-watchdog-aware`, default on): the vanilla watchdog no longer kills the server while the main thread is waiting inside the parallel dimension/region barrier during a generation storm
- **Barrier timeout diagnostics** (`barrier-timeout-ms`, default 120000): a genuinely stalled barrier wait dumps all threads and crashes with full context instead of hanging forever

## Branch-specific config (`prts-features.yml`, server root)

```yaml
# Multi-thread parallel tick engine (all on by default; false falls back to vanilla single-thread)
parallel:
  pathfinding-async: true        # async pathfinding
  dimension-parallel: true       # dimension parallelism
  region-parallel: true          # region parallelism
  region-count: 4                # region count (2/4/8, default 4; non-power-of-2 falls back to 4)
  region-auto-scale: true        # auto-scale region count by load
  region-scale-interval-seconds: 300   # evaluation interval (seconds)
  region-scale-high-mspt: 60           # scale up above this load
  region-scale-low-mspt: 15            # scale down below this load
  region-scale-stable-periods: 2       # consecutive periods required (debounce)
  region-scale-min: 2                  # scale-down floor
  region-scale-max: 8                  # scale-up ceiling
  region-scale-cross-read-ratio: 0.05  # cross-region read budget (blocks scale-up above)
# Reliable chunk save (WAL journal, off by default)
reliable-chunk-save:
  enabled: false
  interval-seconds: 30
  chunks-per-tick: 50
# Chunkgen spike control (v1.0.29; 0 = off)
generation-tasks-per-tick: 50      # intake budget: max generation submissions per tick
chunkgen-inflight-limit: 128       # submission window: max per rolling 2s (>= worldgen throughput)
# Barrier robustness (v1.0.29)
barrier-watchdog-aware: true       # watchdog ignores parallel barrier waits (prevents false kills)
barrier-timeout-ms: 120000         # barrier stall timeout (ms) before thread dump + crash
```

> Config ownership: the parallel engine and WAL are **PRTS-original features** and live in
> `prts-features.yml`; `config/servercore.yml` keeps only the ServerCore (Spigot/Paper port)
> features — the two files are strictly separated.

## Version & Artifact

- Current version: **v1.21.1-1.0.29-Multithreading**
- Build artifact: **`PRTS-neoforge-1.21.1-<version>-Multithreading.jar`** (the `-Multithreading`
  suffix marks the engineering-validation build)

## Build & Deploy

- JDK 21; command: `./gradlew --no-daemon :bootstrap:neoforgeJar`
- Deploy: copy the jar to the server root → **clear `.arclight/mod_file/*`** (forces re-extraction
  of the embedded common.jar) → start
- Full instructions match the main branch — **see the main branch README**

## Everything else

Identical to the main `1.21.1` branch (ServerCore port, entity tracking, chunk saving, async
logging, powered-rail optimization, client-mod guard, redstone/pathfinding optimizations, etc.) —
**see the main branch README for the full feature table and provenance**.

## License

[GPL v3](LICENSE), same as upstream; third-party copyrights and attributions in `THIRD-PARTY.md`.
