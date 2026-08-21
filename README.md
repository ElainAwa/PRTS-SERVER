# PRTS-Multithreading — Minecraft 1.21.1 Multi-threaded Parallel Server

**[中文文档](./README_zh.md) · [English](./README.md)**

> [!NOTE]
> PRTS is a fork of [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara), focused on multi-threaded parallelism and production stability. This branch `1.21.1-Multithreading` is the experimental parallel engine development track, now in **long-term maintenance and continuous optimization phase**.

## Core Features

### 🚀 Multi-threaded Parallel Engine

Splits single-threaded world tick into parallel execution, reducing lag under heavy load:

- **Async Pathfinding** (`pathfinding-async`): Mob/villager pathfinding runs on worker threads
- **Dimension Parallel** (`dimension-parallel`): Overworld/Nether/End tick in separate threads
- **Region Parallel** (`region-parallel`): Entities divided into chunk stripes for parallel ticking
  - Configurable region count (2/4/8/16, default 4)
  - Auto load balancing (`region-auto-scale`)
  - Players stay on main thread (containers/menus/events)

**Production Validated**: Stable on 100+ mods heavy modpack, 20-40% TPS improvement.

### 🛡️ Thread Safety Guarantees

- **ThreadPolicy Auto-learning**: Runtime violation detection, auto-routes unsafe classes to main thread
  - Sliding window violation stats (2400 ticks window, 5 violations trigger routing)
  - Route learning persistence (`config/prts-learned-routes.json`)
  - Bidirectional Probation: Auto-heals by testing on worker, clears routing on success
- **World Access Guard**: Main-thread-only APIs (getBlockEntity/setBlock) log violations on worker
- **Entity/BE Safety Valve**: Worker exception → permanent main-thread fallback, no server crash

### ⚡ Performance Optimizations

**Chunk Generation Peak Shaving**
- Submission budget (`generation-tasks-per-tick: 50`): Prevents generation storm mailbox overload
- Rolling window throttle (`chunkgen-inflight-limit: 128`): Teleport spike 1.5-2.3s → ~430ms

**Barrier Robustness**
- Watchdog-aware: Parallel wait doesn't trigger false timeout
- Timeout diagnostics (`barrier-timeout-ms: 120000`): Dumps state and crashes on deadlock

**Entity Tick Optimization**
- Routed entity drain batching: Main thread queue batch consumption prevents blocking
- Villager POI path budget: Main thread claim throttle (`villager-poi-path-budget`)

**Create Mod Compat**
- Track lazy-spread: Rasterize across ticks, avoid first-load freeze
- Belt passenger deferred registration: Prevent worker race causing contraption self-destruct

### 📊 Observability

`/servercore status` real-time monitoring:
- ThreadPolicy violation stats, auto-routed class list
- Probation telemetry (attempts/success/failed)
- Chunk load/drain queue depth
- Barrier wait time, per-region load
- BE/entity tick hotspot analysis

## Configuration

`prts-features.yml` (server root):

```yaml
parallel:
  pathfinding-async: true        # Async pathfinding
  dimension-parallel: true       # Dimension parallelism
  region-parallel: true          # Region parallelism
  region-count: 4                # Region count (2/4/8/16, default 4)
  region-auto-scale: true        # Auto load balancing
  
  # ThreadPolicy auto-learning
  main-thread-routing: "auto"    # auto=learning mode, stats=observe only
  route-threshold: 5             # Violations to trigger routing in window
  route-window-ticks: 2400       # Sliding window size (2 minutes)
  
  # Route learning persistence (v1.0.37+)
  persist-learned-routes: true   # Enable persistence
  learned-routes-file: "config/prts-learned-routes.json"
  learned-routes-limit: 200      # Max persisted class count
  
  # Probation self-healing (v1.0.37+)
  route-probation-enabled: true  # Enable bidirectional probation
  route-probation-ticks: 12000   # Attempt interval (10 minutes)
  route-probation-max-violations: 2  # Historical violation filter
  
  # Routed entity drain batching
  main-thread-entity-drain-budget: 0  # Per-tick consumption budget (0=off)
  
  # Villager POI path budget
  villager-poi-path-budget: 0    # Main thread claim budget (0=unlimited, 4-8 recommended)

# Chunk generation peak shaving
generation-tasks-per-tick: 50    # Per-tick submission cap
chunkgen-inflight-limit: 128     # Rolling window throttle

# Barrier robustness
barrier-watchdog-aware: true     # Watchdog awareness
barrier-timeout-ms: 120000       # Crash timeout (2 minutes)
```

> **Config Separation**: `prts-features.yml` manages PRTS original features; `config/servercore.yml` manages ServerCore (Spigot/Paper port) features.

## Build & Deploy

**Requirements**
- JDK 21
- Gradle 8.13+

**Build**
```bash
./gradlew --no-daemon :bootstrap:neoforgeJar
```

**Deploy**
1. Copy `build/libs/PRTS-neoforge-1.21.1-<version>-Multithreading.jar` to server root
2. Start server (embedded common.jar auto-extracts on content change, no manual `.arclight` cleanup)

**Download**
- [GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER/releases)
- Latest: [v1.0.37](https://github.com/ElainAwa/PRTS-SERVER/releases/tag/v1.0.37)

## Latest Updates

### v1.0.37 (2026-08-21)

**S2.9 Routed Entity Drain Optimization**
- Enhanced telemetry: drain queue depth, barrier wait, per-region load
- Drain batching: `main-thread-entity-drain-budget` config prevents queue blocking

**S3.1 Route Learning Persistence**
- Independent JSON file: `config/prts-learned-routes.json`
- Auto-load on startup, save on shutdown
- Entry refactoring: `routed` → AtomicBoolean, added `learnedTick` field

**S3.2 Bidirectional Probation (Self-healing)**
- Auto-routed classes periodically tested on worker, restore parallel on success
- Exponential backoff (2x/4x/8x, max 1h)
- ThreadLocal isolation: probation violations don't pollute formal window
- Telemetry: `/servercore status` shows attempts/success/failed

**Fixes**
- B4: Belt passenger deferred registration (prevent Create contraption self-destruct)
- getPos optimization: Skip redundant calls on hot path

Full Changelog at [Releases](https://github.com/ElainAwa/PRTS-SERVER/releases).

## Other Features

Identical to main branch `1.21.1` (ServerCore port, entity tracking, chunk saving, async logging, powered-rail optimization, client-mod guard, redstone/pathfinding optimizations, etc.).

## License

[GPL v3](LICENSE), same as upstream. Third-party copyrights and attributions in `THIRD-PARTY.md`.

## Credits

- [Arclight](https://github.com/IzzelAliz/Arclight) - Original Hybrid server
- [Luminara](https://github.com/CraftAmethyst/Luminara) - Upstream fork
- [ServerCore](https://github.com/Wesley1808/ServerCore) - Spigot/Paper optimization port source

---

**Maintainer**: [ElainAwa](https://github.com/ElainAwa) | **Issues**: [GitHub Issues](https://github.com/ElainAwa/PRTS-SERVER/issues)
