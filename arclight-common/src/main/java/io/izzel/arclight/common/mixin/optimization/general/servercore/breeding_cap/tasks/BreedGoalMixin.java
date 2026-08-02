package io.izzel.arclight.common.mixin.optimization.general.servercore.breeding_cap.tasks;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCap;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BreedGoal.class)
public abstract class BreedGoalMixin {
    @Shadow
    @Final
    protected Animal animal;
    @Shadow
    protected Animal partner;

    @Shadow
    protected abstract void breed();

    // 源用 @WrapWithCondition；本树无 mixin-extras，改原生 @Redirect
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/BreedGoal;breed()V"
            )
    )
    private void servercore$enforceBreedCap(BreedGoal goal) {
        BreedingCapConfig config = ServerCoreConfig.breedingCap();
        if (config.enabled() && config.animals().exceedsLimit(this.animal)) {
            BreedingCap.resetLove(this.animal, this.partner);
            return;
        }

        this.breed();
    }
}
