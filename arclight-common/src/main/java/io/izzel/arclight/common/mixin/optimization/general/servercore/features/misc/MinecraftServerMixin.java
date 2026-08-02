package io.izzel.arclight.common.mixin.optimization.general.servercore.features.misc;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = MinecraftServer.class, priority = 900)
public class MinecraftServerMixin {

    @ModifyConstant(method = "tickServer", constant = @Constant(intValue = 6000), require = 0)
    public int servercore$modifyAutoSaveInterval(int constant) {
        return ServerCoreConfig.features().autosaveInterval();
    }
}
