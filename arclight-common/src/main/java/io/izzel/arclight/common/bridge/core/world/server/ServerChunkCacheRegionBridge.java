/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.core.world.server;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 并行 worker 可用的活区块检查桥接（由 ServerChunkCacheMixin_DimParallel 实现）：
 * 只读 visible/updating 两张 map 的已完成 future，无主线程派发、无阻塞。
 */
public interface ServerChunkCacheRegionBridge {

    boolean arclight$hasLiveChunk(int x, int z);

    /** worker 安全的区块读取（快照读优先，updating 兜底）；无活区块返回 null。 */
    ChunkAccess arclight$getChunkForRead(int x, int z);

    /** 提交异步 chunk 加载需求（与 getChunk miss 同一条 ChunkDemandQueue 路径，不阻塞）。 */
    void arclight$submitChunkDemand(int x, int z);
}
