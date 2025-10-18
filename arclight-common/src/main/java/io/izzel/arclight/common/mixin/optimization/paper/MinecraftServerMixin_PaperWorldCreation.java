package io.izzel.arclight.common.mixin.optimization.paper;

import io.izzel.arclight.common.bridge.optimization.paper.WorldCreationBridge;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@LoadIfMod(modid = {ModIds.MODERNFIX}, condition = LoadIfMod.ModCondition.ABSENT)
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin_PaperWorldCreation implements WorldCreationBridge {

    private boolean luminara$isIteratingOverLevels = false;

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void luminara$setIteratingFlag(CallbackInfo ci) {
        this.luminara$isIteratingOverLevels = true;
    }

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void luminara$unsetIteratingFlag(CallbackInfo ci) {
        this.luminara$isIteratingOverLevels = false;
    }

    @Override
    public boolean luminara$isIteratingOverLevels() {
        return this.luminara$isIteratingOverLevels;
    }
}
