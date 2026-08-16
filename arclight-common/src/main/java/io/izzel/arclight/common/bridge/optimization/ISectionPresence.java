/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

/**
 * Per-chunk POI-section presence tracking for {@code SectionStorage} subclasses (audit doc
 * §阶段5·5.1). Implemented by {@code SectionStorageMixin_Presence}; consumed by
 * {@code PoiManagerMixin_QueryFastPath}.
 *
 * <p>Vanilla 1.21.1 keeps every touched column in {@code storage} forever (loaded sections are
 * {@code Optional.of}, loaded-empty sections {@code Optional.empty}), so a chunk's state only
 * transitions never-read → known, never back. The mask therefore only needs monotone adds: it is
 * exact ("chunk has ≥1 present POI section"), never stale.
 */
public interface ISectionPresence {

    /** Mark the section's chunk as known and set the present bit for this section's y level. */
    void arclight$markSectionPresent(long sectionKey);

    /** Mark the section's chunk as known-empty (bit unchanged). */
    void arclight$markChunkKnownEmpty(long sectionKey);

    /** True once any section of this chunk has been read from storage (in-memory or disk). */
    boolean arclight$isChunkKnown(long chunkLong);

    /** Bitmask of present section y levels, bit (y - minSection). 0 = known but no POI. */
    long arclight$presentMask(long chunkLong);

    /** The stored section instance, or null when absent/empty. */
    Object arclight$getSectionIfPresent(long sectionKey);

    /** {@code LevelHeightAccessor.getMinSection()} of the owning storage. */
    int arclight$minSection();
}
