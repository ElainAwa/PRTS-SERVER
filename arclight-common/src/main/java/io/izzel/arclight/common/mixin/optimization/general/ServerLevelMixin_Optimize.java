package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerLevel.class)
public class ServerLevelMixin_Optimize {
    private static final org.apache.logging.log4j.Logger LOGGER = ArclightI18nLogger.getLogger("ServerLevel-Optimize");

    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true)
    private int luminara$modifyChunkLoadRate(int chunks) {
        var config = ArclightConfig.spec().getOptimization().getChunkOptimization();
        if (config.isOptimizeChunkLoading()) {
            int limited = Math.min(chunks, config.getChunkLoadRateLimit());
            if (limited < chunks) {
                LOGGER.debug("optimization.chunk.rate-limit", chunks, limited);
            }
            return limited;
        }
        return chunks;
    }

}
