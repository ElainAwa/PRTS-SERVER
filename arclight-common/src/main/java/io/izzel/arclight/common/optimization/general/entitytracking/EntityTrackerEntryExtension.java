package io.izzel.arclight.common.optimization.general.entitytracking;

/**
 * [Luminara 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.common.playerwatching.EntityTrackerEntryExtension 的 mojmap 版。
 * 由 ServerEntity_TrackingMixin 实现，挂在 ServerEntity 上。
 */
public interface EntityTrackerEntryExtension {

    void vmp$tickAlways();

    void vmp$syncEntityData();

    void vmp$updatePassengers();

}
