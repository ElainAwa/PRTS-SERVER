package io.izzel.arclight.common.mixin.optimization.general.network;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin_Optimize {

    @Redirect(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;move(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void arclight$markTrackerDirty(ServerChunkCache instance, ServerPlayer player, ServerboundMovePlayerPacket packet) {
        if (!packet.hasPosition()) {
            // 纯旋转/视角包：玩家位置未变，所观看区块集合不变，无需重新评估 chunk watching。
            // 跳过 move() 可大幅削减主线程 ChunkMap.updatePosition 调用（旋转包是最高频的玩家包），
            // 降低高负载下 updatePosition 阻塞引发的看门狗 60s 卡 tick 风险（崩溃 B 缓解）。
            // 位置包（Pos / PosRot）仍正常 move()，区块观看与 tick 行为 100% 不变。
            return;
        }
        instance.move(player);
    }
}
