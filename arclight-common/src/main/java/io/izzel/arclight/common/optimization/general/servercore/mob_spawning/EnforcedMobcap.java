package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

// 单组生成来源的 mobcap 约束设置（移植自 ServerCore EnforcedMobcap）。
public record EnforcedMobcap(boolean enforcesMobcap, int additionalCapacity) {
    public static final EnforcedMobcap DISABLED = new EnforcedMobcap(false, 0);
}
