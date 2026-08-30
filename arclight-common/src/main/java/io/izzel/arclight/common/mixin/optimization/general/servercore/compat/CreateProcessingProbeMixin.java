/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.fluids.spout.FillingBySpout;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [探针-临时] create 装配/注液并行诊断：deployer 装配与 spout 注液在 region worker 上
 * 失败的定位（打线程 + 状态 + 结果）。定位完成后删除。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
public abstract class CreateProcessingProbeMixin {

    @Mixin(value = BeltDeployerCallbacks.class, remap = false)
    public abstract static class DeployerProbe {

        @Inject(method = "whenItemHeld", at = @At("RETURN"))
        private static void prts$probeHeld(TransportedItemStack stack,
                                           TransportedItemStackHandlerBehaviour handler,
                                           DeployerBlockEntity deployer,
                                           CallbackInfoReturnable<BeltProcessingBehaviour.ProcessingResult> cir) {
            String st = "?";
            int timer = -1;
            boolean rl = false;
            boolean hasPlayer = deployer.getPlayer() != null;
            try {
                var stateF = DeployerBlockEntity.class.getDeclaredField("state");
                stateF.setAccessible(true);
                st = String.valueOf(stateF.get(deployer));
                var timerF = DeployerBlockEntity.class.getDeclaredField("timer");
                timerF.setAccessible(true);
                timer = timerF.getInt(deployer);
                var rlF = DeployerBlockEntity.class.getDeclaredField("redstoneLocked");
                rlF.setAccessible(true);
                rl = rlF.getBoolean(deployer);
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("deployer held t={} pos={} result={} state={} timer={} rl={} player={} speed={}",
                    Thread.currentThread().getName(), deployer.getBlockPos(), cir.getReturnValue(),
                    st, timer, rl, hasPlayer, deployer.getSpeed());
        }

        @Inject(method = "activate", at = @At("HEAD"))
        private static void prts$probeActivate(TransportedItemStack stack,
                                               TransportedItemStackHandlerBehaviour handler,
                                               DeployerBlockEntity deployer,
                                               Recipe<?> recipe,
                                               CallbackInfo ci) {
            LogManager.getLogger("PRTS-CreateProbe").info("deployer activate t={} pos={} recipe={}",
                    Thread.currentThread().getName(), deployer.getBlockPos(), recipe);
        }

        @Redirect(method = "activate",
                at = @At(value = "INVOKE",
                        target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;"))
        private static java.util.List<net.minecraft.world.item.ItemStack> prts$probeResult(
                Level level, net.minecraft.world.item.ItemStack stack, Recipe recipe, boolean b) {
            java.util.List<net.minecraft.world.item.ItemStack> result =
                    com.simibubi.create.foundation.recipe.RecipeApplier.applyRecipeOn(level, stack, recipe, b);
            LogManager.getLogger("PRTS-CreateProbe").info("deployer result t={} in={} resultSize={} first={}",
                    Thread.currentThread().getName(), stack, result.size(),
                    result.isEmpty() ? "EMPTY" : result.get(0));
            return result;
        }
    }

    @Mixin(value = FillingBySpout.class, remap = false)
    public abstract static class SpoutProbe {

        @Inject(method = "fillItem", at = @At("RETURN"))
        private static void prts$probeFill(Level level, int amount, ItemStack stack, FluidStack fluid,
                                           CallbackInfoReturnable<ItemStack> cir) {
            LogManager.getLogger("PRTS-CreateProbe").info("spout fill t={} in={} -> out={}",
                    Thread.currentThread().getName(), stack, cir.getReturnValue());
        }
    }
}
