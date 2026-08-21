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
  pathfinding-async: true        # Async pathfinding on worker threads
  dimension-parallel: true       # Each dimension ticks on its own thread
  region-parallel: true          # Non-player entities tick in parallel chunk stripes
  region-count: 4                # Region count (2/4/8/16)
  region-auto-scale: true        # Auto-adjust region count by load

  # ThreadPolicy: auto-routes entity classes that call main-thread-only APIs on workers
  main-thread-routing: "auto"    # auto=auto-route; stats=count only
  route-threshold: 5             # Violations to trigger routing in window
  route-window-ticks: 2400       # Violation window (ticks, 2400 = 2 min)
  route-on-read: true            # Whether read violations count; false=writes only

  # Route persistence: learned routes saved to JSON, restored on restart
  persist-learned-routes: true   # Enable persistence
  learned-routes-file: "config/prts-learned-routes.json"
  learned-routes-limit: 200      # Max persisted classes

  # Probation self-healing: routed classes retested on worker, cleared on no violation
  route-probation-enabled: true  # Enable self-healing
  route-probation-ticks: 12000   # Test interval (ticks)
  route-probation-max-violations: 2  # Skip testing above this violation count

  # Routed entity drain batching: cap main-thread routed entity ticks per tick
  main-thread-entity-drain-budget: 0  # Max per tick (0 = unlimited)

  # Villager POI path budget: limit main-thread villager pathfinding
  villager-poi-path-budget: 0    # Per-tick budget (0 = unlimited, try 4-8 if lagging)

# Chunk generation peak shaving
generation-tasks-per-tick: 50    # Max generation tasks submitted per tick
chunkgen-inflight-limit: 128     # Max submissions per 2s window

# Barrier robustness
barrier-watchdog-aware: true     # Pause watchdog while in barrier, prevent false kill
barrier-timeout-ms: 120000       # Barrier stall timeout (ms)
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
