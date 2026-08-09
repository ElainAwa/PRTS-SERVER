/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * PRTS region-level entity tick entry (P3 slice 1, AI-created).
 *
 * <p>Redirects the {@code entityTickList.forEach} call inside
 * {@code ServerLevel.tick}: when the {@code region-parallel} feature is enabled
 * for the overworld, the entity tick phase is dispatched to region workers by
 * {@link RegionTickManager} (which replicates the vanilla forEach semantics);
 * otherwise the vanilla consumer runs untouched.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_RegionTick {

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void arclight$regionEntityTick(EntityTickList list, Consumer<Entity> consumer, BooleanSupplier hasTimeLeft) {
        if (!RegionTickManager.dispatchAndTick((ServerLevel) (Object) this, list)) {
            list.forEach(consumer);
        }
    }
}
