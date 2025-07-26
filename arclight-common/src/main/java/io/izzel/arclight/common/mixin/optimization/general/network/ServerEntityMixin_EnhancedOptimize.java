package io.izzel.arclight.common.mixin.optimization.general.network;

import io.izzel.arclight.common.mixin.optimization.general.EntitySyncOptimizer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public class ServerEntityMixin_EnhancedOptimize {

    @Shadow
    @Final
    private Entity entity;
    @Shadow
    private int tickCount;

    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void luminara$optimizeEntitySync(CallbackInfo ci) {
        // Use optimized update interval for distant entities
        int updateInterval = EntitySyncOptimizer.getOptimizedUpdateInterval(this.entity);

        if (updateInterval > 1 && this.tickCount % updateInterval != 0) {
            // Skip this update cycle for optimization
            ci.cancel();
        }
    }
}
