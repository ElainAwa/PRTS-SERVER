package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class ChunkOptimizationSpec {

    @Setting("aggressive-chunk-unloading")
    private boolean aggressiveChunkUnloading = false;

    @Setting("chunk-unload-delay")
    private int chunkUnloadDelay = 300;

    @Setting("optimize-chunk-loading")
    private boolean optimizeChunkLoading = true;

    @Setting("chunk-load-rate-limit")
    private int chunkLoadRateLimit = 10;

    // 实验性：将区块读取（磁盘 IO）移到独立线程池，主线程不再被读盘阻塞。
    // 由 optimization.experimental-optimizations-enabled 总开关罩住，默认关闭。
    @Setting("async-chunk-io-enabled")
    private boolean asyncChunkIoEnabled = false;

    public boolean isAggressiveChunkUnloading() {
        return aggressiveChunkUnloading;
    }

    public int getChunkUnloadDelay() {
        return chunkUnloadDelay;
    }

    public boolean isOptimizeChunkLoading() {
        return optimizeChunkLoading;
    }

    public int getChunkLoadRateLimit() {
        return chunkLoadRateLimit;
    }

    public boolean isAsyncChunkIoEnabled() {
        return asyncChunkIoEnabled;
    }
}
