package io.izzel.arclight.common.mixin.core.network.protocol.login;

import io.izzel.arclight.common.bridge.core.network.protocol.login.ServerboundCustomQueryPacketBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;

import java.lang.reflect.Field;

/**
 * Mixin to provide access to ServerboundCustomQueryPacket fields
 * Uses reflection to avoid field mapping issues completely
 */
@Mixin(ServerboundCustomQueryPacket.class)
public class ServerboundCustomQueryPacketMixin implements ServerboundCustomQueryPacketBridge {

    private static Field transactionIdField;
    private static Field identifierField;
    private static Field dataField;

    static {
        try {
            Class<?> packetClass = ServerboundCustomQueryPacket.class;
            Field[] fields = packetClass.getDeclaredFields();

            // Find fields by type since field names may be obfuscated
            for (Field field : fields) {
                field.setAccessible(true);
                if (field.getType() == int.class && transactionIdField == null) {
                    transactionIdField = field;
                } else if (field.getType() == ResourceLocation.class) {
                    identifierField = field;
                } else if (field.getType() == FriendlyByteBuf.class) {
                    dataField = field;
                }
            }
        } catch (Exception e) {
            // Fallback: log error but don't crash
            System.err.println("Failed to initialize ServerboundCustomQueryPacket field access: " + e.getMessage());
        }
    }

    @Override
    public int bridge$getTransactionId() {
        try {
            return transactionIdField != null ? (int) transactionIdField.get(this) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public ResourceLocation bridge$getIdentifier() {
        try {
            return identifierField != null ? (ResourceLocation) identifierField.get(this) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public FriendlyByteBuf bridge$getData() {
        try {
            return dataField != null ? (FriendlyByteBuf) dataField.get(this) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
