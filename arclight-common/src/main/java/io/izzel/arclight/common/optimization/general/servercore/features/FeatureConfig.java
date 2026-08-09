/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.features;

/**
 * features 段配置（移植自 Wesley1808/ServerCore FeatureConfig）。
 * DISABLED 的各值即原版行为，总开关关闭时返回它以让所有 mixin 自动回落。
 */
public class FeatureConfig {

    /** 记分板标签：给村民打上后永久豁免降频 tick。 */
    public static final String EXCLUDE_LOBOTOMIZATION = "exclude_lobotomization";

    private final boolean preventMovingIntoUnloadedChunks;
    private final int autosaveIntervalSeconds;
    private final int xpMergeFraction;
    private final double xpMergeRadius;
    private final double itemMergeRadius;
    private final boolean lobotomizeVillagers;
    private final int lobotomizedTickInterval;
    private final boolean optimizeChunkRandomTicks;
    private final boolean optimizeChunkBroadcasts;
    private final boolean asyncChunkIoEnabled;

    public FeatureConfig(boolean preventMovingIntoUnloadedChunks, int autosaveIntervalSeconds,
                         int xpMergeFraction, double xpMergeRadius, double itemMergeRadius,
                         boolean lobotomizeVillagers, int lobotomizedTickInterval,
                         boolean optimizeChunkRandomTicks, boolean optimizeChunkBroadcasts,
                         boolean asyncChunkIoEnabled) {
        this.preventMovingIntoUnloadedChunks = preventMovingIntoUnloadedChunks;
        this.autosaveIntervalSeconds = autosaveIntervalSeconds;
        this.xpMergeFraction = xpMergeFraction;
        this.xpMergeRadius = xpMergeRadius;
        this.itemMergeRadius = itemMergeRadius;
        this.lobotomizeVillagers = lobotomizeVillagers;
        this.lobotomizedTickInterval = lobotomizedTickInterval;
        this.optimizeChunkRandomTicks = optimizeChunkRandomTicks;
        this.optimizeChunkBroadcasts = optimizeChunkBroadcasts;
        this.asyncChunkIoEnabled = asyncChunkIoEnabled;
    }

    public boolean preventMovingIntoUnloadedChunks() {
        return preventMovingIntoUnloadedChunks;
    }

    public int autosaveIntervalSeconds() {
        return autosaveIntervalSeconds;
    }

    public int xpMergeFraction() {
        return xpMergeFraction;
    }

    public double xpMergeRadius() {
        return xpMergeRadius;
    }

    public double itemMergeRadius() {
        return itemMergeRadius;
    }

    public boolean lobotomizeVillagers() {
        return lobotomizeVillagers;
    }

    /** 最小 2，避免取模为 0 或每 tick 都过。 */
    public int lobotomizedTickInterval() {
        return Math.max(2, lobotomizedTickInterval);
    }

    public boolean optimizeChunkRandomTicks() {
        return optimizeChunkRandomTicks;
    }

    public boolean optimizeChunkBroadcasts() {
        return optimizeChunkBroadcasts;
    }

    public boolean asyncChunkIoEnabled() {
        return asyncChunkIoEnabled;
    }

    /** 各值为原版默认：合并基数 40、半径 0.5、自动保存 300 秒、不回弹、不降频、不异步 IO。 */
    public static final FeatureConfig DISABLED =
            new FeatureConfig(false, 300, 40, 0.5D, 0.5D, false, 20, false, false, false);
}
