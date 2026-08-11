package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.common.optimization.MemoryOptimizationCleaner;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** 内存缓存清理的驱动器（默认关闭）。 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin_MemoryCleanup {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void prts$tickMemoryCleanup(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MemoryOptimizationCleaner.tick();
    }
}
