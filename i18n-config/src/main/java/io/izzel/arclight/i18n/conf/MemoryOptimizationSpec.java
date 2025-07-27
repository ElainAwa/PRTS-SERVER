package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class MemoryOptimizationSpec {

    @Setting("entity-cleanup-enabled")
    private boolean entityCleanupEnabled = true;

    @Setting("cache-cleanup-enabled")
    private boolean cacheCleanupEnabled = true;

    @Setting("cache-cleanup-interval")
    private int cacheCleanupInterval = 300;

    public boolean isEntityCleanupEnabled() {
        return entityCleanupEnabled;
    }

    public boolean isCacheCleanupEnabled() {
        return cacheCleanupEnabled;
    }

    public int getCacheCleanupInterval() {
        return cacheCleanupInterval;
    }
}
