/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerLevelRegionBlockTickAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void arclight$regionBlockTickPhase(ServerChunkCache cache, BooleanSupplier hasTimeLeft, boolean tickChunks) {
        RegionTickManager.runBlockTickPhase((ServerLevel) (Object) this);
        cache.tick(hasTimeLeft, tickChunks);
    }
}
