package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.common.optimization.mpem.EntityOptimizer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin_EnhancedOptimize {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void luminara$optimizeEntityTick(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;

        if (EntityOptimizer.shouldOptimizeEntityTick(entity)) {
            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.tickCount++;

                if (!livingEntity.isAlive()) {
                    return;
                }

                ci.cancel();
            }
        }
    }

    @Inject(method = "baseTick", at = @At("HEAD"), cancellable = true)
    private void luminara$optimizeBaseTick(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;

        if (entity instanceof Player) return;

        if (EntityOptimizer.shouldReduceEntityUpdates(entity)) {
            if (entity.tickCount % 4 != 0) {
                ci.cancel();
            }
        }
    }
}
