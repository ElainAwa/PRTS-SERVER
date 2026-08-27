/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.bridge.optimization.ILevelRandomAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * {@code Level.random} 字段持有面（M2.2 四件套 ④ 装配辅助）。
 *
 * <p>{@code random} 声明在 {@code Level} 且为实例初始化器 {@code final}；
 * 装配点（{@code ServerLevelMixin_RandomGuard} 的构造注入）在子类上无法
 * 直接 {@code @Mutable @Shadow} 继承字段，故字段重赋值收敛到本目标为
 * {@code Level} 的 mixin，经 {@link ILevelRandomAccess} 暴露。
 */
@Mixin(Level.class)
public abstract class LevelMixin_RandomGuardField implements ILevelRandomAccess {

    @Mutable
    @Shadow
    @Final
    public RandomSource random;

    @Override
    public void prts$setRandom(RandomSource random) {
        this.random = random;
    }
}
