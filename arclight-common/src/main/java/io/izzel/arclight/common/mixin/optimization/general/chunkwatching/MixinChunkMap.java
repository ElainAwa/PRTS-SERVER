package io.izzel.arclight.common.mixin.optimization.general.chunkwatching;

import io.izzel.arclight.common.optimization.general.chunkwatching.IChunkWatchingManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pushes the server view distance into the spatial {@link io.izzel.arclight.common.optimization.general.chunkwatching.M... */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {

    @Shadow
    private PlayerMap playerMap;

    @Inject(method = "setViewDistance", at = @At("RETURN"))
    private void onSetViewDistance(int i, CallbackInfo ci) {
        // PlayerMap does not implement IChunkWatchingManager at compile time; the mixin adds it at
        ((IChunkWatchingManager) (Object) this.playerMap).setWatchDistance(i);
    }
}
