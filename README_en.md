# PRTS-stable-Trials

[简体中文](README.md)

A private, production-hardened downstream fork of [PRTS](https://github.com/QianMo0721/PRTS)
(itself a fork of [Luminara](https://github.com/QianMo0721/Luminara),
which is an [Arclight](https://github.com/IzzelAliz/Arclight) Hybrid fork)
for **Minecraft 1.20.1 / Forge 47.4.16**.

This fork targets a heavily-modded, multiplayer production environment (mixed Forge mods +
Bukkit/Spigot plugins). Custom changes follow a single principle:

> **Zero-perception, low-level optimizations only.**
> Gameplay is unchanged: view distance, mob caps, spawning rules, crop growth and chunk ticking remain vanilla.
> Only the resource cost of the same logic is reduced, or crashes are fixed.

> [!NOTE]
> This is a private downstream fork. Upstream PRTS is no longer updated; the optimizations and crash
> fixes below are maintained here independently. See [CHANGELOG.md](CHANGELOG.md) for the full version history.

## Environment

| Item | Value |
|---|---|
| Minecraft | 1.20.1 |
| Loader | Forge 47.4.16 |
| Base | Luminara (Arclight Hybrid fork) |
| JDK | 21 (build & runtime) |
| Current version | see `version` in [`build.gradle`](build.gradle) |

## Custom Optimizations (this fork)

Each optimization is gated behind a config switch in `prts.yml`, and is designed to be behaviorally
identical to vanilla at a lower resource cost. Runtime status is printed with `[PRTS-*]` log tags on boot.

| Optimization | Log tag | Notes |
|---|---|---|
| **routeB spatial entity tracking** | `[PRTS-EntityTrack]` | Spatialized `AreaMap` tracker (HariPlayer-derived) |
| **ticketpropagator** | `[PRTS-TP]` | Paper-style delayed 8-way ticket distance propagation |
| **ServerCore (12 items)** | — | Assorted server-tick micro-optimizations |
| **move-zero-velocity** | — | Skip redundant `move()` for zero-velocity entities |
| **async-logging** | — | log4j2 AsyncAppender wrapping the root logger |
| **NearbyPlayerIndex (NPI)** | `[PRTS-NPI]` | Spatial index accelerating `getNearestPlayer` / `hasNearbyAlivePlayer` (default `enabled=false`) |
| **Native chunk tuning** | — | chunk-load-rate-limit, parallel world init + async data load, async world saving |
| **Crash fixes (always on)** | `[PRTS-ChampionsFix]` | ChampionsConfig lazy bake; RevelationFix `inWhitelist` null guard |

### NearbyPlayerIndex safety model

NPI does not touch the chunk packet-dispatch path; it only accelerates queries, with three layers of safety:

1. **Vanilla accounting is authoritative** — the index only side-tracks, and does not override packet sending.
2. **verify double-run** (default on) — every index result is compared against vanilla; on mismatch it logs a
   `WARN` and uses the vanilla result.
3. **144-block math guard + self-poisoning** — the worst case is "no speedup", never a silent wrong answer.

## Building

> [!IMPORTANT]
> This fork has several mandatory build/deploy rules. Read them before building.

**Build command** (JDK 21 required):

```bash
# Use a clean environment + the pinned Gradle 8.14.5 + isolated temp dirs.
gradle --no-daemon collect --rerun-tasks
```

The artifact is produced at `arclight-forge/build/libs/PRTS-1.20.1-<version>.jar`.

**Build rules:**

- ✅ Update `version` in `build.gradle` only for the final build synced to the repository; local iterative builds keep the version unchanged.
- ❌ Do **not** run `:arclight-forge:clean` — it deletes the reobf SRG cache and corrupts the refmap.
- ⚠️ Non-`@Mixin` helper classes must not live under a `mixin/...` package (causes `IllegalClassLoadError`);
  put them under `io.izzel.arclight.common.optimization.general.<feature>`.
- ⚠️ Before writing any mixin, verify class/field/INVOKE owners with `javap` against the compiled mojmap classes;
  do not reuse symbols from newer MC versions.

## Deployment

1. Copy the new jar to the server root and point your start script at it.
2. **Force re-unpack** — the outer jar is a launcher; the real classes live inside an inner `common.jar`.
   Both cache dirs must be cleared, otherwise the old `common.jar` is reused and the fix does not apply:

   ```bash
   rm -rf .arclight/mod_file/*
   rm -rf .arclight/class_cache/*
   ```

3. Start the server:

   ```bash
   java -jar PRTS-1.20.1-<version>.jar nogui
   ```

## Compatibility

- Runs Forge mods and Bukkit/Spigot plugins simultaneously.
- May be incompatible with some optimization mods; incompatible with optimization Bukkit plugins.
- Known-good optimization mods on this server: ModernFix, FerriteCore, Canary, Saturn, KryptonReforged,
  Noisium, Radium (Lithium), Immersive Optimization, MemoryLeakFix, PacketFixer, Spark.

## Credits & License

- Built on [Arclight](https://github.com/IzzelAliz/Arclight) by IzzelAliz.
- Forked from [Luminara](https://github.com/QianMo0721/Luminara) by QianMo0721 (Arclight Hybrid fork).
- Re-forked as PRTS (this repository); custom optimizations and crash fixes maintained by [ElainAwa](https://github.com/ElainAwa).

Licensed under [GPL v3](LICENSE), same as upstream.
