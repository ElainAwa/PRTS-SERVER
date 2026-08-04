package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCap;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import io.izzel.arclight.common.optimization.general.servercore.FeatureConfig;
import io.izzel.arclight.common.optimization.general.servercore.commands.CommandConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicManager;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.Setting;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.EnforcedMobcap;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.IMobCategory;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.MobSpawnConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.MobSpawnEntry;
import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationRangeConfig;
import net.minecraft.world.entity.MobCategory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PRTS ServerCore 优化开关（移植自 Wesley1808/ServerCore，Mojmap/Forge 1.20.1）。
 * 读取 config/servercore.yml；仅门控本文件声明的段，早期常开优化不受 enabled 影响。
 */
public final class ServerCoreConfig {

    private static final String FILE_NAME = "servercore.yml";

    private static BreedingCapConfig breedingCapConfig = BreedingCapConfig.DISABLED;
    private static DynamicConfig dynamicConfig = DynamicConfig.DISABLED;
    private static FeatureConfig featureConfig = FeatureConfig.DISABLED;
    private static CommandConfig commandConfig = CommandConfig.DISABLED;
    private static OptimizationConfig optimizationConfig = OptimizationConfig.DISABLED;
    private static MobSpawnConfig mobSpawningConfig = MobSpawnConfig.DISABLED;
    private static boolean mobSpawningActive = false;
    private static ActivationRangeConfig activationRangeConfig = new ActivationRangeConfig();
    private static boolean master = true;
    private static boolean loaded = false;
    private static boolean loadFailed = false;

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
            + "\n"
            + "  # A list of settings that will be modified dynamically, in order of priority.\n"
            + "  # \u25ba setting = The setting to modify. (CHUNK_TICK_DISTANCE, SIMULATION_DISTANCE, VIEW_DISTANCE)\n"
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

    private static final String FEATURES_BODY = ""
            + "# Feature toggles (merging / misc / spawn-chunks).\n"
            + "# Note: villager brain-offload (lobotomize) is already provided by the built-in\n"
            + "# minecrafttweaks optimization, so it is intentionally not duplicated here.\n"
            + "features:\n"
            + "  # Master toggle for the features section below.\n"
            + "  enabled: true\n"
            + "\n"
            + "  # spawn-chunks: Stops the server from loading spawn chunks.\n"
            + "  disable-spawn-chunks: false\n"
            + "\n"
            + "  # misc: Prevents lagspikes from players moving into unloaded chunks.\n"
            + "  prevent-moving-into-unloaded-chunks: false\n"
            + "\n"
            + "  # misc: Ticks between auto-saves (>=1). Vanilla = 6000.\n"
            + "  autosave-interval: 6000\n"
            + "\n"
            + "  # merging: 1-in-X chance for XP orbs to merge (>=1). Vanilla = 40.\n"
            + "  xp-merge-chance: 8\n"
            + "  # merging: Merge radius in blocks for items / xp (>=0.5). Vanilla = 0.5.\n"
            + "  item-merge-radius: 2.0\n"
            + "  xp-merge-radius: 3.0\n";

    private static final String COMMANDS_BODY = ""
            + "# ServerCore commands (/servercore, /sc, /mobcaps).\n"
            + "commands:\n"
            + "  # Master toggle: false unregisters every command below, including /servercore reload and settings.\n"
            + "  enabled: true\n"
            + "\n"
            + "  # /servercore status - shows core version and dynamic setting values.\n"
            + "  status: true\n"
            + "  # /mobcaps - shows nearby mob counts per category for the executing player.\n"
            + "  mobcaps: true\n"
            + "\n"
            + "  # Feedback text colors (hex without '#'; vanilla color names are also accepted).\n"
            + "  primary-color: '00aabb'\n"
            + "  secondary-color: '55ff55'\n"
            + "  tertiary-color: '55ffff'\n";

    private static final String OPTIMIZATIONS_BODY = ""
            + "# Low-level tick / packet optimizations. Disabling any entry restores vanilla behaviour.\n"
            + "optimizations:\n"
            + "  # Master toggle for the optimizations section below.\n"
            + "  enabled: true\n"
            + "\n"
            + "  # Skips the expensive inventory / name lookups when ticking filled maps.\n"
            + "  map-ticking: true\n"
            + "\n"
            + "  # Only broadcasts block changes for chunks that actually changed this tick.\n"
            + "  chunk-broadcasts: true\n"
            + "\n"
            + "  # Replaces the per-chunk lightning / ice-and-snow random rolls with cheap counters.\n"
            + "  chunk-random-ticks: true\n"
            + "\n"
            + "  # /statistics - entity and block-entity counters (needs commands.enabled as well).\n"
            + "  statistics-command: true\n";

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
            + "      mobcap: 80\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'AMBIENT'\n"
            + "      mobcap: 15\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'AXOLOTLS'\n"
            + "      mobcap: 5\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'UNDERGROUND_WATER_CREATURE'\n"
            + "      mobcap: 5\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'WATER_CREATURE'\n"
            + "      mobcap: 5\n"
            + "      spawn-interval: 1\n"
            + "\n"
            + "    - category: 'WATER_AMBIENT'\n"
            + "      mobcap: 20\n"
            + "      spawn-interval: 1\n";

    private static final String DEFAULT_ACTIVATION_RANGE = ""
            + "\n"
            + "# Activation range can drastically reduce the amount of lag caused by ticking entities.\n"
            + "# It does this by cleverly skipping certain entity ticks based on the distance to players and other factors, like immunity checks.\n"
            + "# Immunity checks determine whether an entity should be ticked even when it's outside the activation range, like for example when it is falling or takes damage.\n"
            + "# Note: while this is a very powerful feature, it can still slow down mobfarms and break very specific technical contraptions.\n"
            + "activation-range:\n"
            + "  # Enables activation range.\n"
            + "  enabled: true\n"
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

    private static final String DEFAULT = ""
            + "# PRTS ServerCore optimization toggles (ported from Wesley1808/ServerCore, Mojmap/Forge 1.20.1)\n"
            + "# These optimizations are built into the PRTS core; this file lets you disable any of them.\n"
            + "# Changes require a server restart.\n"
            + "#\n"
            + "# enabled: master switch for the sections declared in this file.\n"
            + "# Note: the older always-on optimizations of this build (sync-loads, chunk-tickets,\n"
            + "# biome-lookups, pathfinding, activation-range) are NOT controlled by this file yet.\n"
            + "enabled: true\n"
            + "\n"
            + BREEDING_CAP_BODY
            + "\n"
            + DYNAMIC_BODY
            + "\n"
            + FEATURES_BODY
            + "\n"
            + COMMANDS_BODY
            + "\n"
            + OPTIMIZATIONS_BODY
            + "\n"
            + MOB_SPAWNING_BODY
            + "\n"
            + DEFAULT_ACTIVATION_RANGE;

    private static final String DEFAULT_BREEDING_CAP = "\n" + BREEDING_CAP_BODY;
    private static final String DEFAULT_DYNAMIC = "\n" + DYNAMIC_BODY;
    private static final String DEFAULT_FEATURES = "\n" + FEATURES_BODY;
    private static final String DEFAULT_COMMANDS = "\n" + COMMANDS_BODY;
    private static final String DEFAULT_OPTIMIZATIONS = "\n" + OPTIMIZATIONS_BODY;
    private static final String DEFAULT_MOB_SPAWNING = "\n" + MOB_SPAWNING_BODY;

    public static void load() {
        if (loaded || loadFailed) return;
        synchronized (ServerCoreConfig.class) {
            if (loaded || loadFailed) return;
            File cfg = new File("config", FILE_NAME);
            if (!cfg.exists()) {
                writeDefault(cfg);
            }
            try (InputStream in = new FileInputStream(cfg)) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(in);
                if (root instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) root;
                    Object en = m.get("enabled");
                    if (en instanceof Boolean) master = (Boolean) en;
                    Object bc = m.get("breeding-cap");
                    if (bc instanceof Map) {
                        breedingCapConfig = parseBreedingCap((Map<?, ?>) bc);
                    } else {
                        appendSection(cfg, DEFAULT_BREEDING_CAP); // 旧配置补写新段
                        breedingCapConfig = parseBreedingCap(defaultSection(DEFAULT_BREEDING_CAP, "breeding-cap"));
                    }
                    Object dy = m.get("dynamic");
                    if (dy instanceof Map) {
                        dynamicConfig = parseDynamic((Map<?, ?>) dy);
                    } else {
                        appendSection(cfg, DEFAULT_DYNAMIC); // 旧配置补写新段
                        dynamicConfig = parseDynamic(defaultSection(DEFAULT_DYNAMIC, "dynamic"));
                    }
                    Object ft = m.get("features");
                    if (ft instanceof Map) {
                        featureConfig = parseFeatures((Map<?, ?>) ft);
                    } else {
                        appendSection(cfg, DEFAULT_FEATURES); // 旧配置补写新段
                        featureConfig = parseFeatures(defaultSection(DEFAULT_FEATURES, "features"));
                    }
                    Object cm = m.get("commands");
                    if (cm instanceof Map) {
                        commandConfig = parseCommands((Map<?, ?>) cm);
                    } else {
                        appendSection(cfg, DEFAULT_COMMANDS); // 旧配置补写新段
                        commandConfig = parseCommands(defaultSection(DEFAULT_COMMANDS, "commands"));
                    }
                    Object op = m.get("optimizations");
                    if (op instanceof Map) {
                        optimizationConfig = parseOptimizations((Map<?, ?>) op);
                    } else {
                        appendSection(cfg, DEFAULT_OPTIMIZATIONS); // 旧配置补写新段
                        optimizationConfig = parseOptimizations(defaultSection(DEFAULT_OPTIMIZATIONS, "optimizations"));
                    }
                    Object ms = m.get("mob-spawning");
                    if (ms instanceof Map) {
                        mobSpawningConfig = parseMobSpawning((Map<?, ?>) ms);
                        mobSpawningActive = true;
                    } else {
                        appendSection(cfg, DEFAULT_MOB_SPAWNING); // 旧配置补写新段
                        mobSpawningConfig = parseMobSpawning(defaultSection(DEFAULT_MOB_SPAWNING, "mob-spawning"));
                        mobSpawningActive = true;
                    }
                    Object ar = m.get("activation-range");
                    if (ar instanceof Map) {
                        activationRangeConfig = ActivationRangeConfig.parse((Map<?, ?>) ar);
                    } else {
                        appendSection(cfg, DEFAULT_ACTIVATION_RANGE); // 旧配置补写新段
                        activationRangeConfig = ActivationRangeConfig.parse(defaultSection(DEFAULT_ACTIVATION_RANGE, "activation-range"));
                    }
                }
            } catch (IOException | YAMLException e) {
                loadFailed = true; // 读取失败则回退为关闭，保持原版行为
            }
            loaded = true;
        }
    }

    public static BreedingCapConfig breedingCap() {
        load();
        return master ? breedingCapConfig : BreedingCapConfig.DISABLED;
    }

    public static DynamicConfig dynamic() {
        load();
        return master ? dynamicConfig : DynamicConfig.DISABLED;
    }

    public static boolean dynamicActive() {
        load();
        return master && dynamicConfig.enabled();
    }

    public static FeatureConfig features() {
        load();
        return master ? featureConfig : FeatureConfig.DISABLED;
    }

    public static CommandConfig commands() {
        load();
        return master ? commandConfig : CommandConfig.DISABLED;
    }

    public static OptimizationConfig optimizations() {
        load();
        return master ? optimizationConfig : OptimizationConfig.DISABLED;
    }

    public static MobSpawnConfig mobSpawning() {
        load();
        return master ? mobSpawningConfig : MobSpawnConfig.DISABLED;
    }

    public static boolean mobSpawningActive() {
        load();
        return mobSpawningActive;
    }

    public static ActivationRangeConfig activationRange() {
        load();
        if (!master) {
            activationRangeConfig.forceDisable();
        }
        return activationRangeConfig;
    }

    public static boolean isActivationRangeEnabled() {
        return activationRange().enabled();
    }

    public static boolean reload() {
        loaded = false;
        loadFailed = false;
        IMobCategory.restoreAll(); // 先还原旧配置对 MobCategory 的修改
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
            w.close();
        } catch (IOException ignored) {
        }
    }

    private static BreedingCapConfig parseBreedingCap(Map<?, ?> m) {
        boolean enabled = asBool(m.get("enabled"), false);
        BreedingCap villagers = parseCap(asMap(m.get("villagers")));
        BreedingCap animals = parseCap(asMap(m.get("animals")));
        return new BreedingCapConfig(enabled, villagers, animals);
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

    private static FeatureConfig parseFeatures(Map<?, ?> m) {
        boolean enabled = asBool(m.get("enabled"), true);
        boolean disableSpawnChunks = asBool(m.get("disable-spawn-chunks"), false);
        boolean preventMoving = asBool(m.get("prevent-moving-into-unloaded-chunks"), false);
        int autosaveInterval = asInt(m.get("autosave-interval"), 6000);
        if (autosaveInterval < 1) autosaveInterval = 1;
        int xpMergeChance = asInt(m.get("xp-merge-chance"), 8);
        if (xpMergeChance < 1) xpMergeChance = 1;
        double itemMergeRadius = asDouble(m.get("item-merge-radius"), 2.0D);
        if (itemMergeRadius < 0.5D) itemMergeRadius = 0.5D;
        double xpMergeRadius = asDouble(m.get("xp-merge-radius"), 3.0D);
        if (xpMergeRadius < 0.5D) xpMergeRadius = 0.5D;
        return new FeatureConfig(enabled, disableSpawnChunks, preventMoving,
                autosaveInterval, xpMergeChance, itemMergeRadius, xpMergeRadius);
    }

    private static CommandConfig parseCommands(Map<?, ?> m) {
        if (!asBool(m.get("enabled"), true)) return CommandConfig.DISABLED;
        boolean status = asBool(m.get("status"), true);
        boolean mobcaps = asBool(m.get("mobcaps"), true);
        String primary = asColor(m.get("primary-color"), "00aabb");
        String secondary = asColor(m.get("secondary-color"), "55ff55");
        String tertiary = asColor(m.get("tertiary-color"), "55ffff");
        return new CommandConfig(true, status, mobcaps, primary, secondary, tertiary);
    }

    private static OptimizationConfig parseOptimizations(Map<?, ?> m) {
        if (!asBool(m.get("enabled"), true)) return OptimizationConfig.DISABLED;
        boolean mapTicking = asBool(m.get("map-ticking"), true);
        boolean broadcasts = asBool(m.get("chunk-broadcasts"), true);
        boolean randomTicks = asBool(m.get("chunk-random-ticks"), true);
        boolean statistics = asBool(m.get("statistics-command"), true);
        return new OptimizationConfig(true, mapTicking, broadcasts, randomTicks, statistics);
    }

    private static MobSpawnConfig parseMobSpawning(Map<?, ?> m) {
        EnforcedMobcap zombie = parseEnforced(asMap(m.get("zombie-reinforcements")));
        EnforcedMobcap portal = parseEnforced(asMap(m.get("nether-portal-randomticks")));
        EnforcedMobcap spawner = parseEnforced(asMap(m.get("monster-spawners")));
        List<MobSpawnEntry> categories = new ArrayList<>();
        Object list = m.get("categories");
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                Map<?, ?> cm = asMap(item);
                if (cm == null) continue;
                String catName = asStr(cm.get("category"));
                MobCategory mc = safeMobCategory(catName);
                if (mc == null) continue;
                int capacity = asInt(cm.get("mobcap"), mc.getMaxInstancesPerChunk());
                int interval = asInt(cm.get("spawn-interval"), 1);
                int despawn = asInt(cm.get("despawn-distance"), mc.getDespawnDistance());
                categories.add(new MobSpawnEntry(mc, capacity, interval, despawn));
            }
        }
        MobSpawnConfig config = new MobSpawnConfig(zombie, portal, spawner, EnforcedMobcap.DISABLED, categories);
        // 应用配置到 MobCategory 实例（改 max / spawnInterval 等）
        IMobCategory.apply(config.categories());
        return config;
    }

    private static EnforcedMobcap parseEnforced(Map<?, ?> m) {
        if (m == null) return EnforcedMobcap.DISABLED;
        boolean enforce = asBool(m.get("enforce-mobcap"), false);
        int capacity = asInt(m.get("additional-capacity"), 0);
        return enforce ? new EnforcedMobcap(true, capacity) : EnforcedMobcap.DISABLED;
    }

    private static MobCategory safeMobCategory(String name) {
        if (name == null) return null;
        try {
            return MobCategory.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // 补写新段后回填内存值，避免升级首启整段不生效
    private static Map<?, ?> defaultSection(String body, String key) {
        try {
            Object root = new Yaml().load(body);
            if (root instanceof Map) {
                Object sec = ((Map<?, ?>) root).get(key);
                if (sec instanceof Map) return (Map<?, ?>) sec;
            }
        } catch (YAMLException ignored) {
        }
        return Collections.emptyMap();
    }

    private static String asColor(Object o, String def) {
        String s = asStr(o);
        return s == null || s.trim().isEmpty() ? def : s;
    }

    private static DynamicSetting safeDynamicSetting(String name) {
        if (name == null) return null;
        try {
            return DynamicSetting.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
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
