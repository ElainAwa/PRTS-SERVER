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
    private final boolean reliableChunkSave;
    private final int journalIntervalSeconds;
    private final int journalChunksPerTick;

    public FeatureConfig(boolean preventMovingIntoUnloadedChunks, int autosaveIntervalSeconds,
                         int xpMergeFraction, double xpMergeRadius, double itemMergeRadius,
                         boolean lobotomizeVillagers, int lobotomizedTickInterval,
                         boolean optimizeChunkRandomTicks, boolean optimizeChunkBroadcasts,
                         boolean asyncChunkIoEnabled, boolean reliableChunkSave, int journalIntervalSeconds,
                         int journalChunksPerTick) {
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
        this.reliableChunkSave = reliableChunkSave;
        this.journalIntervalSeconds = journalIntervalSeconds;
        this.journalChunksPerTick = journalChunksPerTick;
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

    /** 可靠区块保存（journal 模式）：true=脏区块写 journal + 启动回放；false=现状。 */
    public boolean reliableChunkSave() {
        return reliableChunkSave;
    }

    /** journal 写盘周期（秒），最小 5，避免过频刷盘。 */
    public int journalIntervalSeconds() {
        return Math.max(5, journalIntervalSeconds);
    }

    /** journal 分片：每 tick 最多序列化多少个脏区块（分摊主线程卡顿）。 */
    public int journalChunksPerTick() {
        return Math.max(1, journalChunksPerTick);
    }

    /** 各值为原版默认：合并基数 40、半径 0.5、自动保存 300 秒、不回弹、不降频、不 journal。 */
    public static final FeatureConfig DISABLED =
            new FeatureConfig(false, 300, 40, 0.5D, 0.5D, false, 20, false, false, false, false, 30, 50);
}
