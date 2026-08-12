/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the world random source thread-safe when {@code region-parallel} is enabled:
 * <ul>
 *   <li>the constructor's {@code RandomSource.create()} is redirected to
 *       {@code createThreadSafe()} for the {@link Level#random} field, and</li>
 *   <li>{@code getRandom()} itself returns a cached thread-safe source, covering
 *       callers that read the random at runtime (entity AI behaviors, mobs, etc.).</li>
 * </ul>
 * The vanilla LegacyRandomSource throws on cross-thread access; semantics are
 * equivalent, determinism is not preserved.
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionRandom {

    @Unique
    private static volatile RandomSource arclight$threadSafeRandom;

    @Unique
    private static RandomSource arclight$random() {
        RandomSource r = arclight$threadSafeRandom;
        if (r == null) {
            r = RandomSource.createThreadSafe();
            arclight$threadSafeRandom = r;
        }
        return r;
    }

    @Redirect(method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private static RandomSource arclight$regionRandom() {
        if (PRTSFeaturesConfig.parallelRegion) {
            return arclight$random();
        }
        return RandomSource.create();
    }

    @Inject(method = "getRandom", at = @At("RETURN"), cancellable = true)
    private void arclight$threadSafeGetRandom(CallbackInfoReturnable<RandomSource> cir) {
        if (PRTSFeaturesConfig.parallelRegion) {
            cir.setReturnValue(arclight$random());
        }
    }
}
