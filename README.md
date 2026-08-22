# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge (Multi-threaded Parallel Branch)

**[中文文档](./README_zh.md) · [English](./README.md)**

> PRTS is a fork of [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara). This branch `1.21.1-Multithreading` is the experimental multi-threaded parallel engine development track.

> ⚠ This project is currently developed entirely via vibecoding. With a small team, progress is slow. If you're interested in joining multi-threaded server development, contact: QQ 3031917948 / Telegram [t.me/Mon3trQAQ](https://t.me/Mon3trQAQ)

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

## Future Plans

Directions under planning, not yet implemented or enabled by default. All new parallel capabilities land with conservative defaults and are validated with reproducible synthetic load and production gray rollout before any default change.

### Chunk Loading Multithreading

- **Lighting threading**: move the lighting engine off the main thread onto a dedicated thread. Today only a per-tick propagation budget exists, which spreads large light updates across ticks but keeps the work on the main thread.
- **Player-distance-priority chunk scheduling**: schedule chunk load demands by distance to players instead of a first-in-first-out queue, reducing wait time after login, teleport or random teleport.
- **Long-term: parallel chunk state machine**: rework the vanilla chunk status chain (disk read, generation, installation) to run stages on worker threads, referencing the C2ME project's design. This requires changes to the NeoForge chunk state machine and related event timing; it will be developed and validated on a separate branch before merge.

### Region Parallel Engine Evolution

- **Uneven stripes**: entities are currently divided into fixed-width chunk stripes; plan to size regions by actual load to reduce whole-tick barrier waits caused by a single overloaded region.
- **Cross-region access observation & value snapshots**: measure cross-region read/write frequency and hot values, snapshot high-frequency read-only values per task to reduce cross-region read overhead and the share of entities auto-routed back to the main thread.
- **Authoritative region copies (long-term)**: maintain an authoritative copy of each region's data to remove cross-region read races. This has data-copy cost, and will only be evaluated after cross-region access measurement is in place.
- **Deterministic total ordering (optional switch)**: apply cross-region writes in a fixed global order, off by default, as a debugging tool for concurrency issues.

### Block Entity Parallelism Gray Rollout

- Gradually allow more block entity types to tick on worker threads, starting with container-like types observed to be hot in production. Block entity ticks currently stay on the main thread by default, with only a small allowlist on workers.

### Synthetic Load & Capacity Validation

- Keep using reproducible synthetic loads (large-scale pathfinding entities, chunk generation bursts, continuous item flow) to measure the benefit of parallel execution before any production shadow rollout.

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
