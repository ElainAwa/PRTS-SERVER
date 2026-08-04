package io.izzel.arclight.common.optimization.general.servercore;

/**
 * ServerCore features 开关（移植自 Wesley1808/ServerCore，Mojmap/Forge 1.20.1）。
 * 关闭（master 关或 features.enabled=false）时，值类字段回退原版默认值，使对应 mixin 退化为 no-op。
 * 注：村民脑切(lobotomize)由本树既有 minecrafttweaks.MixinVillager_BrainOffload 提供，此处不再重复。
 */
public final class FeatureConfig {

    public static final FeatureConfig DISABLED = new FeatureConfig(false, false, false, 6000, 40, 0.5D, 0.5D, false, 30, 50);

    private final boolean enabled;
    private final boolean disableSpawnChunks;
    private final boolean preventMovingIntoUnloadedChunks;
    private final int autosaveInterval;
    private final int xpMergeChance;
    private final double itemMergeRadius;
    private final double xpMergeRadius;
    private final boolean reliableChunkSave;
    private final int journalIntervalSeconds;
    private final int journalChunksPerTick;

    public FeatureConfig(boolean enabled, boolean disableSpawnChunks, boolean preventMovingIntoUnloadedChunks,
                         int autosaveInterval, int xpMergeChance, double itemMergeRadius, double xpMergeRadius,
                         boolean reliableChunkSave, int journalIntervalSeconds, int journalChunksPerTick) {
        this.enabled = enabled;
        this.disableSpawnChunks = disableSpawnChunks;
        this.preventMovingIntoUnloadedChunks = preventMovingIntoUnloadedChunks;
        this.autosaveInterval = autosaveInterval;
        this.xpMergeChance = xpMergeChance;
        this.itemMergeRadius = itemMergeRadius;
        this.xpMergeRadius = xpMergeRadius;
        this.reliableChunkSave = reliableChunkSave;
        this.journalIntervalSeconds = journalIntervalSeconds;
        this.journalChunksPerTick = journalChunksPerTick;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean disableSpawnChunks() {
        return enabled && disableSpawnChunks;
    }

    public boolean preventMovingIntoUnloadedChunks() {
        return enabled && preventMovingIntoUnloadedChunks;
    }

    // 关闭时回退原版值，保证 @ModifyConstant 为 no-op
    public int autosaveInterval() {
        return enabled ? autosaveInterval : 6000;
    }

    public int xpMergeChance() {
        return enabled ? xpMergeChance : 40;
    }

    public double itemMergeRadius() {
        return enabled ? itemMergeRadius : 0.5D;
    }

    public double xpMergeRadius() {
        return enabled ? xpMergeRadius : 0.5D;
    }

    /** 可靠区块保存（journal 模式）：true=脏区块写 journal + 启动回放；false=现状。 */
    public boolean reliableChunkSave() {
        return enabled && reliableChunkSave;
    }

    /** journal 写盘周期（秒），最小 5。 */
    public int journalIntervalSeconds() {
        return Math.max(5, journalIntervalSeconds);
    }

    /** journal 分片：每 tick 最多序列化多少个脏区块（分摊主线程卡顿）。 */
    public int journalChunksPerTick() {
        return Math.max(1, journalChunksPerTick);
    }
}
