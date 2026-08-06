/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.activation_range;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 激活范围配置（移植自 ServerCore，dazzleconf 接口改为 POJO + SnakeYAML 解析）。
 * 实体类型/匹配器延迟解析，避免注册表冻结前触发查表。
 */
public final class ActivationRangeConfig {

    public static final String EXCLUDE_TAG = "exclude_ear";

    private boolean enabled = true;
    private boolean tickNewEntities = true;
    private boolean useVerticalRange = false;
    private boolean skipNonImmune = false;
    private boolean villagerTickPanic = true;
    private int villagerWorkImmunityAfter = 20;
    private int villagerWorkImmunityFor = 20;

    private List<String> excludedTypeIds = new ArrayList<>(Arrays.asList(
            "minecraft:ghast", "minecraft:hopper_minecart", "minecraft:warden"));
    private ActivationType defaultActivationType = new ActivationType(16, 20, -1, false, false);
    private List<RawType> rawTypes = defaultRawTypes();

    private volatile boolean resolved = false;
    private Set<EntityType<?>> excludedEntityTypes = Collections.emptySet();
    private List<CustomActivationType> activationTypes = Collections.emptyList();

    public boolean enabled() {
        return this.enabled;
    }

    /** 总开关关闭时强制停用本组优化。 */
    public void forceDisable() {
        this.enabled = false;
    }

    public boolean tickNewEntities() {
        return this.tickNewEntities;
    }

    public boolean useVerticalRange() {
        return this.useVerticalRange;
    }

    public boolean skipNonImmune() {
        return this.skipNonImmune;
    }

    public boolean villagerTickPanic() {
        return this.villagerTickPanic;
    }

    public int villagerWorkImmunityAfter() {
        return this.villagerWorkImmunityAfter;
    }

    public int villagerWorkImmunityFor() {
        return this.villagerWorkImmunityFor;
    }

    public ActivationType defaultActivationType() {
        return this.defaultActivationType;
    }

    public Set<EntityType<?>> excludedEntityTypes() {
        this.resolve();
        return this.excludedEntityTypes;
    }

    public List<CustomActivationType> activationTypes() {
        this.resolve();
        return this.activationTypes;
    }

    private void resolve() {
        if (this.resolved) return;
        synchronized (this) {
            if (this.resolved) return;
            Set<EntityType<?>> excluded = new HashSet<>();
            for (String id : this.excludedTypeIds) {
                EntityType<?> type = lookupEntityType(id);
                if (type != null) excluded.add(type);
            }
            List<CustomActivationType> types = new ArrayList<>(this.rawTypes.size());
            for (RawType raw : this.rawTypes) {
                List<EntityTypeTest<? super Entity, ?>> matchers = new ArrayList<>(raw.matchers.size());
                for (String spec : raw.matchers) {
                    EntityTypeTest<? super Entity, ?> matcher = resolveMatcher(spec);
                    if (matcher != null) matchers.add(matcher);
                }
                if (matchers.isEmpty()) continue;
                types.add(new CustomActivationType(raw.name, raw.activationRange, raw.tickInterval,
                        raw.wakeupInterval, raw.extraHeightUp, raw.extraHeightDown, matchers));
            }
            this.excludedEntityTypes = excluded;
            this.activationTypes = types;
            this.resolved = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityTypeTest<? super Entity, ?> resolveMatcher(String spec) {
        String s = spec.trim();
        if (s.startsWith("typeof:")) {
            return EntityTypeTests.get(s.substring("typeof:".length()).trim());
        }
        EntityType<?> type = lookupEntityType(s);
        return type == null ? null : (EntityTypeTest<? super Entity, ?>) type;
    }

    private static EntityType<?> lookupEntityType(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id.trim());
        if (key == null) return null;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
    }

    public static ActivationRangeConfig parse(Map<?, ?> section) {
        ActivationRangeConfig cfg = new ActivationRangeConfig();
        if (section == null) return cfg;
        cfg.enabled = readBool(section, "enabled", cfg.enabled);
        cfg.tickNewEntities = readBool(section, "tick-new-entities", cfg.tickNewEntities);
        cfg.useVerticalRange = readBool(section, "use-vertical-range", cfg.useVerticalRange);
        cfg.skipNonImmune = readBool(section, "skip-non-immune", cfg.skipNonImmune);
        cfg.villagerTickPanic = readBool(section, "villager-tick-panic", cfg.villagerTickPanic);
        cfg.villagerWorkImmunityAfter = readInt(section, "villager-work-immunity-after", cfg.villagerWorkImmunityAfter);
        cfg.villagerWorkImmunityFor = readInt(section, "villager-work-immunity-for", cfg.villagerWorkImmunityFor);

        List<String> excluded = readStringList(section.get("excluded-entity-types"));
        if (excluded != null) cfg.excludedTypeIds = excluded;

        Object def = section.get("default-activation-type");
        if (def instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) def;
            cfg.defaultActivationType = new ActivationType(
                    readInt(m, "activation-range", 16),
                    readInt(m, "tick-interval", 20),
                    readInt(m, "wakeup-interval", -1),
                    readBool(m, "extra-height-up", false),
                    readBool(m, "extra-height-down", false));
        }

        Object custom = section.get("custom-activation-types");
        if (custom instanceof List) {
            List<RawType> raws = new ArrayList<>();
            for (Object o : (List<?>) custom) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                List<String> matchers = readStringList(m.get("entity-matcher"));
                if (matchers == null || matchers.isEmpty()) continue;
                Object name = m.get("name");
                raws.add(new RawType(name == null ? "unnamed" : String.valueOf(name),
                        readInt(m, "activation-range", 16),
                        readInt(m, "tick-interval", 20),
                        readInt(m, "wakeup-interval", -1),
                        readBool(m, "extra-height-up", false),
                        readBool(m, "extra-height-down", false),
                        matchers));
            }
            cfg.rawTypes = raws;
        }
        return cfg;
    }

    private static boolean readBool(Map<?, ?> map, String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static int readInt(Map<?, ?> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static List<String> readStringList(Object value) {
        if (!(value instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) value) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static List<RawType> defaultRawTypes() {
        List<RawType> list = new ArrayList<>();
        list.add(new RawType("raider", 48, 20, 20, true, false, Arrays.asList("typeof:raider")));
        list.add(new RawType("water", 16, 20, 60, false, false, Arrays.asList("typeof:water_animal")));
        list.add(new RawType("villager", 16, 20, 30, false, false, Arrays.asList("typeof:villager")));
        list.add(new RawType("zombie", 16, 20, 20, true, false, Arrays.asList("minecraft:zombie", "minecraft:husk")));
        list.add(new RawType("monster-below", 32, 20, 20, true, true,
                Arrays.asList("minecraft:creeper", "minecraft:slime", "minecraft:magma_cube", "minecraft:hoglin")));
        list.add(new RawType("flying-monster", 48, 20, 20, true, false,
                Arrays.asList("minecraft:ghast", "minecraft:phantom")));
        list.add(new RawType("monster", 32, 20, 20, true, false, Arrays.asList("typeof:monster")));
        list.add(new RawType("animal", 16, 20, 60, false, false, Arrays.asList("typeof:animal", "typeof:ambient")));
        list.add(new RawType("creature", 24, 20, 30, false, false, Arrays.asList("typeof:mob")));
        return list;
    }

    private static final class RawType {
        final String name;
        final int activationRange;
        final int tickInterval;
        final int wakeupInterval;
        final boolean extraHeightUp;
        final boolean extraHeightDown;
        final List<String> matchers;

        RawType(String name, int activationRange, int tickInterval, int wakeupInterval,
                boolean extraHeightUp, boolean extraHeightDown, List<String> matchers) {
            this.name = name;
            this.activationRange = activationRange;
            this.tickInterval = tickInterval;
            this.wakeupInterval = wakeupInterval;
            this.extraHeightUp = extraHeightUp;
            this.extraHeightDown = extraHeightDown;
            this.matchers = matchers;
        }
    }
}
