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
