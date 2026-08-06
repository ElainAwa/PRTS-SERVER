/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.dynamic;

/**
 * MinecraftServer duck interface，持有动态管理器实例（移植自 ServerCore IMinecraftServer，精简为仅 dynamic 所需）。
 */
public interface IMinecraftServer {
    void servercore$setDynamicManager(DynamicManager manager);

    DynamicManager servercore$getDynamicManager();
}
