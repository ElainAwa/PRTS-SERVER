/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * 计划 tick 分发 + 并发串行化：block tick 排主线程 POST（worker 执行会丢邻居通知语义），
 * fluid 落原路径；tick 容器访问（收集/调度/增删容器）加同一把可重入锁，
 * 维度 worker 与主线程延迟调度并发安全。
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin_RegionBlockTick {

    @Unique
    private final ReentrantLock prts$tickLock = new ReentrantLock();

    @Redirect(method = "runCollectedTicks(Ljava/util/function/BiConsumer;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private static void arclight$regionScheduledTick(BiConsumer<Object, Object> consumer, Object pos, Object type) {
        ServerLevel level = RegionTickManager.collectingLevel();
        if (level == null) {
            consumer.accept(pos, type);
        } else if (type instanceof Block && PRTSFeaturesConfig.parallelRegion) {
            RegionTickManager.queueMainThreadBlockTick(level, (BlockPos) pos, (Block) type);
        } else if (type instanceof net.minecraft.world.level.material.Fluid) {
            // 流体 tick（岩浆扩散链）一律主线程 POST：setBlock/onRemove 跨线程链在
            // worker 上产生违规与延迟堆积，主线程预算内处理保语义且背压有界。
            RegionTickManager.queueMainThreadFluidTick(level, (BlockPos) pos,
                    (net.minecraft.world.level.material.Fluid) type);
        } else {
            consumer.accept(pos, type);
        }
    }

    @WrapMethod(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V")
    private void arclight$regionScheduleLocked(ScheduledTick<?> tick, Operation<Void> original) {
        if (RegionTickManager.isRegionWorker()) {
            RegionTickManager.collectScheduleTick((LevelTicks<?>) (Object) this, tick);
            return;
        }
        this.prts$tickLock.lock();
        try {
            original.call(tick);
        } finally {
            this.prts$tickLock.unlock();
        }
    }

    @WrapMethod(method = "tick(JILjava/util/function/BiConsumer;)V")
    private void arclight$tickLocked(long gameTime, int subTickCount, BiConsumer<BlockPos, ?> consumer,
                                     Operation<Void> original) {
        this.prts$tickLock.lock();
        try {
            original.call(gameTime, subTickCount, consumer);
        } finally {
            this.prts$tickLock.unlock();
        }
    }

    @WrapMethod(method = "sortContainersToTick(J)V")
    private void arclight$sortLocked(long currentTick, Operation<Void> original) {
        this.prts$tickLock.lock();
        try {
            original.call(currentTick);
        } finally {
            this.prts$tickLock.unlock();
        }
    }

    @WrapMethod(method = "addContainer(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/ticks/LevelChunkTicks;)V")
    private void arclight$addContainerLocked(ChunkPos pos, LevelChunkTicks<?> container, Operation<Void> original) {
        this.prts$tickLock.lock();
        try {
            original.call(pos, container);
        } finally {
            this.prts$tickLock.unlock();
        }
    }

    @WrapMethod(method = "removeContainer(Lnet/minecraft/world/level/ChunkPos;)V")
    private void arclight$removeContainerLocked(ChunkPos pos, Operation<Void> original) {
        this.prts$tickLock.lock();
        try {
            original.call(pos);
        } finally {
            this.prts$tickLock.unlock();
        }
    }
}
