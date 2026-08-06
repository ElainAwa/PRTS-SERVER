/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

import java.util.List;

// mob-spawning 配置段（移植自 ServerCore MobSpawnConfig）。
public record MobSpawnConfig(
        EnforcedMobcap zombieReinforcements,
        EnforcedMobcap portalRandomTicks,
        EnforcedMobcap monsterSpawner,
        EnforcedMobcap infested,
        List<MobSpawnEntry> categories
) {
    public static final MobSpawnConfig DISABLED = new MobSpawnConfig(
            EnforcedMobcap.DISABLED,
            EnforcedMobcap.DISABLED,
            EnforcedMobcap.DISABLED,
            EnforcedMobcap.DISABLED,
            List.of()
    );
}
