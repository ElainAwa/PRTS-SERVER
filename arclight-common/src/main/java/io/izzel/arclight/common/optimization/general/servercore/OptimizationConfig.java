/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore;

/**
 * ServerCore optimizations 开关（移植自 Wesley1808/ServerCore，Mojmap/Forge 1.20.1）。
 * 关闭时对应 mixin 走原版回退分支，不改变任何行为。
 */
public final class OptimizationConfig {

    public static final OptimizationConfig DISABLED = new OptimizationConfig(false, false, false, false, false);

    private final boolean enabled;
    private final boolean mapTicking;
    private final boolean chunkBroadcasts;
    private final boolean chunkRandomTicks;
    private final boolean statisticsCommand;

    public OptimizationConfig(boolean enabled, boolean mapTicking, boolean chunkBroadcasts,
                              boolean chunkRandomTicks, boolean statisticsCommand) {
        this.enabled = enabled;
        this.mapTicking = mapTicking;
        this.chunkBroadcasts = chunkBroadcasts;
        this.chunkRandomTicks = chunkRandomTicks;
        this.statisticsCommand = statisticsCommand;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean optimizeMapTicking() {
        return enabled && mapTicking;
    }

    public boolean optimizeChunkBroadcasts() {
        return enabled && chunkBroadcasts;
    }

    public boolean optimizeChunkRandomTicks() {
        return enabled && chunkRandomTicks;
    }

    public boolean statisticsCommandEnabled() {
        return enabled && statisticsCommand;
    }
}
