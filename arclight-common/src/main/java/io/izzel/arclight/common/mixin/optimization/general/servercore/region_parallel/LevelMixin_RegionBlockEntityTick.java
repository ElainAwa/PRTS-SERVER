/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PRTS region-level block-entity tick split (P3 v04, AI-created).
 *
 * <p>Inside {@code Level.tickBlockEntities} the main loop's
 * {@code TickingBlockEntity.tick} is redirected into the owning region's queue
 * (isRemoved / shouldTickBlocksAt checks stay on the calling thread), and the
 * collected ticks run in parallel on region workers at the method's RETURN.
 * The fresh-entity lambda only calls onLoad, so it is untouched; on client
 * levels and when the feature is off, ticks run inline as before.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionBlockEntityTick {

    @Redirect(method = "tickBlockEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void arclight$regionBlockEntityTick(TickingBlockEntity ticker) {
        if (RegionTickManager.regionEnabled()) {
            RegionTickManager.collectBlockEntityTick(ticker);
        } else {
            ticker.tick();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void arclight$regionBlockEntityTickRun(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel) {
            RegionTickManager.runBlockEntityTickPhase((ServerLevel) (Object) this);
        }
    }
}
