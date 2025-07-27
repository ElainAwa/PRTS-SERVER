package io.izzel.arclight.common.mixin.optimization.paper;

import io.izzel.arclight.common.bridge.optimization.paper.WorldCreationBridge;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CraftServer.class)
public class CraftServerMixin_PaperWorldCreation {

    @Shadow @Final
    private MinecraftServer console;

    @Inject(method = "createWorld", at = @At("HEAD"))
    private void luminara$checkWorldCreationSafety(WorldCreator creator, CallbackInfoReturnable<World> cir) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();

        if (config.isFastWorldCreation() && ((WorldCreationBridge) console).luminara$isIteratingOverLevels()) {
            throw new IllegalStateException("Cannot create world while server is ticking worlds");
        }
    }

    @Inject(method = "unloadWorld(Lorg/bukkit/World;Z)Z", at = @At("HEAD"))
    private void luminara$checkWorldUnloadSafety(World world, boolean save, CallbackInfoReturnable<Boolean> cir) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();

        if (config.isFastWorldCreation() && ((WorldCreationBridge) console).luminara$isIteratingOverLevels()) {
            throw new IllegalStateException("Cannot unload world while server is ticking worlds");
        }
    }
}
