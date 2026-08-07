package io.izzel.arclight.common.mixin.optimization.general.servercore.features.merging;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {

    /** 命中 inflate(DDD) 的 X/Z 两个 0.5d；Y 轴是 dconst_0，不受影响。 */
    @ModifyConstant(method = "mergeWithNeighbours", require = 0, expect = 0, constant = @Constant(doubleValue = 0.5D))
    private double luminara$modifyMergeRadius(double original) {
        double radius = ServerCoreConfig.features().itemMergeRadius();
        return radius < 0.0D ? original : radius;
    }
}
