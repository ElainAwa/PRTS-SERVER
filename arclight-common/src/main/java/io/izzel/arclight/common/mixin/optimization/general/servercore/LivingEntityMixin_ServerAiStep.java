package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.LivingEntityServerAiStepAccess;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * PRTS region parallelism: exposes the NeoForge server AI step (P3 v08).
 *
 * <p>NeoForge 1.21.1 moved AI scheduling (goalSelector / targetSelector /
 * brain) out of {@code Entity.tick} (now just baseTick) into the protected
 * {@code serverAiStep()}, invoked only by the main-thread tick consumer. The
 * region worker path would silently skip AI and freeze mobs; this invoker lets
 * {@code RegionTickManager.tickEntity} run the AI step on the region worker
 * (same-thread with the tick).</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin_ServerAiStep implements LivingEntityServerAiStepAccess {

    @Invoker("serverAiStep")
    @Override
    public abstract void arclight$invokerServerAiStep();
}
