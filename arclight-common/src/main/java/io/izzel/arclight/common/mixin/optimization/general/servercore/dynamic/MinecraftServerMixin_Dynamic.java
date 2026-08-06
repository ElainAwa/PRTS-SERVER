/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dynamic;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicManager;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.IMinecraftServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 持有动态管理器并驱动每 tick 性能检查与关闭重置（移植自 ServerCore MinecraftServerMixin + Events 接线）。
 * 用 mixin 直挂 MinecraftServer 生命周期，替代上游 NeoForge 事件总线。
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin_Dynamic implements IMinecraftServer {

    @Unique
    private DynamicManager servercore$dynamicManager;

    @Override
    public void servercore$setDynamicManager(DynamicManager manager) {
        this.servercore$dynamicManager = manager;
    }

    @Override
    public DynamicManager servercore$getDynamicManager() {
        return this.servercore$dynamicManager;
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void prts$dynamicTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!ServerCoreConfig.dynamicActive()) {
            return;
        }
        MinecraftServer server = (MinecraftServer) (Object) this;
        // reload() 须先于管理器构造：先算出各设置上限，再由 initDefaultValues 应用
        if (this.servercore$dynamicManager == null) {
            DynamicManager.reload();
            this.servercore$dynamicManager = new DynamicManager(server);
        }
        DynamicManager.update(server);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void prts$dynamicShutdown(CallbackInfo ci) {
        DynamicSetting.resetAll();
    }
}
