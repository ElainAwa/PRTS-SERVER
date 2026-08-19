/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.concurrent.atomic.AtomicLong;

/** Limits expensive villager path searches that run on the server thread. */
public final class VillagerPathBudget {

    private static final ThreadLocal<Sample> CURRENT_SAMPLE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ROUTED_ENTITY_TICK = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicLong ALLOWED = new AtomicLong();
    private static final AtomicLong DEFERRED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong MULTI_TARGET = new AtomicLong();
    private static final AtomicLong SINGLE_TARGET = new AtomicLong();

    private static long currentTick = Long.MIN_VALUE;
    private static int usedThisTick;

    private VillagerPathBudget() {
    }

    public static boolean tryStart(MinecraftServer server, Mob mob, boolean multiTarget) {
        if (!enabledFor(server, mob)) {
            return true;
        }
        if (CURRENT_SAMPLE.get() != null) {
            return true;
        }
        int budget = PRTSFeaturesConfig.villagerPoiPathBudget;
        long tick = server.getTickCount();
        int spreadWindow = Math.max(64, budget * 16);
        if (Math.floorMod(mob.getId() + (int) tick, spreadWindow) >= budget) {
            DEFERRED.incrementAndGet();
            return false;
        }
        synchronized (VillagerPathBudget.class) {
            if (tick != currentTick) {
                currentTick = tick;
                usedThisTick = 0;
            }
            if (usedThisTick >= budget) {
                DEFERRED.incrementAndGet();
                return false;
            }
            usedThisTick++;
        }
        ALLOWED.incrementAndGet();
        CURRENT_SAMPLE.set(new Sample(Util.getNanos(), multiTarget));
        return true;
    }

    public static void finish(Mob mob) {
        Sample sample = CURRENT_SAMPLE.get();
        if (sample == null) {
            return;
        }
        CURRENT_SAMPLE.remove();
        if (!(mob instanceof Villager)) {
            return;
        }
        long elapsed = Math.max(0L, Util.getNanos() - sample.startNanos());
        TOTAL_NANOS.addAndGet(elapsed);
        COMPLETED.incrementAndGet();
        if (sample.multiTarget()) {
            MULTI_TARGET.incrementAndGet();
        } else {
            SINGLE_TARGET.incrementAndGet();
        }
    }

    public static void enterRoutedEntityTick() {
        ROUTED_ENTITY_TICK.set(Boolean.TRUE);
        CURRENT_SAMPLE.remove();
    }

    public static void exitRoutedEntityTick() {
        ROUTED_ENTITY_TICK.set(Boolean.FALSE);
        CURRENT_SAMPLE.remove();
    }

    public static void clearCurrent() {
        CURRENT_SAMPLE.remove();
    }

    public static String statusText() {
        int budget = PRTSFeaturesConfig.villagerPoiPathBudget;
        if (budget <= 0) {
            return "budget=off";
        }
        long completed = COMPLETED.get();
        double avgMs = completed == 0 ? 0.0 : (TOTAL_NANOS.get() / 1_000_000.0) / completed;
        return "budget=" + budget
                + " used=" + usedThisTick
                + " allowed=" + ALLOWED.get()
                + " deferred=" + DEFERRED.get()
                + " completed=" + completed
                + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgMs)
                + " multi=" + MULTI_TARGET.get()
                + " single=" + SINGLE_TARGET.get();
    }

    private static boolean enabledFor(MinecraftServer server, Mob mob) {
        return PRTSFeaturesConfig.villagerPoiPathBudget > 0
                && server != null
                && server.isSameThread()
                && ROUTED_ENTITY_TICK.get()
                && mob instanceof Villager;
    }

    private record Sample(long startNanos, boolean multiTarget) {
    }
}
