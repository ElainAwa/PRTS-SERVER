/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.optimization.general.servercore.VillagerPathBudget;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

@Mixin(AcquirePoi.class)
public abstract class AcquirePoiMixin_VillagerPathBudget {

    @Inject(
            method = "method_46885",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;findAllClosestFirstWithType(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true
    )
    private static void arclight$budgetPoiPath(boolean onlyRunIfChild, MutableLong nextScheduledStart, Long2ObjectMap failedPoiRetries,
                                               Predicate<Holder<PoiType>> poiPredicate, MemoryAccessor<?, ?> memoryAccessor,
                                               Optional<Byte> entityEvent, ServerLevel level, PathfinderMob mob, long gameTime,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!VillagerPathBudget.tryStart(level.getServer(), mob, true)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "method_46885", at = @At("RETURN"))
    private static void arclight$finishPoiPath(boolean onlyRunIfChild, MutableLong nextScheduledStart, Long2ObjectMap failedPoiRetries,
                                              Predicate<Holder<PoiType>> poiPredicate, MemoryAccessor<?, ?> memoryAccessor,
                                              Optional<Byte> entityEvent, ServerLevel level, PathfinderMob mob, long gameTime,
                                              CallbackInfoReturnable<Boolean> cir) {
        VillagerPathBudget.finish(mob);
    }
}
