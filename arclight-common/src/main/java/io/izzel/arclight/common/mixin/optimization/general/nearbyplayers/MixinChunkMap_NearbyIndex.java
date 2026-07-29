package io.izzel.arclight.common.mixin.optimization.general.nearbyplayers;

import io.izzel.arclight.common.optimization.general.nearbyplayers.NearbyPlayerIndex;
import io.izzel.arclight.common.optimization.general.nearbyplayers.NearbyPlayerIndexHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NearbyPlayerIndex 维护侧：TAIL 旁路同步（vanilla 记账先完成、且是唯一权威）。 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap_NearbyIndex implements NearbyPlayerIndexHolder {

    @Unique private final NearbyPlayerIndex luminara$npi = new NearbyPlayerIndex();
    @Unique private static boolean luminara$npiLogged = false;

    @Override
    public NearbyPlayerIndex luminara$getNearbyPlayerIndex() {
        return this.luminara$npi;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void luminara$npiInit(CallbackInfo ci) {
        if (!luminara$npiLogged) {
            luminara$npiLogged = true;
            NearbyPlayerIndex.LOGGER.info("[PRTS-NPI] nearby-player-index mixin active (enabled={}, verify={})",
                    NearbyPlayerIndex.enabled(), NearbyPlayerIndex.verifyMode());
        }
    }

    @Inject(method = "updatePlayerStatus", at = @At("TAIL"))
    private void luminara$npiUpdateStatus(ServerPlayer player, boolean track, CallbackInfo ci) {
        if (!NearbyPlayerIndex.enabled()) return;
        if (track) {
            this.luminara$npi.addPlayer(player);
        } else {
            this.luminara$npi.removePlayer(player);
        }
    }

    @Inject(method = "move", at = @At("TAIL"))
    private void luminara$npiMove(ServerPlayer player, CallbackInfo ci) {
        if (!NearbyPlayerIndex.enabled()) return;
        this.luminara$npi.movePlayer(player);
    }
}
