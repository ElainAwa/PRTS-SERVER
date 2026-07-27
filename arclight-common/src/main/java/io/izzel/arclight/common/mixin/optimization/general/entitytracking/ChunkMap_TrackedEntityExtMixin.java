package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.EntityTrackerEntryExtension;
import io.izzel.arclight.common.optimization.general.entitytracking.EntityTrackerExtension;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * [PRTS 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.mixins.playerwatching.optimize_nearby_entity_tracking_lookups.MixinThreadedAnvilChunkStorageEntityTracker
 * 的 mojmap 移植，挂在 ChunkMap.TrackedEntity 上，实现 EntityTrackerExtension。
 *
 * 关键 mojmap 映射（相对 VMP Yarn）：
 *   listeners(Set<EntityTrackingListener>) → seenBy(Set<ServerPlayerConnection>)
 *   entry(EntityTrackerEntry)               → serverEntity(ServerEntity)
 *   updateTrackedStatus(ServerPlayer)     → updatePlayer(ServerPlayer)  [beforeStartTracking 注入点]
 *   updateTrackedStatus(List)             → updatePlayers(List)        [在 ChunkMap_TrackingMixin 里 redirect]
 *   stopTracking()                        → broadcastRemoved()         [在 ChunkMap_TrackingMixin 里 redirect]
 *   entity.getPos()                       → entity.position()
 *   entity.getPassengerList()             → entity.getPassengers()
 *   entity.velocityModified               → entity.velocityChanged
 *   ChunkPos.toLong / ChunkSectionPos.getSectionCoord → ChunkPos.asLong / SectionPos.blockToSectionCoord
 *
 * VMP 用 IEntityTrackerEntry accessor 取 lastPassengers，本份改为在 ServerEntity_TrackingMixin 里
 * 通过 EntityTrackerEntryExtension.vmp$updatePassengers() 直接作业，省掉一个 accessor。
 */
@Mixin(ChunkMap.TrackedEntity.class)
public abstract class ChunkMap_TrackedEntityExtMixin implements EntityTrackerExtension {

    // @formatter:off
    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity serverEntity;
    @Shadow public Set<ServerPlayerConnection> seenBy;
    @Shadow public abstract void updatePlayer(ServerPlayer player);
    @Shadow protected abstract int getEffectiveRange();
    // @formatter:on

    @Unique
    private double prevX = Double.NaN;
    @Unique
    private double prevY = Double.NaN;
    @Unique
    private double prevZ = Double.NaN;

    @Override
    public boolean isPositionUpdated() {
        final Vec3 pos = this.entity.position();
        return pos.x != this.prevX || pos.y != this.prevY || pos.z != this.prevZ;
    }

    @Override
    public void updatePosition() {
        final Vec3 pos = this.entity.position();
        this.prevX = pos.x;
        this.prevY = pos.y;
        this.prevZ = pos.z;
    }

    @Override
    public Vec3 getPreviousLocation() {
        return new Vec3(this.prevX, this.prevY, this.prevZ);
    }

    @Override
    public long getPreviousChunkPos() {
        // 修正 VMP 原版用 prevX 取代 prevZ 的笔误
        return ChunkPos.asLong(SectionPos.blockToSectionCoord((int) this.prevX),
                SectionPos.blockToSectionCoord((int) this.prevZ));
    }

    @Override
    public void updateListeners(Set<ServerPlayer> triedPlayers) {
        for (ServerPlayerConnection conn : this.seenBy) {
            final ServerPlayer player = conn.getPlayer();
            if (triedPlayers != null) {
                triedPlayers.add(player);
            }
            if (player != null) {
                this.updatePlayer(player);
            }
        }
    }

    @Override
    public void tryTick() {
        if (!this.seenBy.isEmpty()) {
            // 有玩家在追踪：直接走原版 sendChanges（mojmap ServerEntity.sendChanges）
            this.serverEntity.sendChanges();
        } else {
            // 暂无玩家追踪：同步一次乘客列表 + （若是玩家自身）补发实体数据
            // 注：原 VMP 在玩家自身分支额外判断 entity.velocityChanged 后给它自己发速度包；
            // mojmap 下该字段名不确定且此分支极罕见（玩家不在任何人视野时），
            // 玩家自身速度本就由客户端权威运算，故省略自同步速度包，仅保留乘客/数据同步。
            ((EntityTrackerEntryExtension) this.serverEntity).vmp$updatePassengers();
            if (this.entity instanceof ServerPlayer) {
                ((EntityTrackerEntryExtension) this.serverEntity).vmp$syncEntityData();
            }
        }
    }

    @Override
    public void vmp$updatePassengers() {
        ((EntityTrackerEntryExtension) this.serverEntity).vmp$updatePassengers();
    }

    @Override
    public int vmp$getEffectiveRange() {
        // mojmap TrackedEntity.getEffectiveRange()（Yarn getMaxTrackDistance）
        return this.getEffectiveRange();
    }

    /**
     * 当 seenBy 即将从空变为非空（玩家开始追踪该实体）时，强制一次完整 tick，
     * 让刚进入视野的玩家立刻拿到实体初始状态（速度/乘客/数据）。
     */
    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z", shift = At.Shift.BEFORE))
    private void beforeStartTracking(ServerPlayer player, CallbackInfo ci) {
        if (this.seenBy.isEmpty()) {
            ((EntityTrackerEntryExtension) this.serverEntity).vmp$tickAlways();
        }
    }
}
