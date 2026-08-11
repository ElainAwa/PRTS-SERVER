/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.LivingEntityServerAiStepAccess;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected NeoForge server AI step: NeoForge moved AI scheduling out
 * of {@code Entity.tick} into {@code serverAiStep()}, invoked only by the main-thread
 * tick consumer. This invoker lets {@code RegionTickManager.tickEntity} run the AI
 * step on the region worker (same-thread with the tick), or mobs would freeze.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin_ServerAiStep implements LivingEntityServerAiStepAccess {

    @Invoker("serverAiStep")
    @Override
    public abstract void arclight$invokerServerAiStep();
}
