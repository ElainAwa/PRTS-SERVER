package io.izzel.arclight.common.mixin.optimization.general.chunkwatching;

import io.izzel.arclight.common.optimization.general.chunkwatching.IChunkWatchingManager;
import net.minecraft.server.level.PlayerMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** chunkwatching (子系统 B) — 已回退到 vanilla 语义（2026-07-23 实锤 bug 后）。 */
@Mixin(PlayerMap.class)
public abstract class MixinPlayerMap implements IChunkWatchingManager {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-CW");
    private static boolean logged = false;
    private int watchDistance = 5;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(CallbackInfo ci) {
        if (!logged) {
            logged = true;
            LOGGER.info("[PRTS-CW] chunkwatching mixin active (spatial index disabled — using vanilla getPlayers)");
        }
    }

    @Override
    public void setWatchDistance(int watchDistance) {
        this.watchDistance = Math.max(3, watchDistance);
    }

    @Override
    public int getWatchDistance() {
        return this.watchDistance;
    }
}
