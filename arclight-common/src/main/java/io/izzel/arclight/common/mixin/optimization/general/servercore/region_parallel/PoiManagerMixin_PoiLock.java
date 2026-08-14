/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Village distance tracker serialization. {@code PoiManager$DistanceTracker}
 * mutates a fastutil priority queue plus a level map that vanilla only touches
 * on the main thread; region workers call sectionsToVillage from mob AI, so the
 * four tracker entry points are serialized per manager instance. An exception
 * inside these methods crashes the server anyway (vanilla semantics), so the
 * HEAD/RETURN lock pair is safe.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin_PoiLock {

    @Unique
    private final ReentrantLock arclight$poiLock = new ReentrantLock();

    @Inject(method = "sectionsToVillage", at = @At("HEAD"))
    private void arclight$lockSections(CallbackInfoReturnable<Integer> cir) {
        this.arclight$poiLock.lock();
    }

    @Inject(method = "sectionsToVillage", at = @At("RETURN"))
    private void arclight$unlockSections(CallbackInfoReturnable<Integer> cir) {
        this.arclight$poiLock.unlock();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void arclight$lockTick(CallbackInfo ci) {
        this.arclight$poiLock.lock();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void arclight$unlockTick(CallbackInfo ci) {
        this.arclight$poiLock.unlock();
    }

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void arclight$lockSetDirty(CallbackInfo ci) {
        this.arclight$poiLock.lock();
    }

    @Inject(method = "setDirty", at = @At("RETURN"))
    private void arclight$unlockSetDirty(CallbackInfo ci) {
        this.arclight$poiLock.unlock();
    }

    @Inject(method = "onSectionLoad", at = @At("HEAD"))
    private void arclight$lockSectionLoad(CallbackInfo ci) {
        this.arclight$poiLock.lock();
    }

    @Inject(method = "onSectionLoad", at = @At("RETURN"))
    private void arclight$unlockSectionLoad(CallbackInfo ci) {
        this.arclight$poiLock.unlock();
    }
}
