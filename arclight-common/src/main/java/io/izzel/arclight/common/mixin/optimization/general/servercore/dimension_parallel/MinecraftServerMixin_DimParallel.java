package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickUnit;
import io.izzel.arclight.common.optimization.general.servercore.ParallelTickUnit;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
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
 * PRTS dimension-level parallelism entry point (P2 experiment, AI-created).
 *
 * <p>Redirects the {@code getWorldArray()} call at the head of
 * {@code MinecraftServer.tickChildren} (the dimension tick loop): when the
 * {@code dimension-parallel} feature is enabled and more than one dimension is
 * loaded, the whole dimension phase is executed by {@link DimensionTickManager}
 * (pre events -> parallel ticks -> barrier -> post events -> tick times ->
 * deferred transfers) and an empty array is returned so the vanilla loop spins
 * zero times. When disabled, returns the real world array (vanilla behavior).</p>
 *
 * <p>The existing {@code @Inject tickChildren HEAD} handler (Bukkit scheduler
 * heartbeat / queued task drain) is not cancelled and keeps running.</p>
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
        if (!ServerCoreConfig.isEnabled(ServerCoreConfig.Feature.DIMENSION_PARALLEL) || worldArray.length <= 1) {
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
