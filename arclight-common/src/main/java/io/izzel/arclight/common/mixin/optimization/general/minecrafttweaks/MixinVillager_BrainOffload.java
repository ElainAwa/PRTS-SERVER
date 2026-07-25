package io.izzel.arclight.common.mixin.optimization.general.minecrafttweaks;

import io.izzel.arclight.common.optimization.general.minecrafttweaks.MinecraftTweaks;
import io.izzel.arclight.common.optimization.general.minecrafttweaks.VillagerBrainOffloader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 村民脑切：注入 Villager.customServerAiStep 中对 Brain.tick 的调用。
 * 仅当开关开启且村民"卡住"时跳过 brain.tick；开关关闭或村民可自由移动时照常调用（回退原版）。
 */
@Mixin(Villager.class)
public abstract class MixinVillager_BrainOffload {

    @Redirect(method = "customServerAiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void luminara$villagerBrainTick(Brain brain, ServerLevel level, LivingEntity self) {
        Villager villager = (Villager) (Object) self;
        if (!MinecraftTweaks.villagerBrainOffloadEnabled() || VillagerBrainOffloader.getInstance().isLobotomized(villager)) {
            brain.tick(level, villager);
        }
    }
}
