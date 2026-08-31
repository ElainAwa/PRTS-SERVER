/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Serializes all PoiManager section-storage access per manager instance.
 * Vanilla assumes main-thread-only storage; workers querying POIs or reading
 * chunks on IO threads can race the shared map and corrupt it.
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

    @WrapMethod(method = "getInChunk")
    private Stream<PoiRecord> arclight$getInChunk(Predicate<Holder<PoiType>> typePredicate, ChunkPos pos,
                                                  PoiManager.Occupancy status, Operation<Stream<PoiRecord>> original) {
        this.arclight$poiLock.lock();
        try {
            // Materialize the lazy stream while the lock is held so storage
            // reads and record iteration never escape the critical section.
            return original.call(typePredicate, pos, status).toList().stream();
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "add")
    private void arclight$add(BlockPos pos, Holder<PoiType> type, Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(pos, type);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "remove")
    private void arclight$remove(BlockPos pos, Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(pos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "release")
    private boolean arclight$release(BlockPos pos, Operation<Boolean> original) {
        this.arclight$poiLock.lock();
        try {
            return original.call(pos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "exists")
    private boolean arclight$exists(BlockPos pos, Predicate<Holder<PoiType>> typePredicate, Operation<Boolean> original) {
        this.arclight$poiLock.lock();
        try {
            return original.call(pos, typePredicate);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "getType")
    private Optional<Holder<PoiType>> arclight$getType(BlockPos pos, Operation<Optional<Holder<PoiType>>> original) {
        this.arclight$poiLock.lock();
        try {
            return original.call(pos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "getFreeTickets")
    private int arclight$getFreeTickets(BlockPos pos, Operation<Integer> original) {
        this.arclight$poiLock.lock();
        try {
            return original.call(pos);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }

    @WrapMethod(method = "ensureLoadedAndValid")
    private void arclight$ensureLoadedAndValid(LevelReader levelReader, BlockPos pos, int coordinateOffset,
                                               Operation<Void> original) {
        this.arclight$poiLock.lock();
        try {
            original.call(levelReader, pos, coordinateOffset);
        } finally {
            this.arclight$poiLock.unlock();
        }
    }
}
