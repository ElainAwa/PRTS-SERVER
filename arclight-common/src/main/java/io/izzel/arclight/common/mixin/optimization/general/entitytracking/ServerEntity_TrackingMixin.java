package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.EntityTrackerEntryExtension;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * [Luminara 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.mixins.playerwatching.optimize_nearby_entity_tracking_lookups.MixinEntityTrackerEntry
 * 的 mojmap 移植，挂在 ServerEntity 上，实现 EntityTrackerEntryExtension。
 *
 * 关键 mojmap 映射（相对 VMP Yarn）：
 *   trackingTick   → tickCount
 *   tickInterval   → updateInterval
 *   tick()         → sendChanges()          [原版每 tick 把实体变更广播给追踪玩家]
 *   syncEntityData → sendDirtyEntityData()
 *   Entity.velocityDirty  → entity.hurtMarked   [vmp$tickAlways 里强制速度同步]
 *   Entity.velocityModified → entity.velocityChanged [tryTick 里玩家自身速度同步]
 *   getPassengerList() → getPassengers()
 *   MathHelper.roundUpToMultiple → 手写取上整到 updateInterval 的倍数（避免依赖 Mth）
 *
 * 另提供 vmp$updatePassengers()：在 seenBy 为空时同步一次乘客列表（lastPassengers），
 * 供 ChunkMap_TrackedEntityExtMixin.tryTick 的空分支调用，省掉 VMP 的 IEntityTrackerEntry accessor。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntity_TrackingMixin implements EntityTrackerEntryExtension {

    // @formatter:off
    @Shadow private int tickCount;
    @Shadow @Final private int updateInterval;
    @Shadow @Final private Entity entity;
    @Shadow private List<Entity> lastPassengers;
    @Shadow protected abstract void sendDirtyEntityData();
    @Shadow public abstract void sendChanges();
    // @formatter:on

    @Override
    public void vmp$tickAlways() {
        // 等价于 MathHelper.roundUpToMultiple(tickCount, updateInterval)：把计数对齐到下一个 updateInterval 倍数，
        // 使下一次 sendChanges 立即触发一次完整广播。
        int rem = this.tickCount % this.updateInterval;
        if (rem != 0) {
            this.tickCount += this.updateInterval - rem;
        }
        this.entity.hurtMarked = true;
        this.sendChanges();
    }

    @Override
    public void vmp$syncEntityData() {
        this.tickCount++;
        if (this.tickCount % this.updateInterval == 0) {
            this.sendDirtyEntityData();
        }
    }

    @Override
    public void vmp$updatePassengers() {
        List<Entity> current = this.entity.getPassengers();
        if (!this.lastPassengers.equals(current)) {
            this.lastPassengers = current;
        }
    }
}
