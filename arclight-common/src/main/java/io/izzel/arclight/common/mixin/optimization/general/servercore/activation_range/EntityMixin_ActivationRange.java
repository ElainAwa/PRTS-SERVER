package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_ActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationRangeConfig;
import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

// Ported from Wesley1808/ServerCore;原始实现 Paper/Spigot Entity-Activation-Range (Aikar, GPL-3.0)。
@Mixin(Entity.class)
public abstract class EntityMixin_ActivationRange implements EntityBridge_ActivationRange {

    // @formatter:off
    @Shadow @Final private Set<String> tags;
    @Shadow public int tickCount;
    @Shadow public abstract Level level();
    @Shadow public abstract void discard();
    @Shadow public abstract boolean isAlive();
    // @formatter:on

    public ActivationType activationRange$type;
    public boolean activationRange$excluded;
    public int activationRange$activatedTick;
    public int activationRange$activatedImmunityTick;
    public boolean activationRange$inactive;
    public int activationRange$fullTickCount;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void activationRange$init(EntityType<?> entityType, Level level, CallbackInfo ci) {
        final Entity entity = (Entity) (Object) this;
        this.activationRange$type = ActivationRange.initializeEntityActivationType(entity);
        this.activationRange$excluded = level == null
                || !ServerCoreConfig.isActivationRangeEnabled()
                || ActivationRange.isExcluded(entity);
    }

    // 被活塞推动时唤醒实体，避免卡在活塞里
    @Inject(method = "move", at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
            target = "Lnet/minecraft/world/entity/Entity;limitPistonMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private void activationRange$onPistonMove(MoverType moverType, Vec3 vec3, CallbackInfo ci) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) return;
        final MinecraftServer server = this.level().getServer();
        if (server != null) {
            final int ticks = server.getTickCount() + 20;
            this.activationRange$activatedTick = Math.max(this.activationRange$activatedTick, ticks);
            this.activationRange$activatedImmunityTick = Math.max(this.activationRange$activatedImmunityTick, ticks);
        }
    }

    // 非激活实体不接受推力，防止累积出极端速度
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void activationRange$ignorePushWhileInactive(double x, double y, double z, CallbackInfo ci) {
        if (this.activationRange$inactive && !this.level().isClientSide) {
            ci.cancel();
        }
    }

    // 记分板标签 exclude_ear 可让单个实体永久豁免激活范围
    @Inject(method = "load", at = @At("RETURN"))
    private void activationRange$onLoadNbt(CallbackInfo ci) {
        this.activationRange$excluded |= this.tags.contains(ActivationRangeConfig.EXCLUDE_TAG);
    }

    @Inject(method = "addTag", at = @At("HEAD"))
    private void activationRange$onTagAdded(String tag, CallbackInfoReturnable<Boolean> cir) {
        this.activationRange$excluded |= tag.equals(ActivationRangeConfig.EXCLUDE_TAG);
    }

    public void inactiveTick() {
    }

    @Override
    public ActivationType bridge$getActivationType() {
        return this.activationRange$type;
    }

    @Override
    public boolean bridge$isExcludedFromActivation() {
        return this.activationRange$excluded;
    }

    @Override
    public int bridge$getActivatedTick() {
        return this.activationRange$activatedTick;
    }

    @Override
    public void bridge$setActivatedTick(int activatedTick) {
        this.activationRange$activatedTick = activatedTick;
    }

    @Override
    public int bridge$getActivatedImmunityTick() {
        return this.activationRange$activatedImmunityTick;
    }

    @Override
    public void bridge$setActivatedImmunityTick(int activatedImmunityTick) {
        this.activationRange$activatedImmunityTick = activatedImmunityTick;
    }

    @Override
    public boolean bridge$isInactive() {
        return this.activationRange$inactive;
    }

    @Override
    public void bridge$setInactive(boolean inactive) {
        this.activationRange$inactive = inactive;
    }

    @Override
    public void bridge$incFullTickCount() {
        this.activationRange$fullTickCount++;
    }

    @Override
    public int bridge$getFullTickCount() {
        return this.activationRange$fullTickCount;
    }

    @Override
    public void bridge$inactiveTick() {
        this.inactiveTick();
    }
}
