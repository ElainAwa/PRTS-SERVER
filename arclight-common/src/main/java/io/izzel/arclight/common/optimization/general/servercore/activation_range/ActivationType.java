package io.izzel.arclight.common.optimization.general.servercore.activation_range;

/**
 * 激活类型参数组（移植自 ServerCore，dazzleconf 接口改为 POJO）。
 */
public class ActivationType {

    private final int activationRange;
    private final int tickInterval;
    private final int wakeupInterval;
    private final boolean extraHeightUp;
    private final boolean extraHeightDown;

    public ActivationType(int activationRange, int tickInterval, int wakeupInterval, boolean extraHeightUp, boolean extraHeightDown) {
        this.activationRange = activationRange;
        this.tickInterval = tickInterval;
        this.wakeupInterval = wakeupInterval;
        this.extraHeightUp = extraHeightUp;
        this.extraHeightDown = extraHeightDown;
    }

    public int activationRange() {
        return this.activationRange;
    }

    public int tickInterval() {
        return this.tickInterval;
    }

    public int wakeupInterval() {
        return this.wakeupInterval;
    }

    public boolean extraHeightUp() {
        return this.extraHeightUp;
    }

    public boolean extraHeightDown() {
        return this.extraHeightDown;
    }
}
