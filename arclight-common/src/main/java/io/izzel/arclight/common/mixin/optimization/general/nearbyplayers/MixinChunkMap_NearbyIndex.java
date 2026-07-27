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

/**
 * NearbyPlayerIndex 维护侧：TAIL 旁路同步（vanilla 记账先完成、且是唯一权威）。
 * updatePlayerStatus(player, true/false) 是玩家进出维度的完整且唯一入口；
 * move(player) 是移动入口（索引内部只在跨区块时更新，零开销早退）。
 * 1.21.1 签名核验：updatePlayerStatus(ServerPlayer,boolean)V / move(ServerPlayer)V 与 1.20.1 一致。
 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap_NearbyIndex implements NearbyPlayerIndexHolder {

    @Unique private final NearbyPlayerIndex prts$npi = new NearbyPlayerIndex();
    @Unique private static boolean prts$npiLogged = false;

    @Override
    public NearbyPlayerIndex prts$getNearbyPlayerIndex() {
        return this.prts$npi;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void prts$npiInit(CallbackInfo ci) {
        if (!prts$npiLogged) {
            prts$npiLogged = true;
            NearbyPlayerIndex.LOGGER.info("[PRTS-NPI] nearby-player-index mixin active (enabled={}, verify={})",
                    NearbyPlayerIndex.enabled(), NearbyPlayerIndex.verifyMode());
        }
    }

    @Inject(method = "updatePlayerStatus", at = @At("TAIL"))
    private void prts$npiUpdateStatus(ServerPlayer player, boolean track, CallbackInfo ci) {
        if (!NearbyPlayerIndex.enabled()) return;
        if (track) {
            this.prts$npi.addPlayer(player);
        } else {
            this.prts$npi.removePlayer(player);
        }
    }

    @Inject(method = "move", at = @At("TAIL"))
    private void prts$npiMove(ServerPlayer player, CallbackInfo ci) {
        if (!NearbyPlayerIndex.enabled()) return;
        this.prts$npi.movePlayer(player);
    }
}
