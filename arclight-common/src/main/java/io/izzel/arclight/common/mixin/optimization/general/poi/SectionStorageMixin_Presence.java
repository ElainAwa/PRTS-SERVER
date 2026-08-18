/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.poi;

import io.izzel.arclight.common.bridge.optimization.ISectionPresence;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chunk POI-section presence mask for {@code SectionStorage} (audit doc §阶段5·5.1).
 *
 * <p><b>Why</b>: vanilla 1.21.1 {@code PoiManager.getInChunk} walks every vertical section of
 * every chunk in the query square ({@code IntStream.range(minSection, maxSection)} →
 * {@code getOrLoad} → {@code Optional} wrapping) even though most chunks have no POI at all
 * (villages are sparse). The mask turns that into one {@code isChunkKnown} check plus a popcount
 * of present sections.
 *
 * <p><b>Correctness</b>: vanilla never removes sections from {@code storage} (unloaded columns
 * stay as {@code Optional.empty}), so a chunk's state is monotone never-read → known. The mask
 * only ever adds bits; known-empty chunks (column read, all sections empty) are skipped by the
 * query fast path, which is safe because an empty section contributes nothing to the result.
 * Never-read chunks keep the vanilla {@code getOrLoad} path (including its synchronous disk read),
 * so on-disk POI is never missed.
 *
 * <p>Semantics-neutral, config-gated, telemetry via {@code [poi-query]}.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin_Presence implements ISectionPresence {

    /** chunkLong → bitmask of present section y levels; entry present = chunk known. */
    @Unique
    private final ConcurrentHashMap<Long, Long> prts$chunkPresence = new ConcurrentHashMap<>();

    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    @Shadow
    protected abstract Optional<?> get(long sectionKey);

    @Shadow
    protected abstract boolean outsideStoredRange(long sectionKey);

    @Override
    public void arclight$markSectionPresent(long sectionKey) {
        if (!PRTSFeaturesConfig.poiQueryEnabled) {
            return;
        }
        long chunkLong = ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
        int bit = SectionPos.y(sectionKey) - this.levelHeightAccessor.getMinSection();
        if (bit >= 0 && bit < Long.SIZE) {
            this.prts$chunkPresence.merge(chunkLong, 1L << bit, (oldMask, newBit) -> oldMask | newBit);
        } else {
            // out-of-range section: mark known without a bit (still exact: no bit for out-of-range y)
            this.prts$chunkPresence.putIfAbsent(chunkLong, 0L);
        }
    }

    @Override
    public void arclight$markChunkKnownEmpty(long sectionKey) {
        if (!PRTSFeaturesConfig.poiQueryEnabled) {
            return;
        }
        long chunkLong = ChunkPos.asLong(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
        this.prts$chunkPresence.putIfAbsent(chunkLong, 0L);
    }

    @Override
    public boolean arclight$isChunkKnown(long chunkLong) {
        return this.prts$chunkPresence.containsKey(chunkLong);
    }

    @Override
    public long arclight$presentMask(long chunkLong) {
        return this.prts$chunkPresence.getOrDefault(chunkLong, 0L);
    }

    @Override
    public Object arclight$getSectionIfPresent(long sectionKey) {
        Optional<?> section = this.get(sectionKey);
        return section == null ? null : section.orElse(null);
    }

    @Override
    public int arclight$minSection() {
        return this.levelHeightAccessor.getMinSection();
    }

    // ---- maintenance hooks: every load/create path funnels through these two ----

    @Inject(method = "getOrLoad", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void prts$trackLoaded(long sectionKey, CallbackInfoReturnable<Optional<Object>> cir) {
        if (!PRTSFeaturesConfig.poiQueryEnabled) {
            return;
        }
        if (this.outsideStoredRange(sectionKey)) {
            return; // getOrLoad returned empty for range reasons; nothing to record
        }
        if (cir.getReturnValue().isPresent()) {
            this.arclight$markSectionPresent(sectionKey);
        } else {
            this.arclight$markChunkKnownEmpty(sectionKey);
        }
    }

    @Inject(method = "getOrCreate", at = @At("RETURN"))
    private void prts$trackCreated(long sectionKey, CallbackInfoReturnable<Object> cir) {
        if (!PRTSFeaturesConfig.poiQueryEnabled) {
            return;
        }
        if (cir.getReturnValue() != null) {
            this.arclight$markSectionPresent(sectionKey);
        }
    }
}
