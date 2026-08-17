/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.ColonyManager;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Cache the colony snapshot consumed by
 * {@code ColonyManager.onServerTick(ServerTickEvent.Pre)}.
 *
 * <p>Spark (200-citizen walk load, 2026-08-16) showed
 * {@code ColonyManager.getAllColonies} + {@code getColonySaveData} taking
 * ~6% of active main-thread samples: every server tick the handler allocates a
 * fresh {@code ArrayList}, iterates all levels and resolves the per-world
 * colony save data. The returned list is only read by the caller here, so a
 * private cached snapshot is safe. It is rebuilt immediately on known
 * create/delete paths and self-heals after {@code colony-manager-tick-cache-interval}
 * ticks in case colonies were changed through other entry points.</p>
 */
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = ColonyManager.class, remap = false)
public abstract class ColonyManagerMixin_ServerTickCache {

    @Unique
    private static volatile List<IColony> prts$colonySnapshot;

    @Unique
    private static volatile long prts$lastSnapshotNanos;

    @Unique
    private static volatile boolean prts$coloniesDirty = true;

    @Redirect(method = "onServerTick", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/minecolonies/core/colony/ColonyManager;getAllColonies()Ljava/util/List;"))
    private List<IColony> prts$cachedAllColonies(ColonyManager self) {
        if (!PRTSFeaturesConfig.colonyManagerTickCacheEnabled) {
            return self.getAllColonies();
        }
        List<IColony> snapshot = prts$colonySnapshot;
        boolean dirty = prts$coloniesDirty;
        long ttlNanos = PRTSFeaturesConfig.colonyManagerTickCacheInterval * 50_000_000L;
        if (snapshot == null || dirty || System.nanoTime() - prts$lastSnapshotNanos >= ttlNanos) {
            List<IColony> fresh = self.getAllColonies();
            prts$colonySnapshot = fresh;
            prts$lastSnapshotNanos = System.nanoTime();
            prts$coloniesDirty = false;
            return fresh;
        }
        return snapshot;
    }

    @Inject(method = "createColony", remap = false, at = @At("RETURN"))
    private void prts$markDirtyOnCreate(CallbackInfoReturnable<IColony> cir) {
        prts$coloniesDirty = true;
    }

    @Inject(method = "addColonyDirect", remap = false, at = @At("RETURN"))
    private void prts$markDirtyOnAdd(CallbackInfo ci) {
        prts$coloniesDirty = true;
    }

    @Inject(method = "deleteColonyByWorld", remap = false, at = @At("HEAD"))
    private void prts$markDirtyOnDeleteWorld(CallbackInfo ci) {
        prts$coloniesDirty = true;
    }

    @Inject(method = "deleteColonyByDimension", remap = false, at = @At("HEAD"))
    private void prts$markDirtyOnDeleteDimension(CallbackInfo ci) {
        prts$coloniesDirty = true;
    }

    @Inject(method = "onWorldUnload", remap = false, at = @At("HEAD"))
    private void prts$markDirtyOnWorldUnload(CallbackInfo ci) {
        prts$coloniesDirty = true;
    }
}
