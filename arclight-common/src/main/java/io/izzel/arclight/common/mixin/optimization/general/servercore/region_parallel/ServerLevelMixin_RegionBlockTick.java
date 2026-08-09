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
 * PRTS region-level scheduled block tick split (P3 v04, AI-created).
 *
 * <p>Two redirects inside {@code ServerLevel.tick}: (1) the private
 * {@code tickBlock} is exposed through {@link ServerLevelRegionBlockTickAccess}
 * so region workers can run scheduled ticks; (2) the {@code ServerChunkCache.tick}
 * call becomes the phase boundary where the collected scheduled block ticks are
 * executed in parallel on region workers (latch) before the random-tick phase
 * proceeds on the dimension worker. The collection itself happens in
 * {@code LevelTicksMixin_RegionBlockTick}; order of phases is preserved so a
 * region ticks blocks before entities (v03 review plan A).</p>
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
