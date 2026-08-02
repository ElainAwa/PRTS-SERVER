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

    public FeatureConfig(boolean preventMovingIntoUnloadedChunks, int autosaveIntervalSeconds,
                         int xpMergeFraction, double xpMergeRadius, double itemMergeRadius,
                         boolean lobotomizeVillagers, int lobotomizedTickInterval) {
        this.preventMovingIntoUnloadedChunks = preventMovingIntoUnloadedChunks;
        this.autosaveIntervalSeconds = autosaveIntervalSeconds;
        this.xpMergeFraction = xpMergeFraction;
        this.xpMergeRadius = xpMergeRadius;
        this.itemMergeRadius = itemMergeRadius;
        this.lobotomizeVillagers = lobotomizeVillagers;
        this.lobotomizedTickInterval = lobotomizedTickInterval;
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

    /** 各值为原版默认：合并基数 40、半径 0.5、自动保存 300 秒、不回弹、不降频。 */
    public static final FeatureConfig DISABLED =
            new FeatureConfig(false, 300, 40, 0.5D, 0.5D, false, 20);
}
