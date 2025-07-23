package io.izzel.arclight.common.bridge.core.network.protocol.login;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Bridge interface for accessing ServerboundCustomQueryPacket fields
 */
public interface ServerboundCustomQueryPacketBridge {

    /**
     * Get the transaction ID of this packet
     */
    int bridge$getTransactionId();

    /**
     * Get the identifier (channel) of this packet
     */
    ResourceLocation bridge$getIdentifier();

    /**
     * Get the data buffer of this packet
     */
    FriendlyByteBuf bridge$getData();
}
