package io.izzel.arclight.common.mixin.optimization.general.move_zero_velocity;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ported from HariPlayer (VMP fork) move_zero_velocity. */
@Mixin(Entity.class)
public class MixinEntity {

    @Shadow
    private AABB bb;

    @Unique
    private boolean luminara$boundingBoxChanged = false;

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void luminara$onMove(MoverType movementType, Vec3 movement, CallbackInfo ci) {
        if (!io.izzel.arclight.i18n.ArclightConfig.spec().getOptimization().isMoveZeroVelocityEnabled()) {
            return;
        }
        if (!luminara$boundingBoxChanged && movement.equals(Vec3.ZERO)) {
            ci.cancel();
        }
        // 无论是否取消，本次 move 后重置标记：使包围盒变化后的“下一帧 move”只处理一次即恢复跳过
        luminara$boundingBoxChanged = false;
    }

    @Inject(method = "setBoundingBox", at = @At("HEAD"))
    private void luminara$onBoundingBoxChanged(AABB boundingBox, CallbackInfo ci) {
        if (!this.bb.equals(boundingBox)) {
            luminara$boundingBoxChanged = true;
        }
    }
}
