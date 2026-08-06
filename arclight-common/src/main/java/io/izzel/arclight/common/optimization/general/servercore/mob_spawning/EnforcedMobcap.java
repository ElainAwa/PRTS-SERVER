/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

// 单组生成来源的 mobcap 约束设置（移植自 ServerCore EnforcedMobcap）。
public record EnforcedMobcap(boolean enforcesMobcap, int additionalCapacity) {
    public static final EnforcedMobcap DISABLED = new EnforcedMobcap(false, 0);
}
