/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.core.world.server;

/**
 * 主线程消费 chunk 需求队列的桥接接口（由 ServerChunkCacheMixin_DimParallel 实现，
 * 主线程 tick / 维度 PRE 阶段经此接口调用，避免直接引用 mixin 类）。
 */
public interface ServerChunkCacheDemandBridge {

    void arclight$drainChunkDemands();
}
