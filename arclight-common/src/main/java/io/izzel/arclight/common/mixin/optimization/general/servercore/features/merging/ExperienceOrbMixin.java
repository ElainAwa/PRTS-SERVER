package io.izzel.arclight.common.mixin.optimization.general.servercore.features.merging;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ExperienceOrb.class)
public class ExperienceOrbMixin {

    @ModifyConstant(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z", constant = @Constant(intValue = 40), require = 0, expect = 0)
    private static int luminara$modifyMergeFraction(int constant) {
        return ServerCoreConfig.features().xpMergeFraction();
    }

    @ModifyArg(
            method = "scanForEntities",
            require = 0,
            expect = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"
            )
    )
    private double luminara$modifyMergeRadius(double original) {
        double radius = ServerCoreConfig.features().xpMergeRadius();
        return radius < 0.0D ? original : radius;
    }
}
