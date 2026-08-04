package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.util.ArrayList;
import java.util.List;

@ConfigSerializable
public class OptimizationSpec {

    @Setting("cache-plugin-class")
    private boolean cachePluginClass;

    @Setting("goal-selector-update-interval")
    private int goalSelectorInterval;

    @Setting("use-activation-and-tracking-range")
    private boolean useActivationAndTrackingRange;

    @Setting("nearby-player-index-enabled")
    private boolean nearbyPlayerIndexEnabled = false;

    @Setting("nearby-player-index-verify")
    private boolean nearbyPlayerIndexVerify = false;

    @Setting("optimize-powered-rails")
    private boolean optimizePoweredRails = true;

    // --- 实验性：内存缓存清理（默认关闭）---
    @Setting("memory-cache-cleanup-enabled")
    private boolean memoryCacheCleanupEnabled = false;

    @Setting("memory-cache-cleanup-interval")
    private int memoryCacheCleanupInterval = 300;

    @Setting("memory-cache-cleanup-targets")
    private List<String> memoryCacheCleanupTargets = new ArrayList<>();

    // --- 实验性：网络线程调优（默认关闭）---
    @Setting("network-optimization-enabled")
    private boolean networkOptimizationEnabled = false;

    @Setting("network-optimization-netty-threads")
    private int networkOptimizationNettyThreads = 0;

    // --- 实体优化：禁用实体互推（默认关闭，行为不变）---
    @Setting("disable-entity-collisions")
    private boolean disableEntityCollisions = false;

    public boolean isOptimizePoweredRails() {
        return optimizePoweredRails;
    }

    public boolean isMemoryCacheCleanupEnabled() {
        return memoryCacheCleanupEnabled;
    }

    public int getMemoryCacheCleanupInterval() {
        return memoryCacheCleanupInterval;
    }

    public List<String> getMemoryCacheCleanupTargets() {
        return memoryCacheCleanupTargets;
    }

    public boolean isNetworkOptimizationEnabled() {
        return networkOptimizationEnabled;
    }

    public boolean isDisableEntityCollisions() {
        return disableEntityCollisions;
    }

    public int getNetworkOptimizationNettyThreads() {
        return networkOptimizationNettyThreads;
    }

    public boolean isNearbyPlayerIndexEnabled() {
        return nearbyPlayerIndexEnabled;
    }

    public boolean isNearbyPlayerIndexVerify() {
        return nearbyPlayerIndexVerify;
    }

    public boolean useActivationAndTrackingRange() {
        return useActivationAndTrackingRange;
    }

    public boolean isCachePluginClass() {
        return cachePluginClass;
    }

    public int getGoalSelectorInterval() {
        return goalSelectorInterval;
    }
}
