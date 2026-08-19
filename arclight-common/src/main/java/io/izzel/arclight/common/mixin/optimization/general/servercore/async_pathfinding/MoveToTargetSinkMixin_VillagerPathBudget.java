/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.optimization.general.servercore.VillagerPathBudget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin_VillagerPathBudget {

    @Inject(
            method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/MoveToTargetSink;tryComputePath(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/entity/ai/memory/WalkTarget;J)Z",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true
    )
    private void arclight$budgetStartPath(ServerLevel level, Mob mob, CallbackInfoReturnable<Boolean> cir) {
        if (!VillagerPathBudget.tryStart(level.getServer(), mob, false)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/MoveToTargetSink;tryComputePath(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/entity/ai/memory/WalkTarget;J)Z",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true
    )
    private void arclight$budgetRetargetPath(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        if (!VillagerPathBudget.tryStart(level.getServer(), mob, false)) {
            ci.cancel();
        }
    }

    @Inject(method = "tryComputePath", at = @At("RETURN"))
    private void arclight$finishMovePath(Mob mob, WalkTarget walkTarget, long gameTime, CallbackInfoReturnable<Boolean> cir) {
        VillagerPathBudget.finish(mob);
    }
}
