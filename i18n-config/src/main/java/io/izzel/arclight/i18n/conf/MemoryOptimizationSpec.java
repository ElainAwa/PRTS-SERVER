package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class MemoryOptimizationSpec {

    @Setting("cache-cleanup-enabled")
    private final boolean cacheCleanupEnabled = true;

    @Setting("cache-cleanup-interval")
    private final int cacheCleanupInterval = 300;

    public boolean isCacheCleanupEnabled() {
        return cacheCleanupEnabled;
    }

    public int getCacheCleanupInterval() {
        return cacheCleanupInterval;
    }
}
