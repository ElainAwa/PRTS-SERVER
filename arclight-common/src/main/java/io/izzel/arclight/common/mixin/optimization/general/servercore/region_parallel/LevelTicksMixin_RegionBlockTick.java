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
 * PRTS region-level scheduled tick collection (P3 v04, AI-created).
 *
 * <p>Redirects the single {@code BiConsumer.accept} inside
 * {@code LevelTicks.runCollectedTicks} (the one execution point where due
 * scheduled ticks are handed to the level). Block ticks are dispatched to the
 * owning region's queue instead of running inline; fluid ticks and all other
 * callers fall through to the original consumer. The {@code LevelTicks} bookkeeping
 * (bucket removal, already-run set) is untouched.</p>
 *
 * <p>{@code LevelTicks} itself is not thread-safe, so on a region worker
 * {@link LevelTicks#schedule} (the write path behind every
 * {@code level.scheduleTick} call, e.g. a repeater scheduling its next tick)
 * is deferred to the main thread, which drains the collected tasks after the
 * region session latch — same deferred-queue pattern as the tracking mixin.</p>
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
        if (RegionTickManager.inRegionTick()) {
            RegionTickManager.collectScheduleTick(tick);
            ci.cancel();
        }
    }
}
