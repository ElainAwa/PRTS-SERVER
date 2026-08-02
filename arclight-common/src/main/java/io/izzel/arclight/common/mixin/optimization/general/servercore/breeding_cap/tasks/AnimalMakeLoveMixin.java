package io.izzel.arclight.common.mixin.optimization.general.servercore.breeding_cap.tasks;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCap;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AnimalMakeLove.class)
public class AnimalMakeLoveMixin {

    // 源用 @WrapWithCondition；改 @Redirect 以保留调用点之后的 eraseMemory 语义
    @Redirect(
            method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/animal/Animal;spawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;)V"
            )
    )
    private void servercore$enforceBreedCap(Animal owner, ServerLevel level, Animal mate) {
        BreedingCapConfig config = ServerCoreConfig.breedingCap();
        if (config.enabled() && config.animals().exceedsLimit(owner)) {
            BreedingCap.resetLove(owner, mate);
            return;
        }

        owner.spawnChildFromBreeding(level, mate);
    }
}
