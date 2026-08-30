/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.compat.SableNativeLock;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Sable rapier native 串行化：维度 worker 的 step/tick 与主线程绳索
 * queryRope/removeRope 用同一把锁，消除跨线程 native 死锁。
 */
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
public abstract class SableNativeSerializationMixin {

    @Mixin(value = RapierPhysicsPipeline.class, remap = false)
    public abstract static class RapierPhysicsPipelineLock {

        @WrapMethod(method = "prePhysicsTicks")
        private void prts$lockPreTicks(Operation<Void> original) {
            SableNativeLock.LOCK.lock();
            try {
                original.call();
            } finally {
                SableNativeLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "physicsTick")
        private void prts$lockTick(double delta, Operation<Void> original) {
            SableNativeLock.LOCK.lock();
            try {
                original.call(delta);
            } finally {
                SableNativeLock.LOCK.unlock();
            }
        }
    }

    @Mixin(value = RapierRopeHandle.class, remap = false)
    public abstract static class RapierRopeHandleLock {

        @WrapMethod(method = "readPose")
        private void prts$lockReadPose(List<Vector3d> out, Operation<Void> original) {
            SableNativeLock.LOCK.lock();
            try {
                original.call(out);
            } finally {
                SableNativeLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "remove")
        private void prts$lockRemove(Operation<Void> original) {
            SableNativeLock.LOCK.lock();
            try {
                original.call();
            } finally {
                SableNativeLock.LOCK.unlock();
            }
        }
    }
}
