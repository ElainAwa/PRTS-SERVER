/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the public {@link Level#random} field thread-safe when {@code region-parallel}
 * is enabled: the constructor's {@code RandomSource.create()} is redirected to
 * {@code createThreadSafe()} because the vanilla LegacyRandomSource throws on
 * cross-thread access. Semantics are equivalent; determinism is not preserved.
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionRandom {

    @Redirect(method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private static RandomSource arclight$regionRandom() {
        if (PRTSFeaturesConfig.parallelRegion) {
            return RandomSource.createThreadSafe();
        }
        return RandomSource.create();
    }
}
