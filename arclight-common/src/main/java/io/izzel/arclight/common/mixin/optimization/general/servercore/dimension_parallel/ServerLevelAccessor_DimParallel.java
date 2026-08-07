package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * PRTS dimension parallelism (P2 experiment, AI-created): field accessors for
 * {@link ServerLevel} used by the hand-written
 * {@code EntityCallbacks.onTrackingStart} rewrite
 * (see {@link EntityCallbacksMixin_DimParallel}).
 */
@Mixin(ServerLevel.class)
public interface ServerLevelAccessor_DimParallel {

    @Accessor("isUpdatingNavigations")
    boolean arclight$isUpdatingNavigations();

    @Accessor("navigatingMobs")
    Set<Mob> arclight$getNavigatingMobs();

    @Accessor("dragonParts")
    Int2ObjectMap<Object> arclight$getDragonParts();
}
