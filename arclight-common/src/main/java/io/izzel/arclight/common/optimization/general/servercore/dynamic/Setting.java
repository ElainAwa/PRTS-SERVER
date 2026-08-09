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
 * 单个动态设置的数据类（移植自 ServerCore Setting，去掉 dazzleconf 改为普通 POJO）。
 */
public class Setting {
    private final DynamicSetting dynamicSetting;
    private final int max;
    private final int min;
    private final int increment;
    private final int interval;

    public Setting(DynamicSetting dynamicSetting, int max, int min, int increment, int interval) {
        this.dynamicSetting = dynamicSetting;
        this.max = max;
        this.min = min;
        this.increment = increment;
        this.interval = interval;
    }

    public DynamicSetting dynamicSetting() {
        return dynamicSetting;
    }

    public int max() {
        return max;
    }

    public int min() {
        return min;
    }

    public int increment() {
        return increment;
    }

    public int interval() {
        return interval;
    }
}
