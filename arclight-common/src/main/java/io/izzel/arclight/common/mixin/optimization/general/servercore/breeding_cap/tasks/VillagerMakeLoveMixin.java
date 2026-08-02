package io.izzel.arclight.common.mixin.optimization.general.servercore.breeding_cap.tasks;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCap;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerMakeLove.class)
public class VillagerMakeLoveMixin {

    // 不用 LocalCapture（多模组环境下局部变量表易漂移），改从 BREED_TARGET 记忆重新取伴侣
    @Inject(
            method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V",
            cancellable = true,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/npc/Villager;eatAndDigestFood()V",
                    ordinal = 0
            )
    )
    private void servercore$enforceBreedCap(ServerLevel level, Villager owner, long gameTime, CallbackInfo ci) {
        BreedingCapConfig config = ServerCoreConfig.breedingCap();
        if (!config.enabled() || !config.villagers().exceedsLimit(owner)) {
            return;
        }

        AgeableMob mate = owner.getBrain().getMemory(MemoryModuleType.BREED_TARGET).orElse(null);
        if (mate != null) {
            BreedingCap.resetAge(owner, mate);
        }
        ci.cancel();
    }
}
