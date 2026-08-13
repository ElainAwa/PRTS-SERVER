/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiFunction;

/**
 * Sable 用两个无锁的 fastutil 缓存做方块固体/完整判定，被物理线程与主线程
 * 并发 computeIfAbsent 时 rehash 损坏(Index -1)。仅当 Sable 加载时，把
 * isSolid/isFullBlock 的缓存查询串行化，消除并发写。
 */
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(targets = "dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState", remap = false)
public abstract class VoxelNeighborhoodStateMixin_Sable {

    @Redirect(method = "isSolid",
        at = @At(value = "INVOKE", target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object arclight$lockSolidApply(BiFunction<Object, Object, Object> fn, Object getter, Object state) {
        synchronized (VoxelNeighborhoodStateMixin_Sable.class) {
            return fn.apply(getter, state);
        }
    }

    @Redirect(method = "isFullBlock",
        at = @At(value = "INVOKE", target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object arclight$lockFullBlockApply(BiFunction<Object, Object, Object> fn, Object getter, Object state) {
        synchronized (VoxelNeighborhoodStateMixin_Sable.class) {
            return fn.apply(getter, state);
        }
    }
}
