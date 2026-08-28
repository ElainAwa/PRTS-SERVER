/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BooleanSupplier;

/**
 * Keeps the client delta-sync chain on the main thread while the dimension
 * worker continues the compute skeleton. {@code ServerChunkCache.tick} (chunk
 * loading/unloading, random ticks, {@code ChunkMap.tick} entity tracking
 * broadcasts) and {@code PersistentEntitySectionManager.tick} (entity storage
 * management) are deferred to the POST phase of {@link DimensionTickManager}
 * when invoked on a dimension worker; the worker thread never runs them.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_TickSyncCollect {

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void arclight$collectChunkSourceTick(ServerChunkCache chunkSource, BooleanSupplier hasTimeLeft,
                                                 boolean tickPassengers) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (DimensionTickManager.isDimensionTickThread()) {
            DimensionTickManager.collectPostSync(level, () -> chunkSource.tick(hasTimeLeft, tickPassengers));
        } else {
            chunkSource.tick(hasTimeLeft, tickPassengers);
        }
    }

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;tick()V"))
    private void arclight$collectEntityManagerTick(PersistentEntitySectionManager<?> manager) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (DimensionTickManager.isDimensionTickThread()) {
            DimensionTickManager.collectPostSync(level, manager::tick);
        } else {
            manager.tick();
        }
    }
}
