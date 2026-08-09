/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import com.google.common.collect.Lists;
import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.world.level.saveddata.maps.MapItemSavedDataBridge;
import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * PRTS dimension parallelism (P2 experiment, AI-created): stabilize
 * {@code ServerLevel$EntityCallbacks.onTrackingStart/onTrackingEnd} on dimension
 * tick worker threads.
 *
 * <p>Both methods used to carry a vanilla {@code invokedynamic} (the
 * {@code BiConsumer} for {@code updateDynamicGameEventListener}) and, until the
 * AsyncCatcher void-method fix (P2 v22), an injected AsyncCatcher checkOp block
 * whose Supplier lambda failed to link on workers. The whole method is rewritten
 * here with a cancellable HEAD injection using hand-written anonymous classes
 * (no invokedynamic at all), mirroring the merged-jar bytecode 1:1 plus the Arclight
 * core mixin side effects (valid/in-world flags, map/InventoryHolder cleanup,
 * entity-remove broadcast).
 *
 * <p>priority stays at the default 1000 so Lithium's
 * {@code entity.inactive_navigations.ServerLevel$EntityCallbacksMixin} (also 1000)
 * can keep injecting {@code onTrackingStart} (a higher priority overwrite would
 * block it, "cannot inject ... merged by ...").</p>
 */
@Mixin(targets = "net/minecraft/server/level/ServerLevel$EntityCallbacks")
public abstract class EntityCallbacksMixin_DimParallel {

    @Shadow(aliases = {"this$0", "f_143351_", "field_26936"})
    private ServerLevel outerThis;

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void arclight$stableOnTrackingStart(Entity entity, CallbackInfo ci) {
        ci.cancel();
        ServerLevel outer = this.outerThis;
        outer.getChunkSource().addEntity(entity);
        if (entity instanceof ServerPlayer player) {
            outer.players().add(player);
            outer.updateSleepingPlayerList();
        }
        if (entity instanceof Mob mob) {
            ServerLevelAccessor_DimParallel acc = (ServerLevelAccessor_DimParallel) outer;
            if (acc.arclight$isUpdatingNavigations()) {
                Util.logAndPauseIfInIde("onTrackingStart called during navigation iteration",
                        new IllegalStateException("onTrackingStart called during navigation iteration"));
            }
            acc.arclight$getNavigatingMobs().add(mob);
        }
        Entity[] parts = ((EntityBridge) entity).bridge$forge$getParts();
        if (parts != null && parts.length > 0) {
            ServerLevelAccessor_DimParallel acc = (ServerLevelAccessor_DimParallel) outer;
            for (Entity part : parts) {
                acc.arclight$getDragonParts().put(part.getId(), part);
            }
        }
        entity.updateDynamicGameEventListener(new BiConsumer<>() {
            @Override
            public void accept(DynamicGameEventListener<?> listener, ServerLevel level) {
                listener.add(level);
            }
        });
        ((EntityBridge) entity).bridge$setInWorld(true);
        ((EntityBridge) entity).bridge$setValid(true);
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void arclight$stableOnTrackingEnd(Entity entity, CallbackInfo ci) {
        ci.cancel();
        ServerLevel outer = this.outerThis;
        outer.getChunkSource().removeEntity(entity);
        if (entity instanceof ServerPlayer player) {
            outer.players().remove(player);
            outer.updateSleepingPlayerList();
        }
        if (entity instanceof Mob mob) {
            ServerLevelAccessor_DimParallel acc = (ServerLevelAccessor_DimParallel) outer;
            if (acc.arclight$isUpdatingNavigations()) {
                Util.logAndPauseIfInIde("onTrackingStart called during navigation iteration",
                        new IllegalStateException("onTrackingStart called during navigation iteration"));
            }
            acc.arclight$getNavigatingMobs().remove(mob);
        }
        Entity[] parts = ((EntityBridge) entity).bridge$forge$getParts();
        if (parts != null && parts.length > 0) {
            ServerLevelAccessor_DimParallel acc = (ServerLevelAccessor_DimParallel) outer;
            for (Entity part : parts) {
                acc.arclight$getDragonParts().remove(part.getId());
            }
        }
        entity.updateDynamicGameEventListener(new BiConsumer<>() {
            @Override
            public void accept(DynamicGameEventListener<?> listener, ServerLevel level) {
                listener.remove(level);
            }
        });
        // Replicate the Arclight core mixin handlers (arclight$entityCleanup HEAD,
        // arclight$invalid RETURN) which the cancellation skips.
        if (entity instanceof Player player) {
            for (ServerLevel serverLevel : ArclightServer.getMinecraftServer().getAllLevels()) {
                DimensionDataStorage worldData = serverLevel.getDataStorage();
                for (Object o : worldData.cache.values()) {
                    if (o instanceof MapItemSavedData map) {
                        map.carriedByPlayers.remove(player);
                        ((MapItemSavedDataBridge) map).bridge$getCarriedBy().removeIf(
                                new Predicate<>() {
                                    @Override
                                    public boolean test(MapItemSavedData.HoldingPlayer holdingPlayer) {
                                        return holdingPlayer.player == entity;
                                    }
                                });
                    }
                }
            }
        }
        if (((EntityBridge) entity).bridge$getBukkitEntity() instanceof InventoryHolder holder) {
            for (HumanEntity h : Lists.newArrayList(holder.getInventory().getViewers())) {
                h.closeInventory();
            }
        }
        ((EntityBridge) entity).bridge$setValid(false);
        if (!(entity instanceof ServerPlayer)) {
            for (ServerPlayer p : outer.players()) {
                ((ServerPlayerBridge) p).bridge$getBukkitEntity().onEntityRemove(entity);
            }
        }
    }
}
