package io.izzel.arclight.common.mixin.optimization.paper;

import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@LoadIfMod(modid = {ModIds.MODERNFIX}, condition = LoadIfMod.ModCondition.ABSENT)
@Mixin(PlayerList.class)
public class PlayerListMixin_PaperWorldCreation {

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;updateEntireScoreboard(Lnet/minecraft/server/ServerScoreboard;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void luminara$paperForceCloseLoadingScreen(net.minecraft.network.Connection connection, ServerPlayer player, CallbackInfo ci) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();
        if (!config.isForceCloseLoadingScreen()) {
            return;
        }

        if (player.isDeadOrDying()) {
            ServerLevel world = player.serverLevel();
            var chunkPos = player.chunkPosition();

            try {
                var chunk = world.getChunk(chunkPos.x, chunkPos.z);
                if (chunk != null) {
                    player.connection.send(new ClientboundLevelChunkWithLightPacket(
                            chunk, world.getLightEngine(), null, null)
                    );
                }
            } catch (Exception ignored) {
            }
        }
    }
}
