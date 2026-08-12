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
under heavy load. The parallel engine consists of three independently toggleable parts:

- **Async pathfinding** (`pathfinding-async`): mob pathfinding runs on worker threads, off the main thread.
- **Dimension parallelism** (`dimension-parallel`): each dimension ticks on its own thread without blocking the others.
- **Region parallelism** (`region-parallel`): the overworld is split into regions by chunk stripes and ticked in parallel. The region count is configurable via `region-count` (2/4/8, default 4); `region-auto-scale` adjusts it based on load.

### Chunkgen spike control & barrier robustness (v1.0.29)

Generation-storm spikes can saturate the worldgen mailbox and stall parallel barriers. Two budgets
and two watchdog hooks keep the engine responsive:

- **Chunkgen intake budget** (`generation-tasks-per-tick`, default 50): caps the pending generation tasks the main thread hands to the worldgen mailbox per tick — lowers average MSPT ~20% under load.
- **Chunkgen submission window** (`chunkgen-inflight-limit`, default 128): at most N submissions per rolling 2 s window, so worldgen completions arrive at a steady rate — forceload/teleport spike max MSPT drops from ~1.5–2.3 s to ~430 ms.
- **Watchdog barrier awareness** (`barrier-watchdog-aware`, default on): the vanilla watchdog no longer kills the server while the main thread waits inside a parallel barrier during a generation storm.
- **Barrier timeout diagnostics** (`barrier-timeout-ms`, default 120000): a genuinely stalled barrier wait dumps all threads and crashes with full context instead of hanging forever.

### Unified async chunk scheduling (v1.0.30)

Prevents the server hang that parallelism could trigger on heavy modpacks. Effect: with
parallelism on, world tick stays responsive and chunk generation no longer deadlocks under load.

## Branch-specific config (`prts-features.yml`, server root)

```yaml
# Multi-thread parallel tick engine (all on by default; false falls back to vanilla single-thread)
parallel:
  pathfinding-async: true        # async pathfinding
  dimension-parallel: true       # dimension parallelism
  region-parallel: true          # region parallelism
  region-count: 4                # region count (2/4/8, default 4; non-power-of-2 falls back to 4)
  region-auto-scale: true        # auto-scale region count by load
  region-scale-interval-seconds: 300
  region-scale-high-mspt: 60
  region-scale-low-mspt: 15
  region-scale-stable-periods: 2
  region-scale-min: 2
  region-scale-max: 8
  region-scale-cross-read-ratio: 0.05
# Reliable chunk save (WAL journal, off by default)
reliable-chunk-save:
  enabled: false
  interval-seconds: 30
  chunks-per-tick: 50
# Chunkgen spike control (v1.0.29; 0 = off)
generation-tasks-per-tick: 50
chunkgen-inflight-limit: 128
# Barrier robustness (v1.0.29)
barrier-watchdog-aware: true
barrier-timeout-ms: 120000
```

> Config ownership: the parallel engine and WAL are **PRTS-original features** and live in
> `prts-features.yml`; `config/servercore.yml` keeps only the ServerCore (Spigot/Paper port)
> features — the two files are strictly separated.

## Build & Deploy

- JDK 21; command: `./gradlew --no-daemon :bootstrap:neoforgeJar`
- Deploy: copy the jar to the server root → start. The build version embeds a working-tree
  fingerprint, so the inner common.jar is re-extracted automatically on every build; no manual
  `.arclight` cleanup is ever needed
- Full instructions match the main branch — **see the main branch README**

## Everything else

Identical to the main `1.21.1` branch (ServerCore port, entity tracking, chunk saving, async
logging, powered-rail optimization, client-mod guard, redstone/pathfinding optimizations, etc.) —
**see the main branch README for the full feature table and provenance**.

## License

[GPL v3](LICENSE), same as upstream; third-party copyrights and attributions in `THIRD-PARTY.md`.
