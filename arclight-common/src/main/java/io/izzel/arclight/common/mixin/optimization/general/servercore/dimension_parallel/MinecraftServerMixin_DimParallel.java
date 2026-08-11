/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickUnit;
import io.izzel.arclight.common.optimization.general.servercore.ParallelTickUnit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Dimension-level parallelism entry point: redirects {@code getWorldArray()} at the
 * head of {@code MinecraftServer.tickChildren}. When enabled with more than one
 * dimension loaded, the dimension phase runs via {@link DimensionTickManager} and
 * an empty array is returned so the vanilla loop spins zero times.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_DimParallel {

    @Shadow
    @Final
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Shadow
    @Final
    private Map<ResourceKey<Level>, long[]> perWorldTickTimes;

    @Shadow
    private void synchronizeTime(ServerLevel level) {
    }

    @Redirect(method = "tickChildren",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getWorldArray()[Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel[] arclight$dimParallel(MinecraftServer server, BooleanSupplier shouldKeepTicking) {
        ServerLevel[] worldArray = this.levels.values().toArray(new ServerLevel[0]);
        if (!PRTSFeaturesConfig.parallelDimension || worldArray.length <= 1) {
            return worldArray;
        }
        ParallelTickUnit[] units = new ParallelTickUnit[worldArray.length];
        for (int i = 0; i < units.length; i++) {
            units[i] = new DimensionTickUnit(worldArray[i]);
        }
        DimensionTickManager.parallelTick(server, units, shouldKeepTicking,
                server.getTickCount(), this.perWorldTickTimes, this::synchronizeTime);
        return new ServerLevel[0];
    }
}
