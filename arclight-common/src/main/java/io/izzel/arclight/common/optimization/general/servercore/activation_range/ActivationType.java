/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

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
