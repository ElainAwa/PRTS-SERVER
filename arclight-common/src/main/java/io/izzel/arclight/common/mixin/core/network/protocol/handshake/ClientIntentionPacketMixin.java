package io.izzel.arclight.common.mixin.core.network.protocol.handshake;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientIntentionPacket.class)
public class ClientIntentionPacketMixin {

    /** 代理转发模式下 hostname 会携带附加数据（如 BungeeCord IP 转发），
     *  需要大于原版 255 上限；但不再放大到 Short.MAX_VALUE（认证前输入处理成本）。
     *  4096 对代理数据充足，同时约束单包解析成本。 */
    private static final int MAX_HOSTNAME_LENGTH = 4096;

    @Redirect(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readUtf(I)Ljava/lang/String;"))
    private static String arclight$bungeeHostname(FriendlyByteBuf packetBuffer, int maxLength) {
        return packetBuffer.readUtf(Math.max(maxLength, MAX_HOSTNAME_LENGTH));
    }
}
