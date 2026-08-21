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
  pathfinding-async: true
  dimension-parallel: true
  region-parallel: true
  region-count: 4                # 2/4/8/16
  region-auto-scale: true
  
  # ThreadPolicy
  main-thread-routing: "auto"    # auto=learning mode, stats=observe only
  route-threshold: 5             # violations to trigger routing in window
  route-window-ticks: 2400       # sliding window (2 minutes)
  
  # Route learning persistence
  persist-learned-routes: true
  learned-routes-file: "config/prts-learned-routes.json"
  learned-routes-limit: 200
  
  # Probation self-healing
  route-probation-enabled: true
  route-probation-ticks: 12000   # 10 minutes
  route-probation-max-violations: 2
  
  # Routed entity drain batching
  main-thread-entity-drain-budget: 0  # 0=off
  
  # Villager POI path budget
  villager-poi-path-budget: 0    # 0=unlimited, 4-8 recommended

generation-tasks-per-tick: 50
chunkgen-inflight-limit: 128
barrier-watchdog-aware: true
barrier-timeout-ms: 120000
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
