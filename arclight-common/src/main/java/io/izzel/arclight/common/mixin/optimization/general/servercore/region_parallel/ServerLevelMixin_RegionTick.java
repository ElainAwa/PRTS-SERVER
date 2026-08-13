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
 * Region-level entity tick entry: redirects {@code entityTickList.forEach} in
 * {@code ServerLevel.tick}. When {@code region-parallel} is enabled for the
 * overworld, the entity tick phase is dispatched to region workers by
 * {@link RegionTickManager} (vanilla forEach semantics); otherwise vanilla runs.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_RegionTick {

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void arclight$regionEntityTick(EntityTickList list, Consumer<Entity> consumer, BooleanSupplier hasTimeLeft) {
        if (!RegionTickManager.dispatchAndTick((ServerLevel) (Object) this, list, consumer)) {
            list.forEach(consumer);
        }
    }
}
