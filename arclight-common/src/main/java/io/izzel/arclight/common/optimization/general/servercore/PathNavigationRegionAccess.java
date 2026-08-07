package io.izzel.arclight.common.optimization.general.servercore;

/**
 * Bridge interface implemented by PathNavigationRegionMixin.
 * Captures an immutable block-state snapshot of the region on the main thread
 * before handing the task to a worker thread.
 */
public interface PathNavigationRegionAccess {

    /**
     * Captures a read-only BlockState snapshot of this region, clipped to
     * [centerY - yRadius, centerY + yRadius] in the vertical axis.
     * MUST be called on the main thread.
     */
    ImmutablePathNavigationRegion arclight$snapshot(int centerY, int yRadius);
}
