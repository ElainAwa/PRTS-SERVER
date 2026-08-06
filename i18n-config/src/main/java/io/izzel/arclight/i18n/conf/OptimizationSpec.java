/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from Mohist by MohistMC
 * (https://github.com/MohistMC/Mohist), licensed under GPL-3.0.
 * Original code Copyright (c) MohistMC.
 */

package io.izzel.arclight.i18n.conf;

import io.izzel.arclight.i18n.conf.MinecraftOptimizationSpec;
import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class OptimizationSpec {

    @Setting("cache-plugin-class")
    private boolean cachePluginClass;

    @Setting("goal-selector-update-interval")
    private int goalSelectorInterval;

    @Setting("use-activation-and-tracking-range")
    private boolean useActivationAndTrackingRange;

    // Entity optimization settings
    @Setting("entity-optimization")
    private EntityOptimizationSpec entityOptimization;

    // Chunk optimization settings
    @Setting("chunk-optimization")
    private ChunkOptimizationSpec chunkOptimization;

    // Memory optimization settings
    @Setting("memory-optimization")
    private MemoryOptimizationSpec memoryOptimization;

    // Async system settings
    @Setting("async-system")
    private AsyncSystemSpec asyncSystem;

    // World creation optimization settings
    @Setting("world-creation")
    private WorldCreationSpec worldCreation;

    // Network optimization settings
    @Setting("network-optimization")
    private NetworkOptimizationSpec networkOptimization;

    // ============================================================
    // 实验性核心优化总开关（默认开启）
    // 罩住 route-b / servercore 等并入模组功能。只有本开关为 true 时，
    // 各子开关才会真正生效。默认 true => 默认开启并入优化。
    // ============================================================
    @Setting("experimental-optimizations-enabled")
    private boolean experimentalOptimizationsEnabled = true;

    // 空间化实体追踪（源自 HariPlayer 的 use_optimized_entity_tracking / AreaMap）独立开关
    @Setting("route-b")
    private RouteBSpec routeB;

    // ServerCore 优化（sync_loads / tickets / biome_lookups / pathfinder 12 个 mixin）独立开关
    @Setting("servercore")
    private ServerCoreSpec serverCore;

    // 移动零速度跳过（源自 HariPlayer move_zero_velocity，静止实体跳过 move 碰撞/位置计算）独立开关
    @Setting("move-zero-velocity")
    private MoveZeroVelocitySpec moveZeroVelocity;

    // 异步日志 Appender（源自 HariPlayer async_logging，log4j2 AsyncAppender 包裹根日志）独立开关
    @Setting("async-logging")
    private AsyncLoggingSpec asyncLogging;

    // 空间最近玩家索引（NearbyPlayerIndex：AreaMap 桶索引加速 checkDespawn/刷怪笼全玩家扫描）独立开关
    @Setting("nearby-player-index")
    private NearbyPlayerIndexSpec nearbyPlayerIndex;

    // 源自 Mohist 1.20.1 的下游移植（去 Mohist 化：村民脑切 / 出生点区块 / 两条 NPE 守卫）独立开关
    @Setting("minecraft-optimizations")
    private MinecraftOptimizationSpec minecraftOptimizations;

    public boolean isCachePluginClass() {
        return cachePluginClass;
    }

    public int getGoalSelectorInterval() {
        return goalSelectorInterval;
    }

    public boolean useActivationAndTrackingRange() {
        return useActivationAndTrackingRange;
    }

    public EntityOptimizationSpec getEntityOptimization() {
        return entityOptimization != null ? entityOptimization : new EntityOptimizationSpec();
    }

    public ChunkOptimizationSpec getChunkOptimization() {
        return chunkOptimization != null ? chunkOptimization : new ChunkOptimizationSpec();
    }

    public MemoryOptimizationSpec getMemoryOptimization() {
        return memoryOptimization != null ? memoryOptimization : new MemoryOptimizationSpec();
    }

    public AsyncSystemSpec getAsyncSystem() {
        return asyncSystem != null ? asyncSystem : new AsyncSystemSpec();
    }

    public WorldCreationSpec getWorldCreation() {
        return worldCreation != null ? worldCreation : new WorldCreationSpec();
    }

    public NetworkOptimizationSpec getNetworkOptimization() {
        return networkOptimization != null ? networkOptimization : new NetworkOptimizationSpec();
    }

    public boolean isExperimentalOptimizationsEnabled() {
        return experimentalOptimizationsEnabled;
    }

    public RouteBSpec getRouteB() {
        return routeB != null ? routeB : new RouteBSpec();
    }

    public ServerCoreSpec getServerCore() {
        return serverCore != null ? serverCore : new ServerCoreSpec();
    }

    // routeB 实际生效 = 总开关 && 子开关（默认全开，保持现状）
    public boolean isRouteBEnabled() {
        return experimentalOptimizationsEnabled && getRouteB().isEnabled();
    }

    // ServerCore 实际生效 = 总开关 && 子开关（默认全开，保持现状）
    public boolean isServerCoreEnabled() {
        return experimentalOptimizationsEnabled && getServerCore().isEnabled();
    }

    public MoveZeroVelocitySpec getMoveZeroVelocity() {
        return moveZeroVelocity != null ? moveZeroVelocity : new MoveZeroVelocitySpec();
    }

    public AsyncLoggingSpec getAsyncLogging() {
        return asyncLogging != null ? asyncLogging : new AsyncLoggingSpec();
    }

    // move-zero-velocity 实际生效 = 总开关 && 子开关（默认全开）
    public boolean isMoveZeroVelocityEnabled() {
        return experimentalOptimizationsEnabled && getMoveZeroVelocity().isEnabled();
    }

    // async-logging 实际生效 = 总开关 && 子开关（默认全开）
    public boolean isAsyncLoggingEnabled() {
        return experimentalOptimizationsEnabled && getAsyncLogging().isEnabled();
    }

    public NearbyPlayerIndexSpec getNearbyPlayerIndex() {
        return nearbyPlayerIndex != null ? nearbyPlayerIndex : new NearbyPlayerIndexSpec();
    }

    public MinecraftOptimizationSpec getMinecraftOptimizations() {
        return minecraftOptimizations != null ? minecraftOptimizations : new MinecraftOptimizationSpec();
    }

    // nearby-player-index 实际生效 = 总开关 && 子开关（默认关闭，观察期后手动开启）
    public boolean isNearbyPlayerIndexEnabled() {
        return experimentalOptimizationsEnabled && getNearbyPlayerIndex().isEnabled();
    }
}
