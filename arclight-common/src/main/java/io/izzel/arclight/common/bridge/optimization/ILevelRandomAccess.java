/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

import net.minecraft.util.RandomSource;

/**
 * {@code Level.random} 写入桥（M2.2 World.random 跨线程检测装配用）。
 *
 * <p>字段声明在 {@code Level}，跨子类 {@code ServerLevel} 的构造注入无法
 * 直接 {@code @Mutable @Shadow}（shadow 只在目标类查找），故经本接口在
 * 目标为 {@code Level} 的 mixin 内完成 {@code final} 字段重赋值。
 */
public interface ILevelRandomAccess {

    void prts$setRandom(RandomSource random);
}
