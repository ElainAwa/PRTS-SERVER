package io.izzel.arclight.common.mixin.optimization.general.move_zero_velocity;

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

/**
 * Ported from HariPlayer (VMP fork) move_zero_velocity.
 * Skips Entity.move() when the entity is fully stationary (zero movement and
 * unchanged bounding box), avoiding redundant collision/position work.
 * Pure zero-perception optimization, no gameplay behaviour change.
 *
 * Note: 1.20.1 renamed the bounding-box field from `boundingBox` to `bb`, and
 * `MovementType` -> `MoverType`, `Vec3d` -> `Vec3`, `Box` -> `AABB`.
 *
 * The upstream flag-reset only fired inside the cancel branch, which permanently
 * disabled the optimization after the first bounding-box change. We reset the flag
 * unconditionally at the end of onMove so exactly one move is processed after each
 * box change, then the skip re-engages.
 *
 * Gated behind the "move-zero-velocity" sub-switch. The 1.20.1 ArclightConfig
 * optimization sub-tree does not exist in this NeoForge 1.21.1 build, so we use a
 * system property: `-Dluminara.movezerovelocity.disabled=true` disables it
 * (default = enabled). @Mixin(Entity.class) cannot be package-prefix gated, hence
 * the runtime check inside the inject.
 */
@Mixin(Entity.class)
public class MixinEntity {

    @Shadow
    private AABB bb;

    @Unique
    private boolean luminara$boundingBoxChanged = false;

    @Unique
    private static boolean luminara$isEnabled() {
        return !Boolean.getBoolean("luminara.movezerovelocity.disabled");
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void luminara$onMove(MoverType movementType, Vec3 movement, CallbackInfo ci) {
        if (!luminara$isEnabled()) {
            return;
        }
        if (!luminara$boundingBoxChanged && movement.equals(Vec3.ZERO)) {
            ci.cancel();
        }
        // 无论是否取消，本次 move 后重置标记：使包围盒变化后的"下一帧 move"只处理一次即恢复跳过
        luminara$boundingBoxChanged = false;
    }

    @Inject(method = "setBoundingBox", at = @At("HEAD"))
    private void luminara$onBoundingBoxChanged(AABB boundingBox, CallbackInfo ci) {
        if (!this.bb.equals(boundingBox)) {
            luminara$boundingBoxChanged = true;
        }
    }
}
