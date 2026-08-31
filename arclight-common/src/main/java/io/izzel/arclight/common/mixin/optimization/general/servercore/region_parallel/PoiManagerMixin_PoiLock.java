/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Village distance tracker serialization. {@code PoiManager$DistanceTracker}
 * mutates a fastutil priority queue plus a level map that vanilla only touches
 * on the main thread; region workers call sectionsToVillage from mob AI, so the
 * tracker entry points are serialized per manager instance.
 *
 * <p>All five methods are wrapped with try/finally: a plain HEAD/RETURN
 * {@code @Inject} pair would skip unlock on the exception path, and once a
 * worker thread dies mid-method the lock is leaked forever - the main thread
 * then blocks in PoiManager.tick waiting on the POI lock (observed deadlock
 * with 2000 citizens). try/finally guarantees unlock on every path.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin_PoiLock {

    @Unique
    private final ReentrantLock arclight$poiLock = new ReentrantLock();

    @WrapMethod(method = "sectionsToVillage")
    private int arclight$sectionsToVillage(SectionPos pos, Operation<Integer> original) {
        this.arclight$poiLock.lock();
        try {
            return original.call(pos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "tick")
    private void arclight$tick(BooleanSupplier hasTimeLeft, Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(hasTimeLeft);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "setDirty")
    private void arclight$setDirty(long packedPos, Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(packedPos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "onSectionLoad")
    private void arclight$onSectionLoad(long packedPos, Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(packedPos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "checkConsistencyWithBlocks")
    private void arclight$checkConsistencyWithBlocks(SectionPos pos, LevelChunkSection section, Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(pos, section);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }
}
