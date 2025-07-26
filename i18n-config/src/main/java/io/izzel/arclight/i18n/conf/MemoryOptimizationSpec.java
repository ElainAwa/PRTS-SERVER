package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class MemoryOptimizationSpec {

    @Setting("memory-clean-interval")
    private int memoryCleanInterval = 600;

    @Setting("enable-gc")
    private boolean enableGC = false;

    @Setting("entity-cleanup-enabled")
    private boolean entityCleanupEnabled = true;

    @Setting("cache-cleanup-enabled")
    private boolean cacheCleanupEnabled = true;

    @Setting("memory-threshold")
    private double memoryThreshold = 0.8;

    @Setting("aggressive-cleanup")
    private boolean aggressiveCleanup = false;

    public int getMemoryCleanInterval() {
        return memoryCleanInterval;
    }

    public boolean isEnableGC() {
        return enableGC;
    }

    public boolean isEntityCleanupEnabled() {
        return entityCleanupEnabled;
    }

    public boolean isCacheCleanupEnabled() {
        return cacheCleanupEnabled;
    }

    public double getMemoryThreshold() {
        return memoryThreshold;
    }

    public boolean isAggressiveCleanup() {
        return aggressiveCleanup;
    }
}
