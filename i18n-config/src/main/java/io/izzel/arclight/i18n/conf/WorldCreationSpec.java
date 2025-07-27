package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class WorldCreationSpec {

    @Setting("fast-world-creation")
    private boolean fastWorldCreation = true;

    @Setting("skip-spawn-chunk-loading")
    private boolean skipSpawnChunkLoading = false;

    @Setting("force-close-loading-screen")
    private boolean forceCloseLoadingScreen = true;

    @Setting("early-world-list-addition")
    private boolean earlyWorldListAddition = true;

    @Setting("parallel-world-initialization")
    private boolean parallelWorldInitialization = false;

    @Setting("world-init-timeout-seconds")
    private int worldInitTimeoutSeconds = 30;

    @Setting("max-concurrent-world-loads")
    private int maxConcurrentWorldLoads = 2;

    @Setting("optimize-world-border-setup")
    private boolean optimizeWorldBorderSetup = true;

    @Setting("defer-spawn-area-preparation")
    private boolean deferSpawnAreaPreparation = false;

    @Setting("spawn-area-radius")
    private int spawnAreaRadius = 11;

    @Setting("async-world-data-loading")
    private boolean asyncWorldDataLoading = true;

    public boolean isFastWorldCreation() {
        return fastWorldCreation;
    }

    public boolean isSkipSpawnChunkLoading() {
        return skipSpawnChunkLoading;
    }

    public boolean isForceCloseLoadingScreen() {
        return forceCloseLoadingScreen;
    }

    public boolean isEarlyWorldListAddition() {
        return earlyWorldListAddition;
    }

    public boolean isParallelWorldInitialization() {
        return parallelWorldInitialization;
    }

    public int getWorldInitTimeoutSeconds() {
        return worldInitTimeoutSeconds;
    }

    public int getMaxConcurrentWorldLoads() {
        return maxConcurrentWorldLoads;
    }

    public boolean isOptimizeWorldBorderSetup() {
        return optimizeWorldBorderSetup;
    }

    public boolean isDeferSpawnAreaPreparation() {
        return deferSpawnAreaPreparation;
    }

    public int getSpawnAreaRadius() {
        return spawnAreaRadius;
    }

    public boolean isAsyncWorldDataLoading() {
        return asyncWorldDataLoading;
    }
}
