# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge (multi-thread engineering-validation branch)

> [!WARNING]
> ⚠️ **This is an engineering-validation build — use with caution, do not use in production.**
> This branch carries experimental multi-thread parallelism, enabled by default.
> To fall back to stable single-thread behavior, disable the toggles in `prts-features.yml`
> (see "Branch-specific config" below).

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
```

> Config ownership: the parallel engine and WAL are **PRTS-original features** and live in
> `prts-features.yml`; `config/servercore.yml` keeps only the ServerCore (Spigot/Paper port)
> features — the two files are strictly separated.

## Version & Artifact

- Current version: **v1.21.1-1.0.25** (version number synced with main)
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
