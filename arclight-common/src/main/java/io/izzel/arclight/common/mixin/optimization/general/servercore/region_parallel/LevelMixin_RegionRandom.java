package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PRTS region parallelism (P3 slice 1 fix, AI-created): make the public
 * {@link Level#random} field thread-safe when {@code region-parallel} is enabled.
 *
 * <p>Vanilla initializes {@code random = RandomSource.create()} (a LegacyRandomSource
 * with a ThreadingDetector that throws on cross-thread access) and separately keeps
 * {@code threadSafeRandom = RandomSource.createThreadSafe()}. On a region worker the
 * entity AI reads {@code level.random} (e.g. Villager SetRaidStatus) concurrently with
 * the dimension worker's random tick phase → "Accessing LegacyRandomSource from multiple
 * threads" crash. Redirecting the constructor's {@code create()} call to
 * {@code createThreadSafe()} makes every {@code level.random} call site safe without
 * writing a final field (NeoForge rejects field writes at runtime). Random stream
 * semantics are equivalent (pseudo-random); determinism is not preserved (documented
 * P3 non-determinism, see docs/parallel-phase3-region-parallelism-v01.md §4.5).</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionRandom {

    @Redirect(method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private static RandomSource arclight$regionRandom() {
        if (ServerCoreConfig.isEnabled(ServerCoreConfig.Feature.REGION_PARALLEL)) {
            return RandomSource.createThreadSafe();
        }
        return RandomSource.create();
    }
}
