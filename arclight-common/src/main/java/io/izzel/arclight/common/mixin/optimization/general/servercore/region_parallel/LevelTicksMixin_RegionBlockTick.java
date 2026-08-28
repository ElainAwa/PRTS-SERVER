/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Region-level scheduled tick collection: redirects {@code BiConsumer.accept} in
 * {@code LevelTicks.runCollectedTicks} to dispatch block ticks into the owning
 * region's queue (fluid ticks fall through). {@code LevelTicks} is not thread-safe,
 * so {@link LevelTicks#schedule} on a worker is deferred to the main thread.
 *
 * <p>维度并行下 {@code LevelTicks.tick}（维度 worker 收集/清空计划 tick 容器）与
 * 主线程延迟调度 drain（{@code LevelTicks.schedule} 写入同一容器）并发访问
 * LevelChunkTicks → 哈希表撕裂 AIOOBE（生产 2026-08-29 崩溃）。tick 与
 * schedule 加同一把可重入锁串行化（tick 内重调度同线程重入安全）。
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin_RegionBlockTick {

    @Unique
    private final ReentrantLock prts$tickLock = new ReentrantLock();

    @Redirect(method = "runCollectedTicks(Ljava/util/function/BiConsumer;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private static void arclight$regionScheduledTick(BiConsumer<Object, Object> consumer, Object pos, Object type) {
        if (type instanceof Block && PRTSFeaturesConfig.parallelRegion) {
            RegionTickManager.collectBlockTick((BlockPos) pos, (Block) type);
        } else {
            consumer.accept(pos, type);
        }
    }

    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V",
        at = @At("HEAD"), cancellable = true)
    private void arclight$regionScheduleLock(ScheduledTick<?> tick, CallbackInfo ci) {
        // Only a real region worker defers; the owning LevelTicks is captured so the
        // deferred task is re-scheduled into the correct dimension's tick list.
        if (RegionTickManager.isRegionWorker()) {
            RegionTickManager.collectScheduleTick((LevelTicks<?>) (Object) this, tick);
            ci.cancel();
        } else {
            this.prts$tickLock.lock();
        }
    }

    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V",
        at = @At("RETURN"))
    private void arclight$regionScheduleUnlock(ScheduledTick<?> tick, CallbackInfo ci) {
        if (!RegionTickManager.isRegionWorker()) {
            this.prts$tickLock.unlock();
        }
    }

    @Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("HEAD"))
    private void arclight$tickLock(long gameTime, int subTickCount, BiConsumer<BlockPos, ?> consumer, CallbackInfo ci) {
        this.prts$tickLock.lock();
    }

    @Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("RETURN"))
    private void arclight$tickUnlock(long gameTime, int subTickCount, BiConsumer<BlockPos, ?> consumer, CallbackInfo ci) {
        this.prts$tickLock.unlock();
    }
}
