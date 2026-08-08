# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge (engineering-validation branch)

> [!WARNING]
> ⚠️ **This is an engineering-validation build — use with caution, do not use in production.**
> This branch carries an experimental multi-thread parallel tick engine (P1/P2/P3),
> enabled by default. To fall back to stable single-thread behavior, explicitly disable
> the toggles in `prts-features.yml` (see "Branch-specific config" below).

> [!NOTE]
> This README is AI-authored and maintained, for a quick overview and feature provenance.
> Except for the parallel tick engine, this branch is identical to the main `1.21.1` branch —
> the full feature table and provenance live in **the main branch README**, not repeated here.

## Project Origins

[Arclight](https://github.com/IzzelAliz/Arclight) (hybrid server) → [Luminara](https://github.com/CraftAmethyst/Luminara) → **this fork: PRTS** (maintained by [ElainAwa](https://github.com/ElainAwa))
→ **this branch: `p3-parallel`** (PRTS + experimental parallel tick engine)

## Branch-specific: Multi-thread Parallel Tick Engine

Splits the single main-thread tick into parallel workers, three independent layers
(toggle in `prts-features.yml`, all on by default):

| Layer | Capability | Notes | Design docs |
|---|---|---|---|
| **P1** | Async pathfinding (pathfinding-async) | PathFinder A* offloaded to worker threads, results applied next tick; region workers submit per-region buckets and drain locally | `docs/parallel-phase2-dimension-parallelism-v01.md` etc. |
| **P2** | Dimension parallelism (dimension-parallel) | Each dimension ticks on its own worker behind a main-thread barrier; cross-dimension teleports deferred to the barrier | `docs/parallel-phase2-dimension-parallelism-v01.md` |
| **P3** | Region parallelism (region-parallel) | Overworld split into 2 regions by chunk stripes; block/entity/block-entity phases run in three sessions on region workers; cross-region writes via update-set protocol (1-tick window), two-level entity locking, cross read/write counters | `docs/parallel-phase3-region-parallelism-v01.md` ~ `v10` |

**Key fixes baked into this branch** (not in main):
- **Mob AI freeze fix**: NeoForge 1.21.1 moved AI scheduling into `serverAiStep()` (called only by the main-thread tick consumer); region workers silently lost AI — now re-invoked (commit `f63a294`)
- **Entity management thread-safety**: two-level locking on EntityLookup/EntityTickList/EntitySectionStorage/EntitySection (v05, prior to `5edd04a`)
- **Cross-region redstone stats**: `cross.redstoneBoundary` band counter; measured cross-region redstone <1%, so the NP-hard redstone graph solver is permanently dropped (commit `7f24b2f`)

## Branch-specific config (`prts-features.yml`, server root)

```yaml
# Parallel tick engine (all on by default; false falls back to vanilla single-thread)
parallel:
  pathfinding-async: true
  dimension-parallel: true
  region-parallel: true
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
- History: this branch's commits (see git log)

## Build & Deploy

- JDK 21; command: `./gradlew --no-daemon :bootstrap:neoforgeJar`
- Deploy: copy the jar to the server root → **clear `.arclight/mod_file/*`** (forces re-extraction
  of the embedded common.jar) → start
- Full instructions match the main branch — **see the main branch README**

## Everything else

Identical to the main `1.21.1` branch: full ServerCore six-section port, routeB spatial entity
tracking, ticketpropagator, move-zero-velocity / async-logging, powered-rail optimization,
PRTSThreadCost, WatchMohist, NPI, ClientModGuard, Guava shadowing fix, neighbor-update circuit
breaker, ae2lt throttle, etc. — **see the main branch README for the full feature table and
provenance**.

## License

[GPL v3](LICENSE), same as upstream; third-party copyrights and attributions in `THIRD-PARTY.md`.
