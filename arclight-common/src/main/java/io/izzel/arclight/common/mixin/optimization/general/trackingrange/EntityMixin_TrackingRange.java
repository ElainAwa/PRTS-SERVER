package io.izzel.arclight.common.mixin.optimization.general.trackingrange;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spigotmc.ActivationRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// activation range 已换用 ServerCore 实现，此处仅补回 org.spigotmc.TrackingRange/ActivationRange
// 直接访问的三个 Entity 字段，防止 NoSuchFieldError；不参与任何 tick 门控。
@Mixin(Entity.class)
public abstract class EntityMixin_TrackingRange {

    public ActivationRange.ActivationType activationType;
    public boolean defaultActivationState;
    public long activatedTick = Integer.MIN_VALUE;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void trackingRange$initActivationType(EntityType<?> entityType, Level level, CallbackInfo ci) {
        this.activationType = ActivationRange.initializeEntityActivationType((Entity) (Object) this);
    }
}
