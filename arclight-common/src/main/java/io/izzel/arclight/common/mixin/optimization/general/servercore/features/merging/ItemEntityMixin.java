package io.izzel.arclight.common.mixin.optimization.general.servercore.features.merging;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {

    @ModifyConstant(method = "mergeWithNeighbours", require = 0, constant = @Constant(doubleValue = 0.5))
    private double servercore$modifyMergeRadius(double constant) {
        return ServerCoreConfig.features().enabled() ? ServerCoreConfig.features().itemMergeRadius() : constant;
    }
}
