# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge (Multi-threaded Parallel Branch)

**[中文文档](./README_zh.md) · [English](./README.md)**

> PRTS is a fork of [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara). This branch `1.21.1-Multithreading` is the experimental multi-threaded parallel engine development track.

## Parallel Engine

Splits single-threaded world tick into multi-threaded execution:

- **Async Pathfinding** (`pathfinding-async`): Mob/villager pathfinding runs on worker threads
- **Dimension Parallel** (`dimension-parallel`): Overworld/Nether/End tick in separate threads
- **Region Parallel** (`region-parallel`): Entities divided into chunk stripes for parallel ticking
  - Configurable region count (2/4/8/16, default 4)
  - Auto load balancing (`region-auto-scale`)
  - Players stay on main thread (containers/menus/events)

### ThreadPolicy Auto-learning

Runtime violation detection (worker calls main-thread-only APIs like `getBlockEntity`/`setBlock`), auto-routes unsafe classes to main thread:

- Sliding window stats (default 2400 ticks window, 5 violations trigger routing)
- Route learning persistence (`config/prts-learned-routes.json`, load on startup / save on shutdown)
- Bidirectional Probation: auto-routed classes periodically tested on worker, restore parallel on success (exponential backoff 2x/4x/8x)
- Safety valve: worker exception → permanent main-thread fallback

### Chunk Generation Peak Shaving

- Submission budget (`generation-tasks-per-tick: 50`)
- Rolling window throttle (`chunkgen-inflight-limit: 128`)
- Teleport spike: 1.5-2.3s → ~430ms

### Barrier Robustness

- Watchdog-aware: parallel wait doesn't trigger false timeout
- Timeout diagnostics (`barrier-timeout-ms: 120000`): dumps state and crashes on deadlock

### Create Mod Compat

- Track lazy-spread: rasterize across ticks
- Belt passenger deferred registration: prevent worker race

## Configuration

`prts-features.yml` (server root):

```yaml
parallel:
  # ---- Parallel engine ----
  pathfinding-async: true        # Async pathfinding: mob/villager pathfinding on worker threads
  dimension-parallel: true       # Dimension parallelism: each dimension ticks on its own thread
  region-parallel: true          # Region parallelism: non-player entities tick in parallel chunks
  region-count: 4                # Region count (2/4/8/16, default 4); more regions = more parallelism but more cross-region cost
  region-auto-scale: true        # Auto load balancing: adjust region count by overworld load

  # ---- ThreadPolicy auto-learning ----
  # When a worker thread calls main-thread-only APIs (getBlockEntity/setBlock etc),
  # a violation is recorded. Classes exceeding the threshold are auto-routed to the
  # main thread to avoid cross-thread access races.
  main-thread-routing: "auto"    # auto=learning mode (auto-route violating classes); stats=count only (observe)
  route-threshold: 5             # Violations to trigger routing within the window (lower = more sensitive)
  route-window-ticks: 2400       # Violation window size (ticks, 2400 = 2 minutes)
  route-on-read: true            # Whether MAIN_ONLY_READ counts toward the window; false=only writes route (reads are null-safe)

  # ---- Route learning persistence ----
  # Auto-learned routes (classes routed to the main thread) are lost on restart by default.
  # When enabled, they persist to an independent JSON file and are restored on startup.
  persist-learned-routes: true   # Enable route persistence (write/read JSON)
  learned-routes-file: "config/prts-learned-routes.json"  # Persistence file path
  learned-routes-limit: 200      # Max persisted class count (prevents file bloat)

  # ---- Probation self-healing ----
  # Auto-routed classes may have been temporarily unsafe; keeping them on the main
  # thread forever wastes parallelism. When enabled, periodically test the class on
  # a region worker: no violations clears the route, violations extend the interval.
  route-probation-enabled: true  # Enable self-healing test ticks
  route-probation-ticks: 12000   # Test interval (ticks, 12000 = 10 minutes)
  route-probation-max-violations: 2  # Classes with more historical violations skip testing (high-risk)

  # ---- Routed entity drain batching ----
  # The main-thread routed entity queue is fully drained per tick by default; many
  # entities can stretch a single tick. When enabled, at most N entities tick per
  # tick and the rest carry over, smoothing main-thread pressure.
  main-thread-entity-drain-budget: 0  # Max entities per tick (0 = unlimited)

  # ---- Villager POI path budget ----
  # Main-thread-routed villagers claim jobs/pathfind expensively; limit per-tick
  # pathfinding work here.
  villager-poi-path-budget: 0    # Pathfinding budget per tick (0 = unlimited; 4-8 if lagging)

# ---- Chunk generation peak shaving ----
# Many chunks generating at once (teleport/forceload) can saturate the generation
# pool and stall the main thread.
generation-tasks-per-tick: 50    # Max generation tasks submitted per tick (0 = unlimited)
chunkgen-inflight-limit: 128     # Max submissions per 2s rolling window (prevents generation spikes)

# ---- Barrier robustness ----
# Parallel tick uses a barrier to wait for all regions; avoid watchdog false-kills
# and deadlocks.
barrier-watchdog-aware: true     # Pause watchdog timer while waiting in barrier (prevent false kill)
barrier-timeout-ms: 120000       # Barrier stall timeout (ms); dumps all threads and crashes instead of hanging
```

## Build & Deploy

**Requirements**: JDK 21

**Build**:
```bash
./gradlew --no-daemon :bootstrap:neoforgeJar
```

**Deploy**: Copy `build/libs/PRTS-neoforge-1.21.1-*-Multithreading.jar` to server root, start server.

**Download**: [GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER/releases)

## Other Features

Identical to main branch `1.21.1` (ServerCore port, entity tracking, chunk saving, async logging, powered-rail optimization, client-mod guard, redstone/pathfinding optimizations, etc.).

## License

[GPL v3](LICENSE), same as upstream. Third-party copyrights and attributions in `THIRD-PARTY.md`.

## Credits

- [Arclight](https://github.com/IzzelAliz/Arclight) - Original Hybrid server
- [Luminara](https://github.com/CraftAmethyst/Luminara) - Upstream fork
- [ServerCore](https://github.com/Wesley1808/ServerCore) - Optimization port source
