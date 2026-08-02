package io.izzel.arclight.common.mixin.optimization.general.servercore.features.spawn_chunks;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @Inject(
            method = "setDefaultSpawnPos",
            cancellable = true,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"
            )
    )
    private void servercore$preventAddingRegionTicket(CallbackInfo ci) {
        if (ServerCoreConfig.features().disableSpawnChunks()) {
            ci.cancel();
        }
    }
}
