package io.izzel.arclight.common.optimization.general.entitytracking;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * [Luminara 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.common.playerwatching.EntityTrackerExtension 的 mojmap 版。
 * 由 ChunkMap_TrackedEntityExtMixin 实现，挂在 ChunkMap.TrackedEntity 上。
 * vmp$getEffectiveRange() 替代 VMP 的 invokeGetMaxTrackDistance()（原版 TrackedEntity.getMaxTrackDistance → mojmap getEffectiveRange）。
 */
public interface EntityTrackerExtension {

    boolean isPositionUpdated();

    void updatePosition();

    Vec3 getPreviousLocation();

    long getPreviousChunkPos();

    void updateListeners(Set<ServerPlayer> triedPlayers);

    void tryTick();

    // 让 ServerEntity 在 seenBy 为空时同步一次乘客列表（mojmap lastPassengers）
    void vmp$updatePassengers();

    int vmp$getEffectiveRange();

}
