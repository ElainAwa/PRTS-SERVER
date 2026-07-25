package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

/**
 * 源自 Mohist 1.20.1 下游移植的优化/兼容性子开关（去 Mohist 化）。
 * 各自独立开关，默认全开，可单独 A/B；重启生效。
 */
@ConfigSerializable
public class MinecraftOptimizationSpec {

    // 村民脑切：卡住（无法寻路）的村民跳过 brain tick，零感知；铁农场床方块放行。默认开启。
    @Setting("villager-brain-offload")
    private boolean villagerBrainOffload = true;

    // 出生点区块不常驻：启动加载完出生区域后移除 START ticket，出生点等同普通区块按需加载。默认开启。
    @Setting("disable-spawn-chunks")
    private boolean disableSpawnChunks = true;

    // 刷怪笼 nextSpawnData 空守卫：getSpawner 返回前若 nextSpawnData 为 null 补默认 SpawnData，防 NPE 崩。默认开启（安全修复）。
    @Setting("spawner-null-guard")
    private boolean spawnerNullGuard = true;

    // HolderSet.contains 空守卫：入参 Holder 为 null 时返回 false 而非 NPE。默认开启（安全修复）。
    @Setting("holderset-null-guard")
    private boolean holdersetNullGuard = true;

    public boolean isVillagerBrainOffloadEnabled() {
        return villagerBrainOffload;
    }

    public boolean isDisableSpawnChunksEnabled() {
        return disableSpawnChunks;
    }

    public boolean isSpawnerNullGuardEnabled() {
        return spawnerNullGuard;
    }

    public boolean isHoldersetNullGuardEnabled() {
        return holdersetNullGuard;
    }
}
