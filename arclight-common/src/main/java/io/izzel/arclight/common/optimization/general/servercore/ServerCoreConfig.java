package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationRangeConfig;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCap;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import io.izzel.arclight.common.optimization.general.servercore.commands.CommandConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicManager;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.Setting;
import io.izzel.arclight.common.optimization.general.servercore.features.FeatureConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.EnforcedMobcap;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.IMobCategory;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.MobSpawnConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.MobSpawnEntry;
import net.minecraft.world.entity.MobCategory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PRTS ServerCore 优化开关（移植自 Wesley1808/ServerCore）。
 * 读取 config/servercore.yml，按 4 组优化分别门控；首次运行自动写出默认配置。
 * 默认全开，关闭某项会回退到原版行为。修改后需重启生效。
 */
public final class ServerCoreConfig {

    public enum Feature {
        SYNC_LOADS("sync-loads"),
        CHUNK_TICKETS("chunk-tickets"),
        BIOME_LOOKUPS("biome-lookups"),
        PATHFINDING("pathfinding");

        final String key;

        Feature(String key) {
            this.key = key;
        }
    }

    private static final String FILE_NAME = "servercore.yml";
    private static final Map<Feature, Boolean> FEATURES = new HashMap<Feature, Boolean>();
    private static ActivationRangeConfig activationRange = new ActivationRangeConfig();
    private static BreedingCapConfig breedingCapConfig = BreedingCapConfig.DISABLED;
    private static MobSpawnConfig mobSpawningConfig = MobSpawnConfig.DISABLED;
    private static boolean mobSpawningActive = false;
    private static FeatureConfig featureConfig = FeatureConfig.DISABLED;
    private static DynamicConfig dynamicConfig = DynamicConfig.DISABLED;
    private static CommandConfig commandConfig = CommandConfig.DISABLED;
    private static boolean master = true;
    private static boolean loaded = false;
    private static boolean loadFailed = false;

    private static final String MOB_SPAWNING_BODY = ""
            + "# Gives more control over mob spawning.\n"
            + "mob-spawning:\n"
            + "  # Mobcap settings for zombie reinforcements.\n"
            + "  # \u25ba enforce-mobcaps = Whether to enforce mobcaps for this type of mobspawning.\n"
            + "  # \u25ba additional-capacity = Additional capacity for this specific mobcap. Decides how much it can spawn over the regular mobcap.\n"
            + "  # It is recommended to allow them to spawn a bit over the regular mobcap as they would otherwise never get a chance to spawn.\n"
            + "  zombie-reinforcements:\n"
            + "    enforce-mobcap: false\n"
            + "    additional-capacity: 40\n"
            + "\n"
            + "  # Mobcap settings for zombified piglin spawning from nether portal random ticks.\n"
            + "  nether-portal-randomticks:\n"
            + "    enforce-mobcap: false\n"
            + "    additional-capacity: 40\n"
            + "\n"
            + "  # Mobcap settings for mobs spawned from monster spawners.\n"
            + "  monster-spawners:\n"
            + "    enforce-mobcap: false\n"
            + "    additional-capacity: 40\n"
            + "\n"
            + "  # Mobcap settings for silverfish spawned from the infested potion effect.\n"
            + "  infested:\n"
            + "    enforce-mobcap: false\n"
            + "    additional-capacity: 40\n"
            + "\n"
            + "  # A list of mob categories with their respective mobcap and spawn interval.\n"
            + "  # \u25ba category = The vanilla spawn category.\n"
            + "  # \u25ba mobcap = The maximum amount of entities in the same category that can spawn near a player.\n"
            + "  # \u25ba spawn-interval = The interval between spawn attempts in ticks. Higher values mean less frequent spawn attempts.\n"
            + "  categories:\n"
            + "    - category: 'MONSTER'\n"
            + "      mobcap: 80\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'CREATURE'\n"
            + "      mobcap: 15\n"
            + "      spawn-interval: 400\n"
            + "\n"
            + "    - category: 'AMBIENT'\n"
            + "      mobcap: 15\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'AXOLOTLS'\n"
            + "      mobcap: 6\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'UNDERGROUND_WATER_CREATURE'\n"
            + "      mobcap: 6\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'WATER_CREATURE'\n"
            + "      mobcap: 6\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'WATER_AMBIENT'\n"
            + "      mobcap: 20\n"
            + "      spawn-interval: 1\n";

    private static final String FEATURES_BODY = ""
            + "# Most miscellaneous feature toggles.\n"
            + "features:\n"
            + "  # Prevents lagspikes caused by players moving into unloaded chunks.\n"
            + "  prevent-moving-into-unloaded-chunks: true\n"
            + "  # The amount of seconds between auto-saves when /save-on is active.\n"
            + "  autosave-interval-seconds: 300\n"
            + "  # The fraction that decides the chance of experience orbs being able to merge with each other. (1 = 100%, 40 = 2.5%)\n"
            + "  # Note that just like in vanilla, experience orbs will still need to be of the same size to actually merge.\n"
            + "  xp-merge-fraction: 8\n"
            + "  # The radius in blocks that experience orbs will merge at.\n"
            + "  xp-merge-radius: 3.0\n"
            + "  # The radius in blocks that items will merge at.\n"
            + "  item-merge-radius: 2.0\n"
            + "  lobotomize-villagers:\n"
            + "    # Makes villagers tick less often if they are stuck in a 1x1 space.\n"
            + "    enabled: true\n"
            + "    # Decides the interval in between villager ticks when lobotomized.\n"
            + "    tick-interval: 20\n";

    private static final String BREEDING_CAP_BODY = ""
            + "# A special mobcap that only affects the breeding of animals and villagers.\n"
            + "breeding-cap:\n"
            + "  # Enables breeding caps.\n"
            + "  enabled: true\n"
            + "  # The breeding cap for villagers.\n"
            + "  # \u25ba limit = The limit of mobs of the same type within range. Setting this to negative will disable the breeding cap.\n"
            + "  # \u25ba range = The range it will check for entities of the same type.\n"
            + "  villagers:\n"
            + "    limit: 36\n"
            + "    range: 64\n"
            + "\n"
            + "  # The breeding cap for animals.\n"
            + "  # Note that this cap only checks for animals of the same type.\n"
            + "  # If the limit is 32 you can still breed 32 cows and 32 pigs next to each other.\n"
            + "  animals:\n"
            + "    limit: 36\n"
            + "    range: 64\n";

    private static final String DYNAMIC_BODY = ""
            + "# Dynamically adjusts performance related settings based on the server's tick time.\n"
            + "dynamic:\n"
            + "  # Enables dynamic performance settings.\n"
            + "  enabled: false\n"
            + "  # The mspt (milliseconds per tick) that the server will try to stay below.\n"
            + "  # Settings get decreased above target-mspt + 5, and increased below target-mspt - 5.\n"
            + "  target-mspt: 35\n"
            + "\n"
            + "  # The starting values of dynamic settings. Omitted entries fall back to their vanilla defaults.\n"
            + "  # default-values:\n"
            + "  #   CHUNK_TICK_DISTANCE: 10\n"
            + "  #   MOBCAP_PERCENTAGE: 100\n"
            + "\n"
            + "  # A list of settings that will be modified dynamically, in order of priority.\n"
            + "  # \u25ba setting = The setting to modify. (MOBCAP_PERCENTAGE, CHUNK_TICK_DISTANCE, SIMULATION_DISTANCE, VIEW_DISTANCE)\n"
            + "  # \u25ba maximum = The highest value this setting can reach.\n"
            + "  # \u25ba minimum = The lowest value this setting can reach.\n"
            + "  # \u25ba increment = The amount added or removed per modification.\n"
            + "  # \u25ba interval = The amount of seconds between modifications of this setting.\n"
            + "  dynamic-settings:\n"
            + "    - setting: 'CHUNK_TICK_DISTANCE'\n"
            + "      maximum: 8\n"
            + "      minimum: 4\n"
            + "      increment: 1\n"
            + "      interval: 15\n"
            + "\n"
            + "    - setting: 'MOBCAP_PERCENTAGE'\n"
            + "      maximum: 100\n"
            + "      minimum: 30\n"
            + "      increment: 10\n"
            + "      interval: 15\n"
            + "\n"
            + "    - setting: 'SIMULATION_DISTANCE'\n"
            + "      maximum: 8\n"
            + "      minimum: 4\n"
            + "      increment: 1\n"
            + "      interval: 15\n"
            + "\n"
            + "    - setting: 'VIEW_DISTANCE'\n"
            + "      maximum: 10\n"
            + "      minimum: 4\n"
            + "      increment: 1\n"
            + "      interval: 300\n";

    private static final String COMMANDS_BODY = ""
            + "# Commands added by ServerCore.\n"
            + "commands:\n"
            + "  # Enables the /servercore status command.\n"
            + "  status-enabled: true\n"
            + "  # Enables the /mobcaps command.\n"
            + "  mobcaps-enabled: true\n"
            + "  # The colors used in command feedback. You can use hex codes (e.g. 00aabb) or legacy color names.\n"
            + "  colors:\n"
            + "    primary: '00aabb'\n"
            + "    secondary: '55ff55'\n"
            + "    tertiary: '55ffff'\n";

    private static final String DEFAULT = ""
            + "# PRTS ServerCore optimization toggles (ported from Wesley1808/ServerCore, Mojmap/NeoForge 1.21.1)\n"
            + "# These optimizations are built into the PRTS core; this file lets you disable any of them.\n"
            + "# Changes require a server restart.\n"
            + "#\n"
            + "# enabled: master switch. false = all ServerCore optimizations off (vanilla behavior).\n"
            + "enabled: true\n"
            + "\n"
            + "# Only validate/sync entities when the chunk is already loaded (bee hive, pathfinding, maps, structure checks).\n"
            + "# Set false to revert to vanilla. The 1.21.1 bee NullPointerException crash came from this group;\n"
            + "# if it recurs, disable this group as a temporary workaround.\n"
            + "sync-loads: true\n"
            + "\n"
            + "# Chunk-loading ticket optimizations (no extra tickets when spawning mobs/structures).\n"
            + "chunk-tickets: true\n"
            + "\n"
            + "# Fast biome lookup while spawning mobs (cached noise biome).\n"
            + "biome-lookups: true\n"
            + "\n"
            + "# PathFinder Map/Set allocation reductions.\n"
            + "pathfinding: true\n"
            + "\n"
            + FEATURES_BODY
            + "\n"
            + BREEDING_CAP_BODY
            + "\n"
            + MOB_SPAWNING_BODY
            + "\n"
            + DYNAMIC_BODY
            + "\n"
            + COMMANDS_BODY;

    private static final String DEFAULT_MOB_SPAWNING = "\n" + MOB_SPAWNING_BODY;

    private static final String DEFAULT_DYNAMIC = "\n" + DYNAMIC_BODY;

    private static final String DEFAULT_COMMANDS = "\n" + COMMANDS_BODY;

    private static final String DEFAULT_FEATURES = "\n" + FEATURES_BODY;

    private static final String DEFAULT_ACTIVATION_RANGE = ""
            + "\n"
            + "# Activation range can drastically reduce the amount of lag caused by ticking entities.\n"
            + "# It does this by cleverly skipping certain entity ticks based on the distance to players and other factors, like immunity checks.\n"
            + "# Immunity checks determine whether an entity should be ticked even when it's outside the activation range, like for example when it is falling or takes damage.\n"
            + "# Note: while this is a very powerful feature, it can still slow down mobfarms and break very specific technical contraptions.\n"
            + "activation-range:\n"
            + "  # Enables activation range.\n"
            + "  enabled: false\n"
            + "  # Briefly ticks entities newly added to the world for 10 seconds (includes both spawning and loading).\n"
            + "  # This gives them a chance to properly immunize when they are spawned if they should be. Can be helpful for mobfarms.\n"
            + "  tick-new-entities: true\n"
            + "  # Enables vertical range checks. By default, activation ranges only work horizontally.\n"
            + "  # This can greatly improve performance on taller worlds, but might break a few very specific ai-based mobfarms.\n"
            + "  use-vertical-range: false\n"
            + "  # Skips 1/4th of entity ticks whilst not immune.\n"
            + "  # This affects entities that are within the activation range, but not immune (for example by falling or being in water).\n"
            + "  skip-non-immune: false\n"
            + "  # Allows villagers to tick regardless of the activation range when panicking.\n"
            + "  villager-tick-panic: true\n"
            + "  # The time in seconds that a villager needs to be inactive for before obtaining work immunity (if it has work tasks).\n"
            + "  villager-work-immunity-after: 20\n"
            + "  # The amount of ticks an inactive villager will wake up for when it has work immunity.\n"
            + "  villager-work-immunity-for: 20\n"
            + "  # A list of entity types that should be excluded from activation range checks.\n"
            + "  excluded-entity-types:\n"
            + "    - 'minecraft:ghast'\n"
            + "    - 'minecraft:hopper_minecart'\n"
            + "    - 'minecraft:warden'\n"
            + "  # The activation type that will get assigned to any entity that doesn't have a custom activation type.\n"
            + "  # > activation-range = The range an entity is required to be in from a player to be activated.\n"
            + "  # > tick-interval = The interval between 'active' ticks whilst the entity is inactive. Negative values will disable these active ticks.\n"
            + "  # > wakeup-interval = The interval between inactive entity wakeups in seconds.\n"
            + "  # > extra-height-up = Allows entities to be ticked when far above the player when vertical range is in use.\n"
            + "  # > extra-height-down = Allows entities to be ticked when far below the player when vertical range is in use.\n"
            + "  default-activation-type:\n"
            + "    activation-range: 16\n"
            + "    tick-interval: 20\n"
            + "    wakeup-interval: -1\n"
            + "    extra-height-up: false\n"
            + "    extra-height-down: false\n"
            + "\n"
            + "  # A list of custom activation types.\n"
            + "  # > name = The name of the activation type.\n"
            + "  # > entity-matcher = A list of conditions to filter entities. Only one of these conditions needs to be met for an entity to match.\n"
            + "  # > If an entity matches multiple activation types, the one highest in the list will be used. The conditions accept the following formats:\n"
            + "  #   - Entity type matching    |   Uses the entity type's identifier.  |  'minecraft:zombie' matches zombies, but for example not husks or drowned.\n"
            + "  #   - Typeof class matching   |   Uses the 'typeof:' prefix.          |  'typeof:monster' matches all monsters.\n"
            + "  # > Available typeof classes: mob, monster, raider, neutral, ambient, animal, water_animal, flying_animal, flying_monster, villager, projectile.\n"
            + "  custom-activation-types:\n"
            + "    - name: 'raider'\n"
            + "      activation-range: 48\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 20\n"
            + "      extra-height-up: true\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'typeof:raider'\n"
            + "\n"
            + "    - name: 'water'\n"
            + "      activation-range: 16\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 60\n"
            + "      extra-height-up: false\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'typeof:water_animal'\n"
            + "\n"
            + "    - name: 'villager'\n"
            + "      activation-range: 16\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 30\n"
            + "      extra-height-up: false\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'typeof:villager'\n"
            + "\n"
            + "    - name: 'zombie'\n"
            + "      activation-range: 16\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 20\n"
            + "      extra-height-up: true\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'minecraft:zombie'\n"
            + "        - 'minecraft:husk'\n"
            + "\n"
            + "    - name: 'monster-below'\n"
            + "      activation-range: 32\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 20\n"
            + "      extra-height-up: true\n"
            + "      extra-height-down: true\n"
            + "      entity-matcher:\n"
            + "        - 'minecraft:creeper'\n"
            + "        - 'minecraft:slime'\n"
            + "        - 'minecraft:magma_cube'\n"
            + "        - 'minecraft:hoglin'\n"
            + "\n"
            + "    - name: 'flying-monster'\n"
            + "      activation-range: 48\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 20\n"
            + "      extra-height-up: true\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'minecraft:ghast'\n"
            + "        - 'minecraft:phantom'\n"
            + "\n"
            + "    - name: 'monster'\n"
            + "      activation-range: 32\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 20\n"
            + "      extra-height-up: true\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'typeof:monster'\n"
            + "\n"
            + "    - name: 'animal'\n"
            + "      activation-range: 16\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 60\n"
            + "      extra-height-up: false\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'typeof:animal'\n"
            + "        - 'typeof:ambient'\n"
            + "\n"
            + "    - name: 'creature'\n"
            + "      activation-range: 24\n"
            + "      tick-interval: 20\n"
            + "      wakeup-interval: 30\n"
            + "      extra-height-up: false\n"
            + "      extra-height-down: false\n"
            + "      entity-matcher:\n"
            + "        - 'typeof:mob'\n";

    private static final String DEFAULT_BREEDING_CAP = "\n" + BREEDING_CAP_BODY;

    public static void load() {
        if (loaded || loadFailed) return;
        synchronized (ServerCoreConfig.class) {
            if (loaded || loadFailed) return;
            for (Feature f : Feature.values()) FEATURES.put(f, Boolean.TRUE);
            File cfg = new File("config", FILE_NAME);
            if (!cfg.exists()) {
                writeDefault(cfg);
                // 首运行写出默认配置后回落解析，确保所有段立即生效（无需重启）
            }
            try (InputStream in = new FileInputStream(cfg)) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(in);
                if (root instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) root;
                    Object en = m.get("enabled");
                    if (en instanceof Boolean) master = (Boolean) en;
                    for (Feature f : Feature.values()) {
                        Object v = m.get(f.key);
                        if (v instanceof Boolean) FEATURES.put(f, (Boolean) v);
                    }
                    Object ar = m.get("activation-range");
                    if (ar instanceof Map) {
                        activationRange = ActivationRangeConfig.parse((Map<?, ?>) ar);
                    } else {
                        appendSection(cfg, DEFAULT_ACTIVATION_RANGE); // 旧配置补写新段
                    }
                    Object bc = m.get("breeding-cap");
                    if (bc instanceof Map) {
                        breedingCapConfig = parseBreedingCap((Map<?, ?>) bc);
                    } else {
                        appendSection(cfg, DEFAULT_BREEDING_CAP);
                    }
                    Object ms = m.get("mob-spawning");
                    if (ms instanceof Map) {
                        mobSpawningConfig = parseMobSpawning((Map<?, ?>) ms);
                        mobSpawningActive = true;
                    } else {
                        appendSection(cfg, DEFAULT_MOB_SPAWNING);
                    }
                    Object ft = m.get("features");
                    if (ft instanceof Map) {
                        featureConfig = parseFeatures((Map<?, ?>) ft);
                    } else {
                        appendSection(cfg, DEFAULT_FEATURES);
                    }
                    Object dy = m.get("dynamic");
                    if (dy instanceof Map) {
                        dynamicConfig = parseDynamic((Map<?, ?>) dy);
                    } else {
                        appendSection(cfg, DEFAULT_DYNAMIC);
                    }
                    Object cm = m.get("commands");
                    if (cm instanceof Map) {
                        commandConfig = parseCommands((Map<?, ?>) cm);
                    } else {
                        appendSection(cfg, DEFAULT_COMMANDS);
                    }
                }
            } catch (IOException | YAMLException e) {
                loadFailed = true; // 读取失败则回退默认（全开）
            }
            if (!master) activationRange.forceDisable();
            loaded = true;
        }
    }

    public static boolean isEnabled(Feature f) {
        load();
        return master && FEATURES.getOrDefault(f, Boolean.TRUE);
    }

    public static ActivationRangeConfig activationRange() {
        load();
        return activationRange;
    }

    public static BreedingCapConfig breedingCap() {
        load();
        return master ? breedingCapConfig : BreedingCapConfig.DISABLED;
    }

    public static boolean isActivationRangeEnabled() {
        return activationRange().enabled();
    }

    public static MobSpawnConfig mobSpawning() {
        load();
        return master ? mobSpawningConfig : MobSpawnConfig.DISABLED;
    }

    public static boolean mobSpawningActive() {
        load();
        return master && mobSpawningActive;
    }

    public static FeatureConfig features() {
        load();
        return master ? featureConfig : FeatureConfig.DISABLED;
    }

    public static DynamicConfig dynamic() {
        load();
        return master ? dynamicConfig : DynamicConfig.DISABLED;
    }

    public static boolean dynamicActive() {
        load();
        return master && dynamicConfig.enabled();
    }

    public static CommandConfig commands() {
        load();
        return master ? commandConfig : CommandConfig.DISABLED;
    }

    public static boolean reload() {
        loaded = false;
        loadFailed = false;
        load();
        if (dynamicActive()) DynamicManager.reload();
        return !loadFailed;
    }

    private static void writeDefault(File cfg) {
        try {
            File parent = cfg.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter w = new FileWriter(cfg);
            w.write(DEFAULT);
            w.write(DEFAULT_ACTIVATION_RANGE);
            w.close();
        } catch (IOException ignored) {
        }
    }

    private static FeatureConfig parseFeatures(Map<?, ?> m) {
        Map<?, ?> lobo = asMap(m.get("lobotomize-villagers"));
        return new FeatureConfig(
                asBool(m.get("prevent-moving-into-unloaded-chunks"), false),
                asInt(m.get("autosave-interval-seconds"), 300),
                asInt(m.get("xp-merge-fraction"), 40),
                asDouble(m.get("xp-merge-radius"), 0.5D),
                asDouble(m.get("item-merge-radius"), 0.5D),
                lobo != null && asBool(lobo.get("enabled"), false),
                lobo != null ? asInt(lobo.get("tick-interval"), 20) : 20
        );
    }

    private static BreedingCapConfig parseBreedingCap(Map<?, ?> m) {
        if (!(m instanceof Map)) return BreedingCapConfig.DISABLED;
        boolean enabled = asBool(m.get("enabled"), false);
        BreedingCap villagers = parseCap(asMap(m.get("villagers")));
        BreedingCap animals = parseCap(asMap(m.get("animals")));
        return new BreedingCapConfig(enabled, villagers, animals);
    }

    private static MobSpawnConfig parseMobSpawning(Map<?, ?> m) {
        EnforcedMobcap zombieReinforcements = parseEnforcedMobcap(asMap(m.get("zombie-reinforcements")));
        EnforcedMobcap portalRandomTicks = parseEnforcedMobcap(asMap(m.get("nether-portal-randomticks")));
        EnforcedMobcap monsterSpawner = parseEnforcedMobcap(asMap(m.get("monster-spawners")));
        EnforcedMobcap infested = parseEnforcedMobcap(asMap(m.get("infested")));

        List<MobSpawnEntry> categories = new ArrayList<>();
        Object cats = m.get("categories");
        if (cats instanceof List) {
            for (Object item : (List<?>) cats) {
                Map<?, ?> cm = asMap(item);
                if (cm == null) continue;
                String catName = asStr(cm.get("category"));
                if (catName == null) continue;
                MobCategory category = safeCategory(catName);
                if (category == null) continue;
                int capacity = asInt(cm.get("mobcap"), category.getMaxInstancesPerChunk());
                int spawnInterval = asInt(cm.get("spawn-interval"), 1);
                int despawnDistance = asInt(cm.get("despawn-distance"), category.getDespawnDistance());
                categories.add(new MobSpawnEntry(category, capacity, spawnInterval, despawnDistance));
            }
        }

        MobSpawnConfig config = new MobSpawnConfig(zombieReinforcements, portalRandomTicks, monsterSpawner, infested, categories);
        // 应用配置到 MobCategory 实例（改 max / spawnInterval 等）
        IMobCategory.apply(config.categories());
        return config;
    }

    private static DynamicConfig parseDynamic(Map<?, ?> m) {
        boolean enabled = asBool(m.get("enabled"), false);
        int targetMspt = asInt(m.get("target-mspt"), 35);

        Map<DynamicSetting, Integer> defaultValues = new HashMap<>();
        Map<?, ?> dv = asMap(m.get("default-values"));
        if (dv != null) {
            for (Map.Entry<?, ?> e : dv.entrySet()) {
                DynamicSetting ds = safeDynamicSetting(asStr(e.getKey()));
                if (ds != null && e.getValue() instanceof Number) {
                    defaultValues.put(ds, ((Number) e.getValue()).intValue());
                }
            }
        }

        List<Setting> settings = new ArrayList<>();
        Object list = m.get("dynamic-settings");
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                Map<?, ?> sm = asMap(item);
                if (sm == null) continue;
                DynamicSetting ds = safeDynamicSetting(asStr(sm.get("setting")));
                if (ds == null) continue;
                int max = asInt(sm.get("maximum"), ds.getDefaultValue());
                int min = asInt(sm.get("minimum"), ds.getLowerBound());
                int increment = asInt(sm.get("increment"), 1);
                int interval = asInt(sm.get("interval"), 15);
                settings.add(new Setting(ds, max, min, increment, interval));
            }
        }
        return new DynamicConfig(enabled, targetMspt, defaultValues, settings);
    }

    private static CommandConfig parseCommands(Map<?, ?> m) {
        boolean status = asBool(m.get("status-enabled"), true);
        boolean mobcaps = asBool(m.get("mobcaps-enabled"), true);
        Map<?, ?> colors = asMap(m.get("colors"));
        String primary = colors != null ? asStr(colors.get("primary")) : null;
        String secondary = colors != null ? asStr(colors.get("secondary")) : null;
        String tertiary = colors != null ? asStr(colors.get("tertiary")) : null;
        return new CommandConfig(
                status, mobcaps,
                primary != null ? primary : "00aabb",
                secondary != null ? secondary : "55ff55",
                tertiary != null ? tertiary : "55ffff"
        );
    }

    private static DynamicSetting safeDynamicSetting(String name) {
        if (name == null) return null;
        try {
            return DynamicSetting.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EnforcedMobcap parseEnforcedMobcap(Map<?, ?> m) {
        if (m == null) return EnforcedMobcap.DISABLED;
        boolean enforces = asBool(m.get("enforce-mobcap"), false);
        int addCap = asInt(m.get("additional-capacity"), 0);
        return new EnforcedMobcap(enforces, addCap);
    }

    private static MobCategory safeCategory(String name) {
        try {
            return MobCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BreedingCap parseCap(Map<?, ?> m) {
        if (m == null) return new BreedingCap(-1, 0, false);
        int limit = asInt(m.get("limit"), -1);
        int range = asInt(m.get("range"), 0);
        boolean uh = asBool(m.get("unlimited-height"), false);
        return new BreedingCap(limit, range, uh);
    }

    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : null;
    }

    private static boolean asBool(Object o, boolean def) {
        return o instanceof Boolean ? (Boolean) o : def;
    }

    private static int asInt(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static double asDouble(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    private static String asStr(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static void appendSection(File cfg, String section) {
        try {
            FileWriter w = new FileWriter(cfg, true);
            w.write(section);
            w.close();
        } catch (IOException ignored) {
        }
    }

    private ServerCoreConfig() {
    }
}
