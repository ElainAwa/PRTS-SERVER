/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.lightengine;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.lightengine.LightBudget;
import io.izzel.arclight.common.optimization.general.lightengine.LightEngineStats;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PRTS light-engine per-tick budget + telemetry.
 *
 * <p>Vanilla's {@link LightEngine#runLightUpdates()} drains the entire increase / decrease
 * propagation queue in one pass. A light "storm" (thousands of block edits in a tick) can
 * therefore backlog the light thread and stall {@code waitForPendingTasks} (chunk load/save)
 * on the main thread.
 *
 * <p>This mixin caps the per-tick propagation work: each queued position processed consumes one
 * unit of a global per-tick budget; once the budget is exhausted the drain loop exits and the
 * remaining entries stay queued for the next tick. Final lighting is unchanged — only the rate of
 * propagation is spread out ("语义不变，只是延迟"), so mods reading {@code getBrightness} are
 * unaware. The {@code blockNodesToCheck} pre-drain (cheap read + enqueue) is intentionally not
 * budgeted; the cost is dominated by the increase/decrease BFS.
 *
 * <p>Telemetry records queue size / update count / drain duration into {@link LightEngineStats}
 * ({@code [light-engine]} summary log) so {@code lighting.budget-per-tick} can be tuned from real
 * server data.
 *
 * <p>Yields via {@link LoadIfMod} to C2ME / Lithium / Canary / Radium, which replace or optimize
 * the light engine themselves.
 */
@Mixin(LightEngine.class)
@LoadIfMod(modid = {ModIds.LITHIUM, ModIds.CANARY, ModIds.RADIUM, ModIds.C2ME}, condition = LoadIfMod.ModCondition.ABSENT)
public abstract class LightEngineMixin_LightBudget {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Optimization");

    // Per-tick budget state machine lives in LightBudget (JDK-only, unit-testable standalone).
    // Best-effort: races between light threads only make the budget slightly imprecise, never
    // drop light work (deferral just exits the drain loop, leaving entries queued).
    @Unique
    private static long prts$warnNanos = 0L;

    // Telemetry scratch (per-instance; runLightUpdates is not re-entrant per instance).
    @Unique
    private long prts$startNanos = 0L;
    @Unique
    private int prts$pendingNodes = 0;

    @Shadow @Final
    protected LightChunkGetter chunkSource;
    @Shadow @Final
    private LongOpenHashSet blockNodesToCheck;
    @Shadow @Final
    private LongArrayFIFOQueue decreaseQueue;
    @Shadow @Final
    private LongArrayFIFOQueue increaseQueue;

    @Redirect(method = "propagateIncreases", require = 1,
            at = @At(value = "INVOKE", remap = false, target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;isEmpty()Z"))
    private boolean prts$budgetedIncreaseIsEmpty(LongArrayFIFOQueue queue) {
        return prts$budgetIsEmpty(queue);
    }

    @Redirect(method = "propagateDecreases", require = 1,
            at = @At(value = "INVOKE", remap = false, target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;isEmpty()Z"))
    private boolean prts$budgetedDecreaseIsEmpty(LongArrayFIFOQueue queue) {
        return prts$budgetIsEmpty(queue);
    }

    @Unique
    private boolean prts$budgetIsEmpty(LongArrayFIFOQueue queue) {
        long tick = prts$tick();
        if (tick < 0L) {
            return queue.isEmpty();
        }
        boolean deferred = LightBudget.shouldDefer(tick, queue.isEmpty(),
                PRTSFeaturesConfig.lightBudgetEnabled, PRTSFeaturesConfig.lightBudgetPerTick);
        if (deferred && !queue.isEmpty()) {
            prts$warnIfStalled(tick); // budget exhausted, work deferred to next tick
        }
        return deferred;
    }

    @Unique
    private long prts$tick() {
        if (this.chunkSource == null) {
            return -1L;
        }
        Object level = this.chunkSource.getLevel();
        if (!(level instanceof Level l)) {
            return -1L;
        }
        MinecraftServer server = l.getServer();
        return server == null ? -1L : server.getTickCount();
    }

    @Unique
    private void prts$warnIfStalled(long tick) {
        long now = System.nanoTime();
        if (now - prts$warnNanos > 5000000000L) {
            prts$warnNanos = now;
            LOGGER.warn("[PRTS] Light propagation budget exhausted in tick {} ({} positions this tick); "
                    + "deferring the remainder to the next tick. Final lighting is unaffected. "
                    + "Tune lighting.budget-per-tick in prts-features.yml.", tick,
                    PRTSFeaturesConfig.lightBudgetPerTick);
        }
    }

    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void prts$statsHead(CallbackInfoReturnable<Integer> cir) {
        if (!PRTSFeaturesConfig.lightTelemetryEnabled) {
            return;
        }
        this.prts$startNanos = System.nanoTime();
        this.prts$pendingNodes = this.blockNodesToCheck.size() + this.decreaseQueue.size() + this.increaseQueue.size();
    }

    @Inject(method = "runLightUpdates", at = @At("RETURN"))
    private void prts$statsReturn(CallbackInfoReturnable<Integer> cir) {
        if (!PRTSFeaturesConfig.lightTelemetryEnabled) {
            return;
        }
        int updates = cir.getReturnValueI();
        int pending = this.prts$pendingNodes;
        long elapsed = System.nanoTime() - this.prts$startNanos;
        LightEngineStats.record(elapsed, updates, pending);
    }
}