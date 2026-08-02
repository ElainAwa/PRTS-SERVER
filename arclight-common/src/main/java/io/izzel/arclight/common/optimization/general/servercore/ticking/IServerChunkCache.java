package io.izzel.arclight.common.optimization.general.servercore.ticking;

import net.minecraft.server.level.ChunkHolder;

/** 记录本 tick 真正发生变更的区块，供 broadcast 阶段精确广播。 */
public interface IServerChunkCache {

    void arclight$requiresBroadcast(ChunkHolder holder);
}
