/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.ticking;

import net.minecraft.server.level.ChunkHolder;

/** 记录本 tick 真正发生变更的区块，供 broadcast 阶段精确广播。 */
public interface IServerChunkCache {

    void arclight$requiresBroadcast(ChunkHolder holder);
}
