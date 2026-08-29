/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerLevelRegionBlockTickAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Region-level scheduled block tick split: exposes the private {@code tickBlock}
 * via {@link ServerLevelRegionBlockTickAccess}, and turns the
 * {@code ServerChunkCache.tick} call into the phase boundary where collected
 * scheduled block ticks run in parallel on region workers.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_RegionBlockTick implements ServerLevelRegionBlockTickAccess {

    @Invoker("tickBlock")
    protected abstract void arclight$invokerTickBlock(BlockPos pos, Block block);

    @Override
    public void arclight$tickBlock(BlockPos pos, Block block) {
        this.arclight$invokerTickBlock(pos, block);
    }

    @Invoker("tickFluid")
    protected abstract void arclight$invokerTickFluid(BlockPos pos, net.minecraft.world.level.material.Fluid fluid);

    @Override
    public void arclight$tickFluid(BlockPos pos, net.minecraft.world.level.material.Fluid fluid) {
        this.arclight$invokerTickFluid(pos, fluid);
    }

    /** 维度 worker 上压缩方块/流体计划 tick 预算：岩浆扩散 backlog 会让单 tick
     *  磨数分钟（主线程 barrier 干等）；0 = 保持 vanilla 65536。 */
    @ModifyArg(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V", ordinal = 0),
        index = 1)
    private int arclight$workerBlockTickBudget(int maxTicks) {
        if (DimensionTickManager.isDimensionTickThread() && PRTSFeaturesConfig.workerTickBudget > 0) {
            return PRTSFeaturesConfig.workerTickBudget;
        }
        return maxTicks;
    }

    @ModifyArg(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V", ordinal = 1),
        index = 1)
    private int arclight$workerFluidTickBudget(int maxTicks) {
        if (DimensionTickManager.isDimensionTickThread() && PRTSFeaturesConfig.workerTickBudget > 0) {
            return PRTSFeaturesConfig.workerTickBudget;
        }
        return maxTicks;
    }

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void arclight$regionBlockTickPhase(ServerChunkCache cache, BooleanSupplier hasTimeLeft, boolean tickChunks) {
        RegionTickManager.runBlockTickPhase((ServerLevel) (Object) this);
        cache.tick(hasTimeLeft, tickChunks);
    }

    /**
     * Route collected block ticks into the right dimension's queues: while
     * {@link LevelTicks#tick} runs (block + fluid collection), the current level is
     * published so {@link RegionTickManager#collectBlockTick} knows which dimension
     * to write into. Cleared in finally so fluid ticks and nested calls stay clean.
     */
    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private void arclight$publishCollectingLevel(LevelTicks ticks, long gameTime, int maxTicks, BiConsumer consumer) {
        RegionTickManager.setCollectingLevel((ServerLevel) (Object) this);
        try {
            ticks.tick(gameTime, maxTicks, consumer);
        } finally {
            RegionTickManager.setCollectingLevel(null);
        }
    }
}
