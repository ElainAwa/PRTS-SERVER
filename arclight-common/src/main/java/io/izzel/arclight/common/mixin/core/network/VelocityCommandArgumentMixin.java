package io.izzel.arclight.common.mixin.core.network;

import io.izzel.arclight.common.mod.velocity.VelocityManager;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin to handle command argument types for Velocity compatibility
 * Based on Proxy Compatible Forge implementation
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundCommandsPacket$ArgumentNodeStub")
public class VelocityCommandArgumentMixin {

    private static final int MOD_ARGUMENT_INDICATOR = -256;

    @Shadow
    @Final
    private ArgumentTypeInfo.Template<?> argumentType;

    @Shadow
    @Final
    private String id;

    @Shadow
    @Final
    private ResourceLocation suggestionId;

    /**
     * @author Luminara Team
     * @reason Handle command argument types for Velocity compatibility
     */
    @Overwrite
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(this.id);

        var typeInfo = argumentType.type();
        var identifier = ForgeRegistries.COMMAND_ARGUMENT_TYPES.getKey(typeInfo);
        var id = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getId(typeInfo);

        VelocityManager velocityManager = VelocityManager.getInstance();

        if (identifier != null && velocityManager.getIntegratedArgumentTypes().contains(identifier.toString())) {
            buffer.writeVarInt(id);
            ((ArgumentTypeInfo) typeInfo).serializeToNetwork(argumentType, buffer);
        } else {
            buffer.writeVarInt(MOD_ARGUMENT_INDICATOR);
            buffer.writeVarInt(id);

            FriendlyByteBuf extraData = new FriendlyByteBuf(Unpooled.buffer());
            ((ArgumentTypeInfo) typeInfo).serializeToNetwork(argumentType, extraData);

            buffer.writeVarInt(extraData.readableBytes());
            buffer.writeBytes(extraData);

            extraData.release();
        }

        if (suggestionId != null) {
            buffer.writeResourceLocation(suggestionId);
        }
    }
}
