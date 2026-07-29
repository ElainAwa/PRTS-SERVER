package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.ServerPlayerEntityExtension;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** VMP mojmap 移植：记录玩家上一 tick 坐标供 NearbyEntityTracking 判移动/瞬移。禁 @Shadow（reobf refmap 失败），改用虚调用。 */

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

    // 瞬移判定阈值（chunk）：正常移动远小于此值，仅 /tp/传送门等大跳变超过。
    private static final int prts$teleportChunks = 2;

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
        return Math.max(dx, dz) > prts$teleportChunks;
    }
}
