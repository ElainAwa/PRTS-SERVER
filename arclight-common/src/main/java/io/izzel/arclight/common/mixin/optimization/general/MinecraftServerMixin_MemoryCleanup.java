package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.common.optimization.MemoryOptimizationCleaner;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 实验性内存缓存清理的驱动器（默认关闭）。
 * 每个 tick 调用清理器，由清理器内部按 cache-cleanup-interval 节流。
 * 在主线程执行，避免并发修改静态缓存。
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin_MemoryCleanup {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void luminara$tickMemoryCleanup(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        MemoryOptimizationCleaner.tick();
    }
}
