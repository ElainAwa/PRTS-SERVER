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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * Region-level scheduled tick collection: redirects {@code BiConsumer.accept} in
 * {@code LevelTicks.runCollectedTicks} to dispatch block ticks into the owning
 * region's queue (fluid ticks fall through). {@code LevelTicks} is not thread-safe,
 * so {@link LevelTicks#schedule} on a worker is deferred to the main thread.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin_RegionBlockTick {

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
        }
    }
}
