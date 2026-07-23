package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.util.Arrays;
import java.util.List;

@ConfigSerializable
public class MemoryOptimizationSpec {

    @Setting("cache-cleanup-enabled")
    private final boolean cacheCleanupEnabled = true;

    @Setting("cache-cleanup-interval")
    private final int cacheCleanupInterval = 300;

    // 实验性：周期性清理的缓存目标（主线程执行，默认空 = 零影响）。
    // 格式："fully.qualified.Class#fieldName" 或 "fully.qualified.Class"（尝试调用其 clear()）。
    // 仅清理你已在测试服验证过、确认安全可清的缓存；切勿填入模组关键状态缓存。
    // 由 optimization.experimental-optimizations-enabled 总开关罩住。
    @Setting("cache-cleanup-targets")
    private final List<String> cacheCleanupTargets = Arrays.asList();

    public boolean isCacheCleanupEnabled() {
        return cacheCleanupEnabled;
    }

    public int getCacheCleanupInterval() {
        return cacheCleanupInterval;
    }

    public List<String> getCacheCleanupTargets() {
        return cacheCleanupTargets;
    }
}
