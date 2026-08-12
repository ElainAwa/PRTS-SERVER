/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-parallel workers tick entity AI off the main thread, so a
 * {@link LegacyRandomSource} shared across threads (vanilla behavior/mob AI,
 * or any mod-held instance) hits a seed CAS conflict. The vanilla response is
 * to throw; with {@code region-parallel} on we instead retry the CAS in a spin
 * loop, matching {@code java.util.Random}'s own concurrency behavior. Off, the
 * vanilla guard is untouched.
 */
@Mixin(LegacyRandomSource.class)
public abstract class LegacyRandomSourceMixin_RegionSafe {

    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;
    private static final long MASK = 281474976710655L;

    @Shadow
    @Final
    private AtomicLong seed;

    @Inject(method = "next", at = @At("HEAD"), cancellable = true)
    private void arclight$spinCasNext(int bits, CallbackInfoReturnable<Integer> cir) {
        if (PRTSFeaturesConfig.parallelRegion) {
            long i;
            long j;
            do {
                i = this.seed.get();
                j = (i * MULTIPLIER + INCREMENT) & MASK;
            } while (!this.seed.compareAndSet(i, j));
            cir.setReturnValue((int) (j >>> (48 - bits)));
        }
    }
}
