/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.biome_lookups;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig.Feature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Ported from Wesley1808/ServerCore (Mojmap / 1.21.1).
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {

    @Redirect(
            method = {"mobsAt", "getRandomSpawnMobAt"},
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"
            )
    )
    private static Holder<Biome> servercore$fastBiomeLookup(ServerLevel level, BlockPos pos) {
        if (!ServerCoreConfig.isEnabled(Feature.BIOME_LOOKUPS)) return level.getBiome(pos);
        return ChunkManager.getRoughBiome(level, pos);
    }
}
