package io.izzel.arclight.common.mixin.optimization.general.servercore.sync_loads;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Bee.isTooFarAway (private under Mojmap) for BeeMixin.
 * Equivalent to ServerCore's original @ModifyExpressionValue behaviour.
 */
@Mixin(Bee.class)
public interface BeeAccessor {
    @Invoker("isTooFarAway")
    boolean arclight$isTooFarAway(BlockPos pos);
}
