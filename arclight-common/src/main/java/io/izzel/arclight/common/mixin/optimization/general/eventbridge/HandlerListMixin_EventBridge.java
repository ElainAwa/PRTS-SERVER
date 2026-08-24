/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.eventbridge;

import io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeRegistry;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notifies {@link EventBridgeRegistry} after every listener-registration mutation.
 * Hooking {@link HandlerList} (the lowest common denominator) covers all paths:
 * {@code SimplePluginManager.registerEvent/registerEvents} end in
 * {@code HandlerList.register/registerAll}, and plugins calling
 * {@code HandlerList.unregister*} directly are covered too — a gate can never stay
 * open/closed on a path we miss.
 *
 * <p>Thread discipline: Bukkit event registration is main-thread only, so the gate
 * re-evaluation runs on the main thread inside the same call stack (a plugin enable /
 * disable / reload). The dispatcher registration that follows is synchronous, which
 * makes the "listener registered but dispatcher not yet on the bus" window
 * unreachable.</p>
 */
@Mixin(value = HandlerList.class, remap = false)
public abstract class HandlerListMixin_EventBridge {

    @Inject(method = "register(Lorg/bukkit/plugin/RegisteredListener;)V", at = @At("RETURN"))
    private void arclight$afterRegister(RegisteredListener listener, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "registerAll(Ljava/util/Collection;)V", at = @At("RETURN"))
    private void arclight$afterRegisterAll(java.util.Collection<RegisteredListener> listeners, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "unregister(Lorg/bukkit/plugin/RegisteredListener;)V", at = @At("RETURN"))
    private void arclight$afterUnregister(RegisteredListener listener, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "unregister(Lorg/bukkit/plugin/Plugin;)V", at = @At("RETURN"))
    private void arclight$afterUnregister(Plugin plugin, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "unregister(Lorg/bukkit/event/Listener;)V", at = @At("RETURN"))
    private void arclight$afterUnregister(Listener listener, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "unregisterAll()V", at = @At("RETURN"))
    private static void arclight$afterUnregisterAll(CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "unregisterAll(Lorg/bukkit/plugin/Plugin;)V", at = @At("RETURN"))
    private static void arclight$afterUnregisterAll(Plugin plugin, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }

    @Inject(method = "unregisterAll(Lorg/bukkit/event/Listener;)V", at = @At("RETURN"))
    private static void arclight$afterUnregisterAll(Listener listener, CallbackInfo ci) {
        EventBridgeRegistry.onHandlerListChanged();
    }
}
