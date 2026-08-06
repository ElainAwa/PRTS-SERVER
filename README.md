# PRTS-stable-Trials — Minecraft 1.20.1 / Forge

> [!NOTE]
> This README is AI-authored and maintained, for a quick overview and feature provenance.

## Project Origins

[Arclight](https://github.com/IzzelAliz/Arclight) (hybrid server) → [Luminara](https://github.com/CraftAmethyst/Luminara) → **this fork: PRTS** (maintained by [ElainAwa](https://github.com/ElainAwa), private production hardening)

## What This Is

Built for heavily-modded, multiplayer production servers: **Forge mods and Bukkit/Spigot plugins run side by side**.
Only zero-perception low-level optimizations and crash fixes — gameplay is unchanged, only the resource cost of the same logic is reduced.

## Features & Sources

> The original mods are not compatible with the Luminara (Arclight) base. To avoid conflicts and incidents from installing them, their code has been folded into the core (de-modded rewrite). Folding them in is mainly for convenience — making the core compatible with both mods running side by side would require touching too much and is not worth it.

| Feature | Source | Repository |
|---|---|---|
| Full ServerCore port (breeding-cap / dynamic / features / commands / optimizations + /statistics) | ServerCore mod | https://github.com/Wesley1808/ServerCore |
| routeB spatial entity tracking | VMP mod (AreaMap algorithm) | https://github.com/RelativityMC/Very-Many-Players |
| ticketpropagator (delayed 8-way chunk-ticket propagation) | VMP (Paper-derived algorithm) | https://github.com/RelativityMC/Very-Many-Players |
| move-zero-velocity / async-logging | VMP mod | https://github.com/RelativityMC/Very-Many-Players |
| Villager brain offload (minecrafttweaks) | Mohist server OptVillager | https://github.com/MohistMC/Mohist |
| NearbyPlayerIndex (NPI) spatial player index | In-house (built on Paper AreaMap) | https://github.com/PaperMC/Paper |
| ClientModGuard client-mod precheck / crash self-healing | In-house | — |
| Crash fixes (ChampionsFix / RevelationFix, etc.) | In-house | — |

## Versions

- Current: **v1.0.56** (get from GitHub Releases; Latest follows the newest release)
- Changelog: [CHANGELOG.md](CHANGELOG.md)

## Build

- Requirements: JDK 21 (build) + pinned Gradle 8.14.5 (distribution jar, not `./gradlew`)
- Commands: `gradle --no-daemon :arclight-common:createSrgToMcp --rerun-tasks` → `gradle --no-daemon :collect --offline`
- Artifact: `build/libs/PRTS-1.20.1-<version>.jar`
- Rules: never run `:arclight-forge:clean` (destroys the reobf SRG cache and corrupts the refmap); bump the version only for repo-synced final builds

## Deploy

1. Copy the new jar to the server root and point your start script at it
2. **Force re-unpack**: the outer jar is a launcher; real classes live in the inner `common.jar` — you must clear `.arclight/mod_file/*` and `.arclight/class_cache/*`, otherwise the old common is reused
3. Start: `java -jar PRTS-1.20.1-<version>.jar nogui`

## Config

- `prts.yml` (server root): legacy optimizations (NPI, routeB, tracking, etc.)
- `config/servercore.yml`: five ServerCore sections (breeding-cap / dynamic / features / commands / optimizations); missing sections are auto-appended and take effect on startup

## Compatibility

- Forge mods + Bukkit/Spigot plugins on the same server
- Verified co-existing optimization mods: ModernFix, FerriteCore, Canary, Saturn, KryptonReforged, Noisium, Radium, MemoryLeakFix, PacketFixer, Spark, etc.

## License

Open source under [GPL v3](LICENSE), same as upstream.
