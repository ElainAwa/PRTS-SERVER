/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.core.world.server;

/**
 * 并行 worker 可用的活区块检查桥接（由 ServerChunkCacheMixin_DimParallel 实现）：
 * 只读 visible/updating 两张 map 的已完成 future，无主线程派发、无阻塞。
 */
public interface ServerChunkCacheRegionBridge {

    boolean arclight$hasLiveChunk(int x, int z);
}
