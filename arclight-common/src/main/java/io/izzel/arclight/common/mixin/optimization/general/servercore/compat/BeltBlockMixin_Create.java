/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.transport.BeltMovementHandler.TransportedEntityInfo;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * B4: lets entities that tick on a parallel worker (e.g. villagers returned to
 * parallel by route-on-read) still be transported by Create belts.
 *
 * <p>On a worker thread {@code Level.getBlockEntity} returns null, so vanilla
 * {@code entityInside} can never add the entity to the belt controller's
 * (non-concurrent) {@code passengers} map. Instead we capture (entity, pos, state)
 * and defer the registration to the main thread, where it runs before the belt
 * BE tick (PRE phase) using the live controller BE. Main-thread calls are
 * untouched. Gated by {@code parallel.belt-passenger-defer} (default off).</p>
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = BeltBlock.class, remap = false)
public abstract class BeltBlockMixin_Create {

    static {
        // Register the main-thread applier once: replays vanilla's living-passenger
        // registration with the real controller BE (see BeltBlock.entityInside).
        RegionTickManager.setBeltPassengerApplier((level, entity, pos, state) -> {
            if (!BeltBlock.canTransportObjects(state)) {
                return;
            }
            BeltBlockEntity controller = BeltHelper.getControllerBE(level, pos);
            if (controller == null || controller.passengers == null) {
                return;
            }
            if (controller.passengers.containsKey(entity)) {
                TransportedEntityInfo info = controller.passengers.get(entity);
                if (info.getTicksSinceLastCollision() != 0 || pos.equals(entity.blockPosition())) {
                    info.refresh(pos, state);
                }
            } else {
                controller.passengers.put(entity, new TransportedEntityInfo(pos, state));
                entity.setOnGround(true);
            }
        });
    }

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$deferWorkerPassenger(BlockState state, Level worldIn, BlockPos pos, Entity entityIn,
                                               CallbackInfo ci) {
        if (!PRTSFeaturesConfig.beltPassengerDefer) {
            return;
        }
        if (!(worldIn instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!RegionTickManager.isRegionWorker() && !DimensionTickManager.isDimensionTickThread()) {
            return;
        }
        // Items are ticked on the main thread already; only defer living passengers.
        if (entityIn instanceof ItemEntity) {
            return;
        }
        if (!BeltBlock.canTransportObjects(state)) {
            return;
        }
        RegionTickManager.queueBeltPassenger(serverLevel, entityIn, pos, state);
        ci.cancel();
    }
}
