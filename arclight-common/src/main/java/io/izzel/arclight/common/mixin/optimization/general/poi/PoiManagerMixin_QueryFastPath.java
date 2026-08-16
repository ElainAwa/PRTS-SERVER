/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.poi;

import io.izzel.arclight.common.bridge.optimization.ISectionPresence;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.poi.PoiQueryStats;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * POI query fast path (audit doc §阶段5·5.1): {@code PoiManager.getInChunk} skips chunks known
 * to have no POI and iterates only present vertical sections for chunks that do.
 *
 * <p>Vanilla 1.21.1 {@code PoiSection} already buckets records by {@code PoiType}, so the
 * remaining waste is the per-chunk vertical scan ({@code IntStream.range(minSection, maxSection)}
 * → {@code getOrLoad} → {@code Optional}) executed for <em>every</em> chunk of every range
 * query — most chunks in a village-free area are empty. The presence mask maintained by
 * {@code SectionStorageMixin_Presence} makes that a constant-time skip.
 *
 * <p>Semantics: exact. Known-empty chunks contribute no records either way; present chunks are
 * iterated y-ascending through the same {@code PoiSection.getRecords} as vanilla (same order);
 * never-read chunks keep the vanilla {@code getOrLoad} path, preserving the synchronous disk
 * load of on-disk POI data. Config-gated, telemetry via {@code [poi-query]}.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin_QueryFastPath {

    @Inject(method = "getInChunk(Ljava/util/function/Predicate;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;",
            at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void prts$fastInChunk(Predicate<Holder<PoiType>> predicate, ChunkPos chunkPos,
                                  PoiManager.Occupancy occupancy,
                                  CallbackInfoReturnable<Stream<PoiRecord>> cir) {
        if (!PRTSFeaturesConfig.poiQueryEnabled) {
            return;
        }
        ISectionPresence presence = (ISectionPresence) this;
        long chunkLong = chunkPos.toLong();
        if (!presence.arclight$isChunkKnown(chunkLong)) {
            // never read from storage: vanilla path (may load the column from disk)
            PoiQueryStats.increment("vanillaChunks");
            return;
        }
        long mask = presence.arclight$presentMask(chunkLong);
        if (mask == 0) {
            // known chunk with no POI sections at all -> empty result, skip the vertical scan
            PoiQueryStats.increment("skippedEmptyChunks");
            cir.setReturnValue(Stream.empty());
            return;
        }
        Stream.Builder<PoiRecord> builder = Stream.builder();
        int y = presence.arclight$minSection();
        while (mask != 0) {
            if ((mask & 1L) != 0) {
                Object section = presence.arclight$getSectionIfPresent(
                        SectionPos.asLong(chunkPos.x, y, chunkPos.z));
                if (section instanceof PoiSection poiSection) {
                    poiSection.getRecords(predicate, occupancy).forEach(builder);
                }
            }
            mask >>>= 1;
            y++;
        }
        PoiQueryStats.increment("indexedChunks");
        cir.setReturnValue(builder.build());
    }
}
