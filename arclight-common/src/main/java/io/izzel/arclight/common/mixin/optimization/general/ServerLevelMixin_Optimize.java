package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class ServerLevelMixin_Optimize {

    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true)
    private int luminara$modifyChunkLoadRate(int chunks) {
        var config = ArclightConfig.spec().getOptimization().getChunkOptimization();
        if (config.isOptimizeChunkLoading()) {
            return Math.min(chunks, config.getChunkLoadRateLimit());
        }
        return chunks;
    }

    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void luminara$markChunkAccessed(net.minecraft.world.level.chunk.LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ChunkOptimizer.markChunkAccessed(chunk.getPos());
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void luminara$optimizeEntityTick(Entity entity, CallbackInfo ci) {
        // Additional entity tick optimization at the level processing stage
        if (EntityOptimizer.shouldOptimizeEntityTick(entity)) {
            // Skip processing for very distant entities
            if (entity.tickCount % 4 != 0) {
                ci.cancel();
            }
        }
    }
}
