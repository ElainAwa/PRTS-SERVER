# Luminara-stable-Trials

[简体中文](README_zh.md)

A private, production-hardened downstream fork of [Luminara](https://github.com/QianMo0721/Luminara)
(itself an [Arclight](https://github.com/IzzelAliz/Arclight) Hybrid fork) for **Minecraft 1.20.1 / Forge 47.4.16**.

This fork is tuned for a **large social-simulation server** (80+ concurrent players, 250+ mods, mixed
Forge mods + Bukkit/Spigot plugins). Every custom change follows one rule:

> **Zero-perception, low-level optimizations only.**
> Never change gameplay — view distance, mob caps, spawning rules, crop growth and chunk ticking all stay vanilla.
> We only make the *same work* cheaper, or fix crashes.

> [!NOTE]
> This is a **private** downstream fork. Upstream Luminara is permanently discontinued; all optimizations and
> crash fixes documented below are maintained here independently. See [CHANGELOG.md](CHANGELOG.md) for the full
> version history.

## Environment

| Item | Value |
|---|---|
| Minecraft | 1.20.1 |
| Loader | Forge 47.4.16 |
| Base | Arclight Hybrid (Luminara fork) |
| JDK | 21 (build & runtime) |
| Current version | see `version` in [`build.gradle`](build.gradle) |

## Custom Optimizations (this fork)

All optimizations are gated behind config switches in `luminara.yml` and are designed to be **behaviorally
identical to vanilla** while cheaper. Runtime status is printed with `[Luminara-*]` log tags on boot.

| Optimization | Log tag | Notes |
|---|---|---|
| **routeB spatial entity tracking** | `[Luminara-EntityTrack]` | Spatialized `AreaMap` tracker (HariPlayer-derived) |
| **ticketpropagator** | `[Luminara-TP]` | Paper-style delayed 8-way ticket distance propagation |
| **ServerCore (12 items)** | — | Assorted server-tick micro-optimizations |
| **move-zero-velocity** | — | Skip redundant `move()` for zero-velocity entities |
| **async-logging** | — | log4j2 AsyncAppender wrapping the root logger |
| **NearbyPlayerIndex (NPI)** | `[Luminara-NPI]` | Spatial index accelerating `getNearestPlayer` / `hasNearbyAlivePlayer` (default `enabled=false`) |
| **Native chunk tuning** | — | chunk-load-rate-limit, parallel world init + async data load, async world saving |
| **Crash fixes (always on)** | `[Luminara-ChampionsFix]` | ChampionsConfig lazy bake; RevelationFix `inWhitelist` null guard |

### NearbyPlayerIndex safety model

NPI never touches the chunk packet-dispatch path. It only *accelerates queries* with three layers of safety:

1. **Vanilla accounting is always authoritative** — the index only side-tracks, never overrides packet sending.
2. **verify double-run** (default on) — every index result is compared against vanilla; on mismatch it logs a
   `WARN` and uses the vanilla result.
3. **144-block math guard + self-poisoning** — worst case is "no speedup", never a silent wrong answer.

## Building

> [!IMPORTANT]
> This fork has strict build/deploy rules learned the hard way. Read them before building.

**Build command** (JDK 21 required):

```bash
# Use a clean environment + the pinned Gradle 8.14.5 + isolated temp dirs.
gradle --no-daemon collect --rerun-tasks
```

The artifact is produced at `arclight-forge/build/libs/luminara-1.20.1-<version>.jar`.

**Build iron laws:**

- ✅ Bump `version` in `build.gradle` for **every** new build.
- ❌ Do **not** run `:arclight-forge:clean` — it deletes the reobf SRG cache and corrupts the refmap.
- ⚠️ Non-`@Mixin` helper classes must **never** live under a `mixin/...` package (causes `IllegalClassLoadError`);
  put them under `io.izzel.arclight.common.optimization.general.<feature>`.
- ⚠️ Before writing any mixin, verify class/field/INVOKE owners with `javap` against the compiled mojmap classes —
  do not assume symbols from newer MC versions.

## Deployment

1. Copy the new jar to the server root and point your start script at it.
2. **Force re-unpack** — the outer jar is a launcher; the real classes live inside an inner `common.jar`.
   You **must** clear both cache dirs, otherwise the old `common.jar` is reused and your fix silently won't apply:

   ```bash
   rm -rf .arclight/mod_file/*
   rm -rf .arclight/class_cache/*
   ```

3. Start the server:

   ```bash
   java -jar luminara-1.20.1-<version>.jar nogui
   ```

## Compatibility

- Runs Forge mods and Bukkit/Spigot plugins simultaneously.
- May be incompatible with some optimization mods; incompatible with optimization Bukkit plugins.
- Known-good optimization mods on this server: ModernFix, FerriteCore, Canary, Saturn, KryptonReforged,
  Noisium, Radium (Lithium), Immersive Optimization, MemoryLeakFix, PacketFixer, Spark.

## Credits & License

- Built on [Arclight](https://github.com/IzzelAliz/Arclight) by IzzelAliz.
- Forked from [Luminara](https://github.com/QianMo0721/Luminara) by QianMo0721.
- Custom optimizations and crash fixes in this fork are maintained by [ElainAwa](https://github.com/ElainAwa).

Licensed under [GPL v3](LICENSE), same as upstream.
