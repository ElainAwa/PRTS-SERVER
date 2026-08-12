# PRTS-FeudalKings — Minecraft 1.21.1 / NeoForge

> [!NOTE]
> This README is AI-authored and maintained, for a quick overview and feature provenance.

## Project Origins

[Arclight](https://github.com/IzzelAliz/Arclight) (hybrid server) → [Luminara](https://github.com/CraftAmethyst/Luminara) → **this fork: PRTS** (maintained by [ElainAwa](https://github.com/ElainAwa), private production hardening)

## What This Is

Built for heavily-modded, multiplayer production servers: **NeoForge mods and Bukkit/Spigot plugins run side by side**.
Only zero-perception low-level optimizations and crash fixes — gameplay is unchanged, only the resource cost of the same logic is reduced.

## Features & Sources

> The original mods are not compatible with the Luminara (Arclight) base. To avoid conflicts and incidents from installing them, their code has been folded into the core (de-modded rewrite). Folding them in is mainly for convenience — making the core compatible with both mods running side by side would require touching too much and is not worth it.

| Feature | Source | Repository |
|---|---|---|
| Full ServerCore port, six sections (activation-range / breeding-cap / mob-spawning / features / dynamic / commands) | ServerCore mod | https://github.com/Wesley1808/ServerCore |
| routeB spatial entity tracking | VMP mod (AreaMap algorithm) | https://github.com/RelativityMC/Very-Many-Players |
| ticketpropagator (delayed 8-way chunk-ticket propagation) | VMP (Paper-derived algorithm) | https://github.com/RelativityMC/Very-Many-Players |
| move-zero-velocity / async-logging | VMP mod | https://github.com/RelativityMC/Very-Many-Players |
| Powered-rail optimization (bounded power propagation depth) | Fluorite server (PoweredRailsOptimized) | https://github.com/FluoritePowered/Fluorite-1.19.2 |
| Thread CPU-cost profiling (PRTSThreadCost) | Youer server (YouerThreadCost) | https://github.com/MohistMC/Youer |
| Main-thread stall watchdog (WatchMohist) | Youer server (WatchMohist) | https://github.com/MohistMC/Youer |
| Console log format fix (FML overriding log4j) | In-house, inspired by Youer | https://github.com/MohistMC/Youer |
| NearbyPlayerIndex (NPI) spatial player index | In-house (built on Paper AreaMap) | https://github.com/PaperMC/Paper |
| ClientModGuard client-mod precheck / crash self-healing (v27/v28) | In-house | — |
| Guava shadowing crash fix (boot jar excludes com.google.common) | In-house | — |

## Build

- Requirements: JDK 21
- Commands: `./gradlew --no-daemon :bootstrap:neoforgeJar` (full build; do **not** skip `generateInstallerInfo` — missing it causes an NPE on startup)
- Artifact: `bootstrap/build/libs/PRTS-neoforge-1.21.1-<version>.jar`
- Rules: the boot jar must not bundle `com.google.common` (old Guava shadows the platform Guava; already excluded in the embed phase); bump the version only for repo-synced final builds

## Deploy

1. Copy the new jar to the server root and point your start script at it (`_start.bat` runs `java -jar PRTS-neoforge-1.21.1-<version>.jar -nogui`)
2. Start — the build version embeds a working-tree fingerprint, so the inner `common.jar` is re-extracted automatically on every build; no manual `.arclight` cleanup is ever needed

## Config

- `prts.yml` (server root): legacy optimizations (NPI, routeB, tracking, etc.)
- `config/servercore.yml`: six ServerCore sections (activation-range / breeding-cap / mob-spawning / features / dynamic / commands); missing sections are auto-appended and take effect on startup

## Compatibility

- NeoForge mods + Bukkit/Spigot plugins on the same server
- May be incompatible with some optimization mods; incompatible with optimization Bukkit plugins

## License

Open source under [GPL v3](LICENSE), same as upstream.
