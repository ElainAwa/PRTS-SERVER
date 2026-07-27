package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.ServerPlayerEntityExtension;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [PRTS 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.mixins.playerwatching.optimize_nearby_entity_tracking_lookups.MixinServerPlayerEntity
 * 的 mojmap 移植，挂在 ServerPlayer 上，实现 ServerPlayerEntityExtension。
 * 记录玩家上一 tick 的坐标，供 NearbyEntityTracking 判断是否移动、需要刷新视野桶。
 *
 * 注意：这里【不要】用 @Shadow 读坐标。本服在子类(ServerPlayer)上 @Shadow 继承自 Entity 的
 * getX()/position() 会因 reobf refmap 无法定位运行时名而失败
 * （"@Shadow method m_20185_/m_20182_ was not located"）。
 * 改用普通虚调用 ((Entity)(Object)this).position() —— 由 reobf 重映射，运行时直接命中，
 * 与 ChunkMap_TrackedEntityExtMixin 里 this.entity.position() 的方式一致。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayer_TrackingMixin implements ServerPlayerEntityExtension {

    private double vmpTracking$prevX = Double.NaN;
    private double vmpTracking$prevY = Double.NaN;
    private double vmpTracking$prevZ = Double.NaN;

    @Override
    public boolean vmpTracking$isPositionUpdated() {
        final Vec3 pos = ((Entity) (Object) this).position();
        final double x = pos.x;
        final double y = pos.y;
        final double z = pos.z;
        return x != this.vmpTracking$prevX || y != this.vmpTracking$prevY || z != this.vmpTracking$prevZ;
    }

    @Override
    public void vmpTracking$updatePosition() {
        final Vec3 pos = ((Entity) (Object) this).position();
        this.vmpTracking$prevX = pos.x;
        this.vmpTracking$prevY = pos.y;
        this.vmpTracking$prevZ = pos.z;
    }

    // 瞬移判定阈值（单位：chunk）。玩家每 tick 正常移动/飞行远小于此值；
    // 只有 /tp、waystones、tpmaster、传送门等真实大跳变才会超过。设小一点宁可多跳过也绝不漏判真实 tp。
    private static final int luminara$teleportChunks = 2;

    @Override
    public boolean vmpTracking$isTeleport() {
        if (Double.isNaN(this.vmpTracking$prevX) || Double.isNaN(this.vmpTracking$prevZ)) {
            return false;
        }
        final Vec3 pos = ((Entity) (Object) this).position();
        final int curX = SectionPos.blockToSectionCoord((int) pos.x);
        final int curZ = SectionPos.blockToSectionCoord((int) pos.z);
        final int prevX = SectionPos.blockToSectionCoord((int) this.vmpTracking$prevX);
        final int prevZ = SectionPos.blockToSectionCoord((int) this.vmpTracking$prevZ);
        final int dx = Math.abs(curX - prevX);
        final int dz = Math.abs(curZ - prevZ);
        return Math.max(dx, dz) > luminara$teleportChunks;
    }

    /**
     * 在玩家每 tick 逻辑末尾刷新 prev 坐标快照。
     * 关键：原先这个快照在 NearbyEntityTracking.tick() 内刷新，但 routeB 接管 ChunkMap.move() 后，
     * 若 move() 在 routeB tick() 之后调用，prev 已被刷新会导致 move 误判"非瞬移"而被空掉 → 瞬移卡死复现。
     * 把刷新挪到玩家 tick 末尾，保证同 tick 内 routeB tick 与 ChunkMap.move 读到一致的 isTeleport()。
     */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void luminara$updateTrackPos(CallbackInfo ci) {
        this.vmpTracking$updatePosition();
    }
}
